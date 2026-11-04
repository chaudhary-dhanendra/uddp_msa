package gov.uddp.core.ai;

/** The default: no provider, nothing leaves the platform. */
public class DisabledAiDraftService implements AiDraftService {
    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public String model() {
        return null;
    }

    @Override
    public String complete(String instruction, String content) {
        throw new AiDisabledException();
    }
}
