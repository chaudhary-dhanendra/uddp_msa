package gov.uddp.core.scheme;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import gov.uddp.core.config.StatePackProperties;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The platform scheme catalogue (M15, ADR-0011): every scheme once, with its department, beneficiary level, defaults,
 * exclusivity group and optional eligibility. Read from {@code <platform>/schemes/catalogue.yaml} and validated when
 * constructed; State Packs crosswalk their own scheme codes onto its scheme IDs. It names no state.
 */
@Component
public class SchemeCatalogue {
    public static final Set<String> BENEFIT_TYPES = Set.of("ENTITLEMENT", "ASSISTANCE");
    public static final Set<String> BENEFIT_FORMS = Set.of("IN_KIND", "CASH");
    public static final Set<String> BENEFICIARY_LEVELS = Set.of("FAMILY", "RESIDENT");
    public static final Set<String> FREQUENCIES = Set.of("MONTHLY", "QUARTERLY", "HALF_YEARLY", "ANNUAL", "ONE_TIME");
    public static final Set<String> GENDERS = Set.of("MALE", "FEMALE", "OTHER", "UNKNOWN");

    public record Department(String code, String name) {
    }

    /** Declarative eligibility of a RESIDENT-level scheme (ADR-0012); every given criterion must hold. */
    public record Eligibility(Integer minAgeYears, Integer maxAgeYears, List<String> genders) {
    }

    public record Scheme(String schemeId, String name, String department, String benefitType, String benefitForm,
                         String beneficiaryLevel, String unit, String frequency, String exclusivityGroup,
                         Eligibility eligibility) {
        /** The group a resident should hold at most one active benefit of; the scheme itself unless configured. */
        public String group() {
            return exclusivityGroup == null || exclusivityGroup.isBlank() ? schemeId : exclusivityGroup;
        }
    }

    public record Definition(String catalogueVersion, List<Department> departments, List<Scheme> schemes) {
    }

    private final Definition definition;
    private final Map<String, Scheme> schemes = new LinkedHashMap<>();
    private final Map<String, Department> departments = new LinkedHashMap<>();

    @Autowired
    public SchemeCatalogue(StatePackProperties properties) {
        this(Path.of(properties.getPlatformDirectory(), "schemes", "catalogue.yaml"));
    }

    public SchemeCatalogue(Path file) {
        this(read(file.toAbsolutePath().normalize()));
    }

    public SchemeCatalogue(Definition definition) {
        this.definition = validate(definition);
        definition.departments().forEach(d -> departments.put(d.code(), d));
        definition.schemes().forEach(s -> schemes.put(s.schemeId(), s));
    }

    public String version() {
        return definition.catalogueVersion();
    }

    public Optional<Scheme> scheme(String schemeId) {
        return Optional.ofNullable(schemeId == null ? null : schemes.get(schemeId));
    }

    public List<Scheme> schemes() {
        return List.copyOf(schemes.values());
    }

    public Optional<Department> department(String code) {
        return Optional.ofNullable(code == null ? null : departments.get(code));
    }

    public List<Department> departments() {
        return List.copyOf(departments.values());
    }

    private static Definition read(Path file) {
        try {
            return new ObjectMapper(new YAMLFactory()).findAndRegisterModules()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(file.toFile(), Definition.class);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load scheme catalogue " + file + ": " + e.getMessage(), e);
        }
    }

    private static Definition validate(Definition d) {
        if (d == null || blank(d.catalogueVersion())) {
            throw invalid("catalogueVersion is required");
        }
        if (d.departments() == null || d.departments().isEmpty() || d.schemes() == null || d.schemes().isEmpty()) {
            throw invalid("departments and schemes are required");
        }
        Set<String> departmentCodes = new HashSet<>();
        for (Department department : d.departments()) {
            if (department == null || blank(department.code()) || blank(department.name())) {
                throw invalid("every department needs a code and a name");
            }
            if (!departmentCodes.add(department.code())) {
                throw invalid("duplicate department " + department.code());
            }
        }
        Set<String> ids = new HashSet<>();
        for (Scheme s : d.schemes()) {
            if (s == null || blank(s.schemeId()) || blank(s.name())) {
                throw invalid("every scheme needs a schemeId and a name");
            }
            String id = s.schemeId();
            if (!ids.add(id)) {
                throw invalid("duplicate scheme " + id);
            }
            if (!departmentCodes.contains(s.department())) {
                throw invalid("scheme " + id + " names unknown department " + s.department());
            }
            oneOf(id, "benefitType", s.benefitType(), BENEFIT_TYPES);
            oneOf(id, "benefitForm", s.benefitForm(), BENEFIT_FORMS);
            oneOf(id, "beneficiaryLevel", s.beneficiaryLevel(), BENEFICIARY_LEVELS);
            if (s.frequency() != null) {
                oneOf(id, "frequency", s.frequency(), FREQUENCIES);
            }
            Eligibility e = s.eligibility();
            if (e != null) {
                if (!"RESIDENT".equals(s.beneficiaryLevel())) {
                    throw invalid("scheme " + id + ": eligibility is supported for RESIDENT-level schemes only");
                }
                if (e.minAgeYears() == null && e.maxAgeYears() == null && (e.genders() == null || e.genders().isEmpty())) {
                    throw invalid("scheme " + id + ": eligibility declares no criterion");
                }
                if ((e.minAgeYears() != null && e.minAgeYears() < 0) || (e.maxAgeYears() != null && e.maxAgeYears() < 0)
                        || (e.minAgeYears() != null && e.maxAgeYears() != null && e.minAgeYears() > e.maxAgeYears())) {
                    throw invalid("scheme " + id + ": invalid eligibility age range");
                }
                if (e.genders() != null) {
                    e.genders().forEach(g -> oneOf(id, "eligibility.genders", g, GENDERS));
                }
            }
        }
        return d;
    }

    private static void oneOf(String scheme, String field, String value, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw invalid("scheme " + scheme + ": " + field + " must be one of "
                    + String.join(", ", new java.util.TreeSet<>(allowed)) + " but was " + value);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("Invalid scheme catalogue: " + message);
    }
}
