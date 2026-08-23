#!/bin/zsh
# ─────────────────────────────────────────────────────────────────────────────
#  DIE FRAME-SERIE — der Beweis, dass sich etwas bewegt (Regie v2, Regel 4/6)
#
#  Ein Screenshot beweist ein Bild, aber nicht seinen Herzschlag. Dieses Skript
#  schießt dieselbe Mini-Bühne bei vier VIRTUELLEN Zeitpunkten (1/4/7/10 s):
#  Chrome rechnet die Animationen bis dorthin durch und knipst dann. Frames, die
#  sich messbar unterscheiden, sind der Beleg; identische Frames sind ein Befund.
#
#  Die Bühne ist absichtlich dieselbe wie bei der Galerie-Sichtung vom 19.08.
#  (1366×1024, 920-px-Spalte, zwei Karten) — nur so sind die Bilder mit Ukiyo
#  und Hanashigure vergleichbar.
#
#  Chrome beendet sich in diesem Modus NIE von selbst: auf die Datei warten,
#  dann den EIGENEN Kindprozess killen (Pod-Regel: nie einen fremden Chrome).
#
#  VIERTES ARGUMENT `still` (23.08.2026): schießt dieselbe Bühne mit
#  `data-scene-motion="still"` — dem Zustand, in dem die Szene steht, während
#  man Widgets anordnet (s. frontend/src/styles/themes.css). Vier gleiche
#  Frames sind hier der BEWEIS und kein Befund; wozu man sie braucht, ist die
#  Gegenprobe zur laufenden Serie: steht das Bild noch, oder ist es nur leer?
#
#    tools/theme-contrast/frames.sh [theme] [breite] [hoehe] [still]
#    → tools/theme-contrast/frames/<theme>[-still]-t{1,4,7,10}s.png
# ─────────────────────────────────────────────────────────────────────────────
set -e
THEME=${1:-nagareboshi}
WIDTH=${2:-1366}
HEIGHT=${3:-1024}
STILL=${4:-}

REPO=${0:A:h:h:h}
OUT="$REPO/tools/theme-contrast/frames"

if [[ -n "$STILL" ]]; then
  MOTION=' data-scene-motion="still"'
  SUFFIX='-still'
  # NUR im Still-Lauf kommt styles/themes.css dazu: dort steht die generische
  # Regel, die auch die CSS-Uhren des Wirtsdokuments anhält. Die normale Serie
  # lädt sie bewusst NICHT — ihr Vertrag ist „index.css + die eine Themen-
  # Datei", und was dort fehlt, soll ersatzlos fehlen (s. Kopf von
  # hanaikada.css). Ein Stylesheet mehr würde jeden bisherigen Frame ändern.
  EXTRA="<link rel=\"stylesheet\" href=\"file://$REPO/frontend/src/styles/themes.css\">"
else
  MOTION=''
  SUFFIX=''
  EXTRA=''
fi
WORK=$(mktemp -d /tmp/hoshi-frames-XXXXXX)
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
mkdir -p "$OUT"

cat > "$WORK/page.html" <<HTML
<!doctype html><html lang="de" data-theme="$THEME"$MOTION><head><meta charset="utf-8">
<link rel="stylesheet" href="file://$REPO/frontend/src/index.css">$EXTRA
<link rel="stylesheet" href="file://$REPO/frontend/public/themes/$THEME.css">
<style>html,body{min-height:100vh;margin:0}</style></head>
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

for T in 1000 4000 7000 10000; do
  PNG="$OUT/$THEME$SUFFIX-t$((T/1000))s.png"
  rm -f "$PNG"
  "$CHROME" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 \
    --no-first-run --no-default-browser-check \
    --window-size=$WIDTH,$HEIGHT --virtual-time-budget=$T \
    --screenshot="$PNG" --user-data-dir="$WORK/profile-$T" \
    "file://$WORK/page.html" >/dev/null 2>&1 &
  PID=$!
  for _ in {1..120}; do [[ -f "$PNG" ]] && break; perl -e 'select(undef,undef,undef,0.25)'; done
  perl -e 'select(undef,undef,undef,0.6)'
  kill $PID 2>/dev/null || true
  wait $PID 2>/dev/null || true
  print -r -- "$PNG  $(stat -f%z "$PNG" 2>/dev/null || echo 0) B  md5 $(md5 -q "$PNG" 2>/dev/null)"
done
rm -rf "$WORK"
