# -*- coding: utf-8 -*-
"""Zustellquoten-Konvention ueber die drei Arme (Entscheidung 2026-08-10, METHODS-LOG 2.21).

Vorher trug jeder Arm eine eigene Quote auf einer eigenen Basis: Baseline `delivery_rate`
NETTO (Not-at-home-Overlay abgezogen), 1c `delivery_rate_total` BRUTTO, 1d gar keine --
und der Provider-Block der Baseline denselben Netto-Fehler wie die Headline. Unter aehnlich
klingenden Namen las der Drei-Arm-Vergleich damit netto gegen brutto und kehrte das
Vorzeichen der Kernaussage um (Baseline 93,6 % gegen 1c 93,7 % statt ~100 % gegen 93,7 %).

Diese Datei pinnt die Konvention selbst, nicht die Einzelwerte: EIN Name `delivery_rate`,
kpi_group "freight", Basis = zugestellt / Nachfrage, Overlay NICHT abgezogen. Der
Netto-Wert bleibt als `delivery_rate_net_overlay` erhalten, damit nichts verloren geht.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import extract_freight_provider as efp
import extract_modular
import extract_shareduse as es
from extract_freight import extract as extract_freight

FIX_BASELINE = Path(__file__).parent / "fixtures" / "drtrun"        # total 500 / missed 10 / unass. 5
FIX_LMD = Path(__file__).parent / "fixtures" / "mini_lmd"
FIX_SHAREDUSE = Path(__file__).parent / "fixtures" / "shareduse"


def _by_name(rows):
    return {r["kpi_name"]: r for r in rows}


# --------------------------------------------------------------------------- Baseline

def test_baseline_delivery_rate_is_operational_not_net():
    k = _by_name(extract_freight(FIX_BASELINE, "DRT_TEST"))
    total, missed, unassigned = 500, 10, 5

    assert k["delivery_rate"]["value"] == pytest.approx((total - unassigned) / total)
    assert k["delivery_rate"]["kpi_group"] == "freight"
    # Diskriminierend: waere das Overlay noch abgezogen, stuenden hier 485/500.
    assert k["delivery_rate"]["value"] != pytest.approx((total - missed - unassigned) / total)


def test_baseline_keeps_the_net_value_under_its_own_name():
    k = _by_name(extract_freight(FIX_BASELINE, "DRT_TEST"))
    assert k["delivery_rate_net_overlay"]["value"] == pytest.approx((500 - 10 - 5) / 500)
    assert "NOT comparable" in k["delivery_rate_net_overlay"]["source"], \
        "die Netto-Zeile muss im source-Feld sagen, dass sie nicht armuebergreifend lesbar ist"
    assert k["parcels_delivered_operational"]["value"] == 500 - 5


def test_baseline_cost_denominator_stays_net():
    """parcels_handled ist der Nenner von economics.freight_cost_per_parcel. Die
    Konventionsaenderung darf die EUR-Kennzahl nicht still mitverschieben -- die
    Kostenfunktion wird separat ueberarbeitet."""
    k = _by_name(extract_freight(FIX_BASELINE, "DRT_TEST"))
    assert k["parcels_handled"]["value"] == 410, "Load_perVehicle-Pfad, unveraendert"


# --------------------------------------------------------------------------- Provider

def test_provider_rates_follow_the_same_convention_as_the_headline():
    """Geschwister-Defekt: eine Provider-Tabelle, deren Quoten auf einer anderen Basis
    stehen als die Headline, widerspricht ihr sichtbar."""
    rows = [r for r in efp.extract(FIX_LMD, "MINI")]
    per_provider = {}
    for r in rows:
        per_provider.setdefault(r["provider"], {})[r["kpi_name"]] = r["value"]

    checked = 0
    for prov, k in per_provider.items():
        if "delivery_rate" not in k:
            continue
        total = k["parcels_total"]
        assert k["delivery_rate"] == pytest.approx(
            ((total - k["parcels_unassigned"]) / total) if total else 1.0), prov
        assert k["delivery_rate_net_overlay"] == pytest.approx(
            ((total - k["parcels_missed"] - k["parcels_unassigned"]) / total)
            if total else 1.0), prov
        assert k["delivery_rate"] >= k["delivery_rate_net_overlay"], prov
        checked += 1
    assert checked, "die Fixture muss mindestens einen Provider mit Quoten liefern"


# --------------------------------------------------------------------------- 1d Modular

def _modular_stats(tmp_path, prefix, **overrides):
    values = {
        "tours_planned": 10, "tours_expired_pending": 2, "tours_dispatched": 7,
        "tours_completed": 6, "tours_dispatched_incomplete": 1, "tours_pending_eod": 1,
        "parcels_planned": 500, "parcels_expired_pending": 80, "parcels_dispatched": 400,
        "parcels_served": 350, "parcels_dispatched_unserved": 50, "parcels_pending_eod": 20,
        "delta_parcels": 150, "swaps_completed": 13, "retooling_hours": 1.5,
        "deadhead_km_planned": 42.5, "service_km_planned": 120.0,
        "freight_vehicle_hours": 33.0, "tours_completed_late": 0, "parcels_served_late": 0,
        "tours_rejected_at_splice": 0,
        # Task-1-Block: parcels_demand == parcels_planned + parcels_unassigned_jsprit
        "parcels_demand": 530, "parcels_unassigned_jsprit": 30,
        "parcels_missed_overlay": 90, "max_parcels_per_tour": 99, "peak_concurrent_swaps": 3,
    }
    values.update(overrides)
    for drop in [k for k, v in values.items() if v is None]:
        del values[drop]
    path = tmp_path / (prefix + ".modular_tour_stats.csv")
    path.write_text("metric;value\n"
                    + "".join("%s;%s\n" % (k, v) for k, v in values.items()), encoding="utf-8")
    return path


def test_modular_emits_a_delivery_rate_at_all(tmp_path):
    _modular_stats(tmp_path, "MOD")
    k = _by_name(extract_modular.extract(tmp_path, "MOD"))
    assert "delivery_rate" in k, "1d trug vorher gar keine Quote, nur delta_share_*"
    assert k["delivery_rate"]["kpi_group"] == "freight"


def test_modular_rate_denominator_is_demand_not_planned(tmp_path):
    """jsprit-unzugeordnete Pakete sind ein echter Zustellausfall und gehoeren in den
    Nenner. 350/530, nicht 350/500 -- diskriminierend, die beiden liegen 6 pp auseinander."""
    _modular_stats(tmp_path, "MOD")
    k = _by_name(extract_modular.extract(tmp_path, "MOD"))
    assert k["delivery_rate"]["value"] == pytest.approx(350 / 530)
    assert k["delivery_rate"]["value"] != pytest.approx(350 / 500)


def test_modular_rate_does_not_deduct_the_overlay(tmp_path):
    """parcels_served ist hier schon brutto -- parcels_missed_overlay wird ausgewiesen,
    aber nirgends subtrahiert. Ein Overlay-Abzug wuerde 260/530 ergeben."""
    _modular_stats(tmp_path, "MOD")
    k = _by_name(extract_modular.extract(tmp_path, "MOD"))
    assert k["delivery_rate"]["value"] != pytest.approx((350 - 90) / 530)


def test_modular_rate_falls_back_to_planned_on_legacy_csv(tmp_path):
    """Alte CSVs ohne den Task-1-Block haben kein parcels_demand. Dann ist
    parcels_planned der Nenner -- dokumentierte Degradation, kein KeyError."""
    _modular_stats(tmp_path, "OLD", parcels_demand=None, parcels_unassigned_jsprit=None,
                   parcels_missed_overlay=None, max_parcels_per_tour=None,
                   peak_concurrent_swaps=None)
    k = _by_name(extract_modular.extract(tmp_path, "OLD"))
    assert k["delivery_rate"]["value"] == pytest.approx(350 / 500)


# --------------------------------------------------------------------------- 1c Shared-Use

def test_shareduse_emits_the_shared_name_alongside_its_own():
    k = _by_name(es.extract(FIX_SHAREDUSE, "SHAREDUSE_TEST"))
    assert k["delivery_rate"]["kpi_group"] == "freight"
    assert k["delivery_rate"]["value"] == pytest.approx(k["delivery_rate_total"]["value"])
    assert k["delivery_rate_total"]["kpi_group"] == "channel", \
        "die alte Zeile bleibt unveraendert stehen, damit 1c-Dashboards nicht brechen"


def test_shareduse_computes_the_rate_when_the_legacy_csv_lacks_it(tmp_path):
    path = tmp_path / "LEG.shareduse_channel_stats.csv"
    path.write_text("metric;value\nparcels_injected;1000\nparcels_delivered;900\n",
                    encoding="utf-8")
    k = _by_name(es.extract(tmp_path, "LEG"))
    assert k["delivery_rate"]["value"] == pytest.approx(0.9)
    assert "legacy" in k["delivery_rate"]["source"]


# --------------------------------------------------------------------------- Invariante

def test_all_three_arms_agree_on_name_group_and_unit(tmp_path):
    """Der eigentliche Punkt der Konvention: ein Vergleich darf EINEN Namen aufloesen."""
    _modular_stats(tmp_path, "MOD")
    per_arm = {
        "baseline": _by_name(extract_freight(FIX_BASELINE, "DRT_TEST")),
        "shareduse": _by_name(es.extract(FIX_SHAREDUSE, "SHAREDUSE_TEST")),
        "modular": _by_name(extract_modular.extract(tmp_path, "MOD")),
    }
    for arm, k in per_arm.items():
        assert "delivery_rate" in k, arm
        assert k["delivery_rate"]["kpi_group"] == "freight", arm
        assert k["delivery_rate"]["unit"] == "share", arm
        assert 0.0 <= k["delivery_rate"]["value"] <= 1.0, arm
