package gov.uddp.core.rules;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.department.SourcePackService;
import gov.uddp.core.gates.MetricsSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The rules engine's contribution to monitoring (Track A): PENDING_APPROVALS counts rule sets and Scheme Pack
 * versions waiting for a checker that belong to the source's pack or to its department.
 */
@Component
public class PendingApprovalMetrics implements MetricsSource {
    private final PostgresConnectionFactory connections;
    private final SourcePackService packs;

    public PendingApprovalMetrics(PostgresConnectionFactory connections, SourcePackService packs) {
        this.connections = connections;
        this.packs = packs;
    }

    @Override
    public String name() {
        return "Rules and Scheme Packs";
    }

    @Override
    public Map<String, Double> metrics(String stateCode, String sourceSystem, UUID runId, UUID batchId) {
        List<SourcePackService.SourcePack> own = packs.all().stream()
                .filter(p -> p.sourceCode() != null && p.sourceCode().equalsIgnoreCase(stateCode))
                .toList();
        if (own.isEmpty()) {
            return Map.of();
        }
        String[] packIds = own.stream().map(SourcePackService.SourcePack::packId).toArray(String[]::new);
        String[] departments = own.stream().map(SourcePackService.SourcePack::departmentCode)
                .filter(java.util.Objects::nonNull).distinct().toArray(String[]::new);
        try (var c = connections.open();
                var s = c.prepareStatement("select (select count(*) from rule_set where status='PENDING_APPROVAL' and"
                        + " ((owner_type='SOURCE_PACK' and owner_id=any(?)) or (owner_type='DEPARTMENT' and"
                        + " owner_id=any(?)) or (owner_type='SCHEME' and owner_id in (select scheme_id from scheme_pack"
                        + " where owning_department_code=any(?))))) + (select count(*) from scheme_pack_version v join"
                        + " scheme_pack p on p.scheme_id=v.scheme_id where v.status='PENDING_APPROVAL' and"
                        + " p.owning_department_code=any(?))")) {
            var packArray = c.createArrayOf("text", packIds);
            var departmentArray = c.createArrayOf("text", departments);
            s.setArray(1, packArray);
            s.setArray(2, departmentArray);
            s.setArray(3, departmentArray);
            s.setArray(4, departmentArray);
            var r = s.executeQuery();
            r.next();
            return Map.of("PENDING_APPROVALS", r.getDouble(1));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot count the pending approvals", e);
        }
    }
}
