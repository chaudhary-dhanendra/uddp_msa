package gov.uddp.core.validation;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.mapping.RawRecord;
import java.util.UUID;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Repository;

@Repository
public class DqRepository {
    private final PostgresConnectionFactory connections;
    public DqRepository(PostgresConnectionFactory connections) { this.connections = connections; }
    public void persistReferenceData(Map<String, Set<String>> datasets) {
        for (var entry : datasets.entrySet()) {
            try (var c=connections.open(); var dataset=c.prepareStatement("insert into reference_dataset(reference_set,version,description) values(?,'1.0.0','Platform reference data') on conflict(reference_set,version) do update set reference_set=excluded.reference_set returning reference_dataset_id")) {
                dataset.setString(1,entry.getKey()); var r=dataset.executeQuery();r.next();UUID id=r.getObject(1,UUID.class);
                try(var value=c.prepareStatement("insert into reference_value(reference_dataset_id,code) values(?,?) on conflict(reference_dataset_id,code) do nothing")){for(String code:entry.getValue()){value.setObject(1,id);value.setString(2,code);value.addBatch();}value.executeBatch();}
            } catch(Exception e){throw new IllegalStateException("Cannot persist platform reference data",e);}
        }
    }
    /** Returns the values actually persisted for evaluation, rather than retaining a parallel hard-coded list. */
    public Map<String, Set<String>> referenceValues() {
        Map<String, Set<String>> values = new LinkedHashMap<>();
        String sql = "select d.reference_set,v.code from reference_dataset d join reference_value v on v.reference_dataset_id=d.reference_dataset_id order by d.reference_set,v.code";
        try (var c = connections.open(); var s = c.prepareStatement(sql); var r = s.executeQuery()) {
            while (r.next()) values.computeIfAbsent(r.getString(1), ignored -> new java.util.LinkedHashSet<>()).add(r.getString(2));
            return values;
        } catch (Exception e) { throw new IllegalStateException("Cannot read persisted platform reference data", e); }
    }
    public UUID start(RawRecord raw, int records, int rules, int errors, int warnings, int affected) {
        UUID id = UUID.randomUUID();
        String sql = "insert into dq_batch_evaluation(dq_evaluation_id,batch_id,state_code,source_system,state_pack_version,rule_config_version,records_evaluated,rules_evaluated,error_count,warning_count,affected_record_count) values(?,?,?,?,?,'m9',?,?,?,?,?)";
        try (var c=connections.open(); var s=c.prepareStatement(sql)) { s.setObject(1,id);s.setObject(2,raw.batchId());s.setString(3,raw.stateCode());s.setString(4,raw.sourceSystem());s.setString(5,raw.statePackVersion());s.setInt(6,records);s.setInt(7,rules);s.setInt(8,errors);s.setInt(9,warnings);s.setInt(10,affected);s.executeUpdate(); return id; } catch(Exception e){throw new IllegalStateException("Cannot persist DQ evaluation",e);}
    }
    /** The batch's newest evaluation, which a resumed processing adds its remaining chunks to. */
    public java.util.Optional<UUID> latestEvaluation(UUID batch) {
        try (var c=connections.open(); var s=c.prepareStatement("select dq_evaluation_id from dq_batch_evaluation where batch_id=? order by evaluated_at desc limit 1")) {
            s.setObject(1,batch); var r=s.executeQuery(); return r.next() ? java.util.Optional.of(r.getObject(1,UUID.class)) : java.util.Optional.empty();
        } catch(Exception e){throw new IllegalStateException("Cannot read DQ evaluation",e);}
    }
    /** Adds a later chunk's counts to the batch's evaluation, so a chunked batch still has one evaluation. */
    public void add(UUID evaluation, int records, int rules, int errors, int warnings, int affected) {
        String sql="update dq_batch_evaluation set records_evaluated=records_evaluated+?,rules_evaluated=rules_evaluated+?,error_count=error_count+?,warning_count=warning_count+?,affected_record_count=affected_record_count+? where dq_evaluation_id=?";
        try(var c=connections.open();var s=c.prepareStatement(sql)){s.setInt(1,records);s.setInt(2,rules);s.setInt(3,errors);s.setInt(4,warnings);s.setInt(5,affected);s.setObject(6,evaluation);s.executeUpdate();}catch(Exception e){throw new IllegalStateException("Cannot update DQ evaluation",e);}
    }
    public void finding(UUID evaluation, RawRecord raw, UUID canonical, ValidationIssue issue) {
        String sql="insert into dq_result(dq_evaluation_id,raw_record_id,canonical_resident_id,rule_id,rule_type,severity,field_path,message,state_pack_version,rule_config_version) values(?,?,?,?,?,?,?,?,?,'m9')";
        try(var c=connections.open();var s=c.prepareStatement(sql)){s.setObject(1,evaluation);s.setObject(2,raw.rawRecordId());s.setObject(3,canonical);s.setString(4,issue.rule());s.setString(5,issue.rule());s.setString(6,issue.severity().name());s.setString(7,issue.field());s.setString(8,issue.message());s.setString(9,raw.statePackVersion());s.executeUpdate();}catch(Exception e){throw new IllegalStateException("Cannot persist DQ finding",e);}
    }
}
