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

Die Datenbank wird ausschliesslich mit SQLite `mode=ro` geoeffnet. Der
optionale Summary-Pfad nutzt den vorhandenen lokalen Ollama-Endpunkt; faellt er
aus, bleibt der volle Extract erhalten.

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
