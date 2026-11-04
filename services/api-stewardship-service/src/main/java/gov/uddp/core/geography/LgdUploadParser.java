package gov.uddp.core.geography;

import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Turns an uploaded file into an {@link LgdSnapshotData} with the same parsers the file source uses. Every refusal is an
 * {@link IllegalArgumentException} with a message the uploader can act on; nothing is written anywhere.
 */
public final class LgdUploadParser {
    private LgdUploadParser() {
    }

    /**
     * @param asOf optional YYYY-MM-DD that overrides the file's own as-of date; without either, {@code today}
     */
    public static LgdSnapshotData parse(String fileName, byte[] bytes, String asOf, LocalDate today) {
        String name = fileName == null ? "" : fileName.strip();
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".csv") && !lower.endsWith(".json")) {
            throw new IllegalArgumentException("Unsupported file type: upload a .csv or .json snapshot");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("The file is empty");
        }
        String text = decode(bytes);
        LocalDate override = null;
        if (asOf != null && !asOf.isBlank()) {
            try {
                override = LocalDate.parse(asOf.strip());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("asOf must be a date like 2024-01-31");
            }
        }
        try {
            LgdSnapshotData data = FileLgdSourceClient.parse(name, text, today);
            if (data.rows().isEmpty()) {
                throw new IllegalArgumentException("The file has a header but no data rows");
            }
            return override == null ? data : new LgdSnapshotData(data.sourceLabel(), override, data.rows());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("The JSON file cannot be read: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw new IllegalArgumentException("The file cannot be read: " + e.getMessage());
        }
    }

    private static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("The file is not UTF-8 text; save it as CSV UTF-8 and upload again");
        }
    }
}
