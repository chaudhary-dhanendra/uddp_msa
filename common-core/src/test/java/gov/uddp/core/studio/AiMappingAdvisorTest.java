package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.ai.DisabledAiDraftService;
import gov.uddp.core.ai.FakeAiDraftService;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.studio.StudioModels.Suggestions;
import gov.uddp.core.studio.StudioModels.TargetSuggestion;
import gov.uddp.core.testsupport.SampleData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Track D2: model suggestions see only masked shapes, raise agreeing D1 suggestions and are never auto-accepted alone. */
class AiMappingAdvisorTest {
    @TempDir
    Path temp;

    private final StudioMetadata metadata = new StudioMetadata(SampleData.REPOSITORY_ROOT.resolve("platform"),
            new SchemeCatalogue(SampleData.SCHEME_CATALOGUE));
    private StudioModels.Profile profile;
    private Suggestions learned;

    @BeforeEach
    void setUp() throws Exception {
        Path file = temp.resolve("register.csv");
        Files.writeString(file, String.join("\n",
                "applicant_name,dob,contact,locality_code",
                "Asha Rao,1970-12-31,9123456789,Q17",
                "Vikram Das,1965-02-01,9123456780,Q18",
                ""), StandardCharsets.UTF_8);
        profile = new CsvProfiler().profile(file);
        learned = new SuggestionEngine(metadata, 0.7).suggest(profile, List.of());
    }

    @Test
    void combinesModelSuggestionsWithLearnedOnesAndSendsNoPersonalData() {
        FakeAiDraftService fake = FakeAiDraftService.answering("""
                {"suggestions":[
                  {"column":"applicant_name","target":"Resident:name","confidence":0.9,"reason":"person name"},
                  {"column":"locality_code","target":"Resident:address.districtLGD","confidence":0.95,"reason":"area code"},
                  {"column":"contact","target":"Resident:notATarget","confidence":0.9},
                  {"column":"missing","target":"Resident:name","confidence":0.9}]}""");

        Suggestions combined = new AiMappingAdvisor(fake, metadata, new ObjectMapper()).combine(profile, learned);

        String sent = fake.calls.get(0).content();
        for (String value : List.of("Asha", "Rao", "Vikram", "9123456789", "1970-12-31", "Q17")) {
            assertFalse(sent.contains(value), "no source value is sent: " + value);
        }
        assertTrue(sent.contains("applicant_name") && sent.contains("Aaaa Aaa") && sent.contains("9999999999"));
        assertTrue(sent.contains("Resident:dateOfBirth"), "the canonical target list is sent");

        TargetSuggestion before = target(learned, "applicant_name", "name").orElseThrow();
        TargetSuggestion name = target(combined, "applicant_name", "name").orElseThrow();
        assertTrue(name.confidence() > before.confidence(), "agreement raises the learned confidence");
        assertTrue(name.basis().contains("model agrees (90%: person name)"), name.basis());

        assertTrue(target(learned, "locality_code", "address.districtLGD").isEmpty());
        TargetSuggestion aiOnly = target(combined, "locality_code", "address.districtLGD").orElseThrow();
        assertFalse(aiOnly.accepted(), "a model-only suggestion is never pre-accepted, even at 95%");
        assertEquals(0.95, aiOnly.confidence());
        assertTrue(aiOnly.basis().startsWith("model only, review"));
        assertTrue(target(combined, "contact", "notATarget").isEmpty(), "unknown targets are ignored");
        assertEquals(learned.crosswalks(), combined.crosswalks());
    }

    @Test
    void unusableOutputOrADisabledProviderLeavesTheLearnedSuggestions() {
        assertSame(learned, new AiMappingAdvisor(FakeAiDraftService.answering("not json"), metadata, new ObjectMapper())
                .combine(profile, learned));
        assertSame(learned, new AiMappingAdvisor(new DisabledAiDraftService(), metadata, new ObjectMapper())
                .combine(profile, learned));
    }

    private static Optional<TargetSuggestion> target(Suggestions suggestions, String column, String target) {
        return suggestions.columns().stream()
                .filter(c -> c.column().equals(column))
                .flatMap(c -> c.targets().stream())
                .filter(t -> t.target().equals(target))
                .findFirst();
    }
}
