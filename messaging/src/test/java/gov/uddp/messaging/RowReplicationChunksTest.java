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

    @Test
    void aSelfReferencingTableIsSplitWithEveryReferenceInTheSameOrAnEarlierChunk() {
        var rows = Outbox.json().createObjectNode();
        var golden = rows.putArray("golden");
        // 2,500 rows; every 10th row was merged into the row after it (so the reference points forward in the input).
        for (int i = 0; i < 2500; i++) {
            var row = golden.addObject().put("id", "g" + i);
            if (i % 10 == 0) {
                row.put("merged_into", "g" + (i + 1));
            } else {
                row.putNull("merged_into");
            }
        }

        var chunks = RowReplication.chunks(rows, List.of("golden"), 1000,
                java.util.Map.of("golden", new RowReplication.SelfReference("id", List.of("merged_into"))));

        assertThat(chunks).hasSize(3);
        assertThat(chunks).allSatisfy(c -> assertThat(c.path("golden").size()).isLessThanOrEqualTo(1000));
        var seen = new java.util.HashSet<String>();
        for (var chunk : chunks) {
            var inChunk = new java.util.HashSet<String>();
            chunk.path("golden").forEach(r -> inChunk.add(r.path("id").asText()));
            for (var row : chunk.path("golden")) {
                String target = row.path("merged_into").asText(null);
                if (target != null) {
                    assertThat(seen.contains(target) || inChunk.contains(target)).as(row.toString()).isTrue();
                }
            }
            seen.addAll(inChunk);
        }
        assertThat(seen).hasSize(2500);
    }
}
