import hashlib
import json

import numpy as np
import pandas as pd
import pytest


def test_source_classification_and_packaged_series_values():
    """The published series preserves the historical anchors and labels projections."""
    from hagrid_demand.baseline.series import build_series
    from hagrid_demand.baseline.sources import packaged_series_inputs

    series = build_series(packaged_series_inputs(), list(range(2021, 2031)),
                          volume_fit_policy="observed_only")
    assert series["b2b"].set_index("year").loc[2021, "share"] == .23
    volume = series["volume"].set_index("year")
    assert volume.loc[2021, "value"] == 4.51e9
    assert not volume.loc[volume.index.to_series().between(2024, 2028), "status"].eq("observed").any()


def test_market_series_is_a_nonnegative_simplex_each_year():
    """The reconstructed carrier projection remains a usable probability distribution."""
    from hagrid_demand.baseline.series import build_series
    from hagrid_demand.baseline.sources import packaged_series_inputs

    market = build_series(packaged_series_inputs(), list(range(2014, 2031)),
                          volume_fit_policy="observed_only")["market"]
    assert (market["share"] >= 0).all()
    assert np.allclose(market.groupby("year")["share"].sum().to_numpy(), 1.0)


def test_raw_sources_normalize_all_52_iso_weeks_and_record_hash(tmp_path):
    """Fresh source preparation derives the notebook profile without a historical output."""
    from hagrid_demand.baseline.sources import prepare_sources

    source = tmp_path / "Parcels19_20_21_inter.xlsx"
    weekly = pd.DataFrame({
        "week": list(range(1, 53)),
        "2019": np.arange(1, 53),
        "2020": np.arange(2, 54),
        "2021": np.arange(3, 55),
    })
    weekly.to_excel(source, sheet_name="Tabelle1", index=False)
    prepared = prepare_sources({"source_mode": "raw", "input_dir": str(tmp_path)}, tmp_path / "out")

    profile = prepared["weekly_profile"]
    assert profile["week"].tolist() == list(range(1, 53))
    assert (profile["relative_volume"] > 0).all()
    assert profile["relative_volume"].mean() == pytest.approx(1.0)
    assert prepared["sources"]["weekly_source_sha256"] == hashlib.sha256(source.read_bytes()).hexdigest()


def test_missing_weekly_source_stops_fresh_run(tmp_path):
    """Raw preparation never substitutes a stale weekly profile for a missing source."""
    from hagrid_demand.baseline.sources import prepare_sources

    with pytest.raises(FileNotFoundError, match="Parcels19_20_21_inter.xlsx"):
        prepare_sources({"source_mode": "raw", "input_dir": str(tmp_path)}, tmp_path / "out")


def test_foundation_reader_verifies_artifact_hashes_and_required_columns(tmp_path):
    """Foundation mode reads a completed artifact, not an unverified path."""
    from hagrid_demand.baseline.sources import read_foundation

    sites = tmp_path / "sites.csv"
    sites.write_text("site_id,recipient_type\na,private\n", encoding="utf-8")
    manifest = {
        "artifacts": {"sites.csv": hashlib.sha256(sites.read_bytes()).hexdigest()},
        "schemas": {"sites.csv": ["site_id", "recipient_type"]},
    }
    (tmp_path / "artifact_manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    tables = read_foundation(tmp_path)
    assert tables["sites.csv"].columns.tolist() == ["site_id", "recipient_type"]
    sites.write_text("site_id,recipient_type\nb,business\n", encoding="utf-8")
    with pytest.raises(ValueError, match="hash"):
        read_foundation(tmp_path)


def test_failed_volume_fit_is_explicit_instead_of_a_fallback_curve():
    """Insufficient observed support must remain visible to callers."""
    from hagrid_demand.baseline.series import build_series
    from hagrid_demand.baseline.sources import packaged_series_inputs

    inputs = packaged_series_inputs()
    inputs["volume_inputs"]["anchors"] = [{"year": 2023, "value": 4e9, "status": "observed"}]
    volume = build_series(inputs, [2030], volume_fit_policy="observed_only")["volume"].iloc[0]
    assert volume["status"] == "fit_failed"
    assert np.isnan(volume["value"])


def test_b2b_forecast_uses_the_notebook_relative_year_sigmoid():
    """The bounded sigmoid keeps its 2009-relative independent variable."""
    from hagrid_demand.baseline.series import build_series
    from hagrid_demand.baseline.sources import packaged_series_inputs

    result = build_series(packaged_series_inputs(), [2024], volume_fit_policy="observed_only")["b2b"]
    assert result.loc[0, "share"] == pytest.approx(0.2209639502)
