// Winziger Static-Server über dist/ — eigener Port, keine Abhängigkeiten.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { extname, join, normalize } from 'node:path';

const ROOT = process.argv[2];
const PORT = Number(process.argv[3] ?? 8791);
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.json': 'application/json; charset=utf-8',
};

createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  let path = normalize(decodeURIComponent(url.pathname));
  if (path.endsWith('/')) path += 'index.html';
  // Zweiter Fallback-Ordner (Scratchpad) — damit die Probe-Seite same-origin
  // neben den echten dist/-Dateien liegt, ohne dist/ zu verschmutzen.
  const FALLBACK = process.argv[4];
  try {
    let body;
    try {
      body = await readFile(join(ROOT, path));
    } catch (e) {
      if (!FALLBACK) throw e;
      body = await readFile(join(FALLBACK, path));
    }
    res.writeHead(200, { 'content-type': TYPES[extname(path)] ?? 'application/octet-stream' });
    res.end(body);
  } catch {
    res.writeHead(404, { 'content-type': 'text/plain' });
    res.end('404');
  }
}).listen(PORT, '127.0.0.1', () => console.log(`serving ${ROOT} on ${PORT}`));
