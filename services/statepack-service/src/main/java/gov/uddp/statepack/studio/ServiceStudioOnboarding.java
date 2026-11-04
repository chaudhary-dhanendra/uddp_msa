package gov.uddp.statepack.studio;

import gov.uddp.core.studio.StudioConflictException;
import gov.uddp.core.studio.StudioModels;
import gov.uddp.core.studio.StudioOnboarding;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Onboarding runs where the Studio is hosted by statepack-service: the edge starts them and reports each run's status
 * here (studio_run), which the Studio's state list shows. Nothing runs here.
 */
@Component
public class ServiceStudioOnboarding implements StudioOnboarding {

    private final JdbcTemplate jdbc;

    public ServiceStudioOnboarding(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public String runStatus(String runId) {
        return jdbc.query("select status from studio_run where run_id = ?",
                r -> r.next() ? r.getString(1) : null, runId);
    }

    @Override
    public boolean inProgress() {
        return false;
    }

    @Override
    public boolean goldenDataExists() {
        return false;
    }

    @Override
    public StudioModels.OnboardStarted start(String packId, String version, String sourceId, String requestedMode) {
        throw new StudioConflictException("Onboarding runs are started by the edge application");
    }

    @Override
    public void runInBackground(String runId, Consumer<String> finished) {
        throw new StudioConflictException("Onboarding runs are started by the edge application");
    }

    @Override
    public void recorded(String draftId, String runId, String status) {
        jdbc.update("insert into studio_run (run_id, draft_id, status) values (?, ?, ?) on conflict (run_id) do update"
                + " set status = excluded.status, updated_at = now()", runId, draftId, status);
    }
}
