package gov.uddp.core.inbox;

import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.held.HeldRecordRepository;
import gov.uddp.core.inbox.ExceptionSource.Item;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Held records in the inbox: one HELD_RECORDS item per waiting (batch, reason code) group with its count and share of
 * the batch, and one HELD_BULK_PENDING item per bulk reject waiting for a second steward. Counts only, never personal
 * data. A group is HIGH when its share reaches {@code uddp.processing.max-held-percent} (the release gate) or it has
 * waited three days, MEDIUM from 1% or one day, LOW otherwise.
 */
@Component
public class HeldRecordExceptions implements ExceptionSource {
    static final Duration HIGH_AGE = Duration.ofDays(3);
    static final Duration MEDIUM_AGE = Duration.ofDays(1);
    static final double MEDIUM_SHARE = 1.0;

    private final HeldRecordRepository repository;
    private final PostgresConnectionFactory connections;
    private final double highShare;
    private final Clock clock;

    @Autowired
    public HeldRecordExceptions(HeldRecordRepository repository, PostgresConnectionFactory connections,
                                @Value("${uddp.processing.max-held-percent:5}") double highShare) {
        this(repository, connections, highShare, Clock.systemUTC());
    }

    HeldRecordExceptions(HeldRecordRepository repository, PostgresConnectionFactory connections, double highShare,
                         Clock clock) {
        this.repository = repository;
        this.connections = connections;
        this.highShare = highShare;
        this.clock = clock;
    }

    @Override
    public List<String> types() {
        return List.of("HELD_RECORDS", "HELD_BULK_PENDING");
    }

    @Override
    public List<Item> items() {
        List<Item> items = new ArrayList<>();
        for (var group : repository.groups(null)) {
            double share = group.batchRows() == 0 ? 0 : 100.0 * group.count() / group.batchRows();
            String link = "/stewardship?tab=held&batch=" + group.batchId() + "&reason=" + group.reasonCode();
            items.add(new Item("HELD_RECORDS:" + group.batchId() + ":" + group.reasonCode(), "HELD_RECORDS",
                    group.count() + " held records: " + group.reasonCode(),
                    String.format(Locale.ROOT, "%.1f%% of batch %s from %s", share, group.batchId(),
                            group.sourceSystem()),
                    priority(share, Duration.between(group.oldest(), clock.instant())), group.stateCode(),
                    group.sourceSystem(), group.oldest(), link));
        }
        items.addAll(PlatformExceptionSources.query(connections, "select a.action_id, b.state_code, b.source_system,"
                + " a.reason_code, a.record_count, a.created_by, a.created_at from held_bulk_action a"
                + " join ingestion_batch b on b.batch_id = a.batch_id where a.status = 'PENDING_APPROVAL'"
                + " order by a.created_at desc",
                r -> new Item("HELD_BULK_PENDING:" + r.getString(1), "HELD_BULK_PENDING",
                        "Bulk reject of " + r.getInt(5) + " held records waits for a second steward",
                        "Reason " + r.getString(4) + ", requested by " + r.getString(6), "HIGH", r.getString(2),
                        r.getString(3), PlatformExceptionSources.at(r, 7), "/stewardship?tab=held&bulk=pending")));
        return items;
    }

    String priority(double share, Duration age) {
        if (share >= highShare || age.compareTo(HIGH_AGE) >= 0) {
            return "HIGH";
        }
        if (share >= MEDIUM_SHARE || age.compareTo(MEDIUM_AGE) >= 0) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
