#!/usr/bin/env python3
"""Verifiziert eine private Hoshi-Wissensbibliothek vollständig und offline."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
import re
import shutil
import sqlite3
import stat
import sys
import tempfile
import urllib.parse
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Optional


HERE = Path(__file__).resolve().parent


def _load_builder():
    path = HERE / "build_library.py"
    spec = importlib.util.spec_from_file_location("hoshi_private_library_builder", path)
    module = importlib.util.module_from_spec(spec)
    if spec.loader is None:  # pragma: no cover
        raise RuntimeError("build_library.py besitzt keinen Loader")
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


builder = _load_builder()

EXPECTED_FILES = {
    builder.DATABASE_FILE,
    builder.DOCUMENTS_FILE,
    builder.MANIFEST_FILE,
}
EXPECTED_TABLES = {
    "library",
    "documents",
    "chunks",
    "chunks_fts",
    "chunks_fts_data",
    "chunks_fts_idx",
    "chunks_fts_docsize",
    "chunks_fts_config",
}
TOP_KEYS = {
    "schemaVersion",
    "artifactType",
    "artifactStatus",
    "libraryId",
    "generationId",
    "language",
    "createdAt",
    "scope",
    "egressPolicy",
    "activation",
    "source",
    "builder",
    "database",
    "retrieval",
    "limits",
}
SCOPE_KEYS = {"kind", "ownerId", "recognitionRequired"}
ACTIVATION_KEYS = {"runtimeEnabled", "voiceEnabled"}
SOURCE_KEYS = {
    "mode",
    "inputSetSha256",
    "logicalRecordsSha256",
    "semanticContentSha256",
    "documentsFile",
    "documentsSha256",
    "documentsSizeBytes",
    "fileCount",
    "defaultLicense",
    "sourcePathsStored",
}
BUILDER_KEYS = {
    "transform",
    "sourceFile",
    "sourceSha256",
    "pythonVersion",
    "sqliteVersion",
    "modelDerivedFeatures",
}
DATABASE_KEYS = {
    "file",
    "sha256",
    "sizeBytes",
    "documentCount",
    "chunkCount",
    "recipeCount",
    "riskFlaggedDocumentCount",
}
RETRIEVAL_KEYS = {"method", "tokenizer", "denseIndex"}
LIMIT_KEYS = {
    "maxFiles",
    "maxFileBytes",
    "maxSourceBytes",
    "maxDocumentsBytes",
    "maxDatabaseBytes",
}
NOTE_KEYS = {
    "schemaVersion",
    "documentId",
    "revision",
    "kind",
    "title",
    "language",
    "tags",
    "source",
    "content",
    "riskFlags",
    "sourceSha256",
    "contentSha256",
}
RECIPE_BASE_KEYS = {
    "schemaVersion",
    "documentId",
    "revision",
    "kind",
    "title",
    "language",
    "ingredients",
    "steps",
    "tags",
    "notes",
    "source",
    "riskFlags",
    "sourceSha256",
    "contentSha256",
}
RECIPE_OPTIONAL_KEYS = {"yieldText", "times"}
NOTE_CONTENT_KEYS = {"format", "sections"}
SECTION_KEYS = {"heading", "body"}
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
GENERATION_RE = re.compile(r"^gen_[0-9a-f]{24}$")


class VerificationError(ValueError):
    pass


@dataclass(frozen=True)
class ExpectedDocument:
    document_id: str
    kind: str
    title: str
    language: str
    tags: tuple[str, ...]
    source_label: str
    source_license: str
    source_sha256: str
    content_sha256: str
    canonical_sha256: str
    parser: str
    risk_flags: tuple[str, ...]
    canonical_json: str
    chunks: tuple[Any, ...]


def _exact_keys(value: Any, expected: set[str], field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise VerificationError(f"{field} muss ein Objekt sein")
    actual = set(value)
    if actual != expected:
        missing = sorted(expected - actual)
        unexpected = sorted(actual - expected)
        parts = []
        if missing:
            parts.append("fehlt=" + ",".join(missing))
        if unexpected:
            parts.append("unerwartet=" + ",".join(unexpected))
        raise VerificationError(f"{field} besitzt nicht exakt das Schema ({'; '.join(parts)})")
    return value


def _integer(value: Any, field: str, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise VerificationError(f"{field} muss eine Ganzzahl >= {minimum} sein")
    return value


def _text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise VerificationError(f"{field} muss ein nicht-leerer String sein")
    return value


def _sha(value: Any, field: str) -> str:
    digest = _text(value, field)
    if not SHA256_RE.fullmatch(digest):
        raise VerificationError(f"{field} ist kein SHA-256")
    return digest


def _read_regular(path: Path, max_bytes: int) -> bytes:
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        before = os.fstat(fd)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
            raise VerificationError("Artefaktdatei muss regulär und ohne Hardlink sein")
        if before.st_mode & 0o077:
            raise VerificationError("private Artefaktdatei ist für Gruppe/Welt lesbar")
        if before.st_size < 0 or before.st_size > max_bytes:
            raise VerificationError("Artefaktdatei überschreitet ihr Größenlimit")
        chunks: list[bytes] = []
        remaining = before.st_size
        while remaining:
            block = os.read(fd, min(remaining, 1024 * 1024))
            if not block:
                break
            chunks.append(block)
            remaining -= len(block)
        raw = b"".join(chunks)
        after = os.fstat(fd)
        fingerprint_before = (
            before.st_dev,
            before.st_ino,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        )
        fingerprint_after = (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        )
        if fingerprint_before != fingerprint_after or len(raw) != before.st_size:
            raise VerificationError("Artefaktdatei änderte sich während der Prüfung")
        return raw
    finally:
        os.close(fd)


def _database_fingerprint(path: Path) -> tuple[int, int, int, int, int]:
    metadata = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(metadata.st_mode) or metadata.st_nlink != 1:
        raise VerificationError("Datenbank muss regulär und ohne Hardlink sein")
    if metadata.st_mode & 0o077:
        raise VerificationError("private Datenbank ist für Gruppe/Welt lesbar")
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    fd = os.open(path, flags)
    try:
        while True:
            block = os.read(fd, 1024 * 1024)
            if not block:
                break
            digest.update(block)
    finally:
        os.close(fd)
    return digest.hexdigest()


def _load_manifest(root: Path) -> dict[str, Any]:
    raw = _read_regular(root / builder.MANIFEST_FILE, 1024 * 1024)
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=builder._reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise VerificationError("manifest.json ist kein eindeutiges UTF-8-JSON") from exc
    manifest = _exact_keys(value, TOP_KEYS, "manifest")
    if raw != (
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8"):
        raise VerificationError("manifest.json ist nicht kanonisch formatiert")
    return manifest


def _manifest_contract(manifest: dict[str, Any]) -> None:
    if _integer(manifest["schemaVersion"], "schemaVersion", 1) != builder.SCHEMA_VERSION:
        raise VerificationError("unbekannte schemaVersion")
    if manifest["artifactType"] != builder.ARTIFACT_TYPE:
        raise VerificationError("unbekannter artifactType")
    if manifest["artifactStatus"] != builder.ARTIFACT_STATUS:
        raise VerificationError("Bibliothek ist kein Offline-Kandidat")
    library_id = _text(manifest["libraryId"], "libraryId")
    if not builder.ID_RE.fullmatch(library_id):
        raise VerificationError("libraryId ist nicht kanonisch")
    generation_id = _text(manifest["generationId"], "generationId")
    if not GENERATION_RE.fullmatch(generation_id):
        raise VerificationError("generationId ist nicht kanonisch")
    language = _text(manifest["language"], "language")
    if not builder.LANGUAGE_RE.fullmatch(language):
        raise VerificationError("language ist nicht kanonisch")
    created_at = _text(manifest["createdAt"], "createdAt")
    if not builder.UTC_RE.fullmatch(created_at):
        raise VerificationError("createdAt ist nicht UTC/RFC3339")
    try:
        datetime.strptime(created_at, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise VerificationError("createdAt ist ungültig") from exc

    scope = _exact_keys(manifest["scope"], SCOPE_KEYS, "scope")
    if scope["kind"] == "shared":
        if scope["ownerId"] is not None or scope["recognitionRequired"] is not False:
            raise VerificationError("shared-Scope darf keine Person binden")
    elif scope["kind"] == "person":
        if (
            not isinstance(scope["ownerId"], str)
            or not builder.PERSON_ID_RE.fullmatch(scope["ownerId"])
            or scope["recognitionRequired"] is not True
        ):
            raise VerificationError("person-Scope besitzt keine gültige opake Bindung")
    else:
        raise VerificationError("unbekannter Scope")
    if manifest["egressPolicy"] != "never":
        raise VerificationError("private Bibliothek muss egressPolicy=never tragen")
    activation = _exact_keys(manifest["activation"], ACTIVATION_KEYS, "activation")
    if (
        activation["runtimeEnabled"] is not False
        or activation["voiceEnabled"] is not False
    ):
        raise VerificationError("K0 darf Runtime oder Voice nicht aktivieren")

    source = _exact_keys(manifest["source"], SOURCE_KEYS, "source")
    if source["mode"] != "local-files" or source["sourcePathsStored"] is not False:
        raise VerificationError("Quellvertrag ist nicht lokal und pfadfrei")
    _sha(source["inputSetSha256"], "source.inputSetSha256")
    _sha(source["logicalRecordsSha256"], "source.logicalRecordsSha256")
    _sha(source["semanticContentSha256"], "source.semanticContentSha256")
    if source["documentsFile"] != builder.DOCUMENTS_FILE:
        raise VerificationError("falsche kanonische Dokumentdatei")
    _sha(source["documentsSha256"], "source.documentsSha256")
    _integer(source["documentsSizeBytes"], "source.documentsSizeBytes", 1)
    _integer(source["fileCount"], "source.fileCount", 1)
    _text(source["defaultLicense"], "source.defaultLicense")

    build = _exact_keys(manifest["builder"], BUILDER_KEYS, "builder")
    if build["transform"] != builder.TRANSFORM_ID:
        raise VerificationError("unbekannte Builder-Transformation")
    if build["sourceFile"] != "tools/knowledge-library/build_library.py":
        raise VerificationError("Builder-Quellpfad ist nicht kanonisch")
    local_builder_hash = _sha256_file(HERE / "build_library.py")
    if _sha(build["sourceSha256"], "builder.sourceSha256") != local_builder_hash:
        raise VerificationError("Bibliothek ist nicht an diesen Builder gebunden")
    expected_python = ".".join(map(str, sys.version_info[:3]))
    if build["pythonVersion"] != expected_python:
        raise VerificationError("Python-Version weicht vom Buildervertrag ab")
    if build["sqliteVersion"] != sqlite3.sqlite_version:
        raise VerificationError("SQLite-Version weicht vom Buildervertrag ab")
    if build["modelDerivedFeatures"] != []:
        raise VerificationError("K0 darf keine modellgenerierten Features enthalten")

    database = _exact_keys(manifest["database"], DATABASE_KEYS, "database")
    if database["file"] != builder.DATABASE_FILE:
        raise VerificationError("falsche Datenbankdatei")
    _sha(database["sha256"], "database.sha256")
    _integer(database["sizeBytes"], "database.sizeBytes", 1)
    for field in (
        "documentCount",
        "chunkCount",
        "recipeCount",
        "riskFlaggedDocumentCount",
    ):
        _integer(database[field], f"database.{field}", 0)

    retrieval = _exact_keys(manifest["retrieval"], RETRIEVAL_KEYS, "retrieval")
    if retrieval != {
        "method": "fts5-title-tags-heading-text",
        "tokenizer": builder.TOKENIZER,
        "denseIndex": None,
    }:
        raise VerificationError("Retrievalvertrag weicht von K0 ab")
    limits = _exact_keys(manifest["limits"], LIMIT_KEYS, "limits")
    if _integer(limits["maxFiles"], "limits.maxFiles", 1) != builder.MAX_FILES:
        raise VerificationError("maxFiles weicht vom Builder ab")
    if (
        _integer(limits["maxFileBytes"], "limits.maxFileBytes", 1)
        != builder.MAX_FILE_BYTES
    ):
        raise VerificationError("maxFileBytes weicht vom Builder ab")
    if (
        _integer(limits["maxSourceBytes"], "limits.maxSourceBytes", 1)
        != builder.MAX_SOURCE_BYTES
    ):
        raise VerificationError("maxSourceBytes weicht vom Builder ab")
    if (
        _integer(limits["maxDocumentsBytes"], "limits.maxDocumentsBytes", 1)
        != builder.MAX_DOCUMENTS_BYTES
    ):
        raise VerificationError("maxDocumentsBytes weicht vom Builder ab")
    max_database = _integer(limits["maxDatabaseBytes"], "limits.maxDatabaseBytes", 1)
    if max_database > builder.MAX_DATABASE_BYTES:
        raise VerificationError("maxDatabaseBytes überschreitet den K0-Hardcap")
    if database["sizeBytes"] > max_database:
        raise VerificationError("Datenbank überschreitet das manifestierte Größenlimit")


def _parse_source(record: dict[str, Any], field: str) -> tuple[str, str]:
    source = _exact_keys(record, builder.SOURCE_KEYS, field)
    return (
        builder._atomic_string(source["label"], f"{field}.label", max_chars=200),
        builder._atomic_string(source["license"], f"{field}.license", max_chars=100),
    )


def _document_id(library_id: str, kind: str, title: str) -> str:
    return builder.sha256_bytes(
        f"{library_id}\0{kind}\0{title.casefold()}".encode("utf-8")
    )


def _note_record(record: dict[str, Any], library_id: str) -> ExpectedDocument:
    _exact_keys(record, NOTE_KEYS, "note")
    if (
        _integer(record["schemaVersion"], "note.schemaVersion", 1) != 1
        or _integer(record["revision"], "note.revision", 1) != 1
        or record["kind"] != "note"
    ):
        raise VerificationError("Notiz besitzt falsche Version oder Art")
    title = builder._atomic_string(record["title"], "note.title", max_chars=200)
    language = builder._atomic_string(record["language"], "note.language", max_chars=10)
    if not builder.LANGUAGE_RE.fullmatch(language):
        raise VerificationError("Notizsprache ist ungültig")
    tags = builder._string_list(record["tags"], "note.tags")
    source_label, source_license = _parse_source(record["source"], "note.source")
    content = _exact_keys(record["content"], NOTE_CONTENT_KEYS, "note.content")
    if content["format"] not in {"markdown", "text"}:
        raise VerificationError("Notizformat ist unbekannt")
    sections = content["sections"]
    if not isinstance(sections, list) or not sections:
        raise VerificationError("Notiz braucht mindestens einen Abschnitt")
    chunks = []
    for ordinal, section_value in enumerate(sections):
        section = _exact_keys(section_value, SECTION_KEYS, f"note.sections[{ordinal}]")
        heading = builder._atomic_string(
            section["heading"],
            f"note.sections[{ordinal}].heading",
            max_chars=300,
        )
        body = builder._clean_string(
            section["body"],
            f"note.sections[{ordinal}].body",
            max_chars=builder.MAX_CHUNK_CHARS,
        )
        if heading != section["heading"] or body != section["body"]:
            raise VerificationError("Notizabschnitt ist nicht kanonisch")
        chunks.append(builder.Chunk(ordinal=ordinal, heading=heading, text=body))
    flags = builder._risk_flags(
        builder._risk_surface(
            title=title,
            tags=tags,
            source_label=source_label,
            source_license=source_license,
            chunks=tuple(chunks),
        )
    )
    if record["riskFlags"] != list(flags):
        raise VerificationError("Notiz-Risikomarker sind nicht reproduzierbar")
    source_sha256 = _sha(record["sourceSha256"], "note.sourceSha256")
    content_sha256 = _sha(record["contentSha256"], "note.contentSha256")
    semantic_record = {
        key: value
        for key, value in record.items()
        if key
        not in {
            "documentId",
            "revision",
            "riskFlags",
            "sourceSha256",
            "contentSha256",
        }
    }
    if (
        builder.sha256_bytes(builder.canonical_json(semantic_record).encode("utf-8"))
        != content_sha256
    ):
        raise VerificationError("Notiz-Inhaltshash ist nicht reproduzierbar")
    expected_id = _document_id(library_id, "note", title)
    if record["documentId"] != expected_id:
        raise VerificationError("Notiz-ID ist nicht deterministisch")
    canonical = builder.canonical_json(record)
    return ExpectedDocument(
        document_id=expected_id,
        kind="note",
        title=title,
        language=language,
        tags=tags,
        source_label=source_label,
        source_license=source_license,
        source_sha256=source_sha256,
        content_sha256=content_sha256,
        canonical_sha256=builder.sha256_bytes(canonical.encode("utf-8")),
        parser="markdown-heading-v1" if content["format"] == "markdown" else "plain-text-v1",
        risk_flags=flags,
        canonical_json=canonical,
        chunks=tuple(chunks),
    )


def _recipe_record(record: dict[str, Any], library_id: str) -> ExpectedDocument:
    keys = set(record)
    if not RECIPE_BASE_KEYS <= keys or not keys <= RECIPE_BASE_KEYS | RECIPE_OPTIONAL_KEYS:
        raise VerificationError("Rezept besitzt nicht das exakte K0-Schema")
    if (
        _integer(record["schemaVersion"], "recipe.schemaVersion", 1) != 1
        or _integer(record["revision"], "recipe.revision", 1) != 1
        or record["kind"] != "recipe"
    ):
        raise VerificationError("Rezept besitzt falsche Version oder Art")
    title = builder._atomic_string(record["title"], "recipe.title", max_chars=200)
    language = builder._atomic_string(record["language"], "recipe.language", max_chars=10)
    if not builder.LANGUAGE_RE.fullmatch(language):
        raise VerificationError("Rezeptsprache ist ungültig")
    tags = builder._string_list(record["tags"], "recipe.tags")
    notes = builder._string_list(
        record["notes"],
        "recipe.notes",
        max_chars=2_000,
    )
    source_label, source_license = _parse_source(record["source"], "recipe.source")

    ingredients = record["ingredients"]
    if (
        not isinstance(ingredients, list)
        or not ingredients
        or len(ingredients) > builder.MAX_RECIPE_ITEMS
    ):
        raise VerificationError("Rezeptzutaten sind leer oder zu groß")
    ingredient_lines = []
    for index, value in enumerate(ingredients, 1):
        if not isinstance(value, dict):
            raise VerificationError("Rezeptzutat muss ein Objekt sein")
        if not set(value) <= builder.INGREDIENT_KEYS or "itemText" not in value:
            raise VerificationError("Rezeptzutat besitzt unbekannte oder fehlende Felder")
        normalized = {
            key: builder._atomic_string(
                value[key],
                f"recipe.ingredients[{index}].{key}",
                max_chars=300 if key in {"itemText", "noteText"} else 50,
            )
            for key in value
        }
        if normalized != value:
            raise VerificationError("Rezeptzutat ist nicht kanonisch")
        quantity = " ".join(
            normalized[key]
            for key in ("amountText", "unitText")
            if key in normalized
        )
        note = f" — {normalized['noteText']}" if "noteText" in normalized else ""
        ingredient_lines.append(
            f"{index}. {quantity + ' ' if quantity else ''}{normalized['itemText']}{note}"
        )

    steps_value = record["steps"]
    if (
        not isinstance(steps_value, list)
        or not steps_value
        or len(steps_value) > builder.MAX_RECIPE_ITEMS
    ):
        raise VerificationError("Rezeptschritte sind leer oder zu groß")
    steps = [
        builder._atomic_string(value, f"recipe.steps[{index}]", max_chars=2_000)
        for index, value in enumerate(steps_value, 1)
    ]

    overview = []
    if "yieldText" in record:
        overview.append(
            "Ergibt: "
            + builder._atomic_string(record["yieldText"], "recipe.yieldText", max_chars=100)
        )
    times = record.get("times", {})
    if "times" in record:
        if not isinstance(times, dict) or not times or not set(times) <= builder.TIMES_KEYS:
            raise VerificationError("Rezeptzeiten besitzen ein ungültiges Schema")
        if "prepText" in times:
            overview.append(
                "Vorbereitung: "
                + builder._atomic_string(times["prepText"], "recipe.times.prepText", max_chars=100)
            )
        if "cookText" in times:
            overview.append(
                "Garzeit: "
                + builder._atomic_string(times["cookText"], "recipe.times.cookText", max_chars=100)
            )

    chunks = []
    chunks.extend(builder._recipe_section_chunks(title, "Übersicht", overview, len(chunks)))
    chunks.extend(
        builder._recipe_section_chunks(title, "Zutaten", ingredient_lines, len(chunks))
    )
    chunks.extend(
        builder._recipe_section_chunks(
            title,
            "Zubereitung",
            [f"{index}. {step}" for index, step in enumerate(steps, 1)],
            len(chunks),
        )
    )
    chunks.extend(
        builder._recipe_section_chunks(
            title,
            "Notizen",
            [f"{index}. {note}" for index, note in enumerate(notes, 1)],
            len(chunks),
        )
    )
    flags = builder._risk_flags(
        builder._risk_surface(
            title=title,
            tags=tags,
            source_label=source_label,
            source_license=source_license,
            chunks=tuple(chunks),
        )
    )
    if record["riskFlags"] != list(flags):
        raise VerificationError("Rezept-Risikomarker sind nicht reproduzierbar")
    source_sha256 = _sha(record["sourceSha256"], "recipe.sourceSha256")
    content_sha256 = _sha(record["contentSha256"], "recipe.contentSha256")
    semantic_record = {
        key: value
        for key, value in record.items()
        if key
        not in {
            "documentId",
            "revision",
            "riskFlags",
            "sourceSha256",
            "contentSha256",
        }
    }
    if (
        builder.sha256_bytes(builder.canonical_json(semantic_record).encode("utf-8"))
        != content_sha256
    ):
        raise VerificationError("Rezept-Inhaltshash ist nicht reproduzierbar")
    expected_id = _document_id(library_id, "recipe", title)
    if record["documentId"] != expected_id:
        raise VerificationError("Rezept-ID ist nicht deterministisch")
    canonical = builder.canonical_json(record)
    return ExpectedDocument(
        document_id=expected_id,
        kind="recipe",
        title=title,
        language=language,
        tags=tags,
        source_label=source_label,
        source_license=source_license,
        source_sha256=source_sha256,
        content_sha256=content_sha256,
        canonical_sha256=builder.sha256_bytes(canonical.encode("utf-8")),
        parser="recipe-json-v1",
        risk_flags=flags,
        canonical_json=canonical,
        chunks=tuple(chunks),
    )


def _documents(raw: bytes, library_id: str) -> list[ExpectedDocument]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise VerificationError("documents.jsonl ist kein UTF-8") from exc
    if not text.endswith("\n"):
        raise VerificationError("documents.jsonl braucht einen abschließenden LF")
    documents = []
    for line_no, line in enumerate(text.splitlines(), 1):
        if not line:
            raise VerificationError("documents.jsonl enthält eine Leerzeile")
        try:
            record = json.loads(line, object_pairs_hook=builder._reject_duplicate_keys)
        except (json.JSONDecodeError, ValueError) as exc:
            raise VerificationError(f"documents.jsonl Zeile {line_no} ist ungültig") from exc
        if builder.canonical_json(record) != line:
            raise VerificationError("documents.jsonl ist nicht kanonisch")
        if not isinstance(record, dict):
            raise VerificationError("Dokumentzeile muss ein Objekt sein")
        kind = record.get("kind")
        if kind == "note":
            documents.append(_note_record(record, library_id))
        elif kind == "recipe":
            documents.append(_recipe_record(record, library_id))
        else:
            raise VerificationError("unbekannte Dokumentart")
    if not documents or len(documents) > builder.MAX_FILES:
        raise VerificationError("ungültige Dokumentzahl")
    if [doc.document_id for doc in documents] != sorted(doc.document_id for doc in documents):
        raise VerificationError("Dokumente sind nicht deterministisch sortiert")
    if len({doc.document_id for doc in documents}) != len(documents):
        raise VerificationError("doppelte Dokument-ID")
    if len({doc.title.casefold() for doc in documents}) != len(documents):
        raise VerificationError("doppelter Dokumenttitel")
    if len({doc.canonical_sha256 for doc in documents}) != len(documents):
        raise VerificationError("doppelter kanonischer Dokumentinhalt")
    return documents


def _schema_contract(conn: sqlite3.Connection) -> list[tuple[Any, ...]]:
    return [
        tuple(row)
        for row in conn.execute(
            "SELECT type,name,tbl_name,sql FROM sqlite_master "
            "WHERE name NOT LIKE 'sqlite_%' ORDER BY type,name"
        ).fetchall()
    ]


def _verify_schema(conn: sqlite3.Connection, db_path: Path) -> None:
    actual_tables = {
        row[0]
        for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")
    }
    if actual_tables != EXPECTED_TABLES:
        raise VerificationError("SQLite besitzt nicht exakt das private K0-Schema")
    with tempfile.TemporaryDirectory(prefix="hoshi-private-schema-proof-") as temp:
        expected_path = Path(temp) / "expected.sqlite"
        with sqlite3.connect(expected_path) as expected:
            builder._create_schema(expected)
            expected_schema = _schema_contract(expected)
    if _schema_contract(conn) != expected_schema:
        raise VerificationError("SQLite-Schema weicht vom gebundenen Builder ab")
    if conn.execute("PRAGMA user_version").fetchone()[0] != builder.SCHEMA_VERSION:
        raise VerificationError("SQLite user_version weicht ab")
    if conn.execute("PRAGMA application_id").fetchone()[0] != builder.APPLICATION_ID:
        raise VerificationError("SQLite application_id weicht ab")
    page_size = conn.execute("PRAGMA page_size").fetchone()[0]
    page_count = conn.execute("PRAGMA page_count").fetchone()[0]
    if conn.execute("PRAGMA freelist_count").fetchone()[0] != 0:
        raise VerificationError("SQLite enthält freie Seiten")
    if db_path.stat().st_size != page_size * page_count:
        raise VerificationError("SQLite besitzt angehängte Bytes")


def _verify_fts(db_path: Path) -> None:
    try:
        with tempfile.TemporaryDirectory(prefix="hoshi-private-fts-proof-") as temp:
            copied = Path(temp) / builder.DATABASE_FILE
            shutil.copyfile(db_path, copied)
            with sqlite3.connect(copied) as conn:
                conn.execute(
                    "INSERT INTO chunks_fts(chunks_fts, rank) "
                    "VALUES('integrity-check', 1)"
                )
    except (OSError, sqlite3.Error) as exc:
        raise VerificationError("FTS5-External-Content-Integrität fehlt") from exc


def _verify_database(
    *,
    root: Path,
    manifest: dict[str, Any],
    documents: list[ExpectedDocument],
) -> None:
    db_path = root / builder.DATABASE_FILE
    fingerprint = _database_fingerprint(db_path)
    database = manifest["database"]
    if fingerprint[2] != database["sizeBytes"]:
        raise VerificationError("Datenbankgröße stimmt nicht mit Manifest überein")
    if _sha256_file(db_path) != database["sha256"]:
        raise VerificationError("Datenbank-SHA-256 stimmt nicht")
    with tempfile.TemporaryDirectory(prefix="hoshi-private-db-proof-") as temp:
        expected_path = Path(temp) / builder.DATABASE_FILE
        expected_chunk_count = builder._write_database(
            db_path=expected_path,
            library_id=manifest["libraryId"],
            scope=builder.Scope(
                kind=manifest["scope"]["kind"],
                owner_id=manifest["scope"]["ownerId"],
            ),
            language=manifest["language"],
            created_at=manifest["createdAt"],
            logical_records_sha256=manifest["source"]["logicalRecordsSha256"],
            documents=documents,
        )
        if expected_chunk_count != database["chunkCount"]:
            raise VerificationError("rekonstruierte Chunkzahl weicht ab")
        if expected_path.stat().st_size != fingerprint[2]:
            raise VerificationError("SQLite ist nicht die exakte kanonische Ableitung")
        if _sha256_file(expected_path) != database["sha256"]:
            raise VerificationError("SQLite enthält nicht aus den Records ableitbare Bytes")
    uri = (
        "file:"
        + urllib.parse.quote(str(db_path.resolve()), safe="/")
        + "?mode=ro&immutable=1"
    )
    with sqlite3.connect(uri, uri=True) as conn:
        conn.row_factory = sqlite3.Row
        _verify_schema(conn, db_path)
        library_rows = conn.execute("SELECT * FROM library").fetchall()
        if len(library_rows) != 1:
            raise VerificationError("library-Tabelle besitzt nicht genau eine Zeile")
        library = library_rows[0]
        scope = manifest["scope"]
        if (
            library["singleton"] != 1
            or library["library_id"] != manifest["libraryId"]
            or library["scope_kind"] != scope["kind"]
            or library["owner_id"] != scope["ownerId"]
            or library["language"] != manifest["language"]
            or library["egress_policy"] != "never"
            or library["artifact_status"] != builder.ARTIFACT_STATUS
            or library["runtime_enabled"] != 0
            or library["created_at"] != manifest["createdAt"]
            or library["transform_id"] != builder.TRANSFORM_ID
            or library["logical_records_sha256"]
            != manifest["source"]["logicalRecordsSha256"]
        ):
            raise VerificationError("library-Metadaten stimmen nicht mit Manifest überein")

        actual_documents = conn.execute(
            "SELECT * FROM documents ORDER BY document_id"
        ).fetchall()
        if len(actual_documents) != len(documents):
            raise VerificationError("Dokumentzahl in SQLite weicht ab")
        expected_chunks = []
        for expected, actual in zip(documents, actual_documents):
            expected_row = (
                expected.document_id,
                1,
                expected.kind,
                expected.title,
                expected.language,
                builder.canonical_json(list(expected.tags)),
                expected.source_label,
                expected.source_license,
                expected.canonical_sha256,
                expected.parser,
                1,
                builder.canonical_json(list(expected.risk_flags)),
                expected.canonical_json,
            )
            if tuple(actual) != expected_row:
                raise VerificationError("Dokumentzeile ist keine Ableitung aus documents.jsonl")
            for chunk in expected.chunks:
                chunk_id = builder.sha256_bytes(
                    f"{expected.document_id}\0{chunk.ordinal}".encode("utf-8")
                )
                expected_chunks.append(
                    (
                        chunk_id,
                        expected.document_id,
                        chunk.ordinal,
                        expected.title,
                        " ".join(expected.tags),
                        chunk.heading,
                        chunk.text,
                        builder.sha256_bytes(chunk.text.encode("utf-8")),
                    )
                )
        actual_chunks = conn.execute(
            "SELECT chunk_id,document_id,ordinal,title,tags,heading,text,text_sha256 "
            "FROM chunks ORDER BY rowid"
        ).fetchall()
        if [tuple(row) for row in actual_chunks] != expected_chunks:
            raise VerificationError("Chunkzeilen sind keine Ableitung aus documents.jsonl")
        if conn.execute("SELECT count(*) FROM chunks_fts").fetchone()[0] != len(expected_chunks):
            raise VerificationError("FTS-Zeilenzahl weicht von Chunks ab")
        if conn.execute("PRAGMA foreign_key_check").fetchall():
            raise VerificationError("SQLite-Fremdschlüssel sind verletzt")
        if conn.execute("PRAGMA quick_check").fetchone()[0] != "ok":
            raise VerificationError("SQLite quick_check ist nicht ok")

    if _database_fingerprint(db_path) != fingerprint:
        raise VerificationError("Datenbank änderte sich während der Prüfung")
    _verify_fts(db_path)


def _verify_snapshot(root: Path) -> dict[str, Any]:
    if root.is_symlink() or not root.is_dir():
        raise VerificationError("Bibliothekspfad muss ein reguläres Verzeichnis sein")
    if root.stat().st_mode & 0o077:
        raise VerificationError("privates Bibliotheksverzeichnis ist für Gruppe/Welt zugänglich")
    actual_files = {entry.name for entry in root.iterdir()}
    if actual_files != EXPECTED_FILES:
        raise VerificationError("Bibliothek enthält fehlende oder zusätzliche Dateien")
    manifest = _load_manifest(root)
    _manifest_contract(manifest)

    source = manifest["source"]
    documents_raw = _read_regular(
        root / builder.DOCUMENTS_FILE,
        builder.MAX_DOCUMENTS_BYTES,
    )
    if len(documents_raw) != source["documentsSizeBytes"]:
        raise VerificationError("documents.jsonl-Größe stimmt nicht")
    if hashlib.sha256(documents_raw).hexdigest() != source["documentsSha256"]:
        raise VerificationError("documents.jsonl-SHA-256 stimmt nicht")
    if hashlib.sha256(documents_raw).hexdigest() != source["logicalRecordsSha256"]:
        raise VerificationError("logischer Records-Hash stimmt nicht")
    documents = _documents(documents_raw, manifest["libraryId"])
    if len(documents) != source["fileCount"]:
        raise VerificationError("source.fileCount stimmt nicht")
    expected_input_set = builder.sha256_bytes(
        builder.canonical_json(
            sorted(document.source_sha256 for document in documents)
        ).encode("utf-8")
    )
    if source["inputSetSha256"] != expected_input_set:
        raise VerificationError("source.inputSetSha256 ist nicht aus den Records ableitbar")
    expected_semantic_content = builder.sha256_bytes(
        builder.canonical_json(
            sorted(document.content_sha256 for document in documents)
        ).encode("utf-8")
    )
    if source["semanticContentSha256"] != expected_semantic_content:
        raise VerificationError(
            "source.semanticContentSha256 ist nicht aus den Records ableitbar"
        )

    database = manifest["database"]
    chunk_count = sum(len(document.chunks) for document in documents)
    recipe_count = sum(document.kind == "recipe" for document in documents)
    risk_count = sum(bool(document.risk_flags) for document in documents)
    if database["documentCount"] != len(documents):
        raise VerificationError("database.documentCount stimmt nicht")
    if database["chunkCount"] != chunk_count:
        raise VerificationError("database.chunkCount stimmt nicht")
    if database["recipeCount"] != recipe_count:
        raise VerificationError("database.recipeCount stimmt nicht")
    if database["riskFlaggedDocumentCount"] != risk_count:
        raise VerificationError("database.riskFlaggedDocumentCount stimmt nicht")

    expected_generation = builder._generation_id(manifest)
    if manifest["generationId"] != expected_generation:
        raise VerificationError("generationId ist nicht deterministisch")
    _verify_database(root=root, manifest=manifest, documents=documents)
    return {
        "status": "ok",
        "artifactStatus": builder.ARTIFACT_STATUS,
        "scopeKind": manifest["scope"]["kind"],
        "personIdPresent": manifest["scope"]["ownerId"] is not None,
        "runtimeEnabled": False,
        "voiceEnabled": False,
        "documentCount": len(documents),
        "chunkCount": chunk_count,
        "recipeCount": recipe_count,
        "riskFlaggedDocumentCount": risk_count,
        "logicalRecordsSha256": source["logicalRecordsSha256"],
        "semanticContentSha256": source["semanticContentSha256"],
    }


def _fingerprint(metadata: os.stat_result) -> tuple[int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def verify_library(root: Path) -> dict[str, Any]:
    """Prüft einen über stabile Deskriptoren eingefrorenen 3-Dateien-Snapshot."""

    root_flags = (
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    root_fd = os.open(root, root_flags)
    file_descriptors: dict[str, tuple[int, tuple[int, int, int, int, int]]] = {}
    try:
        root_before = os.fstat(root_fd)
        if not stat.S_ISDIR(root_before.st_mode):
            raise VerificationError("Bibliothekspfad muss ein reguläres Verzeichnis sein")
        if root_before.st_mode & 0o077:
            raise VerificationError(
                "privates Bibliotheksverzeichnis ist für Gruppe/Welt zugänglich"
            )
        if set(os.listdir(root_fd)) != EXPECTED_FILES:
            raise VerificationError("Bibliothek enthält fehlende oder zusätzliche Dateien")
        limits = {
            builder.MANIFEST_FILE: 1024 * 1024,
            builder.DOCUMENTS_FILE: builder.MAX_DOCUMENTS_BYTES,
            builder.DATABASE_FILE: builder.MAX_DATABASE_BYTES,
        }
        with tempfile.TemporaryDirectory(prefix="hoshi-private-snapshot-") as temp:
            snapshot = Path(temp)
            os.chmod(snapshot, stat.S_IRWXU)
            for name in sorted(EXPECTED_FILES):
                flags = (
                    os.O_RDONLY
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_NOFOLLOW", 0)
                )
                descriptor = os.open(name, flags, dir_fd=root_fd)
                before = os.fstat(descriptor)
                if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
                    os.close(descriptor)
                    raise VerificationError(
                        "Artefaktdatei muss regulär und ohne Hardlink sein"
                    )
                if before.st_mode & 0o077:
                    os.close(descriptor)
                    raise VerificationError(
                        "private Artefaktdatei ist für Gruppe/Welt lesbar"
                    )
                if before.st_size < 0 or before.st_size > limits[name]:
                    os.close(descriptor)
                    raise VerificationError("Artefaktdatei überschreitet ihr Größenlimit")
                chunks: list[bytes] = []
                remaining = before.st_size
                while remaining:
                    block = os.read(descriptor, min(remaining, 1024 * 1024))
                    if not block:
                        break
                    chunks.append(block)
                    remaining -= len(block)
                raw = b"".join(chunks)
                after = os.fstat(descriptor)
                if _fingerprint(after) != _fingerprint(before) or len(raw) != before.st_size:
                    os.close(descriptor)
                    raise VerificationError(
                        "Artefaktdatei änderte sich beim Erzeugen des Snapshots"
                    )
                file_descriptors[name] = (descriptor, _fingerprint(before))
                snapshot_path = snapshot / name
                snapshot_path.write_bytes(raw)
                os.chmod(snapshot_path, stat.S_IRUSR | stat.S_IWUSR)

            result = _verify_snapshot(snapshot)

            if set(os.listdir(root_fd)) != EXPECTED_FILES:
                raise VerificationError("Bibliotheksinhalt änderte sich während der Prüfung")
            for name, (descriptor, expected) in file_descriptors.items():
                if _fingerprint(os.fstat(descriptor)) != expected:
                    raise VerificationError(
                        "Artefaktdatei änderte sich während der Gesamtprüfung"
                    )
                current = os.stat(name, dir_fd=root_fd, follow_symlinks=False)
                if _fingerprint(current) != expected:
                    raise VerificationError(
                        "Artefaktdateipfad änderte sich während der Gesamtprüfung"
                    )
            if _fingerprint(os.fstat(root_fd)) != _fingerprint(root_before):
                raise VerificationError(
                    "Bibliotheksverzeichnis änderte sich während der Gesamtprüfung"
                )
            visible_root = root.stat(follow_symlinks=False)
            if (
                not stat.S_ISDIR(visible_root.st_mode)
                or visible_root.st_dev != root_before.st_dev
                or visible_root.st_ino != root_before.st_ino
            ):
                raise VerificationError(
                    "Bibliothekspfad änderte sich während der Gesamtprüfung"
                )
            return result
    finally:
        for descriptor, _ in file_descriptors.values():
            os.close(descriptor)
        os.close(root_fd)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("library_dir", type=Path)
    args = parser.parse_args()
    try:
        result = verify_library(args.library_dir.expanduser())
    except (OSError, sqlite3.Error, VerificationError, ValueError) as exc:
        print(f"[knowledge-library-verify] FATAL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
