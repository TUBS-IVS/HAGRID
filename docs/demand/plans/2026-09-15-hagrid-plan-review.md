# Review-Protokoll der HAGRID-Umsetzungsplanung

Stand: 15. September 2026. Geprüft wurden die fachliche Spezifikation, drei Implementierungspläne und relevante bestehende Python-/Notebook-Schnittstellen. Das ist eine Planfreigabe innerhalb des beschriebenen Umfangs, keine Freigabe bereits neu implementierten Produktivcodes.

Nachfolgende Nutzerergänzung: Die Dashboard-Ausgaben werden zu einem gemeinsamen Einstieg mit Run-Auswahl und Stage-Navigation zusammengeführt (Spec 5a, Aufgaben 4/8/11/12). Die nachstehenden ursprünglichen Terra-Reviews beziehen sich auf den davor konsolidierten Modell-/Implementierungsplan. Die Ergänzung wird bei der Umsetzung gezielt auf Stage-Wechsel, Analysezuordnung und konkurrierende Katalogupdates geprüft.

## Reviewer und Umfang

| Rolle | Agent/Modell | Prüfung |
|---|---|---|
| Methodischer Review | `method_review`, gpt-5.6-terra, high | Mengenbilanz, segmentweiser LSP-/Strukturblend, zeitliche Pfade, Monte Carlo, räumliche Mittelwerte, Sensitivitätsdesign |
| Technischer Review | `technical_review`, gpt-5.6-terra, high | Bestehende Module, neue Schnittstellen, Fixtures, Artefakt-Schemas, Zufall, Cache/Resume, Exporte und Agentenübergabe |
| Integration und Gegenprüfung | Hauptagent | Befunde gegen Quellen geprüft, Änderungen eingearbeitet, Abdeckung/Signaturen/Links und synthetische Rechenbeispiele geprüft |

Beide Terra-Agenten arbeiteten ohne Schreibzugriff und unabhängig an ihrem Review-Bereich. Anschließend prüften sie die konkreten Korrekturen erneut. Methodischer Abschluss: keine zuvor identifizierten mathematischen/Monte-Carlo-Blocker verblieben. Technischer Abschluss: alle angesprochenen wesentlichen Schnittstellen-/Übergabelücken einschließlich gemeinsamem Cachepfad geschlossen.

## Wesentliche Befunde und eingearbeitete Korrekturen

| Befund | Korrektur im Entwurf/Plan | Status |
|---|---|---|
| Globaler Blend könnte Segment-/Anbietermargen verändern | H und S getrennt je Segment normieren; keine erneuten zukünftigen LSP-PLZ-Anker | Eingearbeitet und nachgeprüft |
| Referenzabstimmung hatte offene Machbarkeit | B2B-/Anbietergrenzen vorab prüfen; log(k)-Bracketing, konstante Fälle und Residuum explizit | Eingearbeitet und nachgeprüft |
| Unklare Null-/Rest-/Geometrieunterstützung | Kanonischer Index, located/unlocated, Potenzialinventar getrennt, positive unbegründete Referenzmenge stoppt | Eingearbeitet und nachgeprüft |
| Strukturmerkmale fehlten im eingefrorenen Referenzvertrag | Population/Beschäftigte/Branche und eigene Geometrietabelle im Fingerprint | Eingearbeitet und nachgeprüft |
| Korrelierte Felder erhalten Standortmittel nicht automatisch | Zielabhängige Kalibrierung, andere Prüfdaten, Toleranzen und Fehlerstatus | Eingearbeitet und nachgeprüft |
| Hierarchisches Dirichlet könnte falsch normiert sein | Konkreter Mehrfachziehungstest für Standort- und PLZ-Mittelwerte mit ungleichen Gewichten/Nullgruppe | Eingearbeitet und nachgeprüft |
| Feste Jahresmenge war algorithmisch unvollständig | Einmalige Jahres-/Segmentrundung, voller Kalender, multinomiale Tagesverteilung, Datumauswahl als Filter | Eingearbeitet und nachgeprüft |
| Parameter- und Prozessintervalle konnten vermischt werden | Drei definierte Intervallprodukte, gleiche äußere Gewichte, unvollständige Ziehungen verhindern finale Quantile | Eingearbeitet und nachgeprüft |
| LHS-Zeilen sind keine IID-Stichprobe | Unabhängige gleich große Designblöcke, näherungsweiser kompletter Blockbootstrap, MCSE bei zu wenigen Blöcken nicht verfügbar | Eingearbeitet und nachgeprüft |
| Morris-Punkte könnten durch Fehlerfilter verzerrt werden | Unveränderliches Design; ungültige Trajektorie gibt keine vollständige Rangliste; latente unabhängige Inputs | Eingearbeitet und nachgeprüft |
| Neue Komponenten waren nicht vollständig verbunden | AnnualProjection, SpatialPlan, vollständige Generator-/Ensemble-Callchain und Legacy-Inputs definiert | Eingearbeitet und nachgeprüft |
| Cache/Resume/Erweiterung waren nicht eindeutig | Expliziter cache_root, Fingerprint-Pfade, atomare Publikation, Designblöcke, per-Run-Manifest und kopierte finale Artefakte | Eingearbeitet und nachgeprüft |
| CSV-Lesbarkeit beweist keine alte Semantik | Echte ID-/Geometrieverträge plus Verbraucherbeispiel LSP 20/Anteil 0,5 → Markt 40 → Zellgewichte 30/10 | Eingearbeitet und nachgeprüft |
| Frischer Lauf/Installation könnten alte Outputs voraussetzen | Lokale JSON-Quellen, XLSX-Leser, konkrete Rohdatenfixture, importlib.resources und Package-Data | Eingearbeitet und nachgeprüft |

## Zusätzliche Gegenprüfung durch den Hauptagenten

Die [Prüfdatei](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/planning_review_checks_20260915.json) enthält Dokumenthashes und Ergebnisse isolierter synthetischer Rechnungen. Getestet wurden eine kleine konsistente B2B-/Anbieterabstimmung mit LSP-Referenz, Halbwertszeit-/Blend-Endpunkte, hierarchische Dirichlet-Mittelwerte mit 4096 Ziehungen sowie Morris-Effekte für eine bekannte lineare Funktion. Die Python-Beispiele in den Plänen wurden auf Syntax und die lokalen Dokumentverweise auf Existenz geprüft.

Diese Rechnungen prüfen die geplanten Formeln und Testfälle. Sie sind keine Ausführung der noch zu implementierenden APIs, kein vollständiger HAGRID-Testlauf und keine Bestätigung empirischer Schwankungsparameter. Die Implementierungsaufgaben bleiben bis zur tatsächlichen Umsetzung und ihren separaten Reviews offen.

## Verbleibende fachliche Grenzen

- Zeit-/Abdeckungsdefinition der beobachteten Tagesmittel bleibt eine dokumentierte Annahme.
- Halbwertszeiten, tägliche Schwankungen, Persistenz und nicht beobachtete Anbieterprofile sind nicht aus LSP 2021 identifiziert.
- Die HAGRID-Strukturverteilung aus alten Einwohner-/Firmendaten ist keine automatisch aktuelle Zukunftsbeobachtung.
- Keine neue empirisch bessere Fehlerrate wurde in dieser Planungsrunde behauptet oder ermittelt.

## Umsetzungseinstieg

Weiter mit [Aufgabe 1 des Basismodell-Plans](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-01-baseline-core.md): Arbeitsstand sichern, neutrale Verträge/Seed-/Cache-Verwaltung und Importgrenze schaffen. Der [festgelegte Terra-Ablauf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-roadmap.md) gilt je Implementierungsaufgabe; eine erneute Wahl zwischen Subagenten und Einzelagent ist nicht erforderlich.
