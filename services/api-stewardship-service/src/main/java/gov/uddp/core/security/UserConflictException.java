package gov.uddp.core.security;

/** A user administration request that conflicts with the current users (409). */
public class UserConflictException extends RuntimeException {
    public UserConflictException(String message) {
        super(message);
    }
}
