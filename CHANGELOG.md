# Changelog

Alle nennenswerten Änderungen an Hoshi. Format lose an
[Keep a Changelog](https://keepachangelog.com/) angelehnt — dieses Projekt hat
noch keine erste stabile Version, Einträge sind daher grob nach Thema statt
nach Release sortiert.

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
