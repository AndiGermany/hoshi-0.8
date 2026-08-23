# RESULT — Rahmen-Transparenz · Content-Fit · Maximieren (23.08.2026)

Order: `vault/tracks/ORDER-widget-inhalt-maximieren-2026-08-23.md` (inkl. Nachtrag
„Play-Knopf im kleinen Sauger"). Pod: Opus 5, eigener Worktree, drei Commits.

| | |
|---|---|
| Worktree | `/Users/andi/IdeaProjects/Hoshi_0.8/.claude/worktrees/agent-a8cb47978c63ba91c` |
| Branch | `worktree-agent-a8cb47978c63ba91c` |
| Commits | `0ee72910` (Scheibe 1) · `8790aeb5` (Scheibe 2) · `3f963f84` (Scheibe 3) |
| FE-Suite | 100 Dateien, **1978 Tests grün** (vorher 99/1958) |
| Beweise | Chrome (`schnitt.mjs`, `maximieren.mjs`, `rahmen.mjs`) **und** Firefox (`firefox.mjs`, Schritte 6+7) |

---

## Scheibe 1 — Der Rahmen ist eine Stufe transparenter

**Token-Wert: `--hairline-mix: 68%`** (`frontend/src/index.css`, `:root`), gelesen
von genau EINER Regel: `.tile` — dem Rahmen jedes Widgets (Übersicht wie Zuhause,
`.idle__tile` erbt ihn). Die deckende `border`-Zeile bleibt als Rückfall für
Browser ohne `color-mix` stehen. Geriegelt von `test/surfacemix.test.ts`
(genau EINE Deklaration, Prozentwert, kein Theme biegt ihn um).

**Gemessen, nicht geschätzt** — neue Sonde `tools/theme-contrast/rahmen.mjs`:
eine echte `.tile` an bekannten Koordinaten über der echten Szene, drei
Bildpunkte auf der GERADEN Oberkante (die Ecken sind gerundet und antialiast).
Gemeldet wird der WCAG-Kontrast der Linie gegen ihre beiden Seiten.
`flaechen.mjs` konnte das nicht beantworten: es spart die Kante ausdrücklich mit
3 px Einzug aus, weil auf ihr nie eine Glyphe steht. Genau darum gilt hier auch
der 4,5:1-Text-Boden NICHT — ein Rahmen ist Dekoration. Riegel: **1,10:1**
(„liest sich noch als Kachel").

| Szene (Auswahl) | deckend | 68 % |
|---|---:|---:|
| ukiyo (stärkste Linie) | 4,37:1 | 2,57:1 |
| yoake | 3,64:1 | 2,18:1 |
| fuyubare (hell) | 1,61:1 | 1,37:1 |
| yoru (dunkel) | 1,65:1 | 1,34:1 |
| **aoi (schwächste)** | **1,19:1** | **1,11:1** |

Alle 17 Szenen über dem Riegel. Frames Fuyubare und Yoru mit eigenen Augen
angesehen: die Kante ist weicher, die Kachel bleibt eine Kachel.

**Nicht angefasst:** Linien INNERHALB einer Kachel (Trennstriche, Pillen,
Faltungen) und die Möbel des Hauses (Nav-Insel, Fußleiste, Compose-Leiste).
Andis Satz sagt „die Rahmen um die **Widgets**".

---

## Scheibe 2 — Content-Fit: nichts wird mehr abgeschnitten

### Die Sonde: `tools/zuhause-probe/schnitt.mjs` (neu)

`flaeche.mjs` meldet je Kachel EINE Zahl (`scrollHeight − clientHeight`) — ein
Alarm, keine Diagnose. `schnitt.mjs` misst **jedes Text-Blatt einzeln** gegen die
Polster-Box der Kachel und unterscheidet vier Arten:

- **▼ unten** — das Feld liegt unter der Kante der Kachel und ist WEG.
- **⤓ Käfig** — dasselbe, aber eine Liste mit eigenem `overflow-y: auto` liegt
  dazwischen: eine Fingerbreite entfernt, und die Kachel sagt mit „+N weitere",
  dass es da ist. Die bestellte Bauart, kein Schnitt.
- **… Ellipse** — waagerecht gekürzt.
- **✂ Klemme** — `line-clamp`, meist Absicht.

Dazu `SCHNITT_BILANZ=1`: die Höhe jeder Zeile der Kachel. Ohne sie wäre jede
Container-Query-Schwelle eine geratene Zahl.

**Zwei Fallen stellt sie sich jetzt selbst** — beide teuer bezahlt:
1. Die geprüfte Kachel wird auf Seite 1 / Zelle (0,0) **genagelt**. Ohne das
   schrieb die App eigene `placements` zurück, die Kachel landete auf Seite 3 —
   eine inaktive Seite ist `visibility: hidden` mit voller Geometrie, also
   plausible Maße und **kein einziges Feld**. Ein stiller Nullbefund.
2. **Belegte Ports brechen ab.** Auf 8798 lief der Probe-Server eines
   PARALLELEN Pods aus einem anderen Worktree; `spawn` mit `stdio:'ignore'`
   schluckt `EADDRINUSE`, und die Sonde maß klaglos die FREMDE App weiter — eine
   vollständige Vorher/Nachher-Tabelle, in der sich kein Wert geändert hatte.

`serve-xl.mjs` trägt jetzt die volle Sauger-Familie (vorher EINE nackte
Entität — ein Content-Fit-Audit hätte die leere Kachel gemessen und „passt"
gemeldet).

### Befund: 58 Fälle (8 Widgets × alle Stufen × 1366×1024 + 834×1112)

|  | vorher | nachher |
|---|---:|---:|
| Fälle mit ▼ abgeschnittenem Feld | **27** | **0** |
| Fälle mit Kachel-Überlauf ↕ | **20** | **0** |
| Fälle mit … Ellipse | 3 | 2 |
| Fälle mit ⤓ Scroll-Käfig | — | 17 |

Die 17 ⤓ sind kein Rückschritt, sondern dieselben Zeilen, die vorher ▼ waren:
jetzt in einer scrollenden Liste, unter einer sichtbaren „+N weitere"-Zeile.

#### Widget × Stufe (nur Fälle mit Befund; „▼-Felder / Kachel-Überlauf")

| Fenster | Widget | Stufe | vorher | nachher |
|---|---|---|---|---|
| 1366×1024 | climate | M | 8 / 0 px | 0 / 0 px |
| 1366×1024 | climate | L | 1 / 0 px | 0 / 0 px |
| 1366×1024 | einkauf | M | 3 / 79 px | 0 / 0 px |
| 1366×1024 | einkauf | L | 3 / 64 px | 0 / 0 px |
| 1366×1024 | einkauf | XL | 5 / 80 px | 0 / 0 px |
| 1366×1024 | laeuft | M | 1 / 42 px | 0 / 0 px |
| 1366×1024 | news | S | 0 / 9 px | 0 / 0 px |
| 1366×1024 | news | M | 3 / 96 px | 0 / 0 px |
| 1366×1024 | news | L | 25 / 0 px | 0 / 0 px |
| 1366×1024 | news | XL | 22 / 0 px | 0 / 0 px |
| 1366×1024 | vacuum | M | 1 / 28 px | 0 / 0 px |
| 1366×1024 | vacuum | L | 3 / 126 px | 0 / 0 px |
| 1366×1024 | vacuum | XL | 1 / 52 px | 0 / 0 px |
| 1366×1024 | wetter | M | 0 / 0 px (2× Ellipse) | 0 / 0 px |
| 834×1112 | climate | M | 10 / 0 px | 0 / 0 px |
| 834×1112 | climate | L | 2 / 0 px | 0 / 0 px |
| 834×1112 | einkauf | M | 4 / 94 px | 0 / 0 px |
| 834×1112 | einkauf | L | 4 / 95 px | 0 / 0 px |
| 834×1112 | einkauf | XL | 8 / 117 px | 0 / 0 px |
| 834×1112 | laeuft | M | 2 / 57 px | 0 / 0 px |
| 834×1112 | laeuft | L | 1 / 20 px | 0 / 0 px |
| 834×1112 | news | S | 1 / 48 px | 0 / 0 px |
| 834×1112 | news | M | 4 / 111 px | 0 / 0 px |
| 834×1112 | news | L | 27 / 0 px | 0 / 0 px |
| 834×1112 | news | XL | 22 / 0 px | 0 / 0 px |
| 834×1112 | vacuum | M | 1 / 43 px | 0 / 0 px |
| 834×1112 | vacuum | L | 4 / 157 px | 0 / 0 px |
| 834×1112 | vacuum | XL | 2 / 74 px | 0 / 0 px |
| 834×1112 | wetter | XL | 7 / 2 px | 0 / 0 px |

Die beiden verbleibenden Ellipsen sind **eine alte, dokumentierte Entscheidung**:
„mäßiger Schneefall" in der Mehrtage-Spalte wird gekürzt, der volle Text steht im
`title` (CSS-Kommentar bei `.idle__outlookcond`). In der maximierten Ansicht
steht er jetzt ausgeschrieben.

### Der Fix ist eine Regel, keine Liste von Ausnahmen

Jede Kachel hat denselben Bau: **Kopf · Liste · Schlusszeile**, die sagt, was
fehlt („+11 weitere", „Stand 10:32"). Starr war bisher die Liste — hinausgedrückt
wurde die Zeile, die den Fehlbestand MELDET. Die schlimmste mögliche Reihenfolge.
Jetzt trägt die Liste die Enge (`flex: 1 1 auto`, `min-height: 0`, eigener
Scroll-Käfig wie das Lagebild seit S1), die Schlusszeilen stehen fest.

Dazu fünf gemessene Einzelentscheidungen:

- **Flache Kachel** (`@container kachel (max-height: 180px)`; gemessene
  Innenhöhen: 108–123 px bei einer Rasterzeile gegen 258–289 px bei zweien —
  die Schwelle liegt mit ~57 px Abstand dazwischen): Nachrichten zeigen EINE
  Meldung, Schlagzeile 2 Zeilen; **schmal UND flach** fällt die Quellenzeile als
  GANZES Feld weg.
- **Sauger flach**: die Rückmeldezeile wird `sr-only` (`aria-live` spricht
  weiter), die Knopfzeile legt sich in die freie untere rechte Ecke — sie kostet
  damit 0 Zeilen statt 50 px, „Stand" bleibt sichtbar, 44-px-Ziel bleibt.
- **Wetter M**: die Fuge zwischen Lage und Fakten war das letzte Fenster-Maß
  (`2.6vw` ⇒ **35 px** auf 553 px Inhalt). `3cqw` ⇒ 17 px. Beide Kürzungen weg.
- **Wetter XL**: der Stundenverlauf ist das dehnbare Glied, die Mehrtage-Zeile
  steht fest (vorher 2 px Schnitt in JEDER der sieben Spalten).
- **Sauger S bekommt den Play-Knopf** (Nachtrag): rechts im Kopf, gezeichnetes
  Dreieck statt „▶" (iOS macht daraus ein Emoji), nur Start, kein „Zur Basis".
  Im Kopf kostet er 21 px, als eigene Zeile hätte er 50 gekostet — und den
  Schnitt selbst erzeugt.

---

## Scheibe 3 — „Maximieren" für Nachrichten und Wetter

**Der Zugang:** ein dezenter Vier-Ecken-Knopf im Kachelkopf, 44-px-Ziel, im
Edit-Modus per CSS **weg** (nicht nur wirkungslos). Er trägt
`margin-block: -10px`: sonst zieht er die 23-px-Kopfzeile auf 44, und genau die
21 px hat die flache Wetter-Kachel nicht — die Sonde meldete nach dem ersten Wurf
sofort wieder Schnitte („morgen 13–19°" 5 px, „hell bis 20:48" 3 px). Mit den
negativen Blockrändern bleibt die Content-Fit-Bilanz bei 0 (58 Fälle nachgemessen).

**Nachrichten maximiert:** ALLE Meldungen (8 statt 3 in der Fixture; der Deckel
`CURRENT_AFFAIRS_EXPANDED_COUNT = 6` ist ein Flächen-Riegel für eine Kachel auf
einer Bühne, die nicht scrollen darf). Titel, ungeklemmter Teaser, Quelle mit
Abzeichen, Attribution, Alter und Uhrzeit. **Quellen-Chips = die bestehenden
`SourceBadge`s als Knöpfe** — kein neues Settings-Konzept: sie filtern die
Ansicht und speichern nichts; sie erscheinen erst ab zwei Quellen.

**Wetter maximiert:** vier Abschnitte vom Feinen zum Groben — Jetzt ·
Stundenverlauf · Nächste Tage · Sonne — und jeder **nur mit echten Feldern**:
ohne `hourly` kein Verlauf, ohne `outlook` keine Mehrtage-Zeile, ein einzelner
Sonnenwert ergibt gar keinen Sonnen-Abschnitt. Neu gegenüber der Kachel: BEIDE
Sonnenzeiten plus die gerechnete Tageslänge, und der Lagentext steht
ausgeschrieben.

**Kein neues Gerüst, kein zweiter Datenweg.** Rahmen/Grund/Escape/Fokus kommen
aus der EINEN Modal-Hülle (`Overlay`), Geometrie als Modifikator auf der
generischen Karte — wie die Themen-Galerie. Beide Ansichten sind rein
prop-getrieben und lesen denselben Zustand wie die Kacheln: kein Fetch, kein
Timer, kein WebSocket, **Visibility-Gating unberührt**. Eine Ansicht existiert
nur, solange ihre Kachel existiert.

**i18n in fünf Sprachen**: Maximieren/Schließen/ARIA, Quellen-Filter, Bilanz,
Wetter-Abschnitte (`test/maximieren.test.tsx` prüft alle fünf Kataloge auf
gefüllte Werte und darauf, dass `openAria` den Kachelnamen wirklich einsetzt).

### Beweise, mit dem Finger auf dem Knopf

`tools/zuhause-probe/maximieren.mjs` (neu, Chrome) — 6 Schritte grün:

| Schritt | Messwert |
|---|---|
| 1 · Knopf da | 44-px-Ziel, Kasten zu |
| 2 · Klick öffnet | `role=dialog`, `aria-modal=true`, Titel „Nachrichten" |
| 3 · alle Meldungen | **8 im Kasten** gegen **3 auf der Kachel** |
| 4 · Chip filtert | 8 → 4, Bilanz „4 von 8 Meldungen" |
| 5 · Escape schließt | offen = false |
| 6 · Wetter | Titel „Wetter · Duisburg", Abschnitte `[Jetzt, Stundenverlauf, Nächste Tage, Sonne]` |

`tools/zuhause-probe/firefox.mjs` — 7 Schritte grün (WebDriver BiDi, echte
Zeiger-/Tastatur-Eingaben):

- **Schritt 6 (`FF_STIL=1`)**: Rahmen `oklab(… / 0.68)` — der Token wirkt;
  Container-Query auf HÖHE greift; `line-clamp` und `color-mix` vorhanden.
- **Schritt 7 (`FF_MAX=1`)**: im Edit verborgen (`imEdit: false`), Ziel 44 px,
  Kasten auf, 8 im Kasten gegen 3 auf der Kachel, Chip filtert 8 → 4, Escape
  schließt.

---

## Rate-Stellen (6)

Stellen, an denen eine Zahl oder eine Wertung **nicht** aus einer Messung folgt.
Jede ist im Code an ihrer Fundstelle begründet.

1. **`--hairline-mix: 68%`** — die FOLGE ist gemessen (aoi 1,11:1), die Stufe
   selbst ist eine Wertung: „noch transparenter" ist qualitativ. 65 oder 72
   wären genauso vertretbar gewesen. Nachjustieren ist ein Wert.
2. **Der Riegel `1,10:1` in `rahmen.mjs`** — an den Frames dieses Repos
   kalibriert (aoi war deckend schon bei 1,19:1 kaum sichtbar), aber nicht aus
   einer Norm abgeleitet. Für Text gibt es 4,5:1; für Dekoration gibt es nichts.
3. **Zwei Zeilen Schlagzeile auf der flachen Nachrichten-Kachel** — dass eine
   Schlagzeile überhaupt geklemmt wird, ist unvermeidlich (sie ist beliebig
   lang); dass es ZWEI sind und nicht eine, ist eine Wertung. Die Folge (passt)
   ist gemessen.
4. **Welches Feld auf der schmalen flachen Kachel weicht** (die Quellenzeile,
   nicht die Schlagzeile) — eine Produkt-Entscheidung über Wichtigkeit, nicht
   messbar. Andi kann sie umdrehen; es ist eine CSS-Zeile.
5. **Boden und Deckel der Wetter-M-Fuge (`clamp(10px, 3cqw, 28px)`)** — die
   `3cqw` sind aus dem Fehlbetrag gerechnet (12 px fehlten, 18 kommen zurück);
   10 und 28 sind Sicherungen gegen sehr schmale/breite Kacheln, geschätzt.
6. **Die Breite der Vollbild-Karte (`min(1180px, 96vw)`)** — eine Lesbarkeits-
   Wertung (die Themen-Galerie nimmt 1680 px, aber die zeigt Bilder, keine
   Lesespalte). Nicht gemessen.

## Was NICHT angefasst wurde (VERBOTEN-Block eingehalten)

HomeStage-Gesten/Edit-Schicht und `SunArc.tsx` unberührt (`git show --stat` je
Commit belegt es) · keine Theme-Palette geändert (nur `themes.css`-Regeln für die
neue Overlay-Ansicht ergänzt, keine Farbwerte) · BE-Verträge unverändert, reine
FE-Scheiben · kein Deploy, kein `ssh ct-106`, kein `git add -A`, kein `stash`.
