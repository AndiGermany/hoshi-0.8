"""Versionierter, rein öffentlicher Vertrag für Hoshi-Knowledge-Packs.

Das Manifest liegt neben der SQLite-Datei und beschreibt ausschließlich
öffentliches Korpusmaterial. Private Laufzeitdaten gehören nie in diesen
Vertrag. Die Runtime prüft standardmäßig nur billige Metadaten. Für einen
Release-Benchmark kann sie vor READY zusätzlich den tatsächlichen DB-Inhalt
vollständig hashen; das bleibt bewusst opt-in.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any, Optional


SCHEMA_VERSION = 1
DEFAULT_MANIFEST_NAME = "manifest.json"
PACK_DATABASE_NAME = "pack.sqlite"
_PACK_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
_SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_RELEASE_STATUSES = {"forensic-non-release", "release-candidate"}


class ManifestError(ValueError):
    """Das Manifest ist vorhanden, aber nicht vertrauenswürdig interpretierbar."""


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ManifestError(f"{field} muss ein JSON-Objekt sein")
    return value


def _text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ManifestError(f"{field} muss ein nicht-leerer String sein")
    return value.strip()


def _https_url(value: Any, field: str) -> str:
    url = _text(value, field)
    if not url.startswith("https://"):
        raise ManifestError(f"{field} muss eine öffentliche HTTPS-URL sein")
    return url


def _integer(value: Any, field: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ManifestError(f"{field} muss eine Ganzzahl >= {minimum} sein")
    return value


def _enabled(value: str) -> bool:
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _fingerprint(value: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def _sqlite_sidecars(db_path: Path) -> list[str]:
    return [
        candidate.name
        for candidate in (
            Path(str(db_path) + "-wal"),
            Path(str(db_path) + "-shm"),
            Path(str(db_path) + "-journal"),
        )
        if candidate.exists()
    ]


def _verified_database_bytes(
    db_path: Path,
    expected_sha256: str,
) -> tuple[str, tuple[int, int, int, int, int]]:
    sidecars = _sqlite_sidecars(db_path)
    if sidecars:
        raise ManifestError(
            "Content-Verifikation verweigert SQLite-Sidecars: "
            + ", ".join(sidecars)
        )
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(db_path, flags)
    except OSError as exc:
        raise ManifestError(f"Knowledge-DB ist nicht sicher lesbar: {exc}") from exc
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ManifestError("Knowledge-DB ist keine reguläre Datei")
        digest = hashlib.sha256()
        with os.fdopen(os.dup(descriptor), "rb") as handle:
            for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
                digest.update(chunk)
        after = os.fstat(descriptor)
        if _fingerprint(after) != _fingerprint(before):
            raise ManifestError(
                "Knowledge-DB änderte sich während der Content-Verifikation"
            )
    finally:
        os.close(descriptor)
    try:
        path_state = db_path.lstat()
    except OSError as exc:
        raise ManifestError(f"Knowledge-DB ist nach dem Hash nicht prüfbar: {exc}") from exc
    observed_fingerprint = _fingerprint(path_state)
    if observed_fingerprint != _fingerprint(before):
        raise ManifestError("Knowledge-DB-Pfad änderte sich während der Content-Verifikation")
    if _sqlite_sidecars(db_path):
        raise ManifestError("Während der Content-Verifikation entstand ein SQLite-Sidecar")
    observed = digest.hexdigest()
    if observed != expected_sha256:
        raise ManifestError(
            "DB-SHA-256 stimmt nicht: "
            f"Manifest={expected_sha256}, tatsächlich={observed}"
        )
    return observed, observed_fingerprint


@dataclass(frozen=True)
class KnowledgePackManifest:
    """Die kleine Runtime-Sicht auf ein validiertes Manifest."""

    path: Path
    pack_id: str
    language: str
    source_name: str
    source_url: str
    source_dump_date: str
    source_dump_size_bytes: Optional[int]
    source_dump_sha1: Optional[str]
    source_dump_sha256: Optional[str]
    license_spdx: str
    notice_file: str
    database_file: str
    database_sha256: str
    database_size_bytes: int
    article_count: int
    retrieval_method: str
    revision_coverage: str
    release_status: Optional[str]
    raw: dict[str, Any]

    def public_summary(self) -> dict[str, Any]:
        source = {
            "name": self.source_name,
            "url": self.source_url,
            "dumpDate": self.source_dump_date,
            "license": self.license_spdx,
            "noticeFile": self.notice_file,
            "revisionCoverage": self.revision_coverage,
        }
        if (
            self.source_dump_size_bytes is not None
            and self.source_dump_sha1 is not None
            and self.source_dump_sha256 is not None
        ):
            source["dump"] = {
                "sizeBytes": self.source_dump_size_bytes,
                "sha1": self.source_dump_sha1,
                "sha256": self.source_dump_sha256,
            }
        result = {
            "schemaVersion": SCHEMA_VERSION,
            "packId": self.pack_id,
            "language": self.language,
            "source": source,
            "database": {
                "file": self.database_file,
                "sha256": self.database_sha256,
                "sizeBytes": self.database_size_bytes,
                "articleCount": self.article_count,
            },
            "retrieval": {"method": self.retrieval_method},
        }
        if self.release_status is not None:
            result["releaseStatus"] = self.release_status
        return result


@dataclass(frozen=True)
class KnowledgePackState:
    """Ehrlicher Runtime-Zustand: manifestiert oder explizit Legacy."""

    status: str
    manifest: Optional[KnowledgePackManifest]
    manifest_path: Optional[Path]
    content_sha256_verified: bool = False
    actual_database_sha256: Optional[str] = None
    database_fingerprint: Optional[tuple[int, int, int, int, int]] = None

    @property
    def pack_id(self) -> Optional[str]:
        return self.manifest.pack_id if self.manifest else None

    def assert_database_unchanged(self, db_path: Path) -> None:
        """Bindet jeden Query-Open an den vor READY gehashten Inode."""

        if not self.content_sha256_verified:
            return
        if self.database_fingerprint is None:
            raise ManifestError("Content-verifizierter Zustand besitzt keinen Dateifingerprint")
        sidecars = _sqlite_sidecars(db_path)
        if sidecars:
            raise ManifestError(
                "Content-verifizierte DB besitzt SQLite-Sidecars: "
                + ", ".join(sidecars)
            )
        try:
            observed = db_path.lstat()
        except OSError as exc:
            raise ManifestError(f"Content-verifizierte DB ist nicht prüfbar: {exc}") from exc
        if _fingerprint(observed) != self.database_fingerprint:
            raise ManifestError(
                "Content-verifizierte DB änderte sich nach dem Start"
            )

    def public_summary(self) -> dict[str, Any]:
        verification = {
            "contentSha256Verified": self.content_sha256_verified,
            "actualDatabaseSha256": self.actual_database_sha256,
        }
        if self.manifest is None:
            return {
                "status": self.status,
                "schemaVersion": None,
                "packId": None,
                "verification": verification,
            }
        return {
            "status": self.status,
            "verification": verification,
            **self.manifest.public_summary(),
        }


def parse_manifest(path: Path, db_path: Path) -> KnowledgePackManifest:
    """Liest und validiert ein Manifest ohne die große DB zu hashen."""

    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ManifestError(f"Manifest fehlt: {path}") from exc
    except (OSError, json.JSONDecodeError) as exc:
        raise ManifestError(f"Manifest nicht lesbar: {path}: {exc}") from exc

    root = _object(raw, "manifest")
    schema_version = _integer(root.get("schemaVersion"), "schemaVersion", 1)
    if schema_version != SCHEMA_VERSION:
        raise ManifestError(
            f"schemaVersion={schema_version} wird nicht unterstützt; erwartet {SCHEMA_VERSION}"
        )

    pack_id = _text(root.get("packId"), "packId")
    if not _PACK_ID_RE.fullmatch(pack_id):
        raise ManifestError("packId darf nur Kleinbuchstaben, Ziffern, Punkt, _ und - enthalten")

    language = _text(root.get("language"), "language")
    release_status = root.get("releaseStatus")
    if release_status is not None:
        release_status = _text(release_status, "releaseStatus")
        if release_status not in _RELEASE_STATUSES:
            raise ManifestError(f"unbekannter releaseStatus: {release_status!r}")
    source = _object(root.get("source"), "source")
    source_name = _text(source.get("name"), "source.name")
    source_url = _https_url(source.get("url"), "source.url")
    source_dump_date = _text(source.get("dumpDate"), "source.dumpDate")
    try:
        date.fromisoformat(source_dump_date)
    except ValueError as exc:
        raise ManifestError("source.dumpDate muss YYYY-MM-DD sein") from exc
    source_dump = source.get("dump")
    source_dump_size_bytes: Optional[int] = None
    source_dump_sha1: Optional[str] = None
    source_dump_sha256: Optional[str] = None
    if source_dump is not None:
        dump = _object(source_dump, "source.dump")
        source_dump_size_bytes = _integer(
            dump.get("sizeBytes"),
            "source.dump.sizeBytes",
            1,
        )
        source_dump_sha1 = _text(dump.get("sha1"), "source.dump.sha1").lower()
        source_dump_sha256 = _text(
            dump.get("sha256"),
            "source.dump.sha256",
        ).lower()
        if not _SHA1_RE.fullmatch(source_dump_sha1):
            raise ManifestError("source.dump.sha1 muss ein SHA-1-Hexwert sein")
        if not _SHA256_RE.fullmatch(source_dump_sha256):
            raise ManifestError("source.dump.sha256 muss ein SHA-256-Hexwert sein")
    license_spdx = _text(source.get("license"), "source.license")
    notice_file = _text(source.get("noticeFile"), "source.noticeFile")
    if Path(notice_file).name != notice_file:
        raise ManifestError("source.noticeFile muss ein Dateiname ohne Verzeichnisteile sein")
    if not (path.parent / notice_file).is_file():
        raise ManifestError(f"Lizenz-/Attributionshinweis fehlt: {path.parent / notice_file}")
    revision_coverage = _text(
        source.get("revisionCoverage", "page-id-only"),
        "source.revisionCoverage",
    )
    if revision_coverage not in {"page-id-only", "per-article"}:
        raise ManifestError("source.revisionCoverage muss page-id-only oder per-article sein")

    database = _object(root.get("database"), "database")
    database_file = _text(database.get("file"), "database.file")
    if database_file != PACK_DATABASE_NAME:
        raise ManifestError(f"database.file muss exakt {PACK_DATABASE_NAME} sein")
    if database_file != db_path.name:
        raise ManifestError(
            f"database.file={database_file!r} passt nicht zur gestarteten DB {db_path.name!r}"
        )
    database_sha256 = _text(database.get("sha256"), "database.sha256").lower()
    if not _SHA256_RE.fullmatch(database_sha256):
        raise ManifestError("database.sha256 muss ein SHA-256-Hexwert sein")
    database_size_bytes = _integer(database.get("sizeBytes"), "database.sizeBytes", 1)
    article_count = _integer(database.get("articleCount"), "database.articleCount", 1)

    retrieval = _object(root.get("retrieval"), "retrieval")
    retrieval_method = _text(retrieval.get("method"), "retrieval.method")

    # Billiges Start-Gate: verhindert Verwechslung/abgebrochene Kopien. Der teure
    # Inhalts-Hash wird durch verify_pack.py einmalig beim Installieren geprüft.
    try:
        actual_size = db_path.stat().st_size
    except OSError as exc:
        raise ManifestError(f"Knowledge-DB nicht lesbar: {db_path}: {exc}") from exc
    if actual_size != database_size_bytes:
        raise ManifestError(
            f"DB-Größe stimmt nicht: Manifest={database_size_bytes}, Datei={actual_size}"
        )

    return KnowledgePackManifest(
        path=path,
        pack_id=pack_id,
        language=language,
        source_name=source_name,
        source_url=source_url,
        source_dump_date=source_dump_date,
        source_dump_size_bytes=source_dump_size_bytes,
        source_dump_sha1=source_dump_sha1,
        source_dump_sha256=source_dump_sha256,
        license_spdx=license_spdx,
        notice_file=notice_file,
        database_file=database_file,
        database_sha256=database_sha256,
        database_size_bytes=database_size_bytes,
        article_count=article_count,
        retrieval_method=retrieval_method,
        revision_coverage=revision_coverage,
        release_status=release_status,
        raw=root,
    )


def load_pack_state(
    db_path: Path,
    *,
    explicit_manifest: Optional[str] = None,
    require_manifest: Optional[bool] = None,
    verify_content: Optional[bool] = None,
) -> KnowledgePackState:
    """Entdeckt das Manifest; ein vorhandenes kaputtes Manifest ist fatal.

    Ohne Manifest bleibt die historische Datenbank startbar, wird aber sichtbar
    als ``legacy-unmanifested`` ausgewiesen. Sobald ein expliziter Pfad gesetzt
    oder ``HOSHI_KNOWLEDGE_REQUIRE_MANIFEST=true`` ist, ist Fehlen ein Fehler.
    """

    explicit = explicit_manifest
    if explicit is None:
        explicit = os.environ.get("HOSHI_KNOWLEDGE_MANIFEST_PATH")
    required = require_manifest
    if required is None:
        required = os.environ.get("HOSHI_KNOWLEDGE_REQUIRE_MANIFEST", "false").lower() in {
            "1",
            "true",
            "yes",
            "on",
        }
    verify = verify_content
    if verify is None:
        verify = _enabled(
            os.environ.get("HOSHI_KNOWLEDGE_VERIFY_CONTENT_AT_START", "false")
        )

    manifest_path = Path(explicit).expanduser() if explicit else db_path.parent / DEFAULT_MANIFEST_NAME
    if not manifest_path.exists():
        if explicit or required or verify:
            raise ManifestError(f"Knowledge-Pack-Manifest ist erforderlich, fehlt aber: {manifest_path}")
        return KnowledgePackState(
            status="legacy-unmanifested",
            manifest=None,
            manifest_path=manifest_path,
        )

    manifest = parse_manifest(manifest_path, db_path)
    if verify:
        observed_sha256, observed_fingerprint = _verified_database_bytes(
            db_path,
            manifest.database_sha256,
        )
        return KnowledgePackState(
            status="manifest-content-verified",
            manifest=manifest,
            manifest_path=manifest_path,
            content_sha256_verified=True,
            actual_database_sha256=observed_sha256,
            database_fingerprint=observed_fingerprint,
        )
    return KnowledgePackState(
        status="manifest-valid-metadata-only",
        manifest=manifest,
        manifest_path=manifest_path,
    )
