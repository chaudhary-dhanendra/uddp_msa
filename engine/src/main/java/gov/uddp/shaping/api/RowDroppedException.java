package gov.uddp.shaping.api;

/** Thrown when layout operations consumed a raw row (header, banner, total) so it yields no tidy record. */
public class RowDroppedException extends RuntimeException {

    public RowDroppedException(String message) {
        super(message);
    }
}
