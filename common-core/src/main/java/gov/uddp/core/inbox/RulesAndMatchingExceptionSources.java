package gov.uddp.core.inbox;

import static gov.uddp.core.inbox.PlatformExceptionSources.at;
import static gov.uddp.core.inbox.PlatformExceptionSources.query;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.department.SourcePackService;
import gov.uddp.core.inbox.ExceptionSource.Item;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The exception sources of Track A (rule drafts, rule, eligibility and Scheme Pack approvals) and Track B (uncertain
 * matches, matching drift, matching approvals). Items scoped to a configuration owner carry a source code of the
 * owning department so department-scoped users see them; matching-wide items carry none and need ALL scope.
 */
public final class RulesAndMatchingExceptionSources {
    private RulesAndMatchingExceptionSources() {
    }

    /** Rule sets still in DRAFT, and those submitted for a checker (eligibility rules reported on their own). */
    @Component
    public static class RuleSets implements ExceptionSource {
        private final PostgresConnectionFactory connections;
        private final SourcePackService packs;

        public RuleSets(PostgresConnectionFactory connections, SourcePackService packs) {
            this.connections = connections;
            this.packs = packs;
        }

        @Override
        public List<String> types() {
            return List.of("RULE_DRAFT", "RULE_APPROVAL", "ELIGIBILITY_APPROVAL");
        }

        @Override
        public List<Item> items() {
            List<Item> items = new ArrayList<>();
            try (var c = connections.open();
                    var s = c.prepareStatement("select rule_set_id,rule_type,owner_type,owner_id,version,status,"
                            + "coalesce(submitted_at,created_at),coalesce(submitted_by,created_by,'') from rule_set"
                            + " where status in ('DRAFT','PENDING_APPROVAL') order by created_at desc limit 200")) {
                var r = s.executeQuery();
                while (r.next()) {
                    boolean pending = "PENDING_APPROVAL".equals(r.getString(6));
                    String type = !pending ? "RULE_DRAFT"
                            : "ELIGIBILITY".equals(r.getString(2)) ? "ELIGIBILITY_APPROVAL" : "RULE_APPROVAL";
                    String ruleType = r.getString(2).toLowerCase(Locale.ROOT).replace('_', ' ');
                    items.add(new Item(type + ":" + r.getString(1), type,
                            (pending ? "Rule set awaits approval: " : "Rule set draft not submitted: ") + ruleType
                                    + " v" + r.getInt(5) + " for " + r.getString(4),
                            (pending ? "Submitted by " : "Created by ") + r.getString(8),
                            pending ? "MEDIUM" : "LOW", scopeCode(r.getString(3), r.getString(4)), null, at(r, 7),
                            "/rules"));
                }
                return items;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read the rule set items", e);
            }
        }

        /** A source code of the owner's department, so department scope applies; null for an unknown owner. */
        private String scopeCode(String ownerType, String ownerId) {
            return switch (ownerType) {
                case "SOURCE_PACK" -> packs.find(ownerId).map(SourcePackService.SourcePack::sourceCode).orElse(null);
                case "DEPARTMENT" -> departmentCode(packs, ownerId);
                case "SCHEME" -> departmentCode(packs, schemeDepartment(connections, ownerId));
                default -> null;
            };
        }
    }

    /** Scheme Pack versions submitted for a checker. */
    @Component
    public static class SchemePacks implements ExceptionSource {
        private final PostgresConnectionFactory connections;
        private final SourcePackService packs;

        public SchemePacks(PostgresConnectionFactory connections, SourcePackService packs) {
            this.connections = connections;
            this.packs = packs;
        }

        @Override
        public List<String> types() {
            return List.of("SCHEME_APPROVAL");
        }

        @Override
        public List<Item> items() {
            return query(connections, "select v.scheme_pack_version_id,v.scheme_id,v.version,p.owning_department_code,"
                    + "coalesce(v.submitted_at,v.created_at),coalesce(v.submitted_by,'') from scheme_pack_version v"
                    + " join scheme_pack p on p.scheme_id=v.scheme_id where v.status='PENDING_APPROVAL'"
                    + " order by v.submitted_at desc",
                    r -> new Item("SCHEME_APPROVAL:" + r.getString(1), "SCHEME_APPROVAL",
                            "Scheme Pack " + r.getString(2) + " v" + r.getInt(3) + " awaits approval",
                            "Submitted by " + r.getString(6), "MEDIUM", departmentCode(packs, r.getString(4)), null,
                            at(r, 5), "/scheme-studio/" + r.getString(2) + "/" + r.getString(1)));
        }
    }

    /** Pairs a steward labelled UNSURE: the matching model cannot be calibrated on them until someone decides. */
    @Component
    public static class UncertainMatches implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public UncertainMatches(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("UNCERTAIN_MATCH");
        }

        @Override
        public List<Item> items() {
            return query(connections, "select l.canonical_record_a,l.canonical_record_b,c.state_code,c.source_system,"
                    + "l.match_probability,l.labelled_at,coalesce(l.run_id::text,'') from matching_label l join"
                    + " canonical_resident c on c.canonical_resident_id=l.canonical_record_a where l.label='UNSURE'"
                    + " order by l.labelled_at desc limit 200",
                    r -> new Item("UNCERTAIN_MATCH:" + r.getString(1) + ":" + r.getString(2), "UNCERTAIN_MATCH",
                            "Pair labelled unsure", "Match probability "
                                    + String.format(Locale.ROOT, "%.2f", r.getDouble(5)), "LOW", r.getString(3),
                            r.getString(4), at(r, 6), r.getString(7).isEmpty() ? "/matching-studio"
                                    : "/matching-studio/why/" + r.getString(7) + "/" + r.getString(1) + "/"
                                    + r.getString(2)));
        }
    }

    /** Open matching monitoring alerts (out of bounds or drift) and matching changes waiting for a checker. */
    @Component
    public static class MatchingAlerts implements ExceptionSource {
        private final PostgresConnectionFactory connections;

        public MatchingAlerts(PostgresConnectionFactory connections) {
            this.connections = connections;
        }

        @Override
        public List<String> types() {
            return List.of("MATCHING_DRIFT", "MATCHING_APPROVAL");
        }

        @Override
        public List<Item> items() {
            List<Item> items = query(connections, "select alert_id,kind,metric,message,created_at from matching_alert"
                    + " where status='OPEN' order by created_at desc limit 200",
                    r -> new Item("MATCHING_DRIFT:" + r.getString(1), "MATCHING_DRIFT",
                            "Matching " + ("DRIFT".equals(r.getString(2)) ? "drift" : "metric out of bounds") + ": "
                                    + r.getString(3), r.getString(4), "DRIFT".equals(r.getString(2)) ? "HIGH" : "MEDIUM",
                            null, null, at(r, 5), "/matching-studio"));
            items.addAll(query(connections, "select profile_id,name,version,coalesce(submitted_by,''),created_at"
                    + " from matching_profile where status='PENDING_APPROVAL' order by created_at desc",
                    r -> new Item("MATCHING_APPROVAL:" + r.getString(1), "MATCHING_APPROVAL",
                            "Matching profile " + r.getString(2) + " v" + r.getInt(3) + " awaits approval",
                            "Submitted by " + r.getString(4), "MEDIUM", null, null, at(r, 5), "/matching-studio")));
            items.addAll(query(connections, "select proposal_id,kind,current_value,proposed_value,proposed_by,created_at"
                    + " from matching_threshold_proposal where status='PENDING_APPROVAL' order by created_at desc",
                    r -> new Item("MATCHING_APPROVAL:" + r.getString(1), "MATCHING_APPROVAL",
                            "Threshold change awaits approval: " + r.getString(2), "From " + r.getString(3) + " to "
                                    + r.getString(4) + ", proposed by " + r.getString(5), "MEDIUM", null, null,
                            at(r, 6), "/matching-studio")));
            return items;
        }
    }

    private static String departmentCode(SourcePackService packs, String departmentCode) {
        if (departmentCode == null) {
            return null;
        }
        return packs.department(departmentCode).flatMap(d -> d.sourcePacks().stream()
                .map(SourcePackService.SourcePack::sourceCode).sorted().findFirst()).orElse(null);
    }

    private static String schemeDepartment(PostgresConnectionFactory connections, String schemeId) {
        try (var c = connections.open();
                var s = c.prepareStatement("select owning_department_code from scheme_pack where scheme_id=?")) {
            s.setString(1, schemeId);
            var r = s.executeQuery();
            return r.next() ? r.getString(1) : null;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the Scheme Pack owner", e);
        }
    }
}
