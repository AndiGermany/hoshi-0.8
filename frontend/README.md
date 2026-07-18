# Hoshi 0.8 Frontend

Schlanke, ehrliche, **status-first** Shell. Spricht LIVE an Hoshis echte Endpoints —
nichts wird gemockt im laufenden Betrieb, nichts grün gefärbt, was nicht läuft.

## Stack

Vite + React 18 + TypeScript. Kein Gradle, kein Server-Boot zum Bauen/Testen.

## Was drin ist

- **Chat** — `POST /api/v1/chat/stream`, rendert den SSE-`ChatEvent`-Strom
  (`start` → `delta…` → `done`, plus sichtbare `error`-Events). Token via
  `X-Hoshi-Token`-Header. `speak:false` → reiner Text-Turn.
- **Health-Badge** — pollt `GET /api/health` und zeigt ehrlich up/down;
  unbekannt = grau (nie Fake-grün).
- **Übersicht** — Momentaufnahme (Backend-URL, Health, Token, letzte Prüfung).

## Config

Kopiere `.env.example` nach `.env.local`:

```
VITE_API_BASE=http://localhost:8090   # Default-Backend-Port
VITE_TOKEN=                            # X-Hoshi-Token (leer ⇒ geschützte Pfade 401)
```

## Befehle

```
npm install
npm run dev      # Dev-Server (Port 5180 — kein Clash mit Backend :8090)
npm run build    # tsc -b && vite build
npm test         # vitest run (SSE-Parsing)
```
