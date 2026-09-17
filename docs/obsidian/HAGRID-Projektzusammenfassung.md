---
title: HAGRID — Projektzusammenfassung
aliases: [HAGRID, Lausitz DRT-Freight-Studie]
tags: [projekt, dissertation, verkehrssimulation, logistik]
created: 2026-07-13
status: aktiv
---

# HAGRID — Integrierte Personen- und Paketlogistik-Simulation

**HAGRID** ist das zentrale Forschungs-Repository (TU Braunschweig, Hendrik Bimmermann) zur Simulation von Paketlogistik in agentenbasierten Verkehrsmodellen. Es umfasst zwei Stränge:

1. **Paketnachfrage-Generator (Hannover, 2014–2050):** projiziert und verortet tägliche Paketmengen auf ~50-m-Straßensegmente, aufgeschlüsselt nach [[CEP-Dienstleister]]n (DHL, Hermes, UPS, DPD, GLS, FedEx, Amazon) und [[B2B-B2C-Segmentierung]].
2. **Lausitz-Studie (aktueller Kern):** Vergleich integrierter Personen+Güter-Verkehrskonzepte gegen eine Baseline in **einem** [[MATSim]]-Setup, Untersuchungsraum [[Hoyerswerda]] ([[Lausitz]]).

Die externe Paketnachfrage kommt aus dem Schwesterprojekt [[PANDA]] (Parcel Demand Analyzer), das das Hannover-gefittete [[Paketmengenmodell]] auf die Lausitz überträgt.

## Forschungsfrage

Kann die Integration von Personenbeförderung ([[DRT]]) und Paketzustellung ([[LMD]]) im ländlichen Raum Effizienzgewinne bringen — gegenüber getrennten Systemen? Untersucht werden drei Szenarien in identischem Setting:

| Szenario | Konzept |
|---|---|
| **Baseline** | Multi-LSP [[DRT]] (Personen) + separate dedizierte [[LMD]]-Lieferwagen |
| **Shared-Use** | [[Cargo-Hitching]]: Minibusse mit 2D-Kapazität (Sitze + Paketslots), Online-[[DVRP]]-Insertion, **kein** [[jsprit]] auf der Paketseite |
| **Modular** | [[U-Shift]]-Kapselwechsel: Offline-[[jsprit]]-Freight + Pax-Prioritäts-Dispatch mit Idle-Schwelle |

Orthogonal dazu: ein **[[Autonomie-Schalter]]** (Fahrerkosten aus, Roboter-Dwell länger, Tempolimit, Autobahn-Ausschluss), der beide integrierten Szenarien zwischen „begleitet heute" und „vollautonom" aufspannt.

## Architektur

- [[HAGRID]] ist das **Frontend** (Parameter → Preprocessing → [[MATSim]]-Trigger → Dashboard); [[matsim-lausitz]] ist Maven-Dependency, native DRT-Konfiguration wird **komponiert, nicht reimplementiert** (Paket `hagrid.lausitz`).
- **100-%-Stichprobe ist nicht verhandelbar** — Subsampling verzerrt die [[jsprit]]-Tourgeometrie (Paketstopp-Dichte). Rechenlast wird über die Gebietsgröße gesteuert; Full-[[DVRP]] ist der große Laufzeithebel.
- **Regionalbahn bleibt, Bus entfällt** in allen drei Szenarien → [[DRT]] als sauberer [[Zubringer-zum-Schienenverkehr]] und Bus-Ersatz; intermodales Routing via [[SwissRailRaptor]].
- 7 reale, OSM-geocodierte **Depots** (gemeinsam für DRT-Spawn und LMD): [[Depot-Dispatching]] = Spawn an Depots, nachfragebasiertes MinCostFlow-[[Rebalancing]], kapazitätsbegrenzte Rückkehr.
- DRT wird wie ÖPNV bepreist (natives PtAndDrtFareModule, VVO-Tarif) — „DRT = ÖPNV-Produkt zum ÖPNV-Tarif".

## Untersuchungsraum & Nachfrage

- Bediengebiet ≈ **206,8 km²** um [[Hoyerswerda]] (das abgetrennte Ruhland-Polygonfragment wurde entfernt — es war physisch unerreichbar; langfristig ist ein zusammenhängender Hoyerswerda↔Ruhland-Korridor angedacht, DRT explizit als Bahn-Feeder).
- **41.937 Personen-Agenten** (100 %, home-anchor-geclippt), **6.381 Pakete/Tag** (B2C 5.521 / B2B 860) über 7 [[CEP-Dienstleister]], 1.056 Nachfragesegmente aus [[PANDA]].

## Zentrale Ergebnisse (Stand Juli 2026)

- **„Heirat" vollzogen:** DRT_BASELINE simuliert Personen-DRT **und** LMD-Carrier in einem MATSim-Controler. Headline-Run (married120, Flotte 120): [[Modal Split|DRT-Anteil]] **6,07 %**, 9.171 Fahrten, Rejection ~0,26 %, Wartezeit Ø ~700 s; LMD 7 Carrier / 67 Vans / 81 % Auslastung. Die 67 Vans sind **Rauschen** für die DRT-KPIs — die Integration stört die Personenseite nicht.
- **Das System ist nachfrage-, nicht angebotsbegrenzt:** +50 % Flotte (80→120) kauft Servicequalität (Wartezeit −58 s, Rejections 113→26), aber kaum Nachfrage (+0,26 pp Modal-Anteil), während die Schichtauslastung von 62 % auf 47 % einbricht. Hoyerswerda hat zudem **keinen klassischen Morgenpeak** (Arbeitswege ~9 %, Maximum 14–16 Uhr).
- **LMD ist zeit-/dwell-gebunden, nicht kapazitätsgebunden:** 7-h-Tourdauergrenze + 2 min/Paket Zustellzeit deckeln Touren bei ~100–124 Paketen; ~62 % Kapazitätsauslastung ist realistisch.
- **Kostenmodell = Platzhalter** (25 €/Fahrzeug-Schichtstunde: 20 Arbeit + 5 Fahrzeug, nach Rudolph; Benchmark Currie & Fournier 2020: ~68 €/veh-h) — muss vor der Headline-Auswertung verfeinert werden. Der ~80-%-Arbeitskostenanteil ist der Hebel des [[Autonomie-Schalter]]s.

## KPI- & Dashboard-Infrastruktur

- Kanonische **Langformat-KPI-CSV** (`kpis_long.csv`, 9 Spalten, Schema eingefroren) aus dem Python-Paket `analysis/lausitz/kpi/` — MATSims eigene Analyse-CSVs sind autoritativ (Event-Rekonstruktion läuft ~3 % zu niedrig). Neue Daten = neue Dateien (`kpis_provider`, `kpi_iterations`, `kpi_distributions`, `kpi_timeseries`).
- Schlanke [[Chart.js]]-Dashboards (per Run + Szenario-Vergleich mit Tabs), Performance-Budgets als Akzeptanzkriterium (Einzel-Run < 1 MB vs. 26 MB Legacy-Plotly).
- **Run-Dashboard v2** (in Arbeit) holt den vollen Legacy-Analyseumfang zurück: Plan A (Daten-Extraktoren) ✅, Plan B (Java-Auto-Trigger nach Simulationsende), Plan C (Rendering: 22 DRT- + 20 LMD-Kacheln, Tabs, Tabellen), Plan D ([[Leaflet]]-Karten, vendored/offline, Usability-Gate statt Byte-Budget) — Pläne geschrieben, Ausführung steht aus.

## Status & nächste Schritte

- **Fertig** (Branch `hendrik`, nie ohne Freigabe nach master): Foundations, DRT-Baseline auf Realdaten, Schienen-Intermodalität, LMD-Baseline, Depot-Dispatching, LMD+DRT-Heirat, KPI-CSV-System (1e), Dashboard-v2-Datenlayer (Plan A).
- **Als Nächstes:** Dashboard-v2 Pläne B→C→D ausführen; [[Shared-Use]] (1c) — erfordert MATSim-Versionsbump auf 2025.0 (dort existiert erst `DvrpLoad` für 2D-Kapazität), Grilling-Pass auf dem 1c-Plan offen; danach [[Modular]] (1d), Autonomie-Folgeplan, einpendelnde Bahnpendler.
- **Methodik-Vorbehalt:** DRT-ASC unkalibriert (kein reales DRT in Hoyerswerda) → Modal-Shift als optimistische Obergrenze bzw. Sensitivitätsband berichten.

## Begriffsanker

- [[MATSim]] — agentenbasiertes Verkehrssimulationsframework (Multi-Agent Transport Simulation); Co-Evolutionärer Ansatz mit Scoring + Replanning über Iterationen.
- [[DRT]] — Demand-Responsive Transport: flexibler On-Demand-ÖPNV (hier: 8-Sitzer-Flotte mit Full-[[DVRP]]-Dispatch statt Teleportation).
- [[DVRP]] — Dynamic Vehicle Routing Problem; MATSim-Contrib für Echtzeit-Fahrzeugdisposition (Insertion, Rebalancing).
- [[LMD]] — Last-Mile Delivery: die letzte Zustellmeile der Paketlogistik; hier via MATSim Freight/Carriers-Contrib.
- [[jsprit]] — Open-Source-Tourenplanungs-Heuristik (Ruin-and-Recreate), routet die LMD-Carrier-Touren offline.
- [[CEP-Dienstleister]] — Kurier-, Express-, Paketdienste (DHL, Hermes, DPD, GLS, UPS, FedEx, Amazon).
- [[Cargo-Hitching]] — Mitnahme von Gütern in Personenverkehrsmitteln (Kern des Shared-Use-Szenarios).
- [[U-Shift]] — DLR-Konzept modularer Fahrzeuge mit wechselbaren Kapseln (Personen-/Güterkapsel); Basis des Modular-Szenarios.
- [[PANDA]] — Parcel Demand Analyzer: eigenständiges Repo, erzeugt die segmentfeine Paketnachfrage aus dem [[Paketmengenmodell]].
- [[SwissRailRaptor]] — MATSim-Router für ÖPNV inkl. intermodalem Access/Egress (Walk + DRT zur Bahn).
- [[Modal Split]] — Verteilung der Wege auf Verkehrsmittel; zentrale Zielgröße der Studie (DRT-Anteil ~6 %).
