# Held and rejected records

For stewards and the data-quality owner. Design record: `docs/decisions/LOG.md` (V108, V109).

## What happens to a row
A mapped row that fails an ERROR or SEND_TO_STEWARD rule is **held** (`held_record`), not loaded. A steward can
**rectify** it (stored as a new correction version, validated again, then released), **release** it as it is (only when
every reason is steward-routed), or **reject it for good** (`REJECTED_FINAL`, with a reason code and, for `OTHER`, a note).
Only a platform administrator can **reopen** a rejected record, with a reason. Every step is an append-only event in
`held_record_event`.

## Reports
- **Audit report, category "Final rejected records"** (Audit page, `GET /api/v1/audit-reports/final-rejected`, needs
  AUDIT_VIEW; CSV export `.../export` also needs REPORT_EXPORT). Filters: batch, source, department, state, reason code,
  decided by, date range. Columns: record reference (masked), source, batch, reason code, note, original failure
  reasons, decided by, decided at, reopened. A record that was rejected and then reopened stays in the report, flagged
  `reopened = yes`, with its current status. The landing page shows the totals and the rejections per source per week
  (last twelve weeks). Links lead here from the batch run screen and the Held records tab.
- **Department feedback file**, `GET /api/v1/batches/{id}/rejection-report` (CSV, needs BATCH_VIEW, limited to the
  caller's states): one line per reason of each held row: row number, reason code, reason text, field, **masked** value,
  status (`HELD`, `RECTIFIED`, `REJECTED_FINAL`, or `RELEASED`). Send it to the source department so they can correct
  the file. The row number is the line in the delivered file, as stored by the Hop ingest (`raw_record.source_line`,
  V111): for a CSV the physical line with the header as line 1 (blank lines counted but not landed), for a workbook the
  Excel row of its sheet. Rows landed before V111 fall back to the sheet row, or for CSV the position among the
  batch's landed rows. The run's rejected-rows CSV has the same line in its `sourceLine` column.

## Privacy
References and values are masked (names as first name plus initial, birth dates as the year, mobile numbers as the last
four digits, other identifiers as their last four characters). Exports are always masked unless the caller has
RESIDENT_VIEW_FULL and passes `unmasked=true` on the audit report (references only; a payload is never exported). Every
view and export is audited (`FINAL_REJECTED_REPORT_VIEW`, `FINAL_REJECTED_REPORT_EXPORT`,
`BATCH_REJECTION_REPORT_EXPORT`) with its filter and row count.

## Retention
When the raw-payload retention period (`uddp.retention.raw-payload-days`) has passed for a final-rejected record, the
daily purge replaces its stored payloads (`current_payload`, `original_payload`, and the payloads inside correction
attempts) with the purge marker and sets `payload_purged_at`. **The audit row stays**: the held record (reference,
reason codes, decision reason, note, actor, time) and its events. The report still lists it with `payloadPurged = yes`,
and the department file shows its lines without values. A purged record cannot be reopened. The purge counts these as
`REJECTED_PAYLOADS` in each retention run.

## For the data-quality owner
Watch the weekly trend per source: a rising count of `SOURCE_ERROR` rejections is a conversation with the department
(send the batch rejection report); many `OUT_OF_SCOPE` or `DUPLICATE_OF_OTHER_SOURCE` rejections point at the source
definition; `UNREADABLE` points at the file format. Reopened records show where a rejection was reconsidered.

## Bulk rejects and the inbox

Rejecting a whole group (one batch and reason code) over `uddp.stewardship.bulk-approval-threshold` (50) records
does not reject anything yet: it creates a bulk reject that waits for a second steward. Stewardship -> Held records
shows them under **Pending bulk rejects** (group, records, reject reason, requester, age and five masked records);
another steward with STEWARD_HELD_DECIDE approves (every record still waiting in the group is rejected) or rejects
with a reason (the records stay held). The requester sees "You requested this; another steward must decide"; the API
answers 409 if they try. The list is `GET /api/v1/held-records/bulk-actions?status=PENDING_APPROVAL|APPROVED|REJECTED`,
scoped to the caller's states; the decisions are `POST .../bulk-actions/{id}/approve` and `.../decline`.

The exception inbox reads held records from the server: one **HELD_RECORDS** item per waiting (batch, reason) group
with its count and share of the batch, and one **HELD_BULK_PENDING** item per bulk reject waiting for a decision.
A group is HIGH when its share reaches `uddp.processing.max-held-percent` (the release gate, 5%) or it has waited
three days, MEDIUM from 1% or one day, LOW otherwise. The Held records tab shows how many bulk rejects are waiting.
## Fix in pack

Every held record and every rejected row (`canonical_processing_error`, V110) records where it is fixed: the pack and
source it was mapped with, the Studio draft of that pack (if it was made in the Studio), and a fix target per reason:
`RULE` (rule id) for `RULE_FAILED:<id>`, `CROSSWALK` (crosswalk name) for UNMAPPED_CODE, `MAPPING` (source
column) for UNREADABLE_DATE, MISSING_NAME and ID_FORMAT, `SHAPING` (field) for LOW_CONFIDENCE and `PACK_SOURCE`
otherwise. The target comes from the reason code; the pack only names the exact column or crosswalk.

The Studio Preview step groups the sample's issues by the same reason codes, with counts (`countsByCode` in the
preview API). Each group's "Fix in pack" link opens the draft's wizard step for its target (Source 2, Shape 3, Map
columns 4, Crosswalks 5, Rules 6, with the key as `?focus=`); the Held records tab uses the same targets. A
suggestion is shown for UNMAPPED_CODE (nearest crosswalk values), UNREADABLE_DATE (the date formats the value reads
with), ID_FORMAT (the identifier type's pattern) and MISSING_NAME (other filled name columns); suggestions never repeat
the row's values. Records held before V110 get the target their code implies and no draft link.
## See also

[Stewardship](stewardship.md), [Onboarding runs](m10-onboarding.md), [Shaping operations](shaping-operations.md) and [Geography master, Loading LGD](geography-master.md#loading-lgd).

The wizard step opened by a fix link scrolls to and outlines the named rule, column, crosswalk or shaping proposal (`?focus=`). The held record detail (`GET /api/v1/held-records/{id}`) returns `suggestions` per reason code, from the same server-side suggester as the Studio preview.
