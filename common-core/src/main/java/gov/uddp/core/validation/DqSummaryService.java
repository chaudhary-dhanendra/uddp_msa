package gov.uddp.core.validation;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class DqSummaryService {
    public static final int SAMPLE_LIMIT = 20;
    private final PostgresConnectionFactory connections;
    public DqSummaryService(PostgresConnectionFactory connections) { this.connections = connections; }
    public DqBatchSummary summary(UUID batchId) {
        String base="select dq_evaluation_id,state_code,source_system,state_pack_version,records_evaluated,rules_evaluated,error_count,warning_count,affected_record_count from dq_batch_evaluation where batch_id=? order by evaluated_at desc limit 1";
        try(var c=connections.open();var s=c.prepareStatement(base)){s.setObject(1,batchId);var r=s.executeQuery();if(!r.next())throw new NoSuchElementException("DQ evaluation does not exist for batch");UUID id=r.getObject(1,UUID.class);
            var rules=new ArrayList<DqBatchSummary.RuleBreakdown>();try(var q=c.prepareStatement("select rule_id,rule_type,severity,count(*) from dq_result where dq_evaluation_id=? group by rule_id,rule_type,severity order by rule_id")){q.setObject(1,id);var x=q.executeQuery();while(x.next())rules.add(new DqBatchSummary.RuleBreakdown(x.getString(1),x.getString(2),x.getString(3),x.getInt(4)));}
            var samples=new ArrayList<DqBatchSummary.Sample>();try(var q=c.prepareStatement("select raw_record_id,canonical_resident_id,rule_id,field_path,severity,message from dq_result where dq_evaluation_id=? order by evaluated_at,dq_result_id limit ?")){q.setObject(1,id);q.setInt(2,SAMPLE_LIMIT);var x=q.executeQuery();while(x.next())samples.add(new DqBatchSummary.Sample(x.getObject(1,UUID.class),x.getObject(2,UUID.class),x.getString(3),x.getString(4),x.getString(5),x.getString(6)));}
            return new DqBatchSummary(batchId,r.getString(2),r.getString(3),r.getString(4),r.getInt(5),r.getInt(6),r.getInt(7),r.getInt(8),r.getInt(9),List.copyOf(rules),List.copyOf(samples));
        }catch(NoSuchElementException e){throw e;}catch(Exception e){throw new IllegalStateException("Cannot read DQ summary",e);}
    }
}
