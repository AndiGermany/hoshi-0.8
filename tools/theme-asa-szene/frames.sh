#!/bin/zsh
# Frame-Serie fuer die Selbstabnahme (Regie v2). Vorlage: tools/theme-natsunohi-szene/frames.sh
#
# Schiesst die Mini-Buehne bei 1/4/7/10 s Animationszeit, dazu reduced-motion.
#
# DIE DREI FALLEN (alle vom Piloten geerbt, hier unveraendert geloest):
#   * `--virtual-time-budget` treibt die CSS-Animationsuhr NICHT. Vier Frames
#     bei 1000/4000/7000/10000 ms sind sonst derselbe Moment, viermal
#     fotografiert. Die Phase wird deshalb mit `animation-delay: -Ns` gestellt.
#   * `timeout` gibt es in macOS-zsh nicht -- wer es benutzt, bekommt leere
#     Proben und haelt sie fuer einen Befund. Hier wird auf die DATEI gewartet.
#   * Headless-Chrome beendet sich nach --screenshot nicht zuverlaessig: eigener
#     PID, eigenes --user-data-dir je Lauf, danach kill.
#
# ASA-SPEZIFISCH: das Theme animiert DREI Ebenen -- body::before (der
# Lichtschacht), :root::before (der Fensterkreuz-Fleck auf den Tatami) und
# :root::after (der Dampf). Alle drei muessen die Phasenstellung bekommen,
# sonst misst man den Herzschlag eines Beins.
#
# Aufruf: tools/theme-asa-szene/frames.sh [zielverzeichnis]
set -u

ROOT="${0:A:h:h:h}"
OUT="${1:-$ROOT/tools/theme-asa-szene/frames}"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
mkdir -p "$OUT"

shoot() {  # $1 = name, $2 = extra css, $3 = virtual time budget, $4 = chrome flag
  local NAME="$1" EXTRA="$2" MS="$3" FLAG="${4:-}"
  local STAGE="${TMPDIR:-/tmp}/asa-stage-${NAME}.html"
  cat > "$STAGE" <<HTML
<!doctype html><html lang="de" data-theme="asa"><head><meta charset="utf-8">
<link rel="stylesheet" href="file://$ROOT/frontend/src/index.css">
<link rel="stylesheet" href="file://$ROOT/frontend/public/themes/asa.css">
<style>html,body{min-height:100vh;margin:0}
$EXTRA
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

  local PNG="$OUT/${NAME}.png"
  local PROFILE="$OUT/.profile-${NAME}"
  rm -f "$PNG"; rm -rf "$PROFILE"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars $FLAG \
    --window-size=1366,1024 --virtual-time-budget=$MS \
    --screenshot="$PNG" --user-data-dir="$PROFILE" \
    "file://$STAGE" >/dev/null 2>&1 &
  local PID=$!
  for _ in {1..120}; do
    [[ -s "$PNG" ]] && sleep 0.3 && break
    sleep 0.25
  done
  kill $PID 2>/dev/null; wait $PID 2>/dev/null
  rm -rf "$PROFILE"; rm -f "$STAGE"
  if [[ -s "$PNG" ]]; then
    print "$PNG  $(wc -c < "$PNG" | tr -d ' ') B"
  else
    print "FEHLT: $PNG"
  fi
}

PHASED=":root[data-theme='asa'] body::before,
:root[data-theme='asa']::before,
:root[data-theme='asa']::after{animation-delay:-SECs!important}"

for MS in 1000 4000 7000 10000; do
  SEC=$((MS / 1000))
  shoot "t${MS}" "${PHASED//SEC/$SEC}" "$MS"
done

# reduced-motion: der Beweis, dass alles auch stillsteht. Chrome kennt dafuer
# einen eigenen Schalter -- die Media-Query von Hand nachzubauen wuerde die
# Regel testen, die man selbst geschrieben hat, statt die des Browsers.
shoot "reduced-motion" "" 1500 "--force-prefers-reduced-motion"

# Der nackte Vektor ohne Schleier: nur zur Werkstatt-Kontrolle der Zeichnung.
# Was hier gut aussieht, ist noch lange nicht abgenommen -- abgenommen wird das
# Bild MIT Schleier, denn so sieht es der Nutzer.
STAGE_RAW="${TMPDIR:-/tmp}/asa-raw.html"
cat > "$STAGE_RAW" <<HTML
<!doctype html><html><head><meta charset="utf-8"><style>html,body{margin:0}
img{width:100vw;display:block}</style></head>
<body><img src="file://$ROOT/frontend/public/themes/asa-szene.svg"></body></html>
HTML
rm -f "$OUT/raw-vektor.png"; rm -rf "$OUT/.profile-raw"
"$CHROME" --headless=new --disable-gpu --hide-scrollbars \
  --window-size=1600,1000 --virtual-time-budget=1500 \
  --screenshot="$OUT/raw-vektor.png" --user-data-dir="$OUT/.profile-raw" \
  "file://$STAGE_RAW" >/dev/null 2>&1 &
PID=$!
for _ in {1..120}; do [[ -s "$OUT/raw-vektor.png" ]] && sleep 0.3 && break; sleep 0.25; done
kill $PID 2>/dev/null; wait $PID 2>/dev/null
rm -rf "$OUT/.profile-raw"; rm -f "$STAGE_RAW"
print "$OUT/raw-vektor.png  $(wc -c < "$OUT/raw-vektor.png" | tr -d ' ') B"
