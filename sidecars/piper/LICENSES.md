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

Der gepflegte Piper-Upstream bettet `espeak-ng` ein und ist ausdrücklich GPL.
Der frühere MIT-Upstream `rhasspy/piper` ist archiviert; er wird hier nicht als
Lizenz-Abkürzung verwendet. Hoshi und Piper bleiben zur Laufzeit über HTTP
getrennte Prozesse, doch das allein ist keine pauschale juristische Aussage zur
Weitergabe eines Gesamtpakets. Wer Piper mitliefert oder verteilt, muss die
anwendbaren GPL-Pflichten separat erfüllen. Deshalb ist die Engine optional,
default-OFF und kein ungeprüfter Apache-/Contest-Claim.
