import numpy as np
import pandas as pd
import pytest
from hagrid_demand.diagnostics import special_offsets


def test_special_offsets_preserve_core_and_require_unique_matching_observations():
    obs = pd.DataFrame({'plz':['1','2'], 'street':['a','b'], 'value':[100.,20.]})
    special = pd.DataFrame({'plz':['1'], 'name':['a'], 'vm_tag':[100.], 'exzess':[90.]})
    offset = special_offsets(obs,special,['1','2'])
    np.testing.assert_array_equal(offset,[90.,0.])
    np.testing.assert_array_equal(obs.value.to_numpy()-offset,[10.,20.])
    with pytest.raises(ValueError,match='uniquely match'):
        special_offsets(pd.concat([obs,obs]),special,['1','2'])
    with pytest.raises(ValueError,match='Invalid'):
        special_offsets(obs,special.assign(exzess=101.),['1','2'])
    with pytest.raises(ValueError,match='Duplicate'):
        special_offsets(obs,pd.concat([special,special]),['1','2'])
