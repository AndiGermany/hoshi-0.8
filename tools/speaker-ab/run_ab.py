#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""run_ab.py — Offline-A/B-Runner für die Speaker-Score-Aggregation (BEST_SAMPLE vs.
CENTROID) gegen echte (oder synthetische) Voice-Proben.

WARUM dieses Tool: der Test-Gate für `SpeakerProfileAggregation` (siehe
web-inbound/src/main/kotlin/de/hoshi/web/SpeakerIdentifyService.kt) verlangt einen
FAR/FRR-Proxy + eine kanalgetrennte Confusion-Matrix statt Anekdoten ("meine Stimme
hat bei mir geklappt"). Dieses Skript spielt eine Menge Proben (echte WAVs ODER ein
synthetischer Smoke-Fixture) offline gegen einen geladenen Speaker-Profile-Store und
wertet BEIDE Aggregations-Modi über eine Schwellen-Matrix aus — ohne den Kotlin-Service
selbst anzufassen.

**Null Mathe-Divergenz zum Kotlin-Pfad (bewusstes Design):**
  - BEST_SAMPLE = max(cosine(probe, sample_i)) über `profile.samples` — exakt
    `CosineSpeakerIdentifyService.scoreProfile`.
  - CENTROID = cosine(probe, profile.embedding) — das im Store BEREITS
    L2-renormalisiert gemittelte Embedding wird NUR GELESEN, nie neu berechnet
    (`SpeakerProfileStore.renormalizedMean` läuft ausschließlich beim Enroll/Append,
    nie hier).
  - Bindungsregel (Top-1 vs. Top-2, Vera-Abstandsregel) 1:1 aus `identify()`:
    Top-1 >= Schwelle UND (bei >=2 Profilen) Top-1 schlägt Top-2 um >= margin, sonst
    ehrlich Gast. Siehe `decide()` unten — Zeile für Zeile dieselbe Fallunterscheidung.

**Sidecar-Vertrag (verifiziert aus sidecars/speaker/server.py + CamppSpeakerAdapter.kt,
NICHT multipart):** `POST /embed` erwartet JSON `{"audio": <base64>, "sampleRate": int}`
— `audio` ist entweder ein WAV-Container (RIFF-Magic, Samplerate selbstbeschreibend)
oder rohes PCM16-LE. Dieses Skript schickt die WAV-Datei 1:1 als base64 (Form 1,
RIFF-Pfad) — bit-identisch zum echten `CamppSpeakerAdapter.embed()`.

Modi:
  --manifest manifest.tsv   Spalten (Header, Tab-getrennt): wav_path, truth_speaker,
                            channel, quality.
  --wav-dir DIR             Layout <truth_speaker>/<channel>/*.wav; quality unbekannt.
  --smoke                   Kein Sidecar, keine echten Stimmen nötig: synthetische
                            WAVs (stdlib `wave`) + ein Fake-Store mit deterministisch
                            geseedeten Zufalls-Embeddings, direkt als Proben-Embeddings
                            verwendet (siehe `build_smoke_fixture`). Beweist: Report
                            entsteht, best-sample==centroid bei 1-Sample-Profil,
                            Margin-Regel greift.

Report (NIE ins Repo — Datenschutz hart, siehe README): `~/.hoshi/speaker-ab/<ts>/`
  - probes.tsv  je Probe: truth/channel/dauer/quality, ALLE Profil-Scores beider
    Modi, Top-1/Runner-up/Margin je Modus, Entscheidung je Schwelle × Modus.
  - report.md   FAR-Proxy/FRR-Proxy je Modus×Schwelle + Confusion-Matrix je Kanal,
    mit explizitem Hinweis auf die kleine Proben-Zahl (kein ROC/EER-Ersatz).

Nur stdlib (kein numpy/Framework-Dep) — 512-d Cosine in reinem Python ist für die
hier realistischen Proben-Zahlen (zig bis wenige tausend) schnell genug.
"""
from __future__ import annotations

import argparse
import base64
import csv
import io
import json
import math
import os
import random
import sys
import urllib.error
import urllib.request
import wave
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Tuple

DEFAULT_SIDECAR_URL = "http://127.0.0.1:9002"
DEFAULT_MARGIN = 0.10          # exakt der Kotlin-Default (hoshi.speaker.recognition.margin)
THRESHOLD_MIN = 0.35
THRESHOLD_MAX = 0.70
THRESHOLD_STEP = 0.05
GUEST_LABEL = "GAST"           # Report-Label für "kein sicherer Treffer" (== Recognition.GUEST)
MODES = ("best-sample", "centroid")
REPORT_ROOT = Path.home() / ".hoshi" / "speaker-ab"   # NIE ins Repo, s. Modul-Docstring
REPO_ROOT = Path(__file__).resolve().parents[2]        # tools/speaker-ab/ → Repo-Wurzel


# ── Domain-Objekte ────────────────────────────────────────────────────────────

@dataclass
class Profile:
    """Ein Store-Eintrag, exakt wie `SpeakerProfileStore.SpeakerProfile` — nur mit den
    für Scoring relevanten Feldern (Name + Embedding + Roh-Samples)."""
    name: str
    embedding: List[float]
    samples: List[List[float]]


@dataclass
class Probe:
    """Eine Test-Probe: WAHRHEIT (truth_speaker) + Kanal/Qualität-Metadaten + entweder
    ein `wav_path` (Embed läuft über den Sidecar) oder ein direkt mitgegebenes
    `embedding` (Smoke-Modus, Sidecar-frei)."""
    truth_speaker: str
    channel: str
    quality: str
    wav_path: Optional[str] = None
    embedding: Optional[List[float]] = None
    duration_s: Optional[float] = None
    error: Optional[str] = None


# ── Cosine-Mathematik (reine Portierung von SpeakerEmbedPort.similarity) ──────

def cosine(a: List[float], b: List[float]) -> float:
    """Cosine-Similarity — Zeile für Zeile `SpeakerEmbedPort.similarity`: ungleiche/leere
    Größen ⇒ 0.0, Null-Norm ⇒ 0.0 (kein Div/0, kein Treffer statt Wurf)."""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = na = nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na == 0.0 or nb == 0.0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


def l2_normalize(vec: List[float]) -> List[float]:
    norm = math.sqrt(sum(x * x for x in vec))
    if norm <= 0.0:
        return list(vec)  # degeneriert ⇒ unnormiert zurück (Kotlin-Fallback, s. renormalizedMean)
    return [x / norm for x in vec]


def renormalized_mean(samples: List[List[float]]) -> List[float]:
    """1:1-Portierung von `SpeakerProfileStore.renormalizedMean` — NUR für den
    Smoke-Fixture-Bau gebraucht (dort simulieren wir, was der echte Store beim
    Enroll geschrieben hätte). Der Scoring-Pfad selbst ruft das NIE auf."""
    dim = len(samples[0])
    mean = [0.0] * dim
    for s in samples:
        for i in range(dim):
            mean[i] += s[i]
    for i in range(dim):
        mean[i] /= len(samples)
    return l2_normalize(mean)


def score_profile(probe_emb: List[float], profile: Profile, mode: str) -> float:
    """Profil-Score exakt wie `CosineSpeakerIdentifyService.scoreProfile`."""
    if mode == "best-sample":
        samples = profile.samples or [profile.embedding]
        return max(cosine(probe_emb, s) for s in samples)
    if mode == "centroid":
        return cosine(probe_emb, profile.embedding)
    raise ValueError(f"unbekannter Modus {mode!r}")


def best_and_second(probe_emb: List[float], profiles: List[Profile], mode: str) -> Tuple[Optional[str], float, Optional[float]]:
    """Top-1/Top-2 über ALLE Profile — exakt derselbe Inkrement-Algorithmus wie die
    `for (p in profiles)`-Schleife in `CosineSpeakerIdentifyService.identify()`."""
    best_name: Optional[str] = None
    best_score = float("-inf")
    second_score = float("-inf")
    for p in profiles:
        s = score_profile(probe_emb, p, mode)
        if s > best_score:
            second_score = best_score
            best_score = s
            best_name = p.name
        elif s > second_score:
            second_score = s
    score = best_score if math.isfinite(best_score) else 0.0
    runner_up = second_score if math.isfinite(second_score) else None
    return best_name, score, runner_up


def decide(best_name: Optional[str], score: float, runner_up: Optional[float], threshold: float, margin: float) -> Optional[str]:
    """Bindungsregel 1:1 aus `CosineSpeakerIdentifyService.identify()`:
    1. >=2 Profile UND Top-1 >= Schwelle UND Abstand zu Top-2 < margin ⇒ MEHRDEUTIG ⇒ Gast.
    2. sonst: Top-1 >= Schwelle ⇒ gebunden.
    3. sonst: Gast (Near-Miss, keine Bindung).
    Rückgabe: der gebundene Name ODER None (== Recognition.GUEST)."""
    if runner_up is not None and score >= threshold and (score - runner_up) < margin:
        return None
    if best_name is not None and score >= threshold:
        return best_name
    return None


def threshold_list(tmin: float = THRESHOLD_MIN, tmax: float = THRESHOLD_MAX, tstep: float = THRESHOLD_STEP) -> List[float]:
    n = round((tmax - tmin) / tstep)
    return [round(tmin + i * tstep, 2) for i in range(n + 1)]


# ── Store laden (tolerant wie SpeakerProfileStore.loadInitial/loadEntry) ──────

def resolve_default_store_path() -> Path:
    """Spiegelt `PipelineConfig.resolveSpeakerStorePath`: explizit (Env) ▷ Prod-Pfad
    (falls beschreibbar) ▷ Dev `~/.hoshi/speaker-profiles.json`."""
    env = os.environ.get("HOSHI_SPEAKER_STORE_PATH", "").strip()
    if env:
        return Path(env)
    prod = Path("/var/lib/hoshi-0.8/speaker-profiles.json")
    if prod.parent.exists() and os.access(prod.parent, os.W_OK):
        return prod
    return Path.home() / ".hoshi" / "speaker-profiles.json"


def load_store(path: Path) -> List[Profile]:
    """Lädt `{"profiles":[…]}` ODER ein nacktes Array (Legacy-tolerant, wie der
    Kotlin-Store). Kaputte Einzel-Einträge werden mit WARN übersprungen — ein
    kaputter Store bricht NICHT den ganzen Lauf ab, aber ein fehlender/leerer Store
    ist hier (anders als im Kotlin-Service) ein harter Nutzer-Fehler: ein Report
    gegen 0 Profile wäre nutzlos."""
    if not path.exists():
        raise SystemExit(
            f"FEHLER: Speaker-Profil-Store nicht gefunden: {path}\n"
            f"  (Default-Pfad = echter Laufzeit-Pfad, s. PipelineConfig.resolveSpeakerStorePath;"
            f" --store explizit setzen, falls der Store woanders liegt.)"
        )
    try:
        root = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:  # noqa: BLE001
        raise SystemExit(f"FEHLER: Speaker-Profil-Store {path} unlesbar: {e}")

    if isinstance(root, list):
        arr = root
    elif isinstance(root, dict):
        arr = root.get("profiles")
    else:
        arr = None
    if not isinstance(arr, list):
        raise SystemExit(f"FEHLER: {path} hat unbekannte JSON-Form (weder Array noch {{'profiles':[...]}})")

    profiles: List[Profile] = []
    for i, node in enumerate(arr):
        if not isinstance(node, dict):
            print(f"[store] WARN: Eintrag #{i} ist kein Objekt — übersprungen", file=sys.stderr)
            continue
        name = node.get("name")
        emb = node.get("embedding")
        if not isinstance(name, str) or not name.strip() or not isinstance(emb, list) or not emb:
            print(f"[store] WARN: Eintrag #{i} ohne brauchbaren name/embedding — übersprungen", file=sys.stderr)
            continue
        embedding = [float(x) for x in emb]
        raw_samples = node.get("samples")
        samples: List[List[float]] = []
        if isinstance(raw_samples, list):
            for s in raw_samples:
                if isinstance(s, list) and len(s) == len(embedding):
                    samples.append([float(x) for x in s])
        if not samples:
            samples = [embedding]  # Alt-Profil / kaputtes samples-Feld ⇒ 1-Sample-Fallback (Kotlin-Parität)
        profiles.append(Profile(name=name, embedding=embedding, samples=samples))
    if not profiles:
        raise SystemExit(f"FEHLER: {path} enthält KEIN brauchbares Profil — Report wäre gegen 0 Profile sinnlos.")
    return profiles


# ── Proben einlesen (Manifest / wav-dir) ───────────────────────────────────────

def read_manifest(path: Path) -> List[Probe]:
    with path.open(newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh, delimiter="\t")
        required = {"wav_path", "truth_speaker", "channel", "quality"}
        missing = required - set(reader.fieldnames or [])
        if missing:
            raise SystemExit(f"FEHLER: Manifest {path} fehlen Spalten: {sorted(missing)} (Header: {reader.fieldnames})")
        probes = []
        for row in reader:
            probes.append(Probe(
                wav_path=row["wav_path"].strip(),
                truth_speaker=row["truth_speaker"].strip(),
                channel=row["channel"].strip(),
                quality=row["quality"].strip(),
            ))
    if not probes:
        raise SystemExit(f"FEHLER: Manifest {path} hat 0 Daten-Zeilen")
    return probes


def scan_wav_dir(base: Path) -> List[Probe]:
    """Layout `<truth_speaker>/<channel>/*.wav` — quality ist in diesem Layout nicht
    kodiert (ehrlich 'unbekannt' statt geraten)."""
    probes: List[Probe] = []
    for speaker_dir in sorted(p for p in base.iterdir() if p.is_dir()):
        for channel_dir in sorted(p for p in speaker_dir.iterdir() if p.is_dir()):
            for wav in sorted(channel_dir.glob("*.wav")):
                probes.append(Probe(
                    wav_path=str(wav),
                    truth_speaker=speaker_dir.name,
                    channel=channel_dir.name,
                    quality="unbekannt",
                ))
    if not probes:
        raise SystemExit(f"FEHLER: {base} enthält keine *.wav unter <truth_speaker>/<channel>/ — Layout geprüft?")
    return probes


def wav_duration_seconds(path: str) -> Optional[float]:
    try:
        with wave.open(path, "rb") as w:
            rate = w.getframerate()
            return round(w.getnframes() / float(rate), 3) if rate else None
    except Exception:  # noqa: BLE001 — Dauer ist Diagnose, kein Blocker
        return None


# ── Sidecar-Aufruf (Vertrag == CamppSpeakerAdapter.embed, s. Modul-Docstring) ──

def embed_via_sidecar(sidecar_url: str, wav_bytes: bytes, timeout: float = 10.0) -> List[float]:
    body = json.dumps({
        "audio": base64.b64encode(wav_bytes).decode("ascii"),
        "sampleRate": 16000,
    }).encode("utf-8")
    req = urllib.request.Request(
        sidecar_url.rstrip("/") + "/embed",
        data=body,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        obj = json.loads(resp.read().decode("utf-8"))
    emb = obj.get("embedding")
    if not isinstance(emb, list) or not emb:
        raise ValueError("Sidecar-Antwort ohne brauchbares 'embedding'")
    return [float(x) for x in emb]


def fetch_embeddings(probes: List[Probe], sidecar_url: str) -> None:
    """Füllt `probe.embedding` + `probe.duration_s` in-place. Best-effort mit EINEM
    Retry pro Probe (Muster: measure-brain-ab.py `run_mode`) — ein einzelner
    Sidecar-Hänger soll nicht den ganzen Lauf töten, aber JEDER Fehler bleibt
    sichtbar (`probe.error`) statt still als Nullen in die Matrix zu rutschen."""
    total = len(probes)
    for i, p in enumerate(probes, 1):
        p.duration_s = wav_duration_seconds(p.wav_path)
        try:
            wav_bytes = Path(p.wav_path).read_bytes()
        except OSError as e:
            p.error = f"wav unlesbar: {e}"
            print(f"[embed] {i}/{total} {p.wav_path}: FEHLER {p.error}", file=sys.stderr)
            continue
        last_err = None
        for attempt in (1, 2):
            try:
                p.embedding = embed_via_sidecar(sidecar_url, wav_bytes)
                last_err = None
                break
            except (urllib.error.URLError, ValueError, TimeoutError, OSError) as e:
                last_err = f"{type(e).__name__}: {e}"
                if attempt == 1:
                    print(f"[embed] {i}/{total} {p.wav_path}: Versuch 1 FEHLER {last_err} — Retry", file=sys.stderr)
        if last_err:
            p.error = last_err
            print(f"[embed] {i}/{total} {p.wav_path}: FEHLER {last_err}", file=sys.stderr)
        else:
            print(f"[embed] {i}/{total} {p.wav_path}: ok (dim={len(p.embedding)})", file=sys.stderr)


# ── Smoke-Fixture (Sidecar-frei, Stimmen-frei) ─────────────────────────────────

def _seeded_unit_vector(seed: int, dim: int = 512) -> List[float]:
    rng = random.Random(seed)
    vec = [rng.gauss(0.0, 1.0) for _ in range(dim)]
    return l2_normalize(vec)


def _write_sine_wav(path: Path, freq_hz: float, seconds: float = 1.0, sr: int = 16000) -> None:
    n = int(seconds * sr)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        frames = bytearray()
        for i in range(n):
            sample = int(3000 * math.sin(2 * math.pi * freq_hz * i / sr))
            frames += sample.to_bytes(2, "little", signed=True)
        w.writeframes(bytes(frames))


def _write_noise_wav(path: Path, seed: int, seconds: float = 1.0, sr: int = 16000) -> None:
    rng = random.Random(seed)
    n = int(seconds * sr)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        frames = bytearray()
        for _ in range(n):
            sample = rng.randint(-2000, 2000)
            frames += sample.to_bytes(2, "little", signed=True)
        w.writeframes(bytes(frames))


def build_smoke_fixture(tmp_dir: Path) -> Tuple[Path, List[Probe]]:
    """Baut OHNE Sidecar/echte Stimmen:
      - einen Fake-Store mit 3 Profilen: 'alice' + 'bob' (je 3 Samples, wie vom Auftrag
        verlangt) UND zusätzlich 'solo' (genau 1 Sample) — 'solo' ist nötig, um die
        Assertion "best-sample==centroid bei 1-Sample-Profil" beweisen zu können (bei
        3-Sample-Profilen sind die beiden Modi per Konstruktion NICHT gleich).
      - 4 synthetische WAVs (Sinus/Rauschen) auf Platte, ECHT per `wave` gelesen für
        `duration_s` — beweist den Datei-I/O-Pfad, auch wenn das Embedding selbst
        NICHT über den Sidecar läuft (die Proben-Embeddings werden direkt gesetzt).
      - 4 Proben, die gezielt die drei Assertions triggern (siehe run() smoke-Zweig).
    """
    wav_dir = tmp_dir / "wavs"
    wav_dir.mkdir(parents=True, exist_ok=True)

    alice_samples = [_seeded_unit_vector(1001), _seeded_unit_vector(1002), _seeded_unit_vector(1003)]
    bob_samples = [_seeded_unit_vector(2001), _seeded_unit_vector(2002), _seeded_unit_vector(2003)]
    solo_sample = _seeded_unit_vector(3001)

    store = {
        "profiles": [
            {"name": "alice", "enrolledAtEpochMs": 0, "embedding": renormalized_mean(alice_samples),
             "samples": alice_samples},
            {"name": "bob", "enrolledAtEpochMs": 0, "embedding": renormalized_mean(bob_samples),
             "samples": bob_samples},
            {"name": "solo", "enrolledAtEpochMs": 0, "embedding": solo_sample, "samples": [solo_sample]},
        ]
    }
    store_path = tmp_dir / "speaker-profiles.json"
    store_path.write_text(json.dumps(store), encoding="utf-8")

    genuine_alice_wav = wav_dir / "genuine_alice.wav"
    genuine_solo_wav = wav_dir / "genuine_solo.wav"
    ambiguous_wav = wav_dir / "ambiguous.wav"
    impostor_wav = wav_dir / "impostor.wav"
    _write_sine_wav(genuine_alice_wav, freq_hz=440.0)
    _write_sine_wav(genuine_solo_wav, freq_hz=660.0)
    _write_noise_wav(ambiguous_wav, seed=42)
    _write_noise_wav(impostor_wav, seed=99)

    # Ambiguitäts-Probe: normalize(alice_s0 + bob_s0). Algebraisch EXAKT gleich weit
    # von beiden (dot(u+v,u) == dot(u+v,v) == 1+dot(u,v)) ⇒ Top-1/Top-2 liegen für
    # best-sample praktisch auf demselben Score (die jeweils anderen 2 Samples pro
    # Profil sind unabhängige Zufallsvektoren — in 512 Dimensionen verschwindend
    # unwahrscheinlich, dass einer davon diesen Wert übertrifft) ⇒ beweist die
    # Margin-Regel (Abstand < 0.10 ⇒ Gast trotz Score >= Schwelle).
    ambiguous_emb = l2_normalize([a + b for a, b in zip(alice_samples[0], bob_samples[0])])
    # Impostor: unabhängiger Zufallsvektor, dessen Wahrheit ("fremd") in KEINEM
    # Profilnamen des Stores vorkommt ⇒ FAR-Proxy-Nenner.
    impostor_emb = _seeded_unit_vector(7777)

    probes = [
        Probe(truth_speaker="alice", channel="phone", quality="clean",
              wav_path=str(genuine_alice_wav), embedding=list(alice_samples[0])),
        Probe(truth_speaker="solo", channel="phone", quality="clean",
              wav_path=str(genuine_solo_wav), embedding=list(solo_sample)),
        Probe(truth_speaker="bob", channel="funk", quality="rauschig",
              wav_path=str(ambiguous_wav), embedding=ambiguous_emb),
        Probe(truth_speaker="fremd", channel="funk", quality="rauschig",
              wav_path=str(impostor_wav), embedding=impostor_emb),
    ]
    for p in probes:
        p.duration_s = wav_duration_seconds(p.wav_path)
    return store_path, probes


# ── Auswertung + Report ────────────────────────────────────────────────────────

@dataclass
class ProbeResult:
    probe: Probe
    scores: Dict[str, Dict[str, float]] = field(default_factory=dict)     # mode -> {profile_name: score}
    top1: Dict[str, Optional[str]] = field(default_factory=dict)           # mode -> name
    top1_score: Dict[str, float] = field(default_factory=dict)
    runner_up: Dict[str, Optional[float]] = field(default_factory=dict)
    decisions: Dict[Tuple[str, float], Optional[str]] = field(default_factory=dict)  # (mode, threshold) -> name|None


def evaluate(probes: List[Probe], profiles: List[Profile], thresholds: List[float], margin: float) -> List[ProbeResult]:
    results = []
    for p in probes:
        r = ProbeResult(probe=p)
        if p.error or not p.embedding:
            results.append(r)
            continue
        for mode in MODES:
            r.scores[mode] = {prof.name: score_profile(p.embedding, prof, mode) for prof in profiles}
            best_name, score, runner_up = best_and_second(p.embedding, profiles, mode)
            r.top1[mode] = best_name
            r.top1_score[mode] = score
            r.runner_up[mode] = runner_up
            for t in thresholds:
                r.decisions[(mode, t)] = decide(best_name, score, runner_up, t, margin)
        results.append(r)
    return results


def write_probes_tsv(path: Path, results: List[ProbeResult], profiles: List[Profile], thresholds: List[float]) -> None:
    names = sorted(p.name for p in profiles)
    header = ["wav_path", "truth_speaker", "channel", "quality", "duration_s", "error"]
    for n in names:
        header += [f"score_best_{n}", f"score_centroid_{n}"]
    header += ["top1_best", "top1_best_score", "runnerup_best_score", "margin_best",
               "top1_centroid", "top1_centroid_score", "runnerup_centroid_score", "margin_centroid"]
    for t in thresholds:
        header += [f"decision_best_{t:.2f}", f"decision_centroid_{t:.2f}"]

    with path.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh, delimiter="\t")
        w.writerow(header)
        for r in results:
            p = r.probe
            row = [p.wav_path or "", p.truth_speaker, p.channel, p.quality,
                   "" if p.duration_s is None else f"{p.duration_s:.3f}", p.error or ""]
            if p.error or not p.embedding:
                row += [""] * (len(header) - len(row))
                w.writerow(row)
                continue
            for n in names:
                row.append(f"{r.scores['best-sample'][n]:.6f}")
                row.append(f"{r.scores['centroid'][n]:.6f}")
            for mode in ("best-sample", "centroid"):
                ru = r.runner_up[mode]
                margin_val = "" if ru is None else f"{(r.top1_score[mode] - ru):.6f}"
                row += [r.top1[mode] or "", f"{r.top1_score[mode]:.6f}",
                        "" if ru is None else f"{ru:.6f}", margin_val]
            for t in thresholds:
                for mode in ("best-sample", "centroid"):
                    d = r.decisions[(mode, t)]
                    row.append(d if d is not None else GUEST_LABEL)
            w.writerow(row)


def _far_frr(results: List[ProbeResult], profile_names: set, mode: str, threshold: float) -> Tuple[Tuple[int, int], Tuple[int, int]]:
    """(FAR (k,n) über Impostor-Proben, FRR (k,n) über Genuine-Proben) für (mode, threshold).
    FAR = fremd/nicht-enrollte Wahrheit als bekannt gebunden. FRR = enrollte Wahrheit NICHT
    korrekt gebunden (egal ob Gast oder falsche Person — 'die wahre Person nicht gebunden')."""
    far_k = far_n = frr_k = frr_n = 0
    for r in results:
        if r.probe.error or not r.probe.embedding:
            continue
        decision = r.decisions[(mode, threshold)]
        is_genuine = r.probe.truth_speaker in profile_names
        if is_genuine:
            frr_n += 1
            if decision != r.probe.truth_speaker:
                frr_k += 1
        else:
            far_n += 1
            if decision is not None:
                far_k += 1
    return (far_k, far_n), (frr_k, frr_n)


def _confusion(results: List[ProbeResult], mode: str, threshold: float, channel: str) -> Dict[str, Dict[str, int]]:
    matrix: Dict[str, Dict[str, int]] = {}
    for r in results:
        p = r.probe
        if p.error or not p.embedding or p.channel != channel:
            continue
        decision = r.decisions[(mode, threshold)]
        col = decision if decision is not None else GUEST_LABEL
        matrix.setdefault(p.truth_speaker, {}).setdefault(col, 0)
        matrix[p.truth_speaker][col] += 1
    return matrix


def _frac(k: int, n: int) -> str:
    return f"{k}/{n} ({100.0 * k / n:.1f} %)" if n else "—"


def write_report_md(path: Path, results: List[ProbeResult], profiles: List[Profile],
                     thresholds: List[float], margin: float, store_path: Path,
                     sidecar_url: str, source_desc: str) -> None:
    profile_names = {p.name for p in profiles}
    usable = [r for r in results if not r.probe.error and r.probe.embedding]
    errored = [r for r in results if r.probe.error or not r.probe.embedding]
    n_genuine = sum(1 for r in usable if r.probe.truth_speaker in profile_names)
    n_impostor = len(usable) - n_genuine
    channels = sorted({r.probe.channel for r in usable})
    qualities: Dict[str, int] = {}
    for r in usable:
        qualities[r.probe.quality] = qualities.get(r.probe.quality, 0) + 1

    lines: List[str] = []
    lines.append(f"# Speaker-A/B-Report — {datetime.now().isoformat(timespec='seconds')}")
    lines.append("")
    lines.append(f"- Quelle: {source_desc}")
    lines.append(f"- Store: `{store_path}` ({len(profiles)} Profile: {', '.join(sorted(profile_names))})")
    lines.append(f"- Sidecar: `{sidecar_url}`")
    lines.append(f"- Margin (Bindungsregel): {margin:.2f}")
    lines.append(f"- Schwellen-Matrix: {', '.join(f'{t:.2f}' for t in thresholds)}")
    lines.append(f"- Proben: {len(results)} gesamt, {len(usable)} auswertbar "
                 f"({n_genuine} genuine [Wahrheit ist ein enrolltes Profil], "
                 f"{n_impostor} fremd/Impostor [Wahrheit NICHT enrollt]), {len(errored)} Fehler")
    lines.append(f"- Kanäle: {', '.join(channels) if channels else '—'}")
    lines.append(f"- Qualitäten: " + (", ".join(f'{q}={n}' for q, n in sorted(qualities.items())) or "—"))
    lines.append("")
    lines.append(f"> **Ehrlicher Hinweis:** N={len(usable)} auswertbare Proben ist KLEIN. FAR-Proxy/"
                 f"FRR-Proxy hier sind Test-Gate-Anekdoten-Ersatz, KEINE belastbare ROC/EER-Messung — "
                 f"erst mit deutlich mehr Proben je Kanal/Sprecher (Größenordnung Dutzende bis "
                 f"Hunderte pro Zelle) tragen die Prozentzahlen ein echtes Konfidenzintervall.")
    if errored:
        lines.append("")
        lines.append(f"Fehlerhafte Proben ({len(errored)}, aus Auswertung ausgeschlossen):")
        for r in errored:
            lines.append(f"  - `{r.probe.wav_path}`: {r.probe.error or 'kein Embedding'}")

    lines.append("")
    lines.append("## Übersicht — FAR-Proxy / FRR-Proxy je Modus × Schwelle")
    lines.append("")
    lines.append("| Schwelle | best-sample FAR-Proxy | best-sample FRR-Proxy | centroid FAR-Proxy | centroid FRR-Proxy |")
    lines.append("|---|---|---|---|---|")
    for t in thresholds:
        (fb_k, fb_n), (rb_k, rb_n) = _far_frr(results, profile_names, "best-sample", t)
        (fc_k, fc_n), (rc_k, rc_n) = _far_frr(results, profile_names, "centroid", t)
        lines.append(f"| {t:.2f} | {_frac(fb_k, fb_n)} | {_frac(rb_k, rb_n)} | {_frac(fc_k, fc_n)} | {_frac(rc_k, rc_n)} |")

    lines.append("")
    lines.append("## Confusion-Matrizen — je Modus × Schwelle, GETRENNT je Kanal")
    lines.append("")
    lines.append("Zeilen = Wahrheit (truth_speaker), Spalten = Entscheidung (Profilname oder "
                 f"`{GUEST_LABEL}`). Diagonale (truth == Entscheidung) = korrekt.")
    for mode in MODES:
        lines.append(f"\n### Modus: {mode}")
        for t in thresholds:
            lines.append(f"\n#### Schwelle {t:.2f}")
            if not channels:
                lines.append("(keine auswertbaren Proben)")
                continue
            for ch in channels:
                matrix = _confusion(results, mode, t, ch)
                if not matrix:
                    continue
                truths = sorted(matrix)
                cols = sorted({c for row in matrix.values() for c in row})
                lines.append(f"\n**Kanal `{ch}`**")
                lines.append("| Wahrheit \\ Entscheidung | " + " | ".join(cols) + " |")
                lines.append("|---|" + "---|" * len(cols))
                for tr in truths:
                    row = matrix[tr]
                    lines.append(f"| {tr} | " + " | ".join(str(row.get(c, 0)) for c in cols) + " |")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


# ── Orchestrierung ──────────────────────────────────────────────────────────────

def _guard_out_dir_outside_repo(out_dir: Path) -> None:
    """Datenschutz hart (s. README): der Report darf NIE im Repo landen. Auch bei
    manuell gesetztem --out-dir wird das erzwungen, damit kein Proben-/Embedding-Leak
    per Copy-Paste-Fehler ins Repo rutscht."""
    try:
        out_dir.resolve().relative_to(REPO_ROOT)
    except ValueError:
        return  # liegt NICHT im Repo ⇒ ok
    raise SystemExit(f"FEHLER: --out-dir {out_dir} liegt IM Repo ({REPO_ROOT}) — verboten "
                     f"(Proben/Embeddings dürfen nie ins Repo). Report-Pfad muss außerhalb liegen.")


def run(args: argparse.Namespace) -> int:
    thresholds = threshold_list()
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")

    if args.smoke:
        import tempfile
        with tempfile.TemporaryDirectory(prefix="speaker-ab-smoke-") as td:
            tmp = Path(td)
            store_path, probes = build_smoke_fixture(tmp)
            profiles = load_store(store_path)  # derselbe Loader wie im echten Lauf — beweist Schema-Kompatibilität
            out_dir = Path(args.out_dir) if args.out_dir else REPORT_ROOT / f"{ts}-smoke"
            _guard_out_dir_outside_repo(out_dir)
            out_dir.mkdir(parents=True, exist_ok=True)
            results = evaluate(probes, profiles, thresholds, args.margin)
            probes_path = out_dir / "probes.tsv"
            report_path = out_dir / "report.md"
            write_probes_tsv(probes_path, results, profiles, thresholds)
            write_report_md(report_path, results, profiles, thresholds, args.margin,
                             store_path, args.sidecar, "SMOKE (synthetisch, kein Sidecar, keine echten Stimmen)")
            return _smoke_assertions(results, probes_path, report_path, args.margin)

    if bool(args.manifest) == bool(args.wav_dir):
        raise SystemExit("FEHLER: genau EINES von --manifest ODER --wav-dir angeben (oder --smoke).")

    store_path = Path(args.store) if args.store else resolve_default_store_path()
    profiles = load_store(store_path)
    probes = read_manifest(Path(args.manifest)) if args.manifest else scan_wav_dir(Path(args.wav_dir))
    fetch_embeddings(probes, args.sidecar)

    out_dir = Path(args.out_dir) if args.out_dir else REPORT_ROOT / ts
    _guard_out_dir_outside_repo(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    results = evaluate(probes, profiles, thresholds, args.margin)
    probes_path = out_dir / "probes.tsv"
    report_path = out_dir / "report.md"
    write_probes_tsv(probes_path, results, profiles, thresholds)
    write_report_md(report_path, results, profiles, thresholds, args.margin, store_path, args.sidecar,
                     f"--manifest {args.manifest}" if args.manifest else f"--wav-dir {args.wav_dir}")

    n_ok = sum(1 for p in probes if not p.error and p.embedding)
    print(f"[run] fertig: {n_ok}/{len(probes)} Proben ausgewertet", file=sys.stderr)
    print(f"[run] Report: {out_dir}")
    print(f"[run]   - {probes_path}")
    print(f"[run]   - {report_path}")
    return 0 if n_ok > 0 else 1


def _smoke_assertions(results: List[ProbeResult], probes_path: Path, report_path: Path, margin: float) -> int:
    """Die 3 im Auftrag verlangten Beweise. Druckt PASS/FAIL-Zeilen; Exit != 0 bei
    JEDEM Fail (ein grüner Smoke, der etwas Falsches beweist, ist schlimmer als
    ein roter)."""
    ok = True
    print(f"[smoke] Report: {report_path.parent}")

    a1 = probes_path.exists() and report_path.exists()
    print(f"[smoke] Assertion 1 (Report entsteht): {'PASS' if a1 else 'FAIL'} — "
          f"probes.tsv={'da' if probes_path.exists() else 'FEHLT'}, report.md={'da' if report_path.exists() else 'FEHLT'}")
    ok &= a1

    solo_row = next((r for r in results if r.probe.truth_speaker == "solo"), None)
    if solo_row is None:
        print("[smoke] Assertion 2 (best-sample==centroid bei 1-Sample-Profil): FAIL — Testprobe 'solo' fehlt")
        ok = False
    else:
        s_best = solo_row.scores["best-sample"]["solo"]
        s_cent = solo_row.scores["centroid"]["solo"]
        a2 = abs(s_best - s_cent) < 1e-9
        print(f"[smoke] Assertion 2 (best-sample==centroid bei 1-Sample-Profil 'solo'): "
              f"{'PASS' if a2 else 'FAIL'} — best={s_best:.6f} centroid={s_cent:.6f}")
        ok &= a2

    amb_row = next((r for r in results if r.probe.wav_path and "ambiguous" in r.probe.wav_path), None)
    if amb_row is None:
        print("[smoke] Assertion 3 (Margin-Regel greift): FAIL — Ambiguitäts-Probe fehlt")
        ok = False
    else:
        mode = "best-sample"
        top1, top1_score, runner_up = amb_row.top1[mode], amb_row.top1_score[mode], amb_row.runner_up[mode]
        diff = None if runner_up is None else (top1_score - runner_up)
        t = THRESHOLD_MIN  # niedrigste Schwelle der Matrix — score liegt bei ~0.70, sollte hier >= sein
        decision = amb_row.decisions[(mode, t)]
        a3 = (runner_up is not None and diff is not None and diff < margin
              and top1_score >= t and decision is None)
        print(f"[smoke] Assertion 3 (Margin-Regel greift bei Paar-Ambiguität, Modus={mode}, Schwelle={t:.2f}): "
              f"{'PASS' if a3 else 'FAIL'} — top1={top1}({top1_score:.4f}) "
              f"runner_up={runner_up if runner_up is None else round(runner_up, 4)} "
              f"diff={'—' if diff is None else round(diff, 4)} margin={margin:.2f} ⇒ "
              f"Entscheidung={decision or GUEST_LABEL}")
        ok &= a3

    print(f"[smoke] {'ALLE ASSERTIONS PASS' if ok else 'MINDESTENS EINE ASSERTION FAIL'}")
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", default=None, help="TSV: wav_path, truth_speaker, channel, quality")
    ap.add_argument("--wav-dir", default=None, help="Verzeichnis, Layout <truth_speaker>/<channel>/*.wav")
    ap.add_argument("--store", default=None, help="Speaker-Profile-JSON (Default: echter Laufzeit-Pfad, s. resolve_default_store_path)")
    ap.add_argument("--sidecar", default=DEFAULT_SIDECAR_URL, help=f"Speaker-Sidecar-Basis-URL (Default {DEFAULT_SIDECAR_URL})")
    ap.add_argument("--margin", type=float, default=DEFAULT_MARGIN, help=f"Bindungs-Margin (Default {DEFAULT_MARGIN}, == Kotlin-Default)")
    ap.add_argument("--out-dir", default=None, help="Report-Zielverzeichnis (Default: ~/.hoshi/speaker-ab/<ts>/ — MUSS außerhalb des Repos liegen)")
    ap.add_argument("--smoke", action="store_true", help="Sidecar-freier Selbsttest mit synthetischen Proben (siehe Modul-Docstring)")
    args = ap.parse_args()
    return run(args)


if __name__ == "__main__":
    sys.exit(main())
