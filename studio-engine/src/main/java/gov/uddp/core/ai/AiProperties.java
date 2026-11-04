package gov.uddp.core.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Provider settings for {@link AiDraftService}; off by default. The API key is never configured here: it is read
 * from the environment variable named by {@code apiKeyEnv} and is never logged or returned.
 */
@ConfigurationProperties(prefix = "uddp.ai")
public class AiProperties {
    private boolean enabled;
    private String providerUrl;
    private String model;
    private String apiKeyEnv = "UDDP_AI_API_KEY";
    private int timeoutSeconds = 60;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getProviderUrl() { return providerUrl; }
    public void setProviderUrl(String providerUrl) { this.providerUrl = providerUrl; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getApiKeyEnv() { return apiKeyEnv; }
    public void setApiKeyEnv(String apiKeyEnv) { this.apiKeyEnv = apiKeyEnv; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    /** Enabled only when switched on and both the provider URL and model are present. */
    public boolean configured() {
        return enabled && providerUrl != null && !providerUrl.isBlank() && model != null && !model.isBlank();
    }
}
