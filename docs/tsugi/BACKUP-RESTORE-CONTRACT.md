# Backup-/Restore-Vertrag v1 — Vorschlag

> **Summary (EN):** A backup is valid only when its source version and every
> store format are identified from evidence, integrity-checked and restorable
> into a fresh target. Unknown/newer formats fail closed; browser-local timer
> routing remains an explicit restore gap until device identity is rebound.

## Ziel und Nicht-Ziele

Ein Backup ist erst gültig, wenn ein fremder Betreiber es offline prüfen und in
ein **neues Ziel** trocken wiederherstellen kann. „Datei kopiert" ist keine
Erfolgsaussage.

Nicht Teil von v1 sind Home-Assistant-Backups, Modelle, Knowledge-Packs, Secrets,
Browser-`localStorage` und Roh-Audio. Sprecherprofile sind ein getrenntes,
bewusstes Opt-in.

Das Ausschließen von Browser-`localStorage` ist bei Timern **keine kosmetische
Lücke**: `ScheduledItem.origin` enthält die dort persistierte
`hoshi.deviceId`. Ein restaurierter Timer kann deshalb seine Zuordnung zum
ursprünglich klingelnden Browser verlieren. V1 darf diesen Zustand weder als
vollständig restauriert melden noch `origin` still löschen oder umschreiben.

## Artefaktform

Ein Backup ist ein Verzeichnis oder ein daraus erzeugtes Archiv mit folgender
logischer Struktur:

```text
hoshi-backup-v1/
  manifest.json
  stores/
    settings/...
    lists/...
    timers/...
    memory/...
    notes/...
  evidence/                 # nur bei explizitem Opt-in
  speaker-profiles/         # nur als separates Opt-in-Artefakt
```

`manifest.json` besitzt mindestens:

```json
{
  "contract": "hoshi-backup",
  "contractVersion": 1,
  "createdAt": "<UTC ISO-8601>",
  "hoshiVersion": "<aus Quell-Build-Metadaten gelesen>",
  "backupToolVersion": "<semver>",
  "schemaCatalogVersion": 1,
  "sourcePlatform": "darwin-arm64|linux-arm64",
  "scope": ["household", "notes"],
  "consistent": true,
  "entries": [
    {
      "logicalId": "lists.default",
      "storeSchemaVersion": 1,
      "artifact": "stores/lists/lists.json",
      "bytes": 0,
      "sha256": "<hex>",
      "sensitivity": "PRIVATE",
      "sourcePathHint": "HOSHI_LIST_STORE_PATH",
      "requiredForRestore": false
    }
  ],
  "excluded": [
    {"logicalId": "secrets", "reason": "NEVER_BACKED_UP"},
    {"logicalId": "rooms", "reason": "OWNED_BY_HOME_ASSISTANT"}
  ]
}
```

Der Manifest-Pfad enthält keine Benutzernamen oder absoluten Quellpfade.
`sourcePathHint` nennt nur den Konfigurationsschlüssel. Jeder Eintrag ist über
Größe und SHA-256 gebunden. Unbekannte `contractVersion` ist fail-closed.
`hoshiVersion` wird aus der tatsächlich zu sichernden Installation gelesen
(Build-Metadaten bzw. deren maschinenlesbare Versionsquelle), nie aus der
Version des Backup-Werkzeugs geraten. Ist sie nicht eindeutig lesbar, blockiert
der Lauf.

## Schema-Versionierung und Migration

- Das Werkzeug besitzt einen versionierten Schema-Katalog mit einer expliziten
  Obergrenze unterstützter Hoshi-Quellversionen. Ist die gelesene Quellversion
  neuer als diese Formatdecke, unbekannt oder keinem vollständigen Katalogsatz
  zugeordnet, blockiert der Lauf **vor** dem Kopieren.
- V1 ordnet den heute bekannten, noch unversionierten Formaten erst nach
  erfolgreicher Strukturvalidierung `storeSchemaVersion = 1` zu. Die Zahl wird
  nicht pauschal behauptet; logische ID, gelesene Hoshi-Version, Parser und
  erwartete Formatmerkmale müssen gemeinsam einen eindeutigen Katalogeintrag
  ergeben.
- Jede spätere persistente Formatänderung erhöht die Store-Version und liefert
  eine gerichtete Migration `n → n+1` plus Roundtrip-Fixture.
- Restore migriert nur über vollständig bekannte, lückenlose Pfade. Downgrade
  ist nicht implizit erlaubt.
- Eine Migration arbeitet ausschließlich in einem Staging-Verzeichnis. Erst
  Validierung und Integritätsprüfung erlauben den Tausch auf den Zielpfad.
- Unbekannte zusätzliche JSON-Felder bleiben erhalten, sofern der jeweilige
  Store-Vertrag sie nicht ausdrücklich verbietet.

## Konsistenter Backup-Lauf

1. Quell-Build-Version read-only ermitteln und gegen die Formatdecke des
   Schema-Katalogs prüfen. Unlesbar, unbekannt oder neuer als unterstützt =
   `BLOCKED`.
2. Effektive Pfade read-only auflösen und als logische IDs anzeigen. Fehlende
   optionale Stores sind `ABSENT`, nicht `ERROR`.
3. Vorab prüfen: Ziel neu/leer, genug Platz, keine Symlink-Flucht, reguläre
   Quelldateien, keine Welt-Leserechte bei privaten Daten.
4. Für JSON-Snapshot-Stores darf eine vollständig gelesene reguläre Datei
   kopiert werden; atomare Schreiber garantieren alte oder neue Vollversion.
5. JSONL-Schreiber werden für einen garantierten Snapshot kontrolliert
   geschlossen oder der Dienst wird im Owner-Fenster angehalten. Eine partielle
   letzte Zeile macht den Eintrag ungültig.
6. SQLite wird über die SQLite-Backup-API bzw. `VACUUM INTO` gesichert, nie per
   blindem Kopieren einer laufenden DB. Danach `PRAGMA integrity_check` gegen die
   Kopie.
7. Jede Kopie wird geparst/validiert, gehasht und erst dann ins Manifest
   aufgenommen. Bei einem erforderlichen Fehler entsteht **kein** als konsistent
   markiertes Backup.
8. Das Manifest wird zuletzt atomar geschrieben. Ein Artefakt ohne gültiges
   Manifest ist unvollständig.

Die spätere CLI muss standardmäßig read-only planen. Ein Stopp/Start des
Backends ist ein Owner-Gate und wird nie still ausgelöst.

## `restore --dry-run`

Dry-run schreibt niemals auf konfigurierte Zielpfade. Er muss:

1. Manifest-Schema, alle Hashes und Größen prüfen;
2. doppelte/unbekannte logische IDs und Pfad-Traversal ablehnen;
3. jede JSON-/JSONL-/SQLite-Struktur mit der angegebenen Store-Version prüfen;
4. alle nötigen Migrationen in ein frisches temporäres Ziel ausführen;
5. SQLite-Integrität und fachliche Mindestbedingungen prüfen;
6. Dateirechte für private/hochprivate Ziele simulieren;
7. einen Plan `CREATE`, `REPLACE`, `SKIP`, `MIGRATE` oder `BLOCKED` je Store
   ausgeben;
8. Lücken wie Räume, Secrets, Browserzustand und Modelle sichtbar wiederholen.

Enthält der Timer-Store Einträge mit nichtleerem `origin`, meldet der Plan
zusätzlich `BROWSER_DEVICE_ID_NOT_RESTORED`, solange keine explizite
Owner-bestätigte Zuordnung zur aktuellen `hoshi.deviceId` existiert. Ein
stilles Löschen von `origin` wäre eine Verhaltensänderung („überall klingeln")
und ist verboten.

Exit-Codes: `0` vollständig restaurierbar, `2` restaurierbar mit benannten
optionalen Lücken, `>=10` blockiert. Ein Dry-run darf nie „grün" werden, wenn
ein erforderlicher Store nur deshalb leer startet, weil sein Parser einen
Fehler verschluckt.

## Echter Restore

1. Owner bestätigt Wartungsfenster, Backup-ID und Zielhost.
2. Laufende Schreiber werden kontrolliert beendet; der Restore selbst beendet
   nie ungefragt Prozesse.
3. Vorhandene Ziele werden in ein datiertes Rollback-Verzeichnis **auf demselben
   Dateisystem** verschoben, nicht gelöscht.
4. Validierte Staging-Dateien werden mit restriktiven Rechten und atomarem
   Rename aktiviert.
5. Backend startet mit Cloud, Sprechererkennung und sonstigen sensiblen Gates
   weiterhin in der vorher dokumentierten OFF-/Owner-Konfiguration.
6. `bin/hoshi doctor` prüft den Stack; danach folgen read-only Fachproben für
   Settings, Listen, Timer und Memory. Keine Hausaktion wird zum Restore-Beweis
   ausgelöst.
7. Für Timer prüft eine nichtproduktive Fixture zusätzlich das Ring-Routing
   (`origin` ↔ Browser-`hoshi.deviceId`). Ohne bestätigte Zuordnung bleibt der
   Timer-Teilstatus `DEGRADED`; ein bloß lesbarer Timer-JSON-Eintrag ist kein
   Klingelbeweis.
8. Erfolg wird erst gemeldet, wenn angeforderte Werte gelesen wurden und dem
   Manifest entsprechen. Bei Fehler: Dienst stoppen, neue Ziele beiseitelegen,
   Rollback-Verzeichnis atomar zurücktauschen.

## Sprecherprofile

Das separate Sprecher-Artefakt verlangt einen expliziten Schalter und eine
zweite Bestätigung. Es enthält keine Audio-Captures und keine Namen im Manifest.
Restore stellt Profile höchstens bereit; **Recognition und Trust bleiben aus**,
bis das aktuelle Holdout-Gate erneut bestanden und der Owner beide Flags
gemeinsam freigegeben hat.

## Abnahmematrix der späteren Implementierung

| Fall | Erwartung |
|---|---|
| fehlender optionaler Store | Backup gültig, `ABSENT` im Manifest |
| kaputtes JSON / partielle JSONL-Zeile | Backup blockiert, kein `consistent=true` |
| laufende SQLite-DB | konsistenter API-Snapshot, `integrity_check=ok` |
| manipuliertes Byte im Archiv | Dry-run blockiert am Hash |
| unbekannte Manifest-/Store-Version | fail-closed |
| Quell-Hoshi-Version unlesbar oder neuer als Formatdecke | fail-closed vor Kopie; keine behauptete `storeSchemaVersion` |
| Pfad `../…` oder Symlink aus Ziel | fail-closed |
| Restore in nichtleeres Ziel ohne Owner-Bestätigung | blockiert |
| Sprecherprofile ohne Opt-in | nicht im Artefakt |
| Timer mit `origin`, aber ohne passende restaurierte Browser-`hoshi.deviceId` | `BROWSER_DEVICE_ID_NOT_RESTORED`; kein vollständiger Erfolg und kein stilles Umschreiben |
| Timer-Restore-Probe | Timer lesbar **und** Ring-Routing mit nichtproduktiver Fixture belegt |
| Restore-Probe | Einstellungen/Liste/Timer/Memory fachlich lesbar; Räume/Secrets/Browserzustand ehrlich als extern gemeldet |
