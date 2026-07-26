#!/usr/bin/env python3
"""Prüft ein Hoshi-Knowledge-Pack vor Installation oder Veröffentlichung."""

from __future__ import annotations

import argparse
import contextlib
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "sidecars" / "knowledge"))

import pack_manifest as _pack_manifest_module  # noqa: E402
from pack_manifest import ManifestError, parse_manifest  # noqa: E402

if Path(_pack_manifest_module.__file__).resolve() != (
    REPO_ROOT / "sidecars" / "knowledge" / "pack_manifest.py"
).resolve():
    raise RuntimeError(
        "pack_manifest wurde nicht aus sidecars/knowledge geladen"
    )


REQUIRED_TABLES = {
    "articles",
    "classifications",
    "classifications_fts",
    "article_sources",
}
PRIVATE_TABLES = {
    "external_lookups",
    "build_progress",
    "user_notes",
    "lookup_notes",
}
PUBLIC_TABLES = {
    "articles",
    "classifications",
    "classifications_fts",
    "classifications_fts_data",
    "classifications_fts_idx",
    "classifications_fts_content",
    "classifications_fts_docsize",
    "classifications_fts_config",
    "article_sources",
    "sqlite_sequence",
}
_SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
_UTC_TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_DUMP_UPDATED_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
_RELEASE_STATUSES = {None, "forensic-non-release", "release-candidate"}
_DUMP_JOB = "articlesmultistreamdumprecombine"
_DUMPSTATUS_FILE = "dumpstatus.json"
_SELECTION_FILE = "selection.jsonl"
_MAX_DUMPSTATUS_BYTES = 16 * 1024 * 1024
_BUILDER_SOURCE = "tools/knowledge-pack/build_pack_from_dump.py"
_TRANSFORM_ID = "hoshi-pack-v1-direct-dump-conservative-lead-v1"
_MAX_RELEASE_PACK_BYTES = 512 * 1024 * 1024
_USER_AGENT = "Hoshi-Knowledge-Pack-Verifier/1.0"
_RELEASE_FILES = {
    "manifest.json",
    "NOTICE.md",
    "dumpstatus.json",
    "selection.jsonl",
    "pack.sqlite",
}
_RELEASE_FILE_MAX_BYTES = {
    "manifest.json": 1024 * 1024,
    "NOTICE.md": 1024 * 1024,
    "dumpstatus.json": 64 * 1024,
    "selection.jsonl": 16 * 1024 * 1024,
    "pack.sqlite": _MAX_RELEASE_PACK_BYTES,
}


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _authority_opener():
    """Autoritätsabruf ohne Systemproxy und ohne Kontakt zu Redirectzielen."""

    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        _NoRedirectHandler(),
    ).open


def _readonly_uri(path: Path) -> str:
    """Öffnet exakt die Hauptdatei, ohne %-Umdeutung oder WAL-Überlagerung."""

    absolute = str(path.resolve())
    return (
        "file:"
        + urllib.parse.quote(absolute, safe="/")
        + "?mode=ro&immutable=1"
    )


def _release_text(value, field):
    if not isinstance(value, str) or not value.strip():
        raise ManifestError(f"{field} muss für einen Release-Pack gesetzt sein")
    return value.strip()


def _release_integer(value, field, minimum=0):
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ManifestError(f"{field} muss eine Ganzzahl >= {minimum} sein")
    return value


def _exact_keys(value: dict, expected: set[str], field: str) -> None:
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        details = []
        if missing:
            details.append("fehlt=" + ",".join(missing))
        if unexpected:
            details.append("unerwartet=" + ",".join(unexpected))
        raise ManifestError(f"{field} besitzt nicht exakt das Release-Schema ({'; '.join(details)})")


def _utc_timestamp(value, field):
    timestamp = _release_text(value, field)
    if not _UTC_TIMESTAMP_RE.fullmatch(timestamp):
        raise ManifestError(f"{field} muss UTC/RFC3339 im Format YYYY-MM-DDTHH:MM:SSZ sein")
    try:
        return datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ").replace(
            tzinfo=timezone.utc
        )
    except ValueError as exc:
        raise ManifestError(f"{field} ist kein gültiger UTC-Zeitpunkt") from exc


def _dump_updated(value, field) -> str:
    updated = _release_text(value, field)
    if not _DUMP_UPDATED_RE.fullmatch(updated):
        raise ManifestError(f"{field} muss YYYY-MM-DD HH:MM:SS sein")
    try:
        datetime.strptime(updated, "%Y-%m-%d %H:%M:%S")
    except ValueError as exc:
        raise ManifestError(f"{field} ist kein gültiger Zeitpunkt") from exc
    return updated


def _canonical_dumpstatus_evidence(source: dict, updated: str) -> bytes:
    dump_date = source["dumpDate"].replace("-", "")
    filename = f"dewiki-{dump_date}-pages-articles-multistream.xml.bz2"
    payload = {
        "schema": "hoshi-wikimedia-dump-evidence-v1",
        "job": _DUMP_JOB,
        "status": "done",
        "updated": updated,
        "file": {
            "url": f"/dewiki/{dump_date}/{filename}",
            "size": source["dump"]["sizeBytes"],
            "sha1": source["dump"]["sha1"].lower(),
        },
    }
    return (
        json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _validate_dumpstatus_payload(raw, source, field):
    """Projiziert eine frische Wikimedia-Antwort auf den kanonischen Beleg."""

    if len(raw) > _MAX_DUMPSTATUS_BYTES:
        raise ManifestError(f"{field} überschreitet das Größenlimit")
    dump_date = source["dumpDate"].replace("-", "")
    filename = f"dewiki-{dump_date}-pages-articles-multistream.xml.bz2"
    try:
        root = json.loads(raw.decode("utf-8"))
        job = root["jobs"][_DUMP_JOB]
        item = job["files"][filename]
    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        KeyError,
        TypeError,
    ) as exc:
        raise ManifestError(f"{field} enthält keinen erwarteten Artikel-Dump") from exc
    if job.get("status") != "done":
        raise ManifestError(f"{field} meldet den Artikel-Dump nicht als done")
    if item.get("url") != f"/dewiki/{dump_date}/{filename}":
        raise ManifestError(f"{field} enthält keinen kanonischen Dump-Pfad")
    dump = source["dump"]
    if item.get("size") != dump["sizeBytes"]:
        raise ManifestError(f"{field} stimmt bei sizeBytes nicht mit source.dump überein")
    if str(item.get("sha1", "")).lower() != dump["sha1"].lower():
        raise ManifestError(f"{field} stimmt bei SHA-1 nicht mit source.dump überein")
    updated = _dump_updated(job.get("updated"), f"{field}.updated")
    canonical = _canonical_dumpstatus_evidence(source, updated)
    return {
        "sha256": hashlib.sha256(canonical).hexdigest(),
        "job": _DUMP_JOB,
        "status": "done",
        "updated": updated,
    }


def _validate_bundled_dumpstatus_payload(raw, source):
    """Akzeptiert im Pack ausschließlich die kanonische Minimalprojektion."""

    if len(raw) > _MAX_DUMPSTATUS_BYTES:
        raise ManifestError("gebündelter dumpstatus überschreitet das Größenlimit")
    try:
        root = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ManifestError("gebündelter dumpstatus ist kein gültiges JSON") from exc
    if not isinstance(root, dict):
        raise ManifestError("gebündelter dumpstatus muss ein JSON-Objekt sein")
    _exact_keys(
        root,
        {"schema", "job", "status", "updated", "file"},
        "dumpstatus.json",
    )
    if root.get("schema") != "hoshi-wikimedia-dump-evidence-v1":
        raise ManifestError("dumpstatus.json besitzt ein unbekanntes Schema")
    if root.get("job") != _DUMP_JOB or root.get("status") != "done":
        raise ManifestError("dumpstatus.json besitzt keinen abgeschlossenen Artikeljob")
    updated = _dump_updated(root.get("updated"), "dumpstatus.json.updated")
    file_info = root.get("file")
    if not isinstance(file_info, dict):
        raise ManifestError("dumpstatus.json.file muss ein JSON-Objekt sein")
    _exact_keys(file_info, {"url", "size", "sha1"}, "dumpstatus.json.file")
    expected = _canonical_dumpstatus_evidence(source, updated)
    if raw != expected:
        raise ManifestError("dumpstatus.json ist nicht die kanonische Minimal-Evidenz")
    return {
        "sha256": hashlib.sha256(expected).hexdigest(),
        "job": _DUMP_JOB,
        "status": "done",
        "updated": updated,
    }


def _verify_online_dumpstatus(source, *, opener=None):
    url = source["dumpStatusUrl"]
    safe_open = opener or _authority_opener()
    request = urllib.request.Request(
        url,
        headers={"User-Agent": _USER_AGENT, "Accept": "application/json"},
    )
    try:
        with safe_open(request, timeout=30) as response:
            final_url = getattr(response, "geturl", lambda: url)()
            if final_url != url:
                raise ManifestError(
                    f"Online-dumpstatus wurde unerwartet umgeleitet: {final_url}"
                )
            raw = response.read(_MAX_DUMPSTATUS_BYTES + 1)
    except ManifestError:
        raise
    except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
        raise ManifestError(f"Online-dumpstatus nicht abrufbar: {exc}") from exc
    return _validate_dumpstatus_payload(raw, source, "Online-dumpstatus")


def _verify_builder_binding(builder):
    commit = builder["commit"]
    try:
        committed = subprocess.run(
            ["git", "-C", str(REPO_ROOT), "show", f"{commit}:{_BUILDER_SOURCE}"],
            check=True,
            capture_output=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ManifestError(
            "builder.commit enthält die angegebene Builder-Quelle nicht"
        ) from exc
    observed = hashlib.sha256(committed).hexdigest()
    if observed != builder["transformSourceSha256"].lower():
        raise ManifestError(
            "builder.transformSourceSha256 stimmt nicht mit builder.commit überein"
        )


def _load_bound_release_builder(builder):
    """Lädt nur den Builder, dessen Bytes das Manifest bereits gebunden hat."""

    source_path = REPO_ROOT / _BUILDER_SOURCE
    observed = sha256_file(source_path)
    expected = builder["transformSourceSha256"].lower()
    if observed != expected:
        raise ManifestError(
            "lokaler Release-Builder entspricht nicht builder.commit; "
            "logischer Wiederaufbau braucht den gebundenen Checkout"
        )
    module_name = "_hoshi_bound_knowledge_pack_builder"
    spec = importlib.util.spec_from_file_location(module_name, source_path)
    if spec is None or spec.loader is None:
        raise ManifestError("gebundener Release-Builder ist nicht ladbar")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except (ImportError, OSError, ValueError) as exc:
        raise ManifestError(f"gebundener Release-Builder ist nicht ausführbar: {exc}") from exc
    return module


def _stat_fingerprint(value: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        value.st_dev,
        value.st_ino,
        value.st_size,
        value.st_mtime_ns,
        value.st_ctime_ns,
    )


def _database_fingerprint(path: Path) -> tuple[int, int, int, int, int]:
    try:
        observed = path.lstat()
    except OSError as exc:
        raise ManifestError(f"Pack-SQLite ist nicht prüfbar: {exc}") from exc
    if not stat.S_ISREG(observed.st_mode):
        raise ManifestError("Pack-SQLite ist keine reguläre Datei")
    return _stat_fingerprint(observed)


def _assert_no_sqlite_sidecars(path: Path) -> None:
    unexpected = [
        candidate.name
        for candidate in (
            Path(str(path) + "-wal"),
            Path(str(path) + "-shm"),
            Path(str(path) + "-journal"),
        )
        if candidate.exists()
    ]
    if unexpected:
        raise ManifestError(
            "Release-Pack besitzt SQLite-Sidecars: " + ", ".join(unexpected)
        )


def _assert_database_stable(
    path: Path,
    expected: tuple[int, int, int, int, int],
) -> None:
    _assert_no_sqlite_sidecars(path)
    if _database_fingerprint(path) != expected:
        raise ManifestError("Pack-SQLite änderte sich während der Verifikation")


def _hash_open_dump(handle) -> tuple[int, str, str]:
    sha1 = hashlib.sha1(usedforsecurity=False)
    sha256 = hashlib.sha256()
    size = 0
    for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
        size += len(chunk)
        sha1.update(chunk)
        sha256.update(chunk)
    return size, sha1.hexdigest(), sha256.hexdigest()


def _read_actual_logical_records(conn, release_builder) -> list[dict]:
    rows = conn.execute(
        "SELECT "
        "a.id,a.title,a.title_norm,a.redirect_to,a.is_disambig,a.is_stopword,"
        "a.plaintext_zstd,a.plaintext_bytes,a.kern,a.kern_gen_at,a.kern_model,"
        "a.inserted_at,a.updated_at,a.kern_emb,"
        "c.alias_idx,c.classification,c.perspective,c.gen_model,c.gen_at,"
        "c.prompt_hash,c.validation_score,c.validation_ok,"
        "s.source_url,s.source_revision_id,s.source_revision_timestamp "
        "FROM articles a "
        "JOIN classifications c ON c.article_id=a.id "
        "JOIN article_sources s ON s.article_id=a.id "
        "ORDER BY a.id"
    ).fetchall()
    decompressor = release_builder.legacy.zstd.ZstdDecompressor()
    records: list[dict] = []
    for row_no, row in enumerate(rows, 1):
        blob = row["plaintext_zstd"]
        byte_count = row["plaintext_bytes"]
        if not isinstance(blob, bytes) or isinstance(byte_count, bool) or not isinstance(
            byte_count,
            int,
        ):
            raise ManifestError(
                f"logischer DB-Inhalt in Zeile {row_no} besitzt keinen gültigen Plaintext"
            )
        try:
            plaintext_bytes = decompressor.decompress(
                blob,
                max_output_size=byte_count,
            )
            plaintext = plaintext_bytes.decode("utf-8")
        except (UnicodeError, release_builder.legacy.zstd.ZstdError) as exc:
            raise ManifestError(
                f"logischer DB-Inhalt in Zeile {row_no} ist nicht reproduzierbar"
            ) from exc
        if len(plaintext_bytes) != byte_count:
            raise ManifestError(
                f"plaintext_bytes stimmt in logischer DB-Zeile {row_no} nicht"
            )
        records.append(
            {
                "article": {
                    "id": row["id"],
                    "title": row["title"],
                    "titleNorm": row["title_norm"],
                    "redirectTo": row["redirect_to"],
                    "isDisambig": row["is_disambig"],
                    "isStopword": row["is_stopword"],
                    "plaintext": plaintext,
                    "plaintextBytes": byte_count,
                    "kern": row["kern"],
                    "kernGenAt": row["kern_gen_at"],
                    "kernModel": row["kern_model"],
                    "insertedAt": row["inserted_at"],
                    "updatedAt": row["updated_at"],
                    "kernEmb": row["kern_emb"],
                },
                "classification": {
                    "aliasIdx": row["alias_idx"],
                    "classification": row["classification"],
                    "perspective": row["perspective"],
                    "genModel": row["gen_model"],
                    "genAt": row["gen_at"],
                    "promptHash": row["prompt_hash"],
                    "validationScore": row["validation_score"],
                    "validationOk": row["validation_ok"],
                },
                "source": {
                    "url": row["source_url"],
                    "revisionId": row["source_revision_id"],
                    "revisionTimestamp": row["source_revision_timestamp"],
                },
            }
        )
    return records


def _schema_contract(conn) -> list[tuple]:
    return [
        tuple(row)
        for row in conn.execute(
            "SELECT type,name,tbl_name,sql FROM sqlite_schema "
            "ORDER BY type,name,tbl_name"
        ).fetchall()
    ]


def _verify_database_container(
    conn,
    db_path: Path,
    release_builder,
) -> None:
    """Schließt Zusatzschema, Freelist-Payload und angehängte Bytes aus."""

    with tempfile.TemporaryDirectory(prefix="hoshi-pack-schema-proof-") as temp_name:
        expected_path = Path(temp_name) / "expected.sqlite"
        with sqlite3.connect(expected_path) as expected:
            release_builder.legacy._create_schema(expected)
            expected_schema = _schema_contract(expected)
    if _schema_contract(conn) != expected_schema:
        raise ManifestError(
            "SQLite-Schema ist nicht exakt die Ausgabe des gebundenen Release-Builders"
        )
    page_size = conn.execute("PRAGMA page_size").fetchone()[0]
    page_count = conn.execute("PRAGMA page_count").fetchone()[0]
    freelist_count = conn.execute("PRAGMA freelist_count").fetchone()[0]
    user_version = conn.execute("PRAGMA user_version").fetchone()[0]
    application_id = conn.execute("PRAGMA application_id").fetchone()[0]
    encoding = conn.execute("PRAGMA encoding").fetchone()[0]
    if freelist_count != 0:
        raise ManifestError("Release-Pack enthält freie SQLite-Seiten")
    if db_path.stat().st_size != page_size * page_count:
        raise ManifestError("Release-Pack enthält Bytes außerhalb der SQLite-Seiten")
    if user_version != 0 or application_id != 0 or encoding != "UTF-8":
        raise ManifestError("SQLite-Header weicht vom Release-Builder-Vertrag ab")


def _verify_fts_external_content(db_path: Path) -> None:
    """Prüft auf privater Kopie auch den FTS5-Index gegen seinen External Content."""

    try:
        with tempfile.TemporaryDirectory(prefix="hoshi-pack-fts-proof-") as temp_name:
            copied = Path(temp_name) / "pack.sqlite"
            shutil.copyfile(db_path, copied)
            with sqlite3.connect(copied, timeout=30.0) as conn:
                conn.execute(
                    "INSERT INTO classifications_fts(classifications_fts, rank) "
                    "VALUES('integrity-check', 1)"
                )
    except (OSError, sqlite3.Error) as exc:
        raise ManifestError(f"FTS5-External-Content-Integrität fehlt: {exc}") from exc


def _verify_source_dump_and_logical_content(
    *,
    source_dump_path: Path,
    manifest_path: Path,
    raw: dict,
    db_path: Path,
) -> dict:
    """Beweist Source-Bytes → gebundene Transformation → tatsächliche DB-Zeilen."""

    builder = raw["builder"]
    release_builder = _load_bound_release_builder(builder)
    if builder["toolchain"] != release_builder.toolchain_contract():
        raise ManifestError(
            "lokale Toolchain stimmt nicht mit dem bytegenauen Release-Build überein"
        )
    selection_path = manifest_path.parent / builder["selectionFile"]
    try:
        selections = release_builder.read_public_selection(selection_path)
    except (OSError, ValueError) as exc:
        raise ManifestError(f"gebündelte Auswahl ist nicht reproduzierbar: {exc}") from exc
    try:
        bundled_selection = selection_path.read_bytes()
    except OSError as exc:
        raise ManifestError(f"gebündelte Auswahl ist nicht lesbar: {exc}") from exc
    if bundled_selection != release_builder.canonical_public_selection(selections):
        raise ManifestError(
            "selection.jsonl ist nicht die kanonische Builder-Ausgabe"
        )

    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(source_dump_path, flags)
    except OSError as exc:
        raise ManifestError(f"lokaler Quelldump ist nicht sicher lesbar: {exc}") from exc
    try:
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            raise ManifestError("lokaler Quelldump ist keine reguläre Datei")
        with os.fdopen(os.dup(descriptor), "rb") as hash_handle:
            size, sha1, sha256 = _hash_open_dump(hash_handle)
        after_hash = os.fstat(descriptor)
        if _stat_fingerprint(after_hash) != _stat_fingerprint(before):
            raise ManifestError("lokaler Quelldump änderte sich während des Hashens")
        expected_dump = raw["source"]["dump"]
        if size != expected_dump["sizeBytes"]:
            raise ManifestError("lokaler Quelldump stimmt bei sizeBytes nicht mit dem Manifest")
        if sha1 != expected_dump["sha1"].lower():
            raise ManifestError("lokaler Quelldump stimmt bei SHA-1 nicht mit dem Manifest")
        if sha256 != expected_dump["sha256"].lower():
            raise ManifestError("lokaler Quelldump stimmt bei SHA-256 nicht mit dem Manifest")

        os.lseek(descriptor, 0, os.SEEK_SET)
        try:
            with os.fdopen(os.dup(descriptor), "rb") as parse_handle:
                articles = release_builder.extract_selected_articles(
                    parse_handle,
                    selections,
                    lead_chars=builder["parameters"]["leadChars"],
                )
        except (OSError, ValueError) as exc:
            raise ManifestError(
                f"Quelldump lässt sich mit dem gebundenen Builder nicht reproduzieren: {exc}"
            ) from exc
        after_parse = os.fstat(descriptor)
        if _stat_fingerprint(after_parse) != _stat_fingerprint(before):
            raise ManifestError("lokaler Quelldump änderte sich während des Wiederaufbaus")
    finally:
        os.close(descriptor)

    spec = release_builder.DumpSpec.create(
        raw["source"]["dumpDate"].replace("-", ""),
        size,
        sha1,
    )
    artifact = release_builder.DumpArtifact(
        path=source_dump_path,
        size=size,
        sha1=sha1,
        sha256=sha256,
    )
    try:
        with tempfile.TemporaryDirectory(prefix="hoshi-pack-notice-proof-") as temp_name:
            expected_notice = Path(temp_name) / "NOTICE.md"
            release_builder._write_notice(
                expected_notice,
                pack_id=raw["packId"],
                spec=spec,
                artifact=artifact,
            )
            actual_notice = manifest_path.parent / raw["source"]["noticeFile"]
            if expected_notice.read_bytes() != actual_notice.read_bytes():
                raise ManifestError(
                    "NOTICE ist nicht die Ausgabe des gebundenen Release-Builders"
                )
    except OSError as exc:
        raise ManifestError(f"NOTICE-Wiederaufbau fehlgeschlagen: {exc}") from exc

    expected_records = release_builder.logical_records(
        pack_id=raw["packId"],
        created_at=raw["createdAt"],
        selections=selections,
        articles=articles,
    )
    expected_digest = release_builder.logical_payload_sha256(
        pack_id=raw["packId"],
        created_at=raw["createdAt"],
        records=expected_records,
    )
    if expected_digest != builder["logicalRecordsSha256"].lower():
        raise ManifestError(
            "builder.logicalRecordsSha256 stimmt nicht mit Quelldump und Auswahl überein"
        )
    try:
        with sqlite3.connect(_readonly_uri(db_path), uri=True, timeout=30.0) as conn:
            conn.row_factory = sqlite3.Row
            _verify_database_container(conn, db_path, release_builder)
            actual_records = _read_actual_logical_records(conn, release_builder)
    except sqlite3.Error as exc:
        raise ManifestError(f"logischer DB-Inhalt ist nicht lesbar: {exc}") from exc
    if actual_records != expected_records:
        raise ManifestError(
            "Pack-Inhalt ist kein exakter logischer Wiederaufbau aus Quelldump und Auswahl"
        )
    actual_digest = release_builder.logical_payload_sha256(
        pack_id=raw["packId"],
        created_at=raw["createdAt"],
        records=actual_records,
    )
    if actual_digest != builder["logicalRecordsSha256"].lower():
        raise ManifestError("tatsächlicher logischer DB-Digest stimmt nicht mit dem Manifest")
    _verify_fts_external_content(db_path)
    try:
        with tempfile.TemporaryDirectory(prefix="hoshi-pack-byte-proof-") as temp_name:
            rebuilt = Path(temp_name) / "pack.sqlite"
            release_builder.write_pack_database(
                rebuilt,
                selections=selections,
                articles=articles,
                created_at=raw["createdAt"],
            )
            rebuilt_sha256 = sha256_file(rebuilt)
    except (OSError, sqlite3.Error, ValueError) as exc:
        raise ManifestError(f"bytegenauer Pack-Wiederaufbau fehlgeschlagen: {exc}") from exc
    actual_database_sha256 = sha256_file(db_path)
    if rebuilt_sha256 != actual_database_sha256:
        raise ManifestError(
            "Pack-SQLite ist nicht bytegleich zur frischen Ausgabe des "
            "gebundenen Builders und der gepinnten Toolchain"
        )
    return {
        "sourceDumpSha256": sha256,
        "logicalRecordsSha256": actual_digest,
        "canonicalDatabaseSha256": rebuilt_sha256,
        "sourceDumpBytesVerified": True,
        "logicalContentVerified": True,
        "ftsIntegrityVerified": True,
        "byteRebuildVerified": True,
    }


def _validate_release_manifest(raw, manifest_path, article_count):
    """Validiert Felder, die Legacy-/Forensic-Packs absichtlich nicht behaupten."""

    _exact_keys(
        raw,
        {
            "schemaVersion",
            "releaseStatus",
            "packId",
            "language",
            "createdAt",
            "source",
            "builder",
            "database",
            "retrieval",
            "budget",
        },
        "manifest",
    )
    if raw.get("language") != "de":
        raise ManifestError("language muss für dewiki-Pack v1 de sein")
    try:
        entries = list(manifest_path.parent.iterdir())
    except OSError as exc:
        raise ManifestError(f"Release-Verzeichnis ist nicht lesbar: {exc}") from exc
    entry_names = {entry.name for entry in entries}
    if entry_names != _RELEASE_FILES:
        missing = sorted(_RELEASE_FILES - entry_names)
        unexpected = sorted(entry_names - _RELEASE_FILES)
        details = []
        if missing:
            details.append("fehlt=" + ",".join(missing))
        if unexpected:
            details.append("unerwartet=" + ",".join(unexpected))
        raise ManifestError(
            "Release-Verzeichnis besitzt nicht exakt die öffentlichen Pack-Dateien "
            f"({'; '.join(details)})"
        )
    for entry in entries:
        try:
            mode = entry.lstat().st_mode
        except OSError as exc:
            raise ManifestError(f"Pack-Datei ist nicht prüfbar: {entry.name}: {exc}") from exc
        if not stat.S_ISREG(mode):
            raise ManifestError(
                f"Pack-Artefakt ist keine reguläre Datei: {entry.name}"
            )
    created = _utc_timestamp(raw.get("createdAt"), "createdAt")
    source = raw.get("source")
    if not isinstance(source, dict):
        raise ManifestError("source muss ein JSON-Objekt sein")
    _exact_keys(
        source,
        {
            "name",
            "url",
            "dumpDate",
            "dumpStatusUrl",
            "dumpStatus",
            "dump",
            "license",
            "noticeFile",
            "noticeSha256",
            "revisionCoverage",
            "revisionTimestampCoverage",
            "revisionCount",
            "revisionTimestampCount",
        },
        "source",
    )
    if source.get("name") != "Wikipedia":
        raise ManifestError("source.name muss Wikipedia sein")
    dump_date = _release_text(source.get("dumpDate"), "source.dumpDate")
    compact_date = dump_date.replace("-", "")
    canonical_base = f"https://dumps.wikimedia.org/dewiki/{compact_date}"
    canonical_filename = (
        f"dewiki-{compact_date}-pages-articles-multistream.xml.bz2"
    )
    expected_url = f"{canonical_base}/{canonical_filename}"
    if source.get("url") != expected_url:
        raise ManifestError(
            "source.url ist nicht der kanonische dewiki-pages-articles-multistream-Dump"
        )
    if source.get("dumpStatusUrl") != f"{canonical_base}/dumpstatus.json":
        raise ManifestError("source.dumpStatusUrl ist nicht die kanonische dumpstatus-URL")
    if source.get("license") != "CC-BY-SA-4.0":
        raise ManifestError("source.license muss für diesen Releasepfad CC-BY-SA-4.0 sein")
    dump = source.get("dump")
    if not isinstance(dump, dict):
        raise ManifestError("source.dump muss ein JSON-Objekt sein")
    _exact_keys(dump, {"sizeBytes", "sha1", "sha256"}, "source.dump")
    _release_integer(dump.get("sizeBytes"), "source.dump.sizeBytes", 1)
    dump_sha1 = _release_text(dump.get("sha1"), "source.dump.sha1").lower()
    dump_sha256 = _release_text(dump.get("sha256"), "source.dump.sha256").lower()
    if not _SHA1_RE.fullmatch(dump_sha1):
        raise ManifestError("source.dump.sha1 muss ein SHA-1-Hexwert sein")
    if not _SHA256_RE.fullmatch(dump_sha256):
        raise ManifestError("source.dump.sha256 muss ein SHA-256-Hexwert sein")
    dump_status = source.get("dumpStatus")
    if not isinstance(dump_status, dict):
        raise ManifestError("source.dumpStatus muss ein JSON-Objekt sein")
    _exact_keys(
        dump_status,
        {"file", "sha256", "job", "status", "updated"},
        "source.dumpStatus",
    )
    if dump_status.get("file") != _DUMPSTATUS_FILE:
        raise ManifestError("source.dumpStatus.file muss dumpstatus.json sein")
    status_sha256 = _release_text(
        dump_status.get("sha256"),
        "source.dumpStatus.sha256",
    ).lower()
    if not _SHA256_RE.fullmatch(status_sha256):
        raise ManifestError("source.dumpStatus.sha256 muss ein SHA-256-Hexwert sein")
    if dump_status.get("job") != _DUMP_JOB:
        raise ManifestError(f"source.dumpStatus.job muss {_DUMP_JOB} sein")
    if dump_status.get("status") != "done":
        raise ManifestError("source.dumpStatus.status muss done sein")
    _dump_updated(dump_status.get("updated"), "source.dumpStatus.updated")
    try:
        bundled_status = (manifest_path.parent / _DUMPSTATUS_FILE).read_bytes()
    except OSError as exc:
        raise ManifestError(
            f"gebündelter dumpstatus ist nicht lesbar: {exc}"
        ) from exc
    status_evidence = _validate_bundled_dumpstatus_payload(
        bundled_status,
        source,
    )
    if status_evidence["sha256"] != status_sha256:
        raise ManifestError(
            "source.dumpStatus.sha256 stimmt nicht mit dumpstatus.json überein"
        )
    for key in ("job", "status", "updated"):
        if dump_status.get(key) != status_evidence[key]:
            raise ManifestError(
                f"source.dumpStatus.{key} stimmt nicht mit dumpstatus.json überein"
            )
    if source.get("revisionCoverage") != "per-article":
        raise ManifestError("source.revisionCoverage muss per-article sein")
    if source.get("revisionTimestampCoverage") != "per-article":
        raise ManifestError("source.revisionTimestampCoverage muss per-article sein")
    if _release_integer(source.get("revisionCount"), "source.revisionCount", 1) != article_count:
        raise ManifestError("source.revisionCount stimmt nicht mit articleCount überein")
    if (
        _release_integer(
            source.get("revisionTimestampCount"),
            "source.revisionTimestampCount",
            1,
        )
        != article_count
    ):
        raise ManifestError(
            "source.revisionTimestampCount stimmt nicht mit articleCount überein"
        )

    builder = raw.get("builder")
    if not isinstance(builder, dict):
        raise ManifestError("builder muss ein JSON-Objekt sein")
    _exact_keys(
        builder,
        {
            "commit",
            "transform",
            "transformSha256",
            "transformSourceSha256",
            "selection",
            "selectionFile",
            "selectionSha256",
            "modelDerivedFeatures",
            "logicalRecordsSha256",
            "parameters",
            "toolchain",
        },
        "builder",
    )
    commit = _release_text(builder.get("commit"), "builder.commit")
    if not _SHA1_RE.fullmatch(commit):
        raise ManifestError("builder.commit muss ein vollständiger Git-SHA-1 sein")
    transform = _release_text(builder.get("transform"), "builder.transform")
    if transform != _TRANSFORM_ID:
        raise ManifestError(f"builder.transform muss {_TRANSFORM_ID} sein")
    for field in ("transformSha256", "transformSourceSha256", "selectionSha256"):
        value = _release_text(builder.get(field), f"builder.{field}").lower()
        if not _SHA256_RE.fullmatch(value):
            raise ManifestError(f"builder.{field} muss ein SHA-256-Hexwert sein")
    if (
        hashlib.sha256(transform.encode("utf-8")).hexdigest()
        != builder["transformSha256"].lower()
    ):
        raise ManifestError(
            "builder.transformSha256 stimmt nicht mit builder.transform überein"
        )
    if builder.get("selection") != "explicit-public-title-list":
        raise ManifestError("builder.selection muss explicit-public-title-list sein")
    if builder.get("selectionFile") != _SELECTION_FILE:
        raise ManifestError("builder.selectionFile muss selection.jsonl sein")
    if builder.get("modelDerivedFeatures") != []:
        raise ManifestError("builder.modelDerivedFeatures muss für Pack v1 leer sein")
    logical_sha256 = _release_text(
        builder.get("logicalRecordsSha256"),
        "builder.logicalRecordsSha256",
    ).lower()
    if not _SHA256_RE.fullmatch(logical_sha256):
        raise ManifestError("builder.logicalRecordsSha256 muss ein SHA-256-Hexwert sein")
    parameters = builder.get("parameters")
    if not isinstance(parameters, dict) or set(parameters) != {
        "leadChars",
        "zstdLevel",
    }:
        raise ManifestError(
            "builder.parameters muss ausschließlich leadChars und zstdLevel enthalten"
        )
    lead_chars = _release_integer(
        parameters.get("leadChars"),
        "builder.parameters.leadChars",
        300,
    )
    if lead_chars > 10_000:
        raise ManifestError("builder.parameters.leadChars darf höchstens 10000 sein")
    if parameters.get("zstdLevel") != 10:
        raise ManifestError("builder.parameters.zstdLevel muss 10 sein")
    toolchain = builder.get("toolchain")
    if not isinstance(toolchain, dict):
        raise ManifestError("builder.toolchain muss ein JSON-Objekt sein")
    _exact_keys(
        toolchain,
        {
            "pythonImplementation",
            "pythonVersion",
            "sqliteVersion",
            "zstandardVersion",
        },
        "builder.toolchain",
    )
    for field in sorted(toolchain):
        _release_text(toolchain[field], f"builder.toolchain.{field}")
    _verify_builder_binding(builder)

    database = raw.get("database")
    if not isinstance(database, dict):
        raise ManifestError("database muss ein JSON-Objekt sein")
    _exact_keys(
        database,
        {"file", "sha256", "sizeBytes", "articleCount"},
        "database",
    )
    retrieval = raw.get("retrieval")
    if not isinstance(retrieval, dict):
        raise ManifestError("retrieval muss ein JSON-Objekt sein")
    _exact_keys(
        retrieval,
        {"method", "tokenizer", "denseIndex"},
        "retrieval",
    )
    if retrieval != {
        "method": "fts5-title-alias-lead",
        "tokenizer": "unicode61 remove_diacritics 2",
        "denseIndex": None,
    }:
        raise ManifestError("retrieval stimmt nicht mit dem Release-Builder v1 überein")
    budget = raw.get("budget")
    if not isinstance(budget, dict):
        raise ManifestError("budget muss ein JSON-Objekt sein")
    _exact_keys(budget, {"maxPackBytes", "diskPreflight"}, "budget")
    max_pack_bytes = _release_integer(
        budget.get("maxPackBytes"),
        "budget.maxPackBytes",
        1,
    )
    if max_pack_bytes > _MAX_RELEASE_PACK_BYTES:
        raise ManifestError("budget.maxPackBytes überschreitet 512 MiB")
    if database["sizeBytes"] > max_pack_bytes:
        raise ManifestError("database.sizeBytes überschreitet budget.maxPackBytes")
    if budget.get("diskPreflight") != "compressed-dump-plus-pack-plus-1GiB-margins":
        raise ManifestError("budget.diskPreflight stimmt nicht mit Release-Builder v1 überein")

    try:
        selection_bytes = (manifest_path.parent / _SELECTION_FILE).read_bytes()
    except OSError as exc:
        raise ManifestError(
            f"gebündelte öffentliche Auswahl ist nicht lesbar: {exc}"
        ) from exc
    if hashlib.sha256(selection_bytes).hexdigest() != builder["selectionSha256"].lower():
        raise ManifestError(
            "builder.selectionSha256 stimmt nicht mit selection.jsonl überein"
        )

    notice_file = source.get("noticeFile")
    if notice_file != "NOTICE.md":
        raise ManifestError("source.noticeFile muss NOTICE.md sein")
    try:
        notice_bytes = (manifest_path.parent / notice_file).read_bytes()
        notice = notice_bytes.decode("utf-8")
    except (OSError, UnicodeError, TypeError) as exc:
        raise ManifestError(f"NOTICE ist nicht als UTF-8 lesbar: {exc}") from exc
    for required in ("CC BY-SA 4.0", "Attribution", "Modifications", "ShareAlike"):
        if required.casefold() not in notice.casefold():
            raise ManifestError(f"NOTICE enthält den Releasehinweis {required!r} nicht")
    notice_sha256 = _release_text(
        source.get("noticeSha256"),
        "source.noticeSha256",
    ).lower()
    if not _SHA256_RE.fullmatch(notice_sha256):
        raise ManifestError("source.noticeSha256 muss ein SHA-256-Hexwert sein")
    if hashlib.sha256(notice_bytes).hexdigest() != notice_sha256:
        raise ManifestError("source.noticeSha256 stimmt nicht mit NOTICE überein")
    return created, source


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _release_directory_state(directory: Path) -> dict[str, tuple[int, int, int, int, int]]:
    try:
        entries = list(directory.iterdir())
    except OSError as exc:
        raise ManifestError(f"Release-Verzeichnis ist nicht lesbar: {exc}") from exc
    names = {entry.name for entry in entries}
    if names != _RELEASE_FILES:
        missing = sorted(_RELEASE_FILES - names)
        unexpected = sorted(names - _RELEASE_FILES)
        details = []
        if missing:
            details.append("fehlt=" + ",".join(missing))
        if unexpected:
            details.append("unerwartet=" + ",".join(unexpected))
        raise ManifestError(
            "Release-Verzeichnis besitzt nicht exakt die öffentlichen Pack-Dateien "
            f"({'; '.join(details)})"
        )
    state: dict[str, tuple[int, int, int, int, int]] = {}
    for entry in entries:
        try:
            observed = entry.lstat()
        except OSError as exc:
            raise ManifestError(f"Pack-Datei ist nicht prüfbar: {entry.name}: {exc}") from exc
        if not stat.S_ISREG(observed.st_mode):
            raise ManifestError(
                f"Pack-Artefakt ist keine reguläre Datei: {entry.name}"
            )
        maximum = _RELEASE_FILE_MAX_BYTES[entry.name]
        if observed.st_size > maximum:
            raise ManifestError(
                f"Pack-Artefakt überschreitet das Größenlimit: "
                f"{entry.name} ({observed.st_size} > {maximum})"
            )
        state[entry.name] = _stat_fingerprint(observed)
    return state


@contextlib.contextmanager
def _snapshot_release_directory(
    directory: Path,
    initial_manifest: bytes,
):
    """Verifiziert ausschließlich stabile Snapshots statt mehrfach gelesener Pfade."""

    original_state = _release_directory_state(directory)
    with tempfile.TemporaryDirectory(prefix="hoshi-pack-snapshot-") as temp_name:
        snapshot = Path(temp_name)
        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        for name in sorted(_RELEASE_FILES):
            source = directory / name
            try:
                descriptor = os.open(source, flags)
            except OSError as exc:
                raise ManifestError(f"Pack-Datei ist nicht sicher lesbar: {name}: {exc}") from exc
            try:
                before = os.fstat(descriptor)
                if _stat_fingerprint(before) != original_state[name]:
                    raise ManifestError(f"Pack-Datei änderte sich vor dem Snapshot: {name}")
                destination = snapshot / name
                with destination.open("xb") as target:
                    with os.fdopen(os.dup(descriptor), "rb") as source_handle:
                        shutil.copyfileobj(
                            source_handle,
                            target,
                            length=8 * 1024 * 1024,
                        )
                    target.flush()
                    os.fsync(target.fileno())
                after = os.fstat(descriptor)
                if _stat_fingerprint(after) != original_state[name]:
                    raise ManifestError(
                        f"Pack-Datei änderte sich während des Snapshots: {name}"
                    )
            finally:
                os.close(descriptor)
        if (snapshot / "manifest.json").read_bytes() != initial_manifest:
            raise ManifestError("manifest.json änderte sich vor dem Release-Snapshot")
        if _release_directory_state(directory) != original_state:
            raise ManifestError("Release-Verzeichnis änderte sich während des Snapshots")
        try:
            yield snapshot / "manifest.json"
        finally:
            if _release_directory_state(directory) != original_state:
                raise ManifestError("Release-Verzeichnis änderte sich während der Verifikation")


def _verify_pack_impl(
    manifest_path: Path,
    *,
    fast: bool = False,
    verify_source_online: bool = False,
    source_dump_path: Path | None = None,
    source_opener=None,
) -> dict:
    """Validiert Vertrag, öffentliches Schema, Zeilenzahlen und optional SHA."""

    try:
        raw = json.loads(manifest_path.read_text(encoding="utf-8"))
        database_file = raw["database"]["file"]
    except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
        raise ManifestError(f"Manifest kann DB-Datei nicht bestimmen: {exc}") from exc

    db_path = manifest_path.parent / database_file
    manifest = parse_manifest(manifest_path, db_path)
    release_status = raw.get("releaseStatus")
    if release_status not in _RELEASE_STATUSES:
        raise ManifestError(f"unbekannter releaseStatus: {release_status!r}")
    release_created = None
    release_source = None
    database_fingerprint = None
    if release_status == "release-candidate":
        release_created, release_source = _validate_release_manifest(
            raw,
            manifest_path,
            manifest.article_count,
        )
        _assert_no_sqlite_sidecars(db_path)
        database_fingerprint = _database_fingerprint(db_path)
    elif verify_source_online:
        raise ManifestError(
            "Online-Quellenprüfung ist nur für release-candidate zulässig"
        )
    if source_dump_path is not None:
        if release_status != "release-candidate":
            raise ManifestError(
                "Quelldump-Wiederaufbau ist nur für release-candidate zulässig"
            )
        if fast:
            raise ManifestError(
                "Quelldump-Wiederaufbau ist ohne vollständige DB-Prüfung kein Releasebeweis"
            )

    with sqlite3.connect(_readonly_uri(db_path), uri=True, timeout=10.0) as conn:
        conn.row_factory = sqlite3.Row
        names = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type IN ('table','view')"
            )
        }
        missing = sorted(REQUIRED_TABLES - names)
        if missing:
            raise ManifestError(f"Pack-Schema unvollständig: {', '.join(missing)}")
        leaked = sorted(PRIVATE_TABLES & names)
        if leaked:
            raise ManifestError(
                "Pack enthält private Runtime-Tabellen: " + ", ".join(leaked)
            )
        unexpected = sorted(names - PUBLIC_TABLES)
        if unexpected:
            raise ManifestError(
                "Pack enthält nicht erlaubte Tabellen/Views: " + ", ".join(unexpected)
            )

        article_count = conn.execute("SELECT COUNT(*) FROM articles").fetchone()[0]
        source_count = conn.execute("SELECT COUNT(*) FROM article_sources").fetchone()[0]
        classification_count = conn.execute(
            "SELECT COUNT(*) FROM classifications"
        ).fetchone()[0]
        fts_count = conn.execute("SELECT COUNT(*) FROM classifications_fts").fetchone()[0]
        unsafe_source_count = conn.execute(
            "SELECT COUNT(*) FROM article_sources "
            "WHERE source_url NOT LIKE 'https://%'"
        ).fetchone()[0]
        invalid_revision_count = conn.execute(
            "SELECT COUNT(*) FROM article_sources "
            "WHERE source_revision_id IS NOT NULL "
            "AND source_revision_id GLOB '*[^0-9]*'"
        ).fetchone()[0]
        source_columns = {
            row["name"] for row in conn.execute("PRAGMA table_info(article_sources)")
        }

        if article_count != manifest.article_count:
            raise ManifestError(
                "Artikelzahl stimmt nicht: "
                f"Manifest={manifest.article_count}, DB={article_count}"
            )
        if source_count != article_count:
            raise ManifestError(
                f"Provenienz unvollständig: article_sources={source_count}, articles={article_count}"
            )
        if classification_count < article_count:
            raise ManifestError(
                "Mindestens ein deterministischer Suchtext pro Artikel fehlt: "
                f"classifications={classification_count}, articles={article_count}"
            )
        if (
            release_status == "release-candidate"
            and classification_count != article_count
        ):
            raise ManifestError(
                "Release-Pack braucht exakt einen deterministischen Suchtext pro Artikel: "
                f"classifications={classification_count}, articles={article_count}"
            )
        if fts_count != classification_count:
            raise ManifestError(
                "FTS-Inhalt stimmt nicht mit classifications überein: "
                f"fts={fts_count}, classifications={classification_count}"
            )
        if unsafe_source_count:
            raise ManifestError(
                f"{unsafe_source_count} Artikelquelle(n) sind keine HTTPS-URLs"
            )
        if invalid_revision_count:
            raise ManifestError(
                f"{invalid_revision_count} Wikipedia-Revisions-ID(s) sind nicht numerisch"
            )
        if release_status == "release-candidate":
            if "source_revision_timestamp" not in source_columns:
                raise ManifestError(
                    "Release-Pack benötigt article_sources.source_revision_timestamp"
                )
            source_rows = conn.execute(
                "SELECT source_url, source_revision_id, source_revision_timestamp "
                "FROM article_sources"
            ).fetchall()
            for row_no, row in enumerate(source_rows, 1):
                revision_id = row["source_revision_id"]
                if revision_id is None or not revision_id.isdigit():
                    raise ManifestError(
                        f"Release-Provenienzzeile {row_no} besitzt keine Revisions-ID"
                    )
                expected_url = (
                    f"https://de.wikipedia.org/w/index.php?oldid={revision_id}"
                )
                if row["source_url"] != expected_url:
                    raise ManifestError(
                        f"Release-Provenienzzeile {row_no} besitzt keine permanente oldid-URL"
                    )
                revision_time = _utc_timestamp(
                    row["source_revision_timestamp"],
                    f"article_sources[{row_no}].source_revision_timestamp",
                )
                if release_created is not None and revision_time > release_created:
                    raise ManifestError(
                        f"Revisionszeit in Zeile {row_no} liegt nach createdAt"
                    )

        if not fast:
            quick_check = conn.execute("PRAGMA quick_check").fetchone()[0]
            if quick_check != "ok":
                raise ManifestError(f"SQLite quick_check fehlgeschlagen: {quick_check}")
    if database_fingerprint is not None:
        _assert_database_stable(db_path, database_fingerprint)

    digest = None
    if not fast:
        digest = sha256_file(db_path)
        if digest != manifest.database_sha256:
            raise ManifestError(
                f"SHA-256 stimmt nicht: Manifest={manifest.database_sha256}, Datei={digest}"
            )
    if database_fingerprint is not None:
        _assert_database_stable(db_path, database_fingerprint)

    source_authority = None
    if verify_source_online:
        if fast:
            raise ManifestError(
                "Online-Quellenprüfung ist ohne vollständige DB-Prüfung kein Releasebeweis"
            )
        assert release_source is not None
        source_authority = _verify_online_dumpstatus(
            release_source,
            opener=source_opener,
        )

    source_proof = None
    if source_dump_path is not None:
        source_proof = _verify_source_dump_and_logical_content(
            source_dump_path=source_dump_path,
            manifest_path=manifest_path,
            raw=raw,
            db_path=db_path,
        )
    if database_fingerprint is not None:
        _assert_database_stable(db_path, database_fingerprint)

    artifact_verified = not fast
    source_authority_verified = source_authority is not None
    source_dump_bytes_verified = bool(
        source_proof and source_proof["sourceDumpBytesVerified"]
    )
    logical_content_verified = bool(
        source_proof and source_proof["logicalContentVerified"]
    )
    fts_integrity_verified = bool(
        source_proof and source_proof["ftsIntegrityVerified"]
    )
    byte_rebuild_verified = bool(
        source_proof and source_proof["byteRebuildVerified"]
    )
    return {
        "status": "ok",
        "mode": "metadata-only" if fast else "full",
        "packId": manifest.pack_id,
        "manifestSha256": sha256_file(manifest_path),
        "selectionSha256": (
            raw["builder"]["selectionSha256"].lower()
            if release_status == "release-candidate"
            else None
        ),
        "databaseFile": manifest.database_file,
        "articleCount": article_count,
        "classificationCount": classification_count,
        "sha256": digest,
        "privateTables": [],
        "releaseStatus": release_status or "legacy-unspecified",
        "artifactVerified": artifact_verified,
        "sourceAuthorityVerified": source_authority_verified,
        "sourceAuthoritySha256": (
            source_authority["sha256"] if source_authority else None
        ),
        "sourceDumpBytesVerified": source_dump_bytes_verified,
        "logicalContentVerified": logical_content_verified,
        "ftsIntegrityVerified": fts_integrity_verified,
        "byteRebuildVerified": byte_rebuild_verified,
        "logicalRecordsSha256": (
            source_proof["logicalRecordsSha256"] if source_proof else None
        ),
        "canonicalDatabaseSha256": (
            source_proof["canonicalDatabaseSha256"] if source_proof else None
        ),
        "sourceDump": (
            {
                "url": release_source["url"],
                "sha256": release_source["dump"]["sha256"].lower(),
            }
            if release_source is not None
            else None
        ),
        "releaseEligible": (
            release_status == "release-candidate"
            and artifact_verified
            and source_authority_verified
            and source_dump_bytes_verified
            and logical_content_verified
            and fts_integrity_verified
            and byte_rebuild_verified
        ),
    }


def verify_pack(
    manifest_path: Path,
    *,
    fast: bool = False,
    verify_source_online: bool = False,
    source_dump_path: Path | None = None,
    source_opener=None,
) -> dict:
    """Friert Release-Dateien vor der eigentlichen Beweiskette privat ein."""

    try:
        initial_stat = manifest_path.lstat()
        if (
            not stat.S_ISREG(initial_stat.st_mode)
            or initial_stat.st_size > _RELEASE_FILE_MAX_BYTES["manifest.json"]
        ):
            raise ManifestError(
                "manifest.json ist keine reguläre Datei innerhalb des Größenlimits"
            )
        initial_manifest = manifest_path.read_bytes()
        initial_raw = json.loads(initial_manifest.decode("utf-8"))
    except ManifestError:
        raise
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ManifestError(f"Manifest ist nicht stabil lesbar: {exc}") from exc
    initial_status = (
        initial_raw.get("releaseStatus")
        if isinstance(initial_raw, dict)
        else None
    )
    if initial_status == "release-candidate":
        with _snapshot_release_directory(
            manifest_path.parent,
            initial_manifest,
        ) as snapshot_manifest:
            return _verify_pack_impl(
                snapshot_manifest,
                fast=fast,
                verify_source_online=verify_source_online,
                source_dump_path=source_dump_path,
                source_opener=source_opener,
            )
    result = _verify_pack_impl(
        manifest_path,
        fast=fast,
        verify_source_online=verify_source_online,
        source_dump_path=source_dump_path,
        source_opener=source_opener,
    )
    if result["releaseStatus"] == "release-candidate":
        raise ManifestError("releaseStatus änderte sich während der Verifikation")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path, help="Pfad zu manifest.json")
    parser.add_argument(
        "--fast",
        action="store_true",
        help="Größe/Schema/Counts prüfen, aber DB nicht vollständig hashen",
    )
    parser.add_argument(
        "--verify-source-online",
        action="store_true",
        help=(
            "kanonischen Wikimedia-dumpstatus frisch prüfen; zusammen mit Full-Check "
            "Voraussetzung für releaseEligible"
        ),
    )
    parser.add_argument(
        "--source-dump",
        type=Path,
        help=(
            "lokaler, vollständiger Quelldump; bindet dessen tatsächliche Bytes "
            "per logischem Wiederaufbau an den Pack"
        ),
    )
    args = parser.parse_args()
    try:
        result = verify_pack(
            args.manifest.expanduser().resolve(),
            fast=args.fast,
            verify_source_online=args.verify_source_online,
            source_dump_path=(
                args.source_dump.expanduser().resolve()
                if args.source_dump is not None
                else None
            ),
        )
    except ManifestError as exc:
        print(f"[knowledge-pack-verify] FATAL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
