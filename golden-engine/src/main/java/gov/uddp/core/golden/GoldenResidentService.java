package gov.uddp.core.golden;

import gov.uddp.core.identity.IdentityPolicy;
import gov.uddp.core.identity.IdentityPolicyEvaluator;
import gov.uddp.core.identity.IdentityPolicyService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.golden.GoldenLifecycleStore.CurrentIdentity;
import gov.uddp.core.golden.GoldenLifecycleStore.Event;
import gov.uddp.core.golden.GoldenTargetGroups.CurrentMember;
import gov.uddp.core.golden.GoldenTargetGroups.Participant;
import gov.uddp.core.identity.IdentityPolicy;
import gov.uddp.core.identity.IdentityPolicyEvaluator;
import gov.uddp.core.identity.IdentityPolicyService;
import gov.uddp.core.statepack.StatePackLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds and reads stable Golden Residents (M11, docs/golden-lifecycle.md). Each build turns a completed matching
 * run into target groups, carries Golden IDs forward (ADR-0001), and writes a new version only for identities whose
 * members, values, provenance or policy changed, with lifecycle events for every change. With a
 * {@link GoldenFamilyBuilder} (M14) the Golden Families follow in the same transaction; with
 * {@link StewardshipConstraints} (M13) stewardship decisions shape the groups and new review tasks are created.
 */
@Service
public class GoldenResidentService implements gov.uddp.core.onboarding.GoldenBuildRunner {
    private static final int MAX_MERGE_CHAIN = 100;

    private final PostgresConnectionFactory connections;
    private final ObjectMapper json;
    private final StatePackLoader packs;
    private final PlatformSurvivorshipPolicyLoader platformPolicy;
    private final SurvivorshipEngine engine = new SurvivorshipEngine();
    private final GoldenLifecycleStore store;
    private GoldenFamilyBuilder families;
    private StewardshipConstraints stewardship;
    private IdentityPolicyService identityPolicies;
    private gov.uddp.core.identity.UddpIdService uddpIds;
    private DmnSurvivorshipRules dmnSurvivorshipRules;
    private org.springframework.context.ApplicationEventPublisher events;
    private gov.uddp.core.events.PlatformEvents platformEvents;

    @Autowired
    public GoldenResidentService(PostgresConnectionFactory connections, ObjectMapper json, StatePackLoader packs,
                                 PlatformSurvivorshipPolicyLoader platformPolicy) {
        this.connections = connections;
        this.json = json;
        this.packs = packs;
        this.platformPolicy = platformPolicy;
        this.store = new GoldenLifecycleStore(json);
    }

    /** Compatibility constructor for M1-M8 fixtures; application wiring uses the platform policy constructor. */
    public GoldenResidentService(PostgresConnectionFactory connections, ObjectMapper json, StatePackLoader packs) {
        this(connections, json, packs, null);
    }

    /** Enables the Golden Family build (M14) at the end of every Golden Resident build, in the same transaction. */
    @Autowired(required = false)
    public void setFamilyBuilder(GoldenFamilyBuilder families) {
        this.families = families;
    }

    /** Enables stewardship link constraints and review-task creation (M13, ADR-0010) in every build. */
    @Autowired(required = false)
    public void setStewardship(StewardshipConstraints stewardship) {
        this.stewardship = stewardship;
    }

    /** Optional A2 extension; absent or unconfigured DMN leaves legacy survivorship unchanged. */
    @Autowired(required = false)
    public void setDmnSurvivorshipRules(DmnSurvivorshipRules dmnSurvivorshipRules) {
        this.dmnSurvivorshipRules = dmnSurvivorshipRules;
    }

    /** M9 uses a platform-governed policy rather than State Pack conflict precedence. */
    public SurvivorshipPolicy policy() {
        return platformPolicy == null ? SurvivorshipPolicy.fromStatePacks(packs.all())
                : SurvivorshipPolicy.fromPlatform(platformPolicy.load());
    }

    /** Optional: announces every committed build (A5 recomputes scheme eligibility after it). */
    @Autowired(required = false)
    public void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    /** MSA Phase 2: with events enabled, a build publishes its matching run's events and its own changes. */
    @Autowired(required = false)
    public void setPlatformEvents(gov.uddp.core.events.PlatformEvents platformEvents) {
        this.platformEvents = platformEvents;
    }

    @Override
    public GoldenBuildSummary build(UUID run) {
        if (platformEvents != null) {
            platformEvents.matchRunCompleted(run);
        }
        GoldenBuildSummary summary = buildAndCommit(run);
        if (events != null) {
            events.publishEvent(new GoldenBuildCompletedEvent(run, summary));
        }
        return summary;
    }

    private GoldenBuildSummary buildAndCommit(UUID run) {
        var policy = policy();
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                store.lock(c);
                String mode = store.completedRunMode(c, run);
                var identities = store.currentIdentities(c);
                List<CurrentMember> current = new ArrayList<>();
                identities.values().forEach(identity -> identity.members().forEach((key, member) ->
                        current.add(new CurrentMember(identity.id(), member.canonicalId(), key))));
                List<GoldenTargetGroups.LinkConstraint> constraints = stewardship == null ? List.of()
                        : stewardship.effective(c);
                var participants = store.participants(c);
                var clusters = store.clusters(c, run);
                var grouping = GoldenTargetGroups.build(mode, participants, clusters, current, constraints);
                Map<String, Object> identitySummary = null;
                var identityPolicy = identityPolicies == null ? java.util.Optional.<IdentityPolicy>empty()
                        : identityPolicies.effective(c);
                if (identityPolicy.isPresent()) {
                    var evaluation = identityEvaluation(identityPolicy.get(), participants, grouping.groups());
                    var identityConstraints = identityConstraints(evaluation);
                    if (!identityConstraints.isEmpty()) {
                        List<GoldenTargetGroups.LinkConstraint> all = new ArrayList<>(identityConstraints);
                        all.addAll(constraints);
                        grouping = GoldenTargetGroups.build(mode, participants, clusters, current, all);
                    }
                    identitySummary = new LinkedHashMap<>();
                    identitySummary.put("deterministicMatches", evaluation.autoMatches().size());
                    identitySummary.put("conflictsForReview", evaluation.conflicts().size());
                    identitySummary.put("neverMerge", evaluation.neverMerge().size());
                    identitySummary.put("probabilisticOnly", evaluation.probabilisticOnly());
                }
                var groups = grouping.groups();
                var outcome = CarryForward.assign(
                        groups.stream().map(GoldenResidentService::keys).toList(),
                        identities.values().stream().map(CurrentIdentity::carryForward).toList());
                UUID build = UUID.randomUUID();
                store.startBuild(c, build, run, policy.version(), mode);
                var summary = apply(c, build, run, mode, policy, groups, identities, outcome);
                store.completeBuild(c, build, summary);
                if (identitySummary != null) {
                    recordSummary(c, build, "identity", identitySummary);
                }
                if (uddpIds != null) {
                    uddpIds.mintMissing(c, gov.uddp.core.identity.UddpIdService.Kind.RESIDENT);
                }
                if (stewardship != null) {
                    int tasks = stewardship.createReviewTasks(c, run, build);
                    recordStewardship(c, build, grouping, tasks);
                }
                if (families != null) {
                    families.build(c, build);
                    if (uddpIds != null) {
                        uddpIds.mintMissing(c, gov.uddp.core.identity.UddpIdService.Kind.FAMILY);
                    }
                }
                if (platformEvents != null) {
                    platformEvents.goldenBuildCommitting(c, build, summary);
                }
                c.commit();
                return summary;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot build golden Residents", e);
        }
    }

    /** Enables the Identity Policy ladder (Track E3); without an approved policy builds are unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setIdentityPolicies(IdentityPolicyService identityPolicies) {
        this.identityPolicies = identityPolicies;
    }

    /** Mints a UDDP ID for every new Golden Resident and Family (Track E4). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setUddpIds(gov.uddp.core.identity.UddpIdService uddpIds) {
        this.uddpIds = uddpIds;
    }

    /** The ladder over the latest canonicals; the pairs inside each automatic group are the candidates. */
    static IdentityPolicyEvaluator.Evaluation identityEvaluation(IdentityPolicy policy, List<Participant> participants,
                                                                 List<List<Participant>> groups) {
        var subjects = participants.stream().map(p -> IdentityPolicyEvaluator.Subject.of(p.key(),
                p.observation().stateCode(), p.observation().source(), p.observation().payload())).toList();
        List<IdentityPolicyEvaluator.Candidate> candidates = new ArrayList<>();
        for (List<Participant> group : groups) {
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    candidates.add(new IdentityPolicyEvaluator.Candidate(group.get(i).key(), group.get(j).key(), 1.0, true));
                }
            }
        }
        return IdentityPolicyEvaluator.evaluate(policy, subjects, candidates, false);
    }

    /**
     * The ladder as stewardship-style link constraints, ordered before every stewardship decision: deterministic
     * matches MUST_LINK, different values of a unique identifier CANNOT_LINK (which nothing can override).
     */
    static List<GoldenTargetGroups.LinkConstraint> identityConstraints(IdentityPolicyEvaluator.Evaluation evaluation) {
        List<GoldenTargetGroups.LinkConstraint> out = new ArrayList<>();
        evaluation.neverMerge().forEach(pair -> out.add(
                new GoldenTargetGroups.LinkConstraint(pair.keyA(), pair.keyB(), false, Long.MIN_VALUE + 1)));
        evaluation.autoMatches().forEach(pair -> out.add(
                new GoldenTargetGroups.LinkConstraint(pair.keyA(), pair.keyB(), true, Long.MIN_VALUE)));
        return out;
    }

    private void recordSummary(Connection c, UUID build, String name, Map<String, Object> detail) throws Exception {
        try (var s = c.prepareStatement("update golden_build set summary = summary || jsonb_build_object(?,"
                + " cast(? as jsonb)) where golden_build_id = ?")) {
            s.setString(1, name);
            s.setString(2, json.writeValueAsString(detail));
            s.setObject(3, build);
            s.executeUpdate();
        }
    }

    private void recordStewardship(Connection c, UUID build, GoldenTargetGroups.Result grouping, int tasks)
            throws Exception {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("constraintsInForce", grouping.constraintsInForce());
        detail.put("linksSkipped", grouping.linksSkipped());
        detail.put("reviewTasksCreated", tasks);
        try (var s = c.prepareStatement("update golden_build set summary = summary || jsonb_build_object('stewardship',"
                + " cast(? as jsonb)) where golden_build_id = ?")) {
            s.setString(1, json.writeValueAsString(detail));
            s.setObject(2, build);
            s.executeUpdate();
        }
    }

    /** What one target group becomes in this build. */
    private record Plan(UUID id, CurrentIdentity current, UUID splitFrom, List<Participant> members,
                        Map<String, GoldenField> fields, JsonNode payload, int version, List<String> reasons) {
        boolean isNew() {
            return current == null;
        }

        boolean changed() {
            return !reasons.isEmpty();
        }
    }

    private GoldenBuildSummary apply(Connection c, UUID build, UUID run, String mode, SurvivorshipPolicy policy,
                                     List<List<Participant>> groups, Map<UUID, CurrentIdentity> identities,
                                     CarryForward.Outcome outcome) throws Exception {
        List<Plan> plans = new ArrayList<>();
        Map<UUID, List<UUID>> absorbed = new LinkedHashMap<>();
        Map<UUID, UUID> mergedInto = new LinkedHashMap<>();
        for (int g = 0; g < groups.size(); g++) {
            UUID inherited = outcome.inherited().get(g);
            plans.add(plan(inherited == null ? UUID.randomUUID() : inherited,
                    inherited == null ? null : identities.get(inherited), outcome.splitFrom().get(g), groups.get(g), policy));
        }
        outcome.retiredInto().forEach((loser, group) -> {
            UUID survivor = group < 0 ? null : plans.get(group).id();
            mergedInto.put(loser, survivor);
            if (survivor != null) {
                absorbed.computeIfAbsent(survivor, x -> new ArrayList<>()).add(loser);
            }
        });
        Map<UUID, List<UUID>> splitsByOrigin = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            List<String> reasons = new ArrayList<>(plan.reasons());
            if (absorbed.containsKey(plan.id())) {
                addOnce(reasons, "MERGED");
            }
            if (plan.splitFrom() != null) {
                addOnce(reasons, "SPLIT");
                splitsByOrigin.computeIfAbsent(plan.splitFrom(), x -> new ArrayList<>()).add(plan.id());
            }
            plans.set(i, new Plan(plan.id(), plan.current(), plan.splitFrom(), plan.members(), plan.fields(),
                    plan.payload(), plan.version(), reasons));
        }
        for (int i = 0; i < plans.size(); i++) {
            Plan plan = plans.get(i);
            if (!plan.isNew() && splitsByOrigin.containsKey(plan.id())) {
                List<String> reasons = new ArrayList<>(plan.reasons());
                addOnce(reasons, "SPLIT");
                plans.set(i, new Plan(plan.id(), plan.current(), plan.splitFrom(), plan.members(), plan.fields(),
                        plan.payload(), plan.version(), reasons));
            }
        }

        // Close every membership that ends in this build before opening any, so a source record that moves between
        // identities never has two current memberships.
        for (Plan plan : plans) {
            if (plan.isNew() || !plan.changed()) {
                continue;
            }
            Set<String> keys = keys(plan.members());
            Map<String, UUID> canonicalByKey = canonicalByKey(plan.members());
            for (var entry : plan.current().members().entrySet()) {
                boolean stays = keys.contains(entry.getKey())
                        && canonicalByKey.get(entry.getKey()).equals(entry.getValue().canonicalId());
                if (!stays) {
                    store.closeMembership(c, entry.getValue().membershipId(), plan.version());
                }
            }
            store.closeCurrentVersion(c, plan.id());
        }
        for (UUID loser : mergedInto.keySet()) {
            var identity = identities.get(loser);
            for (var member : identity.members().values()) {
                store.closeMembership(c, member.membershipId(), identity.version() + 1);
            }
            store.closeCurrentVersion(c, loser);
        }

        List<Event> events = new ArrayList<>();
        int created = 0;
        int unchanged = 0;
        int versioned = 0;
        int singletons = 0;
        for (Plan plan : plans) {
            if (plan.members().size() == 1) {
                singletons++;
            }
            if (plan.isNew()) {
                created++;
                write(c, build, run, policy, plan, events);
            } else if (plan.changed()) {
                versioned++;
                write(c, build, run, policy, plan, events);
            } else {
                unchanged++;
            }
            for (UUID loser : absorbed.getOrDefault(plan.id(), List.of())) {
                events.add(Event.of(plan.id(), "MERGED", build, plan.version()).related(loser));
            }
            for (UUID side : splitsByOrigin.getOrDefault(plan.id(), List.of())) {
                events.add(Event.of(plan.id(), "SPLIT", build, plan.version()).related(side));
            }
        }
        for (var entry : mergedInto.entrySet()) {
            UUID loser = entry.getKey();
            var identity = identities.get(loser);
            store.retire(c, loser, entry.getValue());
            for (UUID side : splitsByOrigin.getOrDefault(loser, List.of())) {
                events.add(Event.of(loser, "SPLIT", build, identity.version()).related(side));
            }
            events.add(Event.of(loser, "RETIRED", build, identity.version()).related(entry.getValue()));
        }
        store.events(c, events);
        int considered = groups.stream().mapToInt(List::size).sum();
        int merged = (int) plans.stream().filter(plan -> absorbed.containsKey(plan.id())).count();
        int split = (int) plans.stream().filter(plan -> plan.isNew() && plan.splitFrom() != null).count();
        return new GoldenBuildSummary(run, considered, groups.size(), singletons, created, plans.size() - created,
                policy.version(), build, mode, unchanged, versioned, merged, split, mergedInto.size());
    }

    /** Survives the group and works out why (if at all) it needs a new version. */
    private Plan plan(UUID id, CurrentIdentity current, UUID splitFrom, List<Participant> members, SurvivorshipPolicy policy) {
        var observations = members.stream().map(Participant::observation).toList();
        var fields = engine.survive(observations, policy, dmnSurvivorshipRules,
                SurvivorshipPolicy.goldenStateFields(packs.all()));
        JsonNode payload = engine.payload(fields, observations, json);
        if (current == null) {
            return new Plan(id, null, splitFrom, members, fields, payload, 1, new ArrayList<>(List.of("CREATED")));
        }
        List<String> reasons = new ArrayList<>();
        Map<String, UUID> canonicalByKey = canonicalByKey(members);
        if (!canonicalByKey.keySet().equals(current.members().keySet())) {
            reasons.add("MEMBERSHIP_CHANGED");
        }
        boolean newerVersion = canonicalByKey.entrySet().stream().anyMatch(entry -> {
            var member = current.members().get(entry.getKey());
            return member != null && !member.canonicalId().equals(entry.getValue());
        });
        if (newerVersion) {
            reasons.add("MEMBER_VERSION_CHANGED");
        }
        if (!payload.equals(current.payload()) || !sameProvenance(fields, current)) {
            reasons.add("VALUES_CHANGED");
        }
        if (!policy.version().equals(current.policyVersion())) {
            reasons.add("POLICY_CHANGED");
        }
        return new Plan(id, current, splitFrom, members, fields, payload, current.version() + 1, reasons);
    }

    /** Writes a new identity or a new version of an existing one, its memberships and its member/value events. */
    private void write(Connection c, UUID build, UUID run, SurvivorshipPolicy policy, Plan plan, List<Event> events)
            throws Exception {
        String memberSetKey = memberSetKey(plan.members());
        if (plan.isNew()) {
            store.createIdentity(c, plan.id(), run, policy.version(), memberSetKey, plan.payload(), plan.splitFrom(),
                    plan.members().stream().map(Participant::observation).toList(), plan.fields());
            var createdEvent = Event.of(plan.id(), "CREATED", build, 1);
            events.add(plan.splitFrom() == null ? createdEvent : createdEvent.detail(Map.of("splitFromId", plan.splitFrom())));
            if (plan.splitFrom() != null) {
                events.add(Event.of(plan.id(), "SPLIT", build, 1).related(plan.splitFrom()));
            }
        }
        store.insertVersion(c, plan.id(), plan.version(), build, run, policy.version(), memberSetKey, plan.payload(),
                plan.reasons(), plan.fields());
        var current = plan.current();
        Set<String> keys = keys(plan.members());
        for (Participant member : plan.members()) {
            var previous = current == null ? null : current.members().get(member.key());
            UUID canonical = member.observation().id();
            if (previous != null && previous.canonicalId().equals(canonical)) {
                continue;
            }
            store.openMembership(c, plan.id(), canonical, member.key(), plan.version());
            if (previous == null) {
                events.add(Event.of(plan.id(), "MEMBER_ADDED", build, plan.version()).member(canonical, member.key()));
            } else {
                events.add(Event.of(plan.id(), "MEMBER_VERSION_CHANGED", build, plan.version())
                        .member(canonical, member.key())
                        .detail(Map.of("previousCanonicalResidentId", previous.canonicalId())));
            }
        }
        if (current == null) {
            return;
        }
        for (var entry : current.members().entrySet()) {
            if (!keys.contains(entry.getKey())) {
                events.add(Event.of(plan.id(), "MEMBER_REMOVED", build, plan.version())
                        .member(entry.getValue().canonicalId(), entry.getKey()));
            }
        }
        Set<String> paths = new java.util.LinkedHashSet<>(SurvivorshipEngine.PATHS);
        paths.addAll(plan.fields().keySet());
        paths.addAll(current.provenance().keySet());
        for (String path : paths) {
            JsonNode before = at(current.payload(), path);
            JsonNode after = at(plan.payload(), path);
            if (!Objects.equals(before, after)) {
                events.add(Event.of(plan.id(), "VALUE_CHANGED", build, plan.version()).value(path, before, after));
            }
        }
    }

    private boolean sameProvenance(Map<String, GoldenField> fields, CurrentIdentity current) {
        if (!fields.keySet().equals(current.provenance().keySet())) {
            return false;
        }
        for (var entry : fields.entrySet()) {
            var stored = current.provenance().get(entry.getKey());
            var field = entry.getValue();
            boolean same = stored.canonicalId().equals(field.selectedCanonicalId())
                    && stored.value().equals(field.value())
                    && stored.rule().equals(field.rule())
                    && stored.contributors().equals(field.contributors().stream().map(UUID::toString).toList());
            if (!same) {
                return false;
            }
        }
        return true;
    }

    public List<Map<String, Object>> list() {
        return list(null, null);
    }

    /** Active Golden Residents with their current version, optionally narrowed to that version's run and/or policy. */
    public List<Map<String, Object>> list(UUID matchingRunId, String policyVersion) {
        return list(matchingRunId, policyVersion, false);
    }

    /** As {@link #list(UUID, String)}; {@code includeRetired} adds retired identities with their last version. */
    public List<Map<String, Object>> list(UUID matchingRunId, String policyVersion, boolean includeRetired) {
        String sql = "select g.golden_resident_id, v.matching_run_id, v.survivorship_policy_version, v.golden_payload::text,"
                + " g.status, v.version_number, g.merged_into_id from golden_resident g"
                + " join lateral (select * from golden_resident_version x where x.golden_resident_id = g.golden_resident_id"
                + " order by x.version_number desc limit 1) v on true"
                + " where (g.status = 'ACTIVE' or ?)"
                + " and (cast(? as uuid) is null or v.matching_run_id = ?)"
                + " and (cast(? as text) is null or v.survivorship_policy_version = ?)"
                + " order by g.created_at, g.golden_resident_id";
        try (var c = connections.open(); var s = c.prepareStatement(sql)) {
            s.setBoolean(1, includeRetired);
            s.setObject(2, matchingRunId);
            s.setObject(3, matchingRunId);
            s.setString(4, policyVersion);
            s.setString(5, policyVersion);
            var r = s.executeQuery();
            var out = new ArrayList<Map<String, Object>>();
            while (r.next()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("goldenResidentId", r.getObject(1, UUID.class));
                row.put("matchingRunId", r.getObject(2, UUID.class));
                row.put("survivorshipPolicyVersion", r.getString(3));
                row.put("goldenPayload", json.readTree(r.getString(4)));
                row.put("status", r.getString(5));
                row.put("version", r.getInt(6));
                row.put("mergedIntoId", r.getObject(7, UUID.class));
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot list golden Residents", e);
        }
    }

    /**
     * The identity with its current version (payload, members, provenance) and a summary of all versions. A retired
     * identity returns its last version, {@code mergedIntoId} and the active identity its merge chain resolves to.
     */
    public Map<String, Object> detail(UUID id) {
        try (var c = connections.open()) {
            var base = identity(c, id);
            var versions = rows(c, "select version_number, golden_build_id, matching_run_id, survivorship_policy_version,"
                    + " change_reasons::text, effective_from, effective_to, golden_resident_version_id, golden_payload::text"
                    + " from golden_resident_version where golden_resident_id = ? order by version_number", id);
            if (versions.isEmpty()) {
                throw new IllegalStateException("Golden Resident has no version: " + id);
            }
            var latest = versions.getLast();
            boolean active = "ACTIVE".equals(base.get("status"));
            base.put("currentVersion", active ? latest.get("version_number") : null);
            base.put("latestVersion", latest.get("version_number"));
            base.put("matchingRunId", latest.get("matching_run_id"));
            base.put("survivorshipPolicyVersion", latest.get("survivorship_policy_version"));
            base.put("goldenPayload", json.readTree((String) latest.get("golden_payload")));
            base.put("members", rows(c, "select m.canonical_resident_id, c.state_code, c.source_system, m.source_record_key,"
                    + " m.effective_from from golden_resident_membership m"
                    + " join canonical_resident c on c.canonical_resident_id = m.canonical_resident_id"
                    + " where m.golden_resident_id = ? and m.effective_to is null order by m.canonical_resident_id", id));
            base.put("fieldProvenance", provenance(c, (UUID) latest.get("golden_resident_version_id")));
            List<Map<String, Object>> summary = new ArrayList<>();
            for (var version : versions) {
                var row = new LinkedHashMap<String, Object>();
                row.put("versionNumber", version.get("version_number"));
                row.put("effectiveFrom", version.get("effective_from"));
                row.put("effectiveTo", version.get("effective_to"));
                row.put("changeReasons", version.get("change_reasons"));
                row.put("matchingRunId", version.get("matching_run_id"));
                row.put("goldenBuildId", version.get("golden_build_id"));
                row.put("survivorshipPolicyVersion", version.get("survivorship_policy_version"));
                summary.add(row);
            }
            base.put("versions", summary);
            return base;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read golden Resident", e);
        }
    }

    /** Every version (payload, provenance, members) and every lifecycle event of the identity, oldest first. */
    public Map<String, Object> history(UUID id) {
        try (var c = connections.open()) {
            var base = identity(c, id);
            List<Map<String, Object>> versions = new ArrayList<>();
            for (var version : rows(c, "select golden_resident_version_id, version_number, golden_build_id, matching_run_id,"
                    + " survivorship_policy_version, change_reasons::text, effective_from, effective_to, golden_payload::text"
                    + " from golden_resident_version where golden_resident_id = ? order by version_number", id)) {
                var row = new LinkedHashMap<String, Object>();
                int number = (Integer) version.get("version_number");
                row.put("versionNumber", number);
                row.put("effectiveFrom", version.get("effective_from"));
                row.put("effectiveTo", version.get("effective_to"));
                row.put("changeReasons", version.get("change_reasons"));
                row.put("matchingRunId", version.get("matching_run_id"));
                row.put("goldenBuildId", version.get("golden_build_id"));
                row.put("survivorshipPolicyVersion", version.get("survivorship_policy_version"));
                row.put("goldenPayload", json.readTree((String) version.get("golden_payload")));
                row.put("fieldProvenance", provenance(c, (UUID) version.get("golden_resident_version_id")));
                row.put("members", members(c, id, number));
                versions.add(row);
            }
            base.put("versions", versions);
            base.put("events", rows(c, "select event_sequence, event_type, version_number, golden_build_id,"
                    + " related_golden_resident_id, canonical_resident_id, source_record_key, field_path, old_value::text,"
                    + " new_value::text, detail::text, occurred_at from golden_resident_event where golden_resident_id = ?"
                    + " order by event_sequence", id));
            return base;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read golden Resident history", e);
        }
    }

    /** Identity columns plus the active identity its merge chain resolves to; unknown IDs throw NoSuchElementException. */
    private Map<String, Object> identity(Connection c, UUID id) throws Exception {
        var base = new LinkedHashMap<String, Object>();
        try (var s = c.prepareStatement("select status, created_at, retired_at, merged_into_id, split_from_id"
                + " from golden_resident where golden_resident_id = ?")) {
            s.setObject(1, id);
            var r = s.executeQuery();
            if (!r.next()) {
                throw new NoSuchElementException("Golden Resident does not exist");
            }
            base.put("goldenResidentId", id);
            base.put("status", r.getString(1));
            base.put("createdAt", r.getObject(2));
            base.put("retiredAt", r.getObject(3));
            base.put("mergedIntoId", r.getObject(4, UUID.class));
            base.put("splitFromId", r.getObject(5, UUID.class));
        }
        base.put("resolvedGoldenResidentId", resolve(c, id));
        return base;
    }

    /** Follows merged_into pointers to the active identity (or the last retired one if the chain ends retired). */
    private UUID resolve(Connection c, UUID id) throws Exception {
        UUID current = id;
        for (int i = 0; i < MAX_MERGE_CHAIN; i++) {
            try (var s = c.prepareStatement("select status, merged_into_id from golden_resident where golden_resident_id = ?")) {
                s.setObject(1, current);
                var r = s.executeQuery();
                if (!r.next()) {
                    return current;
                }
                UUID next = r.getObject(2, UUID.class);
                if ("ACTIVE".equals(r.getString(1)) || next == null) {
                    return current;
                }
                current = next;
            }
        }
        throw new IllegalStateException("Golden Resident merge chain is too long or cyclic: " + id);
    }

    private List<Map<String, Object>> provenance(Connection c, UUID versionId) throws Exception {
        return rows(c, "select field_path, canonical_resident_id, selected_value::text, rule_applied,"
                + " survivorship_policy_version, contributing_canonical_ids::text from golden_resident_version_provenance"
                + " where golden_resident_version_id = ? order by field_path", versionId);
    }

    /** Members of one version: memberships added at or before it and not removed by it. */
    private List<Map<String, Object>> members(Connection c, UUID id, int version) throws Exception {
        try (var s = c.prepareStatement("select canonical_resident_id, source_record_key from golden_resident_membership"
                + " where golden_resident_id = ? and added_in_version <= ?"
                + " and (removed_in_version is null or removed_in_version > ?) order by canonical_resident_id")) {
            s.setObject(1, id);
            s.setInt(2, version);
            s.setInt(3, version);
            return rows(s);
        }
    }

    private List<Map<String, Object>> rows(Connection c, String sql, UUID id) throws Exception {
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, id);
            return rows(s);
        }
    }

    private List<Map<String, Object>> rows(java.sql.PreparedStatement s) throws Exception {
        var r = s.executeQuery();
        var out = new ArrayList<Map<String, Object>>();
        var md = r.getMetaData();
        while (r.next()) {
            var row = new LinkedHashMap<String, Object>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                Object value = r.getObject(i);
                String label = md.getColumnLabel(i);
                boolean jsonColumn = label.contains("value") || label.contains("ids") || label.equals("detail")
                        || label.equals("change_reasons");
                if (value instanceof String text && jsonColumn) {
                    value = json.readTree(text);
                }
                row.put(label, value);
            }
            out.add(row);
        }
        return out;
    }

    private static JsonNode at(JsonNode node, String path) {
        for (String part : path.split("\\.")) {
            node = node == null ? null : node.get(part);
        }
        return node;
    }

    private static void addOnce(List<String> list, String value) {
        if (!list.contains(value)) {
            list.add(value);
        }
    }

    private static Set<String> keys(List<Participant> members) {
        Set<String> keys = new LinkedHashSet<>();
        members.forEach(member -> keys.add(member.key()));
        return keys;
    }

    private static Map<String, UUID> canonicalByKey(List<Participant> members) {
        Map<String, UUID> out = new LinkedHashMap<>();
        members.forEach(member -> out.put(member.key(), member.observation().id()));
        return out;
    }

    private static String memberSetKey(List<Participant> members) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        String joined = members.stream().map(x -> x.observation().id().toString()).sorted().reduce("", String::concat);
        return HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
    }
}
