package gov.uddp.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Large replicas travel in bounded, ordered chunks; a table whose rows reference each other is never split. */
class RowReplicationChunksTest {

    @Test
    void splitsInTableOrderAndKeepsWholeTablesTogether() {
        ObjectNode rows = Outbox.json().createObjectNode();
        for (int i = 0; i < 30; i++) {
            rows.withArray("parent").addObject().put("n", i);
        }
        for (int i = 0; i < 25; i++) {
            rows.withArray("child").addObject().put("n", i);
        }
        var chunks = RowReplication.chunks(rows, List.of("parent", "child"), 20, Set.of("parent"));

        assertThat(chunks.getFirst().get("parent")).hasSize(30);
        assertThat(chunks.getFirst().has("child")).isFalse();
        assertThat(chunks.stream().mapToInt(c -> c.path("child").size()).sum()).isEqualTo(25);
        assertThat(chunks.subList(1, chunks.size())).allSatisfy(c -> assertThat(c.path("child").size()).isLessThanOrEqualTo(20));
        assertThat(RowReplication.chunks(Outbox.json().createObjectNode(), List.of("parent"), 20, Set.of()))
                .hasSize(1);
    }
}
