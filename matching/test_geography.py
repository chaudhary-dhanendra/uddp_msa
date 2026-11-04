import pandas as pd

from matching.config import load_geography_level, resident_match_settings
from matching.run import geography_lgd, normalise_records


def test_configured_geography_level_compares_known_values_without_penalising_absence(tmp_path):
    pack = tmp_path / "fixture"
    (pack / "matching").mkdir(parents=True)
    (pack / "state.yaml").write_text("packId: fixture\nmatching: [matching/resident.yaml]\n", encoding="utf-8")
    (pack / "matching" / "resident.yaml").write_text(
        "entity: Resident\nfields: [name, geography.village.lgd]\nthresholds: {autoMatch: 0.95, possibleMatch: 0.70}\n",
        encoding="utf-8")
    assert load_geography_level(tmp_path) == "village"

    same = {"extensions": {"uddp.geography": {"village": {"lgd": "500001"}}}}
    conflict = {"extensions": {"uddp.geography": {"village": {"lgd": "500002"}}}}
    missing = {"extensions": {"uddp.geography": {}}}
    assert geography_lgd(same, "village") == "500001"       # same known geography
    assert geography_lgd(conflict, "village") != geography_lgd(same, "village")  # conflicting known geography
    assert geography_lgd(missing, "village") is None          # missing is absent evidence, not a false code

    settings = resident_match_settings(geography_column="district_lgd")
    comparison = next(item for item in settings.comparisons if item.create_output_column_name() == "district_lgd")
    assert comparison.create_comparison_levels()[0].is_null_level is True
    records = normalise_records([("1", same), ("2", missing)], geography_level="village")
    assert pd.isna(records.iloc[1].district_lgd)
