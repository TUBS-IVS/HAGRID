# Modellvergleich und Abnahme

Stand: 9. September 2026. Teil des [Gesamtentwurfs](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md). Dieser Plan beschreibt künftige Prüfungen, keine bereits erzielten Gütewerte.

## 1. Vergleichbare Kandidaten

| ID | Kandidat | Fragestellung |
|---|---|---|
| R0 | Bereits erzeugte HAGRID-Nachfrage | Welches bisherige Verhalten muss erklärt werden? Historischer Vergleich, mangels unberührter Testdaten nicht automatisch ein blinder Benchmark |
| R1 | Minimal reparierte HAGRID-Referenz | Was leisten alte fachliche Annahmen nach Korrektur belegter Implementierungsfehler? Reparaturen einzeln dokumentieren |
| R2 | PANDA-Referenz am geprüften Commit | Lassen sich Datenaufbereitung und berichtete Vergleiche reproduzieren? Fehlende Originaldaten verhindern gegebenenfalls eine exakte Reproduktion |
| R3 | Schlanker Standortansatz mit Wohnstruktur und gewerblichen Proxies | Wirkung des Standortmodells bei einfacher Parametrisierung |
| R4 | R3 mit HAGRID-Betrieben/Branchen/Beschäftigten | Zusatznutzen der reicheren Betriebsdaten gegenüber Proxies |
| R5 | Gemeinsame Nachfrage-/LSP-Schätzung mit B2B/B2C-Verbindung | Konsistente Anbieter- und Gesamtmengen bei Nutzung realer Beobachtungen |
| R6 | Jeweils eine zusätzliche Erweiterung | Nutzen von Haushalten, Branchenprofilen, räumlichen Effekten oder Zeitmodell isolieren |

R3–R6 sind keine vorweggenommene Rangfolge. Ein Standortexport eines PANDA-Fits ist von einem tatsächlich neu auf Standorten kalibrierten Modell zu unterscheiden. Datenverbesserungen und Algorithmusänderungen werden möglichst getrennt verglichen.

## 2. Splits vor dem Fit festlegen

- Ganze Beobachtungsgruppen räumlich zurückhalten; Fragmente derselben Straße bleiben zusammen. PLZ-Gruppen und ausreichend getrennte Gebiete als zusätzliche Robustheitsprüfung verwenden. Gruppierung aus Geometrie/IDs, nicht aus Testzielwerten ableiten.
- Zeitliche Tests rollen vom früheren zum späteren Fenster, sofern echte vergleichbare Zeitdaten verfügbar sind. Verschiedene Anbieter in verschiedenen Jahren ersetzen keinen Zeit-Holdout desselben Prozesses.
- Merkmalsauswahl, Skalierung, Großkundenregeln, Parameterwahl und Zielwert-basierte Zuordnungen ausschließlich im Training bestimmen. Validierung von Modellauswahl und abschließender Test sind getrennt; bei kleinen Datenmengen verschachtelte Gruppen-CV erwägen.
- Wenn Hermes zur Kalibrierung verwendet wird, ist dieselbe Hermes-Beobachtung kein unabhängiger Transfertest mehr. Einen Teil zurückhalten oder die Prüfrolle ehrlich ändern.
- Nationale/regionale Summen mit Testbeiträgen nur verwenden, wenn sie auch im vorgesehenen Anwendungsszenario vorab bekannt sind. Dann ist die Aufgabe eine bedingte Rekonstruktion und wird so benannt.
- Regeln und Splits nach Datenprüfung, aber vor Modellvergleich einfrieren. Änderungen später mit Begründung protokollieren.

## 3. Getrennte Qualitätsdimensionen

| Dimension | Messung | Interpretationsgrenze |
|---|---|---|
| Absolute Anbieterprognose | wMAPE = sum(abs(y-mu))/sum(y), MAE, signierter Gesamtfehler auf unveränderten Testmengen | Kein nachträgliches Skalieren auf die Testsumme; bei Nenner 0 wMAPE nicht definiert |
| Räumliche Form | Abweichung normierter Mengenanteile auf gleichen Beobachtungseinheiten | Normalisierung anhand der beobachteten Summe prüft ausschließlich Verteilung |
| Lokale Robustheit | Fehler nach Bevölkerungsdichte, Gewerbeprägung und Beobachtungsgröße; ungewichtete und mengengewichtete Maße | Große Empfänger dürfen kleine Gebiete nicht unsichtbar machen; Gruppenmerkmale unabhängig von Testziel festlegen |
| B2B-Zerlegung | Fehler gegen unabhängige Segmentdaten, falls vorhanden | Übereinstimmung mit angenommenen B2B-Zielen ist Konsistenz, keine empirische Validierung |
| Fremdanbieter | Absolute/relative Fehler auf zurückgehaltenen Anbieterbeobachtungen | Ohne solche Daten nur Plausibilität und Sensitivität berichten |
| Unsicherheit | Abdeckung und Breite prognostischer Intervalle auf Holdouts | Ohne passende Wiederholungsdaten keine kalibrierte Abdeckung behaupten |
| Zukunft | Rückwirkende Prognose mit damals verfügbaren Informationen | Neuere Struktur-/Marktdaten im historischen Fit verursachen Informationsleckage |
| Logistik | Erreichbare Lieferpunkte, Mengen je Bedienvorgang, Tourwirkung bei identischer Nachfragebasis | Bessere Tourergebnisse belegen allein keine genauere Nachfrage |

Alle Kandidaten werden auf denselben echten Straßen-/PLZ-Beobachtungen bewertet. Ein grobes Modell wird nicht allein deshalb besser, weil seine Fehler auf größeren Gebieten weggemittelt werden. Gebäudegüte kann mit aggregierten Beobachtungen nicht direkt nachgewiesen werden.

## 4. Entscheidung für das zentrale Modell

1. Kandidaten mit verletzten Mengenidentitäten, Datenleckage oder nicht nachvollziehbarer Quellenverwendung werden unabhängig vom Fehlerwert zurückgestellt.
2. Primäre Qualitätsgröße für die erste Anbieterprognose ist die absolute wMAPE auf zurückgehaltenen DHL-Beobachtungen, sofern deren Definition geklärt ist. Bias und Teilgruppenfehler werden zwingend mitbetrachtet. Diese Auswahlgröße bestätigt nicht die Güte für den gesamten Markt.
3. Verbesserungen werden als gepaarte Unterschiede auf denselben Gruppen berichtet. Unsicherheit über Unterschiede wird gruppenweise untersucht; ein Bootstrap fixer Vorhersagen ist keine vollständige Wiederholung der Modellwahl.
4. Bei ähnlich guten und statistisch nicht klar trennbaren Ergebnissen wird der einfachere, stabilere Kandidat bevorzugt. Kein universeller Mindestgewinn wird ohne Kenntnis der Daten willkürlich festgelegt.
5. Ein Kandidat darf zusätzliche Anbieterunterschiede auch aus fachlich begründeten Priors darstellen. Der Auswahlbericht benennt dann den strukturellen Nutzen und die fehlende empirische Bestätigung dieser Komponente.
6. Abschließender Testbericht und Parameter werden eingefroren. Wiederholte Auswahl nach Blick auf Testfehler macht den Test zum Validierungssatz und erfordert eine neue abschließende Prüfung.

## 5. Technische und fachliche Abnahme

- Personen-/Firmenbestand vollständig zugeordnet oder offen ausgewiesen; keine stillen Verluste durch alte Nullzellen.
- Nichtnegative Mengen, gültige Anteile, eindeutige Einheiten und reproduzierbare IDs.
- Anbieter-/Segment-/Zeit-/Exportbilanzen stimmen innerhalb deklarierter numerischer Toleranz. Ganzzahlige konditionierte Mengen bleiben exakt erhalten.
- Erwartungswerte und zufällige Realisierungen werden getrennt geprüft. Ein unbedingter Zufallsprozess muss nicht in jedem Lauf exakt den Erwartungswert ergeben.
- Kalenderprüfungen umfassen Jahreswechsel, ISO-Wochen, Schaltjahre und Liefertage. Jahresmengen dürfen nicht durch Tagesfaktoren verloren gehen.
- Große Standorte, reine Wohnstandorte, reine Gewerbestandorte, fehlende Beobachtungen, echte Nullbeobachtungen und fehlende Firmenmerkmale sind abgedeckt.
- Bei unvereinbaren Markt-/B2B-Grenzen meldet das Modell den Konflikt. Keine versteckte Auffüllung, keine stillschweigende Änderung der Ziele.
- Dieselbe Vorhersagefunktion wird in Fit, Evaluation und Export aufgerufen. Geänderte Quellen/Parameter invalidieren abhängige Caches.
- Historische Rekonstruktion, blinde Prognose und Zukunftsfortschreibung sind im Report unterscheidbar.

## 6. Report eines vollständigen Vergleichslaufs

Der Report enthält Quellenstand, Abdeckung, offene Definitionen, Kandidatenkonfigurationen, Splits, absolute und normierte Fehler, Gruppenfehler, Bilanzen, Unsicherheitsprüfung, Laufzeit, Annahmen und Auswahlbegründung. Für nicht prüfbare Größen steht ausdrücklich „nicht unabhängig validiert“ statt einer aus Modellzielen berechneten Genauigkeit.

Erste Lieferung ist eine reproduzierbare Vergleichsbasis. Ein Ersatz des bisherigen Workflows erfolgt erst nach einem vollständigen historischen Lauf und einem geprüften Export an den tatsächlichen Konsumenten.
