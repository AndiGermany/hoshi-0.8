#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""measure-brain-ab.py — wiederverwendbare Sampling-A/B-Messung gegen das Hoshi-Brain.

Spec: vault/tracks/prep/PREP-xtc-messplan.md (Sektion "S2: Spec").
Metrik-Definitionen 1:1 aus vault/tracks/d1-ab-messung-2026-07-02.md.
Nur stdlib (urllib/json/math). Arme kommen als JSON-Config; deren `fields`
werden FLACH in den Request-Body gemerged — dadurch ist das Skript für jeden
künftigen Sampling-A/B wiederverwendbar (xtc_*, opener_bias, min_p,
presence_penalty, … ohne Codeänderung).

Modi:
  run      Arme × Prompts strikt sequenziell messen (frische sessionId je
           Request, SSE IMMER voll konsumieren — nie `| head`, nie Abbruch
           mitten im Stream), raw.jsonl PERSISTENT schreiben (Lehre aus D1:
           nichts ins flüchtige Scratchpad), danach automatisch analyze.
  analyze  Metriken + Vorzeichentests + Byte-Identitäts-Diagnostik auf einem
           raw.jsonl (auch standalone auf alten Läufen re-runnbar).

Beispiele:
  python3 tools/measure-brain-ab.py run --config tools/measure-brain-ab.example.json
  python3 tools/measure-brain-ab.py run --config … --limit 2 --arms default,xtc
  python3 tools/measure-brain-ab.py run --config … --smoke        # genau 1 Request
  python3 tools/measure-brain-ab.py analyze --raw <dir-oder-raw.jsonl>

Exit-Codes: 0 = ok · 2 = Byte-Identitäts-Warnung („Arm wirkt nicht") · 1 = harter Fehler.
Recovery bei Brain-Hänger: bin/hoshi heal (Brain-WEDGE-Lehre).
"""

import argparse
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_RESULTS_DIR = os.path.join(REPO_ROOT, "training", "lora-v0", "eval", "results")

# Slop-Öffner exakt wie die D1-Messung (d1-ab-messung-2026-07-02.md):
# Antwort BEGINNT (nach Whitespace/Markdown-Strip) mit einem dieser Strings.
SLOP_OPENERS = ("Gerne", "Natürlich", "Als KI", "Klar!", "Selbstverständlich")
# Volle D1b-Ban-Liste (server_e4b.py _OPENER_WORDS): erstes Wort, case-insensitiv
# (der Server bannt je 4 Case/Space-Varianten) — damit der opener_bias-Arm gegen
# genau das misst, was er bannt.
BANLIST_WORDS = frozenset(
    w.lower() for w in
    ("Gerne", "Natürlich", "Klar", "Selbstverständlich", "Als",
     "Certainly", "Sure", "Of")
)


# ── Metriken (Definitionen = exakt die bestehende D1-Messung) ────────────────

def tokenize(text):
    """Wort-Tokenisierung lowercase (\\w+, unicode-aware)."""
    return re.findall(r"\w+", text.lower())


def repetition_rate(words):
    """Duplizierte 3-Gramme / total (0.0 wenn < 3 Wörter)."""
    grams = list(zip(words, words[1:], words[2:]))
    if not grams:
        return 0.0
    return (len(grams) - len(set(grams))) / len(grams)


def distinct2(words):
    """Unique Bigramme / total (1.0 wenn < 2 Wörter)."""
    bigrams = list(zip(words, words[1:]))
    if not bigrams:
        return 1.0
    return len(set(bigrams)) / len(bigrams)


def strip_lead(text):
    """Whitespace/Markdown-Strip am Antwortanfang (für die Öffner-Checks)."""
    return re.sub(r"^[\s>#*_`~\-]+", "", text)


def slop_opener(text):
    return strip_lead(text).startswith(SLOP_OPENERS)


def opener_banlist(text):
    m = re.match(r"\w+", strip_lead(text))
    return bool(m and m.group(0).lower() in BANLIST_WORDS)


def sign_test(n, k):
    """Exakter zweiseitiger Vorzeichentest (Binomial p=0.5, math.comb).

    n = Paare mit Differenz ≠ 0, k = Erfolge. Rückgabe p (None wenn n=0).
    Verifikation aus der D1-Messung: n=22, k=11 → p=1.00."""
    if n == 0:
        return None
    denom = 2 ** n
    cdf = sum(math.comb(n, i) for i in range(0, k + 1)) / denom
    sf = sum(math.comb(n, i) for i in range(k, n + 1)) / denom
    return min(1.0, 2.0 * min(cdf, sf))


def row_metrics(text):
    words = tokenize(text)
    return {
        "rep": repetition_rate(words),
        "d2": distinct2(words),
        "n_words": len(words),
        "slop": slop_opener(text),
        "ban": opener_banlist(text),
    }


# ── run-Modus ────────────────────────────────────────────────────────────────

def load_prompts(files):
    prompts, seen = [], set()
    for path in files:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                o = json.loads(line)
                if o["id"] in seen:
                    raise SystemExit(f"FEHLER: doppelte Prompt-ID {o['id']!r} ({path})")
                seen.add(o["id"])
                prompts.append(o)
    return prompts


def sse_request(url, body, timeout):
    """POST + SSE-Stream VOLLSTÄNDIG konsumieren (bis EOF, nie mittendrin
    abbrechen, nie head) → (antwort_text, latenz_s_gesamtstream)."""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url, data=data,
        headers={"Content-Type": "application/json", "Accept": "text/event-stream"},
    )
    chunks = []
    t0 = time.monotonic()
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        for raw in resp:  # zeilenweise bis EOF — voller Konsum garantiert
            line = raw.decode("utf-8", "replace").rstrip("\r\n")
            if not line.startswith("data:"):
                continue
            payload = line[5:].strip()
            if payload == "[DONE]":
                continue  # NICHT break: bis EOF weiterlesen
            try:
                obj = json.loads(payload)
            except json.JSONDecodeError:
                continue
            delta = obj.get("delta")
            if isinstance(delta, str):
                chunks.append(delta)
    return "".join(chunks), time.monotonic() - t0


def preflight(health_url, timeout=10):
    with urllib.request.urlopen(health_url, timeout=timeout) as resp:
        health = json.loads(resp.read().decode("utf-8"))
    if not health.get("loaded"):
        raise SystemExit(f"FEHLER Preflight: Brain nicht loaded ({health_url}): {health}")
    return health


def run_mode(args):
    with open(args.config, encoding="utf-8") as fh:
        cfg = json.load(fh)
    prompts = load_prompts(cfg["prompt_files"])
    arms = cfg["arms"]
    baseline = cfg.get("baseline_arm", arms[0]["name"])

    if args.arms:
        wanted = [a.strip() for a in args.arms.split(",")]
        by_name = {a["name"]: a for a in arms}
        missing = [w for w in wanted if w not in by_name]
        if missing:
            raise SystemExit(f"FEHLER: unbekannte Arme {missing} (Config hat {sorted(by_name)})")
        arms = [by_name[w] for w in wanted]
    if args.smoke:
        # Smoke = GENAU 1 Request: erster Prompt × Baseline-Arm, sequenziell,
        # SSE voll konsumiert. Minimale Last aufs live Brain.
        prompts = prompts[:1]
        arms = [a for a in arms if a["name"] == baseline][:1] or arms[:1]
    elif args.limit:
        prompts = prompts[: args.limit]

    ts_tag = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = args.out or os.path.join(DEFAULT_RESULTS_DIR, f"xtcab-{ts_tag}")
    os.makedirs(out_dir, exist_ok=True)
    raw_path = os.path.join(out_dir, "raw.jsonl")

    health = preflight(cfg["health_url"])
    header = {
        "header": True,
        "ts": datetime.now(timezone.utc).isoformat(),
        "base_url": cfg["base_url"],
        "model": health.get("model"),        # Provenienz
        "engine": health.get("engine"),      # Provenienz
        "health": health,
        "temperature": cfg["temperature"],
        "max_tokens": cfg["max_tokens"],
        "baseline_arm": baseline,
        "arms": arms,
        "prompt_files": cfg["prompt_files"],
        "n_prompts": len(prompts),
        "smoke": bool(args.smoke),
    }
    total = len(prompts) * len(arms)
    print(f"[run] Brain ok: model={header['model']} engine={header['engine']} — "
          f"{len(prompts)} Prompts × {len(arms)} Arme = {total} Requests → {raw_path}",
          file=sys.stderr)

    n_ok = n_err = 0
    with open(raw_path, "w", encoding="utf-8") as out:
        out.write(json.dumps(header, ensure_ascii=False) + "\n")
        out.flush()
        done = 0
        # Prompt-major interleaved (Prompt 1: Arm ①②③④, Prompt 2: …) —
        # mittelt Drift/Wärme über Arme statt über Zeit. Strikt sequenziell.
        for p in prompts:
            for arm in arms:
                done += 1
                body = {
                    "sessionId": str(uuid.uuid4()),  # frische Session, keine History
                    "userId": "measure-ab",
                    "stream": True,
                    "messages": [{"role": "user", "content": p["text"]}],
                    "temperature": cfg["temperature"],
                    "max_tokens": cfg["max_tokens"],
                }
                body.update(arm.get("fields", {}))  # flacher Merge → wiederverwendbar
                text, latency, err = "", None, None
                for attempt in (1, 2):  # 1 Retry — NIE den Lauf wegen eines Prompts sterben lassen
                    try:
                        text, latency = sse_request(cfg["base_url"], body, args.timeout)
                        err = None
                        break
                    except Exception as e:  # noqa: BLE001
                        err = f"{type(e).__name__}: {e}"
                        print(f"[run] {p['id']}/{arm['name']} Versuch {attempt} FEHLER: {err}",
                              file=sys.stderr)
                        if attempt == 1:
                            time.sleep(2.0)
                row = {
                    "prompt_id": p["id"],
                    "kategorie": p.get("kategorie", "?"),
                    "arm": arm["name"],
                    "text": text,
                    "n_chars": len(text),
                    "latency_s": round(latency, 3) if latency is not None else None,
                    "ts": datetime.now(timezone.utc).isoformat(),
                    "error": err,
                }
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                out.flush()  # sofort persistent
                if err:
                    n_err += 1
                else:
                    n_ok += 1
                lat = f"{latency:.2f}s" if latency is not None else "—"
                print(f"[run] {done}/{total} {p['id']}×{arm['name']}: "
                      f"{'FEHLER' if err else 'ok'} ({lat}, {len(text)} chars)",
                      file=sys.stderr)

    print(f"[run] fertig: {n_ok}/{total} ok, {n_err} Fehler — raw: {raw_path}", file=sys.stderr)
    if args.smoke:
        rows = read_raw(raw_path)[1]
        r = rows[0] if rows else None
        if r is None or r.get("error"):
            print(f"[smoke] FEHLGESCHLAGEN: {r and r.get('error')}", file=sys.stderr)
            return 1
        print(f"[smoke] ok — 1 Request, SSE voll konsumiert: prompt={r['prompt_id']} "
              f"arm={r['arm']} latency={r['latency_s']}s n_chars={r['n_chars']}")
        print(f"[smoke] Antwortanfang: {r['text'][:160]!r}")
        return 0
    # Nach dem Lauf automatisch analyze auf dem eigenen raw.jsonl:
    return analyze(raw_path, baseline_override=None)


# ── analyze-Modus ────────────────────────────────────────────────────────────

def read_raw(raw_path):
    if os.path.isdir(raw_path):
        raw_path = os.path.join(raw_path, "raw.jsonl")
    header, rows = None, []
    with open(raw_path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            o = json.loads(line)
            if o.get("header"):
                header = o
            else:
                rows.append(o)
    return header, rows


def fmt_p(p):
    return "—" if p is None else f"{p:.2f}" if p >= 0.005 else f"{p:.2g}"


def analyze(raw_path, baseline_override=None):
    if os.path.isdir(raw_path):
        raw_path = os.path.join(raw_path, "raw.jsonl")
    header, rows = read_raw(raw_path)
    baseline = baseline_override or (header or {}).get("baseline_arm") or "default"

    # Gruppieren; Arm-Reihenfolge aus Header (falls da), sonst Auftrittsreihenfolge.
    arm_order = [a["name"] for a in (header or {}).get("arms", [])] or []
    by_arm = {}
    for r in rows:
        by_arm.setdefault(r["arm"], {})[r["prompt_id"]] = r
        if r["arm"] not in arm_order:
            arm_order.append(r["arm"])
    if baseline not in by_arm:
        raise SystemExit(f"FEHLER analyze: Baseline-Arm {baseline!r} nicht in raw "
                         f"(Arme: {sorted(by_arm)}) — --baseline setzen?")

    ok = {arm: {pid: r for pid, r in d.items() if not r.get("error")}
          for arm, d in by_arm.items()}
    met = {arm: {pid: row_metrics(r["text"]) for pid, r in d.items()}
           for arm, d in ok.items()}

    lines = []
    lines.append(f"# A/B-Analyse — {os.path.basename(os.path.dirname(os.path.abspath(raw_path)))}")
    if header:
        lines.append(f"\nProvenienz: model={header.get('model')} · engine={header.get('engine')} · "
                     f"base_url={header.get('base_url')} · temp={header.get('temperature')} · "
                     f"max_tokens={header.get('max_tokens')} · ts={header.get('ts')}")
    lines.append(f"Baseline-Arm: **{baseline}** · raw: `{os.path.abspath(raw_path)}`\n")

    # ── Mittelwerte-Tabelle (Spalten wie d1-ab-messung-2026-07-02.md) ──
    def mean(vals):
        vals = list(vals)
        return sum(vals) / len(vals) if vals else float("nan")

    lines.append("## Mittelwerte je Arm\n")
    lines.append("| Metrik | " + " | ".join(arm_order) + " |")
    lines.append("|---|" + "---|" * len(arm_order))

    def cell_row(label, fn):
        lines.append(f"| {label} | " + " | ".join(fn(a) for a in arm_order) + " |")

    cell_row("Repetitions-Rate (dup. 3-Gramme / total)",
             lambda a: f"{mean(m['rep'] for m in met[a].values()):.4f}")
    cell_row("distinct-2 (unique Bigramme / total)",
             lambda a: f"{mean(m['d2'] for m in met[a].values()):.3f}")
    cell_row("Slop-Öffner-Quote (Gerne/Natürlich/Als KI/Klar!/Selbstverständlich)",
             lambda a: (lambda k, n: f"{k}/{n} ({100*k/n:.1f} %)" if n else "—")(
                 sum(m["slop"] for m in met[a].values()), len(met[a])))
    cell_row("Öffner-Banlist-Quote (volle D1b-Ban-Liste, erstes Wort)",
             lambda a: (lambda k, n: f"{k}/{n} ({100*k/n:.1f} %)" if n else "—")(
                 sum(m["ban"] for m in met[a].values()), len(met[a])))
    cell_row("Mittlere Antwortlänge (Wörter)",
             lambda a: f"{mean(m['n_words'] for m in met[a].values()):.1f}")
    cell_row("Mittlere Latenz (Gesamtstream, s)",
             lambda a: f"{mean(r['latency_s'] for r in ok[a].values() if r['latency_s'] is not None):.2f}")
    cell_row("n ok/total",
             lambda a: f"{len(ok[a])}/{len(by_arm[a])}")

    # ── Öffner-Quote je Kategorie (Köder-Kategorien separat!) ──
    cats = []
    for a in arm_order:
        for r in ok[a].values():
            if r["kategorie"] not in cats:
                cats.append(r["kategorie"])
    koeder = [c for c in cats if c in ("bitte-oeffner", "en-v2")]
    rest = [c for c in cats if c not in koeder]
    lines.append("\n## Öffner-Quote je Kategorie — Banlist (Slop) — Köder-Kategorien zuerst\n")
    lines.append("| Kategorie | " + " | ".join(arm_order) + " |")
    lines.append("|---|" + "---|" * len(arm_order))
    for c in koeder + rest:
        tag = " **(Köder)**" if c in koeder else ""
        cells = []
        for a in arm_order:
            sub = [pid for pid, r in ok[a].items() if r["kategorie"] == c]
            if not sub:
                cells.append("—")
                continue
            b = sum(met[a][pid]["ban"] for pid in sub)
            s = sum(met[a][pid]["slop"] for pid in sub)
            cells.append(f"{b}/{len(sub)} ({s}/{len(sub)})")
        lines.append(f"| {c}{tag} | " + " | ".join(cells) + " |")

    # ── je Arm vs Baseline: Vorzeichentests, Byte-Identität, Ausreißer ──
    warn_arms = []
    base_ok, base_met = ok[baseline], met[baseline]
    for a in arm_order:
        if a == baseline:
            continue
        pair_ids = sorted(set(base_ok) & set(ok[a]))
        lines.append(f"\n## Arm `{a}` vs Baseline `{baseline}` (n Paare = {len(pair_ids)})\n")
        if not pair_ids:
            lines.append("Keine auswertbaren Paare.")
            continue

        # Vorzeichentest Repetitions-Rate (k = Paare mit arm < baseline = arm besser)
        d_rep = [(pid, met[a][pid]["rep"] - base_met[pid]["rep"]) for pid in pair_ids]
        nz = [(pid, d) for pid, d in d_rep if d != 0]
        k_less = sum(1 for _, d in nz if d < 0)
        p_rep = sign_test(len(nz), k_less)
        lines.append(f"- **Vorzeichentest Repetitions-Rate:** {a}<{baseline} in {k_less}, "
                     f"{a}>{baseline} in {len(nz) - k_less}, Gleichstand {len(pair_ids) - len(nz)} "
                     f"→ n={len(nz)}, k={k_less}, **p={fmt_p(p_rep)}**")

        # Vorzeichentest distinct-2 (k = Paare mit arm > baseline = arm besser)
        d_d2 = [(pid, met[a][pid]["d2"] - base_met[pid]["d2"]) for pid in pair_ids]
        nz2 = [(pid, d) for pid, d in d_d2 if d != 0]
        k_more = sum(1 for _, d in nz2 if d > 0)
        p_d2 = sign_test(len(nz2), k_more)
        lines.append(f"- **Vorzeichentest distinct-2:** {a}>{baseline} in {k_more}, "
                     f"{a}<{baseline} in {len(nz2) - k_more}, Gleichstand {len(pair_ids) - len(nz2)} "
                     f"→ n={len(nz2)}, k={k_more}, **p={fmt_p(p_d2)}**")

        # Öffner: exakter Vorzeichentest auf diskordanten Paaren (= McNemar exakt)
        for label, key in (("Slop-Öffner", "slop"), ("Öffner-Banlist", "ban")):
            n01 = sum(1 for pid in pair_ids if base_met[pid][key] and not met[a][pid][key])
            n10 = sum(1 for pid in pair_ids if not base_met[pid][key] and met[a][pid][key])
            p_mc = sign_test(n01 + n10, n10)
            lines.append(f"- **McNemar exakt {label}:** nur-{baseline}={n01}, nur-{a}={n10} "
                         f"→ n={n01 + n10}, k={n10}, **p={fmt_p(p_mc)}**")

        # Byte-Identitäts-Diagnostik
        ident = sum(1 for pid in pair_ids if ok[a][pid]["text"] == base_ok[pid]["text"])
        frac = ident / len(pair_ids)
        lines.append(f"- **Byte-identisch zu {baseline}:** {ident}/{len(pair_ids)} ({100*frac:.1f} %)")
        if frac > 0.95:
            warn_arms.append((a, frac))
            lines.append(f"- ⚠️ **WARNUNG: Arm `{a}` wirkt nicht — Flags kamen vermutlich "
                         f"nicht an (Brain-Restart? _XTC_SUPPORTED?)** "
                         f"({100*frac:.1f} % byte-identisch, Schwelle 95 %). "
                         f"Hinweis: für reine opener-Arme Schwelle je Arm-Typ interpretieren "
                         f"(Processor ist ab Pos 3 No-op → auf Nicht-Köder-Prompts legitim identisch).")

        # Top-3-Ausreißer-Paare (größtes |Δrep|) — qualitativ der wertvollste Teil (D1: i01!)
        top = sorted(d_rep, key=lambda t: abs(t[1]), reverse=True)[:3]
        lines.append(f"- **Top-3 |Δrep|-Ausreißer** (Δ = {a} − {baseline}):")
        for pid, d in top:
            lines.append(f"    - `{pid}` Δrep={d:+.4f} (rep {baseline}={base_met[pid]['rep']:.4f}, "
                         f"{a}={met[a][pid]['rep']:.4f})")
            lines.append(f"        - {baseline}: {base_ok[pid]['text'][:120]!r}")
            lines.append(f"        - {a}: {ok[a][pid]['text'][:120]!r}")

    errors = [r for r in rows if r.get("error")]
    if errors:
        lines.append(f"\n## Fehler ({len(errors)})\n")
        for r in errors:
            lines.append(f"- {r['prompt_id']}×{r['arm']}: {r['error']}")

    report = "\n".join(lines) + "\n"
    summary_path = os.path.join(os.path.dirname(os.path.abspath(raw_path)), "summary.md")
    with open(summary_path, "w", encoding="utf-8") as fh:
        fh.write(report)
    print(report)
    print(f"[analyze] Summary: {summary_path}", file=sys.stderr)

    if warn_arms:
        for a, frac in warn_arms:
            print(f"[analyze] ⚠️ LAUT: Arm '{a}' wirkt nicht — {100*frac:.1f} % byte-identisch "
                  f"zur Baseline. Flags kamen vermutlich nicht an "
                  f"(Brain-Restart? _XTC_SUPPORTED?)", file=sys.stderr)
        return 2
    return 0


# ── main ─────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="mode", required=True)

    ap_run = sub.add_parser("run", help="messen (danach automatisch analyze)")
    ap_run.add_argument("--config", required=True, help="JSON-Config (s. measure-brain-ab.example.json)")
    ap_run.add_argument("--out", default=None,
                        help=f"Output-Dir (Default: {DEFAULT_RESULTS_DIR}/xtcab-<ts>/)")
    ap_run.add_argument("--limit", type=int, default=None, help="nur die ersten N Prompts")
    ap_run.add_argument("--arms", default=None, help="Komma-Liste: nur diese Arme (Namen aus der Config)")
    ap_run.add_argument("--smoke", action="store_true",
                        help="genau 1 Request (erster Prompt × Baseline-Arm), kein analyze")
    ap_run.add_argument("--timeout", type=float, default=60.0, help="Request-Timeout s (Default 60)")

    ap_an = sub.add_parser("analyze", help="auswerten (standalone, re-runnbar)")
    ap_an.add_argument("--raw", required=True, help="raw.jsonl oder dessen Verzeichnis")
    ap_an.add_argument("--baseline", default=None, help="Baseline-Arm (Default: aus Header, sonst 'default')")

    args = ap.parse_args()
    if args.mode == "run":
        sys.exit(run_mode(args))
    sys.exit(analyze(args.raw, baseline_override=args.baseline))


if __name__ == "__main__":
    main()
