#!/usr/bin/env bash
# Creates the UDDP topics (docs/msa-design.md, section 4) with dev partition counts. Idempotent.
# Usage: msa-kafka-topics.sh <bootstrap-server>
set -euo pipefail
BOOTSTRAP="${1:-localhost:9092}"
KT=/opt/kafka/bin/kafka-topics.sh

create() { # name partitions extra-config...
  local name="$1" partitions="$2"
  shift 2
  local config=()
  for c in "$@"; do config+=(--config "$c"); done
  "$KT" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists --topic "$name" \
    --partitions "$partitions" --replication-factor 1 "${config[@]}"
}

DAY=86400000
COMPACT_30D=(cleanup.policy=compact,delete "retention.ms=$((30 * DAY))")

create uddp.ingestion.raw-record-received.v1 3 "retention.ms=$((7 * DAY))"
create uddp.ingestion.raw-record-replayed.v1 3 "retention.ms=$((7 * DAY))"
create uddp.ingestion.batch-started.v1 1 "retention.ms=$((30 * DAY))"
create uddp.ingestion.batch-completed.v1 1 "retention.ms=$((30 * DAY))"

for t in resident-created family-observed benefit-observed application-observed asset-observed; do
  create "uddp.canonical.$t.v1" 3 "${COMPACT_30D[@]}"
done
create uddp.canonical.record-held.v1 1 "retention.ms=$((30 * DAY))"
create uddp.canonical.batch-processed.v1 1 "retention.ms=$((30 * DAY))"
create uddp.canonical.batch-observed.v1 3 "retention.ms=$((30 * DAY))"
create uddp.canonical.dlq.v1 1 "retention.ms=$((90 * DAY))"

create uddp.identity.match-decided.v1 3 "retention.ms=$((30 * DAY))"
create uddp.identity.cluster-assigned.v1 3 "retention.ms=$((30 * DAY))"
create uddp.identity.match-run-completed.v1 1 "retention.ms=$((90 * DAY))"

create uddp.registry.golden-resident-changed.v1 3 cleanup.policy=compact
create uddp.registry.golden-family-changed.v1 3 cleanup.policy=compact
create uddp.registry.golden-build-completed.v1 1 "retention.ms=$((90 * DAY))"
# One event carries every Golden row a build wrote (read model of api-stewardship-service).
create uddp.registry.golden-replica.v1 1 "retention.ms=$((90 * DAY))" max.message.bytes=8388608

for t in pack-published pack-approved pack-activated pack-retired; do
  create "uddp.statepack.$t.v1" 1 cleanup.policy=compact
done

create uddp.stewardship.decision-recorded.v1 1 retention.ms=-1
create uddp.privacy.erasure-requested.v1 1 retention.ms=-1

for t in ingestion.ingest-requested ingestion.replay-requested identity.matching-requested registry.golden-build-requested; do
  create "uddp.cmd.$t.v1" 1 "retention.ms=$((7 * DAY))"
done

# Dead-letter topics: <topic>.dlt.<consumer> for every consumer group (docs/msa-design.md, section 3.4).
dlt() { # consumer topic...
  local consumer="$1"
  shift
  for t in "$@"; do create "$t.dlt.$consumer" 1 "retention.ms=$((90 * DAY))"; done
}
dlt identity-service-projections uddp.canonical.resident-created.v1 uddp.registry.golden-resident-changed.v1
dlt identity-service-commands uddp.cmd.identity.matching-requested.v1
dlt common-core-identity-projection uddp.identity.match-decided.v1 uddp.identity.cluster-assigned.v1   uddp.identity.match-run-completed.v1

dlt ingestion-service-commands uddp.cmd.ingestion.ingest-requested.v1 uddp.cmd.ingestion.replay-requested.v1
dlt common-core-raw-projection uddp.ingestion.raw-record-received.v1 uddp.ingestion.raw-record-replayed.v1   uddp.ingestion.batch-completed.v1

dlt canonical-service-raw uddp.ingestion.raw-record-received.v1 uddp.ingestion.batch-completed.v1
dlt registry-service-residents uddp.canonical.resident-created.v1
dlt registry-service-projections uddp.canonical.family-observed.v1 uddp.identity.match-decided.v1   uddp.identity.cluster-assigned.v1 uddp.identity.match-run-completed.v1
dlt registry-service-commands uddp.cmd.registry.golden-build-requested.v1
dlt api-stewardship-golden-replica uddp.registry.golden-replica.v1
dlt common-core-canonical-residents uddp.canonical.resident-created.v1
dlt common-core-canonical-observations uddp.canonical.family-observed.v1 uddp.canonical.batch-processed.v1   uddp.canonical.batch-observed.v1

echo "UDDP topics ready on $BOOTSTRAP"
