# Geography master

The LGD geography master is loaded only through snapshots (`LgdImportService`): stage, validate the whole snapshot,
diff against the active master, activate in one transaction. A row missing from a snapshot is never a deletion.

## Loading LGD

1. **Download** the directory from the LGD portal.
2. **Convert** it to the snapshot layout below with `scripts/lgd/convert_lgd_download.py` (not in the repo yet; until it
   is, produce the CSV by hand or with your own script).
3. **Upload** it on the **Geography master** page (`/admin/geography`, PLATFORM_ADMIN only, permission
   `GEOGRAPHY_UPLOAD`): choose the file, **Check file** (a dry run: row counts, errors with line numbers, and what
   would be added, renamed, moved or deactivated), then **Load into master** after confirming. The load takes the single
   sync slot (a second one is refused with 409) and appears in the run history as trigger `UPLOAD` with the file name.

Snapshot CSV (UTF-8; `GET /api/geography/snapshots/template` returns an example):

```
# asOf=2024-01-01
entityType,code,name,status,parentType,parentCode,effectiveFrom
STATE,01,Example State,ACTIVE,,,
DISTRICT,011,Example District,ACTIVE,STATE,01,
```

`status`, `parentType`, `parentCode` and `effectiveFrom` are optional columns; the `# asOf=` first line is optional
(the `asOf` form field overrides it; without either, today is used). JSON is also accepted: an array of rows, or
`{"asOf": "...", "entities": [...]}`.

## API

- `POST /api/geography/snapshots/validate` (multipart `file`, optional `asOf`): dry run, changes nothing.
- `POST /api/geography/snapshots` (multipart `file`, optional `asOf`, `confirm=true`): 202 with the sync run.
- `GET /api/geography/snapshots/template`.

All need `GEOGRAPHY_UPLOAD`, which is checked before the body (403 before 400). Limit `uddp.geography.lgd.max-upload-mb`
(default 50). The upload is held in memory for the run and stored nowhere but the database (staging rows, then the
master); the snapshot keeps the file name as its source label. Both POSTs are audited (`LGD_SNAPSHOT_VALIDATE`,
`LGD_SNAPSHOT_UPLOAD`).

See also [Held and rejected records](held-and-rejected-records.md): a row whose district or village is not in the loaded master is held or rejected with its reason code.
