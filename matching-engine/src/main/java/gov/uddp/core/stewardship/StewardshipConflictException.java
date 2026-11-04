package gov.uddp.core.stewardship;

/** A stewardship request that conflicts with the current state (decided task, retired identity, no run); HTTP 409. */
public class StewardshipConflictException extends RuntimeException {
    public StewardshipConflictException(String message) {
        super(message);
    }
}
