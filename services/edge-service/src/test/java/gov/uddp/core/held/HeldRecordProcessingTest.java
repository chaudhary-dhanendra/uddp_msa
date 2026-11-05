package gov.uddp.core.held;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.CanonicalResidentProcessingService;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.canonical.ProcessingProgress;
import gov.uddp.core.rules.ApprovalConflictException;
import gov.uddp.core.statepack.StatePackLoader;
import gov.uddp.core.testsupport.MatchingRunFixture;
import gov.uddp.core.testsupport.SampleData;
import gov.uddp.core.testsupport.Sql;
import gov.uddp.core.testsupport.TestDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Held records over the state-1 pds source with two extra rules: an ERROR on a date of birth in the future (a steward
 * can fix it) and a SEND_TO_STEWARD on a mobile number that is not ten digits (a steward may accept it).
 */
class HeldRecordProcessingTest {
    private static final String HEADER = "beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile,card_type,card_status,"
            + "card_issued_on,monthly_grain_kg,last_lifted_on,last_lifted_kg";
    private static final String GOOD_MOBILE = "9876543210";
    private static final String RULES = """
              - { id: dob-not-future, field: dateOfBirth, type: DATE_RANGE, max: TODAY, severity: error }
              - { id: mobile-10-digits, field: mobile, type: PATTERN, pattern: "[0-9]{10}", severity: send_to_steward }
            """;

    @TempDir
    Path work;

    private PostgresConnectionFactory connections;
    private Sql sql;
    private StatePackLoader packs;
    private HeldRecordRepository repository;
    private CanonicalResidentProcessingService processing;
    private HeldRecordService held;
    private ProcessingProgress progress;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void database() throws IOException {
        connections = TestDatabase.freshMigrated();
        sql = new Sql(connections);
        packs = SampleData.loader(packsWith(work.resolve("packs"), RULES));
        repository = new HeldRecordRepository(connections, json);
        progress = new ProcessingProgress(connections);
        processing = newProcessing(packs);
        held = new HeldRecordService(repository, processing, packs, connections, 50);
    }

    private CanonicalResidentProcessingService newProcessing(StatePackLoader loader) {
        var service = SampleData.processing(connections, loader);
        service.setHeldRecords(repository, false, false);
        service.setProgress(progress);
        return service;
    }

    /** A copy of the sample packs whose state-1 resident validation also carries {@code extraRules}. */
    private static Path packsWith(Path target, String extraRules) throws IOException {
        try (Stream<Path> files = Files.walk(SampleData.STATE_PACKS)) {
            for (Path file : files.toList()) {
                Path to = target.resolve(SampleData.STATE_PACKS.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Path validation = target.resolve("state-1/validation/resident.yaml");
        Files.writeString(validation, Files.readString(validation) + extraRules);
        return target;
    }

    // ---- reason codes ----------------------------------------------------------------------------------------------

    @Test
    void everyRejectionCarriesTheCodeItsRaisePointNamed() throws Exception {
        UUID batch = land(
                row("Unreadable Date", "xx-yy-zzzz", "M", "R1", GOOD_MOBILE),
                row("Unmapped Code", "12-03-1980", "Q", "R2", GOOD_MOBILE),
                row("", "12-03-1980", "M", "R3", GOOD_MOBILE),
                row("Twin", "12-03-1980", "M", "R4", GOOD_MOBILE),
                row("Twin", "12-03-1980", "M", "R4", GOOD_MOBILE),
                "Short Row,12-03-1980");

        var summary = processing.processBatch(batch);

        assertEquals(6, summary.recordsFailed());
        assertEquals(0, summary.recordsHeld());
        assertEquals(Map.of("UNREADABLE_DATE", 1L, "UNMAPPED_CODE", 1L, "MISSING_NAME", 1L,
                "DUPLICATE_SOURCE_KEY", 2L, "OTHER", 1L), reasonCounts(batch));
    }

    @Test
    void invalidIdentifiersAndLowConfidenceFieldsHoldTheRowWhenEnabled() {
        var node = json.createObjectNode();
        node.putObject("extensions").putArray(gov.uddp.core.identity.IdentifierProtection.EXTENSION).addObject().put("type", "AADHAAR").put("status", "INVALID")
                .put("reason", "checksum");
        var low = new gov.uddp.core.validation.ValidationIssue("dob", gov.uddp.shaping.ShapingEngine.LOW_CONFIDENCE_RULE,
                gov.uddp.core.validation.ValidationIssue.Severity.WARNING, "guessed day and month");
        var shaped = new gov.uddp.shaping.api.RecordShaper.Shaped(null, List.of(low));

        assertEquals(List.of(), processing.holdReasons(List.of(), node, shaped), "off by default");

        processing.setHeldRecords(repository, true, true);
        var reasons = processing.holdReasons(List.of(), node, shaped);

        assertEquals(List.of("ID_FORMAT", "LOW_CONFIDENCE"), reasons.stream().map(HeldReason::code).toList());
        assertFalse(reasons.stream().anyMatch(HeldReason::blocksOverride), "both can be released as they are");
    }

    // ---- holding ---------------------------------------------------------------------------------------------------

    @Test
    void errorAndStewardRulesHoldTheRowInsteadOfRejectingIt() throws Exception {
        UUID batch = land(
                row("Good Person", "12-03-1980", "M", "R1", GOOD_MOBILE),
                row("Future Born", "12-03-2099", "M", "R2", GOOD_MOBILE),
                row("Bad Mobile", "12-03-1980", "F", "R3", "12345"));

        var summary = processing.processBatch(batch);

        assertEquals(1, summary.recordsMapped());
        assertEquals(2, summary.recordsHeld());
        assertEquals(0, summary.recordsFailed());
        assertEquals(1, sql.count("select count(*) from canonical_resident where batch_id=?", batch));
        assertEquals(0, sql.count("select count(*) from canonical_processing_error where batch_id=?", batch));
        assertEquals(2, progress.totals(batch).held());
        assertEquals(List.of("RULE_FAILED:dob-not-future"), reasonCodes("Future Born"));
        assertEquals(List.of("RULE_FAILED:mobile-10-digits"), reasonCodes("Bad Mobile"));
        assertEquals(0, sql.count("select count(*) from held_record h join canonical_resident c"
                + " on c.raw_record_id = h.raw_record_id where h.batch_id=?", batch), "held rows never become canonical");
        assertEquals(2, sql.count("select count(*) from held_record where batch_id=? and status='OPEN'"
                + " and original_payload is not null", batch));
        assertEquals(1, sql.count("select count(*) from held_record where batch_id=?"
                + " and reason_codes->0->>'severity'='ERROR'", batch));
        assertEquals(1, sql.count("select count(*) from held_record where batch_id=?"
                + " and reason_codes->0->>'severity'='SEND_TO_STEWARD'", batch));
        assertEquals(66.7, Math.round(repository.share(batch).percent() * 10) / 10.0, "held share of the delivery");
    }

    @Test
    void heldRowsStayOutOfMatchingAndGolden() throws Exception {
        UUID batch = land(
                row("Good Person", "12-03-1980", "M", "R1", GOOD_MOBILE),
                row("Other Person", "01-01-1975", "F", "R2", GOOD_MOBILE),
                row("Future Born", "12-03-2099", "M", "R3", GOOD_MOBILE));
        processing.processBatch(batch);

        UUID run = MatchingRunFixture.completed(connections, List.of());
        SampleData.golden(connections, packs).build(run);

        assertEquals(2, sql.count("select count(*) from canonical_resident_current"), "matching input");
        assertEquals(2, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
        assertEquals(0, sql.count("select count(*) from golden_resident_membership m join canonical_resident c"
                + " on c.canonical_resident_id = m.canonical_resident_id join held_record h"
                + " on h.raw_record_id = c.raw_record_id"));
    }

    @Test
    void aHeldRedeliveryLeavesTheExistingGoldenRecordUntouched() throws Exception {
        UUID first = land(row("Anita Devi", "12-03-1980", "F", "R1", GOOD_MOBILE));
        processing.processBatch(first);
        SampleData.golden(connections, packs).build(MatchingRunFixture.completed(connections, List.of()));
        UUID golden = sql.uuid("select golden_resident_id from golden_resident where status='ACTIVE'");
        UUID canonical = sql.uuid("select canonical_resident_id from canonical_resident_current");
        String before = currentGolden(golden);
        long versions = sql.count("select count(*) from golden_resident_version where golden_resident_id=?", golden);

        UUID second = land(row("Anita Devi", "12-03-1980", "F", "R1", "12345"));
        var summary = processing.processBatch(second);

        assertEquals(1, summary.recordsHeld());
        assertEquals(canonical, sql.uuid("select canonical_resident_id from canonical_resident_current"),
                "the held update never supersedes the current canonical version");
        assertEquals(1, sql.count("select count(*) from canonical_resident"));
        assertEquals(before, currentGolden(golden));
        assertEquals(versions, sql.count("select count(*) from golden_resident_version where golden_resident_id=?",
                golden), "no new Golden version");
        assertEquals(1, sql.count("select count(*) from golden_resident where status='ACTIVE'"));
    }

    @Test
    void resumingAfterACrashHoldsEachRawRecordOnce() throws Exception {
        UUID batch = land(
                row("Held One", "12-03-2099", "M", "R1", GOOD_MOBILE), row("Held Two", "12-03-2099", "M", "R2", GOOD_MOBILE),
                row("Good One", "12-03-1980", "M", "R3", GOOD_MOBILE), row("Held Three", "12-03-1980", "M", "R4", "1"),
                row("Held Four", "12-03-1980", "M", "R5", "2"), row("Good Two", "12-03-1980", "M", "R6", GOOD_MOBILE));
        var interrupted = newProcessing(packs);
        interrupted.setChunkSize(2);
        interrupted.setAfterChunk(number -> {
            if (number == 2) {
                throw new IllegalStateException("simulated crash after chunk 2");
            }
        });
        assertThrows(IllegalStateException.class, () -> interrupted.processBatch(batch));
        assertEquals(4, sql.count("select (select count(*) from held_record where batch_id=?)"
                + " + (select count(*) from canonical_resident where batch_id=?)", batch, batch),
                "two chunks of two rows committed, whichever rows landed first");

        var resumed = newProcessing(packs);
        resumed.setChunkSize(2);
        var summary = resumed.processBatch(batch);

        assertEquals(6, summary.recordsRead());
        assertEquals(4, summary.recordsHeld());
        assertEquals(4, sql.count("select count(*) from held_record where batch_id=?", batch));
        assertEquals(4, sql.count("select count(distinct raw_record_id) from held_record where batch_id=?", batch));
        assertEquals(4, sql.count("select count(*) from held_record_event where action='HELD'"));
        // Processing the finished batch again changes nothing.
        resumed.processBatch(batch);
        assertEquals(4, sql.count("select count(*) from held_record where batch_id=?", batch));
        assertEquals(2, sql.count("select count(*) from canonical_resident where batch_id=?", batch));
    }

    // ---- decisions -------------------------------------------------------------------------------------------------

    @Test
    void aCorrectionThatPassesReleasesTheRecordAsStewardCorrected() throws Exception {
        UUID id = holdOne("Future Born", "12-03-2099", "R1", GOOD_MOBILE);

        var result = held.rectify(id, Map.of("dateOfBirth", "1990-01-01"), "year typed wrongly", "sam.steward");

        assertEquals("RECTIFIED", result.status());
        assertEquals(1, result.correctionVersion());
        assertTrue(result.reasons().isEmpty());
        var canonical = json.readTree(sql.text("select canonical_payload::text from canonical_resident where"
                + " canonical_resident_id=?", result.canonicalResidentId()));
        assertEquals("1990-01-01", canonical.path("dateOfBirth").asText());
        assertEquals("steward-corrected", canonical.path("extensions").path("stewardDecision").path("provenance").asText());
        assertEquals(List.of("dateOfBirth"), json.convertValue(
                canonical.path("extensions").path("stewardDecision").path("correctedFields"), List.class));
        var record = repository.find(id).orElseThrow();
        assertEquals("RECTIFIED", record.status());
        assertEquals("sam.steward", record.decidedBy());
        assertEquals("1990-01-01", record.currentPayload().path("dateOfBirth").asText());
        assertEquals("2099-03-12", record.originalPayload().path("dateOfBirth").asText(), "the original is kept");
        assertEquals(List.of("HELD", "RECTIFY_ATTEMPT", "RECTIFY"),
                repository.events(id).stream().map(HeldEvent::action).toList());
        assertThrows(ApprovalConflictException.class, () -> held.reject(id, "SOURCE_ERROR", null, "sam.steward"));
    }

    @Test
    void aCorrectionThatStillFailsLeavesTheRecordOpenWithTheNewReasons() throws Exception {
        UUID id = holdOne("Future Born", "12-03-2099", "R1", GOOD_MOBILE);

        var result = held.rectify(id, Map.of("dateOfBirth", "2098-01-01"), null, "sam.steward");

        assertEquals("OPEN", result.status());
        assertEquals(1, result.correctionVersion());
        assertEquals(List.of("RULE_FAILED:dob-not-future"), result.reasons().stream().map(HeldReason::code).toList());
        assertNull(result.canonicalResidentId());
        assertEquals(0, sql.count("select count(*) from canonical_resident"));
        assertEquals("2098-01-01", repository.find(id).orElseThrow().currentPayload().path("dateOfBirth").asText());

        var second = held.rectify(id, Map.of("dateOfBirth", "1991-05-05"), null, "sam.steward");

        assertEquals("RECTIFIED", second.status());
        assertEquals(2, second.correctionVersion());
        assertThrows(IllegalArgumentException.class, () -> held.rectify(holdOne("Other", "12-03-2099", "R2", GOOD_MOBILE),
                Map.of("not a path!", "x"), null, "sam.steward"));
    }

    @Test
    void anOverrideIsRefusedForAHardErrorAndReleasesAStewardRoutedRecord() throws Exception {
        UUID hard = holdOne("Future Born", "12-03-2099", "R1", GOOD_MOBILE);
        UUID soft = holdOne("Short Mobile", "12-03-1980", "R2", "12345");

        assertThrows(ApprovalConflictException.class, () -> held.releaseOverride(hard, "looks fine", "sam.steward"));
        assertEquals("OPEN", repository.find(hard).orElseThrow().status());
        assertThrows(IllegalArgumentException.class, () -> held.releaseOverride(soft, " ", "sam.steward"));

        var released = held.releaseOverride(soft, "landline numbers are valid here", "sam.steward");

        assertEquals("RELEASED_OVERRIDE", released.status());
        var canonical = json.readTree(sql.text("select canonical_payload::text from canonical_resident where"
                + " canonical_resident_id=?", released.canonicalResidentId()));
        assertEquals("steward-released", canonical.path("extensions").path("stewardDecision").path("provenance").asText());
        assertEquals("landline numbers are valid here", repository.find(soft).orElseThrow().decisionNote());
    }

    @Test
    void aRejectionIsFinalUntilAnAdministratorReopensIt() throws Exception {
        UUID id = holdOne("Future Born", "12-03-2099", "R1", GOOD_MOBILE);

        assertThrows(IllegalArgumentException.class, () -> held.reject(id, "NOT_A_CODE", null, "sam.steward"));
        assertThrows(IllegalArgumentException.class, () -> held.reject(id, "OTHER", " ", "sam.steward"));
        var rejected = held.reject(id, "SOURCE_ERROR", "the register says 2099", "sam.steward");

        assertEquals("REJECTED_FINAL", rejected.status());
        assertEquals("SOURCE_ERROR", rejected.decisionReasonCode());
        assertThrows(ApprovalConflictException.class, () -> held.rectify(id, Map.of("dateOfBirth", "1990-01-01"), null,
                "sam.steward"));
        assertThrows(IllegalArgumentException.class, () -> held.reopen(id, " ", "root"));

        var reopened = held.reopen(id, "the source was corrected", "root");

        assertEquals("OPEN", reopened.status());
        assertNull(reopened.decidedBy());
        assertEquals(List.of("HELD", "REJECT", "REOPEN"), repository.events(id).stream().map(HeldEvent::action).toList());
        assertThrows(ApprovalConflictException.class, () -> held.reopen(id, "again", "root"));
    }

    @Test
    void aBulkRejectAboveTheThresholdNeedsASecondApprover() throws Exception {
        var strict = new HeldRecordService(repository, processing, packs, connections, 2);
        UUID batch = land(row("A One", "12-03-2099", "M", "R1", GOOD_MOBILE), row("B Two", "12-03-2099", "M", "R2", GOOD_MOBILE),
                row("C Three", "12-03-2099", "M", "R3", GOOD_MOBILE), row("D Four", "12-03-1980", "M", "R4", "1"),
                row("E Five", "12-03-1980", "M", "R5", "2"));
        processing.processBatch(batch);

        var pending = strict.rejectGroup(batch, "RULE_FAILED:dob-not-future", "SOURCE_ERROR", null, "alice");

        assertEquals("PENDING_APPROVAL", pending.outcome());
        assertEquals(3, pending.records());
        assertEquals(0, sql.count("select count(*) from held_record where status='REJECTED_FINAL'"));
        // WP4: the requester is told another steward must decide (409), not refused a permission.
        var conflict = assertThrows(ApprovalConflictException.class,
                () -> strict.approveBulk(pending.actionId(), "mine", "alice"));
        assertTrue(conflict.getMessage().contains("another steward must decide"), conflict.getMessage());
        assertEquals(0, sql.count("select count(*) from held_record where status='REJECTED_FINAL'"));

        var listed = strict.bulkActions("PENDING_APPROVAL", null);
        assertEquals(1, listed.size());
        assertEquals(pending.actionId(), listed.getFirst().actionId());
        assertEquals(3, listed.getFirst().recordCount());
        assertEquals("alice", listed.getFirst().requestedBy());
        assertEquals("RULE_FAILED:dob-not-future", listed.getFirst().reasonCode());
        assertEquals(3, listed.getFirst().samples().size());
        assertThrows(IllegalArgumentException.class, () -> strict.bulkActions("NOPE", null));

        var inbox = new gov.uddp.core.inbox.HeldRecordExceptions(repository, connections, 5).items();
        assertEquals(1, inbox.stream().filter(i -> i.type().equals("HELD_BULK_PENDING")).count());
        var heldGroup = inbox.stream().filter(i -> i.id().equals("HELD_RECORDS:" + batch + ":RULE_FAILED:dob-not-future"))
                .findFirst().orElseThrow();
        assertEquals("HIGH", heldGroup.priority(), "3 of 5 rows held is above the 5% release gate");
        assertTrue(heldGroup.title().startsWith("3 held records"), heldGroup.title());
        assertTrue(heldGroup.link().contains("tab=held"), heldGroup.link());

        var applied = strict.approveBulk(pending.actionId(), "agreed", "bob");

        assertEquals("APPLIED", applied.outcome());
        assertEquals(3, sql.count("select count(*) from held_record where status='REJECTED_FINAL'"
                + " and decision_reason_code='SOURCE_ERROR' and decided_by='bob'"));
        assertThrows(ApprovalConflictException.class, () -> strict.approveBulk(pending.actionId(), "again", "carol"));

        assertEquals("APPROVED", strict.bulkActions("APPROVED", null).getFirst().status());
        assertEquals(0, strict.bulkActions("PENDING_APPROVAL", null).size());

        var small = strict.rejectGroup(batch, "RULE_FAILED:mobile-10-digits", "OUT_OF_SCOPE", null, "alice");

        assertEquals("APPLIED", small.outcome(), "two records are within the threshold");
        assertEquals(5, sql.count("select count(*) from held_record where status='REJECTED_FINAL'"));
    }

    @Test
    void aRejectedBulkRejectLeavesTheGroupHeld() throws Exception {
        var strict = new HeldRecordService(repository, processing, packs, connections, 2);
        UUID batch = land(row("F Six", "12-03-2099", "M", "R1", GOOD_MOBILE), row("G Seven", "12-03-2099", "M", "R2", GOOD_MOBILE),
                row("H Eight", "12-03-2099", "M", "R3", GOOD_MOBILE));
        processing.processBatch(batch);
        var pending = strict.rejectGroup(batch, "RULE_FAILED:dob-not-future", "SOURCE_ERROR", null, "alice");
        long waitingBefore = sql.count("select count(*) from held_record where batch_id='" + batch + "' and status='OPEN'");

        assertThrows(ApprovalConflictException.class, () -> strict.declineBulk(pending.actionId(), "mine", "alice"));
        assertThrows(IllegalArgumentException.class, () -> strict.declineBulk(pending.actionId(), " ", "bob"),
                "a rejection needs a reason");
        strict.declineBulk(pending.actionId(), "these are real errors to fix at source", "bob");

        assertEquals(waitingBefore, sql.count("select count(*) from held_record where batch_id='" + batch
                + "' and status='OPEN'"), "the records stay held");
        var rejected = strict.bulkActions("REJECTED", null).stream()
                .filter(a -> a.actionId().equals(pending.actionId())).findFirst().orElseThrow();
        assertEquals("bob", rejected.decidedBy());
        assertEquals("these are real errors to fix at source", rejected.decisionReason());
    }

    @Test
    void reprocessingAGroupReleasesWhatARuleFixNowPasses() throws Exception {
        UUID batch = land(row("Short Mobile", "12-03-1980", "M", "R1", "12345"),
                row("Future Born", "12-03-2099", "M", "R2", GOOD_MOBILE));
        processing.processBatch(batch);
        // The pack's mobile rule is fixed (no longer there); the date-of-birth error stays.
        var fixed = SampleData.loader(packsWith(work.resolve("fixed"), """
                  - { id: dob-not-future, field: dateOfBirth, type: DATE_RANGE, max: TODAY, severity: error }
                """));
        var afterFix = new HeldRecordService(repository, newProcessing(fixed), fixed, connections, 50);

        var mobile = afterFix.reprocessGroup(batch, "RULE_FAILED:mobile-10-digits", "sam.steward");
        var dob = afterFix.reprocessGroup(batch, "RULE_FAILED:dob-not-future", "sam.steward");

        assertEquals(1, mobile.released());
        assertEquals(0, mobile.stillHeld());
        assertEquals(0, dob.released());
        assertEquals(1, dob.stillHeld());
        assertEquals(1, sql.count("select count(*) from canonical_resident where batch_id=?", batch));
        assertEquals(1, sql.count("select count(*) from held_record where status='RECTIFIED'"
                + " and decision_reason_code='REPROCESSED'"));
        assertEquals(1, sql.count("select count(*) from held_record where status='OPEN'"));
    }

    @Test
    void aGroupReprocessesInTheBackgroundWithProgressAndExportsAsCsvForTheSource() throws Exception {
        UUID batch = land(row("Future One", "12-03-2099", "M", "R1", GOOD_MOBILE),
                row("Future Two", "12-03-2099", "F", "R2", GOOD_MOBILE),
                row("Fine Person", "12-03-1980", "M", "R3", GOOD_MOBILE));
        processing.processBatch(batch);

        var csv = new java.io.StringWriter();
        held.exportGroup(batch, "RULE_FAILED:dob-not-future", true, csv);
        var lines = csv.toString().strip().split("\n");
        assertEquals(3, lines.length, csv.toString());
        assertTrue(lines[0].startsWith("held_id,source,source_record_id,source_line,status,reason_codes"), lines[0]);
        assertTrue(csv.toString().contains("RULE_FAILED:dob-not-future"), csv.toString());
        assertTrue(csv.toString().contains(",\"Future Two,12-03-2099,"), "the source row is one quoted cell: " + csv);

        var job = held.startReprocess(batch, "RULE_FAILED:dob-not-future", "sam.steward");
        assertEquals(2, job.total());
        for (int i = 0; i < 300 && "RUNNING".equals(job.status()); i++) {
            Thread.sleep(50);
            job = held.reprocessJob(job.jobId());
        }
        assertEquals("COMPLETED", job.status(), String.valueOf(job.error()));
        assertEquals(2, job.processed());
        assertEquals(2, job.stillHeld(), "the dates are still in the future");
        assertEquals(0, job.released());
    }

    @Test
    void metricsCountOpenRectifiedAndRejectedRecords() throws Exception {
        UUID one = holdOne("Future One", "12-03-2099", "R1", GOOD_MOBILE);
        UUID two = holdOne("Future Two", "12-03-2099", "R2", GOOD_MOBILE);
        holdOne("Future Three", "12-03-2099", "R3", GOOD_MOBILE);
        held.rectify(one, Map.of("dateOfBirth", "1990-01-01"), null, "sam.steward");
        held.reject(two, "UNREADABLE", null, "sam.steward");

        var metrics = held.platformMetrics();

        assertEquals(1.0, metrics.get(gov.uddp.core.monitoring.MonitoringService.Metric.HELD_OPEN));
        assertEquals(1.0, metrics.get(gov.uddp.core.monitoring.MonitoringService.Metric.HELD_RECTIFIED));
        assertEquals(1.0, metrics.get(gov.uddp.core.monitoring.MonitoringService.Metric.HELD_REJECTED));
        assertTrue(metrics.get(gov.uddp.core.monitoring.MonitoringService.Metric.HELD_HOURS_TO_DECIDE) >= 0);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    /** Lands one row that processing holds and returns its held record. */
    private UUID holdOne(String name, String dob, String ration, String mobile) throws SQLException {
        UUID batch = land(row(name, dob, "M", ration, mobile));
        processing.processBatch(batch);
        return sql.uuid("select held_id from held_record where batch_id=?", batch);
    }

    private static String row(String name, String dob, String sex, String ration, String mobile) {
        return name + "," + dob + "," + sex + ",101," + ration + "," + mobile + ",PHH,A,01-04-2015,25,05-10-2026,25";
    }

    private Map<String, Long> reasonCounts(UUID batch) {
        return sql.column("select reason_code from canonical_processing_error where batch_id=?", String.class, batch)
                .stream().collect(Collectors.groupingBy(code -> code, Collectors.counting()));
    }

    /** The reason codes of the held record whose canonical payload carries this name. */
    private List<String> reasonCodes(String name) {
        return sql.column("select r->>'code' from held_record h, jsonb_array_elements(h.reason_codes) r"
                + " where h.current_payload->>'name'=? order by 1", String.class, name);
    }

    /** Lands the lines as one completed pds batch of state-1. */
    private UUID land(String... lines) throws SQLException {
        var pack = packs.find("state-1").orElseThrow();
        var source = pack.sources().stream().filter(s -> s.sourceId().equals("pds")).findFirst().orElseThrow();
        String version = pack.manifest().version();
        UUID batch = UUID.randomUUID();
        try (var c = connections.open()) {
            c.setAutoCommit(false);
            try (var s = c.prepareStatement("insert into ingestion_batch(batch_id,state_code,source_system,source_type,"
                    + "source_schema_version,state_pack_version,pipeline_template,status,record_count)"
                    + " values(?,?,?,?,?,?,?,'COMPLETED',?)")) {
                s.setObject(1, batch);
                s.setString(2, pack.manifest().stateCode());
                s.setString(3, source.sourceId());
                s.setString(4, source.sourceType());
                s.setString(5, source.sourceSchemaVersion());
                s.setString(6, version);
                s.setString(7, source.hopTemplate());
                s.setInt(8, lines.length);
                s.executeUpdate();
            }
            try (var s = c.prepareStatement("insert into raw_record(batch_id,state_code,source_system,raw_payload,"
                    + "source_metadata,state_pack_version,source_schema_version,pipeline_template)"
                    + " values(?,?,?,?::jsonb,?::jsonb,?,?,?)")) {
                for (String line : lines) {
                    s.setObject(1, batch);
                    s.setString(2, pack.manifest().stateCode());
                    s.setString(3, source.sourceId());
                    s.setString(4, "{\"csvHeader\":\"" + HEADER + "\",\"rawLine\":\"" + line + "\"}");
                    s.setString(5, "{\"sourceEntity\":\"" + source.sourceId() + "\"}");
                    s.setString(6, version);
                    s.setString(7, source.sourceSchemaVersion());
                    s.setString(8, source.hopTemplate());
                    s.addBatch();
                }
                s.executeBatch();
            }
            c.commit();
        }
        return batch;
    }

    private String currentGolden(UUID golden) {
        return sql.text("select golden_payload::text from golden_resident_version where golden_resident_id=?"
                + " and effective_to is null", golden);
    }
}
