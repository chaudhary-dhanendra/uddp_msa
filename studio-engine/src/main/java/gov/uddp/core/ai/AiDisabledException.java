package gov.uddp.core.ai;

/** Model-assisted drafting was requested while no provider is configured. */
public class AiDisabledException extends IllegalStateException {
    public AiDisabledException() {
        super("AI drafting is not enabled: set uddp.ai.enabled=true with uddp.ai.provider-url and uddp.ai.model");
    }
}
