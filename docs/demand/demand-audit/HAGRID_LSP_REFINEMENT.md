# HAGRID-LSP-Modell gezielt verbessern

**Einordnung:** Kandidatenherleitung zum [Gesamtentwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md). Die Beibehaltung von HAGRIDs Profilen ist keine Nutzervorgabe; diese Abbildung wird gegen andere nachvollziehbare Ansätze verglichen.

Die Anbieterunterschiede gehören zum regulären Modell. Ausgangspunkt bleibt HAGRIDs Idee: lokaler B2B-Bedarf, globale Anbieteranteile und unterschiedliche B2B-Ausrichtungen der Anbieter. Unsicherheit wird zusätzlich berichtet; sie ersetzt nicht die zentrale Schätzung.

## Konkrete Schwäche der bisherigen Abbildung

Notebook 05 berechnet aus globalem Marktanteil m_c und anbieterbezogener B2B-Quote q_c zunächst b = sum_c m_c q_c. Der lokale Anbieteranteil wird dann über delta_i = b_i - b verschoben:

`p_ic = normalize(clip(m_c + delta_i * (q_c-b) * strength_i))`

Anschließend wird `sum_c p_ic q_c` mit dem lokalen B2B-Ziel verglichen. Damit behandelt das Verfahren die globale B2B-Quote eines Anbieters zugleich als lokal konstant. Eine gewichtete Mischung dieser Quoten kann lokale B2B-Werte außerhalb von min(q_c) und max(q_c) grundsätzlich nicht erreichen. Selbst ein ausschließlich gewerblicher Standort wird so unnötig eingeschränkt. Die zusätzliche Verschiebung, Mindestanteile und Renormalisierung garantieren weder Ziel-B2B noch globale Marktanteile.

Die Quoten und Grenzen sind Modellannahmen: Im Code stehen beispielsweise FedEx/TNT 0,78–0,95 und Amazon 0,01–0,10 als Basisgrenzen, die im Jahreslauf angepasst werden. Dies sind hier keine neu bestätigten Marktstatistiken.

## Konsistente Ersatzabbildung

Alle Größen müssen denselben räumlichen Markt, Zeitraum und Paketbegriff beschreiben. Nationale Angaben dienen als Ausgangswerte für regionale Parameter, nicht automatisch als exakte regionale Messungen.

- m_c: Anteil des Anbieters am gesamten modellierten Markt.
- q_c: B2B-Anteil innerhalb dieses Anbieters im gesamten modellierten Markt.
- B_i und C_i: lokale B2B- und B2C-Mengen aus dem Standortmodell.
- b = sum_i B_i / sum_i(B_i+C_i): B2B-Anteil des modellierten Gesamtmarkts.

Zuerst die Profile abstimmen: `sum_c m_c q_c = b`, mit `sum_c m_c = 1` und allen Anteilen zwischen 0 und 1. Für 0 < b < 1 ergibt sich:

`a_c = m_c q_c / b` (Anteil des Anbieters am B2B-Markt)

`d_c = m_c (1-q_c) / (1-b)` (Anteil des Anbieters am B2C-Markt)

`X_ic_B = B_i a_c`

`X_ic_C = C_i d_c`

Beide Segmentanteilsvektoren summieren sich zu 1. Die lokalen Segmentmengen bleiben erhalten. Die Summe über alle Standorte reproduziert m_c exakt. Für b=0 oder b=1 entfällt das nicht vorhandene Segment; es wird nicht durch Null geteilt.

Der lokale B2B-Anteil eines Anbieters entsteht nun als `X_ic_B / (X_ic_B+X_ic_C)`, sofern der Nenner positiv ist. Er darf in einem Gewerbegebiet hoch und in einem Wohngebiet niedrig sein. Seine globale B2B-Ausrichtung bleibt trotzdem q_c. Damit wird die Kernidee des alten Modells mit korrekten bedingten Anteilen umgesetzt.

## Wie q geschätzt wird

Die vorhandenen HAGRID-Werte bilden Startwerte q0. Eine erste klar überprüfbare Kalibrierung minimiert bei festem m und b `sum_c w_c (q_c-q0_c)^2` unter den genannten Bilanz- und Wertebedingungen. Gut begründete Profile erhalten höhere Gewichte. Bei positiven Gewichten und zulässiger Menge hat dieses Teilproblem eine eindeutige Lösung; das bedeutet keine empirische Identifizierbarkeit ohne Annahmen.

Harte historische Anbietergrenzen werden zunächst auf gemeinsame Machbarkeit geprüft: `sum m_c lower_c <= b <= sum m_c upper_c`. Bei Konflikten wird die Unvereinbarkeit berichtet und die unsicherere Vorgabe begründet gelockert. Stilles Clipping oder automatisches Verschieben nach Jahreszahl verdeckt den Konflikt.

Danach kann die gemeinsame Schätzung der B_i/C_i und DHL-Segmentanteile zusätzlich die tatsächlichen DHL-Beobachtungen berücksichtigen. Für Beobachtung o lautet die erwartete DHL-Menge `sum_i A_oi (B_i a_DHL + C_i d_DHL)`. Dieses größere Problem ist nicht automatisch eindeutig oder konvex. Die Fremdanbieterprofile bleiben durch die HAGRID-Annahmen mitbestimmt.

## Priorisierte Änderungen

1. HAGRIDs Anbieterorientierungen als dokumentierte Ausgangswerte übernehmen und mit den Segment-/Gesamtmengen konsistent abstimmen.
2. Die heuristische lokale Marktanteilsverschiebung durch die obige Mengenzerlegung ersetzen. Keine universelle Mindestpräsenz von 1 % je Anbieter und Standort erzwingen.
3. Lokale B2B-Mengen aus Betrieben, Branchen und Beschäftigten ableiten; private Mengen aus Bevölkerung und Wohnstruktur. Standorte ersetzen Raster als Recheneinheiten.
4. DHL-Beobachtungen auf ihrem ursprünglichen räumlichen Support in die Kalibrierung aufnehmen. Lokale DHL-Abweichungen nicht unverändert auf alle übrigen Anbieter übertragen.
5. Eine identische Vorhersagefunktion für Optimierung, Validierung und Export verwenden. Die bereits im Audit beschriebenen Unterschiede in Stärke, Clipping und Zielfunktion entfernen.
6. Anbieterprofile über die Zeit stabilisieren. Eine Veränderung der weltweiten B2B-Gesamtquote erzwingt nicht bei jedem Anbieter dieselbe Veränderung seiner B2B-Quote.

Das Standardergebnis ist eine zentrale Nachfrageprognose je Standort, Segment, Anbieter und Zeitraum. Unsicherheitsintervalle begleiten diese Prognose. Zusätzliche frei angenommene Branchenpräferenzen einzelner LSP sind für diese erste Version nicht erforderlich.
