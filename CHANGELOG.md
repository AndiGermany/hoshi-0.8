# Changelog

Alle nennenswerten Änderungen an Hoshi. Format lose an
[Keep a Changelog](https://keepachangelog.com/) angelehnt — dieses Projekt hat
noch keine erste stabile Version, Einträge sind daher grob nach Thema statt
nach Release sortiert.

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
- Das **dichte Gemma-4-12B steht zur Wahl, lädt aber noch nicht**: die eingefrorene mlx-lm-Version
  kennt seine Architektur nicht (`gemma4_unified`). Weil der Sidecar wegen der 16-GB-Grenze das alte
  Modell *vor* dem Laden entlädt, kostet ein Versuch das laufende Sprachmodell, bis
  `bin/hoshi heal` es zurückholt — beides steht so im Auswahl-Label. Der Eintrag bleibt sichtbar,
  weil er nach dem geplanten mlx-lm-Upgrade sofort trägt.
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
