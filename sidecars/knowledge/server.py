"""
hoshi-knowledge-bridge — Mac-Sidecar für die Wikipedia-FTS5-DB.

Iter-94g (Nachtschicht 2026-05-19/20): Backend ct-106 (Proxmox-LXC) hat
keinen Zugriff auf ~/.hoshi/knowledge/wiki-de/articles.db (~7 GB, liegt
auf Mac). Dieser Sidecar ist die HTTP-Bridge.

Pattern: analog zu hoshi-tts-qwen3 und hoshi-stt-mlx — ein Python-Sidecar
auf Mac der eine spezifische Datenquelle exposed. Backend nutzt es via
HTTP. These IV (Mac als AI-Coprocessor) konsequent angewendet auf
Daten-Layer.

API-Kompatibilität mit Backend WikiKnowledgeSearchService:
- GET /health → {status, articleCount}
- GET /search?q=...&limit=5&extract_max_chars=1500 → identische
  JSON-Struktur wie WikiSearchHit
- GET /article/{id}?max_chars=1500 → einzelner Artikel mit Volltext

Implementation: SQLite FTS5 (gleiche SQL wie Kotlin-Service), zstandard
für plaintext-Dekompression.

Quelle:
- agent/src/main/kotlin/de/hoshi/app/knowledge/WikiKnowledgeSearchService.kt
- wiki/welt-update-2026-05-19.md These IV
"""
import argparse
import json
import logging
import math
import os
import re
import sqlite3
import sys
import time
import urllib.request
from pathlib import Path
from typing import Optional

import zstandard as zstd
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

# ── Logging ─────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)
log = logging.getLogger("hoshi-knowledge-bridge")

# ── Argparse ────────────────────────────────────────────────────────────────
parser = argparse.ArgumentParser(description="Hoshi-Knowledge-Bridge: Wiki-FTS5-Sidecar")
parser.add_argument("--host", default="0.0.0.0", help="Bind-Adresse (default 0.0.0.0)")
parser.add_argument("--port", type=int, default=8035, help="Port (default 8035)")
parser.add_argument(
    "--db-path",
    default=os.environ.get(
        "HOSHI_WIKI_DB_PATH",
        str(Path.home() / ".hoshi" / "knowledge" / "wiki-de" / "articles.db"),
    ),
    help="Pfad zur articles.db",
)
args, _unknown = parser.parse_known_args()

DB_PATH = Path(args.db_path)
if not DB_PATH.exists():
    log.error("articles.db nicht gefunden: %s", DB_PATH)
    sys.exit(1)

log.info("DB: %s (%.1f GB)", DB_PATH, DB_PATH.stat().st_size / 1e9)


# ── SQLite-Connection-Pool ──────────────────────────────────────────────────
# SQLite ist per-thread. FastAPI/uvicorn ist single-thread per default.
# Wir öffnen eine Connection on-demand pro Request (cheap, file is mmap'd by OS).

def open_conn() -> sqlite3.Connection:
    conn = sqlite3.connect(f"file:{DB_PATH}?mode=ro", uri=True, timeout=5.0)
    conn.row_factory = sqlite3.Row
    return conn


# ── zstd-Decompressor ───────────────────────────────────────────────────────
_decompressor = zstd.ZstdDecompressor()


# ── Extract-Reinigung (Iter-96, Nora-Domäne / Andi „besser aber nicht gut") ──
# Befund 2026-05-21: Der Grounding-Extract für „Albert Einstein" begann mit
# Wikipedia-Thumbnail-Markup („mini|alt=Einstein mit Kreide vor einer Tafel
# stehend, …lächelnd, von halblinks…\n…"). Dieser Bild-Beschreibungstext fraß
# das 600-Zeichen-Fenster auf → die echten Fakten (Nobelpreis 1921) lagen
# DAHINTER → e2b antwortete vage. Wir reinigen die rohe Plaintext-Spalte VOR
# dem Truncate, damit das Fenster Prosa statt Bild-Captions enthält.
#
# Iter-117 (T140, Nora): Das alte Cleaning verwarf nur FÜHRENDE Caption-Zeilen
# (while-Schleife brach bei der ersten Prosa-Zeile ab). Damit leckten inline-
# Captions TIEF im Body weiter ins Fenster — gemessen bei 3/5 Faktfragen, z.B.
# Weinbergschnecke: der Fakt „…Radula, auf der sich rund 40.000 Zähnchen
# befinden…" steht zwischen zwei „mini|…"-Captions eingeklemmt (Roh-Zeichen
# ~6860). Im L4-Passage-Pfad ist das besonders kritisch, weil dort tiefe
# Passagen geliefert werden. Fix: Caption-Zeilen GLOBAL strippen
# (strip_caption_lines), nicht nur führend.
#
# Bewusst KONSERVATIV (Veto-Bereich): eine Zeile gilt nur als Caption, wenn
# low.startswith("alt=") ODER ("|" in s UND head ∈ _IMG_KEYWORDS). Über 1500
# zufällige Artikel (>5 KB) gegengeprüft: KEIN einziger Prosa-False-Positive —
# Treffer waren ausnahmslos echtes Bild-Markup (rechts|100px|Wappen…, mini|…,
# rahmenlos|rechts|…). Eine Prosa-Zeile, die zufällig „rechts" enthält, hat das
# „|" nicht an der Kopf-Position. Fallback in den Aufrufern, nie leerer Extract.
_IMG_KEYWORDS = (
    "mini", "thumb", "thumbnail", "hochkant", "gerahmt", "rahmenlos",
    "links", "rechts", "zentriert", "center", "right", "left",
)
_FILE_LINK_RE = re.compile(r"\[\[(?:Datei|File|Image|Bild):[^\[\]]*\]\]", re.IGNORECASE)


def _is_caption_line(stripped: str) -> bool:
    """True, wenn eine (bereits getrimmte) Zeile eine Wikipedia-Bild-Caption ist.

    Enges Signal (T140): low.startswith("alt=") ODER ("|" am Kopf UND das erste
    Pipe-Segment ist ein Bild-Keyword). Damit fängt es „mini|…", „rechts|100px|…",
    „rahmenlos|rechts|…", „alt=…", aber KEINE Prosa, die nur irgendwo im Satz
    eines dieser Wörter trägt (deren „|" steht — wenn überhaupt — nicht am Kopf).
    """
    if not stripped:
        return False
    low = stripped.lower()
    if low.startswith("alt="):
        return True
    if "|" in stripped:
        head = low.split("|", 1)[0].strip()
        if head in _IMG_KEYWORDS:
            return True
    return False


def strip_caption_lines(text: str) -> str:
    """Entfernt ALLE Wikipedia-Bild-Caption-Zeilen (global, nicht nur führend).

    Zeilenweise: jede Zeile, die _is_caption_line erfüllt, fällt raus — egal ob
    am Kopf oder mitten im Body. Reine Prosa-Zeilen bleiben unangetastet. Gibt
    den Eingabe-Text unverändert zurück, wenn nichts zu strippen ist.
    """
    if not text or "|" not in text and "alt=" not in text.lower():
        return text
    kept = [ln for ln in text.split("\n") if not _is_caption_line(ln.strip())]
    return "\n".join(kept)


def clean_extract(text: str) -> str:
    """Entfernt Wikipedia-Bild-/Thumbnail-Markup aus einem Extract.

    T140: inline-Captions werden jetzt GLOBAL entfernt (nicht nur führend) —
    sonst leckt „mini|…"-Markup tief im Body ins Grounding-Fenster.
    """
    if not text:
        return text
    # 1) Inline-Datei-/Bild-Links überall raus.
    text = _FILE_LINK_RE.sub(" ", text)
    # 2) Bild-Caption-Zeilen GLOBAL verwerfen (führend UND inline).
    text = strip_caption_lines(text)
    # 3) Falls direkt am Anfang noch ein alt=…-Rest steht.
    text = re.sub(r"^\s*alt=[^\n|]*\|?", "", text)
    # 4) Whitespace glätten.
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text).strip()
    return text


def _decompress_full(blob: bytes, original_bytes: int) -> Optional[str]:
    """Dekomprimiert zstd → UTF-8 (KEIN Truncate, KEIN Markup-Cleaning).

    Roher Volltext-Body, Basis für die Passage-Zerlegung (L4). Gibt None
    zurück, wenn nichts dekomprimierbar ist.
    """
    if not blob or original_bytes <= 0:
        return None
    try:
        out = _decompressor.decompress(blob, max_output_size=original_bytes)
    except Exception as e:
        log.warning("zstd-decompress fehlgeschlagen: %s", e)
        return None
    return out.decode("utf-8", errors="replace")


def decompress_truncated(blob: bytes, original_bytes: int, max_chars: int) -> str:
    """Dekomprimiert zstd → UTF-8 → reinigt Markup → truncate auf max_chars.

    Spiegelt WikiKnowledgeSearchService.decompressTruncated (Kotlin).
    """
    text = _decompress_full(blob, original_bytes)
    if text is None:
        return "(Dekompression fehlgeschlagen)" if blob else ""
    cleaned = clean_extract(text)
    # Fallback: nie leeren Extract liefern, falls die Reinigung zu aggressiv war.
    if cleaned.strip():
        text = cleaned
    if len(text) > max_chars:
        return text[:max_chars] + "…"
    return text


# ── Deterministische LEAD-Extraktion (Grounding-Fix 2026-06-27) ──────────────
# WARUM: /search lieferte für „nackte" Entity-/Definitions-Queries (genau die,
# die der 0.8-Fts5GroundingAdapter schickt — „Wer war Konrad Adenauer?" →
# „konrad adenauer") über die L4-Passage-RAG eine BELIEBIGE Sub-Sektion tief im
# Artikel (das Token „adenauer" kommt in einem Body-Abschnitt häufiger vor als
# im Lead) — teils englisch-beginnend, nie die eigentliche Einleitung. Folge:
# das Modell groundete auf Müll. Fix: für Definitions-/Entity-Queries
# deterministisch den ARTIKEL-LEAD liefern.
#
# Die DB-Plaintext-Spalte hat KEINE „== Überschrift =="-Marker mehr (vom
# Extraktor gestrippt) — Sektionen sind nur per Leerzeile (`\n\n`) getrennt, und
# Artikel beginnen oft mit Bild-Caption-Zeilen (mini|… / rahmenlos|…) bzw. einem
# Infobox-Block (viele „|"/„alt="). Darum: erste ECHTE Prosa-Absätze wählen
# (Caption-/Infobox-Zeilen überspringen), bis ~max_chars erreicht sind.
_REF_WEBCITE_RE = re.compile(
    r"\bIn:\s.{0,200}?abgerufen am\s+\d{1,2}\.\s*[A-Za-zÄÖÜäöü]+\s*\d{4}\.?",
    re.IGNORECASE,
)
_REF_TAIL_RE = re.compile(
    r",?\s*abgerufen am\s+\d{1,2}\.\s*[A-Za-zÄÖÜäöü]+\s*\d{4}\.?",
    re.IGNORECASE,
)


def _is_lead_prose(para: str) -> bool:
    """True, wenn ein Absatz echte Lead-Prosa ist (kein Caption-/Infobox-Block).

    Heuristik bewusst konservativ: ≥60 Zeichen, beginnt mit einem Buchstaben,
    enthält einen Satzpunkt, KEIN `alt=` und <2 Pipes (`|` = Bild-/Tabellen-/
    Infobox-Dump). So überspringt der Lead-Picker den (z. B. Berlin-)Infobox-Block
    und führende Bild-Captions und greift den ersten Definitions-Absatz.
    """
    p = para.strip()
    if len(p) < 60 or not p[:1].isalpha():
        return False
    if "alt=" in p.lower() or p.count("|") >= 2 or "." not in p:
        return False
    return True


def extract_lead(full_text: Optional[str], max_chars: int) -> str:
    """Deterministischer Artikel-LEAD (Einleitung) — sauberer deutscher Prosa-Lead.

    clean_extract → bis zum FACTS-Footer kappen → ersten ECHTEN Prosa-Absatz/
    -Absätze (Caption-/Infobox-Zeilen übersprungen) bis ~max_chars sammeln →
    Inline-Web-Referenzen scrubben → an Satzgrenze auf max_chars kappen. Nie leer,
    wenn Text da ist (Fallback: erste max_chars des bereinigten Texts).
    """
    if not full_text:
        return ""
    cleaned = clean_extract(full_text)
    src = cleaned if cleaned.strip() else full_text
    fi = src.find(_FACTS_MARKER)
    if fi > 0:
        src = src[:fi]
    parts: list[str] = []
    total = 0
    for para in re.split(r"\n\s*\n", src):
        para = re.sub(r"\s+", " ", para).strip()
        if not _is_lead_prose(para):
            if parts:
                break  # Lead-Prosa zu Ende (nächste Sektion/Infobox) → stop
            continue   # führende Caption-/Infobox-Zeile überspringen
        parts.append(para)
        total += len(para)
        if total >= max_chars:
            break
    lead = " ".join(parts).strip()
    if not lead:  # Robust: nie leer, wenn überhaupt Text da ist
        lead = re.sub(r"\s+", " ", src).strip()
    # Inline-Web-Referenzen („In: … abgerufen am 10. Januar 2017.") raus.
    lead = _REF_WEBCITE_RE.sub(" ", lead)
    lead = _REF_TAIL_RE.sub(" ", lead)
    lead = re.sub(r"\s+", " ", lead).strip()
    if len(lead) > max_chars:
        cut = lead.rfind(". ", 0, max_chars)
        lead = lead[: cut + 1] if cut > max_chars // 2 else lead[:max_chars] + "…"
    return lead


# ── L4 Passage-RAG (Task #62, Iter-104) ──────────────────────────────────────
# PROBLEM: /search lieferte bisher das INTRO (clean_extract der ersten ~1100
# Zeichen) des korrekt gewählten Artikels. Für Faktfragen deren Antwort TIEF im
# Artikel steht — „Wie viele Zähne hat eine Weinbergschnecke" → die Radula mit
# ~40.000 Zähnchen liegt bei Zeichen ~6860 von 15160 — fehlt der Fakt im Fenster
# → das Modell halluziniert. Seit Iter-103 finden wir zuverlässig den RICHTIGEN
# Artikel; jetzt brauchen wir die richtige PASSAGE daraus.
#
# ANSATZ (rein in der Bridge, billig — nur der EINE bereits gewählte Artikel):
# Vollen Body dekomprimieren, in überlappende Passagen (~1400 Zeichen) splitten,
# jede gegen die Query-Content-Tokens scoren (BM25-lite: idf-gedämpftes Term-
# Overlap mit Token-Frequenz), die best-scorende Passage als extract liefern.
#
# REGRESSIONS-SCHUTZ (Kernpunkt): Eine Passage ersetzt das Intro NUR, wenn
#   (a) die Query NICHT generisch ist („Was ist X / Wer war Y" = Definitionsfrage
#       → Intro ist per Definition die beste Antwort), UND
#   (b) die beste Passage das Intro um eine Marge nachweislich schlägt.
# Sonst bleibt das bewährte Intro (clean_extract). So verbessern wir Faktfragen
# OHNE Definitionsfragen zu verschlechtern.
PASSAGE_RAG_ENABLED = os.environ.get("HOSHI_BRIDGE_PASSAGE_RAG", "1").lower() not in (
    "0", "false", "no", "off",
)
_PASSAGE_CHARS = 1400          # Ziel-Passagenlänge (~256-512 Tokens)
_PASSAGE_OVERLAP = 250         # Überlappung, damit Fakten an Grenzen nicht zerreißen
_PASSAGE_MIN_MARGIN = 1.25     # Passage muss Intro-Score um diesen Faktor schlagen
_PASSAGE_MIN_SCORE = 1.5       # absolute Untergrenze: sonst kein klarer Treffer

# „Was ist …", „Wer war/ist …", „Was sind …" — reine Definitionsfragen, für die
# das Intro die kanonische Antwort ist. Bei diesen NIE auf Passage umschalten.
_GENERIC_QUERY_RE = re.compile(
    r"^\s*(was\s+(ist|sind|war|waren|bedeutet)|wer\s+(ist|war|sind|waren)|"
    r"erkläre?|erzähl(e|st)?\s+(mir|was|etwas))\b",
    re.IGNORECASE,
)


def _is_generic_query(query: str) -> bool:
    """True für Definitionsfragen (Was ist X / Wer war Y) → Intro behalten.

    Auch eine sehr kurze, reine Substantiv-Frage („Kölner Dom", „Albert
    Einstein") ist effektiv eine Definitionsfrage: es gibt keinen fokussierten
    Aspekt, den eine Passage besser träfe als das Intro.
    """
    if _GENERIC_QUERY_RE.match(query):
        return True
    # Reine Entitäts-Nennung ohne Fragefokus: ≤3 Content-Tokens, kein „w-Frage"-
    # Aspektwort. Dann ist das Intro die richtige Antwort.
    return False


# ── T138 AC#1 (Schatten-Modus, Nora): Elastic Retrieval Depth ────────────────
# WARUM: Die Retrieval-Tiefe folgt heute nicht der Anfrage-Klasse. Bevor wir die
# Tiefe scharf schalten (AC#2-4), wollen wir ERST MESSEN, welche Anfrage welche
# Tiefe *hätte*. Diese Funktion leitet einen `depth`-Tag (none/gist/passage/cloud)
# aus Kategorie (= top-Hit-Klassifikation + Treffer-Existenz) + `_is_generic_query`
# ab. Mapping nach der Ticket-Tabelle, soweit die Bridge die Signale sieht:
#
#   • SMALLTALK / SMART_HOME / AGENT erreichen die Bridge gar nicht erst — der
#     `WikiGroundingService` schließt sie vom Grounding aus. Sieht die Bridge
#     trotzdem eine solche Klassifikation am top-Hit ⇒ `none` (kein Retrieval).
#   • Definitionsfrage (`_is_generic_query` True) MIT brauchbarem Treffer ⇒ `gist`
#     (Kern-Gist/Intro würde reichen, kein Decompress nötig).
#   • Aspekt-/Faktfrage (kein generic) MIT brauchbarem Treffer ⇒ `passage`.
#   • Kein brauchbarer Treffer / Score-Floor unterschritten ⇒ `cloud`
#     (Cloud-Audit-Kandidat; im Zweifel grounden, Nora-Veto AC#3 greift erst
#     beim Scharfschalten).
#
# DISZIPLIN: Diese Funktion ist eine REINE Ableitung — kein I/O, keine Mutation,
# kein Flag-Flip. Ihr Rückgabewert wird AUSSCHLIESSLICH geloggt (siehe `/search`).
# Default-Retrieval-Verhalten bleibt byte-identisch.
_SHADOW_DEPTH_NO_RAG_CLASSES = ("SMALLTALK", "SMART_HOME", "AGENT")
# Absolute Score-Untergrenze für „brauchbarer Treffer": BM25-lite ist hier negativ
# = besser (vgl. _PASSAGE_MIN_SCORE-Logik / Disambig-Battery). Wir prüfen daher
# nur, ob ÜBERHAUPT ein Hit existiert; eine echte Cosine-Floor-Auswertung ist
# AC#3-Arbeit (Nora-Veto). Schatten-Heuristik bewusst konservativ.
def _shadow_retrieval_depth(
    query: str, top_classification: Optional[str], has_hit: bool,
) -> str:
    """Schatten-Tag der *hypothetischen* Retrieval-Tiefe — NUR fürs Logging.

    Rein additiv/seiteneffektfrei: leitet `none|gist|passage|cloud` aus
    Kategorie (top-Hit-Klassifikation) + `_is_generic_query(query)` ab und gibt
    den Tag zurück. Ändert NICHTS am Retrieval (kein Flag, kein Boost, keine
    andere Tiefe). Caller loggt den Tag im bestehenden `[search]`-Trace.
    """
    cls = (top_classification or "").upper()
    if any(c in cls for c in _SHADOW_DEPTH_NO_RAG_CLASSES):
        return "none"
    if not has_hit:
        return "cloud"
    if _is_generic_query(query):
        return "gist"
    return "passage"


def _split_passages(text: str) -> list[str]:
    """Zerlegt den Body in überlappende Passagen ~_PASSAGE_CHARS, satzfreundlich.

    Gleitet in _PASSAGE_CHARS-Schritten (minus Overlap) durch den Text und
    schneidet das Ende der Passage an der letzten Satz-/Absatzgrenze ab, damit
    Passagen nicht mitten im Wort enden. Kein Token-Tokenizer nötig — Zeichen-
    Fenster ist robust und billig (ein Artikel = wenige KB).
    """
    text = text.strip()
    if not text:
        return []
    if len(text) <= _PASSAGE_CHARS:
        return [text]
    passages: list[str] = []
    step = max(1, _PASSAGE_CHARS - _PASSAGE_OVERLAP)
    pos = 0
    n = len(text)
    while pos < n:
        end = min(pos + _PASSAGE_CHARS, n)
        chunk = text[pos:end]
        if end < n:
            # An letzter Satz-/Absatzgrenze im hinteren Drittel sauber kappen.
            cut = max(
                chunk.rfind(". "), chunk.rfind(".\n"),
                chunk.rfind("\n"), chunk.rfind("! "), chunk.rfind("? "),
            )
            if cut > _PASSAGE_CHARS // 2:
                chunk = chunk[: cut + 1]
                end = pos + len(chunk)
        passages.append(chunk.strip())
        if end >= n:
            break
        pos = end - _PASSAGE_OVERLAP if end - _PASSAGE_OVERLAP > pos else end
    return [p for p in passages if p]


# Token-Stamm-Helfer: deutsche Faktfragen variieren das Hauptnomen oft stark
# (Frage „Zähne" steht im Artikel als „Zähnchen"; „Zähnen"; „Zahn"). Reines
# Präfix-Cropping reicht nicht — „zähne"[:6]=„zähne" trifft „zähnchen" NICHT,
# da deren gemeinsamer Stamm „zähn" ist. Daher: erst gängige flektierende
# Endungen abschneiden (Plural/Diminutiv-Brücke), dann Präfix-Stamm. Bewusst
# konservativ: Mindeststammlänge 4, sonst Token unverändert (kein Übergeneralisieren
# bei kurzen Wörtern). Das ist eine billige Heuristik, kein voller Snowball-Stemmer.
_STEM_SUFFIXES = ("ungen", "chen", "lein", "isch", "en", "er", "es", "em", "ern", "n", "e", "s")


def _stem(tok: str) -> str:
    """Grober deutscher Präfix-Stamm für Term-Overlap-Matching.

    Schneidet eine flektierende Endung ab (falls Reststamm ≥4) und kappt dann
    auf max. 6 Zeichen. So matcht „zähne" → „zähn" → trifft „Zähnchen"/„Zähnen".
    """
    base = tok
    if len(tok) >= 5:
        for suf in _STEM_SUFFIXES:
            if tok.endswith(suf) and len(tok) - len(suf) >= 4:
                base = tok[: -len(suf)]
                break
    return base[:6]


# T150 (Iter-124, Nora): FACTS-Footer-Boost. Der Backfill (tools/wiki-infobox-
# backfill/) hängt strukturierte Infobox-Fakten als „--FACTS--\nHöhe: 8848 m\n…"
# an den plaintext. Der Boost hier sorgt dafür, dass eine Passage die diesen
# Marker enthält UND zur Frage passt (Adjektiv „hoch/weit/groß/alt" oder W-Frage
# „wie/wo/wann") gegenüber der Mid-Body-Passage gewinnt. Default ON, Kill-Switch
# via ENV `HOSHI_BRIDGE_FACTS_BOOST=0`.
_FACTS_BOOST_ENABLED = os.environ.get(
    "HOSHI_BRIDGE_FACTS_BOOST", "1"
).lower() not in ("0", "false", "no", "off")
_FACTS_MARKER = "--FACTS--"
# Multiplikator: Passage mit FACTS-Block bekommt diesen Faktor on top. 1.6 reicht
# damit der Footer das beste Mid-Body-Match meist schlägt, aber nicht so stark
# dass ein OFF-Topic-Backfill (z.B. Tempelberg mit Höhe 700 m) bei „Tempel"
# unangemessen aufsteigt.
_FACTS_BOOST_FACTOR = 1.6
# W-Frage-Indikator: typische Fakt-Adjektive/Fragepronomen. Wenn die Query keins
# enthält (z.B. „Mount Everest" als nackte Entity), kein Boost — Intro reicht.
_FACT_QUERY_RE = re.compile(
    r"\b(wie|wo|wann|wieviel|wieviele|wievielen|welche[rs]?|"
    r"hoch|weit|gro[sß]|alt|tief|lang|breit|schwer|teuer)\b",
    re.IGNORECASE,
)


def _score_passage(passage: str, q_tokens: list[str], doc_df: dict[str, int],
                   n_passages: int, *, query_has_fact_word: bool = False) -> float:
    """BM25-lite Score einer Passage gegen die Query-Content-Tokens.

    Term-Overlap mit Stamm-Match, gewichtet per
      - idf: seltene Query-Tokens (in wenigen Passagen) zählen mehr,
      - tf-Dämpfung: 1 + log(count) statt roher Frequenz (Längen-fair).
    T150-Erweiterung: enthält die Passage den FACTS-Footer-Marker UND die Query
    hat einen Fakt-Indikator (wie hoch/weit/…), bekommt sie einen multiplikativen
    Boost (default ×1.6, default ON, Kill-Switch ENV HOSHI_BRIDGE_FACTS_BOOST=0).
    Keine externen Libs — billig, ein Artikel hat eine Handvoll Passagen.
    """
    if not q_tokens:
        return 0.0
    low = passage.lower()
    import math
    score = 0.0
    for tok in q_tokens:
        stem = _stem(tok)
        count = low.count(stem)
        if count == 0:
            continue
        df = doc_df.get(tok, 1)
        idf = math.log(1.0 + (n_passages / df))
        tf = 1.0 + math.log(count)
        score += idf * tf
    # FACTS-Footer-Boost: Passage MUSS den Marker enthalten UND die Query muss
    # eine Fakt-Frage sein. Sonst kein Boost — wir wollen keine Definitionsfragen
    # auf den FACTS-Block umlenken.
    if _FACTS_BOOST_ENABLED and query_has_fact_word and _FACTS_MARKER in passage:
        score *= _FACTS_BOOST_FACTOR
    return score


def _query_focused_extract(
    full_text: str, query: str, q_tokens: list[str], max_chars: int,
) -> Optional[str]:
    """L4: best-scorende Passage als Extract, sonst None (→ Intro behalten).

    Gibt nur dann eine Passage zurück, wenn sie das INTRO (erste Passage) um
    _PASSAGE_MIN_MARGIN schlägt UND _PASSAGE_MIN_SCORE überschreitet. So bleibt
    bei Definitions-/Generik-Fragen das Intro die Antwort (kein Regress).
    """
    if not q_tokens or not full_text:
        return None
    cleaned = clean_extract(full_text)
    if not cleaned.strip():
        cleaned = full_text
    passages = _split_passages(cleaned)
    if len(passages) <= 1:
        return None  # Artikel passt in eine Passage → Intro == Volltext
    # T150: ist die Query eine Fakt-Frage (z.B. „wie hoch ist X")? Dann darf der
    # FACTS-Boost im _score_passage greifen, falls eine Passage den Footer hat.
    query_has_fact_word = bool(_FACT_QUERY_RE.search(query))
    # Document-Frequency pro Query-Token (in wie vielen Passagen kommt der Stamm vor)
    doc_df: dict[str, int] = {}
    for tok in q_tokens:
        stem = _stem(tok)
        doc_df[tok] = sum(1 for p in passages if stem in p.lower()) or 1
    n = len(passages)
    intro_score = _score_passage(passages[0], q_tokens, doc_df, n,
                                 query_has_fact_word=query_has_fact_word)
    best_idx, best_score = 0, intro_score
    for i in range(1, n):
        s = _score_passage(passages[i], q_tokens, doc_df, n,
                           query_has_fact_word=query_has_fact_word)
        if s > best_score:
            best_idx, best_score = i, s
    if best_idx == 0:
        return None  # Intro ist eh am besten
    # Marge gegen Intro + absolute Untergrenze: Passage muss klar gewinnen.
    margin_ok = best_score >= max(_PASSAGE_MIN_SCORE,
                                  intro_score * _PASSAGE_MIN_MARGIN)
    if not margin_ok:
        return None
    chosen = passages[best_idx].strip()
    # Führendes Satzfragment (Overlap-Artefakt) entfernen, damit die Passage an
    # einer Satz-/Absatzgrenze beginnt — nur wenn das Fragment kurz ist und der
    # Such-Stamm dahinter liegt, sonst riskieren wir den Fakt wegzuschneiden.
    head_cut = -1
    for sep in (". ", ".\n", "\n", "! ", "? "):
        idx = chosen.find(sep)
        if 0 <= idx < 220 and (head_cut < 0 or idx < head_cut):
            head_cut = idx
    if head_cut >= 0:
        candidate = chosen[head_cut + 1:].strip()
        # nur kappen, wenn dadurch kein Query-Stamm verloren geht
        stems = [_stem(t) for t in q_tokens]
        if all((s in candidate.lower()) == (s in chosen.lower()) for s in stems):
            chosen = candidate
    if len(chosen) > max_chars:
        cut = chosen.rfind(". ", 0, max_chars)
        chosen = chosen[: cut + 1] if cut > max_chars // 2 else chosen[:max_chars] + "…"
    return chosen


def query_focused_or_intro(
    blob: bytes, original_bytes: int, query: str, q_tokens: list[str],
    max_chars: int, passage_rag: bool, is_fact_query: bool = False,
) -> tuple[str, bool]:
    """Wählt Passage (L4) ODER den deterministischen LEAD. Gibt (extract, used_passage).

    Grounding-Fix (2026-06-27): Die L4-Passage-RAG lieferte für „nackte" Entity-/
    Definitions-Queries (was der 0.8-Adapter schickt — „konrad adenauer") eine
    beliebige Sub-Sektion tief im Artikel statt der Einleitung → Müll-Grounding.
    Darum greift die Passage NUR noch bei echten Fakt-/Aspekt-Fragen
    (`is_fact_query`, z. B. „wie viele Zähne…" — dort steht der gesuchte Fakt
    nachweislich tief im Body). Für ALLES andere (Definitions-/Entity-Queries)
    liefert `extract_lead` deterministisch den sauberen deutschen Artikel-Lead.
    Schlägt die Passage nicht klar an → ebenfalls Lead. Ein Decompress pro Hit.
    """
    full = _decompress_full(blob, original_bytes)
    if full is None:
        return ("(Dekompression fehlgeschlagen)" if blob else ""), False
    if (
        passage_rag and PASSAGE_RAG_ENABLED and is_fact_query
        and not _is_generic_query(query)
    ):
        passage = _query_focused_extract(full, query, q_tokens, max_chars)
        if passage is not None:
            return passage, True
    # Default: deterministischer LEAD (Einleitung statt beliebiger Sub-Sektion).
    return extract_lead(full, max_chars), False


# ── Tier-1 extraktives Satz-Summary (Iter-135, Nora) ─────────────────────────
# WARUM: Der volle Extract (Tier-2) ist für Faktfragen oft groß und verstopft
# das Grounding-Fenster (L-096.5: RAG-Qualität = Disambiguation × Extract-
# Qualität). Für „Wer war Einstein?" reichen 2-3 Lead-Sätze. Tier-1 liefert
# genau die: ein EXTRAKTIVES Top-N-Satz-Summary aus dem Artikel, gerankt per
# embeddinggemma-Cosine(Frage↔Satz), in ORIGINALREIHENFOLGE (Chronologie nicht
# zerreißen — sonst „1921 Nobelpreis. Geboren 1879." statt umgekehrt).
#
# T139-Veto-konform: rein EXTRAKTIV zur Laufzeit (verbatim Wikipedia-Sätze),
# KEINE vorgespeicherten/abstraktiven Gists. Reproduzierbar aus der DB.
#
# DISZIPLIN (Recall-Schutz): Komplett ADDITIV. Nur aktiv, wenn der neue Param
# `summary_sentences > 0` gesetzt ist. Default 0 = heutiges Verhalten, voller
# Extract, alter Pfad unangetastet. Schlägt der Embed-Pass fehl (Ollama kalt/
# weg) → Summary = None, voller Extract bleibt erhalten (nie schlechter).
SUMMARY_ENABLED = os.environ.get("HOSHI_BRIDGE_SUMMARY", "1").lower() not in (
    "0", "false", "no", "off",
)
# Ollama-Embed-Endpoint (Batch). Wir nutzen /api/embed (input-Array → EIN Call
# über ALLE Sätze), nicht /api/embeddings (single). embeddinggemma:300m, 768d.
# Bewusst KEIN task-type-Prefix (search_query/search_document): der etablierte
# Disambiguation-Pfad (OllamaStreamingClient.embed) nutzt auch keinen → Frage-
# und Satz-Vektoren bleiben im selben Raum wie der bestehende Re-Rank.
_OLLAMA_EMBED_URL = os.environ.get(
    "HOSHI_OLLAMA_EMBED_URL", "http://127.0.0.1:11434/api/embed"
)
_SUMMARY_EMBED_MODEL = os.environ.get(
    "HOSHI_BRIDGE_EMBED_MODEL", "embeddinggemma:300m"
)
_SUMMARY_EMBED_TIMEOUT_S = float(os.environ.get("HOSHI_BRIDGE_EMBED_TIMEOUT_S", "8"))
# Wie viele Sätze maximal embedded werden (Latenz-/Kosten-Deckel). Wir scannen
# bewusst nur das LEAD-Fenster (erste N Sätze): Tier-1 ist das Lead-Summary für
# Definitions-/„Wer war X"-Fragen, und bei Wikipedia trägt der Lead die Kern-
# Definition + die prominentesten Fakten (Einstein: Nobelpreis/Relativität;
# Curie: Doppel-Nobelpreis).
# T216 (Nacht 2026-06-01, live gemessen): 12 war ZU ENG — der Nobelpreis-Satz
# (Einstein/Curie) liegt jenseits von Satz 12, fiel also aus dem N=3-Summary
# (Akzeptanz „N=3 behält Nobelpreis 1921" verfehlt). Mit 28 zieht die entkoppelte
# Satz-Cosine (`summary_query`) den Nobel-Satz zuverlässig rein. Latenz-Kosten
# gemessen: scan 12→0,40 s, scan 30→0,50 s = +~100 ms — tragbar für das opt-in-
# Tier-1-Summary (Embed-Batch bleibt klein, Lead-Anchor + Fakt-Bias halten den
# Definitions-Satz vorn). Kill-Switch/Tuning via ENV HOSHI_BRIDGE_SUMMARY_SCAN.
_SUMMARY_MAX_SENTENCES_SCANNED = int(
    os.environ.get("HOSHI_BRIDGE_SUMMARY_SCAN", "28")
)
_SUMMARY_MIN_SENTENCE_CHARS = 25   # Fragmente/Überschriften raus
_SUMMARY_MAX_SENTENCE_CHARS = 400  # eine Listen-/Tabellen-Zeile ist kein Satz

# ── T223 (Iter-136, Nora): Fakt-Bias im Satz-Ranking + Lead-Anchor-Mix ───────
# WARUM: embeddinggemma rankt generische Lead-Definitionssätze über den konkreten
# Auszeichnungs-/Fakt-Satz. Einstein-Nobelpreis-1921 (Lead-Index ~10) und Curie-
# Doppelpreis (1903+1911, Index ~4) liegen im Scan-Fenster, werden aber von reiner
# Frage↔Satz-Cosine nicht gewählt. Tier-1-Kompression verlor so den Schlüssel-Fakt
# und blieb default-OFF (T216-Folge). Fix (Kai-Design §2): ADDITIVER Fakt-Boost
# aufs Cosine — Sätze mit Jahr/Auszeichnungs-Marker/Maßzahl bekommen einen kleinen
# Aufschlag, sodass ein mittelguter Cosine über die Lead-Floskel steigt OHNE das
# Cosine zu ersetzen. Plus Lead-Anchor-Mix: Satz 1 (Definition) IMMER drin.
#
# DISZIPLIN: rein additiv, klein, regelbasiert (kein ML-Reranker — Kai-Veto §4).
# Greift nur im Tier-1-Pfad (summary_sentences>0). Default-OFF des Features bleibt
# (Flag wikiSummaryCompressionEnabled auf master). Gewichte auf Recall geeicht
# (siehe T223 A/B). Kill-Switch: HOSHI_BRIDGE_FACT_BIAS=0 → reines Cosine wie T216.
_FACT_BIAS_ENABLED = os.environ.get(
    "HOSHI_BRIDGE_FACT_BIAS", "1"
).lower() not in ("0", "false", "no", "off")
# Startgewichte aus Kai-Design (Hypothese), auf Recall geeicht (T223 A/B).
# Additiv aufs Cosine (Cosine-Range typ. ~0.3..0.75 bei embeddinggemma).
_FACT_W_YEAR = float(os.environ.get("HOSHI_BRIDGE_FACT_W_YEAR", "0.15"))
_FACT_W_MARKER = float(os.environ.get("HOSHI_BRIDGE_FACT_W_MARKER", "0.10"))
_FACT_W_MEASURE = float(os.environ.get("HOSHI_BRIDGE_FACT_W_MEASURE", "0.08"))
_FACT_MARKER_CAP = 2  # marker_hits gedeckelt — ein Aufzählungssatz dominiert nicht
# Lead-Anchor-Mix: Satz 1 (Definitions-/Identitätssatz) IMMER ins Summary, dann
# Top-score-Faktsätze auffüllen. „Wer war X" braucht den Identitäts-Satz.
_FACT_LEAD_ANCHOR = os.environ.get(
    "HOSHI_BRIDGE_LEAD_ANCHOR", "1"
).lower() not in ("0", "false", "no", "off")

# Jahr-Regex: 1xxx oder 20xx — fängt 1921/1903/1911 und meidet 4-stellige
# Nicht-Jahre (z.B. „40000" wird vom Maßzahl-Regex erfasst, nicht hier).
_FACT_YEAR_RE = re.compile(r"\b(1\d{3}|20\d{2})\b")
# Maßzahl: Zahl (mit Tausender-Punkt/Dezimal-Komma) + Einheit/Mengen-Wort.
# Fängt „40.000 Zähnchen", „8848 m", „83 Millionen Einwohner", „12 %", „1,5 kg".
_FACT_MEASURE_RE = re.compile(
    r"\b\d{1,3}(?:[.\s]\d{3})+\b"                       # Tausender (40.000, 1 000)
    r"|\b\d+(?:[.,]\d+)?\s?"
    r"(?:%|°|km|cm|mm|m²|km²|m|kg|g|t|l|ml|°c|°f|"
    r"euro|dollar|jahre|jahren|einwohner|"
    r"millionen|milliarden|millionen\.|tausend|"
    r"zähnchen|zähne|meter|kilometer|tonnen|prozent|hektar)\b",
    re.IGNORECASE,
)
# Marker-Lexikon: Auszeichnungs-/Biografie-/Entdeckungs-Wörter. Diese signalisieren
# einen prominenten Fakt-Satz, den embeddinggemma sonst unter generischen Lead-
# Floskeln verliert. Gewichtet, marker_hits gedeckelt bei _FACT_MARKER_CAP.
_FACT_MARKERS = (
    "nobelpreis", "nobelpreises", "auszeichnung", "ausgezeichnet",
    "geboren", "gestorben", "erhielt", "erhielten", "gewann", "verliehen",
    "preis", "preisträger", "entdeckte", "entdeckung", "erfand", "erfindung",
    "gilt als", "gründete", "begründete", "veröffentlichte", "wurde … benannt",
)
# „wurde … benannt" als Phrase ist im Tuple unpraktisch — separat behandelt.
_FACT_MARKERS = tuple(m for m in _FACT_MARKERS if "…" not in m)


def _has_year(s: str) -> bool:
    """True, wenn der Satz eine Jahreszahl (1xxx/20xx) enthält."""
    return bool(_FACT_YEAR_RE.search(s))


def _marker_hits(s: str) -> int:
    """Zahl der Fakt-Marker-Treffer im Satz, gedeckelt bei _FACT_MARKER_CAP."""
    low = s.lower()
    hits = 0
    for m in _FACT_MARKERS:
        if m in low:
            hits += 1
            if hits >= _FACT_MARKER_CAP:
                break
    return hits


def _has_measure(s: str) -> bool:
    """True, wenn der Satz eine Maßzahl (Zahl + Einheit/Mengen-Wort) trägt."""
    return bool(_FACT_MEASURE_RE.search(s))


def _fact_boost(s: str) -> float:
    """Additiver Fakt-Boost eines Satzes (T223, Kai-Design §2.1).

    score += 0.15·hasYear + 0.10·min(markerHits,2) + 0.08·hasMeasure
    Klein gehalten, damit ein Fakt-Satz mit mittelgutem Cosine die Lead-Floskel
    überholt, OHNE das Cosine als Hauptsignal zu verdrängen. Kill-Switch über
    _FACT_BIAS_ENABLED → 0.0 (reines Cosine, T216-Verhalten).
    """
    if not _FACT_BIAS_ENABLED:
        return 0.0
    boost = 0.0
    if _has_year(s):
        boost += _FACT_W_YEAR
    mh = _marker_hits(s)
    if mh:
        boost += _FACT_W_MARKER * min(mh, _FACT_MARKER_CAP)
    if _has_measure(s):
        boost += _FACT_W_MEASURE
    return boost


# Literatur-/Fußnoten-Referenzen aus dem Lead filtern (Iter-135-Befund: im
# Einstein-Lead leckten „Vgl. Albert Einstein: Why Socialism?" und „Auf:
# einstein-online.info …" ins Summary und verdrängten echte Lead-Sätze wie die
# Nobelpreis-Begründung). clean_extract entfernt nur Bild-Captions, nicht
# Referenzen. Konservativ am Satzanfang verankert bzw. eindeutige Marker —
# ein normaler Prosa-Satz beginnt nicht mit „Vgl."/„Ebd."/„Siehe auch" und
# enthält selten „ISBN"/„Hrsg.". Damit verschwinden Refs, ohne Prosa zu treffen.
_REF_PREFIXES = ("vgl.", "ebd.", "ebenda", "siehe auch", "auf:", "in:", "abgerufen am")
_REF_MARKERS = ("isbn", "issn", "doi:", " hrsg.", "(hrsg.)")
# Seitenangabe „S. 171–175" / „S. 12 f." — eindeutiges Zitat-Signal, in Prosa
# praktisch nie. Fängt den Einstein-Lead-Leak „… New York 2010, S. 171–175.
# Staatsangehörigkeiten …" (Referenz an Abschnittskopf geklebt).
_REF_PAGE_RE = re.compile(r"\bS\.\s?\d", re.IGNORECASE)


def _is_reference_sentence(s: str) -> bool:
    """True für Literatur-/Fußnoten-Reste, die nicht ins Lead-Summary gehören."""
    low = s.strip().lower()
    if not low:
        return True
    if low.startswith(_REF_PREFIXES):
        return True
    if any(m in low for m in _REF_MARKERS):
        return True
    return bool(_REF_PAGE_RE.search(s))

# Satz-Ende-Erkennung: Punkt/!/? gefolgt von Whitespace+Großbuchstabe/Ende.
# Deutsche Abkürzungen (z. B., u. a., Dr., Nr., Jh.) NICHT als Satzende werten —
# sonst zerfällt „z. B. 1921" in zwei Pseudo-Sätze. Konservativ: Split nur, wenn
# das Zeichen vor dem Punkt KEINE der bekannten Abkürzungs-Endungen ist.
_ABBREV_TAILS = (
    "z. b", "u. a", "d. h", "u. ä", "o. ä", "s. o", "s. u", "i. d. r",
    "dr", "prof", "nr", "bzw", "ca", "vgl", "evtl", "ggf", "inkl", "etc",
    "jh", "jhdt", "geb", "gest", "sog", "abk", "max", "min", "mind",
)
# Satz-Ende: .!? + Whitespace + Satzanfang-Zeichen. ZWEI Schutz-Lookbehinds:
#   (?<![0-9]) — eine Ziffer vor dem Punkt ist Ordinal/Datum („14. März", „18.
#                April", „1879.") und KEIN Satzende. Das war der Iter-135-Bug:
#                der Einstein-Lead „(* 14. März 1879 in Ulm; † 18. April 1955…)"
#                zerfiel am Datum.
#   (?<![A-ZÄÖÜ]) — ein einzelner Großbuchstabe vor dem Punkt ist eine Initiale
#                („J. C. Maxwell", „A. Einstein") — ebenfalls kein Satzende.
# Satzanfang-Zeichen bewusst OHNE '(' — eine öffnende Klammer beginnt selten
# einen neuen Satz, aber HÄUFIG einen eingeklammerten Lebensdaten-Zusatz.
_SENT_SPLIT_RE = re.compile(
    r"(?<![0-9])(?<![A-ZÄÖÜ])([.!?])\s+(?=[A-ZÄÖÜ„»\"'])"
)


def _split_sentences(text: str) -> list[str]:
    """Zerlegt einen (bereits ge-clean_extract-eten) Body in Sätze.

    Verbatim — kein Umschreiben (T139). Schneidet an .!? + Whitespace +
    Großbuchstabe/Ziffer/Anführung, schützt gängige deutsche Abkürzungen vor
    Fehl-Splits. Filtert Fragmente (< _SUMMARY_MIN_SENTENCE_CHARS) und
    überlange „Sätze" (Listen-/Tabellenzeilen > _SUMMARY_MAX_SENTENCE_CHARS).
    """
    text = (text or "").strip()
    if not text:
        return []
    # Absatz-Umbrüche zu Leerzeichen — wir wollen Satz-Granularität, nicht Zeilen.
    flat = re.sub(r"\s*\n\s*", " ", text)
    flat = re.sub(r"[ \t]+", " ", flat)
    # Verklebter Satz-/Referenz-Übergang ohne Leerzeichen (Iter-135-Befund:
    # „…zur Welt kam.Christof Rieber…" — Fließtext-Ende + angeklebte Literatur-
    # Referenz). Ein Punkt zwischen Klein- und Großbuchstabe OHNE Space bekommt
    # ein Leerzeichen, damit der Splitter dort sauber trennt. Konservativ: nur
    # Kleinbuchstabe→Punkt→Großbuchstabe (keine Zahlen, keine Abkürzungen mit
    # Space — die sind hier per Definition nicht betroffen).
    flat = re.sub(r"(?<=[a-zäöüß])\.(?=[A-ZÄÖÜ])", ". ", flat)
    raw = _SENT_SPLIT_RE.split(flat)
    # _SENT_SPLIT_RE.split gibt [satz, delim, satz, delim, …] → rejoinen.
    sentences: list[str] = []
    buf = ""
    i = 0
    while i < len(raw):
        part = raw[i]
        delim = raw[i + 1] if i + 1 < len(raw) else ""
        candidate = (buf + part + delim).strip()
        low = candidate.lower().rstrip(".")
        # Endet der Kandidat auf einer Abkürzung? Dann mit nächstem Teil mergen.
        if delim == "." and any(low.endswith(tail) for tail in _ABBREV_TAILS):
            buf = candidate + " "
            i += 2
            continue
        if candidate:
            sentences.append(candidate)
        buf = ""
        i += 2
    out = []
    for s in sentences:
        s = s.strip()
        if not (_SUMMARY_MIN_SENTENCE_CHARS <= len(s) <= _SUMMARY_MAX_SENTENCE_CHARS):
            continue
        if _is_reference_sentence(s):
            continue  # Literatur-/Fußnoten-Rest — nicht ins Lead-Summary
        out.append(s)
    return out


def _embed_batch(texts: list[str]) -> Optional[list[list[float]]]:
    """EIN Ollama-/api/embed-Call über ALLE Texte (input-Array). 768d-Vektoren.

    Gibt None zurück, wenn Ollama nicht erreichbar ist / die Antwort fehlt —
    der Aufrufer fällt dann auf den vollen Extract zurück (Recall-Schutz).
    """
    if not texts:
        return None
    payload = json.dumps({
        "model": _SUMMARY_EMBED_MODEL,
        "input": texts,
        "keep_alive": -1,  # embeddinggemma resident halten (wie Backend-Pfad)
    }).encode("utf-8")
    req = urllib.request.Request(
        _OLLAMA_EMBED_URL, data=payload,
        headers={"Content-Type": "application/json"}, method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=_SUMMARY_EMBED_TIMEOUT_S) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        log.warning("[summary] embed-batch failed (%s) → voller Extract bleibt", e)
        return None
    embs = body.get("embeddings")
    if not embs or len(embs) != len(texts):
        log.warning("[summary] embed-batch Größe %s ≠ %d → Fallback",
                    len(embs) if embs else None, len(texts))
        return None
    return embs


def _cosine(a: list[float], b: list[float]) -> float:
    """Cosine-Similarity zweier Vektoren (gleiche Dim vorausgesetzt)."""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na <= 0.0 or nb <= 0.0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


def extractive_summary(
    full_text: str, query: str, n_sentences: int,
    rank_query: Optional[str] = None, max_scan: int = 0,
) -> Optional[dict]:
    """Tier-1: Top-N-Satz-Summary aus full_text, Cosine(Frage↔Satz)-gerankt.

    Mechanik:
      clean_extract → _split_sentences → EIN embed-Batch über [Frage, s1..sk]
      → Cosine(Frage, sᵢ) → Top-N Indizes → in ORIGINALREIHENFOLGE zusammen-
      fügen. Verbatim Wikipedia-Sätze (T139), kein Umschreiben.

    Anker-Entkopplung (T216, Nora): `query` ist der FTS-/Such-Anker (Content-
    Tokens, z.B. „Albert Einstein"). Für das SATZ-RANKING ist ein knapper Token-
    Anker aber semantisch arm — „Nobelpreis 1921" rankt gegen „Albert Einstein"
    schlechter als gegen die volle Frage „Wer war Einstein?". Liegt `rank_query`
    (die volle Nutzerfrage) vor, rankt die Cosine GEGEN diese; der FTS-`query`
    bleibt für Exakt-Match/Disambiguation unberührt. So echte Kompression OHNE
    Fakt-Verlust. Ohne `rank_query` → altes Verhalten (rankt gegen `query`).

    `max_scan` > 0 überschreibt das Lead-Scan-Fenster (Default
    `_SUMMARY_MAX_SENTENCES_SCANNED` = 12). Mehr Sätze = höherer Recall für
    Fakten tief im Lead (Radula-/Weinbergschnecke-Fall), kostet aber Embed-Latenz.

    Returns None (→ Aufrufer behält vollen Extract), wenn:
      - Summary deaktiviert / n_sentences ≤ 0,
      - keine ausreichenden Sätze,
      - Embed-Pass fehlschlägt (Ollama kalt/weg).
    Sonst dict: {text, sentences:[{text,cosine,index}], embedMs, scanned}.
    """
    if not SUMMARY_ENABLED or n_sentences <= 0 or not full_text:
        return None
    cleaned = clean_extract(full_text)
    if not cleaned.strip():
        cleaned = full_text
    sentences = _split_sentences(cleaned)
    if not sentences:
        return None
    # Rank-Anker: volle Frage, wenn übergeben (T216) — sonst FTS-Token-Query.
    rank_q = (rank_query or "").strip() or query
    scan_limit = max_scan if max_scan and max_scan > 0 else _SUMMARY_MAX_SENTENCES_SCANNED
    scanned = sentences[:scan_limit]
    # Artikel kürzer als das Budget → ganzer (geclean'ter) Lead ist das Summary.
    if len(scanned) <= n_sentences:
        return {
            "text": " ".join(scanned),
            "sentences": [
                {"text": s, "cosine": None, "index": i}
                for i, s in enumerate(scanned)
            ],
            "embedMs": 0,
            "scanned": len(scanned),
        }
    t0 = time.perf_counter()
    embs = _embed_batch([rank_q] + scanned)
    embed_ms = int((time.perf_counter() - t0) * 1000)
    if embs is None:
        return None
    q_vec = embs[0]
    s_vecs = embs[1:]
    # T223: Score = Cosine + additiver Fakt-Boost (Jahr/Marker/Maßzahl). Der
    # Boost hebt einen mittelguten-Cosine-Faktsatz über die Lead-Floskel, ohne
    # das Cosine als Hauptsignal zu ersetzen. Fakt-Bias aus → reines Cosine (T216).
    cos_by_i = [_cosine(q_vec, sv) for i, sv in enumerate(s_vecs)]
    scored = [
        (cos_by_i[i] + _fact_boost(scanned[i]), cos_by_i[i], i)
        for i in range(len(s_vecs))
    ]
    # T223 Lead-Anchor-Mix (Kai-Design §2.2): Satz 1 (Definition/Identität) IMMER
    # ins Summary („Wer war X" braucht den Identitäts-Satz), dann mit den Top-
    # score-Faktsätzen auffüllen bis n_sentences. De-Dupe gegen den Anker. So
    # bleibt die Definition UND der Schlüssel-Fakt (Nobelpreis 1921 / beide Curie-
    # Preise) im N-Budget. Anchor aus → reines Top-N nach Score (Rückwärtskompat).
    selected_idx: list[int] = []
    if _FACT_LEAD_ANCHOR and scanned:
        selected_idx.append(0)  # Satz 1 als Anker
    remaining = n_sentences - len(selected_idx)
    if remaining > 0:
        ranked = sorted(scored, key=lambda x: x[0], reverse=True)
        for score, cos, i in ranked:
            if i in selected_idx:
                continue
            selected_idx.append(i)
            remaining -= 1
            if remaining <= 0:
                break
    # In ORIGINALREIHENFOLGE zusammenfügen (Chronologie schützen).
    selected_idx = sorted(set(selected_idx))
    picked = [
        {"text": scanned[i], "cosine": round(cos_by_i[i], 4),
         "factBoost": round(_fact_boost(scanned[i]), 4), "index": i}
        for i in selected_idx
    ]
    return {
        "text": " ".join(p["text"] for p in picked),
        "sentences": picked,
        "embedMs": embed_ms,
        "scanned": len(scanned),
    }


# ── T140 (Iter-137, Nora+Lara): Extractive Zahlen-Vertrag ────────────────────
# WARUM: Ein generatives 4B-Modell *erzählt Zahlen nach* statt sie zu *zitieren*
# — „40.000" wird zu „vierzig"/„40000"/„4.000", eine Null verschwindet, ein Maß
# wird gerundet. Forschung 2025/26: Zahlen kopieren ist für ein LLM näher an
# Zufallsbits als an Sprache. Lösung NICHT im Output (Constrained Decoding =
# Lara-Veto, kein Self-Check = B-077), sondern VOR der Generierung: die Bridge
# markiert die Zahl-Spans im Extract und liefert sie als strukturiertes Feld
# `facts`. Der Prompt-Vertrag (Kotlin) instruiert e4b dann, GENAU diese Spans
# «wörtlich» zu übernehmen. Nur die Zahl ist heilig, nicht der Satz.
#
# DISZIPLIN: rein additiv + billig (Regex auf dem bereits gebauten Extract, KEIN
# extra Decompress). `facts` wird NUR befüllt, wenn die Query eine Zahl-Faktfrage
# ist (_NUMBER_FACT_QUERY_RE) — sonst leer, Default-Pfad byte-identisch. Greift
# bei jeder Faktfrage automatisch an (Nora: Faithfulness-kritisch, kein Param).
_NUMBER_FACT_QUERY_RE = re.compile(
    r"\b(wie\s+vie(?:le|l)|wie\s+hoch|wie\s+alt|wie\s+lang|wie\s+weit|"
    r"wie\s+gro[sß]|wie\s+tief|wie\s+schwer|wie\s+breit|wie\s+hei[sß]|"
    r"wie\s+warm|wie\s+kalt|wann|"
    r"welches\s+jahr|in\s+welchem\s+jahr|jahr)\b",
    re.IGNORECASE,
)
# Zeit-/Datums-Frage („wann", „welches Jahr") — hier ist eine Jahreszahl die
# erwartete Antwort. Bei einer MASS-/MENGEN-Frage („wie hoch/viele/…") ist eine
# nackte Jahreszahl dagegen fast immer Lärm (Lebensdaten, Listen) → dann liefern
# wir KEINE reinen Jahre, lieber leere facts → ehrliches „weiß ich nicht".
_YEAR_QUESTION_RE = re.compile(
    r"\b(wann|welches\s+jahr|in\s+welchem\s+jahr|jahr)\b", re.IGNORECASE
)
# Zahl-Token: Tausender-gruppiert (40.000 / 1 000) oder einfach (8848 / 1,5 / 12).
_NUM_TOKEN_RE = re.compile(r"\d{1,3}(?:[. ]\d{3})+(?:[.,]\d+)?|\d+(?:[.,]\d+)?")
_FACT_YEAR_FULL_RE = re.compile(r"^(1\d{3}|20\d{2})$")
# Einheits-/Mengen-Wort, das direkt auf eine Zahl folgen darf → bleibt Teil des
# Fakt-Spans („40.000 Zähnchen", „8848 m", „83 Millionen"). Klein gehalten +
# konservativ (Kai-Veto: keine Domain-Tabelle, nur generische Maße/Mengen).
_FACT_UNIT_WORDS = {
    "%", "°", "°c", "°f", "km²", "m²", "km", "cm", "mm", "dm", "m", "kg", "g",
    "mg", "t", "l", "ml", "euro", "dollar", "mark", "jahre", "jahren", "jahr",
    "einwohner", "einwohnern", "millionen", "milliarden", "tausend", "mio",
    "mrd", "zähnchen", "zähne", "zähnen", "zahn", "meter", "kilometer",
    "zentimeter", "millimeter", "tonnen", "tonne", "prozent", "hektar",
    "lichtjahre", "lichtjahr", "grad", "kelvin", "ps", "kw", "kwh",
    # Dativ-Plural-Flexion („368 Metern", „2857 Kilometern") — sonst wird die
    # Einheit nicht erkannt und der Span fällt als bare Zahl raus (live: „368
    # Metern" → kein Fakt). Generisch, keine Domain-Tabelle.
    "metern", "kilometern", "zentimetern", "millimetern",
}
_FACT_FOLLOW_RE = re.compile(r"\s?([A-Za-zÄÖÜäöüß%°²³]+)")
# Zweites Mengen-Wort (z.B. „83 Millionen Einwohner") — nur direkt nach einem
# bereits akzeptierten Mengen-Wort, damit „Millionen Einwohner" zusammenbleibt.
_FACT_SECOND_WORDS = {"einwohner", "einwohnern", "menschen", "personen", "stück"}
# Zeit-Dauer-Einheiten: „65 Jahren", „850 Jahre", „6 Monate". Bei einer JAHR-Frage
# („wann/welches Jahr") ist die Antwort ein KALENDERJAHR (1879), nie eine Dauer/
# ein Alter — solche Spans dürfen das echte Jahr nicht aus den Top-Fakten drängen.
_TIME_DURATION_UNITS = {
    "jahr", "jahre", "jahren", "monat", "monate", "monaten", "tag", "tage",
    "tagen", "woche", "wochen", "stunde", "stunden", "jahrhundert",
    "jahrhunderte", "jahrhunderten", "jahrzehnt", "jahrzehnte",
}
# T140-Recall: Lead-Fenster (Zeichen), aus dem bei JAHR-Fragen zusätzlich Jahr-
# Fakten gezogen werden. Der Wikipedia-Lead trägt die kanonischen Vita-Jahre
# („* 1879 … † 1955"); 700 deckt den Einleitungssatz + erste Fakten, ohne tief in
# den verrauschten Body zu greifen.
_YEAR_LEAD_CHARS = 700


# T140: Zitat-/Quellen-Kontext rund um eine Zahl — Seitenangabe, ISBN/ISSN,
# „Auf:/In:/Vgl.", Jahresangabe am Listen-/Filmografie-Ende. In so einem Kontext
# ist eine Zahl KEINE Antwort auf „wie viele/wann/wie hoch", sondern Bibliografie-
# Lärm (live gemessen: Everest-Filmografie 1984/1997/…, „S. 423"). Konservativ:
# wir prüfen ein kleines Fenster VOR der Zahl auf eindeutige Zitat-Marker.
_REF_CONTEXT_RE = re.compile(
    r"(S\.\s?|ISBN|ISSN|DOI|Hrsg\.|Auf:|In:|Vgl\.|Ebd\.|abgerufen)\s*$",
    re.IGNORECASE,
)


# ── T140 (Reparatur, Iter-138, Nora): Subjekt-Bindung ───────────────────────
# PROBLEM (live gemessen, 3/3 falsch): extract_number_facts nahm JEDE Zahl im
# Extract in Textreihenfolge — ohne zu prüfen, ob sie überhaupt zur GESTELLTEN
# Frage gehört. „wann wurde Einstein geboren" → [1982,1986,…] (Publikationsjahre
# aus der Bibliografie); „wie hoch ist der Eiffelturm" → [3.155.000] (Besucher).
# FIX: aus der fact_query das/die Subjekt-Nomen + die erwartete Maß-Einheit
# ziehen und einen Zahl-Span NUR behalten, wenn seine Umgebung (gleiches Fenster)
# einen Subjekt-Stamm ODER die erwartete Einheit trägt. Kein Treffer → leere
# facts (ehrliche Abstention, Faithfulness > Recall — Nora-Veto).
#
# Maß-Frage → erwartete Einheit-Klasse. Bindet „wie hoch" an Meter/Höhe, nicht an
# Tonnen/Besucher. Konservativ + generisch (Kai-Veto: keine Domain-Tabelle).
_MEASURE_UNIT_HINTS: dict[str, set[str]] = {
    "hoch": {"m", "meter", "metern", "km", "kilometer", "kilometern", "cm",
             "zentimeter", "zentimetern", "fuß", "höhe"},
    "tief": {"m", "meter", "metern", "km", "kilometer", "kilometern", "cm",
             "zentimeter", "zentimetern", "tiefe"},
    "lang": {"m", "meter", "metern", "km", "kilometer", "kilometern", "cm",
             "zentimeter", "zentimetern", "mm", "länge"},
    "weit": {"m", "meter", "metern", "km", "kilometer", "kilometern", "länge",
             "entfernung"},
    "breit": {"m", "meter", "metern", "km", "kilometer", "cm", "mm", "breite"},
    "schwer": {"kg", "g", "t", "tonnen", "tonne", "gramm", "kilogramm", "gewicht"},
    "groß": {"m²", "km²", "hektar", "einwohner", "einwohnern", "fläche"},
    # „wie alt wurde X" — Antwort ist ein Alter in Jahren („starb im Alter von 76
    # Jahren"). Über den Lead+Gate-Pfad: steht das explizite Alter im Lead → genau
    # dieser Span; sonst abstinieren statt der falschen Passage-Zahl („65 Jahren"
    # eines anderen). Faithfulness > Recall.
    "alt": {"jahre", "jahren", "jahr"},
}
# W-Wort/Floskel-Tokens, die NICHT zum Subjekt gehören (raus vor der Nomen-Wahl).
_FACT_QUERY_STOP = {
    "wie", "viel", "viele", "hoch", "tief", "lang", "weit", "breit", "groß",
    "gross", "schwer", "alt", "wann", "welches", "welchem", "jahr", "in", "ist",
    "war", "sind", "waren", "hat", "haben", "hatte", "der", "die", "das", "ein",
    "eine", "einer", "einem", "einen", "den", "dem", "des", "von", "vom", "zu",
    "und", "oder", "es", "er", "sie", "man", "wurde", "wird", "werden", "etwa",
    "ungefähr", "ungefaehr", "circa", "ca", "mir", "mal", "denn", "eigentlich",
    "geboren", "gestorben", "gegründet", "gegruendet", "erbaut", "gebaut",
}
# Stamm-Länge für den Komposita-/Flexions-Match: „Zähne"→„zähn" matcht
# „Zähnchen"/„Zähnen". 4 Zeichen — kurz genug für Komposita, lang genug gegen
# Allerwelts-Präfixe.
_STEM_LEN = 4


def _stem(word: str) -> str:
    return word.lower()[:_STEM_LEN]


def _stem_match(word: str, stems: set[str]) -> bool:
    """Trägt `word` (eines der Subjekt-Nomen)? Präfix-Match auf _STEM_LEN,
    bidirektional via startswith — fängt Komposita/Flexion („Zähnchen"~„Zähne").
    """
    w = word.lower()
    if len(w) < _STEM_LEN:
        return w in stems
    return _stem(w) in stems


def _fact_query_cues(
    fact_query: str,
) -> tuple[set[str], set[str], set[str], bool, bool, bool]:
    """Zieht aus der Frage Subjekt-Stämme + erwartete Einheit-Hints.

    Rückgabe: (subject_stems, unit_hints, year_markers, is_count, is_measure,
    is_year).
      - subject_stems: gestemmte Kern-Nomen („Zähne"→„zähn", „Eiffelturm"→
        „eiff") für den Nähe-/Follow-Wort-Test um eine Zahl herum.
      - unit_hints: erwartete Einheiten aus einer Maß-Frage („wie hoch"→
        {m,meter,höhe,…}). Leer bei Zähl-/Zeit-Fragen.
      - is_count: Zähl-Frage („wie viele X") — gebunden über das FOLGE-Nomen
        am Span (die gezählte Sache, „40.000 Zähnchen").
      - is_measure: Maß-Frage (wie hoch/lang/schwer/…) — gebunden über die
        erwartete Einheit + Subjekt-Nähe.
      - is_year: Zeit-/Datums-Frage (wann/welches Jahr).
    """
    if not fact_query:
        return set(), set(), set(), False, False, False
    low = fact_query.lower()
    is_year = bool(_YEAR_QUESTION_RE.search(fact_query))
    is_count = bool(re.search(r"\bwie\s+vie(?:le|l)\b", low))
    # Ereignis-Marker für Zeit-Fragen: „geboren"→deutsche Geburtskonvention „*",
    # „gestorben"→„†". Bindet das Jahr an das richtige Ereignis statt an irgendeine
    # Jahreszahl in der Vita. (Genutzt unten als Fenster-Test VOR dem Jahr.)
    year_markers: set[str] = set()
    if re.search(r"\bgeboren|geburt|geb\.", low):
        year_markers = {"*", "geb", "* "}
    elif re.search(r"\b(ge)?storben|starb|verstarb|gestarb|tod|todes", low):
        # „wann starb X" — das Grundwort „starb" fehlte hier, dadurch band
        # „wann starb Einstein" das Todesjahr 1955 NICHT (live gemessen, LEAD=[]).
        year_markers = {"†", "gest", "verst", "starb"}
    elif re.search(r"\bgegr[üu]ndet|gr[üu]ndung", low):
        year_markers = {"gegründet", "gegruendet", "gründ", "gegr"}
    elif re.search(r"\b(er|ge)?baut|errichtet|fertiggestellt|er[öo]ffnet", low):
        year_markers = {"baut", "errich", "eröff", "eroeff", "fertig", "vollend"}
    unit_hints: set[str] = set()
    for w, hints in _MEASURE_UNIT_HINTS.items():
        if re.search(rf"\bwie\s+{w}\b", low) or (w == "groß" and "wie gross" in low):
            unit_hints |= hints
    is_measure = bool(unit_hints)
    # Subjekt-Stämme: Tokens ohne Floskeln, ≥4 Zeichen, auf _STEM_LEN gekürzt.
    stems: set[str] = set()
    for tok in re.findall(r"[a-zA-ZÄÖÜäöüß]+", low):
        if tok in _FACT_QUERY_STOP or len(tok) < 4:
            continue
        stems.add(_stem(tok))
    return stems, unit_hints, year_markers, is_count, is_measure, is_year


def extract_number_facts(
    text: str, max_facts: int = 4, *, allow_years: bool = True,
    fact_query: str = "", subject: str = "",
) -> list[str]:
    """Extrahiert wörtliche Zahl-Fakt-Spans aus einem Extract (T140).

    Findet Maß-/Mengen-Zahlen (Zahl + optionales Einheits-/Mengen-Wort) und
    Jahreszahlen, in Textreihenfolge, dedupliziert. Verbatim — der Span ist
    GENAU der Teilstring aus dem Extract, damit der Prompt-Vertrag ihn 1:1
    «wörtlich» übernehmen lassen kann.

    Lärm-Filter (live geeicht):
      - reine kurze Zähl-Integers ohne Einheit („1", „2") raus,
      - Zahlen in eindeutigem Zitat-Kontext („S. 423", ISBN, „Auf: …, 2010") raus
        — das ist Bibliografie, keine Antwort,
      - Maß-/Mengen-Spans (Zahl + Einheit) zuerst, dann Jahre: damit „8848 m"
        oder „40.000 Zähnchen" vor einer Liste von Jahreszahlen landet.
    Deckel max_facts (default 4) hält den Vertrag fokussiert — eine Wand aus
    Zahlen verwässert das „übernimm GENAU diesen Wert".
    """
    if not text:
        return []
    # T140-Reparatur: Subjekt-Bindung. Mit fact_query ziehen wir Subjekt-Stämme
    # + erwartete Einheiten und behalten einen Span NUR, wenn seine Umgebung
    # einen davon trägt. Ohne fact_query bleibt das Alt-Verhalten (Rückwärts-
    # kompat für die Unit-Tests + Aufrufer ohne Frage).
    (stems, unit_hints, year_markers, q_is_count, q_is_measure,
     q_is_year) = _fact_query_cues(fact_query)
    bind = bool(fact_query) and (bool(stems) or bool(unit_hints))
    # Entity-Stämme (der gefragte Eigenname, z.B. „Albert Einstein") — die kommen
    # in IHREM Artikel überall vor und taugen daher NICHT zur Jahr-Bindung. Für
    # eine Zeit-Frage ohne Struktur-Marker binden wir deshalb an die EREIGNIS-
    # Stämme = Frage-Nomen MINUS Entity (z.B. „Nobelpreis"), eng am Jahr.
    entity_stems: set[str] = set()
    for tok in re.findall(r"[a-zA-ZÄÖÜäöüß]+", subject.lower()):
        if len(tok) >= 4:
            entity_stems.add(_stem(tok))
    event_stems = {s for s in stems if s not in entity_stems}
    # Nähe-Fenster um die Zahl (Zeichen vor/nach) für den Subjekt-/Einheit-Test.
    WIN = 60
    measures: list[str] = []   # Zahl + Einheit (höchste Priorität)
    years: list[str] = []      # nackte Jahreszahlen
    seen: set[str] = set()
    # Mit Bindung: Spans, deren Fenster die ERWARTETE Einheit trägt, zuerst —
    # vor Spans, die nur über einen Subjekt-Stamm gebunden sind.
    unit_matched: list[str] = []
    for m in _NUM_TOKEN_RE.finditer(text):
        num = m.group(0)
        # Zitat-/Quellen-Kontext direkt vor der Zahl? → Bibliografie-Lärm, skip.
        before = text[max(0, m.start() - 12):m.start()]
        if _REF_CONTEXT_RE.search(before):
            continue
        # Nähe-Fenster (für Subjekt-/Einheit-Bindung), klein gehalten.
        window = text[max(0, m.start() - WIN):m.end() + WIN].lower()
        # Bindestrich-verkettete Ziffern (ISBN-/Code-Chunk, „978-3-16-148410-0")
        # — eine Zahl mit '-' oder Ziffer direkt davor/danach ist kein Fakt.
        prev_ch = text[m.start() - 1] if m.start() > 0 else ""
        next_ch = text[m.end()] if m.end() < len(text) else ""
        if prev_ch in "-" or next_ch == "-" or prev_ch.isdigit():
            continue
        span = num
        unit: Optional[str] = None
        follow_word: str = ""   # rohes Folgewort (für die Zähl-Bindung „N Zähne")
        follow = _FACT_FOLLOW_RE.match(text, m.end())
        if follow:
            w = follow.group(1)
            follow_word = w
            if w.lower().rstrip(".") in _FACT_UNIT_WORDS or w == "%":
                unit = w
                span = f"{num} {w}"
                # Zweites Mengen-Wort anhängen (z.B. „Millionen Einwohner").
                follow2 = _FACT_FOLLOW_RE.match(text, follow.end())
                if follow2 and follow2.group(1).lower() in _FACT_SECOND_WORDS:
                    span = f"{span} {follow2.group(1)}"
        key = span.lower()
        if key in seen:
            continue
        # ── Subjekt-Bindung (T140-Reparatur) ──────────────────────────────
        # Je Frage-Typ ein eigenes, KONSERVATIVES Bindungs-Kriterium. Kein
        # Treffer → die Zahl fällt raus (lieber leer als falsch).
        subj_near = any(s in window for s in stems) if stems else False
        # Einheit-Nähe NUR als ganzes Token (nicht Substring!) — sonst matcht das
        # 1-Zeichen-„m"/„g"/„t" in „Masse"/„reine"/„Eiffelturm" alles. Mehrere
        # Einheiten-Schreibweisen (Meter/m) sind in unit_hints, das reicht.
        unit_near = bool(
            unit_hints and re.search(
                r"\b(" + "|".join(re.escape(u) for u in unit_hints) + r")\b",
                window,
            )
        )
        # Trägt der Span SELBST die erwartete Einheit-Klasse (z.B. „330 Meter")?
        span_unit_ok = bool(
            unit_hints and unit is not None
            and unit.lower().rstrip(".") in unit_hints
        )
        if bind:
            # JAHR-Frage: die Antwort ist immer ein KALENDERJAHR (unten im
            # Jahr-Zweig), nie ein Maß. Jeder Einheits-Span — eine Dauer/ein
            # Alter („65 Jahren", „850 Jahre") ebenso wie eine Länge/Höhe
            # („47 cm", „330 Meter") — ist hier Lärm und würde das echte Jahr
            # (1879) aus den Top-Fakten drängen (live im Volltext gemessen).
            # Faithfulness > Recall. (_TIME_DURATION_UNITS bleibt als sprechende
            # Doku der häufigsten Fehl-Spans erhalten.)
            if q_is_year and unit is not None:
                continue
            if q_is_count:
                # ZÄHL-Frage („wie viele Zähne"): die gezählte Sache muss DIREKT
                # am Span stehen — das Folgewort am Span muss ein Subjekt-Nomen
                # sein („40.000 Zähnchen"). Ohne passendes Folge-Nomen ist die
                # Zahl nicht die gefragte Menge (Lebensdauer/Größe/Gewicht) → raus.
                if not (follow_word and _stem_match(follow_word, stems)):
                    continue
            elif unit_hints:
                # MASS-Frage („wie hoch/lang/schwer"): Span trägt die erwartete
                # Einheit (oder sie steht eng im Fenster) UND Subjekt-Nähe. Sonst
                # ist es ein anderes Maß (Besucherzahl/Tonnage) → raus.
                if not (span_unit_ok or unit_near):
                    continue
                if stems and not subj_near:
                    continue
            else:
                # Restfälle (z.B. „wie alt"): mindestens Subjekt-Nähe verlangen.
                # AUSNAHME (Mozart-Fix 2026-07-02, Nora): Ereignis-Zeitfrage MIT
                # Marker („wann starb/geboren/gegründet …") — hier ersetzt die
                # Marker-Bindung die Subjekt-Nähe. Der Jahr-Zweig unten verlangt
                # den Marker DICHT VOR dem Jahr (±30, „† … 1791"); das ist die
                # STÄRKERE Bindung, Subjekt-Nähe wäre Doppel-Prüfung. Live-Fall
                # (echte DB): der Mozart-Lead ist mit Taufbuch-/Sterbebuch-
                # Zitationen verklebt („…Salzburg;AES, …Taufbuch…, S. 3.,
                # abgerufen…") — dadurch liegt „† 5. Dezember 1791" >60 Zeichen
                # von jedem Subjekt-Stamm, und dieses Gate warf das korrekte
                # Jahr raus, BEVOR der Marker-Check überhaupt lief ⇒ facts=[].
                # Gewählt statt Zitations-Putzen (Variante b): kein Text-
                # Rewriting (verbatim-Spans bleiben verbatim), kleinste Fläche.
                # Sicher, weil in diesem Pfad (q_is_year + year_markers) NUR
                # marker-gebundene Kalenderjahre überleben KÖNNEN: Einheits-
                # Spans sterben oben (q_is_year and unit), bare Nicht-Jahre im
                # bare-Zweig (q_is_year → continue), Jahre ohne Marker im
                # Jahr-Zweig. Dazu hält das Single-Value-Gate im Caller.
                if stems and not subj_near and not (q_is_year and year_markers):
                    continue
        if unit is not None:
            seen.add(key)
            (unit_matched if (bind and (span_unit_ok or unit_near)) else measures).append(span)
        elif _FACT_YEAR_FULL_RE.match(num):
            if bind and (q_is_count or unit_hints):
                # Zähl-/Maß-Frage: ein nacktes Jahr ist nie die Antwort.
                continue
            if bind and q_is_year and year_markers:
                # Ereignis-gebundene Zeit-Frage („wann geboren/gestorben/
                # gegründet/gebaut"): der passende Marker muss DICHT VOR dem Jahr
                # stehen (deutsche Konvention „* … 1879" / „† … 1955"). Sonst ist
                # es eine andere Jahreszahl der Vita → raus.
                pre = text[max(0, m.start() - 30):m.start()].lower()
                if not any(mk in pre for mk in year_markers):
                    continue
            elif bind and q_is_year:
                # Zeit-Frage ohne Struktur-Marker (z.B. „wann bekam … Nobelpreis"):
                # an die EREIGNIS-Nomen der Frage (ohne Entity) binden, ENG am
                # Jahr (±30). Kein Ereignis-Nomen vorhanden → reine Vita-Jahre,
                # nicht entscheidbar → abstinieren (Faithfulness > Recall).
                if not event_stems:
                    continue
                near = text[max(0, m.start() - 30):m.end() + 30].lower()
                if not any(es in near for es in event_stems):
                    continue
            seen.add(key)
            years.append(span)
        else:
            # Zahl ohne Einheit und kein 1xxx/20xx-Jahr.
            digits = num.replace(".", "").replace(",", "").replace(" ", "")
            is_grouped = ("." in num) or ("," in num)
            if bind:
                # T140-Faithfulness-Härtung (Iter-138, Nora-Review): die alte
                # „≥3-stellig"-Klausel WAR das Leck — bare Seitenzahlen aus
                # Zitaten („Albert Einstein… 119") sind 3-stellig und stehen eng
                # am Subjekt-Namen, also rutschten 119/120/135/2334 als „Fakt"
                # durch. Live gemessen gg. echte DB. Faithfulness > Recall:
                #   Fix A: Jahr-Frage will ein ECHTES Jahr (1xxx/20xx). Eine bare
                #     Nicht-Jahr-Zahl ist dort nie die Antwort → abstinieren.
                #   Fix B: bare Zahl nur als Zähl-Antwort (Folge-Nomen oben in
                #     Z.1104-1110 bereits validiert, deckt „206 Knochen"/„16
                #     Bundesländer") ODER wenn gruppiert („40.000"). Sonst ist es
                #     Aufzähl-/Seitenzahl-Lärm → raus.
                if q_is_year:
                    continue
                count_validated = bool(
                    q_is_count and follow_word and _stem_match(follow_word, stems)
                )
                if not is_grouped and not count_validated:
                    continue
                # Zähl-Treffer ohne Einheits-Wort: das gezählte Nomen an den Span
                # hängen (verbatim — `follow_word` steht direkt hinter der Zahl),
                # damit der Vertrag „206 Knochen" statt nacktem „206" bekommt.
                if count_validated and span == num:
                    span = f"{num} {follow_word}"
                    key = span.lower()
                    if key in seen:
                        continue
            else:
                # Rückwärtskompat (kein fact_query): Alt-Verhalten — ungruppierte
                # <3-stellige Zahl raus, sonst behalten.
                if not is_grouped and len(digits) < 3:
                    continue
            seen.add(key)
            measures.append(span)  # gruppierte/große/gezählte Zahl = belastbarer Fakt
    # Einheit-gebundene Spans zuerst (die erwartete Antwort), dann übrige Maße,
    # dann Jahre. Bei MASS-/MENGEN-Fragen (allow_years=False) NUR Maße: nackte
    # Jahre wären hier Lärm (Lebensdaten/Listen) → kein erfundener „Fakt".
    ordered = unit_matched + measures
    if not allow_years:
        return ordered[:max_facts]
    return (ordered + years)[:max_facts]


# ── FTS5-Match-Query bauen (1:1 wie Kotlin) ─────────────────────────────────
_ALLOWED_EXTRA = set("äöüßéèêáàâñ ")

# Iter-94g Bridge-Fix: deutsche Stop-Words rausfiltern, sonst läuft FTS5 mit
# 8 nicht-selektiven OR-Terms (was OR steht OR der OR über OR den OR …) bis
# 8 Sekunden auf 5M Artikeln. Nora-Lesson aus Iter-94g: Wikipedia wörtlich
# als Token bringt eh nichts wenn 100% der Artikel aus Wikipedia kommen.
_GERMAN_STOPWORDS = {
    "der", "die", "das", "den", "dem", "des",
    "ein", "eine", "einer", "eines", "einem", "einen",
    "und", "oder", "aber", "doch", "wenn", "als", "wie",
    "ist", "war", "sind", "waren", "hat", "hatte", "wird", "werden",
    "kann", "soll", "muss", "will", "darf", "mag",
    "ich", "mir", "mich", "mein", "meine",
    "du", "dir", "dich", "dein", "deine",
    "er", "ihm", "ihn", "sein", "seine",
    "sie", "ihr", "ihnen", "ihre",
    "es", "wir", "uns", "unser",
    "in", "an", "auf", "bei", "mit", "zu", "von", "für", "durch", "ohne",
    "über", "unter", "vor", "nach", "neben", "zwischen",
    "was", "wer", "wo", "wann", "warum", "welche", "welcher", "welches",
    "nicht", "kein", "keine", "nur", "auch", "noch", "schon",
    "sich", "sehr", "mehr", "viel", "viele",
    "denn", "dass", "weil", "ob",
    # Bridge-spezifisch: "wikipedia" als Token bringt nichts (alles ist Wiki)
    "wikipedia", "wiki", "artikel",
    # Verb-Plattmacher die häufig in „Kannst du in Wikipedia nachschauen …"
    "steht", "nachschauen", "schauen", "sagen", "erzähl", "erzähle", "erkläre",
    "kannst", "könntest", "magst",
}


def content_tokens(query: str) -> list[str]:
    """Inhaltliche Tokens einer Anfrage: ≥3 Zeichen, kein Stop-Word, distinct.

    Spiegelt WikiGroundingService.contentTokens (Kotlin) und ist die Basis
    sowohl für die FTS5-Match-Query als auch für den Titel-Match.
    """
    if not query.strip():
        return []
    lower = query.lower()
    cleaned = []
    for ch in lower:
        if ch.isalnum() or ch in _ALLOWED_EXTRA:
            cleaned.append(ch)
        else:
            cleaned.append(" ")
    seen: set[str] = set()
    unique: list[str] = []
    for t in "".join(cleaned).split():
        if len(t) > 2 and t not in _GERMAN_STOPWORDS and t not in seen:
            seen.add(t)
            unique.append(t)
    return unique


def to_fts_match_query(query: str) -> str:
    # max 8 Tokens, OR-verknüpft (großzügiges Recall — re-ranked danach).
    unique = content_tokens(query)[:8]
    return " OR ".join(f'"{t}"' for t in unique)


# ── FastAPI ─────────────────────────────────────────────────────────────────
app = FastAPI(title="hoshi-knowledge-bridge", version="0.1.0")


class HealthResponse(BaseModel):
    status: str
    articleCount: int
    dbPath: str
    dbSizeGb: float


class WikiSearchHit(BaseModel):
    articleId: int
    title: str
    matchedClassification: str
    aliasIdx: int
    bm25Score: float
    extract: str
    hasKern: bool
    plaintextBytes: int = 0
    attribution: str = "Aus Wikipedia, CC-BY-SA"
    # Tier-1 (Iter-135): extraktives Top-N-Satz-Summary, Cosine(Frage↔Satz)-
    # gerankt, in Originalreihenfolge. Nur befüllt, wenn summary_sentences>0 und
    # der Embed-Pass gelang. `extract` (Tier-2, voll) bleibt IMMER unverändert.
    # null = kein Summary (Param 0/unset ODER Fallback) → Backend nutzt extract.
    summary: Optional[str] = None
    summarySentenceCount: int = 0
    # T140 (Iter-137, Nora+Lara): extractive Zahl-Fakt-Spans aus dem `extract`,
    # NUR befüllt wenn die Query eine Zahl-Faktfrage ist (_NUMBER_FACT_QUERY_RE).
    # Der Prompt-Vertrag lässt diese Spans «wörtlich» übernehmen. Leer = keine
    # Zahl-Frage / kein Span gefunden → der Vertrag fordert dann ehrliches
    # „weiß ich nicht" statt einer erfundenen Zahl.
    facts: list[str] = []


class WikiSearchResponse(BaseModel):
    query: str
    durationMs: int
    totalHits: int
    hits: list[WikiSearchHit]


class ArticleResponse(BaseModel):
    articleId: int
    title: str
    plaintextLength: int
    extract: str
    hasKern: bool
    attribution: str = "Aus Wikipedia, CC-BY-SA"
    # Tier-1 (Iter-135): siehe WikiSearchHit. null = kein Summary.
    summary: Optional[str] = None
    summarySentenceCount: int = 0
    # T140 (Iter-137): siehe WikiSearchHit.facts.
    facts: list[str] = []


# ── Article-Count-Cache (Iter-96 Bridge-Watchdog-Fix, Felix) ────────────────
# WARUM: /health rief bei JEDEM Probe ein `SELECT COUNT(*) FROM articles` auf
# einer 7,8-GB-/4,98-M-Zeilen-Tabelle → voller Table-Scan. Bei warmem OS-Page-
# Cache ist das 0,04s; aber sobald Memory-Druck (16-GB-Mac!) den Cache evictet,
# muss der erste COUNT GB von Platte page-innen → >curl-Timeout. Folge: der
# Watchdog hielt eine GESUNDE Bridge für tot und killte sie im ~3-min-Takt
# (Server-Log zeigt sauberes „Application shutdown", keinen Crash). Health darf
# nicht von Memory-Druck abhängen — sonst killt der Watchdog genau dann, wenn
# das System eh schon klemmt.
#
# FIX: articleCount EINMAL beim Start zählen (DB ist read-only + statisch) und
# cachen. /health prüft danach nur noch, dass die Connection ÖFFNET (billig,
# kein Scan). Das ist die ehrliche Health-Frage: „Lebt der Server + ist die DB
# erreichbar?" — nicht „Kann ich unter Last 5 M Zeilen scannen?".
_article_count_cache: Optional[int] = None


def _article_count() -> int:
    """Article-Count gecacht (DB read-only/statisch). Erst-Zählung lazy."""
    global _article_count_cache
    if _article_count_cache is None:
        with open_conn() as conn:
            _article_count_cache = conn.execute(
                "SELECT COUNT(*) FROM articles"
            ).fetchone()[0]
    return _article_count_cache


@app.on_event("startup")
def _warm_article_count() -> None:
    """Article-Count beim Start vorzählen, damit der erste /health nicht scannt."""
    try:
        n = _article_count()
        log.info("article-count vorgewärmt: %d", n)
    except Exception:
        # Nicht fatal: /health zählt dann beim ersten Aufruf lazy nach.
        log.warning("article-count-Vorwärmung fehlgeschlagen — wird lazy nachgeholt")


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    """Billiger Health-Check: Connection öffnen (kein Table-Scan) + gecachter Count.

    Bewusst KEIN COUNT(*) pro Request — siehe Cache-Kommentar oben. Eine offene
    Read-Only-Connection beweist, dass DB-Datei + SQLite intakt sind; das ist
    Memory-druck-resistent und damit watchdog-tauglich.
    """
    try:
        # Billige Liveness-Probe: Connection öffnen + triviale Query, kein Scan.
        with open_conn() as conn:
            conn.execute("SELECT 1").fetchone()
        return HealthResponse(
            status="ok",
            articleCount=_article_count(),
            dbPath=str(DB_PATH),
            dbSizeGb=round(DB_PATH.stat().st_size / 1e9, 2),
        )
    except Exception as e:
        log.exception("health failed")
        raise HTTPException(status_code=500, detail=str(e))


# ── Task #54 (Iter-97): Hauptnomen-Titel-Boost gegen BM25-OR-Lärm ────────────
# Andi-Bug „Haben Meerschweinchen einen Schwanz" → grounded mit „Baumschliefer".
# Wurzel: Die reine OR-FTS über die Klassifikations-Aliase gewichtet jedes Token
# gleich. {meerschweinchen, schwanz} OR-verknüpft → kurze „…schwanz"-Aliase
# (Taubenschwänzchen, Hausrotschwanz, Schliefer) bekamen den besten BM25-Score,
# der echte „Hausmeerschweinchen"/„Meerschweinchen" landete weiter unten und fiel
# beim alten LIMIT raus → die Disambiguation sah nur Müll und konnte nichts retten.
#
# Befund (Live-DB): Über-fetcht man die Alias-FTS auf Top-15, sind die richtigen
# Artikel ALLE schon dabei — nur lexikalisch out-ranked. Ein DB-Titel-Scan
# (title_norm LIKE '%tok%') ist NICHT nötig und auf der 7,8-GB-DB unter
# Memory-Druck katastrophal langsam (Leading-Wildcard = Full-Scan, hier 26–80s
# gemessen → sprengt das 5s-Backend-Timeout). Stattdessen:
#
# Fix: Über-fetchen + IN-MEMORY-Re-Rank. Jeder Kandidat dessen TITEL ein
# Content-Token (Hauptnomen) der Frage enthält bekommt einen Score-Bonus
# (exakter Titel > Titel-Substring), proportional zur Token-Länge. Kein extra
# DB-I/O, sub-ms. So führt der echte Artikel und embeddinggemma feinjustiert.
_TITLE_MATCH_BONUS = 6.0

# Task #61 (Iter-99): Exakter Voll-Titel-Match muss dominieren.
# Andi-Bug „Wie viele Zähne hat eine Weinbergschnecke" → der ROMAN „Zähne zeigen"
# (Klassifikations-Treffer auf das Nebenwort „Zähne", BM25 ~-9.7 + Substring-
# Titelbonus -7.5 = -17.18) schlug die per Titel-Lookup gemergte echte
# „Weinbergschnecke" (kein BM25, nur exakter Titelbonus -13.8). Ein exakter
# Voll-Titel-Match (der Artikel-Titel IST das Hauptnomen der Frage) ist das
# stärkste Relevanz-Signal das die Bridge hat — er muss einen reinen
# Klassifikations-Treffer eines NEBENWORTS sicher überstimmen. BM25-Scores in
# dieser DB liegen ~-10..-25; mit diesem Bonus erreicht ein exakter Titel-Match
# ~-18..-22 und schlägt den Nebenwort-Roman, OHNE den korrekt-klassifizierten
# Artikel zu verdrängen (der bekommt BM25 *und* denselben Exakt-Bonus → bleibt
# vorn, z.B. Meerschweinchen/Einstein/Kölner Dom).
_EXACT_TITLE_BONUS = 12.0

# Nacht-W6 (Nora-Design, Modus-2-Disambiguation): Coverage-Bonus. `_title_boost`
# nahm bisher nur `max` über die Tokens — ein Titel, der MEHR distinct Query-
# Tokens trägt, war damit nicht besser als einer mit nur einem. „Mount Everest"
# ({mount,everest}=2) bekam denselben Boost wie „Everest (2015)" ({everest}=1).
# Pro zusätzlich abgedecktem Query-Token ein kleiner Bonus, bei 2 Extra-Tokens
# gedeckelt (Nora: verhindert, dass ein Listen-/Sammeltitel „Liste der …" einen
# exakten Single-Token-Hauptartikel überholt). Klein gehalten (< _EXACT_TITLE_BONUS).
_COVERAGE_PER_TOKEN = 3.0
# Nacht-W6 (Modus-1): Subjekt-Boost bei Zahl-Faktfragen. Das gefragte SUBJEKT
# (Nomen nach dem Verb: „…hat BERLIN", „…ist der MOUNT EVEREST") soll den Artikel
# treiben, nicht das Attribut-Konzept („Einwohner"/„Knochen"/„Temperatur"). Stark
# genug, den BM25 des fokussierten Attribut-Artikels zu schlagen, kleiner als der
# Voll-Query-Exakt-Bonus (40).
_SUBJECT_FACT_BONUS = 22.0

# ── Task #62 (Iter-115, Nora): Exakt-Voll-Query-Bonus + Klärungs-Deprio ──────
# Andi-Bug „Mercury-Programm" → Top-1 war die inhaltsarme Begriffsklärung
# „Mercury" (id 481683, 2740 B, „Mercury bezeichnet im Bereich der Technik…")
# statt des echten Artikels „Mercury-Programm" (id 74594, 17519 B). Wurzel: Der
# Single-Token-Exakt-Match „mercury"==Titel „Mercury" (_title_boost = -20.1)
# schlug den Substring-Match „mercury"⊂„Mercury-Programm" (-8.4). Der Iter-113-
# Filter (plaintext_bytes==0 && !kern) griff nicht — die Klärung hat 2740 B.
#
# (C) Exakt-Voll-Query-Bonus: Entspricht die NORMALISIERTE Gesamt-Query exakt
# einem Artikeltitel (z.B. „Mercury-Programm" == title_norm „mercury-programm"),
# bekommt GENAU dieser Artikel den stärksten Bonus — stärker als jeder Single-
# Token-Exakt-Match auf einen kürzeren Titel. So gewinnt der spezifische Artikel
# gegen die Klärungsseite, aber nur bei „nackten" Entity-Queries (Fragesätze wie
# „Was ist das Mercury-Programm" normalisieren NICHT auf den Titel → unberührt).
#
# (A) Inhaltsarme-Klärungs-Deprio: Treffer mit Klärungs-Intro-Muster („X
# bezeichnet", „steht für" …) UND niedrigem plaintext_bytes bekommen einen
# Score-MALUS (nicht hart raus — vage Anfragen wie „Wer ist Mercury?" dürfen die
# Klärung weiter liefern → LLM kann nachfragen). Konservativ: nur wenn das
# Muster klar greift und der Artikel klein ist.
_EXACT_QUERY_TITLE_BONUS = 40.0  # muss Single-Token-Exakt (~-20) sicher schlagen
_DISAMBIG_DEPRIO_MALUS = 15.0    # Score-Aufschlag (positiv = schlechter)
_DISAMBIG_BYTE_THRESHOLD = 4000  # nur kleine Treffer prüfen (Intro-Decompress)
# Klärungs-Intro-Muster: deutsche Begriffsklärungs-Einleitungen. Bewusst am
# Satzanfang/-nähe verankert (erste ~120 Zeichen), nicht irgendwo im Body.
_DISAMBIG_INTRO_PATTERNS = (
    "bezeichnet",
    "steht für",
    "ist der name",
    "ist ein name",
    "ist der familienname",
    "ist ein familienname",
    "kann sich beziehen auf",
    "kann sich auf",
    "ist die bezeichnung für",
    "ist mehrdeutig",
    "bezeichnungen für",
)


def _query_title_norm_variants(query: str) -> list[str]:
    """Normalisiert die GESAMTE Query ins title_norm-Schema der DB.

    Spiegelt tools/knowledge/wiki-extract.normalize_title (lowercase + Whitespace
    → '_'). ABER: die DB enthält Titel mit Bindestrich (z.B. „Mercury-Programm" →
    title_norm „mercury-programm"). Eine gesprochene Query trennt diese Wörter mit
    einem Space („mercury programm"), der zu „mercury_programm" normalisiert →
    träfe den Bindestrich-Titel NIE (Iter-116-Bug). Wir liefern daher BEIDE
    Varianten: Whitespace→'_' UND Whitespace→'-'. Der Lookup probiert beide
    (index-gestützt via IN-Liste), sodass (C) auch für die Content-Token-Query
    „mercury programm" greift. Reihenfolge: Underscore zuerst (häufigster Fall).
    """
    base = query.strip().lower()
    if not base:
        return []
    under = re.sub(r"\s+", "_", base)
    hyphen = re.sub(r"\s+", "-", base)
    out = [under]
    if hyphen != under:
        out.append(hyphen)
    return out


# ── Nacht-W6 (Modus-1-Disambiguation, Nora-Design + grammatischer Hebel) ─────
# Das gefragte SUBJEKT einer Faktfrage ist (deutsche Frage-Grammatik) das Nomen
# NACH dem Verb: „Wie viele Einwohner hat BERLIN", „Wie hoch ist der MOUNT
# EVEREST", „Wie heiß ist die SONNE", „Wann ist die TITANIC gesunken". Wir ziehen
# es rein grammatisch (kein Themen-Wortliste = Kai-konform): alles nach dem ersten
# Kopula-/Hilfsverb, führende Artikel + abschließende Partizipien/Maß-Adjektive
# weg, Rest = content_tokens. Genutzt für einen gezielten Exakt-Titel-Boost, damit
# der Subjekt-Artikel das Attribut-Konzept („Einwohner"/„Knochen") schlägt.
_FACT_SUBJECT_VERB_RE = re.compile(
    r"\b(?:ist|sind|war|waren|hat|hatte|haben|besitzt|besitzen|wird|wurde|"
    r"misst|z[äa]hlt)\b",
    re.IGNORECASE,
)
_FACT_SUBJECT_ARTICLES = {
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem",
    "einer", "eines",
}
# Partizipien/Maß-Adjektive am Satzende, die NICHT zum Subjekt gehören.
_FACT_SUBJECT_TAIL = {
    "gesunken", "gestorben", "geboren", "gebaut", "entstanden", "erbaut",
    "gegründet", "gegruendet", "untergegangen", "passiert", "errichtet",
    "hoch", "lang", "alt", "weit", "tief", "breit", "schwer", "groß", "gross",
    "entfernt", "heiß", "heiss", "warm", "kalt", "schnell", "schwer",
}


def _fact_subject_tokens(fact_query: str) -> list[str]:
    """Subjekt-Tokens einer Faktfrage (Nomen nach dem Verb). Leer wenn keins."""
    if not fact_query:
        return []
    low = fact_query.lower().strip().rstrip("?").strip()
    m = _FACT_SUBJECT_VERB_RE.search(low)
    if not m:
        return []
    rest = low[m.end():].split()
    while rest and rest[0] in _FACT_SUBJECT_ARTICLES:
        rest = rest[1:]
    return [t for t in content_tokens(" ".join(rest)) if t not in _FACT_SUBJECT_TAIL]


# ── Nacht-W7 (Nora-Design): Entity-Disambiguation für „Wer/Was war X" ────────
# Wenn das Subjekt KEINEN Exakt-Titel-Artikel hat (z.B. „beethoven" → der Artikel
# heißt „Ludwig van Beethoven", „titanic" → „Titanic (Schiff)"), überstrahlen
# gleichnamige Werke/Sub-Artikel den Hauptartikel („14. Streichquartett
# (Beethoven)", „Streitfragen zur Titanic"). Generischer Hebel: der gesuchte
# Hauptartikel trägt das Subjekt am Titel-ANFANG oder -ENDE als ganzes Wort; die
# Überstrahler tragen es in der Mitte/als Genitiv. Größe (plaintext_bytes) ist nur
# Tiebreaker. ABSTAIN bei nicht-eindeutiger Führung (lieber kein Boost als falsch).
_DEFINITION_QUERY_RE = re.compile(r"^\s*(wer|was)\s+(war|ist|sind|waren)\b", re.IGNORECASE)
_DEFINITION_SUBJECT_BONUS = 18.0   # < Exakt-Anker (40) + Subjekt-Exakt (22), > Substring
_ENTITY_LEAD_FACTOR = 1.3          # Top-Kandidat muss den 2. um diesen Faktor schlagen


def _entity_fallback_aid(
    pool: dict, subject_tokens: list[str],
) -> Optional[int]:
    """W7: bester Entity-Hauptartikel im Pool fürs Subjekt, sonst None (abstain).

    Kandidat = Titel enthält ALLE Subjekt-Tokens als ganze Wörter UND beginnt
    oder endet mit der Subjekt-Phrase. Pick = größter plaintext_bytes, ABER nur
    wenn er den Zweiten um _ENTITY_LEAD_FACTOR schlägt (oder allein ist).
    """
    if not subject_tokens:
        return None
    subj = " ".join(subject_tokens)
    cands: list[tuple[int, int]] = []  # (aid, bytes)
    for aid, (r, _score, _m, _ai) in pool.items():
        tl = (r["title"] or "").lower().strip()
        words = set(re.findall(r"\w+", tl))
        if not all(t in words for t in subject_tokens):
            continue
        if not (tl.startswith(subj) or tl.endswith(subj)):
            continue  # Subjekt nur in der Mitte/Genitiv → Überstrahler, raus
        nb = r["plaintext_bytes"] or 0
        if nb <= 0 and not r["kern"]:
            continue
        cands.append((aid, nb))
    if not cands:
        return None
    cands.sort(key=lambda c: c[1], reverse=True)
    if len(cands) == 1:
        return cands[0][0]
    # Abstain-Gate: nur boosten, wenn der Top-Kandidat klar führt.
    if cands[0][1] >= _ENTITY_LEAD_FACTOR * max(cands[1][1], 1):
        return cands[0][0]
    return None


def _looks_like_disambig_intro(text: str) -> bool:
    """True, wenn der Anfang des Plaintexts ein Begriffsklärungs-Muster zeigt.

    Prüft nur die ersten ~120 Zeichen (Einleitung), damit ein Vorkommen tief
    im Body keinen Fehlalarm auslöst. clean_extract entfernt Bild-Captions
    (mini|…) — der erste echte Satz steht danach vorn.
    """
    head = clean_extract(text)[:120].lower()
    if not head.strip():
        head = text[:120].lower()
    return any(p in head for p in _DISAMBIG_INTRO_PATTERNS)


def _title_boost(title: str, tokens: list[str]) -> float:
    """Score-Bonus (negativ = besser) wenn der Titel ein Content-Token enthält.

    title_norm-Äquivalent on the fly: Titel lowercase. Exakter Titel-Match
    (Titel == Token, z.B. „Meerschweinchen") schlägt Substring („Hausmeer…")
    deutlich (eigener großer Exakt-Bonus). Längere Tokens (selektiveres
    Hauptnomen) zählen mehr. 0.0 = kein Match.
    """
    if not tokens:
        return 0.0
    tl = title.lower().strip()
    best = 0.0
    covered = 0  # W6: wie viele DISTINCT Query-Tokens kommen im Titel vor
    for tok in tokens:
        if tok == tl:
            # Exakter Voll-Titel-Match: dominantes Signal.
            cand = _TITLE_MATCH_BONUS + len(tok) * 0.3 + _EXACT_TITLE_BONUS
        elif tok in tl:
            cand = _TITLE_MATCH_BONUS + len(tok) * 0.3        # Substring im Titel
        else:
            continue
        covered += 1
        best = max(best, cand)
    if best == 0.0:
        return 0.0
    # W6 Coverage: jeder zusätzlich abgedeckte Query-Token gibt etwas, gedeckelt
    # bei 2 Extra (Nora) — „Mount Everest" (2 Tokens) > „Everest (2015)" (1).
    coverage_bonus = min(covered - 1, 2) * _COVERAGE_PER_TOKEN
    return -(best + coverage_bonus)


# ── Task #57 (Iter-98): Titel-Index-Lookup gegen Klassifikations-Lücken ───────
# Andi-Bug „Was ist eine Weinbergschnecke" → /search liefert []. Wurzel: nur
# ~54.7k von 4,98M Artikeln sind klassifiziert (1,1%). „Weinbergschnecke"
# (article 2339855, 15 KB Plaintext) EXISTIERT in `articles`, ist aber NICHT
# klassifiziert → fehlt in classifications_fts → leeres Ergebnis → e2b fabuliert.
#
# Idee: Den Titel-Treffer über ALLE 4,98M Artikel IMMER mit-suchen und in den
# Kandidaten-Pool MERGEN — nicht nur wenn die Klassifikations-FTS leer blieb.
# Coverage wird damit universell für jeden Artikel dessen TITEL ein Hauptnomen
# der Frage ist.
#
# Task #61 (Iter-99): Warum „immer" und nicht nur „bei leerem Pool":
# Andi-Bug „Wie viele Zähne hat eine Weinbergschnecke" → grounded auf den ROMAN
# „Zähne zeigen" (Zadie Smith). Wurzel: Das NEBENWORT „Zähne" matchte einen
# klassifizierten Roman → classifications_fts war NICHT leer → der Titel-Fallback
# (alter Code: `if not sorted_hits`) griff NIE → die echte „Weinbergschnecke"
# (0 Klassifikationen, nur per Titel findbar) trat nicht an. Gleiche Klasse wie
# Meerschweinchen→Baumschliefer, nur dass hier ein Nebenwort den Klassifikations-
# Treffer erzeugte. FIX: Titel-Lookup für die Hauptnomen IMMER laufen lassen,
# in den Pool mergen, dann entscheidet der etablierte Titel-Boost-Re-Rank. So
# konkurriert „Weinbergschnecke" (exakter Titel-Match → starker Bonus) mit
# „Zähne zeigen" (nur Klassifikation) und gewinnt.
#
# SICHERHEIT (das ist der Knackpunkt): KEIN Full-Scan. Das leere `articles_fts`
# (build_progress fts=0 — nie befüllt) verbietet eine echte Volltext-Suche
# sowieso. Und ein `title LIKE '%tok%'` wäre ein Leading-Wildcard-SCAN über die
# 7,8-GB-DB (26–80 s gemessen, sprengt das 5s-Backend-Timeout, killte schon
# /health). Stattdessen NUR INDEX-gestützte Lookups auf `idx_articles_title_norm`:
#   1) Exakter Match  title_norm = tok                (B-Tree-Seek, <2 ms)
#   2) Präfix-Range   title_norm >= tok AND < tok+1   (B-Tree-Range, <6 ms)
# Beides nutzt den Index (EXPLAIN: SEARCH USING INDEX), keine SCAN-Zeile.
# Redirects/Disambig/Stopwords werden gefiltert; Redirects werden aufgelöst.
_FALLBACK_PER_TOKEN = 6  # Präfix-Range-Limit pro Token (klein halten)


def _next_prefix(tok: str) -> str:
    """Obergrenze für eine Präfix-Range: tok mit letztem Zeichen +1.

    title_norm >= tok AND title_norm < _next_prefix(tok) entspricht
    „beginnt mit tok" — und nutzt den Index (im Gegensatz zu LIKE 'tok%').
    """
    return tok[:-1] + chr(ord(tok[-1]) + 1)


def _resolve_redirect(conn: sqlite3.Connection, row: sqlite3.Row) -> Optional[sqlite3.Row]:
    """Folgt einem einzelnen Redirect (max. 1 Hop) auf den Zielartikel."""
    if row["redirect_to"] is None:
        return row
    target = conn.execute(
        "SELECT id, title, redirect_to, is_disambig, is_stopword, "
        "plaintext_zstd, plaintext_bytes, kern "
        "FROM articles WHERE id = ?",
        (row["redirect_to"],),
    ).fetchone()
    return target


def title_fallback_search(
    conn: sqlite3.Connection, tokens: list[str], limit: int
) -> list[sqlite3.Row]:
    """Index-gestützter Titel-Fallback über ALLE Artikel (kein Scan).

    Pro Content-Token: exakter title_norm-Match (höchste Priorität), dann
    Präfix-Range. Längere Tokens (selektivere Hauptnomen) zuerst, damit
    „weinbergschnecke" vor generischem „schnecke" greift. Dedupe per id,
    Redirects aufgelöst, Disambig/Stopword raus.
    """
    cols = (
        "id, title, redirect_to, is_disambig, is_stopword, "
        "plaintext_zstd, plaintext_bytes, kern"
    )
    seen: set[int] = set()
    out: list[sqlite3.Row] = []
    # Selektivste (längste) Hauptnomen zuerst.
    for tok in sorted(tokens, key=len, reverse=True):
        if len(out) >= limit:
            break
        # 1) Exakter Titel-Match.
        exact = conn.execute(
            f"SELECT {cols} FROM articles WHERE title_norm = ? LIMIT 3", (tok,)
        ).fetchall()
        # 2) Präfix-Range (index-gestützt, KEIN Leading-Wildcard).
        prefix = conn.execute(
            f"SELECT {cols} FROM articles "
            f"WHERE title_norm >= ? AND title_norm < ? "
            f"ORDER BY length(title_norm) ASC LIMIT ?",
            (tok, _next_prefix(tok), _FALLBACK_PER_TOKEN),
        ).fetchall()
        # Innerhalb eines Tokens: Inhaltsreiche Artikel zuerst. Exakter Titel-
        # Match vor Präfix; danach mehr Plaintext = besser. So führt die echte
        # „Weinbergschnecke" vor leeren Stubs wie „WeinbergschneckenVO".
        candidates = [(r, True) for r in exact] + [(r, False) for r in prefix]

        def _rank(item: tuple[sqlite3.Row, bool]) -> tuple[int, int, int]:
            r, is_exact = item
            has_content = 1 if (r["kern"] or r["plaintext_zstd"]) else 0
            return (-int(is_exact), -has_content, -(r["plaintext_bytes"] or 0))

        for r, _is_exact in sorted(candidates, key=_rank):
            resolved = _resolve_redirect(conn, r)
            if resolved is None:
                continue
            if resolved["is_disambig"] or resolved["is_stopword"]:
                continue
            aid = resolved["id"]
            if aid in seen:
                continue
            seen.add(aid)
            out.append(resolved)
            if len(out) >= limit:
                break
    return out


@app.get("/search", response_model=WikiSearchResponse)
def search(
    q: str,
    limit: int = 5,
    extract_max_chars: int = 1500,
    dedupe: bool = True,
    passage_rag: bool = True,
    summary_sentences: int = 0,
    summary_query: str = "",
    summary_scan: int = 0,
    # T140 (Iter-137): die VOLLE Nutzerfrage für die Zahl-Faktfrage-Erkennung.
    # Der FTS-`q` ist auf Content-Tokens reduziert (Trigger-Wörter wie „wie viele/
    # wann" sind dort als Füller schon raus) → der Trigger braucht die ungekürzte
    # Frage. Leer ⇒ Fallback auf summary_query bzw. q (kein Regress).
    fact_query: str = "",
) -> WikiSearchResponse:
    """BM25-Search über Klassifikations-Aliase mit Hauptnomen-Titel-Re-Rank.

    Liefert mehrere Kandidaten (Top-K), damit die embeddinggemma-Disambiguation
    im Backend re-ranken kann. Kandidaten deren Titel das Hauptnomen der Frage
    enthält werden hochgewichtet, damit der echte Artikel oben landet.

    Tier-1-Summary (Iter-135, ADDITIV): `summary_sentences=N` (>0) hängt an
    JEDEN Hit zusätzlich ein extraktives Top-N-Satz-Summary (Cosine(Frage↔Satz),
    Originalreihenfolge, embeddinggemma-Batch) im Feld `summary`. `extract`
    (Tier-2, voll/L4-Passage) bleibt unverändert → das Backend wählt Tier-1
    (knapp, fürs Grounding-Fenster) oder Tier-2 (voll). Default 0 = aus, Pfad
    unverändert. Embed-Fehler → summary=null (voller Extract bleibt, Recall-Schutz).

    Anker-Entkopplung (T216, Nora): `q` treibt weiter FTS-Exakt-Match +
    Disambiguation (Such-Recall unberührt). `summary_query` (die VOLLE
    Nutzerfrage) — wenn gesetzt — treibt NUR die Satz-Cosine fürs Summary, sodass
    Schlüssel-Fakten („Nobelpreis 1921") gegen die Frage statt gegen den knappen
    Token-Anker ranken. `summary_scan` (>0) weitet das Lead-Scan-Fenster
    (Default 12) für Fakten tief im Lead, gegen Latenz abzuwägen.
    """
    t0 = time.perf_counter()
    tokens = content_tokens(q)
    fts_query = " OR ".join(f'"{t}"' for t in tokens[:8])
    if not fts_query:
        return WikiSearchResponse(query=q, durationMs=0, totalHits=0, hits=[])

    # T140 (Iter-137): ist das eine Zahl-Faktfrage? Trigger auf der VOLLEN Frage
    # (fact_query > summary_query > q — der FTS-`q` ist Token-reduziert, dort sind
    # „wie viele/wann/…" schon als Füller raus). Dann je Hit Zahl-Fakt-Spans aus
    # dem Grounding-Text. Regex auf dem fertig gebauten Extract — kein extra I/O.
    _fact_q = fact_query or summary_query or q
    is_number_fact = bool(_NUMBER_FACT_QUERY_RE.search(_fact_q))
    # „wann/welches Jahr" → Jahre sind die Antwort; sonst (Maß-/Mengen-Frage) raus.
    _allow_years = bool(_YEAR_QUESTION_RE.search(_fact_q))
    # Maß-Frage („wie hoch/lang/schwer/…")? Dann läuft sie — wie die Jahr-Frage —
    # über den Lead-only + Single-Value-Gate-Pfad (Nacht-W3): die L4-Passage liefert
    # bei Maßen live FALSCHE Werte (Fernsehturm→700 m statt 368, Rhein→13 km statt
    # 1233 — die Passage trifft irgendein Maß mit der Einheit, nicht das kanonische).
    # Der Lead trägt die Headline-Größe; Single-Value-Gate = nur bei genau einem
    # Treffer emittieren, sonst abstinieren. Zähl-Fragen bleiben Passage-basiert.
    _q_is_measure = bool(_fact_query_cues(_fact_q)[4])  # is_measure

    # Über-fetchen, damit der echte Artikel (lexikalisch out-ranked) im Pool ist
    # und nach dem Titel-Re-Rank/Dedupe genug Alternativen für die Disambiguation
    # bleiben — auch im dedupe=false-Aufrufpfad (Backend).
    over_fetch = max(limit * 6, 30)

    # (C) Exakt-Voll-Query-Titel-Match (Task #62): Ein B-Tree-Seek auf
    # title_norm (kein Scan). Greift NUR bei „nackten" Entity-Queries, deren
    # Normalform exakt ein Titel ist (z.B. „Mercury-Programm"). Diese article_id
    # bekommt unten den stärksten Bonus → schlägt jeden Single-Token-Exakt-Match.
    exact_query_aid: Optional[int] = None
    # W6: das grammatische Subjekt der Faktfrage (Nomen nach dem Verb) als
    # eigener Exakt-Titel-Anker — damit „Berlin" das Attribut-Konzept „Einwohner"
    # schlägt. Nur bei Zahl-Faktfragen (konservativ, Nora-Gate).
    subject_aid: Optional[int] = None
    # W7: Definitionsfrage „Wer/Was war X" zählt auch als Subjekt-Frage (eigener
    # Pfad). Das Subjekt einmal ziehen — für den Exakt-Anker (subject_aid) UND den
    # Positions-Fallback (entity_aid) unten.
    is_definition = bool(_DEFINITION_QUERY_RE.match(_fact_q))
    _subject_tokens = (
        _fact_subject_tokens(_fact_q) if (is_number_fact or is_definition) else []
    )
    subject_norms = _query_title_norm_variants(" ".join(_subject_tokens)) if _subject_tokens else []
    entity_aid: Optional[int] = None  # W7 Positions-Fallback (kein Exakt-Treffer)

    sql = """
        SELECT
            c.article_id,
            a.title,
            c.classification,
            c.alias_idx,
            bm25(classifications_fts) AS rank,
            a.plaintext_zstd,
            a.plaintext_bytes,
            a.kern
        FROM classifications_fts f
        JOIN classifications c ON c.id = f.rowid
        JOIN articles a ON a.id = c.article_id
        WHERE classifications_fts MATCH ?
          AND a.redirect_to IS NULL
          AND a.is_disambig = 0
          AND a.is_stopword = 0
        ORDER BY rank
        LIMIT ?
    """

    # 1) Über-fetch: pro Artikel den besten (Titel-geboosteten) Score behalten,
    #    OHNE schon zu dekomprimieren (zstd ist teuer — erst für die Top-K).
    #    Pool-Eintrag: (row, score, matched_classification). `matched` ist None
    #    für Titel-Lookup-Treffer (die haben keine Klassifikation).
    best_per_article: dict[int, tuple[sqlite3.Row, float, Optional[str], int]] = {}
    title_boosted = 0
    title_merged = 0
    try:
        with open_conn() as conn:
            # ── (C) Exakt-Voll-Query-Titel-Match (Task #62) ─────────────────
            # B-Tree-Seek auf title_norm (idx_articles_title_norm, kein Scan).
            # Nur bei „nackten" Entity-Queries trifft das (Fragesätze norm. nicht
            # auf einen Titel). Redirect aufgelöst, Disambig/Stopword/leer raus.
            q_norms = _query_title_norm_variants(q)
            if q_norms:
                try:
                    placeholders = ",".join("?" for _ in q_norms)
                    eq = conn.execute(
                        f"SELECT id, redirect_to, is_disambig, is_stopword, "
                        f"plaintext_bytes, kern FROM articles "
                        f"WHERE title_norm IN ({placeholders}) LIMIT 1",
                        tuple(q_norms),
                    ).fetchone()
                    if eq is not None:
                        eq = _resolve_redirect(conn, eq)
                    if (
                        eq is not None
                        and not eq["is_disambig"]
                        and not eq["is_stopword"]
                        and ((eq["plaintext_bytes"] or 0) > 0 or eq["kern"])
                    ):
                        exact_query_aid = eq["id"]
                except sqlite3.OperationalError as e:
                    log.warning("Exakt-Query-Titel-Lookup failed for '%s': %s", q, e)

            # ── (C2) W6 Subjekt-Exakt-Titel-Lookup (gleicher Index-Seek) ─────
            # Greift NUR bei Zahl-Faktfragen mit einem als ganzer Titel
            # existierenden Subjekt (z.B. „mount everest"→„Mount Everest",
            # „berlin"→„Berlin"), damit der Subjekt-Artikel das Attribut-Konzept
            # („Einwohner"/„Knochen"/„Temperatur") schlägt.
            if subject_norms:
                try:
                    ph = ",".join("?" for _ in subject_norms)
                    sq = conn.execute(
                        f"SELECT id, redirect_to, is_disambig, is_stopword, "
                        f"plaintext_bytes, kern FROM articles "
                        f"WHERE title_norm IN ({ph}) LIMIT 1",
                        tuple(subject_norms),
                    ).fetchone()
                    if sq is not None:
                        sq = _resolve_redirect(conn, sq)
                    if (
                        sq is not None
                        and not sq["is_disambig"]
                        and not sq["is_stopword"]
                        and ((sq["plaintext_bytes"] or 0) > 0 or sq["kern"])
                    ):
                        subject_aid = sq["id"]
                except sqlite3.OperationalError as e:
                    log.warning("Subjekt-Titel-Lookup failed for '%s': %s", q, e)

            # ── 1a) Klassifikations-FTS (etablierter Pfad) ──────────────────
            rows = conn.execute(sql, (fts_query, over_fetch)).fetchall()
            for r in rows:
                aid = r["article_id"]
                # Iter-113 (Nora): inhaltslose Artikel raus — z.B. die ungeflaggte
                # Begriffsklärung „Einstein" (plaintext_bytes=0, kein kern) gewann
                # per Exakt-Titel-Boost Platz 1 und verdrängte Albert Einstein.
                # Ohne Inhalt lässt sich nichts grounden → nie in den Pool.
                if (r["plaintext_bytes"] or 0) == 0 and not r["kern"]:
                    continue
                boost = _title_boost(r["title"] or "", tokens)
                score = float(r["rank"]) + boost  # negativ = besser
                cur = best_per_article.get(aid)
                if cur is None or score < cur[1]:
                    best_per_article[aid] = (
                        r, score, r["classification"] or "", r["alias_idx"] or 0
                    )

            # ── 1b) Titel-Index-Lookup (Task #61) — IMMER mergen ────────────
            # Hauptnomen der Frage exakt/präfix gegen idx_articles_title_norm
            # (kein Scan). Treffer kommen MIT in den Pool, sodass ein exakter
            # Titel-Match (z.B. „Weinbergschnecke") gegen einen reinen
            # Klassifikations-Treffer (z.B. Roman „Zähne zeigen") konkurriert.
            # Score = nur _title_boost (kein BM25): der starke Exakt-Titel-Bonus
            # zieht den richtigen Artikel an die Spitze. Dedupe per articleId
            # gegen 1a — schon im Pool stehende Artikel werden nicht überschrieben
            # (ihr Klassifikations-Score ist informativer).
            try:
                fb_rows = title_fallback_search(conn, tokens, max(limit * 3, 12))
                for r in fb_rows:
                    aid = r["id"]
                    if aid in best_per_article:
                        continue  # bereits über Klassifikation im Pool → dedupe
                    if (r["plaintext_bytes"] or 0) == 0 and not r["kern"]:
                        continue  # inhaltslos → nichts zu grounden (Iter-113)
                    boost = _title_boost(r["title"] or "", tokens)
                    if boost >= 0.0:
                        continue  # kein echter Titel-Match → nicht aufnehmen
                    best_per_article[aid] = (r, boost, None, 0)
                    title_merged += 1
            except sqlite3.OperationalError as e:
                # Titel-Lookup ist additiv — Ausfall darf den FTS-Pfad nicht killen.
                log.warning("Titel-Lookup-Merge failed for '%s': %s", q, e)

            # ── 1c) Exakt-Voll-Query-Treffer sicher in den Pool (Task #62) ──
            # Sollte (C) einen Artikel gefunden haben, der weder per FTS noch per
            # Titel-Fallback gemergt wurde (möglich, wenn der Titel nur als ganze
            # Phrase, nicht als Einzel-Token, gegen den Index trifft), holen wir
            # ihn explizit nach — sonst greift der (C)-Bonus unten ins Leere.
            if exact_query_aid is not None and exact_query_aid not in best_per_article:
                try:
                    fr = conn.execute(
                        "SELECT id AS article_id, title, NULL AS classification, "
                        "0 AS alias_idx, 0.0 AS rank, plaintext_zstd, "
                        "plaintext_bytes, kern FROM articles WHERE id = ?",
                        (exact_query_aid,),
                    ).fetchone()
                    if fr is not None:
                        best_per_article[exact_query_aid] = (fr, 0.0, None, 0)
                except sqlite3.OperationalError as e:
                    log.warning("Exakt-Query-Merge failed for '%s': %s", q, e)

            # ── 1c2) W6: Subjekt-Treffer sicher in den Pool (wie 1c) ─────────
            if subject_aid is not None and subject_aid not in best_per_article:
                try:
                    sr = conn.execute(
                        "SELECT id AS article_id, title, NULL AS classification, "
                        "0 AS alias_idx, 0.0 AS rank, plaintext_zstd, "
                        "plaintext_bytes, kern FROM articles WHERE id = ?",
                        (subject_aid,),
                    ).fetchone()
                    if sr is not None:
                        best_per_article[subject_aid] = (sr, 0.0, None, 0)
                except sqlite3.OperationalError as e:
                    log.warning("Subjekt-Merge failed for '%s': %s", q, e)

            # ── 1c3) W7: Entity-Positions-Fallback, wenn KEIN Exakt-Subjekt-Treffer
            # (z.B. „beethoven"→„Ludwig van Beethoven"). Über den schon gebauten
            # Pool — kein extra I/O. Abstain bei nicht-eindeutiger Führung.
            if subject_aid is None and _subject_tokens:
                entity_aid = _entity_fallback_aid(best_per_article, _subject_tokens)

            # ── 1d) Score-Anpassung: (C) Exakt-Voll-Query-Bonus + W6-Subjekt-
            #        Bonus + (A) Klärungs-Deprio (Task #62). (C)/Subjekt ziehen den
            #        spezifischen Artikel nach vorn; (A) schiebt inhaltsarme
            #        Klärungsseiten weg, ohne sie zu entfernen.
            for aid, (r, score, matched, alias_idx) in list(best_per_article.items()):
                new_score = score
                is_anchor = False
                if aid == exact_query_aid:
                    new_score -= _EXACT_QUERY_TITLE_BONUS
                    is_anchor = True
                if aid == subject_aid:
                    # W6: Subjekt-Artikel (Nomen nach dem Verb) bei Zahl-Faktfragen
                    # nach vorn — schlägt das Attribut-Konzept („Einwohner" etc.).
                    new_score -= _SUBJECT_FACT_BONUS
                    is_anchor = True
                if aid == entity_aid:
                    # W7: Entity-Hauptartikel (Positions-Fallback) — „Ludwig van
                    # Beethoven" schlägt das gleichnamige Werk. Schwächer als die
                    # Exakt-Anker, stärker als ein Substring-Titel.
                    new_score -= _DEFINITION_SUBJECT_BONUS
                    is_anchor = True
                if not is_anchor:
                    nb = r["plaintext_bytes"] or 0
                    if (
                        0 < nb < _DISAMBIG_BYTE_THRESHOLD
                        and not r["kern"]
                        and r["plaintext_zstd"]
                    ):
                        full = _decompress_full(r["plaintext_zstd"], nb)
                        if full and _looks_like_disambig_intro(full):
                            new_score += _DISAMBIG_DEPRIO_MALUS
                if new_score != score:
                    best_per_article[aid] = (r, new_score, matched, alias_idx)

            # ── 2) Top-K nach geboostetem Score — erst DANN dekomprimieren ──
            # Iter-113 (Nora): Tiebreak nach Inhalts-Reichtum. Bei gleichem Score
            # (z.B. Carl vs. Albert Einstein — beide nur Substring-Titel-Match)
            # gewinnt der inhaltsreichere Artikel = i.d.R. der Haupt-Artikel.
            top = sorted(
                best_per_article.keys(),
                key=lambda a: (
                    best_per_article[a][1],
                    -(best_per_article[a][0]["plaintext_bytes"] or 0),
                ),
            )[:limit]
            hits: list[WikiSearchHit] = []
            passage_used = 0
            summary_used = 0
            for aid in top:
                r, score, matched, alias_idx = best_per_article[aid]
                kern = r["kern"]
                summary_text: Optional[str] = None
                summary_count = 0
                if kern:
                    # `kern` ist eine kompakte LLM-Definition — keine Passagen
                    # darin; das ist die etablierte Intro-Antwort. Unverändert.
                    extract = kern
                elif r["plaintext_zstd"]:
                    # L4: query-fokussierte Passage ODER Intro (Fallback intern).
                    extract, used_passage = query_focused_or_intro(
                        r["plaintext_zstd"], r["plaintext_bytes"] or 0,
                        q, tokens, extract_max_chars, passage_rag,
                        is_fact_query=is_number_fact,
                    )
                    if used_passage:
                        passage_used += 1
                    # ── Tier-1-Summary (ADDITIV, nur wenn angefordert) ──────
                    # Auf dem VOLLEN Body (nicht der L4-Passage) — der Lead trägt
                    # die Kern-Definition. Eigener Decompress nur in diesem Pfad,
                    # also kein Overhead im Default (summary_sentences=0).
                    if summary_sentences > 0 and SUMMARY_ENABLED:
                        full = _decompress_full(
                            r["plaintext_zstd"], r["plaintext_bytes"] or 0
                        )
                        if full:
                            summ = extractive_summary(
                                full, q, summary_sentences,
                                rank_query=summary_query, max_scan=summary_scan,
                            )
                            if summ is not None:
                                summary_text = summ["text"]
                                summary_count = len(summ["sentences"])
                                summary_used += 1
                else:
                    extract = "(kein Inhalt verfügbar)"
                if _title_boost(r["title"] or "", tokens) < 0.0:
                    title_boosted += 1
                # T140: Zahl-Fakt-Spans nur bei Zahl-Faktfragen. Quelle ist der
                # Text, der ins Grounding geht — Summary (falls gebaut) UND
                # Extract, vereinigt in Textreihenfolge (der Vertrag soll den Span
                # finden, egal welchen Tier das Backend wählt).
                facts: list[str] = []
                if is_number_fact and (_allow_years or _q_is_measure) \
                        and r["plaintext_zstd"]:
                    # ── JAHR- & MASS-Frage: Lead-only + Single-Value-Gate ────────
                    # (Jahr: Nacht-W2 · Maß: Nacht-W3). Der gefragte Kern-Fakt steht
                    # kanonisch im LEAD: das Geburts-/Todesjahr („* 1879 … † 1955")
                    # bzw. die Headline-Größe („ist ein 330 Meter hoher …"). Die
                    # gewählte L4-Passage liefert dagegen live FALSCHE Werte — bei
                    # „wann starb" Body-Jahr-Rauschen (Mozart→[1788,1789,…]), bei
                    # „wie hoch/lang" irgendein Maß mit der Einheit statt des
                    # kanonischen (Fernsehturm→700 m statt 368, Rhein→13 km statt
                    # 1233). Darum NUR den Lead scannen (auch bei kern-Artikeln, aus
                    # dem Plaintext — der kern hat die Vita-/Maß-Fakten nicht).
                    # Single-Value-Gate (Faithfulness > Recall): nur emittieren,
                    # wenn der Lead GENAU EINEN distinct Span liefert = hohe
                    # Konfidenz; sonst abstinieren (lieber leer → Prosa-Fallback als
                    # zwei konkurrierende Werte). `allow_years` trennt die Typen:
                    # Jahr-Frage lässt Jahre + unterdrückt Maße, Maß-Frage umgekehrt.
                    # Verifiziert (live, echte DB): Jahr-Battery 8/8, Maß-Battery
                    # turnt 4 falsche → korrekt/leer, 0 Regression.
                    _lead_full = _decompress_full(
                        r["plaintext_zstd"], r["plaintext_bytes"] or 0
                    )
                    lead = (
                        clean_extract(_lead_full)[:_YEAR_LEAD_CHARS]
                        if _lead_full else ""
                    )
                    cand = extract_number_facts(
                        lead, allow_years=_allow_years, fact_query=_fact_q,
                        subject=q,
                    )
                    distinct = list(dict.fromkeys(cand))
                    # ── Satz-1-Präferenz als Tie-Break VOR der Abstention ────
                    # (2026-07-02, Nora). Live-Befund Eiffelturm: der 700-Zeichen-
                    # Lead trägt ZWEI Höhen — «330 Meter» (heutiger Wert, im
                    # DEFINITIONSSATZ „…ist ein 330 Meter hoher…") und «312
                    # Meter» (historische Bauhöhe, späterer Satz) → das Gate
                    # abstinierte, der Zahlen-Vertrag feuerte nie, das 4B-Brain
                    # schwafelte („ziemlich hoch"). Der KANONISCHE Wert steht
                    # praktisch immer in Satz 1 des Leads. Darum bei >1 distinct:
                    # NUR Satz 1 (Schnitt am ersten Satzende via _SENT_SPLIT_RE,
                    # frühestens ab Zeichen 20 — schützt vor Mini-Fragmenten)
                    # erneut scannen, gleiche Args. GENAU EIN distinct Span dort
                    # → der ist es; sonst weiter [] (Abstention lebt). Kein
                    # Raten: wir engen nur die Quelle ein — Faithfulness >
                    # Recall bleibt (Nora-Veto, Nacht-W2/W3) unangetastet.
                    if len(distinct) > 1:
                        _s1_end = _SENT_SPLIT_RE.search(lead, 20)
                        _s1 = lead[:_s1_end.start() + 1] if _s1_end else lead
                        _s1_cand = extract_number_facts(
                            _s1, allow_years=_allow_years, fact_query=_fact_q,
                            subject=q,
                        )
                        distinct = list(dict.fromkeys(_s1_cand))
                    facts = distinct if len(distinct) == 1 else []
                    # ── Nacht-W5 (e4b-Capstone-Fund): den `extract` (Grounding-
                    # Hintergrund) bei Jahr/Maß-Fragen EBENFALLS aus dem Lead
                    # speisen. Sonst widerspricht der Passage-Extract den facts:
                    # e4b las bei „wie hoch Fernsehturm" die 700-m-Passage und
                    # ignorierte den «368»-Span (→ „Höhe fehlt"); bei „wann starb
                    # Mozart" fabulierte e4b „1788" aus der Passage, obwohl facts
                    # leer waren. Mit dem Lead als Hintergrund (konsistent zu den
                    # facts UND mit dem kanonischen „* 1879 … † 1791") antwortet
                    # e4b korrekt — live verifiziert (Fernsehturm 368, Mozart 1791,
                    # Einstein 1879). Nur wenn der Lead trägt; sonst Passage lassen.
                    if lead:
                        extract = lead
                elif is_number_fact:
                    # Zähl-Frage (kein Jahr/Maß): wie bisher aus Summary+Extract —
                    # „40.000 Zähnchen" steht zuverlässig in der Passage.
                    src = ((summary_text or "") + "\n" + (extract or "")).strip()
                    facts = extract_number_facts(
                        src, allow_years=_allow_years, fact_query=_fact_q,
                        subject=q,
                    )
                hits.append(
                    WikiSearchHit(
                        articleId=aid,
                        title=r["title"] or "(unbekannt)",
                        matchedClassification=(
                            matched if matched is not None else "(titel-lookup)"
                        ),
                        aliasIdx=alias_idx,
                        bm25Score=score,
                        extract=extract,
                        hasKern=kern is not None,
                        plaintextBytes=r["plaintext_bytes"] or 0,
                        summary=summary_text,
                        summarySentenceCount=summary_count,
                        facts=facts,
                    )
                )
    except sqlite3.OperationalError as e:
        log.warning("FTS5-Query failed for '%s': %s", q, e)
        raise HTTPException(status_code=500, detail=str(e))

    sorted_hits = hits

    # ── T138 AC#1 (Schatten-Modus): hypothetische Retrieval-Tiefe NUR loggen ──
    # Rein additiv: aus Kategorie (top-Hit-Klassifikation) + `_is_generic_query`
    # auf der vollen Frage (`_fact_q`) abgeleitet. KEIN Verhalten geändert — der
    # Tag fließt ausschließlich in den bestehenden `[search]`-Trace. Schalten
    # (andere Tiefe/Flag/Boost) ist AC#2-4, hier bewusst NICHT.
    _shadow_top_cls = sorted_hits[0].matchedClassification if sorted_hits else None
    _shadow_depth = _shadow_retrieval_depth(
        _fact_q, _shadow_top_cls, bool(sorted_hits)
    )

    duration_ms = int((time.perf_counter() - t0) * 1000)
    log.info(
        "[search] q=%r dedupe=%s → %d hits in %dms "
        "(fts=%r, titelboost=%d, titelmerge=%d, passage=%d, summary=%d/n=%d, "
        "shadow_depth=%s)",
        q, dedupe, len(sorted_hits), duration_ms, fts_query, title_boosted,
        title_merged, passage_used, summary_used, summary_sentences,
        _shadow_depth,
    )
    return WikiSearchResponse(
        query=q,
        durationMs=duration_ms,
        totalHits=len(sorted_hits),
        hits=sorted_hits,
    )


@app.get("/article/{article_id}", response_model=ArticleResponse)
def article(
    article_id: int,
    max_chars: int = 2000,
    summary_sentences: int = 0,
    q: str = "",
    summary_query: str = "",
    summary_scan: int = 0,
    fact_query: str = "",  # T140: volle Frage für die Zahl-Faktfrage-Erkennung.
) -> ArticleResponse:
    """Einzelner Artikel mit Volltext.

    Tier-1-Summary (Iter-135, ADDITIV): `summary_sentences=N` (>0) + `q=<Frage>`
    liefern zusätzlich ein extraktives Top-N-Satz-Summary im Feld `summary`.
    Default 0 = aus, `extract` unverändert.

    Anker-Entkopplung (T216): `summary_query` (volle Frage) treibt — wenn gesetzt —
    die Satz-Cosine; sonst `q`. `summary_scan` (>0) weitet das Lead-Fenster.
    """
    sql = """
        SELECT id, title, plaintext_zstd, plaintext_bytes, kern
        FROM articles WHERE id = ?
    """
    try:
        with open_conn() as conn:
            row = conn.execute(sql, (article_id,)).fetchone()
        if row is None:
            raise HTTPException(status_code=404, detail=f"article {article_id} not found")
        kern = row["kern"]
        summary_text: Optional[str] = None
        summary_count = 0
        if kern:
            extract = kern
        elif row["plaintext_zstd"]:
            extract = decompress_truncated(
                row["plaintext_zstd"], row["plaintext_bytes"] or 0, max_chars
            )
            if summary_sentences > 0 and SUMMARY_ENABLED:
                full = _decompress_full(
                    row["plaintext_zstd"], row["plaintext_bytes"] or 0
                )
                if full:
                    summ = extractive_summary(
                        full, q, summary_sentences,
                        rank_query=summary_query, max_scan=summary_scan,
                    )
                    if summ is not None:
                        summary_text = summ["text"]
                        summary_count = len(summ["sentences"])
        else:
            extract = "(kein Inhalt verfügbar)"
        # T140: Zahl-Fakt-Spans bei Zahl-Faktfragen (fact_query > summary_query > q).
        facts: list[str] = []
        _aq = fact_query or summary_query or q
        if _NUMBER_FACT_QUERY_RE.search(_aq):
            src = ((summary_text or "") + "\n" + (extract or "")).strip()
            facts = extract_number_facts(
                src, allow_years=bool(_YEAR_QUESTION_RE.search(_aq)),
                fact_query=_aq, subject=q,
            )
        return ArticleResponse(
            articleId=row["id"],
            title=row["title"],
            plaintextLength=row["plaintext_bytes"] or 0,
            extract=extract,
            hasKern=kern is not None,
            summary=summary_text,
            summarySentenceCount=summary_count,
            facts=facts,
        )
    except sqlite3.OperationalError as e:
        log.warning("article query failed for %d: %s", article_id, e)
        raise HTTPException(status_code=500, detail=str(e))


# ── Main ────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    log.info("hoshi-knowledge-bridge startet auf %s:%d", args.host, args.port)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info", workers=1)
