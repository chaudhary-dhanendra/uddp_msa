package gov.uddp.core.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Onboarding Studio locations and limits ({@code uddp.studio.*}); every directory is gitignored. */
@ConfigurationProperties(prefix = "uddp.studio")
public class StudioProperties {
    /** Where published Studio packs are written; demo mode points uddp.state-pack-directory here too. */
    private String packsDirectory = "../../studio-packs";
    /** Drafts and uploaded source files (the landing folder). */
    private String workspaceDirectory = "../../studio-workspace";
    private long uploadLimitBytes = 10L * 1024 * 1024;
    /** Git ref the portability evidence compares platform code against. */
    private String baselineRef = "master";
    /** Suggestions at or above this confidence are pre-accepted; the rest wait for review in the exception inbox. */
    private double suggestionAcceptConfidence = 0.7;

    public double getSuggestionAcceptConfidence() {
        return suggestionAcceptConfidence;
    }

    public void setSuggestionAcceptConfidence(double suggestionAcceptConfidence) {
        this.suggestionAcceptConfidence = suggestionAcceptConfidence;
    }

    public String getPacksDirectory() {
        return packsDirectory;
    }

    public void setPacksDirectory(String packsDirectory) {
        this.packsDirectory = packsDirectory;
    }

    public String getWorkspaceDirectory() {
        return workspaceDirectory;
    }

    public void setWorkspaceDirectory(String workspaceDirectory) {
        this.workspaceDirectory = workspaceDirectory;
    }

    public long getUploadLimitBytes() {
        return uploadLimitBytes;
    }

    public void setUploadLimitBytes(long uploadLimitBytes) {
        this.uploadLimitBytes = uploadLimitBytes;
    }

    public String getBaselineRef() {
        return baselineRef;
    }

    public void setBaselineRef(String baselineRef) {
        this.baselineRef = baselineRef;
    }

    public Path packs() {
        return Path.of(packsDirectory).toAbsolutePath().normalize();
    }

    public Path workspace() {
        return Path.of(workspaceDirectory).toAbsolutePath().normalize();
    }
}
