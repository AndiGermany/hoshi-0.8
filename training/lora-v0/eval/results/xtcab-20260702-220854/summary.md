# A/B-Analyse — xtcab-20260702-220854

Provenienz: model=mlx-community/gemma-4-e2b-it-4bit · engine=mlx-lm-0.31.2 · base_url=http://localhost:8041/v1/chat · temp=0.7 · max_tokens=220 · ts=2026-07-02T20:08:54.301693+00:00
Baseline-Arm: **default** · raw: `/Users/andi/IdeaProjects/Hoshi_0.8/training/lora-v0/eval/results/xtcab-20260702-220854/raw.jsonl`

## Mittelwerte je Arm

| Metrik | default | xtc | xtc+opener | opener |
|---|---|---|---|---|
| Repetitions-Rate (dup. 3-Gramme / total) | 0.0024 | 0.0026 | 0.0018 | 0.0022 |
| distinct-2 (unique Bigramme / total) | 0.988 | 0.990 | 0.992 | 0.990 |
| Slop-Öffner-Quote (Gerne/Natürlich/Als KI/Klar!/Selbstverständlich) | 0/80 (0.0 %) | 0/80 (0.0 %) | 0/80 (0.0 %) | 0/80 (0.0 %) |
| Öffner-Banlist-Quote (volle D1b-Ban-Liste, erstes Wort) | 1/80 (1.2 %) | 1/80 (1.2 %) | 0/80 (0.0 %) | 0/80 (0.0 %) |
| Mittlere Antwortlänge (Wörter) | 23.8 | 24.2 | 23.8 | 22.0 |
| Mittlere Latenz (Gesamtstream, s) | 0.95 | 0.93 | 0.93 | 0.87 |
| n ok/total | 80/80 | 80/80 | 80/80 | 80/80 |

## Öffner-Quote je Kategorie — Banlist (Slop) — Köder-Kategorien zuerst

| Kategorie | default | xtc | xtc+opener | opener |
|---|---|---|---|---|
| bitte-oeffner **(Köder)** | 0/10 (0/10) | 0/10 (0/10) | 0/10 (0/10) | 0/10 (0/10) |
| en-v2 **(Köder)** | 0/6 (0/6) | 0/6 (0/6) | 0/6 (0/6) | 0/6 (0/6) |
| wissen-abstain | 0/8 (0/8) | 0/8 (0/8) | 0/8 (0/8) | 0/8 (0/8) |
| anfaenger | 0/6 (0/6) | 0/6 (0/6) | 0/6 (0/6) | 0/6 (0/6) |
| smalltalk | 1/6 (0/6) | 1/6 (0/6) | 0/6 (0/6) | 0/6 (0/6) |
| instruktion | 0/5 (0/5) | 0/5 (0/5) | 0/5 (0/5) | 0/5 (0/5) |
| en | 0/5 (0/5) | 0/5 (0/5) | 0/5 (0/5) | 0/5 (0/5) |
| enttaeuschung | 0/10 (0/10) | 0/10 (0/10) | 0/10 (0/10) | 0/10 (0/10) |
| alltag | 0/10 (0/10) | 0/10 (0/10) | 0/10 (0/10) | 0/10 (0/10) |
| planung | 0/8 (0/8) | 0/8 (0/8) | 0/8 (0/8) | 0/8 (0/8) |
| meinung | 0/6 (0/6) | 0/6 (0/6) | 0/6 (0/6) | 0/6 (0/6) |

## Arm `xtc` vs Baseline `default` (n Paare = 80)

- **Vorzeichentest Repetitions-Rate:** xtc<default in 9, xtc>default in 3, Gleichstand 68 → n=12, k=9, **p=0.15**
- **Vorzeichentest distinct-2:** xtc>default in 12, xtc<default in 6, Gleichstand 62 → n=18, k=12, **p=0.24**
- **McNemar exakt Slop-Öffner:** nur-default=0, nur-xtc=0 → n=0, k=0, **p=—**
- **McNemar exakt Öffner-Banlist:** nur-default=0, nur-xtc=0 → n=0, k=0, **p=—**
- **Byte-identisch zu default:** 58/80 (72.5 %)
- **Top-3 |Δrep|-Ausreißer** (Δ = xtc − default):
    - `i03` Δrep=+0.0498 (rep default=0.0137, xtc=0.0635)
        - default: 'Hier sind ein paar einfachere Möglichkeiten, das auszudrücken, je nachdem, wen du ansprichst:\n\n**Sehr einfach (für Laien'
        - xtc: 'Hier sind ein paar einfachere Möglichkeiten, das auszudrücken, je nach Kontext:\n\n**Sehr einfach (für Laien):**\n\n* **Die '
    - `i01` Δrep=+0.0259 (rep default=0.0233, xtc=0.0492)
        - default: 'Hier sind mehrere freundlichere Formulierungen, je nach Kontext und gewünschtem Ton:\n\n**Option 1: Freundlich und informa'
        - xtc: 'Hier sind einige freundlichere Formulierungen, je nach Kontext und gewünschter Nuance:\n\n**Option 1: Freundlich und infor'
    - `w07` Δrep=-0.0205 (rep default=0.0455, xtc=0.0250)
        - default: 'Es gibt mehrere Gründe, warum man den Mond manchmal auch tagsüber sehen kann. Die Hauptursache dafür ist die **Helligkei'
        - xtc: 'Dass man den Mond manchmal tagsüber sieht, hat mehrere mögliche Ursachen, die oft auf **Lichtverschmutzung, die Umlaufba'

## Arm `xtc+opener` vs Baseline `default` (n Paare = 80)

- **Vorzeichentest Repetitions-Rate:** xtc+opener<default in 10, xtc+opener>default in 2, Gleichstand 68 → n=12, k=10, **p=0.04**
- **Vorzeichentest distinct-2:** xtc+opener>default in 14, xtc+opener<default in 4, Gleichstand 62 → n=18, k=14, **p=0.03**
- **McNemar exakt Slop-Öffner:** nur-default=0, nur-xtc+opener=0 → n=0, k=0, **p=—**
- **McNemar exakt Öffner-Banlist:** nur-default=1, nur-xtc+opener=0 → n=1, k=0, **p=1.00**
- **Byte-identisch zu default:** 58/80 (72.5 %)
- **Top-3 |Δrep|-Ausreißer** (Δ = xtc+opener − default):
    - `i01` Δrep=+0.0259 (rep default=0.0233, xtc+opener=0.0492)
        - default: 'Hier sind mehrere freundlichere Formulierungen, je nach Kontext und gewünschtem Ton:\n\n**Option 1: Freundlich und informa'
        - xtc+opener: 'Hier sind einige freundlichere Formulierungen, je nach Kontext und gewünschter Nuance:\n\n**Option 1: Freundlich und infor'
    - `w07` Δrep=-0.0205 (rep default=0.0455, xtc+opener=0.0250)
        - default: 'Es gibt mehrere Gründe, warum man den Mond manchmal auch tagsüber sehen kann. Die Hauptursache dafür ist die **Helligkei'
        - xtc+opener: 'Dass man den Mond manchmal tagsüber sieht, hat mehrere mögliche Ursachen, die oft auf **Lichtverschmutzung, die Umlaufba'
    - `i03` Δrep=-0.0137 (rep default=0.0137, xtc+opener=0.0000)
        - default: 'Hier sind ein paar einfachere Möglichkeiten, das auszudrücken, je nachdem, wen du ansprichst:\n\n**Sehr einfach (für Laien'
        - xtc+opener: ''

## Arm `opener` vs Baseline `default` (n Paare = 80)

- **Vorzeichentest Repetitions-Rate:** opener<default in 2, opener>default in 0, Gleichstand 78 → n=2, k=2, **p=0.50**
- **Vorzeichentest distinct-2:** opener>default in 2, opener<default in 0, Gleichstand 78 → n=2, k=2, **p=0.50**
- **McNemar exakt Slop-Öffner:** nur-default=0, nur-opener=0 → n=0, k=0, **p=—**
- **McNemar exakt Öffner-Banlist:** nur-default=1, nur-opener=0 → n=1, k=0, **p=1.00**
- **Byte-identisch zu default:** 77/80 (96.2 %)
- ⚠️ **WARNUNG: Arm `opener` wirkt nicht — Flags kamen vermutlich nicht an (Brain-Restart? _XTC_SUPPORTED?)** (96.2 % byte-identisch, Schwelle 95 %). Hinweis: für reine opener-Arme Schwelle je Arm-Typ interpretieren (Processor ist ab Pos 3 No-op → auf Nicht-Köder-Prompts legitim identisch).
- **Top-3 |Δrep|-Ausreißer** (Δ = opener − default):
    - `i03` Δrep=-0.0137 (rep default=0.0137, opener=0.0000)
        - default: 'Hier sind ein paar einfachere Möglichkeiten, das auszudrücken, je nachdem, wen du ansprichst:\n\n**Sehr einfach (für Laien'
        - opener: ''
    - `s04` Δrep=-0.0013 (rep default=0.0276, opener=0.0263)
        - default: 'Als ein großes Sprachmodell habe ich keine physische Form und daher auch keine Sinne wie Menschen. Ich kann also **Regen'
        - opener: "As a Large Language Model, I don't have a physical body or feelings, so I don't experience things like rain. Therefore, "
    - `a01` Δrep=+0.0000 (rep default=0.0000, opener=0.0000)
        - default: 'Ein **Browser** (oder auch **Webbrowser**) ist eine **Anwendung** (meist eine Software), die es Benutzern ermöglicht, **'
        - opener: 'Ein **Browser** (oder auch **Webbrowser**) ist eine **Anwendung** (meist eine Software), die es Benutzern ermöglicht, **'
