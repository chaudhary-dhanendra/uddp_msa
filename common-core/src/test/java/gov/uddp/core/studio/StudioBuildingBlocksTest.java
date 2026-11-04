package gov.uddp.core.studio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.scheme.SchemeCatalogue;
import gov.uddp.core.studio.StudioModels.ColumnSuggestion;
import gov.uddp.core.testsupport.SampleData;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Profiling, suggestions and YAML generation on a source no pack has seen (generic column names and values). */
class StudioBuildingBlocksTest {
    @TempDir
    Path temp;

    private final StudioMetadata metadata = new StudioMetadata(SampleData.REPOSITORY_ROOT.resolve("platform"),
            new SchemeCatalogue(SampleData.SCHEME_CATALOGUE));

    @Test
    void profilesTypesDateFormatsBlanksAndUniqueness() throws Exception {
        Path file = temp.resolve("register.csv");
        Files.writeString(file, String.join("\n",
                "citizen_id,full_name,birth_date,paid_on,phone,village",
                "C1,Asha Rao,31-12-1970,20240105,9123456789,Rampur",
                "C2,Vikram Das,01-02-1965,20240106,9123456780,",
                ""), StandardCharsets.UTF_8);

        var profile = new CsvProfiler().profile(file);

        Map<String, StudioModels.ColumnProfile> columns = profile.columns().stream()
                .collect(Collectors.toMap(StudioModels.ColumnProfile::name, c -> c));
        assertEquals(2, profile.rowCount());
        assertEquals("DD-MM-YYYY", columns.get("birth_date").dateFormat());
        assertEquals("YYYYMMDD", columns.get("paid_on").dateFormat());
        assertEquals("mobile", columns.get("phone").inferredType());
        assertTrue(columns.get("citizen_id").unique());
        assertEquals(1, columns.get("village").blanks());
        assertNull(columns.get("full_name").dateFormat());
    }

    @Test
    void suggestsTargetsFromGenericSignalsAndKeepsTheRestAsExtensions() throws Exception {
        Path file = temp.resolve("register.csv");
        Files.writeString(file, String.join("\n",
                "household_no,applicant_name,dob,sex,district,contact,remarks",
                "H1,Asha Rao,1970-12-31,F,Patna,9123456789,new",
                "H1,Vikram Rao,1965-02-01,M,Gaya,9123456780,",
                ""), StandardCharsets.UTF_8);
        var profile = new CsvProfiler().profile(file);

        var suggestions = new SuggestionEngine(metadata).suggest(profile, List.of());

        Map<String, List<String>> targets = suggestions.columns().stream().collect(Collectors.toMap(
                ColumnSuggestion::column, c -> c.targets().stream().map(t -> t.entity() + ":" + t.target()).toList()));
        assertEquals(List.of("Resident:name"), targets.get("applicant_name"));
        assertEquals(List.of("Resident:dateOfBirth"), targets.get("dob"));
        assertEquals(List.of("Resident:gender"), targets.get("sex"));
        assertEquals(List.of("Resident:address.districtLGD"), targets.get("district"));
        assertEquals(List.of("Resident:mobile"), targets.get("contact"));
        assertTrue(targets.get("household_no").contains("Family:sourceFamilyIds[]"));
        assertEquals(List.of(), targets.get("remarks"), "an unrecognised column stays an extension");
        assertEquals("household_no", suggestions.recordIdField());
        assertEquals(List.of("household_no", "applicant_name"), suggestions.recordKeyFields(),
                "a shared household number needs the name to tell residents apart");
        var district = suggestions.crosswalks().stream().filter(c -> c.id().equals("district")).findFirst().orElseThrow();
        assertEquals(List.of("100001", "100002"), district.entries().stream().map(e -> e.targetValue()).toList());
        var gender = suggestions.crosswalks().stream().filter(c -> c.id().equals("gender")).findFirst().orElseThrow();
        assertEquals(Map.of("F", "FEMALE", "M", "MALE"), gender.entries().stream()
                .collect(Collectors.toMap(e -> e.sourceValue(), e -> e.targetValue())));
    }

    @Test
    void yamlScalarsAreQuotedWhenTheyCouldBeMisread() {
        assertEquals("pds", PackGenerator.scalar("pds"));
        assertEquals("address.districtLGD", PackGenerator.scalar("address.districtLGD"));
        assertEquals("'101'", PackGenerator.scalar("101"));
        assertEquals("'No'", PackGenerator.scalar("No"));
        assertEquals("'date:DD-MM-YYYY'", PackGenerator.scalar("date:DD-MM-YYYY"));
        assertEquals("'sourceFamilyIds[]'", PackGenerator.scalar("sourceFamilyIds[]"));
        assertEquals("'it''s'", PackGenerator.scalar("it's"));
        assertFalse(PackGenerator.scalar("North Patna").equals("North Patna"));
    }
}
