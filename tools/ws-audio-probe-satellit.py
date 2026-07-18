#!/usr/bin/env python3
# ws-audio-probe-satellit.py — /ws/audio-E2E-Probe gegen Prod (ct-106:8082), OHNE Gerät.
#
# Spielt EIN fertiges WAV (PCM16 mono 16 kHz) als Satelliten-Turn ein — exakt das
# Wire-Verhalten der Route-B-Firmware (PROTOCOL.md, hoshi-satellite/firmware/):
#   {start, mimeType:audio/wav, turnId} → WAV-Blob in ≤16-KB-Binär-Chunks → {stop}
#   → Downlink lesen bis llm_done / llm_error / no_input.
# Beweist die Kette Auth(?token=) → STT → Route → TTS → llm_audio VOR dem Flash.
#
# TLS wie das Gerät: Leaf-Pin. Live-Fetch (TOFU) + SHA-256-Abgleich gegen den
# dokumentierten Pin (PROTOCOL.md §1) — bei Mismatch ABBRUCH, kein Insecure-Fallback.
# Token: HOSHI_API_TOKEN oder ~/.hoshi/secrets.json["api"] — wird NIE ausgegeben,
# URL wird nur ohne Query geloggt. satelliteId markiert den Turn als Test-Traffic
# (Konvention analog chatId-Präfix „replay-satellit-" am Text-Rand).
#
# Aufruf: python3 tools/ws-audio-probe-satellit.py <wav> [outdir]
# Exit 0 = transcript nicht leer UND llm_done kam. llm_audio-Segmente landen im outdir.
#
# Test-WAV erzeugen (state-sicher, brain-frei — Golden #20):
#   say -v Anna -o /tmp/probe.aiff "Hoshi, Probe." \
#   && afconvert -f WAVE -d LEI16@16000 -c 1 /tmp/probe.aiff /tmp/probe-16k.wav
#
# Befund 09.07 ~00:15 (server-hand): HTTP 404 beim Handshake = WS-Rand flag-gated
# default OFF (HOSHI_WS_AUDIO_ENABLED, WebSocketConfig.kt) — erst Flag AN, dann proben.
import asyncio, base64, hashlib, json, os, ssl, struct, sys, tempfile, time, uuid

HOST, PORT, PATH = "192.168.178.106", 8082, "/ws/audio"
EXPECTED_FP = ("9F:C7:65:62:73:81:C8:F1:E3:5F:C8:40:E1:A1:40:DE:"
               "35:C6:49:C2:C5:03:8F:3F:8A:6D:B8:EF:21:94:21:A1")
UPLINK_CHUNK = 16384          # PROTOCOL.md §3.2: Server kappt oversized Frames
RECV_TIMEOUT_S = 45           # je Frame; Gesamtlauf endet mit llm_done/error/no_input
SECRETS = os.path.expanduser("~/.hoshi/secrets.json")


def load_token() -> str:
    tok = os.environ.get("HOSHI_API_TOKEN", "")
    if not tok and os.path.exists(SECRETS):
        try:
            tok = json.load(open(SECRETS)).get("api", "")
        except Exception:
            tok = ""
    if not tok:
        sys.exit("FEHLER: kein Token (HOSHI_API_TOKEN oder secrets.json[api])")
    return tok


def pinned_ssl_context(outdir: str) -> ssl.SSLContext:
    pem = ssl.get_server_certificate((HOST, PORT))
    der = ssl.PEM_cert_to_DER_cert(pem)
    fp = hashlib.sha256(der).hexdigest().upper()
    fp = ":".join(fp[i:i + 2] for i in range(0, len(fp), 2))
    if fp != EXPECTED_FP:
        sys.exit(f"PIN-MISMATCH! serviert={fp}\n erwartet={EXPECTED_FP}\n"
                 "→ Cert rotiert oder MITM. Abbruch (Firmware würde ebenso ablehnen).")
    pem_path = os.path.join(outdir, "ct106-leaf.pem")
    open(pem_path, "w").write(pem)
    ctx = ssl.create_default_context(cafile=pem_path)
    return ctx


def check_wav(path: str) -> bytes:
    blob = open(path, "rb").read()
    if len(blob) < 44 or blob[:4] != b"RIFF" or blob[8:12] != b"WAVE":
        sys.exit(f"FEHLER: {path} ist kein WAV")
    ch, rate = struct.unpack_from("<HI", blob, 22)
    bits = struct.unpack_from("<H", blob, 34)[0]
    if (ch, rate, bits) != (1, 16000, 16):
        sys.exit(f"FEHLER: WAV muss mono/16000Hz/16bit sein, ist {ch}ch/{rate}Hz/{bits}bit")
    return blob


async def run(wav_path: str, outdir: str) -> int:
    import websockets
    token = load_token()
    blob = check_wav(wav_path)
    ctx = pinned_ssl_context(outdir)
    turn_id = str(uuid.uuid4())
    url_shown = f"wss://{HOST}:{PORT}{PATH}"
    url = f"{url_shown}?token={token}"

    print(f"== ws-audio-probe gegen {url_shown} (auth=query, Leaf-Pin ✓) ==")
    print(f"   wav={os.path.basename(wav_path)} ({len(blob)} B ≙ {(len(blob)-44)/32000:.2f}s) turnId={turn_id[:8]}…")

    t0 = time.monotonic()
    marks: dict[str, float] = {}
    deltas: list[str] = []
    transcript = ""
    audio_segments: list[tuple[int, bytes]] = []
    done = err = no_input = False

    async with websockets.connect(url, ssl=ctx, max_size=8 * 1024 * 1024) as ws:
        marks["connected"] = time.monotonic() - t0
        await ws.send(json.dumps({"type": "start", "mimeType": "audio/wav",
                                  "turnId": turn_id,
                                  "satelliteId": "replay-satellit-wsprobe"}))
        for off in range(0, len(blob), UPLINK_CHUNK):
            await ws.send(blob[off:off + UPLINK_CHUNK])
        await ws.send(json.dumps({"type": "stop"}))
        marks["uplink_done"] = time.monotonic() - t0

        while not (done or err or no_input):
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=RECV_TIMEOUT_S)
            except asyncio.TimeoutError:
                print(f"⚠ recv-Timeout nach {RECV_TIMEOUT_S}s — Abbruch")
                break
            except websockets.ConnectionClosed as e:
                print(f"⚠ ws geschlossen: code={e.code}")
                break
            if isinstance(raw, bytes):
                continue  # Stray-Binär-Downlink ignorieren (PROTOCOL.md §5)
            try:
                ev = json.loads(raw)
            except Exception:
                continue
            t = ev.get("type", "?")
            ts = time.monotonic() - t0
            marks.setdefault(t, ts)
            if t == "transcript":
                transcript = ev.get("text", "")
                print(f"  +{ts:6.2f}s transcript      → „{transcript}“")
            elif t == "llm_delta":
                deltas.append(ev.get("text", ""))
            elif t == "llm_audio":
                seq = ev.get("seq", -1)
                pcm = base64.b64decode(ev.get("data", "") or "")
                audio_segments.append((seq, pcm))
                marks.setdefault("first_audio", ts)
                rate = struct.unpack_from("<I", pcm, 24)[0] if len(pcm) > 28 and pcm[:4] == b"RIFF" else 0
                print(f"  +{ts:6.2f}s llm_audio seq={seq} ({len(pcm)} B dekodiert, {rate} Hz)")
            elif t == "llm_done":
                done = True
                print(f"  +{ts:6.2f}s llm_done        ttsHandled={ev.get('ttsHandled')}")
            elif t == "llm_error":
                err = True
                print(f"  +{ts:6.2f}s llm_error       [{ev.get('stage')}] {ev.get('message','')}")
            elif t == "no_input":
                no_input = True
                print(f"  +{ts:6.2f}s no_input        (keine Sprache erkannt)")
            else:
                extra = {k: v for k, v in ev.items() if k not in ("type", "data")}
                print(f"  +{ts:6.2f}s {t:<15} {json.dumps(extra, ensure_ascii=False)[:120]}")

    answer = "".join(deltas).replace("\n", " ").strip()
    if answer:
        print(f"   Antwort: „{answer[:250]}“")
    for seq, pcm in sorted(audio_segments):
        p = os.path.join(outdir, f"llm_audio-{seq:02d}.wav")
        open(p, "wb").write(pcm)
    if audio_segments:
        total = sum(len(p) for _, p in audio_segments)
        print(f"   Audio: {len(audio_segments)} Segment(e), {total} B → {outdir}/llm_audio-*.wav")
    lat = " · ".join(f"{k}={v:.2f}s" for k, v in marks.items())
    print(f"   ⏱ {lat}")

    ok = done and bool(transcript)
    print(f"== {'GRÜN: Kette Auth→STT→Route→TTS→Downlink steht' if ok else 'ROT: Kette unvollständig'} ==")
    return 0 if ok else 1


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(f"Aufruf: {sys.argv[0]} <wav-16k-mono-pcm16> [outdir]")
    out = sys.argv[2] if len(sys.argv) > 2 else tempfile.mkdtemp(prefix="hoshi-wsprobe.")
    os.makedirs(out, exist_ok=True)
    sys.exit(asyncio.run(run(sys.argv[1], out)))
