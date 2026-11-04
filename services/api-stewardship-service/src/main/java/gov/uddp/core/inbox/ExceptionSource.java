package gov.uddp.core.inbox;

import java.time.Instant;
import java.util.List;

/**
 * Something that needs a person, read from where it already lives (Track C4): the inbox stores nothing. Each part of
 * the platform contributes its own items by implementing this interface; Track A (rules, schemes) and Track B
 * (matching) add theirs the same way. An item carries identifiers and counts, never personal data, so it can be shown
 * to anyone allowed to see the source it belongs to.
 */
public interface ExceptionSource {
    /** Item type, for example GATE_FAILURE; one source may report several types. */
    List<String> types();

    /** The open items, newest first; the inbox applies scope and filters. */
    List<Item> items();

    /**
     * @param id stable per item, unique across sources
     * @param priority HIGH, MEDIUM or LOW
     * @param link the application route of the screen that resolves it
     */
    record Item(String id, String type, String title, String detail, String priority, String stateCode,
                String sourceSystem, Instant createdAt, String link) {
    }
}
