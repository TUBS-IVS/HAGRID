# -*- coding: utf-8 -*-
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import config_diff  # noqa: E402

_CFG = ('<config><module name="freightCarriers">'
        '<param name="carriersVehicleTypeFile" value="{root}/parcel-demand-2-matsim-pipeline/'
        'hagrid-input/lausitz/vehicles/lmd-vehicle-types.xml" /></module>'
        '<module name="multiModeDrt"><parameterset type="drt">'
        '<param name="numberOfThreads" value="{threads}" /></parameterset></module></config>')


def _cfg(tmp_path, name, root, threads=12):
    f = tmp_path / name
    f.write_text(_CFG.format(root=root, threads=threads), encoding="utf-8")
    return f


def test_the_same_repo_file_on_two_machines_is_not_a_difference(tmp_path):
    """Found on b120rgs (dev, Windows) vs b140rgs (Lausitz VM, Linux): nine 'substantive'
    differences that were only the checkout location of the same repo files."""
    a = _cfg(tmp_path, "a.xml", "C:/Users/Hendrik Bimmermann/Documents/GitHub/HAGRID")
    b = _cfg(tmp_path, "b.xml", "/home/hendrik/HAGRID")
    assert config_diff.diff(a, b)["real"] == []


def test_a_real_parameter_difference_still_shows(tmp_path):
    a = _cfg(tmp_path, "a.xml", "C:/Users/x/HAGRID", threads=14)
    b = _cfg(tmp_path, "b.xml", "/home/y/HAGRID", threads=12)
    real = config_diff.diff(a, b)["real"]
    assert [k for k, _, _ in real] == ["multiModeDrt/drt/numberOfThreads"]
