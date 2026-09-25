# Gate: Verweise auf das alte Modullayout (Spec 2026-09-21 §6.6)

Zweck: repo-weiter Grep über alle getrackten Dateien nach `hagrid/` statt `hagrid/simulation/`.
Die fünf Muster (a1, a2, b, c, d) stehen in `tools/gate-old-module-path.patterns`, eine PCRE je
Zeile — `git grep -f` kennt keine Kommentarzeilen, deshalb steht die Erklärung hier.

Aufruf aus Git Bash (eine Zeile):

    git grep -nIP -f tools/gate-old-module-path.patterns -- . :!docs/superpowers :!docs/METHODS-LOG.md :!docs/BACKLOG-DONE.md :!docs/legacy :!.superpowers :!analysis/hannover/sweep/provenance :!tools/Migrate-Common.ps1 :!tools/migrate-*.ps1 :!tools/Test-Migrate*.ps1 :!tools/check-run-scripts.ps1 :!tools/Test-CheckRunScripts.ps1 :!tools/gate-old-module-path.* :!analysis/common/run-monitoring/Test-Installers.ps1

Positivkontrolle: bei `2dd4618` (vor dem Umzug) müssen die Muster treffen — mit dieser Allowlist
gemessen a2 34 Dateien, b 2, c 1, d 5. Null Treffer dort heißt kaputtes Gate, nicht sauberes Repo.

WARNUNG: inline getippte Muster verlieren im Bash-Werkzeug eines Agenten eine Backslash-Ebene
(`[\\/]` wird zu `[\/]`) und treffen dann nichts — immer mit `-f` und dieser Datei laufen.
