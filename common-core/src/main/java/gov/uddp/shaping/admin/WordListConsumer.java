package gov.uddp.shaping.admin;

/** A parser or operation that reads {@code reference-data/shaping/*.yaml}; asked to read them again after an approved change. */
public interface WordListConsumer {
    void reloadWordLists();
}
