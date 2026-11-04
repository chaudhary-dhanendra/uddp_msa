package gov.uddp.core.stewardship;

import gov.uddp.core.golden.GoldenTargetGroups.LinkConstraint;
import gov.uddp.core.golden.StewardshipConstraints;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Stewardship's part of every Golden build (M13, ADR-0010): reads the effective link constraints and creates review
 * tasks for the build's POSSIBLE_MATCH pairs. Matching evidence is only read; tasks are only inserted.
 */
@Component
public class StewardshipStore implements StewardshipConstraints {
    /** A pair's source record key: the canonical's version row, as the Golden build resolves it. */
    static final String KEY_OF = "coalesce((select v.source_record_key from canonical_resident_version v"
            + " where v.canonical_resident_id = %s), 'canonical:' || %s)";

    @Override
    public List<LinkConstraint> effective(Connection c) throws Exception {
        List<LinkConstraint> out = new ArrayList<>();
        try (var s = c.prepareStatement("select record_key_a, record_key_b, constraint_type, decision_sequence"
                + " from stewardship_link_constraint_effective order by decision_sequence, record_key_a, record_key_b");
                var r = s.executeQuery()) {
            while (r.next()) {
                out.add(new LinkConstraint(r.getString(1), r.getString(2), "MUST_LINK".equals(r.getString(3)),
                        r.getLong(4)));
            }
        }
        return out;
    }

    @Override
    public int createReviewTasks(Connection c, UUID matchingRun, UUID build) throws Exception {
        String keyA = String.format(KEY_OF, "p.canonical_record_a", "p.canonical_record_a");
        String keyB = String.format(KEY_OF, "p.canonical_record_b", "p.canonical_record_b");
        String sql = "insert into stewardship_review_task(review_task_id, record_key_a, record_key_b, matching_run_id,"
                + " canonical_record_a, canonical_record_b, match_probability, match_weight, golden_build_id)"
                + " select gen_random_uuid(), least(ka, kb), greatest(ka, kb), run_id,"
                + " case when ka < kb then canonical_record_a else canonical_record_b end,"
                + " case when ka < kb then canonical_record_b else canonical_record_a end,"
                + " match_probability, match_weight, ?"
                + " from (select p.*, " + keyA + " as ka, " + keyB + " as kb from resident_match_pair p"
                + " where p.run_id = ? and p.decision = 'POSSIBLE_MATCH') pairs"
                + " where ka <> kb order by least(ka, kb), greatest(ka, kb)"
                + " on conflict (record_key_a, record_key_b, task_type) do nothing";
        try (var s = c.prepareStatement(sql)) {
            s.setObject(1, build);
            s.setObject(2, matchingRun);
            return s.executeUpdate();
        }
    }
}
