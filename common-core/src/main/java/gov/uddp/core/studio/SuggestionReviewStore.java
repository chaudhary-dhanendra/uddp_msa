package gov.uddp.core.studio;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.studio.StudioModels.Suggestions;
import org.springframework.stereotype.Component;

/**
 * Track D1: the suggestions of a draft that were not pre-accepted, kept until a person saves the draft's mappings so
 * the exception inbox can list them. Column names, targets and code values only; never row data.
 */
@Component
public class SuggestionReviewStore {
    private final PostgresConnectionFactory connections;

    public SuggestionReviewStore(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    /** Replaces the draft's open review items with the suggestions that are not accepted; returns how many. */
    public int record(String draftId, String packId, String stateCode, Suggestions suggestions) {
        String insert = "insert into studio_suggestion_review(draft_id,pack_id,state_code,kind,subject,target,confidence,basis)"
                + " values (?,?,?,?,?,?,?,?) on conflict do nothing";
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try {
                delete(c, draftId);
                int count = 0;
                try (var s = c.prepareStatement(insert)) {
                    for (var column : suggestions.columns()) {
                        for (var target : column.targets()) {
                            if (!target.accepted()) {
                                add(s, draftId, packId, stateCode, "MAPPING", column.column(),
                                        target.entity() + ":" + target.target(), target.confidence(), target.basis());
                                count++;
                            }
                        }
                    }
                    for (var crosswalk : suggestions.crosswalks()) {
                        for (var entry : crosswalk.entries()) {
                            if (!Boolean.TRUE.equals(entry.accepted())) {
                                add(s, draftId, packId, stateCode, "CROSSWALK", crosswalk.id() + ":" + entry.sourceValue(),
                                        entry.targetValue() == null ? "" : entry.targetValue(),
                                        entry.confidence() == null ? 0 : entry.confidence(), entry.basis());
                                count++;
                            }
                        }
                    }
                    s.executeBatch();
                }
                c.commit();
                return count;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot record suggestions to review", e);
        }
    }

    /** The draft's mappings were saved by a person: nothing is waiting any more. */
    public void clear(String draftId) {
        try (var c = connections.open()) {
            delete(c, draftId);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot clear suggestions to review", e);
        }
    }

    private static void delete(java.sql.Connection c, String draftId) throws java.sql.SQLException {
        try (var s = c.prepareStatement("delete from studio_suggestion_review where draft_id=?")) {
            s.setString(1, draftId);
            s.executeUpdate();
        }
    }

    private static void add(java.sql.PreparedStatement s, String draftId, String packId, String stateCode, String kind,
                            String subject, String target, double confidence, String basis) throws java.sql.SQLException {
        s.setString(1, draftId);
        s.setString(2, packId);
        s.setString(3, stateCode);
        s.setString(4, kind);
        s.setString(5, subject);
        s.setString(6, target);
        s.setDouble(7, Math.max(0, Math.min(confidence, 1)));
        s.setString(8, basis);
        s.addBatch();
    }
}
