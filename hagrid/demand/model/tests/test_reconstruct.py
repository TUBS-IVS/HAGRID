import pandas as pd
import numpy as np
import pytest
from hagrid_demand.reconstruct import constrain


def test_reconstruction_preserves_relative_allocation_and_non_dhl():
    frame=pd.DataFrame({'plz':['1','1','2'],'DHL':[2.,3.,7.],'UPS':[4.,5.,6.]})
    out=constrain(frame,pd.Series({'1':100.,'2':0.}))
    np.testing.assert_allclose(out.DHL,[40.,60.,0.])
    np.testing.assert_array_equal(out.UPS,frame.UPS)
    with pytest.raises(ValueError): constrain(frame,pd.Series({'3':1.}))
    with pytest.raises(ValueError): constrain(frame,pd.Series({'1':-1.}))
