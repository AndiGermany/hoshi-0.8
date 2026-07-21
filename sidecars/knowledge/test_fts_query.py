"""BRIDGE-FTS-QUERY-TEST — reine Unit-Tests der FTS-Query-Bau- und
Text-Bereinigungs-Logik der Knowledge-Bridge (4.98M Artikel, :8035).

Getestet werden AUSSCHLIESSLICH reine Funktionen aus ``server.py`` — KEINE
DB-Verbindung, kein Ollama, kein HTTP. Der Server lädt seine ``articles.db``
lazy; wir setzen ``HOSHI_WIKI_DB_PATH=/dev/null`` (existiert → der
``DB_PATH.exists()``-Guard löst kein ``sys.exit(1)`` aus), bevor ``server``
importiert wird.

Schwerpunkte laut Ticket:
  - FTS5-Match-Query-Konstruktion (``content_tokens`` / ``to_fts_match_query``):
    Sonderzeichen, Zahl-Präfix, leere Eingabe, Stop-Word-Filter, Distinct,
    8-Token-Deckel, OR-Quoting.
  - Text-Bereinigung (``clean_extract`` / ``strip_caption_lines`` /
    ``_is_caption_line``): Bild-Captions führend UND inline, Datei-Links,
    Prosa bleibt unangetastet (keine False-Positives).

Lauf:
  HOSHI_WIKI_DB_PATH=/dev/null python -m pytest test_fts_query.py -q
"""
import os
import sys

# server.py liest HOSHI_WIKI_DB_PATH beim Import (argparse-Default). /dev/null
# existiert → kein sys.exit(1). Muss VOR `import server` gesetzt sein.
os.environ.setdefault("HOSHI_WIKI_DB_PATH", "/dev/null")
sys.path.insert(0, os.path.dirname(__file__))

import server  # noqa: E402


# ── content_tokens ──────────────────────────────────────────────────────────

def test_content_tokens_empty_returns_empty_list():
    """Leere / Whitespace-Eingabe → leere Token-Liste (kein Crash)."""
    assert server.content_tokens("") == []
    assert server.content_tokens("   ") == []
    assert server.content_tokens("\t\n ") == []


def test_content_tokens_filters_stopwords():
    """Deutsche Stop-Words fallen raus, das Hauptnomen bleibt."""
    toks = server.content_tokens("Was ist die Hauptstadt von Frankreich?")
    # "was", "ist", "die", "von" sind Stop-Words → weg.
    assert "was" not in toks
    assert "ist" not in toks
    assert "die" not in toks
    assert "von" not in toks
    assert "hauptstadt" in toks
    assert "frankreich" in toks


def test_content_tokens_drops_short_tokens():
    """Tokens mit ≤2 Zeichen (len > 2 gefordert) fallen raus."""
    toks = server.content_tokens("EU im DC ab Mount")
    # "eu", "im", "dc", "ab" sind ≤2 Zeichen → weg, "mount" bleibt.
    assert "eu" not in toks
    assert "im" not in toks
    assert "ab" not in toks
    assert "mount" in toks


def test_content_tokens_special_chars_become_separators():
    """Sonderzeichen (Klammern, Bindestrich, Slash, Komma) trennen Tokens,
    Umlaute/ß bleiben erhalten (im _ALLOWED_EXTRA-Set)."""
    toks = server.content_tokens("Größe (Höhe): 8.848 m — Mount-Everest/Nepal!")
    # Umlaute überleben.
    assert "größe" in toks
    assert "höhe" in toks
    # Bindestrich trennt → "mount" und "everest" getrennt.
    assert "mount" in toks
    assert "everest" in toks
    # Slash trennt → "nepal" eigenständig.
    assert "nepal" in toks


def test_content_tokens_number_prefix_kept():
    """Zahl-Präfix / Zahl-Tokens bleiben (isalnum), aber zu kurze (≤2) fallen."""
    toks = server.content_tokens("1879 Geburtsjahr 42 Antwort")
    assert "1879" in toks          # 4-stellig → bleibt
    assert "geburtsjahr" in toks
    assert "antwort" in toks
    assert "42" not in toks         # 2-stellig → ≤2 → raus


def test_content_tokens_distinct_preserves_order():
    """Doppelte Tokens werden dedupliziert, Reihenfolge des Erstauftritts bleibt."""
    toks = server.content_tokens("Berlin Berlin Hamburg Berlin München")
    assert toks == ["berlin", "hamburg", "münchen"]


def test_content_tokens_lowercases():
    """Alles wird lowercased (Spiegelt contentTokens im Kotlin-Service)."""
    toks = server.content_tokens("MOUNT Everest KÖLN")
    assert toks == ["mount", "everest", "köln"]


# ── to_fts_match_query ──────────────────────────────────────────────────────

def test_fts_query_empty_is_empty_string():
    """Leere Eingabe / nur Stop-Words → leere Match-Query (kein nacktes OR)."""
    assert server.to_fts_match_query("") == ""
    # Nur Stop-Words → keine Content-Tokens → leerer String.
    assert server.to_fts_match_query("ist die das was von") == ""


def test_fts_query_quotes_and_or_joins_tokens():
    """Jedes Token wird in Double-Quotes gewrappt und mit ' OR ' verbunden."""
    q = server.to_fts_match_query("Mount Everest Höhe")
    assert q == '"mount" OR "everest" OR "höhe"'


def test_fts_query_single_token_no_or():
    """Genau ein Content-Token → kein OR, nur das gequotete Token."""
    q = server.to_fts_match_query("Was ist Frankreich?")
    assert q == '"frankreich"'


def test_fts_query_caps_at_8_tokens():
    """Höchstens 8 Tokens landen in der Match-Query (großzügiges Recall-Limit)."""
    many = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda"
    q = server.to_fts_match_query(many)
    # 8 gequotete Tokens → 8 Segmente, getrennt durch ' OR '.
    assert q.count(" OR ") == 7
    assert q.split(" OR ")[0] == '"alpha"'
    # 9.+ Token (iota/kappa/lambda) tauchen NICHT auf.
    assert '"iota"' not in q
    assert '"lambda"' not in q


def test_fts_query_special_chars_do_not_leak_into_query():
    """Sonderzeichen aus der Frage dürfen NICHT in die FTS-Query lecken
    (sonst FTS5-Syntaxfehler). Nur saubere gequotete Content-Tokens."""
    q = server.to_fts_match_query('Was ist "Köln (Dom)" — wirklich?')
    # Keine rohe Klammer / kein Gedankenstrich / kein Roh-Quote-Salat.
    assert "(" not in q
    assert ")" not in q
    assert "—" not in q
    assert "köln" in q
    assert "dom" in q
    # Struktur bleibt valide: ausschließlich '"tok"'-Segmente, OR-getrennt.
    for seg in q.split(" OR "):
        assert seg.startswith('"') and seg.endswith('"')
        # innen keine weiteren Quotes
        assert '"' not in seg[1:-1]


# ── _is_caption_line ────────────────────────────────────────────────────────

def test_is_caption_line_alt_prefix():
    assert server._is_caption_line("alt=Einstein vor einer Tafel") is True


def test_is_caption_line_image_keyword_head():
    assert server._is_caption_line("mini|Ein Bild von Einstein") is True
    assert server._is_caption_line("rechts|100px|Wappen der Stadt") is True
    assert server._is_caption_line("rahmenlos|rechts|Foto") is True


def test_is_caption_line_prose_is_not_caption():
    """Prosa, die zufällig ein Bild-Keyword trägt, ist KEINE Caption
    (das '|' steht nicht am Kopf / fehlt ganz). Konservativer Veto-Bereich."""
    assert server._is_caption_line("Er stand rechts neben dem Tisch.") is False
    assert server._is_caption_line("Das Tier hat einen Schwanz.") is False
    # '|' vorhanden, aber Kopf-Segment ist KEIN Bild-Keyword → keine Caption.
    assert server._is_caption_line("Wert A | Wert B") is False


def test_is_caption_line_empty():
    assert server._is_caption_line("") is False


# ── strip_caption_lines ─────────────────────────────────────────────────────

def test_strip_caption_lines_removes_inline_and_leading():
    """Caption-Zeilen fallen GLOBAL raus (führend UND inline, T140),
    Prosa-Zeilen bleiben in Reihenfolge erhalten."""
    text = (
        "mini|Einstein mit Kreide vor einer Tafel\n"
        "Albert Einstein war ein Physiker.\n"
        "rechts|Foto von Einstein\n"
        "Er erhielt 1921 den Nobelpreis."
    )
    out = server.strip_caption_lines(text)
    assert "mini|Einstein mit Kreide" not in out
    assert "rechts|Foto von Einstein" not in out
    assert "Albert Einstein war ein Physiker." in out
    assert "Er erhielt 1921 den Nobelpreis." in out


def test_strip_caption_lines_noop_without_markers():
    """Ohne '|' und ohne 'alt=' bleibt der Text byte-identisch (Fast-Path)."""
    text = "Reiner Prosatext ohne jedes Bild-Markup."
    assert server.strip_caption_lines(text) == text


def test_strip_caption_lines_empty():
    assert server.strip_caption_lines("") == ""


# ── clean_extract ───────────────────────────────────────────────────────────

def test_clean_extract_empty_passthrough():
    """Leere Eingabe wird unverändert zurückgegeben (kein Crash)."""
    assert server.clean_extract("") == ""


def test_clean_extract_removes_file_links():
    """Inline [[Datei:...]] / [[Bild:...]]-Links werden global entfernt."""
    text = "Vorher [[Datei:Einstein.jpg|mini|Foto]] Nachher steht Prosa."
    out = server.clean_extract(text)
    assert "[[Datei:" not in out
    assert "Vorher" in out
    assert "Nachher steht Prosa." in out


def test_clean_extract_strips_leading_caption_then_prose():
    """Der dokumentierte Einstein-Fall: führende mini|alt=...-Caption fällt,
    der Prosa-Fakt rückt nach vorn ins Grounding-Fenster."""
    text = (
        "mini|alt=Einstein mit Kreide vor einer Tafel stehend\n"
        "Albert Einstein erhielt 1921 den Nobelpreis für Physik."
    )
    out = server.clean_extract(text)
    assert out.startswith("Albert Einstein")
    assert "1921" in out
    assert "mini|alt=" not in out


def test_clean_extract_collapses_whitespace():
    """Mehrfach-Spaces/Tabs werden zu einem Space, ≥3 Newlines zu max. 2."""
    text = "Wort1     Wort2\t\tWort3\n\n\n\nWort4"
    out = server.clean_extract(text)
    assert "Wort1 Wort2 Wort3" in out
    assert "\n\n\n" not in out


def test_clean_extract_preserves_prose_with_keyword():
    """Prosa, die ein Bild-Keyword als Wort trägt, überlebt vollständig
    (kein False-Positive-Stripping — Veto-Bereich)."""
    text = "Der Eingang liegt rechts und führt in den Hof."
    out = server.clean_extract(text)
    assert "rechts" in out
    assert "Hof" in out
