"""Audit of the reported delivery rate (missed-parcel overlay) per provider.

Compares the rate reported in a run's output_carriers.xml.gz against what the two overlay
implementations (Hannover CarrierGenerator, Lausitz LmdCarrierBuilder) are configured to give.
Diagnosis only; mirrors the Java formulas, does not change them.

Usage:
  python -u audit_delivery_rate.py --carriers <output_carriers.xml.gz> --area hannover --out x.csv
  python -u audit_delivery_rate.py --carriers <...> --area lausitz --demand-shp <demand.shp> \
      [--service-area-shp <drt-service-area.shp>] --out x.csv
"""
import argparse
import gzip
import math
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# HagridConfig.java:190-196 == LmdCarrierBuilder.java:80-82 (percent)
TARGETS = {"dhl": 94, "gls": 91, "hermes": 91, "dpd": 89, "ups": 89, "amazon": 93, "fedex": 89}
B2B_RATE = 99.0       # CarrierGenerator:879, LmdCarrierBuilder.B2B_DELIVERY_RATE
MAX_RATE = 98.0       # CarrierGenerator:875, LmdCarrierBuilder.MAX_EFFECTIVE_RATE
MISSED_DELIVERY_SEED = 4711  # LausitzFreightPreprocessor.java:71
KEEP_TARGET_AS_B2C = ("ups", "fedex")  # CarrierGenerator:292

COLUMNS = ["provider", "parcels", "b2b_parcels", "missed", "reported_rate", "target_rate",
           "expected_H", "expected_L", "bias_pp",
           # additions (controller ruling 3 and diagnostics)
           "expected_H_exact", "expected_L_exact", "bias_pp_reconstructed", "sigma_pp",
           "svc_parcels", "shp_parcels", "missed_replay"]


# ------------------------------------------------------------------ Java ports

def java_string_hash(s):
    h = 0
    for ch in s:
        # Java hashes UTF-16 code units; BMP-only input assumed (provider ids are ASCII)
        h = (31 * h + ord(ch)) & 0xFFFFFFFF
    return h - (1 << 32) if h >= (1 << 31) else h


class JavaRandom:
    """java.util.Random (48-bit LCG) incl. nextGaussian polar method with cached value."""
    _MULT = 0x5DEECE66D
    _MASK = (1 << 48) - 1

    def __init__(self, seed):
        self.seed = (seed ^ self._MULT) & self._MASK
        self._next_gaussian = None

    def _next(self, bits):
        self.seed = (self.seed * self._MULT + 0xB) & self._MASK
        v = self.seed >> (48 - bits)
        if bits == 32 and v >= (1 << 31):  # Java's (int) cast only matters for 32 bits
            v -= (1 << 32)
        return v

    def next_int(self):
        return self._next(32)

    def next_double(self):
        return ((self._next(26) << 27) + self._next(27)) * (1.0 / (1 << 53))

    def next_gaussian(self):
        if self._next_gaussian is not None:
            g, self._next_gaussian = self._next_gaussian, None
            return g
        while True:
            v1 = 2 * self.next_double() - 1
            v2 = 2 * self.next_double() - 1
            s = v1 * v1 + v2 * v2
            if 0 < s < 1:
                break
        mul = math.sqrt(-2 * math.log(s) / s)
        self._next_gaussian = v2 * mul
        return v1 * mul


def sigma_for(provider):
    return 2.5 if provider == "dhl" else 5.0


def lausitz_bias(provider):
    """Lausitz daily bias: first nextGaussian() of Random(4711 + provider.hashCode()) * sigma
    (LausitzFreightPreprocessor:180, LmdCarrierBuilder.buildCore:282)."""
    r = JavaRandom(MISSED_DELIVERY_SEED + java_string_hash(provider))
    return r.next_gaussian() * sigma_for(provider)


# ------------------------------------------------------------------ formulas (percent)

def _clip(x, lo, hi):
    return max(lo, min(hi, x))


def expected_L(b2b_share, target):
    """Lausitz: target is used directly as B2C rate, B2B 99, bias 0 (cap irrelevant for targets < 98)."""
    return b2b_share * B2B_RATE + (1 - b2b_share) * min(MAX_RATE, target)


def expected_L_exact(b2b_share, target, bias):
    return b2b_share * B2B_RATE + (1 - b2b_share) * _clip(target + bias, 0.0, MAX_RATE)


def h_b2c_rate(b2b_share, target, provider):
    """Hannover back-calculated B2C rate (CarrierGenerator:300-313): B2B counted as 100 %."""
    if provider in KEEP_TARGET_AS_B2C or b2b_share >= 1.0:
        return float(target)
    b = (target - b2b_share * 100.0) / (1 - b2b_share)
    return _clip(b, 0.0, 100.0)


def expected_H(b2b_share, target, provider):
    b = h_b2c_rate(b2b_share, target, provider)
    return b2b_share * B2B_RATE + (1 - b2b_share) * min(MAX_RATE, b)


# ------------------------------------------------------------------ carriers XML

def _local(tag):
    return tag.rsplit("}", 1)[-1]


def _attrs(el):
    out = {}
    for a in el:
        if _local(a.tag) == "attributes":
            for x in a:
                out[x.get("name")] = (x.text or "").strip()
    return out


def iter_carriers(path):
    """Yield dict(id, provider, parcels, missed, bias, b2b_svc, svc) per carrier, streaming."""
    opener = gzip.open if str(path).endswith(".gz") else open
    with opener(path, "rb") as f:
        for _, el in ET.iterparse(f, events=("end",)):
            if _local(el.tag) != "carrier":
                continue
            at = _attrs(el)
            svc = b2b = 0
            for s in el.iter():
                if _local(s.tag) == "service":
                    amt = int(s.get("capacityDemand", "0"))
                    svc += amt
                    sat = _attrs(s)
                    # Merged services (type=MIXED, CarrierServiceMerger) keep the pre-merge split in
                    # the integer attributes b2b/b2c; the draw happened per ORIGINAL delivery, so
                    # these are the right denominator. Fallback to `type` only if they are missing.
                    # Other types without b2b/b2c (C2C, WHITE_LABEL, ...) count as non-B2B.
                    if "b2b" in sat:
                        b2b += int(sat["b2b"])
                    elif sat.get("type") == "B2B":
                        b2b += amt
            cid = el.get("id")
            bias = at.get("dailyDeliveryBias")
            yield {
                "id": cid,
                "provider": (at.get("provider") or cid.split("_")[0]).lower(),
                "parcels": int(at.get("numberOfParcels", 0)),
                "missed": int(at.get("missedParcels", 0)),
                "bias": float(bias) if bias is not None else None,
                "b2b_svc": b2b,
                "svc": svc,
            }
            el.clear()


def _sigma_pp(p_pct, n):
    p = p_pct / 100.0
    return 100.0 * math.sqrt(max(p * (1 - p), 0.0) / n) if n else float("nan")


def audit_hannover(path):
    per = {}
    for c in iter_carriers(path):
        if c["id"].startswith("supply_") or c["provider"] not in TARGETS:
            continue
        per.setdefault(c["provider"], []).append(c)
    rows = []
    for p in sorted(per):
        cs = per[p]
        parcels = sum(c["parcels"] for c in cs)
        missed = sum(c["missed"] for c in cs)
        svc = sum(c["svc"] for c in cs)
        b2b = sum(c["b2b_svc"] for c in cs)
        if svc == 0 or parcels == 0:
            # no service parcels -> B2B share and every rate undefined; say so instead of dividing by 0
            print(f"[audit] SKIP provider {p}: {len(cs)} carrier(s) with {svc} service parcels and "
                  f"numberOfParcels={parcels} - no rate to audit", file=sys.stderr)
            continue
        share = b2b / svc
        t = TARGETS[p]
        b = h_b2c_rate(share, t, p)
        no_bias = [c["id"] for c in cs if c["bias"] is None]
        if no_bias:
            # dailyDeliveryBias is what the exact columns need; the closed-form ones do not
            print(f"[audit] provider {p}: {len(no_bias)} of {len(cs)} carrier(s) lack dailyDeliveryBias "
                  f"(e.g. {no_bias[0]}) - bias_pp and expected_H_exact left empty", file=sys.stderr)
            bias_w = exact = ""
        else:
            bias_w = sum(c["bias"] * c["svc"] for c in cs) / svc
            exact = sum(c["b2b_svc"] * B2B_RATE
                        + (c["svc"] - c["b2b_svc"]) * _clip(b + c["bias"], 0.0, MAX_RATE)
                        for c in cs) / svc
        eH = expected_H(share, t, p)
        rows.append({"provider": p, "parcels": parcels, "b2b_parcels": b2b, "missed": missed,
                     "reported_rate": 100.0 * (1 - missed / parcels), "target_rate": t,
                     "expected_H": eH, "expected_L": expected_L(share, t), "bias_pp": bias_w,
                     "expected_H_exact": exact, "expected_L_exact": "",
                     "bias_pp_reconstructed": "", "sigma_pp": _sigma_pp(eH, parcels),
                     "svc_parcels": svc, "shp_parcels": "", "missed_replay": ""})
    return rows


def audit_lausitz(path, shp_stats, replay=None):
    """shp_stats: provider -> (parcels, b2b_parcels) from the (clipped) demand shapefile.
    replay: optional provider -> replayed missed count (column missed_replay)."""
    rows = []
    for c in iter_carriers(path):
        p = c["provider"]
        if p not in TARGETS or c["id"] != p:  # skip district carriers (1c/1d) and anything else
            continue
        sp, sb = shp_stats[p]
        share = sb / sp
        t = TARGETS[p]
        stored = c["bias"]  # dailyDeliveryBias, written by LmdCarrierBuilder since Task 7
        reconstructed = lausitz_bias(p)  # Java-Random port, kept as an independent cross-check
        bias = stored if stored is not None else reconstructed
        eL = expected_L(share, t)
        rows.append({"provider": p, "parcels": c["parcels"], "b2b_parcels": share * c["parcels"],
                     "missed": c["missed"], "reported_rate": 100.0 * (1 - c["missed"] / c["parcels"]),
                     "target_rate": t, "expected_H": expected_H(share, t, p), "expected_L": eL,
                     "bias_pp": stored if stored is not None else "", "expected_H_exact": "",
                     "expected_L_exact": expected_L_exact(share, t, bias),
                     "bias_pp_reconstructed": reconstructed,
                     "sigma_pp": _sigma_pp(eL, c["parcels"]),
                     "svc_parcels": c["svc"], "shp_parcels": sp,
                     "missed_replay": (replay or {}).get(p, "")})
    return sorted(rows, key=lambda r: r["provider"])


# ------------------------------------------------------------------ Lausitz shapefile

def _safe10(s):
    return s[:10]


def read_demand_shp(shp, service_area_shp=None):
    """provider -> (parcels, b2b_parcels), same column logic as LmdDemandReader.group
    (`<p>_tag` = B2C, `<p>_type`/`<p>_typ` = B2B). With service_area_shp the points are clipped
    like LausitzFreightPreprocessor.clipToArea (area.contains(point))."""
    import geopandas as gpd
    gdf = gpd.read_file(shp)
    if service_area_shp:
        area = gpd.read_file(service_area_shp)
        assert gdf.crs is not None and area.crs is not None, "missing CRS"
        assert gdf.crs == area.crs, f"CRS mismatch: demand {gdf.crs} vs area {area.crs}"
        geom = area.geometry.union_all()
        pts = gdf.geometry.centroid
        gdf = gdf[pts.apply(geom.contains)]
    out = {}
    for p in TARGETS:
        tag = _safe10(p + "_tag")
        typ = next((c for c in (_safe10(p + "_type"), _safe10(p + "_typ")) if c in gdf.columns), None)
        if tag not in gdf.columns:
            continue
        b2c = gdf[tag].fillna(0).clip(lower=0).sum()
        b2b = gdf[typ].fillna(0).clip(lower=0).sum() if typ else 0
        # Java only adds a Delivery when amount > 0, so negative/NaN contribute nothing
        out[p] = (int(b2c + b2b), int(b2b))
    return out


def deliveries_from_shp(shp, service_area_shp=None):
    """provider -> ordered list of (is_b2b, amount) exactly as LmdDemandReader.group builds them
    (feature order; per feature B2B delivery first, then B2C; amount > 0 only), optionally clipped."""
    import geopandas as gpd
    gdf = gpd.read_file(shp)
    if service_area_shp:
        area = gpd.read_file(service_area_shp)
        assert gdf.crs == area.crs, f"CRS mismatch: demand {gdf.crs} vs area {area.crs}"
        geom = area.geometry.union_all()
        gdf = gdf[gdf.geometry.centroid.apply(geom.contains)]
    out = {p: [] for p in TARGETS}
    cols = {}
    for p in TARGETS:
        typ = next((c for c in (_safe10(p + "_type"), _safe10(p + "_typ")) if c in gdf.columns), None)
        cols[p] = (_safe10(p + "_tag"), typ)

    def _long(v):
        try:
            return int(v) if v == v else 0  # NaN -> 0 (Java asLong: non-Number -> 0)
        except (TypeError, ValueError):
            return 0
    for _, row in gdf.iterrows():
        for p, (tag, typ) in cols.items():
            b2c = _long(row[tag]) if tag in gdf.columns else 0
            b2b = _long(row[typ]) if typ else 0
            if b2b > 0:
                out[p].append((True, b2b))
            if b2c > 0:
                out[p].append((False, b2c))
    return out


def replay_missed(provider, deliveries):
    """Replay LmdCarrierBuilder.buildCore with Random(4711 + provider.hashCode()):
    first nextGaussian = bias, then one nextDouble per parcel in delivery order."""
    r = JavaRandom(MISSED_DELIVERY_SEED + java_string_hash(provider))
    bias = r.next_gaussian() * sigma_for(provider)
    base = float(TARGETS[provider])
    rate_b2c = _clip(base + bias, 0.0, MAX_RATE)
    missed = 0
    for is_b2b, amount in deliveries:
        rate = B2B_RATE if is_b2b else rate_b2c
        for _ in range(amount):
            if r.next_double() * 100.0 > rate:
                missed += 1
    return missed, bias


def write_csv(rows, out):
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    with open(out, "w", encoding="utf-8", newline="") as f:
        f.write(";".join(COLUMNS) + "\n")
        for r in rows:
            f.write(";".join(
                f"{r[c]:.4f}" if isinstance(r[c], float) else str(r[c]) for c in COLUMNS) + "\n")


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--carriers", required=True)
    ap.add_argument("--area", choices=["hannover", "lausitz"], required=True)
    ap.add_argument("--demand-shp")
    ap.add_argument("--service-area-shp")
    ap.add_argument("--out", required=True)
    a = ap.parse_args(argv)
    if a.area == "hannover":
        rows = audit_hannover(a.carriers)
    else:
        if not a.demand_shp:
            sys.exit("--demand-shp required for --area lausitz")
        stats = read_demand_shp(a.demand_shp, a.service_area_shp)
        dl = deliveries_from_shp(a.demand_shp, a.service_area_shp)
        replay = {p: replay_missed(p, d)[0] for p, d in dl.items() if d}
        rows = audit_lausitz(a.carriers, stats, replay)
    write_csv(rows, a.out)
    for r in rows:
        print(f"{r['provider']:7s} n={r['parcels']:>7} rep={r['reported_rate']:.2f} "
              f"tgt={r['target_rate']} eH={r['expected_H']:.2f} eL={r['expected_L']:.2f}")


if __name__ == "__main__":
    main()
