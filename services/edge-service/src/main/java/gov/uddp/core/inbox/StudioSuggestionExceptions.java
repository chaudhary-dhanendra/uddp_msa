package gov.uddp.core.inbox;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.util.List;
import org.springframework.stereotype.Component;

/** Track D1: Studio drafts with mapping or crosswalk suggestions below the pre-accept confidence. */
@Component
public class StudioSuggestionExceptions implements ExceptionSource {
    private final PostgresConnectionFactory connections;

    public StudioSuggestionExceptions(PostgresConnectionFactory connections) {
        this.connections = connections;
    }

    @Override
    public List<String> types() {
        return List.of("MAPPING_SUGGESTION");
    }

    @Override
    public List<Item> items() {
        return PlatformExceptionSources.query(connections, "select draft_id,pack_id,state_code,count(*),"
                        + "count(*) filter (where kind='CROSSWALK'),max(recorded_at) from studio_suggestion_review"
                        + " group by draft_id,pack_id,state_code order by max(recorded_at) desc limit 200",
                r -> new Item("MAPPING_SUGGESTION:" + r.getString(1), "MAPPING_SUGGESTION",
                        r.getInt(4) + " suggestion(s) to review in Studio draft " + r.getString(2),
                        (r.getInt(4) - r.getInt(5)) + " mapping(s) and " + r.getInt(5)
                                + " crosswalk value(s) below the pre-accept confidence",
                        "LOW", r.getString(3), r.getString(2), PlatformExceptionSources.at(r, 6),
                        "/onboarding-studio/drafts/" + r.getString(1) + "/3"));
    }
}
