package gov.uddp.core.geography;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import gov.uddp.core.canonical.PostgresConnectionFactory;
import gov.uddp.core.read.Db;
import gov.uddp.core.security.AuditService;
import gov.uddp.core.testsupport.SyntheticLgdSnapshot;
import gov.uddp.core.testsupport.TestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Geography master upload: the dry run changes nothing and predicts what the import then does, an uploaded snapshot
 * takes the single running slot and is recorded as an UPLOAD run, and a bad snapshot leaves the master untouched.
 */
class LgdSnapshotUploadTest {
    private static final LocalDate TODAY = LocalDate.parse("2025-06-01");

    private final PostgresConnectionFactory connections = TestDatabase.freshMigrated();
    private final Db reads = new Db(connections, new ObjectMapper());
    private final LgdImportService imports = new LgdImportService(connections, new ObjectMapper());
    private final List<Runnable> queued = new ArrayList<>();
    private final LgdSyncService sync = new LgdSyncService(connections, reads, new FileLgdSourceClient(""), imports,
            new AuditService(connections, new ObjectMapper()), Clock.systemUTC(), queued::add, Duration.ofHours(24),
            Duration.ofHours(6));

    private static LgdSnapshotData parse(String name, String text) {
        return LgdUploadParser.parse(name, text.getBytes(StandardCharsets.UTF_8), null, TODAY);
    }

    private LgdSyncService.SyncRun upload(String name, String text) {
        var started = sync.startUpload(parse(name, text), "admin");
        assertEquals("RUNNING", started.status());
        queued.removeFirst().run();
        return sync.run(started.runId()).orElseThrow();
    }

    private long entities() {
        return reads.count("select count(*) from lgd_entity");
    }

    @Test
    void theDryRunChangesNothingAndPredictsTheImport() {
        var preview = imports.preview(parse("synthetic.csv", SyntheticLgdSnapshot.csv()));
        assertEquals(0, entities());
        assertEquals(0, reads.count("select count(*) from lgd_snapshot"));
        assertEquals(SyntheticLgdSnapshot.ROWS, preview.rows());
        assertEquals(List.of(), preview.errors());
        assertEquals(Map.of("STATE", 2, "DISTRICT", 8, "SUB_DISTRICT", 190), preview.rowsByType());
        assertEquals(SyntheticLgdSnapshot.ROWS, preview.changeTotals().get("ADDED"));
        assertEquals(20, preview.samples().get("ADDED").size(), "at most 20 samples per change type");

        var run = upload("synthetic.csv", SyntheticLgdSnapshot.csv());
        assertEquals("SUCCEEDED", run.status());
        assertEquals("UPLOAD", run.triggerKind());
        assertEquals("admin", run.actor());
        assertEquals("synthetic.csv", run.sourceLabel());
        assertEquals(preview.changeTotals().get("ADDED"), run.changeCount());
        assertEquals(SyntheticLgdSnapshot.ROWS, entities());
        assertEquals("synthetic.csv", reads.one("select source_label from lgd_snapshot where status='ACTIVE'",
                r -> r.getString(1)).orElseThrow());
    }

    @Test
    void theFixtureActivatesAndTheSameFileAgainIsUnchanged() throws Exception {
        String fixture = Files.readString(LgdMasterImportTest.FIXTURE);
        var first = upload("lgd-snapshot-v1.csv", fixture);
        assertEquals("SUCCEEDED", first.status());
        assertEquals(13, first.changeCount());

        var again = upload("lgd-snapshot-v1.csv", fixture);
        assertEquals("UNCHANGED", again.status());
        assertEquals(0, again.changeCount());
        assertEquals(first.snapshotId(), sync.status().activeSnapshotId());
        assertEquals(0, imports.preview(parse("lgd-snapshot-v1.csv", fixture)).changeTotals().size());
    }

    @Test
    void aBadParentFailsTheRunAndLeavesTheMasterUntouched() throws Exception {
        var good = upload("good.csv", Files.readString(LgdMasterImportTest.FIXTURE));
        long before = entities();

        String bad = "entityType,code,name,status,parentType,parentCode\nVILLAGE,500009,Orphan,ACTIVE,SUB_DISTRICT,0000\n";
        var preview = imports.preview(parse("bad.csv", bad));
        assertEquals(1, preview.errors().size());
        assertEquals(2, preview.errors().getFirst().line());
        assertTrue(preview.errors().getFirst().message().contains("unknown"));
        assertEquals(0, preview.changeTotals().size(), "no diff for an invalid snapshot");

        var failed = upload("bad.csv", bad);
        assertEquals("FAILED", failed.status());
        assertTrue(failed.error().contains("line 2"));
        assertEquals(good.snapshotId(), sync.status().activeSnapshotId());
        assertEquals(before, entities());
    }

    @Test
    void aRenamedDistrictShowsAsARenameAndErrorLinesAreFileLines() throws Exception {
        upload("v1.csv", Files.readString(LgdMasterImportTest.FIXTURE));
        String renamed = Files.readString(LgdMasterImportTest.FIXTURE).replace("North District", "Northern District");
        var preview = imports.preview(parse("v2.csv", renamed));
        assertEquals(1, preview.changeTotals().get("RENAMED"));
        assertEquals(1, preview.changeTotals().size());
        var change = preview.samples().get("RENAMED").getFirst();
        assertEquals("DISTRICT", change.entityType());
        assertEquals("North District", change.oldValue());
        assertEquals("Northern District", change.newValue());
        assertEquals(Map.of("RENAMED", 1), preview.changesByType().get("DISTRICT"));

        // line 1 is the asOf comment and line 2 the header, so the third data row is file line 5
        String broken = "# asOf=2024-01-01\nentityType,code,name,status\nSTATE,1,A,ACTIVE\nSTATE,2,B,ACTIVE\nSTATE,,C,ACTIVE\n";
        assertEquals(5, imports.preview(parse("broken.csv", broken)).errors().getFirst().line());
    }

    @Test
    void aSecondUploadWhileOneRunsIsRefused() {
        sync.startUpload(parse("one.csv", SyntheticLgdSnapshot.csv()), "admin");
        assertThrows(LgdSyncService.SyncAlreadyRunningException.class,
                () -> sync.startUpload(parse("two.csv", SyntheticLgdSnapshot.csv()), "admin"));
        assertThrows(LgdSyncService.SyncAlreadyRunningException.class, () -> sync.start("MANUAL", "admin"));
    }

    @Test
    void theParserRefusesUnusableFiles() {
        byte[] ok = "entityType,code,name\nSTATE,1,A\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(1, LgdUploadParser.parse("a.CSV", ok, null, TODAY).rows().size());
        assertEquals(TODAY, LgdUploadParser.parse("a.csv", ok, null, TODAY).asOf());
        assertEquals(LocalDate.parse("2024-02-03"), LgdUploadParser.parse("a.csv", ok, "2024-02-03", TODAY).asOf());

        assertTrue(message(() -> LgdUploadParser.parse("a.txt", ok, null, TODAY)).contains("Unsupported file type"));
        assertTrue(message(() -> LgdUploadParser.parse("a.csv", new byte[] {(byte) 0xE9, 'x', '\n'}, null, TODAY))
                .contains("UTF-8"));
        assertTrue(message(() -> LgdUploadParser.parse("a.csv", "STATE,1,A\n".getBytes(StandardCharsets.UTF_8), null,
                TODAY)).contains("header"));
        assertTrue(message(() -> LgdUploadParser.parse("a.csv", "x,code,name\n".getBytes(StandardCharsets.UTF_8), null,
                TODAY)).contains("entityType"));
        assertTrue(message(() -> LgdUploadParser.parse("a.csv", ok, "31-01-2024", TODAY)).contains("asOf"));
        assertTrue(message(() -> LgdUploadParser.parse("a.csv",
                "entityType,code,name,effectiveFrom\nSTATE,1,A,soon\n".getBytes(StandardCharsets.UTF_8), null, TODAY))
                .contains("line 2"));
        assertTrue(message(() -> LgdUploadParser.parse("a.json", "{nope".getBytes(StandardCharsets.UTF_8), null, TODAY))
                .contains("JSON"));
        assertEquals(1, LgdUploadParser.parse("a.json",
                "[{\"entityType\":\"STATE\",\"code\":\"1\",\"name\":\"A\"}]".getBytes(StandardCharsets.UTF_8), null,
                TODAY).rows().size());
    }

    private static String message(Runnable action) {
        return assertThrows(IllegalArgumentException.class, action::run).getMessage();
    }
}
