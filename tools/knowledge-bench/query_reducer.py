#!/usr/bin/env python3
"""Eingefrorener Python-Spiegel des produktiven FTS5-Query-Reducers.

Der Kotlin-Reducer ist derzeit ``internal`` und kann von einem stdlib-Python-
Sammelwerkzeug nicht ohne Modulbruch aufgerufen werden. Deshalb friert dieser
Spiegel den ab ``productionRegionStart`` markierten Dateisuffix per SHA-256
sowie die vollständige Quelldatei am gepinnten Commit per Git-Blob/SHA-256 und
gemeinsame Testvektoren ein. ``freeze`` verweigert die Arbeit, sobald der
markierte Suffix driftet.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
CONTRACT_PATH = SCRIPT_DIR / "query-reducer-contract-v1.json"

_CONTENT_KEEP = {"ä", "ö", "ü", "ß", " "}
_FILLER_TOKENS = {
    "hallo", "hi", "hey", "moin", "servus", "tach", "guten", "tag", "morgen", "abend",
    "danke", "bitte", "tschüss", "ciao", "hallöchen",
    "sag", "sags", "sage", "kurz", "mal", "doch", "bitteschön",
    "wie", "geht", "gehts", "gehs", "dir", "euch", "ihnen", "uns", "mir", "mich", "dich",
    "wer", "war", "ist", "sind", "bist", "bin", "warst", "waren", "wars",
    "was", "wann", "wo", "wieso", "warum", "weshalb", "welche", "welcher", "welches",
    "der", "die", "das", "den", "dem", "des", "ein", "eine", "einen", "einem", "eines", "einer",
    "und", "oder", "aber", "auch", "noch", "schon", "denn", "nur",
    "magst", "mag", "kannst", "kann", "willst", "will", "möchtest", "möchte", "darfst",
    "erzähl", "erzähle", "erzaehl", "erzaehle", "witz", "witze", "spaß", "spass",
    "alles", "fit", "klar", "okay", "gut", "schön", "toll",
    "machst", "macht", "tust", "tut",
}
_POLITENESS_PREFIXES = (
    re.compile(
        r"^\s*(?:kannst|könntest|würdest|magst)\s+du\s+(?:mir\s+)?"
        r"(?:bitte\s+)?(?:mal\s+)?(?:kurz\s+)?"
        r"(?:erklären|sagen|verraten|erzählen)\s*,?\s*",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\s*(?:erklär|erkläre|verrat|verrate|erzähl|erzähle)\s+"
        r"(?:mir[\s,]+)?(?:bitte[\s,]+)?(?:mal[\s,]+)?"
        r"(?:kurz[\s,]+)?,?\s*",
        re.IGNORECASE,
    ),
    re.compile(r"^\s*wei(?:ß|ss)t\s+du\s*,?\s*", re.IGNORECASE),
    re.compile(
        r"^\s*(?:can|could|would)\s+you\s+(?:please\s+)?"
        r"(?:tell\s+me|explain(?:\s+to\s+me)?)\s*,?\s*",
        re.IGNORECASE,
    ),
)
_LEADING_FRAMES = (
    re.compile(
        r"^\s*woher\s+(?:kommt|stammt)\s+"
        r"(?:der\s+name\s+|das\s+wort\s+|der\s+begriff\s+)?",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\s*woher\s+(?:der\s+name\s+|das\s+wort\s+|der\s+begriff\s+)",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\s*was\s+bedeutet\s+"
        r"(?:das\s+wort\s+|der\s+begriff\s+|die\s+abkürzung\s+|der\s+name\s+)?",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\s*was\s+hei(?:ß|ss)t\s+"
        r"(?:das\s+wort\s+|der\s+begriff\s+|der\s+name\s+)?",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\s*where\s+does\s+(?:the\s+)?(?:name\s+|word\s+)?",
        re.IGNORECASE,
    ),
    re.compile(
        r"^\s*what(?:'?s|\s+is)\s+the\s+(?:origin|meaning)\s+of\s+"
        r"(?:the\s+)?(?:name\s+|word\s+)?",
        re.IGNORECASE,
    ),
)
_DE_TRAILING_VERB = re.compile(
    r"\s+(?:kommt|stammt|herkommt)\s*\??\s*$",
    re.IGNORECASE,
)
_TRAILING_FRAME = re.compile(r"\s+come\s+from\s*\??\s*$", re.IGNORECASE)
_MODAL_FILLER_CORE = re.compile(
    r"eigentlich|denn|überhaupt|noch\s*mals?|eben|halt",
    re.IGNORECASE,
)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _continuation(char: str) -> bool:
    return char.isalpha() or char.isdigit() or char in {"'", "’", "-"}


def _strip_modal_fillers(query: str) -> str:
    parts: list[str] = []
    cursor = 0
    for match in _MODAL_FILLER_CORE.finditer(query):
        before = query[match.start() - 1] if match.start() else ""
        after = query[match.end()] if match.end() < len(query) else ""
        if (before and _continuation(before)) or (after and _continuation(after)):
            continue
        parts.append(query[cursor:match.start()])
        parts.append(" ")
        cursor = match.end()
    parts.append(query[cursor:])
    return "".join(parts)


def _strip_first(patterns: tuple[re.Pattern[str], ...], value: str) -> tuple[str, bool]:
    for pattern in patterns:
        stripped, count = pattern.subn("", value, count=1)
        if count:
            return stripped.strip(), True
    return value, False


def _question_frame_strip(query: str) -> tuple[str, bool]:
    value = _strip_modal_fillers(query).strip().lstrip(", ")
    value, _ = _strip_first(_POLITENESS_PREFIXES, value)
    value, matched = _strip_first(_LEADING_FRAMES, value)
    if matched:
        value, count = _DE_TRAILING_VERB.subn("", value, count=1)
        if count:
            value = value.strip()
    value, trailing = _TRAILING_FRAME.subn("", value, count=1)
    if trailing:
        value = value.strip()
        matched = True
    return value, matched


def content_tokens(query: str) -> list[str]:
    normalized = "".join(
        char
        if char.isalpha() or char.isdigit() or char in _CONTENT_KEEP
        else " "
        for char in query.lower()
    )
    result: list[str] = []
    for token in normalized.split(" "):
        token = token.strip()
        if (
            token
            and token not in _FILLER_TOKENS
            and (len(token) >= 3 or token.isdigit())
            and token not in result
        ):
            result.append(token)
    return result


def search_query(query: str) -> str:
    stripped, _ = _question_frame_strip(query)
    tokens = content_tokens(stripped)
    return " ".join(tokens) if tokens else query.strip()


def load_contract() -> dict:
    return json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))


def _source_region(source: Path, marker: str) -> bytes:
    content = source.read_text(encoding="utf-8")
    try:
        start = content.index(marker)
    except ValueError as exc:
        raise ValueError("Produktions-Reducer-Marker fehlt") from exc
    return content[start:].encode("utf-8")


def verify_contract(repo_root: Path = REPO_ROOT) -> dict:
    contract = load_contract()
    source = repo_root / contract["productionSource"]
    if not source.is_file():
        raise ValueError(f"Produktions-Reducer fehlt: {source}")
    actual_sha = _sha256(source)
    region_sha = hashlib.sha256(
        _source_region(source, contract["productionRegionStart"])
    ).hexdigest()
    expected_region_sha = contract["productionRegionSha256"]
    if region_sha != expected_region_sha:
        raise ValueError(
            "Der Query-Reducer ist gegenüber dem eingefrorenen Sammelvertrag "
            f"gedriftet: {region_sha} != {expected_region_sha}"
        )
    for vector in contract["vectors"]:
        actual = search_query(vector["query"])
        if actual != vector["searchQuery"]:
            raise ValueError(
                f"Reducer-Vertragsvektor driftet für {vector['query']!r}: "
                f"{actual!r} != {vector['searchQuery']!r}"
            )
    try:
        blob = subprocess.run(
            ["git", "-C", str(repo_root), "hash-object", str(source)],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError) as exc:
        raise ValueError(f"Git-Blob des Produktions-Reducers nicht bestimmbar: {exc}") from exc
    pinned_spec = (
        f"{contract['productionCommit']}:{contract['productionSource']}"
    )
    try:
        pinned_source = subprocess.run(
            ["git", "-C", str(repo_root), "show", pinned_spec],
            check=True,
            capture_output=True,
        ).stdout
        pinned_blob = subprocess.run(
            ["git", "-C", str(repo_root), "rev-parse", pinned_spec],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        # Exportierter Baum (öffentliches Repo, CI): der gepinnte Commit lebt
        # nur in der privaten History. Der Vertrag bleibt trotzdem voll
        # prüfbar — `git hash-object` errechnet die Blob-ID aus dem INHALT,
        # und die SHA-256-/Blob-Vergleiche unten beißen unverändert. Was hier
        # entfällt, ist allein der Beweis, dass der Commit existierte; was
        # bleibt, ist der Beweis, dass der Inhalt byte-identisch ist.
        source_path = repo_root / contract["productionSource"]
        try:
            pinned_source = source_path.read_bytes()
            pinned_blob = subprocess.run(
                ["git", "-C", str(repo_root), "hash-object", "--", str(source_path)],
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
        except (OSError, subprocess.CalledProcessError) as exc:
            raise ValueError(f"Gepinnter Reducer-Commit nicht verifizierbar: {exc}") from exc
    pinned_sha = hashlib.sha256(pinned_source).hexdigest()
    if pinned_sha != contract["productionSourceSha256"]:
        raise ValueError(
            "SHA-256 des gepinnten Reducers stimmt nicht mit dem Vertrag überein"
        )
    if pinned_blob != contract["productionGitBlob"]:
        raise ValueError(
            "Git-Blob des gepinnten Reducers stimmt nicht mit dem Vertrag überein"
        )
    return {
        "version": contract["contractVersion"],
        "source": contract["productionSource"],
        "sourceSha256": actual_sha,
        "sourceGitBlob": blob,
        "pinnedSourceSha256": pinned_sha,
        "pinnedSourceGitBlob": pinned_blob,
        "regionSha256": region_sha,
        "sourceCommit": contract["productionCommit"],
        "contractFile": CONTRACT_PATH.name,
        "contractSha256": _sha256(CONTRACT_PATH),
    }
