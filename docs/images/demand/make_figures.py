"""Render the parcel-demand figures of the project README from the decade dashboard payload.

Every figure is written twice, ``<name>-light.png`` and ``<name>-dark.png``; the README picks the variant that matches
the reader's colour scheme with a ``<picture>`` element. From the repository root:

    python docs/images/demand/make_figures.py --dashboard hagrid/demand/runs/decade_dashboard.html --out docs/images/demand

The dashboard is built by ``runs/hannover/run_demand_decade.bat`` (scenarios trend, saettigung, boom, trend-innen,
trend-suburban); its first scenario is the primary one.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

import geopandas as gpd
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
from matplotlib import patheffects  # noqa: E402

# The validated reference palette of the data-viz method: categorical slots in fixed order, a blue sequential ramp,
# blue <-> red diverging around a neutral midpoint. Dark mode uses its own steps (brighter = more), not an inversion.
THEMES = {
    "light": {"surface": "#fcfcfb", "ink": "#0b0b0b", "ink2": "#52514e", "muted": "#898781", "grid": "#e1e0d9",
              "axis": "#c3c2b7", "series": ["#2a78d6", "#eb6834", "#1baf7a"], "mid": "#f0efec", "empty": "#e7e6e1",
              "land": "#ecebe6", "outline": "#52514e", "halo": "#fcfcfb",
              "blue": ["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"],
              "red": ["#f3aeac", "#c93c3a"]},
    "dark": {"surface": "#1a1a19", "ink": "#ffffff", "ink2": "#c3c2b7", "muted": "#898781", "grid": "#2c2c2a",
             "axis": "#383835", "series": ["#3987e5", "#d95926", "#199e70"], "mid": "#383835", "empty": "#242423",
             "land": "#262625", "outline": "#c3c2b7", "halo": "#1a1a19",
             "blue": ["#0d366b", "#104281", "#184f95", "#256abf", "#3987e5", "#6da7ec", "#b7d3f6"],
             "red": ["#7a2e2d", "#f07f78"]},
}
SCENARIOS = [("trend", "Trend"), ("saettigung", "Saturation"), ("boom", "Boom")]
LABEL = dict(SCENARIOS)
DPI = 150
WIDTH = 12.


# --- payload and geometry ---------------------------------------------------------------------------------------------

def load_payload(path: Path) -> dict:
    """The JSON payload embedded in the decade dashboard, with the shared structure pool resolved."""
    html = path.read_text(encoding="utf-8")
    match = re.search(r'<script[^>]*id="hagrid-decade"[^>]*>(.*?)</script>', html, re.S)
    if match is None:
        raise ValueError(f"{path} holds no decade dashboard payload")
    data = json.loads(match.group(1))
    pool = data.get("structure_pool") or {"geo": {}, "sites": {}}
    for block in (data.get("structure") or {}).values():
        if not block:
            continue
        if isinstance(block["districts"].get("geo"), str):
            block["districts"]["geo"] = pool["geo"][block["districts"]["geo"]]
        if isinstance(block.get("sites"), str):
            block["sites"] = pool["sites"][block["sites"]]
    return data


def features(collection: dict) -> gpd.GeoDataFrame:
    frame = gpd.GeoDataFrame.from_features(collection["features"], crs=4326).to_crs(25832)
    return frame.set_index(frame["id"].astype(str))


def points(lon, lat) -> gpd.GeoSeries:
    return gpd.GeoSeries(gpd.points_from_xy(lon, lat), crs=4326).to_crs(25832)


class Region:
    """Forecast districts (EPSG:25832) with the region and city outlines and the place labels."""

    def __init__(self, structure: dict):
        districts = structure["districts"]
        frame = features(districts["geo"])
        info = gpd.GeoDataFrame({"name": districts["names"], "kind": districts["kinds"]}, index=[str(i) for i in districts["ids"]])
        self.districts = frame.join(info)
        self.outline = self.districts.union_all()
        self.city = self.districts.loc[self.districts.kind.eq("city")].union_all()
        umland = self.districts.loc[self.districts.kind.eq("umland")]
        self.labels = [(name, geometry.representative_point()) for name, geometry in zip(umland.name, umland.geometry)]
        self.labels.append(("Hannover", self.city.centroid))

    def frame_axes(self, ax, theme: dict, *, labels: str = "all", pad: float = 1200.) -> None:
        minx, miny, maxx, maxy = self.outline.bounds
        ax.set_xlim(minx - pad, maxx + pad)
        ax.set_ylim(miny - pad, maxy + pad)
        ax.set_aspect("equal")
        ax.set_axis_off()
        gpd.GeoSeries([self.outline.boundary], crs=25832).plot(ax=ax, color=theme["outline"], linewidth=.9, zorder=4)
        gpd.GeoSeries([self.city.boundary], crs=25832).plot(ax=ax, color=theme["outline"], linewidth=.6, linestyle=(0, (3, 2)), zorder=4)
        for name, point in self.labels:
            city = name == "Hannover"
            if labels == "none" or (labels == "city" and not city):
                continue
            ax.text(point.x, point.y, name, ha="center", va="center", zorder=6, color=theme["ink"] if city else theme["ink2"],
                    fontsize=10.5 if city else 7.2, fontweight="bold" if city else "normal",
                    path_effects=[patheffects.withStroke(linewidth=2.6, foreground=theme["halo"])])


# --- styling and layout -----------------------------------------------------------------------------------------------

def use_theme(theme: dict) -> None:
    plt.rcParams.update({
        "font.family": ["Segoe UI", "DejaVu Sans"], "font.size": 10, "text.color": theme["ink"],
        "axes.facecolor": theme["surface"], "figure.facecolor": theme["surface"], "savefig.facecolor": theme["surface"],
        "axes.edgecolor": theme["axis"], "axes.labelcolor": theme["ink2"], "xtick.color": theme["muted"],
        "ytick.color": theme["muted"], "axes.grid": False, "grid.color": theme["grid"], "grid.linewidth": .8,
        "axes.spines.top": False, "axes.spines.right": False, "legend.frameon": False,
    })


def figure(height: float, title: str, subtitle: str, theme: dict):
    """A figure with title and subtitle placed in inches from the top edge, so every height gets the same header."""
    fig = plt.figure(figsize=(WIDTH, height))
    fig.text(.03, 1 - .3 / height, title, fontsize=16, fontweight="bold", color=theme["ink"], ha="left", va="top")
    fig.text(.03, 1 - .72 / height, subtitle, fontsize=10.5, color=theme["ink2"], ha="left", va="top")
    return fig


def band(height: float, top: float, bottom: float) -> tuple[float, float]:
    """Figure-fraction bottom and height of a plot band *top* inches below the top edge and *bottom* above the foot."""
    return bottom / height, 1 - (top + bottom) / height


def source(fig, text: str, theme: dict) -> None:
    fig.text(.03, .018, text, fontsize=7.5, color=theme["muted"], ha="left", va="bottom")


def row_legend(fig, rect, entries: list[tuple[str, str]], theme: dict, heading: str) -> None:
    """A row of colour swatches with a label under each (discrete classes, not a gradient)."""
    ax = fig.add_axes(rect)
    ax.set_axis_off()
    ax.set_xlim(0, len(entries))
    ax.set_ylim(0, 1)
    ax.text(0, 1.12, heading, fontsize=8.5, color=theme["ink2"], ha="left", va="bottom")
    for index, (color, label) in enumerate(entries):
        ax.add_patch(plt.Rectangle((index + .04, .5), .92, .45, facecolor=color, edgecolor=theme["surface"], linewidth=1.5))
        ax.text(index + .5, .32, label, fontsize=7.8, color=theme["ink2"], ha="center", va="top")


def column_legend(ax, y: float, entries: list[tuple[str, str]], theme: dict, heading: str, step: float = .055) -> float:
    """A column of swatches (axes fraction); returns the y below the last entry."""
    ax.text(0, y, heading, fontsize=9.5, fontweight="bold", color=theme["ink"], ha="left", va="top")
    y -= .06
    for color, label in entries:
        ax.add_patch(plt.Rectangle((0, y - .032), .09, .036, facecolor=color, edgecolor=theme["axis"], linewidth=.4))
        ax.text(.13, y - .014, label, fontsize=9, color=theme["ink2"], ha="left", va="center")
        y -= step
    return y


def key_figures(ax, y: float, rows: list[tuple[str, str]], theme: dict, heading: str) -> float:
    ax.text(0, y, heading, fontsize=9.5, fontweight="bold", color=theme["ink"], ha="left", va="top")
    y -= .065
    for value, label in rows:
        ax.text(0, y, value, fontsize=15, fontweight="bold", color=theme["ink"], ha="left", va="top")
        ax.text(0, y - .045, label, fontsize=8.6, color=theme["ink2"], ha="left", va="top")
        y -= .115
    return y


def side_panel(fig, left: float = .7) -> plt.Axes:
    ax = fig.add_axes([left, .08, .98 - left, .74])
    ax.set_axis_off()
    ax.set_xlim(0, 1)
    ax.set_ylim(0, 1)
    return ax


def year_axis(ax, years: list[int]) -> None:
    ticks = [year for year in years if (year - years[0]) % 2 == 0]
    ax.set_xticks(ticks)
    ax.set_xticklabels([str(year) for year in ticks])


def save(fig, out: Path, name: str, mode: str) -> Path:
    path = out / f"{name}-{mode}.png"
    fig.savefig(path, dpi=DPI, pil_kwargs={"optimize": True})
    plt.close(fig)
    return path


def signed(value: float, digits: int = 0) -> str:
    return f"{value * 100:+.{digits}f} %"


# --- figures ----------------------------------------------------------------------------------------------------------

def hexagon_change(payload: dict, region: Region, theme: dict, out: Path, mode: str) -> Path:
    """Where demand grows: change of expected parcels per delivery day per 800 m hexagon against the region."""
    change, name = payload["change"], payload["meta"]["primary"]
    first, last = str(payload["years"][0]), str(payload["years"][-1])
    hexes = features(change["hex"]["geo"]).reindex([str(key) for key in change["hex"]["ids"]])
    start = np.asarray(change["values"][name][first]["total"], float)
    end = np.asarray(change["values"][name][last]["total"], float)
    growth = end.sum() / start.sum()
    with np.errstate(divide="ignore", invalid="ignore"):
        relative = (end / start) / growth - 1.
    classes = [(theme["red"][1], "below −5 %"), (theme["red"][0], "−5 to −1 %"), (theme["mid"], "within ±1 %"),
               (theme["blue"][2], "+1 to +5 %"), (theme["blue"][4], "+5 to +15 %"), (theme["blue"][6], "over +15 % or new")]
    edges = [-np.inf, -.05, -.01, .01, .05, .15, np.inf]
    grade = np.clip(np.searchsorted(edges, np.nan_to_num(relative, nan=np.inf, posinf=np.inf), side="right") - 1, 0, len(classes) - 1)
    shown = (start >= 1.) | (end >= 1.)          # hexagons with at least one parcel per delivery day
    grade[start < 1.] = len(classes) - 1         # new demand where there was (almost) none
    height = 7.8
    fig = figure(height, "Where parcel demand grows until 2035",
                 f"Scenario {LABEL.get(name, name)} with land use · expected parcels per delivery day {first} → {last} in 800 m "
                 f"hexagons, compared with the regional growth of {signed(growth - 1, 1)}", theme)
    bottom, span = band(height, 1.05, .35)
    ax = fig.add_axes([.01, bottom, .67, span])
    region.districts.plot(ax=ax, facecolor=theme["land"], edgecolor="none", zorder=1)
    colors = np.array([classes[index][0] for index in grade], dtype=object)
    hexes.loc[shown].plot(ax=ax, color=list(colors[shown]), edgecolor=theme["surface"], linewidth=.35, zorder=2)
    region.frame_axes(ax, theme)
    panel = side_panel(fig)
    y = column_legend(panel, .98, classes, theme, "Growth against the region")
    districts = change["districts"].get(name) or {}
    if districts:
        kinds = np.asarray(districts["kinds"])
        total = {year: np.asarray(districts[year]["total"], float) for year in (first, last)}
        plain = np.asarray(districts[last]["plain"], float)
        city, umland = kinds == "city", kinds == "umland"
        moved = plain[city].sum() - total[last][city].sum()         # > 0: land use moves parcels out of the city
        direction = "from the city to the towns" if moved >= 0 else "from the towns to the city"
        rows = [(signed(growth - 1, 1), "Region Hannover"),
                (signed(total[last][city].sum() / total[first][city].sum() - 1, 1), "City of Hannover"),
                (signed(total[last][umland].sum() / total[first][umland].sum() - 1, 1), "20 surrounding towns"),
                (f"{abs(moved):,.0f}", f"parcels per day that land use\nshifts {direction}")]
        key_figures(panel, y - .05, rows, theme, f"Parcels per delivery day {first} → {last}")
    source(fig, "Colours count hexagons; the growth figures weight them by parcels. HAGRID demand model · districts of the "
           "population forecast 2025–2035 · © OpenStreetMap contributors", theme)
    return save(fig, out, "hexagon-change", mode)


def volume_scenarios(payload: dict, theme: dict, out: Path, mode: str) -> Path:
    """National parcel volume (observed and three scenarios) next to the simulated parcels of Region Hannover."""
    national, annual, years = payload["national"], payload["annual"], payload["years"]
    height = 5.2
    fig = figure(height, "Three volume paths to 2035", "Left: courier, express and parcel shipments in Germany. "
                 "Right: parcels simulated for Region Hannover, every day of every year.", theme)
    bottom, span = band(height, 1.2, .55)
    left, right = fig.add_axes([.065, bottom, .41, span]), fig.add_axes([.56, bottom, .33, span])
    observed, estimates = national["observed"], national.get("estimates") or {"years": [], "values": []}
    left.axvspan(years[0] - .5, years[-1] + .5, color=theme["grid"], alpha=.5, linewidth=0, zorder=0)
    left.plot(observed["years"], observed["values"], "o", color=theme["ink2"], markersize=3.6, zorder=6)
    left.plot(estimates["years"], estimates["values"], "o", markerfacecolor=theme["surface"], markeredgecolor=theme["ink2"],
              markersize=4.2, markeredgewidth=1.2, linestyle="none", zorder=6)
    left.text(2009, 2.75, "observed", color=theme["ink2"], fontsize=8.5, ha="center")
    if estimates["years"]:
        left.annotate("notebook\nestimates", (estimates["years"][-1], estimates["values"][-1]), xytext=(2026.4, 2.55),
                      color=theme["ink2"], fontsize=8, ha="center",
                      arrowprops={"arrowstyle": "-", "color": theme["muted"], "linewidth": .8})
    left.text(years[0] + .2, .25, "projection", color=theme["muted"], fontsize=8)
    reference = next((item.get("reference") for item in payload["meta"]["scenarios"] if item.get("reference")), None)
    for index, (key, label) in enumerate(SCENARIOS):
        series = national["scenarios"].get(key)
        if not series:
            continue
        xs, ys = zip(*[(year, value) for year, value in zip(series["years"], series["values"]) if year >= years[0]])
        color = theme["series"][index]
        fit = series.get("fit")
        if fit:
            left.plot(fit["years"], fit["values"], color=color, linewidth=1.3, linestyle=(0, (4, 3)), zorder=3)
        left.plot(xs, ys, color=color, linewidth=2.2, zorder=4)
        left.plot([xs[-1]], [ys[-1]], "o", color=color, markersize=5, zorder=5)
        left.text(xs[-1] + .5, ys[-1], f"{label} {ys[-1]:.2f}", color=theme["ink"], fontsize=8.5, va="center")
        values = [annual[key][str(year)]["parcels"] / 1e6 if (annual.get(key) or {}).get(str(year)) else np.nan for year in years]
        if reference:
            right.plot([reference["year"], years[0]], [reference["parcels"] / 1e6, values[0]], color=color, linewidth=1.3,
                       linestyle=(0, (4, 3)), zorder=3)
        right.plot(years, values, color=color, linewidth=2.2, marker="o", markersize=3.5, zorder=4)
        right.text(years[-1] + .35, values[-1], f"{label} {values[-1]:.1f} M", color=theme["ink"], fontsize=8.5, va="center")
    left.set_xlim(1999.5, years[-1] + 4.6)
    left.set_xticks([2000, 2005, 2010, 2015, 2020, 2025, 2030, 2035])
    left.set_ylim(0, 7.2)
    left.set_ylabel("billion shipments per year")
    first = reference["year"] if reference else years[0]
    right.set_xlim(first - .5, years[-1] + .5)
    year_axis(right, list(range(first, years[-1] + 1)))
    right.set_ylim(0, 95)
    right.set_ylabel("million parcels per year")
    right.set_clip_on(False)
    for ax in (left, right):
        ax.grid(axis="y")
        ax.set_axisbelow(True)
        ax.tick_params(length=0)
    start = annual[SCENARIOS[0][0]][str(years[0])]["parcels"] / 1e6
    right.annotate(f"{years[0]}: {start:.1f} M", (years[0], start), xytext=(years[0] + .3, start - 16), color=theme["ink2"], fontsize=8.5,
                   arrowprops={"arrowstyle": "-", "color": theme["muted"], "linewidth": .8})
    if reference:
        value = reference["parcels"] / 1e6
        right.plot([reference["year"]], [value], "o", markerfacecolor=theme["surface"], markeredgecolor=theme["ink2"],
                   markersize=5.5, markeredgewidth=1.4, zorder=6)
        right.annotate(f"{reference['year']} reference: {value:.1f} M", (reference["year"], value),
                       xytext=(reference["year"] + .2, value + 12), color=theme["ink2"], fontsize=8.5,
                       arrowprops={"arrowstyle": "-", "color": theme["muted"], "linewidth": .8})
    source(fig, "Observed: BIEK / Statista 2000–2023; rings: notebook estimates. Dashed: fits through the data and the bridge "
           "from the calibrated 2021 reference; the scenarios start at the 2025 level. Region: HAGRID annual store.", theme)
    return save(fig, out, "volume-scenarios", mode)


def calendar_year(payload: dict, theme: dict, out: Path, mode: str) -> Path:
    """Parcels per day of the last simulated year as a calendar carpet (weeks x weekdays)."""
    name, year = payload["meta"]["primary"], str(payload["years"][-1])
    calendar = payload["calendar"][name][year]
    days = np.asarray(calendar["parcels"], float)
    start = np.datetime64(calendar["start"])
    dates = np.arange(start, start + len(days))
    weekday = (dates.astype("datetime64[D]").view("int64") - 4) % 7          # 1970-01-01 was a Thursday -> Monday = 0
    week = (np.arange(len(days)) + weekday[0]) // 7
    edges = [1, 150e3, 200e3, 250e3, 300e3, 400e3, 500e3, np.inf]
    labels = ["< 150k", "150–200k", "200–250k", "250–300k", "300–400k", "400–500k", "> 500k"]
    ramp = theme["blue"]
    height = 4.3
    fig = figure(height, f"A year in days: {year}", f"Scenario {LABEL.get(name, name)} · {days.sum() / 1e6:.1f} million parcels on "
                 f"{int((days > 0).sum())} delivery days · no delivery on Sundays and public holidays (Lower Saxony)", theme)
    bottom, span = band(height, 1.3, 1.05)
    ax = fig.add_axes([.06, bottom, .92, span])
    for index, value in enumerate(days):
        color = theme["empty"] if value <= 0 else ramp[int(np.clip(np.searchsorted(edges, value, side="right") - 1, 0, 6))]
        ax.add_patch(plt.Rectangle((week[index], 6 - weekday[index]), .9, .9, facecolor=color, linewidth=0))
    ax.set_xlim(-.3, week.max() + 1.2)
    ax.set_ylim(-.1, 7.5)
    ax.set_axis_off()
    for row, label in enumerate(["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]):
        ax.text(-.6, 6 - row + .45, label, fontsize=7.5, color=theme["muted"], ha="right", va="center")
    for month, label in enumerate(["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]):
        index = int((np.datetime64(f"{year}-{month + 1:02d}-01") - start).astype(int))
        ax.text(week[index], 7.2, label, fontsize=8, color=theme["ink2"], ha="left", va="bottom")
    marks = [(int(event["from"]), int(event["to"]), event["name"]) for event in calendar.get("events", [])
             if event["name"] in ("Prime Day", "Black Week")]
    peak = int(np.argmax(days))
    marks.append((peak, peak, f"peak day {days[peak] / 1e3:,.0f}k"))
    for first, last, label in marks:
        for index in range(first, last + 1):
            ax.add_patch(plt.Rectangle((week[index] - .02, 6 - weekday[index] - .02), .94, .94, facecolor="none",
                                       edgecolor=theme["ink"], linewidth=1.1, zorder=5))
        anchor = week[first] + .45
        right_aligned = label.startswith("Black")
        ax.plot([anchor, anchor], [-.12, -.62], color=theme["muted"], linewidth=.8, clip_on=False)
        ax.text(anchor + (-.2 if right_aligned else .2), -.7, label, fontsize=8, color=theme["ink"], va="top",
                ha="right" if right_aligned else "left", clip_on=False)
    row_legend(fig, [.06, .07, .4, .075], [(theme["empty"], "no delivery")] + list(zip(ramp, labels)), theme, "Parcels per day")
    return save(fig, out, "calendar", mode)


def pickup_network(payload: dict, region: Region, theme: dict, out: Path, mode: str) -> Path:
    """Pickup points of the primary scenario by opening period, next to the network size of every scenario."""
    name, years = payload["meta"]["primary"], payload["years"]
    network = payload["network"][name]
    xy = points(network["lon"], network["lat"])
    opened = np.asarray(network["year_opened"], int)
    height = 7.4
    fig = figure(height, f"The pickup network grows from {int((opened <= years[0]).sum())} to {len(opened)} points",
                 f"Scenario {LABEL.get(name, name)} · parcel lockers, shared boxes and counters; new sites at supermarkets, fuel "
                 "stations, kiosks and bakeries where out-of-home demand and coverage gaps are largest", theme)
    bottom, span = band(height, 1.05, .35)
    ax = fig.add_axes([.01, bottom, .6, span])
    region.districts.plot(ax=ax, facecolor=theme["land"], edgecolor=theme["surface"], linewidth=.6, zorder=1)
    region.frame_axes(ax, theme, labels="city")
    middle = years[0] + (years[-1] - years[0]) // 2
    groups = [(opened <= years[0], theme["ink2"], f"existing {years[0]}", 9),
              ((opened > years[0]) & (opened <= middle), theme["series"][1], f"new {years[0] + 1}–{middle}", 15),
              (opened > middle, theme["series"][0], f"new {middle + 1}–{years[-1]}", 15)]
    for mask, color, label, size in groups:
        ax.scatter(xy.x[mask], xy.y[mask], s=size, color=color, edgecolors=theme["surface"], linewidths=.5, zorder=5,
                   label=f"{label} ({int(mask.sum())})")
    ax.legend(loc="lower left", fontsize=8.5, labelcolor=theme["ink2"], handletextpad=.3, borderaxespad=1.)
    chart = fig.add_axes([.67, .2, .24, .55])
    for index, (key, label) in enumerate(SCENARIOS):
        annual = payload["annual"].get(key) or {}
        values = [sum(annual[str(year)]["network"]["points"].values()) if annual.get(str(year)) else np.nan for year in years]
        chart.plot(years, values, color=theme["series"][index], linewidth=2.2, marker="o", markersize=3.5, clip_on=False)
        chart.text(years[-1] + .35, values[-1], f"{label} {int(values[-1])}", fontsize=8.5, va="center", color=theme["ink"])
    chart.set_xlim(years[0] - .5, years[-1] + .5)
    year_axis(chart, years)
    chart.set_ylim(0, None)
    chart.grid(axis="y")
    chart.set_axisbelow(True)
    chart.tick_params(length=0)
    chart.set_title("pickup points per scenario", fontsize=9.5, color=theme["ink2"], loc="left")
    source(fig, "Existing points: OpenStreetMap (2026) · growth per network group: N(y) = max(N(y−1), N(2025)·(D(y)/D(2025))^0.6)", theme)
    return save(fig, out, "pickup-network", mode)


def districts_population(payload: dict, region: Region, theme: dict, out: Path, mode: str) -> Path:
    """Population change per forecast district until the last year, with the numbered development areas."""
    name, first, last = payload["meta"]["primary"], payload["years"][0], payload["years"][-1]
    structure = payload["structure"][name]
    change = np.asarray(structure["districts"]["population_index"][str(last)], float) - 1.
    classes = [(theme["red"][1], "below −2 %"), (theme["red"][0], "−2 to −0.5 %"), (theme["mid"], "within ±0.5 %"),
               (theme["blue"][2], "+0.5 to +2 %"), (theme["blue"][4], "+2 to +5 %"), (theme["blue"][6], "over +5 %")]
    edges = [-np.inf, -.02, -.005, .005, .02, .05, np.inf]
    grade = np.clip(np.searchsorted(edges, change, side="right") - 1, 0, len(classes) - 1)
    frame = region.districts.reindex([str(i) for i in structure["districts"]["ids"]]).assign(color=[classes[g][0] for g in grade])
    height = 7.8
    fig = figure(height, f"Where people will live in {last}",
                 f"Population change {first} → {last} per forecast district (official forecast of Region Hannover) · "
                 "numbered rings: development areas", theme)
    bottom, span = band(height, 1.05, .35)
    ax = fig.add_axes([.01, bottom, .67, span])
    frame.plot(ax=ax, color=frame["color"], edgecolor=theme["surface"], linewidth=.8, zorder=2)
    region.frame_axes(ax, theme)
    sites = structure.get("sites") or {}
    areas: dict[str, list[int]] = {}
    for position, area in enumerate(sites.get("area", [])):
        if area:
            areas.setdefault(area, []).append(position)
    residents = {item["name"]: item["residents"][str(last)] for item in structure.get("developments", [])}
    order = sorted(areas, key=lambda area: -residents.get(area, 0.))
    rows = []
    for number, area in enumerate(order, start=1):
        spots = areas[area]
        centre = points([sites["lon"][i] for i in spots], [sites["lat"][i] for i in spots]).union_all().centroid
        ax.scatter([centre.x], [centre.y], s=150, facecolor=theme["surface"], edgecolor=theme["ink"], linewidth=1.2, zorder=7)
        ax.text(centre.x, centre.y, str(number), ha="center", va="center", fontsize=7, fontweight="bold", color=theme["ink"], zorder=8)
        rows.append((number, area, residents.get(area, 0.)))
    panel = side_panel(fig)
    y = column_legend(panel, .98, classes, theme, "Population change")
    panel.text(0, y - .04, f"Development areas ({last})", fontsize=9.5, fontweight="bold", color=theme["ink"], ha="left", va="top")
    y -= .1
    for number, area, people in rows:
        panel.text(0, y, f"{number}", fontsize=8.6, fontweight="bold", color=theme["ink"], ha="left", va="top")
        panel.text(.08, y, area, fontsize=8.6, color=theme["ink2"], ha="left", va="top")
        panel.text(.98, y, f"+{people:,.0f}", fontsize=8.6, color=theme["ink"], ha="right", va="top")
        y -= .045
    panel.text(0, y - .01, "model residents moved in", fontsize=7.6, color=theme["muted"], ha="left", va="top")
    source(fig, "Landeshauptstadt und Region Hannover, Bevölkerungsprognose 2025 bis 2035 (2026), tables 7 and 8 · "
           "© OpenStreetMap contributors", theme)
    return save(fig, out, "districts-population", mode)


def out_of_home(payload: dict, theme: dict, out: Path, mode: str) -> Path:
    """Out-of-home parcels per year by pickup-point kind, with the out-of-home share of B2C parcels."""
    name, years = payload["meta"]["primary"], payload["years"]
    annual = payload["annual"][name]
    kinds = [("locker", "parcel lockers"), ("shared_locker", "shared boxes"), ("counter", "counters")]
    height = 4.8
    fig = figure(height, "More parcels end at a pickup point",
                 f"Scenario {LABEL.get(name, name)} · parcels delivered to lockers, shared boxes and counters; "
                 "labels give the out-of-home share of all B2C parcels", theme)
    bottom, span = band(height, 1.25, .5)
    ax = fig.add_axes([.065, bottom, .9, span])
    base = np.zeros(len(years))
    for index, (key, label) in enumerate(kinds):
        values = np.array([annual[str(year)]["channels"].get(key, 0) / 1e6 for year in years])
        ax.bar(years, values, bottom=base, width=.66, color=theme["series"][index], edgecolor=theme["surface"], linewidth=1.5,
               label=label, zorder=3)
        base += values
    for year, top in zip(years, base):
        ax.text(year, top + .25, f"{annual[str(year)]['ooh_b2c_share'] * 100:.1f} %", ha="center", va="bottom", fontsize=8.5,
                color=theme["ink"])
    ax.set_xticks(years)
    ax.set_ylabel("million parcels per year")
    ax.set_ylim(0, base.max() * 1.25)
    ax.grid(axis="y")
    ax.set_axisbelow(True)
    ax.tick_params(length=0)
    ax.legend(loc="upper left", ncols=3, fontsize=8.5, labelcolor=theme["ink2"])
    source(fig, "Carrier targets: bounded sigmoid fitted to DHL (3 % 2019, 5 % 2021, 10 % 2025) · locker capacity and "
           "pickup times from the locker model (60/20/20 %)", theme)
    return save(fig, out, "out-of-home", mode)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--dashboard", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    payload = load_payload(args.dashboard)
    args.out.mkdir(parents=True, exist_ok=True)
    region = Region(payload["structure"][payload["meta"]["primary"]])
    written = []
    for mode, theme in THEMES.items():
        use_theme(theme)
        written += [hexagon_change(payload, region, theme, args.out, mode), volume_scenarios(payload, theme, args.out, mode),
                    calendar_year(payload, theme, args.out, mode), pickup_network(payload, region, theme, args.out, mode),
                    districts_population(payload, region, theme, args.out, mode), out_of_home(payload, theme, args.out, mode)]
    for path in written:
        print(f"{path.name} ({path.stat().st_size // 1000} KB)")


if __name__ == "__main__":
    main()
