# Personenmerkmale plus raeumliche Restkorrektur

Der neue Vergleich kombiniert das einfache Nachfragemodell mit drei Personenmerkmalen: Anteil 25–44 Jahre, Anteil ab 65 Jahre und Erwerbstaetigenanteil. Eine regularisierte Regression erklaert logarithmische Trainingsresiduen. Optional korrigiert ein 5-km-Kernel anschliessend die verbleibenden raeumlichen Trainingsresiduen. Keine zurueckgehaltenen Zielwerte werden fuer diese Schritte verwendet. Dies sind aggregierte Nachfragekorrekturen, noch keine individuell geschaetzten Bestellentscheidungen.

```powershell
python -m hagrid_demand.model_search --person-hybrid-only --config hagrid-demand/configs/improved.json --persons parcel-demand-estimation/input/persons_total.csv --output hagrid-demand/runs/person-hybrid-new
python -m hagrid_demand.households --persons parcel-demand-estimation/input/persons_total.csv --output hagrid-demand/runs/household-audit-new
```

## Ergebnisse, KEP-Abgrenzung >1000 ausgeschlossen

| Modell | Mittlerer wMAPE, zufaellig | wMAPE, raeumliche Bloecke |
|---|---:|---:|
| Einfaches Modell | 17.03 % | 17.67 % |
| Bisherige raeumliche Korrektur | 15.79 % | 16.65 % |
| Personen plus Raum, starke Regularisierung | 15.36 % | 15.98 % |
| Innere Auswahl aus sechs Kandidaten | 15.51 % | 15.62 % |

Die starke Variante hat einen Bias von -4.78 % im zufaelligen Vergleich (vorher -3.59 %) und -0.65 % in der raeumlichen Pruefung (vorher -0.91 %). Die verschachtelte Auswahl erreicht -4.62 bzw. -2.03 %. Keine unabhaengige abschliessende Bestaetigung: wiederverwendete Daten, wenige PLZ, raeumliche Gruppen ohne Puffer. Kein automatischer Austausch des produktiven Gesamtmarkt-/Anbietermodells. Die exakte beobachtungsgebundene Rekonstruktion ist weiterhin separat.

## Haushaltsdaten

536496 von 1150862 Personen haben keine Household-Kennung. Von 33324 h_id-Werten mit bekannter Household-Zuordnung kommen 25803 in mehreren Haushalten vor. Ein globales Zusammenfassen anhand h_id ist deshalb unzulaessig. Die strengere Kombination Building+h_id liefert fuer die fehlenden Kennungen keine Kandidaten mit bekannter eindeutiger Household-Zuordnung.

Keine Kennungen wurden erfunden oder geaendert. Das Audit schreibt eine nachvollziehbare Kandidatentabelle und Herkunftshashes. Als Vorbereitung eines Haushaltsmodells sind nun die urspruengliche Synthesezuordnung beziehungsweise eine durch Gebaeude- und Haushaltsgroessen kontrollierte Neusynthese erforderlich. Fehlende Haushalte duerfen nicht als Einpersonenhaushalte behandelt werden.

47 Tests bestanden, einschliesslich Ausschluss der Testziele aus Personen-/Raumschaetzung und Schutz vor Zusammenfuehrung wiederverwendeter h_id-Werte.
