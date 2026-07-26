#!/usr/bin/env python3
"""Baut eine private, lokale Hoshi-Wissensbibliothek als immutable SQLite.

Das Werkzeug ist absichtlich nur die Offline-Bauscheibe K0. Es aktiviert keine
Runtime, verändert keine Hoshi-Konfiguration und sendet keine Inhalte ins Netz.
"""

from __future__ import annotations

import argparse
import ctypes
import errno
import hashlib
import json
import os
import re
import sqlite3
import stat
import tempfile
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional


SCHEMA_VERSION = 1
ARTIFACT_TYPE = "hoshi-private-knowledge-library"
ARTIFACT_STATUS = "offline-candidate"
DATABASE_FILE = "knowledge.sqlite"
MANIFEST_FILE = "manifest.json"
DOCUMENTS_FILE = "documents.jsonl"
TRANSFORM_ID = "hoshi-private-library-v1-markdown-text-recipe-fts5"
TOKENIZER = "unicode61 remove_diacritics 2"
APPLICATION_ID = 0x484B4C31  # ASCII-nahe Kennung "HKL1"
MAX_FILES = 1_000
MAX_FILE_BYTES = 1024 * 1024
MAX_SOURCE_BYTES = 16 * 1024 * 1024
MAX_DOCUMENTS_BYTES = 48 * 1024 * 1024
MAX_DATABASE_BYTES = 64 * 1024 * 1024
MAX_CHUNK_CHARS = 4_000
MAX_RECIPE_ITEMS = 200
IGNORED_NAMES = {".DS_Store"}
ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,63}$")
PERSON_ID_RE = re.compile(r"^person_[0-9a-f]{32}$")
LANGUAGE_RE = re.compile(r"^[a-z]{2,3}(?:-[A-Z]{2})?$")
UTC_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
HEADING_RE = re.compile(r"^(#{1,6})[ \t]+(.+?)\s*$")
INSTRUCTION_PATTERNS = (
    re.compile(r"\bignoriere\b.{0,80}\b(?:regel|anweisung|system|prompt)", re.I | re.S),
    re.compile(r"\bignore\b.{0,80}\b(?:rule|instruction|system|prompt)", re.I | re.S),
    re.compile(r"\b(?:system[- ]?prompt|developer message)\b", re.I),
    re.compile(r"</?system>|[\"']tool[\"']\s*:", re.I),
    re.compile(
        r"\b(?:schalte|turn)\b.{0,80}\b(?:licht|light)\b.{0,40}\b(?:aus|off)\b",
        re.I | re.S,
    ),
)
RECIPE_KEYS = {
    "schemaVersion",
    "type",
    "title",
    "language",
    "yieldText",
    "times",
    "ingredients",
    "steps",
    "tags",
    "notes",
    "source",
}
RECIPE_REQUIRED_KEYS = {
    "schemaVersion",
    "type",
    "title",
    "language",
    "ingredients",
    "steps",
}
INGREDIENT_KEYS = {"amountText", "unitText", "itemText", "noteText"}
TIMES_KEYS = {"prepText", "cookText"}
SOURCE_KEYS = {"label", "license"}


@dataclass(frozen=True)
class Scope:
    kind: str
    owner_id: Optional[str]


@dataclass(frozen=True)
class Chunk:
    ordinal: int
    heading: str
    text: str


@dataclass(frozen=True)
class Document:
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
    chunks: tuple[Chunk, ...]


def canonical_json(value: Any) -> str:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    )


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _version_is(value: Any, expected: int) -> bool:
    return type(value) is int and value == expected


def _generation_id(manifest: dict[str, Any]) -> str:
    material = {
        key: value for key, value in manifest.items() if key != "generationId"
    }
    return "gen_" + sha256_bytes(
        canonical_json(material).encode("utf-8")
    )[:24]


def validate_utc(value: Optional[str]) -> str:
    timestamp = value or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if not UTC_RE.fullmatch(timestamp):
        raise ValueError("created-at muss UTC im Format YYYY-MM-DDTHH:MM:SSZ sein")
    try:
        datetime.strptime(timestamp, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise ValueError("created-at ist kein gültiger UTC-Zeitpunkt") from exc
    return timestamp


def parse_scope(value: str) -> Scope:
    if value == "shared":
        return Scope(kind="shared", owner_id=None)
    if value.startswith("person:"):
        owner_id = value.removeprefix("person:")
        if not PERSON_ID_RE.fullmatch(owner_id):
            raise ValueError(
                "person-Scope braucht eine opake ID im Format person_<32 lowercase hex>"
            )
        return Scope(kind="person", owner_id=owner_id)
    raise ValueError("scope muss 'shared' oder 'person:<opaque-id>' sein")


def _clean_string(
    value: Any,
    field: str,
    *,
    allow_empty: bool = False,
    max_chars: int = 2_000,
) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{field} muss ein String sein")
    normalized = unicodedata.normalize("NFC", value).replace("\r\n", "\n").replace("\r", "\n")
    if not allow_empty and not normalized.strip():
        raise ValueError(f"{field} darf nicht leer sein")
    if len(normalized) > max_chars:
        raise ValueError(f"{field} überschreitet {max_chars} Zeichen")
    for char in normalized:
        code = ord(char)
        if code == 0 or (code < 32 and char not in "\n\t"):
            raise ValueError(f"{field} enthält unzulässige Steuerzeichen")
    return normalized.strip()


def _atomic_string(
    value: Any,
    field: str,
    *,
    allow_empty: bool = False,
    max_chars: int = 2_000,
) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{field} muss ein String sein")
    if value != unicodedata.normalize("NFC", value):
        raise ValueError(f"{field} muss NFC-normalisiert sein")
    if value != value.strip():
        raise ValueError(f"{field} darf keinen äußeren Whitespace enthalten")
    if "\n" in value or "\r" in value or "\t" in value:
        raise ValueError(f"{field} muss ein einzeiliger atomarer Wert sein")
    return _clean_string(
        value,
        field,
        allow_empty=allow_empty,
        max_chars=max_chars,
    )


def _decode_text(raw: bytes, field: str) -> str:
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise ValueError(f"{field} muss UTF-8 ohne BOM sein")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise ValueError(f"{field} ist kein gültiges UTF-8") from exc
    if text != unicodedata.normalize("NFC", text):
        raise ValueError(f"{field} muss NFC-normalisiert sein")
    return _clean_string(text, field, max_chars=MAX_FILE_BYTES)


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"JSON enthält den doppelten Schlüssel {key!r}")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise ValueError(f"JSON-Zahl {value!r} ist nicht zulässig")


def _json_object(raw: bytes, field: str) -> dict[str, Any]:
    if raw.startswith((b"\xef\xbb\xbf", b"\xff\xfe", b"\xfe\xff")):
        raise ValueError(f"{field} muss UTF-8 ohne BOM sein")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_json_constant,
        )
    except UnicodeDecodeError as exc:
        raise ValueError(f"{field} ist kein gültiges UTF-8") from exc
    except (json.JSONDecodeError, RecursionError) as exc:
        raise ValueError(f"{field} enthält ungültiges JSON: {exc}") from exc
    if not isinstance(value, dict):
        raise ValueError(f"{field} muss ein JSON-Objekt enthalten")
    return value


def _source_format(name: str) -> str:
    lower = name.lower()
    if lower.endswith(".recipe.json"):
        return "recipe"
    if lower.endswith(".md"):
        return "markdown"
    if lower.endswith(".txt"):
        return "text"
    raise ValueError("nicht unterstütztes Format im Importverzeichnis")


def _safe_source_names(root_fd: int) -> list[str]:
    names: list[str] = []
    for raw_name in os.listdir(root_fd):
        name = unicodedata.normalize("NFC", raw_name)
        if (
            name != raw_name
            or "/" in name
            or "\\" in name
            or name in {".", ".."}
            or any(ord(char) < 32 or ord(char) == 127 for char in name)
        ):
            raise ValueError("Quellname ist nicht kanonisch")
        metadata = os.stat(raw_name, dir_fd=root_fd, follow_symlinks=False)
        if stat.S_ISLNK(metadata.st_mode):
            raise ValueError("Symlinks sind im Importverzeichnis nicht erlaubt")
        if not stat.S_ISREG(metadata.st_mode):
            raise ValueError("Unterverzeichnisse und Spezialdateien sind in K0 nicht erlaubt")
        if raw_name in IGNORED_NAMES:
            continue
        _source_format(name)
        names.append(name)
    names.sort(key=str.casefold)
    if not names:
        raise ValueError("Quellpfad enthält keine unterstützten Dokumente")
    if len(names) > MAX_FILES:
        raise ValueError(f"Bibliothek überschreitet das Dateilimit von {MAX_FILES}")
    folded: set[str] = set()
    for name in names:
        key = name.casefold()
        if key in folded:
            raise ValueError("Quellnamen kollidieren ohne Beachtung der Großschreibung")
        folded.add(key)
    return names


def _read_sources(source: Path) -> list[tuple[str, bytes]]:
    result: list[tuple[str, bytes]] = []
    total = 0
    open_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0)
    root_fd = os.open(source, open_flags)
    try:
        root_stat = os.fstat(root_fd)
        if not stat.S_ISDIR(root_stat.st_mode):
            raise ValueError("Quellpfad ist kein reguläres Verzeichnis")
        names = _safe_source_names(root_fd)
        for name in names:
            file_flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
            fd = os.open(name, file_flags, dir_fd=root_fd)
            try:
                before = os.fstat(fd)
                if not stat.S_ISREG(before.st_mode):
                    raise ValueError("Quelle ist keine reguläre Datei")
                if before.st_nlink != 1:
                    raise ValueError("Hardlinks sind im Importverzeichnis nicht erlaubt")
                if before.st_size <= 0:
                    raise ValueError("leere Quelldatei")
                if before.st_size > MAX_FILE_BYTES:
                    raise ValueError(
                        f"Quelldatei überschreitet das Limit von {MAX_FILE_BYTES} Bytes"
                    )
                chunks: list[bytes] = []
                remaining = before.st_size
                while remaining:
                    block = os.read(fd, min(remaining, 64 * 1024))
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
                    raise ValueError("Quelldatei änderte sich während des Imports")
            finally:
                os.close(fd)
            total += len(raw)
            if total > MAX_SOURCE_BYTES:
                raise ValueError(
                    f"Bibliothek überschreitet das Quellbudget von {MAX_SOURCE_BYTES} Bytes"
                )
            result.append((name, raw))
    finally:
        os.close(root_fd)
    return result


def _split_long_text(text: str, heading: str) -> list[tuple[str, str]]:
    text = text.strip()
    if not text:
        return []
    paragraphs = [part.strip() for part in re.split(r"\n{2,}", text) if part.strip()]
    parts: list[str] = []
    current = ""
    for paragraph in paragraphs:
        if len(paragraph) > MAX_CHUNK_CHARS:
            if current:
                parts.append(current)
                current = ""
            rest = paragraph
            while len(rest) > MAX_CHUNK_CHARS:
                window = rest[:MAX_CHUNK_CHARS]
                cut = max(window.rfind("\n"), window.rfind(" "))
                if cut < MAX_CHUNK_CHARS // 2:
                    cut = MAX_CHUNK_CHARS
                parts.append(rest[:cut].strip())
                rest = rest[cut:].strip()
            if rest:
                current = rest
            continue
        candidate = paragraph if not current else current + "\n\n" + paragraph
        if len(candidate) <= MAX_CHUNK_CHARS:
            current = candidate
        else:
            parts.append(current)
            current = paragraph
    if current:
        parts.append(current)
    return [(heading, part) for part in parts if part]


def _markdown_chunks(text: str, source_name: str) -> tuple[str, tuple[Chunk, ...]]:
    lines = text.splitlines()
    first = next((index for index, line in enumerate(lines) if line.strip()), None)
    if first is None:
        raise ValueError(f"{source_name} ist leer")
    match = HEADING_RE.fullmatch(lines[first].strip())
    if match is None or len(match.group(1)) != 1:
        raise ValueError(f"{source_name} braucht als erste Inhaltszeile '# Titel'")
    title = _atomic_string(match.group(2), f"{source_name}.title", max_chars=200)
    sections: list[tuple[str, str]] = []
    heading = title
    body: list[str] = []
    for line in lines[first + 1 :]:
        heading_match = HEADING_RE.fullmatch(line.strip())
        if heading_match:
            if body:
                sections.extend(_split_long_text("\n".join(body), heading))
            heading = _atomic_string(
                heading_match.group(2),
                f"{source_name}.heading",
                max_chars=300,
            )
            body = []
        else:
            body.append(line.rstrip())
    if body:
        sections.extend(_split_long_text("\n".join(body), heading))
    if not sections:
        raise ValueError(f"{source_name} besitzt keinen Inhalt nach dem Titel")
    return title, tuple(
        Chunk(ordinal=index, heading=section_heading, text=section_text)
        for index, (section_heading, section_text) in enumerate(sections)
    )


def _text_chunks(text: str, source_name: str, stem: str) -> tuple[str, tuple[Chunk, ...]]:
    title = _atomic_string(
        unicodedata.normalize("NFC", stem.replace("_", " ").replace("-", " ")),
        f"{source_name}.title",
        max_chars=200,
    )
    parts = _split_long_text(text, title)
    if not parts:
        raise ValueError(f"{source_name} besitzt keinen Inhalt")
    return title, tuple(
        Chunk(ordinal=index, heading=heading, text=part)
        for index, (heading, part) in enumerate(parts)
    )


def _exact_keys(value: dict[str, Any], allowed: set[str], field: str) -> None:
    unexpected = sorted(set(value) - allowed)
    if unexpected:
        raise ValueError(f"{field} enthält unbekannte Felder: {', '.join(unexpected)}")


def _string_list(
    value: Any,
    field: str,
    max_items: int = 100,
    max_chars: int = 100,
) -> tuple[str, ...]:
    if not isinstance(value, list):
        raise ValueError(f"{field} muss eine Liste sein")
    if len(value) > max_items:
        raise ValueError(f"{field} überschreitet {max_items} Einträge")
    items = tuple(
        _atomic_string(item, f"{field}[]", max_chars=max_chars) for item in value
    )
    if len({item.casefold() for item in items}) != len(items):
        raise ValueError(f"{field} enthält doppelte Einträge")
    return items


def _recipe_section_chunks(
    title: str,
    heading: str,
    lines: list[str],
    start_ordinal: int,
) -> list[Chunk]:
    if not lines:
        return []
    prefix = f"Rezept: {title}\n{heading}:\n"
    result: list[Chunk] = []
    current: list[str] = []
    for line in lines:
        if len(prefix) + len(line) > MAX_CHUNK_CHARS:
            raise ValueError(f"ein einzelner Rezeptfakt in {heading} ist zu lang")
        candidate = prefix + "\n".join([*current, line])
        if current and len(candidate) > MAX_CHUNK_CHARS:
            result.append(
                Chunk(
                    ordinal=start_ordinal + len(result),
                    heading=heading,
                    text=prefix + "\n".join(current),
                )
            )
            current = [line]
        else:
            current.append(line)
    if current:
        result.append(
            Chunk(
                ordinal=start_ordinal + len(result),
                heading=heading,
                text=prefix + "\n".join(current),
            )
        )
    return result


def _recipe(
    raw: bytes,
    source_name: str,
    default_label: str,
    default_license: str,
) -> tuple[str, str, tuple[str, ...], str, str, dict[str, Any], tuple[Chunk, ...]]:
    value = _json_object(raw, source_name)
    _exact_keys(value, RECIPE_KEYS, source_name)
    missing = sorted(RECIPE_REQUIRED_KEYS - set(value))
    if missing:
        raise ValueError(f"{source_name} fehlen Pflichtfelder: {', '.join(missing)}")
    if not _version_is(value["schemaVersion"], 1) or value["type"] != "recipe":
        raise ValueError(f"{source_name} braucht schemaVersion=1 und type='recipe'")
    title = _atomic_string(value["title"], f"{source_name}.title", max_chars=200)
    language = _atomic_string(value["language"], f"{source_name}.language", max_chars=10)
    if not LANGUAGE_RE.fullmatch(language):
        raise ValueError(f"{source_name}.language ist kein unterstützter Sprachcode")
    tags = _string_list(value.get("tags", []), f"{source_name}.tags")

    source = value.get("source", {})
    if not isinstance(source, dict):
        raise ValueError(f"{source_name}.source muss ein Objekt sein")
    _exact_keys(source, SOURCE_KEYS, f"{source_name}.source")
    source_label = _atomic_string(
        source.get("label", default_label),
        f"{source_name}.source.label",
        max_chars=200,
    )
    source_license = _atomic_string(
        source.get("license", default_license),
        f"{source_name}.source.license",
        max_chars=100,
    )

    ingredients_value = value["ingredients"]
    if not isinstance(ingredients_value, list) or not ingredients_value:
        raise ValueError(f"{source_name}.ingredients muss eine nicht-leere Liste sein")
    if len(ingredients_value) > MAX_RECIPE_ITEMS:
        raise ValueError(f"{source_name}.ingredients überschreitet {MAX_RECIPE_ITEMS} Einträge")
    ingredients: list[dict[str, str]] = []
    for index, item in enumerate(ingredients_value, 1):
        if not isinstance(item, dict):
            raise ValueError(f"{source_name}.ingredients[{index}] muss ein Objekt sein")
        _exact_keys(item, INGREDIENT_KEYS, f"{source_name}.ingredients[{index}]")
        if "itemText" not in item:
            raise ValueError(f"{source_name}.ingredients[{index}].itemText fehlt")
        normalized_ingredient: dict[str, str] = {
            "itemText": _atomic_string(
                item.get("itemText"),
                f"{source_name}.ingredients[{index}].itemText",
                max_chars=300,
            )
        }
        for optional in ("amountText", "unitText", "noteText"):
            if optional in item:
                normalized_ingredient[optional] = _atomic_string(
                    item[optional],
                    f"{source_name}.ingredients[{index}].{optional}",
                    max_chars=300 if optional == "noteText" else 50,
                )
        ingredients.append(normalized_ingredient)

    steps_value = value["steps"]
    if not isinstance(steps_value, list) or not steps_value:
        raise ValueError(f"{source_name}.steps muss eine nicht-leere Liste sein")
    if len(steps_value) > MAX_RECIPE_ITEMS:
        raise ValueError(f"{source_name}.steps überschreitet {MAX_RECIPE_ITEMS} Einträge")
    steps = [
        _atomic_string(
            item,
            f"{source_name}.steps[{index}]",
            max_chars=2_000,
        )
        for index, item in enumerate(steps_value, 1)
    ]
    notes = _string_list(
        value.get("notes", []),
        f"{source_name}.notes",
        max_chars=2_000,
    )

    normalized: dict[str, Any] = {
        "schemaVersion": 1,
        "type": "recipe",
        "title": title,
        "language": language,
        "ingredients": ingredients,
        "steps": steps,
        "tags": list(tags),
        "notes": list(notes),
        "source": {"label": source_label, "license": source_license},
    }
    if "yieldText" in value:
        normalized["yieldText"] = _atomic_string(
            value["yieldText"],
            f"{source_name}.yieldText",
            max_chars=100,
        )
    if "times" in value:
        times = value["times"]
        if not isinstance(times, dict) or not times:
            raise ValueError(f"{source_name}.times muss ein nicht-leeres Objekt sein")
        _exact_keys(times, TIMES_KEYS, f"{source_name}.times")
        normalized["times"] = {
            key: _atomic_string(
                times[key],
                f"{source_name}.times.{key}",
                max_chars=100,
            )
            for key in ("prepText", "cookText")
            if key in times
        }

    chunks: list[Chunk] = []
    overview: list[str] = []
    if "yieldText" in normalized:
        overview.append(f"Ergibt: {normalized['yieldText']}")
    times = normalized.get("times", {})
    if "prepText" in times:
        overview.append(f"Vorbereitung: {times['prepText']}")
    if "cookText" in times:
        overview.append(f"Garzeit: {times['cookText']}")
    chunks.extend(_recipe_section_chunks(title, "Übersicht", overview, len(chunks)))

    ingredient_lines: list[str] = []
    for index, ingredient in enumerate(ingredients, 1):
        quantity = " ".join(
            ingredient[key]
            for key in ("amountText", "unitText")
            if key in ingredient
        )
        note = (
            f" — {ingredient['noteText']}"
            if "noteText" in ingredient
            else ""
        )
        ingredient_lines.append(
            f"{index}. {quantity + ' ' if quantity else ''}{ingredient['itemText']}{note}"
        )
    chunks.extend(
        _recipe_section_chunks(title, "Zutaten", ingredient_lines, len(chunks))
    )
    step_lines = [f"{index}. {step}" for index, step in enumerate(steps, 1)]
    chunks.extend(
        _recipe_section_chunks(title, "Zubereitung", step_lines, len(chunks))
    )
    note_lines = [f"{index}. {note}" for index, note in enumerate(notes, 1)]
    chunks.extend(_recipe_section_chunks(title, "Notizen", note_lines, len(chunks)))
    return (
        title,
        language,
        tags,
        source_label,
        source_license,
        normalized,
        tuple(chunks),
    )


def _risk_flags(text: str) -> tuple[str, ...]:
    flags = []
    if any(pattern.search(text) for pattern in INSTRUCTION_PATTERNS):
        flags.append("instruction-like-text")
    return tuple(flags)


def _risk_surface(
    *,
    title: str,
    tags: tuple[str, ...],
    source_label: str,
    source_license: str,
    chunks: tuple[Chunk, ...],
) -> str:
    """Bündelt jedes später indizierte oder in einer Quelle gezeigte Textfeld."""

    values = [
        title,
        *tags,
        source_label,
        source_license,
        *(chunk.heading for chunk in chunks),
        *(chunk.text for chunk in chunks),
    ]
    return "\n".join(values)


def _document(
    *,
    library_id: str,
    source_name: str,
    raw: bytes,
    default_language: str,
    default_label: str,
    default_license: str,
) -> Document:
    source_format = _source_format(source_name)
    recipe_payload: Optional[dict[str, Any]] = None
    tags: tuple[str, ...] = ()
    source_label = default_label
    source_license = default_license
    language = default_language
    if source_format == "markdown":
        text = _decode_text(raw, source_name)
        title, chunks = _markdown_chunks(text, source_name)
        kind = "note"
        parser = "markdown-heading-v1"
    elif source_format == "text":
        text = _decode_text(raw, source_name)
        title, chunks = _text_chunks(text, source_name, Path(source_name).stem)
        kind = "note"
        parser = "plain-text-v1"
    elif source_format == "recipe":
        (
            title,
            language,
            tags,
            source_label,
            source_license,
            recipe_payload,
            chunks,
        ) = _recipe(raw, source_name, default_label, default_license)
        kind = "recipe"
        parser = "recipe-json-v1"
    else:  # pragma: no cover - von _source_format ausgeschlossen
        raise ValueError("nicht unterstütztes Format")
    flags = _risk_flags(
        _risk_surface(
            title=title,
            tags=tags,
            source_label=source_label,
            source_license=source_license,
            chunks=chunks,
        )
    )
    document_id = sha256_bytes(
        f"{library_id}\0{kind}\0{title.casefold()}".encode("utf-8")
    )
    source_sha256 = sha256_bytes(raw)
    if kind == "recipe":
        assert recipe_payload is not None
        canonical_record = {
            "schemaVersion": 1,
            "documentId": document_id,
            "revision": 1,
            "kind": "recipe",
            **{
                key: value
                for key, value in recipe_payload.items()
                if key not in {"schemaVersion", "type"}
            },
            "riskFlags": list(flags),
        }
    else:
        canonical_record = {
            "schemaVersion": 1,
            "documentId": document_id,
            "revision": 1,
            "kind": kind,
            "title": title,
            "language": language,
            "tags": list(tags),
            "source": {
                "label": source_label,
                "license": source_license,
            },
            "content": {
                "format": source_format,
                "sections": [
                    {
                        "heading": chunk.heading,
                        "body": chunk.text,
                    }
                    for chunk in chunks
                ],
            },
            "riskFlags": list(flags),
        }
    semantic_record = {
        key: value
        for key, value in canonical_record.items()
        if key not in {"documentId", "revision", "riskFlags"}
    }
    content_sha256 = sha256_bytes(
        canonical_json(semantic_record).encode("utf-8")
    )
    canonical_record["sourceSha256"] = source_sha256
    canonical_record["contentSha256"] = content_sha256
    canonical_record_json = canonical_json(canonical_record)
    canonical_sha256 = sha256_bytes(canonical_record_json.encode("utf-8"))
    return Document(
        document_id=document_id,
        kind=kind,
        title=title,
        language=language,
        tags=tags,
        source_label=source_label,
        source_license=source_license,
        source_sha256=source_sha256,
        content_sha256=content_sha256,
        canonical_sha256=canonical_sha256,
        parser=parser,
        risk_flags=flags,
        canonical_json=canonical_record_json,
        chunks=chunks,
    )


def _create_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        f"""
        PRAGMA page_size=4096;
        PRAGMA journal_mode=DELETE;
        PRAGMA synchronous=FULL;
        PRAGMA foreign_keys=ON;
        PRAGMA user_version={SCHEMA_VERSION};
        PRAGMA application_id={APPLICATION_ID};

        CREATE TABLE library (
            singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
            library_id TEXT NOT NULL,
            scope_kind TEXT NOT NULL CHECK(scope_kind IN ('shared', 'person')),
            owner_id TEXT,
            language TEXT NOT NULL,
            egress_policy TEXT NOT NULL CHECK(egress_policy = 'never'),
            artifact_status TEXT NOT NULL CHECK(artifact_status = 'offline-candidate'),
            runtime_enabled INTEGER NOT NULL CHECK(runtime_enabled = 0),
            created_at TEXT NOT NULL,
            transform_id TEXT NOT NULL,
            logical_records_sha256 TEXT NOT NULL,
            CHECK(
                (scope_kind = 'shared' AND owner_id IS NULL) OR
                (scope_kind = 'person' AND owner_id IS NOT NULL)
            )
        );

        CREATE TABLE documents (
            document_id TEXT PRIMARY KEY,
            revision INTEGER NOT NULL CHECK(revision = 1),
            kind TEXT NOT NULL CHECK(kind IN ('note', 'recipe')),
            title TEXT NOT NULL,
            language TEXT NOT NULL,
            tags_json TEXT NOT NULL,
            source_label TEXT NOT NULL,
            source_license TEXT NOT NULL,
            canonical_sha256 TEXT NOT NULL,
            parser TEXT NOT NULL,
            parser_version INTEGER NOT NULL,
            risk_flags_json TEXT NOT NULL,
            canonical_json TEXT NOT NULL
        );

        CREATE TABLE chunks (
            rowid INTEGER PRIMARY KEY,
            chunk_id TEXT NOT NULL UNIQUE,
            document_id TEXT NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
            ordinal INTEGER NOT NULL,
            title TEXT NOT NULL,
            tags TEXT NOT NULL,
            heading TEXT NOT NULL,
            text TEXT NOT NULL,
            text_sha256 TEXT NOT NULL,
            UNIQUE(document_id, ordinal)
        );

        CREATE VIRTUAL TABLE chunks_fts USING fts5(
            title,
            tags,
            heading,
            text,
            content='chunks',
            content_rowid='rowid',
            tokenize='{TOKENIZER}'
        );
        CREATE TRIGGER chunks_ai AFTER INSERT ON chunks BEGIN
            INSERT INTO chunks_fts(rowid, title, tags, heading, text)
            VALUES (new.rowid, new.title, new.tags, new.heading, new.text);
        END;
        CREATE TRIGGER chunks_ad AFTER DELETE ON chunks BEGIN
            INSERT INTO chunks_fts(chunks_fts, rowid, title, tags, heading, text)
            VALUES ('delete', old.rowid, old.title, old.tags, old.heading, old.text);
        END;
        CREATE TRIGGER chunks_au AFTER UPDATE ON chunks BEGIN
            INSERT INTO chunks_fts(chunks_fts, rowid, title, tags, heading, text)
            VALUES ('delete', old.rowid, old.title, old.tags, old.heading, old.text);
            INSERT INTO chunks_fts(rowid, title, tags, heading, text)
            VALUES (new.rowid, new.title, new.tags, new.heading, new.text);
        END;
        """
    )


def _write_database(
    *,
    db_path: Path,
    library_id: str,
    scope: Scope,
    language: str,
    created_at: str,
    logical_records_sha256: str,
    documents: list[Any],
) -> int:
    """Erzeugt die einzige zulässige SQLite-Ableitung der kanonischen Records."""

    with sqlite3.connect(db_path) as conn:
        _create_schema(conn)
        conn.execute("BEGIN")
        conn.execute(
            "INSERT INTO library VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            (
                1,
                library_id,
                scope.kind,
                scope.owner_id,
                language,
                "never",
                ARTIFACT_STATUS,
                0,
                created_at,
                TRANSFORM_ID,
                logical_records_sha256,
            ),
        )
        chunk_count = 0
        for document in documents:
            conn.execute(
                "INSERT INTO documents VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    document.document_id,
                    1,
                    document.kind,
                    document.title,
                    document.language,
                    canonical_json(list(document.tags)),
                    document.source_label,
                    document.source_license,
                    document.canonical_sha256,
                    document.parser,
                    1,
                    canonical_json(list(document.risk_flags)),
                    document.canonical_json,
                ),
            )
            for chunk in document.chunks:
                chunk_id = sha256_bytes(
                    f"{document.document_id}\0{chunk.ordinal}".encode("utf-8")
                )
                conn.execute(
                    "INSERT INTO chunks("
                    "chunk_id,document_id,ordinal,title,tags,heading,text,text_sha256"
                    ") VALUES (?,?,?,?,?,?,?,?)",
                    (
                        chunk_id,
                        document.document_id,
                        chunk.ordinal,
                        document.title,
                        " ".join(document.tags),
                        chunk.heading,
                        chunk.text,
                        sha256_bytes(chunk.text.encode("utf-8")),
                    ),
                )
                chunk_count += 1
        conn.commit()
        conn.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('optimize')")
        conn.commit()
        conn.execute("VACUUM")
    return chunk_count


def _rename_directory_no_replace(
    parent_fd: int,
    source_name: str,
    target_name: str,
) -> None:
    """Publiziert atomar und verweigert selbst ein leeres konkurrierendes Ziel."""

    libc = ctypes.CDLL(None, use_errno=True)
    source = os.fsencode(source_name)
    target = os.fsencode(target_name)
    if os.sys.platform == "darwin":
        rename = libc.renameatx_np
        rename.argtypes = (
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        )
        rename.restype = ctypes.c_int
        result = rename(parent_fd, source, parent_fd, target, 0x00000004)
    elif os.sys.platform.startswith("linux") and hasattr(libc, "renameat2"):
        rename = libc.renameat2
        rename.argtypes = (
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_uint,
        )
        rename.restype = ctypes.c_int
        result = rename(parent_fd, source, parent_fd, target, 0x00000001)
    else:
        raise OSError(
            errno.ENOTSUP,
            "atomare No-Replace-Veröffentlichung wird auf dieser Plattform nicht unterstützt",
        )
    if result != 0:
        error_number = ctypes.get_errno()
        if error_number in {errno.EEXIST, errno.ENOTEMPTY}:
            raise FileExistsError(
                error_number,
                "Ausgabeziel entstand während des Builds; nichts wurde überschrieben",
                target_name,
            )
        raise OSError(error_number, os.strerror(error_number), target_name)


def _publish_directory(temp_dir: Path, output_dir: Path) -> None:
    parent = output_dir.parent
    parent_flags = (
        os.O_RDONLY
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    parent_fd = os.open(parent, parent_flags)
    try:
        parent_before = os.fstat(parent_fd)
        source_before = os.stat(
            temp_dir.name,
            dir_fd=parent_fd,
            follow_symlinks=False,
        )
        if not stat.S_ISDIR(source_before.st_mode):
            raise OSError("temporärer Kandidat ist kein Verzeichnis")
        published = False
        try:
            _rename_directory_no_replace(parent_fd, temp_dir.name, output_dir.name)
            published = True
            target_after = os.stat(
                output_dir.name,
                dir_fd=parent_fd,
                follow_symlinks=False,
            )
            if (
                target_after.st_dev != source_before.st_dev
                or target_after.st_ino != source_before.st_ino
                or not stat.S_ISDIR(target_after.st_mode)
            ):
                raise OSError("veröffentlichter Kandidat ist nicht der gebaute Inode")
            os.fsync(parent_fd)
            parent_after = os.fstat(parent_fd)
            visible_parent = parent.stat(follow_symlinks=False)
            visible_target = output_dir.stat(follow_symlinks=False)
            if (
                parent_after.st_dev != parent_before.st_dev
                or parent_after.st_ino != parent_before.st_ino
                or visible_parent.st_dev != parent_before.st_dev
                or visible_parent.st_ino != parent_before.st_ino
                or visible_target.st_dev != target_after.st_dev
                or visible_target.st_ino != target_after.st_ino
            ):
                raise OSError("Ausgabepfad änderte sich während der Veröffentlichung")
        except BaseException:
            if published:
                try:
                    current = os.stat(
                        output_dir.name,
                        dir_fd=parent_fd,
                        follow_symlinks=False,
                    )
                    if (
                        current.st_dev != source_before.st_dev
                        or current.st_ino != source_before.st_ino
                    ):
                        raise OSError(
                            "veröffentlichter Ziel-Inode ist nicht sicher rückrollbar"
                        )
                    _rename_directory_no_replace(
                        parent_fd,
                        output_dir.name,
                        temp_dir.name,
                    )
                except OSError as rollback_error:
                    raise RuntimeError(
                        "Veröffentlichung wurde committed, der Rücklauf scheiterte; "
                        f"Ziel manuell prüfen: {output_dir}"
                    ) from rollback_error
            raise
    finally:
        os.close(parent_fd)


def build_library(
    *,
    source: Path,
    output_dir: Path,
    library_id: str,
    scope_text: str,
    language: str = "de",
    source_label: Optional[str] = None,
    source_license: str = "private-use",
    created_at: Optional[str] = None,
    max_database_bytes: int = MAX_DATABASE_BYTES,
) -> dict[str, Any]:
    if not ID_RE.fullmatch(library_id):
        raise ValueError(
            "library-id darf nur Kleinbuchstaben, Ziffern, Punkt, _ und - "
            "enthalten (3–64 Zeichen)"
        )
    if not LANGUAGE_RE.fullmatch(language):
        raise ValueError("language ist kein unterstützter Sprachcode")
    label = _atomic_string(
        source_label or "Private Bibliothek",
        "source-label",
        max_chars=200,
    )
    license_value = _atomic_string(source_license, "source-license", max_chars=100)
    scope = parse_scope(scope_text)
    created = validate_utc(created_at)
    if type(max_database_bytes) is not int or max_database_bytes <= 0:
        raise ValueError("max-database-bytes muss positiv sein")
    if max_database_bytes > MAX_DATABASE_BYTES:
        raise ValueError(
            f"max-database-bytes darf den K0-Hardcap von {MAX_DATABASE_BYTES} nicht überschreiten"
        )
    if output_dir.exists() or output_dir.is_symlink():
        raise ValueError("Ausgabeziel existiert bereits")
    source_resolved = source.resolve()
    output_resolved = output_dir.resolve(strict=False)
    if (
        source_resolved == output_resolved
        or source_resolved in output_resolved.parents
        or output_resolved in source_resolved.parents
    ):
        raise ValueError("Quell- und Ausgabeordner dürfen sich nicht überlappen")

    source_rows = _read_sources(source)
    documents = [
        _document(
            library_id=library_id,
            source_name=name,
            raw=raw,
            default_language=language,
            default_label=label,
            default_license=license_value,
        )
        for name, raw in source_rows
    ]
    documents.sort(key=lambda document: document.document_id)
    normalized_titles: set[str] = set()
    canonical_hashes: set[str] = set()
    for document in documents:
        key = document.title.casefold()
        if key in normalized_titles:
            raise ValueError(f"doppelter Dokumenttitel: {document.title!r}")
        normalized_titles.add(key)
        if document.canonical_sha256 in canonical_hashes:
            raise ValueError("doppelter kanonischer Dokumentinhalt")
        canonical_hashes.add(document.canonical_sha256)
    documents_bytes = "".join(
        document.canonical_json + "\n" for document in documents
    ).encode("utf-8")
    if len(documents_bytes) > MAX_DOCUMENTS_BYTES:
        raise ValueError(
            "kanonische Dokumente überschreiten das K0-Budget von "
            f"{MAX_DOCUMENTS_BYTES} Bytes"
        )
    logical_records_sha256 = sha256_bytes(documents_bytes)
    input_set_sha256 = sha256_bytes(
        canonical_json(sorted(document.source_sha256 for document in documents)).encode(
            "utf-8"
        )
    )
    semantic_content_sha256 = sha256_bytes(
        canonical_json(sorted(document.content_sha256 for document in documents)).encode(
            "utf-8"
        )
    )
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f".{output_dir.name}.building-",
        dir=output_dir.parent,
    ) as temp_name:
        temp_dir = Path(temp_name)
        os.chmod(temp_dir, stat.S_IRWXU)
        documents_path = temp_dir / DOCUMENTS_FILE
        documents_path.write_bytes(documents_bytes)
        db_path = temp_dir / DATABASE_FILE
        chunk_count = _write_database(
            db_path=db_path,
            library_id=library_id,
            scope=scope,
            language=language,
            created_at=created,
            logical_records_sha256=logical_records_sha256,
            documents=documents,
        )

        size = db_path.stat().st_size
        if size > max_database_bytes:
            raise ValueError(
                f"Bibliothek überschreitet das DB-Budget: {size} > {max_database_bytes} Bytes"
            )
        database_sha256 = sha256_file(db_path)
        risk_document_count = sum(bool(document.risk_flags) for document in documents)
        recipe_count = sum(document.kind == "recipe" for document in documents)
        builder_path = Path(__file__).resolve()
        manifest: dict[str, Any] = {
            "schemaVersion": SCHEMA_VERSION,
            "artifactType": ARTIFACT_TYPE,
            "artifactStatus": ARTIFACT_STATUS,
            "libraryId": library_id,
            "language": language,
            "createdAt": created,
            "scope": {
                "kind": scope.kind,
                "ownerId": scope.owner_id,
                "recognitionRequired": scope.kind == "person",
            },
            "egressPolicy": "never",
            "activation": {
                "runtimeEnabled": False,
                "voiceEnabled": False,
            },
            "source": {
                "mode": "local-files",
                "inputSetSha256": input_set_sha256,
                "logicalRecordsSha256": logical_records_sha256,
                "semanticContentSha256": semantic_content_sha256,
                "documentsFile": DOCUMENTS_FILE,
                "documentsSha256": sha256_file(documents_path),
                "documentsSizeBytes": documents_path.stat().st_size,
                "fileCount": len(documents),
                "defaultLicense": license_value,
                "sourcePathsStored": False,
            },
            "builder": {
                "transform": TRANSFORM_ID,
                "sourceFile": "tools/knowledge-library/build_library.py",
                "sourceSha256": sha256_file(builder_path),
                "pythonVersion": ".".join(map(str, os.sys.version_info[:3])),
                "sqliteVersion": sqlite3.sqlite_version,
                "modelDerivedFeatures": [],
            },
            "database": {
                "file": DATABASE_FILE,
                "sha256": database_sha256,
                "sizeBytes": size,
                "documentCount": len(documents),
                "chunkCount": chunk_count,
                "recipeCount": recipe_count,
                "riskFlaggedDocumentCount": risk_document_count,
            },
            "retrieval": {
                "method": "fts5-title-tags-heading-text",
                "tokenizer": TOKENIZER,
                "denseIndex": None,
            },
            "limits": {
                "maxFiles": MAX_FILES,
                "maxFileBytes": MAX_FILE_BYTES,
                "maxSourceBytes": MAX_SOURCE_BYTES,
                "maxDocumentsBytes": MAX_DOCUMENTS_BYTES,
                "maxDatabaseBytes": max_database_bytes,
            },
        }
        manifest["generationId"] = _generation_id(manifest)
        manifest_path = temp_dir / MANIFEST_FILE
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.chmod(db_path, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(documents_path, stat.S_IRUSR | stat.S_IWUSR)
        os.chmod(manifest_path, stat.S_IRUSR | stat.S_IWUSR)
        for path in (db_path, documents_path, manifest_path):
            with path.open("rb") as handle:
                os.fsync(handle.fileno())
        temp_fd = os.open(
            temp_dir,
            os.O_RDONLY | getattr(os, "O_DIRECTORY", 0),
        )
        try:
            os.fsync(temp_fd)
        finally:
            os.close(temp_fd)
        _publish_directory(temp_dir, output_dir)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--library-id", required=True)
    parser.add_argument("--scope", required=True, help="shared oder person:<opaque-id>")
    parser.add_argument("--language", default="de")
    parser.add_argument("--source-label")
    parser.add_argument("--source-license", default="private-use")
    parser.add_argument("--created-at")
    parser.add_argument("--max-database-bytes", type=int, default=MAX_DATABASE_BYTES)
    args = parser.parse_args()
    try:
        manifest = build_library(
            source=args.source.expanduser(),
            output_dir=args.output_dir.expanduser(),
            library_id=args.library_id,
            scope_text=args.scope,
            language=args.language,
            source_label=args.source_label,
            source_license=args.source_license,
            created_at=args.created_at,
            max_database_bytes=args.max_database_bytes,
        )
    except (OSError, sqlite3.Error, ValueError) as exc:
        print(f"[knowledge-library-build] FATAL: {exc}", file=os.sys.stderr)
        return 1
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
