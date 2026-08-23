# zuhause-probe — den Zuhause-Reiter mit eigenen Augen ansehen

Drei kleine Node-Skripte ohne jede Abhängigkeit (nur Node-Bordmittel), mit denen
man die Übersicht headless aufmacht, **misst** und **fotografiert** — ohne
laufendes Backend und ohne einen fremden Chrome anzufassen.

Entstanden für die Feinschliff-Bestellungen vom 19.08. (Fußleiste · Orb-Anker ·
Wetter-M). Die Bilder in `docs/screenshots/zuhause-feinschliff/` kommen genau
von hier.

## Warum es das braucht

Ein Urteil über Fläche („die Kachel ist zu leer") lässt sich nicht aus dem Code
ableiten, und jsdom rechnet kein Layout. Die einzige ehrliche Quelle ist ein
echter Browser mit echten Zahlen: `probe.mjs` hat die Bestellung „beim Wetter
habe ich echt viel Platz" in *583 × 200 px Kachel, 553 × 80 px Inhalt*
übersetzt — und damit auch gezeigt, dass der Platz **rechts** lag, nicht unten.

## Benutzung

```sh
# 1. Frontend gegen den Probe-Server bauen
cd frontend && VITE_API_BASE=http://127.0.0.1:8794 npm run build

# 2. dist/ + gefälschte, aber vertragstreue API auf einem Port servieren
node ../tools/zuhause-probe/serve.mjs "$PWD/dist" 8794 &

# 3. Bilder (1366×1024 und 834×1112) in ein Verzeichnis legen
node ../tools/zuhause-probe/shot.mjs ./shots nachher
SHOT_VARIANT=tall node ../tools/zuhause-probe/shot.mjs ./shots nachher-hoch

# 4. Geometrie messen statt schätzen
node ../tools/zuhause-probe/probe.mjs 1366 1024
```

`SHOT_VARIANT=tall` schaltet den Einkauf ab — dann packt die Bühne zwei statt
drei Zeilen und die Kacheln werden hoch. **Beide Fälle ansehen**: eine
M-Kachel ist je nach Zeilenzahl 134 px oder 200 px hoch, und ein Inhalt, der
nur im hohen Fall passt, bricht im niedrigen ab (genau so ist der erste
Wetter-M-Versuch gescheitert, s. `wetter-m-fehlversuch-1366x1024.png`).

## Zwei Fallen, die hier schon eingebaut sind

- **Chrome beendet sich auf dieser Kiste nie von selbst.** Beide Skripte
  starten ihren eigenen Chrome mit eigenem `--user-data-dir` unter
  `os.tmpdir()` und killen am Ende **nur den eigenen Kindprozess**.
- **Die API-Antworten sind gefälscht, aber nicht ausgedacht.** Die Formen in
  `serve.mjs` stammen aus den echten Parsern in `frontend/src/hooks/*` — ein
  Feld, das der Parser verwirft, ist auch hier keins. Sonst fotografiert man
  einen Zustand, den es nie gibt.
