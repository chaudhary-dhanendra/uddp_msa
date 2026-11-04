package gov.uddp.core.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import gov.uddp.core.security.DepartmentScope;
import gov.uddp.core.security.Permission;
import gov.uddp.core.security.StateScope;
import gov.uddp.core.security.UddpAuthentication;
import gov.uddp.core.security.UddpPrincipal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Test-only (registered in src/test/resources/META-INF/spring): MockMvc requests run as a platform administrator for
 * every state with a valid CSRF token unless the request says otherwise, so tests written before P1 keep testing
 * their own behaviour. Security tests pass {@link #as} explicitly; a request's own post-processors override these
 * defaults. Test principals carry no user ID, so the per-request refresh from the database leaves them alone.
 */
@AutoConfiguration
public class TestSecurity {
    public static final UddpPrincipal PLATFORM_ADMIN = principal("test-admin", Set.of("PLATFORM_ADMIN"),
            EnumSet.allOf(Permission.class), List.of(DepartmentScope.ALL));

    @Bean
    MockMvcBuilderCustomizer platformAdminByDefault() {
        return builder -> builder.defaultRequest(MockMvcRequestBuilders.get("/")
                .with(as(PLATFORM_ADMIN))
                .with(csrf()));
    }

    /** Runs the request as this principal. */
    public static RequestPostProcessor as(UddpPrincipal principal) {
        return authentication(new UddpAuthentication(principal));
    }

    public static UddpPrincipal principal(String username, Set<String> roles, Collection<Permission> permissions,
                                          List<String> departments) {
        DepartmentScope scope = DepartmentScope.of(departments);
        // The record-level scope of a department list is resolved per request by PrincipalRefreshFilter.
        return new UddpPrincipal(null, username, username, roles,
                permissions.isEmpty() ? Set.of() : EnumSet.copyOf(permissions), scope,
                scope.all() ? StateScope.everyState() : StateScope.none(), false, "TEST");
    }
}
