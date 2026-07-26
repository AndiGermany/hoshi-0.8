# Knowledge-Pack v1

Ein Knowledge-Pack ist ein **unveränderliches, rein öffentliches** Artefakt:

```text
de-core/
├── dumpstatus.json
├── manifest.json
├── NOTICE.md
├── selection.jsonl
└── pack.sqlite
```

`pack.sqlite` enthält ausschließlich explizit ausgewählte Wikipedia-Leads,
öffentliche Titel, FTS5 und Quellenprovenienz. Private Queries,
Lookup-Historie, Haushaltsdaten und modellgenerierte Gists gehören nicht hinein.
Der Benchmark entscheidet über die Auswahl; der Builder erweitert sie nie aus
Nutzungsdaten.

## Releasepfad: offizieller Dump → Pack

[`build_pack_from_dump.py`](build_pack_from_dump.py) ist der bindende Pfad für
ein veröffentlichbares Pack. Er:

- leitet Dump- und Status-URL kanonisch aus `dewiki` + Datum ab;
- ruft den kanonischen `dumpstatus.json` ohne Redirect ab, bindet dort Status,
  Größe und SHA-1 an den Dump und legt ausschließlich eine kanonische
  Minimalprojektion dieser öffentlichen Belegfelder dem Pack bei;
- prüft erwartete Bytezahl und den offiziellen SHA-1, bevor XML gelesen wird;
- berechnet beim Download zusätzlich SHA-256 und schreibt ihn ins Manifest;
- lädt in eine temporäre Datei und veröffentlicht weder Download noch Pack
  überschreibend;
- streamt bz2/XML ohne entpackte Zwischenkopie;
- übernimmt Page-ID, Revisions-ID und Revisionszeit direkt aus dem Dump;
- akzeptiert nur Main-namespace-Artikel aus der öffentlichen Auswahl;
- lehnt Redirects ehrlich ab, statt unbemerkt einen anderen Artikel zu packen.
- verweigert einen Releasebuild, solange Builder oder Verifier uncommitted sind.

Die Auswahl ist JSONL und darf ausschließlich `title` und `aliases` enthalten.
Für ein Release-Pack v1 muss `aliases` jedoch leer sein:

```json
{"title":"Albert Einstein","aliases":[]}
{"title":"Photosynthese","aliases":[]}
```

Freie Aliase sind nicht durch den Wikimedia-Dump belegt und könnten private
Texte in das öffentliche Artefakt schmuggeln. Sie werden deshalb fail-closed
abgewiesen. Eine spätere Pack-Version darf Aliase nur deterministisch aus
öffentlichen Dumpdaten ableiten, etwa aus verifizierten Redirect-Seiten.

### Reproduzierbares Beispiel: dewiki 2026-07-01

Diese Werte binden den Build an den abgeschlossenen Wikimedia-Dump:

- Dump: `dewiki-20260701-pages-articles-multistream.xml.bz2`
- Größe: `8191590940` Bytes
- offizieller SHA-1: `78b9aefc316c07ffe7c6044aabb16be2759b49ec`
- Status: <https://dumps.wikimedia.org/dewiki/20260701/dumpstatus.json>

```bash
sidecars/knowledge/.venv/bin/python \
  tools/knowledge-pack/build_pack_from_dump.py \
  --dump-date 20260701 \
  --expected-size 8191590940 \
  --expected-sha1 78b9aefc316c07ffe7c6044aabb16be2759b49ec \
  --dump-cache-dir "$HOME/.hoshi/knowledge/dumps" \
  --selection tools/knowledge-pack/examples/de-core-smoke.jsonl \
  --output-dir "$HOME/.hoshi/knowledge/packs/de-core-20260701" \
  --pack-id hoshi-wikipedia-de-core-20260701
```

Hier steht absichtlich **kein vorab behaupteter SHA-256**. Der Builder berechnet
ihn aus der vollständig empfangenen lokalen Datei und bewahrt ihn zusammen mit
Größe und geprüftem SHA-1 im Manifest auf. Ein vorhandener Cache wird vollständig
neu gehasht und nie still ersetzt.

### Platten- und Laufzeitbudget

Der komprimierte Juli-Dump belegt rund 7,63 GiB. Beim Standard-Packlimit von
512 MiB und den zwei Sicherheitsmargen von zusammen 1 GiB fordert der Preflight
auf einem gemeinsamen Dateisystem rund **9,13 GiB freien Platz**. Eine Maschine
mit 23 GB frei hat damit rechnerisch ausreichend Reserve; der reale freie Platz
wird trotzdem unmittelbar vor Download und Build geprüft. Auf getrennten
Dateisystemen werden Cache und Ausgabe separat gegated.

Die entpackte XML-Datei wird nie auf Platte geschrieben. bz2 und XML laufen als
Stream; im Speicher bleiben nur die explizit ausgewählten Artikel. Das senkt
den Peak-Plattenbedarf, nicht die nötige CPU-/Lesezeit für einen kompletten
Wikipedia-Durchlauf.

### Bewusst konservative Wikitext-Transformation

Der Builder ist kein vollständiger MediaWiki-Renderer. Er entfernt Referenzen,
Templates, Tabellen, Medien-/Kategorie-Links, HTML und Abschnitte nach dem Lead.
Bleibt strukturelles Markup unaufgelöst, bricht er ab. Das ist ein
Release-Sicherheitsventil: ungewöhnliche Seiten werden nicht als scheinbar sauber
transformiert ausgegeben. Vor einer großen Auswahl muss der synthetische Vertrag
um reale, öffentlich dokumentierte Randfälle erweitert werden.

## Vollständig verifizieren

```bash
sidecars/knowledge/.venv/bin/python \
  tools/knowledge-pack/verify_pack.py \
  "$HOME/.hoshi/knowledge/packs/de-core-20260701/manifest.json" \
  --source-dump \
  "$HOME/.hoshi/knowledge/dumps/dewiki-20260701-pages-articles-multistream.xml.bz2" \
  --verify-source-online
```

Der Full-Check prüft:

- Manifest, Releasefelder, Dump-Bindung und UTC-Zeitstempel;
- den beigelegten `dumpstatus.json` gegen Manifest und Dump sowie bei
  `--verify-source-online` noch einmal gegen die kanonische Wikimedia-URL;
- die Builder-Quelle bytegenau gegen den im Manifest genannten Git-Commit;
- die tatsächlichen lokalen Dump-Bytes gegen Größe, offiziellen SHA-1 und den
  beim Build gemessenen SHA-256;
- die kanonisch beigefügte Auswahl und alle Transformationsparameter;
- einen erneuten logischen Aufbau aus genau diesem Dump: Artikel, Suchtext und
  Revisionsprovenienz müssen zeilenweise der tatsächlichen SQLite entsprechen;
- Python-, SQLite- und zstandard-Version; mit genau dieser gepinnten Toolchain
  wird eine frische Pack-SQLite erzeugt und muss bytegleich zum Kandidaten sein;
- Dateigröße und SHA-256 der kompletten SQLite-Datei;
- SQLite `quick_check`, öffentliches Schema, Zeilenzahlen und den
  FTS5-External-Content-Integritätscheck;
- vollständige Page-/Revisionsprovenienz samt permanenter `oldid`-URL;
- Abwesenheit privater Runtime-Tabellen;
- die Lizenzkerne Attribution, Modifications und ShareAlike im `NOTICE`.

`--fast` überspringt ausschließlich DB-SHA-256 und `quick_check`. Der Modus
eignet sich für einen Start-Preflight, nicht als Veröffentlichungsbeweis.

Das Manifest trägt zunächst `releaseStatus: release-candidate`. Ein lokaler
Full-Check ohne Online-Beleg meldet ehrlich nur `artifactVerified: true`.
`releaseEligible: true` entsteht ausschließlich, wenn derselbe Lauf zusätzlich
den frischen kanonischen Wikimedia-Status bestätigt, die vollständigen
Quelldump-Bytes erhält und den logischen Wiederaufbau samt FTS-Integrität
abschließt. Zusätzlich muss der bytegenaue Wiederaufbau mit der im Manifest
gepinnten Toolchain gelingen; eine andere Toolchain führt ehrlich zu keinem
Release-GO, auch wenn die logischen Inhalte gleich aussehen. Dadurch können
ungenutzte SQLite-Bytes nicht als unbelegter Nebenkanal mitveröffentlicht werden.
Das Manifest allein kann diesen externen Releasebeweis nicht behaupten.

## Legacy-DB: nur forensischer Export

[`build_pack.py`](build_pack.py) liest die historische lokale SQLite-DB. Diese
DB speichert den ursprünglichen Dump-Hash nicht. Selbst ein plausibles Datum
oder eine manuell eingetragene Revisions-ID kann die fehlende Bindung nicht
nachträglich beweisen. Deshalb setzt dieser Pfad zwingend:

```json
{
  "releaseStatus": "forensic-non-release",
  "source": {
    "provenanceStatus": "caller-asserted-unverified"
  }
}
```

Er bleibt für lokale Experimente und Vergleiche verfügbar, ist aber kein
Releasepfad. Für ihn darf kein Dump-Datum geraten und kein Release-Claim
abgeleitet werden.

## Lokal starten

```bash
HOSHI_WIKI_DB_PATH="$HOME/.hoshi/knowledge/packs/de-core-20260701/pack.sqlite" \
HOSHI_KNOWLEDGE_REQUIRE_MANIFEST=true \
HOSHI_KNOWLEDGE_VERIFY_CONTENT_AT_START=true \
sidecars/knowledge/run.sh

curl http://127.0.0.1:8035/v1/health
curl 'http://127.0.0.1:8035/v1/search?q=einstein&limit=3'
```

Der alte `/search`-Vertrag bleibt bestehen. `/v1/search` ergänzt dieselben Hits
um Pack-ID, Page-ID, Revisions-ID, permanente Quellen-URL, Lizenz und
Retrieval-Herkunft. Neue Manifestfelder sind additiv; die Runtime bleibt mit
Knowledge-Pack v1 kompatibel.

`HOSHI_KNOWLEDGE_VERIFY_CONTENT_AT_START` ist bewusst default-OFF: Der
vollständige DB-Hash kostet Startzeit. Ein Release-Benchmark verlangt ihn
explizit und akzeptiert nur `manifest-content-verified`; nach READY prüft die
Runtime vor jedem DB-Open, dass Inode, Größe und Änderungszeiten unverändert
geblieben sind.

## Lizenz und Weitergabe

`NOTICE.md` nennt CC BY-SA 4.0, Quelle, exakte Dump-Bindung, Transformation,
Attribution über die permanenten Artikel-URLs und die ShareAlike-Pflicht.
Bei einer Weitergabe müssen Pack, Manifest und Notice zusammenbleiben. Der
Builder erzeugt keine Rechtsgarantie; der Releaseverantwortliche prüft die
Auswahl und die erfüllte Attribution vor Veröffentlichung. Insbesondere ist die
Attribution im Pack noch kein Beweis, dass Hoshis hörbare oder sichtbare Antwort
sie ausreichend präsentiert: Wikimedias
[Attribution guide](https://meta.wikimedia.org/wiki/Brand/Attribution) nennt
Voice Assistants ausdrücklich. Diese Produktentscheidung und eine rechtliche
Prüfung bleiben daher ein offenes menschliches Release-Gate; Grundlage sind die
[Wikimedia Terms of Use](https://foundation.wikimedia.org/wiki/Policy:Terms_of_Use).
