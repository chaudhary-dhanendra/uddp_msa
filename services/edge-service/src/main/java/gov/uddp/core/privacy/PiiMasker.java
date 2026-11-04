package gov.uddp.core.privacy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Masks personal data in an API response tree (display only; stored, matched and survived data stay unmasked):
 * names as first name plus initial, dates of birth as the year, mobile numbers as the last four digits, addresses as
 * the district only. It recognises resident fields by key ({@code name}, {@code nameA}, {@code dateOfBirth},
 * {@code mobile}, {@code address}, ...) and by field path where a value is described by one ({@code fieldPath},
 * {@code field}, {@code target}); raw source payloads and unlabelled source fields are masked completely.
 */
public final class PiiMasker {
    static final String HIDDEN = "•••";
    private static final Set<String> NAME_KEYS = Set.of("name", "fullname", "residentname");
    private static final Set<String> BIRTH_KEYS = Set.of("dateofbirth", "dob", "birthdate");
    private static final Set<String> MOBILE_KEYS = Set.of("mobile", "phone", "mobilenumber");
    private static final Set<String> ADDRESS_KEYS = Set.of("address");
    private static final Set<String> DISTRICT_KEYS = Set.of("districtlgd", "district", "districtname");
    private static final Set<String> RECORD_KEY_KEYS = Set.of("sourcerecordkey", "recordkey", "residentrecordkey",
            "sourcerecordkeys", "detachedsourcerecordkeys", "remainingsourcerecordkeys");
    private static final Set<String> PATH_KEYS = Set.of("fieldPath", "field", "target");
    private static final Set<String> PATH_VALUE_KEYS = Set.of("value", "before", "after", "oldValue", "newValue",
            "valueA", "valueB", "sourceValue", "canonicalValue", "selectedValue");

    private PiiMasker() {
    }

    enum Kind { NAME, BIRTH, MOBILE, ADDRESS, RECORD_KEY, NONE }

    /** Masks the tree in place and returns it. */
    public static JsonNode mask(JsonNode node) {
        if (node instanceof ObjectNode object) {
            maskObject(object);
        } else if (node instanceof ArrayNode array) {
            array.forEach(PiiMasker::mask);
        }
        return node;
    }

    private static void maskObject(ObjectNode object) {
        Kind pathKind = Kind.NONE;
        for (String pathKey : PATH_KEYS) {
            if (object.path(pathKey).isTextual()) {
                pathKind = kindOfPath(object.get(pathKey).asText());
                if (pathKind != Kind.NONE) {
                    break;
                }
            }
        }
        List<String> keys = new ArrayList<>();
        object.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            JsonNode value = object.get(key);
            if (pathKind != Kind.NONE && PATH_VALUE_KEYS.contains(key)) {
                object.set(key, maskValue(value, pathKind));
                continue;
            }
            if (pathKind != Kind.NONE && key.equals("contributors") && value.isArray()) {
                for (JsonNode contributor : value) {
                    if (contributor instanceof ObjectNode item && item.has("value")) {
                        item.set("value", maskValue(item.get("value"), pathKind));
                    }
                    mask(contributor);
                }
                continue;
            }
            if (key.equals("rawPayload") && value.isTextual()) {
                object.put(key, HIDDEN);
                continue;
            }
            if ((key.equals("fields") || key.equals("sourceFields") || key.equals("rawFields")) && value.isArray()
                    && isSourceFieldList(value)) {
                value.forEach(field -> ((ObjectNode) field).put("value", field.path("value").isNull() ? null : HIDDEN));
                continue;
            }
            Kind kind = kindOfKey(key);
            if (kind != Kind.NONE && !(kind == Kind.ADDRESS && !value.isObject())) {
                object.set(key, maskValue(value, kind));
            } else {
                mask(value);
            }
        }
    }

    /** {@code [{name, value}]} with no other keys: source columns, whose meaning the API does not know. */
    private static boolean isSourceFieldList(JsonNode list) {
        for (JsonNode item : list) {
            if (!item.isObject() || !item.has("name") || !item.has("value") || item.size() != 2) {
                return false;
            }
        }
        return true;
    }

    static Kind kindOfKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        // Pair views name the two sides nameA / nameB, dateOfBirthA, mobileB ...
        if (key.length() > 1 && (key.endsWith("A") || key.endsWith("B")) && Character.isLowerCase(key.charAt(key.length() - 2))) {
            k = k.substring(0, k.length() - 1);
        }
        if (NAME_KEYS.contains(k)) {
            return Kind.NAME;
        }
        if (BIRTH_KEYS.contains(k)) {
            return Kind.BIRTH;
        }
        if (MOBILE_KEYS.contains(k)) {
            return Kind.MOBILE;
        }
        if (ADDRESS_KEYS.contains(k)) {
            return Kind.ADDRESS;
        }
        if (RECORD_KEY_KEYS.contains(k)) {
            return Kind.RECORD_KEY;
        }
        return Kind.NONE;
    }

    static Kind kindOfPath(String path) {
        String last = path.replace("[]", "");
        if (last.toLowerCase(Locale.ROOT).startsWith("address")) {
            String rest = last.contains(".") ? last.substring(last.lastIndexOf('.') + 1) : "";
            return rest.isEmpty() ? Kind.ADDRESS : DISTRICT_KEYS.contains(rest.toLowerCase(Locale.ROOT)) ? Kind.NONE
                    : Kind.ADDRESS;
        }
        if (last.contains(".")) {
            last = last.substring(last.lastIndexOf('.') + 1);
        }
        Kind kind = kindOfKey(last);
        return kind == Kind.ADDRESS ? Kind.ADDRESS : kind;
    }

    private static JsonNode maskValue(JsonNode value, Kind kind) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return value;
        }
        // A fraction under a resident key (the share of records with a name) is a statistic, not personal data; a
        // whole number can still be a phone number and stays masked.
        if (value.isFloatingPointNumber()) {
            return value;
        }
        if (value.isArray()) {
            ArrayNode copy = ((ArrayNode) value).deepCopy();
            for (int i = 0; i < copy.size(); i++) {
                copy.set(i, maskValue(copy.get(i), kind));
            }
            return copy;
        }
        if (kind == Kind.ADDRESS) {
            if (value.isObject()) {
                ObjectNode district = ((ObjectNode) value).objectNode();
                value.fieldNames().forEachRemaining(key -> {
                    if (DISTRICT_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                        district.set(key, value.get(key));
                    }
                });
                return district;
            }
            return TextNode.valueOf(HIDDEN);
        }
        String text = value.asText();
        return TextNode.valueOf(switch (kind) {
            case NAME -> name(text);
            case BIRTH -> birth(text);
            case MOBILE -> mobile(text);
            case RECORD_KEY -> recordKey(text);
            default -> text;
        });
    }

    /** "Ram Singh" -> "Ram S."; a single name keeps its first letter. */
    static String name(String text) {
        String[] parts = text.strip().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return text;
        }
        if (parts.length == 1) {
            return parts[0].charAt(0) + HIDDEN;
        }
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    /**
     * A source record key is {@code state|source|entity|recordId[|key fields]}; key fields can be personal data (a
     * name), so they are masked like names: "XX|pds|pds|R1001|Ram Singh" -> "XX|pds|pds|R1001|Ram S.".
     */
    static String recordKey(String text) {
        String[] parts = text.split("\\|", -1);
        if (parts.length <= 4) {
            return text;
        }
        StringBuilder out = new StringBuilder(String.join("|", java.util.Arrays.copyOf(parts, 4)));
        for (int i = 4; i < parts.length; i++) {
            out.append('|').append(parts[i].chars().anyMatch(Character::isLetter) ? name(parts[i]) : parts[i]);
        }
        return out.toString();
    }

    /** "1980-04-12" -> "1980". */
    static String birth(String text) {
        String trimmed = text.strip();
        return trimmed.length() >= 4 && trimmed.substring(0, 4).chars().allMatch(Character::isDigit)
                ? trimmed.substring(0, 4) : HIDDEN;
    }

    /** "9876543210" -> "******3210". */
    static String mobile(String text) {
        String digits = text.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return HIDDEN;
        }
        return "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }
}
