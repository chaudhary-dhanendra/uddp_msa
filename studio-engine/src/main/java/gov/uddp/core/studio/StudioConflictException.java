package gov.uddp.core.studio;

/** A Studio request the draft's state does not allow (409), for example publishing with blocking errors. */
public class StudioConflictException extends RuntimeException {
    public StudioConflictException(String message) {
        super(message);
    }
}
