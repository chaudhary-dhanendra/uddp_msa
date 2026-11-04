package gov.uddp.core.studio;

import gov.uddp.core.statepack.LoadedStatePack;
import gov.uddp.core.statepack.StatePackAssets.MappingRule;
import gov.uddp.core.testsupport.SampleData;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Compares a Studio-generated pack with a hand-written pack, field by field, as a Markdown list of differences. */
final class PackComparison {
    private PackComparison() {
    }

    static String compare(Path generatedDirectory, Path referenceDirectory) {
        var loader = SampleData.loader(referenceDirectory.getParent());
        return compare(loader.load(generatedDirectory), loader.load(referenceDirectory));
    }

    static String compare(LoadedStatePack generated, Path referenceDirectory) {
        return compare(generated, SampleData.loader(referenceDirectory.getParent()).load(referenceDirectory));
    }

    private static String compare(LoadedStatePack generated, LoadedStatePack reference) {
        List<String> differences = new ArrayList<>();

        var gs = generated.sources().getFirst();
        var rs = reference.sources().getFirst();
        same(differences, "sourceId", gs.sourceId(), rs.sourceId());
        same(differences, "hopTemplate", gs.hopTemplate(), rs.hopTemplate());
        same(differences, "recordIdField", gs.recordIdField(), rs.recordIdField());
        same(differences, "record key", gs.keyFields(), rs.keyFields());
        same(differences, "location", gs.location(), rs.location());

        Map<String, String> generatedRules = rules(generated);
        Map<String, String> referenceRules = rules(reference);
        TreeSet<String> keys = new TreeSet<>(generatedRules.keySet());
        keys.addAll(referenceRules.keySet());
        for (String key : keys) {
            String g = generatedRules.get(key);
            String r = referenceRules.get(key);
            if (g == null) {
                differences.add("mapping " + key + " " + r + ": only in the hand-written pack");
            } else if (r == null) {
                differences.add("mapping " + key + " " + g + ": only in the Studio pack");
            } else if (!g.equals(r)) {
                differences.add("mapping " + key + ": Studio " + g + ", hand-written " + r);
            }
        }

        TreeSet<String> crosswalks = new TreeSet<>(generated.crosswalks().keySet());
        crosswalks.addAll(reference.crosswalks().keySet());
        for (String id : crosswalks) {
            var g = generated.crosswalks().get(id);
            var r = reference.crosswalks().get(id);
            same(differences, "crosswalk " + id, g == null ? null : new TreeMap<>(g.values()),
                    r == null ? null : new TreeMap<>(r.values()));
        }
        same(differences, "validation rules", validation(generated), validation(reference));
        same(differences, "matching", generated.matching().getFirst().toString(), reference.matching().getFirst().toString());
        same(differences, "survivorship", generated.survivorship().getFirst().fieldPriority(),
                reference.survivorship().getFirst().fieldPriority());

        StringBuilder out = new StringBuilder();
        if (differences.isEmpty()) {
            out.append("- No differences.\n");
        }
        differences.forEach(d -> out.append("- ").append(d).append('\n'));
        return out.toString();
    }

    /** entity:sourceField->target to its conversion and crosswalk (Resident extensions included). */
    private static Map<String, String> rules(LoadedStatePack pack) {
        Map<String, String> rules = new TreeMap<>();
        pack.mappings().getFirst().entities().forEach((entity, list) -> {
            for (MappingRule rule : list) {
                rules.put(entity + ":" + rule.sourceField() + "->" + rule.target(),
                        "[" + (rule.conversion() == null ? "" : rule.conversion()) + "|"
                                + (rule.crosswalk() == null ? "" : rule.crosswalk()) + "]");
            }
        });
        return rules;
    }

    private static List<String> validation(LoadedStatePack pack) {
        List<String> rules = new ArrayList<>();
        pack.validations().forEach(v -> v.rules().forEach(r -> rules.add(r.field() + "/" + (r.type() == null ? (
                Boolean.TRUE.equals(r.required()) ? "REQUIRED" : "ALLOWED_VALUES") : r.type()) + "/" + r.severity())));
        rules.sort(String::compareTo);
        return rules;
    }

    private static void same(List<String> differences, String label, Object generated, Object reference) {
        if (!Objects.equals(generated, reference)) {
            differences.add(label + ": Studio " + generated + ", hand-written " + reference);
        }
    }
}
