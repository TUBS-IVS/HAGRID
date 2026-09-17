# -*- coding: utf-8 -*-
"""chi_detour.py -- the per-segment detour distribution that replaces the saturating
chi block counters (2026-08-10, METHODS-LOG 2.31).

The counters could only show the gate is ACTIVE (chi_blocked_segments == segments_submitted
in every run, so the expired-bucket attribution is an identity). These rows show whether it
BINDS: the smallest achievable detour per segment is a lower bound on the chi that segment
would have needed, and the delivered-vs-expired comparison locates the sweep grid.
"""
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import chi_detour

HEADER = "segment;parcels;evaluations;min_detour_s;outcome"


def _write(tmp_path, prefix, *rows):
    path = tmp_path / (prefix + chi_detour.FILE_SUFFIX)
    path.write_text(HEADER + "\n" + "".join(r + "\n" for r in rows), encoding="utf-8")
    return path


def _long(tmp_path, prefix):
    return {r["kpi_name"]: r for r in chi_detour.extract(tmp_path, prefix)[0]}


def _dists(tmp_path, prefix):
    out = {}
    for r in chi_detour.extract(tmp_path, prefix)[1]:
        out.setdefault(r["series"], []).append((r["bin_lo"], r["bin_hi"], r["value"]))
    return out


# --------------------------------------------------------------------- Emissionspolitik

def test_missing_file_is_a_silent_no_op(tmp_path):
    """Jeder Nicht-1c-Lauf und jeder 1c-Lauf vor der Instrumentierung. Kein meta-Flag --
    das waere ein Fehlersignal fuer den Normalfall."""
    assert chi_detour.extract(tmp_path, "NOPE") == ([], [])


def test_header_only_file_is_a_no_op(tmp_path):
    """noParcels-Arm: es gibt legitim nichts zu messen."""
    _write(tmp_path, "EMPTY")
    assert chi_detour.extract(tmp_path, "EMPTY") == ([], [])


def test_has_detour_csv_uses_the_run_id_prefix(tmp_path):
    """Bug 89f1ee5 hatte genau das falsch: MATSim schreibt run-ID-praefixiert, ein
    Praedikat auf dem nackten Dateinamen liess den Extraktor auf JEDEM echten Lauf
    stillschweigend aus."""
    _write(tmp_path, "RUN_X", "parcel_a;1;1;100.0;delivered")
    assert chi_detour.has_detour_csv(tmp_path, SimpleNamespace(prefix="RUN_X"))
    assert not chi_detour.has_detour_csv(tmp_path, SimpleNamespace(prefix="RUN_Y"))


# --------------------------------------------------------------------- Buckets

def test_delivered_and_expired_are_reported_separately(tmp_path):
    _write(tmp_path, "R",
           "parcel_a;2;5;100.0;delivered",
           "parcel_b;3;4;300.0;delivered",
           "parcel_c;1;9;4000.0;window_expired",
           "parcel_d;1;7;4400.0;window_expired")
    k = _long(tmp_path, "R")

    assert k["chi_detour_segments_evaluated"]["value"] == 4
    assert k["chi_detour_evaluations_total"]["value"] == 25
    assert k["chi_detour_delivered_segments"]["value"] == 2
    assert k["chi_detour_expired_segments"]["value"] == 2
    assert k["chi_detour_delivered_median_s"]["value"] == pytest.approx(200.0)
    assert k["chi_detour_expired_median_s"]["value"] == pytest.approx(4200.0)
    # Die Kopfzahl: das billigste verfallene Segment. Liegt sie weit ueber chi, haette
    # kein plausibler Schwellenwert eines dieser Segmente zugestellt.
    assert k["chi_detour_expired_cheapest_s"]["value"] == pytest.approx(4000.0)


def test_late_deliveries_count_as_delivered(tmp_path):
    """Eine verspaetete Zustellung kam unter IRGENDEINEM chi durch -- das ist die
    Eigenschaft, um die es hier geht (delta selbst bleibt in-window, s. extract_shareduse)."""
    _write(tmp_path, "R",
           "parcel_a;1;1;100.0;delivered",
           "parcel_b;1;1;500.0;delivered_late")
    k = _long(tmp_path, "R")
    assert k["chi_detour_delivered_segments"]["value"] == 2
    assert k["chi_detour_delivered_median_s"]["value"] == pytest.approx(300.0)


def test_pending_open_is_not_evidence_about_chi(tmp_path):
    """Sein Fenster ist nie zugegangen. In den expired-Bucket zu zaehlen wuerde genau den
    Nenner aufblaehen, auf dem die Aussage steht."""
    _write(tmp_path, "R",
           "parcel_a;1;1;100.0;delivered",
           "parcel_b;1;1;9000.0;pending_open")
    k = _long(tmp_path, "R")
    assert "chi_detour_expired_segments" not in k
    assert k["chi_detour_outcome_pending_open"]["value"] == 1, \
        "aber sichtbar bleiben muss es -- sonst fehlt es lautlos in jeder Bilanz"


def test_empty_bucket_is_omitted_not_zero_filled(tmp_path):
    """M4-Konvention: ein 0.0-Median liest sich als 'diese Segmente haetten umsonst
    mitfahren koennen' statt als 'es gibt keine'."""
    _write(tmp_path, "R", "parcel_a;1;1;100.0;delivered")
    k = _long(tmp_path, "R")
    for name in ("chi_detour_expired_segments", "chi_detour_expired_median_s",
                 "chi_detour_expired_cheapest_s", "chi_detour_expired_mean_parcels"):
        assert name not in k, name


def test_outcome_census_covers_every_row(tmp_path):
    _write(tmp_path, "R",
           "parcel_a;1;1;100.0;delivered",
           "parcel_b;1;1;200.0;window_expired",
           "parcel_c;1;1;300.0;rejected_final",
           "parcel_d;1;1;400.0;pending_open")
    k = _long(tmp_path, "R")
    census = sum(v["value"] for name, v in k.items()
                 if name.startswith("chi_detour_outcome_"))
    assert census == k["chi_detour_segments_evaluated"]["value"]


def test_segment_size_per_bucket_answers_the_f1_question(tmp_path):
    """Kippen grosse Segmente noch strukturell heraus, nachdem die eigene Standzeit
    abgezogen ist? Die mittlere Segmentgroesse je Bucket ist der erste Blick darauf."""
    _write(tmp_path, "R",
           "parcel_a;2;1;100.0;delivered",
           "parcel_b;4;1;200.0;delivered",
           "parcel_c;18;1;5000.0;window_expired")
    k = _long(tmp_path, "R")
    assert k["chi_detour_delivered_mean_parcels"]["value"] == pytest.approx(3.0)
    assert k["chi_detour_expired_mean_parcels"]["value"] == pytest.approx(18.0)


# --------------------------------------------------------------------- Histogramme

def test_histograms_are_binned_and_sorted(tmp_path):
    _write(tmp_path, "R",
           "parcel_a;1;1;40.0;delivered",
           "parcel_b;1;1;90.0;delivered",
           "parcel_c;1;1;150.0;delivered",
           "parcel_d;1;1;4000.0;window_expired")
    d = _dists(tmp_path, "R")

    assert d["chi_detour_min_delivered"] == [(0.0, 100.0, 2), (100.0, 200.0, 1)]
    assert d["chi_detour_min_expired"] == [(4000.0, 4100.0, 1)]


def test_cumulative_read_gives_a_first_delta_of_chi(tmp_path):
    """Der eigentliche Zweck: 'wie viele Segmente haben ein Minimum <= x' aus EINEM Lauf.
    Bei chi=600 haetten hier 2 der 3 verfallenen Segmente durchgepasst, bei chi=200 keines."""
    _write(tmp_path, "R",
           "parcel_a;1;1;250.0;window_expired",
           "parcel_b;1;1;550.0;window_expired",
           "parcel_c;1;1;3000.0;window_expired")
    bins = _dists(tmp_path, "R")["chi_detour_min_expired"]

    def below(x):
        return sum(n for lo, hi, n in bins if hi <= x)

    assert below(200.0) == 0
    assert below(600.0) == 2
    assert below(4000.0) == 3


def test_no_histogram_for_an_empty_bucket(tmp_path):
    _write(tmp_path, "R", "parcel_a;1;1;100.0;delivered")
    assert "chi_detour_min_expired" not in _dists(tmp_path, "R")


def test_rows_within_the_channel_group(tmp_path):
    """kpi_group ist von common.row() schema-validiert -- 'channel' ist die Gruppe, in der
    die uebrigen 1c-Segment-KPIs stehen."""
    _write(tmp_path, "R", "parcel_a;1;1;100.0;delivered")
    for r in chi_detour.extract(tmp_path, "R")[0]:
        assert r["kpi_group"] == "channel", r["kpi_name"]
        assert r["source"] == "shareduse_detour_min"
