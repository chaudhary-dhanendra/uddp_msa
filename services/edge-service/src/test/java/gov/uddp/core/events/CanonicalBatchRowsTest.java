package gov.uddp.core.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gov.uddp.messaging.Outbox;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A large batch's rows travel in ordered chunks of at most 500 rows, parents before children. */
class CanonicalBatchRowsTest {

    @Test
    void rowsAreSplitInTableOrderIntoBoundedChunks() {
        ObjectNode rows = Outbox.json().createObjectNode();
        for (int i = 0; i < 700; i++) {
            rows.withArray("canonical_benefit_observation").addObject().put("n", i);
        }
        for (int i = 0; i < 400; i++) {
            rows.withArray("benefit").addObject().put("n", i);
        }
        List<ObjectNode> chunks = CanonicalBatchRows.chunks(rows);

        assertEquals(3, chunks.size());
        List<String> order = new ArrayList<>();
        int total = 0;
        for (ObjectNode chunk : chunks) {
            int size = 0;
            for (var table : (Iterable<String>) chunk::fieldNames) {
                order.add(table);
                size += chunk.get(table).size();
            }
            assertEquals(true, size <= 500);
            total += size;
        }
        assertEquals(1100, total);
        assertEquals("benefit", order.getFirst(), "parents (benefit) travel before their observations");
        assertEquals(1, CanonicalBatchRows.chunks(Outbox.json().createObjectNode()).size(), "an empty batch still"
                + " sends one (last) chunk");
    }
}
