#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Leakage-freies Offline-Gate fuer ein festes Zwei-Sprecher-Hard-Case.

Das Tool liest ausschliesslich pseudonymisierte BEST_SAMPLE-Scores. Es kennt
weder Audio, Embeddings, Profilnamen noch private Pfade und veraendert keine
Produktkonfiguration. Kandidaten werden nur auf ``train`` ausgewaehlt und danach
genau einmal auf ``holdout`` bewertet.

Die Sicherheitsordnung ist lexikographisch:

1. keine A<->B-Fehlbindung,
2. keine Guest-Bindung,
3. erst dann moeglichst viele korrekte Bindungen.

Mit der kleinen Datenbasis ist das ein lokales Owner-Gate, keine EER-/SOTA-
Messung. Ein Tie bleibt immer Guest; das Tool erfindet keine Sprecherinformation.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import sys
import tempfile
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Iterable, Sequence


REPO_ROOT = Path(__file__).resolve().parents[2]
REPORT_ROOT = Path.home() / ".hoshi" / "speaker-pair-calibration"

FIELDS = (
    "probe_id",
    "group_id",
    "split",
    "truth",
    "channel",
    "duration_s",
    "quality",
    "score_best_a",
    "score_best_b",
)
TRUTHS = ("a", "b", "guest")
CHANNELS = ("browser", "satellite")
SPLITS = ("train", "holdout")
PROBE_ID = re.compile(r"^p[0-9]{3,6}$")
GROUP_ID = re.compile(r"^g[0-9]{3,6}$")
EVIDENCE_SHA256 = re.compile(r"^[0-9a-f]{64}$")
QUALITIES = ("clean", "noisy", "tv", "overlap", "short", "mixed", "unknown")


class ValidationError(ValueError):
    """Die Eingabe wuerde ein falsches oder privates Ergebnis erlauben."""


@dataclass(frozen=True, order=True)
class Candidate:
    tau: float
    delta: float

    @property
    def label(self) -> str:
        return f"tau={self.tau:.2f},delta={self.delta:.2f}"


# Vor Sichtung der Labels bewusst klein eingefroren. Niedrigere Schwellen als
# Produktion (0.45) werden nicht ausprobiert; das Guest-Risiko darf nicht fuer
# Coverage abgesenkt werden.
BASELINE = Candidate(0.45, 0.10)
CANDIDATES = tuple(
    Candidate(tau, delta)
    for tau in (0.45, 0.50, 0.55)
    for delta in (0.05, 0.10)
)


@dataclass(frozen=True)
class ActiveConfig:
    aggregation: str
    tau: float
    delta: float
    evidence_sha256: str


SELFTEST_CONFIG = ActiveConfig("best-sample", BASELINE.tau, BASELINE.delta, "synthetic/selftest")


@dataclass(frozen=True)
class ProbeScore:
    probe_id: str
    group_id: str
    split: str
    truth: str
    channel: str
    duration_s: float
    quality: str
    score_a: float
    score_b: float


@dataclass
class SliceCounts:
    total: int = 0
    genuine: int = 0
    guest: int = 0
    correct_bind: int = 0
    genuine_reject: int = 0
    cross_bind: int = 0
    guest_bind: int = 0
    guest_reject: int = 0


@dataclass
class Evaluation:
    candidate: Candidate
    counts: SliceCounts
    by_truth_channel: dict[str, SliceCounts] = field(default_factory=dict)
    cross_probe_ids: list[str] = field(default_factory=list)
    guest_bind_probe_ids: list[str] = field(default_factory=list)

    @property
    def safe(self) -> bool:
        return self.counts.cross_bind == 0 and self.counts.guest_bind == 0


def _outside_repo(path: Path, what: str) -> Path:
    resolved = path.expanduser().resolve()
    try:
        resolved.relative_to(REPO_ROOT)
    except ValueError:
        return resolved
    raise ValidationError(
        f"{what} {resolved} liegt im Repo {REPO_ROOT}; Score-Daten und Reports "
        "muessen ausserhalb bleiben"
    )


def _id_token(value: str | None, field_name: str, line: int) -> str:
    if value is None:
        raise ValidationError(f"Zeile {line}: {field_name} fehlt")
    value = value.strip().lower()
    pattern = PROBE_ID if field_name == "probe_id" else GROUP_ID
    expected = "pNNN.." if field_name == "probe_id" else "gNNN.."
    if not pattern.fullmatch(value):
        raise ValidationError(f"Zeile {line}: {field_name} muss anonymes Format {expected} haben")
    return value


def _finite_float(value: str | None, field_name: str, line: int) -> float:
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValidationError(f"Zeile {line}: {field_name} ist keine Zahl") from exc
    if not math.isfinite(parsed):
        raise ValidationError(f"Zeile {line}: {field_name} muss endlich sein")
    return parsed


def load_manifest(path: Path) -> list[ProbeScore]:
    path = _outside_repo(path, "Manifest")
    if not path.is_file():
        raise ValidationError(f"Manifest fehlt: {path}")

    with path.open(newline="", encoding="utf-8") as fh:
        reader = csv.DictReader(fh, delimiter="\t")
        if reader.fieldnames is None or set(reader.fieldnames) != set(FIELDS) or len(reader.fieldnames) != len(FIELDS):
            raise ValidationError(
                "Manifest-Header muss exakt diese pseudonymisierten Felder enthalten: "
                + ", ".join(FIELDS)
            )
        rows: list[ProbeScore] = []
        seen_ids: set[str] = set()
        group_splits: dict[str, set[str]] = {}

        for line, raw in enumerate(reader, start=2):
            if None in raw:
                raise ValidationError(f"Zeile {line}: mehr Werte als der erlaubte Header")
            probe_id = _id_token(raw["probe_id"], "probe_id", line)
            group_id = _id_token(raw["group_id"], "group_id", line)
            quality_raw = raw["quality"]
            if quality_raw is None:
                raise ValidationError(f"Zeile {line}: quality fehlt")
            quality = quality_raw.strip().lower()
            if quality not in QUALITIES:
                raise ValidationError(
                    f"Zeile {line}: quality erwartet {'|'.join(QUALITIES)} (keine Namen/Freitexte)"
                )
            if probe_id in seen_ids:
                raise ValidationError(f"Zeile {line}: probe_id {probe_id!r} ist doppelt")
            seen_ids.add(probe_id)

            split = (raw["split"] or "").strip().lower()
            truth = (raw["truth"] or "").strip().lower()
            channel = (raw["channel"] or "").strip().lower()
            if split not in SPLITS:
                raise ValidationError(f"Zeile {line}: split erwartet train|holdout")
            if truth not in TRUTHS:
                raise ValidationError(f"Zeile {line}: truth erwartet a|b|guest")
            if channel not in CHANNELS:
                raise ValidationError(f"Zeile {line}: channel erwartet browser|satellite")

            duration_s = _finite_float(raw["duration_s"], "duration_s", line)
            score_a = _finite_float(raw["score_best_a"], "score_best_a", line)
            score_b = _finite_float(raw["score_best_b"], "score_best_b", line)
            if not 0.0 < duration_s <= 120.0:
                raise ValidationError(f"Zeile {line}: duration_s muss in (0,120] liegen")
            if not -1.0 <= score_a <= 1.0 or not -1.0 <= score_b <= 1.0:
                raise ValidationError(f"Zeile {line}: Cosine-Scores muessen in [-1,1] liegen")

            group_splits.setdefault(group_id, set()).add(split)
            rows.append(
                ProbeScore(
                    probe_id=probe_id,
                    group_id=group_id,
                    split=split,
                    truth=truth,
                    channel=channel,
                    duration_s=duration_s,
                    quality=quality,
                    score_a=score_a,
                    score_b=score_b,
                )
            )

    if not rows:
        raise ValidationError("Manifest enthaelt keine Proben")
    leaked = sorted(group for group, splits in group_splits.items() if len(splits) > 1)
    if leaked:
        raise ValidationError(
            "Session-/Duplikat-Leakage: group_id liegt in train UND holdout: " + ", ".join(leaked)
        )
    return rows


def decide(score_a: float, score_b: float, candidate: Candidate) -> str:
    """Zwei-Profil-Paritaet zur Kotlin-Regel: Top-1 >= tau und Margin >= delta."""
    if score_a == score_b:
        return "guest"  # nie durch Iterationsreihenfolge A oder B raten
    if score_a >= score_b:
        top, other, speaker = score_a, score_b, "a"
    else:
        top, other, speaker = score_b, score_a, "b"
    if top >= candidate.tau and (top - other) >= candidate.delta:
        return speaker
    return "guest"


def _update(counts: SliceCounts, row: ProbeScore, decision: str) -> None:
    counts.total += 1
    if row.truth == "guest":
        counts.guest += 1
        if decision == "guest":
            counts.guest_reject += 1
        else:
            counts.guest_bind += 1
        return

    counts.genuine += 1
    if decision == row.truth:
        counts.correct_bind += 1
    elif decision == "guest":
        counts.genuine_reject += 1
    else:
        counts.cross_bind += 1


def evaluate(rows: Iterable[ProbeScore], candidate: Candidate) -> Evaluation:
    result = Evaluation(candidate=candidate, counts=SliceCounts())
    for row in rows:
        decision = decide(row.score_a, row.score_b, candidate)
        _update(result.counts, row, decision)
        key = f"{row.truth}:{row.channel}"
        result.by_truth_channel.setdefault(key, SliceCounts())
        _update(result.by_truth_channel[key], row, decision)
        if row.truth in ("a", "b") and decision in ("a", "b") and decision != row.truth:
            result.cross_probe_ids.append(row.probe_id)
        if row.truth == "guest" and decision != "guest":
            result.guest_bind_probe_ids.append(row.probe_id)
    return result


def select_on_train(train_rows: Sequence[ProbeScore]) -> tuple[Candidate | None, list[Evaluation]]:
    evaluations = [evaluate(train_rows, candidate) for candidate in CANDIDATES]
    if not train_rows:
        return None, evaluations
    safe = [result for result in evaluations if result.safe]
    if not safe:
        return None, evaluations
    # Sicherheit ist bereits hart gefiltert. Danach Coverage; bei Gleichstand
    # gewinnt der konservativere Betriebspunkt (hoehere tau, dann delta).
    winner = max(
        safe,
        key=lambda result: (
            result.counts.correct_bind,
            result.candidate.tau,
            result.candidate.delta,
        ),
    )
    return winner.candidate, evaluations


def coverage_gaps(rows: Sequence[ProbeScore]) -> list[str]:
    gaps: list[str] = []
    for split in SPLITS:
        split_rows = [row for row in rows if row.split == split]
        if not split_rows:
            gaps.append(f"{split}:keine-proben")
            continue
        for truth in TRUTHS:
            for channel in CHANNELS:
                if not any(row.truth == truth and row.channel == channel for row in split_rows):
                    gaps.append(f"{split}:{truth}:{channel}")
    return gaps


def paired_group_changes(
    holdout_rows: Sequence[ProbeScore], selected: Candidate, baseline: Candidate = BASELINE
) -> tuple[set[str], set[str]]:
    gains: set[str] = set()
    losses: set[str] = set()
    for row in holdout_rows:
        if row.truth == "guest":
            continue
        selected_correct = decide(row.score_a, row.score_b, selected) == row.truth
        baseline_correct = decide(row.score_a, row.score_b, baseline) == row.truth
        if selected_correct and not baseline_correct:
            gains.add(row.group_id)
        elif baseline_correct and not selected_correct:
            losses.add(row.group_id)
    return gains, losses


def zero_error_upper95(n: int) -> float | None:
    """Einseitige 95%-Obergrenze bei 0/n; optimistisch unter IID-Annahme."""
    return None if n <= 0 else 1.0 - math.pow(0.05, 1.0 / n)


def validate_active_config(config: ActiveConfig, real_run: bool = False) -> None:
    if config.aggregation != "best-sample":
        raise ValidationError(
            f"Aktive Aggregation {config.aggregation!r} passt nicht zur vorregistrierten Baseline best-sample"
        )
    if not math.isclose(config.tau, BASELINE.tau, rel_tol=0.0, abs_tol=1e-12):
        raise ValidationError(
            f"Aktive Schwelle {config.tau} passt nicht zur vorregistrierten Baseline {BASELINE.tau}"
        )
    if not math.isclose(config.delta, BASELINE.delta, rel_tol=0.0, abs_tol=1e-12):
        raise ValidationError(
            f"Aktive Margin {config.delta} passt nicht zur vorregistrierten Baseline {BASELINE.delta}"
        )
    if real_run and not EVIDENCE_SHA256.fullmatch(config.evidence_sha256):
        raise ValidationError("Config-Evidence muss SHA-256 der sanitisierten aktiven Boot-/Readback-Zeile sein")


def _evaluation_dict(result: Evaluation) -> dict:
    return {
        "candidate": asdict(result.candidate),
        "safe": result.safe,
        "counts": asdict(result.counts),
        "by_truth_channel": {key: asdict(value) for key, value in sorted(result.by_truth_channel.items())},
        "cross_probe_ids": sorted(result.cross_probe_ids),
        "guest_bind_probe_ids": sorted(result.guest_bind_probe_ids),
    }


def calibrate(
    rows: Sequence[ProbeScore], active_config: ActiveConfig, manifest_sha256: str | None = None
) -> dict:
    validate_active_config(active_config)
    train = [row for row in rows if row.split == "train"]
    holdout = [row for row in rows if row.split == "holdout"]
    selected, train_evaluations = select_on_train(train)
    baseline_holdout = evaluate(holdout, BASELINE)
    gaps = coverage_gaps(rows)
    reasons = [f"Datenzelle fehlt: {gap}" for gap in gaps]

    report: dict = {
        "schema_version": 1,
        "manifest_sha256": manifest_sha256,
        "active_config": asdict(active_config),
        "baseline": asdict(BASELINE),
        "candidate_grid": [asdict(candidate) for candidate in CANDIDATES],
        "train_rows": len(train),
        "holdout_rows": len(holdout),
        "independent_groups": len({row.group_id for row in rows}),
        "coverage_gaps": gaps,
        "train_candidates": [_evaluation_dict(result) for result in train_evaluations],
        "selected": None,
        "baseline_holdout": _evaluation_dict(baseline_holdout),
        "ready_for_owner_gate": False,
        "reasons": reasons,
        "claim_limits": [
            "lokaler Holdout-Gate, keine EER-/SOTA-Messung",
            "0 beobachtete Fehler bedeutet nicht 0 Prozent Fehlerrate",
            "Session-Korrelation macht IID-Konfidenzgrenzen optimistisch",
        ],
    }

    if selected is None:
        reasons.append("Kein Kandidat ist auf train ohne Cross-/Guest-Bindung")
        return report

    selected_holdout = evaluate(holdout, selected)
    gain_groups, loss_groups = paired_group_changes(holdout, selected)
    report["selected"] = {
        "candidate": asdict(selected),
        "holdout": _evaluation_dict(selected_holdout),
        "gain_groups": sorted(gain_groups),
        "loss_groups": sorted(loss_groups),
    }

    if selected == BASELINE:
        reasons.append("Train-Auswahl ergibt nur die heutige Baseline; kein neuer Betriebspunkt")
    if not selected_holdout.safe:
        reasons.append("Finaler Holdout enthaelt Cross- oder Guest-Bindung")
    if selected_holdout.counts.correct_bind <= baseline_holdout.counts.correct_bind:
        reasons.append("Finaler Holdout verbessert die Zahl korrekter Bindungen nicht")
    if len(gain_groups) < 2:
        reasons.append("Verbesserung liegt nicht in mindestens zwei unabhaengigen Holdout-Gruppen")
    if loss_groups:
        reasons.append("Mindestens eine Holdout-Gruppe verliert eine Baseline-Korrektbindung")

    report["ready_for_owner_gate"] = not reasons
    return report


def render_markdown(report: dict) -> str:
    lines = [
        "# Speaker-Paar-Kalibrierung — Offline-Holdout-Gate",
        "",
        f"- Train: {report['train_rows']} Proben",
        f"- Holdout: {report['holdout_rows']} Proben",
        f"- Unabhaengige Gruppen: {report['independent_groups']}",
        f"- Manifest SHA-256: `{report['manifest_sha256'] or 'synthetic/selftest'}`",
        f"- Aktiver Readback: `{report['active_config']['aggregation']}` / "
        f"tau={report['active_config']['tau']:.2f} / delta={report['active_config']['delta']:.2f}",
        f"- Config-Evidence SHA-256: `{report['active_config']['evidence_sha256']}`",
        f"- Ergebnis: **{'READY FUER OWNER-GATE' if report['ready_for_owner_gate'] else 'NICHT READY'}**",
        "- Scope: lokaler Zwei-Personen-Hard-Case; keine EER-, FAR=0- oder SOTA-Behauptung",
        "",
        "## Train-Auswahl (Holdout war an dieser Auswahl nicht beteiligt)",
        "",
        "| tau | delta | korrekt | Cross-Bind | Guest-Bind | sicher |",
        "|---:|---:|---:|---:|---:|:---:|",
    ]
    for candidate in report["train_candidates"]:
        c = candidate["candidate"]
        counts = candidate["counts"]
        lines.append(
            f"| {c['tau']:.2f} | {c['delta']:.2f} | {counts['correct_bind']}/{counts['genuine']} "
            f"| {counts['cross_bind']} | {counts['guest_bind']} | {'ja' if candidate['safe'] else 'nein'} |"
        )

    lines += ["", "## Einmaliger Holdout", ""]
    baseline = report["baseline_holdout"]
    bc = baseline["counts"]
    lines.append(
        f"Baseline tau=0.45/delta=0.10: korrekt {bc['correct_bind']}/{bc['genuine']}, "
        f"Cross-Bind {bc['cross_bind']}, Guest-Bind {bc['guest_bind']}."
    )
    selected = report["selected"]
    if selected is None:
        lines.append("Kein train-sicherer Kandidat; Holdout wurde nicht zur Kandidatenwahl benutzt.")
    else:
        candidate = selected["candidate"]
        counts = selected["holdout"]["counts"]
        lines.append(
            f"Ausgewaehlt tau={candidate['tau']:.2f}/delta={candidate['delta']:.2f}: "
            f"korrekt {counts['correct_bind']}/{counts['genuine']}, Cross-Bind "
            f"{counts['cross_bind']}, Guest-Bind {counts['guest_bind']}."
        )
        lines.append(
            f"Gewinn-Gruppen: {len(selected['gain_groups'])}; Verlust-Gruppen: {len(selected['loss_groups'])}."
        )
        safety_n = counts["genuine"] + counts["guest"]
        if counts["cross_bind"] == 0 and counts["guest_bind"] == 0 and safety_n:
            bound = zero_error_upper95(safety_n)
            lines.append(
                f"Nur Orientierung: 0/{safety_n} beobachtete Safety-Fehler ergibt unter optimistischer "
                f"IID-Annahme eine einseitige 95%-Obergrenze von {100.0 * bound:.1f} %, nicht 0 %."
            )

    lines += ["", "## Blocker / Stop-Gruende", ""]
    if report["reasons"]:
        lines.extend(f"- {reason}" for reason in report["reasons"])
    else:
        lines.append("- Keine im vorab definierten Offline-Gate.")
    lines += [
        "",
        "> Das Tool nimmt keinen Live-Flip vor. Wiring, Deploy und Owner-Gate bleiben ausserhalb dieses Reports.",
    ]
    return "\n".join(lines) + "\n"


def write_report(report: dict, out_dir: Path) -> tuple[Path, Path]:
    out_dir = _outside_repo(out_dir, "Report-Ziel")
    try:
        out_dir.mkdir(mode=0o700, parents=True, exist_ok=False)
    except FileExistsError as exc:
        raise ValidationError(f"Report-Ziel existiert bereits (kein Ueberschreiben): {out_dir}") from exc
    out_dir.chmod(0o700)
    json_path = out_dir / "calibration-report.json"
    md_path = out_dir / "report.md"
    json_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")
    json_path.chmod(0o600)
    md_path.chmod(0o600)
    return json_path, md_path


def _fixture_rows() -> list[ProbeScore]:
    rows: list[ProbeScore] = []
    idx = 0
    for split in SPLITS:
        for truth in TRUTHS:
            for channel in CHANNELS:
                idx += 1
                if truth == "a":
                    score_a, score_b = 0.66, 0.58  # Margin .08: neue Regel bindet, Baseline nicht.
                elif truth == "b":
                    score_a, score_b = 0.57, 0.66  # Margin .09.
                else:
                    score_a, score_b = 0.34, 0.33
                rows.append(
                    ProbeScore(
                        probe_id=f"p{idx:03d}",
                        group_id=f"g{idx:03d}",
                        split=split,
                        truth=truth,
                        channel=channel,
                        duration_s=2.0,
                        quality="clean",
                        score_a=score_a,
                        score_b=score_b,
                    )
                )
    return rows


def selftest() -> int:
    report = calibrate(_fixture_rows(), SELFTEST_CONFIG)
    selected = report.get("selected")
    ok = bool(
        report["ready_for_owner_gate"]
        and selected
        and selected["candidate"] == asdict(Candidate(0.55, 0.05))
        and selected["holdout"]["counts"]["cross_bind"] == 0
        and selected["holdout"]["counts"]["guest_bind"] == 0
    )
    with tempfile.TemporaryDirectory(prefix="hoshi-speaker-pair-") as tmp:
        json_path, md_path = write_report(report, Path(tmp) / "report")
        ok = ok and json_path.is_file() and md_path.is_file()
    print(
        "[speaker-pair-selftest] " + ("PASS" if ok else "FAIL")
        + f" — selected={None if selected is None else selected['candidate']}"
    )
    return 0 if ok else 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, help="Pseudonymisiertes Score-TSV (muss ausserhalb des Repos liegen)")
    parser.add_argument("--out-dir", type=Path, help="Neues Report-Verzeichnis ausserhalb des Repos")
    parser.add_argument("--active-aggregation", choices=("best-sample", "centroid"))
    parser.add_argument("--active-tau", type=float)
    parser.add_argument("--active-delta", type=float)
    parser.add_argument("--config-evidence-sha256")
    parser.add_argument("--selftest", action="store_true", help="Synthetischer, sidecar-freier Contract-Test")
    args = parser.parse_args(argv)

    try:
        if args.selftest:
            if any(value is not None for value in (
                args.input, args.out_dir, args.active_aggregation, args.active_tau,
                args.active_delta, args.config_evidence_sha256,
            )):
                parser.error("--selftest darf nicht mit Real-Daten-/Config-Argumenten kombiniert werden")
            return selftest()
        if args.input is None:
            parser.error("--input ist erforderlich (oder --selftest)")
        if None in (args.active_aggregation, args.active_tau, args.active_delta, args.config_evidence_sha256):
            parser.error(
                "Real-Lauf braucht --active-aggregation, --active-tau, --active-delta und "
                "--config-evidence-sha256"
            )
        active_config = ActiveConfig(
            aggregation=args.active_aggregation,
            tau=args.active_tau,
            delta=args.active_delta,
            evidence_sha256=args.config_evidence_sha256.lower(),
        )
        validate_active_config(active_config, real_run=True)
        rows = load_manifest(args.input)
        manifest_path = args.input.expanduser().resolve()
        manifest_sha256 = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
        report = calibrate(rows, active_config=active_config, manifest_sha256=manifest_sha256)
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
        out_dir = args.out_dir or REPORT_ROOT / stamp
        json_path, md_path = write_report(report, out_dir)
        print(f"[speaker-pair] report={md_path}")
        print(f"[speaker-pair] json={json_path}")
        print(f"[speaker-pair] manifest_sha256={manifest_sha256}")
        print(f"[speaker-pair] ready_for_owner_gate={str(report['ready_for_owner_gate']).lower()}")
        return 0
    except ValidationError as exc:
        print(f"[speaker-pair] FEHLER: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
