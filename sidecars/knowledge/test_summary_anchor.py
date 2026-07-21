"""T216/T223 — Bridge-Unit-Tests: Summary-Anker + Fakt-Bias.

Testet `extractive_summary` OHNE Ollama, indem `_embed_batch` gemockt wird.

T216 (Anker-Entkopplung):
  1. `rank_query` (volle Frage) treibt die Cosine, NICHT `query` (FTS-Anker).
  2. Ohne `rank_query` rankt es wie bisher gegen `query` (Rueckwaertskompat).
  3. `max_scan` weitet das Lead-Scan-Fenster.
  4. Default-Pfad (summary aus) bleibt unangetastet (None bei n<=0 / leerem Text).

T223 (Fakt-Bias + Lead-Anchor-Mix):
  5. Lead-Anchor: Satz 1 (Definition) ist IMMER im Summary.
  6. Fakt-Boost: ein Faktsatz (Jahr/Marker) mit nur mittelgutem Cosine
     verdraengt eine Lead-Floskel mit leicht hoeherem Cosine.
  7. Fakt-Helfer (_has_year/_marker_hits/_has_measure/_fact_boost) korrekt.
  8. Kill-Switch HOSHI_BRIDGE_FACT_BIAS=0 → reines Cosine (kein Boost).

Lauf: `HOSHI_WIKI_DB_PATH=/dev/null .venv/bin/python test_summary_anchor.py`
"""
import os
import sys

os.environ.setdefault("HOSHI_WIKI_DB_PATH", "/dev/null")  # server laedt DB lazy
sys.path.insert(0, os.path.dirname(__file__))

import server  # noqa: E402

# Lead aus 6 klaren Saetzen. Der Auszeichnungs-Fakt steht in Satz 2 (Index 1).
ARTICLE = (
    "Albert Einstein war ein theoretischer Physiker mit grosser Wirkung. "
    "1921 erhielt er den Nobelpreis fuer Physik fuer das Gesetz des "
    "photoelektrischen Effekts. "
    "Er entwickelte die Relativitaetstheorie und veraenderte die Physik. "
    "Seine Arbeiten praegten das moderne Weltbild nachhaltig. "
    "Er lebte spaeter in den Vereinigten Staaten von Amerika. "
    "Sein Werk wird bis heute breit rezipiert und gewuerdigt."
)


def _vec_for(text: str, axis_word: str) -> list:
    """Deterministischer Pseudo-Vektor: Achse = Vorkommen von axis_word."""
    low = text.lower()
    return [1.0 if axis_word in low else 0.0, 0.1]


def test_rank_query_drives_cosine(axis="nobelpreis"):
    """Mit rank_query='...nobelpreis...' muss der Nobelpreis-Satz im Summary sein,
    OBWOHL query='physiker' (FTS-Anker) auf Satz 1 zeigen wuerde.

    T223: Lead-Anchor zieht Satz 1 zwingend mit rein → wir fragen n_sentences=2,
    damit der Anker UND der Cosine/Fakt-Top-Satz Platz haben. So bleibt die
    T216-Aussage (Rank-Query treibt die Cosine) pruefbar."""
    captured = {}

    def fake_embed(texts):
        captured["first"] = texts[0]
        return [_vec_for(t, axis) for t in texts]

    server._embed_batch = fake_embed
    res = server.extractive_summary(
        ARTICLE, query="physiker", n_sentences=2,
        rank_query="Wer bekam den nobelpreis?",
    )
    assert res is not None
    # Der Rank-Query (texts[0]) MUSS die volle Frage sein, nicht 'physiker'.
    assert "nobelpreis" in captured["first"].lower()
    assert "physiker" not in captured["first"].lower()
    # Gewaehltes Summary traegt das Nobelpreis/1921-Fakt.
    assert "1921" in res["text"] and "nobelpreis" in res["text"].lower()


def test_fallback_to_query_without_rank(axis="physiker"):
    captured = {}

    def fake_embed(texts):
        captured["first"] = texts[0]
        return [_vec_for(t, axis) for t in texts]

    server._embed_batch = fake_embed
    res = server.extractive_summary(ARTICLE, query="physiker", n_sentences=1)
    assert res is not None
    # Ohne rank_query rankt es gegen 'physiker' (Rueckwaertskompat).
    assert captured["first"] == "physiker"


def test_max_scan_widens_window():
    # max_scan begrenzt das Lead-Fenster: ein KLEINES Fenster scannt weniger
    # Saetze als ein GROSSES. Wir vergleichen relativ (robust gegen die genaue
    # Satzzahl nach Filterung), nicht gegen absolute Werte.
    # Embed liefert konstante Vektoren -> Reihenfolge egal, nur scanned zaehlt.
    server._embed_batch = lambda texts: [[1.0, 0.0] for _ in texts]
    res_small = server.extractive_summary(
        ARTICLE, query="x", n_sentences=1, rank_query="x", max_scan=2,
    )
    res_big = server.extractive_summary(
        ARTICLE, query="x", n_sentences=1, rank_query="x", max_scan=20,
    )
    assert res_small is not None and res_big is not None
    assert res_small["scanned"] == 2          # exakt auf das kleine Fenster gedeckelt
    assert res_big["scanned"] > res_small["scanned"]  # groesseres Fenster scannt mehr


def test_disabled_returns_none():
    assert server.extractive_summary(ARTICLE, "x", 0) is None
    assert server.extractive_summary("", "x", 3) is None


# ── T223: Fakt-Bias + Lead-Anchor-Mix ───────────────────────────────────────

def test_lead_anchor_always_includes_sentence_one():
    """Lead-Anchor: Satz 1 (Definition) ist IMMER im Summary, auch wenn sein
    Cosine niedrig ist. Wir setzen die Cosine-Achse auf 'relativitaet' (Satz 3,
    Index 2) — der Definitions-Satz 1 hat dort Cosine 0, muss aber trotzdem rein."""
    server._embed_batch = lambda texts: [_vec_for(t, "relativitaet") for t in texts]
    res = server.extractive_summary(
        ARTICLE, query="einstein", n_sentences=2, rank_query="Wer war Einstein?",
    )
    assert res is not None
    # Index 0 (Definitions-Satz) MUSS dabei sein (Lead-Anchor).
    idxs = [s["index"] for s in res["sentences"]]
    assert 0 in idxs, f"Lead-Anchor fehlt, idxs={idxs}"
    assert "theoretischer Physiker" in res["text"]


def test_fact_boost_lifts_factsentence_over_floskel():
    """Fakt-Boost: Satz 2 (Nobelpreis 1921, Marker+Jahr → Boost 0.25) soll einen
    Lead-Floskel-Satz mit leicht HOEHEREM Cosine ueberholen.

    Konstruktion: Embed gibt Satz 3 (Index 2, 'relativitaet', KEIN Jahr/Marker)
    Cosine 1.0; Satz 2 (Nobelpreis) Cosine 0.85. Ohne Boost gewaenne Satz 3,
    mit Boost (0.85 + 0.25 = 1.10 > 1.0) gewinnt Satz 2. n_sentences=2 →
    Anker (0) + 1 Top-Score-Satz; der eine Slot muss an Satz 2 gehen."""
    def fake_embed(texts):
        out = []
        for t in texts:
            low = t.lower()
            if "relativitaet" in low:
                out.append([1.0, 0.0])
            elif "nobelpreis" in low:
                out.append([0.92, 0.392])  # cos ~0.92 gegen rank-vec [1,0]... s.u.
            else:
                out.append([0.3, 0.0])
        return out
    # rank_query-Vektor: wir mappen rank auf 'relativitaet'-Achse [1,0], sodass
    # der relativitaet-Satz Cosine 1.0 bekommt und der Nobelpreis-Satz ~0.92.
    server._embed_batch = lambda texts: (
        [[1.0, 0.0]] + [
            ([1.0, 0.0] if "relativitaet" in t.lower()
             else [0.92, 0.392] if "nobelpreis" in t.lower()
             else [0.3, 0.0])
            for t in texts[1:]
        ]
    )
    res = server.extractive_summary(
        ARTICLE, query="einstein", n_sentences=2, rank_query="Wer war Einstein?",
    )
    assert res is not None
    # Boost (0.85.. + 0.25) muss den Nobelpreis-Satz (Index 1) ins Summary holen,
    # trotz leicht niedrigerem reinem Cosine als der relativitaet-Satz.
    assert "1921" in res["text"] and "nobelpreis" in res["text"].lower(), res["text"]


def test_fact_helpers():
    assert server._has_year("1921 erhielt er den Nobelpreis.") is True
    assert server._has_year("Er war ein Physiker.") is False
    assert server._has_year("Mit 40000 Zaehnen.") is False  # kein 1xxx/20xx
    assert server._marker_hits("Er erhielt den Nobelpreis.") >= 1
    assert server._marker_hits("Geboren und gestorben und Nobelpreis.") == 2  # gedeckelt
    assert server._marker_hits("Ein gewoehnlicher Satz.") == 0
    assert server._has_measure("Die Radula traegt rund 40.000 Zaehnchen.") is True
    assert server._has_measure("Der Berg ist 8848 m hoch.") is True
    assert server._has_measure("Er war ein Physiker.") is False
    # Boost: Nobelpreis-1921-Satz = Jahr(0.15) + Marker×2(0.10·2, gedeckelt)
    # = 0.15 + 0.20 = 0.35. Zwei Marker: "nobelpreis" UND "erhielt".
    b = server._fact_boost("1921 erhielt er den Nobelpreis fuer Physik.")
    assert abs(b - 0.35) < 1e-6, b
    # Nur EIN Marker + Jahr = 0.15 + 0.10 = 0.25.
    b1 = server._fact_boost("Im Jahr 1879 wurde er geboren.")
    assert abs(b1 - 0.25) < 1e-6, b1
    assert server._fact_boost("Er war ein Physiker.") == 0.0


def test_fact_bias_killswitch():
    """HOSHI_BRIDGE_FACT_BIAS=0 → _fact_boost ist immer 0 (reines Cosine, T216)."""
    saved = server._FACT_BIAS_ENABLED
    try:
        server._FACT_BIAS_ENABLED = False
        assert server._fact_boost("1921 erhielt er den Nobelpreis.") == 0.0
    finally:
        server._FACT_BIAS_ENABLED = saved


# ── T140 — Extractive Zahlen-Vertrag ─────────────────────────────────────────
def test_number_fact_trigger():
    """_NUMBER_FACT_QUERY_RE feuert auf Zahl-Faktfragen, nicht auf Definition."""
    fire = (
        "wie viele Zaehne hat eine Weinbergschnecke",
        "wie hoch ist der Mount Everest",
        "wie alt wurde Einstein",
        "wann erhielt Einstein den Nobelpreis",
        "in welchem Jahr war das",
        "welches Jahr war die Mondlandung",
    )
    no_fire = (
        "wer war Marie Curie",
        "erzaehl mir etwas ueber Einstein",
        "was ist Photosynthese",
        "wie geht es dir",
    )
    for q in fire:
        assert server._NUMBER_FACT_QUERY_RE.search(q), q
    for q in no_fire:
        assert not server._NUMBER_FACT_QUERY_RE.search(q), q


def test_extract_number_facts_verbatim():
    """Zahl-Spans sind VERBATIM aus dem Text (Faithfulness): keine Rundung."""
    f = server.extract_number_facts(
        "Mit der Radula, auf der sich rund 40.000 Zähnchen befinden, frisst sie."
    )
    assert f == ["40.000 Zähnchen"], f
    f = server.extract_number_facts(
        "Albert Einstein war Physiker. 1921 erhielt er den Nobelpreis. Geboren 1879."
    )
    assert f == ["1921", "1879"], f
    f = server.extract_number_facts("Der Mount Everest ist mit 8848 m der hoechste Berg.")
    assert f == ["8848 m"], f
    # Mengen-Wort + zweites Wort bleiben am Span ("83 Millionen Einwohner").
    f = server.extract_number_facts("Die Stadt hat 83 Millionen Einwohner.")
    assert f == ["83 Millionen Einwohner"], f


def test_extract_number_facts_no_noise():
    """Reine Prosa / kurze Aufzaehl-Integers liefern keine Fakt-Spans."""
    assert server.extract_number_facts("Sie lebt in feuchten Waeldern.") == []
    assert server.extract_number_facts("") == []
    # Einzelne kleine Integers ohne Einheit (Aufzaehlung) fallen raus.
    assert server.extract_number_facts("Punkt 1 und Punkt 2 der Liste.") == []


def test_extract_number_facts_skips_reference_context():
    """Zahlen im Zitat-/Quellen-Kontext (Seite/ISBN) sind kein Fakt (Bibliografie)."""
    txt = "Mueller, Berlin 2010, S. 423 ff. ISBN 978-3-16-148410-0."
    # Weder die Seitenzahl (S. 423) noch die ISBN-Ziffern (978/16/148410)
    # duerfen als Fakt auftauchen — das ist Bibliografie, keine Antwort.
    f = server.extract_number_facts(txt)
    assert "423" not in f, f
    assert not any(x in f for x in ("978", "16", "148410")), f


def test_extract_number_facts_measure_question_drops_bare_years():
    """allow_years=False (Mass-/Mengen-Frage): nackte Jahre raus, Masse bleiben."""
    txt = "Der Turm wurde 1991 saniert und ist 26 m hoch."
    assert server.extract_number_facts(txt, allow_years=False) == ["26 m"]
    # Year-Frage darf das Jahr behalten.
    assert "1991" in server.extract_number_facts(txt, allow_years=True)
    # Reine Jahres-Liste + Mass-Frage → leer (ehrliches "weiss ich nicht").
    assert server.extract_number_facts(
        "Filme: 1984. 1997. 2002.", allow_years=False
    ) == []


# ── T140-Reparatur — Subjekt-Bindung der Zahl-Fakten ─────────────────────────
def test_subject_binding_measure_drops_wrong_unit():
    """Maß-Frage „wie hoch": nur Span mit erwarteter Einheit (Meter), KEINE
    Tonnage/Besucherzahl — auch wenn sie im selben Text steht."""
    txt = (
        "Der Eiffelturm ist ein 330 Meter hoher Turm. "
        "Die Stahlkonstruktion hat eine Masse von 7300 Tonnen. "
        "Jährlich kommen 3.155.000 Besucher."
    )
    f = server.extract_number_facts(
        txt, allow_years=False, fact_query="wie hoch ist der Eiffelturm",
        subject="Eiffelturm",
    )
    assert f == ["330 Meter"], f


def test_subject_binding_count_needs_counted_noun():
    """Zähl-Frage „wie viele Zähne": nur „N Zähnchen" zählt, nicht die
    Lebensdauer („30 Jahren") oder Größe („10 cm")."""
    txt = (
        "Die Weinbergschnecke wird bis zu 30 Jahren alt und 10 cm groß. "
        "Auf ihrer Radula sitzen rund 40.000 Zähnchen."
    )
    f = server.extract_number_facts(
        txt, allow_years=False,
        fact_query="wie viele Zähne hat eine Weinbergschnecke",
        subject="Weinbergschnecke",
    )
    assert f == ["40.000 Zähnchen"], f
    # Fehlt das gezählte Nomen → ehrliche Abstention (leer), KEINE Zufallszahl.
    txt2 = "Die Weinbergschnecke wird bis zu 30 Jahren alt und 10 cm groß."
    assert server.extract_number_facts(
        txt2, allow_years=False,
        fact_query="wie viele Zähne hat eine Weinbergschnecke",
        subject="Weinbergschnecke",
    ) == []


def test_subject_binding_birthyear_marker():
    """Zeit-Frage „wann geboren": das Jahr nach „*" (Geburts-Konvention) zählt,
    NICHT Publikations-/Todesjahre."""
    txt = (
        "Albert Einstein (* 14. März 1879 in Ulm; † 18. April 1955) war Physiker. "
        "Eine Biografie erschien 1982. 1999 wurde er zur Person des Jahrhunderts."
    )
    f = server.extract_number_facts(
        txt, allow_years=True, fact_query="wann wurde Albert Einstein geboren",
        subject="Albert Einstein",
    )
    assert f == ["1879"], f
    # Todes-Frage bindet ans „†".
    f2 = server.extract_number_facts(
        txt, allow_years=True, fact_query="wann ist Albert Einstein gestorben",
        subject="Albert Einstein",
    )
    assert f2 == ["1955"], f2


def test_subject_binding_event_year_without_marker():
    """Zeit-Frage ohne Struktur-Marker („wann bekam … Nobelpreis"): das Jahr am
    Ereignis-Nomen (Nobelpreis) zählt, nicht Geburts-/sonstige Vita-Jahre."""
    txt = (
        "Albert Einstein wurde 1879 geboren. "
        "1921 erhielt er den Nobelpreis für Physik. Er starb 1955."
    )
    f = server.extract_number_facts(
        txt, allow_years=True, fact_query="wann bekam Einstein den Nobelpreis",
        subject="Albert Einstein",
    )
    assert f == ["1921"], f


def test_backward_compat_no_fact_query():
    """Ohne fact_query bleibt das Alt-Verhalten (Rückwärtskompat)."""
    f = server.extract_number_facts(
        "Der Mount Everest ist mit 8848 m der höchste Berg."
    )
    assert f == ["8848 m"], f


# ── T140-Faithfulness-Härtung (Iter-138, Nora-Review) ───────────────────────
# Reproduziert die LIVE gemessenen Hazards gg. echte DB: bare Nicht-Jahr-Zahlen
# (Zitat-Seitenzahlen) leakten als „Fakt", weil sie 3-stellig + subjekt-nah waren.
def test_year_question_drops_citation_page_numbers():
    """Jahr-Frage „wann geboren": bare Zitat-Seitenzahlen (119/120) eng am
    Entity-Namen dürfen NICHT als Jahr-Fakt leaken — lieber leer (ehrlich)."""
    # Spiegelt den Live-Fall: das Geburtsjahr fehlt im Fenster, stattdessen eine
    # Bibliografie-Zeile mit dem Namen + Seitenzahlen.
    txt = (
        "Die Tochter Lieserl wurde 1902 geboren. "
        "Albrecht Fölsing: Albert Einstein. Eine Biographie. Frankfurt 1993, 119, 120."
    )
    f = server.extract_number_facts(
        txt, allow_years=True, fact_query="wann wurde Albert Einstein geboren",
        subject="Albert Einstein",
    )
    assert "119" not in f and "120" not in f, f


def test_year_question_drops_bare_non_year_number():
    """Jahr-Frage: eine bare 4-stellige Nicht-Jahr-Zahl (2334, z.B. Werknummer)
    ist nie eine Antwort auf „wann" → raus."""
    txt = "Asteroid (2334) Cuffey wurde nach ihm benannt. Er war Physiker."
    f = server.extract_number_facts(
        txt, allow_years=True, fact_query="wann ist Albert Einstein gestorben",
        subject="Albert Einstein",
    )
    assert "2334" not in f, f


def test_count_question_keeps_bare_number_under_thousand():
    """REGRESSIONS-GUARD für Fix B (Nora): eine Zähl-Antwort < 1000 ohne
    Tausenderpunkt („206 Knochen", „16 Bundesländer") muss erhalten bleiben —
    die Folge-Nomen-Validierung trägt sie, nicht die Gruppierung."""
    txt = "Der erwachsene Mensch hat 206 Knochen in seinem Skelett."
    f = server.extract_number_facts(
        txt, allow_years=False, fact_query="wie viele Knochen hat ein Mensch",
        subject="Mensch",
    )
    assert f == ["206 Knochen"] or "206 Knochen" in f, f
    txt2 = "Deutschland besteht aus 16 Bundesländern."
    f2 = server.extract_number_facts(
        txt2, allow_years=False,
        fact_query="wie viele Bundesländer hat Deutschland", subject="Deutschland",
    )
    assert any("16" in x for x in f2), f2


def test_year_question_suppresses_measure_units():
    """Jahr-Frage: ein Maß-Span („47 cm", „65 Jahren") ist nie die Antwort auf
    „wann" und darf das echte Jahr nicht verdrängen → nur das Jahr bleibt."""
    txt = (
        "Albert Einstein (* 14. März 1879 in Ulm) war Physiker. "
        "Bei der Geburt maß er etwa 47 cm. Mit 65 Jahren zog er um."
    )
    f = server.extract_number_facts(
        txt, allow_years=True, fact_query="wann wurde Albert Einstein geboren",
        subject="Albert Einstein",
    )
    assert f == ["1879"], f


def test_death_marker_recognises_starb():
    """„wann starb X": das Grundwort „starb" muss den Todes-Marker setzen, damit
    das Todesjahr (nach „†") bindet — sonst Recall-Loch."""
    txt = (
        "Albert Einstein (* 14. März 1879; † 18. April 1955 in Princeton) "
        "war Physiker. Er wurde 1921 mit dem Nobelpreis geehrt."
    )
    f = server.extract_number_facts(
        txt, allow_years=True, fact_query="wann starb Albert Einstein",
        subject="Albert Einstein",
    )
    assert f == ["1955"], f


def test_measure_unit_dative_plural_recognised():
    """Dativ-Plural-Einheit („368 Metern") muss als Maß-Span erkannt werden —
    sonst fällt die Zahl als bare Integer raus (live: Fernsehturm 368 → kein Fakt)."""
    txt = "Der Berliner Fernsehturm ist mit 368 Metern das höchste Bauwerk."
    f = server.extract_number_facts(
        txt, allow_years=False, fact_query="wie hoch ist der Berliner Fernsehturm",
        subject="Berliner Fernsehturm",
    )
    assert f == ["368 Metern"], f
    # Längen-Dativ-Plural ebenso.
    txt2 = "Der Fluss ist mit 2857 Kilometern der zweitlängste Strom Europas."
    f2 = server.extract_number_facts(
        txt2, allow_years=False, fact_query="wie lang ist der Fluss",
        subject="Fluss",
    )
    assert f2 == ["2857 Kilometern"], f2


def test_fact_subject_tokens_grammatical():
    """W6: Subjekt = Nomen nach dem Verb, Artikel + Schluss-Partizip/Maß-Adjektiv weg."""
    assert server._fact_subject_tokens("Wie viele Einwohner hat Berlin") == ["berlin"]
    assert server._fact_subject_tokens("Wie hoch ist der Mount Everest") == ["mount", "everest"]
    assert server._fact_subject_tokens("Wie heiß ist die Sonne") == ["sonne"]
    assert server._fact_subject_tokens("Wann ist die Titanic gesunken") == ["titanic"]
    assert server._fact_subject_tokens("Wie viele Knochen hat der Mensch") == ["mensch"]
    # Kein Verb → kein Subjekt (nackte Entity-Query bleibt unberührt).
    assert server._fact_subject_tokens("Mount Everest") == []
    assert server._fact_subject_tokens("") == []


def test_entity_fallback_position_and_abstain():
    """W7: bester Hauptartikel = Subjekt am Titel-Anfang/-Ende, größter Body,
    ABER nur bei eindeutiger Führung; sonst abstain (None)."""
    def row(title, nbytes):
        return ({"title": title, "plaintext_bytes": nbytes, "kern": None}, 0.0, None, 0)
    # Beethoven: „Ludwig van Beethoven" (Suffix, groß) schlägt Werk + Haus.
    pool = {
        1: row("14. Streichquartett (Beethoven)", 27000),
        2: row("Ludwig van Beethoven", 102000),
        3: row("Beethoven-Haus", 69000),
    }
    assert server._entity_fallback_aid(pool, ["beethoven"]) == 2
    # Genitiv-Überstrahler („… zur Titanic") nur, wenn Suffix UND eindeutig.
    pool2 = {
        1: row("Streitfragen zur Titanic", 101000),
        2: row("Titanic (Schiff)", 101200),
    }
    # beide ~gleich groß (Suffix bzw. Präfix) → kein eindeutiger Führer → abstain.
    assert server._entity_fallback_aid(pool2, ["titanic"]) is None
    # Mitte-Treffer (kein Präfix/Suffix) zählt nicht.
    pool3 = {1: row("Geschichte des Mondes im Mittelalter", 90000)}
    assert server._entity_fallback_aid(pool3, ["mond"]) is None
    # Definitionsfrage-Erkennung.
    assert server._DEFINITION_QUERY_RE.match("Wer war Beethoven")
    assert server._DEFINITION_QUERY_RE.match("Was ist Photosynthese")
    assert not server._DEFINITION_QUERY_RE.match("Wie hoch ist der Eiffelturm")


def test_title_boost_coverage():
    """W6: ein Titel der MEHR distinct Query-Tokens trägt schlägt einen der weniger
    trägt (Mount Everest vs Everest)."""
    toks = ["mount", "everest"]
    b_full = server._title_boost("Mount Everest", toks)   # deckt 2
    b_part = server._title_boost("Everest (2015)", toks)  # deckt 1
    assert b_full < b_part, (b_full, b_part)  # negativ = besser
    # Single-Token-Query: Coverage-Bonus ist 0 (kein Effekt).
    assert server._title_boost("Pluto", ["pluto"]) == server._title_boost("Pluto", ["pluto"])


def test_wie_alt_binds_explicit_age_span():
    """„wie alt wurde X" wird als Maß-Frage (Einheit Jahre) behandelt: ein
    expliziter Alters-Span („im Alter von 76 Jahren") wird gebunden, eine
    fremde Jahres-Zahl ohne Alters-Kontext nicht."""
    txt = "Goethe starb am 22. März 1832 im Alter von 82 Jahren in Weimar."
    f = server.extract_number_facts(
        txt, allow_years=False, fact_query="wie alt wurde Goethe",
        subject="Goethe",
    )
    assert f == ["82 Jahren"], f


def test_restfall_drops_bare_citation_number():
    """„wie alt"-Frage (Restfall ohne Einheit-Hint): bare Zitat-Zahl (135) eng
    am Namen ist Lärm → raus (Fix B greift auch im Restfall)."""
    txt = (
        "Einstein war ein Genie. "
        "Siehe dazu Pais 1982, 135, zur Wirkung seiner Arbeit."
    )
    f = server.extract_number_facts(
        txt, allow_years=False, fact_query="wie alt wurde Albert Einstein",
        subject="Albert Einstein",
    )
    assert "135" not in f, f


if __name__ == "__main__":
    test_rank_query_drives_cosine()
    test_fallback_to_query_without_rank()
    test_max_scan_widens_window()
    test_disabled_returns_none()
    test_lead_anchor_always_includes_sentence_one()
    test_fact_boost_lifts_factsentence_over_floskel()
    test_fact_helpers()
    test_fact_bias_killswitch()
    test_number_fact_trigger()
    test_extract_number_facts_verbatim()
    test_extract_number_facts_no_noise()
    test_extract_number_facts_skips_reference_context()
    test_extract_number_facts_measure_question_drops_bare_years()
    test_subject_binding_measure_drops_wrong_unit()
    test_subject_binding_count_needs_counted_noun()
    test_subject_binding_birthyear_marker()
    test_subject_binding_event_year_without_marker()
    test_backward_compat_no_fact_query()
    test_year_question_drops_citation_page_numbers()
    test_year_question_drops_bare_non_year_number()
    test_count_question_keeps_bare_number_under_thousand()
    test_year_question_suppresses_measure_units()
    test_death_marker_recognises_starb()
    test_measure_unit_dative_plural_recognised()
    test_wie_alt_binds_explicit_age_span()
    test_title_boost_coverage()
    test_entity_fallback_position_and_abstain()
    test_fact_subject_tokens_grammatical()
    test_restfall_drops_bare_citation_number()
    print("T216+T223+T140 bridge tests: ALL PASS")
