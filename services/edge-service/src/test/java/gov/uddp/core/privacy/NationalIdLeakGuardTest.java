package gov.uddp.core.privacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The national ID leak rule (P1 Part 5b) fires on Aadhaar numbers only, never on ordinary identifiers. */
class NationalIdLeakGuardTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void realLookingNonAadhaarNumbersNeverTrigger() {
        List<String> numbers = List.of(
                "9000000001",              // mobile
                "+91 90000 00001",         // mobile with country code
                "234567890123",            // 12 digits, wrong Verhoeff check digit
                "2345 6789 0123",          // grouped, wrong check digit
                "123456789012",            // starts with 1
                "034567890124",            // starts with 0
                "1234567890124",           // 13 digits containing a valid 12-digit tail
                "2345678901240",           // 13 digits containing a valid 12-digit head
                "RC2345678901240",         // ration card number
                "PPO-234567890124",        // pension payment order with hyphen prefix
                "2345-6789 0124",          // mixed grouping
                "10000234567",             // bank account
                "a1b2c3d4-2345-6789-0124-0123456789ab", // UUID with digit-only groups
                "2345-6789-0124-5678");    // longer hyphenated identifier
        for (String number : numbers) {
            assertFalse(NationalIdLeakGuard.looksLikeNationalId(number), number);
        }
        for (int i = 0; i < 2000; i++) {
            String uuid = UUID.randomUUID().toString();
            assertFalse(NationalIdLeakGuard.looksLikeNationalId(uuid), uuid);
        }
    }

    @Test
    void aadhaarNumbersTriggerInAnyCommonFormat() {
        for (String text : List.of("234567890124", "2345 6789 0124", "2345-6789-0124", "old card 3456-7890-1238.")) {
            assertTrue(NationalIdLeakGuard.looksLikeNationalId(text), text);
        }
        assertEquals(java.util.Optional.of("AADHAAR"), NationalIdLeakGuard.sensitiveTypeOf("2345 6789 0124"));
        assertTrue(NationalIdLeakGuard.sensitiveTypeOf("card 2345 6789 0124").isEmpty());
    }

    @Test
    void platformIdentifiersAndTheProtectedExtensionAreNotScanned() throws Exception {
        var payload = json.readTree("""
                {"name": "Ram Singh", "mobile": "9000000001",
                 "metadata": {"sourceRecordId": "234567890124"},
                 "sourceReferences": [{"recordId": "234567890124"}],
                 "extensions": {"uddp.rawRecordId": "234567890124", "uddp.nationalIds": {"AADHAAR": {"last4": "0124"}},
                                "ration_no": "RC2345678901240", "note": "Aadhaar 2345 6789 0124"}}
                """);
        assertEquals(List.of("extensions.note"), NationalIdLeakGuard.leaks(payload));
    }
}
