# Lizenzgrenze des optionalen Piper-Sidecars

Hoshis Wrapper (`server.py`, Shell-Skripte und Tests) ist Teil des unter
Apache-2.0 stehenden Hoshi-Projekts. Er enthält keinen kopierten Piper-Code und
keine Modellgewichte.

Zur lokalen Laufzeit lädt `bootstrap.sh` separat lizenzierte Artefakte:

| Artefakt | Pin | Lizenz | Quelle |
|---|---|---|---|
| Piper Engine/Wheel | `piper-tts 1.5.0`, Apple Silicon, PyPI-Artefakt mit SHA-256 im Lockfile | GPL-3.0-or-later | <https://pypi.org/project/piper-tts/1.5.0/> / <https://github.com/OHF-Voice/piper1-gpl/tree/v1.5.0> |
| Thorsten medium ONNX | HF-Commit `2c11851a…`, SHA-256 im Lockfile | MIT laut Modellkarte | <https://huggingface.co/Thorsten-Voice/Piper> |
| Trainingsdaten | `Thorsten-Voice/TV-44kHz-Full` | CC0-1.0 laut Dataset Card | <https://huggingface.co/datasets/Thorsten-Voice/TV-44kHz-Full> |
| Kristin medium ONNX (en_US) | HF-Commit `5b44ec7b…`, SHA-256 im Lockfile | MIT laut Repo-Card | <https://huggingface.co/rhasspy/piper-voices> |
| Trainingsdaten (en_US) | LibriVox-Zusammenstellung, ca. 11,5 h, Einzelsprecherin | **public domain** laut Modellkarte | <https://librivox.org> |

**Warum ausgerechnet `kristin` und keine der bekannteren englischen Stimmen**
(Auswahl am 2026-07-21, Modellkarten einzeln geprüft — nicht nach Bekanntheit
gewählt): Die englischen Piper-Stimmen sind fast alle aus `en_US-lessac`
**feingetunt** und erben damit dessen Datenherkunft — den Blizzard-2013-Lessac-
Datensatz, der unter einer eigenen, nicht pauschal freien Lizenz steht. Das
betrifft `amy`, `joe`, `alba`, `jenny_dioco` und `northern_english_male` ebenso
wie `hfc_female`, deren Datensatz zusätzlich **CC BY-NC-SA** (nicht-kommerziell)
ist. `kristin` ist laut Modellkarte **„Trained from scratch"** auf einem
public-domain-LibriVox-Korpus und damit die einzige geprüfte englische Stimme
ohne geerbte Lizenz-Erblast. Dieselbe Linie wie beim Verzicht auf den
archivierten MIT-`rhasspy/piper`-Upstream: **keine Lizenz-Abkürzungen.**

Der gepflegte Piper-Upstream bettet `espeak-ng` ein und ist ausdrücklich GPL.
Der frühere MIT-Upstream `rhasspy/piper` ist archiviert; er wird hier nicht als
Lizenz-Abkürzung verwendet. Hoshi und Piper bleiben zur Laufzeit über HTTP
getrennte Prozesse, doch das allein ist keine pauschale juristische Aussage zur
Weitergabe eines Gesamtpakets. Wer Piper mitliefert oder verteilt, muss die
anwendbaren GPL-Pflichten separat erfüllen. Deshalb ist die Engine optional,
default-OFF und kein ungeprüfter Apache-/Contest-Claim.
