#!/usr/bin/env python3
"""Render a VIEWABLE Yoake screenshot — for design judgement, not for numbers.

The measuring harness (measure-yoake.py) renders an EMPTY page on purpose, so
the worst pixel it finds is a property of the background alone. This one does
the opposite: it fills the app column with a realistic page (nav, a question, an
answer, status pills, a filled CTA, and the quietest text step standing directly
on the ground) so a human can look at the picture and say whether it is any good.

  python3 tools/theme-contrast/look-yoake.py [width] [dawn-phase-s] [out.png]
"""

import importlib.util
import os
import sys
import tempfile

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, _HERE)

# measure-yoake.py carries a dash, so it cannot be imported by name.
_spec = importlib.util.spec_from_file_location('measure_yoake', os.path.join(_HERE, 'measure-yoake.py'))
M = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(M)

WIDTH = int(sys.argv[1]) if len(sys.argv) > 1 else 1440
PHASE = float(sys.argv[2]) if len(sys.argv) > 2 else 300.0
OUT = sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.getcwd(), 'yoake-look.png')

STYLE = """
html,body{margin:0;height:100%}
.nav{}
.card{background:var(--bg-surface);border:1px solid var(--bg-hairline);border-radius:var(--radius);
      padding:14px 16px;margin:14px 18px}
.msg{margin:18px 20px;max-width:42rem}
.msg h3{margin:0 0 6px;font-size:15px;color:var(--text-1)}
.msg p{margin:0 0 4px;color:var(--text-2)}
.meta{color:var(--text-4);font-size:12px}
.q{background:var(--bg-user);border-radius:18px;padding:10px 14px;display:inline-block;color:var(--text-1)}
.pill{display:inline-block;padding:3px 10px;border-radius:999px;font-size:12px;margin-right:8px}
.cta{background:var(--accent);color:var(--accent-ink);border:0;border-radius:10px;padding:9px 16px;font-weight:600}
.sub{background:var(--bg-subtle);border-radius:12px;padding:12px 16px;margin:14px 18px;color:var(--text-4);font-size:14px}
"""

BODY = """<div class="app">
<div class="nav"><strong style="color:var(--text-1)">Hoshi</strong>
<span style="color:var(--text-3)">Zuhause</span><span style="color:var(--text-3)">Gespr&auml;ch</span>
<span style="color:var(--accent)">Einstellungen</span></div>
<div class="msg" style="text-align:right"><span class="q">Mach bitte das Licht im Wohnzimmer an.</span></div>
<div class="msg"><h3>Guten Morgen.</h3>
<p>Wohnzimmer ist an, 40 Prozent, warmwei&szlig;. Drau&szlig;en sind es 11 Grad, der Himmel wird gerade hell.</p>
<p class="meta">05:14 Uhr &middot; lokal beantwortet &middot; 0,4 s</p></div>
<div class="card"><span class="pill" style="background:var(--success-soft);color:var(--success)">verbunden</span>
<span class="pill" style="background:var(--warn-soft);color:var(--warn)">Akku 18&nbsp;%</span>
<span class="pill" style="background:var(--error-soft);color:var(--error)">Sensor offline</span>
<span class="pill" style="background:var(--accent-soft);color:var(--accent)">lokal</span></div>
<div class="card"><p style="color:var(--text-3);margin:0 0 10px">Noch nichts geplant f&uuml;r heute.</p>
<button class="cta">Routine anlegen</button></div>
<div class="sub">F&uuml;lltext auf --bg-subtle &mdash; die hellste Fl&auml;che, der harte Pr&uuml;fstein.</div>
<div class="msg"><p class="meta">Der leiseste Text des Themas steht hier, direkt auf dem Grund, ohne Karte
darunter &mdash; genau der Fall, den die D&auml;mpfung tragen muss.</p></div>
</div>"""


def main():
    freeze = M.phase_css(PHASE) if PHASE >= 0 else M.REDUCED_MOTION
    M._FIXTURES['/__look.html'] = M.head(STYLE + freeze) + '<body>' + BODY + '</body></html>'
    srv, port = M.serve()
    prof = tempfile.mkdtemp(prefix='yoake-chrome-')
    try:
        M.shoot('http://127.0.0.1:%d/__look.html' % port, WIDTH, 900, OUT, prof)
    finally:
        srv.shutdown()
    print('%s  (%d bytes, width %d, dawn phase %s)'
          % (OUT, os.path.getsize(OUT), WIDTH, 'reduced-motion' if PHASE < 0 else '%.0fs' % PHASE))


if __name__ == '__main__':
    main()
