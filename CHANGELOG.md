# Changelog

Alle nennenswerten Änderungen an Hoshi. Format lose an
[Keep a Changelog](https://keepachangelog.com/) angelehnt — dieses Projekt hat
noch keine erste stabile Version, Einträge sind daher grob nach Thema statt
nach Release sortiert.

## 0.9.0 — Nagori 名残 (2026-08-22) 🌊

Die Version, die dalässt, was trägt: ein Spiegel, der Vollzug beweist statt
behauptet, sechzehn lebende Szenen unter einem Tageslage-Himmel, ein Raster,
das Kacheln dort liegen lässt, wo eine Hand sie hinlegt — und ein Satellit,
der sein eigenes Küchenlicht schaltet. Name: 名残 („das, was zurückbleibt" —
von 波残り, was die Welle am Strand lässt), Battle-Sieger 8:6:5:5, von Andi
ratifiziert (14.08.). 0.8.5 hatte nie einen eigenen Changelog-Eintrag
(Versions-Bump ohne Erzählung) — sein Inhalt steckt mit in diesem.

### Nachrichten/Lagebild
- Multi-Source-Lagebild verdrahtet: Tagesschau + heise + Golem über einen
  brain-freien News-Fastpath (HTTP-Rand, keine Brain-Aufrufe im Hintergrund),
  Quellen-Auswahl als eigene Settings-Naht, Attribution/Quellen-Badges je
  Eintrag, Anzeigen-Filter-Policy gegen leere/verschwundene Quellen.
- „Heute"-Fenster auf der Übersicht, Nachrichten-Kachel mit eigenem
  Anzeige-Schalter (Default an).
- Die amtliche Warnspur (NINA) und Personalisierung sind eigene Scheiben
  nach 0.9 (siehe „Bewusst NICHT in 0.9.0").

### Widget-Raster (Zuhause) — W1 bis W7 komplett
- W1–W4: Registry mit vier Stufen (Krone/Bühne) statt fester Koordinaten,
  Layout-Speicher (Reihenfolge + Stufe, gehärtet), Stufen-Wähler mit einem
  Pointer-Schiedsrichter, „Layout zurücksetzen" mit Rückfrage, die Uhr wird
  ein eigenes Bühnen-Widget.
- W5: XL wird eine eigene Inhalts-Stufe statt „L, nur größer" — Wetter zeigt
  den Stunden-Verlauf als SVG, das Lagebild wird zweispaltig mit längeren
  Teasern, Klima/Einkauf/Läuft bekommen ihre XL-Form, die XL-Listen tragen
  ihre Fläche selbst.
- W6: Edit-Feinschliff nach Andis Livetest — der Wecker verlässt den Kopf,
  die Fußleiste wird so breit wie ihre Wörter und hört auf zu wackeln, die
  Hilfe wird eine Zeile, der Edit-Modus bekommt echte Türen (Tipp aufs
  Widget oder ins Leere) und einen dritten Ausgang namens „Vergessen".
- W7: das freie Raster — eine Kachel besitzt eine **Zelle**, keinen
  Listenplatz. Kacheln bleiben, wo man sie hinlegt, Lücken sind erlaubt,
  die Edit-Bedienung ist eine Schicht AUF der Kachel und verschiebt nichts
  mehr, gesteuert wird nur noch mit +/− (Placements je Orientierung,
  Saat-Migration für Bestands-Layouts).
- Kachel-Ausbau: die große Uhr trägt den Sonnenbogen (Aufgang → jetzt →
  Untergang, aus echten sunrise/sunset-Daten), die Wetter-XL eine
  Mehrtages-Zeile (heute + 7 Tage), das Grounding kennt die JETZT-Werte.
- Sauger nützlich + bedienbar: Start/Zur-Basis als echte Knöpfe
  (`POST /api/v1/home/vacuum/{start|return_to_base}` über einen
  Ein-Entity-Service-Caller, Status ehrlich durchgereicht, Kagami-konform
  im Diary), „Bereit in der Ladestation" statt geisterhaftem „zuletzt
  gesehen", L/XL-Zeilen aus bisher ungenutzten Sensoren, Energiesparmodus
  ist kein Ausfall mehr (Cache-Carry).

### Themes — die v2-Welle und drei Neue
- **16 lebende Szenen** in drei Tageslage-Gruppen (Morgen: Asagiri, Asa,
  Yoake · Tag: Fuyubare, Hanaikada, Komorebi, Momiji, Hanashigure, Ukiyo,
  Natsunohi, Aoi · Abend & Nacht: Natsumatsuri, Yukiakari, Amayadori, Yoru,
  Nagareboshi), dazu Sora (Tageszeit-Automatik), Kasumi (letzter Klassiker,
  in den Ruhestand gruppiert) und das versteckte Stimmungs-Theme Nagori.
- Die v2-Welle baute jede Bestands-Szene mit dem destillierten
  Szenen-Generator-Rezept neu: volle Bilder statt Randstreifen (Asa bekommt
  ein Zimmer mit Andon, Amayadori eine Gasse mit sichtbarem Regen, Aoi ein
  Wrack im Tangwald, Yoake echte Glut, Yoru einen Engawa-Blick mit
  Shoji-Bahnen und eine gemessene Aufhellung in vier Schritten,
  Natsumatsuri ein Hanabi-Taikai statt Aufkleber am Bildrand). Jede Szene
  mit AA-Kontrast-Beweis aus echten Chrome-Pixeln, Herzschlag-Messung und
  Selbstabnahme mit eigenen Augen — Frames in zwei Breiten, vor dem Merge
  angesehen.
- Drei frisch getaufte Neue (Namen: Andi): **Hanaikada** 花筏 — das
  Blütenfloß, Kirschblütenwald am Fluss · **Fuyubare** 冬晴れ — der klare
  Wintertag · **Yukiakari** 雪明かり — das Schneelicht der Nacht.
- Galerie als Ein-Klick-Vollbild mit Tageslage-Gruppen und
  Helligkeits-Ordnung — sie ersetzt den 331-px-Drawer-Käfig; der Weg hinein
  und hinaus headless gemessen.
- Guard-Tests pinnen Manifest ↔ CSS-Wahrheit (Swatches kommen aus den
  echten CSS-Token der Datei, nicht aus Erinnerung).
- Nagori bleibt das versteckte Stimmungs-Theme (3×-Tap-Fund an der
  Versionszeile) — mit diesem Schnitt steht sein Name offen neben der
  Versionsnummer; die Fund-Erzählung („Vorbote der 0.9") ist dadurch
  bewusst nostalgisch geworden (Andis Gate, offen).

### Stimme/F2 (Zuhause + Satellit)
- Raum-Kontext reist mit dem Turn (`ChatRequest.originAreaId` statt
  Hardcode), „kein Raum heißt kein Raum" löst den Wohnzimmer-Fallback ab,
  Home-Assistant-Zeile im Ops-Bild, 60s-Zustands-Frische mit persistentem
  Last-known-Speicher, die verb-lose Licht-Kante (Präposition+Raum als
  Anker).
- „Stand: vor X min" jetzt sprechbar in 5 Sprachen — letzte offene
  F2-Bauscheibe aus dem Plan.
- **Satelliten-Exit-Beweis ERBRACHT** (22.08., 07:24): „Küche schaltet
  Küche" am echten Gerät — zwei Voice-PE-Turns im Diary mit
  `toolCallRan=true`, `area=kuche`, Licht an und wieder aus.
- Raumnamen-Lecks (10 Stellen) geschlossen: der Vollzugs-Satz spricht den
  HA-Anzeigenamen statt des `area_id`-Slugs, EINE geteilte Namens-Auflösung,
  `targetAreaName` reist additiv neben dem Slug ins Diary — in 5 Sprachen,
  mit ehrlichem Fallback.
- WS-Konversations-Nähte für echte Rückfragen über den Satelliten:
  `llm_done` sagt dem Satelliten, dass eine Rückfrage offen ist
  (`expectReply`), und die Eskalations-Antwort verpufft nicht mehr still
  (`speak_push`). Flag OFF, bis die Firmware-Hälfte gebaut ist.

### Sicherheit/F4
- Turn-Inbound-Claims zentral verriegelt (Guard gegen fremde/gefälschte
  Inbounds), Pending-Conversations isoliert (Session-Key/Pending-Arbiter,
  Codex-Paket), die Raum-Rückfrage wird ein echter Pending-Zustand statt
  Fire-and-forget (F1-4 — der erste Stein des Reparaturdialogs).
- Verstümmelungs-Korpus + Replay-Tests für Sprachbefehle (Multi-Turn-
  Raumklärung, sichere Korruptions-Fälle); alle 13 Korpus-Labels von Andi
  abgenommen (homophone-01 → TOOL_CALL).
- **Erster Kagami-Live-Replay GRÜN** (21.08., Prod-Stack): 13 Fälle, 14
  Turns — falsche Vollzugs-Behauptungen = 0, Beweislücken = 0. Der Spiegel
  behauptet nicht, er belegt.

### Betrieb
- F1/Ishibashi-Nacharbeit: `brainTimeout`/Wedge jetzt im Diary sichtbar statt
  durch `brainTtft=null` verdeckt, Grounding-Timeout 5s→2s, Brain-Chat-Timeout
  30s→20s über die gemessene Verteilung (955 Diary-Turns) statt gefühlt,
  `toolCallRan` als Kreuzbeweis, ob der Tool-Executor wirklich lief.
- F3/Tsugi: SETUP-Wahrheit + `bin/hoshi ha check`, `bin/hoshi backup`
  (Manifest, Verify, fail-closed) und `bin/hoshi restore` inklusive einer
  echten Wiederherstellungs-Probe.
- F6/Togi: Kommentar-Migration Welle 1 an den Rändern (Ports, Adapter-KDoc,
  deploy.sh), toter `KeywordRouterStub` gelöscht, KDoc-Lügen in
  `PipelineStubAdapters` korrigiert.
- Remote-Deploy-Verify mit Build-ID aus Bundle-Inhalt statt Zeitstempel
  (reproduzierbarer Build), Eskalations-Timeout 8s→15s, `publish-satellite.sh`
  als wiederholbarer, sanitisierender Export-Weg zum Satelliten-Repo.
- BE-Stabilität: harte Gesamt-Turn-Deadline (60 s) mit Timeout-Spur im
  Diary, hartes Gesamt-Budget über `callBrain` inklusive Empty-Retry, und
  der blockierende 5-s-HA-Areas-Call im Event-Loop wird
  stale-while-revalidate.
- FE-Effizienz: Brotli/Gzip beim Build vorkomprimiert und per
  Accept-Encoding ausgeliefert (**dist −77 %**), Poller pausieren bei
  dunklem Display (−29.664 Requests/Tag; zusammen **−63 % Requests**).
- CI führt jetzt auch die Python-Suiten aus: pytest-Job für 17/20 Suiten
  (7.367 Testzeilen), die drei Ausschlüsse (Modelle nötig) ehrlich
  dokumentiert statt still übersprungen.
- Modell-Klarheit: die neuere HF-Revision des Brains erwies sich im A/B als
  wertlos und unladbar — wir bleiben bewusst auf der gepinnten Revision,
  das gefahrlose A/B-Runbook liegt bereit. Der FATAL-Hilfetext des Brains
  verlangt jetzt `revision=` (sein alter Rat verbog `refs/main` — Ursache
  eines 4,5-h-Incidents). Neu an Bord, noch OFF: der Persona-KV-Freeze
  (gemessen ×27 schnellere Time-to-first-Token) — Flip nach Kohärenz-A/B.
- „Darstellung" in den Settings ist ein Auslöser statt eines Ortes — die
  Zwischenseite ist aufgelöst; der Timer-Klang (`timer_ring`) kennt Zeit
  und Art (Codex-Paket K4).

### Livetest-Finale (22.–23.08.) — Andis Zurufe, am selben Abend gelandet
- **Widget-Raster W8:** jede Kachel größenveränderbar (die Nachrichten
  erstmals — ihre fehlende Bühnen-Durchreiche war der Grund), Uhr und Wetter
  messen ihre Typografie an der KACHEL statt am Fenster (Container-Queries,
  `vw`-Rückfall für ältere Browser); zwei echte Raster-Wurzeln gefixt: eine
  Seite zeichnet immer alle ihre Zeilen (eine frei gelassene unterste Zeile
  schrumpfte vorher die Seite — „die Uhr über die ganze Höhe"), und der Zug
  rechnet gegen die GEZEICHNETE Zelle (waagerecht ziehen ging vorher nicht).
- **Edit-Modus still:** Hinweis-Prosa und die Ablage-Leiste unten sind weg,
  die Bearbeitungsfläche wächst um ~7 %; An/Aus der Widgets wohnt in den
  Einstellungen („Zuhause & Integrationen → Zuhause-Widgets", der Platz
  übersteht Aus→An). Langer Druck auf einer Verlinkung öffnet NUR den Edit.
- **Edit-Modus ganz still (23.08.):** auch die Leiste OBEN fällt — der Modus
  trägt jetzt gar keine eigene Bedienung mehr, die Bearbeitungsfläche IST die
  Bühne (1366 × 1024: 880 × 669 px, byte-identisch mit dem Zustand außerhalb).
  „Zurücksetzen" zieht zu den Widget-Schaltern in die Einstellungen, mit
  derselben Rückfrage; die drei Ausgänge (Tipp aufs gewählte Widget · Tipp ins
  Leere · Escape) sind einzeln in Chrome UND Firefox bewiesen. Unsichtbar
  bleibt, was null Pixel kostet: Tastatur-Belegung und `aria-live`-Ansage.
- **Das Sprech-Overlay überlagert, statt Platz zu nehmen (23.08.):** die Blase
  mit dem letzten Turn stand im Fluss des Orb-Blocks und zog jeden ihrer Pixel
  von der Bühne ab — gemessen wurden Kachel-Kasten 669 → 355 px, Zeilenhöhe
  153 → 162 px, Seiten 3 → 6, alle acht Kacheln versetzt. Sie liegt jetzt in
  einer eigenen Schicht über der Bühne (deckend statt Glas, weil sie auf
  Kacheln liegt; Kontrast 9,8/14,9 gemessen, kein `backdrop-filter`). Kachel-
  Rechtecke vor/während/nach einem echten Turn byte-identisch, beide Engines,
  1366 × 1024 und 834 × 1112.
- **Firefox ist jetzt Beweis-Achse:** Eine Capture-Zeile ließ in Gecko jeden
  +/−-Klick verpuffen (Click-Retargeting nach `setPointerCapture` — in
  Chrome unsichtbar). Gefixt und am echten Firefox bewiesen; die
  Sonden-Flotte fährt seither Chrome UND Firefox (WebDriver BiDi).
- **Orb −38 % Fläche**, Transparenz der Innen- und Widget-Flächen als EIN
  Token (`--surface-mix: 86 %`), AA über alle 16 Szenen gehalten.
- **Sauger-Wartung menschlich:** „noch ~7 Tage" / „überfällig seit ~12 h"
  statt roher Sekunden (Semantik am HA-Core-Quelltext bewiesen), Mopp +
  Wassertank eine Zeile. Und ein Ehrlichkeits-Fund: HA nimmt Service-Calls
  auf schlafende Geräte mit 200 an und lässt sie stumm fallen (Quelltext-
  Beleg) — der Start-Knopf sendet jetzt nur noch an ein waches Gerät,
  sonst sagt er ehrlich, dass der Sauger schläft (409, `toolCallRan=false`).
- **Die Uhr-L zeigt nachts die Mondphase** 🌙 — lokal berechnet (Meeus
  Kap. 47/48, gegen die vier echten Finsternisse 2026 getestet), Terminator
  als Geometrie, 8 Phasennamen in 5 Sprachen, Wechsel an den echten
  Sonnenzeiten.
- **Betrieb:** Persona-KV-Freeze AN (×27 warme Time-to-first-Token, Iter-137-
  Drift-Historie dokumentiert, Kohärenz-A/B-Werkzeug liegt) · Piper-TTS auf
  4 Threads · mlx-lm-Upgrade vorbereitet (Parallel-venv, Fenster-Runbook,
  eigener Gemma-Patch upstream obsolet).

### Bewusst NICHT in 0.9.0
Die amtliche Warnspur (NINA) und Nachrichten-Personalisierung (eigene
Scheiben nach 0.9) · das Kalender-Widget (wartet auf eine
HA-Kalender-Integration) · die Firmware-Hälfte der WS-Konversation (Flag
bleibt OFF) · das Persona-KV-Freeze-Kohärenz-A/B (der Freeze selbst ist seit
22.08. AN — bewusster Risiko-Call, Werkzeug liegt bereit) ·
Flag-Regal, Stimmung-Gruppe und Nagori-Easter-Egg-Copy (Andis
Ein-Satz-Gates, offen). Suite beim Schnitt: ~1.904 FE- + ~3.000 BE-Tests
grün.

## 0.8.4 — Einladung 🌠 (2026-08-08)

Die Version, die Fremde hereinbittet: ein Befehl statt einer Abschrift, eine
Wahrheit statt dreier Meinungen, ein Schloss mit Schlüssel auf beiden Seiten —
und eine Anlern-UI, die aus einem chaotischen Familien-Abend gelernt hat.

### Installierbar: der kürzeste Weg wird ein Vierzeiler

- **`bin/hoshi preflight`** sagt read-only, ob diese Maschine Hoshi fahren kann
  (Tools, JDK, Platte, RAM, Ports, Modelle) — mit `--profile split` für den
  Zwei-Maschinen-Fall, der nur druckt, was zu setzen wäre, statt zu raten.
- **`bin/hoshi setup`** orchestriert die bisherige Abtipp-Folge idempotent:
  zweiter Lauf in unter einer Sekunde, alles „schon da". Modelle lädt er nie
  selbst — Lizenz-Klicks bleiben menschlich.
- **Verifizierte Beschaffung:** `models.json` v2 trägt volle Revision, Bytes
  und SHA-256 je Modell; `tools/verified_fetch.py` holt Hash-geprüft, mit
  Resume, Lizenz-Gates und atomarer Aktivierung (Codex-Paket). Auch die zwei
  Beschaffungswege zur Laufzeit sind jetzt fail-closed: `/switch-model` lädt
  nur noch hash-verifizierte Snapshots, und der STT-Erstpull läuft nicht mehr
  lautlos an den Pins vorbei.
- **Frischklon-CI:** drei Jobs beweisen, dass ein nackter Checkout baut, die
  Suiten grün sind und die echte Version im Bundle steckt.
- **Eine Modell-Wahrheit:** models.json sagt e4b, die Skripte sagen e4b, und
  der neue doctor-Check `model-truth` meldet DEGRADED, falls je wieder eine
  Seite ohne die andere dreht. Die Version kommt überall aus gradle.properties
  — die Kopfzeile von `bin/hoshi` behauptete zuletzt „0.8.2".

### Sicherheit: die Tür bekommt ein Schloss (opt-in)

- Alle sechs Sidecars kennen jetzt eine Token-Wand (`HOSHI_SIDECAR_TOKEN` /
  `X-Hoshi-Token`, timing-sicher, `/health` bleibt immer offen), und das
  Backend kann den Schlüssel auf allen Client-Pfaden tragen. Ungesetzt ändert
  sich nichts; der Live-Flip folgt bewusst erst, wenn auch Werkzeuge und
  Deploy denselben Schlüssel beweisen können (Red-Team-Checkliste, Codex).

### Bedienbar: Settings, Räume, Sprecher

- **Räume S1:** ehrliche Kopfzeile („x von y zugeordnet"), Suche als primäre
  Navigation, vier Domänen-Chips, „Braucht dich"-Inbox mit vorbelegtem
  Raum-Vorschlag — geschrieben wird erst auf „Bestätigen". Und „living room"
  findet das Wohnzimmer wieder (die englischen Aliase waren seit dem 16.07.
  in Prod verloren).
- **Turn-Feed** zeigt die letzten 25 mit Tages-Trennern und lädt Frühere auf
  Wunsch nach — statt einfach nur zu wachsen.
- **TTS-Engine ist ein Dropdown**, Stimm-Details erscheinen nur zur passenden
  Engine; das Nachschlag-Modell erscheint nur, wenn Online überhaupt an ist.
  Dazu ein Bündigkeits-Pass: ein Raster, eine Titel-Regel, ein Innenabstand.
- **Sprecher-Anlernen nach dem 08.08.-Abend:** „Weiter anlernen" per Klick
  (kein Namen-Tippen mehr), aufklappbare Aufnahmen-Liste mit ehrlichem
  „passt gut/mäßig/nicht" und Einzel-Löschen (neue BE-Naht mit Zentroid-
  Neumittelung), Riegel gegen den stillen Profil-Ersatz am vollen Profil,
  und der Hinweis, der den Abend gerettet hätte: eine Person, ein Raum.

### Betrieb: bewiesen statt behauptet

- **Graceful Shutdown am echten Turn gemessen:** ein Restart wartet auf den
  laufenden Turn (17 s beobachtet), der Stream endet mit sauberem `done`.
- Zwei Zeitbomben-Tests entschärft (TTL gegen die Wanduhr — dritter Vorfall
  dieser Klasse, jetzt mit injizierter Uhr), grüne Dependency-Bumps mit
  `--rerun-tasks`-Beweis, Vitest 4/jsdom 30 bei byte-gleichem Bundle.
- Der Frischklon ist 0.5-frei (Audit über 12 Dateien); was nur auf der
  Referenz-Maschine läuft, ist jetzt ehrlich so beschriftet.

### Satellit: aufgeräumt für Fremde

- Eigenes Repo überarbeitet: **ein Substitutions-Kopf statt 626 Zeilen
  anfassen**, bewiesene Version-Pins, XMOS-Reflash nur noch als Opt-in,
  OTA-Passwort scharf, WLAN-Rettungsweg, `secrets.yaml.example` — und das
  trainierte „Hey Hoshi"-Wake-Modell als **GitHub-Release-Asset**
  (`hey-hoshi-v1`), Blobs bleiben aus dem Git. Der Export dorthin ist jetzt
  ein wiederholbares Skript mit blockierendem Sanitize-Scan statt Handarbeit.

## 0.8.3 — Suisei ☄ (2026-07-26)

Ein einziger Tag, aber ein dichter: die Wissens-Kette geht live, die Modelle wählen sich selbst,
und drei Anzeigen sagen jetzt die Wahrheit, die das System längst kannte.

### Wissen: lokal zuerst

- **Hoshi schaut erst bei sich nach, dann im Netz.** Ein „ja" auf das Nachschau-Angebot versucht
  die gespeicherte Frage zuerst gegen die lokale Wikipedia — mit erneuter, strenger Deckungsprüfung
  gegen die *ursprüngliche* Frage, damit kein tangentialer Treffer als Antwort durchgeht. Nur wenn
  lokal nichts trägt, geht es mit Quelle ins Netz. Hörbar wird die Herkunft am Vorspann: „Hab kurz
  **bei mir** nachgeschaut — " gegen „Hab kurz **im Netz** geschaut — ".
- **Zusammengesetzte Wörter narren das Wetter nicht mehr.** „Sonnensystem" und „Sonnenfinsternis"
  enthalten „Sonne" — und wurden deshalb als Wetterfragen gekapert, worauf die lokale Wikipedia nie
  gefragt wurde. Die Erkennung arbeitet jetzt an Wortgrenzen; echte Wetterfragen bleiben unverändert.
- **„ja schau kurz nach" verliert den Faden nicht mehr.** Die natürliche Zustimmung wurde bisher als
  Smalltalk gelesen und das offene Angebot verfiel; die Kette ist mit dem realen Fall als
  Regressionstest festgenagelt.

### Modelle: das richtige zur richtigen Zeit

- **Das dichte Gemma-4-12B läuft jetzt wirklich** — über einen eigenen, mitgelieferten
  Laufzeit-Patch: keine veröffentlichte mlx-lm-Version kennt seine Bauart (`gemma4_unified`),
  dabei ist sein Textteil fast der des E4B; es fehlte nur ein Modul, das das weiß. Gemessen ist
  es die menschlichste Antwortqualität im Haus — bei rund doppelter Antwortzeit.
- **Automatische Modellwahl (abschaltbar, ab Werk aus):** beim Tippen antwortet das gründliche
  Modell, beim Sprechen das schnelle. Der Wechsel aufs Sprech-Modell startet, während man noch
  spricht — die drei Sekunden verstecken sich in der eigenen Sprechzeit. Schlägt ein Wechsel fehl,
  antwortet der Turn mit dem geladenen Modell: nie warten, nie stumm.
- **Kein „Drift"-Gemecker mehr:** die Betriebsanzeige nennt das Modell, das läuft, statt es an
  einer gespeicherten Wahl zu messen — seit Modelle absichtlich wechseln, war das nur noch Lärm.
  Das grüne Schloss misst wieder ausschließlich, was es soll: läuft alles im Haus?
- **Ein festgefahrener Modellwechsel heilt sich selbst:** die Wechselsperre wartet höchstens zwei
  Minuten auf eine hängende Generierung und bricht dann ehrlich ab, ohne das geladene Modell
  anzutasten; ein dauerhaft hängender Wechsel wird im Gesundheitsstatus sichtbar.

### Stimme und Ehrlichkeit

- **Die ersten Millisekunden gehören wieder zum Satz.** Aus „Wie zieht eine Kuh die Hose an"
  wurde „Zieht eine Kuh eine Hose an", aus „Hose" zeitweise „Rose" — der Anlaut starb am
  Aufnahme-Start. Zwei Lücken, beide zu: die Anzeige sagt „ich höre zu" erst, wenn wirklich
  aufgenommen wird, und ein 500-ms-Pufferring fängt den Anfang, bevor der Encoder warm ist.
  Der Ring lebt nur, solange das Mikrofon offen ist.
- **Der Herkunfts-Chip liest jetzt die Wahrheit, die das Backend längst sendet:** eine online
  nachgeschlagene Antwort trug in der Oberfläche „lokal", weil zwei ehrliche Felder drei Wochen
  lang niemand las. Chip und gesprochener Vorspann erzählen wieder dieselbe Geschichte.
- **Die Cache-Telemetrie sieht in allen fünf Sprachen** — vorher erkannte sie ihren
  Herkunfts-Marker nur auf Deutsch (die älteste bekannte Grenze der Release-Notes, jetzt getilgt).
- **Speicherdruck wird sichtbar, bevor die Stimme erstickt:** der Mac misst frei/Kompressor/Swap
  ehrlich (als „kritisch" gilt erst wachsender Druck, nicht ein niedriger Messwert — macOS hält
  „frei" absichtlich klein), und die Oberfläche sagt es warm: „Speicher knapp — die Stimme kann
  gerade zäh werden." Kein automatisches Umschalten; sehen und entscheiden bleibt Sache des Menschen.
- **„Warmweiß" ist jetzt eine Lichtfarbe.** Der Befehl tat bisher schlicht nichts — die Kette
  konnte Farbtemperaturen längst transportieren, nur verstand niemand das Wort. Warmweiß, Neutralweiß
  und Kaltweiß (deutsch wie englisch) werden auf Kelvin übersetzt; ob eine Lampe sie kann,
  entscheidet weiterhin Home Assistant.

### Sprache

- **Die Nachschlage-Sätze klingen wie ein Mensch — und nicht immer gleich.** Ausweichen und
  Ergebnis-Vorspann kommen aus kleinen Pools (je vier Varianten, fünf Sprachen, idiomatisch statt
  übersetzt); Stufen-Quittungen und die Unbelegt-Kennzeichnung bleiben bewusst fest — eine
  Unterschrift soll sich gleich anhören. Fünf Kategorien, die für Spanisch, Französisch und
  Italienisch bisher mitten im Gespräch auf Englisch zurückfielen, sind jetzt echt fünfsprachig.
- **Offline ist eine vierte Stufe:** kein Nachschlagen, aber statt auszuweichen antwortet das
  lokale Modell aus eigenem Wissen — hörbar gekennzeichnet: „Ehrlich, dafür hab ich keinen
  Beleg — aber aus meinem eigenen Wissen: …".
- **Die Einstellungen sind neu geordnet** — sieben Gruppen, und die Online-Funktion hat erstmals
  eine eigene: „Online & Nachschlagen" mit den vier Stufen als beschriftete Karten. Vorher war
  die Stufe ausschließlich per Sprachbefehl schaltbar; wer sie suchte, fand nichts.

- **Der Grundstein der privaten Wissensbibliothek liegt bei** (`tools/knowledge-library/`):
  eigene Notizen, Rezepte und Texte lassen sich in einen rein lokalen Suchindex bauen — mit
  Hash-Kette von der Quelle bis zum Index, dem Scope-Modell „geteilt oder pro Person" von Anfang
  an, und der harten Regel, dass nichts davon je das Haus verlässt. Hoshi selbst benutzt ihn noch
  nicht; das ist bewusst der erste Stein, nicht das Haus.

### Zum Abschluss: ein Regentag in Japan und ein Flur, der lesen kann

- **Amayadori (雨宿り)** — das neunte Farbthema, der Abschluss-Wunsch dieser Version: *„Draußen
  fällt es weiter; hier drinnen ist es trocken und jemand hat Licht gelassen."* Nasses Zedernholz,
  Laternenlicht in Kaki-Persimone, und kaltes Regenblau nur an den Rändern — die Geborgenheit
  entsteht aus dem Temperatur-Unterschied. Zwei Signaturen: die **Traufe** (ein hauchfeiner
  Nieselschleier im Seitenraum, der sichtbar an der Kante der Lesespalte aufhört) und der
  **Pfützen-Orb** (die Sprech-Ringe lesen sich als Regentropfen auf Wasser). Entstanden aus zwei
  unabhängigen Entwürfen, die ohne Absprache dasselbe Konzept empfahlen.
- **Die Home-Seite ist jetzt ein Flur-Display.** Sie lief auf einem iPad im Flur und verschenkte
  dort Fläche und Wissen: ein Grid-Fehler erzeugte eine leere Phantom-Spalte, die Regenmenge kam
  an und wurde nie angezeigt, und die halbe Seite füllte eine veraltete Entwickler-Sektion. Jetzt:
  ein **Jetzt-Band** neben der Uhr (Wetterlage groß, Tagesspanne, „3 mm Regen heute" oder
  „trocken"), echte **Countdowns** statt Timer-Zählern, eine **Einkaufs-Karte** mit den nächsten
  Einträgen — und leere Karten verschwinden, statt „Nichts geplant" zu behaupten. Die
  Entwickler-Inhalte zogen als Diagnose-Sektion in die Aktivität; Schriftgrade und Touch-Ziele
  sind auf Blickabstand ausgelegt.

### Bekannte Grenzen dieser Version

- Die lokale „ja"-Einlösung hat **keinen eigenen Aus-Schalter** — sie hängt an den bestehenden
  Nachschlage-Flags; der Rückweg ist das Deploy-Rollback.
- **„Brauche ich heute Sonnencreme?" ist keine Wetterfrage mehr** — der Preis der neuen
  Wortgrenzen-Präzision. Die Formulierungs-Klasse ist benannt und wird nachgezogen.
- Die **romanischen Sätze sind nicht muttersprachlich gegengelesen**, die **Oberfläche kennt
  weiter nur Deutsch und Englisch** als Bediensprache.
- Das 12B braucht den mitgelieferten mlx-Patch; die Speicherdruck-Messung wird erst mit dem
  nächsten Neustart des Sprachmodell-Dienstes aktiv.

## 0.8.2 — Suisei ☄ (2026-07-25)

Neuer Codename: **Suisei** (彗星, der Komet). Nagareboshi — die Sternschnuppe — war der kurze helle
Streifen. Ein Komet hat eine Bahn und kommt wieder. Das passt zu dem, was diese Runde tut:
aus einem Aufblitzen wird etwas, das bleibt. *(Nagareboshi lebt als Farbthema weiter — und bekommt
in dieser Runde endlich die Uhrzeit, in die es gehört.)*

### Sprache

- **Hoshi spricht fünf Sprachen — überall, nicht nur an der Oberfläche.** Rund 770 Sätze im Backend
  wurden übersetzt: Smart-Home-Bestätigungen, die Ehrlichkeits-Sätze („das weiß ich nicht sicher"),
  Fehler- und Wartemeldungen, und die Wissens-Blöcke, die intern an das Sprachmodell gehen.
  Gerade Letztere waren der eigentliche Hebel: sie zogen die Antwort zurück ins Deutsche, obwohl
  alles andere schon englisch war. Dazu 119 Stellen im Frontend — die englische Oberfläche ist von
  rund einem Viertel Restdeutsch auf unter 5 % gefallen, die Einstellungen sind vollständig.
- **Zeit- und Datumsangaben folgen der Sprache** (`7:05 PM` statt `19:05`). Eine Nebenwirkung davon
  ist gleich mitbehoben: das „PM" hing an der großen Übersichts-Uhr in derselben Riesengröße wie die
  Ziffern. Jetzt steht der Tagesabschnitt als leise Fußnote daneben — ermittelt über die
  Sprach-Formatierung statt über eine AM/PM-Regel, damit es auch für Sprachen stimmt, die ihn
  voranstellen.
- **Eine einheitliche Regel, wenn eine Sprache fehlt.** Vorher bekam ein spanischer Nutzer je nach
  Programmpfad mal Englisch, mal Deutsch. Jetzt: alles außer Deutsch fällt auf Englisch zurück.
- **Nutzerdaten werden nie übersetzt** — Raumnamen aus dem Smart Home bleiben, wie sie sind. Das ist
  jetzt getestet (11 Ausgabewege × 4 Räume × 5 Sprachen), nicht nur vorgenommen.
- **Ehrlich, und das ist die wichtigste Zeile dieses Abschnitts: Hoshi *antwortet* in fünf Sprachen,
  aber er *versteht* noch nicht in fünf.** Übersetzt wurden die Antworten. Die Erkenner — die Stellen,
  die zuhören, ob jemand Licht schalten, einen Timer stellen oder online nachsehen lassen will — sind
  in großen Teilen weiterhin deutsch. Wer auf Spanisch stellt, bekommt spanische Antworten, muss die
  Befehle aber weiter auf Deutsch oder Englisch sagen. Hoshi sagt das selbst: im Sprach-Panel steht
  der Hinweis, und alles außer Deutsch trägt dort ein „Beta". Daran wird gearbeitet.
- **Ebenfalls ehrlich:** Spanisch, Französisch und Italienisch sind echte Übersetzungen und keine
  Platzhalter, aber sie sind **nicht muttersprachlich gegengelesen**. Für diese drei Sprachen gibt es
  außerdem keine Piper-Stimme — sie werden vom macOS-`say`-Sidecar gesprochen.
- **Übersetzt war nur die Antwort — jetzt auch das Verstehen.** Der peinlichste Befund dieser Runde:
  rund 770 Sätze lagen in fünf Sprachen vor, aber die *Erkenner* — die Stellen, die den gesagten Satz
  überhaupt einordnen — prüften weiter gegen deutsche Wortlisten. „How do I bake a cake?" verfehlte
  den vorgesehenen Weg, „play some music" und „turn off the radio" wurden nicht als Radio-Befehl
  erkannt, „note for the workshop" landete in keinem Briefkasten, „rate my day" wurde nicht als
  Tagesnote gespeichert. Gefunden hat das niemand beim Lesen des Codes, sondern der Hausherr in zehn
  Minuten Benutzen. Alle vier Stellen sprechen jetzt fünf Sprachen — mit einer bewussten
  Zurückhaltung: wo ein kurzes fremdes Wort im Deutschen etwas anderes heißt, wurde es weggelassen
  (spanisch nur `cómo` mit Akzent, nie die Präposition `como`; kein mehrdeutiges „prepare"). Ein
  Erkenner, der bei normaler Rede zuschnappt, ist schlimmer als einer, der eine Wendung nicht kennt.

### Farbe

- **Zwei neue Farbwelten, beide von Codex entworfen.** *Yoake* (夜明け, „Tagesanbruch") ist der
  Augenblick vor Sonnenaufgang: tiefer Indigo-Pflaumen-Raum, ein leiser Korall-Horizont — dunkel
  genug für lange Gespräche, ohne ein weiteres Schwarz zu sein. *Natsu no Hi* (夏の日, „Sommertag")
  ist das Gegenstück: helles, warmes Washi-Papier statt neutralem Weiß, Ramune-Blau als Akzent, sehr
  wenig Zinnober. Die Kontraste wurden gemessen statt behauptet. Beide sind **manuell wählbar und
  bleiben bewusst außerhalb der automatischen Tagesrotation** — sie sollen eine Entscheidung sein,
  keine Überraschung.
- **Nagareboshi bekommt die tiefste Nacht.** Bisher war die Sternschnuppe vom automatischen
  Sora-Tageswechsel ausgenommen, weil sie Hoshis Signatur-Theme war. Seit Suisei diese Rolle trägt,
  ist sie frei — und übernimmt das Fenster, in das sie gehört: **02:00–05:59**. Wer um drei Uhr
  morgens mit Hoshi redet, bekommt sie. Yoru endet dafür jetzt um 01:59.
- **Alle Themes bekommen eine Atmosphäre-Ebene:** sehr schwache Radialverläufe im Seitenraum, die dem
  jeweiligen Thema einen Raum geben. Bewusst nur dort — Karten, Statusfarben und Textflächen bleiben
  auf den bisherigen Tokens, damit Kontrast und Lesbarkeit unangetastet sind.

### Verstehen

- **Hoshi erkennt jetzt, wann jemand spielt.** Auf „Stell dir vor, eine Kuh — wie zieht sie ihre Hose
  an?" antwortete er bisher sachlich, korrigierte im nächsten Zug „Pfoten" zu „Beinen" und ließ den
  Faden fallen. Die Ursache war nicht das Gedächtnis, sondern das Register: die Frage wurde als
  Wissensfrage eingestuft und durch die volle Erdungs-Maschinerie geschickt, die genau dafür gebaut
  ist, vorsichtig zu sein. Jetzt gibt es einen eigenen Spiel-Modus. **Der ist absichtlich eng
  gebaut:** es öffnen ihn nur ausdrückliche Marker („stell dir vor", „was wäre wenn", „imagine",
  „what if") oder ein enges Absurditäts-Paar aus Tier und Menschen-Gegenstand — kein Sentiment, keine
  Heuristik. Eine echte Wissensfrage als Spiel misszuverstehen würde Ehrlichkeit kosten; ein
  verpasster Spaß kostet nur Charme. Über einer *Handlung* greift der Modus nie: Smart-Home-Befehle
  werden nicht umgebogen.
- **Der Chip „Wissen gedeckt" lügt nicht mehr.** Bei derselben Kuh-Frage stand er über einer frei
  erfundenen Antwort, weil die Prüfung nur „Textblock nicht leer" sah. Er kommt jetzt aus der
  tatsächlichen Deckung; ist der Block leer, steht er nicht da.
- **Englische Bitten um eine Online-Suche werden verstanden.** „Take a look online for a pizza
  recipe" landete bisher in einer Rückfrage, weil die Verbliste rein deutsch war. Jetzt gibt es
  englische Netz-Marker und Nachschau-Verben — die häufigen Wörter „look"/„check" allerdings nur
  zusammen mit einem Netz-Marker, sonst wäre jeder Blick in den Ofen eine Websuche.
- **Und die Rückfrage merkt sich, dass sie gefragt hat.** „Was genau soll ich nachschauen?" war eine
  Sackgasse: die Antwort darauf fiel ins Leere. Jetzt wird die Frage gemerkt, und das nachgereichte
  Thema löst sie ein. Mit Bremsen: nur für die unmittelbar nächste Nachricht, ein Abwinken („egal",
  „never mind") verwirft still, ein Befehl gewinnt gegen die offene Frage, und eine zweite themenlose
  Bitte merkt sich nichts mehr — keine Endlosschleife.

### Sicherheit & Betrieb

- **Das Sprecher-Erkennungs-Gate ist versiegelt — und die Erkennung bleibt aus.** Der Kalibrator
  konnte bisher Holdout-Ergebnisse auf einem Datensatz erzeugen, der noch gar nicht gate-fähig war;
  der einmalige Holdout wäre verbrannt worden, bevor er etwas beweisen kann. Jetzt verlangt der
  Echt-Lauf einen vorregistrierten Manifest-Hash und bricht vorher ab, und eine fehlende Truth- oder
  Kanal-Zelle endet ehrlich als „nicht ausgewertet" statt als Ergebnis. **Das ist Messanlage, kein
  Freischalten:** die Sprecher-Erkennung ist seit 0.8.0 abgeschaltet, weil das erste lokale Gate
  keinen tragfähigen Betriebspunkt gefunden hat, und sie bleibt es.
- **Das Deploy lieferte seit dem Versionswechsel still das alte Programm aus.** Der Jar-Name war fest
  auf `0.8.0` verdrahtet. Das fiel lange nicht auf, weil die Version nie stieg — als sie zweimal
  stieg, kopierte das Skript weiter den Stand vom 21. Juli auf den Server. Der Deploy meldete grün,
  die Gesundheitsprüfung kam mit 200, ein Test-Turn sprach sauber: alles richtig, nur eben vom alten
  Programm. Aufgefallen ist es an einem Zufall — der Start-Banner sagte nach der Umbenennung weiter
  „Nagareboshi". Zwei Riegel: der Jar-Name wird jetzt aus der Versionsdatei abgeleitet statt geraten,
  und ist irgendeine Quelldatei jünger als das gebaute Programm, bricht der Deploy ab. Ein passender
  Name beweist nicht, dass der richtige Build drinsteckt.
- **Die Modell-Erwartung folgt der Wirklichkeit statt einer Tabelle.** Wer bewusst auf ein anderes
  Sprachmodell wechselte, bekam danach dauerhaft eine Abweichungs-Warnung für einen Zustand, den er
  selbst gewollt hatte. Die Erwartung wird jetzt beim Deploy aus dem laufenden Modell gelesen und
  steht danach fest — ein gewollter Wechsel wird übernommen, ein *späterer* ungefragter (Absturz,
  halber Wechsel) wird weiterhin gemeldet. Schläft das Modell gerade, gilt die alte Tabelle als
  Notnagel; eine geratene Erwartung ist besser als gar keine.

- **Stimmen anlernen geht jetzt über drei Sitzungen an drei Tagen.** Die Auswertung der ersten
  Anlern-Runde zeigte, dass die beiden Profile im Haushalt einander ähnlicher waren als jede Person
  sich selbst — gemessen an der laufenden Anlage: Eigen-Werte 0,52 und 0,41 bei einer
  Kreuz-Ähnlichkeit von 0,71. Die Ursache lag nicht in den Stimmen, sondern in der Aufnahme: drei
  Sätze am Stück, ein Raum, ein Mikrofon, dieselben Sätze. Gelernt wurde das Wohnzimmer, nicht der
  Mensch. Der Anlern-Dialog führt jetzt durch drei getrennte Sitzungen mit je drei **verschiedenen**
  Sätzen (neun in fünf Sprachen) und legt sie an, statt sie zu ersetzen. Zwei Fallen wurden dabei
  entschärft, die den ganzen Ablauf unbrauchbar gemacht hätten: die zweite Sitzung hätte die erste
  überschrieben, und ein Abbruch in Sitzung drei hätte die beiden fertigen Tage mitgerissen.
- **Und diesmal lässt sich messen, statt hinterher zu raten.** Die Diagnose nennt für jede einzelne
  Aufnahme, wie gut sie zu den übrigen desselben Profils passt — eine verkorkste Aufnahme zeigt damit
  auf sich selbst, statt im Mittelwert zu verschwinden. Dazu die höchste Fremd-Ähnlichkeit je
  Profilpaar (genau die Zahl, an der der Befund hing), die Herkunft jeder Aufnahme (Sitzung, Gerät,
  Zeitpunkt) sowie Dauer und Pegel. Zu kurze oder praktisch stumme Aufnahmen werden abgelehnt, statt
  das Profil zu vergiften — aber bewusst feige: lässt sich die Aufnahme nicht auswerten, wird sie
  durchgelassen. Ein zweifelnder Prüfer darf niemanden blockieren.
- **Eine Frist, die nur zufällig stimmte.** Das kurze Gedächtnis für „soll ich kurz nachschauen?"
  maß seinen Ablauf gegen die eine Uhr, stempelte den Eintrag aber mit einer anderen. Im Betrieb war
  das dieselbe Uhr, deshalb war nie etwas kaputt — die Frist war nur nicht überprüfbar. Aufgefallen
  ist es, weil ein Test in genau der Sekunde dauerhaft rot wurde, in der die reale Zeit den in ihm
  festgeschriebenen Zeitpunkt überholte. Jetzt stempelt der Speicher selbst; die gleichartige Stelle
  für Orts-Rückfragen wurde mitgezogen.

### Bekannte Grenzen dieser Version

- **ES/FR/IT sind nicht muttersprachlich gegengelesen** und haben keine Piper-Stimme. Die neu
  hinzugekommenen Erkenner-Wendungen dieser drei Sprachen sind strukturell nachgebaut, nicht
  idiomatisch gegengeprüft.
- Die **Oberfläche lässt sich nur auf Deutsch oder Englisch stellen**, obwohl fünf Sprachpakete
  vorliegen: Spanisch, Französisch und Italienisch sind über „Hoshi spricht" erreichbar, stehen aber
  nicht als eigene Bedien-Sprache zur Wahl.
- Das **dichte Gemma-4-12B ist wählbar — und läuft nur dank eines eigenen Patches.** Seine
  Konfiguration deklariert die Bauart `gemma4_unified`, und dieses Architektur-Modul bringt **keine**
  veröffentlichte mlx-lm-Version mit, auch nicht die neueste. Es zeigte sich aber beim Nachmessen,
  dass der Textteil fast dem E4B entspricht — ein einziger Schalter unterscheidet sie (geteilte
  Key-/Value-Projektionen), und den beherrscht die vorhandene Implementierung längst. Der Patch
  (`sidecars/brain/mlx_patches/`) erbt deshalb den bestehenden Mantel und verwirft eine zusätzliche
  Multimodal-Kopf-Familie; er wird beim Einrichten des Sidecars automatisch eingesetzt, weil eine
  Neuinstallation ihn sonst löschen würde. **Was es kostet, gemessen an derselben Frage:** rund
  doppelt so lange Antwortzeiten (2,6 s / 1,4 s gegenüber 0,8 s / 0,7 s), 4,3 Sekunden Ladezeit,
  6,8 GB Speicher. Und weil der Sidecar wegen der 16-GB-Grenze das alte Modell *vor* dem Laden
  entlädt, kostet ein fehlgeschlagener Wechsel das laufende Sprachmodell, bis `bin/hoshi heal` es
  zurückholt.
- Die **Sprecher-Erkennung ist abgeschaltet**; Anlernen und Profile bleiben, das Erkennen nicht.
- Der **sanfte Neustart** (bis zu 20 Sekunden für laufende Gespräche) ist konfiguriert und plausibel,
  aber weiterhin **nicht an einem echten laufenden Gespräch bewiesen**.
- Im Frontend bleiben Reste: der Sprecher-Anlern-Flow, Mikrofon-/Audio-Fehlermeldungen und rund 33
  meist verschluckte Fehlerwürfe sind noch deutsch.

## 0.8.1-rc1 — Nagareboshi (Release Candidate)

Post-Submission-Aufräumrunde: integrierte Review-Pakete + Betriebs-Härtung + die erste Schicht
Mehrsprachigkeit. **Warum RC und nicht final:** der komplette lokale Sprach-Turn ist auf einer
Entwicklungsmaschine bewiesen (echtes WAV, ohne Cloud-Schlüssel), aber noch nicht auf dem
Produktiv-Host deployt und noch nicht am Lautsprecher gemessen. Zum Ausprobieren gedacht,
nicht als „fertig" ausgegeben.

- **Eskalations-Observability.** Ein „unavailable" trägt jetzt eine Ursachen-Klassifikation
  (timeout/missing_key/network/…) und der Web-Rand schreibt am finalen Ausgang eine
  Diagnosezeile — reine Observability, kein Verhaltens-Eingriff. (Codex P6, `bfd9f49`)
- **Nachschlag-Angebote einlösbar.** Der HonestyGate legt bei einem Angebot jetzt ein
  PendingLookup an (derselbe Vertrag wie der FactCoverage-Deflect); ein „ja" löst die
  Originalfrage ein, statt ins Leere zu laufen. (Codex P7, `b170e72`)
- **Wetter-Tagesbezug im Faktenvertrag.** Der Tagesbezug jeder Wetter-Zeile („heute"/„morgen")
  wandert in den «»-Vertrag, damit die Messwerte an den richtigen Tag gebunden bleiben.
  (Codex P8, `fd535bb`)

- **Der erste Start spricht lokal — oder sagt exakt, was fehlt.** Bisher verdrahtete das
  Deploy-Template die Sprachausgabe hart in die Cloud, und wer das Projekt frisch klonte, bekam
  je nach Startweg eine Engine, die gar nicht laufen konnte. Jetzt ist `say` der Default
  (macOS-Bordmittel, kein Schlüssel, kein Modell-Download), die Engine wird aus der eigenen
  Konfiguration gelesen, und ein unbekannter Wert bricht ab, statt still auf etwas anderes zu
  fallen. Ehrlich dazu: der Sidecar braucht einmalig `sidecars/say/bootstrap.sh` — und **jeder**
  Startweg nennt genau diesen Befehl, statt auf einen unmöglichen Start zu warten. Bewiesen mit
  einem echten Turn ohne Cloud-Schlüssel (123.946 Byte WAV, 2,7 s Audio). Piper bleibt optional
  (eigene Laufzeit, Modelle, GPL-Zustimmung).
- **Ehrliche Gesundheitsanzeige.** Die Statusanzeige prüfte immer dieselbe Sprachausgabe-Engine,
  egal welche tatsächlich aktiv war — „alles lokal" konnte grün sein, während die wirklich
  sprechende Komponente unerreichbar war. Jetzt wird die gewählte Engine geprüft.
- **Einkaufsliste behält ihre Reihenfolge.** Zwei schnell hintereinander genannte Dinge landeten
  in derselben Millisekunde und konnten zwischen zwei Abrufen die Reihenfolge tauschen.
- **Sanfterer Neustart.** Beim Deploy bekommen laufende Gespräche jetzt bis zu 20 Sekunden Zeit,
  sich zu beenden, statt sofort gekappt zu werden; danach endet der Prozess. *Konfiguriert und
  plausibel, aber noch nicht durch einen Test an einem echten laufenden Turn bewiesen.*
- **Never-Speak-Riegel auf allen Wegen.** Die Regel „sprich niemals ein Geheimnis" (Tokens,
  API-Keys, LAN-IPs, UUIDs) hing nur an einem der beiden Wege, auf denen die Sprachausgabe
  gebaut wird — die lokalen Engines sprachen den Rohtext. Jetzt gilt sie überall, und der
  Schalter steht standardmäßig an.
- **Eine Bauwahrheit für die Sprachausgabe.** Sanitize, Verbalize und Lautstärke-Normalisierung
  hängen jetzt an genau einer Stelle. Vorher gingen je nach Weg einzelne Stufen still verloren —
  unter anderem die Lautstärke-Normalisierung nach einem Stimmen-/Engine-Wechsel.
- **Zahlen werden gesprochen, nicht buchstabiert.** Uhrzeiten und Dezimalzahlen bekommen vor der
  Synthese eine sprechbare Form (ICU). Standardmäßig aus, bis es einmal angehört wurde.
- **Mehrsprachigkeit, erste Schicht.** Die Turn-Sprache wird jetzt vom Compiler bis in den
  Wetter-Block erzwungen; Wetterlagen, Wochentage und der komplette Wetter-Rahmen liegen in fünf
  Sprachen vor. Im Frontend sind 41 deutsche Reste im englischen Modus verschwunden.
  **Ehrlich:** große Teile der Backend-Antworten (u.a. Smart-Home-Bestätigungen) sind weiterhin
  deutsch — das ist der Kern von 0.8.2.

## 0.8.0 — Nagareboshi (eingereicht 2026-07-22)

- **Hexagon-Neubau.** 0.8 ist ein kompletter Neubau auf hexagonaler Architektur
  (Ports & Adapters): ein schlankes Kotlin/Spring-Backend orchestriert
  austauschbare Sprachmodell-, Spracherkennungs- und Sprachausgabe-Engines
  ausschließlich über definierte Schnittstellen — kein direkter Durchgriff der
  Engines aufeinander.
- **Voice-Pipeline.** Der komplette Sprach-Turn (Zuhören → Verstehen →
  Antworten → Sprechen) läuft jetzt durchgängig durch dieselbe Pipeline wie
  der Text-Chat. Der Sprachkanal über das Satelliten-Gerät besitzt dieselben
  flag-gesteuerten Nähte für Persona, Sprecherkontext und Gedächtnis wie der
  Chat. Die Sprechererkennung ist nach einem fehlgeschlagenen lokalen
  Safety-Gate derzeit abgeschaltet; der restliche Sprachpfad bleibt nutzbar.
- **Persona & Mitgift.** Hoshi bekommt eine eigene, dokumentierte
  Basis-Persönlichkeit samt Trainingsbeispielen (warmherzig / faktenbasiert /
  ehrliches Zurückhalten), geprüft darauf, dass keine privaten Daten
  hineinsickern. Die Persona ist eine echte Einstellung statt fest verdrahtet.
- **Tool-Fastpaths.** Häufige Befehle — Licht in einem bestimmten Raum
  schalten, Timer-Status abfragen, "schau bitte online nach" — laufen über
  deterministische, sprachmodell-freie Pfade statt jedes Mal durchs LLM zu
  müssen: schneller, günstiger, vorhersagbarer.
- **Grounding & Verbatim-Verträge.** Antworten mit Fakten (Wetter, online
  nachgeschlagene Informationen, wiederholte Antworten aus dem Zwischenspeicher)
  werden wörtlich aus der Quelle übernommen statt vom Sprachmodell frei
  nacherzählt — verhindert, dass sich Orte, Zahlen oder Daten beim
  Umformulieren verändern.
- **Nachtmodus.** Einstellbarer Nachtmodus mit gedämpfter LED-Anzeige am
  Satelliten-Gerät.
- **Räume-Reiter.** Ein eigener Bereich in der Oberfläche zeigt den
  tatsächlichen Zustand pro Raum (Licht, Temperatur, Geräte) — ehrlich mit
  sichtbaren Lücken dort, wo die Smart-Home-Anbindung noch fehlt, statt etwas
  vorzutäuschen.
- **Sidecars im Repository.** Brain, STT, Speaker-ID, Knowledge-Bridge sowie
  die lokalen TTS-Optionen `say` und Piper liegen im Hauptrepository — mit
  reproduzierbaren Startpfaden und gepinnten Abhängigkeiten. Modelle und
  Wikipedia-Daten bleiben externe Artefakte; der deaktivierte Legacy-Voxtral-
  Pfad ist noch nicht portiert.
- **Öffnung für KI-Mitarbeit.** Neue Projektdokumentation, damit auch
  KI-Assistenten sinnvoll am Projekt mitarbeiten können: eine geprüfte
  Kommandoreferenz, eine Einstiegsanleitung und Mitwirkungsregeln.

- **Räume-Editor mit Schreibpfad.** Der Zuhause-Reiter zeigt nicht nur Räume —
  Geräte lassen sich einer HA-Area ZUWEISEN (offizielle HA-WebSocket-API,
  Audit-Zeile mit alt→neu je Write, Existenzcheck vor dem Schreiben, kein
  optimistisches UI). Flag-gated, default aus.
- **Sprecher-Erkennung messbar gemacht.** Score-Aggregation je Profil wählbar
  (best-sample | top-two-mean | centroid gegen das Mittel-Embedding), Offline-A/B-Runner
  (FAR/FRR-Proxy, kanalgetrennte Confusion-Matrix) und flag-gated Capture-Tee
  für kanal-echte Proben. Das erste lokale Gate hat keinen tragfähigen
  Betriebspunkt gefunden; Recognition bleibt OFF. Die Boot-Zeile beweist den
  konfigurierten Modus, nicht dessen Qualität.
- **Explizite Online-Recherche.** „Recherchiere online …" ruft ein
  konfigurierbares Recherche-Modell (gpt-5.6-Familie, katalog-verifizierte
  Preise); der Schnell-Lookup bleibt beim Nano-Default, der Tages-Kosten-Cap
  gilt für beide, die Antwort trägt ihr echtes Modell-Label.
- **Sprecherkontext bis zum Prompt.** Bei aktivierter und sicherer Erkennung
  erreicht der Sprecher-Name den System-Prompt auch im Text-Chat
  (FE-Durchreichung + Server-Fallback aus enrollten Profilen; Gäste bleiben
  anonym). Der Pfad ist implementiert, aber zusammen mit Recognition derzeit
  bewusst nicht produktiv aktiv.
- **Zuhause wird die Bühne.** Übersicht ist der erste Reiter, im Home-Screen
  wohnt ein Voice-Orb (geteilte Gesprächs-Session mit dem Chat-Reiter; alle
  Animationen nur an echten Signalen: Pegel, Pipeline-Stufen, TTS-Wiedergabe).
- **Sora-Modus 空.** Das Theme folgt auf Wunsch dem Tag: Asa am Morgen, Aoi am
  Tag, Kasumi am Abend, Yoru in der Nacht — Geräte-Uhr, ein Timer pro Fenster.
