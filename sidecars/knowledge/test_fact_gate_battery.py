"""Satz-1-Tie-Break-Battery (2026-07-02, Nora) — Jahr-&-Maß-Gate im /search.

Prüft den Satz-1-Tie-Break VOR der Single-Value-Abstention (server.py,
Jahr-&-Maß-Zweig): liefert der 700-Zeichen-Lead MEHRERE distinct Zahl-Spans,
wird NUR der erste Lead-Satz erneut gescannt; genau EIN distinct Span dort
→ facts=[dieser], sonst weiter [] (Abstention lebt, Faithfulness > Recall).

Läuft gegen die ECHTE articles.db (read-only, mode=ro — der Live-Prozess
:8035 bleibt unberührt) via Modul-Import:

    .venv/bin/python3 test_fact_gate_battery.py

Fälle (Battery 2026-07-02, live gegen echte DB verifiziert):
  1. Eiffelturm    „wie hoch"  → Lead trägt 330 UND 312 Meter → Satz 1
                   („…ist ein 330 Meter hoher…") löst auf → ['330 Meter'].
  2. Fernsehturm   „wie hoch"  → Regression: bleibt ['368 Metern'] (Satz 1
                   == einziger Lead-Kandidat, Tie-Break feuert gar nicht).
  3. Mozart        „wann starb" → ['1791'] (Mozart-Fix 2026-07-02, Nora). Der
                   Lead ist mit Taufbuch-/Sterbebuch-Zitationen verklebt
                   („…Salzburg;AES, …Taufbuch…, S. 3., abgerufen…"), dadurch
                   lag „† 5. Dezember 1791" >60 Zeichen von jedem Subjekt-
                   Stamm → das Restfälle-subj_near-Gate warf das Jahr raus,
                   BEVOR der Marker-Check lief. Fix: bei Ereignis-Zeitfragen
                   MIT Marker (†/starb/geb/gegr …) ersetzt die Marker-Bindung
                   (±30 VOR dem Jahr) die Subjekt-Nähe — Doppel-Prüfung weg,
                   1791 ist der EINZIGE marker-gebundene Kandidat im Lead.
  4. Einstein      „wann geboren" → bleibt ['1879'] (Marker „*", Satz 1).
  5. Köln          „welches Jahr gegründet" → EHRLICHER NICHT-BAU-BEFUND
                   (Scheibe 2, 2026-07-02): [] ist KORREKT und jetzt gepinnt.
                   (a) Der Lead trägt kein Gründungsjahr (Einwohner/Verwaltung).
                   (b) Der Volltext datiert die Gründung nur „in römischer
                   Zeit" (Passage @927; „50 n. Chr." = Stadt-Erhebung, als
                   „50" außerhalb _FACT_YEAR_FULL_RE 1xxx/20xx). (c) Ein
                   Marker-Passage-Fallback wäre AKTIV GEFÄHRLICH: der Volltext
                   trägt 20+ marker-gebundene Jahre — alles Gründungen von
                   Chören/Orchestern IN Köln („Camerata Köln (gegründet
                   1979)", „seit seiner Gründung 1951 … Studio für
                   elektronische Musik"); das BM25-lite-Ranking wählt genau
                   die Chor-Listen-Passage als Top-Treffer → Single-Value-Gate
                   würde dort z.T. EIN falsches Jahr (1975/1951) selbstbewusst
                   emittieren. Darum: kein Passage-Pfad, Abstention gepinnt —
                   JEDES 1xxx/20xx-Jahr wäre für Köln (Antike!) falsch, also
                   ist der []-Assert drift-sicher.
  6. Elbe→Schönebeck (Elbe): Lead mehrdeutig (15 km/48 m/94,4 m/115,5 m),
                   Satz 1 trägt KEINEN Kandidaten → weiter [] (Abstention).
  7. München       „wie groß" → Lead mehrdeutig (6,2 vs 2,93 Mio. Einwohner —
                   Metropolregion vs. Agglomeration), Satz 1 zahlenfrei →
                   weiter [] (Abstention).
  8. Synthetisch:  Satz 1 trägt SELBST zwei Kandidaten (100/120 Meter) →
                   distinct>1 → Gate liefert [] (in der echten DB fand die
                   Probe keinen solchen Lead, darum unit-level gepinnt).
"""
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))

import server  # noqa: E402  (öffnet die DB nur read-only, mode=ro)

FAILS: list[str] = []
ROWS: list[tuple[str, str, str]] = []  # (Frage, facts, Status)


def _hit(resp, title: str):
    for h in resp.hits:
        if h.title == title:
            return h
    return None


def _lead_diag(article_id: int, fq: str, q: str) -> tuple[list, list]:
    """Rechnet lead_distinct + s1_distinct nach (Diagnose, wie der Handler)."""
    conn = server.open_conn()
    try:
        row = conn.execute(
            "SELECT plaintext_zstd, plaintext_bytes FROM articles WHERE id=?",
            (article_id,),
        ).fetchone()
    finally:
        conn.close()
    full = server._decompress_full(row["plaintext_zstd"], row["plaintext_bytes"] or 0)
    lead = server.clean_extract(full)[:server._YEAR_LEAD_CHARS] if full else ""
    ay = bool(server._YEAR_QUESTION_RE.search(fq))
    dist = list(dict.fromkeys(server.extract_number_facts(
        lead, allow_years=ay, fact_query=fq, subject=q)))
    m = server._SENT_SPLIT_RE.search(lead, 20)
    s1 = lead[:m.start() + 1] if m else lead
    d1 = list(dict.fromkeys(server.extract_number_facts(
        s1, allow_years=ay, fact_query=fq, subject=q)))
    return dist, d1


def case(fq: str, q: str, title: str, check, label: str) -> None:
    resp = server.search(q=q, limit=5, dedupe=False, fact_query=fq)
    h = _hit(resp, title)
    facts = h.facts if h else None
    ok, note = check(h, facts)
    ROWS.append((fq, repr(facts), ("OK  " if ok else "FAIL") + " " + note))
    if not ok:
        FAILS.append(f"{label}: {fq!r} → facts={facts!r} ({note})")


# 1. Eiffelturm — der Fix: Satz-1-Tie-Break löst 330-vs-312 auf.
case(
    "Wie hoch ist der Eiffelturm?", "Eiffelturm", "Eiffelturm",
    lambda h, f: (f == ["330 Meter"], "Tie-Break: Lead 330+312 → Satz 1 → 330"),
    "1-Eiffelturm",
)

# 2. Berliner Fernsehturm — Regression: einziger Lead-Kandidat bleibt.
case(
    "Wie hoch ist der Berliner Fernsehturm?", "Berliner Fernsehturm",
    "Berliner Fernsehturm",
    lambda h, f: (bool(f) and len(f) == 1 and "368" in f[0], "368 bleibt"),
    "2-Fernsehturm",
)

# 3. Mozart — Mozart-Fix: Marker-Bindung ersetzt Subjekt-Nähe → ['1791'].
#    (Vorher []: subj_near warf das †-gebundene Jahr im Zitations-Lead raus.)
case(
    "Wann starb Mozart?", "Mozart", "Wolfgang Amadeus Mozart",
    lambda h, f: (f == ["1791"],
                  "†-Marker ±30 bindet 1791 trotz Zitations-Lead"),
    "3-Mozart",
)

# 4. Einstein — Regression Jahr-Pfad mit Marker „*".
case(
    "Wann wurde Albert Einstein geboren?", "Albert Einstein",
    "Albert Einstein",
    lambda h, f: (f == ["1879"], "1879 bleibt"),
    "4-Einstein",
)

# 5. Köln — Scheibe-2-Nicht-Bau-Befund (s. Docstring): [] ist GEPINNT.
#    Drift-sicher: Köln ist antik gegründet — jedes emittierte 1xxx/20xx-Jahr
#    wäre eine Institutions-Gründung IN Köln (1951/1975/…) = echter Bug.
case(
    "In welchem Jahr wurde Köln gegründet?", "Köln", "Köln",
    lambda h, f: (f == [], "Abstention gepinnt: Volltext trägt kein "
                           "extractor-fähiges Gründungsjahr (nur Antike)"),
    "5-Köln",
)

# 6. Elbe → Schönebeck (Elbe): Lead mehrdeutig, Satz 1 leer → Abstention lebt.
def _check_ambig(h, f):
    if h is None:
        return False, "Hit fehlt"
    dist, d1 = _lead_diag(h.articleId, _AMBIG_FQ, _AMBIG_Q)
    if len(dist) <= 1:
        return False, f"Vorbedingung weg: lead_distinct={dist} (DB-Drift?)"
    if len(d1) == 1:
        return False, f"Satz 1 löst jetzt auf: {d1} (DB-Drift?)"
    return f == [], f"lead_distinct={len(dist)} Werte, s1={len(d1)} → []"

_AMBIG_FQ, _AMBIG_Q = "Wie lang ist die Elbe?", "Elbe"
case(_AMBIG_FQ, _AMBIG_Q, "Schönebeck (Elbe)", _check_ambig, "6-Elbe-Schönebeck")

# 7. München: Lead mehrdeutig (6,2 vs 2,93 Mio. Einwohner), Satz 1 zahlenfrei.
_AMBIG_FQ, _AMBIG_Q = "Wie groß ist München?", "München"
case(_AMBIG_FQ, _AMBIG_Q, "München", _check_ambig, "7-München")

# 8. Synthetisch: Satz 1 trägt selbst ZWEI Kandidaten → Gate muss abstinieren.
_SYN_S1 = (
    "Der Beispielturm ist ein 100 Meter hoher Turm des Beispielturm-Vereins "
    "mit einer 120 Meter hohen Antenne."
)
_syn = list(dict.fromkeys(server.extract_number_facts(
    _SYN_S1, allow_years=False,
    fact_query="Wie hoch ist der Beispielturm?", subject="Beispielturm")))
_syn_facts = _syn if len(_syn) == 1 else []  # exakt die Gate-Zeile
_ok8 = len(_syn) > 1 and _syn_facts == []
ROWS.append((
    "Wie hoch ist der Beispielturm? (synthetisch, Satz 1 mehrdeutig)",
    repr(_syn_facts),
    ("OK  " if _ok8 else "FAIL") + f" s1_distinct={_syn} → Gate []",
))
if not _ok8:
    FAILS.append(f"8-Synthetisch: s1_distinct={_syn} → facts={_syn_facts}")

# ── Report ──────────────────────────────────────────────────────────────────
W = max(len(r[0]) for r in ROWS)
print("\n── Satz-1-Tie-Break-Battery ──")
for frage, facts, status in ROWS:
    print(f"  {frage:<{W}}  facts={facts:<32}  {status}")
if FAILS:
    print(f"\nBATTERY ROT ({len(FAILS)} Fails):")
    for f in FAILS:
        print("  -", f)
    sys.exit(1)
print(f"\nBATTERY GRÜN — {len(ROWS)} Fälle.")
