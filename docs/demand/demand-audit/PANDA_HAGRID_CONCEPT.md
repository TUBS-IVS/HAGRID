# PANDA + HAGRID: empirisch kalibriertes Hybridmodell

**Einordnung:** PANDA-Review und methodischer Zwischenentwurf. Maßgeblich ist jetzt der [Gesamtentwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md). Insbesondere sind zusätzliche Anbieterprofile innerhalb von B2B eine mögliche Erweiterung, kein notwendiger Modellbestandteil. Die empirische Kalibrierung des neuen gemeinsamen Modells steht noch aus.

Stand: 9. September 2026. PANDA-Quellstand: `1e683d026cec3483214523280877ef44e012d6d5` vom 27. Juli 2026. Das private Repository wurde über den autorisierten Git-Zugang in einer separaten lokalen Arbeitskopie gelesen; PANDA wurde nicht verändert.

**Entscheidung: strukturelles Bottom-up mit empirischer Kalibrierung und weichen Top-down-Randinformationen.** Die räumliche Verteilung entsteht aus Bevölkerung, Wohnstruktur und Betriebsstandorten. LSP-Beobachtungen bestimmen das empirisch abgesicherte Anbieterniveau und dessen räumliche Struktur. Gesamtmarkt, B2B-Zerlegung und andere Anbieter werden gemeinsam unter nachvollziehbaren, unsicheren Annahmen geschätzt.

Dieses Dokument präzisiert den [vorherigen Entwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/DESIGN.md) nach Einsicht in PANDA. Insbesondere wird ein Haushaltsmodell nicht mehr als zwingender erster Prognoseansatz gesetzt: Bevölkerung plus Wohnstruktur ist die zuerst zu reproduzierende Referenz. Haushalte bleiben als mögliche Simulationseinheiten und ergänzende Daten relevant.

## 1. Warum weder reines Bottom-up noch reines Top-down?

| Ansatz | Vorteil mit euren Daten | Grenze | Entscheidung |
|---|---|---|---|
| Nationales Volumen auf Hannover, PLZ und Gebäude verteilen | Einfache Summenbilanz | Regionale Marktanteile und Standortintensitäten fehlen; falsche räumliche Profile können trotz korrekter Summe bestehen bleiben | Nur als Vergleich und weiche Randinformation |
| LSP je Straße mit pauschalem Faktor auf alle Anbieter hochrechnen | Nutzt vorhandene Messungen direkt | Andere Anbieter erben LSP-Geographie; Unterschied zwischen Anbieteranteil und echter Nachfrage ist nicht identifiziert | Keine allgemeine Hauptmethode |
| Pakete aus jeder Person/Firma unabhängig hochrechnen | Räumliche Ursachen explizit; funktioniert auch ohne LSP-Beobachtung vor Ort | Intensitäten und Schwankungen sind teilweise unbekannt; die regionale Summe kann stark abweichen | Strukturelle Grundlage, nicht unkalibriert verwenden |
| Hybrid: Strukturmodell + Beobachtungen + unsichere Marktgrenzen | Nutzt die jeweils stärkste Information auf ihrer tatsächlichen Auflösung | Erfordert klare Herkunft der Annahmen und Sensitivitätsanalysen | Empfohlener Hauptansatz |

Die Priorität richtet sich nach Informationsqualität, nicht nach einer pauschalen Gewichtung wie „70 % Bottom-up, 30 % Top-down“. Eine verlässliche LSP-Straßenbeobachtung wiegt stark für LSP an dieser Straße. Eine nationale Anbieterprognose wirkt schwächer auf Hannovers Stadtteile.

## 2. Was PANDA bereits beiträgt

Der [aktuelle Estimator](https://github.com/HBimmermann/PANDA/blob/1e683d026cec3483214523280877ef44e012d6d5/estimator.py) enthält fünf Parameter:

- B2C auf LSP-Niveau: Bevölkerung plus Wohn-Geschossflächen für EFH und MFH;
- B2B auf LSP-Niveau: Lager-Geschossfläche plus gewerbliche POIs;
- nichtnegative Kalibrierung mit einem Anteils-Malus auf etwa 20 % B2B einschließlich separat behandelter Großkunden.

Die [Modellauswahl-Dokumentation](https://github.com/HBimmermann/PANDA/blob/1e683d026cec3483214523280877ef44e012d6d5/docs/bakeoff_model_selection.md) berichtet eine Verbesserung durch das schlanke Wohnstrukturmodell gegenüber komplexeren Kandidaten. Alters-/Mietmerkmale und die konkret getestete Haushaltszahl-Variante brachten dort keinen stabilen zusätzlichen Nutzen. Das ist Evidenz gegen deren ungeprüfte Übernahme, **kein allgemeiner Nachweis, dass Alter oder Haushalte keine Rolle spielen**.

PANDA dokumentiert außerdem:

- PLZ-Holdouts, Robustheitsprüfungen und Modellvergleiche;
- einen Hermes-Vergleich zur räumlichen Übertragbarkeit;
- eine Sensitivität gegenüber dem gesetzten B2B-Anteil;
- räumliche Prüfungen auf mehreren Auflösungen;
- eine separate Behandlung besonderer Großkunden;
- Transfer- und Exportfunktionen sowie Mengenprüfungen.

Die in der README berichteten ungefähr 10 % blinde PLZ-wMAPE und die Auflösungsbefunde wurden hier **nicht neu berechnet**. PANDA liefert die großen Rohdaten und räumlichen Caches nicht über Git mit. Geprüft wurden Dokumentation, Quellcode und die sieben vorhandenen Carrier-Split-Tests; diese sieben Tests bestanden. Es gibt damit keine unabhängige Bestätigung der empirischen Gütezahlen durch diesen Review.

Zusätzliche Interpretationsgrenzen: Der Hermes-Test skaliert Vorhersagen auf die beobachtete Hermes-Summe und prüft somit die räumliche Form, nicht unabhängig deren absolutes Niveau. Eine schwache LSP-Hermes-Korrelation ist keine mathematische Obergrenze aller denkbaren Modelle. Der aus Straßen abgeleitete 100-m-Vergleichsdatensatz ist keine echte Messung je 100-m-Zelle. Die dokumentierte Güte ist ein Benchmark des getesteten Systems, keine bewiesene allgemeine Genauigkeitsgrenze.

## 3. Die zentrale Erweiterung: Anbieterprofile innerhalb der Segmente

PANDAs [Carrier-Split](https://github.com/HBimmermann/PANDA/blob/1e683d026cec3483214523280877ef44e012d6d5/carrier_split.py) verwendet zwei feste Vektoren: Anbieteranteile innerhalb B2C und innerhalb B2B. Dadurch unterscheiden sich die gesamten Anbieter-Karten schon heute, wenn die regionale B2B-/B2C-Zusammensetzung variiert.

Aber innerhalb eines Segments gilt überall dasselbe Verhältnis, beispielsweise:

`FedEx_B2B(i) / UPS_B2B(i) = 0.277 / 0.353`.

Die reine B2B-Karte von FedEx ist deshalb eine skalierte UPS-B2B-Karte. Der gewünschte zusätzliche Unterschied zwischen Bürovierteln, Handel und Industrie fehlt.

**Neue Struktur:**

1. Einige wenige Empfängergruppen aufbauen: private Wohnstruktur sowie beispielsweise Büro/Dienstleistung, Handel, produzierendes Gewerbe und sonstige Betriebe. Diese Gruppierung ist eine Kandidatenspezifikation, kein feststehendes empirisches Ergebnis.
2. Paketintensitäten dieser Gruppen räumlich aus vorhandenen Strukturen berechnen.
3. Jedem Anbieter ein schwach differenziertes Profil über diese Gruppen geben.
4. Nur bei zusätzlicher Evidenz weitere Gebiets-/Branchenunterschiede zulassen.

So kann ein B2B-orientierter Anbieter stärker in gewerblichen Gebieten auftreten, und innerhalb dieser Gebiete können sich Anbieter durch die Branchenzusammensetzung unterscheiden. Es werden keine frei erfundenen Prozentwerte für einzelne Stadtteile gesetzt.

„FedEx eher B2B“ ist zunächst ein qualitativer Prior. Ein genauer deutscher FedEx-B2B-Anteil muss quellenbasiert begründet werden. Mit wenigen Fremdanbieterdaten werden die Profile priorgetrieben bleiben; die Ausgabe muss dies sichtbar machen. Für unbekannte Gruppen werden die Parameter in Richtung des gemeinsamen Segmentprofils regularisiert statt beliebig geschätzt. Methodischer Bezug: [Partial Pooling](https://www.pymc.io/projects/examples/en/latest/case_studies/hierarchical_partial_pooling.html).

## 4. Drei unterschiedliche Anteile nicht verwechseln

Wir benötigen konsistente Definitionen:

- `P(B2B)` = B2B-Anteil am Gesamtmarkt;
- `P(Anbieter | B2B)` = Anteil eines Anbieters am B2B-Markt;
- `P(B2B | Anbieter)` = B2B-Anteil innerhalb dieses Anbieters.

Mit Markt-B2B-Anteil b und Anbieteranteilen s gilt:

\[
P(B2B\mid c)=\frac{b\,s_{c\mid B2B}}
{b\,s_{c\mid B2B}+(1-b)\,s_{c\mid B2C}}.
\]

**Konkreter Kompatibilitätscheck mit PANDAs Konfiguration:** 80 LSP-B2C-Pakete und 20 LSP-B2B-Pakete werden durch 0,467 bzw. 0,242 geteilt. Das ergibt rund 171,31 Gesamtmarkt-B2C- und 82,64 Gesamtmarkt-B2B-Pakete, also **32,54 % B2B im Gesamtmarkt**. Ein auf 20 % gesetzter LSP-Anteil ist folglich nicht gleich einem 20-%-Gesamtmarktanteil.

Dies ist keine Widerlegung der Zahlen allein durch Arithmetik. Es zeigt, dass Niveau, Marktanteile und Kanalanteile zusammen kalibriert werden müssen. Die Referenzregion, Messperiode und Marktabgrenzung entscheiden, welche Kombination passt.

Die Herkunft der PANDA-Anbieterannahmen ist besonders wichtig: [Transferspezifikation](https://github.com/HBimmermann/PANDA/blob/1e683d026cec3483214523280877ef44e012d6d5/docs/superpowers/specs/2026-06-19-paketmengen-lausitz-transfer-design.md) und Code beziehen sich neben Marktquellen auch auf eine bereits erzeugte Hannover/HAGRID-Nachfragedatei. Diese Datei ist ein Modellprodukt. Sie darf als Legacy-Prior dienen, aber nicht als unabhängige Bestätigung desselben B2B-/Anbietermodells zurück in dessen Kalibrierung eingehen.

## 5. LSP möglichst vollständig nutzen, ohne alles zu LSP-Kopien zu machen

### 5.1 Direkt auf den beobachteten Straßen kalibrieren

Für Standort i und Empfängergruppe g sei `lambda(i,g)` die latente Nachfrageintensität und `p(c|i,g)` die Anbieterwahrscheinlichkeit. Dann:

\[
\mu_{i,c}=\sum_g\lambda_{i,g}\,p(c\mid i,g).
\]

Die modellierte LSP-Menge einer beobachteten Straße ist die Summe der ihr zugeordneten Standortbeiträge, angepasst an das Messfenster. Die Kalibrierung vergleicht diese Summe direkt mit der Straßenbeobachtung. Die reale Straßenmenge wird nicht zuerst künstlich in vermeintlich beobachtete 100-m-Werte zerlegt.

Wo die Straßenauflösung oder Zuordnung schwach ist, erfolgt der Vergleich auf gröberem Support. Straße und ihre PLZ-Summe sind dabei keine unabhängigen Beobachtungen; sie dürfen nicht ohne Berücksichtigung ihrer Abhängigkeit doppelt in die Likelihood eingehen.

### 5.2 Allgemeine Struktur und LSP-spezifische Abweichung trennen

Ein hoher LSP-Wert kann aus hoher Gesamtnachfrage, hohem LSP-Anteil, einem Großkunden, einer Paketstation oder einer Buchungsbesonderheit entstehen. Er wird deshalb nicht automatisch als Indikator allgemeiner Nachfrage übernommen.

Das Strukturmodell erklärt gemeinsam nutzbare Muster. Ein separat regularisierter LSP-Effekt erklärt verbleibende, plausibel anbieterspezifische Abweichungen. Unbeobachtete Anbieter übernehmen den gemeinsamen Strukturanteil und ihre eigenen Profile, nicht automatisch den LSP-Restfehler.

Diese Zerlegung bleibt bei einer einzelnen Anbieterbeobachtung teilweise unidentifiziert. Sie benötigt Priorinformation und mindestens einzelne zusätzliche Anker, etwa Hermes-Aggregate oder beobachtete regionale Gesamt-/B2B-Mengen. Mehr mathematische Komplexität ersetzt diese Anker nicht.

### 5.3 Rekonstruktion und Prognose unterscheiden

Für eine **Baseline-Rekonstruktion** kann die überprüfte LSP-Straßenmenge exakt erhalten werden. Nur ihre plausible Verteilung auf untergeordnete Standorte ist dann noch modelliert. Das wird als beobachtungsbedingte Rekonstruktion gekennzeichnet.

Für **räumliche Validierung** wird die Teststraße bzw. der Testblock vollständig zurückgehalten. Dessen reale Menge darf weder die Gesamtmenge festlegen noch als Residualkorrektur eingehen.

Für **heutige und zukünftige Nachfrage** werden Struktur und Intensitäten fortgeschrieben. Historische anbieterspezifische Residuen werden nicht unbegrenzt unverändert bis 2035 übernommen; Persistenz und Abschwächung sind eigene Szenario-/Modellparameter.

### 5.4 Großkunden und Zustellinfrastruktur als eigene Komponenten

PANDAs Großkundenbehandlung ist ein sinnvoller Ausgangspunkt. Hohe Straßenmengen sollten allerdings zuerst klassifiziert werden: tatsächlicher Geschäftsempfänger, Privatkundenbündelung an einer Paketstation, Depot-/Buchungseffekt oder unklarer Sonderfall. Ein großes Gebäude und hohe Paketmenge allein belegen noch keinen B2B-Empfang.

Bekannte LSP-Sonderkunden werden nicht anhand globaler Marktanteile auf weitere Anbieter vervielfacht. Bei Standorten mit mehreren Anbietern werden Vertrags-/Anbieterprofile separat modelliert. Rohwert, bereinigter Wert und Sonderkomponente bleiben im Mengenbuch nachvollziehbar.

## 6. Gemeinsame Abstimmung statt nacheinander reparierter Mengen

Der Modellzustand ist eine positive Tabelle `Standort × Empfängergruppe × Anbieter × Zeit`. Sie wird gemeinsam angepasst an:

- zuverlässige LSP-Beobachtungen auf ihrem tatsächlichen Support;
- Hermes-Beobachtungen mit eigenem Messfenster;
- strukturelle Nachfrageerwartungen;
- quellen- und zeitspezifische Anbieter-/B2B-Priors;
- eindeutig definierte Summenbedingungen.

Für einen ersten operationalen Ansatz bietet sich eine positive Prior-Tabelle Q mit regularisierter Anpassung an. Eine mögliche Zielfunktion ist:

\[
\min_{X\ge 0}\;\tau D_{KL}(X\Vert Q)
+\sum_o L_o(A_oX,y_o)
+\sum_m\frac{(M_mX-t_m)^2}{\sigma_m^2}.
\]

Die Beobachtungsfunktion L passt zur jeweiligen Messart. Verlässliche bekannte Summen können stattdessen harte Nebenbedingungen sein. Nationale Anteile sind im Regelfall keine exakt bekannten Hannover-Randwerte. Fehlende Werte sind keine Nullen. Kleine Unsicherheit bedeutet stärkeren Einfluss, muss aber quellenbasiert begründet sein.

Bei festen Priors, geeigneten konvexen Verlusten und linearen Nebenbedingungen ist dieser Allokationsschritt kontrolliert lösbar. Die gemeinsame Schätzung aller Intensitäts- und Anbieterparameter ist damit nicht automatisch konvex oder empirisch eindeutig. Für die einfache Mengenabstimmung sind proportionale/entropische Verfahren ein möglicher Ausgangspunkt; sie modellieren hier keine Lieferwege. Hintergrund zu entropischer Abstimmung: [Peyré & Cuturi](https://arxiv.org/abs/1803.00567).

## 7. Synthetischer Nachweis der Idee und ihrer Grenze

Im [ausführbaren Demonstrator](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/carrier_concept_demo.py) werden vier erfundene Gebietstypen, zwei Segmente und sieben Anbieter abgestimmt. Die Flächenmengen, Beobachtungen und Branchenneigungen sind **vollständig synthetisch**. PANDA-Anteile dienen nur als konfigurierter Vergleich, nicht als bestätigte Marktstatistik.

Zwei Varianten werden mit exakt denselben LSP-Gebietsmengen, Gesamtmengen je Gebiet/Segment und Anbieter-Segment-Summen gerechnet. Nur die räumlichen Profilannahmen unterscheiden sich.

| Synthetischer Gebietstyp | FedEx-Anteil am lokalen B2B, neutrales Profil | FedEx-Anteil am lokalen B2B, differenziertes Profil |
|---|---:|---:|
| Wohngebiet EFH | 25,9 % | 10,8 % |
| Wohngebiet MFH | 28,9 % | 14,2 % |
| Büro/Handel | 27,0 % | 17,1 % |
| Industrie/Gewerbe | 28,2 % | 38,1 % |

Beide Varianten halten alle gesetzten Mengenbedingungen mit maximalem numerischem Fehler unter `1e-9` ein. Das Beispiel zeigt:

1. Räumlich unterschiedliche Anbieterprofile sind mit unveränderten LSP-Beobachtungen und Gesamtbilanzen vereinbar.
2. Dieselben LSP-Beobachtungen identifizieren die Fremdanbieterverteilung nicht eindeutig.
3. Die zweite Variante ist dadurch noch nicht empirisch besser. Ohne weitere Daten muss ihre räumliche Differenzierung als Prior-/Szenarioannahme ausgewiesen werden.

Die [JSON-Ergebnisse](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/carrier_concept_demo.json) enthalten beide vollständigen Tabellen. Die Unterschiede wären später Bestandteil eines Ensembles, statt eine Variante als sichere Wahrheit auszugeben.

## 8. Welche neuen Daten zuerst?

| Priorität | Daten | Beitrag |
|---|---|---|
| A | Messdefinition, Abdeckung und Qualität der vorhandenen LSP-Daten; neuere Zeitfenster, falls zugänglich | Stärkster vorhandener empirischer Anker wird fachlich belastbar |
| A | Hermes-PLZ-Daten mit geklärter Einheit/Periode | Zweiter Anker für private Nachfrage und Anbieterunterschiede |
| A | HAGRID-Firmen: Branche, Beschäftigte, Standorte; Abgleich mit amtlichen Aggregaten | Gezielter Zusatznutzen gegenüber PANDAs offenen Gewerbeproxies |
| A | Kleine geschichtete Stichprobe von Betrieben: Paketempfang nach Tag und Anbieter | Direkte Identifikation von B2B-Intensitäten und Anbieterprofilen |
| B | Aktueller Zensus-/Gebäudestand sowie regionale Bevölkerungs- und Beschäftigungspfade | Vollständigkeit und Zukunftsentwicklung |
| B | Einzelne unabhängige Anbieter-/Gebietsaggregate, Großkunden- und Paketstationsinformationen | Engere Grenzen für Anbieterunterschiede |
| C | Weitere soziodemografische Merkmale | Nur nach zusätzlichem Nutzen auf unabhängigen Testdaten übernehmen |

Besonders wertvoll ist nicht die größtmögliche Datensammlung, sondern eine kleine Menge Information, die konkurrierende Erklärungen auseinanderhält: hoher allgemeiner Paketbedarf oder hoher LSP-Anteil; gewerblicher Empfang oder private Bündelung; viele kleine Lieferungen oder wenige Großempfänger.

## 9. Umsetzung als überprüfbare Modellleiter

1. **PANDA-Referenz reproduzieren:** dieselben Feature-Definitionen, Beobachtungen, Bereinigungen und Splits; Herkunft und Güte der Daten prüfen.
2. **HAGRID-Firmendaten ergänzen:** wenige Branchen-/Beschäftigtenmerkmale gegen PANDA-B2B-Proxies testen. PLZ-, Block- und Sonderkundenfehler getrennt ausweisen.
3. **Gemeinsames Beobachtungsmodell:** LSP und Hermes auf ihrem jeweiligen Support einbinden. Die B2B-/Anbieterannahmen auf Konsistenz prüfen und Unsicherheit dokumentieren.
4. **Anbieterprofile erweitern:** zunächst wenige Segmente, dann belegte Branchenunterschiede. Gleichförmige und differenzierte Profile beide als Baseline/Sensitivität erhalten.
5. **Zeit- und Zukunftsmodell ergänzen:** beständige Unterschiede, gemeinsame Tages-/Brancheneffekte und individuelle Zählprozesse getrennt. Ein Tagesmittel von 2021 allein identifiziert keine Tagesvarianz.
6. **Zustellpolitik und MATSim anbinden:** Nachfrageentstehung, Anbieterzuordnung, Lieferzielwahl und Terminierung trennen; dieselbe Nachfrage für Strategie-Vergleiche nutzen.

Die Stages des früheren Projektentwurfs bleiben verwendbar; `fit_baseline` und Anbieterabstimmung müssen aber eine gemeinsame Kalibrierung bilden. Beim Transfer oder Export wird ein eingefrorener Parametersatz angewendet, ohne unbemerkt neu zu fitten.

Empirische Tests trennen **absolute Menge, räumliche Form, B2B-Zerlegung, Anbieterprofil und Zukunftsgüte**. Keine Einzelmetrik belegt alle fünf. Merkmalsauswahl und abschließende Gütebewertung benötigen getrennte bzw. verschachtelte Splits. Ein Bootstrap über bereits fixe CV-Vorhersagen beschreibt nicht die vollständige Parameter- und Modellwahlunsicherheit.

**Empfohlene erste Version:** PANDAs schlanke Struktur als Referenz, HAGRIDs Firmenstandorte als gezielte Erweiterung, LSP/Hermes als Beobachtungsanker und ein regularisiertes, gemeinsam abgestimmtes Anbietermodell. Eine vollständige Mikrosimulation jedes individuellen Kaufvorgangs ist dafür zunächst nicht nötig.
