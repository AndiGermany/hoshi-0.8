"""
embedder.py — CAM++ Speaker-Embedding-Core (ONNX, kein torch).

Modell:  Wespeaker/wespeaker-voxceleb-campplus  ->  voxceleb_CAM++.onnx
         (CAM++ / CAMPPlus-TSTP, fbank80, emb_dim=512, VoxCeleb2, Apache-2.0)

Input-Vertrag des ONNX:  feats [B, T, 80] float32   ->   embs [B, 512] float32
Feature-Pipeline (wespeaker-konform):
  - 16 kHz mono PCM
  - Kaldi-fbank: 80 mel bins, frame_length=25ms, frame_shift=10ms, dither=0
  - CMN: per-utterance Mean-Subtraktion (KEINE Varianz-Norm) vor dem Modell
Output: L2-normalisiertes 512-d Embedding -> Cosine == Dot-Product.

Bewusst KEIN torch/funasr: onnxruntime + kaldi-native-fbank halten den
Sidecar unter der 16-GB-Wand klein (RSS ~100-130 MB statt >1 GB).
"""
from __future__ import annotations

import threading
import numpy as np
import onnxruntime as ort
import kaldi_native_fbank as knf

SAMPLE_RATE = 16000
NUM_MEL_BINS = 80
EMBED_DIM = 512


class CamPlusEmbedder:
    """Thread-sicherer CAM++-Embedder. session.run ist GIL-frei und re-entrant;
    der fbank-Computer ist stateful, daher pro Aufruf frisch erzeugt."""

    def __init__(self, onnx_path: str, intra_threads: int = 1):
        opts = ort.SessionOptions()
        # 1 Thread: Sidecar soll NICHT alle Cores fressen (Whisper/Brain laufen).
        opts.intra_op_num_threads = intra_threads
        opts.inter_op_num_threads = 1
        # CoreML zickt bei dyn. Shapes teils -> CPU ist hier deterministisch & klein.
        self.session = ort.InferenceSession(
            onnx_path, sess_options=opts, providers=["CPUExecutionProvider"]
        )
        self.input_name = self.session.get_inputs()[0].name   # "feats"
        self.output_name = self.session.get_outputs()[0].name  # "embs"
        self._lock = threading.Lock()

    def _fbank(self, wav: np.ndarray) -> np.ndarray:
        """wav: float32 mono in [-1,1] @16kHz -> [T,80] fbank, CMN-normalisiert."""
        opts = knf.FbankOptions()
        opts.frame_opts.samp_freq = SAMPLE_RATE
        opts.frame_opts.frame_length_ms = 25.0
        opts.frame_opts.frame_shift_ms = 10.0
        opts.frame_opts.dither = 0.0          # deterministisch @ inference
        # wespeaker-exakt: hamming-Fenster, snip_edges=True (torchaudio-Default),
        # use_energy=False. Mismatch hier verfälscht das fbank -> Embedding bricht.
        opts.frame_opts.window_type = "hamming"
        opts.frame_opts.snip_edges = True
        opts.use_energy = False
        opts.mel_opts.num_bins = NUM_MEL_BINS
        fbank = knf.OnlineFbank(opts)
        # kaldi-fbank erwartet int16-Range-Samples (so trainiert wespeaker)
        fbank.accept_waveform(SAMPLE_RATE, (wav * 32768.0).tolist())
        fbank.input_finished()
        frames = [fbank.get_frame(i) for i in range(fbank.num_frames_ready)]
        feats = np.asarray(frames, dtype=np.float32)            # [T,80]
        # CMN: per-utterance Mean-Subtraktion (wespeaker-Default)
        feats = feats - feats.mean(axis=0, keepdims=True)
        return feats

    @staticmethod
    def _resample_to_16k(wav: np.ndarray, sr: int) -> np.ndarray:
        """Lineares Resampling auf 16 kHz (kein scipy-Dep). Backend liefert i.d.R.
        schon 16 kHz (application.yml audio-sample-rate=16000) -> No-Op-Fastpath."""
        if sr == SAMPLE_RATE:
            return wav.astype(np.float32)
        n_new = int(round(len(wav) * SAMPLE_RATE / sr))
        xi = np.linspace(0.0, len(wav) - 1, n_new)
        return np.interp(xi, np.arange(len(wav)), wav).astype(np.float32)

    def embed(self, wav: np.ndarray, sr: int = SAMPLE_RATE) -> np.ndarray:
        """wav: 1-D/2-D float32 mono @sr -> L2-normalisiertes 512-d float32 Embedding."""
        if wav.ndim > 1:
            wav = wav.mean(axis=1)            # Stereo -> Mono
        wav = self._resample_to_16k(np.asarray(wav, dtype=np.float32), sr)
        wav = np.ascontiguousarray(wav, dtype=np.float32)
        feats = self._fbank(wav)[None, :, :]  # [1,T,80]
        with self._lock:
            emb = self.session.run([self.output_name], {self.input_name: feats})[0]
        emb = emb[0].astype(np.float32)
        n = np.linalg.norm(emb)
        if n > 0:
            emb = emb / n
        return emb
