#!/usr/bin/env python3
"""Private Erfassung, explizites Human-Review und reproduzierbarer Benchmark-Freeze."""

from __future__ import annotations

import argparse
import ctypes
import errno
import fcntl
import hashlib
import json
import mimetypes
import os
import re
import secrets
import stat
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from dataset_schema import normalize_title, parse_query_item  # noqa: E402
from query_reducer import search_query, verify_contract  # noqa: E402


DEFAULT_INTAKE_ROOT = Path("~/.hoshi/knowledge-bench/intake")
INTAKE_SCHEMA_VERSION = 1
MANIFEST_SCHEMA_VERSION = 1
PRODUCTION_MINIMUM_TOTAL = 80
PRODUCTION_MAXIMUM_TOTAL = 100
PRODUCTION_MINIMUM_HOLDOUT_ANSWERABLE = 20
PRODUCTION_MINIMUM_HOLDOUT_UNANSWERABLE = 10
CANDIDATE_SELECTION_FILE = "candidate-selection.jsonl"
SELECTION_SEAL_FILE = "selection-seal.json"
SELECTION_LOCK_FILE = "selection-seal.lock"
MAX_CANDIDATE_SELECTION_BYTES = 16 * 1024 * 1024
MAX_BASELINE_DATABASE_BYTES = 32 * 1024 * 1024 * 1024
CANDIDATE_SELECTION_FIELDS = {"title", "aliases"}
DATASET_ID = re.compile(r"^[a-z0-9][a-z0-9._-]{1,63}$")
SHA1 = re.compile(r"^[0-9a-fA-F]{40}$")
SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
DEWIKI_ARTICLE_DUMP_PATH = re.compile(
    r"^/dewiki/(?P<date>\d{8})/"
    r"dewiki-(?P=date)-pages-articles-multistream\.xml\.bz2$"
)
INTERNAL_FIELDS = {
    "schemaVersion",
    "id",
    "query",
    "topicGroup",
    "stratum",
    "captureMode",
    "audioPersisted",
    "capturedAt",
    "state",
    "answerable",
    "goldPassages",
    "exactTitleRequired",
    "labeledAt",
    "reviewedAt",
}
PASSAGE_FIELDS = {"title", "evidence"}
PRIVACY_PATTERNS = (
    ("E-Mail-Adresse", re.compile(r"\b[^@\s]+@[^@\s]+\.[^@\s]+\b")),
    ("IPv4-Adresse", re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")),
    ("lokaler Benutzerpfad", re.compile(r"(?:/Users/|/home/|[A-Za-z]:\\)")),
    (
        "möglicher Schlüssel/Token",
        re.compile(r"\b(?:sk-[A-Za-z0-9_-]{16,}|[A-Fa-f0-9]{32,})\b"),
    ),
    (
        "Telefonnummer",
        re.compile(r"(?<!\w)(?:\+?\d[\d ()/-]{7,}\d)(?!\w)"),
    ),
)


@dataclass(frozen=True)
class _FreezePolicy:
    minimum_total: int
    maximum_total: int
    minimum_holdout_answerable: int
    minimum_holdout_unanswerable: int


_PRODUCTION_FREEZE_POLICY = _FreezePolicy(
    minimum_total=PRODUCTION_MINIMUM_TOTAL,
    maximum_total=PRODUCTION_MAXIMUM_TOTAL,
    minimum_holdout_answerable=PRODUCTION_MINIMUM_HOLDOUT_ANSWERABLE,
    minimum_holdout_unanswerable=PRODUCTION_MINIMUM_HOLDOUT_UNANSWERABLE,
)


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_local_dump(
    path: Path,
    expected_sha1: str,
    expected_sha256: str,
) -> dict:
    local_path = path.expanduser().resolve()
    if not local_path.is_file():
        raise ValueError("lokale Dump-Datei fehlt")
    sha1 = hashlib.sha1(usedforsecurity=False)
    sha256 = hashlib.sha256()
    size = 0
    with local_path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            size += len(chunk)
            sha1.update(chunk)
            sha256.update(chunk)
    actual_sha1 = sha1.hexdigest()
    actual_sha256 = sha256.hexdigest()
    if actual_sha1 != expected_sha1.lower():
        raise ValueError("lokale Dump-Datei stimmt nicht mit dem deklarierten SHA-1 überein")
    if actual_sha256 != expected_sha256.lower():
        raise ValueError(
            "lokale Dump-Datei stimmt nicht mit dem deklarierten SHA-256 überein"
        )
    return {
        "performed": True,
        "sizeBytes": size,
        "sha1": actual_sha1,
        "sha256": actual_sha256,
    }


def canonical_json(value: object) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def _file_fingerprint(value: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def stable_regular_bytes(path: Path, maximum_bytes: int, label: str) -> bytes:
    """Liest genau einen unveränderten regulären Inode, ohne Symlinks zu folgen."""

    local_path = path.expanduser().absolute()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(local_path, flags)
    except OSError as exc:
        raise ValueError(f"{label} fehlt oder ist nicht sicher lesbar") from exc
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size > maximum_bytes
        ):
            raise ValueError(
                f"{label} muss eine reguläre Datei bis {maximum_bytes} Bytes sein"
            )
        chunks: list[bytes] = []
        remaining = maximum_bytes + 1
        while remaining > 0:
            chunk = os.read(descriptor, min(1024 * 1024, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        if remaining <= 0 and os.read(descriptor, 1):
            raise ValueError(f"{label} überschreitet das Größenlimit")
        after = os.fstat(descriptor)
        if _file_fingerprint(after) != _file_fingerprint(before):
            raise ValueError(f"{label} änderte sich während des Lesens")
    finally:
        os.close(descriptor)
    try:
        path_state = local_path.lstat()
    except OSError as exc:
        raise ValueError(f"{label} ist nach dem Lesen nicht mehr prüfbar") from exc
    if _file_fingerprint(path_state) != _file_fingerprint(before):
        raise ValueError(f"{label}-Pfad änderte sich während des Lesens")
    return b"".join(chunks)


def stable_regular_sha256(path: Path, maximum_bytes: int, label: str) -> dict:
    """Hasht ein großes Artefakt streaming und verweigert Inode-/Pfaddrift."""

    local_path = path.expanduser().absolute()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(local_path, flags)
    except OSError as exc:
        raise ValueError(f"{label} fehlt oder ist nicht sicher lesbar") from exc
    try:
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_size < 1
            or before.st_size > maximum_bytes
        ):
            raise ValueError(
                f"{label} muss eine reguläre, nicht-leere Datei im Größenbudget sein"
            )
        digest = hashlib.sha256()
        while True:
            chunk = os.read(descriptor, 8 * 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
        after = os.fstat(descriptor)
        if _file_fingerprint(after) != _file_fingerprint(before):
            raise ValueError(f"{label} änderte sich während des Hashens")
    finally:
        os.close(descriptor)
    try:
        path_state = local_path.lstat()
    except OSError as exc:
        raise ValueError(f"{label} ist nach dem Hash nicht mehr prüfbar") from exc
    if _file_fingerprint(path_state) != _file_fingerprint(before):
        raise ValueError(f"{label}-Pfad änderte sich während des Hashens")
    return {
        "databaseSha256": digest.hexdigest(),
        "sizeBytes": before.st_size,
    }


def assert_no_sqlite_sidecars(path: Path, label: str) -> None:
    sidecars = [
        candidate.name
        for candidate in (
            Path(str(path.expanduser().absolute()) + "-wal"),
            Path(str(path.expanduser().absolute()) + "-shm"),
            Path(str(path.expanduser().absolute()) + "-journal"),
        )
        if candidate.exists()
    ]
    if sidecars:
        raise ValueError(
            f"{label} besitzt nicht gebundene SQLite-Sidecars: "
            + ", ".join(sidecars)
        )


def canonical_candidate_selection(path: Path) -> tuple[bytes, int]:
    """Friert denselben rein öffentlichen Titel-/Aliasvertrag wie Pack v1 ein."""

    try:
        source = stable_regular_bytes(
            path,
            MAX_CANDIDATE_SELECTION_BYTES,
            "Candidate-Auswahl",
        ).decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError(
            "Candidate-Auswahl muss gültiges UTF-8 sein"
        ) from exc

    rows: list[dict] = []
    seen_titles: set[str] = set()
    for line_no, line in enumerate(source.splitlines(), 1):
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        try:
            raw = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(
                f"Candidate-Auswahl Zeile {line_no}: ungültiges JSON"
            ) from exc
        if not isinstance(raw, dict):
            raise ValueError(
                f"Candidate-Auswahl Zeile {line_no}: Objekt erwartet"
            )
        unexpected = sorted(set(raw) - CANDIDATE_SELECTION_FIELDS)
        if unexpected:
            raise ValueError(
                f"Candidate-Auswahl Zeile {line_no}: nur öffentliche Felder "
                f"title/aliases sind erlaubt ({', '.join(unexpected)})"
            )
        title = raw.get("title")
        if not isinstance(title, str) or not title.strip():
            raise ValueError(
                f"Candidate-Auswahl Zeile {line_no}: title fehlt"
            )
        aliases = raw.get("aliases", [])
        if not isinstance(aliases, list) or any(
            not isinstance(alias, str) or not alias.strip() for alias in aliases
        ):
            raise ValueError(
                f"Candidate-Auswahl Zeile {line_no}: aliases muss eine String-Liste sein"
            )
        if aliases:
            raise ValueError(
                f"Candidate-Auswahl Zeile {line_no}: Release-Pack v1 erlaubt "
                "keine unbelegten Aliase"
            )
        canonical_title = re.sub(r"\s+", " ", title.strip())
        normalized_title = normalize_title(canonical_title)
        if normalized_title in seen_titles:
            raise ValueError(
                f"Candidate-Auswahl Zeile {line_no}: doppelter Titel"
            )
        seen_titles.add(normalized_title)
        rows.append(
            {
                "title": canonical_title,
                "aliases": [],
            }
        )
    if not rows:
        raise ValueError("Candidate-Auswahl ist leer")
    canonical = "".join(canonical_json(row) + "\n" for row in rows).encode("utf-8")
    return canonical, len(rows)


def ensure_private_dir(path: Path) -> Path:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(path, 0o700)
    return path


def dataset_dir(root: Path, dataset: str) -> Path:
    if not DATASET_ID.fullmatch(dataset):
        raise ValueError(
            "Dataset-ID: 2–64 Zeichen, nur Kleinbuchstaben, Ziffern, . _ -"
        )
    private_root = ensure_private_dir(root.expanduser().resolve())
    return ensure_private_dir(private_root / dataset)


def atomic_private_jsonl(path: Path, rows: list[dict]) -> None:
    ensure_private_dir(path.parent)
    fd, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        text=True,
    )
    temporary = Path(temporary_name)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(canonical_json(row) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def atomic_private_json(path: Path, value: dict) -> None:
    ensure_private_dir(path.parent)
    fd, temporary_name = tempfile.mkstemp(
        dir=path.parent,
        prefix=f".{path.name}.",
        suffix=".tmp",
        text=True,
    )
    temporary = Path(temporary_name)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(
                json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2)
                + "\n"
            )
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        os.chmod(path, 0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def write_private_exclusive_bytes(path: Path, content: bytes) -> None:
    flags = (
        os.O_WRONLY
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        descriptor = os.open(path, flags, 0o600)
    except FileExistsError as exc:
        raise ValueError(f"{path.name} existiert bereits") from exc
    except OSError as exc:
        raise ValueError(f"{path.name} kann nicht sicher angelegt werden") from exc
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(content)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        path.unlink(missing_ok=True)
        raise
    directory_fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def _selection_lock(directory: Path):
    lock_path = directory / SELECTION_LOCK_FILE
    flags = (
        os.O_RDWR
        | os.O_CREAT
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    try:
        descriptor = os.open(lock_path, flags, 0o600)
    except OSError as exc:
        raise ValueError("Selection-Seal-Lock ist nicht sicher verfügbar") from exc
    try:
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            raise ValueError("Selection-Seal-Lock ist keine reguläre Datei")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise ValueError(
                "Selection-Seal wird bereits von einem anderen Prozess bearbeitet"
            ) from exc
    except Exception:
        os.close(descriptor)
        raise
    return descriptor


def _unlock_selection(descriptor: int) -> None:
    try:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
    finally:
        os.close(descriptor)


def _seal_selection_locked(args: argparse.Namespace, directory: Path) -> None:
    seal_path = directory / SELECTION_SEAL_FILE
    bound_selection_path = directory / CANDIDATE_SELECTION_FILE
    canonical, entries = canonical_candidate_selection(args.candidate_selection)
    assert_no_sqlite_sidecars(args.baseline_database, "Baseline-Datenbank")
    baseline = stable_regular_sha256(
        args.baseline_database,
        MAX_BASELINE_DATABASE_BYTES,
        "Baseline-Datenbank",
    )
    assert_no_sqlite_sidecars(args.baseline_database, "Baseline-Datenbank")
    write_private_exclusive_bytes(bound_selection_path, canonical)
    seal = {
        "schemaVersion": 1,
        "datasetId": args.dataset,
        "sealId": secrets.token_hex(32),
        "sealedAt": utc_now(),
        "state": "sealed",
        "selection": {
            "file": CANDIDATE_SELECTION_FILE,
            "sha256": hashlib.sha256(canonical).hexdigest(),
            "entries": entries,
        },
        "baseline": baseline,
    }
    try:
        write_private_exclusive_bytes(
            seal_path,
            (
                json.dumps(seal, ensure_ascii=False, sort_keys=True, indent=2)
                + "\n"
            ).encode("utf-8"),
        )
    except Exception:
        bound_selection_path.unlink(missing_ok=True)
        raise
    print(
        f"[knowledge-bench] Selection-Seal {args.dataset}: "
        f"{entries} öffentliche Titel; noch kein Split erzeugt"
    )


def command_seal_selection(args: argparse.Namespace) -> None:
    """Phase 1: bindet die öffentliche Auswahl, ohne einen Split zu erzeugen."""

    directory = dataset_dir(args.root, args.dataset)
    lock = _selection_lock(directory)
    try:
        _seal_selection_locked(args, directory)
    finally:
        _unlock_selection(lock)


def load_selection_seal(directory: Path, dataset: str) -> tuple[dict, bytes]:
    seal_path = directory / SELECTION_SEAL_FILE
    selection_path = directory / CANDIDATE_SELECTION_FILE
    try:
        seal = json.loads(seal_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(
            "Freeze verlangt zuerst ein gültiges `seal-selection`"
        ) from exc
    if isinstance(seal, dict) and seal.get("state") in {"committing", "frozen"}:
        raise ValueError(
            "Selection-Seal wurde bereits für einen Freeze verbraucht "
            "(bei Zustand committing gilt der Vorgang fail-closed)"
        )
    if not isinstance(seal, dict) or set(seal) != {
        "schemaVersion",
        "datasetId",
        "sealId",
        "sealedAt",
        "state",
        "selection",
        "baseline",
    }:
        raise ValueError("Selection-Seal besitzt nicht das erwartete exakte Schema")
    if (
        seal.get("schemaVersion") != 1
        or seal.get("datasetId") != dataset
        or seal.get("state") != "sealed"
    ):
        raise ValueError(
            "Selection-Seal passt nicht zum Dataset oder wurde bereits verbraucht"
        )
    if not isinstance(seal.get("sealId"), str) or not SHA256.fullmatch(
        seal["sealId"]
    ):
        raise ValueError("Selection-Seal-ID ist ungültig")
    if not isinstance(seal.get("sealedAt"), str) or not seal["sealedAt"]:
        raise ValueError("Selection-Seal-Zeit fehlt")
    selection = seal.get("selection")
    if not isinstance(selection, dict) or set(selection) != {
        "file",
        "sha256",
        "entries",
    }:
        raise ValueError("Selection-Seal-Auswahl besitzt nicht das erwartete Schema")
    baseline = seal.get("baseline")
    if not isinstance(baseline, dict) or set(baseline) != {
        "databaseSha256",
        "sizeBytes",
    }:
        raise ValueError("Selection-Seal-Baseline besitzt nicht das erwartete Schema")
    if (
        not isinstance(baseline.get("databaseSha256"), str)
        or not SHA256.fullmatch(baseline["databaseSha256"])
        or not isinstance(baseline.get("sizeBytes"), int)
        or isinstance(baseline.get("sizeBytes"), bool)
        or baseline["sizeBytes"] < 1
    ):
        raise ValueError("Selection-Seal-Baseline ist ungültig")
    if selection.get("file") != CANDIDATE_SELECTION_FILE:
        raise ValueError("Selection-Seal verweist nicht auf die kanonische Datei")
    if (
        not isinstance(selection.get("entries"), int)
        or isinstance(selection.get("entries"), bool)
        or selection["entries"] < 1
    ):
        raise ValueError("Selection-Seal enthält keine positive Titelanzahl")
    canonical, entries = canonical_candidate_selection(selection_path)
    observed_sha = hashlib.sha256(canonical).hexdigest()
    if (
        entries != selection["entries"]
        or selection.get("sha256") != observed_sha
    ):
        raise ValueError("Selection-Seal und kanonische Auswahl stimmen nicht überein")
    return seal, canonical


def validate_record(record: object, where: str) -> dict:
    if not isinstance(record, dict):
        raise ValueError(f"{where}: Objekt erwartet")
    unknown = sorted(set(record) - INTERNAL_FIELDS)
    missing = sorted(INTERNAL_FIELDS - set(record))
    if unknown:
        raise ValueError(f"{where}: unbekannte Felder: {', '.join(unknown)}")
    if missing:
        raise ValueError(f"{where}: fehlende Felder: {', '.join(missing)}")
    if record["schemaVersion"] != INTAKE_SCHEMA_VERSION:
        raise ValueError(f"{where}: nicht unterstützte Intake-Schemaversion")
    for field in ("id", "query", "topicGroup", "stratum", "capturedAt"):
        if not isinstance(record[field], str) or not record[field].strip():
            raise ValueError(f"{where}: {field} fehlt")
    if record["captureMode"] not in {"text", "audio"}:
        raise ValueError(f"{where}: captureMode muss text oder audio sein")
    if record["audioPersisted"] is not False:
        raise ValueError(f"{where}: Audio darf im Intake nie persistiert werden")
    if record["state"] not in {"draft", "reviewed"}:
        raise ValueError(f"{where}: state muss draft oder reviewed sein")
    if record["answerable"] is not None and not isinstance(
        record["answerable"], bool
    ):
        raise ValueError(f"{where}: answerable muss null oder boolean sein")
    if not isinstance(record["exactTitleRequired"], bool):
        raise ValueError(f"{where}: exactTitleRequired muss boolean sein")
    if not isinstance(record["goldPassages"], list):
        raise ValueError(f"{where}: goldPassages muss Liste sein")
    normalized_titles: set[str] = set()
    for index, passage in enumerate(record["goldPassages"], 1):
        if not isinstance(passage, dict) or set(passage) != PASSAGE_FIELDS:
            raise ValueError(f"{where}:goldPassages[{index}]: title/evidence erwartet")
        if not isinstance(passage["title"], str) or not passage["title"].strip():
            raise ValueError(f"{where}:goldPassages[{index}]: title fehlt")
        title_key = normalize_title(passage["title"])
        if title_key in normalized_titles:
            raise ValueError(f"{where}: doppelter normalisierter Goldtitel")
        normalized_titles.add(title_key)
        evidence = passage["evidence"]
        if not isinstance(evidence, list) or not evidence or any(
            not isinstance(span, str) or not span.strip() for span in evidence
        ):
            raise ValueError(
                f"{where}:goldPassages[{index}]: Evidenzspan-Liste fehlt"
            )
        normalized_evidence = [normalized_query(span) for span in evidence]
        if len(normalized_evidence) != len(set(normalized_evidence)):
            raise ValueError(
                f"{where}:goldPassages[{index}]: doppelte normalisierte Evidenz"
            )
    if record["answerable"] is True and not record["goldPassages"]:
        raise ValueError(f"{where}: beantwortbar braucht goldPassages")
    if record["answerable"] is False and record["goldPassages"]:
        raise ValueError(f"{where}: unbeantwortbar darf keine goldPassages tragen")
    if record["answerable"] is False and record["exactTitleRequired"]:
        raise ValueError(f"{where}: unbeantwortbar darf keinen exakten Titel verlangen")
    if (
        record["exactTitleRequired"]
        and normalize_title(search_query(record["query"])) not in normalized_titles
    ):
        raise ValueError(
            f"{where}: exactTitleRequired verlangt eine searchQuery, die einem "
            "normalisierten Goldtitel entspricht"
        )
    if record["state"] == "reviewed":
        if record["answerable"] is None or not record["reviewedAt"]:
            raise ValueError(f"{where}: Review ohne vollständiges Label")
    for field in ("labeledAt", "reviewedAt"):
        if record[field] is not None and not isinstance(record[field], str):
            raise ValueError(f"{where}: {field} muss null oder String sein")
    return record


def load_records(directory: Path) -> list[dict]:
    path = directory / "records.jsonl"
    if not path.exists():
        return []
    os.chmod(path, 0o600)
    records: list[dict] = []
    ids: set[str] = set()
    with path.open(encoding="utf-8") as handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"records.jsonl:{line_no}: ungültiges JSON") from exc
            validate_record(record, f"records.jsonl:{line_no}")
            if record["id"] in ids:
                raise ValueError(f"records.jsonl:{line_no}: doppelte ID")
            ids.add(record["id"])
            records.append(record)
    return records


def save_records(directory: Path, records: list[dict]) -> None:
    for index, record in enumerate(records, 1):
        validate_record(record, f"Datensatz[{index}]")
    atomic_private_jsonl(directory / "records.jsonl", records)


def privacy_findings(text: str) -> list[str]:
    return [label for label, pattern in PRIVACY_PATTERNS if pattern.search(text)]


def record_privacy_findings(record: dict) -> list[str]:
    texts = [record["query"], record["topicGroup"], record["stratum"]]
    for passage in record["goldPassages"]:
        texts.append(passage["title"])
        texts.extend(passage["evidence"])
    return sorted({finding for text in texts for finding in privacy_findings(text)})


def normalized_query(query: str) -> str:
    return " ".join(query.casefold().split())


def require_yes(prompt: str, yes: bool) -> None:
    if yes:
        return
    if not sys.stdin.isatty():
        raise ValueError(f"{prompt} (interaktiv bestätigen oder --yes setzen)")
    answer = input(f"{prompt} [j/N] ").strip().casefold()
    if answer not in {"j", "ja", "y", "yes"}:
        raise ValueError("abgebrochen")


def validate_loopback_stt_url(stt_url: str) -> str:
    parsed = urllib.parse.urlparse(stt_url)
    if (
        parsed.scheme not in {"http", "https"}
        or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or parsed.path not in {"", "/"}
    ):
        raise ValueError(
            "STT-URL muss ein reiner Loopback-Endpunkt sein "
            "(127.0.0.1, localhost oder ::1)"
        )
    try:
        port = parsed.port
    except ValueError as exc:
        raise ValueError("STT-URL enthält einen ungültigen Port") from exc
    host = {
        "localhost": "127.0.0.1",
        "127.0.0.1": "127.0.0.1",
        "::1": "[::1]",
    }[parsed.hostname]
    authority = f"{host}:{port}" if port is not None else host
    return urllib.parse.urlunsplit((parsed.scheme, authority, "", "", ""))


def transcribe_audio(audio_path: Path, stt_url: str) -> str:
    local_stt_url = validate_loopback_stt_url(stt_url)
    path = audio_path.expanduser().resolve()
    if not path.is_file():
        raise ValueError("Audiodatei fehlt")
    boundary = f"hoshi-{secrets.token_hex(16)}"
    mime = mimetypes.guess_type(path.name)[0] or "application/octet-stream"
    upload_name = re.sub(r"[^A-Za-z0-9._-]", "_", path.name)
    prefix = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="audio_file"; filename="{upload_name}"\r\n'
        f"Content-Type: {mime}\r\n\r\n"
    ).encode("utf-8")
    body = prefix + path.read_bytes() + f"\r\n--{boundary}--\r\n".encode("ascii")
    params = urllib.parse.urlencode(
        {"encode": "true", "task": "transcribe", "language": "de", "output": "json"}
    )
    request = urllib.request.Request(
        f"{local_stt_url}/asr?{params}",
        data=body,
        method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
    )
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=120.0) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except (
        OSError,
        urllib.error.URLError,
        urllib.error.HTTPError,
        json.JSONDecodeError,
    ) as exc:
        raise ValueError(f"lokales STT fehlgeschlagen: {exc}") from exc
    transcript = payload.get("text") if isinstance(payload, dict) else None
    if not isinstance(transcript, str) or not transcript.strip():
        raise ValueError("lokales STT lieferte kein Transkript")
    return transcript.strip()


def confirm_or_edit_text(text: str, yes: bool) -> str:
    if yes:
        return text.strip()
    if not sys.stdin.isatty():
        raise ValueError("Transkript muss interaktiv bestätigt oder mit --yes akzeptiert werden")
    print(f"Erfasster Text: {text}")
    action = input("Übernehmen, bearbeiten oder abbrechen? [J/b/N] ").strip().casefold()
    if action in {"j", "ja", "y", "yes"}:
        return text.strip()
    if action in {"b", "bearbeiten", "e", "edit"}:
        edited = input("Korrigierter Text: ").strip()
        if edited:
            return edited
    raise ValueError("abgebrochen")


def parse_gold_passage(value: str) -> dict:
    if "::" not in value:
        raise ValueError("--gold-passage erwartet TITEL::EVIDENZ[||EVIDENZ]")
    title, raw_evidence = value.split("::", 1)
    evidence = [span.strip() for span in raw_evidence.split("||") if span.strip()]
    if not title.strip() or not evidence:
        raise ValueError("--gold-passage braucht Titel und mindestens einen Evidenzspan")
    return {"title": title.strip(), "evidence": evidence}


def find_record(records: list[dict], record_id: str) -> dict:
    for record in records:
        if record["id"] == record_id:
            return record
    raise ValueError(f"unbekannte Record-ID {record_id!r}")


def command_add(args: argparse.Namespace) -> None:
    directory = dataset_dir(args.root, args.dataset)
    records = load_records(directory)
    if args.audio:
        text = transcribe_audio(args.audio, args.stt_url)
        capture_mode = "audio"
    elif args.text:
        text = args.text.strip()
        capture_mode = "text"
    elif sys.stdin.isatty():
        text = input("Gesprochene/geschriebene Frage: ").strip()
        capture_mode = "text"
    else:
        raise ValueError("--text oder --audio erforderlich")
    if not text:
        raise ValueError("Frage ist leer")
    text = confirm_or_edit_text(text, args.yes)
    duplicate_ids = [
        record["id"]
        for record in records
        if normalized_query(record["query"]) == normalized_query(text)
    ]
    if duplicate_ids and not args.allow_duplicate:
        raise ValueError(
            "Duplikat-Warnung: normalisierte Frage existiert bereits; "
            "--allow-duplicate nur nach bewusster Prüfung"
        )
    findings = privacy_findings(text)
    if findings and not args.acknowledge_privacy:
        raise ValueError(
            "Privacy-Warnung (" + ", ".join(findings) + "): "
            "--acknowledge-privacy nach manueller Prüfung erforderlich"
        )
    record_id = args.id or (
        datetime.now().strftime("q-%Y%m%d%H%M%S-") + secrets.token_hex(3)
    )
    if not DATASET_ID.fullmatch(record_id) or any(
        record["id"] == record_id for record in records
    ):
        raise ValueError("Record-ID ist ungültig oder bereits vergeben")
    record = {
        "schemaVersion": INTAKE_SCHEMA_VERSION,
        "id": record_id,
        "query": text,
        "topicGroup": args.topic_group.strip(),
        "stratum": args.stratum.strip(),
        "captureMode": capture_mode,
        "audioPersisted": False,
        "capturedAt": utc_now(),
        "state": "draft",
        "answerable": None,
        "goldPassages": [],
        "exactTitleRequired": False,
        "labeledAt": None,
        "reviewedAt": None,
    }
    save_records(directory, records + [record])
    print(f"[knowledge-bench] draft {record_id} gespeichert (nur Text, Audio nie kopiert)")


def prompt_answerable() -> bool:
    if not sys.stdin.isatty():
        raise ValueError("--answerable yes|no erforderlich")
    answer = input("Durch den eingefrorenen Dump beantwortbar? [j/n] ").strip().casefold()
    if answer in {"j", "ja", "y", "yes"}:
        return True
    if answer in {"n", "nein", "no"}:
        return False
    raise ValueError("Antwort muss ja oder nein sein")


def command_label(args: argparse.Namespace) -> None:
    directory = dataset_dir(args.root, args.dataset)
    records = load_records(directory)
    record = find_record(records, args.record_id)
    answerable = (
        args.answerable == "yes"
        if args.answerable is not None
        else prompt_answerable()
    )
    passages = [parse_gold_passage(value) for value in args.gold_passage]
    if answerable and not passages:
        if not sys.stdin.isatty():
            raise ValueError("beantwortbar braucht mindestens ein --gold-passage")
        while True:
            value = input("Goldpassage TITEL::EVIDENZ[||EVIDENZ] (leer = fertig): ").strip()
            if not value:
                break
            passages.append(parse_gold_passage(value))
    if answerable and not passages:
        raise ValueError("beantwortbar braucht mindestens eine Goldpassage")
    if not answerable and passages:
        raise ValueError("unbeantwortbar darf keine Goldpassage tragen")
    exact_title = args.exact_title_required == "yes"
    if not answerable and exact_title:
        raise ValueError("unbeantwortbar darf keinen exakten Titel verlangen")
    record.update(
        {
            "state": "draft",
            "answerable": answerable,
            "goldPassages": passages,
            "exactTitleRequired": exact_title,
            "labeledAt": utc_now(),
            "reviewedAt": None,
        }
    )
    save_records(directory, records)
    print(f"[knowledge-bench] {record['id']} gelabelt; Review bleibt offen")


def command_review(args: argparse.Namespace) -> None:
    directory = dataset_dir(args.root, args.dataset)
    records = load_records(directory)
    record = find_record(records, args.record_id)
    if record["answerable"] is None or not record["labeledAt"]:
        raise ValueError("vor Review zuerst labeln")
    print(canonical_json(record))
    require_yes("Label und Privacy manuell geprüft?", args.yes)
    findings = record_privacy_findings(record)
    if findings and not args.acknowledge_privacy:
        raise ValueError(
            "Privacy-Warnung (" + ", ".join(findings) + "): "
            "--acknowledge-privacy erforderlich"
        )
    record["state"] = "reviewed"
    record["reviewedAt"] = utc_now()
    save_records(directory, records)
    print(f"[knowledge-bench] {record['id']} reviewed")


def command_list(args: argparse.Namespace) -> None:
    directory = dataset_dir(args.root, args.dataset)
    records = load_records(directory)
    for record in records:
        label = (
            "offen"
            if record["answerable"] is None
            else ("answerable" if record["answerable"] else "no-answer")
        )
        print(
            f"{record['id']}\t{record['state']}\t{label}\t"
            f"{record['topicGroup']}\t{record['query']}"
        )
    print(f"[knowledge-bench] {len(records)} Records")


def choose_holdout_groups(
    records: list[dict],
    seed: str,
    minimum_answerable: int,
    minimum_unanswerable: int,
) -> set[str]:
    if minimum_answerable < 1 or minimum_unanswerable < 1:
        raise ValueError("Holdout-Mindestklassen müssen positiv sein")
    grouped: dict[str, list[dict]] = defaultdict(list)
    for record in records:
        grouped[record["topicGroup"]].append(record)
    names = sorted(
        grouped,
        key=lambda name: hashlib.sha256(
            f"{seed}\0{name}".encode("utf-8")
        ).hexdigest(),
    )
    group_counts = [
        (
            len(grouped[name]),
            sum(record["answerable"] is True for record in grouped[name]),
            sum(record["answerable"] is False for record in grouped[name]),
        )
        for name in names
    ]
    states: dict[tuple[int, int, int], tuple[int, ...]] = {(0, 0, 0): ()}
    for index, (rows, answerable, no_answer) in enumerate(group_counts):
        additions = {}
        for key, chosen in states.items():
            next_key = (
                key[0] + rows,
                key[1] + answerable,
                key[2] + no_answer,
            )
            additions.setdefault(next_key, chosen + (index,))
        for key, chosen in additions.items():
            states.setdefault(key, chosen)

    total = len(records)
    total_answerable = sum(record["answerable"] is True for record in records)
    total_no_answer = total - total_answerable
    targets = (
        round(total * 0.30),
        round(total_answerable * 0.30),
        round(total_no_answer * 0.30),
    )
    feasible: list[tuple[tuple[int, int, int], tuple[int, ...]]] = []
    for counts, chosen in states.items():
        rows, answerable, no_answer = counts
        if (
            answerable >= minimum_answerable
            and no_answer >= minimum_unanswerable
            and 0 < rows < total
            and total_answerable - answerable > 0
            and total_no_answer - no_answer > 0
        ):
            feasible.append((counts, chosen))
    if not feasible:
        raise ValueError(
            "kein topicGroup-getrennter 70/30-Split erfüllt die Mindestklassen; "
            "mehr reviewed Records/Topic-Gruppen sammeln"
        )
    counts, chosen = min(
        feasible,
        key=lambda item: (
            abs(item[0][0] - targets[0]),
            abs(item[0][1] - targets[1]) + abs(item[0][2] - targets[2]),
            item[1],
        ),
    )
    del counts
    return {names[index] for index in chosen}


def validate_dump_source(
    url: str,
    sha1: str,
    sha256: str,
    local_file: Path | None = None,
) -> dict:
    parsed = urllib.parse.urlparse(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "dumps.wikimedia.org"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.port not in {None, 443}
        or parsed.query
        or parsed.fragment
        or not DEWIKI_ARTICLE_DUMP_PATH.fullmatch(parsed.path)
    ):
        raise ValueError(
            "Dump-URL muss der kanonische deutsche pages-articles-multistream-"
            "Dump auf https://dumps.wikimedia.org sein"
        )
    if not SHA1.fullmatch(sha1):
        raise ValueError("Dump-SHA1 muss 40 Hex-Zeichen haben")
    if not SHA256.fullmatch(sha256):
        raise ValueError("Dump-SHA256 muss 64 Hex-Zeichen haben")
    local_verification = (
        verify_local_dump(local_file, sha1, sha256)
        if local_file is not None
        else {"performed": False}
    )
    return {
        "url": url,
        "sha1": sha1.lower(),
        "sha256": sha256.lower(),
        "operatorAsserted": True,
        "networkMetadataVerified": False,
        "localFileVerification": local_verification,
    }


def validate_group_isolation(records: list[dict]) -> None:
    group_spellings: dict[str, set[str]] = defaultdict(set)
    search_query_groups: dict[str, set[str]] = defaultdict(set)
    gold_title_groups: dict[str, set[str]] = defaultdict(set)
    for record in records:
        group = record["topicGroup"]
        group_spellings[normalized_query(group)].add(group)
        search_key = normalized_query(search_query(record["query"]))
        search_query_groups[search_key].add(group)
        for passage in record["goldPassages"]:
            gold_title_groups[normalize_title(passage["title"])].add(group)

    if any(len(spellings) > 1 for spellings in group_spellings.values()):
        raise ValueError(
            "Freeze verweigert: topicGroup-Bezeichner unterscheiden sich nur "
            "durch Schreibweise oder Leerraum"
        )
    if any(len(groups) > 1 for groups in search_query_groups.values()):
        raise ValueError(
            "Freeze verweigert: identische reduzierte searchQuery kommt in "
            "mehreren topicGroups vor"
        )
    if any(len(groups) > 1 for groups in gold_title_groups.values()):
        raise ValueError(
            "Freeze verweigert: normalisierter Goldtitel kommt in mehreren "
            "topicGroups vor"
        )


def frozen_item(record: dict, split: str) -> dict:
    item = {
        "schemaVersion": 2,
        "id": record["id"],
        "split": split,
        "query": record["query"],
        "searchQuery": search_query(record["query"]),
        "answerable": record["answerable"],
        "goldPassages": record["goldPassages"],
        "exactTitleRequired": record["exactTitleRequired"],
        "topicGroup": record["topicGroup"],
        "stratum": record["stratum"],
    }
    parse_query_item(item, record["id"])
    return item


def write_public_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(canonical_json(row) + "\n" for row in rows),
        encoding="utf-8",
    )
    os.chmod(path, 0o400)


def publish_directory_no_replace(temporary: Path, output: Path) -> None:
    """Publiziert den Freeze atomar, ohne ein konkurrierendes Ziel zu ersetzen."""

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
        result = rename(-100, source, -100, destination, 0x00000001)
    else:
        raise ValueError(
            "Atomarer No-replace-Freeze wird auf dieser Plattform nicht unterstützt"
        )
    if result != 0:
        error = ctypes.get_errno()
        if error in {errno.EEXIST, errno.ENOTEMPTY}:
            raise ValueError(
                "Output-Verzeichnis existiert bereits; Freeze wird nie überschrieben"
            )
        raise OSError(error, os.strerror(error), str(output))
    parent_fd = os.open(output.parent, os.O_RDONLY)
    try:
        os.fsync(parent_fd)
    finally:
        os.close(parent_fd)


def _freeze_with_policy_locked(
    args: argparse.Namespace,
    policy: _FreezePolicy,
    directory: Path,
) -> None:
    selection_seal, selection_bytes = load_selection_seal(
        directory,
        args.dataset,
    )
    selection_entries = selection_seal["selection"]["entries"]
    records = load_records(directory)
    if not records:
        raise ValueError("keine Intake-Records")
    if (
        policy.maximum_total < policy.minimum_total
        or not policy.minimum_total <= len(records) <= policy.maximum_total
    ):
        raise ValueError(
            "Freeze verweigert: der menschlich geprüfte Fragensatz muss "
            f"zwischen {policy.minimum_total} und {policy.maximum_total} Records "
            f"enthalten (aktuell {len(records)})"
        )
    states = Counter(record["state"] for record in records)
    if states != Counter({"reviewed": len(records)}):
        raise ValueError("Freeze verweigert: alle Records müssen reviewed sein")
    normalized = Counter(normalized_query(record["query"]) for record in records)
    if any(count > 1 for count in normalized.values()):
        raise ValueError("Freeze verweigert: doppelte normalisierte Queries")
    validate_group_isolation(records)
    privacy = sorted(
        {
            finding
            for record in records
            for finding in record_privacy_findings(record)
        }
    )
    if privacy and not args.acknowledge_privacy:
        raise ValueError(
            "Freeze verweigert: Privacy-Warnung ("
            + ", ".join(privacy)
            + "); manuell prüfen und --acknowledge-privacy setzen"
        )
    source_dump = validate_dump_source(
        args.source_dump_url,
        args.source_dump_sha1,
        args.source_dump_sha256,
        getattr(args, "source_dump_file", None),
    )
    reducer = verify_contract()
    # Phase 2 erzeugt erst nach dem separaten Single-use-Seal einen nicht vom
    # Aufrufer wählbaren Seed. Das ist ein tool-erzwungener Prozessbeleg, keine
    # Behauptung über eine externe vertrauenswürdige Zeitquelle.
    split_seed = secrets.token_hex(32)
    holdout_groups = choose_holdout_groups(
        records,
        split_seed,
        policy.minimum_holdout_answerable,
        policy.minimum_holdout_unanswerable,
    )
    rows = [
        frozen_item(
            record,
            "holdout" if record["topicGroup"] in holdout_groups else "dev",
        )
        for record in records
    ]
    rows.sort(key=lambda item: (item["split"], item["topicGroup"], item["id"]))
    dev = [row for row in rows if row["split"] == "dev"]
    holdout = [row for row in rows if row["split"] == "holdout"]

    output = args.output_dir.expanduser().resolve()
    if output.exists():
        raise ValueError("Output-Verzeichnis existiert bereits; Freeze ist unveränderlich")
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(
        tempfile.mkdtemp(prefix=f".{output.name}.", dir=output.parent)
    )
    try:
        dev_path = temporary / "dev.jsonl"
        holdout_path = temporary / "holdout.jsonl"
        selection_path = temporary / CANDIDATE_SELECTION_FILE
        write_public_jsonl(dev_path, dev)
        write_public_jsonl(holdout_path, holdout)
        selection_path.write_bytes(selection_bytes)
        os.chmod(selection_path, 0o400)
        dataset_digest = hashlib.sha256(
            dev_path.read_bytes()
            + b"\0"
            + holdout_path.read_bytes()
            + b"\0"
            + selection_path.read_bytes()
        ).hexdigest()
        answerable_total = sum(row["answerable"] for row in rows)
        manifest = {
            "schemaVersion": MANIFEST_SCHEMA_VERSION,
            "datasetSchemaVersion": 2,
            "datasetId": args.dataset,
            "createdAt": utc_now(),
            "datasetSha256": dataset_digest,
            "sourceDump": source_dump,
            "reducer": reducer,
            "baseline": selection_seal["baseline"],
            "candidateSelection": {
                "file": CANDIDATE_SELECTION_FILE,
                "sha256": sha256_file(selection_path),
                "entries": selection_entries,
                "sealId": selection_seal["sealId"],
                "sealedAt": selection_seal["sealedAt"],
                "freezeOrder": "single-use-seal-before-random-split-v1",
            },
            "split": {
                "method": "topic-group-dp-v1",
                "seed": split_seed,
                "targetHoldoutRatio": 0.30,
                "actualHoldoutRatio": len(holdout) / len(rows),
                "minimumTotal": policy.minimum_total,
                "maximumTotal": policy.maximum_total,
                "minimumHoldoutAnswerable": policy.minimum_holdout_answerable,
                "minimumHoldoutNoAnswer": policy.minimum_holdout_unanswerable,
            },
            "counts": {
                "total": len(rows),
                "dev": len(dev),
                "holdout": len(holdout),
                "answerable": answerable_total,
                "noAnswer": len(rows) - answerable_total,
                "holdoutAnswerable": sum(row["answerable"] for row in holdout),
                "holdoutNoAnswer": sum(not row["answerable"] for row in holdout),
                "topicGroups": len({row["topicGroup"] for row in rows}),
                "strata": {
                    "total": dict(sorted(Counter(row["stratum"] for row in rows).items())),
                    "dev": dict(sorted(Counter(row["stratum"] for row in dev).items())),
                    "holdout": dict(
                        sorted(Counter(row["stratum"] for row in holdout).items())
                    ),
                },
                "states": {"reviewed": len(rows)},
            },
            "groundTruthValidation": {
                "schemaValidated": True,
                "exactTitleSearchQueryValidated": True,
                "evidenceAgainstSourceDump": {
                    "performed": False,
                    "isFreezeGate": False,
                    "status": "open",
                },
            },
            "files": {
                "dev": {
                    "file": dev_path.name,
                    "sha256": sha256_file(dev_path),
                    "queries": len(dev),
                },
                "holdout": {
                    "file": holdout_path.name,
                    "sha256": sha256_file(holdout_path),
                    "queries": len(holdout),
                },
            },
            "privacy": {
                "audioPersisted": False,
                "intakeIncluded": False,
                "humanReviewRequired": True,
                "warningsAcknowledged": bool(privacy),
            },
        }
        manifest_path = temporary / "manifest.json"
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        os.chmod(manifest_path, 0o400)
        committing_seal = {
            **selection_seal,
            "state": "committing",
            "freezeManifestSha256": sha256_file(manifest_path),
        }
        atomic_private_json(
            directory / SELECTION_SEAL_FILE,
            committing_seal,
        )
        os.chmod(temporary, 0o500)
        publish_directory_no_replace(temporary, output)
        atomic_private_json(
            directory / SELECTION_SEAL_FILE,
            {
                **committing_seal,
                "state": "frozen",
                "frozenAt": utc_now(),
            },
        )
    finally:
        if temporary.exists():
            os.chmod(temporary, 0o700)
            for child in temporary.iterdir():
                child.unlink()
            temporary.rmdir()
    print(
        f"[knowledge-bench] Freeze {args.dataset}: "
        f"{len(dev)} dev / {len(holdout)} holdout; Audio persistiert: nein"
    )


def _freeze_with_policy(args: argparse.Namespace, policy: _FreezePolicy) -> None:
    directory = dataset_dir(args.root, args.dataset)
    lock = _selection_lock(directory)
    try:
        _freeze_with_policy_locked(args, policy, directory)
    finally:
        _unlock_selection(lock)


def command_freeze(args: argparse.Namespace) -> None:
    _freeze_with_policy(args, _PRODUCTION_FREEZE_POLICY)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=DEFAULT_INTAKE_ROOT,
        help="privater Intake-Root (Default: ~/.hoshi/knowledge-bench/intake)",
    )
    commands = parser.add_subparsers(dest="command", required=True)

    add = commands.add_parser("add", help="Text oder lokal transkribiertes Audio erfassen")
    add.add_argument("dataset")
    add.add_argument("--id")
    source = add.add_mutually_exclusive_group()
    source.add_argument("--text")
    source.add_argument("--audio", type=Path)
    add.add_argument("--topic-group", required=True)
    add.add_argument("--stratum", default="unclassified")
    add.add_argument("--stt-url", default="http://127.0.0.1:9001")
    add.add_argument("--yes", action="store_true")
    add.add_argument("--allow-duplicate", action="store_true")
    add.add_argument("--acknowledge-privacy", action="store_true")
    add.set_defaults(function=command_add)

    label = commands.add_parser("label", help="Ground Truth ergänzen")
    label.add_argument("dataset")
    label.add_argument("record_id")
    label.add_argument("--answerable", choices=("yes", "no"))
    label.add_argument("--gold-passage", action="append", default=[])
    label.add_argument(
        "--exact-title-required",
        choices=("yes", "no"),
        default="no",
    )
    label.set_defaults(function=command_label)

    review = commands.add_parser("review", help="Label und Privacy manuell freigeben")
    review.add_argument("dataset")
    review.add_argument("record_id")
    review.add_argument("--yes", action="store_true")
    review.add_argument("--acknowledge-privacy", action="store_true")
    review.set_defaults(function=command_review)

    listing = commands.add_parser("list", help="Intake-Status zeigen")
    listing.add_argument("dataset")
    listing.set_defaults(function=command_list)

    seal = commands.add_parser(
        "seal-selection",
        help="öffentliche Candidate-Auswahl vor dem Holdout-Split einmalig versiegeln",
    )
    seal.add_argument("dataset")
    seal.add_argument("--candidate-selection", type=Path, required=True)
    seal.add_argument(
        "--baseline-database",
        type=Path,
        required=True,
        help="unveränderte Legacy-Baseline; SHA-256 wird streaming im Seal gebunden",
    )
    seal.set_defaults(function=command_seal_selection)

    freeze = commands.add_parser("freeze", help="reviewed Intake unveränderlich splitten")
    freeze.add_argument("dataset")
    freeze.add_argument("--output-dir", type=Path, required=True)
    freeze.add_argument("--source-dump-url", required=True)
    freeze.add_argument("--source-dump-sha1", required=True)
    freeze.add_argument("--source-dump-sha256", required=True)
    freeze.add_argument(
        "--source-dump-file",
        type=Path,
        help="optional: lokalen Dump einmal vollständig gegen beide Hashes prüfen",
    )
    freeze.add_argument("--acknowledge-privacy", action="store_true")
    freeze.set_defaults(function=command_freeze)
    return parser


def main() -> int:
    parser = build_parser()
    args = parser.parse_args()
    try:
        args.function(args)
    except (OSError, ValueError) as exc:
        print(f"[knowledge-bench] FATAL: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
