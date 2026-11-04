package gov.uddp.core.ai;

/**
 * The one provider interface for model-assisted drafting. Callers send only non-personal content (a published
 * government order, column names, masked samples); whatever comes back is a suggestion that a person reviews and
 * another person approves. Nothing produced through this interface is ever approved automatically.
 */
public interface AiDraftService {

    /** False unless a provider is configured and {@code uddp.ai.enabled=true}. */
    boolean enabled();

    /** The configured model name, recorded with every draft. */
    String model();

    /**
     * Sends one instruction and its non-personal content and returns the model's raw text answer.
     *
     * @throws AiDisabledException when no provider is configured
     */
    String complete(String instruction, String content);
}
