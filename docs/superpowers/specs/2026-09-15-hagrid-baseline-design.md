# HAGRID-Baseline mit steuerbarem Datenalter und Unsicherheitsanalyse

Stand: 15. September 2026. Fachlicher Entwurf nach Zustimmung des Nutzers zur Baseline-Planung und zur Ergänzung um DHL-Gewichtung, Monte Carlo, Sensitivitätsanalyse und Terra-Subagenten. Dieses Dokument beschreibt den Sollzustand; die neue Baseline ist noch nicht implementiert.

## Auftrag und bestehender Prüfstand

Die [Planungsprüfung](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/BASELINE_PLAN_REVIEW_20260915.md) bleibt Grundlage für Datenfehler und Notebook-Kompatibilität. Dieser Entwurf präzisiert und ersetzt ihre Zukunftsverteilung: Die Gesamtregion wird fortgeschrieben, während sich die PLZ-Anteile durch Segmententwicklung und eine konfigurierbare räumliche Mischung verändern dürfen. Eine feste Fortschreibung jeder einzelnen PLZ ist nicht gleichzeitig vorgeschrieben.

HAGRID-Grundgedanken bleiben: jährliche Mengenentwicklung, DHL als Referenzinformation, B2B/B2C, Anbieterprofile, Einwohner und Betriebe, Kalender und tägliche Nachfrage. Der bereits entstandene freie Modellvergleich wird experimentell eingeordnet. Nachfrageerzeugung einschließlich Tagesgenerator und automatischem Dashboard ist der Umfang.

## Globale Vorgaben

- Python >=3.11; bestehender Stack NumPy, Pandas, SciPy, GeoPandas, Shapely, PyArrow; openpyxl>=3.1,<4 zum Lesen der vorhandenen XLSX-Quelle; JSON-Konfiguration.
- Keine neuen OSM-Abfragen, externen Datendownloads oder MATSim-Ausführung als Baseline-Abhängigkeit.
- Referenzjahr 2021. DHL-Beobachtungen mit Wert >1000 werden vor PLZ-Aggregation vollständig ausgeschlossen; Wert 1000 bleibt enthalten. Ausschlussmengen werden bilanziert.
- Personen-, Firmen- und Nachfragerohdaten sowie historische Runs werden nicht überschrieben oder verschoben.
- Neue Nachfrageobjekte sind Gebäude/Betriebe. Raster sind ausschließlich Vergleichs- und Exportobjekte.
- Anbieterreihenfolge: DHL, Hermes, UPS, DPD, GLS, FedEx/TNT, Amazon. Segmente: private, business.
- Referenz-Tagesmittel wird vorläufig als Betriebs-Tagesmittel mit 313 Tagen/Jahr behandelt; Definition und Annahmenstatus stehen im Manifest. Einheitenwechsel erfolgt genau einmal.
- Erwartete Mengen: Bilanzprüfung mit `atol=1e-8, rtol=1e-10`; realisierte ganzzahlige Mengen: exakte Gleichheit.
- Fehlende räumliche Zuordnung bleibt in einem mengenwirksamen Restkonto sichtbar. Keine erfundenen Haushalte, Gebäude oder Messwerte.
- Kein empirischer Genauigkeitsnachweis aus auferlegten DHL-Ankern, Modellannahmen oder Monte-Carlo-Quantilen.
- Baseline importiert keine experimentellen Module. Fachfunktionen sind ohne CLI, Fit oder Dateischreiben aufrufbar.

## 1. Mengenmodell und Anbieter

### Gemeinsame Markt-/B2B-Bilanz

Für jedes Jahr und den explizit angegebenen Geltungsraum gelten Marktanteile `m[c]`, Anbieter-B2B-Anteile `q[c]` und Gesamt-B2B-Anteil `b`:

```text
sum(m) = 1
dot(m, q) = b
r[business,c] = m[c]*q[c]/b
r[private,c] = m[c]*(1-q[c])/(1-b)
```

Bei leerem Segment wird dessen bedingtes Profil nicht zur Mengenerzeugung verwendet. Für aktive Segmente summieren sich Profile auf eins. Null-Marktanteile bleiben null; kein pauschaler Mindestanteil je Anbieter.

Bei `b=0` ist `q=0` für positive Marktanteile und nur das private Profil aktiv; bei `b=1` entsprechend `q=1` und nur das geschäftliche Profil. Grenzen müssen auch diese Fälle zulassen. Bei positiver regionaler DHL-Beobachtung ist `m_DHL=0` unzulässig. Jeder positive DHL-PLZ-Wert erfordert einen positiven lokalen DHL-Anteil; eine komplett leere Referenzregion ist ein Fehler.

Zielpriorität: Markt-/B2B-Reihen sind dokumentierte Referenzziele; `q` wird durch Minimierung von `sum(((q-q_prior)/q_scale)**2)` unter Gleichheitsbedingung und Grenzen abgestimmt. `q_scale>0` beschreibt die relative Änderbarkeit. Machbarkeit wird vor dem Solver durch `dot(m,lower)<=b<=dot(m,upper)` geprüft. Unvereinbare Eingaben führen zu einem diagnostizierten Fehler, nicht zu heimlicher Lockerung oder wiederholtem Neuziehen. Die Übertragung nationaler Anteile auf Hannover ist eine eigene Annahme.

### Referenzniveau

Strukturelle Potenziale sind `U_private=population` und je Betrieb `U_business=1+0.1*employees**power`, zunächst `power=1`. Branchenmultiplikatoren sind optional, positiv und standardmäßig eins. Ungültige Beschäftigtenzahlen erhalten einen Qualitätsstatus und keine stillen Nullwerte.

Aus den Potenzialen pro PLZ wird `b_p(k)=k*U_business,p/(U_private,p+k*U_business,p)` gebildet. Mit `s_DHL,p=(1-b_p)*r_private,DHL+b_p*r_business,DHL` ist `T_p=DHL_p/s_DHL,p`. Ein gemeinsamer positiver Faktor `k` wird so bestimmt, dass `sum(T_p*b_p)/sum(T_p)=b`. Randfälle nur privaten oder nur gewerblichen Potenzials bleiben explizit; positive Beobachtung ohne unterstützendes Potenzial/Anbieterprofil stoppt die Abstimmung mit Diagnose. Die Lösung und Grenzen werden protokolliert.

Die Referenzmenge pro PLZ und Segment wird nach den jeweiligen Standortpotenzialen verteilt. Es gibt keine exakte Anpassung einzelner Straßen. Das Ergebnis liefert Referenz-Jahresniveau `A_2021` und getrennt normierte historische Standortanteile `H[i,s]`. Beide sind aus DHL und Marktannahmen abgeleitet, keine unabhängigen Gesamtmarktbeobachtungen.

`anchor_plz` enthält sämtliche PLZ der beibehaltenen, gültigen DHL-Beobachtungen innerhalb des expliziten regionalen Polygon-Scope, einschließlich berichteter Nullmengen. Fehlende/negative Beobachtungswerte stoppen den Referenzlauf; außerhalb des Scope liegende und über der Schwelle ausgeschlossene Werte stehen getrennt im Scope-Ledger. Positive DHL-Menge ohne fachliches Potenzial stoppt ebenfalls, sie wird nicht aus `A_2021` entfernt. Standorte mit eindeutiger PLZ und fachlichem Potenzial bleiben im kanonischen Index auch ohne nutzbare Punktgeometrie; ihre Menge steht als `unlocated` im Restkonto. Standorte ohne eindeutige PLZ bleiben als nicht zugeordnetes Potenzial inventarisiert und bekommen keine erfundene Paketmenge. `H` und `S` besitzen identische `(site_id,segment)`-Indizes; strukturelle Nullen sind zulässig.

Ungültige Beschäftigtenzahlen innerhalb des Modell-Scope stoppen die Potenzialstage mit Quellkennungen; die erste Version imputiert diese nicht. Das Fundament muss `invalid_employees` erhalten oder es aus dem gespeicherten numerischen Wert erneut ableiten. Ein Standort ohne Geometrie darf nur dann im mengenwirksamen Index bleiben, wenn seine PLZ unabhängig eindeutig belegt ist. Die räumlich allokierbare Teilmenge verlangt zusätzlich eine gültige Punktgeometrie. Restkonten zu bereits geschätzten Mengen und Inventare noch nicht zugeordneter Potenziale sind getrennte Produkte. `A_2021,p=313*T_p`; Segmentmengen sind `A_2021,p*b_p` und `A_2021,p*(1-b_p)`.

Die `k`-Lösung erfolgt in `eta=log(k)` mit `scipy.optimize.brentq` auf `[-30,30]`, `xtol=1e-12` und abschließendem B2B-Residuum <=1e-10. Der Funktionswert ist `F(eta)=sum(T*b_p)/sum(T)-b`. Endpunktwerte und Unterstützung werden vorab geprüft. Bei mindestens einer positiv beobachteten gemischten PLZ und gültigen DHL-Profilen ist der erreichbare B2B-Anteil monoton steigend: der private Summand nimmt mit `k` ab, der geschäftliche zu. Ohne gemischte Unterstützung kann er konstant sein; trifft dieser konstante Wert das Ziel, wird eindeutig `k=1` gewählt, sonst ist die Konfiguration unzulässig. Fehlender Vorzeichenwechsel innerhalb der numerischen Grenzen wird mit erreichbarem Bereich diagnostiziert. Bei `b=0/1` wird der aktive Segmentfall direkt geprüft und berechnet. Im Ledger stehen `k`, Endpunktwerte, erreichbarer Bereich, Iterationen und Residuum.

## 2. Datenalter: Was das DHL-Gewicht verändert

Ein gemeinsamer räumlicher Einflussparameter wirkt auf beide Segmente. Für das Zieljahr werden strukturelle Standortanteile `S[i,s,y]` ausschließlich aus Standortpotenzialen und gegebenenfalls explizit gelieferten Bestandsänderungen gebildet. Sie werden je Segment über denselben Bezugsraum wie `H` normiert. Diese Alternative verwendet keine DHL-Restfehler, PLZ-Korrekturen, Logistik-OSM-Merkmale oder experimentell angepassten Koeffizienten.

```text
w(y) = f + (w0-f)*2**(-(y-2021)/h)       # Modus half_life
p[i,s,y] = w(y)*H[i,s] + (1-w(y))*S[i,s,y]
A[y] = A_2021 * V[y]/V[2021]
A[y,business] = b[y]*A[y]
A[y,private] = (1-b[y])*A[y]
lambda[i,s,c,y] = A[y,s]*p[i,s,y]*r[s,c,y]
```

Damit bleiben regionale Jahres-, Segment- und Anbietermengen konsistent. Anbieterprofile werden vor dieser Multiplikation abgestimmt. Nach der Mischung wird kein alter PLZ-/Straßen-DHL-Anker erneut auferlegt. Auch bei `w=1` kann eine Änderung des B2B-Anteils das zusammengesetzte räumliche Muster verändern; erhalten bleiben dann die historischen Muster innerhalb der Segmente.

Unterstützte Gewichtsmodi:

| Modus | Vertrag |
|---|---|
| fixed | Ein Wert `[0,1]` für alle angeforderten Jahre. |
| half_life | `half_life_years>0`, `0<=floor<=initial_weight<=1`; Jahre ab 2021. |
| yearly | Für jedes angeforderte Jahr expliziter Wert `[0,1]`; fehlende Jahre sind ein Fehler, keine implizite Extrapolation. |

`w=0` entfernt nur den historischen räumlichen Anteil. Das regionale Niveau stammt weiterhin aus DHL. Vollständiger Ersatz des Niveaus ist nur durch `regional_level.mode=external_annual_series` mit expliziter Jahresmengenreihe, Einheit, Herkunft und gleichem räumlichen Scope möglich. In diesem Modus wird kein zusätzlicher nationaler Wachstumsfaktor angewendet. Es wird keine solche externe Reihe beschafft oder erfunden.

Vergleichskonfigurationen: `memory-fixed` mit `w=1`, `memory-slow` mit Halbwertszeit 20 Jahren und `memory-fast` mit 5 Jahren, jeweils `initial_weight=1,floor=0`. Die Zahlen sind Sensitivitätseinstellungen, nicht kalibrierte Parameter. Die feste Referenz bleibt Standard, bis ein anderer Lauf explizit ausgewählt wird. Szenarien werden nicht ohne Wahrscheinlichkeitsannahme zu einem Intervall zusammengelegt.

Alle Zieljahre müssen >=2021 sein. Optionale Bestandsänderungen haben `site_id,year,multiplier`, eindeutige Schlüssel, bekannte Standort-IDs und endliche nicht negative Multiplikatoren. Fehlende Zeile bedeutet eins. Es werden keine neuen Gebäude erfunden. `S` wird erst nach Anwendung der Multiplikatoren normiert. Ein komplett entfallenes Segment mit positiver Zielmenge ist unzulässig; ein leer werdendes einzelnes PLZ-Segment erhält im strukturellen Endpunkt null. Historische Anteile können bei `w>0` trotzdem positiv bleiben: dieser Regler beschreibt alternative räumliche Gewichte und keine garantierte Standortschließung. Echte Schließungen mit Ausschluss aus sämtlichen Endpunkten sind kein Teil dieser Bestandsoption.

## 3. Kalender und tägliche Geografie

Ein normiertes Kalenderprofil je Jahr/Segment liefert Tagesgewichte. Der vorhandene Swiss-Proxy wird mit Herkunft übernommen; Monatsgewichte bleiben bei vollständigem Wochenprofil neutral. Ein Monatsprofil ohne Wochenprofil ist ebenfalls zulässig. Wochentage, Feiertage, Schaltjahre, ISO-Woche 53 und Teilzeiträume werden explizit geprüft.

Jährliche Standortanteile und Tageskalender bestimmen `baseline_expected`. Ein gemeinsamer und ein segmentspezifischer, auf Erwartungswert eins zentrierter Mengenfaktor liefert die bedingte Tagesmenge. Der Standard enthält keine zusätzliche jährliche Zufallsschicht, sobald die äußere Monte-Carlo-Ebene Wachstum/Niveau variiert; ein bewusst zusätzlich aktivierter Jahreseffekt benötigt eine getrennte Bedeutung und Kennzeichnung.

Zwei räumliche Varianten werden implementiert und verglichen:

- `dirichlet`: Zufällige PLZ-Anteile und darin Standortanteile, mit getrennten positiven Konzentrationen nach Ebene/Segment. Nur positive Unterstützung wird gezogen; strukturelle Nullen bleiben null. Dies ist die direkte kontrollierte Weiterentwicklung der alten Idee.
- `correlated`: Ein koordinatenbasiertes räumliches Feld mit `length_scale_m>0`, `log_sigma>=0`, `0<=rho<1` und festem Kalenderanker. Nachbarn können über PLZ-Grenzen hinweg gemeinsam schwanken. Die regionalen Segmentanteile werden nach Multiplikation normiert; es gibt keine anschließende Fixierung der PLZ-Mengen.

Das korrelierte Feld verändert ohne Korrektur eventuell die mittleren Standortanteile. Deshalb werden Ausgangsgewichte auf einer festgelegten Kalibrierungsstichprobe numerisch auf die Zielanteile eingestellt und auf anderen Seeds geprüft. Akzeptanz: totale Variationsdistanz der mittleren Anteile zum Ziel <=0,01 je Segment; PLZ-Abweichungen <=0,005 absolut zuzüglich dreifachem Monte-Carlo-Standardfehler. Reicht die Stichprobe für diesen Nachweis nicht, lautet der Status `mean_preservation_unresolved`; kein stiller Erfolg. Ein solcher Lauf bleibt als Diagnose auswertbar, ist aber keine freigegebene Baseline-Variante. Diese Toleranzen sind technische Abnahmekriterien, keine empirisch bestimmten Schwankungsstärken.

Die Korrektur wird pro eindeutiger Zielverteilung `p[:,s,y,outer_id]` und Feldparametern berechnet. Ein nur für `H` berechneter Korrekturvektor ist für andere Blends ungültig. Ihr Cache enthält Hashes von Zielgewichten, Standortindex/Koordinaten, Parametern sowie Kalibrierungs-/Prüfdesign. Für `unlocated`-Standorte wird kein räumliches Feld erfunden: ihr gemeinsamer Zielanteil bleibt erhalten, das Feld wirkt auf die lokalisierte Teilmenge; die Ganzzahlziehung darf auch die Restmenge variieren. Der Monte-Carlo-Pilot verwendet zunächst `dirichlet`; `correlated` wird dort erst nach bestandener zielabhängiger Mittelwertprüfung zugelassen.

Die Kalibrierung verwendet explizit `calibration_draws=512`, `validation_draws=2048`, getrennte deklarierte Seeds, `max_iterations=50` und `calibration_algorithm_version=1` als technische Startwerte. Bei ungenügender Präzision werden beide Stichproben deterministisch erweitert und diese Erweiterungen gespeichert. Die Prüfung verwendet unabhängige stationäre Felddraws, nicht aufeinanderfolgende korrelierte Tage als angeblich unabhängige Stichprobe.

Danach wird die regionale Tageszahl einmal gezogen und bedingt multinomial auf Standorte und Anbieter verteilt. Keine zweite unabhängige Erzeugung von Anbieter-Gesamtmengen. Standardmäßig kein zusätzliches tägliches Anbieterrauschen. Das stochastische Standardregime erhält Jahresmengen im Erwartungswert. Der optionale Modus `fixed_annual` rundet die Jahresmenge pro Segment einmal und verteilt sie auf den ganzen Kalender; eine Datumauswahl ist danach ein Ausschnitt desselben Jahreslaufs. Ein separater Festwochenmodus ist nicht Teil der ersten Implementierung.

Im Standardmodus `expected_annual` gilt `N_day ~ Poisson(A_s*calendar_day*shock_day)`. Im Modus `fixed_annual` wird zuerst die gesamte Jahresmenge mit Largest Remainder auf die Segmente gerundet, sodass deren Summe gleich `round(A_y)` ist. Danach werden je Segment die Gewichte `calendar_day*shock_day` über alle Tage des Jahres normiert und `N_year,s` multinomial auf Tage verteilt. Standort-/Anbieterziehungen folgen anschließend. Diese Jahresnormierung kann die unbedingten mittleren Tagesanteile gegenüber dem reinen Kalender leicht verschieben; der Bericht weist dies aus und verspricht für diesen Modus nur die feste Jahresbilanz. Ein fixes jährliches Anbietertotal wird nicht zugleich versprochen.

## 4. Monte Carlo und globale Sensitivität

### Getrennte Zufallsebenen

Äußere Ziehungen bestimmen zusammenhängende Modellpfade: optional Niveau-Multiplikator, jährliche Wachstumsabweichung, B2B-/Anbieterprofilabweichungen, Betriebsgrößeneffekt, Halbwertszeit und Parameter der täglichen Schwankungen. Innere Ziehungen erzeugen Tagesrealisierungen bei denselben äußeren Annahmen.

Beispiel einer Wachstumsabweichung: `A_y_draw=A_y_central*exp(delta_g*(y-2021))`. `delta_g` wird pro äußerem Pfad einmal gezogen, nicht jährlich unabhängig. Bei `external_annual_series` muss die Unsicherheitskonfiguration explizit `external_log_growth_delta` wählen; der nationale Wachstumskanal ist gesperrt. Ein lognormaler Niveau-Multiplikator wird bei gewünschtem Mittelwert eins mit `exp(z*sd-0.5*sd**2)` zentriert. Kurvenensemble und Mittelwert sind verschieden: Symmetrische Log-Wachstumsabweichungen erhalten den Medianpfad, nicht automatisch dessen arithmetischen Mittelwert.

Unabhängige latente Parameter werden per SciPy Latin Hypercube im Einheitswürfel erzeugt und durch deklarierte Verteilungen transformiert. Abhängige Anteile entstehen erst anschließend durch Logit-/Softmax-Transformation und die fachliche Mengenabstimmung. Marktanteile werden niemals unabhängig als sieben freie Prozentwerte gezogen. Gleiches gilt für B2B und Anbieter-B2B-Profile. Infeasible Punkte erscheinen mit Fehlergrund; sie dürfen insbesondere bei Sensitivitätsdesigns nicht still ersetzt werden. Erst ein vollständig machbarer Parameterraum darf als vollständige Analyse ausgewertet werden.

Jeder Parameter besitzt Name, Einheit, Verteilung, Grenzen, Zentralwert, Herkunft `assumed|estimated`, aktiven Szenariomodus und Transformationsbeschreibung. Im normalen Baseline-Lauf ist äußere Unsicherheit deaktiviert. Beispielbereiche im Pilot sind ausdrücklich Annahmen: Halbwertszeit 5–20 Jahre, Log-Wachstumsabweichung -0,01 bis +0,01 pro Jahr, Betriebsgrößenexponent 0,5–1. Weitere Parameter werden nur mit expliziten Bereichen aktiviert.

Das kanonische Feld für diese Herkunft heißt `source_status`. Designmetadaten enthalten außerdem `rng_version`, `schema_version`, `analysis_reference_stage='reference'` und `baseline_fingerprint`. Ein kompletter äußeren Pfad behält dieselbe Parameterziehung für alle seine Jahre.

Der Pilot startet mit 100 äußeren Ziehungen und 10 inneren Wiederholungen für einen beschränkten Datumausschnitt. Es werden nicht sämtliche Standortzeilen jeder Wiederholung gespeichert: Standard sind Region/PLZ/Segment/Anbieter-Aggregate, Kennzahlen der Standortaktivität und wenige konfigurierte Detailziehungen. Wiederholungszahlen sind kein Genauigkeitsversprechen. Anschließend werden Stichproben verdoppelt, bis Änderungen der berichteten P10/P50/P90 und deren Monte-Carlo-Fehler für die gewählten Kennzahlen innerhalb einer vorab festgelegten Berichtstoleranz liegen. Standard: absolute Quantiländerung <=1 % der jeweiligen zentralen Regionalmenge; auf PLZ-Anteilsebene <=0,005, jeweils in zwei aufeinanderfolgenden Erweiterungen. Ein Budgetende mit unerfülltem Kriterium wird ausgewiesen.

Monte-Carlo-Fehler der äußeren LHS-Stichprobe wird aus unabhängig randomisierten Blöcken gleicher Größe beurteilt, nicht durch einen IID-Bootstrap der abhängigen Zeilen eines LHS-Blocks. Der erste Block definiert `design_block_size`; jede Erweiterung erzeugt weitere gleich große unabhängig gesäte Blöcke. Ab mindestens acht Blöcken wird ein ausdrücklich näherungsweiser Blockbootstrap mit 200 Wiederholungen für die kombinierten Quantilschätzer verwendet: komplette Blöcke einschließlich aller zugehörigen Inner-Pfade resampeln, nichts innerhalb eines LHS-Blocks entstratifizieren. Bei weniger Blöcken ist der äußere Quantil-MCSE `unavailable`; eine stabile Punktkurve allein gilt dann nicht als erfüllte MCSE-Abnahme. Innerer Simulationsfehler wird getrennt innerhalb eines äußeren Pfads geschätzt. Die Mindestzahl ist ein technischer Startwert, keine Garantie statistischer Präzision.

### Sensitivitätsverfahren

Zuerst Morris-Screening über den gesamten deklarierten Parameterraum; numerische Eingaben werden auf `[0,1]` skaliert. Startdesign: 20 Trajektorien, 4 Gitterstufen, Schrittweite 2/3. Diskrete Modellvarianten und Gewichtsmodi werden separat verglichen. Morris liefert `mu`, `mu_star`, `sigma` je Kennzahl und Parameter; die Größen werden nicht als erklärte Varianzprozente dargestellt. Für tägliche stochastische Kennzahlen werden dieselben inneren Seeds an den benachbarten Designpunkten verwendet und deren Monte-Carlo-Fehler mit ausgegeben. Deterministische Jahreskennzahlen brauchen keine inneren Ziehungen.

Eine detaillierte Sobol-Analyse ist eine optionale zweite Ausbaustufe nach dem Screening, kein Pflichtteil der ersten Lieferung. Sie darf bei unabhängigen latenten Eingaben interpretiert werden; eine Rangliste dieser Eingaben ist nicht automatisch eine eindeutige kausale Aufteilung zwischen den daraus gemeinsam abgeleiteten Marktanteilen.

Kennzahlen: jährliche Regionalmenge; PLZ-Anteile; B2B- und Anbieteranteile; räumliche Distanz zum Referenzmuster `0.5*sum(abs(p-H))`; tägliche Streuung der Mengen und Anteile; Anteil aktiver Standorte; zeitliche und räumliche Korrelation. Wochen-/Monatsquantile entstehen durch Summieren innerhalb eines Pfades vor der Quantilberechnung. Quantile werden nicht addiert. Unsicherheit der mittleren Nachfrage, Tagesvariation und kombinierte Verteilung erhalten getrennte Beschriftungen.

Verbindliche Intervallprodukte: (a) Quantile von `E[Y|theta]` über äußere Pfade; bei nicht analytisch verfügbaren Kennzahlen mit separat ausgewiesenem Schätzfehler der inneren Mittelwerte, (b) Prozessquantile bei festem zentralen Parametervektor und (c) kombinierte Quantile mit gleichem Gewicht jedes äußeren Pfades. Der erste Runner verlangt dafür dieselbe vollständige Anzahl innerer Ziehungen je äußerem Pfad; unvollständige Ensembles erhalten keine vollständigen kombinierten Quantile. Diese Produkte heißen annahmebedingte Simulationsintervalle. Jedes Morris-Design protokolliert `K`, geplante `20*(K+1)` Auswertungen, gültige/ungültige Punkte, Trajektorien, Kopplungs-Seed-IDs und MCSE. Eine unzulässige Trajektorie wird nicht durch Austausch einzelner Punkte repariert; ein neuer zulässiger Parameterraum erzeugt eine neue Designversion.

## 5. Datenverträge und reproduzierbare Ausführung

Kanonische Tabellen verwenden `site_id`, `plz` als String mit führenden Nullen, `segment`, gegebenenfalls `carrier`, `year`, ISO-Datum `date`, `outer_id`, `inner_id`. Standortgeometrie in EPSG:25832 liegt einmal in einer eigenen Tabelle. Mengenfelder heißen `baseline_expected`, `conditional_expected`, `count`; Restkonten tragen `allocation_status` und werden in allen Mengenbilanzen berücksichtigt.

Run-Produkte: `config.resolved.json`, `sources.json`, `runtime.json`, `run.json`, `stage_manifest.json`, `reference_postal.parquet`, `annual_summary.parquet`, `daily_aggregates.parquet`, `unallocated.parquet`, `checks.json`, `report_data.json`. Analysen ergänzen `parameter_draws.parquet`, `draw_status.parquet`, `quantiles.parquet`, `sensitivity.parquet`, `convergence.json`. CSVs 00–06 stehen unter `legacy/` und sind über ein versioniertes Schema beschrieben. Die Anzeige erfolgt im gemeinsamen Dashboard gemäß Abschnitt 5a.

Zusätzlich verpflichtend: `reference_sites.parquet` mit `site_id,plz,segment,weight,historical_share,structural_share,reference_annual,allocation_status`; `reference_reconciliation.json` mit Markt-/Profilinputs, angepassten Werten und Solverdiagnosen; `calendar_profile.parquet` mit `date,year,segment,calendar_weight`. `daily_aggregates` besitzt den Schlüssel `(date,outer_id,inner_id,plz,segment,carrier,allocation_status)`; `unallocated` ist ein separat referenzierter Ausschnitt dieser Bilanz bzw. ein Adapter-Restkonto, kein zusätzlich zu addierender zweiter Bestand. `checks` enthält `check_id,stage,grain,status,expected,observed,absolute_error,atol,rtol,artifact_fingerprint,residual_included`. Rohdatenpotenzial-Probleme stehen in `source_quality.parquet` ohne erfundene Paketanzahl.

`reference_sites` enthält zusätzlich die unveränderlichen Merkmale `population,employees,branch`. Geometrie liegt einmal in `reference_geometry.parquet` mit `site_id,geometry`. Beide Dateien gehören zum Baseline-Fingerprint. `reference_postal` hat genau eine Zeile je PLZ mit `dhl_retained_mean,reference_annual,private_annual,business_annual,b2b_share,dhl_share`; beobachtete DHL-Mengen werden nicht pro Segment dupliziert und versehentlich addiert. `allocation_status` im Nachfragebestand ist `located|unlocated`; Adapter-Restkonten verwenden zusätzlich `legacy_unmapped|legacy_ambiguous`. Nicht zugeordnete Rohpotenziale bleiben ausschließlich im Qualitätsinventar. Detaildateien enthalten `site_id`; Aggregatdateien enthalten keine Standortkennung. Detaildaten werden nur für explizit ausgewählte Pfade geschrieben.

RNG verwendet eine versionierte SHA-256-Kodierung stabiler Schlüssel, niemals Python `hash()` oder Zeilenpositionen als fachliche Identität. Schlüssel bestehen aus Master-Seed, Kanal, Szenario/Pfad, innerer Wiederholung, Datum und gegebenenfalls Objekt. Sortierung erfolgt kanonisch. Für Sensitivitätsvergleiche ersetzt eine gemeinsame `coupling_id` die Designpunktkennung im inneren Zufall. In Monte Carlo bleiben äußere Pfade standardmäßig unabhängig. Serieller Lauf, Parallelisierung, Reihenfolge, Ausschnitt und Wiederanlauf müssen dieselben zugehörigen Ergebnisse liefern.

RNG-Version 1: Stringwerte/-keys Unicode-NFC, UTF-8-JSON mit `sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False`; Felder `rng_version,seed` plus benannte Kontextkeys. SHA-256 vollständig als acht little-endian uint32 in NumPy SeedSequence, Bitgenerator explizit PCG64. Nicht vorhandene optionale Kontextkeys werden weggelassen. Generator-Methoden sind normal/poisson/multinomial/dirichlet. Exakte NumPy-/SciPy-Versionen werden gespeichert und sind Teil des Cachevertrags; Bitidentität über andere Bibliotheksversionen wird nicht zugesagt.

Äußere Ziehungen werden einmal als versioniertes Experimentdesign gespeichert und beim Resume geladen. Eine Erweiterung um neue Ziehungen schreibt einen zusätzlichen Designblock mit eigener Kennung; bestehende Latin-Hypercube-Punkte werden nicht neu erzeugt. Die vereinigten Blöcke werden als solche dokumentiert, nicht als ein einziges neues Latin-Hypercube-Design ausgegeben.

Räumlich/zeitliche Zustände besitzen einen festen Kalenderanker. Längere Horizonte dürfen nicht durch täglich wiederholtes Neuberechnen sämtlicher Vorjahre quadratisch teuer werden: Zustände werden sequentiell fortgeschrieben und mit geprüftem Cache gespeichert. Cache-Schlüssel enthalten konsumierte Eingabehashes einschließlich SHP-Nebenfiles, aufgelöste Parameter, rekursive Code-/Template-Hashes, RNG-/Schemaspezifikation, Designblock und Paketversionen. Geschrieben wird atomar; nur vollständig geprüfte Stages/Pfade sind wiederverwendbar.

`cache_root` ist ein explizit aufgelöster Konfigurationspfad; Standard ist `<output_dir>/.stage-cache`. Gemeinsame unveränderliche Einträge liegen unter `<cache_root>/<stage_name>/<fingerprint>/`. `resolve_stage` erhält `cache_root` unabhängig von `run_dir`; der Run-Manifest speichert Cachepfad, Fingerprint, Abhängigkeiten und geprüfte Artefakthashes. Benannte finale Referenz-/Reportinput-Dateien werden geprüft in den Run kopiert, damit Runs eigenständig lesbar bleiben; semantische Cachedateien werden nie durch Renderer geändert. Bei gleichzeitiger Publikation desselben Fingerprints darf nur ein gültiger Eintrag gewinnen; ein anderer Writer prüft und übernimmt diesen statt ihn zu überschreiben.

## 5a. Ein gemeinsames Dashboard mit Stage-Navigation

Nutzerergänzung vom 15. September 2026: Alle Auswertungen werden in einer gemeinsamen Oberfläche zusammengeführt. Es entsteht **ein Einstieg pro Output-Arbeitsbereich**, standardmäßig `<output_dir>/dashboard/index.html`, statt neuer Dashboardseiten je Stage oder Analyse. Der Pfad ist über `dashboard_root` konfigurierbar. Mehrere Runs sind in derselben Oberfläche auswählbar; Datum, Jahr, Segment und Anbieter sind gemeinsame Filter. Wechsel zwischen Stages bewahrt gültige Filter; unpassende Filter werden sichtbar zurückgesetzt.

Navigation: Übersicht → Daten/Qualität → Markt und B2B → regionale Referenz → Zukunft/DHL-Gewicht → Kalender → tägliche Mengen und Orte → Anbieter → Monte Carlo → Sensitivität → Prüfungen/Exporte. Es sind integrierte Ansichten mit gemeinsamen Komponenten, keine Sammlung von Links oder eingebetteten Einzeldashboards. Stage-Status `not_run|running|complete|failed|blocked` wird aus gespeicherten Manifesten abgeleitet; nicht berechnete Ergebnisse sind nicht mit Nullwerten zu verwechseln.

`report_data.json` ist der versionierte Darstellungsinput pro Run. `dashboard_root/report_catalog.json` registriert Runs und verknüpft Analyse-Runs über `baseline_fingerprint` mit der passenden Referenz. Monte Carlo und Sensitivität erscheinen so unter derselben Referenz, behalten aber eigene Run-ID, Parameter und Status. Fremde Referenzen/Scopes werden nicht still gemischt. Deep-Links verwenden denselben Einstieg, z.B. `index.html#run=example&stage=temporal`; die CLI nennt ausschließlich diesen Einstieg mit passendem Fragment.

Ein erfolgreicher Run oder explizites `baseline report` aktualisiert Run-Darstellungsinput, Katalog und gemeinsame Oberfläche. Ein Fehlerlauf ergänzt, soweit IO möglich ist, die Diagnoseansicht. Updates am gemeinsamen Katalog/Dashboard sind atomar und gegen konkurrierende Analyse-Writer abgesichert; semantische Run-Artefakte und Baseline-Fingerprints bleiben unverändert. Für Änderungen am semantischen Modell ist weiter ein neuer Run nötig. Browseröffnung verwendet denselben vorhandenen Dashboard-Tab, wenn verfügbar; bei Hintergrund-Stages öffnet sich kein neuer Tab.

Das Dashboard ist lokal nutzbar und benötigt keine externen CDNs. Der Renderer bindet kompakte Stage-Aggregate und Karten in die gemeinsame HTML-Ausgabe ein; sämtliche Standortzeilen aller Monte-Carlo-Draws werden weder eingebettet noch vorab geladen. Bestehende relevante Run-Berichte können über explizite, read-only Schemaadapter in den Katalog aufgenommen werden; unsupported Artefakte werden als solche kenntlich gemacht. Frühere HTML-Dateien bleiben als Archiv bestehen und werden nicht gelöscht. Neue Baseline-Stages schreiben keine separaten `temporal.html`, `carriers.html` oder Analyse-Dashboardseiten.

## 6. CLI und Migration

Geplante Befehle:

```text
hagrid-demand baseline run --config <baseline.json> --run-id <id>
hagrid-demand baseline simulate --config <analysis.json> --run-id <id>
hagrid-demand baseline sensitivity --config <analysis.json> --run-id <id>
hagrid-demand baseline report --run-dir <run>
```

Alle drei Laufbefehle erhalten `--resume`; bestehende Run-IDs ohne Resume werden abgelehnt. Resume verlangt identische relevante Hashes. Baseline-Run kann aus Rohdaten starten oder ein durch Hashes geprüftes Datenfundament verwenden. Ein Legacy-Output-Import dient der Vergleichsdiagnose; die fertige Baseline berechnet 00–06 und den Tagesgenerator selbst und benötigt dafür keine früheren Modell-Runs.

`source_mode=raw|foundation_run` ist exklusiv. Im Rohmodus verwendet eine neutrale Aufbereitung die bestehenden Reader/Join-Regeln und gibt Tabellen zurück; die Baseline-Orchestrierung besitzt den Run-Zustand. Bestehende Fundamente werden auf Schema, CRS, Stage-Abschluss und tatsächliche Artefakthashes geprüft. Ihr bekannter Annahmenstatus ist kein stilles Freigabezertifikat. Eingebettete Originalwerte aus 00–02 werden als versionierte JSON-Quellen übernommen, 03 liest die vorhandene Schweizer XLSX. Alte Prognose-CSV-Dateien sind keine Berechnungsvoraussetzung.

`baseline run` akzeptiert `output_scope=reference|daily`; reference liefert den Zwischenmeilenstein `complete_reference`, daily den vollständigen geprüften Nachfragepfad. `simulate` und `sensitivity` benötigen in ihrer Config `baseline_run` und dessen `baseline_fingerprint`; sie prüfen und laden die eingefrorene Referenz einschließlich H, Potenzialen und Scope. Sie berechnen keine neue historische Referenz aus anderen Rohpfaden. Ziehungen des Betriebsgrößenexponenten verändern in diesen Analysen ausschließlich den zukünftigen strukturellen Endpunkt S. `report` liest semantische Artefakte nur und darf ausschließlich Darstellung/Render-Metadaten erneuern.

Die Kompatibilität 04–06 ist eine konfigurierbare Exportstage und benötigt einen gehashten `legacy_contract`: statische Raster-IDs/Geometrien und Straßen-Sample-IDs/Geometrien. Dieser wird einmal aus bestehenden Inputrastern und den unveränderlichen ID-/Geometriefeldern der bisherigen Samples erstellt; seine Herkunft aus dem früheren Sampleexport wird dokumentiert. Alte geschätzte Mengen werden daraus nicht als Nachfragequelle übernommen. So ist kein früherer Fit zur Laufzeit erforderlich, wohl aber ein expliziter Kompatibilitätsvertrag. Bei aktivierter Stage und fehlendem Vertrag ist der Lauf fehlerhaft; bei deaktivierter Stage werden keine vollständigen Legacy-Exporte behauptet.

Nur `simulate --resume --extend-design N` darf bei identischem semantischem Basiskonfigurationshash N weitere äußere Ziehungen als neuen Block anhängen; Änderungen anderer Parameter benötigen eine neue Run-ID. Morris-Designs bleiben unveränderlich; geänderte Grenzen erhalten eine neue Run-ID. Änderungen der gewünschten Ziehungsanzahl in der Config sind keine zulässige Resume-Abkürzung. Gemeinsame Stage-Caches dürfen zwischen Run-IDs nur bei identischem Stage-Fingerprint wiederverwendet werden.

`N` muss ein positives Vielfaches der gespeicherten `design_block_size` sein; entsprechend werden ein oder mehrere neue Blöcke erzeugt. Bestehende Blöcke werden niemals vergrößert oder neu randomisiert.

Enthält ein Monte-Carlo-Design unzulässige äußere Ziehungen, lautet der Status `invalid_infeasible_design`; es gibt keine finalen kombinierten Quantile. Bei ungültigem Morris-Punkt wird keine vollständige Rangliste ausgegeben. Diagnose- und Teilresultate bleiben mit Kennzeichnung erhalten; verworfene Ziehungen werden nicht aus der Statistik unsichtbar entfernt.

Neue Module liegen in `baseline/`, neutrale Bausteine in `common/`, alte Verbraucheradapter in `compatibility/`, freie Fits/OSM/Modellsuche in `experimental/`. Bisherige Befehle bleiben ausdrücklich als experimentelle Kompatibilitätswege erhalten. CLI-Imports erfolgen erst im jeweiligen Befehlszweig; `baseline --help` darf keine Fit-/Scikit-learn-Abhängigkeit laden.

## 7. Umsetzung und unabhängige Reviews

Die Umsetzung wird in drei aufeinander aufbauende Pläne geteilt: (1) deterministische Baseline und Abgrenzung, (2) Kalender/Tagesvariation/Exporte, (3) Datenalter/Monte Carlo/Sensitivität. Jeder Teil liefert ein ausführbares und separat testbares Ergebnis. Die finale Baseline umfasst alle drei Teile.

Wie vom Nutzer gewünscht: Terra-Subagent pro klar begrenztem Implementierungspaket; der Hauptagent koordiniert Schnittstellen und Integration. Ein anderer Terra-Agent prüft zuerst die Erfüllung der Spezifikation und danach Codequalität, Tests und Regressionen. Der Implementierer ersetzt keinen unabhängigen Reviewer. Unabhängige Pakete können parallel laufen; gemeinsame Dateien besitzen jeweils einen Schreiber. Keine weiteren Modellwechsel ohne konkrete Begründung oder Nutzerwunsch.

Ein Paket gilt erst nach relevanten bestandenen Tests und abgearbeiteten Review-Befunden als erledigt. P0/P1 blockieren die Integration; P2 werden behoben oder mit begründetem, sichtbarem Restrisiko dokumentiert. Nach Änderungen an gemeinsamer Mathematik/IO/CLI wird die gesamte Demand-Testsuite ausgeführt. Ein echter kleiner Rohdatenlauf und der Run-Bericht schließen jeden Teilplan ab. Vollregionale Ensembles folgen erst nach kleinem Funktions- und Laufzeitnachweis.

## Methodische Referenz

Die Trennung zwischen Unsicherheitsfortpflanzung, globaler Sensitivität und kostengünstigem Morris-Screening orientiert sich an der [Methodenübersicht des Joint Research Centre](https://joint-research-centre.ec.europa.eu/sensitivity-analysis-samo/methods_en). Konkrete HAGRID-Gewichte, Verteilungen, Halbwertszeiten und Toleranzen sind hier entworfene Modellannahmen bzw. technische Kriterien und werden durch diese Quelle nicht empirisch bestätigt.
