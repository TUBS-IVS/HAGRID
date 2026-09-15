"""Joint provisional demand/carrier model with disjoint spatial evaluation."""

import json
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.optimize import least_squares
from scipy.special import softmax

PROVIDERS = ["DHL", "Hermes", "UPS", "DPD", "GLS", "FedEx/TNT", "Amazon"]


def load_legacy(root, year):
    root = Path(root)
    market = pd.read_csv(root / "00_markedshare_with_amazon.csv").set_index("Year")
    profile = pd.read_csv(root / "05_optimized_b2b_shares_by_year.csv").set_index("year")
    national = pd.read_csv(root / "01_b2b_forecast_complete.csv").set_index("Year")
    volumes = pd.read_csv(root / "02_parcel_volumen_estimation_complete.csv").set_index("year")
    if year not in market.index or year not in profile.index:
        raise ValueError(f"Missing market/profile year: {year}")
    m = market.loc[year, PROVIDERS].to_numpy(float) / 100
    q = profile.loc[year, PROVIDERS].to_numpy(float)
    # Missing profile can only be tolerated for a carrier with exactly zero market share.
    q = np.where((m == 0) & ~np.isfinite(q), 0.5, q)
    if not np.isfinite(m).all() or not np.isfinite(q).all() or (m < 0).any() or ((q<0)|(q>1)).any():
        raise ValueError("Invalid legacy market/profile values")
    m /= m.sum()
    business = m*q
    private = m*(1-q)
    if business.sum() <= 0 or private.sum() <= 0:
        raise ValueError("Legacy segment shares have empty support")
    actual = national.loc[year, "Actual_B2B"] if year in national.index else np.nan
    b = float(actual)/100 if pd.notna(actual) else float(business.sum())
    return {"market": m, "q": q, "private": private/private.sum(), "business": business/business.sum(),
            "b2b": b, "volumes": volumes}


def business_exposure(employees, power=1.):
    if not np.isfinite(power) or not 0 < power <= 1:
        raise ValueError('Business size exponent must be in (0,1]')
    return np.asarray(employees, dtype=float)**power


def prepare_training(source, business_size_power=1., dhl_exclude_above=None):
    source = Path(source)
    sites = pd.read_parquet(source/"sites.parquet").sort_values("site_id").reset_index(drop=True)
    members = pd.read_parquet(source/"site_postal_candidates.parquet")
    unique = members.loc[members.postal_candidates.eq(1), ["site_id", "plz"]]
    if not unique.site_id.is_unique:
        raise ValueError("Ambiguous postal assignment cannot be silently duplicated")
    sites["plz"] = sites.site_id.map(unique.set_index("site_id").plz)
    sites["branch"] = sites.branch.fillna("unknown").astype(str)
    sites["employees"] = pd.to_numeric(sites.employees, errors="coerce").fillna(0)
    if (sites.employees < 0).any():
        raise ValueError("Negative employees")
    sites['business_exposure'] = business_exposure(sites.employees, business_size_power)
    dhl = pd.read_parquet(source/"dhl_observations.parquet")
    if dhl.value.isna().any() or (dhl.value<0).any():
        raise ValueError("Missing/negative DHL values require resolution before additive PLZ fit")
    from .scope import filter_dhl
    dhl = filter_dhl(dhl,dhl_exclude_above)
    y = dhl.groupby("plz").value.sum()
    groups = sorted(set(sites.plz.dropna()) & set(y.index))
    branches = sorted(sites.loc[sites.recipient_type.eq("business"), "branch"].unique())
    population = sites.groupby("plz").population.sum().reindex(groups).fillna(0).to_numpy(float)/1000
    firms = sites.loc[sites.recipient_type.eq("business")].pivot_table(index="plz",columns="branch",values="business_exposure",aggfunc="sum",fill_value=0)
    business = firms.reindex(index=groups,columns=branches).fillna(0).to_numpy(float)/1000
    hermes = pd.read_parquet(source/"hermes_observations.parquet")
    return sites, {"plz": groups, "branches": branches, "population": population, "business": business,
                   "dhl": y.reindex(groups).to_numpy(float), "hermes_table": hermes,
                   "coverage": {"sites_without_training_plz": int((~sites.plz.isin(groups)).sum()),
                                "dhl_plz_without_sites": sorted(set(y.index)-set(groups)),
                                "repeated_street_rows": int(dhl.repeated_street_key.sum())}}


def unpack(theta, kind, branches, priors):
    c, b = np.exp(theta[:2])
    position = 2
    delta = np.zeros(branches)
    if kind != "pooled":
        delta = theta[position:position+branches]
        position += branches
    shares = [priors["private"].copy(), priors["business"].copy()]
    if kind == "joint":
        for segment in range(2):
            shifts = np.r_[0., theta[position:position+6]]
            position += 6
            # Zero support in inherited priors remains exactly zero.
            prior = shares[segment]
            logits = np.full(7, -np.inf)
            active = prior > 0
            logits[active] = np.log(prior[active])+shifts[active]
            shares[segment] = softmax(logits)
    return c, b*np.exp(delta), shares


def predict_groups(theta, kind, data, priors):
    c, coefficients, shares = unpack(theta,kind,len(data["branches"]),priors)
    private = data["population"]*c
    business = data["business"]@coefficients
    return private[:,None]*shares[0], business[:,None]*shares[1]


def fit_candidate(kind, data, priors, cfg, train):
    n = len(data["branches"])
    denominator = data["population"][train].sum()*priors["private"][0] + data["business"][train].sum()*.2*priors["business"][0]
    rate = max(data["dhl"][train].sum()/max(denominator,1e-8),1e-5)
    theta = np.r_[np.log(rate),np.log(rate*.2),np.zeros(n if kind!="pooled" else 0),np.zeros(12 if kind=="joint" else 0)]
    scale = max(float(data["dhl"][train].mean()),1.)
    h = data["hermes"]
    hmask = train & np.isfinite(h) & (h>=0)

    def residual(t):
        C,B = predict_groups(t,kind,data,priors)
        predicted=C+B
        error=[(predicted[train,0]-data["dhl"][train])/scale/np.sqrt(train.sum())]
        if hmask.sum()>1 and h[hmask].sum()>0:
            expected=predicted[hmask,1]
            error.append(cfg["hermes_shape_weight"]*(expected/max(expected.sum(),1e-12)-h[hmask]/h[hmask].sum())*np.sqrt(hmask.sum()))
        total=max(float(predicted[train].sum()),1e-12)
        regional=predicted[train].sum(axis=0)/total
        b=float(B[train].sum()/total)
        error.append(.2*(regional-priors["market"])/cfg["market_share_sd"]/np.sqrt(7))
        error.append(np.array([.2*(b-priors["b2b"])/cfg["b2b_share_sd"]]))
        if kind!="pooled":
            error.append(.1*t[2:2+n]/cfg["branch_log_sd"]/np.sqrt(n))
        if kind=="joint":
            error.append(.1*t[-12:]/cfg["carrier_log_sd"]/np.sqrt(12))
        return np.concatenate(error)

    result=least_squares(residual,theta,bounds=(-12,12),max_nfev=cfg["max_fit_evaluations"],ftol=1e-9,xtol=1e-9,gtol=1e-9)
    if not result.success or not np.isfinite(result.x).all():
        raise ValueError(f"{kind}: optimizer did not converge: {result.message}")
    return {"kind":kind,"theta":result.x.tolist(),"cost":float(result.cost),"evaluations":result.nfev,"optimizer_message":result.message}


def metrics(y, prediction):
    return {"wMAPE":float(np.abs(y-prediction).sum()/y.sum()) if y.sum()>0 else None,
            "MAE":float(np.abs(y-prediction).mean()),"bias":float((prediction-y).sum()/y.sum()) if y.sum()>0 else None,
            "observed_sum":float(y.sum()),"predicted_sum":float(prediction.sum()),"groups":len(y)}


def fit_compare(data,priors,cfg):
    n=len(data["plz"])
    if n<15:
        raise ValueError("At least 15 postal observations required for disjoint train/validation/test")
    rng=np.random.default_rng(cfg["seed"])
    order=rng.permutation(n)
    test=np.zeros(n,dtype=bool);validation=test.copy()
    nt=max(2,int(n*cfg["holdout_fraction"]));nv=max(2,int(n*cfg["validation_fraction"]))
    if nt+nv>n-5: raise ValueError("Not enough training postal areas")
    test[order[:nt]]=True;validation[order[nt:nt+nv]]=True;train=~(test|validation)
    evaluated=[]
    kinds=cfg.get('candidate_kinds',["pooled","branches","joint"])
    if not kinds or any(k not in ['pooled','branches','joint'] for k in kinds):
        raise ValueError('Invalid model candidates')
    for kind in kinds:
        model=fit_candidate(kind,data,priors,cfg,train)
        C,B=predict_groups(np.array(model["theta"]),kind,data,priors)
        model["training"]=metrics(data["dhl"][train],(C+B)[train,0])
        model["validation"]=metrics(data["dhl"][validation],(C+B)[validation,0])
        evaluated.append(model)
        print(f"{kind}: validation wMAPE {model['validation']['wMAPE']:.3f}",flush=True)
    # Test values do not participate in this selection.
    winner=min(evaluated,key=lambda m:m["validation"]["wMAPE"])
    evaluated_fit=fit_candidate(winner["kind"],data,priors,cfg,~test)
    C,B=predict_groups(np.array(evaluated_fit["theta"]),winner["kind"],data,priors)
    test_metrics=metrics(data["dhl"][test],(C+B)[test,0])
    hmask=test & np.isfinite(data["hermes"]) & (data["hermes"]>=0)
    hermes_metric=None
    if hmask.sum()>1 and data["hermes"][hmask].sum()>0:
        hp=(C+B)[hmask,1];ht=data["hermes"][hmask]
        hermes_metric=metrics(ht,hp/hp.sum()*ht.sum())
    predictions=pd.DataFrame({"plz":data["plz"],"split":np.where(test,"test",np.where(validation,"validation","train")),
                              "dhl_observed":data["dhl"],"dhl_predicted_evaluation_model":(C+B)[:,0]})
    frozen=fit_candidate(winner["kind"],data,priors,cfg,np.ones(n,dtype=bool))
    return frozen, {"candidates":evaluated,"selected":winner["kind"],"test_dhl":test_metrics,
                    "test_hermes_shape_only":hermes_metric,"evaluation_model":evaluated_fit,
                    "split":{label:[p for p,m in zip(data['plz'],mask) if m] for label,mask in [('train',train),('validation',validation),('test',test)]},
                    "coverage":data["coverage"],"limitations":["Single disjoint spatial split, not nested cross-validation.",
                       "Model selection uses DHL validation error; B2B and other carriers remain prior-dependent.",
                       "Frozen application model is refit on all observations; reported test metrics belong to separate pre-test model."]}, predictions
