package gov.uddp.contracts;

/** Kafka topic names (docs/msa-design.md, section 4). A breaking contract change gets a new {@code .v2} topic. */
public final class Topics {

    public static final String RAW_RECORD_RECEIVED = "uddp.ingestion.raw-record-received.v1";
    public static final String RAW_RECORD_REPLAYED = "uddp.ingestion.raw-record-replayed.v1";
    public static final String BATCH_STARTED = "uddp.ingestion.batch-started.v1";
    public static final String BATCH_COMPLETED = "uddp.ingestion.batch-completed.v1";

    public static final String RESIDENT_CREATED = "uddp.canonical.resident-created.v1";
    public static final String FAMILY_OBSERVED = "uddp.canonical.family-observed.v1";
    public static final String BENEFIT_OBSERVED = "uddp.canonical.benefit-observed.v1";
    public static final String APPLICATION_OBSERVED = "uddp.canonical.application-observed.v1";
    public static final String ASSET_OBSERVED = "uddp.canonical.asset-observed.v1";
    public static final String RECORD_HELD = "uddp.canonical.record-held.v1";
    public static final String BATCH_PROCESSED = "uddp.canonical.batch-processed.v1";
    public static final String CANONICAL_DLQ = "uddp.canonical.dlq.v1";

    public static final String MATCH_DECIDED = "uddp.identity.match-decided.v1";
    public static final String CLUSTER_ASSIGNED = "uddp.identity.cluster-assigned.v1";
    public static final String MATCH_RUN_COMPLETED = "uddp.identity.match-run-completed.v1";

    public static final String GOLDEN_RESIDENT_CHANGED = "uddp.registry.golden-resident-changed.v1";
    public static final String GOLDEN_FAMILY_CHANGED = "uddp.registry.golden-family-changed.v1";
    public static final String GOLDEN_BUILD_COMPLETED = "uddp.registry.golden-build-completed.v1";
    /** The Golden rows one build wrote, for read models (api-stewardship-service). */
    public static final String GOLDEN_REPLICA = "uddp.registry.golden-replica.v1";

    public static final String PACK_PUBLISHED = "uddp.statepack.pack-published.v1";
    public static final String PACK_APPROVED = "uddp.statepack.pack-approved.v1";
    public static final String PACK_ACTIVATED = "uddp.statepack.pack-activated.v1";
    public static final String PACK_RETIRED = "uddp.statepack.pack-retired.v1";

    public static final String STEWARDSHIP_DECISION_RECORDED = "uddp.stewardship.decision-recorded.v1";
    public static final String PRIVACY_ERASURE_REQUESTED = "uddp.privacy.erasure-requested.v1";

    public static final String CMD_INGEST_REQUESTED = "uddp.cmd.ingestion.ingest-requested.v1";
    public static final String CMD_REPLAY_REQUESTED = "uddp.cmd.ingestion.replay-requested.v1";
    public static final String CMD_MATCHING_REQUESTED = "uddp.cmd.identity.matching-requested.v1";
    public static final String CMD_GOLDEN_BUILD_REQUESTED = "uddp.cmd.registry.golden-build-requested.v1";

    private Topics() {
    }
}
