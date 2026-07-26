# Hoshi-Patch für mlx-lm — Architektur `gemma4_unified` (2026-07-26)
#
# WARUM ES DAS GIBT
# Das dichte Gemma-4-12B (mlx-community/gemma-4-12B-it-4bit) deklariert in seiner
# config.json `model_type: "gemma4_unified"`. mlx-lm löst Architekturen auf, indem
# es `mlx_lm.models.<model_type>` importiert — und dieses Modul existiert in KEINER
# veröffentlichten mlx-lm-Version (geprüft: 0.31.2 lokal, 0.31.3 auf PyPI, und der
# Upstream-Baum). Ohne es endet jeder Ladeversuch mit
#   ValueError: Model type gemma4_unified not supported.
#
# WARUM ER SO KLEIN SEIN DARF (gemessen, nicht vermutet)
# Der Textteil des 12B ist strukturell das, was `gemma4_text` bereits kann:
#   * text_config.model_type = "gemma4_unified_text", sonst deckungsgleich mit e4b
#     bis auf EINEN Schalter — `attention_k_eq_v: true` (e4b: false), also geteilte
#     Key-/Value-Projektionen. Genau den implementiert `gemma4_text` schon
#     (ModelArgs-Feld + use_k_eq_v in der Attention).
#   * enable_moe_block ist false — es ist ein dichtes Modell, kein MoE.
#   * Die Gewichte liegen zu 1324 von 1341 Tensoren unter `language_model.*` —
#     exakt der Namensraum, den der bestehende `gemma4`-Mantel erwartet.
# Der einzige echte Unterschied beim Laden: das 12B bringt einen Multimodal-Kopf
# `vision_embedder.*` mit, den die Überspring-Liste in `gemma4.py` noch nicht kennt
# (sie kennt vision_tower, multi_modal_projector, audio_tower, embed_audio,
# embed_vision). Ohne ihn scheitert das Laden an unerwarteten Schlüsseln.
#
# WAS ER NICHT TUT
# Er macht Hoshi nicht multimodal: Bild-, Audio- und Video-Köpfe werden verworfen,
# genau wie der bestehende Mantel es für e2b/e4b tut. Es ist der TEXT-Pfad des 12B.
#
# EINBAU (bewusst kein pip-Patch): sidecars/brain/bootstrap.sh kopiert diese Datei
# in das models/-Verzeichnis des venv. Ein `pip install`-Neulauf überschreibt sie
# nicht, er löscht sie — deshalb liegt die Quelle hier im Repo und nicht dort.
# Sobald mlx-lm die Architektur selbst mitbringt, ersatzlos streichen.

from dataclasses import dataclass

from .gemma4 import Model as Gemma4Model
from .gemma4 import ModelArgs as Gemma4ModelArgs

# Multimodal-Köpfe des 12B, die der Text-Pfad nicht lädt. `gemma4.py` kennt die
# ersten fünf bereits; `vision_embedder` ist der, der hier dazukommt.
_DROP_PREFIXES = ("vision_embedder",)


@dataclass
class ModelArgs(Gemma4ModelArgs):
    """Identisch zu `gemma4`, nur unter dem Namen, den die config.json nennt."""

    model_type: str = "gemma4_unified"


class Model(Gemma4Model):
    """Der `gemma4`-Mantel, erweitert um die eine zusätzliche Kopf-Familie."""

    def sanitize(self, weights):
        weights = {
            k: v for k, v in weights.items() if not k.startswith(_DROP_PREFIXES)
        }
        return super().sanitize(weights)
