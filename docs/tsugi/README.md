# Tsugi-Minimum — reviewbarer Lebenszyklus-Vertrag

> **Summary (EN):** This review package defines Hoshi's current persistence,
> backup/restore and token-rotation truth. It changes no runtime behavior and
> deliberately leaves the HA check, dynamic area-catalog default and an actual
> third-party installation proof to later implementation slices.

**Status:** Vorschlag, noch nicht implementiert. Dieses Paket verändert weder
Persistenzformate noch Runtime-Verhalten.

Tsugi beantwortet die Frage, ob ein fremder Betreiber Hoshi installieren,
sichern, wiederherstellen und Schlüssel wechseln kann, ohne dem System mehr zu
glauben als den eigenen Messungen.

Die erste Scheibe besteht aus vier Artefakten:

- [`STORE-INVENTORY.md`](STORE-INVENTORY.md): heutige Datei-Wahrheit, inklusive
  fehlender Raum-Persistenz und sensibler Sprecherprofile;
- [`BACKUP-RESTORE-CONTRACT.md`](BACKUP-RESTORE-CONTRACT.md): vorgeschlagener
  versionierter Backup-/Restore-Vertrag mit Dry-run und Integritäts-Gates;
- [`SETUP-TRUTH-REVIEW.md`](SETUP-TRUTH-REVIEW.md): genaue Korrekturen für die
  veraltete Setup-Dokumentation sowie die fehlenden HA-/Satelliten-Kapitel;
- [`TOKEN-FLIP-DRILL.md`](TOKEN-FLIP-DRILL.md): ausführbarer Prüfablauf für den
  Sidecar-Token, ausdrücklich ohne ihn in dieser Scheibe auszuführen.

## Review-Gate

Vor dem Review werden bewusst **nicht** gebaut:

- kein Backup-/Restore-CLI;
- keine `schemaVersion` in bestehenden Store-Dateien;
- keine Änderung an `SETUP.md`;
- keine Token-Verteilung, kein Token- oder Bind-Flip;
- kein Deploy und keine Restore-Probe gegen produktive Daten.

Nach Annahme ist die kleinste sichere Bauscheibe: Manifest/Inventar als
read-only `backup plan`, danach Backup + `restore --dry-run` in ein neues,
leeres Ziel. Ein Restore auf einen echten Zielpfad bleibt ein Owner-Gate.

## Bewusst vertagter Tsugi-Scope

Dieses Vertragspaket ist **nicht** das vollständige F3-Paket. Es spezifiziert
die Ränder, baut aber noch nicht:

- den noch nicht existierenden Befehl `bin/hoshi ha check`;
- die Umstellung des dynamischen HA-Raumkatalogs auf den Installationsdefault;
- ein Release-Artefakt samt protokolliertem Fremdinstall-Beweis auf einem
  anderen Apple-Silicon-Mac durch eine andere Person.

Diese drei Punkte dürfen deshalb weder aus grünen Doku-Checks noch aus einem
lokalen Dry-run als erfüllt abgeleitet werden.
