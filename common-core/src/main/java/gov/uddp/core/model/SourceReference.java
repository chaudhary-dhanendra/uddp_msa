package gov.uddp.core.model;

import jakarta.validation.constraints.NotBlank;

public record SourceReference(@NotBlank String stateCode, @NotBlank String sourceSystem, @NotBlank String sourceRecordId) { }
