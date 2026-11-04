package gov.uddp.core.security.oidc;

import gov.uddp.core.security.SecurityProperties;
import java.util.Arrays;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;

/**
 * Optional OIDC single sign-on (ADR-0017), only when {@code uddp.security.oidc.enabled=true}. The provider is any
 * standard OpenID Connect issuer (Keycloak, Entra ID, Okta, ...); its metadata is discovered on the first sign-in, so
 * the platform starts even while the provider is unreachable. Local sign-in stays available as the fallback.
 */
@Configuration
@ConditionalOnProperty(prefix = "uddp.security.oidc", name = "enabled", havingValue = "true")
public class OidcConfiguration {
    public static final String REGISTRATION_ID = "uddp";

    @Bean
    ClientRegistrationRepository oidcClientRegistrations(SecurityProperties properties) {
        return new LazyRegistrations(properties.getOidc());
    }

    /** Builds the registration from the issuer's discovery document on first use and keeps it. */
    static final class LazyRegistrations implements ClientRegistrationRepository {
        private final SecurityProperties.Oidc oidc;
        private volatile ClientRegistration registration;

        LazyRegistrations(SecurityProperties.Oidc oidc) {
            this.oidc = oidc;
        }

        @Override
        public ClientRegistration findByRegistrationId(String registrationId) {
            if (!REGISTRATION_ID.equals(registrationId)) {
                return null;
            }
            ClientRegistration current = registration;
            if (current == null) {
                synchronized (this) {
                    if (registration == null) {
                        registration = ClientRegistrations.fromIssuerLocation(oidc.getIssuerUri())
                                .registrationId(REGISTRATION_ID)
                                .clientId(oidc.getClientId())
                                .clientSecret(oidc.getClientSecret())
                                .scope(Arrays.stream(oidc.getScope().split(",")).map(String::strip).toList())
                                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                                .userNameAttributeName(oidc.getUsernameClaim())
                                .clientName(oidc.getDisplayName())
                                .build();
                    }
                    current = registration;
                }
            }
            return current;
        }
    }
}
