/**
 * Minimaler PNG-Dekoder (nur was Chromes `--screenshot` erzeugt).
 * ───────────────────────────────────────────────────────────────────────────
 * Unterstützt: Bittiefe 8, Farbtyp 2 (RGB) und 6 (RGBA), nicht interlaced,
 * beliebig viele IDAT-Chunks. Mehr braucht ein Chrome-Screenshot nicht — und
 * mehr zu können hieße, Code zu pflegen, den nie jemand ausführt.
 *
 * Warum kein npm-Paket: das Harness soll in einem frischen Worktree ohne
 * `npm install` laufen (und ohne dass irgendwer einer Kette von Transitiv-
 * Abhängigkeiten vertrauen muss, nur um Pixel zu zählen). zlib ist in Node.
 */

import { inflateSync } from 'node:zlib';

/** @returns {{width:number,height:number,channels:number,data:Uint8Array}} */
export function decodePng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('kein PNG');

  let pos = 8;
  let width = 0;
  let height = 0;
  let channels = 0;
  const idat = [];

  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const body = buf.subarray(pos + 8, pos + 8 + len);
    pos += 12 + len; // len + type + data + crc

    if (type === 'IHDR') {
      width = body.readUInt32BE(0);
      height = body.readUInt32BE(4);
      const depth = body[8];
      const colorType = body[9];
      const interlace = body[12];
      if (depth !== 8) throw new Error(`Bittiefe ${depth} nicht unterstützt`);
      if (interlace !== 0) throw new Error('interlaced nicht unterstützt');
      if (colorType === 2) channels = 3;
      else if (colorType === 6) channels = 4;
      else throw new Error(`Farbtyp ${colorType} nicht unterstützt`);
    } else if (type === 'IDAT') {
      idat.push(body);
    } else if (type === 'IEND') {
      break;
    }
  }

  const raw = inflateSync(Buffer.concat(idat));
  const stride = width * channels;
  const out = new Uint8Array(width * height * channels);

  // PNG-Filter rückgängig machen (Spec 9.2). `prev` ist die bereits
  // entfilterte Vorzeile — deshalb läuft das strikt von oben nach unten.
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)];
    const line = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
    const cur = out.subarray(y * stride, y * stride + stride);
    const prev = y > 0 ? out.subarray((y - 1) * stride, (y - 1) * stride + stride) : null;

    for (let i = 0; i < stride; i++) {
      const a = i >= channels ? cur[i - channels] : 0; // links
      const b = prev ? prev[i] : 0; // oben
      const c = prev && i >= channels ? prev[i - channels] : 0; // oben-links
      let v = line[i];
      switch (filter) {
        case 0:
          break;
        case 1:
          v += a;
          break;
        case 2:
          v += b;
          break;
        case 3:
          v += (a + b) >> 1;
          break;
        case 4: {
          const p = a + b - c;
          const pa = Math.abs(p - a);
          const pb = Math.abs(p - b);
          const pc = Math.abs(p - c);
          v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
          break;
        }
        default:
          throw new Error(`Filter ${filter} unbekannt`);
      }
      cur[i] = v & 0xff;
    }
  }

  return { width, height, channels, data: out };
}

/** Pixel als sRGB 0..1 (Alpha ignoriert — ein Screenshot ist deckend). */
export function pixel(img, x, y) {
  const i = (y * img.width + x) * img.channels;
  return [img.data[i] / 255, img.data[i + 1] / 255, img.data[i + 2] / 255];
}
