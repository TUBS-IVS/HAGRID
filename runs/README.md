# runs/

Kampagnenskripte fuer Simulationslaeufe.

## Kurzer Ausgabe-Root

Wofuer: Der Laufordner `<Modulwurzel>\hagrid-matsim-output\<runId>_iter<N>_jsprit<M>\<runId>.<datei>` wird auf
manchen Rechnern zu lang (native SQLite scheitert bei 252 Zeichen, METHODS-LOG §2.51). Mit
`HAGRID_OUTPUT_ROOT` wandert nur der MATSim-Ausgabebaum auf eine kurze Wurzel. `hagrid-output/` (inklusive
jsprit-Plan-Cache unter `hagrid-output/shared/jsprit-cache`) bleibt unveraendert in der Modulwurzel.

Setzen: im Kampagnenskript vor dem Java-Aufruf

    set HAGRID_OUTPUT_ROOT=C:\hmo

Die Java-Systemeigenschaft `-Dhagrid.output.root=...` hat Vorrang vor der Umgebungsvariable. Leer oder nur
Leerzeichen zaehlt als nicht gesetzt (dann gilt der Default `hagrid-matsim-output`). Ein relativer Wert wird
gegen die Modulwurzel aufgeloest; die Bootstrap-Logs (`<Wurzel>\logs`) loesen ihn gegen das Arbeitsverzeichnis
auf, deshalb besser einen absoluten Pfad nehmen.

Folge: Werkzeuge mit `--run-dir` brauchen den neuen Pfad. Die alten Skripte unter `runs/` kennen ihn nicht und
suchen weiter unter `hagrid-matsim-output`.
