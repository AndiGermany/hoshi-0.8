# Lizenzgrenze des Knowledge-Sidecars

Der Hoshi-Wrapper (`server.py`, Shell-Skripte und Tests) ist Teil des unter
Apache-2.0 stehenden Projekts. Die direkten Python-Abhaengigkeiten sind
permissiv und Apache-2.0-kompatibel:

| Artefakt | Pin | Lizenz | Quelle |
|---|---:|---|---|
| FastAPI | 0.136.3 | MIT | <https://github.com/fastapi/fastapi> |
| Pydantic | 2.13.4 | MIT | <https://github.com/pydantic/pydantic> |
| Uvicorn | 0.49.0 | BSD-3-Clause | <https://github.com/Kludex/uvicorn> |
| python-zstandard | 0.25.0 | BSD-3-Clause | <https://github.com/indygreg/python-zstandard> |

Die lokale `articles.db` wird **nicht** im Repository oder durch
`bootstrap.sh` ausgeliefert. Ihr Wikipedia-Inhalt unterliegt CC BY-SA; die API
liefert deshalb fuer Treffer explizit `attribution: "Aus Wikipedia, CC-BY-SA"`.
Wer eine abgeleitete Datenbank verteilt, muss deren eigene Lizenz- und
Attributionspflichten separat erfuellen.

Das optionale extraktive Summary kann einen bereits separat laufenden
Ollama-/EmbeddingGemma-Endpunkt aufrufen. Weder Ollama noch Modellgewichte
werden hier gebuendelt oder heruntergeladen; deren jeweilige Bedingungen sind
eine eigene Runtime-Grenze.
