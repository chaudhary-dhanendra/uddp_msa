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
create uddp.canonical.dlq.v1 1 "retention.ms=$((90 * DAY))"

create uddp.identity.match-decided.v1 3 "retention.ms=$((30 * DAY))"
create uddp.identity.cluster-assigned.v1 3 "retention.ms=$((30 * DAY))"
create uddp.identity.match-run-completed.v1 1 "retention.ms=$((90 * DAY))"

create uddp.registry.golden-resident-changed.v1 3 cleanup.policy=compact
create uddp.registry.golden-family-changed.v1 3 cleanup.policy=compact
create uddp.registry.golden-build-completed.v1 1 "retention.ms=$((90 * DAY))"

for t in pack-published pack-approved pack-activated pack-retired; do
  create "uddp.statepack.$t.v1" 1 cleanup.policy=compact
done

create uddp.stewardship.decision-recorded.v1 1 retention.ms=-1
create uddp.privacy.erasure-requested.v1 1 retention.ms=-1

for t in ingestion.ingest-requested ingestion.replay-requested identity.matching-requested registry.golden-build-requested; do
  create "uddp.cmd.$t.v1" 1 "retention.ms=$((7 * DAY))"
done

echo "UDDP topics ready on $BOOTSTRAP"
