package gov.uddp.core.golden;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A POSSIBLE_MATCH pair is evidence for review only: Golden build keeps the two Residents apart. */
class PossibleMatchGoldenTest {
    @Test
    void possibleMatchPairNeverSharesAGoldenResident() throws Exception {
        var connections = TestDatabase.freshMigrated();
        var loader = SampleData.loader(SampleData.FIXTURE_PACKS);
        var processing = SampleData.processing(connections, loader);
        SampleData.landAll(connections, SampleData.FIXTURE_PACKS).values().forEach(ids -> ids.forEach(processing::processBatch));
        UUID alpha = ConflictFixtureTest.canonicalId(connections, "FXA", "Ram Singh");
        UUID beta = ConflictFixtureTest.canonicalId(connections, "FXB", "Ram Singh Yadav");
        UUID run = MatchingRunFixture.completed(connections, List.of(), List.of(Set.of(alpha, beta)));

        var summary = new GoldenResidentService(connections, new ObjectMapper(), loader).build(run);

        assertEquals(3, summary.canonicalRecordsConsidered());
        assertEquals(3, summary.created());
        assertEquals(3, summary.singletonGroups());
        try (var c = connections.open();
                var s = c.prepareStatement("select count(distinct golden_resident_id) from golden_resident_member"
                        + " where canonical_resident_id in (?, ?)")) {
            s.setObject(1, alpha);
            s.setObject(2, beta);
            try (var r = s.executeQuery()) {
                r.next();
                assertEquals(2, r.getLong(1));
            }
        }
    }
}
