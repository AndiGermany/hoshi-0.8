# Hoshi 星 — Deutsch

**[English](README.md)** · **Deutsch (diese Seite)**

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero-dark.svg">
  <img src="docs/assets/hero-light.svg" alt="Hoshi 星 — ad astra per aspera" width="100%">
</picture>

> Ein privater, deutschsprachig gewachsener, **lokal-first** Voice-Assistent,
> dem man vertrauen kann. Läuft auf einem Apple-Silicon-Mac mit 16 GB. Keine
> Cloud-Pflicht, keine projektseitige Telemetrie — deine Stimme bleibt bei dir.

**Status:** 0.9.x — aktiv in Entwicklung auf dem Weg zu 1.0. Welche Version dieser
Baum genau trägt, sagt die `version=`-Zeile in [`gradle.properties`](gradle.properties)
(und das Banner von `bin/hoshi help`); den ehrlichen Stand jeder Version, offene
Kanten eingeschlossen, hält das [`CHANGELOG.md`](CHANGELOG.md) fest.

## Was Hoshi ist

Hoshi ist ein Voice-Assistent für ein einzelnes Zuhause. Seine Grundregel lautet:
**Alles, was zu Hause passieren kann, passiert zu Hause.** Wake-Word auf dem
Satelliten, Spracherkennung, Sprachmodell, Wissenssuche und Stimme laufen lokal.
Das Internet ist eine ausdrückliche, einstellbare Ausnahme — nie Voraussetzung.

- Ein kompletter lokaler Sprach-Turn führt vom ESP32-Satelliten über Whisper und
  Gemma 4 bis zur Sprachausgabe; warm kommt der erste Ton nach ungefähr drei Sekunden.
- Fragen suchen zuerst in einer lokalen Wikipedia. Reicht sie nicht, sagt Hoshi
  das und kann vor einer Onlinesuche um Erlaubnis fragen. Danach bleibt hörbar,
  ob die Antwort aus dem Haus oder aus dem Netz kam.
- Timer, Wecker, Licht, Farbtemperaturen und jetzt auch der Saugroboter (Start,
  zurück zur Basis, ehrlicher Status statt geisterhaftem „zuletzt gesehen“)
  laufen über deterministische Fastpaths ohne LLM-Denkpause.
- Die Fluransicht zeigt eine Uhr (nachts mit lokal berechneter Mondphase 🌙),
  Wetter, ein mehrquelliges Nachrichten-Lagebild (mit Quellenangabe, ohne
  Brain-Aufrufe dahinter), echte Countdowns und die Einkaufsliste. Kacheln
  liegen auf einem freien Raster, lassen sich per +/− in der Größe ändern und
  einzeln in den Einstellungen ein- oder ausschalten. Leere Karten
  verschwinden, statt erfundene Zustände zu zeigen.
- Oberfläche, Antworten und Kommandos gibt es in fünf Sprachen. Sechzehn
  lebende Szenen-Themes, gruppiert nach Tageslage, dazu eine
  Vollbild-Galerie zum Aussuchen statt des alten schmalen Auswahl-Drawers.
- Das Aktivitätstagebuch speichert Messwerte wie Kategorie und Latenz, niemals
  Gesprächsinhalte.

## Warum Hoshi anders arbeitet

**Ehrlichkeit ist Architektur.** Unbekanntes wird nicht mit einer plausibel
klingenden Erfindung gefüllt. Die Oberfläche zeigt „—“ statt ausgedachter Zahlen;
ein Cache wird als Cache benannt, eine Onlinequelle als Onlinequelle. Ein
Vollzugs-Wächter (der „Kagami“-Spiegel) verhindert, dass eine Antwort einen
Schaltvorgang behauptet, der nie stattfand; der erste Live-Replay lief grün
(13 Fälle, 14 Turns, null falsche Vollzugsbehauptungen, null Beweislücken),
und der Satellit hat dasselbe am echten Gerät bewiesen — „Küche schaltet
Küche“, Licht an und wieder aus. Der Leitsatz des Projekts lautet
**grün ≠ lebt**: Ein grüner Test beweist noch kein lebendes Feature, deshalb
werden wichtige Wege am echten Stack gemessen.

**Vertrauen liegt im Code.** Schreibende Aktionen laufen durch einen
default-deny Capability-Kernel. Biometrische Stimmprofile verlassen das Gerät
nicht, unbekannte Stimmen werden nie automatisch angelernt, und die private
Wissensbibliothek darf konstruktiv nicht nach außen senden.

**Die 16-GB-Wand ist Teil des Designs.** Es lebt immer nur ein Brain im Speicher.
Modelle, Stimmen und Retrieval-Varianten werden offline gegeneinander gemessen,
bevor ein Mensch sie freigibt.

## Ehrliche Grenzen

- Die Sprechererkennung ist abgeschaltet. Anlernen und Profile existieren; das
  Erkennen bleibt aus, bis ein versiegelter Holdout eine sichere Trennung beweist.
- Spanisch, Französisch und Italienisch sind echte Übersetzungen, aber noch nicht
  muttersprachlich gegengelesen und haben keine eigene Piper-Stimme.
- Der sanfte Neustart ist konfiguriert, aber noch nicht gegen einen real laufenden
  Turn bewiesen.
- Das große 12B-Brain benötigt den mitgelieferten MLX-Architekturpatch.
- Hoshi ist bislang auf genau einer Maschine gehärtet. Die Installation auf
  fremden Macs wird gerade zu einem reproduzierbaren Weg ausgebaut.
- Ein paar Dinge bleiben bewusst auf eine spätere Version verschoben: die
  amtliche Warnspur (NINA) und Nachrichten-Personalisierung, das
  Kalender-Widget (wartet auf eine Home-Assistant-Kalender-Integration) und
  die Satelliten-Seite echter Rückfragen (Server-Teil steht, das Flag bleibt
  aus, bis die Firmware-Hälfte existiert).

## Architektur und Einstieg

Ein dünnes Kotlin-/Spring-WebFlux-Backend orchestriert austauschbare Adapter und
lokale Python-/MLX-Sidecars. Die reine Domäne kennt weder Spring noch Infrastruktur;
ArchUnit-Tests bewachen diese Grenze. Der Satellit spricht über einen
authentifizierten `/ws/audio`-Vertrag mit dem Backend.

```bash
./gradlew build
cd frontend && npm install && npm run build
bin/hoshi run
bin/hoshi doctor
```

Das ist noch kein „Ein-Klick-Produkt“: Modelle und die Wikipedia-Datenbank sind
externe Artefakte, optionale Pfade bleiben bewusst ausgeschaltet. Die vollständige
Einrichtung steht in [`SETUP.md`](SETUP.md), die ausführliche technische und
lizenzielle Wahrheit im [englischen Haupt-README](README.md).

## Gebaut mit Codex, GPT-5.6 und CollabOS

Hoshi entstand vor der Build Week. Seitdem arbeitet **Codex mit GPT-5.6** sowohl
als bauende Instanz als auch als adversarialer Gegenblick: unter anderem an der
lokalen Wissenskette, den Sicherheitswerkzeugen der Sprechererkennung, dem
optionalen Piper-Sidecar, Farbwelten und der privaten Wissensbibliothek. Eine
getrennte Orchestrator-Instanz prüft, integriert und misst Lieferungen unabhängig
nach. Produktrichtung sowie jedes Privatsphäre-, Deploy- und Produktions-Gate
bleiben beim Menschen.

Die Agenten koordinieren sich über **CollabOS**: ein kleines, nachlesbares,
dateibasiertes Protokoll — ein Verzeichnis mit Briefen. Jede Instanz liest ihren
Posteingang, schreibt in ihren Ausgang, und alles landet in der Versionsgeschichte.
Kein zentraler Dienst, kein Daemon; Dateien, die man später noch lesen kann.
Einschließlich der Fehler.

## Das Projekt um das Projekt

| Repository | Aufgabe |
|---|---|
| **hoshi-0.8** *(hier)* | Assistent, Oberfläche, Sidecars und Wire-Verträge |
| [**hoshi-satellite**](https://github.com/AndiGermany/hoshi-satellite) | ESPHome-Firmware, LED-Ring und lokales Wake-Word |
| [**collab-os**](https://github.com/AndiGermany/collab-os) | Methode, Messdisziplin und Making-of — gescheiterte Ideen eingeschlossen |

Mitmachen: [`CONTRIBUTING.md`](CONTRIBUTING.md) · Lizenz: [Apache-2.0](LICENSE)
für Hoshis eigenen Code. Modelle, Stimmen, Daten und optionale Runtimes behalten
ihre jeweiligen Lizenzen. Die vollständige Danksagung an die Projekte und Menschen,
auf deren Arbeit Hoshi steht, bleibt im Abschnitt
[„Danke“ des Haupt-READMEs](README.md#danke--auf-wessen-schultern-hoshi-steht).

---

*Ad astra per aspera — Hoshi (星), der Stern, der bleibt. Jede Mitwirkung ist eine Sternschnuppe über seinem Himmel.*
