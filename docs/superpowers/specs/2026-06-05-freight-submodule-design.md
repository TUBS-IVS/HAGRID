# Design: Freight als Maven-Submodul in HAGRID

**Datum:** 2026-06-05  
**Status:** Approved  

## Ziel

Das MATSim freight-Contrib (`org.matsim.contrib:freight`) wird aus dem Maven-Repo herausgelöst und als editierbares Maven-Submodul direkt in das HAGRID-Repo integriert. Ziel ist es, freight für HAGRID-spezifische Anpassungen zugänglich zu machen und neue Upstream-Versionen bei Bedarf einspielen zu können.

## Repo-Struktur

```
HAGRID/
├── parcel-demand-2-matsim-pipeline/   (bestehendes Modul, minimale Änderungen)
├── freight/                            (neu — freight-Quellcode aus matsim-libs)
│   ├── pom.xml
│   └── src/
│       └── main/java/...
└── pom.xml                             (neues Root-POM, fasst beide Module zusammen)
```

## Maven-Konfiguration

### Root `pom.xml` (neu)

- `<packaging>pom</packaging>`
- Listet beide Module unter `<modules>`: `freight`, `parcel-demand-2-matsim-pipeline`
- Enthält gemeinsame Properties: `matsim.version`, `geotools.version`
- Enthält gemeinsame Repository-Definitionen (MATSim-Repo, pt2matsim)
- Beide Submodule erben über `<parent>`

### `freight/pom.xml` (neu)

- Erbt vom Root-POM
- `<groupId>hagrid</groupId>` (bewusst nicht `org.matsim.contrib`, um Verwechslung mit dem Original zu vermeiden)
- `<artifactId>freight</artifactId>`
- Dependencies: MATSim-Core und direkte freight-Abhängigkeiten

### `parcel-demand-2-matsim-pipeline/pom.xml` (Änderungen)

- `<parent>` auf Root-POM zeigen lassen
- Properties und Repositories entfernen, die in Root-POM verschoben wurden
- freight-Dependency umstellen: `org.matsim.contrib:freight` → `hagrid:freight`

## Freight-Quellcode extrahieren (einmaliges Setup)

Der freight-Quellcode wird per Sparse-Clone aus `matsim-org/matsim-libs` extrahiert — nur `contribs/freight/src/`, ohne die gesamte matsim-libs History (~15 GB).

Initialer Commit in HAGRID:
```
"Initial freight source from matsim-libs 2025.0-2025w13"
```

## Upstream-Sync-Prozess

Ein PowerShell-Script `sync-freight-upstream.ps1` im Root übernimmt zukünftige Syncs:

1. Lädt per GitHub API den aktuellen Stand von `contribs/freight/src/` als ZIP herunter
2. Ersetzt `freight/src/` mit dem neuen Stand
3. Gibt eine Zusammenfassung geänderter Dateien aus
4. Commit bleibt dem Entwickler überlassen (bewusste Entscheidung — kein automatisches Committen)

### Spätere Erweiterung: Update-Check am Pipeline-Start

Am Beginn der Pipeline (vor der `.bat`-Generierung) kann eine optionale Prüfung ergänzt werden:
- GitHub API abfragen: neue Commits in `contribs/freight` seit letztem Sync?
- Falls ja: Dialog anzeigen ("Freight-Update verfügbar — jetzt aktualisieren oder später?")
- "Jetzt": `sync-freight-upstream.ps1` aufrufen
- "Später": Überspringen, Hinweis im Log

Dieses Feature ist bewusst als spätere Erweiterung eingestuft und nicht Teil der initialen Implementierung.

## Abgrenzung

- Keine Beiträge zurück an `matsim-org/matsim-libs` geplant
- Anpassungen bleiben HAGRID-intern
- Keine Git-Submodule, kein JitPack

## Offene Punkte

Keine.
