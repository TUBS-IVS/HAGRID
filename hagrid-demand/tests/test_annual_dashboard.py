import json
import re

import numpy as np
import pandas as pd

from test_annual_store import annual_run  # noqa: F401  (module-scoped run fixture)


def test_dashboard_data_consistent(annual_run):
    from hagrid_demand.baseline.annual_dashboard import build_annual_dashboard_data

    data = build_annual_dashboard_data(annual_run)
    days, plz = data["days"], data["plz"]
    assert data["meta"]["year"] == 2025 and len(days["date"]) == 365 and len(data["meta"]["columns"]) == 14
    cube = np.asarray(data["plz_daily"]).reshape(len(days["date"]), len(plz["codes"]), 14)
    assert (cube.sum(axis=(1, 2)) == np.asarray(days["parcels"])).all()
    stored = pd.read_parquet(annual_run / "annual" / "days.parquet")
    assert int(stored.loc[pd.to_datetime(stored.date).dt.year.eq(2025), "parcels"].sum()) == int(cube.sum())
    assert len(data["geo"]["features"]) == len(plz["codes"])
    assert {row["carrier"] for row in data["weekday"]} == set(data["meta"]["carriers"])


def test_dashboard_html_written(annual_run, tmp_path):
    from hagrid_demand.baseline.annual_dashboard import write_annual_dashboard

    path = write_annual_dashboard(annual_run, tmp_path / "year.html")
    text = path.read_text(encoding="utf-8")
    assert "__HAGRID_ANNUAL_DATA__" not in text and "<title>Hannover Parcel Year</title>" in text[:8192]
    payload = re.search(r'<script id="hagrid-annual" type="application/json">(.*?)</script>', text, re.S).group(1)
    assert "</" not in payload
    assert set(json.loads(payload)) >= {"meta", "days", "plz", "plz_daily", "profiles", "weekday", "geo"}


def test_dashboard_standalone_and_artifact_variants(annual_run, tmp_path):
    from hagrid_demand.baseline.annual_dashboard import write_annual_dashboard

    standalone = write_annual_dashboard(annual_run, tmp_path / "local.html").read_text(encoding="utf-8")
    assert standalone.startswith("<!doctype html>") and '<meta charset="utf-8">' in standalone[:400]
    artifact = write_annual_dashboard(annual_run, tmp_path / "artifact.html", standalone=False).read_text(encoding="utf-8")
    assert artifact.startswith("<title>") and "<!doctype" not in artifact[:200]


def test_run_writes_annual_dashboard(annual_run):
    page = (annual_run / "annual_dashboard.html").read_text(encoding="utf-8")
    assert page.startswith("<!doctype html>") and "<title>Hannover Parcel Year</title>" in page[:8192]
