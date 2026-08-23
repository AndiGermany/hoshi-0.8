#!/usr/bin/env python3
"""Contrast harness for the Yoake scene theme (headless Chrome, no deps).

Why this file exists: the scene themes (asagiri, momiji, ukiyo …) all claim
measured WCAG numbers in their headers, but the repo never carried the thing
that measured them — verified 2026-08-18. So the Yoake pod built one.

Two modes:

  probe   Renders every colour token as an opaque swatch, reads the exact sRGB
          Chrome resolved it to, and prints the full contrast table (text steps
          and signal colours against all five real surfaces, the *-soft chip
          case, accent-ink on accent, hairline as a control border).
          -> these are the numbers in the CSS header.

  scene   Renders the REAL empty app page (index.css base tokens + the complete
          theme file, so the scene layers, the drawing and the veil are all
          live) at several window widths and several animation phases, then
          finds the WORST pixel inside the centred 920 px app column.
          -> this is the "the picture may not eat the text" proof.

The scene sweep holds every clock at its BRIGHTEST extreme (dawn glow at full
strength, night curtain fully lifted) and sweeps the one clock that moves the
light geometrically. Every other phase combination composites strictly darker
than the sampled envelope, and darker means MORE contrast for light-on-dark.

Chrome runs with its own --user-data-dir under the scratch dir; no foreign
Chrome process is ever touched.

Usage:
  python3 tools/theme-contrast/measure-yoake.py probe
  python3 tools/theme-contrast/measure-yoake.py scene [--widths 1024,1280,...]
"""

import argparse
import functools
import http.server
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from png import read_png, luminance, contrast  # noqa: E402

REPO = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
WEBROOT = os.path.join(REPO, 'frontend')
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
THEME = 'yoake'
COLUMN = 920  # .app max-width in src/index.css

# --- the theme's animated layers: selector -> [(keyframe-name, duration_s), ...]
#     order matters, it is the order of the `animation:` shorthand in the CSS.
LAYERS = [
    (":root[data-theme='%s'] body::before" % THEME, [('yoake-dawn-rise', 487), ('yoake-dawn-breath', 197)]),
    (":root[data-theme='%s']::before" % THEME, [('yoake-stars-fade', 271)]),
    (":root[data-theme='%s']::after" % THEME, [('yoake-night-lift', 383), ('yoake-night-thin', 271)]),
]

# The still frame this theme shows under prefers-reduced-motion. Measured as its
# own case, because it is NOT the `from` frame of the animations.
REDUCED_MOTION = (
    ":root[data-theme='{t}'] body::before{{animation:none !important;"
    "transform:translate3d(0.4%,1.9%,0) !important;opacity:.88 !important}}"
    ":root[data-theme='{t}']::before{{animation:none !important;opacity:.55 !important}}"
    ":root[data-theme='{t}']::after{{animation:none !important;"
    "transform:translate3d(0,-3.5%,0) !important;opacity:.68 !important}}"
).format(t=THEME)

SURFACES = ['bg-base', 'bg-surface', 'bg-elevated', 'bg-subtle', 'bg-user']
TEXTS = ['text-1', 'text-2', 'text-3', 'text-4']
SIGNALS = ['accent', 'success', 'warn', 'error']

# ─────────────────────────────────────────────────────────────────────────────
# tiny web server: serves frontend/ plus one generated fixture
# ─────────────────────────────────────────────────────────────────────────────

_FIXTURES = {}


class Handler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        name = self.path.split('?')[0]
        if name in _FIXTURES:
            body = _FIXTURES[name].encode('utf-8')
            self.send_response(200)
            self.send_header('Content-Type', 'text/html; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        return super().do_GET()

    def log_message(self, *_args):
        pass


def serve():
    handler = functools.partial(Handler, directory=WEBROOT)
    srv = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, srv.server_address[1]


def shoot(url, width, height, out, profile):
    """Screenshot one URL.

    Chrome 151 on this Mac WRITES the png and then never exits (the bundled
    GoogleUpdater keeps the process group alive), so `subprocess.run` would
    block forever. We therefore launch it detached, wait for the file to appear
    AND stop growing, and then kill our own process group — never a foreign
    Chrome, the profile dir is ours alone.
    """
    if os.path.exists(out):
        os.remove(out)
    cmd = [
        CHROME, '--headless', '--disable-gpu', '--hide-scrollbars',
        '--force-device-scale-factor=1', '--user-data-dir=' + profile,
        '--no-first-run', '--no-default-browser-check',
        '--disable-background-networking', '--disable-component-update',
        '--window-size=%d,%d' % (width, height),
        '--virtual-time-budget=3000',
        '--screenshot=' + out, url,
    ]
    proc = subprocess.Popen(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                            start_new_session=True)
    size = -1
    stable = 0
    try:
        for _ in range(600):  # 60 s ceiling
            time.sleep(0.1)
            if os.path.exists(out):
                now = os.path.getsize(out)
                if now > 0 and now == size:
                    stable += 1
                    if stable >= 3:
                        break
                else:
                    stable = 0
                size = now
            if proc.poll() is not None and os.path.exists(out):
                break
        else:
            raise RuntimeError('chrome never produced %s' % out)
    finally:
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
        proc.wait()
    return read_png(out)


def head(extra_style=''):
    return (
        '<!doctype html><html data-theme="%s"><head><meta charset="utf-8">'
        # NOTE: a plain file server does not do Vite's public/ -> / mapping, so the
        # theme path carries the real on-disk prefix. Getting this wrong silently
        # falls back to the Yoru base tokens and every number becomes a lie.
        '<link rel="stylesheet" href="/src/index.css">'
        '<link rel="stylesheet" href="/public/themes/%s.css">'
        '<style>%s</style></head>' % (THEME, THEME, extra_style)
    )


# ─────────────────────────────────────────────────────────────────────────────
# probe: exact token colours + the contrast table
# ─────────────────────────────────────────────────────────────────────────────

def probe():
    solids = SURFACES + ['bg-hairline', 'bg-hairline-dashed'] + TEXTS + SIGNALS + ['accent-ink']
    # composited: a *-soft alpha fill sitting on a real surface (that is how the
    # pills/badges in index.css are built) -> read the resulting opaque pixel.
    comps = [(s + '-soft', surf) for s in SIGNALS for surf in ('bg-surface', 'bg-base')]

    cells = []
    for t in solids:
        cells.append('<i style="background:var(--%s)"></i>' % t)
    for tok, surf in comps:
        cells.append('<i style="background:var(--%s)"><b style="background:var(--%s)"></b></i>'
                     % (surf, tok))

    style = (
        'html,body{margin:0;background:#808080}'
        '#g{display:flex;flex-wrap:nowrap}'
        '#g i{display:block;width:20px;height:20px;position:relative}'
        '#g i b{position:absolute;inset:0;display:block}'
    )
    _FIXTURES['/__probe.html'] = head(style) + '<body><div id="g">' + ''.join(cells) + '</div></body></html>'

    srv, port = serve()
    profile = tempfile.mkdtemp(prefix='yoake-chrome-')
    shot = os.path.join(profile, 'probe.png')
    try:
        w, h, nch, px = shoot('http://127.0.0.1:%d/__probe.html' % port, max(1200, 20 * len(cells) + 40), 200,
                              shot, profile)
    finally:
        srv.shutdown()

    def pick(i):
        x, y = i * 20 + 10, 10
        o = (y * w + x) * nch
        return px[o], px[o + 1], px[o + 2]

    col = {}
    for i, t in enumerate(solids):
        col[t] = pick(i)
    for j, (tok, surf) in enumerate(comps):
        col['%s@%s' % (tok, surf)] = pick(len(solids) + j)
    shutil.rmtree(profile, ignore_errors=True)

    # ── The lie-detector (lesson of 18.08.: a probe without the themes import
    # reports the BASE tokens and every number it prints is about Yoru, not
    # about this theme). --bg-hairline-dashed exists ONLY in theme files; if it
    # comes back as the harness page grey, the stylesheet never loaded.
    if col['bg-hairline-dashed'] == (128, 128, 128):
        sys.exit('ABORT: --bg-hairline-dashed is unset -> %s.css did not load. '
                 'Every measurement would be about the base tokens.' % THEME)

    lum = {k: luminance(*v) for k, v in col.items()}
    hexs = {k: '#%02x%02x%02x' % v for k, v in col.items()}

    print('── TOKEN → sRGB (as Chrome resolved it) ' + '─' * 36)
    for k in sorted(col):
        print('   %-24s %s  rgb%s' % (k, hexs[k], col[k]))

    print('\n── TEXT & SIGNAL vs. ALL FIVE SURFACES (worst wins) ' + '─' * 24)
    rows = []
    for t in TEXTS + SIGNALS:
        vals = [(contrast(lum[t], lum[s]), s) for s in SURFACES]
        worst = min(vals)
        rows.append((t, worst))
        print('   %-10s worst %5.2f:1  on %-12s   [%s]' % (
            t, worst[0], worst[1], '  '.join('%s %.2f' % (s.replace('bg-', ''), c) for c, s in vals)))

    print('\n── CHIP CASE: colour on its OWN -soft fill ' + '─' * 33)
    for s in SIGNALS:
        for surf in ('bg-surface', 'bg-base'):
            key = '%s-soft@%s' % (s, surf)
            print('   %-8s on %s-soft over %-11s %5.2f:1   (%s)'
                  % (s, s, surf, contrast(lum[s], lum[key]), hexs[key]))

    print('\n── FILLED CTA & CONTROL BORDERS ' + '─' * 43)
    print('   accent-ink on accent            %5.2f:1' % contrast(lum['accent-ink'], lum['accent']))
    # Both hairlines against ALL five surfaces, not just the flattering three:
    # --bg-hairline is the only contour of real controls (.inbox__select,
    # .rooms__search, .rooms__chip, .feed__chip, .feed__refresh, .feed__loadmore,
    # .sched__delall, .compose__bar, .msg__sources__summary), so WCAG 1.4.11
    # applies against whatever surface the control happens to sit on.
    hair_worst = {}
    for tok in ('bg-hairline', 'bg-hairline-dashed'):
        vals = [(contrast(lum[tok], lum[s]), s) for s in SURFACES]
        hair_worst[tok] = min(vals)
        print('   %-18s worst %5.2f:1 on %-12s [%s]%s'
              % (tok, hair_worst[tok][0], hair_worst[tok][1],
                 '  '.join('%s %.2f' % (s.replace('bg-', ''), c) for c, s in vals),
                 '   (WCAG 1.4.11 needs 3.0)' if tok == 'bg-hairline' else ''))

    fails = [(t, w) for t, w in rows if w[0] < 4.5]
    fails += [('%s-on-soft' % s, c) for s in SIGNALS
              for c in [contrast(lum[s], lum['%s-soft@bg-surface' % s])] if c < 4.5]
    if hair_worst['bg-hairline'][0] < 3.0:
        fails.append(('bg-hairline<3.0', hair_worst['bg-hairline']))
    print('\n   %s' % ('TEXT & CHIPS ≥ 4.5:1, HAIRLINE ≥ 3.0:1 ✓' if not fails
                       else 'FAIL: ' + repr(fails)))
    return col, lum


# ─────────────────────────────────────────────────────────────────────────────
# scene: worst pixel under the app column, real render
# ─────────────────────────────────────────────────────────────────────────────

def phase_css(dawn_rise_t):
    """Freeze every clock at its brightest extreme; sweep only the dawn geometry.

    `to` of the breath/thin animations is the dim/lifted end, so a delay of one
    full duration parks the paused frame exactly there.
    """
    extremes = {
        'yoake-dawn-rise': dawn_rise_t,
        'yoake-dawn-breath': 0,              # from = full glow  (brightest)
        'yoake-stars-fade': 0,               # from = stars at full (brightest)
        'yoake-night-lift': 383 - 0.1,       # to  = curtain lifted (brightest)
        'yoake-night-thin': 271 - 0.1,       # to  = curtain thinnest (brightest)
    }
    out = []
    for sel, anims in LAYERS:
        delays = ', '.join('-%gs' % extremes[n] for n, _d in anims)
        out.append('%s{animation-delay:%s !important;animation-play-state:paused !important}'
                   % (sel, delays))
    return ''.join(out)


def worst_pixel(w, h, nch, px, lums):
    """Return (worst_ratio_per_token, brightest_hex) inside the centred column."""
    x0 = max(0, (w - COLUMN) // 2)
    x1 = min(w, x0 + COLUMN)
    best_l = -1.0
    best_rgb = (0, 0, 0)
    for y in range(h):
        row = y * w
        for x in range(x0, x1):
            o = (row + x) * nch
            l = luminance(px[o], px[o + 1], px[o + 2])
            if l > best_l:
                best_l = l
                best_rgb = (px[o], px[o + 1], px[o + 2])
    return ({t: contrast(lums[t], best_l) for t in lums},
            '#%02x%02x%02x' % best_rgb, best_l)


def scene(widths, phases, lums):
    srv, port = serve()
    profile = tempfile.mkdtemp(prefix='yoake-chrome-')
    shot = os.path.join(profile, 'scene.png')
    overall = {}
    rm = {}
    cases = [(round(487.0 * i / max(1, phases - 1), 1), None) for i in range(phases)]
    cases.append((-1.0, REDUCED_MOTION))  # the still frame, measured on its own
    try:
        for t, override in cases:
            _FIXTURES['/__scene.html'] = (
                head('html,body{margin:0;height:100%}' + (override or phase_css(t)))
                + '<body><div class="app"></div></body></html>')
            for wdt in widths:
                w, h, nch, px = shoot('http://127.0.0.1:%d/__scene.html?p=%s' % (port, t),
                                      wdt, 900, shot, profile)
                ratios, hexs, lum = worst_pixel(w, h, nch, px, lums)
                bucket = rm if t < 0 else overall
                cur = bucket.get(wdt)
                if cur is None or lum > cur[2]:
                    bucket[wdt] = (ratios, hexs, lum, t)
                print('   %-11s w=%-5d worst px %s  text-4 %5.2f:1  text-1 %6.2f:1'
                      % ('reduced-mo.' if t < 0 else 'phase %.0fs' % t,
                         wdt, hexs, ratios['text-4'], ratios['text-1']))
    finally:
        srv.shutdown()
        shutil.rmtree(profile, ignore_errors=True)

    print('\n── WORST PIXEL IN THE 920px COLUMN, per window width ' + '─' * 23)
    floor4 = 99.0
    for wdt in widths:
        ratios, hexs, lum, t = overall[wdt]
        floor4 = min(floor4, ratios['text-4'])
        print('   %-6d %s  text-4 %5.2f:1 · text-3 %5.2f:1 · text-1 %6.2f:1   (dawn phase %.0fs)'
              % (wdt, hexs, ratios['text-4'], ratios['text-3'], ratios['text-1'], t))
    print('\n   %s  (floor %.2f:1)' % ('PASS ≥ 4.5:1 ✓' if floor4 >= 4.5 else 'FAIL < 4.5:1', floor4))

    print('\n── prefers-reduced-motion STILL FRAME ' + '─' * 38)
    floor_rm = 99.0
    for wdt in widths:
        ratios, hexs, lum, _t = rm[wdt]
        floor_rm = min(floor_rm, ratios['text-4'])
        print('   %-6d %s  text-4 %5.2f:1 · text-1 %6.2f:1' % (wdt, hexs, ratios['text-4'], ratios['text-1']))
    print('\n   %s  (floor %.2f:1)' % ('PASS ≥ 4.5:1 ✓' if floor_rm >= 4.5 else 'FAIL < 4.5:1', floor_rm))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('mode', choices=['probe', 'scene', 'all'])
    ap.add_argument('--widths', default='1024,1280,1440,1920,2560')
    ap.add_argument('--phases', type=int, default=9)
    args = ap.parse_args()

    if not os.path.exists(CHROME):
        sys.exit('Chrome not found at %s' % CHROME)

    _col, lums = probe()
    if args.mode in ('scene', 'all'):
        print('\n── SCENE SWEEP (real render, all clocks at their brightest) ' + '─' * 16)
        scene([int(x) for x in args.widths.split(',')], args.phases,
              {t: lums[t] for t in TEXTS})


if __name__ == '__main__':
    main()
