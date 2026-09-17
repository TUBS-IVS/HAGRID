# Checkliste Emissionskanal — offene Punkte

> Arbeitsdokument für das Arbeitspaket „Emissionen / BEV / SOS". Gehört **nicht** in den
> BACKLOG (der bleibt kurz) und **nicht** ins METHODS-LOG (dort landet nur, was entschieden
> und paper-tragend ist). Das Ergebnis jedes erledigten Punkts wandert nach METHODS-LOG oder
> BACKLOG-DONE, der Punkt wird hier dann **gelöscht**, nicht abgehakt stehengelassen.

**Legende:** `[ ]` offen · `[~]` in Arbeit · `[x]` fertig, Ergebnis noch nicht ausgelagert
**Stand:** 2026-09-10 — A-1b / A-2 / A-3 / B-3 erledigt und entfernt, Nebenverbraucher eingerechnet, alle drei Arme durch denselben Extraktor gemessen (Baseline 3 Seeds, 1c 5 Seeds, 1d n=1) → METHODS-LOG §2.55, §2.56, §2.63, §2.67, §3.12.
⚠️ **Der Engpass ist kein Emissionspunkt mehr, sondern der 1d-Seed-Fächer** — das Drei-Arm-Delta liegt unter dem Seed-Rauschen (§2.67, BACKLOG).

---

## 0 · Festgelegte Eingangsgrößen

Fahrzeugkandidaten (User 2026-09-01), nutzbare Kapazität:

| Kandidat | nutzbar | Herstellerreichweite | impliziert Wh/km | Rolle |
|---|---:|---:|---:|---|
| Mercedes eSprinter | 113 kWh | 440 km (ADAC) | 257 | oberer Anker |
| VW ID.Buzz, 6-Sitzer | 86 kWh | — | — | mittlerer Anker |
| Ford Tourneo Tourer | 70 kWh | 370 km (WLTP) | 189 | unterer Anker |

Gemessener Modellverbrauch BEV N1-III am Betriebspunkt der Läufe: **247,9 Wh/km** (1d),
247,8 (Baseline). Der eSprinter-Datenblattwert liegt 3,5 % daneben — externe Gegenprobe des
EC-Kanals, siehe A-3.

Reichweite bei **unserem** Verbrauch inkl. Nebenverbraucher (285,1 Wh/km = 247,9 Traktion × 1,15): 113 kWh → 396,4 km · 86 kWh → 301,7 km · 70 kWh → 245,5 km.

Bedarfsanker ohne Zwischenladung, inkl. Nebenverbraucher (kWh je Fahrzeugtag): 1d Median
107,9 / p95 144,2 / max 154,9 · Baseline 123,1 / 150,8 / 164,9 · 1c 109,9 / 134,7 / 148,0
(5 Seeds).

---

## A · Sofort, ohne neue Läufe

### `[ ]` A-4 Winterfall Diesel (RANGE 2)

- `emep_cold_factors.csv` enthält RANGE 2 für −10…0 °C, aber `ambient_temp_c` = 10 aktiviert
  nur RANGE 1. Vorhandener, ungenutzter Kanal. Vorschlag −5 °C neben 10 °C.
- **Die BEV-Seite ist erledigt** (2026-09-08): Nebenverbraucher als Einzelwert 0,15
  eingerechnet, nicht als Sweep — METHODS-LOG §2.63. Bei −5 °C wäre der Anteil höher; das
  ist der einzige Grund, diesen Punkt noch zu machen.
- **Fertig wenn:** beide Temperaturen gerechnet, Gueltigkeitsbereich der Kaltzeilen
  (v ≤ 45 km/h) geprüft.
- **Aufwand:** ~3 h. Optional, wenn A-5 den Effekt deklariert.


### `[ ]` A-5 Limitations-Absatz mit Zahlen

**Trägt die ganze Vereinfachung.** Entscheidung User 2026-09-03: das Modell bleibt einfach,
nicht modellierte Effekte werden **mit ihrer gemessenen Größe deklariert** statt eingebaut.
Zulässig nur, wenn jede Auslassung ihre Zahl trägt:

| Nicht modelliert | Wirkung | Richtung |
|---|---|---|
| BEV-Warmlauf (erste km) | +0,5 bis +1,9 % BEV | zugunsten BEV |
| Winterfall Diesel (RANGE 2) | NOx-Kaltanteil steigt | zugunsten Diesel |
| Fracht-Kaltstarts (1 je Tour) | Fracht-NOx ist Untergrenze | zugunsten Diesel |
| Fahrzeug-/Batterie-LCA | — | offen |
| Idle an Servicestopps | — | zugunsten Diesel |

**Was jetzt DOCH modelliert ist** und deshalb als Annahme (nicht als Auslassung) in den Absatz
gehört: BEV-Nebenverbraucher 15 % (§2.63), Ladeverluste 7 % (§2.55), Netzintensität als
Dreipunkt-Sweep (§2.55). Die beiden BEV-Aufschläge wirken **gegen** das BEV, der Sweep spannt
−50 bis −94 %.

⚠️ Auslassungen und Annahmen gehören **zusammen** in einen Absatz, nicht über den Text
verteilt — einzeln gelesen wirkt jede harmloser als sie ist.
- **Fertig wenn:** Absatz in `analysis/lausitz/kpi/data/README.md` („Limitations (Paper-Rohtext)")
  ergänzt, Verweis auf METHODS-LOG §2.55.
- **Aufwand:** ~1 h.

---

## B · Lademodell Stufe A (SoC-Nachlauf, kein Sim-Eingriff)

### `[ ]` B-1 Ladeleistung und Ladeort festlegen

- **Daten fehlen:** Ladepunkttypen (Vorschlag Depot-AC 11 / 22 kW, DC 50 / 150 kW als Sweep)
  und die **Ladeort-Politik** — nur die 7 Depots aus
  `hagrid/input/lausitz/hubs/lmd-depots.csv`, oder zusätzlich häufige STAY-Links?
- **Warum:** das ist die Szenarioentscheidung, die „Infrastrukturproblem" von
  „Dispositionsproblem" trennt. Keine Datenfrage.
- **Fertig wenn:** Entscheidung im Spec, Werte in `emep_supplement.csv` mit
  `SETTING`-Begründung.

### `[ ]` B-2 SoC-Replay implementieren

- **Vorhanden:** STAY-Ort (`dvrpTaskStarted` trägt `link`), STAY-Dauer, Fahrblöcke,
  geschwindigkeitsabhängiger BEV-Verbrauch, Depotkoordinaten, Gleichzeitigkeit aus
  STAY-Überlappung ableitbar. **Kein neuer Sim-Lauf nötig.**
- **Fehlt:** nur B-1 plus die Kapazitäten aus Abschnitt 0.
- **Ausgabe:** Anteil Fahrzeuge unter SoC-Schwelle · benötigte Ladepunkte je Depot ·
  benötigte Leistung · Zeitpunkt des ersten Ausfalls je Fahrzeug.
- **Fertig wenn:** die Aussage lautet „bei X kWh und Y kW an den 7 Depots fallen Z % der
  Fahrzeugtage aus" statt „der längste Fahrblock ist größer als eine angenommene Reichweite".
- **Aufwand:** 2–3 d.
- **Anker zum Gegenrechnen:** Idle 11,3 h/Fahrzeugtag (3.240 − 2.178,9 + 460,1 = 1.521 h auf
  135 Fahrzeuge). Bei 11 kW = 124 kWh, bei 22 kW = 248 kWh. Aggregiert reicht die Zeit — die
  Bindung muss also räumlich/zeitlich sein, nicht energetisch. Sagt das Modell etwas anderes,
  ist erst das Modell verdächtig.

---

## C · SOS-Layer (Planetary Boundaries)

### `[ ]` C-1 Grenzbudget beschaffen

- **Daten fehlen:** globales Jahresbudget der Climate Boundary, zitierfähig. Literatur steht
  in METHODS-LOG §4.4 (Richardson et al. 2023, Bjørn & Hauschild, Ryberg et al.), die **Zahl**
  ist nicht im Modell.

### `[ ]` C-2 Allokationsprinzip als BAND festlegen

- **Daten fehlen:** mindestens drei Prinzipien (pro Kopf / Grandfathering / Wertschöpfung).
- ⚠️ **Kein Einzelwert.** Die Allokationswahl verschiebt das Ergebnis um Größenordnungen
  (§4.4). Ein einzelner Wert wäre derselbe Fehler wie das verworfene 250-km-Einzelgate.

### `[ ]` C-3 Tagestyp-Hochrechnung entscheiden

- **Was:** der Lauf ist **ein** Werktag (13.05.2025). ×365 wäre falsch.
- **Daten fehlen:** Werktag/Wochenende-Faktor für Pax und Paketaufkommen, oder die
  Entscheidung, alles als „Werktagsäquivalent" zu etikettieren.

### `[ ]` C-4 Layer rechnen

- **Vorhanden:** 100-%-Stichprobe (`flowCapacityFactor` = `storageCapacityFactor` = 1,0,
  geprüft — **keine Hochskalierung nötig**) · 41.874 Personen im Szenario · absolute
  Tageswerte je Arm · fertiger BEV-Arm · dokumentierte WTW-Systemgrenze (§1.4).
- **Bilanzseite steht bereits:** 0,342 kg CO₂e/Person/Tag (Baseline) gegen 0,329 (1d).
- **Warum notwendig:** die −3,8 % sind ohne absoluten Maßstab nicht interpretierbar — und
  nach der Zerlegung vom 2026-08-28 überwiegend ein Konsolidierungs-, kein Modularitätseffekt.
  Der SOS-Layer ist das Einzige, was daraus eine Nachhaltigkeits- statt einer
  Betriebsorganisationsaussage macht.
- **Aufwand:** 3–5 d, davon fast alles Methodik.

---

## D · Kleinere offene Punkte

- `[ ]` **D-1** `_l`-Van einmal als HDT „Rigid ≤ 7,5 t" gegenrechnen (ausgewiesene Bandbreite
  statt versteckter Annahme).
- `[ ]` **D-2** Midi-Bus als Alternativsubstitution für die DRT-Flotte gegen die heutige
  N1-III-Ersetzung.
- `[ ]` **D-3** Freight-Kaltstartzahl: der konventionelle Arm zählt nur **1 Start je Tour**
  (Datenlücke in `TimeDistance_perVehicle.tsv`), die 5,34 % NOx sind eine Untergrenze.
- `[ ]` **D-4** Weitere Schadstoffe als KPI freischalten: CO, VOC, CH4, N2O, SPN23 und die
  drei PM10-Einzelkanäle werden **gerechnet**, stehen aber nur in
  `kpi_emissions_vehicles.csv`. Je Schadstoff eine Zeile in `_KPI_METRICS`.
  ↓ **Abgewertet 2026-09-10 (§2.67):** die Intensität ist über alle Arme flach
  (CO₂e 0,07 % Streuung, NOₓ 0,5 %, PM10 0,2 %). Weitere Schadstoffe erzeugen Spalten, keine
  Befunde — sie sind alle dieselbe Fahrleistung mit einem anderen Faktor. Nur machen, wenn ein
  Reviewer sie sehen will.
- `[ ]` **D-5** Stem-/Deadhead-Kennzahl für den **konventionellen** Frachtarm. Heute gibt es
  `deadhead_km_planned` nur modular (1d: 47,7 % der Fracht-km); die Baseline hat keinen
  Vergleichswert, deshalb ist „1d fährt je Stopp mehr Anfahrt" nur einseitig belegt.
- `[ ]` **D-6** Multi-Seed-Aggregation für den Emissionskanal (→ `[H]` Multi-Run-Aggregation).
  Stand 2026-09-10: Baseline (3) und 1c (5) sind **von Hand** über Seeds aggregiert (§2.67), 1d
  hat noch keinen Fächer. Sobald er läuft, lohnt das Werkzeug — vorher nicht.

---

## E · Erledigt, noch nicht ausgelagert

_(leer)_

---

## Reihenfolge

**Jetzt:** A-5 (Limitations-Absatz — User macht das selbst)

**Danach, eigene Arbeitspakete:** B-1 → B-2 (Lademodell) → C-1…C-4 (SOS) → A-4 → D

Begründung: A-5 trägt die Gegenleistung dafür, dass das Modell einfach bleiben darf —
ohne den Absatz sind die deklarierten Auslassungen nur mündlich. B kommt vor C, weil der
SOS-Layer auf einem belastbaren BEV-Arm aufsetzen sollte. A-4 ist nach A-5 nur noch
optional — dort ist der Effekt dann deklariert.
