"""
Unit-Tests für silence_gate — KEIN mlx_whisper-Import (rein numpy/synthetisch).

Deckt ab:
  - compute_rms: Null-Array, Sinuston, leeres Array
  - is_silent: Null-Array=silent, Sinuston=not-silent, kurzer Peak in Stille
    (unter Dauer/RMS)=silent, 100ms-Null=silent, ENV-Override
"""

import numpy as np
import pytest

import silence_gate
from silence_gate import compute_rms, is_silent

SAMPLE_RATE = 16000


def _sine(freq_hz: float, duration_s: float, amplitude: int, sr: int = SAMPLE_RATE):
    """Erzeugt einen int16-Sinuston (laute Sprache-Surrogat)."""
    t = np.arange(int(duration_s * sr)) / sr
    wave = amplitude * np.sin(2.0 * np.pi * freq_hz * t)
    return wave.astype(np.int16)


# ── compute_rms ──────────────────────────────────────────────────────────────

def test_compute_rms_null_array_is_zero():
    pcm = np.zeros(SAMPLE_RATE, dtype=np.int16)
    assert compute_rms(pcm) == 0.0


def test_compute_rms_empty_array_is_zero():
    pcm = np.array([], dtype=np.int16)
    assert compute_rms(pcm) == 0.0


def test_compute_rms_sine_is_positive_and_no_overflow():
    # Vollausschlag-Sinus: RMS ~ amplitude / sqrt(2) ≈ 23170.
    pcm = _sine(440.0, 1.0, amplitude=32000)
    rms = compute_rms(pcm)
    assert rms > 20000.0
    assert rms < 32767.0  # kein int16-Overflow


# ── is_silent ────────────────────────────────────────────────────────────────

def test_null_array_is_silent():
    # 2s reine Stille (über Mindestdauer) → silent.
    pcm = np.zeros(2 * SAMPLE_RATE, dtype=np.int16)
    assert is_silent(pcm, SAMPLE_RATE) is True


def test_empty_array_is_silent():
    pcm = np.array([], dtype=np.int16)
    assert is_silent(pcm, SAMPLE_RATE) is True


def test_loud_sine_is_not_silent():
    # 2s lauter Sinus → not silent (echte Energie).
    pcm = _sine(440.0, 2.0, amplitude=10000)
    assert is_silent(pcm, SAMPLE_RATE) is False


def test_short_peak_in_silence_is_silent_under_duration():
    # 2s Stille mit winzigem, leisem 5ms-Knack: Gesamt-RMS bleibt unter der
    # Schwelle → silent. (Ein einzelner kurzer Low-Level-Peak mittelt sich
    # über die lange Stille weg und soll NICHT als Sprache durchrutschen.)
    pcm = np.zeros(2 * SAMPLE_RATE, dtype=np.int16)
    peak_len = int(0.005 * SAMPLE_RATE)
    pcm[:peak_len] = 200
    assert is_silent(pcm, SAMPLE_RATE) is True


def test_loud_short_peak_in_silence_is_not_silent():
    # Lauter Knack in 2s-Stille: Gesamt-RMS überschreitet die Schwelle →
    # nicht silent (konservativ: lieber Whisper laufen lassen als schlucken).
    pcm = np.zeros(2 * SAMPLE_RATE, dtype=np.int16)
    peak_len = int(0.02 * SAMPLE_RATE)
    pcm[:peak_len] = 30000
    assert is_silent(pcm, SAMPLE_RATE) is False


def test_100ms_null_is_silent():
    # 100ms reine Null-PCM: Null-Energie ist risikolos → silent (auch unter
    # Mindestdauer, da exakt 0 RMS kein Sprache-Schlucken riskiert).
    pcm = np.zeros(int(0.1 * SAMPLE_RATE), dtype=np.int16)
    assert is_silent(pcm, SAMPLE_RATE) is True


def test_short_low_level_chunk_not_gated():
    # 100ms leiser (aber nicht-null) Chunk: UNTER Mindestdauer (1.5s) UND
    # Rest-Energie → NICHT gaten (durchlassen, kein Schlucken kurzer Äußerung).
    pcm = np.full(int(0.1 * SAMPLE_RATE), 5, dtype=np.int16)
    assert is_silent(pcm, SAMPLE_RATE) is False


def test_explicit_params_override():
    # Sehr leiser Sinus unter custom-RMS-Schwelle, lang genug → silent.
    pcm = _sine(440.0, 2.0, amplitude=30)
    assert is_silent(pcm, SAMPLE_RATE, min_duration_s=1.0, rms_threshold=60.0) is True
    # Gleiches Signal, Schwelle unter dem RMS → not silent.
    assert is_silent(pcm, SAMPLE_RATE, min_duration_s=1.0, rms_threshold=5.0) is False


def test_env_override(monkeypatch):
    # ENV setzt aggressivere Schwelle: leiser Sinus wird damit als silent gegated.
    monkeypatch.setenv("HOSHI_STT_SILENCE_RMS", "1000")
    monkeypatch.setenv("HOSHI_STT_SILENCE_MIN_MS", "1000")
    pcm = _sine(440.0, 1.5, amplitude=400)  # RMS ~283 < 1000
    assert is_silent(pcm, SAMPLE_RATE) is True


def test_env_default_falls_back_on_garbage(monkeypatch):
    monkeypatch.setenv("HOSHI_STT_SILENCE_RMS", "not-a-number")
    assert silence_gate._env_float("HOSHI_STT_SILENCE_RMS", 60.0) == 60.0


def test_zero_sample_rate_not_silent():
    # Defensive: sample_rate<=0 bei nicht-null PCM → keine Dauerberechnung
    # möglich → nicht gaten (Whisper laufen lassen). Reine Null bleibt silent.
    pcm = np.full(SAMPLE_RATE, 5, dtype=np.int16)
    assert is_silent(pcm, 0) is False


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
