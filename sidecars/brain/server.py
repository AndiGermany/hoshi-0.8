#!/usr/bin/env python3
"""
server.py — text-only Gemma-4-E4B LLM-Sidecar (mlx-lm 0.31.2).
[0.8-Port] 1:1 aus Hoshi_0.5/hoshi-llm-optiq/server_e4b.py umgezogen (Datei
umbenannt für einen neutralen Sidecar-Namen; siehe sidecars/brain/README.md
für das Datei-Mapping). Der gen()-Flow ist UNVERÄNDERT — Umzug, kein Umbau.

Drop-in für den `/v1/chat`-Contract von `server_omni.py` (siehe
agent/.../streaming/clients/MlxOmniLlmClient.kt) → e4b als SMARTHEIT-Alternative
zum omni-e2b. Iter-110 bewies: e4b ist dramatisch ehrlicher+schlauer als e2b
(Meerschweinchen-Bug gefixt, Einstein/Reasoning korrekt), bei ~1,8s TTFT/24-31 tok/s.

Schlüssel-Fixes (Iter-110):
  - mlx-lm 0.31.2 (NICHT 0.31.3 — Regression #1242 bricht Gemma-4-KV-Sharing).
  - enable_thinking=False → kein Gemma-4-Denk-Channel-Leak (1,8s statt 9,7s).

Default-Modell: naiv-e4b (5,0 GB) — auf dem Iter-110-Set gleichwertig zu OptiQ (6,1 GB),
minimal schneller. --model mlx-community/gemma-4-e4b-it-OptiQ-4bit für die OptiQ-Variante.

Contract:
  POST /v1/chat  {sessionId,userId,messages:[{role,content}],stream,max_tokens,temperature}
                 → text/event-stream: `data: {"delta":"…"}\n\n` … `data: [DONE]\n\n`
                 T-Sensor (additiv, default aus): `"logprobs":true` im Request →
                 jeder Delta-Frame trägt zusätzlich `"logprob":<float>` (Logprob
                 des gesampelten Tokens, ln). Fehlt das Feld: Frames byte-identisch
                 zu heute (nur `delta`).
  POST /v1/score {"text":"<transkript>"} → reiner Teacher-Forcing-Prefill (KEINE
                 Generation): {"tokens":[...],"logprobs":[...],"mean_surprisal":…,
                 "max_surprisal":…,"token_count":…,"ms":…} (surprisal = -logprob, ln).
  GET  /health   → {"status":"ok","model":…,"loaded":true}

Start (Voice-Stack runter + Klassifikation pausiert → RAM frei):
  sidecars/brain/.venv/bin/python sidecars/brain/server.py --port 8041
  (kanonisch: sidecars/brain/run.sh — setzt venv/Modell/HF_HUB_OFFLINE korrekt)
"""
import argparse
import copy
import gc
import glob
import json
import os
import re
import shutil
import time
from typing import List, Optional

import uvicorn
import mlx.core as mx
from fastapi import FastAPI, HTTPException
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel
from huggingface_hub import snapshot_download
from mlx_lm import load, stream_generate
from mlx_lm.models.cache import make_prompt_cache, LRUPromptCache

try:
    from mlx_lm.sample_utils import make_sampler, make_logits_processors
except Exception:  # noqa: BLE001
    make_sampler = None
    make_logits_processors = None

# ── State ───────────────────────────────────────────────────────────────────
import threading

MODEL_ID = "mlx-community/gemma-4-e4b-it-4bit"
_model = None
_tok = None
_loaded = False
# MLX/Metal ist NICHT thread-safe — parallele generate-Calls crashen den Command-Buffer
# ("Completed handler provided after commit call", Iter-111-Crash bei 2 gleichzeitigen
# Voice-Turns). Dieser Lock serialisiert die Generierung: paralleler Turn wartet statt
# zu crashen. FastAPI fährt sync-Endpoints im Threadpool → ohne Lock laufen sie parallel.
_GEN_LOCK = threading.Lock()
# PATH B: EIGENER Lock fürs Lazy-Vocab des TokenEnforcer — NICHT _GEN_LOCK nehmen!
# gen() hält _GEN_LOCK über den ganzen Stream; threading.Lock ist NICHT reentrant →
# `with _GEN_LOCK` im selben Thread deadlockt für immer (PATH-B-Regression 2026-06-27,
# alle Folge-Requests starven). Dieser separate Lock serialisiert nur den Vocab-Bau.
_TE_BUILD_LOCK = threading.Lock()

# ── POST /switch-model: e2b↔e4b-Wechsel im selben Prozess (State) ───────────
# 16-GB-Wand (T136-Kommentare oben) + models.json-Pins (Repo-Root, s.
# _MODELS_JSON_PATH): NIE ein drittes/beliebiges HF-Repo laden, NIE e2b+e4b
# gleichzeitig resident. Nur die zwei explizit gepinnten Gemma-4-4bit-Varianten
# sind ueberhaupt schaltbar — alles andere ist ein 422, kein Rate-Limit-Problem.
ALLOWED_SWITCH_MODELS = {
    "mlx-community/gemma-4-e2b-it-4bit",
    "mlx-community/gemma-4-e4b-it-4bit",
}
# Serialisiert NUR die kurze Check-und-Setz-Entscheidung eines /switch-model-Aufrufs
# (Whitelist/Already-active/Cache/Pin/Disk) — NICHT den langen Download/Swap selbst
# (der laeuft ggf. in einem Hintergrund-Thread weiter, s. _download_and_swap).
_SWITCH_LOCK = threading.Lock()
_switching = False                     # True waehrend Download ODER Swap laeuft
_switch_phase: Optional[str] = None    # "downloading" | "loading" | None
_switch_target: Optional[str] = None   # Ziel-Modell des laufenden Wechsels
_switch_error: Optional[str] = None    # letzter Fehlertext (ueberlebt bis zum naechsten Versuch)
# Revisions-Incident 3.0 (2026-07-20 14:24): ein ungepinnter snapshot_download() hat
# real refs/main verbogen + .incomplete-Leichen hinterlassen. Downloads via
# /switch-model laufen deshalb NUR gegen die in models.json gepinnte Revision —
# fehlt der Pin, wird bewusst NICHT blind heruntergeladen (409 statt Risiko).
_MODELS_JSON_PATH = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "models.json")
)
_MIN_FREE_DISK_BYTES = 8 * 1024 ** 3   # 8 GB Sicherheitsmarge vor einem Modell-Download

# ── T372: MLX-Streams sind thread-gebunden ──────────────────────────────────
# mlx_lm legt seinen `generation_stream` beim Import im MAIN-Thread an
# (mlx_lm/generate.py:226). FastAPI fährt sync-Endpoints aber im Threadpool → der
# StreamingResponse-Generator läuft in einem WORKER-Thread, in dem dieser Stream
# ungültig ist ("There is no Stream(gpu, 0) in current thread"; crasht in
# wired_limit → mx.synchronize, generate.py:263). Fix: pro Worker-Thread EINEN
# eigenen Stream anlegen (thread-local — ein Stream ist nur im erzeugenden Thread
# gültig) und mlx_lm VOR jeder Generierung darauf umbiegen. Unter _GEN_LOCK
# serialisiert → kein Race auf die Modul-Globale. Empirisch verifiziert:
# H0=Bug-Repro, H3=Fix wirkt, H4=Stream ist thread-bound (fremder Thread crasht).
import sys as _sys
import mlx_lm.generate  # noqa: F401 — registriert das Submodul in sys.modules
_mlxgen = _sys.modules["mlx_lm.generate"]
_gen_stream_tls = threading.local()


def _ensure_gen_stream() -> None:
    """mlx_lm.generation_stream auf einen im AKTUELLEN Thread gültigen Stream biegen."""
    s = getattr(_gen_stream_tls, "stream", None)
    if s is None:
        s = mx.new_stream(mx.default_device())
        _gen_stream_tls.stream = s
    _mlxgen.generation_stream = s

# S4b (Iter-113, B-087): optionaler Prefix-Cache. server_e4b prefillte bisher die
# volle ~2400-Zeichen-Persona JEDEN Turn neu (~2,2s warm, gemessen via Turn-Trace).
# mlx-lm's LRUPromptCache reused den gemeinsamen Prompt-Prefix (System+History) über
# Turns — nur die neue User-Message wird geprefillt. Library-trim-safe (can_trim-gated,
# kein Crash bei nicht-trimmbarem Cache). Alle Cache-Ops defensiv (jeder Fehler →
# Fallback ohne Cache). Iter-113-Live-Test: kohärent + ~21% schnelleres erstes Token
# (mehr im Mehr-Turn-Betrieb). DEFAULT ON; HOSHI_E4B_PROMPT_CACHE=0 deaktiviert (Revert).
_PROMPT_CACHE_ON = os.environ.get("HOSHI_E4B_PROMPT_CACHE", "1") == "1"
_LRU = LRUPromptCache(max_size=8) if _PROMPT_CACHE_ON else None

# ── T136 / T141: optionale, default-OFF Speicher-Hints (Iter-117) ─────────────
# Beide Features ändern OHNE gesetztes Env NICHTS am Verhalten (Default = aus).
#
# T136 HOSHI_E4B_WIRED_MB (Default 0 = AUS): pinnt nach load() bis zu N MB der
#   Gewichte als wired (resident, nicht swappbar) via mx.set_wired_limit. Ohne
#   Flag landen die ~4,4 GB e4b-Gewichte als IOAccelerator-Shared/dirty → zeitweise
#   geswappt → Re-Decompress-Stalls im Token-Stream (B-093-Jitter). Defensiv:
#   wird gegen den System-wired-Limit (device_info) geclamped, jeder Fehler →
#   Fallback ohne wired. Scharfschalten erst nach Director-Gegengewicht (T144).
_WIRED_MB = 0
try:
    _WIRED_MB = max(0, int(os.environ.get("HOSHI_E4B_WIRED_MB", "0")))
except (TypeError, ValueError):
    _WIRED_MB = 0

# T170 Auto-Release-Guard (Felix-Bedingung, Iter-129): das war das fehlende
# „Director-Gegengewicht" aus dem Felix+Yuki-Veto (B-052/B-073). Ein Hintergrund-
# Monitor liest `kern.memorystatus_level`. Fällt der unter RELEASE (akuter Druck) →
# wired auf 0 zurück (gibt e4bs gepinnte Pages frei, damit ein Live-Voice-Peak
# STT+TTS nicht erstickt). Erholt er sich über REAPPLY (Hysterese) → wired neu setzen.
# Yukis Peak-Angst ist damit adressiert: unter Druck löst sich der Pin selbst.
# Nur aktiv, wenn _WIRED_MB > 0. Defensiv: jeder Fehler im Monitor wird verschluckt,
# der Monitor-Thread darf den Server NIE killen.
try:
    _WIRED_RELEASE_LVL = max(1, int(os.environ.get("HOSHI_E4B_WIRED_RELEASE_LVL", "25")))
except (TypeError, ValueError):
    _WIRED_RELEASE_LVL = 25
try:
    _WIRED_REAPPLY_LVL = max(_WIRED_RELEASE_LVL + 5,
                             int(os.environ.get("HOSHI_E4B_WIRED_REAPPLY_LVL", "40")))
except (TypeError, ValueError):
    _WIRED_REAPPLY_LVL = 40
try:
    _WIRED_POLL_S = max(2, int(os.environ.get("HOSHI_E4B_WIRED_POLL_S", "4")))
except (TypeError, ValueError):
    _WIRED_POLL_S = 4

_wired_want_bytes = 0   # geclampter Ziel-Pin in Bytes (0 = Feature aus)
_wired_active = False   # ist der Pin gerade gesetzt (vs. unter Druck freigegeben)?
_wired_last_lvl = -1    # zuletzt gelesener memorystatus_level (für /health)

# T170 Touch-Loop (Iter-129d EXPERIMENT, default OFF): die Iter-129c-Messung zeigte,
# dass set_wired_limit den Idle-Cold NICHT verhindert (29s Prefill nach 35min Idle).
# Wurzel: e4bs anonymous-writable Gewichte werden idle zu Disk-Swap demotet, das
# Zurückholen von 4,4 GB kostet die 29s. Hypothese: ein periodischer Mini-Forward
# (1 Token) hält die Gewichte „recently accessed" → der OS demotet sie nicht zu Disk.
# Läuft unter _GEN_LOCK (try-lock, überspringt wenn ein echter Turn läuft — der hält
# eh schon warm). HOSHI_E4B_TOUCH_LOOP_S=N (Sekunden, 0=aus). Kosten: ~150ms GPU
# alle N s = etwas Strom/Thermal (Felix-Caveat) — Experiment, ehrlich messen ob's wirkt.
try:
    _TOUCH_LOOP_S = max(0, int(os.environ.get("HOSHI_E4B_TOUCH_LOOP_S", "0")))
except (TypeError, ValueError):
    _TOUCH_LOOP_S = 0
_touch_count = 0        # wie oft wurde getoucht (für /health)
_touch_skips = 0        # wie oft übersprungen (Lock busy = echter Turn lief)
_last_touch_ts = 0.0    # Zeitstempel des letzten Touch

# T168 Persona-KV-Freeze (Iter-129e, default OFF): der warme Wissens-Prefill ist
# ~4s, weil der LRUPromptCache den geteilten Persona-Prefix NICHT wiederverwendet
# (Gemma-4 RotatingKVCache → can_trim=False, nur exakte Treffer; B-110). Fix: den
# stabilen Persona-Prefix EINMAL pur-forward prefillen, KV einfrieren (deepcopy), pro
# Turn nur das Suffix drauf-prefillen. Standalone validiert (Iter-129e): ~27× TTFT
# (9329→349ms), Output KOHÄRENT (9/12 Tokens exakt wie frischer Greedy-Prefill) aber
# NICHT bit-identisch (MLX-Quant über verschiedene Pfade, T141-Klasse — bei temp>0
# eh unsichtbar). Persona-Boundary wird auto-erkannt (längster gemeinsamer Token-
# Prefix zweier aufeinanderfolgender Turns ≥ _PERSONA_MIN). Ersetzt den LRU-Pfad wenn
# AN. Default OFF bis Coherence-A/B (Yuki). Rollback: HOSHI_E4B_PERSONA_KV_FREEZE=0.
_PERSONA_KV_FREEZE = os.environ.get("HOSHI_E4B_PERSONA_KV_FREEZE", "0") == "1"
try:
    _PERSONA_MIN = max(100, int(os.environ.get("HOSHI_E4B_PERSONA_MIN_TOKENS", "400")))
except (TypeError, ValueError):
    _PERSONA_MIN = 400
_persona_cache = None   # eingefrorene Persona-KV (deepcopy-Quelle)
_persona_tokens: List[int] = []  # Token-Sequenz, aus der _persona_cache gebaut wurde
_persona_prev: List[int] = []    # Tokens des vorigen Turns (für Common-Prefix-Erkennung)
_persona_hits = 0       # wie oft der Frozen-Persona-Treffer griff (für /health)
_persona_builds = 0     # wie oft die Persona-KV (neu) eingefroren wurde

# T141 HOSHI_E4B_PLE_CPU (Default OFF): verlagert Gemmas Per-Layer-Embedding-Gather
#   (~1,59 GB, sparse, kalt) auf den CPU-Stream (mx.cpu). Yuki-Bench standalone:
#   CPU-Gather 0,162ms vs GPU 0,307ms, bit-identisch. Greift NUR bei gemma-3n/4
#   (Gemma4TextModel + per-layer-embedding vorhanden) — bei jedem anderen Modell
#   fällt der Patch hart auf OFF (Noa-Caveat: kein Kern-Pfad-Coupling für OSS-Fremde).
#   Physik (Ticket): MLX paginiert nicht pro Row → das verlagert primär COMPUTE,
#   nicht automatisch die 1,59 GB RAM (echter RSS-Drop = mmap-Folge-Ticket, Stufe b).
_PLE_CPU_ON = os.environ.get("HOSHI_E4B_PLE_CPU", "0") == "1"

# ── T137/T172 KV-Cap (Robustheit, default ON mit sicherem Cap) ────────────────
# Yuki-Befund (B-110/T172, gemessen): make_prompt_cache OHNE max_kv_size laesst den
# KV-Cache der globalen (nicht-Sliding) Gemma-4-Layer UNBEGRENZT wachsen. Unter
# Speicherdruck (langer Kontext / Multi-Satellit-Queue, Cache ueber Turns weiter
# befuellt via LRU.insert_cache) crasht der Decode auf ~0,6 statt 26 tok/s = 30s-Turns.
# Fix: max_kv_size setzen -> mlx-lm legt fuer die nicht-Sliding-Layer eine RotatingKVCache
# (max_size=N) an, die aeltere Keys ausringt. Default 4096 deckt einen vollen Wissens-Turn
# (~2500 Prompt-Tok) + Antwort, deckelt aber das Wachstum. HOSHI_E4B_MAX_KV=0 = Revert
# (unbegrenzt, altes Verhalten). WICHTIG (B-077-Veto): NUR Groessen-Cap, KEINE KV-Quant (kv_bits).
try:
    _MAX_KV = max(0, int(os.environ.get("HOSHI_E4B_MAX_KV", "4096")))
except (TypeError, ValueError):
    _MAX_KV = 4096


def _new_cache():
    """T137/T172: make_prompt_cache mit Groessen-Cap. _MAX_KV=0 -> unbegrenzt (alt).
    Defensiv: faellt eine alte mlx-lm-Version ohne max_kv_size-Param zurueck auf ungekappt."""
    if _MAX_KV <= 0:
        return make_prompt_cache(_model)
    try:
        return make_prompt_cache(_model, max_kv_size=_MAX_KV)
    except TypeError:
        return make_prompt_cache(_model)

# Defensiver Channel-Filter (enable_thinking=False unterdrückt es eigentlich schon)
_CH_OPEN = "<|channel>"
_CH_CLOSE = "<channel|>"
_STRIP = re.compile(r"<\|?(channel|turn|think|message)\|?>")


# ── Schemas (Contract: MlxOmniLlmClient.kt) ─────────────────────────────────
class Msg(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    messages: List[Msg]
    sessionId: str = "default"
    userId: str = "default"
    stream: bool = True
    max_tokens: int = 320
    temperature: float = 0.7
    # Gabel B / PATH A (optional, default None = altes Verhalten): OpenAI-style
    # Tool-Definitionen [{"type":"function","function":{...}}]. Nur wenn gesetzt,
    # rendert das gemma-4-Template den nativen <|tool>…<tool|>-Block (Server bleibt
    # bei tools=None byte-identisch zu heute). Das Modell emittiert dann ggf.
    # <|tool_call>call:NAME{…}<tool_call|> — diese Marker reicht der Server roh durch.
    tools: Optional[List[dict]] = None
    # PATH B (Track #3 Agentik, opt-in, default False = altes Verhalten): erzwingt
    # strukturell valides Tool-JSON {tool,args} via lm-format-enforcer-Logits-Processor
    # (statt freier Prosa / gemma-Marker → killt Fake-Confirm/malformed bei indirekten
    # Phrasierungen). Default OFF → Server byte-identisch zu heute. Kill-Switch: Feld
    # weglassen. Siehe vault/tracks/PATH-B-spec.md.
    tool_grammar: bool = False
    # D1 (Sampling-Tuning, flag-gated vom Kotlin-Client; fehlend/None = EXAKT
    # heutiges Verhalten): min_p → make_sampler(min_p=...) (nativ in mlx-lm
    # 0.31.2, Default 0.0 = aus); presence_penalty → nativer Logits-Processor
    # via sample_utils.make_logits_processors (OpenAI-Semantik: subtrahiert
    # penalty von Tokens, die in den letzten 20 Tokens vorkamen).
    min_p: Optional[float] = None
    presence_penalty: Optional[float] = None
    # D1b (Stufe-0-Sampling aus Cowork-Research, flag-gated vom Kotlin-Client;
    # fehlend/None = EXAKT heutiges Verhalten, D1-Muster): XTC „Exclude Top
    # Choices" — schneidet pro Schritt (mit Wahrscheinlichkeit xtc_probability)
    # die TOP-Tokens weg, alle außer dem unwahrscheinlichsten über
    # xtc_threshold → weniger generische Höchst-Wahrscheinlichkeits-Floskeln.
    # Nativ in der installierten mlx-lm-Version (make_sampler-Signatur zur
    # Ladezeit via inspect verifiziert, s. _XTC_SUPPORTED); trägt die Version
    # kein XTC → ehrliches Log + Felder ignorieren (NIE crashen).
    xtc_probability: Optional[float] = None
    xtc_threshold: Optional[float] = None
    # D1b Öffner-Bias (URIAL-Befund: Persona liegt in den FRÜHEN Tokens —
    # generische Assistenten-Öffner dort killen sie): bei True bannt ein
    # positionsabhängiger Logits-Processor NUR auf den ersten 3 Decode-
    # Positionen die kuratierten Öffner („Gerne", „Natürlich", „Sure", …)
    # weich mit −10.0. v1 bewusst NUR Ban-Liste, KEINE Boost-Liste
    # (konservativer). Fehlt/None/False ⇒ exakt heutiger Pfad.
    opener_bias: Optional[bool] = None
    # T-Sensor Delta-Logprobs (additiv, Default False = heutiges Verhalten):
    # bei True trägt jeder SSE-Delta-Frame zusätzlich "logprob" — den Logprob
    # (ln) des gesampelten Tokens, für den Antwort-Entropie-Sensor (Kotlin-
    # Konsument MlxBrainAdapter, 0.8, null-tolerant). logprobs=false/fehlend ⇒
    # Frames byte-identisch zu heute (nur "delta", KEIN zusätzlicher Key).
    logprobs: bool = False


class ScoreRequest(BaseModel):
    text: str


app = FastAPI(title="hoshi-e4b-llm")


def build_prompt(messages: List[Msg], tools=None) -> str:
    """messages → Chat-Template mit enable_thinking=False (Anti-Denk-Leak).

    Gabel B / PATH A: `tools=None` → EXAKT der heutige Aufruf (keine tools-Kwarg,
    byte-identisch). `tools` gesetzt → die Tool-Definitionen werden an
    apply_chat_template durchgereicht; das gemma-4-Template rendert dann den
    nativen <|tool>…<tool|>-Block (enable_thinking=False bleibt kompatibel,
    da der Block bei truthy `tools` ohnehin emittiert wird)."""
    msgs = [{"role": m.role, "content": m.content} for m in messages]
    base = {} if tools is None else {"tools": tools}
    for extra in ({"enable_thinking": False, **base}, {**base}):
        try:
            return _tok.apply_chat_template(
                msgs, add_generation_prompt=True, tokenize=False, **extra
            )
        except TypeError:
            continue
    return _tok.apply_chat_template(msgs, add_generation_prompt=True, **base)


def build_tokens(messages: List[Msg]) -> List[int]:
    """Wie build_prompt, aber Token-IDs (für den Prefix-Cache; kein Doppel-BOS)."""
    msgs = [{"role": m.role, "content": m.content} for m in messages]
    for extra in ({"enable_thinking": False}, {}):
        try:
            return list(_tok.apply_chat_template(
                msgs, add_generation_prompt=True, tokenize=True, **extra
            ))
        except TypeError:
            continue
    return list(_tok.apply_chat_template(msgs, add_generation_prompt=True, tokenize=True))


# ── PATH B (Track #3 Agentik): strukturell erzwungenes Tool-JSON ──────────────
# Alles hier ist GATED auf ChatRequest.tool_grammar (default False). Bei ungesetztem
# Flag wird KEINE dieser Funktionen aufgerufen → Server byte-identisch zu heute.
# Statt freier Prosa / gemma-Marker erzwingt ein lm-format-enforcer-Logits-Processor
# (torch-FREI; mlx-lm 0.31.2 unterstützt logits_processors nativ via generate_step)
# ein strukturell valides JSON-Objekt {tool,args}. Spec: vault/tracks/PATH-B-spec.md.
TOOL_CALL_SCHEMA = {
    "type": "object",
    "properties": {
        "tool": {
            "type": "string",
            # Enum killt halluzinierte Tool-Namen; "none" = strukturelles Ablehnen
            # (statt Fake-Confirm), wenn kein Tool zur Anfrage passt.
            "enum": ["light_set", "climate_set", "scene_activate", "read_state", "none"],
        },
        # permissiv → strukturell valide, nicht über-constrained (Args-Form macht der Client).
        "args": {"type": "object"},
    },
    "required": ["tool", "args"],
    "additionalProperties": False,
}

# Synthetischer System-Turn (Tool-Grammar-Pfad): liefert die SEMANTIK (welches Tool
# wann), die STRUKTUR erzwingt der Logits-Processor. Wird als messages[0] vorangestellt;
# das gemma-4-Template rendert role=system nativ (chat_template.jinja prüft messages[0]).
_TOOL_GRAMMAR_SYSTEM = (
    'Antworte AUSSCHLIESSLICH mit einem JSON-Objekt {"tool":…,"args":…}. '
    "Erlaubte tool-Werte: light_set, climate_set, scene_activate, read_state. "
    'Passt kein Tool zur Anfrage: {"tool":"none","args":{}}.'
)

# Lazy-Singleton: der LFE-Vocab-Bau decodet ~262k Tokens (~wenige s) und wird erst
# beim 1. Tool-Turn (unter _GEN_LOCK serialisiert) gebaut. None → noch nicht gebaut.
_TE_TOKENIZER_DATA = None


def _replicate_te_tokenizer_data(hf_tok):
    """Torch-freie Nachbildung von lmformatenforcer.integrations.transformers.
    build_token_enforcer_tokenizer_data. Die offizielle Variante macht hart
    `import torch` (dieser venv hat KEIN torch) → wir bauen die regular-tokens-Liste
    + decoder selbst nach (identische Heuristik wie LFE _build_regular_tokens_list).
    Special-Tokens werden ausgelassen → <|tool_call>/Marker sind physisch unmöglich,
    das erzwingt ein sauberes JSON-Objekt statt des Markerformats."""
    from lmformatenforcer import TokenEnforcerTokenizerData

    vocab_size = len(hf_tok)
    token_0 = hf_tok.encode("0")[-1]
    special_ids = set(getattr(hf_tok, "all_special_ids", None) or [])
    regular_tokens = []
    for tid in range(vocab_size):
        if tid in special_ids:
            continue
        # token 0 voranstellen + erstes Zeichen droppen → Leerzeichen-Präfix bei
        # Wort-Start-Tokens (LFE-Heuristik 1:1).
        decoded_after_0 = hf_tok.decode([token_0, tid])[1:]
        decoded_regular = hf_tok.decode([tid])
        is_word_start = len(decoded_after_0) > len(decoded_regular)
        regular_tokens.append((tid, decoded_after_0, is_word_start))

    def _decode(tokens):
        return hf_tok.decode(tokens).rstrip("�")  # wie LFE _decode_function

    # eos inkl. gemma <end_of_turn> (mlx-lm-Wrapper kennt die volle Liste).
    eos = list(getattr(_tok, "eos_token_ids", None) or [hf_tok.eos_token_id])
    # 0.11.3-API: use_bitmask (False — Bitmask bräuchte torch) + vocab_size explizit.
    return TokenEnforcerTokenizerData(regular_tokens, _decode, eos, False, vocab_size)


def _get_te_tokenizer_data():
    """Lazy-Singleton der TokenEnforcerTokenizerData (einmaliger Vocab-Bau, unter
    _GEN_LOCK serialisiert, damit nicht zwei erste Tool-Turns parallel bauen)."""
    global _TE_TOKENIZER_DATA
    if _TE_TOKENIZER_DATA is None:
        with _TE_BUILD_LOCK:  # NICHT _GEN_LOCK — gen() hält den schon (non-reentrant → Deadlock)
            if _TE_TOKENIZER_DATA is None:
                _TE_TOKENIZER_DATA = _replicate_te_tokenizer_data(_tok._tokenizer)
    return _TE_TOKENIZER_DATA


def _build_tool_logits_processor():
    """Frischer mlx-lm-Logits-Processor pro Tool-Turn. Contract (mlx-lm 0.31.2,
    generate_step): (tokens: mx.array 1-D, logits: (1,vocab) mx.array) -> logits;
    läuft VOR dem Sampler → pre-sampling-Masking, KV-Cache/Streaming unberührt.
    `tokens` = trailing-prompt-Token + bisher generierte; base = len beim 1. Call,
    generated = tokens[base:] ist die reine Tool-JSON-Sequenz für den TokenEnforcer."""
    import numpy as np  # PATH B: lazy → byte-identisch wenn tool_grammar aus
    from lmformatenforcer import JsonSchemaParser, TokenEnforcer

    enforcer = TokenEnforcer(_get_te_tokenizer_data(), JsonSchemaParser(TOOL_CALL_SCHEMA))
    state = {"base": None}

    def _processor(tokens, logits):
        seq = tokens.tolist()
        if state["base"] is None:
            state["base"] = len(seq)
        generated = seq[state["base"]:]
        allowed = enforcer.get_allowed_tokens(generated).allowed_tokens
        vocab = logits.shape[-1]
        bias = np.full(vocab, -1e9, dtype=np.float32)
        if allowed:
            idx = np.array([t for t in allowed if 0 <= t < vocab], dtype=np.int64)
            if idx.size:
                bias[idx] = 0.0
        return logits + mx.array(bias).astype(logits.dtype)[None, :]

    return _processor


# ── D1b (Stufe-0-Sampling: XTC + Öffner-Bias — opt-in, default None = heute) ──
# Alles hier ist GATED auf die optionalen ChatRequest-Felder (default None).
# Ohne gesetzte Felder wird KEINE dieser Funktionen aufgerufen und kein kwarg
# verändert → Server byte-identisch zu heute (exakt das D1-Muster).

# XTC-Fähigkeit der INSTALLIERTEN mlx-lm-Version EINMAL ehrlich prüfen statt
# blind kwargs zu raten (verifiziert 0.31.1: make_sampler(temp, top_p, min_p,
# min_tokens_to_keep, top_k, xtc_probability=0.0, xtc_threshold=0.0,
# xtc_special_tokens=[])). Trägt die Version kein XTC → Request-Pfad loggt
# ehrlich und ignoriert die Felder (kein Crash, kein gebrochener Turn).
_XTC_SUPPORTED = False
try:
    import inspect as _inspect
    if make_sampler is not None:
        _mk_params = _inspect.signature(make_sampler).parameters
        _XTC_SUPPORTED = ("xtc_probability" in _mk_params
                          and "xtc_threshold" in _mk_params)
except Exception:  # noqa: BLE001
    _XTC_SUPPORTED = False

# XTC-Schutz-Tokens (eos/<end_of_turn>/\n): XTC darf das Turn-Ende NIE
# wegschneiden, sonst generiert eine flache Verteilung bis max_tokens weiter
# (Muster aus mlx_lm/server.py: xtc_special_tokens=[eos, "\n"]). Wird zur
# Ladezeit in _resolve_d1b_tokens() befüllt; leer = mlx-lm-Default (XTC
# funktioniert trotzdem, nur ohne den Schutz).
_XTC_SPECIAL_IDS: List[int] = []

# D1b Öffner-Bias: kuratierte Assistenten-Öffner (URIAL: die Persona sitzt in
# den frühen Tokens — genau dort drücken generische Floskeln sie weg). Pro Wort
# werden 4 Varianten geprüft (mit/ohne führendes Leerzeichen × Groß/klein);
# NUR Single-Token-Treffer wandern in die Ban-Liste — ein −10-Ban auf bloß das
# erste Sub-Token eines Mehr-Token-Öffners wäre ein zu breiter Streu-Ban.
_OPENER_WORDS = ["Gerne", "Natürlich", "Klar", "Selbstverständlich", "Als",
                 "Certainly", "Sure", "Of"]
_OPENER_BAN_IDS: List[int] = []   # zur Ladezeit aufgelöste Single-Token-IDs
_OPENER_BAN_POSITIONS = 3         # wirkt NUR auf Decode-Positionen 0..2
_OPENER_BAN_BIAS = -10.0          # weicher Ban, kein −inf (Modell darf notfalls doch)


def _resolve_d1b_tokens() -> None:
    """D1b: EINMAL zur Ladezeit (main, nach load) Öffner- + XTC-Schutz-Token-IDs
    auflösen. Defensiv: jeder Fehler loggt ehrlich und lässt die Listen leer —
    der Request-Pfad ignoriert die Features dann statt zu crashen."""
    global _OPENER_BAN_IDS, _XTC_SPECIAL_IDS
    # Öffner-Varianten → Token-IDs (add_special_tokens=False, sonst zählt das
    # implizite BOS mit und JEDER Treffer sähe wie Mehr-Token aus).
    try:
        hf = getattr(_tok, "_tokenizer", _tok)
        resolved: dict = {}
        skipped: List[str] = []
        for word in _OPENER_WORDS:
            for variant in sorted({word, word.lower(), " " + word, " " + word.lower()}):
                try:
                    ids = hf.encode(variant, add_special_tokens=False)
                except TypeError:
                    ids = hf.encode(variant)
                if len(ids) == 1:
                    resolved[int(ids[0])] = variant
                else:
                    skipped.append(f"{variant!r}={len(ids)}tok")
        _OPENER_BAN_IDS = sorted(resolved)
        print(f"  ✓ [e4b] D1b Öffner-Bias: {len(_OPENER_BAN_IDS)} Single-Token-Öffner "
              f"auflösbar (Ban −10.0 auf Pos 0..{_OPENER_BAN_POSITIONS - 1} bei "
              f"opener_bias=true); übersprungen (Mehr-Token): "
              f"{', '.join(skipped) if skipped else '—'}")
    except Exception as e:  # noqa: BLE001
        _OPENER_BAN_IDS = []
        print(f"  [e4b] D1b Öffner-Token-Auflösung fehlgeschlagen ({e}) "
              f"→ opener_bias wird ignoriert")
    # XTC-Schutz: alle eos-IDs (mlx-lm-Wrapper kennt inkl. gemma <end_of_turn>)
    # + newline-Token(s) — diese darf apply_xtc nie maskieren.
    try:
        specials = set()
        for tid in (getattr(_tok, "eos_token_ids", None) or []):
            if isinstance(tid, int):
                specials.add(tid)
        hf = getattr(_tok, "_tokenizer", _tok)
        try:
            nl = hf.encode("\n", add_special_tokens=False)
        except TypeError:
            nl = hf.encode("\n")
        specials.update(int(t) for t in nl)
        _XTC_SPECIAL_IDS = sorted(specials)
    except Exception as e:  # noqa: BLE001
        _XTC_SPECIAL_IDS = []
        print(f"  [e4b] D1b XTC-Schutz-Token-Auflösung fehlgeschlagen ({e}) "
              f"→ XTC ohne eos-Schutz")


def _build_opener_bias_processor():
    """D1b: frischer positionsabhängiger Logits-Processor pro Turn. Contract wie
    PATH B (mlx-lm generate_step, im installierten Paket verifiziert — _step
    concat't input_tokens VOR jedem Processor-Call): (tokens 1-D mx.array,
    logits (1,vocab)) → logits; tokens = trailing-prompt-Tokens + bisher
    generierte, base = len beim 1. Call ⇒ pos = len(tokens) − base ist die
    0-basierte Decode-Position des Tokens, das dieser Schritt sampelt. NUR auf
    Pos 0..2 werden die Öffner-IDs weich (−10.0) gebannt; ab Pos 3 gehen die
    Logits UNVERÄNDERT durch (No-op, minimaler Overhead). Append NACH Grammar-/
    Presence-Processoren ist sicher: addiert nur, −inf bleibt −inf."""
    import numpy as np  # lazy wie PATH B → byte-identisch wenn opener_bias aus

    ban_ids = list(_OPENER_BAN_IDS)
    state = {"base": None}

    def _processor(tokens, logits):
        n = int(tokens.shape[0])
        if state["base"] is None:
            state["base"] = n
        if (n - state["base"]) >= _OPENER_BAN_POSITIONS or not ban_ids:
            return logits
        vocab = logits.shape[-1]
        bias = np.zeros(vocab, dtype=np.float32)
        idx = np.array([t for t in ban_ids if 0 <= t < vocab], dtype=np.int64)
        if idx.size:
            bias[idx] = _OPENER_BAN_BIAS
        return logits + mx.array(bias).astype(logits.dtype)[None, :]

    return _processor


# ── POST /switch-model: Whitelist, Cache-Guard, Download+Swap ───────────────
def _load_model(model_id: str):
    """Injektionspunkt fuers Laden — der EINZIGE Aufrufer von mlx_lm.load() im
    ganzen Server (main() beim Start, _do_swap() beim Wechsel). Tests patchen
    diese Funktion, um ohne echtes Modell/GPU zu laufen."""
    return load(model_id)


def _unload_model() -> None:
    """Modell+Tokenizer entladen UND alle daran haengenden Tensor-Caches leeren,
    BEVOR ein neues Modell geladen wird (16-GB-Wand: zwei Gemma-4-4bit-Instanzen
    gleichzeitig resident sprengen den Mac). _LRU/_persona_cache halten echte
    KV-Cache-Tensoren der ALTEN Modell-Instanz — bloss _model=None setzen waere
    ein UNVOLLSTAENDIGES Entladen (stille Speicher-Leiche trotz "entladen"-Behauptung).
    """
    global _model, _tok, _loaded, _LRU, _TE_TOKENIZER_DATA
    global _persona_cache, _persona_tokens, _persona_prev
    _model = None
    _tok = None
    _loaded = False
    if _LRU is not None:
        _LRU = LRUPromptCache(max_size=8)  # frisch statt alte Tensoren mitschleppen
    _TE_TOKENIZER_DATA = None  # Vocab-Bau ist tokenizer-gebunden -> beim naechsten Tool-Turn neu
    _persona_cache = None
    _persona_tokens = []
    _persona_prev = []
    gc.collect()
    try:
        # mx.clear_cache loest die von MLX gehaltenen Metal-Buffer der entladenen
        # Gewichte — ohne das haengt der Speicher trotz gc als "cached" GPU-Buffer
        # weiter. Aeltere mlx-Versionen: mx.metal.clear_cache (Deprecation-Pattern
        # wie bei device_info weiter oben in dieser Datei).
        if hasattr(mx, "clear_cache"):
            mx.clear_cache()
        else:
            mx.metal.clear_cache()
    except Exception as e:  # noqa: BLE001 — Entladen darf nie am Cache-Clear scheitern
        print(f"  [e4b] switch-model: clear_cache fehlgeschlagen ({e}) → weiter")


def _model_fully_cached(model_id: str) -> bool:
    """Python-Nachbau des run.sh-Start-Guards (Brief-15-Prinzip): NIE das laufende
    Brain fuer ein Ziel opfern, das nur unvollstaendig im HF-Cache liegt. Gleiche
    Pruefung wie dort: Snapshot ueberhaupt vorhanden, keine *.incomplete-Reste,
    *.safetensors vorhanden, refs/main zeigt auf einen echten Snapshot-Ordner.
    local_files_only=True -> rein lesend, kein Netz-Zugriff."""
    try:
        path = snapshot_download(model_id, local_files_only=True)
    except Exception:
        return False  # kein Snapshot lokal
    repo_root = os.path.dirname(os.path.dirname(path))  # …/models--org--name
    if glob.glob(os.path.join(repo_root, "blobs", "*.incomplete")):
        return False  # abgebrochener Download
    if not glob.glob(os.path.join(path, "*.safetensors")):
        return False  # keine Gewichte
    refs_main = os.path.join(repo_root, "refs", "main")
    if os.path.isfile(refs_main):
        try:
            with open(refs_main, "rb") as f:
                raw = f.read()
            ref_hash = raw.strip().decode("ascii", "replace")
        except Exception:
            return False  # refs/main nicht lesbar
        if not ref_hash or not os.path.isdir(os.path.join(repo_root, "snapshots", ref_hash)):
            return False  # refs/main leer/kaputt oder zeigt ins Leere
    return True


def _lookup_pinned_revision(model_id: str) -> Optional[str]:
    """Liest models.json (Repo-Root) und liefert die pinned_revision des Eintrags
    mit hf_repo == model_id. None = kein Eintrag ODER kein Pin gesetzt — der
    Aufrufer MUSS dann mit 409 ablehnen statt blind zu downloaden (s. Revisions-
    Incident-Kommentar bei _MODELS_JSON_PATH oben)."""
    try:
        with open(_MODELS_JSON_PATH, "r", encoding="utf-8") as f:
            manifest = json.load(f)
        for entry in manifest.get("models", []):
            if entry.get("hf_repo") == model_id:
                return entry.get("pinned_revision")
    except Exception as e:  # noqa: BLE001
        print(f"  [e4b] switch-model: models.json nicht lesbar ({e}) → kein Pin")
    return None


def _free_disk_bytes() -> int:
    """Freier Speicherplatz auf dem Dateisystem, das den HF-Cache traegt (dort
    landet der Download). Existiert der Cache-Ordner noch nicht, wird gegen
    $HOME geprueft (dieselbe Partition im Normalfall)."""
    try:
        from huggingface_hub.constants import HF_HUB_CACHE
        path = HF_HUB_CACHE if os.path.isdir(HF_HUB_CACHE) else os.path.expanduser("~")
    except Exception:  # noqa: BLE001
        path = os.path.expanduser("~")
    return shutil.disk_usage(path).free


def _switch_download_progress_bytes(model_id: Optional[str]) -> Optional[int]:
    """Billige Fortschritts-Naeherung waehrend eines laufenden Downloads: Summe der
    Bytes, die schon im HF-Cache-Blobs-Ordner liegen (inkl. *.incomplete-Teilstuecke).
    BEWUSST kein Prozent/Total — das braeuchte eine Netz-Anfrage (Andi-Vorgabe:
    "kein Overengineering"). None = (noch) nichts lesbar, nie ein Fehler."""
    if not model_id:
        return None
    try:
        from huggingface_hub.constants import HF_HUB_CACHE
        folder = "models--" + model_id.replace("/", "--")
        blobs_dir = os.path.join(HF_HUB_CACHE, folder, "blobs")
        return sum(os.path.getsize(p) for p in glob.glob(os.path.join(blobs_dir, "*"))
                   if os.path.isfile(p))
    except Exception:  # noqa: BLE001
        return None


def _do_swap(target: str) -> dict:
    """Der eigentliche Modell-Tausch: laufende Generierungen abwarten/sperren
    (bestehender _GEN_LOCK), dann ZUERST entladen (16-GB-Wand), DANN laden.
    Erwartet, dass der Aufrufer _switching/_switch_target bereits gesetzt hat
    (Zusicherung: kein zweiter Wechsel laeuft parallel — s. _SWITCH_LOCK am
    Aufrufer). Wird sowohl synchron (Ziel bereits im Cache) als auch aus dem
    Hintergrund-Download-Thread (Ziel musste erst geladen werden) aufgerufen."""
    global _model, _tok, _loaded, MODEL_ID
    global _switching, _switch_phase, _switch_target, _switch_error
    _switch_phase = "loading"  # ab hier lehnt /v1/chat neue Turns mit 503 ab
    _GEN_LOCK.acquire()  # wartet eine LAUFENDE Generierung ab; neue wurden schon per 503 abgewiesen
    try:
        _unload_model()
        t0 = time.time()
        try:
            new_model, new_tok = _load_model(target)
        except Exception as e:  # noqa: BLE001
            # Ladefehler: ehrlich kaputt melden. KEIN stiller Rueckfall aufs alte
            # Modell — das ist ja bereits entladen. Der Betreiber heilt via
            # bin/hoshi heal/up.
            _model = None
            _tok = None
            _loaded = False
            MODEL_ID = target
            _switch_error = f"{type(e).__name__}: {e}"
            print(f"  [e4b] switch-model: Laden von '{target}' fehlgeschlagen "
                  f"({_switch_error}) → Brain jetzt UNGELADEN")
            raise HTTPException(
                status_code=500,
                detail=f"Modellwechsel zu '{target}' fehlgeschlagen: {_switch_error} — "
                       "Brain ist jetzt UNGELADEN (kein stiller Rueckfall aufs alte "
                       "Modell). bin/hoshi heal/up zum Reparieren.",
            )
        load_ms = int((time.time() - t0) * 1000)
        _model = new_model
        _tok = new_tok
        MODEL_ID = target
        _loaded = True
        _switch_error = None
        _resolve_d1b_tokens()  # tokenizer-abhaengige Konstanten (Oeffner/XTC) neu aufloesen
        return {"status": "ok", "model": MODEL_ID, "changed": True, "loadMs": load_ms}
    finally:
        _switching = False
        _switch_phase = None
        _switch_target = None
        _GEN_LOCK.release()


def _download_and_swap(target: str, pinned_revision: str) -> None:
    """Hintergrund-Worker (eigener Thread): laedt das ZIEL NUR ueber die in
    models.json gepinnte Revision (nie 'main'/HEAD — Revisions-Incident 3.0, s.o.),
    dann — nur bei Erfolg — der eigentliche Tausch via _do_swap. Waehrend des
    Downloads bleibt das ALTE Modell voll im Einsatz (kein Entladen, kein 503):
    genau das ist der Zweck dieses Pfads — es gibt IMMER ein funktionierendes Brain.
    Jeder Fehler hier laesst das alte Modell unangetastet und meldet ehrlich via
    _switch_error (sichtbar in /health)."""
    global _switching, _switch_phase, _switch_target, _switch_error
    try:
        snapshot_download(target, revision=pinned_revision)
    except Exception as e:  # noqa: BLE001
        _switch_error = f"Download fehlgeschlagen: {type(e).__name__}: {e}"
        print(f"  [e4b] switch-model: {_switch_error} → altes Modell bleibt unveraendert im Einsatz")
        _switching = False
        _switch_phase = None
        _switch_target = None
        return
    if not _model_fully_cached(target):
        _switch_error = ("Download gemeldet abgeschlossen, aber Cache-Check danach "
                          "weiterhin unvollstaendig")
        print(f"  [e4b] switch-model: {_switch_error} → Abbruch, altes Modell bleibt")
        _switching = False
        _switch_phase = None
        _switch_target = None
        return
    try:
        result = _do_swap(target)
        print(f"  ✓ [e4b] switch-model: Hintergrund-Download+Wechsel zu '{target}' "
              f"erfolgreich ({result['loadMs']}ms Ladezeit)")
    except HTTPException as e:
        print(f"  [e4b] switch-model: Wechsel nach Download fehlgeschlagen ({e.detail})")
    except Exception as e:  # noqa: BLE001
        print(f"  [e4b] switch-model: Wechsel nach Download unerwarteter Fehler ({e})")


class SwitchModelRequest(BaseModel):
    model: str


@app.post("/switch-model")
def switch_model(req: SwitchModelRequest):
    """Modellwechsel e2b↔e4b im selben Prozess. Whitelist HART (422 sonst).
    Ziel bereits aktiv -> 200 changed:false. Zweiter Aufruf waehrend eines
    laufenden Wechsels -> 409. Ziel schon vollstaendig im Cache -> synchroner
    Tausch (entladen->laden, s. _do_swap). Ziel fehlt/unvollstaendig -> NICHT das
    laufende Brain opfern: Download NUR gegen den models.json-Pin im Hintergrund
    (202), das ALTE Modell bedient waehrenddessen unveraendert weiter."""
    global _switching, _switch_phase, _switch_target, _switch_error
    target = req.model
    if target not in ALLOWED_SWITCH_MODELS:
        raise HTTPException(
            status_code=422,
            detail=f"Modell '{target}' nicht erlaubt — nur "
                   f"{sorted(ALLOWED_SWITCH_MODELS)} (16-GB-Wand + models.json-Pins).",
        )

    with _SWITCH_LOCK:
        if _switching:
            raise HTTPException(
                status_code=409,
                detail=f"Wechsel laeuft bereits (phase={_switch_phase}, "
                       f"target={_switch_target}) — bitte abwarten.",
            )
        if target == MODEL_ID and _loaded:
            return {"status": "ok", "model": MODEL_ID, "changed": False}

        pin = None
        if _model_fully_cached(target):
            phase = "loading"
        else:
            pin = _lookup_pinned_revision(target)
            if pin is None:
                raise HTTPException(
                    status_code=409,
                    detail=f"Ziel '{target}' liegt nicht vollstaendig im Cache UND hat "
                           "keinen Pin in models.json — bewusst kein ungepinnter Download "
                           "(Revisions-Incident 3.0).",
                )
            free = _free_disk_bytes()
            if free < _MIN_FREE_DISK_BYTES:
                raise HTTPException(
                    status_code=507,
                    detail=f"Nur {free // (1024 ** 3)} GB frei, mind. "
                           f"{_MIN_FREE_DISK_BYTES // (1024 ** 3)} GB fuer den Download "
                           "noetig — Wechsel abgebrochen, aktuelles Modell bleibt geladen.",
                )
            phase = "downloading"

        _switching = True
        _switch_phase = phase
        _switch_target = target
        _switch_error = None

    if phase == "loading":
        return _do_swap(target)

    # Ziel fehlt/unvollstaendig: Download im Hintergrund, ALTES Modell laeuft weiter.
    threading.Thread(
        target=_download_and_swap, args=(target, pin),
        name="switch-model-download", daemon=True,
    ).start()
    return JSONResponse(status_code=202, content={
        "status": "downloading", "model": MODEL_ID, "target": target, "changed": False,
    })


@app.get("/health")
def health():
    return {
        "status": "ok" if _loaded else ("switching" if _switching else "loading"),
        "model": MODEL_ID,
        "loaded": _loaded,
        # POST /switch-model-Telemetrie (ehrlich statt still, s. Endpoint oben):
        # switch_phase unterscheidet "downloading" (altes Modell bedient normal
        # weiter) von "loading" (echter Tausch, /v1/chat lehnt kurz mit 503 ab).
        # switch_error ueberlebt bis zum naechsten Versuch (Betreiber-Diagnose).
        "switching": _switching,
        "switch_phase": _switch_phase,
        "switch_target": _switch_target,
        "switch_error": _switch_error,
        "switch_download_bytes": (
            _switch_download_progress_bytes(_switch_target)
            if _switch_phase == "downloading" else None
        ),
        "engine": "mlx-lm-0.31.2",
        "max_kv": _MAX_KV,  # T137/T172: 0 = unbegrenzt (alt), sonst RotatingKVCache-Cap
        "thinking": False,
        # T170: Residency-Telemetrie für Yukis Peak-Test (wired an/frei + RAM-Level).
        "wired": {
            "want_mb": _wired_want_bytes // 1024 // 1024,
            "active": _wired_active,
            "memorystatus_level": _wired_last_lvl,
            "release_lvl": _WIRED_RELEASE_LVL,
            "reapply_lvl": _WIRED_REAPPLY_LVL,
        },
        # T170 Touch-Loop-Telemetrie (Iter-129d): Cold-Fix-Experiment.
        "touch": {
            "loop_s": _TOUCH_LOOP_S,
            "count": _touch_count,
            "skips": _touch_skips,
            "age_s": round(time.time() - _last_touch_ts, 1) if _last_touch_ts else None,
        },
        # T168 Persona-KV-Freeze-Telemetrie (Iter-129e, default OFF).
        "persona_kv": {
            "enabled": _PERSONA_KV_FREEZE,
            "frozen_tokens": len(_persona_tokens),
            "hits": _persona_hits,
            "builds": _persona_builds,
        },
    }


def _persona_fetch(tokens: List[int]):
    """T168: liefert (cache, rest_tokens). Bei Treffer auf den eingefrorenen Persona-
    Prefix → deepcopy(frozen) + nur Suffix prefillen. Sonst: Persona-KV (neu) einfrieren,
    sobald zwei aufeinanderfolgende Turns einen Prefix ≥ _PERSONA_MIN teilen. Defensiv:
    der Aufrufer fängt Exceptions → voller Prefill. Läuft unter _GEN_LOCK (B-083-sicher)."""
    global _persona_cache, _persona_tokens, _persona_prev, _persona_hits, _persona_builds
    n = len(_persona_tokens)
    if _persona_cache is not None and n > 0 and len(tokens) > n and tokens[:n] == _persona_tokens:
        _persona_hits += 1
        return copy.deepcopy(_persona_cache), tokens[n:]
    # kein/abweichender Treffer → prüfen, ob ein stabiler Prefix vorliegt (Common-Prefix
    # mit dem vorigen Turn = Persona+Entity, der byte-feste Teil B-089).
    prev = _persona_prev
    _persona_prev = list(tokens)
    common = 0
    if prev:
        for a, b in zip(prev, tokens):
            if a != b:
                break
            common += 1
    if common >= _PERSONA_MIN and common < len(tokens):
        # Persona = gemeinsamer Prefix. EINMALIG pur-forward prefillen (KEINE Generierung,
        # sonst Offset+1) + einfrieren. Dieser Turn nutzt sie gleich mit.
        pc = _new_cache()
        _model(mx.array(tokens[:common])[None], cache=pc)
        mx.eval([c.state for c in pc])
        _persona_cache = copy.deepcopy(pc)
        _persona_tokens = list(tokens[:common])
        _persona_builds += 1
        print(f"  ✓ [e4b] T168 Persona-KV eingefroren: {common} Tokens (build #{_persona_builds})")
        return copy.deepcopy(_persona_cache), tokens[common:]
    # noch kein stabiler Prefix → voller Prefill diese Runde
    return _new_cache(), tokens


@app.post("/v1/chat")
def chat(req: ChatRequest):
    # POST /switch-model: waehrend der echten Lade-Phase ist _model/_tok kurz WEG
    # (entladen, noch nicht ersetzt) — ehrlich 503 statt gegen _tok=None zu crashen.
    # Waehrend eines reinen Hintergrund-DOWNLOADS bedient das alte Modell unveraendert
    # weiter (kein Guard hier — genau das ist der Zweck des Downloads-im-Hintergrund:
    # es gibt IMMER ein funktionierendes Brain).
    if _switching and _switch_phase == "loading":
        raise HTTPException(
            status_code=503,
            detail=f"Brain wechselt gerade das Modell (lädt {_switch_target}) — "
                   "bitte kurz erneut versuchen.",
        )
    # PATH B (gated, default OFF): bei tool_grammar NICHT gemmas nativen Marker-Block
    # rendern, sondern einen synthetischen JSON-System-Turn voranstellen + tools=None
    # (die Struktur erzwingt der Logits-Processor, nicht das Template). tool_grammar
    # False → EXAKT der heutige Aufruf.
    if req.tool_grammar:
        msgs = [Msg(role="system", content=_TOOL_GRAMMAR_SYSTEM), *req.messages]
        prompt = build_prompt(msgs, tools=None)
    else:
        prompt = build_prompt(req.messages, tools=req.tools)
    # D1: min_p optional durchreichen — Feld fehlt/None ⇒ exakt heutiger
    # Aufruf make_sampler(temp=...) (min_p-Default 0.0 = deaktiviert).
    if make_sampler:
        _s_kwargs = {"temp": req.temperature}
        if req.min_p is not None:
            _s_kwargs["min_p"] = float(req.min_p)
        # D1b XTC (Felder fehlen/None ⇒ _s_kwargs unverändert = exakt heute):
        # nur durchreichen, wenn die installierte mlx-lm-Version den Param trägt
        # (_XTC_SUPPORTED, via inspect verifiziert) — sonst ehrliches Log statt
        # Crash. XTC greift in mlx-lm erst bei xtc_probability > 0 (Default 0.0).
        if req.xtc_probability is not None or req.xtc_threshold is not None:
            if _XTC_SUPPORTED:
                # Ranges hart clampen: apply_xtc validiert PRO Sampling-Schritt
                # (probability ∈ [0,1], threshold ∈ [0,0.5]) — ein ValueError
                # MITTEN im Stream würde den Turn brechen. NIE den Turn brechen.
                if req.xtc_probability is not None:
                    _p = min(1.0, max(0.0, float(req.xtc_probability)))
                    if _p != float(req.xtc_probability):
                        print(f"  [e4b] D1b xtc_probability {req.xtc_probability} "
                              f"außerhalb [0,1] → geclamped auf {_p}")
                    _s_kwargs["xtc_probability"] = _p
                if req.xtc_threshold is not None:
                    _t = min(0.5, max(0.0, float(req.xtc_threshold)))
                    if _t != float(req.xtc_threshold):
                        print(f"  [e4b] D1b xtc_threshold {req.xtc_threshold} "
                              f"außerhalb [0,0.5] → geclamped auf {_t}")
                    _s_kwargs["xtc_threshold"] = _t
                # eos/<end_of_turn>/\n nie wegschneiden (Muster mlx_lm/server.py)
                if _XTC_SPECIAL_IDS:
                    _s_kwargs["xtc_special_tokens"] = list(_XTC_SPECIAL_IDS)
            else:
                print("  [e4b] D1b xtc nicht verfügbar in dieser mlx-lm-Version "
                      "→ xtc_probability/xtc_threshold ignoriert")
        try:
            sampler = make_sampler(**_s_kwargs)
        except TypeError as e:
            # D1b-Versions-Drift-Schutz (sollte dank _XTC_SUPPORTED nie feuern):
            # XTC-Kwargs raus, heutiger Aufruf — NIE den Turn brechen.
            print(f"  [e4b] D1b make_sampler-TypeError ({e}) → ohne XTC-Kwargs")
            for _k in ("xtc_probability", "xtc_threshold", "xtc_special_tokens"):
                _s_kwargs.pop(_k, None)
            sampler = make_sampler(**_s_kwargs)
    else:
        sampler = None

    def gen():
        # MLX/Metal serialisieren — paralleler generate-Call crasht sonst den
        # Command-Buffer. Lock über den GESAMTEN Stream halten (parallele Turns warten).
        _GEN_LOCK.acquire()
        try:
            _ensure_gen_stream()  # T372: GPU-Stream im Worker-Thread gültig machen
            kwargs = {"max_tokens": req.max_tokens}
            if sampler is not None:
                kwargs["sampler"] = sampler
            # S4b: Prefix-Cache-Pfad (flag-gated). Holt den naechsten gecachten
            # Prompt-Prefix, prefillt nur den Rest. Alles defensiv → bei JEDEM
            # Fehler Fallback auf den vollen String-Prefill (kein Cache).
            lru_tokens = None
            gen_input = prompt
            persona_done = False
            # Gabel B / PATH A: bei gesetzten `tools` BEIDE Prefix-Cache-Pfade umgehen.
            # build_tokens() tokenisiert OHNE tools → der gecachte Token-Key passte sonst
            # nicht zum tools-haltigen Prompt (Persona-Boundary + LRU-Key würden driften).
            # Tool-Turns sind selten/Einmal-Kommandos → voller String-Prefill (= `prompt`,
            # tools inklusive) ist hier korrekt und minimal-invasiv. tools=None → unverändert.
            # PATH B: tool_grammar bypassed BEIDE Prefix-Cache-Pfade (der Prompt
            # enthält den synthetischen System-Turn + wird grammar-maskiert → eigener,
            # voller String-Prefill via `prompt`). tool_grammar False → unverändert.
            _cache_ok = req.tools is None and not req.tool_grammar
            # T168 (default OFF): Persona-KV-Freeze ERSETZT den LRU-Pfad. Frozen-Persona
            # + nur Suffix prefillen (~27× TTFT). lru_tokens bleibt None → kein LRU-Insert.
            if _cache_ok and _PERSONA_KV_FREEZE:
                try:
                    ptoks = build_tokens(req.messages)
                    cache, rest = _persona_fetch(ptoks)
                    kwargs["prompt_cache"] = cache
                    gen_input = rest if rest else ptoks[-1:]  # nie leer prefillen
                    persona_done = True
                except Exception as e:  # noqa: BLE001
                    print(f"  [e4b] T168 persona-kv-freeze failed ({e}) → voller Prefill")
                    gen_input = prompt
                    kwargs.pop("prompt_cache", None)
            if _cache_ok and not persona_done and _LRU is not None:
                try:
                    lru_tokens = build_tokens(req.messages)
                    cache, rest = _LRU.fetch_nearest_cache(MODEL_ID, lru_tokens)
                    if cache is None:
                        cache = _new_cache()
                        rest = lru_tokens
                    kwargs["prompt_cache"] = cache
                    gen_input = rest if rest else lru_tokens[-1:]  # nie leer prefillen
                except Exception as e:  # noqa: BLE001
                    print(f"  [e4b] prompt-cache fetch failed ({e}) → ohne Cache")
                    lru_tokens = None
                    gen_input = prompt
                    kwargs.pop("prompt_cache", None)
            # PATH B (gated, default OFF → kwargs unverändert): strukturell valides
            # Tool-JSON erzwingen. mlx-lm 0.31.2 reicht logits_processors via **kwargs
            # an generate_step durch (wirkt VOR dem sampler; sampler+logits_processors
            # koexistieren → der TypeError-Fallback unten feuert NICHT). Defensiv: jeder
            # Aufbau-Fehler → unconstrained weiter (NIE den Turn brechen).
            if req.tool_grammar:
                try:
                    kwargs["logits_processors"] = [_build_tool_logits_processor()]
                except Exception as e:  # noqa: BLE001
                    print(f"  [e4b] PATH B tool_grammar setup failed ({e}) → unconstrained")
                    kwargs.pop("logits_processors", None)
            # D1 (fehlend/None → kwargs unverändert = heute): presence_penalty
            # als nativer mlx-lm-0.31.2-Processor. Append NACH dem Grammar-
            # Processor ist sicher: Presence subtrahiert nur (-inf bleibt -inf).
            # Defensiv: jeder Fehler → ohne Penalty weiter (NIE den Turn brechen).
            if req.presence_penalty is not None and make_logits_processors is not None:
                try:
                    kwargs.setdefault("logits_processors", []).extend(
                        make_logits_processors(presence_penalty=float(req.presence_penalty)))
                except Exception as e:  # noqa: BLE001
                    print(f"  [e4b] D1 presence_penalty setup failed ({e}) → ohne Penalty")
            # D1b (fehlend/None/False → kwargs unverändert = heute): Öffner-Bias.
            # Positionsabhängiger Processor, bannt NUR auf den ersten 3 Decode-
            # Positionen die kuratierten Assistenten-Öffner weich (−10.0) —
            # URIAL: Persona liegt in den frühen Tokens. Append NACH Grammar+
            # Presence ist sicher (addiert nur; −inf bleibt −inf). Defensiv:
            # jeder Fehler → ohne Bias weiter (NIE den Turn brechen).
            if req.opener_bias:
                if _OPENER_BAN_IDS:
                    try:
                        kwargs.setdefault("logits_processors", []).append(
                            _build_opener_bias_processor())
                    except Exception as e:  # noqa: BLE001
                        print(f"  [e4b] D1b opener_bias setup failed ({e}) → ohne Bias")
                else:
                    print("  [e4b] D1b opener_bias=true, aber keine Öffner-Tokens "
                          "aufgelöst → ignoriert")
            try:
                stream = stream_generate(_model, _tok, gen_input, **kwargs)
            except TypeError:
                stream = stream_generate(_model, _tok, gen_input, max_tokens=req.max_tokens)
            # Defensive: falls trotz enable_thinking=False ein Denk-Channel auftaucht,
            # erst NACH dem schließenden <channel|> streamen (sehr selten; kostet dann TTFT).
            seen_close = False
            thinking = False
            gen_tokens: List[int] = []
            for resp in stream:
                if lru_tokens is not None:
                    tk = getattr(resp, "token", None)
                    if tk is not None:
                        gen_tokens.append(tk)
                piece = getattr(resp, "text", "")
                if not piece:
                    continue
                if not seen_close:
                    if _CH_OPEN in piece:
                        thinking = True
                    if _CH_CLOSE in piece:
                        piece = piece.split(_CH_CLOSE, 1)[-1]
                        seen_close = True
                        thinking = False
                    elif thinking:
                        continue  # noch im Denk-Channel → nicht streamen
                piece = _STRIP.sub("", piece)
                if piece:
                    frame = {"delta": piece}
                    # T-Sensor (gated, default aus → frame unveraendert = heute):
                    # Logprob des gesampelten Tokens. resp.logprobs ist bereits
                    # async_eval't (generate_step) — die Indizierung + .item()
                    # holt nur den EINEN Skalar, kein Re-Compute des Vokab-Vektors.
                    if req.logprobs:
                        try:
                            frame["logprob"] = resp.logprobs[resp.token].item()
                        except Exception as e:  # noqa: BLE001
                            print(f"  [e4b] T-Sensor logprob-Extraktion fehlgeschlagen ({e})")
                    yield f"data: {json.dumps(frame, ensure_ascii=False)}\n\n"
            yield "data: [DONE]\n\n"
            # Prompt+Generierung als Cache fuer den naechsten Turn ablegen (Key =
            # volle Token-Sequenz). Defensiv — Insert-Fehler darf den Turn nicht kippen.
            if lru_tokens is not None:
                try:
                    _LRU.insert_cache(MODEL_ID, lru_tokens + gen_tokens, kwargs["prompt_cache"])
                except Exception as e:  # noqa: BLE001
                    print(f"  [e4b] prompt-cache insert failed ({e})")
        finally:
            _GEN_LOCK.release()

    return StreamingResponse(gen(), media_type="text/event-stream")


# ── T-Sensor (Verhör-Detektor): Prefill-Echo-Score-Endpoint ──────────────────
# Additiv, eigener Endpoint, rührt an KEINEM Bestandscode. Reiner Teacher-
# Forcing-Forward-Pass über req.text — KEIN Sampler, KEINE Generation, KEIN
# KV-Cache wird fortgeschrieben (cache=None). Verifiziert (mlx-lm 0.31.2,
# mlx_lm/models/gemma4_text.py): Attention.__call__ setzt bei cache=None
# offset=0 und ueberspringt cache.update_and_fetch (Zeile 247 + 259-260) —
# der Forward-Pass ist damit zustandslos (kein Seiteneffekt auf _model).
# create_attention_mask (models/base.py:45-55) liefert bei cache=None den
# kausalen (bzw. bei sliding-Layern kausal+Fenster) Maskenpfad — exakt wie
# beim echten Prefill, nur ohne KV-Persistenz.
def _score_text(text: str) -> dict:
    """Vektorisierter Teacher-Forcing-Score: pro Token surprisal = -logprob (ln).

    BOS wird manuell vorangestellt: dieser Tokenizer hat add_bos_token=False
    (tokenizer_config.json, per pip-venv verifiziert) — ohne BOS würde das
    erste reale Token kontextlos (statt wie im Training ab <bos>) bewertet.
    "tokens" im Ergebnis sind die TOKEN-IDs des Transkripts (ohne das
    synthetische BOS) — kein Zusatz-Decode pro Token (Perf, ~10-30ms-Ziel).
    """
    t0 = time.time()
    ids = [int(t) for t in _tok.encode(text, add_special_tokens=False)] if text else []
    bos_id = getattr(_tok, "bos_token_id", None)
    full_ids = ([int(bos_id)] + ids) if bos_id is not None else ids
    # Zum Scoren braucht jedes Token mind. 1 Vorgänger-Token als Kontext — bei
    # < 2 Tokens (leerer Text, oder 1 Token UND kein BOS auflösbar) gibt es
    # nichts zu bewerten. Ehrliches leeres Ergebnis statt Crash auf 0-Länge.
    if len(full_ids) < 2:
        return {"tokens": [], "logprobs": [], "mean_surprisal": 0.0,
                "max_surprisal": 0.0, "token_count": 0,
                "ms": int((time.time() - t0) * 1000)}
    _GEN_LOCK.acquire()
    try:
        _ensure_gen_stream()  # T372: GPU-Stream im Worker-Thread gültig machen
        with mx.stream(_gen_stream_tls.stream):
            inp = mx.array(full_ids)[None]
            logits = _model(inp, cache=None)  # (1, L, vocab) — reiner Forward-Pass
            logprobs_all = logits - mx.logsumexp(logits, axis=-1, keepdims=True)
            # Position i (0..L-2) sagt das Token an Position i+1 voraus →
            # targets = full_ids[1:]. Mit aufgelöstem BOS ist das GENAU `ids`
            # (voller Kontext); ohne BOS (bos_id is None, full_ids == ids)
            # fehlt dem allerersten Token der Vorgänger → nur ids[1:] bewertet.
            pred = logprobs_all[0, :-1, :]
            targets = mx.array(full_ids[1:])
            token_logprobs = mx.take_along_axis(
                pred, targets[:, None], axis=-1
            ).squeeze(-1)
            mx.eval(token_logprobs)
        lp_list = [float(x) for x in token_logprobs.tolist()]
    finally:
        _GEN_LOCK.release()
    scored_ids = full_ids[1:]
    surprisal = [-lp for lp in lp_list]
    return {
        "tokens": scored_ids,
        "logprobs": lp_list,
        "mean_surprisal": sum(surprisal) / len(surprisal),
        "max_surprisal": max(surprisal),
        "token_count": len(scored_ids),
        "ms": int((time.time() - t0) * 1000),
    }


@app.post("/v1/score")
def score(req: ScoreRequest):
    return _score_text(req.text)


def _warmup():
    """Erster Inferenz-Call kompiliert Metal-Kernels (bis ~20s) — vorab abfangen."""
    global _loaded
    t = time.time()
    p = build_prompt([Msg(role="user", content="Sag kurz Hallo.")])
    for _ in stream_generate(_model, _tok, p, max_tokens=4):
        break
    _loaded = True
    print(f"  ✓ warmup fertig in {time.time()-t:.1f}s — e4b ready")


def _clamp_wired_bytes(mb: int) -> int:
    """T136: MB → geclampte Bytes gegen den System-wired-Limit. 0 = aus/ungültig."""
    if mb <= 0:
        return 0
    try:
        # mx.metal.device_info ist deprecated → top-level mx.device_info.
        info = mx.device_info() if hasattr(mx, "device_info") else mx.metal.device_info()
        sys_wired = int(info.get("max_recommended_working_set_size", 0))
        want = mb * 1024 * 1024
        # Doku: "wired limit should remain strictly less than the total memory size"
        # und "larger than system wired limit is an error" → hart clampen.
        if sys_wired > 0 and want >= sys_wired:
            want = sys_wired - (64 * 1024 * 1024)  # 64 MB Sicherheitsabstand
            print(f"  [e4b] wired {mb} MB > system-limit {sys_wired//1024//1024} MB "
                  f"→ geclamped auf {want//1024//1024} MB")
        return want if want > 0 else 0
    except Exception as e:  # noqa: BLE001
        print(f"  [e4b] T136 device_info/clamp fehlgeschlagen ({e}) → wired aus")
        return 0


def _set_wired(want_bytes: int, reason: str) -> None:
    """Setzt mx.set_wired_limit unter _GEN_LOCK (nie mitten in einem generate).

    Defensiv: jeder Fehler fällt still zurück. Aktualisiert `_wired_active`.
    """
    global _wired_active
    # _GEN_LOCK: wired NIE mid-stream verändern (B-083 — Metal-Config während
    # aktivem Command-Buffer). Timeout → diesen Zyklus überspringen, nächster Poll retry.
    got = _GEN_LOCK.acquire(timeout=30)
    if not got:
        print(f"  [e4b] T170 wired '{reason}': _GEN_LOCK busy → skip, retry nächster Poll")
        return
    try:
        prev = mx.set_wired_limit(want_bytes)
        _wired_active = want_bytes > 0
        print(f"  ✓ [e4b] T170 wired '{reason}': {want_bytes//1024//1024} MB "
              f"(vorher {int(prev)//1024//1024} MB)")
    except Exception as e:  # noqa: BLE001
        print(f"  [e4b] T170 set_wired_limit '{reason}' fehlgeschlagen ({e})")
    finally:
        _GEN_LOCK.release()


def _apply_wired_limit(mb: int) -> None:
    """T136: bis zu `mb` MB Gewichte wired pinnen (default OFF, mb=0 → no-op).

    Berechnet den geclampten Ziel-Pin einmalig (global `_wired_want_bytes`) und
    setzt ihn. Der T170-Monitor nutzt denselben Ziel-Wert für Release/Reapply.
    """
    global _wired_want_bytes
    _wired_want_bytes = _clamp_wired_bytes(mb)
    if _wired_want_bytes <= 0:
        return
    _set_wired(_wired_want_bytes, "initial")


def _read_memorystatus_level() -> int:
    """sysctl kern.memorystatus_level (0..100, höher = mehr freier RAM). -1 = Lesefehler."""
    try:
        import subprocess
        out = subprocess.run(
            ["sysctl", "-n", "kern.memorystatus_level"],
            capture_output=True, text=True, timeout=3,
        )
        return int(out.stdout.strip())
    except Exception:  # noqa: BLE001
        return -1


def _residency_monitor() -> None:
    """T170 Auto-Release-Guard: pinnt e4b unter Druck frei, re-applied bei Erholung.

    Felix-Bedingung aus dem Veto-Aufheben (Iter-129): wired e4b ist nur dann sicher,
    wenn unter akutem Speicher-Druck (memorystatus < RELEASE) der Pin von selbst
    fällt — sonst könnte 4,6 GB wired einen Live-Voice-Peak (STT+TTS) ersticken.
    Hysterese (REAPPLY > RELEASE) gegen Flattern. Läuft nur bei aktivem Feature.
    """
    global _wired_last_lvl
    print(f"  ▶ [e4b] T170 Residency-Monitor: release<{_WIRED_RELEASE_LVL} "
          f"reapply≥{_WIRED_REAPPLY_LVL} poll={_WIRED_POLL_S}s")
    while True:
        try:
            time.sleep(_WIRED_POLL_S)
            lvl = _read_memorystatus_level()
            _wired_last_lvl = lvl
            if lvl < 0:
                continue
            if _wired_active and lvl < _WIRED_RELEASE_LVL:
                print(f"  ⚠ [e4b] T170 Druck (memorystatus={lvl} < {_WIRED_RELEASE_LVL}) "
                      f"→ wired RELEASE (gib Pages frei für Voice-Peak)")
                _set_wired(0, f"release@lvl{lvl}")
            elif (not _wired_active) and lvl >= _WIRED_REAPPLY_LVL:
                print(f"  ✓ [e4b] T170 erholt (memorystatus={lvl} ≥ {_WIRED_REAPPLY_LVL}) "
                      f"→ wired REAPPLY {_wired_want_bytes//1024//1024} MB")
                _set_wired(_wired_want_bytes, f"reapply@lvl{lvl}")
        except Exception as e:  # noqa: BLE001
            # Monitor darf den Server NIE killen.
            print(f"  [e4b] T170 Monitor-Zyklus-Fehler ({e}) → weiter")


def _touch_weights() -> bool:
    """T170 Touch-Loop: Mini-Forward (1 Token), der ALLE Transformer-Gewichte berührt
    → hält sie „recently accessed", damit der OS sie nicht idle zu Disk-Swap demotet.

    Try-lock auf _GEN_LOCK (non-blocking): läuft ein echter Turn, ist e4b eh warm →
    skip. Defensiv: jeder Fehler wird verschluckt. Gibt True zurück, wenn getoucht.
    """
    global _touch_count, _touch_skips, _last_touch_ts
    if not _loaded or _model is None:
        return False
    if not _GEN_LOCK.acquire(blocking=False):
        _touch_skips += 1
        return False  # echter Turn läuft → schon warm
    try:
        _ensure_gen_stream()  # T372: auch der Touch-Thread braucht einen gültigen Stream
        # 1 Token genügt: der Forward-Pass liest jede Layer-Gewichtsmatrix einmal.
        for _ in stream_generate(_model, _tok, "Hi", max_tokens=1):
            break
        _touch_count += 1
        _last_touch_ts = time.time()
        return True
    except Exception as e:  # noqa: BLE001
        print(f"  [e4b] T170 Touch fehlgeschlagen ({e}) → weiter")
        return False
    finally:
        _GEN_LOCK.release()


def _touch_loop() -> None:
    """T170 Touch-Loop-Thread (Iter-129d EXPERIMENT): berührt die Gewichte alle
    _TOUCH_LOOP_S Sekunden. Nur aktiv bei _TOUCH_LOOP_S > 0. Defensiv (killt nie)."""
    print(f"  ▶ [e4b] T170 Touch-Loop aktiv: alle {_TOUCH_LOOP_S}s ein 1-Token-Forward "
          f"(hält Gewichte warm-resident gg. Idle-Disk-Swap)")
    while True:
        try:
            time.sleep(_TOUCH_LOOP_S)
            t0 = time.time()
            if _touch_weights():
                # nur sporadisch loggen (alle ~10 Touches), sonst Log-Spam
                if _touch_count % 10 == 1:
                    print(f"  · [e4b] T170 Touch #{_touch_count} ({(time.time()-t0)*1000:.0f}ms, "
                          f"skips={_touch_skips}, memstatus={_wired_last_lvl})")
        except Exception as e:  # noqa: BLE001
            print(f"  [e4b] T170 Touch-Loop-Fehler ({e}) → weiter")


def _maybe_patch_ple_cpu(model) -> bool:
    """T141: Per-Layer-Embedding-Gather von Gemma 3n/4 auf den CPU-Stream legen.

    ⚠️ FELIX-VETO (Iter-117, isoliert gemessen) — NICHT bit-identisch.
    Default bleibt OFF. Hintergrund: e4bs `embed_tokens_per_layer` ist eine
    **QuantizedEmbedding** (4-bit). Der vermeintliche „Gather" ist real ein
    Dequantize, und MLX' Quant-Dequant ist CPU vs GPU NICHT bit-gleich
    (gemessen: max-abs-diff 0,023 nach *scale → kippt nach ~10 Tokens ein
    argmax → Token-Divergenz, Prompt P04 im Verify). T141-Akzeptanzkriterium 1
    („bit-identische Logits, sonst STOP") ist damit verletzt. Zusätzlich: der
    Standalone-Compute-Vorteil (CPU 0,08ms vs GPU 0,21ms/Token) verschwindet
    end-to-end im Rauschen (tok/s Δ 0,0%, TTFT Δ -2,8%) und der GPU-Stream-RAM
    sinkt NICHT (Δ peak active 0 MB — MLX paginiert nicht pro Row, Ticket-Physik).
    → Vertagt; ein verlustfreier Weg bräuchte upstream einen dequant-festen
    CPU-Gather oder file-backed mmap-Residenz (Stufe b / Upstream-Patch).

    Dieser Helper bleibt als hart-vergateter, default-OFF-Stub: er erkennt das
    Gemma-Text-Modell robust (named_modules, nicht fester Attribut-Pfad — das
    e4b-Quant lädt als multimodales gemma4.Model mit Text unter
    language_model.model), fällt bei jedem anderen Modell hart auf OFF
    (Noa-Caveat) und WARNT laut, dass das Flag bit-Identität bricht.
    """
    if not _PLE_CPU_ON:
        return False
    try:
        from mlx_lm.models.gemma4_text import Gemma4TextModel
    except Exception as e:  # noqa: BLE001
        print(f"  [e4b] T141 PLE-CPU: gemma4_text nicht importierbar ({e}) → OFF")
        return False
    # Robuste Modell-Identität: irgendein Submodul muss ein Gemma4TextModel MIT
    # per-layer-embedding sein. (e4b-Quant: language_model.model; model.model=None.)
    target = None
    try:
        for _name, sub in model.named_modules():
            if isinstance(sub, Gemma4TextModel) and \
                    getattr(sub, "embed_tokens_per_layer", None) is not None:
                target = sub
                break
    except Exception as e:  # noqa: BLE001
        print(f"  [e4b] T141 PLE-CPU: Modell-Scan fehlgeschlagen ({e}) → OFF")
        return False
    if target is None:
        print("  [e4b] T141 PLE-CPU: kein Gemma4TextModel mit per-layer-embedding "
              "→ hartes OFF (Noa-Caveat: anderes Modell)")
        return False
    if getattr(Gemma4TextModel, "_hoshi_ple_cpu_patched", False):
        print("  [e4b] T141 PLE-CPU: bereits gepatcht → skip")
        return True

    print("  ⚠️ [e4b] T141 PLE-CPU: AKTIVIERT trotz Felix-Veto — dieses Flag bricht "
          "bit-Identität (QuantizedEmbedding-Dequant CPU≠GPU). Nur für Experimente!")

    _orig = Gemma4TextModel._get_per_layer_inputs

    def _cpu_get_per_layer_inputs(self, input_ids, input_embeddings=None):
        # Verlagert den (quantisierten) per-layer-embedding-Dequant auf mx.cpu.
        # NICHT bit-identisch zum GPU-Pfad — siehe Veto-Docstring.
        with mx.stream(mx.cpu):
            return _orig(self, input_ids, input_embeddings)

    Gemma4TextModel._get_per_layer_inputs = _cpu_get_per_layer_inputs
    Gemma4TextModel._hoshi_ple_cpu_patched = True
    print("  ✓ [e4b] T141 PLE-CPU-Stream-Offload aktiv (per-layer-embedding → mx.cpu)")
    return True


def main():
    global _model, _tok, MODEL_ID
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default=MODEL_ID)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=8041)
    args = ap.parse_args()
    MODEL_ID = args.model
    print(f"▶ server_e4b: lade {MODEL_ID} …")
    t = time.time()
    _model, _tok = _load_model(MODEL_ID)  # einziger Lade-Aufruf (s. _load_model-Docstring)
    print(f"  ✓ geladen in {time.time()-t:.1f}s; warmup …")
    # D1b: Öffner-Ban- + XTC-Schutz-Token-IDs EINMAL zur Ladezeit auflösen
    #   (defensiv in sich — Fehler loggen nur, Features werden dann ignoriert).
    _resolve_d1b_tokens()
    # T141 (default OFF): Per-Layer-Embedding-Gather auf CPU-Stream — VOR dem warmup,
    #   damit schon der erste eval den CPU-Pfad nimmt. Hartes OFF bei nicht-gemma.
    _maybe_patch_ple_cpu(_model)
    # T136 (default OFF, _WIRED_MB=0): Gewichte wired pinnen, nach dem Load.
    _apply_wired_limit(_WIRED_MB)
    # T170 (Iter-129): Auto-Release-Guard — nur wenn wired aktiv. Felix-Bedingung
    #   fürs Veto-Aufheben: unter Druck löst sich der Pin selbst (Director-Gegengewicht).
    if _wired_want_bytes > 0:
        threading.Thread(target=_residency_monitor, name="t170-residency",
                         daemon=True).start()
    # T170 Touch-Loop (Iter-129d EXPERIMENT, default OFF): hält Gewichte warm gg. Idle-Cold.
    if _TOUCH_LOOP_S > 0:
        threading.Thread(target=_touch_loop, name="t170-touch", daemon=True).start()
    _warmup()
    uvicorn.run(app, host=args.host, port=args.port, log_level="warning")


if __name__ == "__main__":
    main()
