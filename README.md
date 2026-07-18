# Hoshi 星

**[English](#hoshi--english)** · **[Deutsch](#hoshi--deutsch)**

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero-dark.svg">
  <img src="docs/assets/hero-light.svg" alt="Hoshi 星 — ad astra per aspera" width="100%">
</picture>

---

## Hoshi — Deutsch

> Ein privater, deutscher, **lokal-first** Voice-Assistent, dem man vertrauen kann.
> Läuft auf einem einzelnen Apple-Silicon-Mac (16 GB). Keine Cloud-Pflicht, keine Telemetrie,
> deine Stimme bleibt bei dir.

**Status:** 0.8 „Nagareboshi" — aktiv in Entwicklung, Richtung 1.0.

### Was Hoshi kann

- **Sprechen & zuhören, komplett im Haus.** Wake-Word on-device (ESP32-S3) → Whisper-STT →
  Gemma-4-Brain (MLX, Metal-GPU) → TTS mit satzweisem Streaming — warm ab ~3 s bis zum ersten Ton.
- **Wissen, wer spricht.** Sprecher-Erkennung mit geführtem 3-Satz-Enroll; erkannte Personen
  erscheinen mit Name und Farbe im Chat. Gäste werden erkannt als „Gast" — und nie gespeichert.
- **Eigene Satelliten-Firmware.** HA Voice PE mit eigener ESPHome-Komponente: LED-Ring als
  Sprache (Stimm-VU, Denk-Swirl, Stop-Quittung), Volume-Dial, „Stop"-Wake, Nachtmodus pro Raum
  (Ring dimmt, wenn das Haus schläft).
- **Reflexe ohne Denkpause.** Timer, Wecker und Smart-Home-Kommandos laufen brain-frei in
  Millisekunden.
- **Wetter, ehrlich geerdet.** Ort per Geocoding (keyless open-meteo), Tages-Szenarien,
  Idle-Kachel, Rückfrage bei unklarem Ort.
- **Online-Wissen nur auf Wunsch.** Wenn lokal die Antwort fehlt, sagt Hoshi das ehrlich —
  und fragt, ob es online nachsehen darf (Quelle wird genannt, Kosten-Cap, Default: erst fragen).
- **Ein Tagebuch aus Messwerten, nie aus Inhalten.** Das Nutzungs-Diary speichert Zeitpunkt,
  Kategorie und Latenzen — keine Gesprächsinhalte. Der Aktivität-Tab zeigt p50/p95 je
  Pipeline-Stufe und die Zerlegung jedes Turns.
- **Fünf Farbwelten.** Aoi · Yoru · Asa · Kasumi · Nagareboshi — durchgehend token-basiert,
  `prefers-reduced-motion` wird respektiert.

### Was Hoshi besonders macht

1. **Ehrlichkeit ist Architektur, nicht Stil.** Wenn Hoshi etwas nicht weiß, sagt es das —
   deterministisch, statt zu halluzinieren. Die UI zeigt „—" statt erfundener Zahlen, leere
   Zustände sind ehrlich leer, und der interne Leitsatz `grün ≠ lebt` (ein grüner Test ist noch
   kein lebendes Feature) prägt jede Abnahme.
2. **Vertrauen liegt im Code, nicht im Prompt.** Capability-Kernel mit *default-deny* für jede
   schreibende Aktion; biometrische Stimm-Profile verlassen das Gerät nie; Gast-Stimmen werden
   nicht persistiert. Das gilt unabhängig davon, welches Modell gerade denkt.
3. **Das Modell ist eine tauschbare Zelle.** Hexagonale Ports, Modellwahl per Config-Zeile,
   Blind-A/B mit Ohren-Abnahme beim Wechsel — Hoshi ist gebaut, um mit jeder Modell-Generation
   besser zu werden, ohne seine Seele zu verlieren.
4. **Die 16-GB-Wand als Design-Lehrmeister.** Ein Brain resident, Admission-Steuerung,
   Latenz-Budgets pro Stufe — Genügsamkeit ist hier ein Feature, kein Kompromiss.
5. **Gebaut von einem Menschen mit einem KI-Team.** So ist Hoshi entstanden, und so wird
   es weitergebaut — Mensch und KI als ein Team, das etwas Dauerhaftes baut. (Die
   Projekt-Grundlagen liegen LLM-lesbar im [`vault/`](vault/00-INDEX.md); die private
   Werkstatt dahinter gehört dem Haus.)

### Architektur (kurz)

Hexagonal (Ports & Adapters) — STT-, TTS- und LLM-Engines sind austauschbar; nur DTOs queren
die Grenze. Ein dünnes Backend (Kotlin/Spring WebFlux) orchestriert die ML-Sidecars (Python/MLX)
und spricht über einen authentifizierten `/ws/audio`-Vertrag mit den Satelliten (ESPHome/HA Voice).

### Roadmap (ehrlich)

**0.9:** „Hey Hoshi"-Wake-Word (microWakeWord, deutsches Training) · Wecker klingeln am
Ursprungs-Satelliten · Setup & Übergabe (`hoshi setup`, SETUP.md, KI-lesbares Onboarding) ·
Multi-Room mit Wake-Arbitrierung · Metriken-Sparklines. **Bekannte Kanten:** siehe
„Ehrlichkeit zuerst" unten.

---

## Hoshi — English

> A private, German-speaking, **local-first** voice assistant you can trust.
> Runs on a single Apple Silicon Mac (16 GB). No cloud requirement, no telemetry —
> your voice stays home.

**Status:** 0.8 "Nagareboshi" — under active development, heading toward 1.0.

### What Hoshi does

- **Speaks and listens entirely in your home.** On-device wake word (ESP32-S3) → Whisper STT →
  Gemma 4 brain (MLX on the Metal GPU) → TTS with sentence streaming — first audio from ~3 s.
- **Knows who is talking.** Speaker recognition with a guided 3-sentence enrollment; recognized
  people show up in chat with their name and color. Guests are treated as guests — and never stored.
- **Its own satellite firmware.** HA Voice PE running a custom ESPHome component: an LED ring
  that speaks (voice VU, thinking swirl, stop acknowledgment), a volume dial, a "stop" wake model,
  and a per-room night mode that dims the ring while the house sleeps.
- **Reflexes without thinking pauses.** Timers, alarms and smart-home commands run brain-free in
  milliseconds.
- **Weather, honestly grounded.** Location via keyless open-meteo geocoding, day scenarios,
  an idle tile, and a follow-up question when the place is ambiguous.
- **Online knowledge only on request.** When the local brain doesn't know, Hoshi says so —
  and asks permission to look it up online (source cited, cost cap, ask-first by default).
- **A diary of measurements, never of content.** The usage diary stores timestamps, categories
  and latencies — no conversation content. The activity tab shows p50/p95 per pipeline stage and
  a per-turn breakdown.
- **Five color worlds.** Aoi · Yoru · Asa · Kasumi · Nagareboshi — fully token-based,
  `prefers-reduced-motion` respected.

### What makes it different

1. **Honesty as architecture, not tone.** Unknowns produce a deterministic "I don't know"
   instead of hallucination; the UI shows "—" rather than invented numbers; empty states are
   honestly empty. The house rule `green ≠ alive` (a green test is not yet a living feature)
   governs every acceptance.
2. **Trust lives in code, not in prompts.** A capability kernel with default-deny for every
   writing action; biometric voice profiles never leave the device; guest voices are never
   persisted — regardless of which model happens to be thinking.
3. **The model is a replaceable cell.** Hexagonal ports, model selection as a config line,
   blind A/B with human-ear acceptance on every swap — built to get better with each model
   generation without losing its soul.
4. **The 16 GB wall as a design teacher.** One resident brain, admission control, per-stage
   latency budgets — frugality is a feature here, not a compromise.
5. **Built by one human with an AI team.** That is how Hoshi came to be, and how it keeps
   being built — human and AI as one team, making something that lasts. (Project foundations
   live LLM-readable in [`vault/`](vault/00-INDEX.md); the private workshop behind them
   belongs to the house.)

### Architecture & build (at a glance)

Hexagonal (ports & adapters); a thin Kotlin/Spring WebFlux backend orchestrates Python/MLX
sidecars and talks to satellites over an authenticated `/ws/audio` contract (ESPHome/HA Voice).

```bash
./gradlew build                          # backend (Kotlin modules + ArchUnit guards)
cd frontend && npm install && npm run build
bin/hoshi run      # boots locally on :8090
bin/hoshi doctor   # honest, read-only stack status (OK/DEGRADED/DOWN)
```

Requires an Apple Silicon Mac (MLX needs the Metal GPU); the Gradle wrapper auto-provisions
JDK 21. The brain, Whisper-STT and speaker-ID sidecar *servers* live in [`sidecars/`](sidecars/)
(each with its own pinned `bootstrap.sh`); the knowledge bridge and local TTS are still external —
see the honest note in the German build section. Full German build guide: [Build & Run](#build--run).

### For reviewers: verify in 5 minutes

This project's house rule is `green ≠ alive` — claims come with runnable proof, not screenshots
of test output. On any machine with JDK auto-provisioning (see above):

```bash
./gradlew build            # all Kotlin modules + tests + ArchUnit boundary guards
cd frontend && npm install && npm test
python3 tools/speaker-ab/run_ab.py --smoke   # speaker A/B eval harness, self-contained proof
```

Then read, in this order: [`AGENTS.md`](AGENTS.md) (every claim paired with the command that
proves it) · [`CHANGELOG.md`](CHANGELOG.md) (dated, honest scope) · [`SECURITY.md`](SECURITY.md)
(default-deny kernel, open findings listed rather than hidden) · [`tools/speaker-ab/`](tools/speaker-ab/)
(offline FAR/FRR evaluation — measurement before any live threshold change). A full voice turn
needs the ML sidecars on Apple Silicon; without them, `bin/hoshi doctor` reports DOWN honestly
instead of faking green — that behavior itself is part of the design.

---

## Stack
- **Backend:** Kotlin · Spring WebFlux · Java 21
- **Brain (LLM):** Gemma-4 via MLX (lokal) — 16-GB-Wand: ein Modell resident
- **STT:** Whisper-MLX · **TTS:** Voxtral (lokal) / OpenAI (opt-in)
- **Speaker-ID:** CAM++ (Wespeaker, Apache-2.0) · **Wissen:** lokale Wiki-RAG
- **Frontend:** React · Vite · TypeScript · **Satellit:** ESPHome (HA Voice PE)

## Build & Run

**Voraussetzungen**
- Apple-Silicon-Mac (macOS). MLX braucht die Metal-GPU — läuft nicht in einem Linux-Container oder
  auf x86.
- **JDK 21 musst du nicht selbst installieren.** Der Gradle-Wrapper provisioniert es automatisch
  (foojay-resolver-convention in [`settings.gradle.kts`](settings.gradle.kts) +
  `org.gradle.java.installations.auto-download=true` in [`gradle.properties`](gradle.properties)).
  Verifiziert (2026-07-11) mit komplett frischem `GRADLE_USER_HOME` (kein JDK, keine
  Gradle-Distribution vorab gecacht): `./gradlew` lädt sich Gradle selbst *und* JDK 21 und baut grün —
  auch wenn die Maschine sonst nur ein neueres/anderes JDK auf dem PATH hat.
- Node 20+ fürs Frontend.

**Backend bauen**
```bash
./gradlew build
```
Baut alle Kotlin-Module (hexagonal: `core-domain`, `capability-kernel`, `adapters-*`, `web-inbound`)
inkl. Tests und den ArchUnit-Guards (der Kern darf nicht auf Spring/Adapter zeigen).

**Frontend bauen**
```bash
cd frontend && npm install && npm run build
```

**Python-Sidecars (STT/TTS/Brain/Speaker-ID/Knowledge) — ehrlicher Stand**
Hoshi orchestriert mehrere lokale ML-Sidecars über HTTP auf festen Ports: Whisper-STT (`:9001`),
Speaker-ID/CAM++ (`:9002`), Knowledge-Bridge/Wiki-RAG (`:8035`), das Brain/LLM via MLX (`:8041`),
TTS (`:8042` lokal bzw. OpenAI opt-in). `bin/hoshi up` fährt diesen Stack brain-guard-sicher und
idempotent hoch (siehe `bin/hoshi help` bzw. [`pipeline/up.sh`](pipeline/up.sh)).

**Brain, Whisper-STT und Speaker-ID sind Teil dieses Repos** ([`sidecars/`](sidecars/)): je Sidecar
ein gepinntes `bootstrap.sh` (venv + requirements, kein Modell-Download — Gewichte kommen lazy über
den HuggingFace-Cache) und ein `run.sh`. `bin/hoshi up` wählt automatisch den Repo-Sidecar, sobald
sein venv gebootstrapped ist (Override: `HOSHI_SIDECARS_FROM_REPO=true|false`). Knowledge-Bridge und
der lokale TTS-Server sind noch *nicht* portiert — sie laufen über Run-Skripte eines separaten,
unveröffentlichten Vorgänger-Checkouts (`HOSHI_05_ROOT`); auf einem frischen Klon überspringt
`bin/hoshi up` sie ehrlich (Warnung statt Fake-Start), während Backend und die drei Repo-Sidecars
trotzdem bauen und laufen. Der Port der restlichen Sidecars ist offen (Roadmap im
[`vault/`](vault/00-INDEX.md)).

**Backend starten**
```bash
bin/hoshi run      # bootet lokal auf :8090, prüft Health + die Auth-Wand (401 ohne Token)
bin/hoshi verify   # der grüne Gate: Build + Tests + Live-Brain-Smoke
bin/hoshi doctor   # ehrlicher, read-only Stack-Status (Brain/Sidecars/RAM — OK/DEGRADED/DOWN)
```

**Konfiguration / Env-Vars**
Laufzeit-Flags (Feature-Flags, Sidecar-URLs, Tokens) sind Spring-Properties, gesetzt über
`Environment=`-Zeilen. Die vollständige, kommentierte Referenz für einen Produktions-Deploy ist
[`tools/systemd/hoshi-0.8-backend.service`](tools/systemd/hoshi-0.8-backend.service) — dort stehen
alle bekannten Flags samt Begründung; echte Secrets/Tokens sind darin nur `__PLATZHALTER__`, die ein
Deploy-Skript füllt und nie committet. Für lokale Entwicklung reichen die Defaults; sensible Pfade
(Cloud-TTS, Sprecher-Erkennung, HA-Steuerung) sind einzeln flag-gated und default-dokumentiert in
derselben Datei.

**Ehrlichkeit zuerst:** Hoshi ist ein persönliches Projekt, gebaut und gehärtet auf genau einem
Apple-Silicon-Mac (16 GB) in einem spezifischen Zuhause-Setup (ein bestimmtes Home-Assistant/LAN).
Es ist kein für beliebige Umgebungen poliertes Produkt — Pfade, Ports und Annahmen spiegeln diese eine
Maschine. Wer es adaptiert, sollte Kanten erwarten (siehe Abschnitt oben zu den Sidecars).

## Mitmachen
Siehe [`CONTRIBUTING.md`](CONTRIBUTING.md) — inkl. Lizenz-Zustimmung und DCO (`Signed-off-by`). Die
Vision, die Invarianten und die Roadmap leben im Vault: [`vault/00-INDEX.md`](vault/00-INDEX.md).

## Lizenz
[Apache License 2.0](LICENSE) — siehe auch [`NOTICE`](NOTICE). Beiträge laufen über den DCO, siehe
[`CONTRIBUTING.md`](CONTRIBUTING.md#lizenz--herkunft-dco).

**Lizenzgrenze der optionalen Piper-TTS-Runtime:** Der Hoshi-Kern (dieses Repository) bleibt
durchgehend Apache-2.0. Wer den lokalen Piper-Sidecar (`sidecars/piper/`) per `bootstrap.sh`
aktiviert, lädt zur Laufzeit separat lizenzierte, nicht mitgelieferte Artefakte — darunter die
Piper-Engine unter GPL-3.0-or-later. Nichts davon ist im Repository gebündelt; Details, Pins und
Quellen stehen in [`sidecars/piper/LICENSES.md`](sidecars/piper/LICENSES.md). Die Engine ist
default-OFF.

---

*Ad astra per aspera — Hoshi (星), der Stern, der bleibt. Jede Mitwirkung ist eine Sternschnuppe über seinem Himmel.*

*Ad astra per aspera — Hoshi (星), the star that stays. Every contribution is a shooting star across its sky.*
