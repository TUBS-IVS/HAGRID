"""Explicit observation-level scope; raw inputs are never modified."""
import numpy as np


def filter_dhl(frame, threshold=None, output=None):
    if not np.isfinite(frame.value).all() or (frame.value<0).any():
        raise ValueError('Invalid DHL observations')
    if threshold is not None and (not np.isfinite(threshold) or threshold<=0):
        raise ValueError('DHL exclusion threshold must be positive')
    excluded=frame.value.gt(threshold) if threshold is not None else frame.value.lt(0)
    if output is not None:
        frame.loc[excluded].drop(columns='geometry',errors='ignore').to_csv(output/'excluded_dhl_observations.csv',index=False)
        from .data import write_json
        write_json(output/'observation_scope.json',{'rule':'exclude complete observation when value > threshold',
            'threshold':threshold,'excluded_rows':int(excluded.sum()),'excluded_volume':float(frame.loc[excluded,'value'].sum()),
            'retained_volume':float(frame.loc[~excluded,'value'].sum()),
            'interpretation':'User-defined target scope; transport mode not independently verified. Threshold applies to street observations, not PLZ totals or individual parcels.'})
    return frame.loc[~excluded].copy()
