# tests/test_real_married250.py
"""Task 9: real-married250 integration smoke + schema-drift guard.

Two layers:
  (a) schema-drift guard -- runs offline/in CI against small verbatim heads
      of the real married250 files (tests/fixtures/real_heads/).
  (b) opt-in full-run smoke -- exercises the real on-disk married250 run,
      skipped when that run is absent from disk.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import carriers_parse as cp  # noqa: E402
import extract_freight_provider as efp  # noqa: E402

HEADS = Path(__file__).parent / "fixtures" / "real_heads"
REAL = Path(__file__).parent.parent / ".." / ".." / "hagrid-matsim-output" / \
    "DRT_BASELINE_13052025_married250_iter300_jsprit1000"
REAL = REAL.resolve()


def test_real_carrier_header_schema():
    # verbatim head keeps our TSV column names honest against schema drift
    tsv = (HEADS / "analysis" / "freight" / "TimeDistance_perCarrier.tsv").read_text(
        encoding="utf-8")
    header = tsv.splitlines()[0].split("\t")
    assert header[:2] == ["carrierId", "nuOfTours"]


def test_real_heads_provider_classification():
    carriers = cp.parse_carriers(
        HEADS / "DRT_BASELINE_13052025_married250.output_carriers.xml.gz")
    provs = {c.attrs.get("provider") for c in carriers}
    assert "dhl" in provs


@pytest.mark.skipif(not REAL.exists(), reason="married250 run not on disk")
def test_full_married250_provider_runs():
    rows = efp.extract(REAL, "DRT_BASELINE_13052025_married250")
    provs = {r["provider"] for r in rows if not r["provider"].startswith("type:")}
    assert {"dhl", "amazon", "hermes", "dpd", "gls", "ups", "fedex"} <= provs

    def _by(provider, name):
        for r in rows:
            if r["provider"] == provider and r["kpi_name"] == name:
                return r["value"]
        return None

    # real carrier ids match the TSV carrierIds exactly here (unlike the
    # drtrun fixture) -- the provider join must produce real km, not 0.
    assert _by("dhl", "km") > 0


@pytest.mark.skipif(not REAL.exists(), reason="married250 run not on disk")
def test_full_married250_page_budget(tmp_path):
    """Task 9 usability gate (spec 3.5): the real map-heavy married250 page
    renders with Leaflet + populated map layers and stays within the measured
    budget.

    Measured 2026-07-17: page 6.1 MB, map_data.json 5.5 MB (227 DRT vehicles,
    23146 PU/DO points, 67 LMD tours across 7 providers, 2789 heat points).
    User confirmed fast & responsive in-browser -> per the confirmed
    "usability, not bytes" decision the MEASURED size wins; 6.1 MB rounds up to
    a 7 MB asserted ceiling. (The map-FREE page keeps its own tight 2 MB guard
    in test_render.py -- that assert is unchanged.)
    """
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    import build_kpis  # noqa: E402
    out = build_kpis.build(REAL, out_dir=tmp_path)
    html = (out / "kpi_dashboard.html").read_text(encoding="utf-8")
    # maps actually rendered (not the --no-events / map-free path)
    assert "Leaflet 1.9.4" in html
    assert 'id="map_drt_m0"' in html and 'id="map_lmd_m0"' in html
    assert (out / "map_data.json").exists()
    # the locked budget: fast at 6.1 MB measured -> 7 MB ceiling
    assert len(html.encode("utf-8")) < 7_000_000
