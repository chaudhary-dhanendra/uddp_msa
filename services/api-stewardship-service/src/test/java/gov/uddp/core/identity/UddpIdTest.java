package gov.uddp.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.read.BatchReadService;
import gov.uddp.core.read.Db;
import gov.uddp.core.read.FamilyReadService;
import gov.uddp.core.read.GoldenReadService;
import gov.uddp.core.read.GoldenVisibility;
import gov.uddp.core.read.ReadNotFoundException;
import gov.uddp.core.read.statepack.ConfiguredStatePackQueryService;
import gov.uddp.core.security.DepartmentScope;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.StateScope;
import gov.uddp.core.security.UddpAuthentication;
import gov.uddp.core.security.UddpPrincipal;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Track E4: UDDP IDs are unique, carry a check character in the configured format, are minted once per Golden
 * Resident and Family, survive a merge as a redirect to the surviving identity's UDDP ID, and are read through the
 * caller's scope.
 */
class UddpIdTest {
    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void idsAreUniqueAndTheCheckCharacterCatchesEverySingleCharacterError() {
        var ids = new UddpIdService(null, new UddpIdService.Format(10, "23456789ABCDEFGHJKLMNPQRSTUVWXYZ", "R-", "F-", true));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            String id = ids.generate(UddpIdService.Kind.RESIDENT);
            assertTrue(seen.add(id), "unique");
            assertTrue(id.startsWith("R-") && id.length() == 13, id);
            assertTrue(ids.wellFormed(id), id);
        }
        String id = seen.iterator().next();
        String alphabet = ids.format().alphabet();
        for (int position = 2; position < id.length(); position++) {
            for (char replacement : alphabet.toCharArray()) {
                if (replacement != id.charAt(position)) {
                    String typo = id.substring(0, position) + replacement + id.substring(position + 1);
                    assertFalse(ids.wellFormed(typo), "a one-character typo is caught: " + typo);
                }
            }
        }
        assertTrue(ids.wellFormed("F-" + id.substring(2)), "the family prefix is also accepted");
        assertFalse(ids.wellFormed(id.toLowerCase()));
        assertThrows(IllegalArgumentException.class, () -> new UddpIdService.Format(10, "ABC", "", "", true));
    }

    @Test
    void mergedIdsRedirectToTheSurvivorAndReadsRespectScope() {
        var connections = TestDatabase.freshMigrated();
        var sql = new Sql(connections);
        var loader = SampleData.loader(SampleData.STATE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.STATE_PACKS).values().forEach(batches -> batches.forEach(processing::processBatch));
        var ids = new UddpIdService(connections, new UddpIdService.Format(10, "23456789ABCDEFGHJKLMNPQRSTUVWXYZ", "", "", true));
        var golden = SampleData.golden(connections, loader);
        golden.setUddpIds(ids);

        golden.build(MatchingRunFixture.completed(connections, List.of()));
        assertEquals(7, sql.count("select count(*) from uddp_id where kind = 'RESIDENT'"));
        assertEquals(6, sql.count("select count(*) from uddp_id where kind = 'FAMILY'"));
        List<UUID> ram = sql.canonicalsNamed("Ram Singh");
        Set<String> ramIds = new HashSet<>();
        ram.forEach(canonical -> ramIds.add(ids.idOf(UddpIdService.Kind.RESIDENT, sql.goldenOf(canonical)).orElseThrow()));
        assertEquals(3, ramIds.size());

        golden.build(MatchingRunFixture.completed(connections, List.of(Set.copyOf(ram))));
        UUID survivor = sql.goldenOf(ram.getFirst());
        String survivorId = ids.idOf(UddpIdService.Kind.RESIDENT, survivor).orElseThrow();
        assertEquals(7, sql.count("select count(*) from uddp_id where kind = 'RESIDENT'"), "a merge mints nothing");
        for (String id : ramIds) {
            var resolution = ids.resolve(id).orElseThrow();
            assertEquals(survivor, resolution.resolvedGoldenId());
            assertEquals(survivorId, resolution.resolvedUddpId());
            assertEquals(!id.equals(survivorId), resolution.redirected());
        }
        assertThrows(Exception.class, () -> sql.execute("delete from uddp_id"), "UDDP IDs are never deleted");

        var db = new Db(connections, new ObjectMapper().findAndRegisterModules());
        var batches = new BatchReadService(db, new ConfiguredStatePackQueryService(loader, db));
        var reads = new GoldenReadService(db, batches, new GoldenVisibility(db));
        var families = new FamilyReadService(db, batches, new GoldenVisibility(db));
        String retired = ramIds.stream().filter(id -> !id.equals(survivorId)).findFirst().orElseThrow();
        var view = reads.byUddpId(retired);
        assertEquals("RETIRED", view.status());
        assertEquals(survivor, view.redirectTo());
        assertEquals(survivorId, view.redirectToUddpId());
        assertEquals(survivorId, reads.byUddpId(survivorId).uddpId());
        String familyId = sql.text("select uddp_id from uddp_id u join golden_family f on f.golden_family_id = u.golden_id"
                + " where f.state_code = 'ST1' order by f.family_record_key limit 1");
        assertEquals(familyId, families.byUddpId(familyId).uddpId());
        assertThrows(ReadNotFoundException.class, () -> reads.byUddpId("NOT-AN-ID"));

        String sunita = ids.idOf(UddpIdService.Kind.RESIDENT, sql.goldenOf(sql.canonicalsNamed("Sunita Devi").getFirst()))
                .orElseThrow();
        signIn("ST2");
        assertThrows(ReadNotFoundException.class, () -> reads.byUddpId(sunita), "an ST3-only resident is out of scope");
        assertThrows(ReadNotFoundException.class, () -> families.byUddpId(familyId), "an ST1 household is out of scope");
        assertNotNull(reads.byUddpId(survivorId), "Ram Singh has an ST2 member");
    }

    private static void signIn(String state) {
        var principal = new UddpPrincipal(null, "scoped", "scoped", Set.of("VIEWER"), EnumSet.of(Permission.RESIDENT_VIEW,
                Permission.FAMILY_VIEW), DepartmentScope.of(List.of(DepartmentScope.ALL)), StateScope.of(List.of(state)),
                false, "TEST");
        SecurityContextHolder.getContext().setAuthentication(new UddpAuthentication(principal));
    }
}
