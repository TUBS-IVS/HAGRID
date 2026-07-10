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
