"""Tests for audit_delivery_rate (Task 5, hannover-consistency-fixes)."""
import gzip
import math
import random
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import audit_delivery_rate as adr  # noqa: E402


def _service(sid, amount, typ):
    b2b, b2c = (amount, 0) if typ == "B2B" else (0, amount)
    return (f'<service id="{sid}" to="1" capacityDemand="{amount}"><attributes>'
            f'<attribute name="b2b" class="java.lang.Integer">{b2b}</attribute>'
            f'<attribute name="b2c" class="java.lang.Integer">{b2c}</attribute>'
            f'<attribute name="type" class="x$ParcelType">{typ}</attribute>'
            f'</attributes></service>')


def _carrier(cid, provider, parcels, missed, services, bias=None):
    b = (f'<attribute name="dailyDeliveryBias" class="java.lang.Double">{bias}</attribute>'
         if bias is not None else "")
    return (f'<carrier id="{cid}"><attributes>{b}'
            f'<attribute name="missedParcels" class="java.lang.Integer">{missed}</attribute>'
            f'<attribute name="numberOfParcels" class="java.lang.Integer">{parcels}</attribute>'
            f'<attribute name="provider" class="java.lang.String">{provider}</attribute>'
            f'</attributes><services>{"".join(services)}</services></carrier>')


def _write(path, carriers):
    xml = '<?xml version="1.0"?><carriers>' + "".join(carriers) + "</carriers>"
    with gzip.open(path, "wt", encoding="utf-8") as f:
        f.write(xml)


# ---------------------------------------------------------------- constants

def test_targets_exact_values():
    assert adr.TARGETS == {"dhl": 94, "gls": 91, "hermes": 91, "dpd": 89,
                           "ups": 89, "amazon": 93, "fedex": 89}


# ---------------------------------------------------------------- formulas (hand computed)

def test_expected_L_hand_value():
    # 0.8*91 + 0.2*99 = 92.6
    assert adr.expected_L(0.2, 91) == pytest.approx(92.6)


def test_expected_H_hand_value():
    # b = (91 - 0.2*100)/0.8 = 88.75 ; 0.2*99 + 0.8*88.75 = 90.8
    assert adr.expected_H(0.2, 91, "gls") == pytest.approx(90.8)


def test_expected_H_ups_fedex_keep_target_as_b2c_rate():
    # Controller ruling 1: UPS/FedEx exception, = expected_L semantics
    assert adr.expected_H(0.2, 89, "ups") == pytest.approx(0.8 * 89 + 0.2 * 99)
    assert adr.expected_H(0.5, 89, "fedex") == pytest.approx(0.5 * 89 + 0.5 * 99)


def test_expected_exact_with_bias_and_cap():
    assert adr.expected_L_exact(0.2, 91, 2.0) == pytest.approx(0.8 * 93 + 0.2 * 99)
    # cap 98
    assert adr.expected_L_exact(0.0, 94, 5.0) == pytest.approx(98.0)
    # floor 0
    assert adr.expected_L_exact(0.0, 91, -200.0) == pytest.approx(0.0)


# ---------------------------------------------------------------- Java ports

def test_string_hash_matches_java():
    assert adr.java_string_hash("dhl") == 99432
    assert adr.java_string_hash("amazon") == -1414265340
    assert adr.java_string_hash("fedex") == 97307384
    assert adr.java_string_hash("") == 0
    assert adr.java_string_hash("district_1c_7") == -919514213
    assert adr.java_string_hash("ÄÖü_x") == 187630627


# reference values computed with JDK 21 jshell (new Random(4711+hash).nextGaussian() ...)
JREF = {
    "dhl": (0.8665392396772023, 0.19529938171694003, 0.9443480638133428),
    "amazon": (-0.39958546264616573, 0.8367966905613115, 0.5220376997332007),
    "hermes": (-1.4835631017796942, -0.9246381834478136, 0.1584129361507568),
    "dpd": (0.18281543619457924, 0.957604283530959, 0.6314537093213426),
    "gls": (0.38083360053690196, 0.24572424143371574, 0.7289253511276009),
    "ups": (-1.4453418839099776, -2.383606108097662, 0.0773679710183025),
    "fedex": (-2.155759978875491, -0.9306991487137372, 0.1081963410206811),
}


@pytest.mark.parametrize("p", sorted(JREF))
def test_java_random_matches_jshell(p):
    r = adr.JavaRandom(4711 + adr.java_string_hash(p))
    g1, g2, d1 = JREF[p]
    assert r.next_gaussian() == pytest.approx(g1, abs=1e-15, rel=0)
    assert r.next_gaussian() == pytest.approx(g2, abs=1e-15, rel=0)  # cached second value
    assert r.next_double() == pytest.approx(d1, abs=1e-15, rel=0)


def test_java_random_seed42_sequence():
    r = adr.JavaRandom(42)
    assert r.next_gaussian() == pytest.approx(1.1419053154730547, abs=1e-15, rel=0)
    assert r.next_gaussian() == pytest.approx(0.9194079489827879, abs=1e-15, rel=0)
    assert r.next_gaussian() == pytest.approx(-0.9498666368908959, abs=1e-15, rel=0)
    assert r.next_double() == pytest.approx(0.6655489517945736, abs=1e-15, rel=0)
    assert r.next_int() == -415012931


def test_lausitz_bias_is_first_gaussian_times_sigma():
    assert adr.lausitz_bias("dhl") == pytest.approx(0.8665392396772023 * 2.5)
    assert adr.lausitz_bias("hermes") == pytest.approx(-1.4835631017796942 * 5.0)


# ---------------------------------------------------------------- parsing

def test_parse_carriers_and_reported_rate(tmp_path):
    f = tmp_path / "c.xml.gz"
    # gls: 20 % B2B (20 of 100), 7 missed -> reported 93 ; two carriers for gls (Hannover per-PLZ)
    c1 = _carrier("gls_1", "gls", 60, 4, [_service("a", 12, "B2B"), _service("b", 48, "B2C")], bias=1.0)
    c2 = _carrier("gls_2", "gls", 40, 3, [_service("c", 8, "B2B"), _service("d", 32, "B2C")], bias=-3.0)
    c3 = _carrier("dhl_1", "dhl", 10, 1, [_service("e", 10, "B2C")], bias=0.5)
    sup = _carrier("supply_x", "dhl", 99, 9, [_service("f", 99, "B2C")], bias=0.0)
    _write(f, [c1, c2, c3, sup])
    rows = adr.audit_hannover(f)
    by = {r["provider"]: r for r in rows}
    assert set(by) == {"gls", "dhl"}  # supply_* skipped
    g = by["gls"]
    assert g["parcels"] == 100 and g["b2b_parcels"] == 20 and g["missed"] == 7
    assert g["reported_rate"] == pytest.approx(93.0)
    assert g["target_rate"] == 91
    assert g["expected_H"] == pytest.approx(90.8)
    assert g["expected_L"] == pytest.approx(92.6)
    assert g["bias_pp"] == pytest.approx((1.0 * 60 + -3.0 * 40) / 100)
    # per-carrier exact: b = 88.75 ; (12*99 + 48*min(98,88.75+1))/60 etc.
    e1 = (12 * 99 + 48 * 89.75) / 60 * 60
    e2 = (8 * 99 + 32 * 85.75) / 40 * 40
    assert g["expected_H_exact"] == pytest.approx((e1 + e2) / 100)


def test_lausitz_rows_use_shapefile_share_and_exact_bias(tmp_path):
    f = tmp_path / "l.xml.gz"
    _write(f, [_carrier("dhl", "dhl", 100, 6, [_service("x", 100, "B2C")]),
               _carrier("district_7", "dhl", 50, 1, [_service("y", 50, "B2C")])])
    rows = adr.audit_lausitz(f, {"dhl": (1000, 200)})  # shp: 1000 parcels, 200 b2b
    assert [r["provider"] for r in rows] == ["dhl"]  # district carrier skipped
    r = rows[0]
    assert r["b2b_parcels"] == pytest.approx(0.2 * 100)
    assert r["expected_L"] == pytest.approx(0.8 * 94 + 0.2 * 99)
    bias = adr.lausitz_bias("dhl")
    assert r["expected_L_exact"] == pytest.approx(0.8 * min(98, 94 + bias) + 0.2 * 99)
    assert r["bias_pp"] in ("", None)


def test_lausitz_prefers_stored_bias_attribute(tmp_path):
    f = tmp_path / "ls.xml.gz"
    _write(f, [_carrier("dhl", "dhl", 100, 6, [_service("x", 100, "B2C")], bias=-1.5)])
    r = adr.audit_lausitz(f, {"dhl": (1000, 200)})[0]
    assert r["bias_pp"] == pytest.approx(-1.5)
    assert r["expected_L_exact"] == pytest.approx(0.8 * min(98, 94 - 1.5) + 0.2 * 99)
    # the Java-Random port stays available as an independent cross-check
    assert r["bias_pp_reconstructed"] == pytest.approx(adr.lausitz_bias("dhl"))


def _raw_service(sid, cap, typ, b2b=None, b2c=None):
    a = f'<attribute name="type" class="x$ParcelType">{typ}</attribute>'
    if b2b is not None:
        a += f'<attribute name="b2b" class="java.lang.Integer">{b2b}</attribute>'
        a += f'<attribute name="b2c" class="java.lang.Integer">{b2c}</attribute>'
    return f'<service id="{sid}" to="1" capacityDemand="{cap}"><attributes>{a}</attributes></service>'


def test_mixed_services_use_b2b_attribute_not_type(tmp_path):
    f = tmp_path / "m.xml.gz"
    svcs = [_raw_service("a", 10, "MIXED", 7, 3),   # merged: pre-merge split in the attributes
            _raw_service("b", 10, "B2C", 0, 10),
            _raw_service("c", 5, "C2C"),            # no attributes, not B2B -> non-B2B
            _raw_service("d", 5, "B2B")]            # type-only fallback -> B2B
    _write(f, [_carrier("gls_1", "gls", 30, 2, svcs, bias=0.0)])
    r = adr.audit_hannover(f)[0]
    assert r["b2b_parcels"] == 7 + 5
    assert r["svc_parcels"] == 30


def test_replay_missed_follows_draw_order():
    dl = [(True, 3), (False, 4), (False, 2)]
    r = adr.JavaRandom(4711 + adr.java_string_hash("gls"))
    bias = r.next_gaussian() * 5.0
    rate = min(98.0, 91 + bias)
    exp = 0
    for b2b, a in dl:
        for _ in range(a):
            if r.next_double() * 100.0 > (99.0 if b2b else rate):
                exp += 1
    got, b = adr.replay_missed("gls", dl)
    assert got == exp and b == pytest.approx(bias)


# ---------------------------------------------------------------- positive control

def test_positive_control_reported_matches_expected_L(tmp_path):
    """Synthetic file whose missed count is drawn (seeded) from expected_L: reported must
    be within 3 binomial sigma of expected_L."""
    rng = random.Random(123)
    n, share, target = 20000, 0.2, 91
    exp = adr.expected_L(share, target)
    missed = sum(1 for _ in range(n) if rng.random() * 100 > exp)
    f = tmp_path / "p.xml.gz"
    _write(f, [_carrier("gls", "gls", n, missed,
                        [_service("a", int(n * share), "B2B"), _service("b", n - int(n * share), "B2C")])])
    r = adr.audit_lausitz(f, {"gls": (n, int(n * share))})[0]
    sigma = 100 * math.sqrt(exp / 100 * (1 - exp / 100) / n)
    assert abs(r["reported_rate"] - exp) < 3 * sigma
    # and it is clearly NOT the bare target (semantic offset 1.6 pp >> sigma ~0.2)
    assert abs(r["reported_rate"] - target) > 3 * sigma


# ---------------------------------------------------------------- degenerate input (final review M3)

def test_hannover_carrier_without_bias_leaves_exact_columns_empty(tmp_path, capsys):
    """A carrier without dailyDeliveryBias used to raise TypeError; now the closed-form columns are
    still computed, the bias-dependent ones stay empty and stderr names the gap."""
    f = tmp_path / "nb.xml.gz"
    _write(f, [_carrier("gls_1", "gls", 60, 4, [_service("a", 12, "B2B"), _service("b", 48, "B2C")], bias=1.0),
               _carrier("gls_2", "gls", 40, 3, [_service("c", 8, "B2B"), _service("d", 32, "B2C")])])
    r = adr.audit_hannover(f)[0]
    assert r["bias_pp"] == "" and r["expected_H_exact"] == ""
    assert r["expected_H"] == pytest.approx(90.8) and r["reported_rate"] == pytest.approx(93.0)
    err = capsys.readouterr().err
    assert "lack dailyDeliveryBias" in err and "gls_2" in err
    adr.write_csv([r], tmp_path / "nb.csv")  # empty cells serialize


def test_hannover_provider_without_service_parcels_is_skipped(tmp_path, capsys):
    """svc == 0 used to raise ZeroDivisionError; the provider is skipped with a message, the
    others are still audited."""
    f = tmp_path / "z.xml.gz"
    _write(f, [_carrier("gls_1", "gls", 0, 0, [], bias=1.0),
               _carrier("dhl_1", "dhl", 10, 1, [_service("e", 10, "B2C")], bias=0.5)])
    rows = adr.audit_hannover(f)
    assert [r["provider"] for r in rows] == ["dhl"]
    assert "SKIP provider gls" in capsys.readouterr().err


# ---------------------------------------------------------------- measure_all CLI (final review M4)

def test_measure_all_roots_default_to_repo_layout_and_are_overridable(tmp_path, monkeypatch):
    import measure_all
    repo = Path(__file__).resolve().parents[3]
    monkeypatch.delenv("HAGRID_OUTPUT_ROOT", raising=False)
    a = measure_all.parse_args([])
    assert a.runs_root == repo / "hagrid" / "simulation" / "hagrid-matsim-output"
    assert a.inputs_root == repo / "hagrid" / "simulation" / "input"
    monkeypatch.setenv("HAGRID_OUTPUT_ROOT", str(tmp_path / "ovr"))
    assert measure_all.parse_args([]).runs_root == tmp_path / "ovr"
    b = measure_all.parse_args(["--runs-root", str(tmp_path / "r"), "--inputs-root", str(tmp_path / "i")])
    assert b.runs_root == tmp_path / "r" and b.inputs_root == tmp_path / "i"
