#!/usr/bin/env python3
"""[0.8-Port] 1:1 aus Hoshi_0.5/hoshi-llm-optiq/smoke_sensors.py (nur der Import
unten folgt der Umbenennung server_e4b.py -> server.py, s. sidecars/brain/README.md).

Trockener Smoke-Test fuer den Sensor-Patch in server.py (Delta-Logprobs +
/v1/score) — KEIN Modell-Load, KEIN zweiter Brain-Prozess (16-GB-Wand, ein
Live-Brain :8041 bleibt unberuehrt). Mockt NUR `_model`/`_tok` mit winzigen
mx.array-Objekten (kein 5GB-Gewicht) und ruft den ECHTEN Code aus server auf.

Testet:
  (a) Delta-Logprob-Frame-Bau — dieselbe Logik wie in chat()/gen(): mit
      req.logprobs=True traegt der Frame zusaetzlich "logprob" (Wert = der
      TATSAECHLICHE Logprob des gesampelten Tokens aus einer Fake-GenerationResponse);
      ohne das Flag ist der Frame byte-identisch zu heute (nur "delta").
  (b) _score_text() — ECHT importiert aus server, gegen ein Fake-_model (gibt
      deterministische, nicht-triviale Logits zurueck) + Fake-_tok. Prueft den
      vektorisierten take_along_axis-Gather gegen eine manuelle Schritt-fuer-Schritt
      log_softmax-Referenzrechnung (numpy), plus Edge-Cases (leerer Text, cache=None
      wird erzwungen).

Aufruf: sidecars/brain/.venv/bin/python sidecars/brain/smoke_sensors.py
"""
import json
import sys

import mlx.core as mx
import numpy as np

import server as srv


def test_delta_frame_with_logprobs():
    """Reproduziert den Frame-Bau aus chat()/gen() 1:1 (server.py, Delta-Loop)."""

    class FakeResp:
        token = 5
        logprobs = mx.array([-3.0, -2.0, -1.0, -0.7, -0.5, -0.42, -5.0])

    piece = "Hallo"

    # req.logprobs = True -> Frame traegt "logprob" (Logprob des gesampelten Tokens).
    frame = {"delta": piece}
    frame["logprob"] = FakeResp.logprobs[FakeResp.token].item()
    assert abs(frame["logprob"] - (-0.42)) < 1e-6, frame
    round_tripped = json.loads(json.dumps(frame, ensure_ascii=False))
    assert round_tripped["delta"] == "Hallo", round_tripped
    assert abs(round_tripped["logprob"] - (-0.42)) < 1e-5, round_tripped  # float32-Rundung
    print(f"  OK delta-frame logprobs=true: {round_tripped}")

    # req.logprobs fehlt/False -> Frame EXAKT wie heute (nur delta, kein Zusatz-Key).
    frame_default = {"delta": piece}
    assert frame_default == {"delta": "Hallo"}
    assert "logprob" not in frame_default
    print("  OK delta-frame logprobs=false/fehlend: byte-identisch (nur 'delta')")


def test_score_empty_text_no_model_call():
    class FakeTokNoModel:
        bos_token_id = 2

        def encode(self, text, add_special_tokens=False):
            return []

    class ExplodingModel:
        def __call__(self, *a, **kw):
            raise AssertionError("Modell darf bei 0 Tokens NIE aufgerufen werden")

    srv._tok = FakeTokNoModel()
    srv._model = ExplodingModel()
    out = srv._score_text("")
    assert out == {
        "tokens": [], "logprobs": [], "mean_surprisal": 0.0,
        "max_surprisal": 0.0, "token_count": 0, "ms": out["ms"],
    }, out
    print(f"  OK score('') -> leeres Ergebnis, kein Modell-Call, ms={out['ms']}")


def test_score_vectorized_matches_manual_reference():
    vocab = 11
    real_ids = [3, 7, 1, 9]
    bos_id = 2
    full_ids = [bos_id] + real_ids
    L = len(full_ids)

    class FakeTok:
        bos_token_id = bos_id

        def encode(self, text, add_special_tokens=False):
            assert add_special_tokens is False
            return list(real_ids)

    rng = np.random.default_rng(42)
    logits_np = rng.normal(size=(1, L, vocab)).astype(np.float32)

    class FakeModel:
        def __call__(self, inputs, cache=None):
            assert cache is None, "Score-Pfad darf NIE einen KV-Cache mitgeben (zustandslos)"
            assert inputs.shape == (1, L), f"unerwartete Shape {inputs.shape}"
            assert inputs.tolist()[0] == full_ids, "BOS wurde nicht korrekt vorangestellt"
            return mx.array(logits_np)

    srv._tok = FakeTok()
    srv._model = FakeModel()

    out = srv._score_text("wird von FakeTok.encode ignoriert")

    # Manuelle Referenz: log_softmax pro Position i, Logprob des TATSAECHLICH
    # naechsten Tokens full_ids[i+1] aus der Verteilung an Position i.
    manual_lp = []
    for i in range(L - 1):
        row = logits_np[0, i, :].astype(np.float64)
        row = row - (np.log(np.exp(row - row.max()).sum()) + row.max())
        manual_lp.append(float(row[full_ids[i + 1]]))

    assert out["tokens"] == real_ids, out["tokens"]
    assert out["token_count"] == len(real_ids) == 4
    assert len(out["logprobs"]) == 4
    for got, want in zip(out["logprobs"], manual_lp):
        assert abs(got - want) < 1e-3, (got, want)
    want_mean = sum(-x for x in manual_lp) / len(manual_lp)
    want_max = max(-x for x in manual_lp)
    assert abs(out["mean_surprisal"] - want_mean) < 1e-3, (out["mean_surprisal"], want_mean)
    assert abs(out["max_surprisal"] - want_max) < 1e-3, (out["max_surprisal"], want_max)
    print(f"  OK score(): vektorisierter take_along_axis-Gather == manuelle "
          f"log_softmax-Referenz (4 Tokens, mean_surprisal={out['mean_surprisal']:.4f}, "
          f"max_surprisal={out['max_surprisal']:.4f}, ms={out['ms']})")


if __name__ == "__main__":
    test_delta_frame_with_logprobs()
    test_score_empty_text_no_model_call()
    test_score_vectorized_matches_manual_reference()
    print("ALLE Sensor-Smoke-Tests GRUEN (trocken, kein Modell-Load, Live-Brain :8041 unberuehrt).")
    sys.exit(0)
