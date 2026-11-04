package gov.uddp.core.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresCanonicalResidentRepository;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.canonical.PostgresRawRecordRepository;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.mapping.RawCsvRecordParser;
import gov.uddp.core.mapping.ResidentMappingEngine;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.validation.ResidentValidator;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Wires the real engines against a test database for a State Pack directory. */
public final class SampleData {
    /** Repository root, relative to the module directory Maven runs tests in (services/api-stewardship-service). */
    public static final Path REPOSITORY_ROOT = Path.of("../..");
    public static final Path STATE_PACKS = REPOSITORY_ROOT.resolve("services/statepack-service/seed/state-packs");
    /** Fixture packs fx-alpha and fx-beta: conflict and bad-data cases, never part of the sample demo. */
    public static final Path FIXTURE_PACKS = REPOSITORY_ROOT.resolve("services/api-stewardship-service/src/test/fixtures/state-packs");
    /** Fixture pack fx-household (M14): household members with relationship codes, never part of the demo. */
    public static final Path FAMILY_FIXTURE_PACKS = REPOSITORY_ROOT.resolve("services/api-stewardship-service/src/test/fixtures/family-packs");
    /** Fixture pack fx-welfare (M15): one benefit per row, with Benefit errors; never part of the demo. */
    public static final Path BENEFIT_FIXTURE_PACKS = REPOSITORY_ROOT.resolve("services/api-stewardship-service/src/test/fixtures/benefit-packs");

    private SampleData() {
    }

    public static StatePackLoader loader(Path statePackDirectory) {
        var properties = new StatePackProperties();
        properties.setStatePackDirectory(statePackDirectory.toString());
        // Tests resolve the YAML fixture packs explicitly; operational resolution is database-only (V14).
        properties.setFixtureStatePacksEnabled(true);
        var loader = new StatePackLoader(properties);
        loader.setOperations(TestOperationRegistry.builtIns());
        loader.reload();
        return loader;
    }

    /** The platform scheme catalogue (M15) the sample packs crosswalk onto. */
    public static final Path SCHEME_CATALOGUE = REPOSITORY_ROOT.resolve("platform/schemes/catalogue.yaml");

    /** Canonical processing with Family (M14) and Benefit (M15) observation, as the application wires it. */
    public static CanonicalResidentProcessingService processing(PostgresConnectionFactory connections, StatePackLoader loader) {
        return processing(connections, loader, null);
    }

    /** As {@link #processing(PostgresConnectionFactory, StatePackLoader)}, writing platform events (MSA Phase 2). */
    public static CanonicalResidentProcessingService processing(PostgresConnectionFactory connections, StatePackLoader loader,
                                                                gov.uddp.core.events.PlatformEvents events) {
        var service = processingWithoutBenefits(connections, loader, events);
        var json = new ObjectMapper().findAndRegisterModules();
        service.setBenefitProcessor(new gov.uddp.core.canonical.CanonicalBenefitProcessor(connections,
                new gov.uddp.core.mapping.BenefitMappingEngine(new RawCsvRecordParser(json),
                        new gov.uddp.core.scheme.SchemeCatalogue(SCHEME_CATALOGUE)), json));
        service.setAssetProcessor(new gov.uddp.core.canonical.CanonicalAssetProcessor(connections,
                new gov.uddp.core.mapping.AssetMappingEngine(new RawCsvRecordParser(json)), json));
        service.setApplicationProcessor(new gov.uddp.core.canonical.CanonicalApplicationProcessor(connections,
                new gov.uddp.core.mapping.ApplicationMappingEngine(new RawCsvRecordParser(json),
                        new gov.uddp.core.scheme.SchemeCatalogue(SCHEME_CATALOGUE)), json));
        return service;
    }

    /** Canonical processing with Family observation only, as before M15 (for schemas older than V13). */
    public static CanonicalResidentProcessingService processingWithoutBenefits(PostgresConnectionFactory connections,
                                                                              StatePackLoader loader) {
        return processingWithoutBenefits(connections, loader, null);
    }

    private static CanonicalResidentProcessingService processingWithoutBenefits(PostgresConnectionFactory connections,
            StatePackLoader loader, gov.uddp.core.events.PlatformEvents events) {
        var service = processingWithoutFamilies(connections, loader, events);
        var json = new ObjectMapper().findAndRegisterModules();
        var families = new gov.uddp.core.canonical.CanonicalFamilyProcessor(connections,
                new gov.uddp.core.mapping.FamilyMappingEngine(new RawCsvRecordParser(json)), json);
        families.setEvents(events);
        service.setFamilyProcessor(families);
        return service;
    }

    /** Resident-only canonical processing, as before M14 (for schemas older than V11). */
    public static CanonicalResidentProcessingService processingWithoutFamilies(PostgresConnectionFactory connections,
                                                                              StatePackLoader loader) {
        return processingWithoutFamilies(connections, loader, null);
    }

    private static CanonicalResidentProcessingService processingWithoutFamilies(PostgresConnectionFactory connections,
            StatePackLoader loader, gov.uddp.core.events.PlatformEvents events) {
        var json = new ObjectMapper().findAndRegisterModules();
        var properties = new StatePackProperties();
        properties.setStatePackDirectory(loader == null ? STATE_PACKS.toString() : STATE_PACKS.toString());
        properties.setPlatformDirectory(REPOSITORY_ROOT.resolve("platform").toString());
        var canonicals = new PostgresCanonicalResidentRepository(connections);
        canonicals.setEvents(events);
        var service = new CanonicalResidentProcessingService(
                new PostgresRawRecordRepository(connections, json),
                canonicals,
                loader,
                new ResidentMappingEngine(new RawCsvRecordParser(json)),
                new ResidentValidator(),
                json,
                new gov.uddp.core.validation.DqRepository(connections),
                new gov.uddp.core.validation.PlatformReferenceData(properties));
        service.setEvents(events);
        return service;
    }

    /** A Golden Resident service with the Golden Family build (M14) and stewardship (M13), as the application wires it. */
    public static gov.uddp.core.golden.GoldenResidentService golden(PostgresConnectionFactory connections,
                                                                   StatePackLoader loader) {
        var json = new ObjectMapper();
        var service = new gov.uddp.core.golden.GoldenResidentService(connections, json, loader);
        service.setFamilyBuilder(new gov.uddp.core.golden.GoldenFamilyBuilder(json));
        service.setStewardship(new gov.uddp.core.stewardship.StewardshipStore());
        return service;
    }

    /** Stewardship decisions (M13) applied through the given Golden service. */
    public static gov.uddp.core.stewardship.StewardshipService stewardship(PostgresConnectionFactory connections,
                                                                         gov.uddp.core.golden.GoldenResidentService golden) {
        return new gov.uddp.core.stewardship.StewardshipService(connections, golden, new ObjectMapper());
    }

    /** Lands every source of every pack (Hop-shaped); returns packId to its batch IDs, in pack order. */
    public static Map<String, List<UUID>> landAll(PostgresConnectionFactory connections, Path statePackDirectory) {
        var raw = new HopShapedRawLoader(connections, REPOSITORY_ROOT);
        Map<String, List<UUID>> batches = new LinkedHashMap<>();
        var packs = loader(statePackDirectory).loadAll(statePackDirectory).values().stream()
                .sorted(Comparator.comparing(pack -> pack.manifest().packId()))
                .toList();
        for (LoadedStatePack pack : packs) {
            batches.put(pack.manifest().packId(), raw.landAll(pack));
        }
        return batches;
    }

    /**
     * Lands and canonically processes the sample packs, then writes a fixture matching run whose only
     * AUTO_MATCH group is the three "Ram Singh" records: the decisions Splink makes for this data (proven
     * by the Python integration test).
     */
    public static UUID processedSampleWithMatchingRun(PostgresConnectionFactory connections) {
        return processedSampleWithMatchingRun(connections, processing(connections, loader(STATE_PACKS)));
    }

    /** As {@link #processedSampleWithMatchingRun(PostgresConnectionFactory)} with the given canonical processing. */
    public static UUID processedSampleWithMatchingRun(PostgresConnectionFactory connections,
                                                      CanonicalResidentProcessingService service) {
        landAll(connections, STATE_PACKS).values().forEach(ids -> ids.forEach(service::processBatch));
        Set<UUID> ram = new HashSet<>();
        try (var c = connections.open();
                var s = c.prepareStatement("select canonical_resident_id from canonical_resident"
                        + " where canonical_payload->>'name' = 'Ram Singh'");
                var r = s.executeQuery()) {
            while (r.next()) {
                ram.add(r.getObject(1, UUID.class));
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return MatchingRunFixture.completed(connections, List.of(ram));
    }
}
