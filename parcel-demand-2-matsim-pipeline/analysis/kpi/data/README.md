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
    Harper 2021 TRIP; Mohri, Nassir, Lavieri & Thompson 2024, Travel
    Behaviour and Society 35, 100716) - keine davon deutsch, deklarierter
    Transfer. MITTELWERT, nicht Median.
  - kg_per_passenger = 80 kg ist eine SETZUNG ohne Quelle.
  - slots_per_seat_equiv = 2.5 (20 Paketslots / 8 Sitze) ist
    szenariodefiniert und haengt an keiner externen Massenannahme -
    deshalb die Pflicht-Begleitung zur Massenvariante.

## Kaltstart: quantifizierte Untergrenze (gerechnet 2026-07-31)

Kaltstart ist NICHT modelliert. Der Fehlbetrag ist gerechnet, nicht
geschaetzt, nach Kap. 1.A.3.b.i-iv Gl. (10) in der Euro-6+-Fassung mit
beta-Reduktionsfaktor:

    E_COLD = beta * bc * km * e_HOT * (Q - 1)

    beta  Tab. 3-39   0.6474 - 0.02545*ltrip - (0.00974 - 0.000385*ltrip)*ta
    bc    Tab. 3-46   CO 0.2022-0.0064*ltrip / NOx 0.1719-0.0055*ltrip
                      / VOC 0.2398-0.0076*ltrip; sonst 1.0
    Q     Appendix 4 COLD_EMISSIONS_PARAMETERS, Euro 7 Diesel LCV,
          RANGE 1 (ta > 0), N1-II == N1-III, Q = A*v + B*ta + C, Boden 1:
          NOx 0.04806*v + 14.6608 | CO 0.16114*v + 27.3472
          VOC -0.28614*v + 18.4451 | EC (Energie) 1.34 - 0.008*ta

METHODISCHER TRANSFER, ausgewiesen: `beta` ist ein Anteil an der
GESAMTfahrleistung, kalibriert fuer ltrip in [8, 15] km (Default 12.4 km).
Unsere Touren sind ~99 km lang, dort ist die Formel nicht auswertbar (sie
wird negativ). Uebertragbar ist die KALTDISTANZ je Start,
beta(ltrip)*ltrip, und die ist ueber das gueltige Band stabil:
3.02 / 3.50 / 3.39 km bei ltrip 8 / 12.4 / 15 km (ta = 10 C). Angesetzt
wird EIN Kaltstart je Tour bzw. je Fahrzeugtag, also
beta_eigen = Kaltdistanz / Entity-km.

Ergebnis auf `base10c` (63 Touren, 6252 km; DRT 120 Fahrzeugtage, 47953 km):

| Arm | ta = 10 C | ta = 0 C | Bandbreite ltrip 8-15 km (10 C) |
|---|---|---|---|
| Fracht NOx | **+5.63 %** | +6.62 % | 4.71-5.99 % |
| Fracht CO | +13.94 % | +16.39 % | — |
| Fracht VOC | +3.61 % | +4.25 % | — |
| Fracht Energie/CO2 | +0.93 % | +1.43 % | — |
| DRT NOx | +1.41 % | +1.66 % | — |
| DRT Energie/CO2 | +0.23 % | +0.35 % | — |

ENTSCHEIDUNG: die Regel dieses Plans (>= 5 % -> implementieren) greift fuer
NOx auf der Frachtseite. Der Zuschlag ist als Backlog-Punkt unter dem
Nachhaltigkeits-`[H]` angelegt. Bis dahin gilt:

- Alle berichteten NOx-Zahlen sind eine **UNTERGRENZE**, ca. 5-6 % zu
  niedrig auf der Frachtseite, ca. 1.4 % je Kaltstart auf der DRT-Seite.
  Die Abweichung ist EINSEITIG.
- CO2 und Energie sind praktisch unberuehrt (< 1.5 %), also unter dem
  jsprit-Rauschboden (~6.5 %) - die Kernaussagen des Papers haengen nicht
  daran.
- PM-Auspuff hat fuer Euro 7 KEINE Kaltstart-Parametrisierung im Sheet
  (Euro 5+ nutzt laut Kap. 1.A.3.b.i-iv eine eigene Gleichung mit
  absolutem Kaltfaktor). Irrelevant fuer unsere Bilanz: Auspuff-PM ist
  0.89 g gegen 316.6 g Abrieb im selben Lauf.
- Die DRT-Zahl ist **je Kaltstart** zu lesen und skaliert linear. Ein
  Fahrzeugtag enthaelt lange STAY-Phasen; ob und wie oft der Motor darin
  thermisch auskuehlt, ist ohne Thermomodell nicht entscheidbar. Bei 5
  echten Kaltstarts je Fahrzeugtag laege die DRT-Seite bei ~7 % NOx, also
  in derselben Groessenordnung wie die Frachtseite.

## Limitations (Paper-Rohtext)

- ZULADUNG ist nicht modelliert, und das ist methodenkonform: EMEP/EEA
  loest Last nur fuer schwere Nutzfahrzeuge auf (Default-Lastfaktor 50 %,
  Kap. 1.A.3.b.i-iv S. 62 f.; Kap. 1.A.3.b.vi Gl. 4/7 nennt LCF nur fuer
  Trucks/Busse/Coaches); fuer LCV ist Last kein Methodenparameter und kein
  Referenz-Ladezustand dokumentiert. Der Fahrzeugmasse-Effekt wird ueber
  die Segmentklasse N1-II/N1-III abgebildet - dort legt EMEP ihn hin. BOUND
  des nicht modellierten Lasteffekts, aus der HDV-Parametrisierung
  (Rigid <= 7.5 t, 30 km/h, 0 % Steigung: 4.387 leer -> 4.954 MJ/km voll):
  <= 13 % fuer einen 7.5-Tonner, ~5 % fuer unsere Vans bei ~575 kg
  Zuladung auf ~2100 kg Leergewicht. Kleiner als der Segmenteffekt (43 %).
- Bezugsmasse je Fahrzeugtyp ist eine ausgewiesene Annahme (~1700 / ~2000 /
  ~2400 kg fuer size_s/m/l); die N1-Segmentgrenzen (<= 1305 / <= 1760 /
  > 1760 kg) stammen aus der EU-Typgenehmigung, NICHT aus dem
  Guidebook-Kapitel.
- Kategoriensubstitution M2 -> N1-III fuer die DRT-Flotte (capacity 10,
  also M2 nach Guidebook Tab. 2-1). Einordnung bei 30 km/h: PC
  Large-SUV-Exec 2.545 / N1-III 3.123 / Buses Urban Midi <= 15 t bei
  Default-Last 9.1 MJ/km - N1-III ist die technische Entsprechung.
- Fahrzeugmix ist ENDOGEN (jsprit waehlt bei FleetSize.INFINITE frei aus
  den angebotenen Van-Typen, LmdCarrierBuilder). Er schwankt erheblich
  zwischen Laeufen (base10c 92.6 % der km auf size_s; localdepots_stagger
  100 % size_m). CO2-Deltas zwischen Szenarien sind daher immer zusammen
  mit `segment_km_share_*` zu lesen - sonst ist Mixverschiebung nicht von
  Fahrleistungsaenderung zu trennen. Dieser KPI ist bewusst NUR ueber die
  konventionelle Van-Flotte gebildet und fehlt auf Pax-only- und
  1d-Laeufen: DRT und modularer Arm tragen beide die feste Ersetzung
  N1-III, ihr Anteil ist konstruktionsbedingt 1.0 und damit keine Aussage
  (mitgerechnet ergab er 0.107 statt 0.926 fuer denselben LMD-Plan).
  Nebenwirkung des endogenen Mixes: die Kosten von ct_cep_size_s sind
  selbst interpoliert (lmd-vehicle-types.xml), sie beeinflussen die
  Fahrzeugwahl und damit indirekt das CO2-Ergebnis.
- Tier-3-Kurven auf Trip-/Tour-Mittelgeschwindigkeit angewandt (COPERT-
  Intention), nicht auf Link-Ebene; Stop&Go-Differenzierung unterhalb der
  Kurvenaufloesung entfaellt (laendlicher Raum: unkritisch fuer Deltas).
  Gemessen liegen alle Arme im selben Band: Fracht 36.4-37.1, Pax
  37.5-37.7 km/h - kein Arm-Effekt, den die Kurven aufloesen wuerden.
- Kaltstart nicht modelliert. Kein Schaetzwert, sondern gerechnet: NOx
  **+5.6 %** (Fracht, ta = 10 C; Band 4.7-6.0 %), CO2/Energie < 1 %, DRT
  +1.4 % je Kaltstart. Die NOx-Zahlen des Papers sind damit eine
  UNTERGRENZE. Herleitung, Formeln und Sensitivitaeten: Abschnitt
  "Kaltstart" oben. Implementierung steht im Backlog.
- Idle an Servicestopps: Engine-off-Annahme (Auslieferung/Boarding).
- Euro-7-Faktoren aus Grenzwerten projiziert (Norm ab ~2026/27).
- km-Kanal traegt jsprit-Heuristik-Rauschen (~6.5 % Boden, Seed-Messung
  2026-07-28) -> Paper-Zahlen als Mittel + Min/Max ueber >= 10 Runs. Der
  Lasteffekt-Bound (~5 %) liegt UNTER diesem Rauschboden, der
  Kaltstart-Bound fuer NOx knapp darunter.
- 1d-Zurechnung: `drt_vehicle_km` traegt keinen Freight/Pax-Kanal
  (METHODS-LOG 2.14, "not corrected"). Der Emissionskanal loest das fuer
  seine eigenen Zahlen: der regimebasierte Split ueber die
  MODULAR_FREIGHT_DRIVE-Fenster ist restfrei, `drt_* + freight_modular_*
  == total_*` gilt exakt (gemessen ueber alle Schadstoffe auf m1d050).
  `total_*` bleibt die allokationsfreie Groesse. Pax-Zuladung waere
  ohnehin ~1.2 % (mean_pax_aboard 1.6 -> ~128 kg), also unter dem
  Rauschboden.
- 1c-Zurechnung: die Masse-Basis (EN 16258 / GLEC) und die Slot-Basis
  liefern fuer dasselbe Paket 20 g bzw. 531 g CO2e - Faktor 26, und das
  Vorzeichen der Kernaussage gegenueber unserer eigenen konventionellen
  Referenz (221 g) kippt mit der Wahl. Beide Anteile werden deshalb IMMER
  als Paar berichtet, und `total_*` bleibt die unbestrittene Groesse.
  Details, Mitfahrer-Abhaengigkeit und externe Einordnung (Bienzeisler et
  al. 2026, laendlich 188/239 g): METHODS-LOG 2.26. Die Zeilen entstehen
  nur, wenn eine Paket-kg*km-Basis existiert - im 1d-Arm fahren Pakete als
  Kapsel, dort waere ein Anteil von 0 % die Behauptung, die Fracht sei
  emissionsfrei.
- BEV-Arm: Elektrifizierung nur auf Emissionsebene (keine Reichweiten-/
  Ladezeitrestriktion in der Sim); Reichweite als SWEEP ueber 150/200/
  250 km, nicht als Pass/Fail. Befund ueber die Laeufe mit Freight-Touren:
  laengste Tour 183 km, bei 250 km 0 % Ueberschreitung in jedem Lauf, bei
  150 km 0-13.4 %. Freight-Elektrifizierung ist hier nicht
  reichweitenbegrenzt.
  ACHTUNG, die DRT-Zeilen sind KEINE vergleichbare Groesse: eine Tour ist
  eine zusammenhaengende Schicht, ein DRT-Fahrzeugtag nicht - er enthaelt
  lange STAY-Phasen, in denen geladen werden kann. Auf base10c stehen
  3.2 % (Fracht, je Tour) neben 96.7 % (DRT, je Fahrzeugtag); daraus
  "DRT ist nicht elektrifizierbar" zu lesen waere falsch. Jede Flotte
  traegt ihre Einheit-Definition in der eigenen Provenance-Spalte. Die
  belastbare Groesse waere der laengste Fahrblock zwischen zwei
  ausreichend langen STAY-Phasen (Ladefenster-Analyse, Backlog).
- Netzintensitaet Strom ist ein ausgewiesener Sensitivitaetsparameter
  (emep_supplement.csv). Die BEV-Abrieb-Multiplikatoren sind KEINE freie
  Annahme, sondern die guidebook-eigenen ICE->BEV-Verhaeltnisse des
  Medium-Pkw (Reifen 1.0841 / Bremse 0.2113 / Strasse 1.1267) - ein
  deklarierter Kategorientransfer, weil die Quelle keine BEV-Zeile fuer
  LCV hat.
- Non-Exhaust-Abrieb IST segmentdifferenziert (Kap. 1.A.3.b.vi-vii loest
  LCV nach N1-Segment auf; Reifen/Strasse gruppieren N1-II+III, die Bremse
  trennt alle drei). Die frueher notierte "bewusste Asymmetrie zur
  Auspuffseite" war eine Annahme ueber ein damals nicht vorliegendes
  Kapitel und ist zurueckgezogen.
- REIFEN-PM10 IST EINE OBERGRENZE, KEINE SCHAETZUNG. Das Guidebook
  kennzeichnet die eigenen Reifenwerte als ueberschaetzt: der
  luftgetragene PM10-Anteil liege nach neueren Messungen "well below 3 %"
  statt der angesetzten 60 % (Saladin et al. 2024; Huber et al. 2024;
  Giechaskiel et al. 2024a), eine Revision der Tabelle sei zum
  Redaktionsstand nicht moeglich. Wir rechnen methodentreu mit dem
  dokumentierten Wert 0.600 UND berichten den Vorbehalt: Reifen-PM10
  14.1 mg/km (24 % des Abriebs) bei N1-III/30 km/h waere bei 3 % nur
  ~0.7 mg/km, der Gesamtabrieb fiele von 59.1 auf 45.7 mg/km (-23 %). Die
  Abweichung ist EINSEITIG (wahrer Wert darunter, nie darueber) und trifft
  Diesel und BEV gleich, die Richtungsaussagen bleiben also gueltig - der
  BEV-Vorteil wuerde sogar groesser, weil sein Nachteil genau am
  ueberschaetzten Term haengt.
- KEIN Wert des Non-Exhaust-Kapitels hat Qualitaetscode A: Reifen- und
  Bremsbasen sind Code B, der Strassenabrieb C-D ("highly uncertain") bei
  10.5 mg/km = 18 % des Abriebs. Das ist der Rahmen aller PM-Aussagen.
- Non-Exhaust dominiert die PM-Bilanz: Auspuff-PM eines Euro-7-Diesels mit
  DPF ist 0.142 mg/km, der Abrieb 59.1 mg/km - Faktor ~416. Und die
  Elektrifizierung halbiert ihn nicht: BEV behaelt 34.4 mg/km = 58 %, weil
  Reifen und Strasse mit der Fahrzeugmasse STEIGEN und nur die Bremse
  faellt.
