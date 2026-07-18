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
  der Text-Chat. Der Sprachkanal über das Satelliten-Gerät bekommt dieselbe
  Persona-, Sprechererkennungs- und Gedächtnis-Behandlung wie der Chat —
  vorher war er an mehreren Stellen davon abgeschnitten.
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
- **Sidecars im Repository.** Die Python-Sidecars (Sprachmodell,
  Spracherkennung/STT, Sprechererkennung) liegen jetzt vollständig im
  Hauptrepository statt in einem getrennten Altprojekt — mit reproduzierbarem
  Setup (Bootstrap-Skript, gepinnte Abhängigkeiten).
- **Öffnung für KI-Mitarbeit.** Neue Projektdokumentation, damit auch
  KI-Assistenten sinnvoll am Projekt mitarbeiten können: eine geprüfte
  Kommandoreferenz, eine Einstiegsanleitung und Mitwirkungsregeln.

- **Räume-Editor mit Schreibpfad.** Der Zuhause-Reiter zeigt nicht nur Räume —
  Geräte lassen sich einer HA-Area ZUWEISEN (offizielle HA-WebSocket-API,
  Audit-Zeile mit alt→neu je Write, Existenzcheck vor dem Schreiben, kein
  optimistisches UI). Flag-gated, default aus.
- **Sprecher-Erkennung messbar gemacht.** Score-Aggregation je Profil wählbar
  (best-sample | centroid gegen das Mittel-Embedding), Offline-A/B-Runner
  (FAR/FRR-Proxy, kanalgetrennte Confusion-Matrix) und flag-gated Capture-Tee
  für kanal-echte Proben — Betriebspunkt-Wechsel nur nach Messung, die
  Boot-Zeile beweist den aktiven Modus.
- **Explizite Online-Recherche.** „Recherchiere online …" ruft ein
  konfigurierbares Recherche-Modell (gpt-5.6-Familie, katalog-verifizierte
  Preise); der Schnell-Lookup bleibt beim Nano-Default, der Tages-Kosten-Cap
  gilt für beide, die Antwort trägt ihr echtes Modell-Label.
- **Hoshi kennt die Person.** Der erkannte Sprecher-Name erreicht den
  System-Prompt auch im Text-Chat (FE-Durchreichung + Server-Fallback aus den
  enrollten Profilen; Gäste bleiben anonym).
- **Zuhause wird die Bühne.** Übersicht ist der erste Reiter, im Home-Screen
  wohnt ein Voice-Orb (geteilte Gesprächs-Session mit dem Chat-Reiter; alle
  Animationen nur an echten Signalen: Pegel, Pipeline-Stufen, TTS-Wiedergabe).
- **Sora-Modus 空.** Das Theme folgt auf Wunsch dem Tag: Asa am Morgen, Aoi am
  Tag, Kasumi am Abend, Yoru in der Nacht — Geräte-Uhr, ein Timer pro Fenster.

### 2026-07-20/21 (Build-Week-Finale)

- **Echte Web-Suche statt vorgelesener URLs.** Die Online-Recherche liefert
  jetzt echte Suchergebnisse mit einem Quellen-Icon in der Antwort — Hoshi
  liest keine rohen URLs mehr laut vor.
- **Vier TTS-Engines.** Neben den Cloud-Stimmen laufen `say` und `piper` jetzt
  als lokale Sidecars im Repository (inkl. Lizenz-Hinweisen); die Stimme folgt
  automatisch der erkannten Sprache.
- **Sprachpakete DE/EN/ES/FR/IT.** Antworten, Systemprompt und Formatierung
  sind jetzt für fünf Sprachen ausgelegt statt nur Deutsch/Englisch.
- **Laufzeit-Settings für Lookup/TTS/Brain.** Modell- und Engine-Wahl lassen
  sich zur Laufzeit in der Oberfläche umschalten, ohne Neustart.
- **Übersicht mit Alles-lokal-Schloss.** Ein grünes Schloss-Symbol in der
  Übersicht zeigt ehrlich an, wenn gerade wirklich alles lokal läuft (kein
  Cloud-Fallback aktiv).
- **Timer klingelt am Ursprungs-Satelliten.** Die Serverhälfte des Weckers
  merkt sich, an welchem Satelliten-Gerät ein Timer gestellt wurde, und lässt
  ihn dort klingeln statt überall gleichzeitig.
- **Sprecher-Erkennung bewusst wieder OFF.** Nach einem realen Cross-Bind-Fund
  (ein Sprecher wurde fälschlich einem falschen Profil zugeordnet) bleibt
  Speaker-Recognition standardmäßig aus, bis die Sicherheitskette
  geschlossen ist — ehrlicher Rückschritt statt stillem Risiko.
