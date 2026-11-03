"""Decompose (reported - configured) missed parcels into semantics, daily bias, residual.

Reads out/summary.csv (from measure_all.py). Per row, in missed-parcel counts:
  configured  = (1 - target/100) * N
  semantic    = (1 - E0/100) * N - configured     E0 = expected_L (Lausitz) / expected_H (Hannover), bias 0
  bias        = (1 - Ex/100) * N - (1 - E0/100) * N   Ex = expected_*_exact (with stored/reproduced bias)
  residual    = missed - (1 - Ex/100) * N          (binomial draw noise; 0 when replay is exact)
Negative = fewer missed parcels than configured. Writes out/decomposition.csv and prints
provider tables and per-run totals.
"""
import csv
import sys
from pathlib import Path

OUT = Path(__file__).parent / "out"


def f(x):
    return float(x) if x not in ("", None) else float("nan")


def main():
    rows = list(csv.DictReader(open(OUT / "summary.csv", encoding="utf-8"), delimiter=";"))
    dec = []
    for r in rows:
        n, m, t = f(r["parcels"]), f(r["missed"]), f(r["target_rate"])
        e0 = f(r["expected_L"]) if r["area"] == "lausitz" else f(r["expected_H"])
        ex = f(r["expected_L_exact"]) if r["area"] == "lausitz" else f(r["expected_H_exact"])
        cfg = (1 - t / 100) * n
        sem = (1 - e0 / 100) * n - cfg
        bias = (1 - ex / 100) * n - (1 - e0 / 100) * n
        res = m - (1 - ex / 100) * n
        dec.append({"run": r["run"], "area": r["area"], "provider": r["provider"], "parcels": n,
                    "configured_missed": cfg, "missed": m, "semantic": sem, "bias": bias,
                    "residual": res, "delta_total": m - cfg})
    cols = list(dec[0])
    with open(OUT / "decomposition.csv", "w", encoding="utf-8", newline="") as fh:
        fh.write(";".join(cols) + "\n")
        for d in dec:
            fh.write(";".join(f"{d[c]:.3f}" if isinstance(d[c], float) else str(d[c]) for c in cols) + "\n")
    # per-run totals; Hannover also without ups/fedex (their implied B2C base is ~97, not 89)
    keys = ("configured_missed", "missed", "semantic", "bias", "residual", "delta_total")
    print("run;scope;configured;missed;delta;semantic;bias;residual;delta_pct")
    for run in dict.fromkeys(d["run"] for d in dec):
        for scope, excl in (("all", ()), ("ex_ups_fedex", ("ups", "fedex"))):
            s = [d for d in dec if d["run"] == run and d["provider"] not in excl]
            if scope != "all" and s[0]["area"] != "hannover":
                continue
            tot = {k: sum(d[k] for d in s) for k in keys}
            print(f"{run[:52]};{scope};{tot['configured_missed']:.1f};{tot['missed']:.0f};"
                  f"{tot['delta_total']:.1f};{tot['semantic']:.1f};{tot['bias']:.1f};{tot['residual']:.1f};"
                  f"{100*tot['delta_total']/tot['configured_missed']:.1f}%")
    # Lausitz binomial sigma of the pooled missed count (exact composition, per provider)
    print("\nLausitz pooled binomial sigma of missed count (b2b at 99, b2c at clip(T+bias)):")
    import math
    for run in dict.fromkeys(r["run"] for r in rows if r["area"] == "lausitz"):
        var = 0.0
        for r in rows:
            if r["run"] != run:
                continue
            n, b2b = f(r["parcels"]), f(r["b2b_parcels"])
            q = (100 - min(98.0, max(0.0, f(r["target_rate"]) + f(r["bias_pp_reconstructed"])))) / 100
            var += b2b * 0.01 * 0.99 + (n - b2b) * q * (1 - q)
        print(f"  {run[:52]}  sigma={math.sqrt(var):.1f}")
    print("\nper provider (area, run, provider: parcels | configured | missed | semantic | bias | residual)")
    for run in ("LMD_BASELINE_13052025_bandz_central_iter0_jsprit100",
                "DRT_BASELINE_13052025_b120rgs_iter250_jsprit100",
                "BASECASE_13052025_160v2_iter150_jsprit1000", "BASECASE_13052025_290v2_iter150_jsprit1000"):
        for d in dec:
            if d["run"] == run:
                print(f"  {run[:22]:22s} {d['provider']:7s} {d['parcels']:6.0f} | {d['configured_missed']:7.1f} | "
                      f"{d['missed']:5.0f} | {d['semantic']:7.1f} | {d['bias']:7.1f} | {d['residual']:7.1f}")


if __name__ == "__main__":
    main()
