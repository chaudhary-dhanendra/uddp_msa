package gov.uddp.core.setup;

/** A setup request the current state does not allow (409): environment settings win, a run is in progress, ... */
public class SetupConflictException extends RuntimeException {
    public SetupConflictException(String message) {
        super(message);
    }
}
