package gov.uddp.core.statepack;

public class StatePackLoadException extends RuntimeException {
    public StatePackLoadException(String message) { super(message); }
    public StatePackLoadException(String message, Throwable cause) { super(message, cause); }
}
