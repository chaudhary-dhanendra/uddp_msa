package gov.uddp.core.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Track E1: the identifier type registry is seeded from the platform file, validates and normalises values from each
 * type's configuration (pluggable checksums), keys ISSUER-scoped values by issuer, and changes a type only after
 * someone other than its author approves the change.
 */
class IdentifierTypeRegistryTest {
    private static final Path SEED = Path.of("../../platform/identity/identifier-types.yaml");

    private final IdentifierTypeRegistry registry = new IdentifierTypeRegistry(TestDatabase.freshMigrated(), SEED,
            IdentifierTypeRegistry.defaultValidators());

    @Test
    void validatesAndNormalisesFromConfiguration() {
        assertEquals(Set.of("AADHAAR", "RATION_CARD", "VOTER_ID", "AADHAAR_LAST4", "MOBILE_MASKED"),
                Set.copyOf(registry.types().stream().map(IdentifierType::code).toList()));
        IdentifierType aadhaar = registry.require("aadhaar");
        var grouped = registry.check(aadhaar, " 2345 6789-0124 ");
        assertTrue(grouped.valid());
        assertEquals("234567890124", grouped.normalised());

        var badCheck = registry.check(aadhaar, "234567890123");
        assertFalse(badCheck.valid());
        assertTrue(badCheck.reason().contains("VERHOEFF"));
        assertFalse(badCheck.reason().contains("234567890123"), "a reason never holds the value");
        assertFalse(registry.check(aadhaar, "12345678901").valid(), "length");
        assertFalse(registry.check(aadhaar, "134567890124").valid(), "format: first digit 2-9");

        IdentifierType voter = registry.require("VOTER_ID");
        var epic = registry.check(voter, "abc-1234567");
        assertTrue(epic.valid());
        assertEquals("ABC1234567", epic.normalised());
        assertThrows(IllegalArgumentException.class, () -> registry.require("PASSPORT"));
        assertTrue(registry.sensitiveTypes().stream().anyMatch(type -> type.code().equals("AADHAAR")));
    }

    @Test
    void partialAndMaskedTypesComeFromTheRegistry() {
        IdentifierType last4 = registry.require("AADHAAR_LAST4");
        assertEquals("AADHAAR", last4.partialOf());
        assertEquals(IdentifierType.Trust.WEAK, last4.trust());
        assertEquals(IdentifierType.Storage.PLAIN, last4.storage());
        assertEquals(Set.of(IdentifierType.Usage.MATCH_EVIDENCE), last4.usage());
        assertFalse(last4.unique());
        assertTrue(registry.check(last4, "4821").valid());
        assertFalse(registry.check(last4, "48210").valid());
        assertFalse(registry.check(last4, "48a1").valid());
        assertEquals(null, registry.require("AADHAAR").partialOf());

        IdentifierType masked = registry.require("MOBILE_MASKED");
        assertTrue(registry.check(masked, "98xxxxxx21").valid(), "case is normalised");
        assertTrue(registry.check(masked, "98******21").valid());
        assertFalse(registry.check(masked, "98******2").valid());
        assertFalse(registry.check(masked, "98ab****21").valid());
        assertThrows(IllegalArgumentException.class, () -> new IdentifierType("SELF", "Self", IdentifierType.Subject.PERSON,
                IdentifierType.Scope.NATIONAL, IdentifierType.Trust.WEAK, null, null, IdentifierType.Storage.PLAIN,
                null, null, null, null, null, "SELF"));
    }

    @Test
    void issuerScopedValuesAreKeyedByIssuer() {
        IdentifierType ration = registry.require("RATION_CARD");
        assertEquals(IdentifierType.Subject.HOUSEHOLD, ration.subject());
        String east = registry.scopeKey(ration, "st1", "RC12345");
        String west = registry.scopeKey(ration, "ST2", "RC12345");
        assertEquals("RATION_CARD:ST1:RC12345", east);
        assertNotEquals(east, west, "the same number from two issuers is two identifiers");
        assertThrows(IllegalArgumentException.class, () -> registry.scopeKey(ration, " ", "RC12345"));
        IdentifierType aadhaar = registry.require("AADHAAR");
        assertEquals(registry.scopeKey(aadhaar, "ST1", "234567890124"), registry.scopeKey(aadhaar, "ST2", "234567890124"),
                "a NATIONAL value is the same identifier for every issuer");
    }

    @Test
    void aChangeNeedsAnotherUsersApproval() {
        IdentifierType pan = new IdentifierType("PAN", "Permanent account number", IdentifierType.Subject.PERSON,
                IdentifierType.Scope.NATIONAL, IdentifierType.Trust.STRONG,
                new IdentifierType.Validation("[A-Z]{5}[0-9]{4}[A-Z]", 10, 10, "LUHN_UNKNOWN"),
                List.of(IdentifierType.Normalisation.TRIM, IdentifierType.Normalisation.UPPERCASE),
                IdentifierType.Storage.KEYED_HASH, Set.of(IdentifierType.Usage.MATCH_EVIDENCE), true, true, false, null);
        assertThrows(IllegalArgumentException.class, () -> registry.propose(pan, "maker"), "unknown checksum");

        IdentifierType valid = new IdentifierType(pan.code(), pan.label(), pan.subject(), pan.scope(), pan.trust(),
                new IdentifierType.Validation("[A-Z]{5}[0-9]{4}[A-Z]", 10, 10, null), pan.normalisation(),
                pan.storage(), pan.usage(), true, true, false, null);
        var draft = registry.propose(valid, "maker");
        assertEquals("DRAFT", draft.status());
        assertTrue(registry.find("PAN").isEmpty(), "a draft changes nothing");
        assertThrows(ApprovalConflictException.class, () -> registry.approve(draft.changeId(), "checker", null));
        registry.submit(draft.changeId(), "maker");
        assertThrows(AccessDeniedException.class, () -> registry.approve(draft.changeId(), "maker", null));
        assertTrue(registry.find("PAN").isEmpty(), "a submitted change is not in force");

        assertEquals("APPROVED", registry.approve(draft.changeId(), "checker", "checked").status());
        assertTrue(registry.check(registry.require("PAN"), "abcde1234f").valid());
        assertEquals("checker", registry.changes().getFirst().decidedBy());
    }
}
