package gov.uddp.core.security;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code uddp.security.*}: password policy, lockout, maker-checker switches, the demo seed and optional OIDC single
 * sign-on. Defaults are the secure choice; an environment may only relax maker-checker explicitly.
 */
@ConfigurationProperties(prefix = "uddp.security")
public class SecurityProperties {
    private final Password password = new Password();
    private final Lockout lockout = new Lockout();
    private final MakerChecker makerChecker = new MakerChecker();
    private final Demo demo = new Demo();
    private final Oidc oidc = new Oidc();

    public Password getPassword() {
        return password;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public MakerChecker getMakerChecker() {
        return makerChecker;
    }

    public Demo getDemo() {
        return demo;
    }

    public Oidc getOidc() {
        return oidc;
    }

    public static class Password {
        private int minLength = 12;

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(int minLength) {
            this.minLength = minLength;
        }
    }

    public static class Lockout {
        private int maxFailedAttempts = 5;
        private Duration duration = Duration.ofMinutes(15);

        public int getMaxFailedAttempts() {
            return maxFailedAttempts;
        }

        public void setMaxFailedAttempts(int maxFailedAttempts) {
            this.maxFailedAttempts = maxFailedAttempts;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }
    }

    /** Second-approver rules (ADR-0021); both are on unless an environment turns them off. */
    public static class MakerChecker {
        private boolean statePackPublish = true;
        private boolean stewardship = true;

        public boolean isStatePackPublish() {
            return statePackPublish;
        }

        public void setStatePackPublish(boolean statePackPublish) {
            this.statePackPublish = statePackPublish;
        }

        public boolean isStewardship() {
            return stewardship;
        }

        public void setStewardship(boolean stewardship) {
            this.stewardship = stewardship;
        }
    }

    /**
     * Demo users, one per role, each with its own random one-time password (ADR-0017). Off unless demo mode turns it
     * on. With {@code credentialsFile} set, the passwords are written there (owner-only) for the demo script to show
     * once and delete; otherwise they are printed once to the console, never to the log.
     */
    public static class Demo {
        private boolean seedUsers;
        private String credentialsFile;

        public boolean isSeedUsers() {
            return seedUsers;
        }

        public void setSeedUsers(boolean seedUsers) {
            this.seedUsers = seedUsers;
        }

        public String getCredentialsFile() {
            return credentialsFile;
        }

        public void setCredentialsFile(String credentialsFile) {
            this.credentialsFile = credentialsFile;
        }
    }

    /**
     * Optional OIDC single sign-on (ADR-0017). Users are created on first sign-in; their roles and department scopes
     * come only from the identity provider's groups through {@code groupRoles} and {@code groupDepartments}.
     */
    public static class Oidc {
        private boolean enabled;
        private String displayName = "Single sign-on";
        private String issuerUri;
        private String clientId;
        private String clientSecret;
        private String scope = "openid,profile,email";
        private String usernameClaim = "preferred_username";
        private String groupsClaim = "groups";
        /** After sign-in the browser returns here (the UI). */
        private String successUrl = "/";
        private Map<String, String> groupRoles = new LinkedHashMap<>();
        private Map<String, String> groupDepartments = new LinkedHashMap<>();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getIssuerUri() {
            return issuerUri;
        }

        public void setIssuerUri(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public String getUsernameClaim() {
            return usernameClaim;
        }

        public void setUsernameClaim(String usernameClaim) {
            this.usernameClaim = usernameClaim;
        }

        public String getGroupsClaim() {
            return groupsClaim;
        }

        public void setGroupsClaim(String groupsClaim) {
            this.groupsClaim = groupsClaim;
        }

        public String getSuccessUrl() {
            return successUrl;
        }

        public void setSuccessUrl(String successUrl) {
            this.successUrl = successUrl;
        }

        public Map<String, String> getGroupRoles() {
            return groupRoles;
        }

        public void setGroupRoles(Map<String, String> groupRoles) {
            this.groupRoles = groupRoles;
        }

        public Map<String, String> getGroupDepartments() {
            return groupDepartments;
        }

        public void setGroupDepartments(Map<String, String> groupDepartments) {
            this.groupDepartments = groupDepartments;
        }
    }
}
