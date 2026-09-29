# Nachfrage ohne Raster: methodischer Aufbau

**Einordnung:** Detailmethodik zum [Gesamtentwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md). Standortaufbereitung bleibt Teil der Architektur; die statistischen Modellbausteine werden anhand des gemeinsamen Evaluationsplans ausgewählt.

Stand 9. September 2026. Präzisierung des PANDA/HAGRID-Konzepts. TAZ werden auf Wunsch vorerst nicht verfolgt. Dies ist eine Methodenspezifikation, keine bereits kalibrierte Implementation.

## Entscheidung

Der Modellkern erhält ein Verzeichnis realer bzw. explizit synthetischer Standorte. Nachfrage entsteht an Wohngebäuden und Betriebsstandorten. Die Kalibrierung nutzt jede Beobachtung auf ihrer ursprünglichen räumlichen und zeitlichen Ebene. Zustellziel und befahrbarer Netzanschluss werden getrennt geführt. Raster sind für diese Schritte nicht erforderlich.

Ein Raster ist nicht an sich unrealistisch. Problematisch sind die Gleichverteilung innerhalb einer Zelle, abgeschnittene Nachbarschaften, vermischte Nutzungen und die Gleichsetzung von Zellmittelwerten mit lokalen Messungen. Ein Wechsel zu Punkten beseitigt diese Probleme nur, wenn die Zuordnung fachlich besser begründet wird.

## 1. Was die vorhandenen Inputs erlauben

Am lokalen Input wurden zusätzlich zum Audit die Schemata geprüft:

| Input | Verifizierter Bestand | Verwendung und verbleibende Prüfung |
|---|---|---|
| persons_total.csv / .shp | 1.150.862 Personen; Building, Household, Punktgeometrie; Shapefile EPSG:25832 | Nach Building aggregieren. 227.641 unterschiedliche Gebäude-IDs sind noch kein Nachweis für ebenso viele reale, korrekt geocodierte Gebäude. Herkunft, Referenzjahr und Koordinatenkonsistenz prüfen. |
| companies_Total_reduced.shp | 52.931 Punkte; id, employees, branch, link, type; EPSG:25832 | Betriebsstandorte mit Branchenmerkmalen erhalten. Filiale versus Unternehmenssitz, Mehrfachstandorte und Netzlink-Herkunft prüfen. |
| dhl2streets_2021.shp | 12.342 Linien; name, plz, tagesschni; EPSG:4326 | Beobachtungen getrennt speichern; für räumliche Operationen projizieren. Einheiten, Zeitraum und mögliche Wiederholung derselben Straßenmenge auf mehreren Linien klären. |
| Hermes_PLZ-Menge_2019-2021.csv | Datei vorhanden | Weiterer Beobachtungstyp; Inhalt, Einheit, Jahre und Gebietsstand vor Einbindung prüfen. |

46,62 % fehlende Household-Werte sprechen gegen eine verpflichtende vollständige Haushaltsrekonstruktion in Version 1. Die B2C-Basis kann je Gebäude aggregierte Bevölkerung verwenden. Gebäudeflächen oder Wohnungszahlen sind ergänzende Merkmale; sie dürfen bereits vorhandene Einwohner nicht nochmals als zusätzliche Bevölkerung erzeugen.

## 2. Ein Standortverzeichnis statt räumlicher Zellen

Vier getrennte Objekte vermeiden fachliche Vermischungen:

| Objekt | Bedeutung | Beispiel |
|---|---|---|
| site | Physischer Ort mit stabiler ID und Lagequalität | Wohngebäude, Betriebsareal |
| demand_unit | Einheit, an der Empfangsnachfrage entsteht | Wohnbevölkerung eines Gebäudes, einzelne Betriebsstätte |
| delivery_point | Tatsächliches Lieferziel | Hauseingang, Warenannahme, Paketstation |
| network_access | Erreichbarer Anschluss für ein bestimmtes Verkehrsmittel | Zulässiger Straßenlink für Lieferwagen |

Mehrere Firmen können denselben Standort haben. Ein gemischt genutztes Gebäude kann private und gewerbliche Nachfrage enthalten. Ein Standort kann mehrere Eingänge besitzen. Diese Beziehungen bleiben erhalten; gleiche Koordinaten sind kein hinreichender Grund, Firmen zu löschen.

Jedes Objekt führt Quelle, Referenzjahr, Original-ID, Zuordnungsverfahren und Qualitätsstatus. Unaufgelöste Datensätze bleiben als solche erhalten und werden mit ihrer potenziellen Mengenwirkung berichtet.

## 3. Aufbereitung in nachvollziehbarer Reihenfolge

1. **Quellen prüfen:** Rohdaten unverändert versionieren, IDs und Einheiten prüfen, Geometrien in ein metrisches CRS überführen. Messwerte, synthetische Bevölkerung und Prognosen kennzeichnen.
2. **Wohnstandorte bilden:** Personen über Building zusammenfassen. Prüfen, ob eine Gebäude-ID räumlich konsistente Punkte besitzt. Bei Konflikten nicht blind den Mittelwert bilden; ID-Herkunft und Gebäudegeometrie klären.
3. **Betriebe zuordnen:** Zuerst verlässliche IDs/Adressen, dann passende Gebäudegeometrien, zuletzt räumliche Kandidaten verwenden. Entfernung, Nutzungsart und Adressübereinstimmung dokumentieren. Bestehende link-Werte gegen das verwendete Netz prüfen.
4. **Gebäudebestand ergänzen:** Aktuelle Gebäude bzw. Adressen mit Personen-/Firmenbestand abgleichen. Fehlende Betriebe oder Bewohner nicht allein aus Gebäudegröße als gesicherte Bestände erzeugen. Fehlbestände durch unabhängige Aggregate prüfen.
5. **Beobachtungen anbinden:** DHL-Linien zu fachlichen Beobachtungen zusammenfassen, soweit die Quelldefinition dies verlangt. Gleiche Namen in verschiedenen PLZ sind nicht automatisch dieselbe Straße. Überschneidende Puffer dürfen Mengen nicht mehrfach zählen.
6. **Netzzugang bestimmen:** Eingang/Adresse und erreichbare Straße bevorzugen. Die geometrisch nächste Straße kann eine Autobahn, falsche Straßenseite oder hinter einer Barriere liegen. Unsichere Anschlüsse markieren.

Wenn ein Input ausschließlich eine Flächensumme liefert, wird diese anhand geeigneter Wohn-/Gewerbemerkmale auf Standorte verteilt. Die Summe bleibt erhalten; die Verteilung ist eine Schätzung. Das entspricht dem Prinzip der dasymetrischen Disaggregation. Die ONS beschreibt die Nutzung von Gebäude- und Adressdaten für solche kleinräumigen Schätzungen; dies belegt die Methode, nicht die Güte einer Übertragung auf Pakete: [ONS Methodenstudie](https://www.ons.gov.uk/methodology/methodologicalpublications/generalmethodology/onsworkingpaperseries/geospatialmethodsforsmallareapopulationestimatesproofofconcept).

## 4. Das Modell lernt aus Straßensummen, ohne künstliche Gebäudemessungen

Für Standort i, Nachfragesegment s, Anbieter c und Zeitraum t sei lambda(i,s,t) die erwartete Gesamtnachfrage und p(c|i,s,t) der Anbieteranteil. Dann gilt für eine Beobachtung o:

`mu(o) = sum_i,s A(o,i) * lambda(i,s,t) * p(c|i,s,t)`

A beschreibt die tatsächliche Beobachtungsabdeckung. Bei eindeutiger Zuordnung sind die Einträge 0 oder 1. Bei unsicherer Zuordnung werden alternative konsistente Zuordnungen oder begründete Gewichte verwendet. Eine Straßenmenge wird nicht erst proportional auf Gebäude verteilt und anschließend als unabhängige Gebäude-Trainingsdaten behandelt.

Für disjunkte Beobachtungen müssen Zuordnungsgewichte Mengen erhalten. Sich überlagernde Straßen- und PLZ-Beobachtungen sind nicht statistisch unabhängig; abgeleitete Summen werden nicht als zusätzliche Messungen gezählt.

B2C startet mit einem schlanken Bevölkerungs-/Wohnstrukturmodell. B2B startet mit wenigen Branchen und Beschäftigteneffekten. Ein großer Betrieb muss nicht proportional mehr Empfangspakete haben: lineare und gedämpfte Größeneffekte werden verglichen. Individuelle freie Parameter für jeden Standort sind mit den vorliegenden Aggregaten nicht identifizierbar.

Anbieterprofile können von Branche und Standortmerkmalen abhängen. Räumlich ähnliche Standorte teilen sich Informationen über wenige gemeinsame Parameter. Ein eigener Anbieteranteil pro Gebäude wäre ohne entsprechende Daten Scheingenauigkeit. DHL allein bestimmt weder den B2B-Anteil noch sämtliche Fremdanbieterprofile eindeutig.

## 5. Nachfrageort, Lieferziel und Fahrzeugstopp auseinanderhalten

Ein privat bestelltes Paket bleibt B2C, wenn es an eine Paketstation geliefert wird. Paketstationen erzeugen nicht allein wegen ihrer Koordinate zusätzliche B2B-Nachfrage. Die Zuordnung vom Nachfrageort zum Lieferziel hängt von Anbieter, Erreichbarkeit, Kapazität und Zustellstrategie ab.

Zuerst werden erwartete Empfangsmengen berechnet, dann Tagesmengen realisiert und Lieferziele zugeordnet. Erst danach werden Pakete desselben Anbieters an einem Lieferpunkt und Zeitfenster zu Bedienvorgängen gebündelt. Paketanzahl, Empfängerzahl und Stoppzahl werden separat ausgegeben.

Aus bestehenden DHL-Lieferdaten ist zu klären, ob Paketstationsmengen bereits enthalten sind. Sie dürfen bei einer nachträglichen Zielwahl nicht doppelt umverteilt werden. Nicht bekannte Ursprungsorte solcher Mengen bleiben latent und werden als Szenarien behandelt.

Ein Fachbeispiel für die explizite Modellierung von Lieferoptionen ist [Sakai et al.](https://arxiv.org/abs/2010.14375). Die Autoren weisen selbst auf erforderliche Kalibrierung hin; das Konzept liefert keine übertragbaren Hannover-Parameter.

## 6. Schwankungen und Zukunft ohne Raster

Erwartungswerte werden für Standorte fortgeschrieben: Änderungen der Wohnbevölkerung, neue Gebäude, Betriebseröffnungen/-schließungen, Branchenentwicklung und Paketintensität. Für bekannte Neubaugebiete können neue Standorte hinzukommen. Fehlt eine belastbare räumliche Prognose, werden alternative Standortverteilungen erzeugt statt unbekannte künftige Adressen als sicher darzustellen.

Tagesmengen benötigen gemeinsame Kalender-/Brancheneffekte und individuelle Schwankungen. Eine Poisson-Verteilung ist eine einfache Referenz, zusätzliche Streuung muss mit Zeitreihen geschätzt oder als Sensitivität variiert werden. Ein Jahres- oder Tagesmittel allein identifiziert keine Tagesvarianz. Zeitliche Schwankung, Parameterunsicherheit und unsichere räumliche Zuordnung werden getrennt ausgewiesen.

## 7. Wie der Wechsel auf Punkte bewertet wird

- **Bestandsbilanzen:** Jede Person, jeder Betrieb und jede Beobachtungsmenge wird genau einmal bzw. mit dokumentierten Gewichten berücksichtigt; ungelöste Zuordnungen bleiben sichtbar.
- **Räumliche Plausibilität:** Einwohner an Wohnstandorten, Firmen an plausiblen Betriebsstandorten, Lieferpunkte mit geeignetem Netzzugang. Ein Gebäudezentrum ist noch kein Eingang.
- **Unabhängige Vorhersage:** Ganze räumliche Gruppen bzw. Zeitfenster zurückhalten. Zuordnungsoptimierung und Merkmalswahl dürfen dabei keine zurückgehaltenen Zielmengen verwenden.
- **Gemeinsame Bewertungsebene:** Altes Rastermodell und neues Standortmodell auf denselben echten Straßen-/PLZ-Beobachtungen bewerten. Bessere Passung nach Zusammenfassung auf größere Flächen ist kein Beleg für bessere lokale Genauigkeit.
- **Sensitivität:** Unsichere Gebäudezuordnungen, Branchenintensitäten und Anbieterprofile variieren. Prüfen, ob Nachfragekarten und Tourenergebnisse stabil bleiben.
- **Logistik:** Erreichbare Lieferpunkte, Pakete je Bedienvorgang und Tourlängen gesondert prüfen. Eine detailliertere Punktkarte allein belegt keine besseren Verkehrsprognosen.

## 8. Empfohlene erste Umsetzung

`ingest -> audit_sources -> build_sites -> link_observations -> fit_joint_demand -> forecast_sites -> sample_days -> assign_delivery_points -> export`

Version 1 aggregiert B2C auf Gebäude-IDs, behält B2B-Betriebsstätten einzeln, kalibriert gegen die ursprünglichen DHL-Beobachtungen und führt Standort-/Zuordnungsqualität mit. Sie simuliert keine vollständigen individuellen Kaufbiografien. Lieferoptionen und zusätzliche Branchenprofile werden schrittweise anhand ihres nachgewiesenen Nutzens ergänzt.

Die wichtigste erste technische Lieferung ist ein geprüftes Standortverzeichnis mit Beobachtungszuordnung und Mengenbilanzen. Erst danach lohnt eine aufwendigere Modelloptimierung.
