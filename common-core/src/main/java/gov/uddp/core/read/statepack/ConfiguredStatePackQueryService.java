package gov.uddp.core.read.statepack;

import gov.uddp.core.read.Db;
import gov.uddp.core.read.statepack.StatePackModels.CrosswalkEntry;
import gov.uddp.core.read.statepack.StatePackModels.CrosswalkView;
import gov.uddp.core.read.statepack.StatePackModels.FieldPriority;
import gov.uddp.core.read.statepack.StatePackModels.MappingRuleView;
import gov.uddp.core.read.statepack.StatePackModels.MappingView;
import gov.uddp.core.read.statepack.StatePackModels.MatchingView;
import gov.uddp.core.read.statepack.StatePackModels.PackSourceView;
import gov.uddp.core.read.statepack.StatePackModels.PackVersionView;
import gov.uddp.core.read.statepack.StatePackModels.StatePackListItem;
import gov.uddp.core.read.statepack.StatePackModels.StatePackView;
import gov.uddp.core.read.statepack.StatePackModels.SurvivorshipView;
import gov.uddp.core.read.statepack.StatePackModels.ValidationRuleView;
import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackLoader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The M12 {@link StatePackQueryService}: pack content from the packs loaded from the State Pack directory, lifecycle
 * status and registered versions from {@code state_pack_lifecycle}. Read-only.
 */
@Service
public class ConfiguredStatePackQueryService implements StatePackQueryService {
    static final String UNREGISTERED = "UNREGISTERED";

    private final StatePackLoader packs;
    private final Db db;

    public ConfiguredStatePackQueryService(StatePackLoader packs, Db db) {
        this.packs = packs;
        this.db = db;
    }

    @Override
    public List<StatePackListItem> list() {
        return packs.all().stream()
                // Repository packs are fixtures.  A pack becomes an operational state only after the
                // lifecycle control plane has recorded and activated it in PostgreSQL.
                .filter(pack -> "ACTIVE".equals(lifecycleStatus(pack)))
                .sorted(Comparator.comparing(pack -> pack.manifest().packId()))
                .map(pack -> {
                    var manifest = pack.manifest();
                    var sourceIds = pack.sources().stream().map(source -> source.sourceId()).toList();
                    return new StatePackListItem(manifest.packId(), manifest.stateCode(), manifest.version(),
                            manifest.commonModelVersion(), manifest.status(), lifecycleStatus(pack),
                            sourceIds.size(), sourceIds, entities(pack));
                })
                .toList();
    }

    @Override
    public Optional<StatePackView> find(String packIdOrStateCode) {
        if (packIdOrStateCode == null || packIdOrStateCode.isBlank()) {
            return Optional.empty();
        }
        return packs.find(packIdOrStateCode.strip())
                .filter(pack -> "ACTIVE".equals(lifecycleStatus(pack)))
                .map(this::view);
    }

    @Override
    public Optional<String> stateCode(String packIdOrStateCode) {
        if (packIdOrStateCode == null || packIdOrStateCode.isBlank()) {
            return Optional.empty();
        }
        return find(packIdOrStateCode.strip()).map(StatePackView::stateCode);
    }

    private StatePackView view(LoadedStatePack pack) {
        var manifest = pack.manifest();
        var sources = pack.sources().stream()
                .map(s -> new PackSourceView(s.sourceId(), s.sourceType(), s.hopTemplate(), s.connectionRef(),
                        s.location(), s.recordIdField(), s.keyFields(), s.sourceSchemaVersion()))
                .toList();
        List<MappingView> mappings = new ArrayList<>();
        for (var mapping : pack.mappings()) {
            var entities = mapping.entities() == null ? Map.<String, List<gov.uddp.core.statepack.StatePackAssets.MappingRule>>of()
                    : mapping.entities();
            entities.forEach((entity, rules) -> mappings.add(new MappingView(mapping.mappingVersion(),
                    mapping.sourceId(), entity, (rules == null ? List.<gov.uddp.core.statepack.StatePackAssets.MappingRule>of() : rules)
                    .stream()
                    .map(rule -> new MappingRuleView(rule.sourceField(), rule.target(), rule.conversion(),
                            rule.crosswalk(), Boolean.TRUE.equals(rule.required())))
                    .toList())));
        }
        var crosswalks = pack.crosswalks().values().stream()
                .sorted(Comparator.comparing(c -> c.id()))
                .map(c -> new CrosswalkView(c.id(), (c.values() == null ? Map.<String, String>of() : c.values())
                        .entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(entry -> new CrosswalkEntry(entry.getKey(), entry.getValue()))
                        .toList()))
                .toList();
        List<ValidationRuleView> rules = new ArrayList<>();
        for (var definition : pack.validations()) {
            for (var rule : definition.rules() == null ? List.<gov.uddp.core.statepack.StatePackAssets.ValidationRule>of()
                    : definition.rules()) {
                rules.add(new ValidationRuleView(definition.entity(), rule.id(), rule.type(), rule.field(), rule.fields(),
                        Boolean.TRUE.equals(rule.required()), rule.allowedValues(), rule.pattern(), rule.min(), rule.max(),
                        rule.minAgeYears(), rule.maxAgeYears(), rule.referenceSet(), rule.operator(), rule.severity()));
            }
        }
        var matching = pack.matching().stream()
                .map(m -> new MatchingView(m.entity(), m.fields(),
                        m.thresholds() == null ? null : m.thresholds().autoMatch(),
                        m.thresholds() == null ? null : m.thresholds().possibleMatch()))
                .toList();
        var survivorship = pack.survivorship().stream()
                .map(s -> new SurvivorshipView(s.entity(), (s.fieldPriority() == null ? Map.<String, List<String>>of()
                        : s.fieldPriority()).entrySet().stream()
                        .map(entry -> new FieldPriority(entry.getKey(), entry.getValue()))
                        .toList()))
                .toList();
        return new StatePackView(manifest.packId(), manifest.stateCode(), manifest.version(),
                manifest.commonModelVersion(), manifest.status(), lifecycleStatus(pack), sources, mappings, crosswalks,
                rules, matching, survivorship, versions(pack),
                manifest.extensions() == null ? List.of() : manifest.extensions());
    }

    private List<PackVersionView> versions(LoadedStatePack pack) {
        String loaded = pack.manifest().version();
        List<PackVersionView> versions = new ArrayList<>(db.list("select version, status, created_at, validated_at,"
                        + " activated_at, retired_at from state_pack_lifecycle where pack_id = ? order by created_at, version",
                r -> new PackVersionView(r.getString("version"), r.getString("status"),
                        loaded.equals(r.getString("version")), Db.time(r, "created_at"), Db.time(r, "validated_at"),
                        Db.time(r, "activated_at"), Db.time(r, "retired_at")),
                pack.manifest().packId()));
        if (versions.stream().noneMatch(PackVersionView::loaded)) {
            versions.add(new PackVersionView(loaded, UNREGISTERED, true, null, null, null, null));
        }
        return versions;
    }

    private String lifecycleStatus(LoadedStatePack pack) {
        return db.one("select status from state_pack_lifecycle where pack_id = ? and version = ?",
                        r -> r.getString(1), pack.manifest().packId(), pack.manifest().version())
                .orElse(UNREGISTERED);
    }

    private static List<String> entities(LoadedStatePack pack) {
        var entities = new LinkedHashSet<String>();
        pack.mappings().forEach(mapping -> {
            if (mapping.entities() != null) {
                entities.addAll(mapping.entities().keySet());
            }
        });
        return List.copyOf(entities);
    }
}
