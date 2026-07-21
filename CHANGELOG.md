# Changelog

Alle nennenswerten Änderungen an Hoshi. Format lose an
[Keep a Changelog](https://keepachangelog.com/) angelehnt — dieses Projekt hat
noch keine erste stabile Version, Einträge sind daher grob nach Thema statt
nach Release sortiert.

## 0.8.0 — Nagareboshi (unreleased)

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
