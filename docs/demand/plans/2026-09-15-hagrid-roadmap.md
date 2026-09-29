# HAGRID: konsolidierte Umsetzung und Review-Ablauf

Stand: 15. September 2026. Status: Planungsunterlagen erstellt, technische und methodische Terra-Planreviews abgeschlossen und wesentliche Befunde eingearbeitet. Das [Review-Protokoll](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-plan-review.md) dokumentiert Prüfumfang und Grenzen. Die neue Baseline ist noch nicht implementiert. Das vorhandene Python-Paket enthält den bisherigen experimentellen Entwicklungsstand.

## Fachliche Grundlage

**Neue Quellenprüfung vom 15.09.2026:** Der frühere Paperentwurf belegt eine vorgelagerte Quantilsregression aus Personen- und Unternehmenszahlen. Die bisherige Potenzialheuristik rekonstruiert diesen Fit nicht. Vor Abschluss der Referenzmathematik in Aufgabe 3 ist deshalb der [Regressionsvergleich](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/REGRESSION_RECONSTRUCTION_20260915.md) erforderlich. Er prüft den historischen Ansatz gegen nichtnegative Mittelwertmodelle und räumlich zusammenhängende Straßengruppen ohne Pflichtraster. Die dort beschriebenen Kandidaten sind noch nicht implementiert oder durch die früheren Planreviews freigegeben; Aufgabe 3 und die zugehörigen Potenzialformeln bleiben insoweit vorläufig. Jahres-, Kalender-, Unsicherheits- und Dashboardplanung gelten weiterhin.

Der [konsolidierte Entwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/specs/2026-09-15-hagrid-baseline-design.md) ist die aktuelle fachliche Umsetzungsgrundlage. Er baut auf der [verifizierten Notebook-/Baseline-Planung](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/BASELINE_PLAN_REVIEW_20260915.md) auf. Die neu ergänzten Regeln zur zukünftigen räumlichen Mischung ersetzen eine feste Fortschreibung jeder PLZ.

Der DHL-Einfluss ist räumlich konfigurierbar: fest, je Jahr oder mit Halbwertszeit. Er verändert nicht zusätzlich die Gesamtmenge. Monte Carlo unterscheidet unsichere Zukunftspfade und zufällige Tagesverläufe. Eine globale Sensitivitätsanalyse zeigt, welche angenommenen Parameter die Ergebnisse besonders beeinflussen. Die alte HAGRID-Grundlogik bleibt erkennbar; freie Fits, OSM-Erweiterungen und exakte Straßenrekonstruktion bleiben experimentell.

**Ergänzter Dashboard-Auftrag:** Ein gemeinsames Dashboard pro Output-Arbeitsbereich mit Run-Auswahl und Stage-Navigation führt alle Auswertungen zusammen. Markt/B2B, Referenz, Zukunft, Kalender, Tagesnachfrage, Anbieter, Monte Carlo und Sensitivität sind Ansichten derselben Oberfläche. Analyse-Runs werden ihrer Referenz zugeordnet; neue Stages öffnen keine weiteren Dashboardseiten oder Tabs. Details und Abnahmetests stehen in Spec 5a und Aufgaben 8/11/12.

## Reihenfolge und Lieferung

| Teilplan | Aufgaben | Ausführbares Ergebnis | Abnahme |
|---|---|---|---|
| [01 – Basismodell](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-01-baseline-core.md) | 1–4 | Frischer deterministischer Referenzlauf aus vorhandenen Inputs, Markt-/B2B-Reihen und abgestimmten Anbieterprofilen; Experimente abgegrenzt | Referenzniveau, Quellen, Unterstützung, Bilanzen und Importgrenze geprüft |
| [02 – Tagesnachfrage](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-02-daily-demand.md) | 5–8 | Jahres-/Kalenderentwicklung, Mengen-/Ortsschwankungen, Ganzzahlpakete, kompatible Exporte und Dashboard | Kalender-/Countbilanzen, Reproduzierbarkeit, räumliche Mittelwerte und alte Verbraucher geprüft |
| [03 – Unsicherheitsanalyse](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-03-uncertainty.md) | 9–12 | Steuerbarer DHL-Einfluss, Monte-Carlo-Ensembles, getrennte Intervalle, Morris-Screening | Endpunkte, Pfade, Designs, Gewichtung, Konvergenz und Sensitivitätsgegenbeispiele geprüft |

Alle zwölf Aufgaben enthalten Dateien, Schnittstellen, konkrete Testfälle, Implementierungsschritte und einen Review-Abschluss. Optionale spätere Sobol-Analysen, zusätzliche Datenbeschaffung und neue Prognosemodellfamilien sind kein versteckter Bestandteil dieser Lieferung.

## Vollständigkeit gegenüber den Nutzerzielen

| Nutzerziel | Umsetzung |
|---|---|
| HAGRID-Grundgedanken behalten, Fehler glätten | Aufgaben 2,3,5,6 |
| Neues experimentell abgrenzen | Aufgaben 1,4 |
| Regionalniveau aus DHL 2021 und Zukunftsjahre | Aufgaben 3,5 |
| Anbieter über B2B unterscheiden | Aufgaben 2,3,5,6 |
| Einfluss alter Daten konfigurierbar | Aufgabe 9 |
| Menge UND Ort täglich variabel | Aufgaben 6,7 |
| Monat/Woche/Tag konsistent | Aufgaben 5,6,11 |
| Kein willkürliches Raster als Nachfrageobjekt | Aufgaben 3,7,8 |
| Alte 00–06-Dateien weiter nutzbar | Aufgaben 2,8, expliziter Legacy-Vertrag |
| Reproduzierbare Stages und Wiederanlauf | Aufgaben 1,4,6,10 |
| Ein gemeinsames Dashboard mit Run-Auswahl und umschaltbaren Stages | Aufgaben 4,8,11,12; Spec 5a |
| Monte Carlo und Sensitivität | Aufgaben 10–12 |
| Terra-Subagenten und unabhängige Reviews | Workflow unten, verbindlich je Aufgabe |

## Aufgabenabhängigkeiten und Agenten

Der Hauptagent verantwortet Schnittstellen, Priorisierung und Integration. Terra (`gpt-5.6-terra`, reasoning high) wird für fokussierte Implementierung und Reviews eingesetzt. Agenten erhalten die Spec, ihren Teilplan, genaue Dateizuständigkeiten und Abnahmekriterien. Implementierer erhalten keinen Auftrag, nebenbei benachbarte Module umzugestalten.

Nach den gemeinsamen Verträgen können reine Reihenaufbereitung und Referenzmathematik mit synthetischen Verträgen parallel vorbereitet werden. Integration der Referenz wartet auf beide. Spätere Aufgaben werden nur parallelisiert, wenn ihre Dateien und Voraussetzungen unabhängig sind; insbesondere `projection.py`, `workflow.py`, `config.py` und das Dashboard haben jeweils nur einen aktiven Schreiber.

Je Aufgabe:

1. Hauptagent prüft Voraussetzungen, Arbeitsbaum und Dateizuständigkeit.
2. Terra-Implementierer schreibt aussagekräftige fehlschlagende Tests, implementiert die Aufgabe und führt die relevanten Checks aus.
3. Ein anderer Terra-Agent prüft die Erfüllung der Spezifikation ohne Schreibzugriff.
4. Separater Codereview prüft Mathematik/IO, Regressionen, Tests und unnötige Kopplung. Bei Formel-/Zufallsaufgaben wird hierfür ein weiterer unabhängiger Reviewer eingesetzt.
5. Implementierer behebt Befunde; betroffene Tests werden erneut ausgeführt; Reviewer prüft die Korrektur.
6. Hauptagent prüft Diff und Testbelege, integriert ausschließlich die Aufgabendateien und aktualisiert den Fortschritt.

P0/P1 blockieren die Integration. P2 werden behoben oder sichtbar mit Begründung und verbleibendem Risiko dokumentiert. Ein bestandener Codereview ersetzt keinen Test, und ein grüner Test ersetzt keinen methodischen Nachweis. Falls externe Tagesdaten fehlen, darf keine Review-Freigabe daraus empirisch kalibrierte Schwankungen machen.

## Arbeitsbaum und bestehende Ergebnisse

Das Repo enthält fremde/unabhängige Änderungen; `hagrid-demand/` und große Teile der Dokumentation sind noch untracked. Ein isolierter Git-Worktree muss den benötigten Arbeitsstand ausdrücklich erhalten, weil Git untracked Dateien nicht automatisch überträgt. Paket-/Dokumenthashes vor und nach Übernahme vergleichen. Vorherige Notebook-, Java-, Emissionsänderungen und historische Runs werden weder bereinigt noch pauschal mitcommittet. Es wird kein `git add .` verwendet.

Bestehende experimentelle Befehle bleiben während der Migration als bezeichnete Aliase erhalten. Codeumzüge erfolgen mit expliziter Dateiliste und passenden Asset-/Importprüfungen. Daten und frühere Run-Verzeichnisse bleiben an ihren bisherigen Orten.

## Prüfumfang dieser Planungsrunde

Die Planungsrunde liefert Spezifikation, drei Implementierungspläne und unabhängige Terra-Reviews. Sie führt keine neue Nachfragekalibrierung durch und behauptet keinen geringeren Testfehler. Ein Review-Protokoll hält die gefundenen Planlücken und ihre Korrektur fest. Der erste Umsetzungsschritt ist Aufgabe 1 aus Plan 01.
