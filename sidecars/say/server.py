"""
server.py — Mac-Sidecar für macOS-`say`-TTS (Port 8044).

DRITTE TTS-Engine neben Voxtral (:8042, sidecars/) und OpenAI-Cloud-TTS: reine
Bordmittel, kein Modell im RAM, kein Cloud-Egress. Nutzt ausschließlich zwei
macOS-Bordwerkzeuge als Subprozess:
    /usr/bin/say       — Text -> Sprache, headless via `-o <file>.aiff`
    /usr/bin/afconvert — AIFF -> WAV PCM16 mono @ 22050 Hz (LEI16@22050)

Schwester der hoshi-speaker-id/hoshi-knowledge-bridge-Sidecars (siehe deren
server.py) — FastAPI/argparse/Logging-Stil 1:1 übernommen. Vorbild für den
eigentlichen say/afconvert-Umgang: Hoshi_0.5/hoshi-tts-say/server.py (dort ein
roher http.server-Handler auf Port 8043/24kHz) — HIER bewusst FastAPI (Muster-
Treue zu den 0.8-Geschwistern) + eigener Port :8044 (kollidiert nicht mit einer
eventuell noch laufenden 0.5-Instanz) + 22050 statt 24000 Hz (Auftragsvorgabe).

API:
- GET  /health  → 200 {status:"ok", engine:"say", default_voice, voice_count}
- GET  /voices  → {"voices":[{"name","locale","sample"}, …]} — deutsche
                  Stimmen (locale beginnt mit "de_") ZUERST, sonst
                  Erscheinungsreihenfolge aus `say -v '?'`. Einmal beim Start
                  geparst + gecacht (Prozess läuft, bis der Sidecar neu startet
                  — installierte Stimmen ändern sich nicht zur Laufzeit).
- POST /tts     {"text": str, "voice": str|null, "rate": int|null} → WAV-Bytes
                  (audio/wav, LEI16@22050 mono). `voice` = wörtlicher macOS-
                  Stimmname (z.B. "Anna", "Anna (Enhanced)"); fehlt sie, greift
                  DEFAULT_VOICE. `rate` = Wörter/Minute (say -r); fehlt sie,
                  entscheidet `say` selbst. Text-Länge > MAX_TEXT_CHARS ⇒ 413
                  (Sidecar-Schutz vor Endlos-Synthese); leerer/fehlender Text
                  ⇒ 422. say/afconvert-Fehler ⇒ 500 mit gekapptem stderr.

Never-Silent liegt EINE Etage höher: dieser Sidecar antwortet ehrlich (4xx/5xx
statt stillem Müll) — der aufrufende SayTtsAdapter (Hoshi_0.8-Backend) fängt
jeden Fehler best-effort ab (leeres Audio statt Crash), damit der Text-Turn nie
an der Audio-Schicht stirbt.
"""
from __future__ import annotations

import argparse
import logging
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Optional

from fastapi import FastAPI, HTTPException, Response
from pydantic import BaseModel

# ── Logging ─────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("hoshi-tts-say")

# ── Argparse ────────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Hoshi-TTS-Say: macOS-`say`-Sidecar")
parser.add_argument("--host", default="0.0.0.0", help="Bind-Adresse (default 0.0.0.0 — ct-106 ruft übers LAN)")
parser.add_argument("--port", type=int, default=int(os.environ.get("HOSHI_SAY_PORT", "8044")), help="Port (default 8044)")
parser.add_argument(
    "--default-voice",
    default=os.environ.get("HOSHI_SAY_DEFAULT_VOICE", "Anna"),
    help="macOS-Stimmname, wenn der Request keine 'voice' mitschickt (default 'Anna', de_DE)",
)
parser.add_argument(
    "--selftest",
    action="store_true",
    help="Synthetisiert 'Hallo' lokal (KEIN Server-Start), prüft RIFF-Header + Nicht-Leere, exit 0/1.",
)
args, _unknown = parser.parse_known_args()

DEFAULT_VOICE = args.default_voice
# Hart begrenzte Text-Länge — Schutz vor absichtlich/versehentlich riesigen
# Synthese-Aufträgen (say lief in Tests bei sehr langem Text spürbar länger als
# den 60s-Subprozess-Timeout). 1000 Zeichen sind für Turn-große Sprache reichlich.
MAX_TEXT_CHARS = 1000
SAY_BIN = "/usr/bin/say"
AFCONVERT_BIN = "/usr/bin/afconvert"


# ── say -v '?' parsen (Cache beim Start) ─────────────────────────────────────
# Zeilenformat (macOS, geprüft): "<Name mit evtl. Leerzeichen/Klammern>  <locale>  # <Beispielsatz>"
# z.B. "Anna                de_DE    # Hallo, ich heiße Anna."
# Der Name kann Leerzeichen enthalten ("Allison (Premium)") — darum wird NICHT
# auf das erste Leerzeichen gesplittet, sondern das Locale-Token (xx_XX) als
# Anker gesucht: alles davor = Name, alles danach (nach dem '#') = Beispiel.
_VOICE_LINE_RE = re.compile(r"^(?P<name>.+?)\s+(?P<locale>[a-z]{2}_[A-Z]{2})\s*(#\s*(?P<sample>.*))?$")


def _parse_voices(raw: str) -> list[dict]:
    voices: list[dict] = []
    for line in raw.splitlines():
        line = line.rstrip()
        if not line.strip():
            continue
        m = _VOICE_LINE_RE.match(line)
        if not m:
            continue  # unerwartetes Format (z.B. Meldezeile) — überspringen statt zu crashen
        voices.append({
            "name": m.group("name").strip(),
            "locale": m.group("locale"),
            "sample": (m.group("sample") or "").strip(),
        })
    # Deutsche Stimmen zuerst (Andis Hauptsprache) — stabiler Sort, sonst
    # Erscheinungsreihenfolge von `say -v '?'` erhalten.
    voices.sort(key=lambda v: 0 if v["locale"].startswith("de_") else 1)
    return voices


def _load_voices() -> list[dict]:
    """Lädt die installierten Stimmen EINMAL (beim Modul-Import = Server-Start).

    Best-effort: schlägt `say -v '?'` fehl (z.B. Nicht-macOS-CI), bleibt die
    Liste leer statt den Start zu killen — /voices antwortet dann ehrlich leer,
    /tts funktioniert trotzdem (DEFAULT_VOICE wird nicht validiert, `say`
    selbst meldet eine unbekannte Stimme).
    """
    try:
        out = subprocess.run(
            [SAY_BIN, "-v", "?"], check=True, capture_output=True, timeout=10, text=True,
        )
        return _parse_voices(out.stdout)
    except Exception as e:  # noqa: BLE001 — bewusst breit: Start darf NIE daran sterben
        log.warning("say -v '?' fehlgeschlagen (%s) — /voices bleibt leer, /tts unbeeinflusst", e)
        return []


VOICES_CACHE: list[dict] = _load_voices()
log.info("say-tts: %d Stimmen gecacht, default_voice=%r", len(VOICES_CACHE), DEFAULT_VOICE)


# ── Synthese: say -> AIFF -> afconvert -> WAV PCM16@22050 mono ──────────────
def synth_wav(text: str, voice: Optional[str], rate: Optional[int]) -> bytes:
    """Wirft subprocess.CalledProcessError/TimeoutExpired ehrlich — der Aufrufer
    (POST /tts) übersetzt das in HTTP 500. tmp-Dateien liegen in einem
    TemporaryDirectory und werden beim Verlassen des `with`-Blocks IMMER
    aufgeräumt (auch bei Exception)."""
    voice_name = voice or DEFAULT_VOICE
    with tempfile.TemporaryDirectory(prefix="hoshi-say-") as d:
        aiff_path = Path(d) / "out.aiff"
        wav_path = Path(d) / "out.wav"
        cmd = [SAY_BIN, "-v", voice_name, "-o", str(aiff_path)]
        if rate:
            cmd += ["-r", str(rate)]
        cmd.append(text)  # argv, kein Shell — Injection-sicher (kein shell=True)
        subprocess.run(cmd, check=True, capture_output=True, timeout=60)
        subprocess.run(
            [AFCONVERT_BIN, "-f", "WAVE", "-d", "LEI16@22050", "-c", "1", str(aiff_path), str(wav_path)],
            check=True, capture_output=True, timeout=30,
        )
        return wav_path.read_bytes()


# ── Request/Response-Schemas ─────────────────────────────────────────────────
class TtsRequest(BaseModel):
    text: str
    voice: Optional[str] = None
    rate: Optional[int] = None


class VoiceEntry(BaseModel):
    name: str
    locale: str
    sample: str


class VoicesResponse(BaseModel):
    voices: list[VoiceEntry]


class HealthResponse(BaseModel):
    status: str
    engine: str
    default_voice: str
    voice_count: int


# ── FastAPI-App ──────────────────────────────────────────────────────────────
app = FastAPI(title="hoshi-tts-say", version="0.1.0")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Billiger Liveness-Check — kein echter Synthese-Lauf (der kostet ~100-500ms)."""
    return HealthResponse(status="ok", engine="say", default_voice=DEFAULT_VOICE, voice_count=len(VOICES_CACHE))


@app.get("/voices", response_model=VoicesResponse)
def voices() -> VoicesResponse:
    """Gecachte Stimmenliste, deutsche zuerst (s. `_parse_voices`)."""
    return VoicesResponse(voices=[VoiceEntry(**v) for v in VOICES_CACHE])


@app.post("/tts")
def tts(req: TtsRequest) -> Response:
    """text (+ optional voice/rate) → WAV-Bytes. 422 leer, 413 zu lang, 500 say/afconvert-Fehler."""
    text = (req.text or "").strip()
    if not text:
        raise HTTPException(status_code=422, detail="text fehlt/leer")
    if len(text) > MAX_TEXT_CHARS:
        raise HTTPException(
            status_code=413,
            detail=f"text zu lang ({len(text)} > {MAX_TEXT_CHARS} Zeichen) — Sidecar-Schutz vor Endlos-Synthese",
        )
    try:
        wav = synth_wav(text, req.voice, req.rate)
    except subprocess.CalledProcessError as e:
        detail = (e.stderr or b"").decode(errors="replace")[:200] if isinstance(e.stderr, (bytes, bytearray)) else str(e.stderr)[:200]
        log.warning("say/afconvert fehlgeschlagen: %s", detail)
        raise HTTPException(status_code=500, detail=f"say/afconvert fehlgeschlagen: {detail}")
    except subprocess.TimeoutExpired:
        log.warning("say/afconvert Timeout (text_len=%d)", len(text))
        raise HTTPException(status_code=500, detail="Synthese-Timeout")
    return Response(content=wav, media_type="audio/wav")


# ── Selbsttest (Beweis statt Mock — s. Auftrag) ──────────────────────────────
def _selftest() -> bool:
    """Synthetisiert 'Hallo' über den ECHTEN say/afconvert-Pfad (kein Netz, kein
    FastAPI-Server nötig) und prüft: RIFF-Header + WAV nicht leer. Gibt True/False."""
    print(f"[selftest] synthetisiere 'Hallo' (voice={DEFAULT_VOICE!r}) …")
    try:
        wav = synth_wav("Hallo", None, None)
    except Exception as e:  # noqa: BLE001 — Selbsttest soll JEDEN Fehler ehrlich melden
        print(f"[selftest] FAIL: Synthese warf {type(e).__name__}: {e}")
        return False
    if len(wav) == 0:
        print("[selftest] FAIL: WAV ist leer (0 bytes)")
        return False
    if wav[:4] != b"RIFF" or wav[8:12] != b"WAVE":
        print(f"[selftest] FAIL: kein RIFF/WAVE-Header (erste 12 bytes: {wav[:12]!r})")
        return False
    print(f"[selftest] PASS: {len(wav)} bytes WAV, RIFF/WAVE-Header vorhanden")
    return True


if __name__ == "__main__":
    if args.selftest:
        ok = _selftest()
        sys.exit(0 if ok else 1)

    import uvicorn
    log.info("hoshi-tts-say startet auf %s:%d (default_voice=%s, %d Stimmen gecacht)",
              args.host, args.port, DEFAULT_VOICE, len(VOICES_CACHE))
    # workers=1: ein Prozess reicht (say/afconvert sind eh serielle Subprozesse
    # je Request) — mehr Worker brächten hier keinen Durchsatzgewinn.
    uvicorn.run(app, host=args.host, port=args.port, log_level="info", workers=1)
