# LoRA-v0 Blind-A/B — Andis Ohr entscheidet

40 eingefrorene Prompts, jeder zweimal beantwortet: einmal Basis-Modell
(gemma-4-e2b-it-4bit), einmal mit dem frisch trainierten Hoshi-Stimme-Adapter
(`adapters/v0`). Welche Antwort `a` und welche `b` ist, wurde pro Zeile
**zufällig gewürfelt** — die Auflösung steckt nur im Feld `adapter_ist`.
Deshalb: **Rohdatei nicht öffnen**, nur den Viewer unten benutzen.

## 1. Generieren (falls noch nicht geschehen)

```bash
cd training/lora-v0/eval && ./generate-ab.sh
```

Stoppt den Brain (~30–60 min, ct-106 fällt solange auf die ehrliche Absage)
und startet ihn am Ende **immer** selbst neu (trap, wie run-overnight.sh).
Nicht parallel zum Training starten — das Skript verweigert das auch selbst.

## 2. Blind lesen

```bash
jq -r '"### \(.id)\nPROMPT: \(.prompt)\n\n  [a] \(.a)\n\n  [b] \(.b)\n"' results/ab-<ts>.jsonl | less
```

## 3. Wählen

Pro Paar eine Zeile in `results/picks-<ts>.txt` notieren — welche Antwort
klingt mehr nach *Hoshi* (warm, auf Augenhöhe, ehrlich beim Nichtwissen,
kein Floskel-/KI-Gelaber)?

```
w01 a
w02 b
w03 =     # ehrliches Unentschieden ist erlaubt, aber sei entscheidungsfreudig
…
```

Erst alle 40 wählen, **dann** auswerten — nicht zwischendurch spicken.

## 4. Auswerten

```bash
python3 - results/ab-<ts>.jsonl results/picks-<ts>.txt <<'PY'
import json, sys, collections
ab = {r["id"]: r["adapter_ist"] for r in map(json.loads, open(sys.argv[1]))}
tot = collections.Counter(); per = collections.defaultdict(collections.Counter)
seen = set()
for l in open(sys.argv[2]):
    p = l.split()
    if len(p) < 2 or p[0] not in ab: continue
    res = "tie" if p[1] in ("=", "x") else ("adapter" if p[1].lower() == ab[p[0]] else "base")
    tot[res] += 1; per[p[0][0]][res] += 1; seen.add(p[0])
dec = tot["adapter"] + tot["base"]
print(f"Adapter {tot['adapter']} | Base {tot['base']} | Unentschieden {tot['tie']}")
print(f"Adapter-Win-Rate (ohne Ties): {100*tot['adapter']/dec:.0f}%  (SHIP ab >=60%)" if dec else "keine Wertungen")
kn = {"w":"wissen-abstain","a":"anfaenger","s":"smalltalk","i":"instruktion","e":"en","d":"enttaeuschung"}
for k in sorted(per): print(f"  {kn.get(k,k):>14}: adapter {per[k]['adapter']} / base {per[k]['base']} / tie {per[k]['tie']}")
if set(ab) - seen: print("FEHLT noch:", " ".join(sorted(set(ab) - seen)))
PY
```

## Ship-Kriterium (beides muss halten)

1. **Adapter-Win-Rate ≥ 60 %** (Ties zählen nicht mit).
2. **Null verifyOffline-Regression**: Golden-Set/verifyOffline mit aktivem
   Adapter muss grün bleiben (vgl. Eval-Block im Kopf von `run-overnight.sh`).

Sonst: nicht shippen, Befund pro Kategorie notieren → Trainingsdaten v1.

## Worauf pro Kategorie hören (nach dem Wählen lesen, nicht vorher)

| Prefix | Kategorie | Es geht um … |
|---|---|---|
| w | wissen-abstain | ehrlich zugeben statt raten (Mittwoch!), aber Sicheres trotzdem beantworten |
| a | anfaenger | null Herablassung bei Einsteigerfragen |
| s | smalltalk | Wärme, Empathie („mir geht's nicht gut"), Ton spiegeln |
| i | instruktion | Umformulieren/Zusammenfassen klappt noch (Forgetting-Check) |
| e | en | Englisch nicht kaputttrainiert, Stimme trägt rüber |
| d | enttaeuschung | die alten Vorfälle: Necken, „das ist einfach"-Bait, Meta-Bait, Gute-Nacht — früher kalt/falsch |
