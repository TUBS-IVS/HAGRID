
# CELL 1 execution=1
import geopandas as gpd
import os
import numpy as np
import matplotlib.pyplot as plt
from copy import deepcopy
from datetime import timedelta
import matplotlib as mpl




# CELL 2 execution=2
plt.rcdefaults()                     # reset rcParams
mpl.rcParams.update(mpl.rcParamsDefault)
plt.style.use('default')             # reset style

# CELL 3 execution=None
# Plot from EWGT 2025 Abstract

# Define price values (cost increasing)
price = np.linspace(1, 10, 100)  # Price from 1 to 10

# Demand distribution between fast and standard delivery (summing to 100%)
fast_delivery_share = 100 / (1 + np.exp(1.2 * (price - 4.5)))  # Fast delivery preference decreases with price
standard_delivery_share = 100 - fast_delivery_share  # Standard delivery preference increases accordingly

# Define cost-based sample points at 0%, 25%, 50%, 75%, and 100% of the cost range
cost_levels = [0.0, 0.25, 0.50, 0.75, 0.99]  # Avoid index out of range by using 0.99 instead of 1.0
sample_prices = [price[int(len(price) * c)] for c in cost_levels]  # Select corresponding price points

# Compute the corresponding fast and standard delivery shares
fast_shares = [fast_delivery_share[np.argmin(np.abs(price - p))] for p in sample_prices]
standard_shares = [100 - fs for fs in fast_shares]

# Define colors for scenarios
scenario_colors = ["black", "blue", "orange", "red", "purple"]
# Define updated labels based on cost levels rather than share percentages
scenario_labels = ["Low cost", "25% Higher Cost", "50% Higher Cost", "75% Higher Cost", "High Cost"]


label_offsets = [0, -5, 5, -5, 0]  # Adjust vertical position of text labels

# Create the plot again with improved label placement
plt.figure(figsize=(8, 5))
plt.plot(price, fast_delivery_share, label="Fast delivery (1 day)", linewidth=2, linestyle="dashed", color="blue")
plt.plot(price, standard_delivery_share, label="Standard delivery (2-3 days)", linewidth=2, color="green")

# Mark sample scenarios with same-colored points and connect them with vertical dashed lines
for i, (p, f, s) in enumerate(zip(sample_prices, fast_shares, standard_shares)):
    plt.scatter(p, f, color=scenario_colors[i], s=120, edgecolors="black", zorder=3)  # Fast delivery point
    plt.scatter(p, s, color=scenario_colors[i], s=120, edgecolors="black", zorder=3)  # Standard delivery point
    plt.plot([p, p], [f, s], color=scenario_colors[i], linestyle="dotted", linewidth=2, zorder=2)  # Vertical connector
    plt.text(p, (f + s) / 2 + label_offsets[i], scenario_labels[i], fontsize=12, color=scenario_colors[i],
             ha="center", va="center", bbox=dict(facecolor="white", edgecolor=scenario_colors[i], boxstyle="round,pad=0.3"))

# Adjust labels
plt.xlabel("Increasing additional cost for fast delivery →")
plt.ylabel("Share of users (%)")
plt.title("Preference shift between fast and standard delivery at different cost levels")

# Grid and legend below the plot
plt.grid(True, linestyle="dashed", alpha=0.5)
plt.legend(loc="upper center", bbox_to_anchor=(0.5, -0.15), ncol=2, frameon=False)

# Remove x-axis ticks
plt.xticks([])

plt.show()



# CELL 4 execution=None
# Jetzt: Vertikale Linien = Szenariofarbe | Kreuze = Linienfarbe (B2B/Neutral/B2C)
import numpy as np
import matplotlib.pyplot as plt

# Preisachse
price = np.linspace(1, 10, 100)

# Preis minimal verschieben fuer Modellierung
price_shifted = price - 1.25

# B2C-Modell
fast_delivery_b2c = 10 + (90) / (1 + np.exp(1.5 * (price_shifted - 3)))
batch_delivery_b2c = 100 - fast_delivery_b2c

# B2B-Modell
fast_delivery_b2b = 5 + (95) / (1 + np.exp(3.5 * (price_shifted - 1.75)))
batch_delivery_b2b = 100 - fast_delivery_b2b

# Cost levels
cost_levels = [0.0, 0.25, 0.5, 0.75, 0.999]  # Full batch = fast price ~ max

sample_prices = [price[int(len(price) * c)] for c in cost_levels]

scenario_labels = [
    "Baseline\nScenario",
    "Moderate Cost\nScenario",
    "Medium Cost\nScenario",
    "High Cost\nScenario",
    "Full Batch\nScenario"
 ]

# Scenario colors for vertical lines
scenario_colors = ["black", "blue", "red", "red", "purple"]

# Curve colors for crosses
curve_colors = {
    "fast_b2c": "blue",
    "batch_b2c": "blue",
    "fast_neutral": "green",
    "batch_neutral": "green",
    "fast_b2b": "red",
    "batch_b2b": "red"
}

# === Smooth tail from High -> Full Batch using cubic Hermite interpolation ===
# This ensures C1 continuity: value AND slope match at the High Cost junction
tail_start = sample_prices[-2]  # High Cost x
tail_end = sample_prices[-1]    # Full Batch x
dx = tail_end - tail_start
high_idx = np.argmin(np.abs(price - tail_start))

# Get values and slopes of original sigmoids at High Cost point
val_b2c = fast_delivery_b2c[high_idx]
val_b2b = fast_delivery_b2b[high_idx]
# Finite-difference slope
i_lo = max(high_idx - 1, 0)
i_hi = min(high_idx + 1, len(price) - 1)
slope_b2c = (fast_delivery_b2c[i_hi] - fast_delivery_b2c[i_lo]) / (price[i_hi] - price[i_lo])
slope_b2b = (fast_delivery_b2b[i_hi] - fast_delivery_b2b[i_lo]) / (price[i_hi] - price[i_lo])

# Hermite basis: t in [0,1]
t = np.clip((price - tail_start) / dx, 0, 1)
h00 = 2*t**3 - 3*t**2 + 1   # value at start
h10 = t**3 - 2*t**2 + t      # slope at start
h01 = -2*t**3 + 3*t**2       # value at end (=0)
# h11 not needed (target slope = 0)

# Hermite interpolation: from (val, slope) at High -> (0, 0) at Full Batch
hermite_b2c = h00 * val_b2c + h10 * (slope_b2c * dx)
hermite_b2b = h00 * val_b2b + h10 * (slope_b2b * dx)

# Combine: original sigmoid up to High Cost, Hermite spline after
mask = price > tail_start
fast_b2c_plot = np.where(mask, np.clip(hermite_b2c, 0, 100), fast_delivery_b2c)
fast_b2b_plot = np.where(mask, np.clip(hermite_b2b, 0, 100), fast_delivery_b2b)
batch_b2c_plot = 100 - fast_b2c_plot
batch_b2b_plot = 100 - fast_b2b_plot

# Create the plot
plt.figure(figsize=(8,5 ))

# Plot curves with updated legend labels
plt.plot(price, fast_b2c_plot, linestyle="--", color="blue", linewidth=2, label="Conventional Delivery (B2C)")
plt.plot(price, batch_b2c_plot, linestyle="-", color="blue", linewidth=2, label="Batch Delivery (B2C)")

# plt.plot(price, fast_delivery_neutral, linestyle="--", color="green", linewidth=2, label="Fast Delivery (Neutral)")
# plt.plot(price, batch_delivery_neutral, linestyle="-", color="green", linewidth=2, label="Batch Delivery (Neutral)")

plt.plot(price, fast_b2b_plot, linestyle="--", color="red", linewidth=2, label="Conventional Delivery (B2B)")
plt.plot(price, batch_b2b_plot, linestyle="-", color="red", linewidth=2, label="Batch Delivery (B2B)")

# Add vertical lines and crosses at intersections
for i, p in enumerate(sample_prices):
    scenario_color = scenario_colors[i]
    
    # Vertical line from 0 to 100 in scenario color
    plt.plot([p, p], [0, 100], color=scenario_color, linestyle="dotted", linewidth=2, zorder=1)
    
    # Find closest index
    idx = np.argmin(np.abs(price - p))
    
    # Plot crosses in curve color
    plt.scatter(p, fast_b2c_plot[idx], color=curve_colors["fast_b2c"], marker='x', s=50, linewidths=1, zorder=3)
    plt.scatter(p, batch_b2c_plot[idx], color=curve_colors["batch_b2c"], marker='x', s=50, linewidths=1, zorder=3)
    
    plt.scatter(p, fast_b2b_plot[idx], color=curve_colors["fast_b2b"], marker='x', s=50, linewidths=1, zorder=3)
    plt.scatter(p, batch_b2b_plot[idx], color=curve_colors["batch_b2b"], marker='x', s=50, linewidths=1, zorder=3)

# Style settings
plt.xlabel("Increasing Additional Cost for Conventional Delivery ->", fontsize=10, fontweight='bold')
plt.xticks(sample_prices, scenario_labels, rotation=0, ha="center", fontsize=12)

plt.ylabel("Share of Users [%]", fontsize=12)
plt.ylim(-5, 105)
plt.grid(False)

plt.xlim(1, 10)

# Legend (manuell sortiert)
handles, labels = plt.gca().get_legend_handles_labels()
label_handle_dict = dict(zip(labels, handles))

desired_order = [
    "Conventional Delivery (B2B)",
    "Batch Delivery (B2B)",
    "Conventional Delivery (B2C)",
    "Batch Delivery (B2C)"
 ]

sorted_handles = [label_handle_dict[label] for label in desired_order]
sorted_labels = desired_order

plt.legend(sorted_handles, sorted_labels, loc="upper center", bbox_to_anchor=(0.5, -0.22), ncol=2, frameon=True, fontsize=10)

plt.tight_layout()
plt.savefig("output/preference_shift_b2b_b2c.pdf", dpi=300, bbox_inches='tight')
plt.show()

# CELL 5 execution=4
def load_all_hagrid_shapefiles(output_dir="output", prefix="hagrid_parcel_demand"):
    """
    Loads all HAGRID Shapefiles from the specified output directory individually.

    Parameters
    ----------
    output_dir : str, default="output"
        Directory where the HAGRID shapefiles are stored.
    prefix : str, default="hagrid_parcel_demand"
        Common filename prefix to identify relevant shapefiles.

    Returns
    -------
    shapefile_dict : dict
        Dictionary where keys are filenames (without extension) and values are GeoDataFrames.
    """
    # Find all matching shapefiles
    shp_files = [f for f in os.listdir(output_dir) if f.endswith(".shp") and f.startswith(prefix)]

    if not shp_files:
        raise FileNotFoundError(f"No shapefiles starting with '{prefix}' found in '{output_dir}'.")

    # Load each shapefile into a dictionary
    shapefile_dict = {}
    for shp_file in sorted(shp_files):  # sorted() to keep chronological order
        path = os.path.join(output_dir, shp_file)
        gdf = gpd.read_file(path)
        key = os.path.splitext(shp_file)[0]  # filename without '.shp'
        shapefile_dict[key] = gdf

    return shapefile_dict

# CELL 6 execution=5
carrier_columns = {
    'Amazon': ('ama', 'amazon_tag', 'amazon_typ'),
    'DHL': ('dhl', 'dhl_tag', 'dhl_type'),
    'DPD': ('dpd', 'dpd_tag', 'dpd_type'),
    'GLS': ('gls', 'gls_tag', 'gls_type'),
    'Hermes': ('her', 'hermes_tag', 'hermes_typ'),
    'UPS': ('ups', 'ups_tag', 'ups_type'),
    'FedEx/TNT': ('fxt', 'fedex_tag', 'fedex_type')
}

# Alle GDFs laden
hagrid_files = load_all_hagrid_shapefiles(output_dir="output")

for filename, gdf in hagrid_files.items():
    total_b2b = gdf[[col for col in gdf.columns if col.endswith('_b2b')]].sum().sum()
    total_b2c = gdf[[col for col in gdf.columns if col.endswith('_b2c')]].sum().sum()
    total_packages = total_b2b + total_b2c

    tag_cols = [tag for _, (_, tag, _) in carrier_columns.items() if tag in gdf.columns]
    type_cols = [typ for _, (_, _, typ) in carrier_columns.items() if typ in gdf.columns]
    total_tag = gdf[tag_cols].sum().sum()
    total_type = gdf[type_cols].sum().sum()
    total_tag_type = total_tag + total_type

    print("=" * 80)
    print(f"{filename}: B2B={total_b2b}, B2C={total_b2c}, Total={total_packages} | tag={total_tag}, type={total_type}, tag+type={total_tag_type}")
    
    for carrier, (prefix, _, _) in carrier_columns.items():
        b2b_col = f"{prefix}_b2b"
        b2c_col = f"{prefix}_b2c"

        b2b_sum = gdf[b2b_col].sum() if b2b_col in gdf.columns else 0
        b2c_sum = gdf[b2c_col].sum() if b2c_col in gdf.columns else 0

        print(f"  {carrier:<10} | B2B: {b2b_sum:>7} | B2C: {b2c_sum:>7}")

    print()


# CELL 7 execution=6
# ===============================
# Imports
# ===============================

from collections import defaultdict
import numpy as np
import pandas as pd
import datetime
import matplotlib.pyplot as plt
from tqdm import tqdm
import logging

import copy
import re



# ===============================
# Logger Setup
# ===============================

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
LOGGER = logging.getLogger(__name__)

# ===============================
# Parameters and Scenario Settings
# ===============================

# Preisachse
price = np.linspace(1, 10, 100)

# Preis minimal verschieben für Modellierung
price_shifted = price - 1.25

# B2C-Modell
fast_delivery_b2c = 10 + (90) / (1 + np.exp(1.5 * (price_shifted - 3)))
batch_delivery_b2c = 100 - fast_delivery_b2c

# B2B-Modell
fast_delivery_b2b = 5 + (95) / (1 + np.exp(3.5 * (price_shifted - 1.75)))
batch_delivery_b2b = 100 - fast_delivery_b2b

# Cost levels
cost_levels = [0.0, 0.25, 0.5, 0.75]  # Nur vier Punkte
cost_labels = ['low_cost', 'moderate_cost', 'medium_cost', 'high_cost']

scenario_probs = {}
for cost, label in zip(cost_levels, cost_labels):
    idx = int(len(price) * cost)
    scenario_probs[label] = {
        'b2c': {'fast': fast_delivery_b2c[idx] / 100, 'batch': batch_delivery_b2c[idx] / 100},
        'b2b': {'fast': fast_delivery_b2b[idx] / 100, 'batch': batch_delivery_b2b[idx] / 100}
    }

scenario_probs['full_scenario'] = {
    'b2c': {'fast': 0.0, 'batch': 1.0},
    'b2b': {'fast': 0.0, 'batch': 1.0}
}

carrier_days = {
    'DHL': ['Monday', 'Wednesday', 'Friday'],
    'Hermes': ['Tuesday', 'Saturday'],
    'DPD': ['Tuesday', 'Friday'],
    'GLS': ['Monday', 'Thursday'],
    'UPS': ['Monday', 'Thursday'],
    'Amazon': ['Tuesday', 'Thursday', 'Saturday'],
    'FedEx/TNT': ['Monday', 'Thursday']
}

# carrier_days = {
#     'DHL': ['Monday', 'Friday'],
#     'Hermes': ['Monday', 'Friday'],
#     'DPD': ['Monday', 'Friday'],
#     'GLS': ['Monday', 'Friday'],
#     'UPS': ['Monday', 'Friday'],
#     'Amazon': ['Monday', 'Friday'],
#     'FedEx/TNT': ['Monday', 'Friday']
# }

carrier_columns = {
    'Amazon': ('ama', 'amazon_tag', 'amazon_typ'),
    'DHL': ('dhl', 'dhl_tag', 'dhl_type'),
    'DPD': ('dpd', 'dpd_tag', 'dpd_type'),
    'GLS': ('gls', 'gls_tag', 'gls_type'),
    'Hermes': ('her', 'hermes_tag', 'hermes_typ'),
    'UPS': ('ups', 'ups_tag', 'ups_type'),
    'FedEx/TNT': ('fxt', 'fedex_tag', 'fedex_type')
}

# ===============================
# Helper Functions
# ===============================

def find_source_days_since_last_delivery(current_day, carrier_days_mapping, carrier):
    """
    Collect source days for current_day if it is a delivery day for the carrier.
    Otherwise, return empty (no delivery today).
    """

    days_order = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
    day_idx = days_order.index(current_day)
    carrier_delivery_days = carrier_days_mapping.get(carrier, [])

    # >>>>> NEW: Check if carrier delivers today
    if current_day not in carrier_delivery_days:
        return []  # No delivery today

    source_days = []
    for offset in range(1, len(days_order)):
        previous_idx = (day_idx - offset) % len(days_order)
        previous_day = days_order[previous_idx]
        
        if previous_day in carrier_delivery_days:
            break  # STOP, previous delivery day found
        
        source_days.append(previous_day)

    source_days.reverse()
    return source_days    


def get_geodataframes(hagrid_files_thisweek, source_days, base_date, days, current_date):
    if source_days is None:
        return {}

    result = {}
    days_order = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
    weekday_map = {'Monday': 0, 'Tuesday': 1, 'Wednesday': 2, 'Thursday': 3, 'Friday': 4, 'Saturday': 5}

    def get_previous_weekday(current_date, target_weekday):
        days_diff = (current_date.weekday() - target_weekday) % 7
        return current_date - timedelta(days=days_diff or 7)

    for day in source_days:
        if day not in days_order:
            raise ValueError(f"Invalid day: {day}")

        target_date = get_previous_weekday(current_date, weekday_map[day])

        # === Validierungen:
        assert target_date <= current_date, f"Calculated target_date {target_date} is after current_date {current_date}!"
        assert target_date.weekday() == weekday_map[day], (
            f"Calculated target_date {target_date} is not a {day}! (got weekday {target_date.weekday()})"
        )

        date_str = target_date.strftime("%Y-%m-%d")
        key = f"hagrid_parcel_demand_{date_str}_({day})"

        LOGGER.info(f"Fetching source {day}: {key} (target_date: {target_date})")

        if key not in hagrid_files_thisweek:
            raise KeyError(f"Missing file for {key}.")

        result[day] = hagrid_files_thisweek[key].copy(deep=True)

    return result



def assign_tags_types_wltag(new_week):
    """
    For each day and each carrier, assign b2c counts to _tag, b2b counts to _type, 
    and update wl_tag and total columns. Includes a final assert check.
    """
    for day, df in new_week.items():
        LOGGER.info(f"Assigning tags/types for {day}...")

        # 1. B2C -> _tag, B2B -> _type
        for carrier, (prefix, tag_col, type_col) in carrier_columns.items():
            if f"{prefix}_b2c" in df.columns and f"{prefix}_b2b" in df.columns:
                df[tag_col] = df[f"{prefix}_b2c"]
                df[type_col] = df[f"{prefix}_b2b"]

        # 2. wl_tag = Summe aller _tag und _type Spalten
        tag_cols = [v[1] for v in carrier_columns.values()]
        type_cols = [v[2] for v in carrier_columns.values()]
        df['wl_tag'] = df[tag_cols + type_cols].sum(axis=1)

        # 3. total = Summe aller _b2b und _b2c Spalten
        df['total'] = df[[col for col in df.columns if col.endswith('_b2b') or col.endswith('_b2c')]].sum(axis=1)

        # 4. Harte Endprüfung: Summe total == wl_tag
        total_sum = df['total'].sum()
        wl_tag_sum = df['wl_tag'].sum()

        LOGGER.info(f"Validation totals for {day}: total={total_sum}, wl_tag={wl_tag_sum}")

        assert np.isclose(total_sum, wl_tag_sum), (
            f"Mismatch in totals for {day}: total={total_sum}, wl_tag={wl_tag_sum}"
        )

WEEKDAYS_EN = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']
def find_next_delivery_date(current_date: datetime.date, carrier: str, carrier_days: dict) -> datetime.date | None:
    delivery_days = carrier_days.get(carrier)
    if not delivery_days:
        LOGGER.info(f"{carrier}: No delivery days defined.")
        return None  # Falls der Carrier nicht existiert
    
    for offset in range(1, 7):  # Bis zu 2 Wochen nach vorne prüfen        
        candidate_date = current_date + datetime.timedelta(days=offset)
        weekday_name = WEEKDAYS_EN[candidate_date.weekday()]
        if weekday_name in delivery_days:
            return candidate_date  # Sofort zurückgeben
    LOGGER.info(f"{carrier}: No delivery day found within 1 weeks after {current_date}")
    return None 

def build_days_list(start_date: datetime.date, days: list = None):
    """
    Erzeugt eine Liste mit echten Tagesnamen ('Monday', 'Tuesday', ...) 
    und Mapping von Datum zu Tag.

    Args:
        start_date (datetime.date): Startdatum der Simulation.
        days (list, optional): Liste der Wochentage (z.B. ['Monday', 'Tuesday', ...]).
                               Falls None, wird automatisch ['Monday', ..., 'Saturday'] erzeugt.

    Returns:
        days (list of str): ['Monday', 'Tuesday', ..., 'Saturday']
        date_to_day (dict): {datetime.date: 'Monday', ...}
    """
    if days is None:
        days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']

    num_days = len(days)
    date_to_day = {}
    for i in range(num_days):
        current_date = start_date + datetime.timedelta(days=i)
        day_name = current_date.strftime('%A')
        assert day_name == days[i], (
            f"Mismatch at day {i}: expected {days[i]} but got {day_name} from date {current_date}"
        )
        date_to_day[current_date] = day_name

    return days, date_to_day


def handle_future_delivery_outside_simulation(new_week, day_to_date, carrier_columns, carrier_days, probs):

    weekday_map = {
        'Montag': 'Monday',
        'Dienstag': 'Tuesday',
        'Mittwoch': 'Wednesday',
        'Donnerstag': 'Thursday',
        'Freitag': 'Friday',
        'Samstag': 'Saturday',
        'Sonntag': 'Sunday'
    }


    LOGGER.info("=" * 60)
    LOGGER.info(" *** STARTING POST-PROCESS: HANDLE FUTURE DELIVERIES OUTSIDE SIMULATION ***")
    LOGGER.info("=" * 60)

    total_removed = 0
    available_days = list(new_week.keys())
    available_dates = set(day_to_date.values())
    LOGGER.info(f"Available days: {available_days}")
    LOGGER.info(f"Available dates: {available_dates}")

    change_log = []
    for current_day in available_days:
        current_date = day_to_date[current_day]

        for carrier, (prefix, _, _) in carrier_columns.items():

            df = new_week[current_day]
            b2b_col = f"{prefix}_b2b"
            b2c_col = f"{prefix}_b2c"
            if b2b_col not in df.columns and b2c_col not in df.columns:
                continue

            # Finde nächstes Zustelldatum
            next_delivery_date = find_next_delivery_date(current_date, carrier, carrier_days)
            LOGGER.info(f"{carrier}: {current_day}. Next found delivery day: {next_delivery_date} ")

            if current_day in carrier_days.get(carrier, []):
                LOGGER.info(f"{carrier}: {current_day} is an actual delivery day – skipping postprocessing.")
                continue

            # Jetzt: Wenn dieser nächste Zustelltag NICHT in der Simulationswoche liegt
            if next_delivery_date not in available_dates:
                LOGGER.info(f"{carrier}: {current_day} collects for next delivery on {next_delivery_date} (outside simulation) → removing batch!")
                LOGGER.info(
                    f"{carrier}: Removing batch packages from {current_day} (date: {current_date}) "
                    f"→ would deliver on {next_delivery_date.strftime('%A')} ({next_delivery_date})"
                )

                b2b_vals = df.get(b2b_col, pd.Series(0, index=df.index)).fillna(0).clip(lower=0).astype(int)
                b2c_vals = df.get(b2c_col, pd.Series(0, index=df.index)).fillna(0).clip(lower=0).astype(int)

                total_b2b = b2b_vals.sum()
                total_b2c = b2c_vals.sum()


                if total_b2b > 0:
                    fast_flags_b2b = np.random.rand(total_b2b) < probs['b2b']['fast']
                    fast_b2b_count = fast_flags_b2b.sum()
                    batch_b2b_count = total_b2b - fast_b2b_count
                else:
                    fast_b2b_count = batch_b2b_count = 0

                if total_b2c > 0:
                    fast_flags_b2c = np.random.rand(total_b2c) < probs['b2c']['fast']
                    fast_b2c_count = fast_flags_b2c.sum()
                    batch_b2c_count = total_b2c - fast_b2c_count
                else:
                    fast_b2c_count = batch_b2c_count = 0

                dest_date_value = next_delivery_date
                source_date_value = pd.to_datetime(day_to_date[current_day]).date()

                weekday_de = next_delivery_date.strftime('%A')
                weekday_en = weekday_map.get(weekday_de, weekday_de)


                change_log.append({
                    "carrier": carrier,
                    "source_day": current_day,
                    "source_date": source_date_value,
                    "dest_day": weekday_en,
                    "dest_date": dest_date_value,
                    "b2b_batch": batch_b2b_count,
                    "b2c_batch": batch_b2c_count,
                    "b2b_fast": fast_b2b_count,
                    "b2c_fast": fast_b2c_count
                })


            else:
                LOGGER.info(
                    f"{carrier}: Next delivery after {current_day} ({current_date}) is {next_delivery_date.strftime('%A')} ({next_delivery_date}) "
                    f"→ within simulation window → skipping batch removal."
                )

    return change_log



def initialize_simulation_data(hagrid_files_thisweek, days, start_date):
    """Initializes data for each day in the simulation week."""
    hagrid_files_original = {k: v.copy(deep=True) for k, v in hagrid_files_thisweek.items()}
    new_week = {}
    for i, day in enumerate(days):
        date_str = (start_date + datetime.timedelta(days=i)).strftime("%Y-%m-%d")
        key = f"hagrid_parcel_demand_{date_str}_({day})"
        if key not in hagrid_files_thisweek:
            raise KeyError(f"Missing file for {key}.")
        df = hagrid_files_thisweek[key].copy(deep = True)
        new_week[day] = df
        LOGGER.info(f"Loaded and initialized data for {day}.")
    return hagrid_files_original, new_week

def simulate_day_for_carriers(current_day, carrier_columns, carrier_days, hagrid_files_original, start_date, days, probs, day_to_date):
    """Simulates batch deliveries for all carriers on a specific day."""
    all_changes = []
    for carrier in carrier_columns.keys():
        if current_day not in carrier_days.get(carrier, []):
            LOGGER.info(f"{carrier} does not have a batch delivery on {current_day}. Skipping.")
            continue

        current_date = day_to_date[current_day]
        source_days_for_carrier = find_source_days_since_last_delivery(current_day, carrier_days, carrier)
        sourceDaysDeliveries = get_geodataframes(hagrid_files_original, source_days_for_carrier, start_date, days, current_date)

        for source_day, source_day_gdf in sourceDaysDeliveries.items():
            LOGGER.info(f"Source day {source_day} for {carrier}: {len(source_day_gdf)} rows and date {source_day_gdf['date'].unique()}")

        prefix, _, _ = carrier_columns[carrier]
        
        if not source_days_for_carrier:
            LOGGER.info(f"No source days found for {carrier} on {current_day}.")
            continue

        changes = assign_batch_deliveries(sourceDaysDeliveries, current_day, carrier, prefix, probs, day_to_date)
        all_changes.extend(changes)
    return all_changes

def aggregate_day_changes(change_df):
    """Aggregates batch changes across days and carriers."""
    carriers = change_df['carrier'].unique()
    all_dates = pd.unique(change_df[['source_date', 'dest_date']].values.ravel())

    day_changes = pd.DataFrame(0, index=pd.MultiIndex.from_product([carriers, all_dates], names=['carrier', 'date']),
                               columns=['b2b_delta', 'b2c_delta'])

    for _, row in change_df.iterrows():
        carrier = row['carrier']
        src_date = row['source_date']
        dst_date = row['dest_date']

        day_changes.loc[(carrier, dst_date), 'b2b_delta'] += row['b2b_batch']
        day_changes.loc[(carrier, dst_date), 'b2c_delta'] += row['b2c_batch']
        day_changes.loc[(carrier, src_date), 'b2b_delta'] -= row['b2b_batch']
        day_changes.loc[(carrier, src_date), 'b2c_delta'] -= row['b2c_batch']

    summary_per_date = day_changes.groupby('date')[['b2b_delta', 'b2c_delta']].sum()
    summary_per_date['total_delta'] = summary_per_date['b2b_delta'] + summary_per_date['b2c_delta']
    return summary_per_date

def merge_post_simulation_changes(summary_per_date, post_changes, original_df):
    """Merges post-simulation adjustments into the main summary."""
    post_change_df = pd.DataFrame(post_changes).rename(columns={'source_date': 'date'})

    post_change_df['b2b_batch'] = -post_change_df['b2b_batch']
    post_change_df['b2c_batch'] = -post_change_df['b2c_batch']
    post_change_df['total_delta'] = post_change_df['b2b_batch'] + post_change_df['b2c_batch']

    post_change_summary = post_change_df.groupby('date')[['b2b_batch', 'b2c_batch', 'total_delta']].sum()

    final_summary = summary_per_date.merge(
        post_change_summary,
        on='date',
        how='outer',
        suffixes=('', '_post')
    ).fillna(0)

    final_summary['b2b_final'] = final_summary['b2b_delta'] + final_summary['b2b_batch']
    final_summary['b2c_final'] = final_summary['b2c_delta'] + final_summary['b2c_batch']
    final_summary['total_final'] = final_summary['b2b_final'] + final_summary['b2c_final']

    return final_summary

def collect_original_totals(hagrid_files_thisweek):
    """Collects the original package totals per date from input data."""
    original_totals = {}
    for key, df in hagrid_files_thisweek.items():
        b2b_cols = [col for col in df.columns if col.endswith('_b2b')]
        b2c_cols = [col for col in df.columns if col.endswith('_b2c')]
        total_b2b = df[b2b_cols].fillna(0).sum().sum()
        total_b2c = df[b2c_cols].fillna(0).sum().sum()
        original_totals[key] = total_b2b + total_b2c

    original_totals_parsed = {}
    for key, total in original_totals.items():
        match = re.search(r'_(\d{4}-\d{2}-\d{2})_', key)
        if match:
            date = pd.to_datetime(match.group(1)).date()
            original_totals_parsed[date] = total

    return pd.DataFrame.from_dict(original_totals_parsed, orient='index', columns=['original_total']).rename_axis('date')

def build_final_carrier_date_deltas(day_changes, post_changes, final_summary):
    """
    Combines day_changes (batch simulation results) and post_changes (post-simulation adjustments)
    into a consolidated DataFrame with MultiIndex (carrier, date) and columns:
    b2b_delta, b2c_delta, total_delta.

    Also validates:
    1. That per date: sum(b2b_delta + b2c_delta) == total_delta
    2. That aggregated totals match final_summary values

    Raises ValueError if discrepancies are found.
    """
    # Convert post_changes to DataFrame if necessary
    if isinstance(post_changes, list):
        post_changes = pd.DataFrame(post_changes)  

    if 'date' not in final_summary.columns:
        final_summary = final_summary.reset_index()

    post_changes_df = post_changes[['carrier', 'source_date', 'b2b_batch', 'b2c_batch']].copy()
    post_changes_df = post_changes_df.rename(columns={'source_date': 'date'})
    post_changes_df['b2b_delta'] = - post_changes_df['b2b_batch']
    post_changes_df['b2c_delta'] = - post_changes_df['b2c_batch']
    post_changes_df['total_delta'] = post_changes_df['b2b_delta'] + post_changes_df['b2c_delta']
    post_changes_df_prepared = post_changes_df[['carrier', 'date', 'b2b_delta', 'b2c_delta', 'total_delta']]

    combined_changes = pd.concat([day_changes.reset_index(), post_changes_df_prepared], axis=0)
    final_deltas = combined_changes.groupby(['carrier', 'date'])[['b2b_delta', 'b2c_delta', 'total_delta']].sum()    
    final_deltas_per_date = final_deltas.groupby('date')[['total_delta']].sum().reset_index()

    # Merge beide
    merged_check = final_deltas_per_date.merge(final_summary[['date', 'total_final']], on='date', how='outer')    
    merged_check['diff'] = merged_check['total_delta'] - merged_check['total_final']

    # Prüfe Abweichungen
    if not merged_check[np.abs(merged_check['diff']) > 1e-6].empty:
        raise ValueError(f"Mismatch between total_delta and total_final!\n{merged_check[merged_check['diff'].abs() > 1e-6]}")
    else:
        LOGGER.info("✅ Validation passed: total_delta equals total_final for each date.")
        
    return final_deltas

def build_carrier_date_deltas_from_change_df(change_df):
    """
    Builds a DataFrame with carrier, date, b2b_delta, b2c_delta from change_df.
    """
    records = []

    for _, row in change_df.iterrows():
        carrier = row['carrier']
        src_date = row['source_date']
        dst_date = row['dest_date']
        b2b = row['b2b_batch']
        b2c = row['b2c_batch']

        # Add positive delta to dest_date
        records.append({'carrier': carrier, 'date': dst_date, 'b2b_delta': b2b, 'b2c_delta': b2c})

        # Add negative delta to src_date
        records.append({'carrier': carrier, 'date': src_date, 'b2b_delta': -b2b, 'b2c_delta': -b2c})

    df = pd.DataFrame(records)
    df = df.groupby(['carrier', 'date'])[['b2b_delta', 'b2c_delta']].sum()
    df['total_delta'] = df['b2b_delta'] + df['b2c_delta']

    return df

def extract_date_from_key(key):
    import re
    match = re.search(r'_(\d{4}-\d{2}-\d{2})_', key)
    if match:
        return pd.to_datetime(match.group(1)).date()
    raise ValueError(f"Cannot extract date from key: {key}")

def plot_week_comparison(hagrid_files_original, new_week, day_to_date):
    """
    Plots total packages per day: original vs simulated (new_week).
    """
    original_totals = {}
    new_totals = {}

    for key, df in hagrid_files_original.items():
        date = extract_date_from_key(key)
        total = df[[col for col in df.columns if col.endswith('_b2b') or col.endswith('_b2c')]].fillna(0).sum().sum()
        original_totals[date] = total

    for day, df in new_week.items():
        total = df[[col for col in df.columns if col.endswith('_b2b') or col.endswith('_b2c')]].fillna(0).sum().sum()
        new_totals[day_to_date[day]] = total  # convert day → date

    # to DataFrame
    original_df = pd.DataFrame.from_dict(original_totals, orient='index', columns=['original_total'])
    
    new_df = pd.DataFrame.from_dict(new_totals, orient='index', columns=['simulated_total'])
    original_df = original_df.loc[original_df.index.isin(new_df.index)]

    plot_df = original_df.join(new_df, how='outer').fillna(0)
    plot_df = plot_df.sort_index()

    x_labels = [d.strftime('%Y-%m-%d') if isinstance(d, datetime.date) else str(d) for d in plot_df.index]
    x = np.arange(len(x_labels))
    width = 0.4

    plt.figure(figsize=(12,6))
    plt.bar(x - width/2, plot_df['original_total'], width, label='Original')
    plt.bar(x + width/2, plot_df['simulated_total'], width, label='Simulated')
    plt.xticks(x, x_labels, rotation=45)
    plt.ylabel('Pakete')
    plt.title('Original vs. Simulated Total Parcel Volumes After Data Processing')
    plt.legend()
    plt.grid(axis='y')
    plt.tight_layout()
    plt.show()


def plot_simulation_results(final_summary, original_df, sim_start_date):
    """Plots original vs simulated totals per date."""
    plot_df = original_df.merge(final_summary[['total_final']], on='date', how='left').fillna(0)
    plot_df['simulated_total'] = plot_df['original_total'] + plot_df['total_final']
    plot_df_filtered = plot_df[plot_df.index >= sim_start_date]

    x_labels = [d.strftime('%Y-%m-%d') if isinstance(d, datetime.date) else str(d) for d in plot_df_filtered.index]
    x = np.arange(len(x_labels))
    width = 0.4

    fig, ax = plt.subplots(figsize=(12,6))
    rects1 = ax.bar(x - width/2, plot_df_filtered['original_total'], width, label='Original')
    rects2 = ax.bar(x + width/2, plot_df_filtered['simulated_total'], width, label='Simulated')

    # 🟢 Hier: Werte direkt auf die Balken schreiben
    ax.bar_label(rects1, padding=3, fmt='%.0f')
    ax.bar_label(rects2, padding=3, fmt='%.0f')

    ax.set_xticks(x)
    ax.set_xticklabels(x_labels, rotation=45)
    ax.set_ylabel('Parcel')
    ax.set_title('Original vs. Simulated Total Parcel Volumes Before Data Processing')
    ax.legend()
    ax.grid(axis='y')
    plt.tight_layout()
    plt.show()

def process_carrier_date_delta(carrier, date, delta, eligible_df, col_name, new_week_day, lookup_coords_day):
    """
    Processes delta (b2b or b2c) for a given carrier + date:
    - distributes delta randomly over eligible rows
    - updates new_week_day DataFrame
    """
    if delta > 0:
        remaining_capacity = eligible_df[col_name]
        assigned = distribute_parcels(delta, remaining_capacity, strategy="random")
    elif delta < 0:
        LOGGER.error(f"Negative delta encountered for carrier {carrier} on {date}: {delta}")
        raise ValueError(f"Negative delta not supported for carrier {carrier} on {date}")
    else:
        assigned = pd.Series(0, index=eligible_df.index)

    for idx, amount in assigned.items():
        if amount <= 0:
            continue

        geometry_wkt = eligible_df.at[idx, 'geometry'].wkt if pd.notna(eligible_df.at[idx, 'geometry']) else None
        if geometry_wkt and geometry_wkt in lookup_coords_day:
            target_idx = lookup_coords_day[geometry_wkt]
            new_week_day.at[target_idx, col_name] += amount
        else:
            # Create new row
            new_row = pd.Series(0, index=new_week_day.columns)

            # Kopiere notwendige Identifikatoren
            for id_col in ['str_idx', 'name', 'postal_code', 'cell_id', 'geometry']:
                new_row[id_col] = eligible_df.at[idx, id_col]

            # Setze Carrier-Spalten
            new_row[col_name] = amount  # b2b oder b2c je nach Aufruf

            for other_carrier in carrier_columns:
                if other_carrier != carrier:
                    other_b2b_col = carrier_columns[other_carrier][1]
                    other_b2c_col = carrier_columns[other_carrier][2]
                    new_row[other_b2b_col] = 0
                    new_row[other_b2c_col] = 0

            # Berechne total_sim, total, wl_tag
            new_row['total_sim'] = sum(new_row[col] for col in new_week_day.columns if col.endswith('_b2b') or col.endswith('_b2c'))
            new_row['total'] = new_row['total_sim']
            new_row['wl_tag'] = new_row['total_sim']

            # Optional: auch tag/type-Spalten explizit nullen, falls nötig
            for col in new_week_day.columns:
                if '_tag' in col or '_type' in col:
                    new_row[col] = 0

            # Hänge neue Zeile an
            new_week_day = pd.concat([new_week_day, new_row.to_frame().T], ignore_index=True)

            # Update lookup
            lookup_coords_day[geometry_wkt] = new_week_day.index[-1]

    return new_week_day, lookup_coords_day

def validate_transfer_sums(source_df, target_df, b2b_col, b2c_col, source_day_name, target_day_name, carrier):
    initial_b2b_source = source_df[b2b_col].sum()
    initial_b2c_source = source_df[b2c_col].sum()
    initial_b2b_target = target_df[b2b_col].sum()
    initial_b2c_target = target_df[b2c_col].sum()

    total_b2b_before = initial_b2b_source + initial_b2b_target
    total_b2c_before = initial_b2c_source + initial_b2c_target

    LOGGER.debug(f"[{carrier}] Initial sums – {source_day_name}: B2B={initial_b2b_source}, B2C={initial_b2c_source}; "
                 f"{target_day_name}: B2B={initial_b2b_target}, B2C={initial_b2c_target}")

    def after_sums():
        b2b_source = source_df[b2b_col].sum()
        b2c_source = source_df[b2c_col].sum()
        b2b_target = target_df[b2b_col].sum()
        b2c_target = target_df[b2c_col].sum()
        return b2b_source, b2c_source, b2b_target, b2c_target

    b2b_source_after, b2c_source_after, b2b_target_after, b2c_target_after = after_sums()

    total_b2b_after = b2b_source_after + b2b_target_after
    total_b2c_after = b2c_source_after + b2c_target_after

    LOGGER.debug(f"[{carrier}] Final sums – {source_day_name}: B2B={b2b_source_after}, B2C={b2c_source_after}; "
                 f"{target_day_name}: B2B={b2b_target_after}, B2C={b2c_target_after}")

    assert np.isclose(total_b2b_before, total_b2b_after), \
        f"⚠️ B2B SUM MISMATCH for {carrier} ({source_day_name}→{target_day_name}): {total_b2b_before} → {total_b2b_after}"
    assert np.isclose(total_b2c_before, total_b2c_after), \
        f"⚠️ B2C SUM MISMATCH for {carrier} ({source_day_name}→{target_day_name}): {total_b2c_before} → {total_b2c_after}"

    LOGGER.info(f"✅ Validation passed for {carrier}: totals consistent from {source_day_name} → {target_day_name}")


def distribute_parcels(count, remaining_capacity, strategy="random", weights=None):
    """
    Distributes 'count' packages over available rows in remaining_capacity Series.
    
    Parameters:
        count (int): number of packages to assign
        remaining_capacity (pd.Series): index = row index, values = remaining capacity per row
        strategy (str): "random" or "weighted"
        weights (pd.Series or None): optional weights for weighted strategy

    Returns:
        pd.Series: index = same as remaining_capacity, values = assigned packages per row

    Raises:
        RuntimeError: if not enough capacity to assign all packages
    """

    assigned = pd.Series(0, index=remaining_capacity.index)
    to_assign = count

    # next_log_threshold = original_count - original_count // 4  # Start bei 75%

    if strategy == "random":
        while to_assign > 0:
            candidates = remaining_capacity[remaining_capacity > 0]
            if candidates.empty:
                raise RuntimeError(f"No candidates left to assign packages, but {to_assign} remain!")

            chosen_idx = np.random.choice(candidates.index)
            assigned.at[chosen_idx] += 1
            remaining_capacity.at[chosen_idx] -= 1
            to_assign -= 1

            # if to_assign <= next_log_threshold:
            #     LOGGER.info(f"➡️ distribute_parcels: {original_count - to_assign}/{original_count} assigned ({100*(original_count - to_assign)/original_count:.0f}%)")
            #     next_log_threshold -= original_count // 4  # nächstes 25% Intervall

    elif strategy == "weighted":
        raise NotImplementedError("Weighted strategy not implemented yet.")

    else:
        raise ValueError(f"Unknown strategy: {strategy}")

    return assigned

def subtract_at_source_date(carrier, source_date, b2b_delta, b2c_delta, new_week, day_to_date):
    """
    Subtracts b2b_delta and b2c_delta at source_date (randomly distributed over rows with >0).
    """
    day = [k for k,v in day_to_date.items() if v == source_date][0]  # find day-name for date
    df = new_week[day]

    prefix, _, _ = carrier_columns[carrier]

    b2b_col = f"{prefix}_b2b"
    b2c_col = f"{prefix}_b2c"

    # Filter rows where >0 packages exist
    eligible_b2b = df[df[b2b_col] > 0]
    eligible_b2c = df[df[b2c_col] > 0]


    if b2b_delta > 0:
        LOGGER.debug(f"Subtracting {b2b_delta} B2B from {carrier} on {source_date}")
        remaining_capacity = eligible_b2b[b2b_col].copy()
        assigned = distribute_parcels(b2b_delta, remaining_capacity, strategy="random")

        for idx, amount in assigned.items():
            df.at[idx, b2b_col] -= amount
            assert df.at[idx, b2b_col] >= 0, f"B2B negative at idx {idx}"

    if b2c_delta > 0:
        LOGGER.debug(f"Subtracting {b2c_delta} B2C from {carrier} on {source_date}")
        remaining_capacity = eligible_b2c[b2c_col].copy()
        assigned = distribute_parcels(b2c_delta, remaining_capacity, strategy="random")

        for idx, amount in assigned.items():
            df.at[idx, b2c_col] -= amount
            assert df.at[idx, b2c_col] >= 0, f"B2C negative at idx {idx}"

    new_week[day] = df  # updated DataFrame rein (falls inplace nicht sicher)

def remove_at_source_date_and_add_at_current_date(
    carrier,
    hagrid_files_original,
    source_date,
    current_date,
    b2b_delta,
    b2c_delta,
    new_week,
    day_to_date,
    lookup_coords
):
    """
    Entfernt Pakete am source_date (auf Basis von hagrid_files_original) 
    und fügt sie am current_date in new_week hinzu.
    """

    # 1️⃣ → Determine the day names (if source_date is part of the simulation week)
    source_day_names = [day for day, date in day_to_date.items() if date == source_date]
    target_day_name = [day for day, date in day_to_date.items() if date == current_date][0]

    source_day_name = source_day_names[0] if source_day_names else None

    # 2️⃣ → Find the correct key from hagrid_files_original for source_date
    date_to_key = {}
    for key in hagrid_files_original.keys():
        try:
            date_part = key.split("_")[3]
            date_to_key[date_part] = key
        except IndexError:
            raise ValueError(f"Unexpected filename format: {key}")

    original_key = date_to_key.get(str(source_date))
    if original_key is None:
        raise KeyError(f"No original HAGRID data found for date {source_date}")

    # 3️⃣ → Get the original dataframe from hagrid_files_original
    original_df = hagrid_files_original[original_key].copy(deep=True)

    # 4️⃣ → Get current_df depending on whether source_date is inside simulation week
    if source_day_name is not None:
        # ✅ inside simulation week: work on the existing simulation data
        current_df = new_week[source_day_name]
    else:
        # 🟠 outside simulation week: work from a fresh copy of original data
        current_df = original_df.copy(deep=True)

    # 5️⃣ → Always get the target day dataframe from new_week
    target_df = new_week[target_day_name]

    # 6️⃣ → Build column names
    prefix, _, _ = carrier_columns[carrier]
    b2b_col = f"{prefix}_b2b"
    b2c_col = f"{prefix}_b2c"

    LOGGER.info(
        f"→ Transfer from {source_date} ({source_day_name or 'outside week'}) "
        f"to {current_date} ({target_day_name}) for {carrier}: "
        f"B2B={b2b_delta}, B2C={b2c_delta}"
    )

    # 4️⃣ → Hilfsfunktion: verteilt zu entfernende Pakete
    def subtract_packages(original_df, current_df, col, delta):
        eligible = original_df[original_df[col] > 0][col].copy()
        assigned = distribute_parcels(delta, eligible, strategy="random")
        for idx, amount in assigned.items():
            current_df.at[idx, col] -= amount
            assert current_df.at[idx, col] >= 0, f"{col} negative at idx {idx}"
        return assigned

    # 5️⃣ → Reduzieren am source day
    assigned_b2b = {}
    assigned_b2c = {}
    if b2b_delta > 0:
        LOGGER.debug(f"Subtracting {b2b_delta} B2B from {carrier} on {source_date}")
        assigned_b2b = subtract_packages(original_df, current_df, b2b_col, b2b_delta)

    if b2c_delta > 0:
        LOGGER.debug(f"Subtracting {b2c_delta} B2C from {carrier} on {source_date}")
        assigned_b2c = subtract_packages(original_df, current_df, b2c_col, b2c_delta)

    # 6️⃣ → Hinzufügen am target day (optimiert mit Mapping)
    new_rows_b2b = []
    new_rows_b2c = []
    new_coords_idx_b2b = {}  # geom_wkt → index in new_rows_b2b
    new_coords_idx_b2c = {}  # geom_wkt → index in new_rows_b2c

    for idx, amount in assigned_b2b.items():
        geom_wkt = original_df.at[idx, 'geometry'].wkt if pd.notna(original_df.at[idx, 'geometry']) else None
        if geom_wkt in lookup_coords[target_day_name]:
            target_idx = lookup_coords[target_day_name][geom_wkt]
            target_df.at[target_idx, b2b_col] += amount
            LOGGER.debug(f"→ B2B +{amount} added to EXISTING idx {target_idx} at {target_day_name}")
        elif geom_wkt in new_coords_idx_b2b:
            pending_idx = new_coords_idx_b2b[geom_wkt]
            new_rows_b2b[pending_idx][b2b_col] += amount
            LOGGER.debug(f"→ B2B +{amount} accumulated in pending new row at {target_day_name}")
        else:
            new_row = original_df.loc[idx].copy()
            for colname in new_row.index:
                if colname.endswith(('_b2b', '_b2c')):
                    new_row[colname] = 0
            new_row[b2b_col] = amount
            new_rows_b2b.append(new_row)
            new_coords_idx_b2b[geom_wkt] = len(new_rows_b2b) - 1
            LOGGER.debug(f"→ B2B +{amount} prepared as NEW row (pending) at {target_day_name}")

    for idx, amount in assigned_b2c.items():
        geom_wkt = original_df.at[idx, 'geometry'].wkt if pd.notna(original_df.at[idx, 'geometry']) else None
        if geom_wkt in lookup_coords[target_day_name]:
            target_idx = lookup_coords[target_day_name][geom_wkt]
            target_df.at[target_idx, b2c_col] += amount
            LOGGER.debug(f"→ B2C +{amount} added to EXISTING idx {target_idx} at {target_day_name}")
        elif geom_wkt in new_coords_idx_b2c:
            pending_idx = new_coords_idx_b2c[geom_wkt]
            new_rows_b2c[pending_idx][b2c_col] += amount
            LOGGER.debug(f"→ B2C +{amount} accumulated in pending new row at {target_day_name}")
        else:
            new_row = original_df.loc[idx].copy()
            for colname in new_row.index:
                if colname.endswith(('_b2b', '_b2c')):
                    new_row[colname] = 0
            new_row[b2c_col] = amount
            new_rows_b2c.append(new_row)
            new_coords_idx_b2c[geom_wkt] = len(new_rows_b2c) - 1
            LOGGER.debug(f"→ B2C +{amount} prepared as NEW row (pending) at {target_day_name}")

    # → Jetzt nur EIN concat pro Typ und Indexzuweisung
    if new_rows_b2b:
        start_idx = len(target_df)
        target_df = pd.concat([target_df, pd.DataFrame(new_rows_b2b)], ignore_index=True)
        for geom_wkt, pending_idx in new_coords_idx_b2b.items():
            lookup_coords[target_day_name][geom_wkt] = start_idx + pending_idx
            LOGGER.debug(f"→ B2B new row at idx {start_idx + pending_idx} finalized for {target_day_name}")

    if new_rows_b2c:
        start_idx = len(target_df)
        target_df = pd.concat([target_df, pd.DataFrame(new_rows_b2c)], ignore_index=True)
        for geom_wkt, pending_idx in new_coords_idx_b2c.items():
            lookup_coords[target_day_name][geom_wkt] = start_idx + pending_idx
            LOGGER.debug(f"→ B2C new row at idx {start_idx + pending_idx} finalized for {target_day_name}")

    # 7️⃣ → Write back only if source_day_name is inside simulation week
    if source_day_name is not None:
        new_week[source_day_name] = current_df

    # Always write target day
    new_week[target_day_name] = target_df

    # 8️⃣ → Validierung
    validate_transfer_sums(current_df, target_df, b2b_col, b2c_col, source_day_name, target_day_name, carrier)

def distribute_results_to_hagrid_files(total_changes, new_week, hagrid_files_original, start_date, day_to_date):
    """
    Distributes final_deltas (carrier + date + b2b/b2c delta) into new_week DataFrames.
    For each carrier + date:
        - selects corresponding source day from hagrid_files_original
        - randomly picks b2b/b2c sendings to move
        - updates new_week[date]: existing rows (increment) or adds new row
    """
    # 1️⃣ Initialize lookup_coords: for each day → geometry.wkt → row index
    LOGGER.info("Initializing lookup_coords for new_week...")
    lookup_coords = {
        day: {row.geometry.wkt: idx for idx, row in df.iterrows() if pd.notna(row.geometry)}
        for day, df in new_week.items()
    }
    LOGGER.info("Lookup_coords initialized. Loaded %d geometries.", sum(len(v) for v in lookup_coords.values()))

    # 2️⃣ Loop over each carrier + date in final_deltas
    for (carrier, date), row in total_changes.iterrows():
        b2b_delta = row['b2b_batch']
        b2c_delta = row['b2c_batch']
        source_date = date
        dest_date = row['dest_date']

        LOGGER.debug(f"➡️ Processing {carrier}: {b2b_delta} B2B, {b2c_delta} B2C from {source_date} → {dest_date}")
                        
        if dest_date in day_to_date.values():
            if source_date in day_to_date.values():
                LOGGER.debug(f"🔄 Source {source_date} and destination {dest_date} BOTH inside simulation → SUBTRACT at source, ADD at destination")
            else:
                LOGGER.debug(f"🟢 Source {source_date} OUTSIDE simulation")
            remove_at_source_date_and_add_at_current_date(carrier, hagrid_files_original, source_date, dest_date, b2b_delta, b2c_delta, new_week, day_to_date, lookup_coords)

        elif source_date in day_to_date.values() and dest_date not in day_to_date.values():
            LOGGER.debug(f"🟠 Source {source_date} inside simulation but destination {dest_date} OUTSIDE → ONLY subtracting at source")
            subtract_at_source_date(carrier, source_date, b2b_delta, b2c_delta, new_week, day_to_date)

        elif source_date not in day_to_date.values() and dest_date not in day_to_date.values():
            # Fehler: weder Quelle noch Ziel in Simulation
            LOGGER.error(f"Invalid delivery: source_date {date} and dest_date {dest_date} both outside simulation!")
            raise ValueError(f"Cannot process delivery from {date} to {dest_date}: both outside simulation.")

    plot_week_comparison(hagrid_files_original, new_week, day_to_date)
    LOGGER.info("✅ Finished distributing results into new_week")
    return new_week

def assign_batch_deliveries(sourceDaysDeliveries, current_day, carrier, prefix, probs, day_to_date ):
    LOGGER.info(f"Starting assigning processing for {carrier} on {current_day}")
    change_log = []

    for source_day, source_day_gdf in sourceDaysDeliveries.items():
        b2b_col = f"{prefix}_b2b"
        b2c_col = f"{prefix}_b2c"

        LOGGER.info(f"{carrier} @ {source_day} - B2B Sum: {source_day_gdf[b2b_col].sum()}, B2C Sum: {source_day_gdf[b2c_col].sum()}")

        # --- Schritt 1: Paketzahlen holen ---
        b2b_vals = source_day_gdf.get(b2b_col, pd.Series(0, index=source_day_gdf.index)).fillna(0).astype(int)
        b2c_vals = source_day_gdf.get(b2c_col, pd.Series(0, index=source_day_gdf.index)).fillna(0).astype(int)

        total_b2b = b2b_vals.sum()
        total_b2c = b2c_vals.sum()

        # --- Schritt 2: Globale Monte Carlo Simulation ---
        if total_b2b > 0:
            fast_flags_b2b = np.random.rand(total_b2b) < probs['b2b']['fast']
            fast_b2b_count = fast_flags_b2b.sum()
            batch_b2b_count = total_b2b - fast_b2b_count
        else:
            fast_b2b_count = batch_b2b_count = 0

        if total_b2c > 0:
            fast_flags_b2c = np.random.rand(total_b2c) < probs['b2c']['fast']
            fast_b2c_count = fast_flags_b2c.sum()
            batch_b2c_count = total_b2c - fast_b2c_count
        else:
            fast_b2c_count = batch_b2c_count = 0

        source_date_value = pd.to_datetime(source_day_gdf['date'].iloc[0]).date()
        dest_date_value = pd.to_datetime(day_to_date[current_day]).date()

        change_log.append({
            "carrier": carrier,
            "source_day": source_day,
            "source_date": source_date_value,
            "dest_day": current_day,
            "dest_date": dest_date_value,
            "b2b_batch": batch_b2b_count,
            "b2c_batch": batch_b2c_count,
            "b2b_fast": fast_b2b_count,
            "b2c_fast": fast_b2c_count
        })

    LOGGER.info(f"Finished assigning deliveries for {carrier} on {current_day}")
    return change_log

def combine_changes(day_changes, post_changes):
    if isinstance(post_changes, list):
        post_changes = pd.DataFrame(post_changes)  
    result_total = pd.concat([day_changes, post_changes], ignore_index=True)
    result_total = result_total.set_index(['carrier', 'source_date'])    
    return result_total

# ===============================
# Simulation Functions
# ===============================

def simulate_full_existing_week(hagrid_files_thisweek, days, start_date, scenario='moderate_cost', random_seed=42):
    np.random.seed(random_seed)

    date_to_day = {start_date + datetime.timedelta(days=i): day for i, day in enumerate(days)}
    day_to_date = {day: date for date, day in date_to_day.items()}

    LOGGER.info(f"{'=' * 80}")
    LOGGER.info(f"STARTING WEEKLY SIMULATION FOR SCENARIO: {scenario.upper()}")
    LOGGER.info(f"{'=' * 80}")

    hagrid_files_original, new_week = initialize_simulation_data(hagrid_files_thisweek, days, start_date)

    all_changes = []
    for current_day in days:
        LOGGER.info("="*60)
        LOGGER.info(f" *** START BATCH SIMULATION FOR: {current_day.upper()} *** ")
        LOGGER.info("="*60)
        day_changes = simulate_day_for_carriers(current_day, carrier_columns, carrier_days, hagrid_files_original, start_date, days, scenario_probs[scenario], day_to_date)
        all_changes.extend(day_changes)
        LOGGER.info("-"*60)
        LOGGER.info(f" *** FINISH SIMULATION: {current_day.upper()} *** ")
        LOGGER.info("-"*60)

    change_df = pd.DataFrame(all_changes)
    summary_per_date = aggregate_day_changes(change_df)

    post_changes = handle_future_delivery_outside_simulation(new_week, day_to_date, carrier_columns, carrier_days, scenario_probs[scenario])    
    original_df = collect_original_totals(hagrid_files_thisweek)

    final_summary = merge_post_simulation_changes(summary_per_date, post_changes, original_df)    
    # Validation
    carrier_date_deltas = build_carrier_date_deltas_from_change_df(change_df)
    final_deltas = build_final_carrier_date_deltas(carrier_date_deltas, post_changes, final_summary)

    total_changes = combine_changes(change_df, post_changes)
    plot_simulation_results(final_summary, original_df, start_date)
   
    distribute_results_to_hagrid_files(total_changes, new_week, hagrid_files_original, start_date, day_to_date)

    LOGGER.info(f"Finished simulation for scenario: {scenario}")

    return new_week



# CELL 8 execution=7
new_moderate_week = simulate_full_existing_week(deepcopy(hagrid_files), days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], start_date = datetime.date(2025, 5, 12), scenario='moderate_cost')

# CELL 9 execution=8
new_medium_week = simulate_full_existing_week(deepcopy(hagrid_files), days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], start_date = datetime.date(2025, 5, 12), scenario='medium_cost')

# CELL 10 execution=9
new_high_week = simulate_full_existing_week(deepcopy(hagrid_files), days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], start_date = datetime.date(2025, 5, 12), scenario='high_cost')

# CELL 11 execution=10
new_full_week = simulate_full_existing_week(deepcopy(hagrid_files), days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], start_date = datetime.date(2025, 5, 12), scenario='full_scenario')

# CELL 12 execution=11
def prepare_old_week(hagrid_files_thisweek, days, base_date):
    """
    Prepares the old (original) week from hagrid_files without simulation changes.
    Calculates wl_tag and total fields.
    """
    old_week = {}

    for i, day in enumerate(days):
        date_str = (base_date + datetime.timedelta(days=i)).strftime("%Y-%m-%d")
        key = f"hagrid_parcel_demand_{date_str}_({day})"
        if key not in hagrid_files_thisweek:
            raise KeyError(f"Missing file for {key}.")
        
        df = hagrid_files_thisweek[key].copy() 

        old_week[day] = df

    return old_week

def plot_wltag_comparison(old_week, new_week, old_label="Old Week", new_label="New Week"):
    """
    Plots the total wl_tag per day for old and new weeks for comparison.
    """
    days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday']
    
    old_totals = []
    new_totals = []

    for day in days:
        old_df = old_week.get(day)
        new_df = new_week.get(day)

        if old_df is not None:
            old_total = old_df['wl_tag'].sum()
        else:
            old_total = 0

        if new_df is not None:
            new_total = new_df['wl_tag'].sum()
        else:
            new_total = 0

        old_totals.append(old_total)
        new_totals.append(new_total)

    for carrier in carrier_columns:
        prefix, _, _ = carrier_columns[carrier]
        old_sum = old_week['Tuesday'][f"{prefix}_b2b"].sum() + old_week['Tuesday'][f"{prefix}_b2c"].sum()
        new_sum = new_week['Tuesday'][f"{prefix}_b2b"].sum() + new_week['Tuesday'][f"{prefix}_b2c"].sum()
        print(f"Tuesday {carrier}: old={old_sum}, new={new_sum}")

    # Plot
    plt.figure(figsize=(10, 6))
    plt.plot(days, old_totals, marker='o', label=old_label)
    plt.plot(days, new_totals, marker='o', label=new_label)

    plt.title("Comparison of wl_tag Totals per Day", fontsize=14)
    plt.xlabel("Day of Week", fontsize=12)
    plt.ylabel("Total wl_tag (Deliveries)", fontsize=12)
    plt.legend()
    plt.grid(True, linestyle="--", alpha=0.7)
    plt.xticks(rotation=45)
    plt.tight_layout()
    plt.show()

old_week = prepare_old_week(hagrid_files, days = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'], base_date = datetime.date(2025, 5, 12))

old_week


# CELL 13 execution=None
import numpy as np
import pandas as pd
import logging
from typing import Dict, Tuple

def _flow_cols(df: pd.DataFrame) -> list:
    return [c for c in df.columns if c.endswith("_b2b") or c.endswith("_b2c")]

def apply_type_tag_and_totals_no_rename(
    new_week: Dict[str, pd.DataFrame],
    carrier_columns: Dict[str, Tuple[str, str, str]],
    logger: logging.Logger | None = None,
    scenario_name: str = "unknown"
) -> Dict[str, pd.DataFrame]:
    """
    Do not change column names.
    For each provider:
      copy <prefix>_b2b -> type_col      (from carrier_columns)
      copy <prefix>_b2c -> tag_col       (from carrier_columns)
    Then set:
      wl_tag   = row sum over all tag and type columns
      total_sim= row sum over all *_b2b and *_b2c
      total    = total_sim
    Assertions per day:
      sum(all *_b2b) == sum(all type cols)
      sum(all *_b2c) == sum(all tag cols)
      sum(wl_tag) == sum(all flows) == sum(total_sim) == sum(total)
      no NaN and no negatives in flows, tag, type, totals
      integer values or floats that are integer valued
    Operates on copies and returns modified dict.
    """
    log = logger if logger is not None else logging.getLogger(__name__)

    for day, df in new_week.items():
        log.info("Start edit for scenario=%s | day=%s", scenario_name, day)
        if not isinstance(df, pd.DataFrame):
            raise TypeError(f"{scenario_name} {day}: input is not a DataFrame")

        df = df.copy()

        # 1) copy flows into existing tag/type columns as given in carrier_columns
        b2b_cols = []
        b2c_cols = []
        type_cols = []
        tag_cols = []

        for provider, (prefix, tag_col, type_col) in carrier_columns.items():
            src_b2b = f"{prefix}_b2b"
            src_b2c = f"{prefix}_b2c"

            if src_b2b in df.columns:
                df[type_col] = df[src_b2b].fillna(0).astype(int)
                b2b_cols.append(src_b2b)
                type_cols.append(type_col)
            else:
                log.info("[%s|%s] missing column %s for type copy", scenario_name, day, src_b2b)

            if src_b2c in df.columns:
                df[tag_col] = df[src_b2c].fillna(0).astype(int)
                b2c_cols.append(src_b2c)
                tag_cols.append(tag_col)
            else:
                log.info("[%s|%s] missing column %s for tag copy", scenario_name, day, src_b2c)

        # 2) immediate checks for copy correctness
        b2b_sum = int(df[b2b_cols].sum().sum()) if b2b_cols else 0
        type_sum = int(df[type_cols].sum().sum()) if type_cols else 0
        if b2b_sum != type_sum:
            raise AssertionError(f"{scenario_name} {day}: sum(b2b) {b2b_sum} != sum(type) {type_sum}")

        b2c_sum = int(df[b2c_cols].sum().sum()) if b2c_cols else 0
        tag_sum = int(df[tag_cols].sum().sum()) if tag_cols else 0
        if b2c_sum != tag_sum:
            raise AssertionError(f"{scenario_name} {day}: sum(b2c) {b2c_sum} != sum(tag) {tag_sum}")

        # 3) totals
        flow_cols = _flow_cols(df)
        df["total_sim"] = df[flow_cols].sum(axis=1) if flow_cols else 0
        df["total"] = df["total_sim"]
        all_type_tag = tag_cols + type_cols
        df["wl_tag"] = df[all_type_tag].sum(axis=1) if all_type_tag else 0

        # 4) grand total checks
        flows_total = int(df[flow_cols].sum().sum()) if flow_cols else 0
        wl_tag_total = int(df["wl_tag"].sum()) if "wl_tag" in df.columns else 0
        total_sim_total = int(df["total_sim"].sum()) if "total_sim" in df.columns else 0
        total_total = int(df["total"].sum()) if "total" in df.columns else 0

        if not (flows_total == wl_tag_total == total_sim_total == total_total):
            raise AssertionError(
                f"{scenario_name} {day}: mismatch totals flows={flows_total} wl_tag={wl_tag_total} total_sim={total_sim_total} total={total_total}"
            )

        # 5) quality checks
        cols_check = flow_cols + all_type_tag + ["total_sim", "total", "wl_tag"]
        if cols_check:
            if df[cols_check].isna().any().any():
                raise AssertionError(f"{scenario_name} {day}: NaN detected in {cols_check}")
            if (df[cols_check] < 0).any().any():
                raise AssertionError(f"{scenario_name} {day}: negative values detected in {cols_check}")
            # integerish check
            non_integer_cols = []
            for c in cols_check:
                if not np.issubdtype(df[c].dtype, np.integer):
                    if not np.isclose(df[c] % 1, 0).all():
                        non_integer_cols.append(c)
            if non_integer_cols:
                raise AssertionError(f"{scenario_name} {day}: non integer values in {non_integer_cols}")

        new_week[day] = df
        log.info("Finished edit for scenario=%s | day=%s totals=%d", scenario_name, day, total_total)

    return new_week

scenarios = {
    "full": new_full_week,
    "high": new_high_week,
    "medium": new_medium_week,
    "moderate": new_moderate_week,
}

for name, week in scenarios.items():
    scenarios[name] = apply_type_tag_and_totals_no_rename(
        new_week=week,
        carrier_columns=carrier_columns,
        logger=LOGGER,
        scenario_name=name
    )
    LOGGER.info("Edit completed for scenario %s", name)

# optional zurückschreiben
new_full_week = scenarios["full"]
new_high_week = scenarios["high"]
new_medium_week = scenarios["medium"]
new_moderate_week = scenarios["moderate"]



# CELL 14 execution=1
import os
from typing import Dict

import geopandas as gpd


def load_all_hagrid_shapefiles(output_dir="output", prefix="hagrid_parcel_demand"):
    """
    Load all HAGRID Shapefiles from the specified output directory.

    Parameters
    ----------
    output_dir : str, default="output"
        Directory where the HAGRID shapefiles are stored.
    prefix : str, default="hagrid_parcel_demand"
        Common filename prefix to identify relevant shapefiles.

    Returns
    -------
    shapefile_dict : dict
        Dictionary where keys are filenames without extension and values are GeoDataFrames.
    """
    if not os.path.exists(output_dir):
        raise FileNotFoundError(f"Directory does not exist: {output_dir}")

    shp_files = [
        file_name
        for file_name in os.listdir(output_dir)
        if file_name.endswith(".shp") and file_name.startswith(prefix)
    ]

    if not shp_files:
        raise FileNotFoundError(
            f"No shapefiles starting with '{prefix}' found in '{output_dir}'."
        )

    shapefile_dict = {}

    for shp_file in sorted(shp_files):
        path = os.path.join(output_dir, shp_file)
        gdf = gpd.read_file(path)
        key = os.path.splitext(shp_file)[0]
        shapefile_dict[key] = gdf

    return shapefile_dict


def load_all_weeks(base_output_dir="output"):
    """
    Load the normal HAGRID week and all batch delivery scenario weeks.

    Parameters
    ----------
    base_output_dir : str, default="output"
        Base output directory.

    Returns
    -------
    all_weeks : dict
        Nested dictionary with scenario names as first level and filenames as second level.
    """
    scenario_dirs = {
        "Normal Week": base_output_dir,
        "Moderate Cost": os.path.join(base_output_dir, "batchdelivery", "Moderate Cost"),
        "Medium Cost": os.path.join(base_output_dir, "batchdelivery", "Medium Cost"),
        "High Cost": os.path.join(base_output_dir, "batchdelivery", "High Cost"),
        "Full": os.path.join(base_output_dir, "batchdelivery", "Full"),
    }

    all_weeks = {}

    for scenario_name, scenario_dir in scenario_dirs.items():
        try:
            all_weeks[scenario_name] = load_all_hagrid_shapefiles(
                output_dir=scenario_dir,
                prefix="hagrid_parcel_demand",
            )
        except FileNotFoundError as error:
            print(f"Skipping '{scenario_name}': {error}")

    return all_weeks


carrier_columns = {
    "Amazon": ("ama", "amazon_tag", "amazon_typ"),
    "DHL": ("dhl", "dhl_tag", "dhl_type"),
    "DPD": ("dpd", "dpd_tag", "dpd_type"),
    "GLS": ("gls", "gls_tag", "gls_type"),
    "Hermes": ("her", "hermes_tag", "hermes_typ"),
    "UPS": ("ups", "ups_tag", "ups_type"),
    "FedEx/TNT": ("fxt", "fedex_tag", "fedex_type"),
}


def summarize_hagrid_week(all_weeks, carrier_columns):
    """
    Print B2B, B2C, tag, and type totals for all loaded HAGRID weeks.
    """
    for scenario_name, week_files in all_weeks.items():
        print("#" * 100)
        print(f"Scenario: {scenario_name}")
        print("#" * 100)

        for filename, gdf in week_files.items():
            b2b_cols = [col for col in gdf.columns if col.endswith("_b2b")]
            b2c_cols = [col for col in gdf.columns if col.endswith("_b2c")]

            total_b2b = gdf[b2b_cols].sum().sum() if b2b_cols else 0
            total_b2c = gdf[b2c_cols].sum().sum() if b2c_cols else 0
            total_packages = total_b2b + total_b2c

            tag_cols = [
                tag
                for _, (_, tag, _) in carrier_columns.items()
                if tag in gdf.columns
            ]

            type_cols = [
                type_col
                for _, (_, _, type_col) in carrier_columns.items()
                if type_col in gdf.columns
            ]

            total_tag = gdf[tag_cols].sum().sum() if tag_cols else 0
            total_type = gdf[type_cols].sum().sum() if type_cols else 0
            total_tag_type = total_tag + total_type

            print("=" * 80)
            print(
                f"{filename}: "
                f"B2B={total_b2b}, "
                f"B2C={total_b2c}, "
                f"Total={total_packages} | "
                f"tag={total_tag}, "
                f"type={total_type}, "
                f"tag+type={total_tag_type}"
            )

            for carrier, (prefix, _, _) in carrier_columns.items():
                b2b_col = f"{prefix}_b2b"
                b2c_col = f"{prefix}_b2c"

                b2b_sum = gdf[b2b_col].sum() if b2b_col in gdf.columns else 0
                b2c_sum = gdf[b2c_col].sum() if b2c_col in gdf.columns else 0

                print(f"  {carrier:<10} | B2B: {b2b_sum:>7} | B2C: {b2c_sum:>7}")

            print()


all_hagrid_weeks = load_all_weeks(base_output_dir="output")

summarize_hagrid_week(
    all_weeks=all_hagrid_weeks,
    carrier_columns=carrier_columns,
)

normal_week = all_hagrid_weeks.get("Normal Week")
moderate_week = all_hagrid_weeks.get("Moderate Cost")
medium_week = all_hagrid_weeks.get("Medium Cost")
high_week = all_hagrid_weeks.get("High Cost")
full_week = all_hagrid_weeks.get("Full")

# CELL 15 execution=2
import re

import matplotlib.pyplot as plt
import numpy as np
import pandas as pd


expected_date_by_weekday = {
    "Monday": "2025-05-12",
    "Tuesday": "2025-05-13",
    "Wednesday": "2025-05-14",
    "Thursday": "2025-05-15",
    "Friday": "2025-05-16",
    "Saturday": "2025-05-17",
}


def get_gdf_for_weekday(week_dict, weekday):
    """
    Return the GeoDataFrame for a weekday using the expected study week dates.

    This avoids ambiguous matches when several files with the same weekday exist.
    """
    if week_dict is None:
        return None

    if weekday in week_dict:
        return week_dict[weekday]

    expected_date = expected_date_by_weekday.get(weekday)

    if expected_date is not None:
        exact_matches = [
            key
            for key in week_dict.keys()
            if expected_date in key and f"({weekday})" in key
        ]

        if len(exact_matches) == 1:
            return week_dict[exact_matches[0]]

        if len(exact_matches) > 1:
            raise ValueError(
                f"Multiple exact files found for weekday '{weekday}' and "
                f"date '{expected_date}': {exact_matches}"
            )

    weekday_matches = [
        key
        for key in week_dict.keys()
        if f"({weekday})" in key
    ]

    if len(weekday_matches) == 1:
        return week_dict[weekday_matches[0]]

    if len(weekday_matches) > 1:
        raise ValueError(
            f"Multiple files found for weekday '{weekday}', but no unique file "
            f"matched the expected date '{expected_date}': {weekday_matches}"
        )

    return None


scenarios = [
    ("Baseline", normal_week),
    ("Moderate Cost", moderate_week),
    # ("Medium Cost", medium_week),
    ("High Cost", high_week),
    ("Full", full_week),
]

colors = {
    "Baseline": "black",
    "Moderate Cost": "blue",
    # "Medium Cost": "orange",
    "High Cost": "red",
    "Full": "grey",
}

weekdays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
day_map = {day: index for index, day in enumerate(weekdays)}

carriers = {
    "DHL": ("dhl_b2b", "dhl_b2c"),
    "Amazon": ("ama_b2b", "ama_b2c"),
    "DPD": ("dpd_b2b", "dpd_b2c"),
    "GLS": ("gls_b2b", "gls_b2c"),
    "Hermes": ("her_b2b", "her_b2c"),
    "UPS": ("ups_b2b", "ups_b2c"),
    "FedEx/TNT": ("fxt_b2b", "fxt_b2c"),
}

for carrier, (col_b2b, col_b2c) in carriers.items():
    records = []
    total_per_scenario = {}

    for scenario_name, week_dict in scenarios:
        week_total = 0

        for day in weekdays:
            gdf = get_gdf_for_weekday(week_dict, day)

            if gdf is None:
                print(f"Missing data for {scenario_name}, {day}.")
                continue

            if col_b2b not in gdf.columns or col_b2c not in gdf.columns:
                print(
                    f"Missing columns for {carrier} in {scenario_name}, {day}: "
                    f"{col_b2b}, {col_b2c}"
                )
                continue

            total = gdf[col_b2b].sum() + gdf[col_b2c].sum()

            records.append(
                {
                    "Scenario": scenario_name,
                    "Day": day,
                    "Total": total,
                }
            )

            week_total += total

        total_per_scenario[scenario_name] = week_total

    df = pd.DataFrame(records)

    if df.empty:
        print(f"Skipping {carrier}: no data.")
        continue

    df["Day_idx"] = df["Day"].map(day_map)

    fig, ax = plt.subplots(figsize=(12, 5))

    bar_width = 0.8 / len(scenarios)
    x = np.arange(len(weekdays))
    offsets = np.linspace(
        -0.4 + bar_width / 2,
        0.4 - bar_width / 2,
        len(scenarios),
    )

    for (scenario_name, _), offset in zip(scenarios, offsets):
        df_scenario = (
            df[df["Scenario"] == scenario_name]
            .sort_values("Day_idx")
            .copy()
        )

        if df_scenario.empty:
            continue

        x_positions = df_scenario["Day_idx"].to_numpy() + offset
        values = df_scenario["Total"].to_numpy()

        bars = ax.bar(
            x_positions,
            values,
            width=bar_width,
            label=f"{scenario_name} ({int(total_per_scenario[scenario_name]):,})",
            color=colors[scenario_name],
        )

        for bar in bars:
            height = bar.get_height()

            if height > 0:
                ax.annotate(
                    f"{int(height):,}",
                    xy=(bar.get_x() + bar.get_width() / 2, height),
                    xytext=(0, 3),
                    textcoords="offset points",
                    ha="center",
                    va="bottom",
                    fontsize=8,
                )

    ax.set_xticks(x)
    ax.set_xticklabels(weekdays)
    ax.set_ylabel(f"{carrier} parcel volume")
    ax.set_title(f"{carrier}: parcel volume per weekday and scenario")
    ax.legend(title="Scenario weekly total", loc="upper right")

    plt.tight_layout()
    plt.show()

# CELL 16 execution=48
import matplotlib.pyplot as plt
import seaborn as sns
import pandas as pd
import numpy as np
from matplotlib.ticker import FuncFormatter


def thousands_us(x, pos):
    """Format axis tick labels with thousands separators."""
    return f"{int(x):,}"


expected_date_by_weekday = {
    "Monday": "2025-05-12",
    "Tuesday": "2025-05-13",
    "Wednesday": "2025-05-14",
    "Thursday": "2025-05-15",
    "Friday": "2025-05-16",
    "Saturday": "2025-05-17",
}


def get_gdf_for_weekday(week_dict, weekday):
    """
    Return the GeoDataFrame for a weekday using the expected study week dates.

    This supports both key formats:
    1. "Monday"
    2. "hagrid_parcel_demand_2025-05-12_(Monday)"
    """
    if week_dict is None:
        return None

    if weekday in week_dict:
        return week_dict[weekday]

    expected_date = expected_date_by_weekday.get(weekday)

    if expected_date is not None:
        exact_matches = [
            key
            for key in week_dict.keys()
            if expected_date in key and f"({weekday})" in key
        ]

        if len(exact_matches) == 1:
            return week_dict[exact_matches[0]]

        if len(exact_matches) > 1:
            raise ValueError(
                f"Multiple exact files found for weekday '{weekday}' and "
                f"date '{expected_date}': {exact_matches}"
            )

    weekday_matches = [
        key
        for key in week_dict.keys()
        if f"({weekday})" in key
    ]

    if len(weekday_matches) == 1:
        return week_dict[weekday_matches[0]]

    if len(weekday_matches) > 1:
        raise ValueError(
            f"Multiple files found for weekday '{weekday}', but no unique file "
            f"matched the expected date '{expected_date}': {weekday_matches}"
        )

    return None


plt.style.use("default")
sns.set_style("whitegrid")

selected_lsps = [
    "DHL",
    "Amazon",
    "DPD",
    "GLS",
    "Hermes",
    "UPS",
    "FedEx/TNT",
]

carrier_columns = {
    "Amazon": ("ama", "amazon_tag", "amazon_typ"),
    "DHL": ("dhl", "dhl_tag", "dhl_type"),
    "DPD": ("dpd", "dpd_tag", "dpd_type"),
    "GLS": ("gls", "gls_tag", "gls_type"),
    "Hermes": ("her", "hermes_tag", "hermes_typ"),
    "UPS": ("ups", "ups_tag", "ups_type"),
    "FedEx/TNT": ("fxt", "fedex_tag", "fedex_type"),
}

scenarios = [
    ("Baseline", normal_week),
    ("Moderate Consolidation", moderate_week),
    ("High Consolidation", high_week),
    ("Full Consolidation", full_week),
]

scenario_order = [
    "Baseline",
    "Moderate Consolidation",
    "High Consolidation",
    "Full Consolidation",
]

color_map = {
    "Baseline": "#e41a1c",
    "Moderate Consolidation": "#377eb8",
    "High Consolidation": "#4daf4a",
    "Full Consolidation": "#984ea3",
}

day_order = [
    "Monday",
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
]

day_labels_short = {
    "Monday": "Mon",
    "Tuesday": "Tue",
    "Wednesday": "Wed",
    "Thursday": "Thu",
    "Friday": "Fri",
    "Saturday": "Sat",
}

records = []

for scenario_name, week_dict in scenarios:
    for carrier in selected_lsps:
        prefix = carrier_columns[carrier][0]

        for day in day_order:
            gdf = get_gdf_for_weekday(week_dict, day)

            if gdf is None:
                print(f"Missing data for {scenario_name}, {day}.")
                continue

            b2b_col = f"{prefix}_b2b"
            b2c_col = f"{prefix}_b2c"

            if b2b_col not in gdf.columns or b2c_col not in gdf.columns:
                print(
                    f"Missing columns for {carrier} in {scenario_name}, {day}: "
                    f"{b2b_col}, {b2c_col}"
                )
                continue

            total = gdf[b2b_col].sum() + gdf[b2c_col].sum()

            records.append(
                {
                    "Carrier": carrier,
                    "Scenario": scenario_name,
                    "Day": day,
                    "Total": total,
                }
            )

df = pd.DataFrame(records)

if df.empty:
    raise ValueError("No records were created. Check scenario dictionaries and column names.")

df["Day"] = pd.Categorical(df["Day"], categories=day_order, ordered=True)
df["Scenario"] = pd.Categorical(df["Scenario"], categories=scenario_order, ordered=True)
df["Carrier"] = pd.Categorical(df["Carrier"], categories=selected_lsps, ordered=True)
df = df.sort_values(["Carrier", "Day", "Scenario"])

n_carriers = len(selected_lsps)
cols = 2
rows = int(np.ceil((n_carriers + 1) / cols))

bar_width = 0.18
x = np.arange(len(day_order))
offsets = np.linspace(
    -1.5 * bar_width,
    1.5 * bar_width,
    num=len(scenario_order),
)

fig, axes = plt.subplots(
    rows,
    cols,
    figsize=(7.16, 8.5),
    sharey=True,
)

axes = axes.flatten()

for i, carrier in enumerate(selected_lsps):
    ax = axes[i]
    df_carrier = df[df["Carrier"] == carrier]

    for j, scenario_name in enumerate(scenario_order):
        df_scenario = (
            df_carrier[df_carrier["Scenario"] == scenario_name]
            .sort_values("Day")
            .copy()
        )

        values_by_day = (
            df_scenario
            .set_index("Day")["Total"]
            .reindex(day_order)
            .fillna(0)
            .to_numpy()
        )

        ax.bar(
            x + offsets[j],
            values_by_day,
            width=bar_width,
            label=scenario_name if i == 0 else "",
            color=color_map[scenario_name],
            alpha=0.85,
        )

    carrier_label = "Amazon Logistics" if carrier == "Amazon" else carrier

    ax.set_title(
        carrier_label,
        fontsize=11,
        fontweight="bold",
        pad=4,
    )

    ax.set_xticks(x)
    ax.set_xticklabels(
        [day_labels_short[d] for d in day_order],
        rotation=0,
        fontsize=9,
    )
    ax.grid(axis="y", linestyle="--", linewidth=0.5, alpha=0.5)
    ax.yaxis.set_major_formatter(FuncFormatter(thousands_us))

    if i % cols == 0:
        ax.set_ylabel("Parcel volume", fontsize=10)

legend_ax_index = n_carriers
legend_ax = axes[legend_ax_index]
legend_ax.axis("off")

handles, labels = axes[0].get_legend_handles_labels()

legend_ax.legend(
    handles,
    labels,
    title="Scenario",
    loc="center",
    fontsize=9,
    title_fontsize=10,
    frameon=True,
)

for axis_index in range(n_carriers + 1, len(axes)):
    fig.delaxes(axes[axis_index])

plt.tight_layout()

plt.savefig(
    "output/parcel_volume_comparison_all_carriers.pdf",
    dpi=300,
    bbox_inches="tight",
)

plt.show()


# CELL 17 execution=5
import geopandas as gpd
import matplotlib.pyplot as plt
import numpy as np
from shapely.geometry import box
from matplotlib.gridspec import GridSpec, GridSpecFromSubplotSpec
from matplotlib.colors import LinearSegmentedColormap, Normalize
from matplotlib.cm import get_cmap
import matplotlib.cm as cm

# --- Settings ---
days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]

scenario_defs = {
    "Moderate Cost": new_moderate_week,
    "Medium Cost": new_medium_week,
    "High Cost": new_high_week
}
scenario_order = ["Baseline", "Moderate Cost", "Medium Cost", "High Cost"]

cell_size = 1500
TARGET_CRS = "EPSG:25832"

# --- Custom colormaps ---

# Plasma base color for zero difference
plasma_base = cm.get_cmap("plasma")(0)  # dark blue tone

# 1) Custom diverging colormap for deltas
colors_delta = [
    (0.80, 0.00, 0.00, 1.0),   # red = fewer parcels, opaque
    (plasma_base[0], plasma_base[1], plasma_base[2], 0.6),  # semi-transparent near zero
    (0.00, 0.60, 0.00, 1.0)    # green = more parcels, opaque
]
cmap_delta = LinearSegmentedColormap.from_list(
    "red_plasma_blue_green_alpha",
    colors_delta,
    N=256
)

# 2) Custom plasma-like colormap with alpha fade for small baseline volumes
plasma_full = cm.get_cmap("plasma", 256)
plasma_rgba = plasma_full(np.linspace(0, 1, 256))
# make lower ~20% more transparent
alpha_curve = np.linspace(0.6, 1.0, 256)
plasma_rgba[:, 3] = alpha_curve
cmap_abs = LinearSegmentedColormap.from_list("plasma_alpha", plasma_rgba)

# --- Functions ---

def to_epsg_25832(gdf):
    if gdf is None or gdf.empty:
        return gdf
    if gdf.crs is None:
        gdf = gdf.set_crs(TARGET_CRS, allow_override=True)
    elif gdf.crs.to_string() != TARGET_CRS:
        gdf = gdf.to_crs(TARGET_CRS)
    return gdf

def sum_all_carriers(gdf):
    flow_cols = [c for c in gdf.columns if c.endswith("_b2b") or c.endswith("_b2c")]
    g = gdf.copy()
    g["Total"] = g[flow_cols].sum(axis=1)
    return g.loc[g["Total"] > 0].copy()

def make_grid_from_baseline(gdf_base_day, cell_size):
    xmin, ymin, xmax, ymax = gdf_base_day.total_bounds
    xs = np.arange(xmin, xmax + cell_size, cell_size)
    ys = np.arange(ymin, ymax + cell_size, cell_size)
    cells = [box(x, y, x + cell_size, y + cell_size) for x in xs for y in ys]
    return gpd.GeoDataFrame({"grid_id": np.arange(len(cells))}, geometry=cells, crs=gdf_base_day.crs)

def build_day_grid_with_deltas(day):
    gdf_base_day = to_epsg_25832(old_week.get(day))
    if gdf_base_day is None or gdf_base_day.empty:
        return None
    gdf_base_day = sum_all_carriers(gdf_base_day)
    grid = make_grid_from_baseline(gdf_base_day, cell_size)

    # Baseline totals per cell
    j_base = gpd.sjoin(gdf_base_day, grid, how="inner", predicate="intersects")
    base_counts = j_base.groupby("grid_id")["Total"].sum().reset_index()
    grid = grid.merge(base_counts, on="grid_id", how="left").rename(columns={"Total": "Baseline"})
    grid["Baseline"] = grid["Baseline"].fillna(0.0)

    # Scenario totals and deltas
    for scen_name, weekdict in scenario_defs.items():
        gdf_scen_day = to_epsg_25832(weekdict.get(day))
        if gdf_scen_day is None or gdf_scen_day.empty:
            grid[f"Total_{scen_name}"] = 0.0
            grid[f"Delta_{scen_name}"] = 0.0
            continue
        gdf_scen_day = sum_all_carriers(gdf_scen_day)
        joined = gpd.sjoin(gdf_scen_day, grid, how="inner", predicate="intersects")
        counts = joined.groupby("grid_id")["Total"].sum().reset_index().rename(columns={"Total": f"Total_{scen_name}"})
        grid = grid.merge(counts, on="grid_id", how="left")
        grid[f"Total_{scen_name}"] = grid[f"Total_{scen_name}"].fillna(0.0)
        grid[f"Delta_{scen_name}"] = grid[f"Total_{scen_name}"] - grid["Baseline"]
    return grid

# --- Data pass ---
day_grids = {}
all_deltas_global, all_abs_global = [], []

for day in days:
    grid = build_day_grid_with_deltas(day)
    if grid is None:
        continue
    day_grids[day] = grid

    if (grid["Baseline"] > 0).any():
        all_abs_global.append(grid.loc[grid["Baseline"] > 0, "Baseline"].values)

    for scen_name in scenario_defs.keys():
        mask = (grid["Baseline"] > 0.0) | (grid[f"Total_{scen_name}"] > 0.0)
        if mask.any():
            all_deltas_global.append(grid.loc[mask, f"Delta_{scen_name}"].values)

vmax_abs = float(np.nanmax(np.concatenate(all_abs_global))) if all_abs_global else 1.0
norm_abs = Normalize(vmin=0.0, vmax=vmax_abs)
max_abs_delta = float(np.nanmax(np.abs(np.concatenate(all_deltas_global)))) if all_deltas_global else 1.0
norm_delta = Normalize(vmin=-max_abs_delta, vmax=max_abs_delta)

# --- Plotting ---
fig = plt.figure(figsize=(8.5, 12.5))
outer = GridSpec(3, 2, figure=fig, wspace=0.1, hspace=0.1)

for d_idx, day in enumerate(days):
    if day not in day_grids:
        continue
    grid = day_grids[day]
    inner = GridSpecFromSubplotSpec(2, 2, subplot_spec=outer[d_idx], wspace=0.02, hspace=0.02)
    panels = ["Baseline", "Moderate Cost", "Medium Cost", "High Cost"]

    for s_idx, label in enumerate(panels):
        ax = fig.add_subplot(inner[s_idx // 2, s_idx % 2])

        if label == "Baseline":
            mask = grid["Baseline"] > 0.0
            if mask.any():
                grid.loc[mask].plot(
                    column="Baseline", cmap=cmap_abs, norm=norm_abs,
                    linewidth=0.12, edgecolor="grey", legend=False, ax=ax
                )
        else:
            mask = (grid["Baseline"] > 0.0) | (grid[f"Total_{label}"] > 0.0)
            if mask.any():
                grid.loc[mask].plot(
                    column=f"Delta_{label}", cmap=cmap_delta, norm=norm_delta,
                    linewidth=0.12, edgecolor="grey", legend=False, ax=ax
                )

        ax.axis("off")
        ax.set_title(label, fontsize=9.5, pad=1.8)

    # day label
    cell = outer[d_idx]
    bb = cell.get_position(fig)
    x_center = (bb.x0 + bb.x1) / 2.0
    fig.text(x_center, bb.y1 + 0.008, day, ha="center", va="bottom",
             fontsize=12, fontweight="bold")

plt.show()


# CELL 18 execution=6
for scen_name, weekdict in scenarios:
    print(f"{scen_name} - Total DHL B2B + B2C on Monday: {weekdict['Monday']['dhl_b2b'].sum() + weekdict['Monday']['dhl_b2c'].sum()}")

# CELL 19 execution=7
import numpy as np
import matplotlib.pyplot as plt
from sklearn.neighbors import BallTree

# -------- Fast K without edge correction (single r_max query, batched) --------
def K_naive_hist_batch(points, radii, area, leaf_size=100, batch_size=2048):
    points = np.asarray(points, dtype=np.float64)
    radii = np.asarray(radii, dtype=np.float64)
    n = len(points)
    if n < 2 or area <= 0:
        return np.full_like(radii, np.nan, dtype=float)

    r_max = float(radii.max())
    edges = np.concatenate(([0.0], radii))
    bin_sums = np.zeros(radii.shape[0], dtype=np.float64)

    tree = BallTree(points, metric="euclidean", leaf_size=leaf_size)

    for start in range(0, n, batch_size):
        stop = min(start + batch_size, n)
        centers = points[start:stop]
        idx_list, dist_list = tree.query_radius(
            centers, r=r_max, return_distance=True, sort_results=True
        )
        for idxs, dists in zip(idx_list, dist_list):
            if len(idxs) <= 1:
                continue
            d = dists[1:]  # drop self
            h, _ = np.histogram(d, bins=edges)
            bin_sums += h

    denom = n * (n - 1)
    K = (area / denom) * np.cumsum(bin_sums)
    return K

def weighted_quantile(x, q, w):
    x = np.asarray(x, dtype=float)
    w = np.asarray(w, dtype=float)
    q = np.asarray(q, dtype=float)
    order = np.argsort(x)
    x = x[order]; w = w[order]
    cw = np.cumsum(w); total = cw[-1]
    cdf = (cw - 0.5 * w) / total
    return np.interp(q, cdf, x)

def weighted_iqr_band(L_stack, w_arr):
    lo = np.empty(L_stack.shape[1], dtype=float)
    hi = np.empty(L_stack.shape[1], dtype=float)
    for j in range(L_stack.shape[1]):
        lo[j], hi[j] = weighted_quantile(L_stack[:, j], [0.25, 0.75], w_arr)
    return lo, hi

# -------- Carriers --------
carriers = {
    "DHL": ("dhl_b2b", "dhl_b2c"),
    "Amazon": ("ama_b2b", "ama_b2c"),
    "DPD": ("dpd_b2b", "dpd_b2c"),
    "GLS": ("gls_b2b", "gls_b2c"),
    "Hermes": ("her_b2b", "her_b2c"),
    "UPS": ("ups_b2b", "ups_b2c"),
    "FedEx_TNT": ("fed_b2b", "fed_b2c"),
}

# -------- Scenarios and weekdays --------
scenarios = [
    ("Baseline", old_week),
    ("Moderate Cost", new_moderate_week),
    ("Medium Cost", new_medium_week),
    ("High Cost", new_high_week),
]
weekdays = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]




# CELL 21 execution=8
# =============================================================================
# Build df_daily_delta: clustering metrics per (Scenario, Carrier, Day) and
# their delta vs. baseline (normal_week).
# =============================================================================
import numpy as np
import pandas as pd
from sklearn.neighbors import BallTree

CCI_DAY_ORDER       = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"]
CCI_SCENARIO_ORDER  = ["Moderate Consolidation", "High Consolidation", "Full Consolidation"]
CCI_CARRIER_ORDER   = ["DHL", "Amazon", "DPD", "GLS", "Hermes", "UPS", "FedEx/TNT"]

# NOTE: FedEx columns in the HAGRID GeoDataFrames use the prefix `fxt_`, not `fed_`.
CCI_CARRIER_COLS = {
    "DHL":       ("dhl_b2b", "dhl_b2c"),
    "Amazon":    ("ama_b2b", "ama_b2c"),
    "DPD":       ("dpd_b2b", "dpd_b2c"),
    "GLS":       ("gls_b2b", "gls_b2c"),
    "Hermes":    ("her_b2b", "her_b2c"),
    "UPS":       ("ups_b2b", "ups_b2c"),
    "FedEx/TNT": ("fxt_b2b", "fxt_b2c"),
}

CCI_SCENARIO_WEEKS = {
    "Moderate Consolidation": moderate_week,
    "High Consolidation":     high_week,
    "Full Consolidation":     full_week,
}

NEIGHBOR_RADIUS_M = 1000.0
CLUSTERABLE_MIN   = 50.0


# Verify carrier columns exist in the data and warn if any are missing.
_probe_gdf = get_gdf_for_weekday(normal_week, CCI_DAY_ORDER[0])
if _probe_gdf is None:
    raise RuntimeError("Could not probe baseline GeoDataFrame for column verification.")

_available_cols = set(_probe_gdf.columns)
_missing_map = {
    carrier: tuple(c for c in cols if c not in _available_cols)
    for carrier, cols in CCI_CARRIER_COLS.items()
}
_missing_map = {k: v for k, v in _missing_map.items() if v}
if _missing_map:
    print("WARNING — carriers with missing columns (will be skipped):")
    for k, v in _missing_map.items():
        print(f"  {k}: missing {v}")
    print(f"Hint: available carrier-flow columns are: "
          f"{sorted(c for c in _available_cols if c.endswith(('_b2b','_b2c')))}")

CCI_CARRIER_COLS_OK = {
    k: v for k, v in CCI_CARRIER_COLS.items()
    if all(c in _available_cols for c in v)
}
CCI_CARRIER_ORDER = [c for c in CCI_CARRIER_ORDER if c in CCI_CARRIER_COLS_OK]


def _gini(values):
    v = np.asarray(values, dtype=float)
    v = v[np.isfinite(v) & (v > 0)]
    if v.size < 2:
        return 0.0
    v = np.sort(v)
    n = v.size
    cum = np.cumsum(v)
    return float((2.0 * np.sum((np.arange(1, n + 1)) * v) - (n + 1) * cum[-1]) / (n * cum[-1]))


def _entropy_concentration(values):
    """1 - normalised Shannon entropy.  0 = uniform, 1 = fully concentrated."""
    v = np.asarray(values, dtype=float)
    v = v[np.isfinite(v) & (v > 0)]
    if v.size < 2:
        return 1.0
    p = v / v.sum()
    H = -np.sum(p * np.log(p))
    Hmax = np.log(v.size)
    return float(1.0 - H / Hmax) if Hmax > 0 else 0.0


def _compute_metrics_for_carrier_day(gdf, col_b2b, col_b2c):
    if gdf is None or gdf.empty:
        return None
    if col_b2b not in gdf.columns or col_b2c not in gdf.columns:
        return None

    weight = (gdf[col_b2b].fillna(0).to_numpy(dtype=float)
              + gdf[col_b2c].fillna(0).to_numpy(dtype=float))
    mask = weight > 0
    if mask.sum() < 5 or weight.sum() <= 0:
        return None

    pts = np.column_stack([
        gdf.geometry.x.to_numpy(dtype=float),
        gdf.geometry.y.to_numpy(dtype=float),
    ])[mask]
    w = weight[mask]

    tree = BallTree(pts, metric="euclidean", leaf_size=64)
    neigh_idx = tree.query_radius(pts, r=NEIGHBOR_RADIUS_M)
    local_density = np.array([w[idx].sum() for idx in neigh_idx], dtype=float)

    total_w = float(w.sum())
    mean_local_density    = float(np.sum(w * local_density) / total_w)
    clusterable_share     = float(np.sum(w[local_density >= CLUSTERABLE_MIN]) / total_w) * 100.0
    active_cells_per_1000 = float(w.size * 1000.0 / total_w)
    gini_conc             = _gini(w) * 100.0
    entropy_conc          = _entropy_concentration(w) * 100.0
    top10_share           = float(
        np.sort(w)[-max(1, int(np.ceil(0.10 * w.size))):].sum() / total_w
    ) * 100.0

    return {
        "local_density_1000m":            mean_local_density,
        "clusterable_share_1000m_min50":  clusterable_share,
        "active_cells_per_1000_parcels":  active_cells_per_1000,
        "gini_concentration":             gini_conc,
        "entropy_concentration":          entropy_conc,
        "top10_share":                    top10_share,
        "n_cells":                        int(w.size),
        "total_volume":                   total_w,
    }


METRIC_NAMES = [
    "local_density_1000m",
    "clusterable_share_1000m_min50",
    "active_cells_per_1000_parcels",
    "gini_concentration",
    "entropy_concentration",
    "top10_share",
]
PERCENTAGE_POINT_METRICS = {
    "clusterable_share_1000m_min50",
    "gini_concentration",
    "entropy_concentration",
    "top10_share",
}

print(f"Carriers used: {CCI_CARRIER_ORDER}")
print("Computing baseline metrics ...")
baseline_records = {}
for day in CCI_DAY_ORDER:
    gdf = get_gdf_for_weekday(normal_week, day)
    for carrier, (cb, cc) in CCI_CARRIER_COLS_OK.items():
        m = _compute_metrics_for_carrier_day(gdf, cb, cc)
        if m is not None:
            baseline_records[(carrier, day)] = m

print("Computing scenario metrics ...")
rows = []
for scenario, week_dict in CCI_SCENARIO_WEEKS.items():
    for day in CCI_DAY_ORDER:
        gdf = get_gdf_for_weekday(week_dict, day)
        for carrier, (cb, cc) in CCI_CARRIER_COLS_OK.items():
            m = _compute_metrics_for_carrier_day(gdf, cb, cc)
            base = baseline_records.get((carrier, day))
            if m is None or base is None:
                continue
            row = {"Scenario": scenario, "Carrier": carrier, "Day": day}
            for metric in METRIC_NAMES:
                b = base[metric]
                s = m[metric]
                row[f"Baseline {metric}"] = b
                row[f"Scenario {metric}"] = s
                if metric in PERCENTAGE_POINT_METRICS:
                    row[f"Delta percentage points {metric}"] = s - b
                else:
                    row[f"Delta percent {metric}"] = ((s - b) / b * 100.0) if b > 0 else np.nan
            rows.append(row)

df_daily_delta = pd.DataFrame(rows)
print(f"\ndf_daily_delta: {df_daily_delta.shape[0]} rows, "
      f"{df_daily_delta['Scenario'].nunique()} scenarios × "
      f"{df_daily_delta['Carrier'].nunique()} carriers × "
      f"{df_daily_delta['Day'].nunique()} days")
print("\nAvailable delta columns:")
for c in df_daily_delta.columns:
    if c.startswith("Delta"):
        print(f"  {c}")

df_daily_delta.head()


# CELL 22 execution=9
# =============================================================================
# Composite Consolidation Clustering Index (CCI)
# -----------------------------------------------------------------------------
#   CCI(s, c, d) = (1 / sqrt(K)) * sum_k  sign_k * z_k(s, c, d)
#
# where z_k is a robust median/MAD standardisation of delta metric k across
# the full (Scenario, Carrier, Day) panel and sign_k orients each metric to
# "+1 = more clustered than baseline".
# =============================================================================
import numpy as np
import pandas as pd

CCI_METRIC_SPECS = [
    ("Delta percent local_density_1000m",                    +1.0),
    ("Delta percentage points clusterable_share_1000m_min50", +1.0),
    ("Delta percentage points gini_concentration",            +1.0),
    ("Delta percentage points entropy_concentration",         +1.0),
    ("Delta percentage points top10_share",                   +1.0),
    ("Delta percent active_cells_per_1000_parcels",           -1.0),
]

available_metrics = [(c, s) for c, s in CCI_METRIC_SPECS if c in df_daily_delta.columns]
print("CCI is built from the following metrics:")
for col, sign in available_metrics:
    print(f"  sign={sign:+.0f}  {col}")


def _robust_z(series):
    arr = pd.to_numeric(series, errors="coerce").to_numpy(dtype=float)
    med = np.nanmedian(arr)
    mad = np.nanmedian(np.abs(arr - med))
    scale = 1.4826 * mad if mad > 0 else np.nanstd(arr)
    if not np.isfinite(scale) or scale == 0:
        scale = 1.0
    return (arr - med) / scale


df_cci = df_daily_delta.copy()
z_stack = np.vstack([sign * _robust_z(df_cci[col]) for col, sign in available_metrics])
df_cci["CCI"] = np.nansum(z_stack, axis=0) / np.sqrt(len(available_metrics))

cci_cell = (
    df_cci.pivot_table(index=["Scenario", "Carrier"], columns="Day", values="CCI")
          .reindex(index=pd.MultiIndex.from_product(
              [CCI_SCENARIO_ORDER, CCI_CARRIER_ORDER], names=["Scenario", "Carrier"]),
                   columns=CCI_DAY_ORDER)
)
cci_week     = cci_cell.mean(axis=1).unstack("Scenario").reindex(
                   index=CCI_CARRIER_ORDER, columns=CCI_SCENARIO_ORDER)
cci_scenario = df_cci.groupby("Scenario")["CCI"].agg(["mean", "std", "count"]).reindex(CCI_SCENARIO_ORDER)

print("\nScenario signatures (mean CCI ± std, n cells):")
print(cci_scenario.round(3))
print("\nWeekly mean CCI per carrier × scenario:")
print(cci_week.round(2))


# CELL 23 execution=12
# =============================================================================
# Shared 3D setup — used by the three fancy variants below.
# =============================================================================
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.colors import TwoSlopeNorm, LinearSegmentedColormap, to_rgba
from matplotlib.cm import ScalarMappable
from mpl_toolkits.mplot3d import Axes3D  # noqa: F401
from mpl_toolkits.mplot3d.art3d import Poly3DCollection, Line3DCollection

D3_DAY_ORDER      = ["Monday","Tuesday","Wednesday","Thursday","Friday","Saturday"]
D3_DAY_LABELS     = {"Monday":"Mon","Tuesday":"Tue","Wednesday":"Wed",
                     "Thursday":"Thu","Friday":"Fri","Saturday":"Sat"}
D3_SCENARIO_ORDER = ["Moderate Consolidation","High Consolidation","Full Consolidation"]
D3_SCENARIO_SHORT = {"Moderate Consolidation":"Moderate",
                     "High Consolidation":"High",
                     "Full Consolidation":"Full"}
D3_CARRIER_ORDER  = [c for c in
                     ["DHL","Amazon","DPD","GLS","Hermes","UPS","FedEx/TNT"]
                     if c in df_daily_delta["Carrier"].unique()]

D3_SCENARIO_COLORS = {
    "Moderate Consolidation": "#3a7bd5",
    "High Consolidation":     "#16a085",
    "Full Consolidation":     "#c0392b",
}
D3_CARRIER_COLORS = {
    "DHL":"#d6a200","Amazon":"#1f4e79","DPD":"#c8102e","GLS":"#2b6cb0",
    "Hermes":"#0a6f3c","UPS":"#5a3a1c","FedEx/TNT":"#6b2c91",
}

D3_METRIC       = "Delta percent local_density_1000m"
D3_METRIC_LABEL = "Δ local demand density within 1000 m [%]"

plt.rcParams.update({
    "figure.dpi": 150, "savefig.dpi": 300,
    "font.size": 9, "axes.titlesize": 12, "axes.labelsize": 10,
    "xtick.labelsize": 8, "ytick.labelsize": 8,
})

d3_div = LinearSegmentedColormap.from_list(
    "d3_div", ["#2166ac", "#67a9cf", "#f7f7f7", "#ef8a62", "#b2182b"], N=256,
)

# pivot helper: scenario -> DataFrame(index=carrier, columns=day, values=metric)
_d3_pivots = {
    scenario: (
        df_daily_delta[df_daily_delta["Scenario"] == scenario]
        .pivot_table(index="Carrier", columns="Day", values=D3_METRIC)
        .reindex(index=D3_CARRIER_ORDER, columns=D3_DAY_ORDER)
    )
    for scenario in D3_SCENARIO_ORDER
}
_d3_all_vals = np.concatenate([p.to_numpy(dtype=float).ravel() for p in _d3_pivots.values()])
_d3_all_vals = _d3_all_vals[np.isfinite(_d3_all_vals)]
_d3_absmax   = float(np.nanquantile(np.abs(_d3_all_vals), 0.98)) or 1.0
d3_norm      = TwoSlopeNorm(vmin=-_d3_absmax, vcenter=0.0, vmax=_d3_absmax)

print(f"Using metric: {D3_METRIC}")
print(f"Value range used for colour: ±{_d3_absmax:.1f}%")
print(f"Carriers in plots: {D3_CARRIER_ORDER}")


# CELL 24 execution=16
# =============================================================================
# Shared 2D plotting context — reuses D3_* constants from the 3D cell above.
# Adds robust value clipping so heavy-tail carriers don't dominate the scale.
# =============================================================================
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch
from matplotlib.colors import TwoSlopeNorm, LinearSegmentedColormap
from matplotlib.cm import ScalarMappable
from matplotlib import colormaps


# pivots: scenario -> DataFrame(carrier × day, values = Δ metric in percent)
_p2 = {s: _d3_pivots[s].copy() for s in D3_SCENARIO_ORDER}
_all_vals = np.concatenate([m.to_numpy(dtype=float).ravel() for m in _p2.values()])
_all_vals = _all_vals[np.isfinite(_all_vals)]

# robust symmetric scale — 80 % quantile to suppress FedEx/UPS outliers.
# Clipped values are explicitly annotated in every panel so no info is lost.
P2_VMAX = float(np.nanquantile(np.abs(_all_vals), 0.80)) or 1.0
P2_VMAX = round(P2_VMAX, 1)
P2_NORM = TwoSlopeNorm(vmin=-P2_VMAX, vcenter=0.0, vmax=P2_VMAX)
P2_CMAP = LinearSegmentedColormap.from_list(
    "p2_div", ["#2166ac", "#67a9cf", "#f7f7f7", "#ef8a62", "#b2182b"], N=256,
)
P2_CMAP = colormaps["vanimo"]

plt.rcParams.update({
    "figure.dpi": 150, "savefig.dpi": 300,
    "font.size": 9, "axes.titlesize": 11, "axes.labelsize": 9,
    "xtick.labelsize": 8, "ytick.labelsize": 8,
    "axes.spines.top": False, "axes.spines.right": False,
})

print(f"Symmetric y-axis clipped at ±{P2_VMAX:.1f}% "
      f"(80 % quantile of |Δ|; absolute max was "
      f"{np.nanmax(np.abs(_all_vals)):.1f}%)")


# CELL 26 execution=40
# =============================================================================
# Map-L — Per-carrier Δ-density (PLZ aggregation, absolute parcels, linear)
#   Elsevier double-column, seaborn-whitegrid typography, PDF export
# =============================================================================
import os
import numpy as np
import pandas as pd
import geopandas as gpd
from shapely import wkt
import matplotlib.pyplot as plt
from matplotlib import colormaps
from matplotlib.colors import TwoSlopeNorm
from matplotlib.cm import ScalarMappable
from matplotlib.ticker import FixedLocator, FuncFormatter
import seaborn as sns

# ---- global style (seaborn whitegrid) --------------------------------------
sns.set_theme(style="whitegrid", context="paper",
              font="DejaVu Sans",
              rc={
                  "axes.edgecolor":   "#333",
                  "axes.labelcolor":  "#222",
                  "axes.titlecolor":  "#222",
                  "xtick.color":      "#222",
                  "ytick.color":      "#222",
                  "text.color":       "#222",
                  "font.size":        7.0,
                  "axes.titlesize":   7.0,
                  "axes.labelsize":   6.5,
                  "xtick.labelsize":  6.0,
                  "ytick.labelsize":  6.0,
                  "legend.fontsize":  6.5,
                  "figure.facecolor": "white",
                  "savefig.facecolor":"white",
                  "savefig.dpi":      300,
              })

# ---- carrier branding ------------------------------------------------------
PROV_COLORS = {
    "DHL":             "#FFCC00",
    "Amazon Logistics":"#077498",
    "DPD":             "#E3000B",
    "GLS":             "#1C3F95",
    "Hermes":          "#00A0E1",
    "UPS":             "#392312",
    "FedEx/TNT":       "#4D148C",
}
CARRIER_LABEL = {
    "DHL":"DHL", "Amazon":"Amazon\nLogistics", "DPD":"DPD", "GLS":"GLS",
    "Hermes":"Hermes", "UPS":"UPS", "FedEx/TNT":"FedEx",
}
CARRIER_COLOR_KEY = {
    "DHL":"DHL", "Amazon":"Amazon Logistics", "DPD":"DPD", "GLS":"GLS",
    "Hermes":"Hermes", "UPS":"UPS", "FedEx/TNT":"FedEx/TNT",
}
SCEN_COLORS = {
    "Baseline":               "#e41a1c",
    "Moderate Consolidation": "#377eb8",
    "High Consolidation":     "#4daf4a",
    "Full Consolidation":     "#984ea3",
}

# ---- load PLZ polygons -----------------------------------------------------
_PLZ_CSV_CANDIDATES = [
    "input/plz_region_hannover.csv",
    "../parcel-demand-estimation/input/plz_region_hannover.csv",
    "../input/plz_region_hannover.csv",
]
_plz_path = next((p for p in _PLZ_CSV_CANDIDATES if os.path.exists(p)), None)
assert _plz_path is not None, "plz_region_hannover.csv not found"
print(f"Loading PLZ polygons from {_plz_path}")
_plz_df = pd.read_csv(_plz_path)
_plz_df["geometry"] = _plz_df["geometry"].apply(wkt.loads)
plz_gdf = gpd.GeoDataFrame(_plz_df, geometry="geometry", crs="EPSG:25832")
plz_gdf["postal_cod"] = plz_gdf["postal_cod"].astype(str)
print(f"  → {len(plz_gdf)} PLZ polygons")

# ---- resolve week dicts ----------------------------------------------------
_g = globals()
baseline_week   = _g.get("new_normal_week") or _g.get("normal_week") or _g.get("hagrid_files")
moderate_week_d = _g.get("new_moderate_week") or _g.get("moderate_week")
high_week_d     = _g.get("new_high_week")     or _g.get("high_week")
full_week_d     = _g.get("new_full_week")     or _g.get("full_week")
assert all(w is not None for w in (baseline_week, moderate_week_d, high_week_d, full_week_d))

SCEN_WEEKS = {
    "Moderate Consolidation": moderate_week_d,
    "High Consolidation":     high_week_d,
    "Full Consolidation":     full_week_d,
}
CARRIER_PREFIX = {
    "DHL":"dhl", "Amazon":"ama", "DPD":"dpd", "GLS":"gls",
    "Hermes":"her", "UPS":"ups", "FedEx/TNT":"fxt",
}
_EXPECTED_DATE = {
    "Monday":"2025-05-12","Tuesday":"2025-05-13","Wednesday":"2025-05-14",
    "Thursday":"2025-05-15","Friday":"2025-05-16","Saturday":"2025-05-17",
}
def _wd_gdf(week_dict, weekday):
    if weekday in week_dict:
        return week_dict[weekday]
    dt = _EXPECTED_DATE.get(weekday, "")
    for k in week_dict:
        if f"({weekday})" in k and (not dt or dt in k):
            return week_dict[k]
    for k in week_dict:
        if f"({weekday})" in k:
            return week_dict[k]
    return None

# ---- spatial-join points → PLZ (cached) ------------------------------------
_plz_cache = {}
def _points_with_plz(gd):
    if gd is None or len(gd) == 0:
        return None
    key = id(gd)
    if key in _plz_cache:
        return _plz_cache[key]
    pts = gpd.GeoDataFrame(gd.copy(), geometry=gd.geometry, crs=gd.crs or "EPSG:25832")
    if pts.crs is None:
        pts.set_crs("EPSG:25832", inplace=True)
    _plz_for_join = plz_gdf[["postal_cod", "geometry"]].rename(
        columns={"postal_cod": "plz_key"}
    )
    joined = gpd.sjoin(pts, _plz_for_join, how="left", predicate="within")
    _plz_cache[key] = joined
    return joined

def _plz_sum(week_dict, day, prefix):
    gd = _wd_gdf(week_dict, day)
    if gd is None:
        return {}
    b2b, b2c = f"{prefix}_b2b", f"{prefix}_b2c"
    if b2b not in gd.columns or b2c not in gd.columns:
        return {}
    joined = _points_with_plz(gd)
    if joined is None:
        return {}
    vals = (joined[b2b].fillna(0).astype(float)
            + joined[b2c].fillna(0).astype(float))
    return vals.groupby(joined["plz_key"]).sum().to_dict()

# ---- aggregate -------------------------------------------------------------
print("Aggregating baseline parcels per PLZ …")
BASE = {}
for carrier in D3_CARRIER_ORDER:
    pref = CARRIER_PREFIX[carrier]
    for day in D3_DAY_ORDER:
        BASE[(carrier, day)] = _plz_sum(baseline_week, day, pref)

print("Aggregating scenario parcels per PLZ …")
DELTA_PLZ, SCEN_TOT = {}, {}
for scenario, wk in SCEN_WEEKS.items():
    for carrier in D3_CARRIER_ORDER:
        pref = CARRIER_PREFIX[carrier]
        for day in D3_DAY_ORDER:
            s = _plz_sum(wk, day, pref)
            b = BASE[(carrier, day)]
            keys = set(s) | set(b)
            delta = {k: s.get(k, 0.0) - b.get(k, 0.0) for k in keys}
            DELTA_PLZ[(scenario, carrier, day)] = delta
            SCEN_TOT[(scenario, carrier, day)] = sum(s.values())

# ---- linear colour scale (clipped at 90th percentile of |Δ|) ----------------
_all_abs = []
for k, d in DELTA_PLZ.items():
    if SCEN_TOT[k] == 0:
        continue
    _all_abs.extend(abs(v) for v in d.values())
_all_abs = np.asarray(_all_abs, dtype=float)
_pos = _all_abs[_all_abs > 0]
VMAX_L = float(np.quantile(_pos, 0.90)) if _pos.size else 1.0
VMAX_L= 1000
L_NORM = TwoSlopeNorm(vmin=-VMAX_L, vcenter=0, vmax=VMAX_L)
L_CMAP = colormaps["Spectral"]
print(f"VMAX_L = {VMAX_L:.0f} parcels (90th pct of |Δ|; larger values clipped)")

# ---- figure: paper-compact double-column -----------------------------------
n_car  = len(D3_CARRIER_ORDER)
n_days = len(D3_DAY_ORDER)
n_sc   = len(D3_SCENARIO_ORDER)

FIG_W_IN = 7.16
ROW_H_IN = 0.42
FIG_H_IN = ROW_H_IN * n_car + 0.22   # tighter top+bottom band

SPACER = 0.18
width_ratios = []
for s_idx in range(n_sc):
    width_ratios.extend([1.0] * n_days)
    if s_idx < n_sc - 1:
        width_ratios.append(SPACER)
total_cols = len(width_ratios)

fig = plt.figure(figsize=(FIG_W_IN, FIG_H_IN))
fig.patch.set_facecolor("white")
gs = fig.add_gridspec(n_car, total_cols,
                      width_ratios=width_ratios,
                      left=0.085, right=0.992, top=0.915, bottom=0.085,
                      wspace=0.04, hspace=0.0)

def _gcol(s_idx, d_idx):
    return s_idx * (n_days + 1) + d_idx

axes = np.empty((n_car, n_sc * n_days), dtype=object)
xmin, ymin, xmax, ymax = plz_gdf.total_bounds

for r, carrier in enumerate(D3_CARRIER_ORDER):
    car_label = CARRIER_LABEL[carrier]
    car_color = PROV_COLORS.get(CARRIER_COLOR_KEY[carrier], "#333")
    for s_idx, scenario in enumerate(D3_SCENARIO_ORDER):
        for d_idx, day in enumerate(D3_DAY_ORDER):
            ax = fig.add_subplot(gs[r, _gcol(s_idx, d_idx)])
            axes[r, s_idx * n_days + d_idx] = ax

            tot = SCEN_TOT[(scenario, carrier, day)]
            if tot == 0:
                ax.patch.set_alpha(0.0)
                ax.set_facecolor("none")
            else:
                ax.set_facecolor("white")
                delta = DELTA_PLZ[(scenario, carrier, day)]
                vals = plz_gdf["postal_cod"].map(delta).fillna(0.0)
                gplot = plz_gdf.assign(delta=vals)
                gplot.plot(ax=ax, column="delta", cmap=L_CMAP, norm=L_NORM,
                           linewidth=0.08, edgecolor="#888",
                           rasterized=True)

            ax.set_xlim(xmin, xmax); ax.set_ylim(ymin, ymax)
            ax.set_aspect("equal")
            ax.set_xticks([]); ax.set_yticks([])
            ax.grid(False)
            for sp in ax.spines.values():
                sp.set_visible(False)

            if r == 0:
                ax.set_title(D3_DAY_LABELS[day][:2], fontsize=6.2,
                             pad=1.2, color="#333")
            if s_idx == 0 and d_idx == 0:
                ax.text(-0.18, 0.5, car_label, transform=ax.transAxes,
                        ha="right", va="center", fontsize=6.8,
                        fontweight="bold", color=car_color,
                        linespacing=1.0)

# ---- scenario headers ------------------------------------------------------
fig.canvas.draw()
for s_idx, scenario in enumerate(D3_SCENARIO_ORDER):
    ax_l = axes[0, s_idx * n_days]
    ax_r = axes[0, s_idx * n_days + n_days - 1]
    cx = (ax_l.get_position().x0 + ax_r.get_position().x1) / 2
    fig.text(cx, ax_r.get_position().y1 + 0.022, scenario,
             ha="center", va="bottom", fontsize=8.2,
             fontweight="bold", color=SCEN_COLORS[scenario])

# ---- colourbar (linear; extremes marked >max / <min to signal overflow) -----
cax = fig.add_axes([0.30, 0.012, 0.42, 0.030])
cb = fig.colorbar(ScalarMappable(norm=L_NORM, cmap=L_CMAP),
                  cax=cax, orientation="horizontal", extend="both")
cb.outline.set_visible(False)
_vmax_int = int(round(VMAX_L))
_tick_step = VMAX_L / 2.0
_ticks = [-VMAX_L, -_tick_step, 0.0, _tick_step, VMAX_L]
def _fmt_tick(v, _p):
    if abs(v) < 1:
        return "0"
    if v >= VMAX_L - 1e-6:
        return f">+{_vmax_int}"
    if v <= -VMAX_L + 1e-6:
        return f"<−{_vmax_int}"
    return f"{int(round(v)):+d}"
cb.ax.xaxis.set_major_locator(FixedLocator(_ticks))
cb.ax.xaxis.set_major_formatter(FuncFormatter(_fmt_tick))
cb.ax.tick_params(labelsize=6, pad=1.2, length=2)
cb.ax.grid(False)
cb.set_label(
    f"Δ parcels per PLZ vs baseline",

    fontsize=6.5, labelpad=1.5,
)

# ---- export ---------------------------------------------------------------
os.makedirs("output", exist_ok=True)
_pdf_path = os.path.join("output", "map_l_per_carrier_delta.pdf")
fig.savefig(_pdf_path, bbox_inches="tight", pad_inches=0.02)
print(f"Saved: {_pdf_path}")

plt.show()

