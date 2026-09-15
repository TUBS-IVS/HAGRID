"""Synthetic demonstration: heterogeneous carrier profiles with coherent margins.

Not a fitted Hannover model. All area demands, observations and affinities are
invented; channel shares reproduce PANDA's configured assumptions for comparison.
No private observations or PANDA source code are included.
"""
from pathlib import Path
import json

import numpy as np


AREAS = ["Wohngebiet_EFH", "Wohngebiet_MFH", "Buero_Handel", "Industrie_Gewerbe"]
CHANNELS = ["B2C", "B2B"]
CARRIERS = ["DHL", "Amazon", "Hermes", "DPD", "GLS", "UPS", "FedEx"]
# Assumptions from PANDA carrier_split.py at 1e683d0, not verified market facts.
SHARES = np.array([
    [.467, .225, .122, .081, .074, .021, .010],
    [.242, .006, .017, .076, .029, .353, .277],
])
# Synthetic channel demand; production would infer these with uncertainty.
AREA_CHANNEL_TOTALS = np.array([[800., 40.], [900., 60.], [250., 450.], [100., 600.]])


def reconcile(prior, area_channel, carrier_channel, dhl_area, max_iter=20000, tol=1e-9):
    """Alternating proportional projections for this positive, feasible demo.

    Hard constraints: area/channel totals, carrier/channel totals, DHL area totals.
    Real noisy observations would use soft likelihoods rather than this exact fit.
    """
    if np.any(prior <= 0):
        raise ValueError("This demonstration requires strictly positive priors.")
    if not np.allclose(area_channel.sum(axis=0), carrier_channel.sum(axis=1)):
        raise ValueError("Channel margins are incompatible.")
    if not np.isclose(dhl_area.sum(), carrier_channel[:, 0].sum()):
        raise ValueError("DHL margins are incompatible.")
    if np.any(dhl_area >= area_channel.sum(axis=1)):
        raise ValueError("DHL leaves no positive residual in at least one area.")
    x = prior.copy()
    for iteration in range(max_iter):
        x *= (area_channel / x.sum(axis=2))[:, :, None]
        x *= (carrier_channel / x.sum(axis=0))[None, :, :]
        x[:, :, 0] *= (dhl_area / x[:, :, 0].sum(axis=1))[:, None]
        errors = [
            float(np.max(np.abs(x.sum(axis=2) - area_channel))),
            float(np.max(np.abs(x.sum(axis=0) - carrier_channel))),
            float(np.max(np.abs(x[:, :, 0].sum(axis=1) - dhl_area))),
        ]
        if max(errors) < tol:
            return x, iteration + 1, errors
    raise RuntimeError("No convergence; margins or support may be incompatible.")


def main():
    assert np.allclose(SHARES.sum(axis=1), 1)
    totals = AREA_CHANNEL_TOTALS
    margins = totals.sum(axis=0)[:, None] * SHARES
    base = totals[:, :, None] * SHARES[None, :, :]
    # Construct mutually feasible synthetic DHL area observations; channel split
    # below is used only to construct a witness, NOT given to the reconciliation.
    spatial_factors = np.array([[1.15, .9], [.9, 1.], [1.05, 1.1], [.95, .95]])
    witness_dhl = base[:, :, 0] * spatial_factors
    witness_dhl *= margins[:, 0] / witness_dhl.sum(axis=0)
    assert np.all(witness_dhl < totals)
    dhl_observed = witness_dhl.sum(axis=1)

    affinities = np.ones_like(base)
    # Hypothetical branch/customer mix: no claim about actual carrier behaviour.
    affinities[:, 1, 6] = [.5, .6, 1.2, 2.5]  # FedEx B2B
    affinities[:, 1, 5] = [.8, .8, 1.8, 1.0]  # UPS B2B
    affinities[:, 0, 1] = [.9, 1.25, 1., .8]  # Amazon B2C
    affinities[:, 0, 2] = [1.1, 1.1, .8, .7]  # Hermes B2C
    output = {}
    matrices = []
    for label, prior in [("neutral_profile", base), ("differentiated_profile", base * affinities)]:
        x, iterations, errors = reconcile(prior, totals, margins, dhl_observed)
        matrices.append(x)
        assert (x >= 0).all()
        assert np.isclose(x.sum(), totals.sum())
        output[label] = {
            "iterations": iterations, "max_margin_errors": errors,
            "area_carrier_expected_counts": {
                area: dict(zip(CARRIERS, x[z].sum(axis=0).tolist()))
                for z, area in enumerate(AREAS)
            },
            "fedex_share_within_area_b2b": dict(zip(AREAS, (x[:, 1, 6] / totals[:, 1]).tolist())),
            "fedex_business_fraction": float(x[:, 1, 6].sum() / x[:, :, 6].sum()),
        }
    assert not np.allclose(matrices[0], matrices[1])
    assert np.ptp(matrices[1][:, 1, 6] / totals[:, 1]) > .1
    dhl_c, dhl_b = 80., 20.
    all_c, all_b = dhl_c / SHARES[0, 0], dhl_b / SHARES[1, 0]
    result = {
        "status": "SYNTHETIC, UNCALIBRATED, NOT HANNOVER ESTIMATES",
        "panda_commit": "1e683d026cec3483214523280877ef44e012d6d5",
        "dhl_20pct_business_implied_market_business_fraction": all_b / (all_c + all_b),
        "area_channel_totals": dict(zip(AREAS, totals.tolist())),
        "synthetic_dhl_area_observations": dict(zip(AREAS, dhl_observed.tolist())),
        "synthetic_carrier_channel_margins": dict(zip(CHANNELS, [dict(zip(CARRIERS, row.tolist())) for row in margins])),
        "models": output,
        "interpretation": "Both allocations match identical observations and margins. Unobserved carrier geography remains prior-dependent.",
    }
    path = Path(__file__).with_name("carrier_concept_demo.json")
    path.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(result, indent=2, ensure_ascii=True))


if __name__ == "__main__":
    main()
