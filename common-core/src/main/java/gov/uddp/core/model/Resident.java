package gov.uddp.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Canonical Resident, schema {@code uddp/resident/1.1.0}; the v1.1 additions are omitted from JSON when absent. */
public record Resident(String residentId, @NotBlank String name, LocalDate dateOfBirth, Gender gender,
                       String mobile, @Valid Address address, @NotNull List<@Valid SourceReference> sourceReferences,
                       Map<String, Object> extensions, @NotNull @Valid RecordMetadata metadata,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String nameNormalised,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<Relation> relations,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Age age,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Integer birthYear,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String dateOfBirthPrecision,
                       @JsonInclude(JsonInclude.Include.NON_NULL) List<PartialIdentifier> partialIdentifiers,
                       @JsonInclude(JsonInclude.Include.NON_NULL) String socialCategory,
                       @JsonInclude(JsonInclude.Include.NON_NULL) Geography geography) {

    /** A v1.0 Resident: none of the v1.1 additions. */
    public Resident(String residentId, String name, LocalDate dateOfBirth, Gender gender, String mobile,
                    Address address, List<SourceReference> sourceReferences, Map<String, Object> extensions,
                    RecordMetadata metadata) {
        this(residentId, name, dateOfBirth, gender, mobile, address, sourceReferences, extensions, metadata,
                null, null, null, null, null, null, null, null);
    }

    /** One observed relation; every observation is kept, none wins. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Relation(String type, String name, String nameNormalised, Boolean deceased, Double confidence) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Age(Integer years, LocalDate asOf) { }

    public record PartialIdentifier(String type, String value) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Geography(String freeText) { }
}
