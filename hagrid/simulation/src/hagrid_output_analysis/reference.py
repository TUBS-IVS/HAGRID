"""
HAGRID Output Analysis – Reference / Spatial Data
===================================================

Loads and prepares the static spatial data that is needed for every
run: region clusters, postal-code areas, MATSim network, and the
pre-computed link-to-area-type mapping (``networkplus``).

Everything is wrapped in a :class:`ReferenceData` dataclass so it can
be injected into the pipeline without relying on module-level globals.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import geopandas as gpd
import matsim
import pandas as pd
from shapely import wkt


# ====================================================================
# Network helper – road-type classification
# ====================================================================

def _assign_road_type(row: pd.Series) -> str:
    """Classify a network link as urban / highway / rural."""
    if row["freespeed_kmh"] <= 50:
        return "urban"
    if row["permlanes"] >= 2:
        return "highway"
    return "rural"


# ====================================================================
# Reference data bundle
# ====================================================================

@dataclass
class ReferenceData:
    """Immutable bundle of spatial reference objects.

    Parameters
    ----------
    network : GeoDataFrame
        MATSim network with ``link_id``, ``geometry``, ``length``,
        ``road_type``, ``freespeed_kmh``.
    regionclusters : GeoDataFrame
        Region-type polygons with ``raumtyp`` and ``area_type_agg``.
    regionclusters_split : GeoDataFrame
        Exploded (no MultiPolygons) version of *regionclusters*.
    gdf_areas : GeoDataFrame
        Postal-code area polygons (EPSG:25832).
    link_length : dict[str, float]
        ``{link_id: length_m}``.
    link_geometry : dict[str, Any]
        ``{link_id: shapely_geometry}``.
    link_type : dict[str, str]
        ``{link_id: "urban"/"rural"/"highway"}``.
    link_raumtyp : dict[str, int]
        ``{link_id: raumtyp_code}`` from ``networkplus``.
    """

    network: gpd.GeoDataFrame
    regionclusters: gpd.GeoDataFrame
    regionclusters_split: gpd.GeoDataFrame
    gdf_areas: gpd.GeoDataFrame
    link_length: dict[str, float]
    link_geometry: dict[str, Any]
    link_type: dict[str, str]
    link_raumtyp: dict[str, int]

    # ------------------------------------------------------------------
    # Factory
    # ------------------------------------------------------------------

    @classmethod
    def load(
        cls,
        network_path: str | Path,
        regionclusters_path: str | Path,
        networkplus_path: str | Path,
        plz_areas_csv: str | Path,
    ) -> ReferenceData:
        """Build a :class:`ReferenceData` from file paths.

        Parameters
        ----------
        network_path : path
            MATSim ``output_network.xml.gz``.
        regionclusters_path : path
            Pickle file with region-cluster GeoDataFrame.
        networkplus_path : path
            Pickle file with ``networkplus`` (link_id → raumtyp).
        plz_areas_csv : path
            CSV with ``WKT`` column for postal-code polygons.
        """
        # ---- network ------------------------------------------------
        net = (
            matsim.read_network(str(network_path))
            .as_geo()
            .set_crs("epsg:25832")
        )
        net["freespeed_kmh"] = net["freespeed"] * 3.6
        net["road_type"] = net.apply(_assign_road_type, axis=1)

        link_length = (
            net[["link_id", "length"]]
            .set_index("link_id")["length"]
            .to_dict()
        )
        link_geometry = (
            net[["link_id", "geometry"]]
            .set_index("link_id")["geometry"]
            .to_dict()
        )
        link_type = (
            net[["link_id", "road_type"]]
            .set_index("link_id")["road_type"]
            .to_dict()
        )

        # ---- regionclusters -----------------------------------------
        rc: gpd.GeoDataFrame = pd.read_pickle(regionclusters_path)

        from hagrid_output_analysis.utils import area_type_group, area_type_name

        rc["area_type_agg"] = rc["raumtyp"].apply(area_type_group)
        rc["area_type"] = rc["raumtyp"].apply(area_type_name)
        rc_split = rc.explode(index_parts=False).reset_index(drop=True)

        # ---- networkplus (link → raumtyp) ----------------------------
        networkplus: pd.DataFrame = pd.read_pickle(networkplus_path)
        link_raumtyp: dict[str, int] = networkplus["raumtyp"].to_dict()

        # ---- postal-code areas ---------------------------------------
        areas = pd.read_csv(str(plz_areas_csv))
        areas["geometry"] = areas["WKT"].apply(wkt.loads)
        gdf_areas = gpd.GeoDataFrame(areas, geometry="geometry", crs="EPSG:25832")

        return cls(
            network=net,
            regionclusters=rc,
            regionclusters_split=rc_split,
            gdf_areas=gdf_areas,
            link_length=link_length,
            link_geometry=link_geometry,
            link_type=link_type,
            link_raumtyp=link_raumtyp,
        )

    # ------------------------------------------------------------------
    # Convenience factories
    # ------------------------------------------------------------------

    @classmethod
    def from_config(cls, cfg) -> ReferenceData:
        """Build a :class:`ReferenceData` from a :class:`RunConfig`.

        Reads ``network_path``, ``regionclusters_path``,
        ``networkplus_path``, and ``plz_areas_csv`` from *cfg*.

        Parameters
        ----------
        cfg : RunConfig
            Must have all four reference-data path fields set.

        Raises
        ------
        ValueError
            If any required path is ``None``.
        """
        missing = [
            name for name in (
                "network_path", "regionclusters_path",
                "networkplus_path", "plz_areas_csv",
            )
            if getattr(cfg, name, None) is None
        ]
        if missing:
            raise ValueError(
                f"RunConfig is missing reference-data paths: {', '.join(missing)}"
            )
        return cls.load(
            network_path=cfg.network_path,
            regionclusters_path=cfg.regionclusters_path,
            networkplus_path=cfg.networkplus_path,
            plz_areas_csv=cfg.plz_areas_csv,
        )

    # ------------------------------------------------------------------
    # Utilities
    # ------------------------------------------------------------------

    def to_dict(self) -> dict[str, Any]:
        """Return a plain dict of all look-ups (useful for pickling)."""
        return {
            "link_length": self.link_length,
            "link_geometry": self.link_geometry,
            "link_type": self.link_type,
            "link_raumtyp": self.link_raumtyp,
        }

    @property
    def city_region(self) -> gpd.GeoDataFrame:
        """Region clusters limited to urban types (raumtyp < 7)."""
        return self.regionclusters[self.regionclusters.raumtyp < 7]
