#!/usr/bin/env python3
"""
label_probes.py — die 38 frozen2-Proben von Hand labeln, BLIND.

WARUM BLIND:
Diese Proben sind die einzige unabhängige Prüfung, die wir für die Sprecher-
Erkennung haben. Würde der Klassifikator die Labels erzeugen, prüfte er sich
selbst und bestünde per Konstruktion — der einmalige Holdout wäre verbrannt,
ohne dass wir irgendetwas wüssten. Deshalb: der Mensch hört, der Mensch
entscheidet. Und deshalb zeigt dieses Werkzeug WEDER Score NOCH Modell-Vermutung
NOCH den bisherigen Dateipfad-Hinweis — eine gezeigte Vermutung würde die
Antwort anziehen (Anchoring), und das wäre dieselbe Zirkularität durch die
Hintertür.

Die Reihenfolge ist deterministisch gemischt (fester Seed), damit die
Aufnahme-Reihenfolge einer Sitzung nicht zum Muster wird ("die ersten zehn waren
alle ich, also ist die elfte auch ich").

BEDIENUNG
    python3 tools/speaker-ab/label_probes.py

    [a]      Person A (Andi)
    [b]      Person B
    [space]  nochmal hören
    [?]      unsicher — WICHTIG: lieber unsicher als geraten. Eine geratene
             Wahrheit ist schlimmer als eine fehlende; unsichere Proben werden
             in der Auswertung sauber ausgeschlossen statt still mitgezählt.
    [q]      speichern und beenden (jederzeit; Fortsetzen ist möglich)

Jede Antwort wird SOFORT geschrieben — Abbruch verliert nichts, ein zweiter
Start macht genau dort weiter, wo du aufgehört hast.

DATENSCHUTZ: Person B hat nie zugestimmt, hier Datenpunkt zu sein. Ihr Name
taucht in der Ausgabe nicht auf, nur `person-b`. Die Audiodateien werden nur
temporär auf den Mac geholt (zum Abspielen) und am Ende wieder gelöscht.
"""
from __future__ import annotations

import csv
import random
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

REMOTE = "ct-106"
REMOTE_TSV = "/var/lib/hoshi-0.8/speaker-ab-frozen/frozen2-1928/report/probes.tsv"
OUT = Path(__file__).with_name("owner-labels.tsv")
SEED = 20260725  # fest: gleiche Reihenfolge bei jedem Lauf ⇒ Fortsetzen ist stabil

LABELS = {"a": "person-a", "b": "person-b", "?": "unsicher"}


def fetch_probe_list() -> list[dict[str, str]]:
    """Die Probenliste vom Server holen — NUR Pfad, Kanal, Dauer. Scores bleiben dort."""
    raw = subprocess.run(
        ["ssh", "-o", "ConnectTimeout=10", REMOTE, f"cat {REMOTE_TSV}"],
        capture_output=True, text=True, check=True,
    ).stdout
    rows = list(csv.DictReader(raw.splitlines(), delimiter="\t"))
    return [
        {
            "probe_id": Path(r["wav_path"]).stem,
            "wav_path": r["wav_path"],
            "channel": r.get("channel", ""),
            "duration_s": r.get("duration_s", ""),
        }
        for r in rows
        if r.get("wav_path")
    ]


def load_done() -> dict[str, str]:
    if not OUT.exists():
        return {}
    with OUT.open(newline="", encoding="utf-8") as fh:
        return {r["probe_id"]: r["truth"] for r in csv.DictReader(fh, delimiter="\t")}


def append(row: dict[str, str]) -> None:
    new = not OUT.exists()
    with OUT.open("a", newline="", encoding="utf-8") as fh:
        w = csv.DictWriter(fh, fieldnames=["probe_id", "truth", "channel", "duration_s"], delimiter="\t")
        if new:
            w.writeheader()
        w.writerow(row)


def getkey() -> str:
    """Einen Tastendruck lesen, ohne Enter (tty) — sonst Zeilen-Eingabe als Fallback."""
    try:
        import termios, tty  # noqa: E401  (nur auf Unix vorhanden)
        fd = sys.stdin.fileno()
        old = termios.tcgetattr(fd)
        try:
            tty.setraw(fd)
            return sys.stdin.read(1).lower()
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old)
    except Exception:
        return (sys.stdin.readline().strip() or "\n")[:1].lower()


def main() -> int:
    print("\n  Proben labeln — blind, ohne Modell-Vermutung.\n")
    try:
        probes = fetch_probe_list()
    except subprocess.CalledProcessError:
        print(f"  Komme nicht an {REMOTE}. Läuft der Rechner, geht ssh {REMOTE}?")
        return 2

    random.Random(SEED).shuffle(probes)
    done = load_done()
    todo = [p for p in probes if p["probe_id"] not in done]

    if not todo:
        print(f"  Alle {len(probes)} Proben sind schon gelabelt → {OUT.name}")
        return 0
    if done:
        print(f"  Fortsetzung: {len(done)} von {len(probes)} erledigt.\n")

    tmp = Path(tempfile.mkdtemp(prefix="hoshi-proben-"))
    try:
        for i, p in enumerate(todo, 1):
            local = tmp / f"{p['probe_id']}.wav"
            if not local.exists():
                subprocess.run(["scp", "-q", f"{REMOTE}:{p['wav_path']}", str(local)], check=True)

            print(f"  ── {i} von {len(todo)}   ({p['duration_s']}s, {p['channel']})")
            while True:
                subprocess.run(["afplay", str(local)], check=False)
                print("     [a] Andi   [b] Person B   [?] unsicher   [Leertaste] nochmal   [q] Schluss")
                k = getkey()
                if k == " ":
                    continue
                if k == "q":
                    print(f"\n  Gespeichert: {len(done)} Labels → {OUT}")
                    return 0
                if k in LABELS:
                    append({"probe_id": p["probe_id"], "truth": LABELS[k],
                            "channel": p["channel"], "duration_s": p["duration_s"]})
                    done[p["probe_id"]] = LABELS[k]
                    print(f"     → {LABELS[k]}\n")
                    break
                print("     (a, b, ?, Leertaste oder q)")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    counts: dict[str, int] = {}
    for v in done.values():
        counts[v] = counts.get(v, 0) + 1
    print("\n  Fertig. " + " · ".join(f"{k}: {n}" for k, n in sorted(counts.items())))
    print(f"  Datei: {OUT}")
    print("\n  Als Nächstes wird deine Wahrheit gegen die Modell-Scores gehalten —")
    print("  getrennt nach Kanal und Sitzung. Erst das sagt, WORAN es scheitert.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
