"""
silence_gate — RMS+Dauer-Silence-Gate VOR Whisper

Codex-Fund: Die 3244-Byte-Warmup-Stille (reiner WebM-Container ohne echte
Sprache) wird von mlx_whisper als Halluzination ("Untertitel von ZDF …")
transkribiert. Ein billiges RMS+Dauer-Gate VOR dem teuren Whisper-Aufruf
fängt solche Stille-Chunks ab → {"text": ""}.

WICHTIG: Dieses Modul importiert KEIN mlx_whisper (top-level-Import in
server.py macht pytest sonst unladbar). Es braucht nur numpy auf int16-PCM.

ENV-konfigurierbar mit KONSERVATIVEN Defaults — lieber leise Sprache
durchlassen (und Whisper entscheiden lassen) als echte Sprache schlucken:
  HOSHI_STT_SILENCE_RMS    RMS-Schwelle (int16-Skala 0..32767), default 60
  HOSHI_STT_SILENCE_MIN_MS Mindestdauer in ms, default 1500

Ravi-Kopplung: Die RMS-Schwelle darf das 1,5s-Mindestchunk NICHT unter-
laufen — wir gaten nur, wenn der Chunk MINDESTENS min_duration_s lang ist
UND seine RMS-Energie unter der Schwelle liegt. Kürzere Chunks lassen wir
unangetastet durch (kein Schlucken von legitimen Kurz-Äußerungen).
"""

import os

import numpy as np

# ── Defaults (konservativ) ──────────────────────────────────────────────────
# RMS auf int16-Skala (0..32767). 60 ist sehr leise — Raumton/Rauschen liegt
# typ. darunter, gesprochene Sprache deutlich darüber. Lieber zu niedrig
# (durchlassen) als zu hoch (schlucken).
DEFAULT_RMS_THRESHOLD = 60.0
# Mindestdauer in ms, ab der wir überhaupt gaten. 1500ms = Ravi-Mindestchunk.
DEFAULT_MIN_MS = 1500


def _env_float(name: str, default: float) -> float:
    """Liest einen Float aus der Umgebung, fällt bei Fehler auf default zurück."""
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    try:
        return float(raw)
    except (TypeError, ValueError):
        return default


def compute_rms(pcm_int16) -> float:
    """
    Root-Mean-Square der PCM-Samples (int16) als float auf int16-Skala.

    Leeres Array → 0.0 (gilt als Stille). Berechnung in float64, um
    int16-Overflow beim Quadrieren zu vermeiden.
    """
    pcm = np.asarray(pcm_int16)
    if pcm.size == 0:
        return 0.0
    samples = pcm.astype(np.float64)
    return float(np.sqrt(np.mean(np.square(samples))))


def is_silent(
    pcm_int16,
    sample_rate: int,
    min_duration_s: float | None = None,
    rms_threshold: float | None = None,
) -> bool:
    """
    True, wenn der Chunk als Stille gilt und VOR Whisper geblockt werden soll.

    Reihenfolge der Prüfungen:
      0. Leeres ODER exakt-stilles Array (RMS == 0) → IMMER silent. Reine Null-
         PCM (z.B. die 3244B-Warmup-Stille nach ffmpeg→PCM) hat keinerlei
         Energie → risikolos zu blocken, unabhängig von der Dauer.
      1. Sonst Dauer-Gate (Ravi): nur Chunks >= min_duration_s werden auf
         leise-Energie geprüft. Kürzere Chunks laufen durch (kein Schlucken
         legitimer Kurz-Äußerungen).
      2. RMS-Energie < rms_threshold → silent.

    Args:
        pcm_int16:     int16-PCM-Samples (np.ndarray oder array-like).
        sample_rate:   Abtastrate in Hz (z.B. 16000).
        min_duration_s: Mindestdauer, ab der leise gegated wird. None → ENV/Default.
        rms_threshold:  RMS-Schwelle. None → ENV/Default.
    """
    if min_duration_s is None:
        min_duration_s = _env_float("HOSHI_STT_SILENCE_MIN_MS", DEFAULT_MIN_MS) / 1000.0
    if rms_threshold is None:
        rms_threshold = _env_float("HOSHI_STT_SILENCE_RMS", DEFAULT_RMS_THRESHOLD)

    pcm = np.asarray(pcm_int16)
    if pcm.size == 0:
        return True

    rms = compute_rms(pcm)
    # Exakte Null-Energie ist risikolos → immer blocken (auch unter Mindestdauer).
    if rms == 0.0:
        return True

    if sample_rate <= 0:
        return False

    duration_s = pcm.size / float(sample_rate)
    # Kurze Chunks (mit Rest-Energie) unangetastet durchlassen — Ravi-Mindestchunk.
    if duration_s < min_duration_s:
        return False

    return rms < rms_threshold
