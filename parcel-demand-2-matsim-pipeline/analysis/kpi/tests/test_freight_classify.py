import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import freight_classify as fc


def test_guess_provider_keywords():
    assert fc.guess_provider("dhl") == "dhl"
    assert fc.guess_provider("amazon_lmd_2") == "amazon"
    assert fc.guess_provider("dp_dhl_hub") == "dp/dhl"      # dp_dhl before dhl
    assert fc.guess_provider("some_UPS_carrier") == "ups"
    assert fc.guess_provider("unknown_x") == "other"


def test_provider_attr_wins_and_empty_normalises():
    assert fc.provider_of("weird_id", "hermes") == "hermes"
    assert fc.provider_of("dpd_1", None) == "dpd"
    assert fc.provider_of("dpd_1", "") == "dpd"             # empty attr -> guess
    assert fc.provider_of("weird_id", None) == "other"


def test_carrier_type():
    assert fc.carrier_type_of("amazon_supply_1", None) == "supply"
    assert fc.carrier_type_of("dhl", None) == "delivery"
    assert fc.carrier_type_of("dhl", "supply") == "supply"  # attr forces supply


def test_classify_vehicle_first_match_wins():
    assert fc.classify_vehicle("x_Supply_Vehicle_supply_light_van_1") == "SUPPLY_VAN"
    assert fc.classify_vehicle("x_Supply_Vehicle_light_1") == "TRUCK_LIGHT"
    assert fc.classify_vehicle("x_Supply_Vehicle_1") == "TRUCK"
    assert fc.classify_vehicle("freight_dhl_veh_dhl_CEP_Vehicle_2") == "VAN"
    assert fc.classify_vehicle("dhl_ct_cep_size_s_h8_v0") == "VAN"       # married250 real id
    assert fc.classify_vehicle("x_cargoBike_7") == "CARGOBIKE"
    assert fc.classify_vehicle("pt_bus_42") is None


def test_labels_cover_all_types():
    for t in ("VAN", "CARGOBIKE", "TRUCK", "TRUCK_LIGHT", "SUPPLY_VAN"):
        assert t in fc.VEHICLE_TYPE_LABELS
