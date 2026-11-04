"""Print every State Pack file source as JSON lines for the e2e scripts.

Generic: everything comes from each pack's state.yaml and sources/*.yaml; there is no per-state logic.
Each line carries the technical Hop parameters for one source, plus the CSV header (the source file's
first line) that generic-file-ingest.hpl expects as CSV_HEADER.

Usage: python scripts/e2e/list_sources.py <state-pack-directory> <repository-root>
"""
import json
import sys
from pathlib import Path

import yaml


def sources(pack_directory, repository_root):
    for manifest_path in sorted(Path(pack_directory).glob("*/state.yaml")):
        manifest = yaml.safe_load(manifest_path.read_text(encoding="utf-8"))
        for relative in manifest.get("sources") or []:
            source = yaml.safe_load((manifest_path.parent / relative).read_text(encoding="utf-8"))
            if source.get("sourceType") != "file":
                raise SystemExit(f"{manifest['packId']} {relative}: only file sources are supported by the e2e script")
            location = (Path(repository_root) / source["location"]).resolve()
            if source.get("format") == "xlsx":
                # Excel workbooks have no text header; generic-excel-ingest.hpl takes FILE and SHEETS instead.
                sheets = source.get("sheets") or {}
                yield {
                    "packId": manifest["packId"],
                    "stateCode": manifest["stateCode"],
                    "statePackVersion": str(manifest["version"]),
                    "sourceId": source["sourceId"],
                    "sourceType": source["sourceType"],
                    "sourceSchemaVersion": str(source.get("sourceSchemaVersion") or ""),
                    "hopTemplate": "generic-excel-ingest.hpl",
                    "sourceFile": str(location),
                    "sheets": ",".join(sheets) if sheets else "*",
                }
                continue
            with location.open(encoding="utf-8") as handle:
                header = handle.readline().rstrip("\r\n")
            yield {
                "packId": manifest["packId"],
                "stateCode": manifest["stateCode"],
                "statePackVersion": str(manifest["version"]),
                "sourceId": source["sourceId"],
                "sourceType": source["sourceType"],
                "sourceSchemaVersion": str(source.get("sourceSchemaVersion") or ""),
                "hopTemplate": source["hopTemplate"],
                "sourceFile": str(location),
                "csvHeader": header,
            }


if __name__ == "__main__":
    for item in sources(sys.argv[1], sys.argv[2]):
        print(json.dumps(item))
