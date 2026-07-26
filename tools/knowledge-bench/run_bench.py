#!/usr/bin/env python3
"""Vergleicht zwei lokale Knowledge-Bridges auf demselben unangetasteten Satz."""

from __future__ import annotations

import argparse
import ctypes
import errno
import hashlib
import json
import math
import os
import re
import shutil
import statistics
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Optional, Sequence


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) in sys.path:
    sys.path.remove(str(SCRIPT_DIR))
sys.path.insert(0, str(SCRIPT_DIR))
PACK_TOOLS_DIR = SCRIPT_DIR.parent / "knowledge-pack"
if str(PACK_TOOLS_DIR) in sys.path:
    sys.path.remove(str(PACK_TOOLS_DIR))
sys.path.insert(1, str(PACK_TOOLS_DIR))

import dataset_schema as _dataset_schema_module  # noqa: E402
from dataset_schema import (  # noqa: E402
    SCHEMA_VERSION,
    GoldPassage,
    Query,
    normalize_evidence,
    normalize_title,
    read_queries,
)
import verify_pack as _verify_pack_module  # noqa: E402
from verify_pack import verify_pack as verify_knowledge_pack  # noqa: E402

if Path(_dataset_schema_module.__file__).resolve() != (
    SCRIPT_DIR / "dataset_schema.py"
).resolve():
    raise RuntimeError("dataset_schema wurde nicht aus tools/knowledge-bench geladen")
if Path(_verify_pack_module.__file__).resolve() != (
    PACK_TOOLS_DIR / "verify_pack.py"
).resolve():
    raise RuntimeError("verify_pack wurde nicht aus tools/knowledge-pack geladen")

PRODUCTION_MIN_RECALL_GAIN = 0.10
PRODUCTION_MAX_ADDED_P95_MS = 150.0
PRODUCTION_MIN_HOLDOUT_N = 20
PRODUCTION_MIN_HOLDOUT_ANSWERABLE = 20
PRODUCTION_MIN_HOLDOUT_UNANSWERABLE = 10
PRODUCTION_MAX_FALSE_RETRIEVAL_CANDIDATE_RATE = 0.05
PRODUCTION_EXACT_ALPHA = 0.05
PRODUCTION_MIN_REPEATS = 3
PRODUCTION_MIN_WARMUP_ROUNDS = 1
PRODUCTION_LIMIT = 3
PRODUCTION_BM25_MAX = -3.0
PRODUCTION_BASELINE_ENDPOINT = "/search"
PRODUCTION_CANDIDATE_ENDPOINT = "/v1/search"
PRODUCTION_CANDIDATE_MANIFEST_ENDPOINT = "/v1/manifest"
PRODUCTION_BASELINE_HEALTH_ENDPOINT = "/health"
PRODUCTION_BASELINE_ATTESTATION_ENDPOINT = "/v1/health"
CANDIDATE_SELECTION_FILE = "candidate-selection.jsonl"
MAX_CANDIDATE_SELECTION_BYTES = 16 * 1024 * 1024
MAX_FROZEN_QUERY_BYTES = 64 * 1024 * 1024
MAX_BASELINE_DATABASE_BYTES = 32 * 1024 * 1024 * 1024
LOOPBACK_HOSTS = {"127.0.0.1", "localhost", "::1"}
SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")


class NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Ein lokaler Benchmark darf private Query-Parameter nie weiterleiten."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def proxy_free_no_redirect_opener():
    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        NoRedirectHandler(),
    )


@dataclass(frozen=True)
class Probe:
    query_id: str
    duration_ms: float
    bridge_duration_ms: Optional[int]
    titles: tuple[str, ...]
    scores: tuple[float, ...]
    evidence_rank: Optional[int]
    extracts_chars: int
    error: Optional[str]
    repeat: int = 0


def _fingerprint(value: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_mode,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


@dataclass(frozen=True)
class BaselineState:
    path: Path
    fingerprint: tuple[int, int, int, int, int, int]
    sha256: str
    size_bytes: int

    def assert_unchanged(self) -> None:
        sidecars = _sqlite_sidecars(self.path)
        if sidecars:
            raise ValueError(
                "Baseline-Datenbank besitzt nicht gebundene SQLite-Sidecars: "
                + ", ".join(sidecars)
            )
        try:
            observed = self.path.lstat()
        except OSError as exc:
            raise ValueError("Baseline-Datenbank ist nicht mehr prüfbar") from exc
        if _fingerprint(observed) != self.fingerprint:
            raise ValueError("Baseline-Datenbank änderte sich während der Messung")


def _sqlite_sidecars(path: Path) -> list[str]:
    return [
        candidate.name
        for candidate in (
            Path(str(path) + "-wal"),
            Path(str(path) + "-shm"),
            Path(str(path) + "-journal"),
        )
        if candidate.exists()
    ]


@dataclass
class FrozenDatasetSnapshot:
    source_dir: Path
    source_dir_fingerprint: tuple[int, int, int, int, int, int]
    source_fingerprints: dict[str, tuple[int, int, int, int, int, int]]
    temporary_dir: Path
    query_name: str

    @property
    def manifest_path(self) -> Path:
        return self.temporary_dir / "manifest.json"

    @property
    def query_path(self) -> Path:
        return self.temporary_dir / self.query_name

    def cleanup(self) -> None:
        if self.temporary_dir.exists():
            shutil.rmtree(self.temporary_dir)

    def verify_and_cleanup(self) -> None:
        try:
            current_names = {path.name for path in self.source_dir.iterdir()}
            if current_names != set(self.source_fingerprints):
                raise ValueError(
                    "Freeze-Verzeichnis änderte seinen exakten Dateiinhalt "
                    "während der Messung"
                )
            if _fingerprint(self.source_dir.lstat()) != self.source_dir_fingerprint:
                raise ValueError("Freeze-Verzeichnis änderte sich während der Messung")
            for name, expected in self.source_fingerprints.items():
                if _fingerprint((self.source_dir / name).lstat()) != expected:
                    raise ValueError(
                        f"eingefrorene Datei {name} änderte sich während der Messung"
                    )
        finally:
            self.cleanup()


FROZEN_DATASET_FILES = {
    "manifest.json": 2 * 1024 * 1024,
    "dev.jsonl": MAX_FROZEN_QUERY_BYTES,
    "holdout.jsonl": MAX_FROZEN_QUERY_BYTES,
    CANDIDATE_SELECTION_FILE: MAX_CANDIDATE_SELECTION_BYTES,
}


def _stable_frozen_file(path: Path, maximum_bytes: int) -> tuple[bytes, tuple]:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(path, flags)
    except OSError as exc:
        raise ValueError(f"eingefrorene Datei {path.name} ist nicht sicher lesbar") from exc
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size > maximum_bytes
        ):
            raise ValueError(
                f"eingefrorene Datei {path.name} verletzt Typ-/Größenvertrag"
            )
        chunks: list[bytes] = []
        remaining = maximum_bytes + 1
        while remaining > 0:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        after = os.fstat(descriptor)
        if _fingerprint(after) != _fingerprint(before):
            raise ValueError(
                f"eingefrorene Datei {path.name} änderte sich beim Snapshot"
            )
    finally:
        os.close(descriptor)
    try:
        path_state = path.lstat()
    except OSError as exc:
        raise ValueError(
            f"eingefrorene Datei {path.name} verschwand beim Snapshot"
        ) from exc
    fingerprint = _fingerprint(path_state)
    if fingerprint != _fingerprint(before):
        raise ValueError(
            f"Pfad der eingefrorenen Datei {path.name} änderte sich beim Snapshot"
        )
    return b"".join(chunks), fingerprint


def snapshot_frozen_dataset(
    query_path: Path,
    manifest_path: Path,
) -> FrozenDatasetSnapshot:
    source_query = query_path.expanduser().absolute()
    source_manifest = manifest_path.expanduser().absolute()
    if (
        source_query.parent != source_manifest.parent
        or source_query.name not in {"dev.jsonl", "holdout.jsonl"}
        or source_manifest.name != "manifest.json"
    ):
        raise ValueError(
            "Queries und manifest.json müssen aus demselben kanonischen "
            "Freeze-Verzeichnis stammen"
        )
    source_dir = source_manifest.parent
    try:
        directory_state = source_dir.lstat()
        names = {path.name for path in source_dir.iterdir()}
    except OSError as exc:
        raise ValueError("Freeze-Verzeichnis ist nicht sicher lesbar") from exc
    if not stat.S_ISDIR(directory_state.st_mode):
        raise ValueError("Freeze-Quelle ist kein Verzeichnis")
    if names != set(FROZEN_DATASET_FILES):
        raise ValueError(
            "Freeze-Verzeichnis muss exakt manifest, dev, holdout und "
            "candidate-selection enthalten"
        )
    contents: dict[str, bytes] = {}
    fingerprints: dict[str, tuple] = {}
    for name, maximum in FROZEN_DATASET_FILES.items():
        contents[name], fingerprints[name] = _stable_frozen_file(
            source_dir / name,
            maximum,
        )
    if _fingerprint(source_dir.lstat()) != _fingerprint(directory_state):
        raise ValueError("Freeze-Verzeichnis änderte sich beim Snapshot")

    temporary_dir = Path(tempfile.mkdtemp(prefix="hoshi-knowledge-bench-snapshot-"))
    os.chmod(temporary_dir, 0o700)
    try:
        for name, content in contents.items():
            target = temporary_dir / name
            target.write_bytes(content)
            os.chmod(target, 0o600)
    except Exception:
        shutil.rmtree(temporary_dir)
        raise
    return FrozenDatasetSnapshot(
        source_dir=source_dir,
        source_dir_fingerprint=_fingerprint(directory_state),
        source_fingerprints=fingerprints,
        temporary_dir=temporary_dir,
        query_name=source_query.name,
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bind_baseline_database(path: Path) -> BaselineState:
    local_path = path.expanduser().absolute()
    sidecars = _sqlite_sidecars(local_path)
    if sidecars:
        raise ValueError(
            "Baseline-Datenbank besitzt nicht gebundene SQLite-Sidecars: "
            + ", ".join(sidecars)
        )
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(local_path, flags)
    except OSError as exc:
        raise ValueError("Baseline-Datenbank fehlt oder ist nicht sicher lesbar") from exc
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size < 1
            or before.st_size > MAX_BASELINE_DATABASE_BYTES
        ):
            raise ValueError(
                "Baseline-Datenbank muss eine reguläre, nicht-leere Datei "
                "unter 32 GiB sein"
            )
        digest = hashlib.sha256()
        while True:
            chunk = os.read(descriptor, 8 * 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
        after = os.fstat(descriptor)
        if _fingerprint(after) != _fingerprint(before):
            raise ValueError("Baseline-Datenbank änderte sich während des Hashens")
    finally:
        os.close(descriptor)
    state = BaselineState(
        path=local_path,
        fingerprint=_fingerprint(before),
        sha256=digest.hexdigest(),
        size_bytes=before.st_size,
    )
    state.assert_unchanged()
    return state


def fetch_baseline_health(base_url: str) -> dict:
    local_url = validate_loopback_url(base_url, "Baseline-URL")
    opener = proxy_free_no_redirect_opener()

    def read_health(endpoint: str, label: str) -> dict:
        try:
            with opener.open(
                f"{local_url}{endpoint}",
                timeout=10.0,
            ) as response:
                raw = response.read(1024 * 1024 + 1)
        except (
            OSError,
            urllib.error.URLError,
            urllib.error.HTTPError,
        ) as exc:
            raise ValueError(f"{label} nicht lesbar: {exc}") from exc
        if len(raw) > 1024 * 1024:
            raise ValueError(f"{label}-Antwort ist zu groß")
        try:
            parsed = json.loads(raw.decode("utf-8"))
        except (UnicodeError, json.JSONDecodeError) as exc:
            raise ValueError(f"{label} ist kein gültiges UTF-8/JSON") from exc
        return _object(parsed, label)

    health = read_health(
        PRODUCTION_BASELINE_HEALTH_ENDPOINT,
        "Baseline-Health",
    )
    root = _object(health, "baseline health")
    if root.get("status") != "ok":
        raise ValueError("Baseline-Health meldet nicht ok")
    db_path = _text(root.get("dbPath"), "baseline health.dbPath")
    article_count = _integer(
        root.get("articleCount"),
        "baseline health.articleCount",
    )
    if article_count < 1:
        raise ValueError("Baseline-Health meldet keine Artikel")
    attestation = read_health(
        PRODUCTION_BASELINE_ATTESTATION_ENDPOINT,
        "Baseline-v1-Health",
    )
    if attestation.get("status") != "ok":
        raise ValueError("Baseline-v1-Health meldet nicht ok")
    runtime_code = validate_runtime_code(
        attestation.get("runtimeCode"),
        "baseline v1 health.runtimeCode",
    )
    if _integer(
        attestation.get("articleCount"),
        "baseline v1 health.articleCount",
    ) != article_count:
        raise ValueError("Baseline-Health-Endpunkte melden andere Artikelzahlen")
    return {
        "dbPath": db_path,
        "articleCount": article_count,
        "runtimeCode": runtime_code,
    }


def verify_baseline_identity(
    base_url: str,
    database_path: Path,
    dataset_manifest: dict,
) -> tuple[BaselineState, dict]:
    expected = _object(dataset_manifest.get("baseline"), "manifest.baseline")
    expected_sha = _text(
        expected.get("databaseSha256"),
        "manifest.baseline.databaseSha256",
    ).lower()
    expected_size = _integer(
        expected.get("sizeBytes"),
        "manifest.baseline.sizeBytes",
    )
    state = bind_baseline_database(database_path)
    if state.sha256 != expected_sha or state.size_bytes != expected_size:
        raise ValueError(
            "Baseline-Datenbank stimmt nicht mit dem Selection-Seal überein"
        )
    health = fetch_baseline_health(base_url)
    try:
        same_file = os.path.samefile(health["dbPath"], state.path)
    except OSError as exc:
        raise ValueError(
            "Baseline-Health-Pfad ist lokal nicht derselben Datei zuordenbar"
        ) from exc
    if not same_file:
        raise ValueError(
            "Baseline-Runtime verwendet nicht die eingefrorene Baseline-Datenbank"
        )
    return state, {
        "identityMethod": "legacy-health-path-plus-local-sha256-v1",
        "databaseSha256": state.sha256,
        "sizeBytes": state.size_bytes,
        "articleCount": health["articleCount"],
        "runtimeStatus": "ok",
        "runtimeCode": health["runtimeCode"],
    }


def assert_baseline_unchanged(
    state: BaselineState,
    base_url: str,
    binding: dict,
) -> None:
    state.assert_unchanged()
    health = fetch_baseline_health(base_url)
    try:
        same_file = os.path.samefile(health["dbPath"], state.path)
    except OSError as exc:
        raise ValueError("Baseline-Runtime ist nach der Messung nicht prüfbar") from exc
    if (
        not same_file
        or health["articleCount"] != binding["articleCount"]
        or health["runtimeCode"] != binding["runtimeCode"]
    ):
        raise ValueError("Baseline-Runtime änderte ihre Identität während der Messung")


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("muss mindestens 1 sein")
    return parsed


def _nonnegative_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("darf nicht negativ sein")
    return parsed


def validate_loopback_url(value: str, label: str) -> str:
    """Kanonisiert einen reinen Loopback-Basisendpunkt; kein Proxy-/SSRF-Ausweg."""
    parsed = urllib.parse.urlparse(value)
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.hostname not in LOOPBACK_HOSTS
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise ValueError(
            f"{label} muss ein reiner Loopback-Endpunkt sein "
            "(127.0.0.1, localhost oder ::1)"
        )
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError(f"{label} enthält einen ungültigen Port") from exc
    host = {
        "localhost": "127.0.0.1",
        "127.0.0.1": "127.0.0.1",
        "::1": "[::1]",
    }[parsed.hostname]
    authority = f"{host}:{port}" if port is not None else host
    return urllib.parse.urlunsplit((parsed.scheme, authority, "", "", ""))


def validate_endpoint(value: str, label: str) -> str:
    parsed = urllib.parse.urlparse(value)
    if (
        not value.startswith("/")
        or value.startswith("//")
        or parsed.scheme
        or parsed.netloc
        or parsed.query
        or parsed.fragment
        or ".." in Path(parsed.path).parts
    ):
        raise ValueError(f"{label} muss ein reiner absoluter URL-Pfad sein")
    return "/" + parsed.path.lstrip("/")


def _object(value: object, field: str) -> dict:
    if not isinstance(value, dict):
        raise ValueError(f"{field} muss ein JSON-Objekt sein")
    return value


def _text(value: object, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} muss ein nicht-leerer String sein")
    return value.strip()


def _integer(value: object, field: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{field} muss eine nichtnegative Ganzzahl sein")
    return value


def validate_runtime_code(value: object, label: str) -> dict:
    runtime_code = _object(value, label)
    if set(runtime_code) != {
        "attestation",
        "serverSha256",
        "packManifestSha256",
    } or runtime_code.get("attestation") != "self-reported-source-sha256-v1":
        raise ValueError(f"{label} besitzt keine erwartete Source-Selbstauskunft")
    repo_root = SCRIPT_DIR.parents[1]
    expected_runtime_hashes = {
        "serverSha256": _sha256(
            repo_root / "sidecars" / "knowledge" / "server.py"
        ),
        "packManifestSha256": _sha256(
            repo_root / "sidecars" / "knowledge" / "pack_manifest.py"
        ),
    }
    for field, expected in expected_runtime_hashes.items():
        observed = _text(runtime_code.get(field), f"{label}.{field}").lower()
        if not SHA256_HEX.fullmatch(observed) or observed != expected:
            raise ValueError(
                f"{label} meldet nicht die lokal geprüften Source-Bytes"
            )
    return runtime_code


def load_dataset_manifest(
    manifest_path: Path,
    query_path: Path,
    queries: Sequence[Query],
    requested_split: str,
) -> tuple[dict, str]:
    """Bindet Query-Datei, tatsächlichen Split und Counts an den Freeze-Beleg."""
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"Dataset-Manifest nicht lesbar: {manifest_path.name}: {exc}") from exc
    root = _object(manifest, "manifest")
    if root.get("schemaVersion") != 1:
        raise ValueError("Manifest-Schema passt nicht zum Runner")
    if root.get("datasetSchemaVersion") != SCHEMA_VERSION:
        raise ValueError("Manifest-Dataset-Schema passt nicht zum Runner")
    _text(root.get("datasetId"), "manifest.datasetId")
    dataset_sha = _text(root.get("datasetSha256"), "manifest.datasetSha256").lower()
    if not SHA256_HEX.fullmatch(dataset_sha):
        raise ValueError("manifest.datasetSha256 muss ein SHA-256-Hexwert sein")
    source_dump = _object(root.get("sourceDump"), "manifest.sourceDump")
    _text(source_dump.get("url"), "manifest.sourceDump.url")
    source_sha = _text(
        source_dump.get("sha256"),
        "manifest.sourceDump.sha256",
    ).lower()
    if not SHA256_HEX.fullmatch(source_sha):
        raise ValueError("manifest.sourceDump.sha256 muss ein SHA-256-Hexwert sein")
    baseline = _object(root.get("baseline"), "manifest.baseline")
    if set(baseline) != {"databaseSha256", "sizeBytes"}:
        raise ValueError("manifest.baseline besitzt nicht das erwartete exakte Schema")
    baseline_sha = _text(
        baseline.get("databaseSha256"),
        "manifest.baseline.databaseSha256",
    ).lower()
    if not SHA256_HEX.fullmatch(baseline_sha):
        raise ValueError("manifest.baseline.databaseSha256 muss ein SHA-256 sein")
    if _integer(baseline.get("sizeBytes"), "manifest.baseline.sizeBytes") < 1:
        raise ValueError("manifest.baseline.sizeBytes muss positiv sein")
    candidate_selection = _object(
        root.get("candidateSelection"),
        "manifest.candidateSelection",
    )
    if set(candidate_selection) != {
        "file",
        "sha256",
        "entries",
        "sealId",
        "sealedAt",
        "freezeOrder",
    }:
        raise ValueError(
            "manifest.candidateSelection besitzt nicht das erwartete exakte Schema"
        )
    selection_file = _text(
        candidate_selection.get("file"),
        "manifest.candidateSelection.file",
    )
    if selection_file != CANDIDATE_SELECTION_FILE:
        raise ValueError(
            f"manifest.candidateSelection.file muss {CANDIDATE_SELECTION_FILE} sein"
        )
    if (
        candidate_selection.get("freezeOrder")
        != "single-use-seal-before-random-split-v1"
    ):
        raise ValueError(
            "Candidate-Auswahl wurde nicht nach dem verpflichtenden "
            "Pre-Holdout-Verfahren eingefroren"
        )
    selection_entries = _integer(
        candidate_selection.get("entries"),
        "manifest.candidateSelection.entries",
    )
    if selection_entries < 1:
        raise ValueError("manifest.candidateSelection.entries muss positiv sein")
    expected_selection_sha = _text(
        candidate_selection.get("sha256"),
        "manifest.candidateSelection.sha256",
    ).lower()
    if not SHA256_HEX.fullmatch(expected_selection_sha):
        raise ValueError(
            "manifest.candidateSelection.sha256 muss ein SHA-256-Hexwert sein"
        )
    seal_id = _text(
        candidate_selection.get("sealId"),
        "manifest.candidateSelection.sealId",
    ).lower()
    if not SHA256_HEX.fullmatch(seal_id):
        raise ValueError("manifest.candidateSelection.sealId muss 64 Hexzeichen haben")
    _text(
        candidate_selection.get("sealedAt"),
        "manifest.candidateSelection.sealedAt",
    )
    selection_path = manifest_path.parent / CANDIDATE_SELECTION_FILE
    try:
        selection_stat = selection_path.lstat()
    except OSError as exc:
        raise ValueError("eingefrorene Candidate-Auswahl fehlt") from exc
    if (
        not stat.S_ISREG(selection_stat.st_mode)
        or selection_stat.st_size > MAX_CANDIDATE_SELECTION_BYTES
    ):
        raise ValueError(
            "eingefrorene Candidate-Auswahl muss eine reguläre Datei bis 16 MiB sein"
        )
    if _sha256(selection_path) != expected_selection_sha:
        raise ValueError(
            "Candidate-Auswahl-SHA-256 passt nicht zum Dataset-Manifest"
        )
    files = _object(root.get("files"), "manifest.files")
    frozen_query_bytes: dict[str, bytes] = {}
    for frozen_split, expected_name in (
        ("dev", "dev.jsonl"),
        ("holdout", "holdout.jsonl"),
    ):
        frozen_entry = _object(
            files.get(frozen_split),
            f"manifest.files.{frozen_split}",
        )
        if (
            _text(
                frozen_entry.get("file"),
                f"manifest.files.{frozen_split}.file",
            )
            != expected_name
        ):
            raise ValueError(
                f"manifest.files.{frozen_split}.file muss {expected_name} sein"
            )
        frozen_path = manifest_path.parent / expected_name
        try:
            frozen_stat = frozen_path.lstat()
        except OSError as exc:
            raise ValueError(f"eingefrorene Datei {expected_name} fehlt") from exc
        if (
            not stat.S_ISREG(frozen_stat.st_mode)
            or frozen_stat.st_size > MAX_FROZEN_QUERY_BYTES
        ):
            raise ValueError(
                f"{expected_name} muss eine reguläre Datei bis 64 MiB sein"
            )
        frozen_bytes = frozen_path.read_bytes()
        frozen_sha = _text(
            frozen_entry.get("sha256"),
            f"manifest.files.{frozen_split}.sha256",
        ).lower()
        if not SHA256_HEX.fullmatch(frozen_sha):
            raise ValueError(
                f"manifest.files.{frozen_split}.sha256 muss ein SHA-256-Hexwert sein"
            )
        if hashlib.sha256(frozen_bytes).hexdigest() != frozen_sha:
            raise ValueError(
                f"{expected_name}-SHA-256 passt nicht zum Dataset-Manifest"
            )
        frozen_query_bytes[frozen_split] = frozen_bytes
    observed_dataset_sha = hashlib.sha256(
        frozen_query_bytes["dev"]
        + b"\0"
        + frozen_query_bytes["holdout"]
        + b"\0"
        + selection_path.read_bytes()
    ).hexdigest()
    if observed_dataset_sha != dataset_sha:
        raise ValueError(
            "manifest.datasetSha256 passt nicht zu dev, holdout und Candidate-Auswahl"
        )
    split_values = {query.split for query in queries}
    if len(split_values) != 1:
        raise ValueError("gemischte Query-Splits sind nicht zulässig")
    actual_split = next(iter(split_values))
    if requested_split != "all" and requested_split != actual_split:
        raise ValueError(
            f"angeforderter Split {requested_split!r} passt nicht zur Datei ({actual_split!r})"
        )
    entry = _object(files.get(actual_split), f"manifest.files.{actual_split}")
    if _text(entry.get("file"), f"manifest.files.{actual_split}.file") != query_path.name:
        raise ValueError("Query-Dateiname passt nicht zum Dataset-Manifest")
    actual_sha = _sha256(query_path)
    expected_sha = _text(
        entry.get("sha256"),
        f"manifest.files.{actual_split}.sha256",
    ).lower()
    if not SHA256_HEX.fullmatch(expected_sha):
        raise ValueError(
            f"manifest.files.{actual_split}.sha256 muss ein SHA-256-Hexwert sein"
        )
    if actual_sha != expected_sha:
        raise ValueError("Query-SHA-256 passt nicht zum Dataset-Manifest")
    if _integer(entry.get("queries"), f"manifest.files.{actual_split}.queries") != len(queries):
        raise ValueError("Query-Anzahl passt nicht zum Dataset-Manifest")

    counts = _object(root.get("counts"), "manifest.counts")
    if _integer(counts.get(actual_split), f"manifest.counts.{actual_split}") != len(queries):
        raise ValueError("Split-Anzahl passt nicht zum Dataset-Manifest")
    answerable = sum(query.answerable for query in queries)
    no_answer = len(queries) - answerable
    if actual_split == "holdout":
        if _integer(counts.get("holdoutAnswerable"), "manifest.counts.holdoutAnswerable") != answerable:
            raise ValueError("Holdout-Answerable-Anzahl passt nicht zum Dataset-Manifest")
        if _integer(counts.get("holdoutNoAnswer"), "manifest.counts.holdoutNoAnswer") != no_answer:
            raise ValueError("Holdout-No-Answer-Anzahl passt nicht zum Dataset-Manifest")

    return root, actual_split


def query_set_metadata(
    path: Path,
    queries: list[Query],
    manifest_path: Optional[Path] = None,
    manifest: Optional[dict] = None,
) -> dict:
    result = {
        "file": path.name,
        "sha256": _sha256(path),
        "datasetSchemaVersion": SCHEMA_VERSION,
        "sourceSchemaVersions": sorted(
            {query.source_schema_version for query in queries}
        ),
    }
    if manifest_path is not None and manifest is not None:
        result.update(
            {
                "manifestFile": manifest_path.name,
                "manifestSha256": _sha256(manifest_path),
                "datasetId": manifest.get("datasetId"),
                "datasetSha256": manifest.get("datasetSha256"),
                "sourceDump": manifest.get("sourceDump"),
                "candidateSelection": manifest.get("candidateSelection"),
            }
        )
    return result


def fetch_candidate_manifest(
    base_url: str,
    endpoint: str,
    dataset_manifest: dict,
    local_verification: dict,
) -> tuple[dict, dict]:
    """Bindet Runtime, Vollprüfung und Query-Freeze an genau dasselbe Pack."""
    if local_verification.get("releaseEligible") is not True:
        raise ValueError(
            "Candidate-Pack besitzt keinen vollständigen Releasebeweis"
        )
    if local_verification.get("artifactVerified") is not True:
        raise ValueError("Candidate-Pack wurde nicht vollständig als Artefakt geprüft")
    if local_verification.get("sourceAuthorityVerified") is not True:
        raise ValueError("Candidate-Pack-Quelle wurde nicht online autoritativ geprüft")
    if local_verification.get("sourceDumpBytesVerified") is not True:
        raise ValueError(
            "Candidate-Pack wurde nicht gegen die vollständigen Quelldump-Bytes geprüft"
        )
    if local_verification.get("logicalContentVerified") is not True:
        raise ValueError(
            "Candidate-Pack-Inhalt wurde nicht deterministisch gegen den Quelldump geprüft"
        )
    if local_verification.get("ftsIntegrityVerified") is not True:
        raise ValueError("Candidate-Pack besitzt keinen vollständigen FTS-Integritätsbeleg")
    if local_verification.get("byteRebuildVerified") is not True:
        raise ValueError(
            "Candidate-Pack besitzt keinen bytegenauen Wiederaufbaubeleg"
        )
    local_pack_id = _text(
        local_verification.get("packId"),
        "local verification.packId",
    )
    local_database_sha256 = _text(
        local_verification.get("sha256"),
        "local verification.sha256",
    ).lower()
    if not SHA256_HEX.fullmatch(local_database_sha256):
        raise ValueError("local verification.sha256 ist kein SHA-256")
    local_manifest_sha256 = _text(
        local_verification.get("manifestSha256"),
        "local verification.manifestSha256",
    ).lower()
    if not SHA256_HEX.fullmatch(local_manifest_sha256):
        raise ValueError("local verification.manifestSha256 ist kein SHA-256")
    local_selection_sha256 = _text(
        local_verification.get("selectionSha256"),
        "local verification.selectionSha256",
    ).lower()
    if not SHA256_HEX.fullmatch(local_selection_sha256):
        raise ValueError("local verification.selectionSha256 ist kein SHA-256")
    source_authority_sha256 = _text(
        local_verification.get("sourceAuthoritySha256"),
        "local verification.sourceAuthoritySha256",
    ).lower()
    if not SHA256_HEX.fullmatch(source_authority_sha256):
        raise ValueError(
            "local verification.sourceAuthoritySha256 ist kein SHA-256"
        )
    logical_records_sha256 = _text(
        local_verification.get("logicalRecordsSha256"),
        "local verification.logicalRecordsSha256",
    ).lower()
    if not SHA256_HEX.fullmatch(logical_records_sha256):
        raise ValueError(
            "local verification.logicalRecordsSha256 ist kein SHA-256"
        )
    canonical_database_sha256 = _text(
        local_verification.get("canonicalDatabaseSha256"),
        "local verification.canonicalDatabaseSha256",
    ).lower()
    if not SHA256_HEX.fullmatch(canonical_database_sha256):
        raise ValueError(
            "local verification.canonicalDatabaseSha256 ist kein SHA-256"
        )
    if canonical_database_sha256 != local_database_sha256:
        raise ValueError(
            "Bytegenauer Wiederaufbau und tatsächlich geprüfte SQLite haben "
            "andere SHA-256"
        )
    local_manifest_file = _text(
        local_verification.get("manifestFile"),
        "local verification.manifestFile",
    )
    if Path(local_manifest_file).name != local_manifest_file:
        raise ValueError("local verification.manifestFile darf keinen Pfad enthalten")
    local_source_dump = _object(
        local_verification.get("sourceDump"),
        "local verification.sourceDump",
    )
    local_dump_url = _text(
        local_source_dump.get("url"),
        "local verification.sourceDump.url",
    )
    local_dump_sha256 = _text(
        local_source_dump.get("sha256"),
        "local verification.sourceDump.sha256",
    ).lower()
    if not SHA256_HEX.fullmatch(local_dump_sha256):
        raise ValueError("local verification.sourceDump.sha256 ist kein SHA-256")

    local_url = validate_loopback_url(base_url, "Candidate-URL")
    local_endpoint = validate_endpoint(endpoint, "Candidate-Manifest-Endpoint")
    opener = proxy_free_no_redirect_opener()
    try:
        with opener.open(f"{local_url}{local_endpoint}", timeout=10.0) as response:
            candidate = json.loads(response.read().decode("utf-8"))
    except (
        OSError,
        urllib.error.URLError,
        urllib.error.HTTPError,
        json.JSONDecodeError,
    ) as exc:
        raise ValueError(f"Candidate-Manifest nicht lesbar: {exc}") from exc
    candidate = _object(candidate, "candidate manifest")
    if candidate.get("status") != "manifest-content-verified":
        raise ValueError(
            "Candidate-Runtime ist nicht als manifest-content-verified gestartet"
        )
    runtime_code = validate_runtime_code(
        candidate.get("runtimeCode"),
        "candidate manifest.runtimeCode",
    )
    runtime_verification = _object(
        candidate.get("verification"),
        "candidate manifest.verification",
    )
    if runtime_verification.get("contentSha256Verified") is not True:
        raise ValueError(
            "Candidate-Runtime hat den tatsächlichen DB-Inhalt nicht vollständig gehasht"
        )
    runtime_database_sha256 = _text(
        runtime_verification.get("actualDatabaseSha256"),
        "candidate manifest.verification.actualDatabaseSha256",
    ).lower()
    if not SHA256_HEX.fullmatch(runtime_database_sha256):
        raise ValueError(
            "candidate manifest.verification.actualDatabaseSha256 ist kein SHA-256"
        )
    pack_id = _text(candidate.get("packId"), "candidate manifest.packId")
    if candidate.get("releaseStatus") != "release-candidate":
        raise ValueError("Candidate-Runtime meldet keinen release-candidate")
    if local_verification.get("releaseStatus") != "release-candidate":
        raise ValueError("lokaler Candidate meldet keinen release-candidate")
    if pack_id != local_pack_id:
        raise ValueError("Candidate-Runtime und lokal geprüftes Pack haben andere packId")
    source = _object(candidate.get("source"), "candidate manifest.source")
    candidate_dump_url = _text(source.get("url"), "candidate manifest.source.url")
    candidate_dump = _object(source.get("dump"), "candidate manifest.source.dump")
    candidate_dump_sha256 = _text(
        candidate_dump.get("sha256"),
        "candidate manifest.source.dump.sha256",
    ).lower()
    if not SHA256_HEX.fullmatch(candidate_dump_sha256):
        raise ValueError("candidate manifest.source.dump.sha256 ist kein SHA-256")
    database = _object(candidate.get("database"), "candidate manifest.database")
    candidate_database_sha256 = _text(
        database.get("sha256"),
        "candidate manifest.database.sha256",
    ).lower()
    if not SHA256_HEX.fullmatch(candidate_database_sha256):
        raise ValueError("candidate manifest.database.sha256 ist kein SHA-256")
    if candidate_database_sha256 != local_database_sha256:
        raise ValueError(
            "Candidate-Manifest und vollständig geprüfte SQLite-Datei haben andere SHA-256"
        )
    if runtime_database_sha256 != local_database_sha256:
        raise ValueError(
            "Tatsächliche Runtime-DB und vollständig geprüfte SQLite-Datei "
            "haben andere SHA-256"
        )
    if (
        candidate_dump_url != local_dump_url
        or candidate_dump_sha256 != local_dump_sha256
    ):
        raise ValueError(
            "Candidate-Runtime und vollständig geprüftes Pack binden nicht "
            "dieselbe sourceDump-URL/SHA-256"
        )
    source_dump = _object(dataset_manifest.get("sourceDump"), "manifest.sourceDump")
    frozen_dump_url = _text(source_dump.get("url"), "manifest.sourceDump.url")
    frozen_dump_sha256 = _text(
        source_dump.get("sha256"),
        "manifest.sourceDump.sha256",
    ).lower()
    if candidate_dump_url != frozen_dump_url:
        raise ValueError(
            "Candidate-Pack und Query-Dataset stammen nicht aus demselben sourceDump"
        )
    if candidate_dump_sha256 != frozen_dump_sha256:
        raise ValueError(
            "Candidate-Pack und Query-Dataset tragen nicht denselben sourceDump-SHA-256"
        )
    frozen_selection = _object(
        dataset_manifest.get("candidateSelection"),
        "manifest.candidateSelection",
    )
    frozen_selection_sha256 = _text(
        frozen_selection.get("sha256"),
        "manifest.candidateSelection.sha256",
    ).lower()
    if local_selection_sha256 != frozen_selection_sha256:
        raise ValueError(
            "Candidate-Pack verwendet nicht die vor dem Holdout-Split "
            "eingefrorene Titel-/Alias-Auswahl"
        )
    canonical = json.dumps(
        candidate,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    public_binding = {
        "packId": pack_id,
        "runtimeManifestSha256": hashlib.sha256(canonical).hexdigest(),
        "localManifest": {
            "file": local_manifest_file,
            "sha256": local_manifest_sha256,
        },
        "databaseSha256": local_database_sha256,
        "runtimeActualDatabaseSha256": runtime_database_sha256,
        "runtimeCode": runtime_code,
        "releaseStatus": "release-candidate",
        "artifactVerified": True,
        "sourceAuthorityVerified": True,
        "sourceAuthoritySha256": source_authority_sha256,
        "sourceDumpBytesVerified": True,
        "logicalContentVerified": True,
        "logicalRecordsSha256": logical_records_sha256,
        "ftsIntegrityVerified": True,
        "byteRebuildVerified": True,
        "canonicalDatabaseSha256": canonical_database_sha256,
        "selectionSha256": local_selection_sha256,
        "sourceDump": source_dump,
    }
    # /v1/search liefert bewusst nur die Pack-Summary, nicht die zusätzliche
    # Source-Selbstauskunft des Provenienz-Endpunkts. Für den per-Probe-Drift-
    # Guard wird deshalb exakt dieser stabile Suchvertrag weitergereicht.
    search_pack_contract = {
        key: value
        for key, value in candidate.items()
        if key != "runtimeCode"
    }
    return search_pack_contract, public_binding


def verify_candidate_artifacts(
    candidate_url: Optional[str],
    manifest_argument: Optional[Path],
    source_dump_argument: Optional[Path],
) -> tuple[Optional[Path], Optional[dict]]:
    """Prüft das lokale Candidate-Pack und seinen vollständigen Quelldump."""

    if candidate_url is None:
        if manifest_argument is not None or source_dump_argument is not None:
            raise ValueError(
                "--candidate-pack-manifest/--candidate-source-dump sind ohne "
                "--candidate-url nicht zulässig"
            )
        return None, None
    if manifest_argument is None:
        raise ValueError(
            "--candidate-pack-manifest ist für einen Candidate verpflichtend"
        )
    if source_dump_argument is None:
        raise ValueError(
            "--candidate-source-dump ist für einen Candidate verpflichtend"
        )

    manifest_path = manifest_argument.expanduser().resolve()
    source_dump_path = source_dump_argument.expanduser().resolve()
    verification = verify_knowledge_pack(
        manifest_path,
        fast=False,
        verify_source_online=True,
        source_dump_path=source_dump_path,
    )
    return manifest_path, {
        **verification,
        "manifestFile": manifest_path.name,
    }


def evidence_rank(query: Query, hits: list[dict]) -> Optional[int]:
    """Findet Evidenz nur im Goldartikel, dem der Span zugeordnet wurde."""
    for index, hit in enumerate(hits, 1):
        hit_title = normalize_title(str(hit.get("title", "")))
        hit_text = normalize_evidence(
            str(hit.get("summary") or hit.get("extract", ""))
        )
        for passage in query.gold_passages:
            if normalize_title(passage.title) != hit_title:
                continue
            if any(
                normalize_evidence(span) in hit_text
                for span in passage.evidence
            ):
                return index
    return None


def request_probe(
    base_url: str,
    endpoint: str,
    query: Query,
    limit: int,
    bm25_max: float,
    *,
    repeat: int = 0,
    expected_candidate_manifest: Optional[dict] = None,
) -> Probe:
    local_url = validate_loopback_url(base_url, "Bridge-URL")
    local_endpoint = validate_endpoint(endpoint, "Bridge-Endpoint")
    params = urllib.parse.urlencode(
        {
            "q": query.search_query,
            "fact_query": query.query,
            "limit": limit,
            "extract_max_chars": 800,
            "summary_sentences": 0,
        }
    )
    url = f"{local_url}{local_endpoint}?{params}"
    start = time.perf_counter()
    try:
        opener = proxy_free_no_redirect_opener()
        with opener.open(url, timeout=10.0) as response:
            body = json.loads(response.read().decode("utf-8"))
        if not isinstance(body, dict):
            raise ValueError("Bridge-Antwort muss ein JSON-Objekt sein")
        if expected_candidate_manifest is not None:
            response_pack = _object(body.get("pack"), "candidate response.pack")
            if any(
                response_pack.get(key) != value
                for key, value in expected_candidate_manifest.items()
            ):
                raise ValueError(
                    "Candidate-Pack-Public-Summary wechselte während des Benchmarks"
                )
        raw_hits = body.get("hits", [])
        if not isinstance(raw_hits, list) or any(
            not isinstance(hit, dict) for hit in raw_hits
        ):
            raise ValueError("Bridge-Antwort hits muss eine Liste von Objekten sein")
        hits = []
        for hit in raw_hits:
            score = float(hit.get("bm25Score", 0.0))
            if not math.isfinite(score):
                raise ValueError("Bridge-Antwort enthält keinen endlichen BM25-Score")
            if score > bm25_max:
                continue
            if (
                query.exact_title_required
                and normalize_title(str(hit.get("title", "")))
                != normalize_title(query.search_query)
            ):
                continue
            hits.append(hit)
        titles = tuple(str(hit.get("title", "")).strip() for hit in hits)
        scores = tuple(float(hit.get("bm25Score", 0.0)) for hit in hits)
        matched_evidence_rank = evidence_rank(query, hits)
        extract_chars = sum(len(str(hit.get("extract", ""))) for hit in hits)
        bridge_duration = body.get("durationMs")
        if bridge_duration is not None and (
            not isinstance(bridge_duration, int)
            or isinstance(bridge_duration, bool)
        ):
            raise ValueError("Bridge-Antwort durationMs muss eine Ganzzahl sein")
        return Probe(
            query_id=query.id,
            duration_ms=(time.perf_counter() - start) * 1000.0,
            bridge_duration_ms=bridge_duration,
            titles=titles,
            scores=scores,
            evidence_rank=matched_evidence_rank,
            extracts_chars=extract_chars,
            error=None,
            repeat=repeat,
        )
    except (
        OSError,
        urllib.error.URLError,
        urllib.error.HTTPError,
        json.JSONDecodeError,
        TypeError,
        ValueError,
    ) as exc:
        return Probe(
            query_id=query.id,
            duration_ms=(time.perf_counter() - start) * 1000.0,
            bridge_duration_ms=None,
            titles=(),
            scores=(),
            evidence_rank=None,
            extracts_chars=0,
            error=str(exc),
            repeat=repeat,
        )


def percentile(values: list[float], quantile: float) -> Optional[float]:
    if not values:
        return None
    ordered = sorted(values)
    rank = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[rank]


def consistent_probe_by_query(
    queries: Sequence[Query],
    probes: Sequence[Probe],
) -> dict[str, Probe]:
    """Retrieval-Repeats müssen je Query identisch sein; kein stilles Last-write-wins."""
    grouped: dict[str, list[Probe]] = defaultdict(list)
    for probe in probes:
        grouped[probe.query_id].append(probe)
    expected_ids = {query.id for query in queries}
    if set(grouped) != expected_ids:
        raise ValueError("Probe-IDs passen nicht exakt zum Query-Satz")
    result: dict[str, Probe] = {}
    for query_id, group in grouped.items():
        repeats = [probe.repeat for probe in group]
        if len(repeats) != len(set(repeats)):
            raise ValueError(f"doppelter Repeat für Query {query_id!r}")
        signatures = {
            (
                probe.titles,
                probe.scores,
                probe.evidence_rank,
                probe.extracts_chars,
                probe.error is not None,
            )
            for probe in group
        }
        if len(signatures) != 1:
            raise ValueError(
                f"nichtdeterministisches Retrieval über Repeats für Query {query_id!r}"
            )
        result[query_id] = min(group, key=lambda probe: probe.repeat)
    return result


def metrics(
    queries: list[Query],
    probes: list[Probe],
    *,
    include_strata: bool = True,
) -> dict:
    by_id = consistent_probe_by_query(queries, probes)
    answerable = [query for query in queries if query.answerable]
    unanswerable = [query for query in queries if not query.answerable]

    def recalled(query: Query, k: int) -> bool:
        returned = {normalize_title(title) for title in by_id[query.id].titles[:k]}
        gold = {normalize_title(title) for title in query.gold_titles}
        return bool(returned & gold)

    recall_at_1 = (
        sum(recalled(query, 1) for query in answerable) / len(answerable)
        if answerable
        else None
    )
    recall_at_3 = (
        sum(recalled(query, 3) for query in answerable) / len(answerable)
        if answerable
        else None
    )
    passage_recall_at_1 = (
        sum(by_id[query.id].evidence_rank == 1 for query in answerable) / len(answerable)
        if answerable
        else None
    )
    passage_recall_at_3 = (
        sum(
            by_id[query.id].evidence_rank is not None
            and by_id[query.id].evidence_rank <= 3
            for query in answerable
        )
        / len(answerable)
        if answerable
        else None
    )
    false_retrieval_candidates = (
        sum(bool(by_id[query.id].titles) for query in unanswerable) / len(unanswerable)
        if unanswerable
        else None
    )
    durations = [probe.duration_ms for probe in probes if probe.error is None]
    result = {
        "n": len(queries),
        "answerableN": len(answerable),
        "noAnswerN": len(unanswerable),
        "recallAt1": recall_at_1,
        "recallAt3": recall_at_3,
        "passageRecallAt1": passage_recall_at_1,
        "passageRecallAt3": passage_recall_at_3,
        "falseRetrievalCandidateRate": false_retrieval_candidates,
        "p50WallMs": statistics.median(durations) if durations else None,
        "p95WallMs": percentile(durations, 0.95),
        "meanExtractChars": (
            statistics.mean(probe.extracts_chars for probe in probes)
            if probes
            else None
        ),
        "errors": sum(probe.error is not None for probe in probes),
    }
    if include_strata:
        result["byStratum"] = {
            stratum: metrics(
                [query for query in queries if query.stratum == stratum],
                [
                    probe
                    for probe in probes
                    if probe.query_id
                    in {query.id for query in queries if query.stratum == stratum}
                ],
                include_strata=False,
            )
            for stratum in sorted({query.stratum for query in queries})
        }
    return result


def paired_statistics(
    queries: Sequence[Query],
    baseline_probes: Sequence[Probe],
    candidate_probes: Sequence[Probe],
) -> dict:
    baseline = consistent_probe_by_query(queries, baseline_probes)
    candidate = consistent_probe_by_query(queries, candidate_probes)
    improved = 0
    regressed = 0
    tied = 0
    for query in (query for query in queries if query.answerable):
        before = baseline[query.id].evidence_rank
        after = candidate[query.id].evidence_rank
        before_hit = before is not None and before <= 3
        after_hit = after is not None and after <= 3
        if after_hit and not before_hit:
            improved += 1
        elif before_hit and not after_hit:
            regressed += 1
        else:
            tied += 1
    discordant = improved + regressed
    exact_one_sided_p = (
        sum(math.comb(discordant, index) for index in range(improved, discordant + 1))
        / (2**discordant)
        if discordant
        else 1.0
    )

    baseline_latency = {
        (probe.query_id, probe.repeat): probe.duration_ms
        for probe in baseline_probes
        if probe.error is None
    }
    candidate_latency = {
        (probe.query_id, probe.repeat): probe.duration_ms
        for probe in candidate_probes
        if probe.error is None
    }
    paired_keys = sorted(set(baseline_latency) & set(candidate_latency))
    deltas = [
        candidate_latency[key] - baseline_latency[key]
        for key in paired_keys
    ]
    mean_delta = statistics.mean(deltas) if deltas else None
    if len(deltas) >= 2:
        assert mean_delta is not None
        half_width = 1.96 * statistics.stdev(deltas) / math.sqrt(len(deltas))
        mean_ci = [mean_delta - half_width, mean_delta + half_width]
    else:
        mean_ci = [None, None]
    return {
        "passageRecallAt3": {
            "improved": improved,
            "regressed": regressed,
            "tied": tied,
            "discordant": discordant,
            "exactOneSidedP": exact_one_sided_p,
        },
        "wallMs": {
            "pairs": len(deltas),
            "meanDelta": mean_delta,
            "meanDeltaCi95Normal": mean_ci,
            "medianDelta": statistics.median(deltas) if deltas else None,
        },
    }


def run_interleaved(
    queries: Sequence[Query],
    *,
    baseline_url: str,
    baseline_endpoint: str,
    candidate_url: Optional[str],
    candidate_endpoint: str,
    limit: int,
    bm25_max: float,
    warmup_rounds: int,
    repeats: int,
    candidate_manifest: Optional[dict] = None,
) -> tuple[list[Probe], list[Probe]]:
    """Counterbalanced A/B schedule: Query-Reihenfolge bleibt eingefroren."""
    if warmup_rounds < 0 or repeats < 1:
        raise ValueError("warmup muss >=0 und repeats >=1 sein")
    variants = ["baseline"] + (["candidate"] if candidate_url else [])

    def execute(variant: str, query: Query, repeat: int) -> Probe:
        if variant == "baseline":
            return request_probe(
                baseline_url,
                baseline_endpoint,
                query,
                limit,
                bm25_max,
                repeat=repeat,
            )
        return request_probe(
            candidate_url or "",
            candidate_endpoint,
            query,
            limit,
            bm25_max,
            repeat=repeat,
            expected_candidate_manifest=candidate_manifest,
        )

    for warmup in range(warmup_rounds):
        for query_index, query in enumerate(queries):
            ordered = (
                variants
                if (warmup + query_index) % 2 == 0
                else list(reversed(variants))
            )
            for variant in ordered:
                execute(variant, query, -(warmup + 1))

    baseline: list[Probe] = []
    candidate: list[Probe] = []
    for repeat in range(repeats):
        for query_index, query in enumerate(queries):
            ordered = (
                variants
                if (repeat + query_index) % 2 == 0
                else list(reversed(variants))
            )
            for variant in ordered:
                probe = execute(variant, query, repeat)
                (baseline if variant == "baseline" else candidate).append(probe)
    return baseline, candidate


def validate_production_thresholds(
    *,
    min_recall_gain: float,
    max_added_p95_ms: float,
    minimum_n: int,
    minimum_answerable_n: int,
    minimum_unanswerable_n: int,
    max_false_retrieval_candidate_rate: float = (
        PRODUCTION_MAX_FALSE_RETRIEVAL_CANDIDATE_RATE
    ),
) -> None:
    numeric_thresholds = (
        min_recall_gain,
        max_added_p95_ms,
        max_false_retrieval_candidate_rate,
    )
    if not all(math.isfinite(value) for value in numeric_thresholds):
        raise ValueError("Production-Schwellen müssen endliche Zahlen sein")
    if min_recall_gain < PRODUCTION_MIN_RECALL_GAIN:
        raise ValueError("Production-min-recall-gain darf nicht abgesenkt werden")
    if max_added_p95_ms > PRODUCTION_MAX_ADDED_P95_MS:
        raise ValueError("Production-p95-Budget darf nicht aufgeweicht werden")
    if minimum_n < PRODUCTION_MIN_HOLDOUT_N:
        raise ValueError("Production-minimum-holdout-n darf nicht abgesenkt werden")
    if minimum_answerable_n < PRODUCTION_MIN_HOLDOUT_ANSWERABLE:
        raise ValueError("Production-minimum-answerable darf nicht abgesenkt werden")
    if minimum_unanswerable_n < PRODUCTION_MIN_HOLDOUT_UNANSWERABLE:
        raise ValueError("Production-minimum-unanswerable darf nicht abgesenkt werden")
    if (
        max_false_retrieval_candidate_rate
        > PRODUCTION_MAX_FALSE_RETRIEVAL_CANDIDATE_RATE
    ):
        raise ValueError("Production-False-Positive-Grenze darf nicht aufgeweicht werden")


def validate_production_execution_contract(
    *,
    limit: int,
    bm25_max: float,
    warmup_rounds: int,
    repeats: int,
    baseline_endpoint: str,
    candidate_endpoint: str,
    candidate_manifest_endpoint: str,
) -> None:
    """Verhindert einen nach Ergebnislage optimierten Retrieval-Vertrag."""

    if limit != PRODUCTION_LIMIT:
        raise ValueError(f"Production-Bench verlangt limit={PRODUCTION_LIMIT}")
    if not math.isfinite(bm25_max) or bm25_max != PRODUCTION_BM25_MAX:
        raise ValueError(
            f"Production-Bench verlangt bm25-max={PRODUCTION_BM25_MAX}"
        )
    if warmup_rounds != PRODUCTION_MIN_WARMUP_ROUNDS:
        raise ValueError(
            f"Production-Bench verlangt warmup={PRODUCTION_MIN_WARMUP_ROUNDS}"
        )
    if repeats != PRODUCTION_MIN_REPEATS:
        raise ValueError(
            f"Production-Bench verlangt repeats={PRODUCTION_MIN_REPEATS}"
        )
    expected_endpoints = {
        "Baseline": (baseline_endpoint, PRODUCTION_BASELINE_ENDPOINT),
        "Candidate": (candidate_endpoint, PRODUCTION_CANDIDATE_ENDPOINT),
        "Candidate-Manifest": (
            candidate_manifest_endpoint,
            PRODUCTION_CANDIDATE_MANIFEST_ENDPOINT,
        ),
    }
    for label, (observed, expected) in expected_endpoints.items():
        if observed != expected:
            raise ValueError(
                f"Production-Bench verlangt {label}-Endpoint {expected}"
            )


RUNNER_CODE_PATHS = (
    "tools/knowledge-bench/run_bench.py",
    "tools/knowledge-bench/dataset_schema.py",
    "tools/knowledge-pack/verify_pack.py",
    "tools/knowledge-pack/build_pack_from_dump.py",
    "tools/knowledge-pack/build_pack.py",
    "sidecars/knowledge/pack_manifest.py",
    "sidecars/knowledge/server.py",
)


def runner_code_binding() -> dict:
    """Bindet die entscheidungsrelevante First-party-Closure an Git-Bytes."""

    repo_root = SCRIPT_DIR.parents[1]
    try:
        commit = subprocess.run(
            ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValueError("Production-Bench kann keinen Git-Commit binden") from exc
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Production-Bench-Commit ist kein vollständiger Git-SHA-1")

    files: dict[str, str] = {}
    for relative in RUNNER_CODE_PATHS:
        local_path = repo_root / relative
        try:
            current = local_path.read_bytes()
            committed = subprocess.run(
                ["git", "-C", str(repo_root), "show", f"{commit}:{relative}"],
                check=True,
                capture_output=True,
            ).stdout
        except (OSError, subprocess.CalledProcessError) as exc:
            raise ValueError(
                f"Production-Bench-Quelle {relative} ist nicht commit-gebunden"
            ) from exc
        current_sha = hashlib.sha256(current).hexdigest()
        if hashlib.sha256(committed).hexdigest() != current_sha:
            raise ValueError(
                f"Production-Bench-Quelle {relative} besitzt uncommitted Bytes"
            )
        files[relative] = current_sha
    contract = {"commit": commit, "files": files}
    canonical = json.dumps(
        contract,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return {
        **contract,
        "closureSha256": hashlib.sha256(canonical).hexdigest(),
    }


def _delta(after: object, before: object) -> Optional[float]:
    if (
        isinstance(after, (int, float))
        and not isinstance(after, bool)
        and isinstance(before, (int, float))
        and not isinstance(before, bool)
        and math.isfinite(float(after))
        and math.isfinite(float(before))
    ):
        return float(after) - float(before)
    return None


def gate(
    baseline: dict,
    candidate: dict,
    *,
    min_recall_gain: float,
    max_added_p95_ms: float,
    minimum_n: int,
    minimum_answerable_n: int,
    minimum_unanswerable_n: int,
    holdout_only: bool,
    max_false_retrieval_candidate_rate: float = (
        PRODUCTION_MAX_FALSE_RETRIEVAL_CANDIDATE_RATE
    ),
    exact_recall_p: Optional[float] = None,
) -> dict:
    validate_production_thresholds(
        min_recall_gain=min_recall_gain,
        max_added_p95_ms=max_added_p95_ms,
        minimum_n=minimum_n,
        minimum_answerable_n=minimum_answerable_n,
        minimum_unanswerable_n=minimum_unanswerable_n,
        max_false_retrieval_candidate_rate=max_false_retrieval_candidate_rate,
    )
    recall_gain = _delta(
        candidate.get("passageRecallAt3"),
        baseline.get("passageRecallAt3"),
    )
    false_retrieval_delta = _delta(
        candidate.get("falseRetrievalCandidateRate"),
        baseline.get("falseRetrievalCandidateRate"),
    )
    added_p95 = _delta(candidate.get("p95WallMs"), baseline.get("p95WallMs"))
    candidate_false_rate = candidate.get("falseRetrievalCandidateRate")
    false_rate_is_finite = (
        isinstance(candidate_false_rate, (int, float))
        and not isinstance(candidate_false_rate, bool)
        and math.isfinite(float(candidate_false_rate))
    )
    checks = {
        "holdoutOnly": holdout_only,
        "minimumHoldoutSize": (
            baseline["n"] >= minimum_n and candidate["n"] >= minimum_n
        ),
        "minimumAnswerableSize": (
            baseline["answerableN"] >= minimum_answerable_n
            and candidate["answerableN"] >= minimum_answerable_n
        ),
        "minimumNoAnswerSize": (
            baseline["noAnswerN"] >= minimum_unanswerable_n
            and candidate["noAnswerN"] >= minimum_unanswerable_n
        ),
        "zeroRequestErrors": baseline["errors"] == 0 and candidate["errors"] == 0,
        "passageRecallAt3Gain": (
            recall_gain is not None and recall_gain >= min_recall_gain
        ),
        "falseRetrievalCandidatesNotWorse": (
            false_retrieval_delta is not None and false_retrieval_delta <= 0.0
        ),
        "falsePositiveSafetyBoundary": (
            false_rate_is_finite
            and float(candidate_false_rate) <= max_false_retrieval_candidate_rate
        ),
        "p95Budget": (
            added_p95 is not None and added_p95 <= max_added_p95_ms
        ),
    }
    if exact_recall_p is not None:
        checks["pairedRecallExact"] = exact_recall_p <= PRODUCTION_EXACT_ALPHA
    return {
        "passed": all(checks.values()),
        "checks": checks,
        "thresholds": {
            "minimumN": minimum_n,
            "minimumAnswerableN": minimum_answerable_n,
            "minimumNoAnswerN": minimum_unanswerable_n,
            "minRecallAt3Gain": min_recall_gain,
            "maxAddedP95Ms": max_added_p95_ms,
            "maxFalseRetrievalCandidateRate": max_false_retrieval_candidate_rate,
            "pairedRecallExactAlpha": PRODUCTION_EXACT_ALPHA,
        },
        "observed": {
            "passageRecallAt3Gain": recall_gain,
            "falseRetrievalCandidateDelta": false_retrieval_delta,
            "addedP95Ms": added_p95,
        },
    }


def _format_number(value: object, digits: int = 3, suffix: str = "") -> str:
    if (
        not isinstance(value, (int, float))
        or isinstance(value, bool)
        or not math.isfinite(float(value))
    ):
        return "n/a"
    return f"{float(value):.{digits}f}{suffix}"


def _markdown(report: dict) -> str:
    lines = [
        "# Hoshi Knowledge Bench",
        "",
        f"- Split: `{report['split']}`",
        f"- Queries: {report['baseline']['metrics']['n']}",
        "",
        "| Variante | Titel R@3 | Passage R@1 | Passage R@3 | False Retrieval Candidate | p95 wall | Fehler |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for name in ("baseline", "candidate"):
        if name not in report:
            continue
        item = report[name]
        m = item["metrics"]
        lines.append(
            f"| {name} | {_format_number(m['recallAt3'])} | "
            f"{_format_number(m['passageRecallAt1'])} | "
            f"{_format_number(m['passageRecallAt3'])} | "
            f"{_format_number(m['falseRetrievalCandidateRate'])} | "
            f"{_format_number(m['p95WallMs'], 1, ' ms')} | "
            f"{m['errors']} |"
        )
    if "gate" in report:
        lines.extend(
            [
                "",
                f"## Gate: {'PASS' if report['gate']['passed'] else 'FAIL'}",
                "",
            ]
        )
        for name, passed in report["gate"]["checks"].items():
            lines.append(f"- {'PASS' if passed else 'FAIL'} — {name}")
    lines.extend(
        [
            "",
            "Dieses Ergebnis misst Retrieval, nicht die faktische Qualität der finalen Brain-Antwort.",
            "",
        ]
    )
    return "\n".join(lines)


def _write_private(path: Path, text: str) -> None:
    descriptor = os.open(
        path,
        os.O_WRONLY | os.O_CREAT | os.O_EXCL,
        0o600,
    )
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())


def _publish_directory_no_replace(temporary: Path, output: Path) -> None:
    """Publiziert ein fertiges Verzeichnis atomar, ohne ein Ziel zu ersetzen.

    Hoshi läuft auf macOS; Linux wird für lokale CI ebenfalls unterstützt. Auf
    anderen Plattformen brechen wir ehrlich ab, statt auf das racy
    ``exists()`` + ``replace()``-Muster zurückzufallen.
    """

    libc = ctypes.CDLL(None, use_errno=True)
    source = os.fsencode(temporary)
    destination = os.fsencode(output)
    if sys.platform == "darwin":
        rename = libc.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        rename.restype = ctypes.c_int
        result = rename(source, destination, 0x00000004)  # RENAME_EXCL
    elif sys.platform.startswith("linux"):
        rename = libc.renameat2
        rename.argtypes = [
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        ]
        rename.restype = ctypes.c_int
        result = rename(-100, source, -100, destination, 0x00000001)  # RENAME_NOREPLACE
    else:
        raise ValueError(
            "Atomarer No-replace-Report-Publish wird auf dieser Plattform nicht unterstützt"
        )
    if result != 0:
        error = ctypes.get_errno()
        if error in {errno.EEXIST, errno.ENOTEMPTY}:
            raise ValueError(
                "Report-Output existiert bereits; Bench-Reports werden nie überschrieben"
            )
        raise OSError(error, os.strerror(error), str(output))

    parent_fd = os.open(output.parent, os.O_RDONLY)
    try:
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def write_report_atomic(output: Path, report: dict) -> None:
    """Schreibt einen privaten Report als unveränderliches Verzeichnis."""
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent))
    os.chmod(temporary, 0o700)
    try:
        rendered = json.dumps(
            report,
            ensure_ascii=False,
            indent=2,
            allow_nan=False,
        ) + "\n"
        _write_private(temporary / "report.json", rendered)
        _write_private(temporary / "report.md", _markdown(report))
        _publish_directory_no_replace(temporary, output)
        os.chmod(output, 0o700)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queries", required=True, type=Path)
    parser.add_argument(
        "--manifest",
        type=Path,
        help="Freeze-Manifest (Default: manifest.json neben --queries)",
    )
    parser.add_argument("--baseline-url", required=True)
    parser.add_argument(
        "--baseline-database",
        type=Path,
        help=(
            "lokale Legacy-DB der Baseline; für Candidate-A/B zwingend und gegen "
            "den Selection-Seal zu hashen"
        ),
    )
    parser.add_argument("--baseline-endpoint", default="/search")
    parser.add_argument("--candidate-url")
    parser.add_argument("--candidate-endpoint", default="/v1/search")
    parser.add_argument("--candidate-manifest-endpoint", default="/v1/manifest")
    parser.add_argument(
        "--candidate-pack-manifest",
        type=Path,
        help=(
            "lokales manifest.json des laufenden Candidate-Packs; bei --candidate-url "
            "zwingend und vollständig plus online zu prüfen"
        ),
    )
    parser.add_argument(
        "--candidate-source-dump",
        type=Path,
        help=(
            "lokale vollständige Quelldump-Datei des Candidate-Packs; zusammen mit "
            "--candidate-url und --candidate-pack-manifest zwingend"
        ),
    )
    parser.add_argument("--split", choices=("dev", "holdout", "all"), default="holdout")
    parser.add_argument("--limit", type=int, default=3)
    parser.add_argument("--bm25-max", type=float, default=-3.0)
    parser.add_argument("--warmup", "--warmup-rounds", type=_nonnegative_int, default=1)
    parser.add_argument("--repeats", type=_positive_int, default=3)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument(
        "--min-recall-gain",
        type=float,
        default=PRODUCTION_MIN_RECALL_GAIN,
    )
    parser.add_argument(
        "--max-added-p95-ms",
        type=float,
        default=PRODUCTION_MAX_ADDED_P95_MS,
    )
    parser.add_argument(
        "--minimum-holdout-n",
        type=_positive_int,
        default=PRODUCTION_MIN_HOLDOUT_N,
    )
    parser.add_argument(
        "--minimum-holdout-answerable",
        type=_positive_int,
        default=PRODUCTION_MIN_HOLDOUT_ANSWERABLE,
    )
    parser.add_argument(
        "--minimum-holdout-unanswerable",
        type=_positive_int,
        default=PRODUCTION_MIN_HOLDOUT_UNANSWERABLE,
    )
    parser.add_argument(
        "--max-false-retrieval-candidate-rate",
        type=float,
        default=PRODUCTION_MAX_FALSE_RETRIEVAL_CANDIDATE_RATE,
    )
    args = parser.parse_args()

    source_query_path = args.queries.expanduser().absolute()
    source_manifest_path = (
        args.manifest.expanduser().absolute()
        if args.manifest
        else source_query_path.parent / "manifest.json"
    )
    try:
        dataset_snapshot = snapshot_frozen_dataset(
            source_query_path,
            source_manifest_path,
        )
    except (OSError, ValueError) as exc:
        print(f"[knowledge-bench] FATAL: {exc}", file=sys.stderr)
        return 2
    query_path = dataset_snapshot.query_path
    manifest_path = dataset_snapshot.manifest_path
    try:
        queries = read_queries(query_path, "all")
        dataset_manifest, actual_split = load_dataset_manifest(
            manifest_path,
            query_path,
            queries,
            args.split,
        )
        baseline_url = validate_loopback_url(args.baseline_url, "Baseline-URL")
        baseline_endpoint = validate_endpoint(
            args.baseline_endpoint,
            "Baseline-Endpoint",
        )
        candidate_url = (
            validate_loopback_url(args.candidate_url, "Candidate-URL")
            if args.candidate_url
            else None
        )
        candidate_endpoint = validate_endpoint(
            args.candidate_endpoint,
            "Candidate-Endpoint",
        )
        candidate_manifest_endpoint = validate_endpoint(
            args.candidate_manifest_endpoint,
            "Candidate-Manifest-Endpoint",
        )
        candidate_manifest = None
        candidate_binding = None
        code_binding = None
        baseline_state = None
        baseline_identity = None
        if candidate_url:
            validate_production_execution_contract(
                limit=args.limit,
                bm25_max=args.bm25_max,
                warmup_rounds=args.warmup,
                repeats=args.repeats,
                baseline_endpoint=baseline_endpoint,
                candidate_endpoint=candidate_endpoint,
                candidate_manifest_endpoint=candidate_manifest_endpoint,
            )
            validate_production_thresholds(
                min_recall_gain=args.min_recall_gain,
                max_added_p95_ms=args.max_added_p95_ms,
                minimum_n=args.minimum_holdout_n,
                minimum_answerable_n=args.minimum_holdout_answerable,
                minimum_unanswerable_n=args.minimum_holdout_unanswerable,
                max_false_retrieval_candidate_rate=(
                    args.max_false_retrieval_candidate_rate
                ),
            )
            if args.baseline_database is None:
                raise ValueError(
                    "--baseline-database ist für einen Candidate-A/B-Lauf verpflichtend"
                )
            baseline_state, baseline_identity = verify_baseline_identity(
                baseline_url,
                args.baseline_database,
                dataset_manifest,
            )
            code_binding = runner_code_binding()
            candidate_pack_manifest, local_verification = verify_candidate_artifacts(
                candidate_url,
                args.candidate_pack_manifest,
                args.candidate_source_dump,
            )
            assert candidate_pack_manifest is not None
            assert local_verification is not None
            candidate_manifest, candidate_binding = fetch_candidate_manifest(
                candidate_url,
                candidate_manifest_endpoint,
                dataset_manifest,
                local_verification,
            )
        else:
            verify_candidate_artifacts(
                None,
                args.candidate_pack_manifest,
                args.candidate_source_dump,
            )
    except (OSError, ValueError) as exc:
        dataset_snapshot.cleanup()
        print(f"[knowledge-bench] FATAL: {exc}", file=sys.stderr)
        return 2

    try:
        execution_contract = {
            "schedule": "deterministic-counterbalanced-interleaved-v1",
            "warmupRounds": args.warmup,
            "repeats": args.repeats,
            "limit": args.limit,
            "bm25Max": args.bm25_max,
            "baselineEndpoint": baseline_endpoint,
            "baselineHealthEndpoint": (
                PRODUCTION_BASELINE_HEALTH_ENDPOINT if candidate_url else None
            ),
            "baselineAttestationEndpoint": (
                PRODUCTION_BASELINE_ATTESTATION_ENDPOINT
                if candidate_url
                else None
            ),
            "candidateEndpoint": candidate_endpoint if candidate_url else None,
            "candidateManifestEndpoint": (
                candidate_manifest_endpoint if candidate_url else None
            ),
            "thresholds": (
                {
                    "minimumRecallGain": args.min_recall_gain,
                    "maximumAddedP95Ms": args.max_added_p95_ms,
                    "minimumHoldoutN": args.minimum_holdout_n,
                    "minimumHoldoutAnswerable": args.minimum_holdout_answerable,
                    "minimumHoldoutNoAnswer": args.minimum_holdout_unanswerable,
                    "maximumFalseRetrievalCandidateRate": (
                        args.max_false_retrieval_candidate_rate
                    ),
                }
                if candidate_url
                else None
            ),
        }
        execution_contract_sha256 = hashlib.sha256(
            json.dumps(
                execution_contract,
                sort_keys=True,
                separators=(",", ":"),
                allow_nan=False,
            ).encode("utf-8")
        ).hexdigest()
        baseline_probes, candidate_probes = run_interleaved(
            queries,
            baseline_url=baseline_url,
            baseline_endpoint=baseline_endpoint,
            candidate_url=candidate_url,
            candidate_endpoint=candidate_endpoint,
            limit=args.limit,
            bm25_max=args.bm25_max,
            warmup_rounds=args.warmup,
            repeats=args.repeats,
            candidate_manifest=candidate_manifest,
        )
        baseline_metrics = metrics(queries, baseline_probes)
        report = {
            "schemaVersion": 2,
            "createdAt": datetime.now().astimezone().isoformat(),
            "split": actual_split,
            "querySet": query_set_metadata(
                query_path,
                queries,
                manifest_path,
                dataset_manifest,
            ),
            "execution": {
                "contract": execution_contract,
                "contractSha256": execution_contract_sha256,
                "runnerCode": code_binding,
            },
            "baseline": {
                "url": baseline_url,
                "endpoint": baseline_endpoint,
                "identity": (
                    baseline_identity
                    if baseline_identity is not None
                    else {"status": "unbound-exploratory"}
                ),
                "metrics": baseline_metrics,
                "probes": [asdict(probe) for probe in baseline_probes],
            },
        }
        if candidate_url:
            candidate_metrics = metrics(queries, candidate_probes)
            paired = paired_statistics(queries, baseline_probes, candidate_probes)
            report["candidate"] = {
                "url": candidate_url,
                "endpoint": candidate_endpoint,
                "manifest": candidate_binding,
                "metrics": candidate_metrics,
                "probes": [asdict(probe) for probe in candidate_probes],
            }
            report["paired"] = paired
            report["gate"] = gate(
                baseline_metrics,
                candidate_metrics,
                min_recall_gain=args.min_recall_gain,
                max_added_p95_ms=args.max_added_p95_ms,
                minimum_n=args.minimum_holdout_n,
                minimum_answerable_n=args.minimum_holdout_answerable,
                minimum_unanswerable_n=args.minimum_holdout_unanswerable,
                holdout_only=args.split == "holdout" and actual_split == "holdout",
                max_false_retrieval_candidate_rate=(
                    args.max_false_retrieval_candidate_rate
                ),
                exact_recall_p=paired["passageRecallAt3"]["exactOneSidedP"],
            )
            if runner_code_binding() != code_binding:
                raise ValueError(
                    "Production-Bench-Code änderte sich während der Messung"
                )
            assert baseline_state is not None
            assert baseline_identity is not None
            assert_baseline_unchanged(
                baseline_state,
                baseline_url,
                baseline_identity,
            )
        dataset_snapshot.verify_and_cleanup()
    except ValueError as exc:
        dataset_snapshot.cleanup()
        print(f"[knowledge-bench] FATAL: {exc}", file=sys.stderr)
        return 2

    try:
        rendered = json.dumps(
            report,
            ensure_ascii=False,
            indent=2,
            allow_nan=False,
        )
    except ValueError as exc:
        print(f"[knowledge-bench] FATAL: Report enthält keine gültigen JSON-Zahlen: {exc}", file=sys.stderr)
        return 2
    print(rendered)
    if args.output_dir:
        output = args.output_dir.expanduser().resolve()
        try:
            write_report_atomic(output, report)
        except (OSError, ValueError) as exc:
            print(f"[knowledge-bench] FATAL: {exc}", file=sys.stderr)
            return 2

    if "gate" in report and not report["gate"]["passed"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
