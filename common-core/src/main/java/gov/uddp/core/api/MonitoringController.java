package gov.uddp.core.api;

import gov.uddp.core.monitoring.AlertService;
import gov.uddp.core.monitoring.AlertService.Rule;
import gov.uddp.core.monitoring.MonitoringService;
import gov.uddp.core.onboarding.OnboardingNotFoundException;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Monitoring screen, alert rules, header notifications and the webhook setting (Track C6). */
@RestController
@RequestMapping("/api/monitoring")
@Tag(name = "Monitoring")
public class MonitoringController {
    private final MonitoringService monitoring;
    private final AlertService alerts;

    public MonitoringController(MonitoringService monitoring, AlertService alerts) {
        this.monitoring = monitoring;
        this.alerts = alerts;
    }

    public record WebhookRequest(String url, String signingKey) {
    }

    public record WebhookStatus(boolean configured) {
    }

    /** One row per source with the monitored metrics, filtered by department and source. */
    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public List<Map<String, Object>> overview(@RequestParam(required = false) String department,
                                              @RequestParam(required = false) String source) {
        return monitoring.overview(department, source, state -> CurrentUser.scope().allows(state));
    }

    @GetMapping("/sources/{state}/{source}/trend")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public Map<String, Object> trend(@PathVariable String state, @PathVariable String source,
                                     @RequestParam(defaultValue = "30") int batches) {
        if (!CurrentUser.scope().allows(state)) {
            throw new OnboardingNotFoundException("Unknown source: " + state + "/" + source);
        }
        return Map.of("batches", monitoring.batchTrend(state, source, batches),
                "rules", monitoring.ruleTrend(state, source, batches * 20));
    }

    @GetMapping("/notifications")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public List<Map<String, Object>> notifications(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                                   @RequestParam(defaultValue = "50") int limit) {
        return alerts.notifications(unreadOnly, limit, state -> CurrentUser.scope().allows(state));
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    @Audited(action = "ALERT_READ", resource = "ALERT_NOTIFICATION", idVariable = "id")
    public void markRead(@PathVariable UUID id) {
        alerts.markRead(id);
    }

    @GetMapping("/alert-rules")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_VIEW')")
    public List<Rule> rules() {
        return alerts.rules();
    }

    @PutMapping("/alert-rules")
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ALERT_RULE_SAVE", resource = "ALERT_RULE")
    public Rule saveRule(@RequestBody Rule rule) {
        return alerts.saveRule(rule);
    }

    @DeleteMapping("/alert-rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PERM_ONBOARDING_RUN')")
    @Audited(action = "ALERT_RULE_DELETE", resource = "ALERT_RULE", idVariable = "id")
    public void deleteRule(@PathVariable UUID id) {
        alerts.deleteRule(id);
    }

    @GetMapping("/webhook")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    public WebhookStatus webhook() {
        return new WebhookStatus(alerts.webhookConfigured());
    }

    /** The URL and signing key are write-only and stored encrypted; nothing here echoes them back. */
    @PutMapping("/webhook")
    @PreAuthorize("hasAuthority('PERM_PLATFORM_SETTINGS')")
    @Audited(action = "ALERT_WEBHOOK_SAVE", resource = "ALERT_WEBHOOK")
    public WebhookStatus saveWebhook(@RequestBody WebhookRequest request) {
        alerts.saveWebhook(request.url(), request.signingKey());
        return new WebhookStatus(true);
    }
}
