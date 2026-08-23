#!/usr/bin/env python3
"""tools/kvfreeze_coherence_ab.py — Kohärenz-A/B für HOSHI_E4B_PERSONA_KV_FREEZE
(0.8-Port des 0.5-Vorbilds `tools/kvfreeze_coherence_ab.py`, Iter-135/137/T168).

WARUM ANDERS ALS DAS 0.5-VORBILD: das 0.5-Skript lud das Modell SELBST in einem
zweiten Prozess und implementierte server_e4b.py's _persona_fetch-Mechanik NACH —
zwei Kopien derselben Logik, die auseinanderlaufen können. Dieses Skript spricht
STATTDESSEN per HTTP (/v1/chat, SSE) gegen den ECHTEN laufenden Brain-Sidecar
(sidecars/brain/server.py) — exakt der Pfad, der live auch bedient wird, kein
Nachbau. Kosten: HOSHI_E4B_PERSONA_KV_FREEZE ist ein STARTUP-Env (server.py liest
es einmal beim Import), nicht per-Request umschaltbar → OFF und ON sind ZWEI
SEQUENZIELLE PÄSSE gegen denselben Prozess, der dazwischen mit anderem Env neu
gestartet wird (s. vault/tracks/RUNBOOK-mlx-fenster-2026-08-22.md). Das ist
RAM-sicherer als ein zweites, gleichzeitig geladenes Modell (16-GB-Wand) — kein
Simultan-A/B, aber ausreichend fuer einen Vorher/Nachher-Vergleich EINER Instanz.

PROMPT-SET: die 12 Ein-Turn-Fälle aus tools/command-replay/corpus/draft-v1.jsonl
(Andis approved Verstümmelungs-Korpus, 21.08.) + 5 Alltags-Turns (unten,
ALLTAGS_TURNS) — Auftrag ORDER-mlx-upgrade-vorbereitung-2026-08-22.md Punkt 4.
Der 13. Korpus-Eintrag (`golden-area-clarify-cycle-01`, Mehrturn mit
pending_clarify-State) wird bewusst NICHT verwendet: dessen Turn-State ist
TurnOrchestrator-Sache (Kotlin), nicht Brain-Kohärenz — ein Einzel-Turn-Test
würde die pending_clarify-Semantik falsch simulieren.

WAS DIESES SKRIPT NICHT TUT: es urteilt NICHT über Kohärenz (Auftrag: "Urteil
fällt danach ein Judge-Pod, nicht du"). Es sammelt nur Antwort-Paare + Timing in
EINE JSONL-Zeile pro Prompt und schreibt roh raus.

RATE-STELLEN (VERDACHT, nicht verifiziert — ins Urteil des Judge-Pods einpreisen):
  R1 — SYNTH_PERSONA (unten) ist eine ERSATZ-Persona, NICHT der echte, byte-genaue
       Hoshi-Prefix aus PersonaService.kt (Backend, Kotlin — dieses Skript hat
       keinen Zugriff/Auftrag darauf). Sie ist bewusst lang genug (deutlich über
       HOSHI_E4B_PERSONA_MIN_TOKENS=400), um den Freeze-Pfad zuverlässig zu
       triggern, aber INHALTLICH nicht Hoshis echte Stimme. TTFT-Speedup-Zahlen
       bleiben aussagekräftig (reiner Prefill-Mechanismus, persona-text-agnostisch),
       der Kohärenz-VERGLEICH testet aber eine Ersatz-Persona.
  R2 — Kein Sampling-Seed-Parameter im /v1/chat-Contract (MlxOmniLlmClient.kt) →
       OFF- und ON-Pass sind bei temp>0 NICHT deterministisch gegeneinander
       isolierbar; ein Antwort-Unterschied kann Sampling-Rauschen ODER ein
       echter Freeze-Drift sein. Abhilfe nur teilweise: --temp 0.0 (greedy)
       macht den Vergleich determinismus-näher, weicht aber vom Produktions-
       Sampling (temp 0.7) ab — beide Läufe sind im Runbook vorgesehen.
  R3 — Sequenzielle Pässe (nicht simultan) heißt: OFF und ON laufen nicht gegen
       exakt denselben Prozesszustand (Touch-Loop/GC/Wired-Level können zwischen
       den Pässen leicht driften). Für die TTFT-Zahl ist das ein Rauschfaktor,
       kein Bias in eine Richtung.

Aufruf (zwei Pässe, s. Runbook für die vollen Copy-Paste-Kommandos):
    # Pass 1 — Server läuft mit HOSHI_E4B_PERSONA_KV_FREEZE=0 (oder unset)
    python3 tools/kvfreeze_coherence_ab.py --pass off --state /tmp/kvfreeze-off.json

    # <Hand stoppt den Server, setzt HOSHI_E4B_PERSONA_KV_FREEZE=1, startet neu>

    # Pass 2 — Server läuft mit HOSHI_E4B_PERSONA_KV_FREEZE=1
    python3 tools/kvfreeze_coherence_ab.py --pass on --state /tmp/kvfreeze-off.json \\
        --out vault/tracks/kvfreeze-coherence-pairs-<datum>.jsonl

Lädt/startet NICHTS selbst — reiner HTTP-Client gegen einen bereits laufenden
Brain-Sidecar. Kein Modell-Load in diesem Prozess (stdlib urllib, keine mlx-Importe).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

DEFAULT_URL = "http://127.0.0.1:8041/v1/chat"
DEFAULT_HEALTH_URL = "http://127.0.0.1:8041/health"
CORPUS_PATH = (
    Path(__file__).resolve().parent / "command-replay" / "corpus" / "draft-v1.jsonl"
)

# ── 5 Alltags-Turns (Auftrag Punkt 4) — bewusst KEINE Smart-Home-Kommandos
# (die liefert schon der Korpus), sondern generische Alltags-/Persona-Turns:
# Smalltalk, Wissens-Snack, Aufgaben-Bitte, meinungsartige Frage, Abschluss.
ALLTAGS_TURNS = [
    {"id": "alltag-01", "language": "DE", "text": "Wie war dein Tag bisher?"},
    {"id": "alltag-02", "language": "DE",
     "text": "Erzähl mir einen kurzen Fakt über den Saturn."},
    {"id": "alltag-03", "language": "DE",
     "text": "Kannst du mir helfen, eine kurze E-Mail an einen Kollegen zu formulieren?"},
    {"id": "alltag-04", "language": "DE", "text": "Was hältst du vom Wetter heute?"},
    {"id": "alltag-05", "language": "DE", "text": "Danke dir, das war's erstmal."},
]

# ── SYNTH_PERSONA — Ersatz-Systemprompt, s. Rate-Stelle R1 im Docstring oben.
# GEMESSEN (nicht geschätzt): mit dem echten e4b-Tokenizer (lokaler HF-Cache-
# Snapshot deb1db71…, mlx_lm.tokenizer_utils.load(), reine Vokabular-Dateien —
# KEIN Modell-Load) ergibt dieser Text 452 Tokens, der volle Chat-Template-Turn
# (System+User+Preamble) 470 — beides sicher über HOSHI_E4B_PERSONA_MIN_TOKENS
# =400. Identisch bei JEDEM Request in
# diesem Skript gesendet — genau das Muster, das den Freeze in server.py's
# _persona_fetch (Common-Prefix zweier aufeinanderfolgender Turns) auslöst.
SYNTH_PERSONA = (
    "Du bist Hoshi, ein deutschsprachiger Sprachassistent, der in einem privaten "
    "Zuhause lebt. Du sprichst warm, direkt und unaufgeregt, wie eine vertraute "
    "Person, nicht wie ein Konzern-Chatbot. Du hältst Antworten kurz und mündlich "
    "gut sprechbar, vermeidest Aufzählungszeichen und Markdown, und du erfindest "
    "niemals Fakten, Gerätezustände oder Handlungen, die du nicht wirklich "
    "ausgeführt hast. Wenn dir Informationen fehlen, sagst du das ehrlich, statt "
    "zu raten. Du kennst Smart-Home-Geräte, Räume und Alltagsroutinen des "
    "Haushalts, aber du erwähnst nur, was dir im Kontext tatsächlich gegeben "
    "wurde. Bei Smart-Home-Anfragen antwortest du knapp und handlungsorientiert; "
    "bei Wissensfragen erklärst du in ein bis drei Sätzen, ohne abzuschweifen; "
    "bei Smalltalk bleibst du freundlich und kurz, ohne aufdringlich zu wirken. "
    "Du unterbrichst dich nicht mit Meta-Kommentaren über dich selbst als "
    "Sprachmodell und du wiederholst die Frage der Nutzerin oder des Nutzers "
    "nicht wörtlich, bevor du antwortest. Deine Antworten sollen sich so "
    "anhören, als kämen sie von jemandem, der zuhört und mitdenkt, nicht als "
    "generischer Assistent. Du bleibst höflich, aber du musst nicht bei jedem "
    "Satz danken oder dich entschuldigen. Wenn eine Anfrage unklar ist, stellst "
    "du eine kurze Rückfrage, statt zu raten oder eine falsche Handlung "
    "vorzutäuschen. Du behältst über ein Gespräch hinweg denselben Ton bei und "
    "wechselst ihn nicht abrupt zwischen den Turns. Sicherheit und Privatsphäre "
    "des Haushalts haben für dich Vorrang vor Bequemlichkeit: du gibst niemals "
    "sensible Informationen ungefragt an Dritte weiter und du handelst nur "
    "innerhalb der Geräte und Räume, die dir explizit bekannt gemacht wurden. "
    "Dieser Systemtext ist eine Ersatz-Persona für einen technischen A/B-Test "
    "des Prefill-Cache-Mechanismus und keine vollständige Abbildung von Hoshis "
    "echter, produktiver Persona."
)


def load_corpus_cases() -> list[dict]:
    """12 Ein-Turn-Fälle aus draft-v1.jsonl; überspringt Mehrturn-Einträge
    (haben `turns` statt `text` auf oberster Ebene) mit einer Log-Zeile."""
    cases = []
    if not CORPUS_PATH.exists():
        print(f"WARN: Korpus fehlt ({CORPUS_PATH}) — fahre nur mit Alltags-Turns fort.",
              file=sys.stderr)
        return cases
    with CORPUS_PATH.open(encoding="utf-8") as f:
        for line_no, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError as e:
                print(f"WARN: Korpus-Zeile {line_no} nicht JSON-parsebar: {e}",
                      file=sys.stderr)
                continue
            if "text" not in rec:
                print(f"INFO: Korpus-Zeile {line_no} ('{rec.get('id', '?')}') "
                      f"übersprungen — Mehrturn-Eintrag (turns statt text), "
                      f"gehört zur Orchestrator-/Clarify-Ebene, nicht Brain-Kohärenz.",
                      file=sys.stderr)
                continue
            cases.append({
                "id": rec.get("id", f"corpus-line-{line_no}"),
                "language": rec.get("language", "DE"),
                "text": rec["text"],
                "source": "corpus",
                "mutation": (rec.get("mutation") or {}).get("kind"),
            })
    return cases


def build_cases() -> list[dict]:
    cases = load_corpus_cases()
    for turn in ALLTAGS_TURNS:
        cases.append({**turn, "source": "alltag", "mutation": None})
    return cases


def fetch_health(url: str, timeout: float = 3.0) -> dict:
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception as e:  # noqa: BLE001
        return {"error": f"{type(e).__name__}: {e}"}


def post_chat(url: str, system_text: str, user_text: str, max_tokens: int,
              temperature: float, timeout: float) -> dict:
    """POST /v1/chat, SSE einsammeln (identisches Parsing wie pipeline/bench-brain.sh
    und sidecars/brain/probe-next.sh: `data: {"delta": "..."}`-Zeilen bis [DONE])."""
    messages = [
        {"role": "system", "content": system_text},
        {"role": "user", "content": user_text},
    ]
    body = json.dumps({
        "messages": messages,
        "sessionId": "kvfreeze-ab",
        "userId": "kvfreeze-ab",
        "max_tokens": max_tokens,
        "temperature": temperature,
        "stream": True,
    }).encode("utf-8")
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
        method="POST",
    )
    text_parts: list[str] = []
    ttft_ms = None
    err = None
    t0 = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            for raw in resp:
                line = raw.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                payload = line[len("data:"):].strip()
                if not payload or payload == "[DONE]":
                    if payload == "[DONE]":
                        break
                    continue
                try:
                    frame = json.loads(payload)
                except json.JSONDecodeError:
                    continue
                delta = frame.get("delta", "")
                if delta:
                    if ttft_ms is None:
                        ttft_ms = int((time.monotonic() - t0) * 1000)
                    text_parts.append(delta)
    except urllib.error.URLError as e:
        err = f"URLError: {getattr(e, 'reason', e)}"
    except Exception as e:  # noqa: BLE001
        err = f"{type(e).__name__}: {e}"
    total_ms = int((time.monotonic() - t0) * 1000)
    return {
        "text": "".join(text_parts).strip(),
        "ttft_ms": ttft_ms,
        "total_ms": total_ms,
        "error": err,
    }


def run_pass(cases: list[dict], url: str, max_tokens: float, temperature: float,
             timeout: float) -> list[dict]:
    results = []
    for case in cases:
        r = post_chat(url, SYNTH_PERSONA, case["text"], max_tokens, temperature, timeout)
        row = {**case, **r}
        empty = not row["text"]
        print(f"  [{case['id']}] ttft={row['ttft_ms']}ms total={row['total_ms']}ms "
              f"{'EMPTY/' + str(row['error']) if empty else row['text'][:70]!r}")
        results.append(row)
    return results


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                  formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--pass", dest="which_pass", choices=["off", "on"], required=True,
                     help="off = erster Lauf (Server ohne Freeze gestartet), "
                          "on = zweiter Lauf (Server MIT HOSHI_E4B_PERSONA_KV_FREEZE=1 "
                          "neu gestartet) — merged mit --state in --out")
    ap.add_argument("--url", default=DEFAULT_URL, help=f"Default {DEFAULT_URL}")
    ap.add_argument("--health-url", default=DEFAULT_HEALTH_URL,
                     help=f"Default {DEFAULT_HEALTH_URL}")
    ap.add_argument("--state", default="/tmp/kvfreeze-ab-off-pass.json",
                     help="Zwischenablage der OFF-Pass-Rohdaten (pass=off: Ziel zum "
                          "Schreiben; pass=on: Quelle zum Mergen)")
    ap.add_argument("--out", default=None,
                     help="JSONL-Ausgabe der gepaarten Ergebnisse (nur bei --pass on; "
                          "Default: vault/tracks/kvfreeze-coherence-pairs-<ts>.jsonl)")
    ap.add_argument("--max-tokens", type=int, default=96)
    ap.add_argument("--temperature", type=float, default=0.7,
                     help="0.7 = Produktions-Sampling (Default); 0.0 = greedy, "
                          "determinismus-näherer Vergleich (s. Rate-Stelle R2)")
    ap.add_argument("--timeout", type=float, default=40.0)
    args = ap.parse_args()

    print(f"Health VOR dem Lauf ({args.health_url}):")
    print(json.dumps(fetch_health(args.health_url), ensure_ascii=False, indent=2))

    cases = build_cases()
    print(f"\n{len(cases)} Prompts (Korpus + Alltags-Turns), "
          f"pass={args.which_pass}, temp={args.temperature}, max_tokens={args.max_tokens}\n")

    results = run_pass(cases, args.url, args.max_tokens, args.temperature, args.timeout)

    print(f"\nHealth NACH dem Lauf ({args.health_url}):")
    health_after = fetch_health(args.health_url)
    print(json.dumps(health_after, ensure_ascii=False, indent=2))
    persona_kv = health_after.get("persona_kv", {})

    state_path = Path(args.state)
    if args.which_pass == "off":
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state_path.write_text(json.dumps({
            "results": results, "persona_kv_health": persona_kv,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nOFF-Pass gespeichert: {state_path}")
        print("Nächster Schritt: Server mit HOSHI_E4B_PERSONA_KV_FREEZE=1 neu starten, "
              "dann --pass on --state " + str(state_path) + " aufrufen.")
        return 0

    # --pass on: OFF-State laden, mergen, JSONL schreiben.
    if not state_path.exists():
        print(f"FATAL: --state {state_path} fehlt — zuerst --pass off ausführen.",
              file=sys.stderr)
        return 1
    off_data = json.loads(state_path.read_text(encoding="utf-8"))
    off_by_id = {r["id"]: r for r in off_data["results"]}

    out_path = Path(args.out) if args.out else Path(
        f"vault/tracks/kvfreeze-coherence-pairs-{time.strftime('%Y%m%d-%H%M%S')}.jsonl")
    out_path.parent.mkdir(parents=True, exist_ok=True)

    n_pairs = 0
    with out_path.open("w", encoding="utf-8") as f:
        for row in results:
            off_row = off_by_id.get(row["id"])
            if off_row is None:
                print(f"WARN: '{row['id']}' hat keinen OFF-Gegenpart im State — "
                      "übersprungen.", file=sys.stderr)
                continue
            pair = {
                "id": row["id"],
                "source": row["source"],
                "mutation": row.get("mutation"),
                "language": row.get("language"),
                "off_text": off_row["text"],
                "off_ttft_ms": off_row["ttft_ms"],
                "off_total_ms": off_row["total_ms"],
                "off_error": off_row["error"],
                "on_text": row["text"],
                "on_ttft_ms": row["ttft_ms"],
                "on_total_ms": row["total_ms"],
                "on_error": row["error"],
            }
            f.write(json.dumps(pair, ensure_ascii=False) + "\n")
            n_pairs += 1

    print(f"\n{n_pairs} Paare geschrieben: {out_path}")
    print(f"OFF persona_kv-Health (zur Kontrolle, sollte enabled=false zeigen): "
          f"{off_data.get('persona_kv_health')}")
    print(f"ON  persona_kv-Health (sollte enabled=true, hits>0 zeigen, sonst hat "
          f"der Freeze nicht gegriffen): {persona_kv}")
    print("\nDIESES SKRIPT URTEILT NICHT über Kohärenz — das ist Aufgabe eines "
          "separaten Judge-Pods (Auftrag). Rate-Stellen R1-R3 im Docstring beachten.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
