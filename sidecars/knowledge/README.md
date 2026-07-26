# sidecars/knowledge

Hoshis lokale Knowledge-Bridge stellt die deutsche Wikipedia-FTS5-Datenbank
fuer das Backend bereit. `server.py` ist ein mechanischer Port des zuletzt
betriebenen `Hoshi_0.5/hoshi-knowledge-bridge/server.py`; Retrieval,
Re-Ranking, Passage-Auswahl und Fact-Gates wurden beim Umzug nicht neu
erfunden. Die 0.5-Kopie bleibt bis zum bewiesenen Cutover der Rueckweg.

## HTTP-Vertrag

- `GET /health` → Status, Artikelzahl und DB-Metadaten
- `GET /search?q=...&limit=5&extract_max_chars=1500` → `WikiSearchResponse`
- `GET /article/{id}?max_chars=2000` → einzelner Artikel-Extract
- `GET /v1/health` → Pack-Status und Runtime-Source-Hashes ohne lokalen Maschinenpfad
- `GET /v1/manifest` → veröffentlichbare Pack-Provenienz oder ehrliches 404
- `GET /v1/search?...` → bestehende Hits plus Quellen-/Pack-Evidenz

Die Datenbank wird ausschliesslich mit SQLite `mode=ro` geoeffnet. Der
optionale Summary-Pfad nutzt den vorhandenen lokalen Ollama-Endpunkt; faellt er
aus, bleibt der volle Extract erhalten.

Ohne `manifest.json` startet die heutige externe DB weiter, wird in `/v1/health`
aber sichtbar als `legacy-unmanifested` ausgewiesen. Ein vorhandenes, kaputtes
Manifest ist fatal. Mit `HOSHI_KNOWLEDGE_REQUIRE_MANIFEST=true` ist auch ein
fehlendes Manifest fatal. `/v1/search` verweigert eine Legacy-DB mit HTTP 409;
für sie bleibt ausschließlich der unveränderte `/search`-Pfad. Bau, Vertrag und Full-Verify:
[`tools/knowledge-pack`](../../tools/knowledge-pack/README.md).

## Bootstrap und Start

```bash
sidecars/knowledge/bootstrap.sh
sidecars/knowledge/run.sh
curl http://127.0.0.1:8035/health
```

`bootstrap.sh` prueft zuerst den SHA-256 der gepinnten Requirements und danach
die Runtime-Imports sowie SQLite-FTS5. Es laedt weder Datenbank noch Modelle.
`run.sh` bricht vor dem Port-Bind ab, wenn DB, Schema oder venv fehlen.

| Variable | Default | Wirkung |
|---|---|---|
| `HOSHI_WIKI_DB_PATH` | `$HOME/.hoshi/knowledge/wiki-de/articles.db` | externe Wikipedia-DB |
| `HOSHI_KNOWLEDGE_HOST` | `0.0.0.0` | Bind-Adresse |
| `HOSHI_KNOWLEDGE_PORT` | `HOSHI_BRIDGE_PORT` bzw. `8035` | HTTP-Port |
| `HOSHI_LOG_DIR` | `$HOME/.hoshi/logs` | Log-Ablage |
| `HOSHI_KNOWLEDGE_MANIFEST_PATH` | neben der DB: `manifest.json` | explizites Pack-Manifest |
| `HOSHI_KNOWLEDGE_REQUIRE_MANIFEST` | `false` | Legacy-DB verweigern |
| `HOSHI_KNOWLEDGE_VERIFY_CONTENT_AT_START` | `false` | vor READY tatsächliche Pack-DB vollständig hashen und spätere Dateidrift fail-closed verweigern |

`/v1/manifest` nennt bei content-verifiziertem Start den tatsächlich gemessenen
DB-Hash und eine pfadfreie Selbstauskunft über die geladenen
`server.py`-/`pack_manifest.py`-Bytes. Das bindet einen lokalen A/B-Lauf
reproduzierbar an diese Quellen; es ist keine kryptografische
Prozess-Attestation gegen einen bösartigen lokalen Dienst.

## Tests

```bash
sidecars/knowledge/.venv/bin/python -m pip install -r sidecars/knowledge/requirements-dev.txt
HOSHI_WIKI_DB_PATH=/dev/null sidecars/knowledge/.venv/bin/python -m pytest \
  sidecars/knowledge/test_fts_query.py \
  sidecars/knowledge/test_summary_anchor.py \
  sidecars/knowledge/test_server_contract.py -q
```

`test_fact_gate_battery.py` ist zusaetzlich eine DB-gebundene Battery gegen die
echte lokale `articles.db`; sie ist kein frischer-Klon-Gate und mutiert die DB
nicht.

## Cutover

`pipeline/up.sh` waehlt diesen Repo-Sidecar nur, wenn sein venv existiert
(AUTO), oder explizit mit `HOSHI_SIDECARS_FROM_REPO=true`. Fehlt das venv,
bleibt der 0.5-Run-Pfad erhalten; ein erzwungener Repo-Pfad ohne venv bricht
laut ab. Deploy, Prozesswechsel und Live-Beweis bleiben Owner-/Orchestrator-
Gates.
