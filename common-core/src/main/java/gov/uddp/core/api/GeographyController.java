package gov.uddp.core.api;

import gov.uddp.core.geography.GeographyService;
import gov.uddp.core.geography.LgdImportService;
import gov.uddp.core.geography.LgdSnapshotData;
import gov.uddp.core.geography.LgdSyncService;
import gov.uddp.core.geography.LgdUploadParser;
import gov.uddp.core.read.Page;
import gov.uddp.core.reference.ReferenceDataProvider;
import gov.uddp.core.reference.ReferenceDataService;
import gov.uddp.core.security.Audited;
import gov.uddp.core.security.CurrentUser;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The LGD geography master and reference data (Track F2): server-side search, ancestry, code validation and as-of
 * lookups, and the reference sets REFERENCE_LIST rules can name; sync status, run history, change log and the sync
 * schedule (Track F3). Only GEOGRAPHY_SYNC (platform administrators) may start a sync or change its schedule, and only
 * GEOGRAPHY_UPLOAD (platform administrators) may check or load an uploaded snapshot. The upload parameters are all
 * optional at the binding level and checked in the handler, so the permission check answers 403 before any 400.
 */
@RestController
public class GeographyController {
    private final GeographyService geography;
    private final gov.uddp.core.geography.GeographyCoverageService coverage;
    private final ReferenceDataService referenceData;
    private final LgdSyncService sync;
    private final LgdImportService imports;
    private final long maxUploadBytes;

    public GeographyController(GeographyService geography, ReferenceDataService referenceData, LgdSyncService sync,
                               gov.uddp.core.geography.GeographyCoverageService coverage, LgdImportService imports,
                               @Value("${uddp.geography.lgd.max-upload-mb:50}") long maxUploadMb) {
        this.imports = imports;
        this.maxUploadBytes = maxUploadMb * 1024 * 1024;
        this.geography = geography;
        this.referenceData = referenceData;
        this.sync = sync;
        this.coverage = coverage;
    }

    @GetMapping("/api/geography/entity-types")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public List<GeographyService.EntityType> geographyEntityTypes() {
        return geography.entityTypes();
    }

    /** Coverage per source and level (% resolved, % unknown); levels a source never supplies are omitted. */
    @GetMapping("/api/geography/coverage")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public List<gov.uddp.core.geography.GeographyCoverageService.LevelCoverage> geographyCoverage(
            @RequestParam(required = false) String stateCode, @RequestParam(required = false) String sourceSystem) {
        if (stateCode != null && !CurrentUser.scope().allows(stateCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown geography scope");
        }
        return coverage.coverage(stateCode, sourceSystem).stream()
                .filter(row -> CurrentUser.scope().allows(row.stateCode()))
                .toList();
    }

    /** Server-side search: by type, code or name, within a parent area, paged. */
    @GetMapping("/api/geography/entities")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public Page<GeographyService.GeoEntity> searchGeography(@RequestParam(required = false) String type,
                                                            @RequestParam(required = false) String q,
                                                            @RequestParam(required = false) String parentType,
                                                            @RequestParam(required = false) String parentCode,
                                                            @RequestParam(required = false) String status,
                                                            @RequestParam(required = false) Integer page,
                                                            @RequestParam(required = false) Integer size) {
        return geography.search(type, q, parentType, parentCode, status, page, size);
    }

    @GetMapping("/api/geography/entities/{type}/{code}")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public GeographyService.GeoEntity geographyEntity(@PathVariable String type, @PathVariable String code,
                                                      @RequestParam(required = false) LocalDate asOf) {
        var found = asOf == null ? geography.find(type, code) : geography.asOf(type, code, asOf);
        return found.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown LGD entity"));
    }

    @GetMapping("/api/geography/entities/{type}/{code}/ancestors")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public List<GeographyService.GeoEntity> geographyAncestors(@PathVariable String type, @PathVariable String code) {
        return geography.ancestors(type, code);
    }

    @GetMapping("/api/geography/entities/{type}/{code}/descendants")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public Page<GeographyService.GeoEntity> geographyDescendants(@PathVariable String type, @PathVariable String code,
                                                                 @RequestParam(required = false) String childType,
                                                                 @RequestParam(required = false) Integer page,
                                                                 @RequestParam(required = false) Integer size) {
        return geography.descendants(type, code, childType, page, size);
    }

    /** ACTIVE_VALID, KNOWN_INACTIVE or UNKNOWN, with parent consistency when a parent is given. */
    @GetMapping("/api/geography/validate")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public GeographyService.CodeValidation validateGeographyCode(@RequestParam String type, @RequestParam String code,
                                                                 @RequestParam(required = false) String parentType,
                                                                 @RequestParam(required = false) String parentCode) {
        return geography.validate(type, code, parentType, parentCode);
    }

    @GetMapping("/api/reference-data")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public List<ReferenceDataService.ReferenceSet> referenceSets() {
        return referenceData.sets();
    }

    @GetMapping("/api/reference-data/{referenceSet}/values")
    @PreAuthorize("hasAuthority('PERM_STATE_PACK_VIEW')")
    public List<ReferenceDataProvider.ReferenceValue> referenceValues(@PathVariable String referenceSet,
                                                                      @RequestParam(required = false) String q,
                                                                      @RequestParam(required = false) Integer limit) {
        return referenceData.lookup(referenceSet, q, limit == null ? 50 : Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/api/geography/sync/status")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public LgdSyncService.SyncStatus geographySyncStatus() {
        return sync.status();
    }

    @GetMapping("/api/geography/sync/runs")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public Page<LgdSyncService.SyncRun> geographySyncRuns(@RequestParam(required = false) Integer page,
                                                          @RequestParam(required = false) Integer size) {
        return sync.runs(page, size);
    }

    /** Starts a sync now; 409 while another sync runs. */
    @PostMapping("/api/geography/sync/runs")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_SYNC')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Audited(action = "LGD_MASTER_SYNC_START", resource = "LGD_SYNC_RUN")
    public LgdSyncService.SyncRun startGeographySync() {
        try {
            return sync.start("MANUAL", CurrentUser.username());
        } catch (LgdSyncService.SyncAlreadyRunningException running) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, running.getMessage());
        }
    }

    @GetMapping("/api/geography/sync/schedule")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public LgdSyncService.SyncSchedule geographySyncSchedule() {
        return sync.schedule().orElse(null);
    }

    @PutMapping("/api/geography/sync/schedule")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_SYNC')")
    @Audited(action = "LGD_SYNC_SCHEDULE_SAVE", resource = "LGD_SYNC_SCHEDULE")
    public LgdSyncService.SyncSchedule saveGeographySyncSchedule(@RequestBody LgdSyncService.SyncSchedule schedule) {
        try {
            return sync.saveSchedule(schedule, CurrentUser.username());
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, invalid.getMessage());
        }
    }

    /** The change log: ADDED, RENAMED, REPARENTED, INACTIVATED and REACTIVATED, newest first. */
    @GetMapping("/api/geography/changes")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_VIEW')")
    public Page<LgdSyncService.LgdChange> geographyChanges(@RequestParam(required = false) UUID snapshotId,
                                                           @RequestParam(required = false) String type,
                                                           @RequestParam(required = false) String code,
                                                           @RequestParam(required = false) String changeType,
                                                           @RequestParam(required = false) Integer page,
                                                           @RequestParam(required = false) Integer size) {
        return sync.changes(snapshotId, type, code, changeType, page, size);
    }

    /** A five-row example of the snapshot CSV format. */
    @GetMapping("/api/geography/snapshots/template")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_UPLOAD')")
    public ResponseEntity<String> snapshotTemplate() {
        String csv = "# asOf=2024-01-01\n"
                + "entityType,code,name,status,parentType,parentCode,effectiveFrom\n"
                + "STATE,01,Example State,ACTIVE,,,\n"
                + "DISTRICT,011,Example District,ACTIVE,STATE,01,\n"
                + "SUB_DISTRICT,0111,Example Sub-district,ACTIVE,DISTRICT,011,\n"
                + "VILLAGE,500001,Example Village,ACTIVE,SUB_DISTRICT,0111,\n"
                + "VILLAGE,500002,\"Example Village, North\",INACTIVE,SUB_DISTRICT,0111,2023-04-01\n";
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("lgd-snapshot-template.csv").build().toString())
                .body(csv);
    }

    /** Dry run: parses and validates the whole snapshot and diffs it against the active master; changes nothing. */
    @PostMapping("/api/geography/snapshots/validate")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_UPLOAD')")
    @Audited(action = "LGD_SNAPSHOT_VALIDATE", resource = "LGD_SNAPSHOT")
    public LgdImportService.SnapshotPreview validateSnapshot(@RequestParam(required = false) MultipartFile file,
                                                     @RequestParam(required = false) String asOf) {
        return imports.preview(parse(file, asOf));
    }

    /** Imports the uploaded snapshot as a sync run (trigger UPLOAD); 409 while another sync runs. Refusals are 400s. */
    @PostMapping("/api/geography/snapshots")
    @PreAuthorize("hasAuthority('PERM_GEOGRAPHY_UPLOAD')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Audited(action = "LGD_SNAPSHOT_UPLOAD", resource = "LGD_SYNC_RUN")
    public LgdSyncService.SyncRun uploadSnapshot(@RequestParam(required = false) MultipartFile file,
                                                 @RequestParam(required = false) String asOf,
                                                 @RequestParam(required = false) Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw new IllegalArgumentException("confirm=true is required to load a snapshot");
        }
        LgdSnapshotData snapshot = parse(file, asOf);
        try {
            return sync.startUpload(snapshot, CurrentUser.username());
        } catch (LgdSyncService.SyncAlreadyRunningException running) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, running.getMessage());
        }
    }

    private LgdSnapshotData parse(MultipartFile file, String asOf) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Choose a .csv or .json snapshot file");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new IllegalArgumentException("The file is larger than " + maxUploadBytes / (1024 * 1024) + " MB");
        }
        try {
            return LgdUploadParser.parse(file.getOriginalFilename(), file.getBytes(), asOf,
                    LocalDate.now(ZoneOffset.UTC));
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("The upload could not be read");
        }
    }
}
