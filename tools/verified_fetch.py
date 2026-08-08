#!/usr/bin/env python3
"""Verifizierter, wiederaufnehmbarer Artefakt-Fetch fuer Hoshi.

Der Fetcher hat genau eine Aufgabe: Bytes aus gelockten Manifesten holen und
erst nach Groessen-/SHA-256-Pruefung aktivieren. Er startet keinen Dienst,
akzeptiert keine Lizenz im Namen des Nutzers und faellt nie auf ``main`` oder
``latest`` zurueck.

Aufrufe (Repo-Root):

    python3 tools/verified_fetch.py plan --profile local-mac
    python3 tools/verified_fetch.py fetch jdk-21-macos-aarch64
    sidecars/brain/.venv/bin/python tools/verified_fetch.py fetch brain-e4b \
        stt-whisper --accept-license gemma
    python3 tools/verified_fetch.py verify brain-e4b stt-whisper

URL-/JDK-Downloads brauchen nur die Python-Standardbibliothek. Hugging-Face-
Snapshots brauchen absichtlich das bereits gepinnte ``huggingface_hub`` aus dem
Brain-venv; dadurch entsteht kein zweiter ungepinnter Python-Installationsweg.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import sys
import tarfile
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Callable, Iterable, Mapping, Optional, Sequence, TextIO


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_MODELS_LOCK = REPO_ROOT / "models.json"
DEFAULT_TOOLCHAINS_LOCK = REPO_ROOT / "toolchains.lock.json"
SHA256_RE = re.compile(r"[0-9a-f]{64}")
REVISION_RE = re.compile(r"[0-9a-f]{40}")
CHUNK_BYTES = 1024 * 1024
MIB = 1024 * 1024
DIRECT_DOWNLOAD_RESERVE = 64 * MIB
LARGE_ARTIFACT_RESERVE = 512 * MIB


class FetchError(RuntimeError):
    """Ehrlicher, nutzerlesbarer Abbruch ohne Traceback im CLI."""


class MissingArtifact(FetchError):
    """Ein gelocktes Artefakt fehlt; nur dieser Fall darf einen Fetch ausloesen."""


class StrictRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Folgt Redirects nur innerhalb der vom Aufrufer erlaubten Schemes."""

    def __init__(self, allowed_schemes: set[str]):
        super().__init__()
        self.allowed_schemes = allowed_schemes

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parsed = urllib.parse.urlsplit(newurl)
        if parsed.scheme not in self.allowed_schemes or not parsed.hostname:
            raise FetchError(f"Redirect auf unerlaubtes Ziel abgelehnt: {parsed.scheme or '?'}")
        if parsed.username or parsed.password:
            raise FetchError("Redirect mit eingebetteten Credentials abgelehnt")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def open_verified_url(request: urllib.request.Request, timeout: float, allowed_schemes: set[str]):
    opener = urllib.request.build_opener(StrictRedirectHandler(allowed_schemes))
    return opener.open(request, timeout=timeout)


@dataclass(frozen=True)
class Locks:
    models: tuple[dict, ...]
    toolchains: tuple[dict, ...]

    @property
    def by_id(self) -> dict[str, dict]:
        result: dict[str, dict] = {}
        for entry in (*self.models, *self.toolchains):
            artifact_id = entry["id"]
            if artifact_id in result:
                raise FetchError(f"doppelte Artefakt-ID in Lockfiles: {artifact_id}")
            result[artifact_id] = entry
        return result


class Progress:
    """TTY-Balken oder periodische non-TTY-Zeilen; ein Download bleibt nie still."""

    def __init__(
        self,
        label: str,
        total: int,
        *,
        stream: TextIO = sys.stderr,
        interval_seconds: float = 5.0,
    ) -> None:
        self.label = label
        self.total = total
        self.stream = stream
        self.interval_seconds = interval_seconds
        self.started = time.monotonic()
        self.last = 0.0
        self.last_done: Optional[int] = None

    def update(self, done: int, *, force: bool = False) -> None:
        now = time.monotonic()
        if force and done == self.last_done:
            return
        if not force and now - self.last < self.interval_seconds:
            return
        self.last = now
        self.last_done = done
        elapsed = max(now - self.started, 0.001)
        pct = min(100.0, done * 100.0 / self.total) if self.total else 0.0
        rate = done / elapsed
        text = (
            f"[{self.label}] {pct:6.2f}%  {_human_bytes(done)} / "
            f"{_human_bytes(self.total)}  {_human_bytes(int(rate))}/s"
        )
        if self.stream.isatty():
            end = "\n" if force else "\r"
            print(text, end=end, file=self.stream, flush=True)
        else:
            print(text, file=self.stream, flush=True)


def _human_bytes(value: int) -> str:
    number = float(value)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if number < 1024.0 or unit == "TiB":
            return f"{number:.1f} {unit}"
        number /= 1024.0
    return f"{number:.1f} TiB"


def _load_json(path: Path) -> dict:
    try:
        parsed = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise FetchError(f"Lockfile fehlt: {path}") from exc
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise FetchError(f"Lockfile ist nicht lesbares JSON: {path}: {exc}") from exc
    if not isinstance(parsed, dict):
        raise FetchError(f"Lockfile-Wurzel muss ein Objekt sein: {path}")
    return parsed


def _positive_int(value: object, label: str) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value <= 0:
        raise FetchError(f"{label} muss eine positive Ganzzahl sein")
    return value


def _sha(value: object, label: str) -> str:
    if not isinstance(value, str) or not SHA256_RE.fullmatch(value):
        raise FetchError(f"{label} muss 64 kleine SHA-256-Hexzeichen tragen")
    return value


def _revision(value: object, label: str) -> str:
    if not isinstance(value, str) or not REVISION_RE.fullmatch(value):
        raise FetchError(f"{label} muss eine volle 40-stellige Commit-ID tragen")
    return value


def _relative_posix(value: object, label: str) -> PurePosixPath:
    if not isinstance(value, str) or not value:
        raise FetchError(f"{label} muss ein nichtleerer relativer Pfad sein")
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or "." in path.parts:
        raise FetchError(f"{label} darf das Ziel nicht verlassen: {value!r}")
    return path


def _https_url(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise FetchError(f"{label} muss eine HTTPS-URL sein")
    parsed = urllib.parse.urlsplit(value)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise FetchError(f"{label} muss eine credential-freie HTTPS-URL sein")
    lowered = value.lower()
    if "latest" in lowered or "/resolve/main/" in lowered:
        raise FetchError(f"{label} darf weder latest noch /resolve/main/ verwenden")
    return value


def load_locks(models_path: Path, toolchains_path: Path) -> Locks:
    models_root = _load_json(models_path)
    toolchains_root = _load_json(toolchains_path)
    if models_root.get("version") != 2:
        raise FetchError("models.json muss Schema version=2 tragen")
    if toolchains_root.get("version") != 1:
        raise FetchError("toolchains.lock.json muss Schema version=1 tragen")

    models = models_root.get("models")
    toolchains = toolchains_root.get("artifacts")
    if not isinstance(models, list) or not isinstance(toolchains, list):
        raise FetchError("Lockfile-Artefaktlisten fehlen")

    checked_models: list[dict] = []
    for raw in models:
        if not isinstance(raw, dict) or not isinstance(raw.get("id"), str):
            raise FetchError("jeder Modelleintrag braucht eine String-ID")
        entry = dict(raw)
        kind = entry.get("type")
        label = f"models.json[{entry['id']}]"
        if kind == "hf":
            _revision(entry.get("pinned_revision"), f"{label}.pinned_revision")
            artifacts = entry.get("artifacts")
            if not isinstance(artifacts, list) or not artifacts:
                raise FetchError(f"{label}.artifacts darf nicht leer sein")
            seen: set[str] = set()
            total = 0
            for index, artifact in enumerate(artifacts):
                if not isinstance(artifact, dict):
                    raise FetchError(f"{label}.artifacts[{index}] muss ein Objekt sein")
                path = str(_relative_posix(artifact.get("path"), f"{label}.artifacts[{index}].path"))
                if path in seen:
                    raise FetchError(f"{label} hat doppelte Runtime-Datei: {path}")
                seen.add(path)
                total += _positive_int(artifact.get("bytes"), f"{label}.{path}.bytes")
                _sha(artifact.get("sha256"), f"{label}.{path}.sha256")
            if entry.get("download_bytes") != total:
                raise FetchError(
                    f"{label}.download_bytes={entry.get('download_bytes')!r}, "
                    f"Summe der Dateien ist {total}"
                )
        elif kind == "hf-direct-file":
            _revision(entry.get("pinned_revision"), f"{label}.pinned_revision")
            _https_url(entry.get("source_url"), f"{label}.source_url")
            _positive_int(entry.get("expected_bytes"), f"{label}.expected_bytes")
            _sha(entry.get("expected_sha256"), f"{label}.expected_sha256")
            _relative_posix(entry.get("sidecar_local_path"), f"{label}.sidecar_local_path")
        elif kind != "ollama":
            raise FetchError(f"{label}.type ist unbekannt: {kind!r}")
        checked_models.append(entry)

    checked_toolchains: list[dict] = []
    for raw in toolchains:
        if not isinstance(raw, dict) or not isinstance(raw.get("id"), str):
            raise FetchError("jeder Toolchain-Eintrag braucht eine String-ID")
        entry = dict(raw)
        label = f"toolchains.lock.json[{entry['id']}]"
        if entry.get("type") != "jdk-tar-gz":
            raise FetchError(f"{label}.type ist unbekannt: {entry.get('type')!r}")
        _https_url(entry.get("url"), f"{label}.url")
        _positive_int(entry.get("bytes"), f"{label}.bytes")
        _positive_int(entry.get("installed_bytes"), f"{label}.installed_bytes")
        _sha(entry.get("sha256"), f"{label}.sha256")
        _relative_posix(entry.get("archive_root"), f"{label}.archive_root")
        _relative_posix(entry.get("java_home"), f"{label}.java_home")
        _relative_posix(entry.get("target_dir"), f"{label}.target_dir")
        if not isinstance(entry.get("release"), dict) or not entry["release"]:
            raise FetchError(f"{label}.release fehlt")
        checked_toolchains.append(entry)

    locks = Locks(tuple(checked_models), tuple(checked_toolchains))
    locks.by_id  # erzwingt globale ID-Eindeutigkeit
    return locks


def sha256_file(path: Path, *, progress: Optional[Progress] = None) -> str:
    digest = hashlib.sha256()
    done = 0
    with path.open("rb") as handle:
        while True:
            chunk = handle.read(8 * CHUNK_BYTES)
            if not chunk:
                break
            digest.update(chunk)
            done += len(chunk)
            if progress:
                progress.update(done)
    if progress:
        progress.update(done, force=True)
    return digest.hexdigest()


def verify_file(
    path: Path,
    expected_bytes: int,
    expected_sha256: str,
    *,
    stream: Optional[TextIO] = None,
    label: Optional[str] = None,
) -> None:
    if not path.is_file():
        raise MissingArtifact(f"Datei fehlt: {path}")
    actual_bytes = path.stat().st_size
    if actual_bytes != expected_bytes:
        raise FetchError(f"Groesse falsch: {path}: {actual_bytes} != {expected_bytes} Bytes")
    progress = Progress(label or f"hash:{path.name}", expected_bytes, stream=stream) if stream else None
    actual_sha = sha256_file(path, progress=progress)
    if actual_sha != expected_sha256:
        raise FetchError(f"SHA-256 falsch: {path}: {actual_sha} != {expected_sha256}")


def _fsync_directory(path: Path) -> None:
    try:
        descriptor = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    except OSError:
        pass
    finally:
        os.close(descriptor)


def ensure_free_space(path: Path, required_bytes: int, *, label: str) -> None:
    """Bricht vor neuen Bytes ab; vorhandene Partialbytes sind schon belegt."""

    required_bytes = max(0, required_bytes)
    free = shutil.disk_usage(_nearest_existing(path)).free
    if free < required_bytes:
        raise FetchError(
            f"{label}: zu wenig freier Platz: {_human_bytes(free)} frei, "
            f"mindestens {_human_bytes(required_bytes)} fuer Restdownload/Reserve benoetigt"
        )


def _content_range_starts_at(value: Optional[str], offset: int) -> bool:
    if value is None:
        return False
    match = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+|\*)", value.strip())
    return bool(match and int(match.group(1)) == offset)


def download_verified(
    url: str,
    target: Path,
    expected_bytes: int,
    expected_sha256: str,
    *,
    label: str,
    stream: TextIO = sys.stderr,
    timeout: float = 30.0,
    allow_http_for_tests: bool = False,
    progress_interval: float = 5.0,
    reserve_bytes: int = DIRECT_DOWNLOAD_RESERVE,
    extra_space_bytes: int = 0,
) -> Path:
    """Laedt nach ``target.partial`` und ersetzt das Ziel erst nach SHA-256.

    Ein vorhandenes, korrektes Ziel braucht kein Netz. Ein kaputtes aktives Ziel
    bleibt bis zum vollstaendig verifizierten Ersatz unangetastet.
    """

    parsed = urllib.parse.urlsplit(url)
    allowed_schemes = {"https", "http"} if allow_http_for_tests else {"https"}
    if parsed.scheme not in allowed_schemes or not parsed.hostname:
        raise FetchError(f"{label}: nur HTTPS ist erlaubt")
    if parsed.username or parsed.password:
        raise FetchError(f"{label}: Credentials duerfen nicht in der URL stehen")

    expected_bytes = _positive_int(expected_bytes, f"{label}.bytes")
    expected_sha256 = _sha(expected_sha256, f"{label}.sha256")
    target.parent.mkdir(parents=True, exist_ok=True)

    if target.is_file() and target.stat().st_size == expected_bytes:
        progress = Progress(f"{label}:hash", expected_bytes, stream=stream)
        if sha256_file(target, progress=progress) == expected_sha256:
            print(f"[{label}] bereits verifiziert — kein Netz", file=stream)
            return target

    partial = target.with_name(target.name + ".partial")
    offset = partial.stat().st_size if partial.is_file() else 0
    if offset == expected_bytes:
        progress = Progress(f"{label}:partial-hash", expected_bytes, stream=stream)
        actual_sha = sha256_file(partial, progress=progress)
        if actual_sha == expected_sha256:
            os.replace(partial, target)
            _fsync_directory(target.parent)
            print(f"[{label}] vollstaendige Partialdatei verifiziert + atomar aktiviert", file=stream)
            return target
        partial.unlink()
        offset = 0
    if offset > expected_bytes:
        with partial.open("wb"):
            pass
        offset = 0

    ensure_free_space(
        target.parent,
        (expected_bytes - offset) + max(0, extra_space_bytes) + max(0, reserve_bytes),
        label=label,
    )

    headers = {"User-Agent": "Hoshi-verified-fetch/1"}
    if offset:
        headers["Range"] = f"bytes={offset}-"
    request = urllib.request.Request(url, headers=headers)
    try:
        response = open_verified_url(request, timeout, allowed_schemes)
    except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
        raise FetchError(f"{label}: Download fehlgeschlagen ({type(exc).__name__})") from exc

    with response:
        status = getattr(response, "status", response.getcode())
        append = bool(
            offset
            and status == 206
            and _content_range_starts_at(response.headers.get("Content-Range"), offset)
        )
        if status not in (200, 206):
            raise FetchError(f"{label}: HTTP {status}")
        if status == 206 and not offset:
            raise FetchError(f"{label}: Server lieferte unerwartet Partial Content")
        if not append:
            offset = 0
        mode = "ab" if append else "wb"
        progress = Progress(label, expected_bytes, stream=stream, interval_seconds=progress_interval)
        done = offset
        progress.update(done, force=True)
        with partial.open(mode) as handle:
            while True:
                chunk = response.read(CHUNK_BYTES)
                if not chunk:
                    break
                handle.write(chunk)
                done += len(chunk)
                if done > expected_bytes:
                    raise FetchError(f"{label}: Server lieferte mehr als {expected_bytes} Bytes")
                progress.update(done)
            handle.flush()
            os.fsync(handle.fileno())
        progress.update(done, force=True)

    actual_bytes = partial.stat().st_size
    if actual_bytes != expected_bytes:
        raise FetchError(
            f"{label}: Download unvollstaendig ({actual_bytes} != {expected_bytes} Bytes); "
            f"Partial bleibt fuer Resume: {partial}"
        )
    print(f"[{label}] pruefe SHA-256 ...", file=stream, flush=True)
    actual_sha = sha256_file(partial)
    if actual_sha != expected_sha256:
        # Nur die von uns kontrollierte explizite Partialdatei wird entfernt;
        # ein eventuell vorhandenes aktives Ziel bleibt unangetastet.
        partial.unlink(missing_ok=True)
        raise FetchError(f"{label}: SHA-256-Mismatch ({actual_sha} != {expected_sha256})")
    os.replace(partial, target)
    _fsync_directory(target.parent)
    print(f"[{label}] verifiziert + atomar aktiviert: {target}", file=stream)
    return target


def _normalise_tar_path(path: PurePosixPath) -> PurePosixPath:
    parts: list[str] = []
    for part in path.parts:
        if part in ("", "."):
            continue
        if part == "..":
            if not parts:
                raise FetchError(f"Archivpfad verlaesst sein Ziel: {path}")
            parts.pop()
        else:
            parts.append(part)
    return PurePosixPath(*parts)


def validate_tar_members(members: Iterable[tarfile.TarInfo], archive_root: str) -> None:
    expected_root = _relative_posix(archive_root, "archive_root")
    if len(expected_root.parts) != 1:
        raise FetchError("archive_root muss genau ein Top-Level-Verzeichnis sein")
    root_name = expected_root.parts[0]
    for member in members:
        name = PurePosixPath(member.name)
        if name.is_absolute():
            raise FetchError(f"absoluter Archivpfad verboten: {member.name}")
        normal = _normalise_tar_path(name)
        if not normal.parts or normal.parts[0] != root_name:
            raise FetchError(f"Archivpfad liegt ausserhalb von {root_name}: {member.name}")
        if member.ischr() or member.isblk() or member.isfifo():
            raise FetchError(f"Spezialdatei im JDK-Archiv verboten: {member.name}")
        if member.issym():
            link = PurePosixPath(member.linkname)
            if link.is_absolute():
                raise FetchError(f"absoluter Symlink im Archiv verboten: {member.name}")
            target = _normalise_tar_path(normal.parent / link)
            if not target.parts or target.parts[0] != root_name:
                raise FetchError(f"Symlink verlaesst JDK-Wurzel: {member.name} -> {member.linkname}")
        if member.islnk():
            link = PurePosixPath(member.linkname)
            if link.is_absolute():
                raise FetchError(f"absoluter Hardlink im Archiv verboten: {member.name}")
            target = _normalise_tar_path(link)
            if not target.parts or target.parts[0] != root_name:
                raise FetchError(f"Hardlink verlaesst JDK-Wurzel: {member.name} -> {member.linkname}")


def _parse_release(path: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError) as exc:
        raise FetchError(f"JDK-release-Datei nicht lesbar: {path}") from exc
    for line in lines:
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        result[key] = value.strip().strip('"')
    return result


def verify_installed_jdk(entry: Mapping[str, object], install_root: Path) -> Path:
    java_home = install_root / Path(str(_relative_posix(entry["java_home"], "java_home")))
    java_binary = java_home / "bin" / "java"
    if not java_binary.is_file() or not os.access(java_binary, os.X_OK):
        raise FetchError(f"JDK hat kein ausfuehrbares bin/java: {java_binary}")
    release = _parse_release(java_home / "release")
    expected_release = entry.get("release")
    assert isinstance(expected_release, Mapping)
    for key, expected in expected_release.items():
        if release.get(str(key)) != expected:
            raise FetchError(
                f"JDK-release.{key}={release.get(str(key))!r}, erwartet {expected!r}"
            )
    attestation = install_root / ".hoshi-artifact.json"
    try:
        attested = json.loads(attestation.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise FetchError(f"JDK-Attestation fehlt/ist kaputt: {attestation}") from exc
    if attested != {"id": entry["id"], "sha256": entry["sha256"]}:
        raise FetchError(f"JDK-Attestation passt nicht zum Lock: {attestation}")
    return java_home


def install_jdk_archive(entry: Mapping[str, object], archive: Path, artifact_home: Path) -> Path:
    installs = artifact_home / "toolchains"
    installs.mkdir(parents=True, exist_ok=True)
    final = installs / str(_relative_posix(entry["target_dir"], "target_dir"))
    if final.exists():
        return verify_installed_jdk(entry, final)

    ensure_free_space(
        installs,
        int(entry["installed_bytes"]) + LARGE_ARTIFACT_RESERVE,
        label=str(entry["id"]),
    )

    temporary = Path(tempfile.mkdtemp(prefix=f".{entry['id']}.extracting-", dir=installs))
    try:
        with tarfile.open(archive, mode="r:gz") as bundle:
            members = bundle.getmembers()
            validate_tar_members(members, str(entry["archive_root"]))
            bundle.extractall(temporary, members=members)

        java_home = temporary / Path(str(_relative_posix(entry["java_home"], "java_home")))
        java_binary = java_home / "bin" / "java"
        if not java_binary.is_file() or not os.access(java_binary, os.X_OK):
            raise FetchError(f"entpacktes JDK hat kein ausfuehrbares bin/java: {java_binary}")
        release = _parse_release(java_home / "release")
        expected_release = entry["release"]
        assert isinstance(expected_release, Mapping)
        for key, expected in expected_release.items():
            if release.get(str(key)) != expected:
                raise FetchError(
                    f"entpacktes JDK release.{key}={release.get(str(key))!r}, erwartet {expected!r}"
                )
        attestation = temporary / ".hoshi-artifact.json"
        with attestation.open("w", encoding="utf-8") as handle:
            json.dump({"id": entry["id"], "sha256": entry["sha256"]}, handle, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, final)
        _fsync_directory(installs)
    except Exception:
        if temporary.exists():
            shutil.rmtree(temporary)
        raise
    return verify_installed_jdk(entry, final)


def _hf_cache_root(explicit: Optional[Path]) -> Path:
    if explicit is not None:
        return explicit.expanduser()
    if os.environ.get("HUGGINGFACE_HUB_CACHE"):
        return Path(os.environ["HUGGINGFACE_HUB_CACHE"]).expanduser()
    if os.environ.get("HF_HOME"):
        return Path(os.environ["HF_HOME"]).expanduser() / "hub"
    return Path.home() / ".cache" / "huggingface" / "hub"


def _hf_repo_dir(entry: Mapping[str, object], cache_root: Path) -> Path:
    repo = entry.get("hf_repo")
    if not isinstance(repo, str) or "/" not in repo:
        raise FetchError(f"{entry.get('id')}: ungueltiges hf_repo")
    return cache_root / ("models--" + repo.replace("/", "--"))


def verify_hf_snapshot(
    entry: Mapping[str, object],
    snapshot: Path,
    *,
    stream: Optional[TextIO] = None,
) -> None:
    revision = _revision(entry.get("pinned_revision"), f"{entry.get('id')}.pinned_revision")
    if snapshot.name != revision or not snapshot.is_dir():
        raise MissingArtifact(f"{entry.get('id')}: Snapshot fuer Pin fehlt: {snapshot}")
    artifacts = entry.get("artifacts")
    assert isinstance(artifacts, list)
    for artifact in artifacts:
        assert isinstance(artifact, Mapping)
        relative = _relative_posix(artifact.get("path"), f"{entry.get('id')}.artifact.path")
        candidate = snapshot / Path(str(relative))
        verify_file(
            candidate,
            _positive_int(artifact.get("bytes"), f"{entry.get('id')}.{relative}.bytes"),
            _sha(artifact.get("sha256"), f"{entry.get('id')}.{relative}.sha256"),
            stream=stream,
            label=f"{entry.get('id')}:{relative}",
        )


def _incomplete_files(repo_dir: Path) -> list[Path]:
    blobs = repo_dir / "blobs"
    return sorted(blobs.glob("*.incomplete")) if blobs.is_dir() else []


def activate_hf_ref(entry: Mapping[str, object], repo_dir: Path) -> None:
    revision = str(entry["pinned_revision"])
    refs = repo_dir / "refs"
    refs.mkdir(parents=True, exist_ok=True)
    target = refs / "main"
    if target.is_file() and target.read_bytes() == revision.encode("ascii"):
        return
    temporary = refs / f".main.hoshi-{os.getpid()}.partial"
    with temporary.open("wb") as handle:
        handle.write(revision.encode("ascii"))
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, target)
    _fsync_directory(refs)


SnapshotDownloader = Callable[..., str]


def fetch_hf_model(
    entry: Mapping[str, object],
    cache_root: Path,
    *,
    accepted_licenses: set[str],
    snapshot_downloader: Optional[SnapshotDownloader] = None,
    stream: TextIO = sys.stderr,
) -> Path:
    acceptance = entry.get("license_acceptance")
    if acceptance and acceptance not in accepted_licenses:
        raise FetchError(
            f"{entry['id']}: Lizenzentscheidung fehlt. Lies {entry.get('license_url')} und "
            f"wiederhole bewusst mit --accept-license {acceptance}"
        )
    revision = _revision(entry.get("pinned_revision"), f"{entry['id']}.pinned_revision")
    repo_dir = _hf_repo_dir(entry, cache_root)
    snapshot = repo_dir / "snapshots" / revision

    try:
        verify_hf_snapshot(entry, snapshot, stream=stream)
        incomplete = _incomplete_files(repo_dir)
        if incomplete:
            raise FetchError(
                f"{entry['id']}: verifizierter Snapshot, aber Cache enthaelt .incomplete: "
                + ", ".join(str(path) for path in incomplete)
            )
        activate_hf_ref(entry, repo_dir)
        print(f"[{entry['id']}] bereits vollstaendig + SHA-256-verifiziert — kein Netz", file=stream)
        return snapshot
    except MissingArtifact:
        # Ein fehlender/inkompletter Snapshot darf heruntergeladen bzw. resumed
        # werden. Ein bereits vorhandener Hash-Mismatch bleibt fail-closed: wir
        # zwingen kein Upstream-Redownload ueber mutierte lokale Bytes hinweg.
        pass


    artifacts = entry["artifacts"]
    assert isinstance(artifacts, list)
    missing_bytes = 0
    for artifact in artifacts:
        assert isinstance(artifact, Mapping)
        candidate = snapshot / str(artifact["path"])
        if not candidate.is_file() or candidate.stat().st_size != int(artifact["bytes"]):
            missing_bytes += int(artifact["bytes"])
    ensure_free_space(
        cache_root,
        missing_bytes + LARGE_ARTIFACT_RESERVE,
        label=str(entry["id"]),
    )

    if snapshot_downloader is None:
        try:
            from huggingface_hub import snapshot_download
        except ImportError as exc:
            raise FetchError(
                "huggingface_hub fehlt. Nutze den gepinnten Interpreter: "
                "sidecars/brain/.venv/bin/python tools/verified_fetch.py ..."
            ) from exc
        snapshot_downloader = snapshot_download

    try:
        from tqdm.auto import tqdm
    except ImportError as exc:
        raise FetchError("tqdm fehlt im gepinnten HF-Interpreter; Fortschritt waere sonst still") from exc

    class VisibleTqdm(tqdm):
        """HF-Fortschritt bleibt auch in Logs/CI sichtbar statt auto-disabled."""

        def __init__(self, *args, **kwargs):
            kwargs["disable"] = False
            kwargs.setdefault("file", stream)
            kwargs.setdefault("mininterval", 1.0)
            super().__init__(*args, **kwargs)

    allow_patterns = [str(artifact["path"]) for artifact in artifacts]
    print(
        f"[{entry['id']}] HF {entry['hf_repo']} @ {revision} — "
        f"{_human_bytes(int(entry['download_bytes']))}",
        file=stream,
    )
    try:
        returned = snapshot_downloader(
            repo_id=entry["hf_repo"],
            revision=revision,
            allow_patterns=allow_patterns,
            cache_dir=str(cache_root),
            tqdm_class=VisibleTqdm,
        )
    except Exception as exc:  # noqa: BLE001 — optionale Bibliothek hat viele Fehlertypen
        raise FetchError(
            f"{entry['id']}: HF-Download fehlgeschlagen ({type(exc).__name__}). "
            "Bei 401/403 die Modellbedingungen im Browser akzeptieren und `hf auth login` nutzen."
        ) from exc
    returned_snapshot = Path(returned)
    if returned_snapshot.name != revision:
        raise FetchError(
            f"{entry['id']}: Downloader gab Revision {returned_snapshot.name!r} statt Pin {revision!r} zurueck"
        )
    verify_hf_snapshot(entry, returned_snapshot, stream=stream)
    incomplete = _incomplete_files(repo_dir)
    if incomplete:
        raise FetchError(
            f"{entry['id']}: Download meldet fertig, aber .incomplete bleibt: "
            + ", ".join(str(path) for path in incomplete)
        )
    activate_hf_ref(entry, repo_dir)
    print(f"[{entry['id']}] alle Runtime-Dateien SHA-256-verifiziert; refs/main aktiviert", file=stream)
    return returned_snapshot


def fetch_direct_model(entry: Mapping[str, object], *, stream: TextIO = sys.stderr) -> Path:
    target = REPO_ROOT / Path(str(_relative_posix(entry["sidecar_local_path"], "sidecar_local_path")))
    return download_verified(
        str(entry["source_url"]),
        target,
        int(entry["expected_bytes"]),
        str(entry["expected_sha256"]),
        label=str(entry["id"]),
        stream=stream,
        reserve_bytes=DIRECT_DOWNLOAD_RESERVE,
    )


def fetch_toolchain(entry: Mapping[str, object], artifact_home: Path, *, stream: TextIO = sys.stderr) -> Path:
    system = platform.system().lower()
    machine = platform.machine().lower()
    expected_platform = entry.get("platform")
    assert isinstance(expected_platform, Mapping)
    if system != expected_platform.get("os") or machine != expected_platform.get("arch"):
        raise FetchError(
            f"{entry['id']}: Plattform {system}/{machine}, Lock gilt fuer "
            f"{expected_platform.get('os')}/{expected_platform.get('arch')}"
        )
    downloads = artifact_home / "downloads"
    filename = Path(urllib.parse.unquote(urllib.parse.urlsplit(str(entry["url"])).path)).name
    archive = download_verified(
        str(entry["url"]),
        downloads / filename,
        int(entry["bytes"]),
        str(entry["sha256"]),
        label=str(entry["id"]),
        stream=stream,
        reserve_bytes=LARGE_ARTIFACT_RESERVE,
        extra_space_bytes=int(entry["installed_bytes"]),
    )
    java_home = install_jdk_archive(entry, archive, artifact_home)
    print(f"[{entry['id']}] JAVA_HOME={java_home}", file=stream)
    return java_home


def _nearest_existing(path: Path) -> Path:
    current = path.expanduser().resolve(strict=False)
    while not current.exists() and current != current.parent:
        current = current.parent
    return current


def select_for_profile(locks: Locks, profile: str) -> list[dict]:
    selected = [
        entry for entry in locks.toolchains
        if profile in entry.get("profiles", [])
    ]
    # Bestehendes required-Flag bleibt die Produktwahrheit; der Fetcher erfindet
    # keinen zweiten Feature-/Profil-Resolver.
    selected.extend(entry for entry in locks.models if entry.get("required") is True)
    return selected


def estimated_remaining_download(entry: Mapping[str, object], artifact_home: Path, hf_cache: Path) -> int:
    """Schnelle Schaetzung ueber Pfad+Groesse; kryptografische Wahrheit bleibt verify."""

    kind = entry["type"]
    if kind == "jdk-tar-gz":
        filename = Path(urllib.parse.unquote(urllib.parse.urlsplit(str(entry["url"])).path)).name
        archive = artifact_home / "downloads" / filename
        return 0 if archive.is_file() and archive.stat().st_size == int(entry["bytes"]) else int(entry["bytes"])
    if kind == "hf":
        snapshot = _hf_repo_dir(entry, hf_cache) / "snapshots" / str(entry["pinned_revision"])
        remaining = 0
        artifacts = entry["artifacts"]
        assert isinstance(artifacts, list)
        for artifact in artifacts:
            candidate = snapshot / str(artifact["path"])
            if not candidate.is_file() or candidate.stat().st_size != int(artifact["bytes"]):
                remaining += int(artifact["bytes"])
        return remaining
    if kind == "hf-direct-file":
        target = REPO_ROOT / str(entry["sidecar_local_path"])
        return 0 if target.is_file() and target.stat().st_size == int(entry["expected_bytes"]) else int(entry["expected_bytes"])
    return 0


def print_plan(locks: Locks, profile: str, artifact_home: Path, hf_cache: Path) -> int:
    selected = select_for_profile(locks, profile)
    if not selected:
        raise FetchError(f"Profil hat keine gelockten Artefakte: {profile}")
    total = 0
    remaining_total = 0
    print(f"Hoshi Artefakt-Plan — Profil {profile}")
    for entry in selected:
        kind = entry["type"]
        if kind == "jdk-tar-gz":
            size = int(entry["bytes"])
            target = artifact_home / "toolchains" / str(entry["target_dir"])
            note = (
                f"{entry['license']} · entpackt {_human_bytes(int(entry['installed_bytes']))} "
                f"· Ziel {target}"
            )
        elif kind == "hf":
            size = int(entry["download_bytes"])
            target = _hf_repo_dir(entry, hf_cache) / "snapshots" / str(entry["pinned_revision"])
            acceptance = f" · Zustimmung --accept-license {entry['license_acceptance']}" if entry.get("license_acceptance") else ""
            note = f"{entry['license']}{acceptance} · Cache {target}"
        elif kind == "hf-direct-file":
            size = int(entry["expected_bytes"])
            target = REPO_ROOT / str(entry["sidecar_local_path"])
            note = f"{entry['license']} · Ziel {target}"
        else:
            print(f"  {entry['id']:<28} MANUELL  {entry.get('ollama_name')} (kein gelockter Hashvertrag)")
            continue
        total += size
        remaining = estimated_remaining_download(entry, artifact_home, hf_cache)
        remaining_total += remaining
        print(
            f"  {entry['id']:<28} {_human_bytes(size):>12}  "
            f"noch {_human_bytes(remaining):>10}  {note}"
        )
    free = shutil.disk_usage(_nearest_existing(artifact_home)).free
    print(f"\nGelockte Downloadsumme: {_human_bytes(total)}")
    print(f"Voraussichtlicher Restdownload (Pfad+Groesse): {_human_bytes(remaining_total)}")
    print(f"Freier Platz am Artefaktziel: {_human_bytes(free)}")
    print(
        "Fetch reserviert zusaetzlich 512 MiB fuer grosse Artefakte/JDK-Extraktion; "
        "verify prueft die kryptografische Wahrheit."
    )
    print("Plan ist read-only; es wurden keine Bedingungen akzeptiert und keine Bytes geladen.")
    return 0


def _selected_entries(locks: Locks, ids: Sequence[str], profile: Optional[str]) -> list[dict]:
    if ids and profile:
        raise FetchError("Artefakt-IDs und --profile duerfen nicht gleichzeitig gesetzt sein")
    if profile:
        return select_for_profile(locks, profile)
    if not ids:
        raise FetchError("mindestens eine Artefakt-ID oder --profile ist erforderlich")
    by_id = locks.by_id
    missing = [artifact_id for artifact_id in ids if artifact_id not in by_id]
    if missing:
        raise FetchError(f"unbekannte Artefakt-ID(s): {', '.join(missing)}")
    return [by_id[artifact_id] for artifact_id in ids]


def verify_entry(entry: Mapping[str, object], artifact_home: Path, hf_cache: Path) -> Path:
    kind = entry["type"]
    if kind == "hf":
        repo_dir = _hf_repo_dir(entry, hf_cache)
        snapshot = repo_dir / "snapshots" / str(entry["pinned_revision"])
        verify_hf_snapshot(entry, snapshot, stream=sys.stderr)
        incomplete = _incomplete_files(repo_dir)
        if incomplete:
            raise FetchError(f"{entry['id']}: .incomplete-Reste im HF-Cache")
        ref = repo_dir / "refs" / "main"
        if not ref.is_file() or ref.read_bytes() != str(entry["pinned_revision"]).encode("ascii"):
            raise FetchError(f"{entry['id']}: refs/main zeigt nicht bytegenau auf den Pin")
        return snapshot
    if kind == "hf-direct-file":
        target = REPO_ROOT / str(entry["sidecar_local_path"])
        verify_file(
            target,
            int(entry["expected_bytes"]),
            str(entry["expected_sha256"]),
            stream=sys.stderr,
            label=f"{entry['id']}:hash",
        )
        return target
    if kind == "jdk-tar-gz":
        downloads = artifact_home / "downloads"
        filename = Path(urllib.parse.unquote(urllib.parse.urlsplit(str(entry["url"])).path)).name
        verify_file(
            downloads / filename,
            int(entry["bytes"]),
            str(entry["sha256"]),
            stream=sys.stderr,
            label=f"{entry['id']}:archive-hash",
        )
        return verify_installed_jdk(entry, artifact_home / "toolchains" / str(entry["target_dir"]))
    raise FetchError(f"{entry['id']}: {kind} hat keinen kryptografischen Offline-Vertrag")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--models-lock", type=Path, default=DEFAULT_MODELS_LOCK)
    parser.add_argument("--toolchains-lock", type=Path, default=DEFAULT_TOOLCHAINS_LOCK)
    parser.add_argument(
        "--artifact-home",
        type=Path,
        default=Path(os.environ.get("HOSHI_ARTIFACT_HOME", "~/.cache/hoshi")).expanduser(),
    )
    parser.add_argument("--hf-cache", type=Path, default=None)
    sub = parser.add_subparsers(dest="command", required=True)

    plan = sub.add_parser("plan", help="read-only Download-/Lizenz-/Plattenplan")
    plan.add_argument("--profile", default="local-mac")

    fetch = sub.add_parser("fetch", help="gelockte Artefakte laden + verifizieren")
    fetch.add_argument("ids", nargs="+")
    fetch.add_argument("--accept-license", action="append", default=[])

    verify = sub.add_parser("verify", help="gelockte Artefakte vollstaendig offline hashen")
    verify.add_argument("ids", nargs="*")
    verify.add_argument("--profile")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        locks = load_locks(args.models_lock, args.toolchains_lock)
        artifact_home = args.artifact_home.expanduser()
        hf_cache = _hf_cache_root(args.hf_cache)
        if args.command == "plan":
            return print_plan(locks, args.profile, artifact_home, hf_cache)
        if args.command == "fetch":
            accepted = set(args.accept_license)
            for entry in _selected_entries(locks, args.ids, None):
                kind = entry["type"]
                if kind == "hf":
                    fetch_hf_model(entry, hf_cache, accepted_licenses=accepted)
                elif kind == "hf-direct-file":
                    fetch_direct_model(entry)
                elif kind == "jdk-tar-gz":
                    fetch_toolchain(entry, artifact_home)
                else:
                    raise FetchError(
                        f"{entry['id']}: Ollama-Artefakte werden nicht als hashverifiziert ausgegeben; "
                        f"bewusst manuell: ollama pull {entry.get('ollama_name')}"
                    )
            return 0
        for entry in _selected_entries(locks, args.ids, args.profile):
            verified = verify_entry(entry, artifact_home, hf_cache)
            print(f"[OK] {entry['id']}: {verified}")
        return 0
    except FetchError as exc:
        print(f"[verified-fetch] FATAL: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
