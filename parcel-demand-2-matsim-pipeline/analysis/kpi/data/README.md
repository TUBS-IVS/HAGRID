# Emissionsfaktor-Quellen (Lausitz-Emissions-KPIs)

Zitierfaehige Quellendoku fuer die committeten Faktor-CSVs in diesem
Verzeichnis. Plan:
`docs/superpowers/plans/2026-07-28-emissions-emep-eea-tier3.md`.
Die Rohdateien (xlsx/pdf) liegen unter
`parcel-demand-2-matsim-pipeline/hagrid-input/emissions/` (gitignored,
Provenance-Tabelle dort in `SOURCES.md`).

## emep_hot_factors.csv
Extrahiert aus: EMEP/EEA air pollutant emission inventory guidebook
**2023 - Update 2025**, Kapitel 1.A.3.b.i-iv "Road transport",
**Appendix 4** (Version Okt 2025, verlinkt auf COPERT v5.9.1).
Download: 2026-07-28 von
https://www.eea.europa.eu/en/analysis/publications/emep-eea-guidebook-2025
(Zitat-Hinweis: die Kopfzeile des Kapitels lautet "guidebook 2023 -
Update 2025" - NICHT "guidebook 2025".)
Extraktion: `emep_factor_extract.py` (Filter: LCV, Segmente N1-I/II/III,
Euro 7, Diesel DPF+SCR bzw. Battery electric). Formel und
EF(80)-Validierung: siehe `emissions_emep.py` +
`tests/test_emissions_emep.py`.
ACHTUNG: Spalte "Reduction Factor [%]" der Quelle enthaelt Bruchteile.

24 Zeilen = 3 Segmente x (7 Diesel-Schadstoffe + 1 BEV-EC-Kurve).
Stichprobe (EF bei v=80, aus der Kontrollspalte der Quelle reproduziert):

| Segment | Diesel EC [MJ/km] | Diesel NOx [g/km] | BEV EC [MJ/km] |
|---------|-------------------|-------------------|----------------|
| N1-I    | 1.729636          | 0.038473          | 0.569087       |
| N1-II   | 1.910160          | 0.093489          | 0.842321       |
| N1-III  | 2.732177          | 0.093489          | 1.169166       |

Der NOx-Reduktionsfaktor unterscheidet sich zwischen den Segmenten:
N1-I 0.92, N1-II/III 0.282175. Der NOx-Abstand N1-I <-> N1-II entsteht
also ueberwiegend aus dem Reduktionsfaktor, nicht aus der Rohkurve - wer
N1-I zuordnet, ordnet damit eine andere Nachbehandlungsannahme zu.
`vmin`/`vmax` variieren je Schadstoff UND Segment, das Clamping in `ef(v)`
ist deshalb pro Koeffizientensatz.

## Klassenmapping (Rev. B, 2026-07-31)
Alle Fahrzeuge sind LCV (N1) Diesel Euro 7 DPF+SCR bzw. Battery electric.
Differenziert wird nur das Segment, also die methodeneigene Massenklasse:

| Typ              | Kapa | angesetzte Bezugsmasse | Segment |
|------------------|------|------------------------|---------|
| ct_cep_size_s    | 100  | ~1700 kg               | N1-II   |
| ct_cep_size_m    | 165  | ~2000 kg               | N1-III  |
| ct_cep_size_l    | 230  | ~2400 kg               | N1-III  |
| ct_cep_<cap>_<t> | var. | Kapazitaetsregel       | <=120 -> N1-II, sonst N1-III |
| drt_* (cap 10)   | -    | Sprinter-Tourer-Klasse | N1-III (M2-Substitution) |

Die Bezugsmasse ist je Typ eine ausgewiesene ANNAHME; die N1-Segmente sind
ueber die Bezugsmasse definiert (<=1305 / <=1760 / >1760 kg, EU-Typ-
genehmigung - NICHT im Guidebook-Kapitel, dort ist nur N1 als Ganzes
definiert). Segmentwirkung bei 30 km/h: N1-II 2,183 vs. N1-III 3,123 MJ/km
(+43 %); NOx N1-I 0,054 vs. N1-II/III 0,090 g/km; PM exhaust identisch.

ZULADUNG ist bewusst NICHT modelliert - das Guidebook beschraenkt die
Lastkorrektur auf schwere Nutzfahrzeuge (Default-Lastfaktor 50 %, Kap.
1.A.3.b.i-iv S. 62 f.); LCV-Zeilen tragen keine Load-/Slope-Spalte und
keinen dokumentierten Referenz-Ladezustand. Der Masseeffekt liegt bei
EMEP im Segment. Bound des nicht modellierten Lasteffekts: <=13 % (HDV-
Parametrisierung Rigid <=7,5 t, 0->100 % Last bei 30 km/h), ~5 % fuer
unsere Vans. Faktoren aus anderen Quellen (z. B. STREAM-Lastverhaeltnisse
in src/hagrid_output_analysis/config.py) duerfen hier NICHT eingemischt
werden - keine gemeinsame Referenzbasis.

Kategoriensubstitution M2 -> N1-III fuer die DRT-Flotte (cap 10, also M2
nach Guidebook Tab. 2-1) ist eine benannte Annahme. Alternativen bei
30 km/h: PC Large-SUV-Exec 2,545 / LCV N1-III 3,123 / Buses Urban Midi
<=15 t (Default-Last 50 %) ~9,1 MJ/km.

## emep_supplement.csv
Je Zeile Quelle in der `source`-Spalte; Zeilen, die NICHT aus dem
Guidebook stammen, beginnen mit "NOT a guidebook value" (WTT-Diesel,
Strommix, GWP100). Euro-7-Faktoren sind aus Grenzwerten PROJIZIERT (Norm
greift fuer LCV ab ~2026/27) - im Paper kennzeichnen.
`ev_range_km_{low,mid,high}` sind Sweep-Schwellen, kein Pass/Fail-Gate:
bei 250 km ist die Ueberschreitung in allen 12 geprueften Laeufen 0 %
(laengste Tour 183 km), Trennschaerfe nur bei ~150 km.

Der TTW-CO2-Faktor ist quellenintern hergeleitet: 3.169 kg CO2/kg Diesel
/ 42.695 MJ/kg (CO2-je-kg-Fuel-Tabelle bzw. Tab. 3-28 "Default calorific
and density values") = 74.22 g CO2/MJ. Der Marktkraftstoff B7 ergaebe
3.144/42.32 = 74.3, also unter 0,1 % Unterschied.

N2O: Tab. 3-68, Zeile "Diesel passenger cars and LCVs", Euro 7 -> urban
cold 9 / urban hot 11 / rural 4 / highway 4 mg/km, ohne Segmentaufloesung.
Verwendet wird urban hot (konservativ), rural 4 mg/km ist die
Sensitivitaetsuntergrenze. Bemerkenswert und im Paper zu nennen: der
WARM-Wert liegt ueber dem Kaltwert (SCR bildet N2O als Nebenprodukt im
Betriebszustand) - eine Kaltstartkorrektur wuerde N2O also SENKEN, nicht
erhoehen.

## Non-Exhaust (Kap. 1.A.3.b.vi-vii, verifiziert 2026-07-31)
Quelle: `1.A.3.b.vi-vii Road tyre and brake wear 2025.pdf` (38 S., von der
EEA-Kapitelseite geladen 2026-07-31, s. hagrid-input/emissions/SOURCES.md).

TSP-Basen [g/km] je N1-Segment - die Quelle loest LCV nach Segment auf:

| Abriebquelle | N1-I   | N1-II  | N1-III | Tabelle                  |
|--------------|--------|--------|--------|--------------------------|
| Reifen       | 0.0107 | 0.0169 | 0.0169 | 3-4 (II+III gruppiert)   |
| Bremse       | 0.0117 | 0.0155 | 0.0211 | 3-6 (alle drei getrennt) |
| Strasse      | 0.0150 | 0.0210 | 0.0210 | 3-8 (II+III gruppiert)   |

PM10-Anteil des TSP: Reifen 0.600 (Tab. 3-5), Bremse 0.980 (Tab. 3-7),
Strasse 0.50 (Tab. 3-9).

Geschwindigkeitskorrekturen (mittlere TRIP-Geschwindigkeit, nicht
Konstantfahrt):
  Gl. (5) Reifen: 1.39 fuer V<40; -0.00974*V+1.78 fuer 40<=V<=90; 0.902
    fuer V>90. Normiert auf 80 km/h.
  Gl. (8) Bremse: 1.67 fuer V<40; -0.0270*V+2.75 fuer 40<=V<=95; 0.185
    fuer V>95. Normiert auf 65 km/h.
  Strassenabrieb: KEINE Geschwindigkeitsabhaengigkeit (Gl. 9).
Bei unseren ~30-36 km/h greifen also beide Plateauwerte.

BEV: die Quelle hat keine BEV-Zeile fuer LCV, aber ICE- und BEV-Zeilen je
Pkw-Segment. Verwendet wird das guidebook-interne Verhaeltnis des
Medium-Pkw (Reifen 0.0116/0.0107 = 1.0841, Bremse 0.0030/0.0142 = 0.2113,
Strasse 0.0169/0.0150 = 1.1267) als deklarierter Kategorientransfer.
Physikalische Basis im Quelltext: PMP-Reibbremsanteil 0.17 fuer Elektro,
kombiniert mit hoeherer WLTP-Fahrzeugmasse.

LASTKORREKTUR ist auch hier HDV-only: LCF_T = 1.41 + 1.38*LF (Gl. 4) und
LCF_B = 1 + 0.79*LF (Gl. 7) gelten explizit nur fuer Trucks, Busse und
Reisebusse. Fuer LCV existiert kein Lastparameter - zweite unabhaengige
Belegstelle fuer die Lastentscheidung oben.

Vorbehalte der Quelle selbst, im Paper zu nennen (Details METHODS-LOG 2.27):

REIFEN-PM10 IST EINE OBERGRENZE. Das Guidebook kennzeichnet die eigenen
Werte als ueberschaetzt - der luftgetragene PM10-Anteil liege nach
neueren Messungen "well below 3 %" statt der angesetzten 60 % (Saladin et
al. 2024; Huber et al. 2024; Giechaskiel et al. 2024a); eine Revision der
Tabelle war zum Redaktionsstand nicht moeglich. Wir rechnen methodentreu
mit dem dokumentierten 0.600 und berichten den Vorbehalt, statt selbst
eine Zahl aus ungepruefter Primaerliteratur zu synthetisieren.
Quantifiziert (N1-III, 30 km/h): Reifen 14.1 mg/km = 24 % des Abriebs;
bei 3 % waeren es ~0.7 mg/km und der Gesamtabrieb faellt von 59.1 auf
45.7 mg/km (-23 %). Die Abweichung ist EINSEITIG (wahrer Wert darunter)
und trifft Diesel und BEV gleich - Richtungsaussagen bleiben gueltig.

KEIN Wert dieses Kapitels hat Qualitaetscode A: Reifen und Bremse tragen
B ("non-statistically significant based on a small set of measured
re-evaluated data"), der Strassenabrieb C-D ("highly uncertain") bei
10.5 mg/km = 18 % des Abriebs.

Groessenordnung, damit die Relevanz klar ist: der Auspuff-PM eines
Euro-7-Diesels mit DPF ist 0.142 mg/km, der Abrieb 59.1 mg/km - Faktor
~416. Und Elektrifizierung halbiert ihn nicht: BEV behaelt 34.4 mg/km
= 58 %, weil Reifen- und Strassenabrieb mit der Fahrzeugmasse STEIGEN und
nur der Bremsabrieb faellt.

## Aufteilungskonstanten (kg_per_parcel, kg_per_passenger, slots_per_seat_equiv)
Reine Post-Processing-Groessen: sie gehen weder in die Simulation noch in
den Emissionsfaktor noch in die Fahrleistung ein, sondern verteilen die
fertig berechnete Emission auf Fracht und Fahrgaeste. Aenderung = Edit
hier plus build_kpis-Neulauf, kein Sim-Rerun; `total_*` bleibt unveraendert.
Quellen und Vorbehalte: METHODS-LOG 2.26. Kurz:
  - kg_per_parcel = 1.65 kg, festgezurrte ANNAHME, Groessenordnung durch
    drei Quellen gestuetzt (Amaral et al. 2026 TR Part E; Rajendran &
    Harper 2021 TRIP; Mohri et al.) - keine davon deutsch, deklarierter
    Transfer. MITTELWERT, nicht Median.
  - kg_per_passenger = 80 kg ist eine SETZUNG ohne Quelle.
  - slots_per_seat_equiv = 2.5 (20 Paketslots / 8 Sitze) ist
    szenariodefiniert und haengt an keiner externen Massenannahme -
    deshalb die Pflicht-Begleitung zur Massenvariante.
