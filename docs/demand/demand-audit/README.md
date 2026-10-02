# HAGRID Demand: Audit und Neuentwurf

**Aktueller Umsetzungseinstieg:** [HAGRID-Roadmap mit drei Teilplänen und Terra-Reviews](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-roadmap.md). Die zugehörige [Spezifikation](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/specs/2026-09-15-hagrid-baseline-design.md) enthält den konfigurierbaren Einfluss von LSP 2021, tägliche Ortsvariation, Monte Carlo und globale Sensitivitätsanalysen.

Aktualisierung: 15. September 2026. Einstieg ist die [Prüfung der HAGRID-Baseline-Planung](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/BASELINE_PLAN_REVIEW_20260915.md), mit [frischem Zahlen- und Quellenabgleich](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/plan_verification_20260915.json). Nach Nutzerentscheidung wird zuerst der HAGRID-Grundgedanke als verbesserte Python-Baseline umgesetzt. Die bisherige gemeinsame Modellschätzung und ihre Erweiterungen gehören zum experimentellen Bestand; die Code-Trennung steht noch aus.

Der folgende Dokumentenbestand und Auditbericht stammt aus der Untersuchung vom 9. September 2026 einschließlich des damals vorhandenen Batch-Arbeitsstands. Der [frühere Gesamtentwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md) beschreibt die experimentelle Entwicklungsrichtung und wird durch die aktuelle Baseline-Planungsprüfung eingeordnet.

| Dokument | Inhalt |
|---|---|
| [Ausführbares Python-Datenfundament](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/README.md) | Installation, Startbefehl, Standortaufbereitung, Beobachtungskandidaten und Run-Bericht |
| [Gesamtentwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md) | Zielgrößen, Modellstruktur, feste Anforderungen, offene Modellwahl, Python-Stages und Umsetzungslieferungen |
| [Datenkatalog](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/DATA_CATALOG.md) | HAGRID- und PANDA-Quellen, Verfügbarkeit, Verwendung, Definitionslücken und externe Ergänzungen |
| [Evaluationsplan](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/EVALUATION_PLAN.md) | Vergleichskandidaten, unabhängige Splits, Gütemaße, Modellentscheidung und Abnahme |
| [Nachfrage ohne Raster](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/GRID_FREE_METHOD.md) | Standortverzeichnis, Datenaufbereitung, Beobachtungszuordnung, Lieferziele und Validierung; TAZ vorerst ausgeklammert |
| [Detailliertes Audit](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/AUDIT.md) | Notebook-Übersicht, tatsächliche Inputs, priorisierte Fehler, Modellkritik und Prüfgrenzen |
| [Früherer Modell- und Projektentwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/DESIGN.md) | Detailideen und erste Architektur; Modellpräferenzen durch Gesamtentwurf eingeordnet |
| [PANDA + HAGRID: gemeinsames Hybridmodell](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/PANDA_HAGRID_CONCEPT.md) | Präzisierung nach PANDA-Review: Bottom-up/Top-down, LSP-Nutzung, differenzierte Anbieterprofile und synthetischer Nachweis |
| [LSP-Modell: Kandidatenherleitung](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/HAGRID_LSP_REFINEMENT.md) | Konsistente B2B/B2C-Mengenzerlegung als zu prüfender Modellbaustein |
| [Prüfergebnisse](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/verification.json) | Aggregierte Datenprofile, zwölf reproduzierte Fehlerfälle und Notebook-Hashes |
| [Prüfskript](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/verify_findings.py) | Wiederholbare Prüfung ohne Ausführung der gesamten Notebook-Pipeline |

## Die wichtigsten Ergebnisse

1. **Ein frischer Gesamtlauf ist gebrochen.** Notebook 04 liest englische Exportspalten aus Notebook 01, erwartet aber deutsche Namen; außerdem kopiert es den DataFrame ohne die zuvor berechnete B2B-Normalisierung.
2. **Die räumliche Abdeckung ist durch den alten Nachfragebestand begrenzt.** Von 15.917 Rasterzellen haben 8.531 null alte Paketmenge. Darunter befinden sich 2.625 Zellen mit Einwohnern oder Firmen. Der Filter `total_coun > 0` schließt sie aus.
3. **Die Segmentgewichte zählen falsch.** Ein leerer Left Spatial Join wird als ein Treffer gezählt. Überlappende Puffer können dieselbe Person oder Firma mehrfach berücksichtigen. Ein LSP-Boost ignoriert seinen Schwellwert und wächst mit der Anzahl der Teilsegmente.
4. **Mehrere Verarbeitungsschritte verletzen Mengenbilanzen.** Unabhängiges Runden kann Pakete verlieren; B2B-Umschichtungen können die B2B-Gesamtzahl verändern; ein späterer Auffüllschritt erzeugt zusätzliche Nachfrage.
5. **Prognose und Unsicherheit sind nicht sauber getrennt.** Eingeschriebene Schätzwerte 2024–2028 werden als beobachtet bezeichnet. Die linearen Konfidenzbänder werden falsch berechnet. Wochenprofile und Jahresmengen sind nicht exakt abgestimmt.
6. **Der bessere Input ist teilweise schon da.** 52.931 Firmenstandorte enthalten Branche und Beschäftigtenzahl. 1.150.862 Personendatensätze enthalten Gebäudeinformationen. Haushaltsinformationen sind allerdings bei 536.496 Personen unvollständig.

Der gemeinsame Rahmen verbindet Standortstruktur, reale Anbieterbeobachtungen und dokumentierte Marktinformationen. Die konkrete Nachfrage- und Anbieterformel wird im Modellvergleich ausgewählt. Das implementierte Quellenmanifest und Standortverzeichnis liefern räumliche Beobachtungskandidaten; deren fachliche Bestätigung ist vor der Kalibrierung erforderlich.

## Was hier umgesetzt wurde

Erstellt wurden Analyse, Modell- und Projektentwurf, lesbare Quelltext-Snapshots der 18 Notebooks und ein ausführbares Audit-Skript. Zusätzlich sind Datenfundament, gemeinsame Nachfrage-/Anbieterschätzung, Modellvergleich, eingefrorene Prognose, Schwankungen und Liefer-/GIS-Exporte implementiert. **Die Kalibrierung ist vorläufig; Datenannahmen und noch nicht belegte Güte sind im [Umsetzungsbericht](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/MODEL_WORKFLOW.md) dokumentiert.** Bestehende Notebooks und Nachfrageoutputs wurden nicht verändert.

Die zwölf Prüfungen reproduzieren unerwünschtes Verhalten oder gebrochene Datenverträge. Sie sind keine Bestätigung, dass die alte Pipeline korrekt ist. Ein vollständiger End-to-End-Lauf wurde nicht durchgeführt; insbesondere wurden die genetischen Optimierungen und CVXPY-Lösungen nicht vollständig neu gerechnet.

Aus dem Repository-Stamm mit einer Umgebung, die Pandas, NumPy, GeoPandas, Shapely und scikit-learn enthält:

```powershell
python docs/demand-audit/verify_findings.py
```

Das Skript liest ausgewählte Funktionsdefinitionen direkt aus den Notebooks, prüft kleine Gegenbeispiele und profiliert vorhandene Daten. Es schreibt ausschließlich seinen aggregierten Prüfbericht. Einzelne Personen- und Firmendatensätze werden nicht in die Dokumentation übernommen.
