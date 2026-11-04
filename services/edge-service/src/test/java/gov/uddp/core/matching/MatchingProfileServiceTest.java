package gov.uddp.core.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.matching.MatchingProfileService.MatchingProfile;
import gov.uddp.core.matching.MatchingProfileService.ProfileConflictException;
import gov.uddp.core.testsupport.TestDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MatchingProfileServiceTest {
    private static final Map<String, Object> CONFIG = Map.of(
            "fields", List.of(Map.of("field", "name", "comparison", "NAME")),
            "blocking", List.of(List.of("name_phonetic")),
            "thresholds", Map.of("autoMatch", 0.95, "possibleMatch", 0.7));

    private PostgresConnectionFactory connections;
    private MatchingProfileService service;
    private final List<String> pythonCalls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        connections = TestDatabase.freshMigrated();
        service = new MatchingProfileService(connections, new ObjectMapper(), (module, arguments) -> {
            pythonCalls.add(module + " " + arguments.getFirst());
            return new ObjectMapper().createObjectNode();
        }, Runnable::run);
    }

    @Test
    void versionsAreSequentialPerNameAndTheBuiltInModelIsTheDefaultUntilOneIsApproved() {
        MatchingProfile first = service.create("resident", CONFIG, "maker");
        MatchingProfile second = service.create("resident", CONFIG, "maker");

        assertEquals(1, first.version());
        assertEquals(2, second.version());
        assertEquals("DRAFT", second.status());
        assertEquals("resident-match-v1", service.effectiveModelVersion());
        assertTrue(service.approved().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.create("x", Map.of("blocking", List.of()), "maker"));
    }

    @Test
    void anApprovedProfileIsTheOneMatchingUsesAndApprovingAnotherRetiresIt() {
        MatchingProfile first = trainedAndSubmitted("maker");
        assertThrows(ProfileConflictException.class, () -> service.approve(first.profileId(), "maker"));
        assertThrows(ProfileConflictException.class, () -> service.approve(first.profileId(), null));

        service.approve(first.profileId(), "checker");
        assertEquals("resident-v1", service.effectiveModelVersion());
        assertEquals(first.profileId(), service.approved().orElseThrow().profileId());

        MatchingProfile second = trainedAndSubmitted("maker");
        service.approve(second.profileId(), "checker");
        assertEquals("resident-v2", service.effectiveModelVersion());
        assertEquals("RETIRED", service.get(first.profileId()).status());
        assertEquals("checker", service.get(second.profileId()).approvedBy());
    }

    @Test
    void trainingRunsThePythonModuleAndAnUntrainedProfileCannotBeSubmitted() {
        MatchingProfile draft = service.create("resident", CONFIG, "maker");
        assertThrows(ProfileConflictException.class, () -> service.submit(draft.profileId(), "maker"));

        var job = service.startTraining(draft.profileId(), "maker");

        assertEquals("train --profile-id", pythonCalls.getFirst());
        assertEquals("QUEUED", job.status());
        assertEquals(1, service.jobs(draft.profileId()).size());
        assertThrows(ProfileConflictException.class, () -> service.approve(draft.profileId(), "checker"));
    }

    @Test
    void aFailedTrainingIsReportedOnTheJob() {
        var failing = new MatchingProfileService(connections, new ObjectMapper(), (module, arguments) -> {
            throw new IllegalStateException("Splink exploded");
        }, Runnable::run);
        MatchingProfile draft = failing.create("resident", CONFIG, "maker");

        failing.startTraining(draft.profileId(), "maker");

        var job = failing.jobs(draft.profileId()).getFirst();
        assertEquals("FAILED", job.status());
        assertEquals("Splink exploded", job.message());
    }

    private MatchingProfile trainedAndSubmitted(String maker) {
        MatchingProfile draft = service.create("resident", CONFIG, maker);
        try (var c = connections.open(); var s = c.prepareStatement(
                "update matching_profile set parameters = '{}'::jsonb, weights = '[]'::jsonb, trained_at = now()"
                        + " where profile_id = ?")) {
            s.setObject(1, draft.profileId());
            s.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return service.submit(draft.profileId(), maker);
    }
}
