package gov.uddp.core.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.config.StatePackProperties;
import gov.uddp.core.geography.GeographyService.CodeStatus;
import gov.uddp.core.read.Db;
import gov.uddp.core.reference.LgdReferenceDataProvider;
import gov.uddp.core.reference.ReferenceDataService;
import gov.uddp.core.reference.StaticReferenceDataProvider;
import gov.uddp.core.testsupport.TestDatabase;
import gov.uddp.core.validation.PlatformReferenceData;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Track F2: server-side search within a parent area, ancestry, code validation (active, inactive, unknown, parent
 * consistency), as-of lookups, and {@code lgd-district} as a compatibility view over DISTRICT entities that keeps the
 * static file working until the master is loaded.
 */
class GeographyServiceTest {
    private final PostgresConnectionFactory db = TestDatabase.freshMigrated();
    private final LgdImportService imports = new LgdImportService(db, new ObjectMapper());
    private final GeographyService geography = new GeographyService(new Db(db, new ObjectMapper()));

    @Test
    void searchAndAncestry() {
        imports.importSnapshot(FileLgdSourceClient.read(LgdMasterImportTest.FIXTURE), "tester");

        var villages = geography.search("village", null, "DISTRICT", "901", null, 0, 10);
        assertEquals(List.of("Lakeview", "Riverbend"), villages.items().stream().map(GeographyService.GeoEntity::name).toList());
        assertEquals(2, villages.totalItems());
        var byName = geography.search(null, "hill", null, null, null, 0, 10);
        assertEquals(Set.of("90201", "500003"), Set.copyOf(byName.items().stream().map(GeographyService.GeoEntity::code).toList()));
        var paged = geography.search("VILLAGE", null, null, null, null, 1, 2);
        assertEquals(3, paged.totalItems());
        assertEquals(1, paged.items().size());
        assertEquals("500001", geography.search(null, "500001", null, null, null, 0, 5).items().getFirst().code());

        assertEquals(List.of("90", "901", "9011"), geography.ancestors("VILLAGE", "500002").stream()
                .map(GeographyService.GeoEntity::code).toList());
        assertEquals(Set.of("9011", "90101", "901011", "500001", "500002", "7001", "70011"),
                Set.copyOf(geography.descendants("DISTRICT", "901", null, 0, 50).items().stream()
                        .map(GeographyService.GeoEntity::code).toList()));
        assertEquals("North District", geography.find("SUB_DISTRICT", "9011").orElseThrow().parentName());
    }

    @Test
    void activeInactiveUnknownAndAsOf() {
        imports.importSnapshot(FileLgdSourceClient.read(LgdMasterImportTest.FIXTURE), "tester");
        imports.importSnapshot(new LgdSnapshotData("delta", LocalDate.parse("2024-06-01"), List.of(
                new LgdRow(1, "DISTRICT", "902", "South District", "INACTIVE", "STATE", "90", null),
                new LgdRow(2, "VILLAGE", "500002", "Lake View", "ACTIVE", "SUB_DISTRICT", "9021", null))), "tester");

        var valid = geography.validate("district", "901", "STATE", "90");
        assertEquals(CodeStatus.ACTIVE_VALID, valid.status());
        assertTrue(valid.parentConsistent());
        assertEquals(CodeStatus.KNOWN_INACTIVE, geography.validate("DISTRICT", "902", null, null).status());
        var unknown = geography.validate("DISTRICT", "999", null, null);
        assertEquals(CodeStatus.UNKNOWN, unknown.status());
        assertNull(unknown.entity());
        assertFalse(geography.validate("VILLAGE", "500002", "DISTRICT", "901").parentConsistent(),
                "the village now lies in the south");
        assertTrue(geography.validate("VILLAGE", "500002", "DISTRICT", "902").parentConsistent());

        var before = geography.asOf("VILLAGE", "500002", LocalDate.parse("2024-03-01")).orElseThrow();
        assertEquals("Lakeview", before.name());
        assertEquals("9011", before.parentCode());
        assertEquals("Lake View", geography.asOf("VILLAGE", "500002", LocalDate.parse("2024-07-01")).orElseThrow().name());
        assertTrue(geography.asOf("VILLAGE", "500002", LocalDate.parse("2023-01-01")).isEmpty());
        assertEquals(1, geography.search("DISTRICT", null, null, null, "ACTIVE", 0, 10).totalItems());
    }

    @Test
    void lgdDistrictIsACompatibilityViewOverDistricts() {
        var properties = new StatePackProperties();
        properties.setPlatformDirectory("../platform");
        var files = new PlatformReferenceData(properties);
        var references = new ReferenceDataService(new LgdReferenceDataProvider(geography),
                new StaticReferenceDataProvider(files));

        Map<String, Set<String>> before = references.resolve(files.values());
        assertEquals(files.values().get("lgd-district"), before.get("lgd-district"),
                "before any LGD import the platform file answers lgd-district");
        assertEquals("static", references.sets().stream().filter(s -> s.referenceSet().equals("lgd-district"))
                .findFirst().orElseThrow().provider());

        imports.importSnapshot(FileLgdSourceClient.read(LgdMasterImportTest.FIXTURE), "tester");
        imports.importSnapshot(new LgdSnapshotData("delta", LocalDate.parse("2024-06-01"), List.of(
                new LgdRow(1, "DISTRICT", "902", "South District", "INACTIVE", "STATE", "90", null))), "tester");
        Map<String, Set<String>> after = references.resolve(files.values());
        assertEquals(Set.of("901"), after.get("lgd-district"), "ACTIVE districts only");
        assertEquals(Set.of("500001", "500002", "500003"), after.get("lgd-village"));
        assertEquals("lgd", references.sets().stream().filter(s -> s.referenceSet().equals("lgd-district"))
                .findFirst().orElseThrow().provider());
        assertEquals("North District", references.lookup("lgd-district", "north", 10).getFirst().displayName());
        assertEquals("DISTRICT", LgdReferenceDataProvider.entityType("lgd-district"));
        assertEquals("lgd-sub-district", LgdReferenceDataProvider.referenceSet("SUB_DISTRICT"));
    }
}
