# Prüfung der HAGRID-Baseline-Planung

**Ergänzung nach Nutzerzustimmung:** Der [konsolidierte Entwurf mit LSP-Gewichtung und Unsicherheitsanalyse](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/specs/2026-09-15-hagrid-baseline-design.md) und die [Umsetzungsroadmap mit Terra-Reviews](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/superpowers/plans/2026-09-15-hagrid-roadmap.md) konkretisieren diese Prüfung. Die dortige räumliche Mischung im Zieljahr ersetzt die unten zunächst beschriebene feste PLZ-Fortschreibung; die regionalen Jahresmengen bleiben separat bilanziert.

Stand: 15. September 2026. Geprüft wurden die zuletzt bestätigten Nutzerziele, die acht Nachfrage-Notebooks einschließlich Tagesgenerator, ihre vorhandenen Exporte und das bisherige Python-Paket. **Die Grundrichtung passt; der Gesprächsentwurf brauchte mehrere fachliche Korrekturen.** Dieses Dokument konsolidiert die Prüfung und die daraus empfohlenen Präzisierungen. Es dokumentiert keine bereits implementierte Baseline.

Die Nutzerentscheidung ersetzt die frühere offene Modellwahl im MASTERPLAN vom 9. September: Zuerst den HAGRID-Grundgedanken in Python nachvollziehbar nachbauen und innerhalb dieses Ansatzes verbessern. Die bisherigen Modellversuche bleiben für spätere Vergleiche erhalten.

## 1. Bestätigter Auftrag und Umfang

| Anforderung | Konsequenz für die Planung |
|---|---|
| Grundgedanken der Notebooks erhalten | Jahresentwicklung, B2B/B2C, Anbieterprofile, regionale Nachfragepotenziale und zeitliche Verteilung bleiben fachlicher Kern. Bekannte Rechenfehler werden korrigiert. |
| Bestehende Daten verwenden | Personen/Gebäude, Firmen/Beschäftigte, LSP 2021 und die vorhandenen Markt- und Zeitreihen sind die Ausgangsbasis. Neue OSM-Abfragen und neue externe Merkmale sind keine Voraussetzung. |
| Aktuelle Nachfrage und Zukunft | Historische Referenz, Fortschreibung ins Zieljahr und realisierte Liefertage werden getrennt ausgewiesen. „Heute“ ist ohne neue Beobachtungen eine Schätzung. |
| Anbieter unterscheiden | Unterschiede entstehen aus dem lokalen Privat-/Geschäftskundenmix und abgestimmten Anbieterprofilen. Unbeobachtete Anbieteranteile bleiben modellierte Größen. |
| Menge UND Ort schwanken | Regionale Tagesmenge und räumliche Tagesanteile erhalten getrennte Parameter. Verteilung zwischen PLZ sowie innerhalb einer PLZ wird geprüft. |
| Keine willkürlichen Raster als Nachfrageobjekte | Gebäude, Betriebe und vorhandene Straßenbezüge tragen die Nachfrage. Alte Rastergeometrien bleiben für den Kompatibilitätsexport verfügbar. |
| Kompatible Dateien 00–06 | Namen, Datentypen, Geometrien, IDs und fachliche Spaltenbedeutung werden als Datenverträge behandelt. |
| Reproduzierbarer Python-Lauf mit Dashboard | Konfiguration, Datenstände, Zufall, Zwischenergebnisse, Bilanzen und Annahmen sind je Run nachvollziehbar. |
| Nachfrageproduktion als Ziel | Der Tagesgenerator gehört zum Umfang. Batch-Zustellstrategien und MATSim-Ausführung gehören nicht zu diesem Baseline-Auftrag. |

Die Trennung von Nachfrageort und tatsächlichem Zustellziel bleibt bestehen. Ein Paket am Nachfrageort ist noch kein Fahrzeugstopp. Unklare Mess- und B2B-Definitionen müssen im Run sichtbar sein.

## 2. Tatsächlich verifizierte Befunde

Die Quelltext-Snapshots stimmen bei allen acht geprüften Notebooks mit dem aktuellen Code überein. Notebook-Hashes, Exportspalten und die nachfolgenden Zahlen stehen in [plan_verification_20260915.json](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/plan_verification_20260915.json). Es wurde kein vollständiger Notebook-Lauf ausgeführt und keine neue empirische Kalibrierung vorgenommen.

### A. Der regionale LSP-Bezug muss in der Baseline bleiben

Der ursprüngliche Generator summiert LSP 2021 pro PLZ, multipliziert mit einem Zeitfaktor und dividiert durch den lokalen LSP-Anteil. Anschließend verteilt er die PLZ-Menge räumlich. Die nationale Jahresreihe liefert eine relative Entwicklung; sie wird nicht als deutsche Gesamtmenge unmittelbar auf Hannover verteilt.

Die vorherige Planung „nationale Menge → regionale Verteilung“ war ohne regionalen Bezugsfaktor unvollständig. Ebenso wäre es falsch, jede LSP-Verankerung den Experimenten zuzuordnen. **Die Hochrechnung aus LSP gehört zur Baseline; die neu entwickelte exakte Anpassung jeder einzelnen Straße bleibt ein Experiment.**

Zusätzliche Korrektur: Im Notebook wird der LSP-Anteil des Zieljahres als Divisor benutzt. Bei sinkendem LSP-Anteil erhöht das die geschätzte Gesamtmenge zusätzlich zum nationalen Wachstum. Empfohlen wird, den Gesamtmarkt einmal mit dem LSP-Anteil des Referenzjahres herzuleiten und danach Gesamtmarktentwicklung und Anbieterentwicklung getrennt anzuwenden.

Beleg: [Generator, Hochrechnung](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__ParcelDemandScenarioGenerator.py:646), [Auswahl des Zieljahres](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__ParcelDemandScenarioGenerator.py:2010).

### B. Die B2B- und Anbietereingaben sind nicht automatisch konsistent

Aus den vorhandenen exportierten Marktanteilen und Anbieter-B2B-Profilen ergibt sich:

| Jahr | B2B-Ziel der separaten Reihe | Durch Anbieterprofile impliziert | Differenz |
|---|---:|---:|---:|
| 2021 | 23,00 % | 26,02 % | +3,02 Prozentpunkte |
| 2025 | 21,54 % | 21,57 % | +0,03 Prozentpunkte |
| 2030 | 20,31 % | 21,20 % | +0,89 Prozentpunkte |

Das ist eine Inkonsistenz zwischen Eingaben, keine Neuberechnung des endgültigen Notebook-Tagesoutputs. Ein späterer Korrekturfaktor kann Zahlen verschieben, löst aber die widersprüchliche Bedeutung der Ausgangswerte nicht.

Außerdem nutzt Notebook 05 beim Optimieren `delta * direction * strength`, beim erneuten Berechnen für den Export hingegen `delta * direction`. Eine einzige gemeinsame Berechnungsfunktion muss künftig für Abstimmung, Diagnose und Export gelten.

Beleg: [Berechnung im Optimierungsweg](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__05_EstimateLocalMarketShares.py:546), [erneute Berechnung](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__05_EstimateLocalMarketShares.py:815).

### C. Räumlicher Zufall existiert bereits, seine Parametrisierung ist unzureichend begründet

Der Tagesgenerator nutzt eine Dirichlet-Verteilung zwischen PLZ und nochmals innerhalb einer PLZ, danach teilweise Multinomial-Ziehungen. Es fehlt also nicht jede Ortsvariation. Der Aufruf setzt jedoch `alpha=50000`, für Zellen anschließend `12500`; aufeinanderfolgende Tage werden bei diesen Ziehungen nicht zeitlich gekoppelt.

Für eine Dirichlet-Komponente mit Mittelanteil `p` und Gesamtkonzentration `alpha` gilt `CV = sqrt((1-p)/(p*(alpha+1)))`. Bei `p=0,02` und `alpha=50000` sind das rund **3,13 % relative Streuung des Anteils**. Dieses Rechenbeispiel beschreibt nicht die gesamte Streuung fertiger Tagespakete, zeigt aber den dämpfenden Effekt der Einstellung. Ein einziges alpha erzeugt zudem je nach Größe eines Gebietes unterschiedliche relative Streuungen.

Empfehlung: Den vorhandenen Ansatz kontrolliert weiterentwickeln; Stärke, räumliche Ebene, Segment und zeitliche Abhängigkeit explizit machen. Stärkerer Zufall allein ist noch kein Nachweis realistischer Nachfrage.

Beleg: [Dirichlet-Verteilung](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__ParcelDemandScenarioGenerator.py:541), [Parameter im Tageslauf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__ParcelDemandScenarioGenerator.py:2035).

### D. Kalender und Bezugsgröße brauchen einen gemeinsamen Vertrag

Die alten Wochentagsgewichte ergeben **0,965 statt 1**. Der Zeitfaktor bezieht sich auf Kalenderwoche 20/2021; das ist nicht automatisch der Bezugszeitraum des LSP-Feldes `tagesschni`. Die Bedeutung dieses Tagesmittels muss als Annahme bzw. bestätigte Definition dokumentiert werden. Auch 313 Referenzbetriebstage sind eine Umrechnungskonvention, keine aus dem Feldnamen ablesbare Messdefinition.

Notebook 03 verwendet Schweizer Wochenwerte 2019–2021 als Proxy. Notebook 02 kennzeichnet eingetragene Schätzwerte ab 2024 im Export bis 2028 als `Observed`. Diese Kennzeichnung muss korrigiert werden; das aktuelle Kalenderjahr macht eine alte Schätzung nicht nachträglich zur Beobachtung.

Monats- und Wochenkurven dürfen nicht beide als vollständige Saisonfaktoren multipliziert werden. Ein gemeinsames Kalenderprofil liefert Tage, ISO-Wochen und Monate; ein zusätzliches Monatsprofil benötigt eine klar definierte, innerhalb des Monats normierte Wochenkomponente.

Beleg: [Wochentagsgewichte](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__ParcelDemandScenarioGenerator.py:268), [Kennzeichnung der Jahreswerte](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__02_EstimateGlobalGermanParcelVolumens.py:449), [Herkunft der Wochenreihe](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__03_EstimateWeekyParcelDistribution.py:25).

### E. Dateikompatibilität erfordert mehr als gleiche Spaltennamen

Die vorhandenen Exporte 04/05 enthalten echte `cell_id` und Rastergeometrien; 06 enthält bestehende `sample_idx`, `str_idx`, Puffer- und Punktgeometrien. Standort-IDs dürfen nicht einfach in `cell_id` umbenannt werden.

Die Planung benötigt einen gesonderten Adapter mit nachvollziehbarer räumlicher Zuordnung. Er aggregiert die neue Standortnachfrage auf die bisherigen Exportobjekte und erhält benötigte unveränderliche Referenzfelder aus dem alten Input. Mengenfelder müssen zu den neu berechneten Mengen passen. Nicht zuordenbare Mengen erscheinen in einer Restbilanz. Eine Prüfung muss den tatsächlichen nachfolgenden Loader samt Interpretationen ausführen; bloßes CSV-Einlesen genügt nicht.

### F. Umfang und Dokumentation waren noch widersprüchlich

00–06 allein erzeugen noch keine fertige Tagesnachfrage. Der `ParcelDemandScenarioGenerator` muss als Stage integriert werden. Im aktuellen Paket ist die Trennung `baseline/experimental` noch nicht umgesetzt. Auch der alte MASTERPLAN und die bisherige Dokumentation beschrieben weiterhin eine offene gemeinsame Modellschätzung als maßgeblichen Ansatz.

## 3. Empfohlene konsistente Mengenrechnung

### Referenzjahr, B2B und Anbieter zusammen abstimmen

Für einen gewählten Bezugsraum und ein Jahr seien `m_c` der Marktanteil des Anbieters, `q_c` dessen eigener B2B-Anteil und `b` der gesamte B2B-Anteil. Dann müssen gelten:

```text
sum_c m_c = 1
sum_c m_c * q_c = b
P(c | B2B) = m_c * q_c / b
P(c | B2C) = m_c * (1-q_c) / (1-b)
```

Die Randfälle `b=0` und `b=1` werden gesondert behandelt. Zunächst werden Markt- und B2B-Reihe als Referenzziele verwendet, die unsichereren Anbieter-B2B-Profile unter dokumentierten Grenzen möglichst wenig verändert. Bei unvereinbaren Grenzen darf der Solver keinen stillen Ersatzwert liefern: Zielkonflikt und notwendige Lockerung werden ausgewiesen. Nationale Ziele für Hannover zu übernehmen ist eine Modellannahme und erhält einen eigenen Herkunftsvermerk.

Private Gewichte kommen zunächst aus der Bevölkerung an Gebäuden, gewerbliche aus vorhandenen Firmen und Beschäftigten. Die in Notebook 06 tatsächlich aufgerufene Firmengewichtung `Firmenzahl + 0,1 * Beschäftigte` ist eine Vergleichsreferenz. Gedämpfte Beschäftigteneffekte und Branchenfaktoren sind auswählbare Verbesserungen innerhalb dieses Ansatzes, keine bereits belegten Paketbestellraten. Fehlende Haushaltskennungen werden nicht als erfundene Haushalte aufgefüllt.

Das lokale B2B-Verhältnis liefert unterschiedliche lokale Anbieteranteile. Damit ergeben sich Unterschiede zwischen PLZ und innerhalb einer PLZ aus den jeweiligen Standorttypen. Weitere Anbieter-Branchenpräferenzen sind nur zusätzlich dokumentierte Annahmen; für den ersten funktionierenden Baseline-Lauf sind sie nicht erforderlich.

Für PLZ `p` lautet der regionale Bezug vereinfacht:

```text
s_LSP,p,2021 = b_p,2021 * P(LSP | B2B) + (1-b_p,2021) * P(LSP | B2C)
T_p,2021 = LSP_p,2021 / s_LSP,p,2021
```

`T` hat dabei zunächst dieselbe zeitliche Einheit wie die LSP-Beobachtung. Lokale B2B-Verhältnisse, Divisoren und Gesamtmengen dürfen nicht unabhängig voneinander festgeschrieben werden: Die lokale B2B-Verteilung muss mit Gewichten `T_p,2021` den gewählten regionalen B2B-Zielwert treffen. Eine kleine deterministische Abstimmung eines gemeinsamen Skalierungsfaktors der gewerblichen Potenziale ist dafür ein erster Ansatz. Positive Beobachtungen ohne positives Anbieter-/Standortpotenzial werden als Zielkonflikt behandelt. Das braucht keine freie Optimierung eines Parameters für jede Straße.

Der Filter **über 1.000 Pakete** bleibt als explizite, vom Nutzer gesetzte Abgrenzung auf vollständigen LSP-Beobachtungen erhalten; genau 1.000 bleibt enthalten. Gefilterte Mengen werden separat bilanziert. Die Schwelle beweist weder Lkw-Zustellung noch erlaubt sie, automatisch Firmen oder Nachfrage anderer Anbieter zu löschen.

### Fortschreibung und Tagesmengen

Nach der dokumentierten Umrechnung auf eine Jahresbasis wird die Referenzmenge genau einmal mit der gewählten nationalen relativen Entwicklung fortgeschrieben:

```text
A_p,y = A_p,2021 * V_y / V_2021
```

Der LSP-Marktanteil des Zieljahres verändert danach die Anbieteraufteilung, nicht nochmals die Gesamtmenge. B2B- und Anbieterprofile erhalten eigene zeitliche Entwicklungen. Unterschiedliche regionale Wachstumspfade sind optionale, ausdrücklich angenommene Erweiterungen; unveränderte Standortdaten liefern keine gemessene Stadtentwicklung.

Kalendergewichte werden über das tatsächliche Zieljahr normiert. Erwartete tägliche Segmentmengen summieren sich zur jährlichen Segmentmenge. Feiertage, ausgeschlossene Liefertage, Schaltjahre und ISO-Jahresgrenzen werden explizit behandelt. Werden verschiedene Tagesprofile für B2B/B2C verwendet, muss die resultierende räumliche Jahresbilanz erneut stimmen; ein nachträglicher Segmenttausch ohne Bilanzprüfung ist unzulässig.

## 4. Tägliche Ortsvariation als Bestandteil der Baseline

Die Umsetzung wird in überprüfbare Schichten zerlegt:

1. **Stabile Referenz:** Standortpotenziale, Jahresmengen und Anbieterprofile bestimmen die zentrale Nachfrageverteilung.
2. **Kalender:** Wochen-/Monatsverlauf, Wochentage und gegebenenfalls Feiertage liefern die erwartete Nachfrage des Tages.
3. **Gesamtmenge:** Ein gemeinsamer Tagesfaktor und segmentbezogene Faktoren verändern die regionale Menge. Ihre Mittelwerte und gegenseitige Abhängigkeit sind definiert.
4. **Geografie:** Zufällige Anteile verschieben Nachfrage zwischen Gebieten und innerhalb der Gebiete auf vorhandene Standorte. Die Summe bleibt die in Schritt 3 festgelegte Menge. B2B und B2C dürfen unterschiedlich stark reagieren.
5. **Ganzzahlige Realisierung und Anbieter:** Aus den Intensitäten werden Paketanzahlen gezogen und auf Anbieter verteilt. Kleine Standorte können an einem Tag null Pakete erhalten. Jede Paketanzahl wird nur einmal erzeugt und vollständig zugeordnet.

Die erste Vergleichsvariante verwendet die vorhandene Dirichlet-Idee auf PLZ/Standorten mit transparenten Konzentrationen. Sie erhält die mittleren Anteile analytisch, besitzt für sich jedoch keine positive Nachbarschafts- oder zeitliche Korrelation. Eine zweite Variante ergänzt gemeinsame lokale Effekte und zeitliche Persistenz; Nachbarschaft wird aus vorhandenen Koordinaten abgeleitet. Diese einfache Schwankungsfunktion gehört zur Baseline-Erweiterung. Neu gelernte räumliche Prognosemodelle bleiben bei den Experimenten.

Bei räumlichen Multiplikatoren gilt beispielsweise `w_i,t = w_i * exp(z_i,t) / sum_j(w_j * exp(z_j,t))`. Das erhält die Gesamtmenge, **aber nicht automatisch den Erwartungswert jedes einzelnen Standortanteils**. Vor Verwendung werden mittlere räumliche Anteile über viele Realisierungen geprüft und bei relevanter Verschiebung die Ausgangsgewichte numerisch nachjustiert. „Im Mittel unverändert“ darf hier nicht aus der Normierung allein abgeleitet werden.

Normalisiert man jede PLZ täglich auf ihren unveränderlichen Anteil, verschwindet die gewünschte Variation zwischen PLZ wieder. Deshalb sind Schwankungen zwischen und innerhalb von PLZ eigene, nachvollziehbare Schritte. Gemeinsame lokale Effekte dürfen an PLZ-Grenzen nicht unbeabsichtigt als unabhängig modelliert werden, wenn eine grenzübergreifende Nachbarschaft zugesagt wird.

Anbieter erhalten zuerst dieselbe realisierte Standortnachfrage mit unterschiedlichen Segmentprofilen. Ihre Mengen werden nicht noch einmal unabhängig als neue Nachfrage gezogen. Damit schwanken die lokalen Anbietermengen bereits durch Standort- und Segmentmix sowie Paketziehungen. Ein zusätzliches tägliches Anbieterrauschen bleibt standardmäßig ausgeschaltet, solange keine begründete Parametrisierung vorliegt.

**Mengenregime:** Im normalen stochastischen Modus gilt die Jahresmenge im Erwartungswert; ein einzelnes gezogenes Jahr darf abweichen. Ein optionaler konditionierter Modus hält eine Jahres- oder Wochenmenge exakt fest und zieht deren Verteilung. Diese Modi erzeugen unterschiedliche Abhängigkeiten und müssen im Bericht unterscheidbar sein. Bei festen Wochenmengen müssen diese zuvor aus einer konsistenten Jahresmenge hervorgehen.

**Unsicherheit:** Tägliche Zufallsschwankung, unsichere Anbieter-/B2B-Parameter und Zukunftspfade werden getrennt gesteuert und dargestellt. Simulationsquantile bei festen Parametern sind keine vollständigen Prognoseintervalle. Die LSP-Tagesmittel 2021 reichen nicht zur empirischen Identifikation täglicher räumlicher Streuung und Persistenz. Bis entsprechende Zeitreihen vorliegen, bleiben diese Parameter begründete, durch Sensitivitätsanalysen geprüfte Annahmen.

## 5. Python-Stages und Abgrenzung

Die fachlichen Abhängigkeiten sind wichtiger als die alte Notebook-Nummerierung:

| Stage | Aufgabe und prüfbares Ergebnis |
|---|---|
| 1. Quellen und Bezugsgrößen | Eingaben, Hashes, Zeitbezug, Einheiten, Scope, Herkunft und offene Definitionen erfassen. |
| 2. Markt- und Jahresreihen | Marktanteile, B2B-Reihe, Volumenentwicklung; historische Werte, Interpolation und Annahmen unterscheiden. |
| 3. Räumliche Potenziale | Gebäude/Personen, Betriebe/Beschäftigte, eindeutige Zuordnungen und ungelöste Fälle aufbereiten. |
| 4. Referenzmodell | Anbieter-/B2B-Konsistenz und LSP-basierte regionale Bezugsmenge zusammen abstimmen; zentrale Standortmengen exportieren. |
| 5. Zieljahr und Kalender | Jährliche Mengen, Segment-/Anbieterentwicklung, Kalenderprofile und deren Bilanzen berechnen. |
| 6. Tagesgenerator | Konfigurierte Tage und Realisierungen mit Mengen- und Ortsvariation erzeugen. |
| 7. Exporte und Auswertung | Standortnachfrage, PLZ-/Wochen-/Monatssummen, Legacy-Dateien, Restbilanzen, Dashboard und Run-Manifest schreiben. |

Empfohlene Struktur: `baseline/` für diese Fachlogik, `common/` für neutrale Datenverträge, Einlesen, Geometrieprüfung und Zufallsverwaltung, `compatibility/` für Notebook-Exporte, `experimental/` für bisherige gemeinsame Fits, Logit-/Modellsuche, OSM-Logistikmodelle und exakte Straßenrekonstruktion. Baseline darf experimentelle Module nicht benötigen; Experimente dürfen Baseline/Common verwenden.

Das Verschieben einzelner Dateien wie `model_search.py` genügt nicht: Auch die bisherige gemeinsame Schätzung in `model.py`, ihre Workflow-/Forecast-Verknüpfung, Configs und Tests müssen anhand ihrer Funktion zugeordnet werden. Kalender- oder Bilanzfunktionen können nach Prüfung gemeinsam genutzt werden. Alte Befehle benötigen dokumentierte Weiterleitungen oder eine Migrationserklärung; historische Runs und deren Herkunftsbezüge bleiben erhalten.

Das Paket verwendet heute JSON. Für den ersten Umbau ist deshalb `configs/baseline.json` statt des zuvor vorgeschlagenen YAML zweckmäßig. Der geplante Befehl lautet `hagrid-demand baseline run --config configs/baseline.json`; er existiert noch nicht.

Wiederanlauf und Zwischenspeicher berücksichtigen die tatsächlichen Abhängigkeiten: Daten-, Konfigurations-, Schema- und Codeänderungen machen betroffene Stages ungültig. Eine Cacheentscheidung nur anhand vorhandener Dateien reicht nicht. Zufallsströme werden aus Seed, Realisierung, Datum, Kanal und stabilen Objektbezügen abgeleitet. Ein einzelner Tag und derselbe Tag in einem größeren Zeitraum müssen bei gleicher Konfiguration identische Ergebnisse liefern; zeitabhängige Zustände brauchen dazu einen festen Kalenderanker.

## 6. Prüfungen vor einer fachlichen Freigabe

| Prüfung | Abnahmekriterium |
|---|---|
| Frischer Baseline-Lauf | Ausgangsdaten bis fertige Tagesnachfrage ohne Notebook-Kernelzustand und ohne experimentellen Import ausführbar. |
| Bekannte Altfehler | Fehler bei Spaltenschema, räumlichen Mehrfachzählungen, leeren Joins, ignoriertem Schwellwert, Rundung und unbilanzierter Auffüllung durch gezielte Gegenbeispiele ausgeschlossen. |
| Gemeinsame Mengenbilanz | Standort/PLZ, B2B/B2C und Anbieter addieren sich innerhalb dokumentierter numerischer Toleranzen; ganzzahlige Mengen exakt. Restmengen werden mitgezählt. |
| Zukunft | Änderung eines Anbieteranteils allein erzeugt kein zusätzliches Gesamtmarktwachstum. Jeder Wachstumsfaktor hat genau einen Wirkungsort. |
| Kalender | Jahres-, Monats-, Wochen- und Tagesauswertungen sind konsistent; ISO-Woche 53 und Jahreswechsel abgedeckt; Teilzeiträume korrekt gekennzeichnet. |
| Räumliche Variation | Streuung der PLZ-Anteile, Streuung innerhalb der PLZ, Anteil aktiver Standorte und umverteilte Menge getrennt messen; nicht nur die Kartenfarbe absoluter Mengen vergleichen. |
| Korrelationsstruktur | Bei aktivierter Persistenz zeitliche Korrelation und bei lokalen Effekten Nachbarschaftsabhängigkeit messen; Mittelwerte und unbeabsichtigte Ortsverschiebungen mitprüfen. |
| Reproduzierbarkeit | Gleicher Run wiederholbar; Datumsauswahl, erlaubte Reihenfolgeänderungen und Wiederanlauf verändern dasselbe Ergebnis nicht. |
| Legacy-Kompatibilität | Referenz-IDs, Geometrien, Typen und Berechnungen der tatsächlichen Verbraucher passen; Abweichungen durch Bugfixes sind erklärt. |
| Dashboard je Run | Kalender, Mengenbilanzen, Karten nach Datum/Segment/Anbieter, Unsicherheitsarten, Scope und Datenjahr sichtbar; PLZ-Anteile und Differenzkarten mit festen Skalen vergleichbar. |
| Empirische Aussage | Verwendete LSP-Anker separat von zurückgehaltenen Beobachtungen bewerten; Abweichung zur alten Pipeline ist keine Fehlermetrik gegenüber Wahrheit. |

Die bisher erreichten LSP-Testfehler der experimentellen Fits sind Vergleichsergebnisse. Eine durch Konstruktion getroffene 2021-PLZ-Summe ist kein unabhängiger Genauigkeitsnachweis, und ohne andere Anbieterbeobachtungen lässt sich deren lokale Aufteilung nicht empirisch bestätigen.

## 7. Reihenfolge der Umsetzung nach dieser Prüfung

1. Bestehende Runs und Exportverträge sichern; experimentellen Bestand abgrenzen und Daten-/Bezugsgrößen festschreiben.
2. Markt, Jahresentwicklung, B2B-/Anbieterabstimmung und räumliche Potenziale als deterministischen Baseline-Durchlauf bauen. Abweichungen zur alten Notebook-Logik begründen.
3. Kalender und vollständigen Tagesgenerator integrieren, anschließend getrennt parametrierte Mengen- und Ortsvariation mit dem beschriebenen Vergleich prüfen.
4. Legacy-Adapter und automatisches Dashboard im vollständigen Lauf prüfen; Annahmen und Abnahmeresultate ausgeben.
5. Erst anhand dieser Referenz einzelne weitere Verbesserungen aus dem Experimentbestand bewerten.

Offen bleiben fachlich die genaue Zeit-/Abdeckungsdefinition von LSP und Hermes, die empirische Stärke täglicher räumlicher Schwankungen und die Belastbarkeit nicht beobachteter Anbieterprofile. Diese Punkte verhindern eine transparente technische Umsetzung mit gekennzeichneten Annahmen nicht; sie begrenzen aber die behauptbare empirische Güte.
