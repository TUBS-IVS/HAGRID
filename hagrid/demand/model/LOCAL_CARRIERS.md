# Lokale Anbieterprofile

`configs/local-carriers.json` aktiviert drei gemeinsame Bausteine:

1. Positive, einstellbare Branchenmultiplikatoren auf die bisherigen B2B-Anbieterprioren. Die Beispielwerte sind vorsichtige Hypothesen, keine beobachteten Branchenmarktanteile. Ohne Eintrag gilt Faktor 1.
2. Dauerhafte Anbieterpraeferenzen aus Standort-ID und Seed. Staerke separat fuer private und gewerbliche Empfaenger. Gleiche Standorte behalten ihre Praeferenzen ueber Tage und Realisierungen; ein anderer Seed erzeugt eine andere angenommene Praeferenzwelt. Die Standortreihenfolge spielt keine Rolle.
3. Bedingte PLZ-Korrektur: DHL-Rohmengen und Hermes-Raumprofil verschieben die Anteile. Hermes wird nur auf die vorherige Hermes-Gesamtmenge der abgedeckten PLZ skaliert. Korrekturstaerken sind separat einstellbar; Defaultbeispiel DHL 0.5, Hermes 0.3.

Branchen- und Praeferenzeffekte werden zunaechst so abgeglichen, dass die bisherigen Anbietergesamtmengen je Segment erhalten bleiben. Danach darf die Datenkorrektur diese Anbietergesamtmengen veraendern. Standortgesamtmengen bleiben in beiden Schritten unveraendert. Uebrige Anbieter teilen sich den Rest; negative Mengen sind ausgeschlossen.

`max_local_multiplier` begrenzt lokale Anpassungen gegenueber dem vorherigen Anbieterwert, standardmaessig auf das Intervall [1/1.5, 1.5]. Zusaetzlich duerfen die korrigierten beobachtungsbezogenen Anbieter zusammen hoechstens 98 Prozent der lokalen Gesamtmenge beanspruchen. Begrenzte PLZ erscheinen im Bericht. Beides sind Schutzannahmen gegen die Verwechslung von fehlender Gesamtnachfrage mit extremen Marktanteilen, keine geschaetzten Parameter.

Die Korrektur verwendet die Referenzbeobachtungen nach der Modellanpassung. Sie ist eine bedingte Rekonstruktion, kein neuer unabhaengiger Vorhersagetest. Alte Testmetriken gelten nicht fuer diese Korrektur. Insbesondere Sonderstandorte werden dadurch nicht erklaert. Zukuenftige Jahre uebernehmen die lokalen Beziehungen unter Anwendung der bestehenden relativen Anbieterentwicklung; dies bleibt eine Annahme.

## Ausfuehren

```powershell
python -m hagrid_demand predict --config hagrid-demand/configs/local-carriers.json --model-run hagrid-demand/runs/improved-seasonal-20260909 --run-id local-carriers-new
```

## Ausgaben

- `carrier_site_profiles.parquet`: je Standort urspruengliche, durch Branchen/Praeferenzen angepasste und lokal korrigierte Anbieteranteile.
- `carrier_local_diagnostics.csv`: Vorher-/Nachher-Mengen, lokale Anteile, Streuung der Standortanteile und Begrenzungen.
- `carrier_allocation.json`: Annahmen, Korrekturstaerken und betroffene PLZ.
- `carriers.html`: automatisch erzeugter Vergleich, aus dem Hauptdashboard verlinkt.
- Tagesdateien nutzen diese Standortprofile fuer erwartete Mengen und Paketziehungen. Die ungewichteten P10/P90 im Anbieterbericht zeigen Standortunterschiede, keine Unsicherheitsintervalle.

26 Tests pruefen unter anderem ID-stabile Praeferenzen, Mengenerhaltung, Zielmargen und die Kennzeichnung unvereinbarer Beobachtungen.
