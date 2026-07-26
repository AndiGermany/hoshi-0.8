#!/usr/bin/env python3
"""Baut ein releasefähiges Knowledge-Pack v1 direkt aus einem dewiki-Dump.

Der Pfad ist absichtlich streng:

* nur der kanonische ``pages-articles-multistream``-Dump wird akzeptiert;
* Größe und offizieller SHA-1 werden vorgegeben und vor XML-Verarbeitung geprüft;
* SHA-256 wird beim Download lokal berechnet und im Manifest festgehalten;
* nur Titel aus einer expliziten, öffentlichen JSONL-Auswahl gelangen ins Pack;
* Redirects und Nicht-Artikel-Namensräume werden nicht still aufgelöst;
* Page-/Revisions-ID und Revisionszeit kommen ausschließlich aus dem Dump.

Download, XML-Streaming und die konservative Wikitext-Transformation verwenden
nur die Python-Standardbibliothek. Die bereits für Knowledge-Pack v1 verwendete
Zstandard-Bibliothek komprimiert ausschließlich den fertigen SQLite-Inhalt.
"""

from __future__ import annotations

import argparse
import bz2
import fcntl
import hashlib
import html
import json
import os
import platform
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import BinaryIO, Callable, Iterable, Optional


HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

import build_pack as legacy  # noqa: E402


DATABASE_FILE = legacy.DATABASE_FILE
MANIFEST_FILE = legacy.MANIFEST_FILE
NOTICE_FILE = legacy.NOTICE_FILE
DUMPSTATUS_FILE = "dumpstatus.json"
SELECTION_FILE = "selection.jsonl"
TRANSFORM_ID = "hoshi-pack-v1-direct-dump-conservative-lead-v1"
RELEASE_STATUS = "release-candidate"
DOWNLOAD_MARGIN_BYTES = 512 * 1024 * 1024
BUILD_MARGIN_BYTES = 512 * 1024 * 1024
DEFAULT_MAX_PACK_BYTES = 512 * 1024 * 1024
ZSTD_LEVEL = 10
ALLOWED_SELECTION_KEYS = {"title", "aliases"}
USER_AGENT = "Hoshi-Knowledge-Pack/1.0 (local release builder)"
MAX_DUMPSTATUS_BYTES = 16 * 1024 * 1024
_SHA1_RE = re.compile(r"^[0-9a-f]{40}$")
_PACK_ID_RE = re.compile(r"^[a-z0-9][a-z0-9._-]{2,127}$")
_UTC_TIMESTAMP_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
_DUMP_UPDATED_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$")
_REDIRECT_RE = re.compile(r"^\s*#(?:redirect|weiterleitung)\b", re.IGNORECASE)
_SECTION_RE = re.compile(r"(?m)^==+\s*[^=\n].*?==+\s*$")
_COMMENT_RE = re.compile(r"<!--.*?-->", re.DOTALL)
_BLOCK_TAG_RE = re.compile(
    r"<(?P<tag>ref|gallery|timeline|math|score|syntaxhighlight|source)\b[^>]*>"
    r".*?</(?P=tag)\s*>",
    re.IGNORECASE | re.DOTALL,
)
_SELF_CLOSING_BLOCK_RE = re.compile(
    r"<(?:ref|gallery|timeline|math|score|syntaxhighlight|source)\b[^>]*/\s*>",
    re.IGNORECASE,
)
_INTERNAL_LINK_RE = re.compile(r"\[\[([^\[\]]+)\]\]")
_EXTERNAL_LINK_RE = re.compile(r"\[(?:https?|ftp)://[^\s\]]+(?:\s+([^\]]+))?\]")
_BARE_URL_RE = re.compile(r"https?://\S+")
_HTML_TAG_RE = re.compile(r"<[^>]+>")
_MEDIA_NAMESPACES = {
    "bild",
    "datei",
    "file",
    "image",
    "category",
    "kategorie",
    "media",
}


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def _network_opener() -> Callable[..., object]:
    """Ruft die feste Wikimedia-Quelle ohne Systemproxy oder Redirect auf."""

    return urllib.request.build_opener(
        urllib.request.ProxyHandler({}),
        _NoRedirectHandler(),
    ).open


@dataclass(frozen=True)
class DumpSpec:
    """Kryptografisch gebundener Vertrag für genau einen offiziellen Dump."""

    dump_date: str
    expected_size: int
    expected_sha1: str
    url: str
    dumpstatus_url: str
    filename: str

    @classmethod
    def create(cls, dump_date: str, expected_size: int, expected_sha1: str) -> "DumpSpec":
        if not re.fullmatch(r"\d{8}", dump_date):
            raise ValueError("dump-date muss YYYYMMDD sein")
        try:
            parsed = date(
                int(dump_date[0:4]),
                int(dump_date[4:6]),
                int(dump_date[6:8]),
            )
        except ValueError as exc:
            raise ValueError("dump-date ist kein gültiges Kalenderdatum") from exc
        if isinstance(expected_size, bool) or expected_size <= 0:
            raise ValueError("expected-size muss eine positive Bytezahl sein")
        sha1 = expected_sha1.strip().lower()
        if not _SHA1_RE.fullmatch(sha1):
            raise ValueError("expected-sha1 muss ein SHA-1-Hexwert sein")
        filename = (
            f"dewiki-{dump_date}-pages-articles-multistream.xml.bz2"
        )
        base = f"https://dumps.wikimedia.org/dewiki/{dump_date}"
        return cls(
            dump_date=parsed.strftime("%Y%m%d"),
            expected_size=expected_size,
            expected_sha1=sha1,
            url=f"{base}/{filename}",
            dumpstatus_url=f"{base}/dumpstatus.json",
            filename=filename,
        )

    @property
    def iso_date(self) -> str:
        return (
            f"{self.dump_date[0:4]}-{self.dump_date[4:6]}-{self.dump_date[6:8]}"
        )


@dataclass(frozen=True)
class DumpArtifact:
    path: Path
    size: int
    sha1: str
    sha256: str


@dataclass(frozen=True)
class DumpStatusEvidence:
    raw: bytes
    sha256: str
    job: str
    updated: str


@dataclass(frozen=True)
class PublicSelection:
    title: str
    aliases: tuple[str, ...]


@dataclass(frozen=True)
class DumpArticle:
    page_id: int
    title: str
    revision_id: str
    revision_timestamp: str
    lead: str


def toolchain_contract() -> dict[str, str]:
    """Versionen, von denen die bytegenaue SQLite-Ausgabe abhängt."""

    return {
        "pythonImplementation": platform.python_implementation(),
        "pythonVersion": platform.python_version(),
        "sqliteVersion": sqlite3.sqlite_version,
        "zstandardVersion": legacy.zstd.__version__,
    }


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _git_commit() -> str:
    repo_root = HERE.parents[1]
    guarded_paths = (
        "tools/knowledge-pack/build_pack_from_dump.py",
        "tools/knowledge-pack/build_pack.py",
        "tools/knowledge-pack/verify_pack.py",
    )
    dirty = subprocess.run(
        [
            "git",
            "-C",
            str(repo_root),
            "status",
            "--porcelain",
            "--untracked-files=all",
            "--",
            *guarded_paths,
        ],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()
    if dirty:
        raise ValueError(
            "Release-Builder/Verifier sind nicht vollständig committed; "
            "builder.commit wäre sonst irreführend"
        )
    result = subprocess.run(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def _verified_builder_commit() -> str:
    """Bindet den laufenden Builder bytegenau an einen existierenden HEAD."""

    commit = _git_commit()
    relative = Path(__file__).resolve().relative_to(HERE.parents[1]).as_posix()
    try:
        committed = subprocess.run(
            ["git", "-C", str(HERE.parents[1]), "show", f"{commit}:{relative}"],
            check=True,
            capture_output=True,
        ).stdout
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValueError("Builder-Quelle ist am Git-Commit nicht belegt") from exc
    if hashlib.sha256(committed).hexdigest() != _sha256(Path(__file__).resolve()):
        raise ValueError(
            "Builder-Quelle weicht von HEAD ab; Release-Pack erst aus sauberem Commit bauen"
        )
    return commit


def _normalize_dump_title(title: str) -> str:
    return re.sub(r"\s+", " ", title.replace("_", " ").strip()).casefold()


def read_public_selection(path: Path) -> list[PublicSelection]:
    """Liest ausschließlich öffentliche Titel/Aliase; alle Zusatzfelder sind fatal."""

    rows: list[PublicSelection] = []
    seen: set[str] = set()
    try:
        handle = path.open(encoding="utf-8")
    except OSError as exc:
        raise ValueError(f"Auswahl fehlt oder ist nicht lesbar: {path}: {exc}") from exc
    with handle:
        for line_no, line in enumerate(handle, 1):
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            try:
                item = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no}: ungültiges JSON: {exc}") from exc
            if not isinstance(item, dict):
                raise ValueError(f"{path}:{line_no}: jede Zeile muss ein Objekt sein")
            unexpected = sorted(set(item) - ALLOWED_SELECTION_KEYS)
            if unexpected:
                raise ValueError(
                    f"{path}:{line_no}: nicht-öffentliche/unbekannte Felder: "
                    + ", ".join(unexpected)
                )
            title = item.get("title")
            if not isinstance(title, str) or not title.strip():
                raise ValueError(f"{path}:{line_no}: title fehlt")
            aliases = item.get("aliases", [])
            if not isinstance(aliases, list) or any(
                not isinstance(alias, str) or not alias.strip() for alias in aliases
            ):
                raise ValueError(f"{path}:{line_no}: aliases muss eine String-Liste sein")
            if aliases:
                raise ValueError(
                    f"{path}:{line_no}: aliases müssen für Release-Pack v1 leer sein; "
                    "nicht aus dem Quelldump belegte Texte dürfen nicht veröffentlicht werden"
                )
            normalized = _normalize_dump_title(title)
            if normalized in seen:
                raise ValueError(f"{path}:{line_no}: doppelter Titel {title!r}")
            seen.add(normalized)
            rows.append(
                PublicSelection(
                    title=re.sub(r"\s+", " ", title.strip()),
                    aliases=tuple(dict.fromkeys(alias.strip() for alias in aliases)),
                )
            )
    if not rows:
        raise ValueError("Auswahl ist leer")
    return rows


def canonical_public_selection(selections: Iterable[PublicSelection]) -> bytes:
    """Serialisiert ausschließlich die geprüften öffentlichen Auswahlfelder."""

    lines = [
        json.dumps(
            {
                "title": selection.title,
                "aliases": list(selection.aliases),
            },
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        for selection in selections
    ]
    return ("\n".join(lines) + "\n").encode("utf-8")


def _hash_dump(path: Path) -> DumpArtifact:
    sha1 = hashlib.sha1(usedforsecurity=False)
    sha256 = hashlib.sha256()
    size = 0
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            size += len(chunk)
            sha1.update(chunk)
            sha256.update(chunk)
    return DumpArtifact(
        path=path,
        size=size,
        sha1=sha1.hexdigest(),
        sha256=sha256.hexdigest(),
    )


def _validate_dump_artifact(artifact: DumpArtifact, spec: DumpSpec) -> None:
    if artifact.size != spec.expected_size:
        raise ValueError(
            "Dump-Größe stimmt nicht: "
            f"erwartet={spec.expected_size}, tatsächlich={artifact.size}"
        )
    if artifact.sha1 != spec.expected_sha1:
        raise ValueError(
            "Dump-SHA-1 stimmt nicht: "
            f"erwartet={spec.expected_sha1}, tatsächlich={artifact.sha1}"
        )


def canonical_dumpstatus_evidence(
    spec: DumpSpec,
    *,
    job: str,
    updated: str,
) -> bytes:
    """Projiziert die große Wikimedia-Antwort auf exakt öffentliche Belegfelder."""

    payload = {
        "schema": "hoshi-wikimedia-dump-evidence-v1",
        "job": job,
        "status": "done",
        "updated": updated,
        "file": {
            "url": f"/dewiki/{spec.dump_date}/{spec.filename}",
            "size": spec.expected_size,
            "sha1": spec.expected_sha1,
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


def _validate_dump_updated(value: object) -> str:
    updated = str(value or "").strip()
    if not _DUMP_UPDATED_RE.fullmatch(updated):
        raise ValueError(
            "dumpstatus.updated muss YYYY-MM-DD HH:MM:SS sein"
        )
    try:
        datetime.strptime(updated, "%Y-%m-%d %H:%M:%S")
    except ValueError as exc:
        raise ValueError("dumpstatus.updated ist kein gültiger Zeitpunkt") from exc
    return updated


def fetch_dumpstatus_evidence(
    spec: DumpSpec,
    *,
    timeout_seconds: int = 30,
    opener: Optional[Callable[..., object]] = None,
) -> DumpStatusEvidence:
    """Bindet Caller-Metadaten vor dem Download an Wikimedias HTTPS-Status."""

    safe_open = opener or _network_opener()
    request = urllib.request.Request(
        spec.dumpstatus_url,
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    try:
        with safe_open(request, timeout=timeout_seconds) as response:
            final_url = getattr(response, "geturl", lambda: spec.dumpstatus_url)()
            if final_url != spec.dumpstatus_url:
                raise ValueError(
                    f"dumpstatus wurde unerwartet umgeleitet: {final_url}"
                )
            raw = response.read(MAX_DUMPSTATUS_BYTES + 1)
            if len(raw) > MAX_DUMPSTATUS_BYTES:
                raise ValueError("dumpstatus überschreitet das Größenlimit")
    except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
        raise ValueError(f"dumpstatus nicht abrufbar: {exc}") from exc
    try:
        root = json.loads(raw.decode("utf-8"))
        job_name = "articlesmultistreamdumprecombine"
        job = root["jobs"][job_name]
        file_info = job["files"][spec.filename]
    except (
        UnicodeDecodeError,
        json.JSONDecodeError,
        KeyError,
        TypeError,
    ) as exc:
        raise ValueError("dumpstatus enthält keinen erwarteten Artikel-Dump") from exc
    if job.get("status") != "done":
        raise ValueError("dumpstatus meldet den Artikel-Dump nicht als done")
    if file_info.get("url") != f"/dewiki/{spec.dump_date}/{spec.filename}":
        raise ValueError("dumpstatus-Dateipfad ist nicht kanonisch")
    if file_info.get("size") != spec.expected_size:
        raise ValueError("expected-size stimmt nicht mit dumpstatus überein")
    if str(file_info.get("sha1", "")).lower() != spec.expected_sha1:
        raise ValueError("expected-sha1 stimmt nicht mit dumpstatus überein")
    updated = _validate_dump_updated(job.get("updated"))
    canonical = canonical_dumpstatus_evidence(
        spec,
        job=job_name,
        updated=updated,
    )
    return DumpStatusEvidence(
        raw=canonical,
        sha256=hashlib.sha256(canonical).hexdigest(),
        job=job_name,
        updated=updated,
    )


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def ensure_dump(
    spec: DumpSpec,
    cache_dir: Path,
    *,
    timeout_seconds: int = 60,
    opener: Optional[Callable[..., object]] = None,
) -> DumpArtifact:
    """Lädt fehlende Dumps atomar; vorhandene Dateien werden nur geprüft, nie ersetzt."""

    safe_open = opener or _network_opener()
    cache_dir.mkdir(parents=True, exist_ok=True)
    destination = cache_dir / spec.filename
    if destination.exists():
        if not destination.is_file():
            raise ValueError(f"Dump-Ziel ist keine Datei: {destination}")
        artifact = _hash_dump(destination)
        _validate_dump_artifact(artifact, spec)
        return artifact

    temp_path: Optional[Path] = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb",
            prefix=f".{spec.filename}.",
            suffix=".part",
            dir=cache_dir,
            delete=False,
        ) as temp:
            temp_path = Path(temp.name)
            request = urllib.request.Request(
                spec.url,
                headers={"User-Agent": USER_AGENT, "Accept": "application/octet-stream"},
            )
            with safe_open(request, timeout=timeout_seconds) as response:
                final_url = getattr(response, "geturl", lambda: spec.url)()
                if final_url != spec.url:
                    raise ValueError(
                        f"Dump-Download wurde unerwartet umgeleitet: {final_url}"
                    )
                content_length = response.headers.get("Content-Length")
                if content_length is not None:
                    try:
                        announced = int(content_length)
                    except ValueError as exc:
                        raise ValueError("HTTP Content-Length ist ungültig") from exc
                    if announced != spec.expected_size:
                        raise ValueError(
                            "HTTP Content-Length stimmt nicht mit expected-size überein: "
                            f"{announced} != {spec.expected_size}"
                        )
                sha1 = hashlib.sha1(usedforsecurity=False)
                sha256 = hashlib.sha256()
                size = 0
                while True:
                    chunk = response.read(8 * 1024 * 1024)
                    if not chunk:
                        break
                    temp.write(chunk)
                    size += len(chunk)
                    sha1.update(chunk)
                    sha256.update(chunk)
                temp.flush()
                os.fsync(temp.fileno())
        artifact = DumpArtifact(
            path=temp_path,
            size=size,
            sha1=sha1.hexdigest(),
            sha256=sha256.hexdigest(),
        )
        _validate_dump_artifact(artifact, spec)
        try:
            os.link(temp_path, destination)
        except FileExistsError as exc:
            raise ValueError(
                f"Dump-Ziel entstand parallel; es wird nicht überschrieben: {destination}"
            ) from exc
        _fsync_directory(cache_dir)
        temp_path.unlink()
        temp_path = None
        return DumpArtifact(
            path=destination,
            size=artifact.size,
            sha1=artifact.sha1,
            sha256=artifact.sha256,
        )
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)


def preflight_disk(
    *,
    spec: DumpSpec,
    cache_dir: Path,
    output_parent: Path,
    dump_present: bool,
    max_pack_bytes: int,
    disk_usage: Callable[[Path], object] = shutil.disk_usage,
) -> dict[str, int]:
    """Prüft Peak-Bedarf pro Dateisystem, bevor Download oder Packbau beginnen."""

    if max_pack_bytes <= 0:
        raise ValueError("max-pack-bytes muss für den Releasepfad positiv begrenzt sein")
    cache_dir.mkdir(parents=True, exist_ok=True)
    output_parent.mkdir(parents=True, exist_ok=True)
    cache_device = os.stat(cache_dir).st_dev
    output_device = os.stat(output_parent).st_dev
    needs: dict[int, int] = {}
    paths: dict[int, Path] = {}
    if not dump_present:
        needs[cache_device] = spec.expected_size + DOWNLOAD_MARGIN_BYTES
        paths[cache_device] = cache_dir
    needs[output_device] = (
        needs.get(output_device, 0) + max_pack_bytes + BUILD_MARGIN_BYTES
    )
    paths[output_device] = output_parent

    observed: dict[str, int] = {}
    for device, required in needs.items():
        free = int(disk_usage(paths[device]).free)
        observed[str(paths[device])] = free
        if free < required:
            raise ValueError(
                "Zu wenig freier Speicher für atomaren Dump→Pack-Bau: "
                f"Pfad={paths[device]}, frei={free}, benötigt={required}"
            )
    return observed


def _remove_balanced(text: str, opener: str, closer: str) -> str:
    """Entfernt verschachtelte Wikitext-Blöcke oder bricht bei Unwucht ehrlich ab."""

    result: list[str] = []
    index = 0
    depth = 0
    while index < len(text):
        if text.startswith(opener, index):
            depth += 1
            index += len(opener)
            continue
        if text.startswith(closer, index):
            if depth == 0:
                raise ValueError(f"unbalanciertes Wikitext-Token {closer!r}")
            depth -= 1
            index += len(closer)
            continue
        if depth == 0:
            result.append(text[index])
        index += 1
    if depth:
        raise ValueError(f"unvollständiger Wikitext-Block {opener!r}")
    return "".join(result)


def _replace_internal_link(match: re.Match[str]) -> str:
    content = match.group(1)
    target, _, _ = content.partition("|")
    namespace, separator, _ = target.partition(":")
    if separator and namespace.strip().casefold() in _MEDIA_NAMESPACES:
        return ""
    parts = content.split("|")
    label = parts[-1].strip() if len(parts) > 1 else target.split("#", 1)[0].strip()
    return label


def conservative_wikitext_lead(wikitext: str, max_chars: int) -> str:
    """Extrahiert deterministisch nur klar lesbaren Lead-Text.

    Das ist bewusst kein vollständiger MediaWiki-Renderer. Unaufgelöste
    Struktur-Tokens führen zum Abbruch, statt still als scheinbar sauberer Fakt
    in ein Release-Pack zu gelangen.
    """

    if _REDIRECT_RE.match(wikitext):
        raise ValueError("Redirect-Wikitext wird nicht als Artikeltext akzeptiert")
    section = _SECTION_RE.search(wikitext)
    text = wikitext[: section.start()] if section else wikitext
    text = _COMMENT_RE.sub("", text)
    text = _BLOCK_TAG_RE.sub("", text)
    text = _SELF_CLOSING_BLOCK_RE.sub("", text)
    text = _remove_balanced(text, "{|", "|}")
    text = _remove_balanced(text, "{{", "}}")

    previous = None
    while previous != text:
        previous = text
        text = _INTERNAL_LINK_RE.sub(_replace_internal_link, text)
    text = _EXTERNAL_LINK_RE.sub(lambda match: match.group(1) or "", text)
    text = _BARE_URL_RE.sub("", text)
    text = re.sub(r"(?i)<br\s*/?>", " ", text)
    text = _HTML_TAG_RE.sub("", text)
    text = re.sub(r"(?m)^\s*[*#;:].*$", "", text)
    text = re.sub(r"__[A-Z_]+__", "", text)
    text = text.replace("'''''", "").replace("'''", "").replace("''", "")
    text = html.unescape(text)

    forbidden = ("{{", "}}", "[[", "]]", "<ref", "{|", "|}")
    if any(token.casefold() in text.casefold() for token in forbidden):
        raise ValueError("Wikitext-Transformation ließ Struktur-Markup zurück")
    lead = legacy.compact_lead(text, max_chars)
    if not lead:
        raise ValueError("Wikitext liefert keinen konservativ nutzbaren Lead")
    return lead


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _direct_child(element: ET.Element, name: str) -> Optional[ET.Element]:
    return next((child for child in element if _local_name(child.tag) == name), None)


def _required_child_text(element: ET.Element, name: str, context: str) -> str:
    child = _direct_child(element, name)
    value = child.text.strip() if child is not None and child.text else ""
    if not value:
        raise ValueError(f"{context}: XML-Feld {name} fehlt")
    return value


def _validate_revision_timestamp(value: str, context: str) -> str:
    if not _UTC_TIMESTAMP_RE.fullmatch(value):
        raise ValueError(f"{context}: Revisionszeit ist nicht UTC/RFC3339: {value!r}")
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise ValueError(f"{context}: ungültige Revisionszeit {value!r}") from exc
    return value


def extract_selected_articles(
    dump_path: Path | BinaryIO,
    selections: Iterable[PublicSelection],
    *,
    lead_chars: int,
) -> dict[str, DumpArticle]:
    """Streamt den bz2/XML-Dump und hält nur explizit ausgewählte Leads im RAM."""

    requested = {
        _normalize_dump_title(selection.title): selection for selection in selections
    }
    found: dict[str, DumpArticle] = {}
    try:
        with bz2.open(dump_path, "rb") as source:
            context = ET.iterparse(source, events=("start", "end"))
            first_event, root = next(context)
            if first_event != "start":
                raise ValueError("XML-Stream besitzt kein Wurzelelement")
            for event, element in context:
                if event != "end":
                    continue
                if _local_name(element.tag) != "page":
                    continue
                title = _required_child_text(element, "title", "page")
                normalized = _normalize_dump_title(title)
                selection = requested.get(normalized)
                if selection is not None:
                    namespace = _required_child_text(element, "ns", title)
                    if namespace != "0":
                        raise ValueError(
                            f"Ausgewählter Titel liegt nicht im Main namespace: {title!r} (ns={namespace})"
                        )
                    redirect = _direct_child(element, "redirect")
                    if redirect is not None:
                        target = redirect.attrib.get("title", "<unbekannt>")
                        raise ValueError(
                            f"Redirect wird nicht still aufgelöst: {title!r} → {target!r}"
                        )
                    page_id = _required_child_text(element, "id", title)
                    if not page_id.isdigit():
                        raise ValueError(f"{title!r}: Page-ID ist nicht numerisch")
                    revision = _direct_child(element, "revision")
                    if revision is None:
                        raise ValueError(f"{title!r}: Revision fehlt")
                    revision_id = _required_child_text(revision, "id", title)
                    if not revision_id.isdigit():
                        raise ValueError(f"{title!r}: Revisions-ID ist nicht numerisch")
                    timestamp = _validate_revision_timestamp(
                        _required_child_text(revision, "timestamp", title),
                        title,
                    )
                    text_element = _direct_child(revision, "text")
                    wikitext = text_element.text if text_element is not None else None
                    if not wikitext:
                        raise ValueError(f"{title!r}: Revisionsinhalt fehlt")
                    found[normalized] = DumpArticle(
                        page_id=int(page_id),
                        title=title,
                        revision_id=revision_id,
                        revision_timestamp=timestamp,
                        lead=conservative_wikitext_lead(wikitext, lead_chars),
                    )
                # ``Element.clear`` allein lässt für Millionen Seiten leere
                # Kinder am Root hängen. Root-Clear hält den Stream wirklich
                # O(Seitengröße + ausgewählte Leads), nicht O(Seitenzahl).
                root.clear()
                if len(found) == len(requested):
                    break
    except (OSError, EOFError, ET.ParseError) as exc:
        raise ValueError(f"Dump ist nicht als vollständiger bz2/XML-Stream lesbar: {exc}") from exc

    missing = [
        selection.title
        for key, selection in requested.items()
        if key not in found
    ]
    if missing:
        raise ValueError("Ausgewählte Artikel fehlen im Dump: " + ", ".join(sorted(missing)))
    return found


def deterministic_searchable_text(
    article: DumpArticle,
    selection: PublicSelection,
) -> str:
    """Erzeugt exakt den öffentlichen FTS-Quelltext für einen Artikel."""

    return re.sub(
        r"\s+",
        " ",
        " ".join(
            (
                "WIKIPEDIA",
                article.title,
                " ".join(selection.aliases),
                article.lead,
            )
        ),
    ).strip()


def logical_records(
    *,
    pack_id: str,
    created_at: str,
    selections: Iterable[PublicSelection],
    articles: dict[str, DumpArticle],
) -> list[dict]:
    """Kanonische, SQLite-/zstd-unabhängige Wahrheit des erzeugten Packs."""

    records: list[dict] = []
    transform_hash = hashlib.sha256(TRANSFORM_ID.encode("utf-8")).hexdigest()
    for selection in selections:
        article = articles[_normalize_dump_title(selection.title)]
        records.append(
            {
                "article": {
                    "id": article.page_id,
                    "title": article.title,
                    "titleNorm": legacy.normalize_title(article.title),
                    "redirectTo": None,
                    "isDisambig": 0,
                    "isStopword": 0,
                    "plaintext": article.lead,
                    "plaintextBytes": len(article.lead.encode("utf-8")),
                    "kern": None,
                    "kernGenAt": None,
                    "kernModel": None,
                    "insertedAt": created_at,
                    "updatedAt": created_at,
                    "kernEmb": None,
                },
                "classification": {
                    "aliasIdx": 0,
                    "classification": deterministic_searchable_text(
                        article,
                        selection,
                    ),
                    "perspective": "deterministic-title-alias-lead",
                    "genModel": "none",
                    "genAt": created_at,
                    "promptHash": transform_hash,
                    "validationScore": 1.0,
                    "validationOk": 1,
                },
                "source": {
                    "url": (
                        "https://de.wikipedia.org/w/index.php?"
                        f"oldid={article.revision_id}"
                    ),
                    "revisionId": article.revision_id,
                    "revisionTimestamp": article.revision_timestamp,
                },
            }
        )
    return sorted(records, key=lambda item: item["article"]["id"])


def logical_payload_sha256(
    *,
    pack_id: str,
    created_at: str,
    records: list[dict],
) -> str:
    payload = {
        "schema": "hoshi-knowledge-pack-logical-v1",
        "packId": pack_id,
        "createdAt": created_at,
        "records": records,
    }
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def logical_records_sha256(
    *,
    pack_id: str,
    created_at: str,
    selections: Iterable[PublicSelection],
    articles: dict[str, DumpArticle],
) -> str:
    return logical_payload_sha256(
        pack_id=pack_id,
        created_at=created_at,
        records=logical_records(
            pack_id=pack_id,
            created_at=created_at,
            selections=selections,
            articles=articles,
        ),
    )


def write_pack_database(
    db_path: Path,
    *,
    selections: Iterable[PublicSelection],
    articles: dict[str, DumpArticle],
    created_at: str,
) -> set[int]:
    """Schreibt die eine kanonische SQLite-Ausgabe der gebundenen Toolchain."""

    compressor = legacy.zstd.ZstdCompressor(level=ZSTD_LEVEL)
    copied_ids: set[int] = set()
    with sqlite3.connect(db_path) as target:
        legacy._create_schema(target)
        target.execute("BEGIN")
        for selection in selections:
            article = articles[_normalize_dump_title(selection.title)]
            if article.page_id in copied_ids:
                raise ValueError(
                    f"Mehrere Auswahltitel zeigen auf dieselbe Page-ID: {article.page_id}"
                )
            lead_raw = article.lead.encode("utf-8")
            target.execute(
                "INSERT INTO articles("
                "id,title,title_norm,redirect_to,is_disambig,is_stopword,"
                "plaintext_zstd,plaintext_bytes,kern,kern_gen_at,kern_model,"
                "inserted_at,updated_at,kern_emb"
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    article.page_id,
                    article.title,
                    legacy.normalize_title(article.title),
                    None,
                    0,
                    0,
                    compressor.compress(lead_raw),
                    len(lead_raw),
                    None,
                    None,
                    None,
                    created_at,
                    created_at,
                    None,
                ),
            )
            searchable = deterministic_searchable_text(article, selection)
            target.execute(
                "INSERT INTO classifications("
                "article_id,alias_idx,classification,perspective,gen_model,"
                "gen_at,prompt_hash,validation_score,validation_ok"
                ") VALUES (?,?,?,?,?,?,?,?,?)",
                (
                    article.page_id,
                    0,
                    searchable,
                    "deterministic-title-alias-lead",
                    "none",
                    created_at,
                    hashlib.sha256(TRANSFORM_ID.encode("utf-8")).hexdigest(),
                    1.0,
                    1,
                ),
            )
            target.execute(
                "INSERT INTO article_sources("
                "article_id,source_url,source_revision_id,source_revision_timestamp"
                ") VALUES (?,?,?,?)",
                (
                    article.page_id,
                    "https://de.wikipedia.org/w/index.php?"
                    f"oldid={article.revision_id}",
                    article.revision_id,
                    article.revision_timestamp,
                ),
            )
            copied_ids.add(article.page_id)
        target.commit()
        target.execute(
            "INSERT INTO classifications_fts(classifications_fts) VALUES('optimize')"
        )
        target.commit()
        target.execute("VACUUM")
    return copied_ids


def _write_notice(
    path: Path,
    *,
    pack_id: str,
    spec: DumpSpec,
    artifact: DumpArtifact,
) -> None:
    path.write_text(
        "\n".join(
            [
                f"# {pack_id}",
                "",
                "This pack contains modified extracts from the German-language Wikipedia.",
                "",
                "## Source and license",
                "",
                f"- Source dump: {spec.url}",
                f"- Dump status: {spec.dumpstatus_url}",
                f"- Source dump date: {spec.iso_date}",
                f"- Official dump SHA-1: `{artifact.sha1}`",
                f"- Locally computed dump SHA-256: `{artifact.sha256}`",
                "- Wikipedia text license: CC BY-SA 4.0",
                "- License text: https://creativecommons.org/licenses/by-sa/4.0/",
                "",
                "## Attribution",
                "",
                "Every included row retains the Wikipedia page ID, exact revision ID, "
                "revision timestamp, and a permanent `oldid` URL. That URL identifies "
                "the source revision and links to its public page history and contributors.",
                "",
                "## Modifications",
                "",
                "Hoshi removed templates, references, media/category links, HTML markup, "
                "and content after the first section; then it shortened the remaining "
                "lead deterministically and indexed title, public aliases, and lead text "
                "with SQLite FTS5. No generated facts or private runtime data are included.",
                "",
                "## ShareAlike",
                "",
                "Redistribution or adaptation of the included Wikipedia text must preserve "
                "attribution, indicate modifications, link the license, and use CC BY-SA "
                "4.0 or a license permitted by its ShareAlike terms. Keep this notice and "
                "the per-article permanent URLs with redistributed packs.",
                "",
            ]
        ),
        encoding="utf-8",
    )


def _publish_directory(temp_dir: Path, output_dir: Path) -> None:
    """Publiziert im selben Dateisystem atomar und ohne kooperatives Überschreiben."""

    parent_fd = os.open(output_dir.parent, os.O_RDONLY)
    try:
        fcntl.flock(parent_fd, fcntl.LOCK_EX)
        if output_dir.exists():
            raise ValueError(
                f"Ausgabe existiert bereits, überschreiben verboten: {output_dir}"
            )
        os.rename(temp_dir, output_dir)
        _fsync_directory(output_dir.parent)
    finally:
        fcntl.flock(parent_fd, fcntl.LOCK_UN)
        os.close(parent_fd)


def _validate_created_at(value: str) -> str:
    if not _UTC_TIMESTAMP_RE.fullmatch(value):
        raise ValueError("created-at muss UTC/RFC3339 im Format YYYY-MM-DDTHH:MM:SSZ sein")
    try:
        datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ")
    except ValueError as exc:
        raise ValueError("created-at ist kein gültiger UTC-Zeitpunkt") from exc
    return value


def build_pack_from_dump(
    *,
    spec: DumpSpec,
    cache_dir: Path,
    selection_path: Path,
    output_dir: Path,
    pack_id: str,
    lead_chars: int = 1600,
    max_pack_bytes: int = DEFAULT_MAX_PACK_BYTES,
    created_at: Optional[str] = None,
    source_dump_path: Optional[Path] = None,
    opener: Optional[Callable[..., object]] = None,
    disk_usage: Callable[[Path], object] = shutil.disk_usage,
) -> dict:
    """Verifiziert/streamt einen Dump und publiziert einen unveränderlichen Pack."""

    if output_dir.exists():
        raise ValueError(f"Ausgabe existiert bereits, überschreiben verboten: {output_dir}")
    if not _PACK_ID_RE.fullmatch(pack_id):
        raise ValueError("pack-id darf nur Kleinbuchstaben, Ziffern, Punkt, _ und - enthalten")
    if lead_chars < 300 or lead_chars > 10_000:
        raise ValueError("lead-chars muss zwischen 300 und 10000 liegen")
    if max_pack_bytes <= 0:
        raise ValueError("max-pack-bytes muss für den Releasepfad positiv begrenzt sein")
    selections = read_public_selection(selection_path)
    created = _validate_created_at(
        created_at
        or datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    )
    commit = _verified_builder_commit()
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("builder-commit muss ein vollständiger Git-SHA-1 sein")

    cache_dir.mkdir(parents=True, exist_ok=True)
    output_dir.parent.mkdir(parents=True, exist_ok=True)
    dumpstatus = fetch_dumpstatus_evidence(spec, opener=opener)
    dump_destination = cache_dir / spec.filename
    local_source = source_dump_path.resolve() if source_dump_path is not None else None
    if local_source is not None and not local_source.is_file():
        raise ValueError(f"lokaler Quelldump fehlt oder ist keine Datei: {local_source}")
    preflight_disk(
        spec=spec,
        cache_dir=cache_dir,
        output_parent=output_dir.parent,
        dump_present=local_source is not None or dump_destination.is_file(),
        max_pack_bytes=max_pack_bytes,
        disk_usage=disk_usage,
    )
    if local_source is not None:
        artifact = _hash_dump(local_source)
        _validate_dump_artifact(artifact, spec)
    else:
        artifact = ensure_dump(spec, cache_dir, opener=opener)
    articles = extract_selected_articles(
        artifact.path,
        selections,
        lead_chars=lead_chars,
    )

    transform_hash = hashlib.sha256(TRANSFORM_ID.encode("utf-8")).hexdigest()
    transform_source_hash = _sha256(Path(__file__).resolve())
    temp_dir = Path(
        tempfile.mkdtemp(
            prefix=f".{output_dir.name}.building-",
            dir=output_dir.parent,
        )
    )
    published = False
    try:
        db_path = temp_dir / DATABASE_FILE
        copied_ids = write_pack_database(
            db_path,
            selections=selections,
            articles=articles,
            created_at=created,
        )

        database_size = db_path.stat().st_size
        if database_size > max_pack_bytes:
            raise ValueError(
                "Pack überschreitet das lokale Größenbudget: "
                f"{database_size} > {max_pack_bytes} Bytes"
            )
        database_sha256 = _sha256(db_path)
        _write_notice(
            temp_dir / NOTICE_FILE,
            pack_id=pack_id,
            spec=spec,
            artifact=artifact,
        )
        notice_sha256 = _sha256(temp_dir / NOTICE_FILE)
        (temp_dir / DUMPSTATUS_FILE).write_bytes(dumpstatus.raw)
        selection_file = temp_dir / SELECTION_FILE
        selection_file.write_bytes(canonical_public_selection(selections))
        manifest = {
            "schemaVersion": 1,
            "releaseStatus": RELEASE_STATUS,
            "packId": pack_id,
            "language": "de",
            "createdAt": created,
            "source": {
                "name": "Wikipedia",
                "url": spec.url,
                "dumpDate": spec.iso_date,
                "dumpStatusUrl": spec.dumpstatus_url,
                "dumpStatus": {
                    "file": DUMPSTATUS_FILE,
                    "sha256": dumpstatus.sha256,
                    "job": dumpstatus.job,
                    "status": "done",
                    "updated": dumpstatus.updated,
                },
                "dump": {
                    "sizeBytes": artifact.size,
                    "sha1": artifact.sha1,
                    "sha256": artifact.sha256,
                },
                "license": "CC-BY-SA-4.0",
                "noticeFile": NOTICE_FILE,
                "noticeSha256": notice_sha256,
                "revisionCoverage": "per-article",
                "revisionTimestampCoverage": "per-article",
                "revisionCount": len(copied_ids),
                "revisionTimestampCount": len(copied_ids),
            },
            "builder": {
                "commit": commit,
                "transform": TRANSFORM_ID,
                "transformSha256": transform_hash,
                "transformSourceSha256": transform_source_hash,
                "selection": "explicit-public-title-list",
                "selectionFile": SELECTION_FILE,
                "selectionSha256": _sha256(selection_file),
                "modelDerivedFeatures": [],
                "logicalRecordsSha256": logical_records_sha256(
                    pack_id=pack_id,
                    created_at=created,
                    selections=selections,
                    articles=articles,
                ),
                "parameters": {
                    "leadChars": lead_chars,
                    "zstdLevel": ZSTD_LEVEL,
                },
                "toolchain": toolchain_contract(),
            },
            "database": {
                "file": DATABASE_FILE,
                "sha256": database_sha256,
                "sizeBytes": database_size,
                "articleCount": len(copied_ids),
            },
            "retrieval": {
                "method": "fts5-title-alias-lead",
                "tokenizer": "unicode61 remove_diacritics 2",
                "denseIndex": None,
            },
            "budget": {
                "maxPackBytes": max_pack_bytes,
                "diskPreflight": "compressed-dump-plus-pack-plus-1GiB-margins",
            },
        }
        (temp_dir / MANIFEST_FILE).write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        _publish_directory(temp_dir, output_dir)
        published = True
        return manifest
    finally:
        if not published:
            shutil.rmtree(temp_dir, ignore_errors=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump-date", required=True, help="YYYYMMDD")
    parser.add_argument("--expected-size", required=True, type=int, help="Bytes")
    parser.add_argument("--expected-sha1", required=True)
    parser.add_argument("--dump-cache-dir", required=True, type=Path)
    parser.add_argument("--selection", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--pack-id", required=True)
    parser.add_argument("--lead-chars", type=int, default=1600)
    parser.add_argument(
        "--max-pack-bytes",
        type=int,
        default=DEFAULT_MAX_PACK_BYTES,
        help="Positives, vorab planbares Release-Budget; Default 512 MiB",
    )
    parser.add_argument("--created-at")
    args = parser.parse_args()

    try:
        spec = DumpSpec.create(
            args.dump_date,
            args.expected_size,
            args.expected_sha1,
        )
        manifest = build_pack_from_dump(
            spec=spec,
            cache_dir=args.dump_cache_dir.expanduser().resolve(),
            selection_path=args.selection.expanduser().resolve(),
            output_dir=args.output_dir.expanduser().resolve(),
            pack_id=args.pack_id,
            lead_chars=args.lead_chars,
            max_pack_bytes=args.max_pack_bytes,
            created_at=args.created_at,
        )
    except (
        OSError,
        sqlite3.Error,
        subprocess.CalledProcessError,
        ValueError,
    ) as exc:
        print(f"[knowledge-pack-dump-build] FATAL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
