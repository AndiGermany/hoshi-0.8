# SPDX-License-Identifier: Apache-2.0
"""Kleiner Live-Contract-/RTF-Benchmark ohne Audioablage oder Fremdpakete."""
from __future__ import annotations

import argparse
import io
import json
import statistics
import sys
import urllib.error
import urllib.request
import wave

SENTENCES = [
    "Guten Morgen, Andi.",
    "Das Licht im Wohnzimmer ist jetzt aus.",
    "Draußen sind es heute achtzehn Grad.",
    "Soll ich den Wecker für sieben Uhr stellen?",
    "Ich habe deine Anfrage lokal verarbeitet.",
    "Im Arbeitszimmer ist noch ein Fenster geöffnet.",
    "Die nächste Erinnerung ist für morgen geplant.",
    "Einen Moment, ich schaue in deinem Zuhause nach.",
    "Die Musik ist pausiert und die Lautstärke bleibt unverändert.",
    "Gute Nacht. Ich bin auch ohne Internet für dich da.",
]


def _json_get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as response:  # noqa: S310 — expliziter lokaler Benchmark-Endpunkt
        return json.loads(response.read())


def _synthesize(base_url: str, text: str) -> tuple[int, int]:
    request = urllib.request.Request(
        f"{base_url}/tts",
        data=json.dumps({"text": text}, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:  # noqa: S310 — expliziter lokaler Benchmark-Endpunkt
        wav_bytes = response.read()
        synth_ms = int(response.headers["X-Hoshi-TTS-Ms"])
        audio_ms = int(response.headers["X-Hoshi-Audio-Ms"])
    with wave.open(io.BytesIO(wav_bytes), "rb") as wav_file:
        assert wav_file.getnchannels() == 1
        assert wav_file.getsampwidth() == 2
        assert wav_file.getnframes() > 0
    return synth_ms, audio_ms


def _percentile(values: list[float], fraction: float) -> float:
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round((len(ordered) - 1) * fraction)))
    return ordered[index]


def main() -> int:
    parser = argparse.ArgumentParser(description="Hoshi Piper Live-Benchmark")
    parser.add_argument("--base-url", default="http://127.0.0.1:8045")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    try:
        before = _json_get(f"{base_url}/health")
        if before.get("status") != "ok":
            raise RuntimeError(f"Sidecar nicht bereit: {before}")
        rows = []
        for index, sentence in enumerate(SENTENCES, start=1):
            synth_ms, audio_ms = _synthesize(base_url, sentence)
            rtf = synth_ms / audio_ms
            rows.append((synth_ms, audio_ms, rtf))
            print(f"{index:02d} synth_ms={synth_ms:4d} audio_ms={audio_ms:4d} rtf={rtf:.3f}")
        after = _json_get(f"{base_url}/health")
    except (OSError, urllib.error.URLError, ValueError, AssertionError, RuntimeError) as exc:
        print(f"FAIL: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1

    synth_values = [row[0] for row in rows]
    rtf_values = [row[2] for row in rows]
    print(
        "SUMMARY "
        f"n={len(rows)} synth_median_ms={statistics.median(synth_values):.0f} "
        f"synth_p95_ms={_percentile([float(v) for v in synth_values], 0.95):.0f} "
        f"rtf_median={statistics.median(rtf_values):.3f} "
        f"rtf_p95={_percentile(rtf_values, 0.95):.3f} "
        f"rss_mb={after.get('rss_mb')} peak_rss_mb={after.get('peak_rss_mb')} "
        f"threads={after.get('threads')} voice={after.get('default_voice')}"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
