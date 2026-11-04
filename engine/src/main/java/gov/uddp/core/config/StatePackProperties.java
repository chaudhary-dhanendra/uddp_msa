package gov.uddp.core.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "uddp")
public class StatePackProperties {
    private String statePackDirectory = "../state-packs";
    /** Explicit bootstrap/test-only permission to load YAML packs into the operational registry. */
    private boolean fixtureStatePacksEnabled;
    private String platformDirectory = "../platform";
    /** The repository root: relative source locations, Hop templates and the Python module resolve against it. */
    private String repositoryRoot = "..";
    /** Local settings saved from the Setup screen (outside the repository; ADR-0014). */
    private String settingsDirectory = Path.of(System.getProperty("user.home"), ".uddp").toString();

    public String getStatePackDirectory() {
        return statePackDirectory;
    }

    public void setStatePackDirectory(String statePackDirectory) {
        this.statePackDirectory = statePackDirectory;
    }

    public boolean isFixtureStatePacksEnabled() {
        return fixtureStatePacksEnabled;
    }

    public void setFixtureStatePacksEnabled(boolean fixtureStatePacksEnabled) {
        this.fixtureStatePacksEnabled = fixtureStatePacksEnabled;
    }

    public String getPlatformDirectory() {
        return platformDirectory;
    }

    public void setPlatformDirectory(String platformDirectory) {
        this.platformDirectory = platformDirectory;
    }

    public String getRepositoryRoot() {
        return repositoryRoot;
    }

    public void setRepositoryRoot(String repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
    }

    public String getSettingsDirectory() {
        return settingsDirectory;
    }

    public void setSettingsDirectory(String settingsDirectory) {
        this.settingsDirectory = settingsDirectory;
    }

    public Path repositoryRoot() {
        return Path.of(repositoryRoot).toAbsolutePath().normalize();
    }
}
