package gov.uddp.canonical;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.contracts.StatePackClient;
import gov.uddp.core.canonical.CanonicalApplicationProcessor;
import gov.uddp.core.canonical.CanonicalAssetProcessor;
import gov.uddp.core.canonical.CanonicalBenefitProcessor;
import gov.uddp.core.canonical.CanonicalFamilyProcessor;
import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresCanonicalResidentRepository;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.canonical.PostgresRawRecordRepository;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.events.PlatformEvents;
import gov.uddp.core.held.HeldRecordOperations;
import gov.uddp.core.held.HeldRecordRepository;
import gov.uddp.core.mapping.ApplicationMappingEngine;
import gov.uddp.core.mapping.AssetMappingEngine;
import gov.uddp.core.mapping.BenefitMappingEngine;
import gov.uddp.core.mapping.FamilyMappingEngine;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.validation.DqRepository;
import gov.uddp.core.validation.PlatformReferenceData;
import gov.uddp.core.validation.ResidentValidator;
import gov.uddp.shaping.OperationRegistry;
import gov.uddp.shaping.api.FieldParser;
import gov.uddp.shaping.api.LayoutOperation;
import java.nio.file.Path;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the monolith's unchanged canonical engine and State Pack loader against this service's own schema, as the
 * monolith wires them (Resident, Family, Benefit, Asset and Application observation, DQ, platform reference data,
 * built-in shaping operations and parsers). Packs come only from statepack-service, verified by digest, through the
 * loader's cache directory; the engine's events are published by canonical-service.
 */
@Configuration
@ComponentScan(basePackages = {"gov.uddp.shaping.ops", "gov.uddp.shaping.parsers"})
public class CanonicalConfiguration {

    @Bean
    PostgresConnectionFactory canonicalConnections(JdbcConnectionDetails database) {
        String url = database.getJdbcUrl();
        String schema = (url.contains("?") ? "&" : "?") + "currentSchema=canonical&reWriteBatchedInserts=true";
        return PostgresConnectionFactory.forSettings(url + schema, database.getUsername(), database.getPassword());
    }

    @Bean
    PlatformEvents canonicalEvents(PostgresConnectionFactory canonicalConnections) {
        var events = new PlatformEvents(canonicalConnections, true, "canonical-service");
        // Raw and matching runs are other services' events; this service publishes canonical ones only.
        events.setRemoteIngestion(true);
        events.setRemoteIdentity(true);
        return events;
    }

    /** The LGD geography master in this schema, for the geography crosswalks and the free-address parser. */
    @Bean
    gov.uddp.core.read.Db canonicalDb(PostgresConnectionFactory canonicalConnections) {
        return new gov.uddp.core.read.Db(canonicalConnections, new ObjectMapper().findAndRegisterModules());
    }

    @Bean
    gov.uddp.core.geography.GeographyService geography(gov.uddp.core.read.Db canonicalDb) {
        return new gov.uddp.core.geography.GeographyService(canonicalDb);
    }

    /** Identifier types for the identifier parsers (seeded from the platform directory on first use). */
    @Bean
    gov.uddp.core.identity.IdentifierTypeRegistry identifierTypes(PostgresConnectionFactory canonicalConnections,
                                                                 StatePackProperties statePackProperties) {
        return new gov.uddp.core.identity.IdentifierTypeRegistry(canonicalConnections, statePackProperties,
                java.util.List.of(new gov.uddp.core.identity.LuhnChecksum(),
                        new gov.uddp.core.identity.VerhoeffChecksum()));
    }

    @Bean
    OperationRegistry shapingOperations(ObjectProvider<LayoutOperation> operations, ObjectProvider<FieldParser> parsers,
                                        @Value("${uddp.canonical.shaping-plugins-dir}") String pluginsDir,
                                        @Value("${uddp.canonical.shaping-approved-plugins}") String approved) {
        return new OperationRegistry(operations.orderedStream().toList(), parsers.orderedStream().toList(), pluginsDir,
                approved);
    }

    @Bean
    StatePackProperties statePackProperties(@Value("${uddp.canonical.pack-cache}") String cache,
                                            @Value("${uddp.canonical.platform-directory}") String platform) {
        var properties = new StatePackProperties();
        properties.setStatePackDirectory(Path.of(cache).toAbsolutePath().toString());
        properties.setPlatformDirectory(Path.of(platform).toAbsolutePath().normalize().toString());
        // The engine's static identifier helpers read the platform directory from this system property.
        System.setProperty("uddp.platform-directory", properties.getPlatformDirectory());
        // The cache holds only packs fetched from statepack-service and verified by digest.
        properties.setFixtureStatePacksEnabled(true);
        return properties;
    }

    @Bean
    StatePackLoader statePacks(StatePackProperties statePackProperties, OperationRegistry shapingOperations) {
        var loader = new StatePackLoader(statePackProperties);
        loader.setOperations(shapingOperations);
        return loader;
    }

    @Bean
    StatePackClient statePackClient(@Value("${uddp.canonical.statepack-url}") String url) {
        return new StatePackClient(url);
    }

    /**
     * The shaping layer every mapping engine reads rows through: the pack's layout and parse steps (header detection,
     * totals rows, name and relation split, approved plug-ins) and, for workbooks and layout steps, the whole sheet of
     * the delivery from this service's Raw projection.
     */
    @Bean
    gov.uddp.shaping.ShapingEngine shapingEngine(PostgresConnectionFactory canonicalConnections,
                                                 OperationRegistry shapingOperations) {
        var json = new ObjectMapper().findAndRegisterModules();
        var engine = new gov.uddp.shaping.ShapingEngine(new RawCsvRecordParser(json), shapingOperations);
        var raws = new PostgresRawRecordRepository(canonicalConnections, json);
        engine.setBatchRows(new gov.uddp.shaping.api.BatchRows() {
            @Override
            public java.util.List<gov.uddp.core.mapping.RawRecord> records(java.util.UUID batchId) {
                return raws.findByBatchId(batchId);
            }

            @Override
            public java.util.List<gov.uddp.core.mapping.RawRecord> sheet(java.util.UUID batchId, String sourceSystem,
                                                                         String file, String sheet) {
                return raws.findSheet(batchId, sourceSystem, file, sheet);
            }
        });
        return engine;
    }

    @Bean
    CanonicalResidentProcessingService canonicalProcessing(PostgresConnectionFactory canonicalConnections,
                                                           gov.uddp.shaping.ShapingEngine shaping,
                                                           StatePackLoader statePacks,
                                                           StatePackProperties statePackProperties,
                                                           PlatformEvents canonicalEvents,
                                                           @Value("${uddp.canonical.platform-directory}") String platform,
                                                           @Value("${uddp.processing.hold-low-confidence:false}")
                                                           boolean holdLowConfidence,
                                                           @Value("${uddp.processing.hold-invalid-identifiers:false}")
                                                           boolean holdInvalidIdentifiers) {
        var json = new ObjectMapper().findAndRegisterModules();
        var canonicals = new PostgresCanonicalResidentRepository(canonicalConnections);
        canonicals.setEvents(canonicalEvents);
        var service = new CanonicalResidentProcessingService(new PostgresRawRecordRepository(canonicalConnections, json),
                canonicals, statePacks, new ResidentMappingEngine(shaping), new ResidentValidator(),
                json, new DqRepository(canonicalConnections), new PlatformReferenceData(statePackProperties));
        var families = new CanonicalFamilyProcessor(canonicalConnections,
                new FamilyMappingEngine(shaping), json);
        families.setEvents(canonicalEvents);
        service.setFamilyProcessor(families);
        var catalogue = Path.of(platform, "schemes", "catalogue.yaml");
        service.setBenefitProcessor(new CanonicalBenefitProcessor(canonicalConnections,
                new BenefitMappingEngine(shaping, new SchemeCatalogue(catalogue)), json));
        service.setAssetProcessor(new CanonicalAssetProcessor(canonicalConnections,
                new AssetMappingEngine(shaping), json));
        service.setApplicationProcessor(new CanonicalApplicationProcessor(canonicalConnections,
                new ApplicationMappingEngine(shaping, new SchemeCatalogue(catalogue)), json));
        service.setEvents(canonicalEvents);
        // Rows a steward can fix are held here (held_record), not rejected; the edge decides them through this service.
        service.setHeldRecords(new HeldRecordRepository(canonicalConnections, json), holdLowConfidence,
                holdInvalidIdentifiers);
        return service;
    }

    /** One transaction per chunk of a batch and resume from the last committed chunk after a crash. */
    @Bean
    gov.uddp.core.canonical.ProcessingProgress processingProgress(PostgresConnectionFactory canonicalConnections) {
        return new gov.uddp.core.canonical.ProcessingProgress(canonicalConnections);
    }

    @Bean
    HeldRecordOperations heldRecords(PostgresConnectionFactory canonicalConnections,
                                     CanonicalResidentProcessingService canonicalProcessing,
                                     StatePackLoader statePacks) {
        var operations = new HeldRecordOperations(new HeldRecordRepository(canonicalConnections,
                new ObjectMapper().findAndRegisterModules()), canonicalProcessing, statePacks, canonicalConnections);
        return operations;
    }
}
