"""scripts/e2e/list_sources.py derives every Hop parameter from State Pack YAML, for any pack directory."""
import importlib.util
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("list_sources", ROOT / "scripts" / "e2e" / "list_sources.py")
list_sources = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(list_sources)


def test_sample_packs_yield_one_generic_file_source_each_with_its_header():
    sources = list(list_sources.sources(ROOT / "state-packs", ROOT))
    assert [(s["packId"], s["stateCode"], s["sourceId"]) for s in sources] == [
        ("state-1", "ST1", "pds"), ("state-2", "ST2", "pension"), ("state-3", "ST3", "beneficiaries")]
    assert {s["hopTemplate"] for s in sources} == {"generic-file-ingest.hpl"}
    # The state-1 PDS register carries ration card columns since M15 (docs/benefit-domain.md).
    assert sources[0]["csvHeader"] == ("beneficiary_nm,dob,sex_cd,dist_cd,ration_no,mobile,card_type,card_status,"
                                       "card_issued_on,monthly_grain_kg,last_lifted_on,last_lifted_kg")
    assert all(Path(s["sourceFile"]).is_file() for s in sources)


def test_fixture_packs_work_without_any_code_change():
    sources = list(list_sources.sources(ROOT / "tests" / "fixtures" / "state-packs", ROOT))
    assert [s["packId"] for s in sources] == ["fx-alpha", "fx-beta"]
    assert sources[1]["csvHeader"] == "person,dob,gender,district,family,phone"


def test_xlsx_source_uses_the_excel_template_with_sheets_and_no_header(tmp_path):
    pack = tmp_path / "pk"
    (pack / "sources").mkdir(parents=True)
    (pack / "state.yaml").write_text(
        "packId: xl\nstateCode: XL\nversion: 1\nsources:\n  - sources/book.yaml\n", encoding="utf-8")
    (pack / "sources" / "book.yaml").write_text(
        "sourceId: book\nsourceType: file\nformat: xlsx\nhopTemplate: generic-file-ingest.hpl\n"
        "location: book.xlsx\nsheets:\n  S1: {}\n  S2: {}\n", encoding="utf-8")
    (tmp_path / "book.xlsx").write_bytes(b"PK\x03\x04")
    sources = list(list_sources.sources(tmp_path, tmp_path))
    assert len(sources) == 1
    assert sources[0]["hopTemplate"] == "generic-excel-ingest.hpl"
    assert sources[0]["sheets"] == "S1,S2"
    assert "csvHeader" not in sources[0]
