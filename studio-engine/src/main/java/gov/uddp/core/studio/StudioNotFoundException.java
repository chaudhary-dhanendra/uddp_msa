package gov.uddp.core.studio;

import gov.uddp.core.read.ReadNotFoundException;

/** An unknown draft (404). */
public class StudioNotFoundException extends ReadNotFoundException {
    public StudioNotFoundException(String message) {
        super(message);
    }
}
