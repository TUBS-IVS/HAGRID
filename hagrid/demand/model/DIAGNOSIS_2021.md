# LSP 2021: neue Fehlerdiagnose

Die Rohdaten sind laut Nutzer von 2021. Das bisherige Modell verwendete bereits dieses Referenzjahr. Der vorherige Testfehler von 27,7 % ist ein räumlicher Fehler im Referenzjahr; Wachstum und simulierte Tagesfaktoren verursachen diesen Fehler nicht.

Die alte lineare Mengentabelle liefert 2021 4,51, 2026 4,42 und 2030 4,929619704 Milliarden: gegenüber 2021 also −1,996 % und +9,304 %. Diese Kurve ist eine übernommene Annahme, keine neu geprüfte aktuelle Prognose. Die Tagesumrechnung mit 313 Betriebstagen und sämtliche Schwankungsparameter sind ebenfalls Annahmen. Der neue Diagnosebefehl verwendet nichts davon.

## Reproduzierbarer Aufruf

```powershell
python -m hagrid_demand diagnose-2021 --config hagrid-demand/configs/model.json --special-customers C:/Users/bienzeisler/.codex/tmp/panda-demand-review/grosskunden_final.csv --run-id lsp-2021-diagnosis-20260909
```

Für erneute Läufe eine neue Run-ID verwenden. Jeder Lauf schreibt Roh-/Restmengen, zurückgehaltene Vorhersagen, Kennzahlen, die verwendete Großkundendatei, Eingabe-/Codehashes und ein HTML-Dashboard. 20 Tests bestanden nach dieser Erweiterung.

## Ergebnisse

Drei identische Fünffach-Aufteilungen für jeden Kandidaten, Seeds 42, 73, 101. Mittelwerte der drei vollständigen Out-of-fold-wMAPE:

| Ansatz | Unveränderte Rohmengen | Restnachfrage bei vorgegebenen Großkunden |
|---|---:|---:|
| Bevölkerung + Beschäftigte, feste Anbieterprioren | 33,21 % | 18,91 % |
| Zusätzlich Branchenkoeffizienten | 34,13 % | 19,14 % |
| Zusätzlich gemeinsam angepasste Anbieteranteile | 35,46 % | 20,45 % |

Das ist eine explorative Modellprüfung, kein neuer unberührter Test. Die bisherigen Testdaten wurden bereits betrachtet. PLZ sind außerdem keine vollständig räumlich unabhängigen Stichproben. Eine geografisch geblockte Prüfung bleibt erforderlich.

PANDA enthält zwei separate Großkundenannahmen: Am Berkhopsfeld (30938), 7.356 Pakete/Tag Exzess, und Stockholmer Allee (30539), 4.595,8. Die Rohwerte 7.361 und 4.606 stimmen jeweils eindeutig mit unseren Straßenbeobachtungen überein. Zusammen werden 11.951,8 von 97.906 Paketen als bekannte Sondermenge behandelt. Alle Mengen bleiben dokumentiert; nichts wird als falscher Datensatz gelöscht.

Die Restnachfrage-Auswertung ist **bedingt auf diese bereits aus LSP abgeleiteten Sondermengen**. Sie validiert weder ihre Vorhersage noch eine automatische Großkundenerkennung. PANDA setzt das Bestätigungsfeld automatisch vor; eine unabhängige manuelle Prüfung ist nicht belegt. Wir übernehmen auch keine automatische Gleichsetzung mit B2B oder entsprechende Sondermengen anderer Anbieter.

Größter verbleibender Fehler: PLZ 30855, beobachtet 5.660, im einfachen Restnachfragemodell durchschnittlich 1.665 vorhergesagt. Danach 30419: 2.424 beobachtet, 3.481 vorhergesagt. Das rechtfertigt eine Untersuchung der Standort-/Nutzungsmerkmale; zusätzliche freie Anbieterparameter helfen bislang nicht.

## Methodische Konsequenz

Zuerst allgemeine Standortnachfrage und dokumentierte Sonderstandorte getrennt reproduzieren. Anbieterprofile weiter explizit modellieren, ihre räumlich nicht identifizierbaren Parameter aber nicht als gelernt darstellen. Das einfache Modell bleibt Vergleichsmaßstab. OSM-Gebäude-/Nutzungsmerkmale anhand derselben Aufteilungen prüfen; Datenstand und mögliche zeitliche Informationsleckage gegenüber 2021 ausweisen. Aktuelle OSM-Daten sind kein historisch unabhängiger Nachweis für 2021. Erst danach Jahreswachstum und Tagesvariabilität kalibrieren.

Für die Wachstumsüberarbeitung müssen Marktdefinitionen geprüft werden: nationale Paketmengen sind nicht automatisch identisch mit KEP-Mengen oder regionaler LSP-Zustellnachfrage. Offizielle Ausgangsquelle: [Bundesnetzagentur, Paket-Sendungsmengen](https://www.bundesnetzagentur.de/DE/Fachthemen/Datenportal/3_Post/_svg_Post/Paket/P_Paketmarkt_Mengen/P_Paketmarkt_Mengen.html). In diesem Diagnoselauf wurde keine neue Wachstumskurve eingesetzt und kein MATSim gestartet.
