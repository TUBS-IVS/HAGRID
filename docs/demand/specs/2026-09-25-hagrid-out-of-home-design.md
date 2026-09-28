# Out-of-home delivery: parcel lockers, shops and shared boxes

Status: approved for implementation (user choice "short spec, then build"), 2026-09-25.

## 1. Goal

A share of the B2C parcels of every carrier is delivered to a parcel locker or pickup shop instead of the home
address. The share follows a trend over the years (as the notebooks forecast shares), varies per carrier and day,
and the parcels are routed to concrete points with limited capacity. The points become their own stops in the
annual store and in the MATSim export; home stops lose these parcels. Annual carrier totals do not change.

## 2. Points

* **Source:** OpenStreetMap snapshot via Overpass for the region bounding box, clipped to the postal polygons:
  `amenity=parcel_locker`, `amenity=post_office`, objects with `post_office=*` (partner shops), stored once as
  input parquet (`osm_parcel_points`, EPSG:25832) by a new CLI command `baseline osm-parcel-points`.
  Snapshot 2026-09-25: 204 DHL Packstation, 21 Amazon Locker/Hub, 9 open lockers (Myflexbox, Paketbox),
  about 100 DHL/Deutsche Post shops, 37 Hermes, 8 GLS and 4 DPD shops.
* **Carrier mapping** (`brand`, `operator`, `post_office:brand`, `name`): Packstation/DHL/Deutsche Post → DHL;
  Amazon Locker/Hub/Counter → Amazon; Hermes → Hermes; DPD → DPD; GLS → GLS; UPS → UPS; open lockers
  (Myflexbox, Paketbox, other unbranded lockers) → shared by Hermes, DPD, GLS and UPS.
* **Synthetic shops:** OSM misses most partner shops of Hermes, DPD, GLS and UPS. Target counts per carrier are
  the national networks scaled by the region's population share (≈ 1.4 %): Hermes 16,000, DPD 8,000, GLS 10,000,
  UPS 4,000 nationally (assumptions in `data/out_of_home.json`, to verify). Missing shops are placed at retail POIs
  of the POI extract (kiosk, convenience, supermarket, chemist, fuel, newsagent, stationery, tobacco), drawn with
  probability proportional to the population within 500 m, one carrier per POI, and flagged `synthetic`.

## 3. Share of parcels delivered out of home

* **Trend:** `share_c(y) = ceiling_c / (1 + exp(-growth · (y − midpoint)))`, the bounded sigmoid of the notebooks.
  The shape is fitted to DHL (3 % 2019, 5 % 2021, 10 % 2025: growth 0.24, midpoint 2028.8, ceiling 35 %). Other
  carriers keep the shape with their own 2025 level: DHL 12, GLS 12, DPD 10, Hermes 8, UPS 8, Amazon 5,
  FedEx/TNT 3 % of B2C parcels (assumptions from DHL, Hermes, GLS and BPEX figures; ≈ 10 % overall in 2025,
  ≈ 20 % in 2030). Configurable per carrier: level, or a year table that replaces the trend.
* **Daily variation:** per carrier a mean-one log-normal day factor (log-SD 0.10, AR(1) ρ 0.6) on the share.

## 4. Which parcels and where

* **Propensity per building and carrier:** `p = λ_c · a_b · exp(−d_b,c / 600 m)`, capped at 0.9, with `d` the
  distance to the nearest point serving the carrier (none within 1,500 m → 0), `a_b` 1.5 for buildings with at
  least 3 households, 0.7 otherwise (B2C sites only). `λ_c` is solved per year so that the parcel-weighted mean
  propensity equals `share_c(y)`.
* **Daily draw:** the out-of-home parcels of a site and carrier are binomial with `p · f_c,t`; they go to the
  carrier's points within 1,500 m by a multinomial with weights `exp(−d / 300 m)` (lockers 1.0, shops 0.7).
* **Capacity:** lockers 70 compartments / 1.5 days dwell ≈ 47 parcels a day, shops 120, open lockers 40 shared by
  their carriers (`data/out_of_home.json`). Overflow moves to the next point of the same carrier by distance, what
  still does not fit is delivered at home.

## 5. Outputs

* Annual store: points are extra stops (`stop_index` after the reference stops) with their own table
  `annual/out_of_home_points.parquet` (stop_index, point_id, kind, carriers, synthetic, geometry);
  `days.parquet` gains `out_of_home` parcels per day and carrier.
* MATSim export: points are features with a new field `stop_type` (`locker`, `shop`; homes `home`) and the
  carrier B2C fields; rows above 400 parcels are split as for other stops.
* Dashboard: out-of-home share in the KPIs and a line in the method list.

## 6. Acceptance

1. Annual out-of-home share per carrier equals the trend value (±0.5 pp); carrier totals unchanged.
2. No point exceeds its daily capacity; overflow is counted.
3. Daily out-of-home share per carrier varies with about the configured log-SD.
4. Median parcels per DHL Packstation and delivery day between 20 and 50 in 2025.
5. MATSim export keeps all existing fields; `out_of_home` disabled reproduces the current results.

## 7. Out of scope

Recipient pickup trips, returns via lockers, locker siting optimisation.
