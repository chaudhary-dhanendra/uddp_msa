package gov.uddp.core.identity;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.security.SecureRandom;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * UDDP IDs (Track E4, V83): opaque public IDs for Golden Residents and Families, independent of every source
 * identifier. The format is per deployment ({@code uddp.identity.uddp-id.*}): body length, alphabet, an optional
 * prefix per kind and an optional Luhn mod N check character. Bodies come from {@link SecureRandom}, so IDs are not
 * guessable or sequential; an ID is never reused, and a retired or merged identity's ID resolves to the survivor.
 */
@Component
public class UddpIdService {
    public enum Kind { RESIDENT, FAMILY }

    /** Where a UDDP ID leads: the identity it names and, through merges, the surviving identity and its UDDP ID. */
    public record Resolution(String uddpId, Kind kind, UUID goldenId, String status, UUID resolvedGoldenId,
                             String resolvedUddpId) {
        public boolean redirected() {
            return !goldenId.equals(resolvedGoldenId);
        }
    }

    /** The per-deployment format. */
    public record Format(int length, String alphabet, String residentPrefix, String familyPrefix, boolean checksum) {
        public Format {
            if (length < 6 || length > 32) {
                throw new IllegalArgumentException("uddp-id length must be 6-32");
            }
            if (alphabet == null || alphabet.length() < 16 || alphabet.chars().distinct().count() != alphabet.length()) {
                throw new IllegalArgumentException("uddp-id alphabet needs at least 16 distinct characters");
            }
            residentPrefix = residentPrefix == null ? "" : residentPrefix;
            familyPrefix = familyPrefix == null ? "" : familyPrefix;
        }

        String prefix(Kind kind) {
            return kind == Kind.RESIDENT ? residentPrefix : familyPrefix;
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_MERGE_CHAIN = 1000;

    private final PostgresConnectionFactory connections;
    private final Format format;

    @Autowired
    public UddpIdService(PostgresConnectionFactory connections,
                         @Value("${uddp.identity.uddp-id.length:10}") int length,
                         @Value("${uddp.identity.uddp-id.alphabet:23456789ABCDEFGHJKLMNPQRSTUVWXYZ}") String alphabet,
                         @Value("${uddp.identity.uddp-id.resident-prefix:}") String residentPrefix,
                         @Value("${uddp.identity.uddp-id.family-prefix:}") String familyPrefix,
                         @Value("${uddp.identity.uddp-id.checksum:true}") boolean checksum) {
        this(connections, new Format(length, alphabet, residentPrefix, familyPrefix, checksum));
    }

    public UddpIdService(PostgresConnectionFactory connections, Format format) {
        this.connections = connections;
        this.format = format;
    }

    public Format format() {
        return format;
    }

    /** A new random ID in the configured format (not yet stored). */
    public String generate(Kind kind) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            body.append(format.alphabet().charAt(RANDOM.nextInt(format.alphabet().length())));
        }
        if (format.checksum()) {
            body.append(checkCharacter(body));
        }
        return format.prefix(kind) + body;
    }

    /** True when the text has the configured prefix, length, alphabet and (if configured) check character. */
    public boolean wellFormed(String id) {
        if (id == null) {
            return false;
        }
        for (Kind kind : Kind.values()) {
            String prefix = format.prefix(kind);
            if (!id.startsWith(prefix)) {
                continue;
            }
            String body = id.substring(prefix.length());
            int expected = format.length() + (format.checksum() ? 1 : 0);
            if (body.length() != expected || !body.chars().allMatch(ch -> format.alphabet().indexOf(ch) >= 0)) {
                continue;
            }
            if (!format.checksum() || checkCharacter(body.substring(0, format.length())) == body.charAt(format.length())) {
                return true;
            }
        }
        return false;
    }

    /** The identity's UDDP ID, minting one in the caller's transaction if it has none. */
    public String mint(Connection c, Kind kind, UUID goldenId) throws Exception {
        Optional<String> existing = idOf(c, kind, goldenId);
        if (existing.isPresent()) {
            return existing.get();
        }
        for (int attempt = 0; attempt < 20; attempt++) {
            String id = generate(kind);
            try (var s = c.prepareStatement("insert into uddp_id(uddp_id, kind, golden_id) values (?, ?, ?)"
                    + " on conflict do nothing")) {
                s.setString(1, id);
                s.setString(2, kind.name());
                s.setObject(3, goldenId);
                if (s.executeUpdate() == 1) {
                    return id;
                }
            }
            existing = idOf(c, kind, goldenId);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        throw new IllegalStateException("Cannot mint a unique UDDP ID; widen uddp.identity.uddp-id.length");
    }

    /** Mints IDs for every Golden identity of the kind that has none (new identities of a build, and older data). */
    public int mintMissing(Connection c, Kind kind) throws Exception {
        String sql = kind == Kind.RESIDENT
                ? "select g.golden_resident_id from golden_resident g where not exists (select 1 from uddp_id u"
                        + " where u.kind = 'RESIDENT' and u.golden_id = g.golden_resident_id) order by g.created_at, 1"
                : "select f.golden_family_id from golden_family f where not exists (select 1 from uddp_id u"
                        + " where u.kind = 'FAMILY' and u.golden_id = f.golden_family_id) order by f.created_at, 1";
        List<UUID> ids = new ArrayList<>();
        try (var s = c.prepareStatement(sql); var r = s.executeQuery()) {
            while (r.next()) {
                ids.add(r.getObject(1, UUID.class));
            }
        }
        for (UUID id : ids) {
            mint(c, kind, id);
        }
        return ids.size();
    }

    public Optional<String> idOf(Kind kind, UUID goldenId) {
        try (var c = connections.open()) {
            return idOf(c, kind, goldenId);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the UDDP ID", e);
        }
    }

    /** Resolves an ID; empty when it is malformed or was never minted. */
    public Optional<Resolution> resolve(String uddpId) {
        if (!wellFormed(uddpId)) {
            return Optional.empty();
        }
        try (var c = connections.open()) {
            Kind kind;
            UUID golden;
            try (var s = c.prepareStatement("select kind, golden_id from uddp_id where uddp_id = ?")) {
                s.setString(1, uddpId);
                var r = s.executeQuery();
                if (!r.next()) {
                    return Optional.empty();
                }
                kind = Kind.valueOf(r.getString(1));
                golden = r.getObject(2, UUID.class);
            }
            String table = kind == Kind.RESIDENT ? "golden_resident" : "golden_family";
            String idColumn = kind == Kind.RESIDENT ? "golden_resident_id" : "golden_family_id";
            String status = null;
            UUID current = golden;
            for (int i = 0; i < MAX_MERGE_CHAIN; i++) {
                String merged = kind == Kind.RESIDENT ? ", merged_into_id" : ", null::uuid";
                try (var s = c.prepareStatement("select status" + merged + " from " + table + " where " + idColumn + " = ?")) {
                    s.setObject(1, current);
                    var r = s.executeQuery();
                    if (!r.next()) {
                        break;
                    }
                    if (current.equals(golden)) {
                        status = r.getString(1);
                    }
                    UUID next = r.getObject(2, UUID.class);
                    if ("ACTIVE".equals(r.getString(1)) || next == null) {
                        break;
                    }
                    current = next;
                }
            }
            String resolvedId = current.equals(golden) ? uddpId : mint(c, kind, current);
            return Optional.of(new Resolution(uddpId, kind, golden, status, current, resolvedId));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot resolve the UDDP ID", e);
        }
    }

    private Optional<String> idOf(Connection c, Kind kind, UUID goldenId) throws Exception {
        try (var s = c.prepareStatement("select uddp_id from uddp_id where kind = ? and golden_id = ?")) {
            s.setString(1, kind.name());
            s.setObject(2, goldenId);
            var r = s.executeQuery();
            return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
        }
    }

    /** Luhn mod N over the alphabet: catches every single-character error and most adjacent swaps. */
    char checkCharacter(CharSequence body) {
        String alphabet = format.alphabet();
        int n = alphabet.length();
        int factor = 2;
        int sum = 0;
        for (int i = body.length() - 1; i >= 0; i--) {
            int addend = factor * alphabet.indexOf(body.charAt(i));
            factor = factor == 2 ? 1 : 2;
            sum += addend / n + addend % n;
        }
        int remainder = sum % n;
        return alphabet.charAt((n - remainder) % n);
    }
}
