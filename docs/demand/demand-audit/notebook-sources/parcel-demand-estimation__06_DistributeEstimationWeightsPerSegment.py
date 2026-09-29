
# CELL 1 execution=1
import pandas as pd
import geopandas as gpd
import matplotlib.pyplot as plt

import numpy as np
from tqdm import tqdm
import seaborn as sns
rng = np.random.default_rng(seed=42)

import matplotlib.pyplot as plt
import matplotlib.patches as mpatches

import ast

# CELL 2 execution=2

# ==============================================================================
# 📦 DATA LOADING: Parcel shipment data (DHL)
# ------------------------------------------------------------------------------

print("Reading input data...")

# Define coordinate reference system (ETRS89 / UTM zone 32N)
crs = 25832

# Input folder path
folder = "input/"

# File names
dhl_streets_file = "dhl2streets_2021.shp"                   # DHL street segments with daily volume

# -------------------------------------------------------------------------------
# 2. Load DHL street segments (Shapefile), reproject and rename daily volume column
# -------------------------------------------------------------------------------
dhl_streets_gdf = gpd.read_file(folder + dhl_streets_file, encoding='UTF-8')
dhl_streets_gdf = dhl_streets_gdf.to_crs(crs)
dhl_streets_gdf = dhl_streets_gdf.rename(columns={'tagesschni': 'dhl_tag'})  # 'dhl_tag' = daily DHL volume

ga_corrected_b2b_gdf = gpd.read_file("output/05_ga_corrected_b2b_with_marked_adjust_gdf.csv", GEOM_POSSIBLE_NAMES="geometry", KEEP_GEOM_COLUMNS="NO")
#Convert Columns with Numeric-looking Strings to Proper Numeric Format
# Convert 'object'-type columns to numeric if possible
for col in ga_corrected_b2b_gdf.select_dtypes(include=["object"]).columns:
    if col.startswith("market_shares_"):
        # Try to parse as dictionary
        ga_corrected_b2b_gdf[col] = ga_corrected_b2b_gdf[col].apply(lambda x: ast.literal_eval(x) if pd.notna(x) else {})
    elif col != "geometry":  # avoid geometry column
        try:
            converted = pd.to_numeric(ga_corrected_b2b_gdf[col], errors="coerce")
            if converted.notna().sum() > 0.9 * len(ga_corrected_b2b_gdf):
                ga_corrected_b2b_gdf[col] = converted
        except Exception as e:
            print(f"Skipping column {col} due to error: {e}")
            pass

# Set CRS for the grid if missing
if ga_corrected_b2b_gdf.crs is None:
    ga_corrected_b2b_gdf.set_crs("EPSG:25832", inplace=True)  

print("Finished reading input data.")



# CELL 3 execution=3
# Dein Koordinatensystem
crs = 25832  # ETRS89 / UTM zone 32N

# 🔹 Lade Firmen-Geo-Daten
companies_gdf = gpd.read_file(folder + "companies_Total_reduced.shp")
companies_gdf = companies_gdf.to_crs(crs)

# 🔹 Lade Personen-Geo-Daten
persons_gdf = gpd.read_file(folder + "persons_total.shp")
persons_gdf = persons_gdf.to_crs(crs)

print("✅ Both GeoDataFrames loaded and projected.")

# CELL 4 execution=None
dhl_streets_gdf

def sample_streets(strassen_gdf, m=50):
    pts = []
    for idx, row in strassen_gdf.iterrows():
        parts = round(row['geometry'].length/m)
        if parts <= 1:
            i = 0
            pt = row['geometry'].interpolate(0.5, normalized=True)

            # idx, i, plz, geom, dhl, hermes, ups, amazon, dpd, gls, fedex
            pts.append([idx, i, row['plz'], pt, row["name"]])
        else:
            delta = row['geometry'].length % m / 2

            for i in range(parts):
                intp = delta + i*m
                pt = row['geometry'].interpolate(intp)
                
                # idx, i, plz, geom, dhl, hermes, ups, amazon, dpd, gls, fedex
                pts.append([idx, i, row['plz'], pt, row["name"]])
    pt_gdf = gpd.GeoDataFrame(pts, crs='EPSG:32632', geometry='geometry', 
    columns=['str_idx', 'str_part', 'plz', 'geometry', "name"])  

    return pt_gdf
    
samples = sample_streets(dhl_streets_gdf)

# CELL 5 execution=None
from shapely.geometry import LineString
from shapely.ops import substring
import geopandas as gpd
import numpy as np

from shapely.geometry import LineString, MultiLineString
from shapely.ops import substring
import geopandas as gpd
import numpy as np

def split_line_to_segments(line: LineString, segment_length: float = 50) -> list:
    """
    Splits a LineString into segments of fixed length.
    """
    segments = []
    distance = 0
    while distance < line.length:
        start = distance
        end = min(distance + segment_length, line.length)
        segment = substring(line, start, end)
        if not segment.is_empty:
            segments.append(segment)
        distance += segment_length
    return segments

def generate_rect_buffers_from_segments(
    dhl_streets_gdf: gpd.GeoDataFrame,
    segment_length: float = 50,
    buffer_width: float = 50
) -> gpd.GeoDataFrame:
    """
    Generates rectangular buffer geometries for fixed-length segments of street geometries,
    and stores the central point for later use.
    """
    segment_geoms = []
    meta_data = []

    for idx, row in dhl_streets_gdf.iterrows():
        geom = row.geometry
        name = row.get("name", f"Street_{idx}")
        plz = row.get("plz", None)
        dhl_weight = row.get("dhl_tag", None)

        lines = geom.geoms if isinstance(geom, MultiLineString) else [geom]

        for line_part in lines:
            if not isinstance(line_part, LineString):
                continue

            segments = split_line_to_segments(line_part, segment_length=segment_length)

            for i, segment in enumerate(segments):
                buffer_geom = segment.buffer(buffer_width / 2, cap_style=2)
                center_point = segment.interpolate(0.5, normalized=True)  # Punkt in der Mitte

                segment_geoms.append(buffer_geom)
                meta_data.append({
                    "str_idx": idx,
                    "seg_idx": i,
                    "name": name,
                    "plz": plz,
                    "line_segment": segment,
                    "point_geom": center_point,  # <--- gespeicherter Punkt
                    "dhl_weight": dhl_weight,
                })

    return gpd.GeoDataFrame(meta_data, geometry=segment_geoms, crs=dhl_streets_gdf.crs)


# Erzeuge segmentbasierte Buffer
buffered_samples_smart = generate_rect_buffers_from_segments(dhl_streets_gdf, segment_length=50, buffer_width=250)

buffered_samples_smart.plot(column="seg_idx", cmap="tab20", figsize=(20, 20), alpha=0.8)
plt.title("📏 Intelligente Segment-Buffer entlang Straßen")
plt.axis("equal")
plt.show()

# CELL 6 execution=15
from shapely.geometry.base import BaseGeometry


def assign_cells_to_samples(samples_gdf: gpd.GeoDataFrame, cells_gdf: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    """
    Spatially joins each sample to the corresponding cell using the 'point_geom' column.
    Adds 'cell_id' to the samples.
    """

    assert "cell_id" in cells_gdf.columns, "cells_gdf must contain a 'cell_id' column"
    assert "point_geom" in samples_gdf.columns, "'samples_gdf' must contain a 'point_geom' column"

    # Sicherstellen, dass Geometrie stimmt
    if not isinstance(samples_gdf["point_geom"].iloc[0], BaseGeometry):
        samples_gdf["point_geom"] = gpd.GeoSeries(samples_gdf["point_geom"], crs=samples_gdf.crs)

    # Temporär Geometrie auf point_geom setzen
    samples_temp = samples_gdf.set_geometry("point_geom")

    # Spatial Join
    joined = gpd.sjoin(samples_temp, cells_gdf[["cell_id", "geometry"]], how="left", predicate="intersects")

    # Ursprüngliche Geometrie wiederherstellen
    joined = joined.set_geometry(samples_gdf.geometry.name)

    # Einheitliche cell_id-Spalte setzen
    if "cell_id_right" in joined.columns:
        joined["cell_id"] = joined["cell_id"].combine_first(joined["cell_id_right"])
        joined = joined.drop(columns=[col for col in joined.columns if col.startswith("cell_id_")])
    # joined = joined.drop(columns=[col for col in joined.columns if col.startswith("cell_id_")])

    return joined.drop(columns="index_right")

def assign_b2c_b2b_weights_with_total(
    buffered_samples: gpd.GeoDataFrame,
    companies_gdf: gpd.GeoDataFrame,
    persons_gdf: gpd.GeoDataFrame,
    ga_corrected_b2b_gdf: pd.DataFrame,
    alpha: float = 0.2,
    beta: float = 0.075,
    total_col: str = "total_coun",
    cell_id_col: str = "cell_id"
):
    """
    Erweiterte Gewichtung mit Total-Count pro Zelle als zusätzlichem Faktor.
    """

    samples = buffered_samples.copy()

    # B2C
    b2c_join = gpd.sjoin(samples, persons_gdf, how="left", predicate="contains")
    b2c_counts = b2c_join.groupby(b2c_join.index).size()
    samples["person_sum"] = b2c_join.groupby(b2c_join.index).size()
    samples["b2c_weight"] = samples.index.map(b2c_counts).fillna(0).astype(int)

    # B2B
    b2b_join = gpd.sjoin(samples, companies_gdf, how="left", predicate="contains")
    samples["company_count"] = samples.index.map(b2b_join.groupby(b2b_join.index).size()).fillna(0).astype(int)
    
    if "employees" in companies_gdf.columns:
        employees_sum = b2b_join.groupby(b2b_join.index)["employees"].sum()
        samples["employees_sum"] = samples.index.map(employees_sum).fillna(0).astype(int)
    else:
        samples["employees_sum"] = 0

    samples["b2b_weight"] = (
        alpha * samples["company_count"] +
        beta * samples["employees_sum"]
    )

    # Total-Paketmengen aus Zellen mappen
    # cell_count_map = ga_corrected_b2b_gdf.set_index(cell_id_col)[total_col].to_dict()
    # samples["total_count"] = samples[cell_id_col].map(cell_count_map).fillna(0)

    # # Gesamtgewichtung anwenden
    # samples["b2c_weight"] *= samples["total_count"] * 2
    # samples["b2b_weight"] *= samples["total_count"] * 2
    # Filter auf gültige Einträge
    mask = (samples["b2c_weight"] > 0) | (samples["b2b_weight"] > 0)
    return samples[mask].copy()



# CELL 7 execution=17
samples_weighted = assign_b2c_b2b_weights_with_total(
    buffered_samples=buffered_samples_smart,
    companies_gdf=companies_gdf,
    persons_gdf=persons_gdf,
    ga_corrected_b2b_gdf=ga_corrected_b2b_gdf,
    alpha=1,
    beta=0.1,
    total_col="total_coun",
    cell_id_col="cell_id"
)

# CELL 8 execution=None
def apply_dhl_boost_to_weights(
    samples_gdf, 
    str_idx_col="str_idx", 
    tag_col="dhl_weight", 
    boost_type="b2b", 
    min_tag=10
):
    """
    Addiert DHL-Tags als Mindestboost und skaliert zusätzlichen Boost proportional zur ursprünglichen Gewichtung.

    Parameters:
    -----------
    samples_gdf : GeoDataFrame
        Mit b2b_weight und/oder b2c_weight
    str_idx_col : str
        Straßen-ID
    tag_col : str
        Spalte mit DHL-Tag-Werten
    boost_type : str
        "b2b", "b2c" oder "both"
    min_tag : float
        Mindestwert für DHL-Tag, ab dem überhaupt geboostet wird
    """
    samples = samples_gdf.copy()

    def distribute_boost(group, weight_col):
        base_weight = group[weight_col]
        dhl_boost = group[tag_col] / 2  # Halbe Gewichtung für den Boost

        # Fallunterscheidung: keine Verteilung nötig
        if base_weight.sum() == 0:
            return dhl_boost

        # Ziel: final_weight = dhl + scaled(original)
        total_target = base_weight.sum() + dhl_boost.sum()

        # Proportionen der Originalgewichte
        proportions = base_weight / base_weight.sum()

        # Restgewicht nach DHL-Boost
        remaining_weight = total_target - dhl_boost

        # Endgewicht: DHL-Boost plus Anteil vom restlichen Gewicht
        final = dhl_boost + proportions * (total_target - dhl_boost.sum())

        return final

    if boost_type in ["b2b", "both"]:
        samples["b2b_weight"] = samples.groupby(str_idx_col, group_keys=False).apply(
            lambda g: distribute_boost(g, "b2b_weight")
        )

    if boost_type in ["b2c", "both"]:
        samples["b2c_weight"] = samples.groupby(str_idx_col, group_keys=False).apply(
            lambda g: distribute_boost(g, "b2c_weight")
        )

    return samples

samples_boosted = apply_dhl_boost_to_weights(
    samples_weighted, 
    boost_type="both", 
    min_tag=300
)

# CELL 9 execution=26
import matplotlib.pyplot as plt

fig, ax = plt.subplots(1, 2, figsize=(16, 8))

# 📦 B2C
samples_weighted.plot(
    column="b2c_weight",
    cmap="Blues",
    legend=True,
    ax=ax[0],
    linewidth=0
)
ax[0].set_title("📦 B2C Weight per Sample Area")
ax[0].axis("off")

# 🏭 B2B
samples_weighted.plot(
    column="b2b_weight",
    cmap="nipy_spectral",
    legend=True,
    ax=ax[1],
    linewidth=0
)
ax[1].set_title("🏭 B2B Weight per Sample Area")
ax[1].axis("off")

plt.tight_layout()
plt.show()


# CELL 10 execution=27
def calculate_relative_weights_per_street_extended(samples_weighted: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    """
    Calculates relative B2C and B2B weights per street segment based on sample rectangles,
    including geometry, PLZ, and street name. Adds optional global normalization [0–1].

    Parameters:
    -----------
    samples_weighted : GeoDataFrame
        GeoDataFrame with 'str_idx', 'b2c_weight', 'b2b_weight', 'plz', 'name', 'geometry'

    Returns:
    --------
    GeoDataFrame with:
        - sample_idx (original index)
        - str_idx
        - name
        - plz
        - geometry
        - b2c_ratio (relative to other samples on same street)
        - b2b_ratio
        - b2c_ratio_norm (normalized from 0 to 1 globally)
        - b2b_ratio_norm
    """
    assert "str_idx" in samples_weighted.columns
    assert "name" in samples_weighted.columns
    assert "plz" in samples_weighted.columns
    assert "geometry" in samples_weighted.columns

    result_rows = []

    for str_id, group in samples_weighted.groupby("str_idx"):
        b2c_total = group["b2c_weight"].sum()
        b2b_total = group["b2b_weight"].sum()

        for idx, row in group.iterrows():
            b2c_ratio = row["b2c_weight"]  if b2c_total > 0 else 0
            b2b_ratio = row["b2b_weight"]  if b2b_total > 0 else 0

            result_rows.append({
                "sample_idx": idx,
                "str_idx": str_id,
                "name": row["name"],
                "plz": row["plz"],
                "geometry": row["geometry"],
                "b2c_ratio": b2c_ratio,
                "b2b_ratio": b2b_ratio,
                "point_geom": row["point_geom"],  # Optional: point geometry for visualization
            })

    result_gdf = gpd.GeoDataFrame(result_rows, geometry="geometry", crs=samples_weighted.crs)

    return result_gdf


samples_with_ratios = calculate_relative_weights_per_street_extended(samples_boosted)
# samples_boosted
# samples_with_ratios =  calculate_relative_weights_per_street_extended(samples_boosted)


# CELL 11 execution=28
def assign_dhl_volume_to_samples(samples: gpd.GeoDataFrame, dhl_streets_gdf: gpd.GeoDataFrame) -> gpd.GeoDataFrame:
    """
    Merges the DHL volume per street segment into the samples using 'str_idx'.
    
    Parameters:
    -----------
    samples : GeoDataFrame
        Sample points or polygons with a 'str_idx' column referring to the street index.
    
    dhl_streets_gdf : GeoDataFrame
        Original DHL street segments with 'dhl_tag' (daily DHL volume), indexed by row position.
    
    Returns:
    --------
    GeoDataFrame with new column 'dhl_tag' added to samples.
    """
    # Sicherstellen, dass dhl_streets_gdf einen int-Index hat
    if not isinstance(dhl_streets_gdf.index, pd.RangeIndex):
        dhl_streets_gdf = dhl_streets_gdf.reset_index(drop=True)

    # Nur benötigte Spalten extrahieren
    dhl_volumes = dhl_streets_gdf[["dhl_tag"]].copy()
    dhl_volumes["str_idx"] = dhl_volumes.index  # Index als Spalte

    # Merge auf Basis von 'str_idx'
    merged = samples.merge(dhl_volumes, on="str_idx", how="left")

    return merged

samples_with_ratios = assign_dhl_volume_to_samples(samples_with_ratios, dhl_streets_gdf)

# CELL 12 execution=29
samples_with_ratios.set_geometry("point_geom", inplace=True)

# CELL 13 execution=30
samples_with_ratios.to_csv("output/06_street_samples_with_weights.csv", index=False)
