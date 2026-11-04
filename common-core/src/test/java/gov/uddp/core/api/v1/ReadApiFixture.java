package gov.uddp.core.api.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.onboarding.HopRunner;
import gov.uddp.core.onboarding.OnboardingService;
import gov.uddp.core.onboarding.PythonMatchingRunner;
import gov.uddp.core.onboarding.StatePackLifecycleService;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.HopShapedRawLoader;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared read API fixture: one Spring context and disposable database per JVM, seeded once by onboarding every
 * sample State Pack source through the real {@link OnboardingService} (FULL runs, in pack order). Only the Hop and
 * Splink process boundaries are replaced by what they write: the three "Ram Singh" records are one AUTO_MATCH group
 * and "Sita Singh"/"Sunita Devi" is a POSSIBLE_MATCH pair. Result: 7 raw, 7 canonical, 3 runs, 5 active Golden.
 * Subclasses must not change the data; tests that write use their own context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(ReadApiFixture.Boundaries.class)
abstract class ReadApiFixture {
    @TestConfiguration
    static class Boundaries {
        @Bean
        @Primary
        PostgresConnectionFactory readApiConnections() {
            return TestDatabase.freshMigrated();
        }

        @Bean
        @Primary
        HopRunner readApiHop(PostgresConnectionFactory connections) {
            var raw = new HopShapedRawLoader(connections, SampleData.REPOSITORY_ROOT);
            return (source, stateCode, version, batch) ->
                    new HopRunner.Result(0, "landed " + raw.landInto(source, stateCode, version, batch));
        }

        @Bean
        @Primary
        PythonMatchingRunner readApiMatching(PostgresConnectionFactory connections) {
            return new PythonMatchingRunner(new ObjectMapper()) {
                @Override
                public UUID run() {
                    var sql = new Sql(connections);
                    Set<UUID> ram = new HashSet<>(sql.canonicalsNamed("Ram Singh"));
                    List<Set<UUID>> possible = new ArrayList<>();
                    var sita = sql.canonicalsNamed("Sita Singh");
                    var sunita = sql.canonicalsNamed("Sunita Devi");
                    if (!sita.isEmpty() && !sunita.isEmpty()) {
                        possible.add(Set.of(sita.getFirst(), sunita.getFirst()));
                    }
                    return MatchingRunFixture.completed(connections, ram.isEmpty() ? List.of() : List.of(ram), possible);
                }
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    PostgresConnectionFactory connections;

    @Autowired
    OnboardingService onboarding;

    @Autowired
    StatePackLifecycleService lifecycle;

    @Autowired
    StatePackLoader packs;

    final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void seedOnce() {
        synchronized (ReadApiFixture.class) {
            if (sql().count("select count(*) from onboarding_run") > 0) {
                return;
            }
            var ordered = packs.all().stream().sorted(Comparator.comparing(pack -> pack.manifest().packId())).toList();
            for (var pack : ordered) {
                String id = pack.manifest().packId();
                lifecycle.register(id);
                lifecycle.transition(id, pack.manifest().version(), "VALIDATED");
                lifecycle.transition(id, pack.manifest().version(), "ACTIVE");
                for (var source : pack.sources()) {
                    UUID run = onboarding.start(id, pack.manifest().version(), source.sourceId());
                    onboarding.runToCompletion(run);
                }
            }
        }
    }

    Sql sql() {
        return new Sql(connections);
    }

    /** The purpose these tests name for the full view, so assertions can compare real names. */
    static final String FULL_VIEW_PURPOSE = "Duplicate investigation";

    /** A read as the default test administrator (who has RESIDENT_VIEW_FULL) through the purpose-based full view. */
    JsonNode read(String path, Object... variables) throws Exception {
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path, variables)
                        .header(gov.uddp.core.privacy.PiiMaskingAdvice.PURPOSE_HEADER, FULL_VIEW_PURPOSE))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /**
     * A read as a PROGRAM_USER (no RESIDENT_VIEW_FULL), granted only the view permission the endpoint needs. It names
     * the full-view purpose too, which must make no difference: the response is masked.
     */
    JsonNode readAsProgramUser(gov.uddp.core.security.Permission view, String path, Object... variables)
            throws Exception {
        var permissions = java.util.EnumSet.of(gov.uddp.core.security.Permission.RESIDENT_VIEW,
                gov.uddp.core.security.Permission.FAMILY_VIEW, gov.uddp.core.security.Permission.BENEFIT_VIEW,
                gov.uddp.core.security.Permission.REPORT_VIEW, view);
        var user = gov.uddp.core.testsupport.TestSecurity.principal("pat.program", java.util.Set.of("PROGRAM_USER"),
                permissions, java.util.List.of(gov.uddp.core.security.DepartmentScope.ALL));
        var result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path, variables)
                        .header(gov.uddp.core.privacy.PiiMaskingAdvice.PURPOSE_HEADER, FULL_VIEW_PURPOSE)
                        .with(gov.uddp.core.testsupport.TestSecurity.as(user)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** The first pack's ID and state code in pack order, so tests name no state literally where they need not. */
    String firstPackId() {
        return packs.all().stream().map(pack -> pack.manifest().packId()).sorted().findFirst().orElseThrow();
    }

    String stateCodeOf(String packId) {
        return packs.find(packId).orElseThrow().manifest().stateCode();
    }
}
