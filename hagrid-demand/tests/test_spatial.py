import numpy as np

from hagrid_demand.spatial import spatial_basis, coefficients, normalize_weights, paired_counts


def test_zero_strength_identical_and_zero_weight_never_gets_parcels():
    base, shifted = normalize_weights([0, 2, 4], [-3, 1, 5], 0)
    a,b = paired_counts(base, shifted, 10000, 42, 0, 12)
    np.testing.assert_allclose(base, shifted)
    np.testing.assert_array_equal(a,b)
    assert a[0] == 0


def test_exact_totals_and_repeatability():
    xy = np.array([[0,0], [100,100], [5000,5000]])
    basis = spatial_basis(xy, 1000, 64, 42, 0)
    field = basis @ coefficients(42, 0, 64, .7, 2)
    base, shifted = normalize_weights([2, 3, 8], field, .4)
    assert not np.allclose(base,shifted)
    assert np.isclose(shifted.sum(),1)
    counts = paired_counts(base,shifted,777,42,0,2)
    again = paired_counts(base,shifted,777,42,0,2)
    assert all(x.sum()==777 for x in counts)
    np.testing.assert_array_equal(counts,again)


def test_date_addressed_ar_matches_continuation():
    day = 4
    state = coefficients(42,0,64,.7,day)
    innovation=np.random.default_rng(np.random.SeedSequence([42,0,1,day+1])).normal(size=64)
    np.testing.assert_allclose(.7*state+np.sqrt(1-.7**2)*innovation, coefficients(42,0,64,.7,day+1))


def test_nearby_locations_share_field_and_segment_streams_differ():
    basis=spatial_basis(np.array([[0,0],[0,0],[1,1],[20000,20000]]),3000,256,42,0)
    np.testing.assert_array_equal(basis[0],basis[1])
    assert np.linalg.norm(basis[0]-basis[2]) < np.linalg.norm(basis[0]-basis[3])
    assert not np.array_equal(coefficients(42,0,64,.7,0),coefficients(42,1,64,.7,0))
