// Wirk-Beweis der Theme-Verdrahtung: EIN headless Chrome über CDP.
// (Die CLI-Flags --screenshot/--dump-dom hängen auf dieser Kiste, s. Vault.)
import { writeFileSync, readFileSync, readdirSync } from 'node:fs';

const CDP = 'http://127.0.0.1:9333';
const BASE = 'http://127.0.0.1:8791';
const OUT = process.argv[2];
const IDS = ['amayadori', 'natsunohi', 'nagareboshi', 'asa', 'yoake', 'aoi', 'yoru'];

const DIST =
  '/Users/andi/IdeaProjects/Hoshi_0.8/.claude/worktrees/theme-integration/frontend/dist';
const manifest = JSON.parse(readFileSync(`${DIST}/themes/manifest.json`, 'utf8'));

// ── winziger CDP-Client (Nodes eingebautes WebSocket, keine Abhängigkeit) ────
const version = await (await fetch(`${CDP}/json/version`)).json();
const ws = new WebSocket(version.webSocketDebuggerUrl);
await new Promise((r) => (ws.onopen = r));
let seq = 0;
const pending = new Map();
const events = [];
ws.onmessage = (m) => {
  const msg = JSON.parse(m.data);
  if (msg.id && pending.has(msg.id)) {
    const { resolve, reject } = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result);
  } else if (msg.method) events.push(msg);
};
const send = (method, params = {}, sessionId) =>
  new Promise((resolve, reject) => {
    const id = ++seq;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params, ...(sessionId ? { sessionId } : {}) }));
  });

const { targetId } = await send('Target.createTarget', { url: 'about:blank' });
const { sessionId } = await send('Target.attachToTarget', { targetId, flatten: true });
await send('Page.enable', {}, sessionId);
await send('Runtime.enable', {}, sessionId);

const goto = async (url) => {
  const seen = events.length;
  await send('Page.navigate', { url }, sessionId);
  for (let i = 0; i < 200; i++) {
    if (events.slice(seen).some((e) => e.method === 'Page.loadEventFired')) return;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`load timeout: ${url}`);
};
const evaluate = async (expression) => {
  const res = await send(
    'Runtime.evaluate',
    { expression, awaitPromise: true, returnByValue: true },
    sessionId,
  );
  if (res.exceptionDetails) throw new Error(JSON.stringify(res.exceptionDetails));
  return res.result.value;
};

// ── (a) das Manifest, so wie der Browser es sieht ────────────────────────────
await goto(`${BASE}/probe.html`);
const manifestSeen = await evaluate(`
  (async () => {
    const r = await fetch('/themes/manifest.json');
    const m = await r.json();
    return { status: r.status, ids: m.themes.map(t => t.id), szenen: m.themes.filter(t => t.group === 'szenen').map(t => t.id) };
  })()
`);

// ── (b) je Thema: CSS laden, data-theme setzen, Farben ZURÜCKLESEN ───────────
const appCss = '/assets/' + readdirSync(`${DIST}/assets`).find((f) => f.endsWith('.css'));

const themes = [];
for (const id of IDS) {
  await goto(`${BASE}/probe.html`);
  const entry = manifest.themes.find((t) => t.id === id);
  // Yoru bringt keine eigenen Token mit — es IST der Basis-Satz und wohnt in
  // index.css. Ohne das Bundle gäbe es bei ihm schlicht nichts zu messen.
  const extra = id === 'yoru' ? appCss : null;
  const r = await evaluate(`
    (async () => {
      const load = (href) => new Promise((res) => {
        const l = document.createElement('link');
        l.rel = 'stylesheet'; l.href = href;
        l.onload = () => res({ href, loaded: true });
        l.onerror = () => res({ href, loaded: false });
        document.head.appendChild(l);
      });
      document.documentElement.setAttribute('data-theme', ${JSON.stringify(id)});
      const links = [];
      ${extra ? `links.push(await load(${JSON.stringify(extra)}));` : ''}
      links.push(await load('/themes/${entry.file}'));
      const css = await fetch('/themes/${entry.file}');
      const svg = await fetch('/themes/${id}-szene.svg');
      const el = document.getElementById('probe');
      // Chrome serialisiert berechnete Farben in IHREM Farbraum (oklch bleibt
      // oklch). Für den sRGB-Wert, den der Schirm wirklich zeigt, lassen wir
      // Chrome selbst umrechnen: Farbe auf eine 1x1-Leinwand malen und das
      // Pixel zurücklesen — keine eigene Mathematik gegen eigene Mathematik.
      const cv = document.createElement('canvas');
      cv.width = cv.height = 1;
      const ctx = cv.getContext('2d', { willReadFrequently: true });
      const toHex = (color) => {
        ctx.clearRect(0, 0, 1, 1);
        ctx.fillStyle = color;
        ctx.fillRect(0, 0, 1, 1);
        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
        return '#' + [r, g, b].map((n) => n.toString(16).padStart(2, '0')).join('');
      };
      const read = (prop, token) => {
        el.style.cssText = 'width:10px;height:10px;' + prop + ':var(' + token + ')';
        const v = getComputedStyle(el)[prop === 'background-color' ? 'backgroundColor' : 'color'];
        return { computed: v, hex: toHex(v) };
      };
      const raw = getComputedStyle(document.documentElement).getPropertyValue('--accent').trim();
      return {
        links,
        cssStatus: css.status, cssBytes: (await css.text()).length,
        svgStatus: svg.status, svgBytes: (await svg.text()).length,
        rawAccent: raw,
        bgSurface: read('background-color', '--bg-surface'),
        accent: read('color', '--accent'),
        text1: read('color', '--text-1'),
        atmosphere: getComputedStyle(document.documentElement).getPropertyValue('--theme-atmosphere').trim().slice(0, 60),
      };
    })()
  `);
  themes.push({ id, file: entry.file, swatch: entry.swatch, ...r });
}

// ── (c) Screenshot der Galerie aus der ECHTEN App ────────────────────────────
await goto(`${BASE}/index.html`);
await new Promise((r) => setTimeout(r, 1500));
const opened = await evaluate(`
  (async () => {
    const wait = (ms) => new Promise(r => setTimeout(r, ms));
    const click = (sel) => { const e = document.querySelector(sel); if (e) { e.click(); return true; } return false; };
    const trail = [];
    // 1) Einstellungen öffnen (Knopf trägt ein aria-label/Titel)
    const btns = Array.from(document.querySelectorAll('button'));
    const settings = btns.find(b => /einstellung|settings/i.test(b.getAttribute('aria-label') || b.title || b.textContent || ''));
    trail.push('settingsBtn=' + !!settings);
    settings?.click();
    await wait(600);
    // 2) den Themen-Reiter, falls das Panel Reiter hat
    const tab = Array.from(document.querySelectorAll('button,[role=tab]')).find(b => /design|thema|theme|aussehen|look/i.test(b.textContent || ''));
    trail.push('tab=' + (tab ? tab.textContent.trim() : 'none'));
    tab?.click();
    await wait(400);
    // 3) die Galerie
    trail.push('galleryBtn=' + click('.settings__themegallerybtn'));
    await wait(900);
    const cards = document.querySelectorAll('.themegallery__card [role=radio], [role=radio]');
    return {
      trail,
      overlayOpen: !!document.querySelector('.overlay.is-open'),
      cards: cards.length,
      labels: Array.from(document.querySelectorAll('[role=radio]')).map(r => r.getAttribute('aria-label')),
      images: Array.from(document.querySelectorAll('.themegallery__card img')).map(i => i.getAttribute('src')),
      groups: Array.from(document.querySelectorAll('.themegallery__group h3, .themegallery__group h2, .themegallery__grouptitle')).map(h => h.textContent.trim()),
    };
  })()
`);
const shot = await send(
  'Page.captureScreenshot',
  { format: 'png', captureBeyondViewport: true },
  sessionId,
);
writeFileSync(process.argv[3], Buffer.from(shot.data, 'base64'));

// …und ein zweiter Schuss auf die sieben Wiederbelebten: die Liste so weit
// scrollen, dass Amayadori…Yoru mit ihren echten Szenen im Bild stehen.
const scrolled = await evaluate(`
  (async () => {
    const card = Array.from(document.querySelectorAll('[role=radio]'))
      .find(r => r.getAttribute('aria-label') === 'Szenen: Yoru');
    card?.scrollIntoView({ block: 'end' });
    await new Promise(r => setTimeout(r, 1200)); // Lazy-Bilder nachziehen lassen
    return {
      images: Array.from(document.querySelectorAll('.themegallery__card img')).map(i => i.getAttribute('src')),
      visible: Array.from(document.querySelectorAll('[role=radio]'))
        .filter(r => { const b = r.getBoundingClientRect(); return b.top > 0 && b.bottom < innerHeight; })
        .map(r => r.getAttribute('aria-label')),
    };
  })()
`);
const shot2 = await send('Page.captureScreenshot', { format: 'png' }, sessionId);
writeFileSync(process.argv[4], Buffer.from(shot2.data, 'base64'));
opened.scrolled = scrolled;

writeFileSync(OUT, JSON.stringify({ manifestSeen, themes, opened, chrome: version.Browser }, null, 2));
console.log('OK', version.Browser);
await send('Target.closeTarget', { targetId });
ws.close();
