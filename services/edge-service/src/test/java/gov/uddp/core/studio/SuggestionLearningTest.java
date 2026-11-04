package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.inbox.StudioSuggestionExceptions;
import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.studio.StudioModels.Suggestions;
import gov.uddp.core.studio.StudioModels.TargetSuggestion;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Track D1: once a source is approved, a second similar source gets higher-confidence suggestions, with the reason
 * naming the source they were learned from; suggestions below the pre-accept confidence wait in the exception inbox.
 */
class SuggestionLearningTest {
    @TempDir
    Path temp;

    private final SuggestionEngine engine = new SuggestionEngine(new StudioMetadata(
            SampleData.REPOSITORY_ROOT.resolve("platform"), new SchemeCatalogue(SampleData.SCHEME_CATALOGUE)), 0.7);

    @Test
    void aSecondSimilarSourceGetsHigherConfidenceThanTheFirst() throws Exception {
        Path file = temp.resolve("welfare.csv");
        Files.writeString(file, String.join("\n",
                "beneficiary_name,birth_date,gender_cd,remarks",
                "Asha Rao,19701231,2,new",
                "Vikram Das,19650201,1,",
                ""), StandardCharsets.UTF_8);
        var profile = new CsvProfiler().profile(file);
        List<LoadedStatePack> approved = SampleData.loader(SampleData.STATE_PACKS).all().stream()
                .filter(p -> p.mappings().stream().anyMatch(m -> "pension".equals(m.sourceId())))
                .toList();
        assertEquals(1, approved.size(), "the approved pension source pack");

        Suggestions first = engine.suggest(profile, List.of());
        Suggestions second = engine.suggest(profile, approved);

        for (String[] expected : new String[][] {{"beneficiary_name", "name"}, {"birth_date", "dateOfBirth"},
                {"gender_cd", "gender"}}) {
            TargetSuggestion before = target(first, expected[0], expected[1]);
            TargetSuggestion after = target(second, expected[0], expected[1]);
            assertTrue(after.confidence() > before.confidence(),
                    expected[0] + ": " + after.confidence() + " > " + before.confidence());
            assertTrue(after.basis().contains("used by pension source"), after.basis());
            assertTrue(after.accepted(), expected[0] + " is pre-accepted once learned");
        }
        assertFalse(target(first, "beneficiary_name", "name").accepted(), "a name keyword alone needs review");
        assertTrue(target(second, "birth_date", "dateOfBirth").basis().contains("date pattern YYYYMMDD"));

        var firstGender = first.crosswalks().stream().filter(c -> c.id().equals("gender")).findFirst().orElseThrow();
        var secondGender = second.crosswalks().stream().filter(c -> c.id().equals("gender")).findFirst().orElseThrow();
        var unmapped = firstGender.entries().stream().filter(e -> e.sourceValue().equals("1")).findFirst().orElseThrow();
        var learned = secondGender.entries().stream().filter(e -> e.sourceValue().equals("1")).findFirst().orElseThrow();
        assertNull(unmapped.targetValue());
        assertFalse(unmapped.accepted());
        assertEquals("MALE", learned.targetValue());
        assertTrue(learned.accepted());
        assertEquals("value used by pension source", learned.basis());

        // Not pre-accepted -> listed in the exception inbox until a person saves the draft's mappings.
        var connections = TestDatabase.freshMigrated();
        var reviews = new SuggestionReviewStore(connections);
        var inbox = new StudioSuggestionExceptions(connections);
        assertTrue(reviews.record("draft-1", "welfare-pack", "SRC-W", first) > 0);
        var items = inbox.items();
        assertEquals(1, items.size());
        assertEquals("MAPPING_SUGGESTION", items.get(0).type());
        assertEquals("SRC-W", items.get(0).stateCode());
        assertEquals("/onboarding-studio/drafts/draft-1/3", items.get(0).link());
        reviews.clear("draft-1");
        assertTrue(inbox.items().isEmpty());
    }

    private static TargetSuggestion target(Suggestions suggestions, String column, String target) {
        return suggestions.columns().stream()
                .filter(c -> c.column().equals(column))
                .flatMap(c -> c.targets().stream())
                .filter(t -> t.target().equals(target))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + target + " suggested for " + column));
    }
}
