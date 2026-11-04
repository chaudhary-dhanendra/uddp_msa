package gov.uddp.core.read;

/** A read API resource that does not exist; answered with 404 and an {@code ApiError} body. */
public class ReadNotFoundException extends RuntimeException {
    public ReadNotFoundException(String message) {
        super(message);
    }
}
