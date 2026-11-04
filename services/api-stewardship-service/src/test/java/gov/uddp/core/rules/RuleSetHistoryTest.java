package gov.uddp.core.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import gov.uddp.core.testsupport.TestDatabase;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleSetHistoryTest {
    @Test
    void returnsLogicalRuleSetVersionsNewestFirst() {
        var store = new RuleSetStore(TestDatabase.freshMigrated()); var service = new RuleSetService(store);
        save(store, 1); save(store, 2);
        var history = service.history(RuleSet.Type.DATA_QUALITY, RuleSet.OwnerType.SOURCE_PACK, "pack-a");
        assertEquals(java.util.List.of(2, 1), history.stream().map(RuleSet::version).toList());
        assertEquals("<dmn version=\"2\"/>", history.getFirst().dmnXml());
    }
    private static void save(RuleSetStore store, int version) {
        store.save(new RuleSet(UUID.randomUUID(), RuleSet.Type.DATA_QUALITY, RuleSet.OwnerType.SOURCE_PACK, "pack-a", version,
                RuleSet.Status.DRAFT, LocalDate.of(2026, 1, version), null, "<dmn version=\"" + version + "\"/>"));
    }
}
