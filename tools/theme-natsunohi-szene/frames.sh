#!/bin/zsh
# Frame-Serie für die Selbstabnahme (Regie v2, Harness-Muster vom 19.08.).
#
# Schießt die Mini-Bühne bei 1/4/7/10 s Animationszeit.
#
# DREI FALLEN, alle hier gelöst:
#   * `--virtual-time-budget` allein reicht NICHT. Selbst gemessen: vier Frames
#     bei 1000/4000/7000/10000 ms waren auf 0,0 % der Pixel verschieden — die
#     virtuelle Uhr treibt Timer vor, aber nicht die CSS-Animationsuhr dieser
#     Ebenen. Wer nur darauf baut, fotografiert viermal denselben Moment und
#     schließt daraus, seine Bewegung sei unsichtbar. Deshalb wird die Phase
#     hier mit `animation-delay: -Ns` gestellt: die Animation startet bereits
#     N Sekunden fortgeschritten. Das ist exakt der Frame, den ein Nutzer in
#     Sekunde N sieht — und es hängt an keiner Uhr.
#   * `timeout` gibt es in macOS-zsh nicht. Wer es benutzt, bekommt leere
#     Proben und hält sie für einen Befund (Haus-Lehre, im Vault notiert).
#     Hier wird stattdessen auf die DATEI gewartet.
#   * Headless-Chrome beendet sich nach --screenshot nicht zuverlässig. Also
#     eigener PID, eigenes --user-data-dir je Lauf, danach kill.
#
# Die Phasen-Stellung ist das EINZIGE, was das Harness am Theme ändert; alle
# Regeln, Dauern und Ausschläge kommen unverändert aus natsunohi.css.
#
# Aufruf: tools/theme-natsunohi-szene/frames.sh [zielverzeichnis]
set -u

ROOT="${0:A:h:h:h}"
OUT="${1:-$ROOT/tools/theme-natsunohi-szene/frames}"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
mkdir -p "$OUT"

for MS in 1000 4000 7000 10000; do
  SEC=$((MS / 1000))
  STAGE="${TMPDIR:-/tmp}/natsunohi-stage-t${MS}.html"
  cat > "$STAGE" <<HTML
<!doctype html><html lang="de" data-theme="natsunohi"><head><meta charset="utf-8">
<link rel="stylesheet" href="file://$ROOT/frontend/src/index.css">
<link rel="stylesheet" href="file://$ROOT/frontend/public/themes/natsunohi.css">
<style>html,body{min-height:100vh;margin:0}
/* Phase stellen — s. Kopf. Ein Wert gilt fuer alle Animationen des Elements. */
:root[data-theme='natsunohi'] body::before,
:root[data-theme='natsunohi'] body::after,
:root[data-theme='natsunohi']::before{animation-delay:-${SEC}s!important}
</style></head>
<body><div class="app" style="max-width:920px;margin:0 auto;padding:48px 24px;min-height:100vh">
<h1 style="font-size:2rem;margin-bottom:8px">21:47</h1>
<p style="color:var(--text-2)">Guten Abend, Andi.</p>
<div style="margin-top:24px;background:var(--bg-surface);border-radius:12px;padding:16px">
<p style="color:var(--text-1)">Wetter heute: 21° / 14°, abends trocken.</p>
<p style="color:var(--text-4);margin-top:6px">Stand 21:40 · Quelle: Open-Meteo</p></div>
<div style="margin-top:12px;background:var(--bg-surface);border-radius:12px;padding:16px">
<p style="color:var(--text-2)">Einkauf: Milch · Hafer · Zitronen <span style="color:var(--accent)">+3</span></p></div>
</div></body></html>
HTML

  PNG="$OUT/t${MS}.png"
  PROFILE="$OUT/.profile-$MS"
  rm -f "$PNG"
  rm -rf "$PROFILE"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars \
    --window-size=1366,1024 --virtual-time-budget=$MS \
    --screenshot="$PNG" --user-data-dir="$PROFILE" \
    "file://$STAGE" >/dev/null 2>&1 &
  PID=$!
  # auf die DATEI warten, nicht auf den Prozess
  for _ in {1..120}; do
    [[ -s "$PNG" ]] && sleep 0.3 && break
    sleep 0.25
  done
  kill $PID 2>/dev/null
  wait $PID 2>/dev/null
  rm -rf "$PROFILE"
  rm -f "$STAGE"
  if [[ -s "$PNG" ]]; then
    print "$PNG  $(wc -c < "$PNG" | tr -d ' ') B"
  else
    print "FEHLT: $PNG"
  fi
done
