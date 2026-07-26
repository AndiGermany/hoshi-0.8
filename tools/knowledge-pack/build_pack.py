#!/usr/bin/env python3
"""Baut aus einer lokalen öffentlichen Wiki-DB ein forensisches Hoshi-Pack v1.

Die Auswahl ist absichtlich explizit: Dieses Werkzeug rät nicht, welche
Wikipedia-Artikel für ein Zuhause wichtig sind. Eine öffentliche JSONL-Datei
liefert Titel/Aliase und optional Revisions-IDs; der Benchmark entscheidet über
die Auswahl. Private Queries oder Nutzungsdaten akzeptiert der Builder nicht.

Wichtig: Die historische DB bewahrt den ursprünglichen Dump-Hash nicht auf.
Ausgaben dieses Werkzeugs sind daher immer ``forensic-non-release``. Für ein
veröffentlichbares Pack ist ``build_pack_from_dump.py`` der bindende Pfad.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import urllib.parse
from dataclasses import dataclass
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Optional

import zstandard as zstd


DATABASE_FILE = "pack.sqlite"
MANIFEST_FILE = "manifest.json"
NOTICE_FILE = "NOTICE.md"
TRANSFORM_ID = "hoshi-pack-v1-title-alias-lead"
ALLOWED_SELECTION_KEYS = {"title", "aliases", "sourceRevisionId"}
_CAPTION_HEADS = {
    "mini",
    "thumb",
    "thumbnail",
    "hochkant",
    "gerahmt",
    "rahmenlos",
    "links",
    "rechts",
    "zentriert",
    "center",
    "right",
    "left",
}


@dataclass(frozen=True)
class Selection:
    title: str
    aliases: tuple[str, ...]
    revision_id: Optional[str]


def normalize_title(title: str) -> str:
    return re.sub(r"\s+", "_", title.strip().lower())


def _selection(path: Path) -> list[Selection]:
    rows: list[Selection] = []
    seen: set[str] = set()
    with path.open(encoding="utf-8") as handle:
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
            revision = item.get("sourceRevisionId")
            if revision is not None and (
                not isinstance(revision, str) or not revision.strip()
            ):
                raise ValueError(
                    f"{path}:{line_no}: sourceRevisionId muss String oder null sein"
                )
            if revision is not None and not revision.strip().isdigit():
                raise ValueError(
                    f"{path}:{line_no}: Wikipedia-sourceRevisionId muss numerisch sein"
                )
            key = normalize_title(title)
            if key in seen:
                raise ValueError(f"{path}:{line_no}: doppelter Titel {title!r}")
            seen.add(key)
            rows.append(
                Selection(
                    title=title.strip(),
                    aliases=tuple(dict.fromkeys(a.strip() for a in aliases)),
                    revision_id=revision.strip() if revision else None,
                )
            )
    if not rows:
        raise ValueError("Auswahl ist leer")
    return rows


def _is_caption(line: str) -> bool:
    stripped = line.strip()
    if not stripped:
        return False
    low = stripped.lower()
    if low.startswith(("alt=", "[[datei:", "[[file:", "[[image:", "[[bild:")):
        return True
    head = low.split("|", 1)[0].strip()
    return "|" in low and head in _CAPTION_HEADS


def compact_lead(text: str, max_chars: int) -> str:
    """Konservative Lead-Kompression aus bereits extrahiertem Wikipedia-Text."""
    lines = [line.strip() for line in text.splitlines() if not _is_caption(line)]
    cleaned = "\n".join(line for line in lines if line)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    if len(cleaned) <= max_chars:
        return cleaned
    window = cleaned[:max_chars]
    cut = max(window.rfind(". "), window.rfind("! "), window.rfind("? "))
    return window[: cut + 1].strip() if cut >= max_chars // 2 else window.rstrip() + "…"


def _decompress(row: sqlite3.Row) -> str:
    blob = row["plaintext_zstd"]
    if not blob:
        raise ValueError(f"Artikel {row['title']!r} besitzt keinen öffentlichen Plaintext")
    original_bytes = row["plaintext_bytes"] or 0
    decompressor = zstd.ZstdDecompressor()
    try:
        raw = decompressor.decompress(
            blob,
            max_output_size=max(int(original_bytes), 8 * 1024 * 1024),
        )
    except zstd.ZstdError as exc:
        raise ValueError(f"Artikel {row['title']!r} ist nicht dekomprimierbar: {exc}") from exc
    return raw.decode("utf-8", errors="replace")


def _resolve_article(conn: sqlite3.Connection, selection: Selection) -> tuple[sqlite3.Row, list[str]]:
    normalized = normalize_title(selection.title)
    variants = tuple(dict.fromkeys((normalized, normalized.replace("_", "-"))))
    placeholders = ",".join("?" for _ in variants)
    row = conn.execute(
        "SELECT id, title, title_norm, redirect_to, is_disambig, is_stopword, "
        "plaintext_zstd, plaintext_bytes "
        f"FROM articles WHERE title_norm IN ({placeholders}) "
        "ORDER BY CASE WHEN lower(title)=lower(?) THEN 0 ELSE 1 END, id LIMIT 1",
        (*variants, selection.title),
    ).fetchone()
    if row is None:
        raise ValueError(f"Artikel nicht gefunden: {selection.title!r}")

    aliases = list(selection.aliases)
    if row["redirect_to"] is not None:
        aliases.extend((selection.title, row["title"]))
        target = conn.execute(
            "SELECT id, title, title_norm, redirect_to, is_disambig, is_stopword, "
            "plaintext_zstd, plaintext_bytes FROM articles WHERE id=?",
            (row["redirect_to"],),
        ).fetchone()
        if target is None:
            raise ValueError(
                f"Redirect-Ziel {row['redirect_to']} für {selection.title!r} fehlt"
            )
        row = target
    elif not row["title"].casefold() == selection.title.casefold():
        aliases.append(selection.title)
    return row, list(dict.fromkeys(alias for alias in aliases if alias != row["title"]))


def _create_schema(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        PRAGMA foreign_keys=ON;
        CREATE TABLE articles (
            id INTEGER PRIMARY KEY,
            title TEXT NOT NULL,
            title_norm TEXT NOT NULL,
            redirect_to INTEGER REFERENCES articles(id),
            is_disambig INTEGER NOT NULL DEFAULT 0,
            is_stopword INTEGER NOT NULL DEFAULT 0,
            plaintext_zstd BLOB,
            plaintext_bytes INTEGER,
            kern TEXT,
            kern_gen_at TEXT,
            kern_model TEXT,
            inserted_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            kern_emb BLOB
        );
        CREATE UNIQUE INDEX idx_articles_title_norm ON articles(title_norm);
        CREATE INDEX idx_articles_redirect
            ON articles(redirect_to) WHERE redirect_to IS NOT NULL;

        CREATE TABLE classifications (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            article_id INTEGER NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
            alias_idx INTEGER NOT NULL,
            classification TEXT NOT NULL,
            perspective TEXT NOT NULL,
            gen_model TEXT NOT NULL,
            gen_at TEXT NOT NULL,
            prompt_hash TEXT NOT NULL,
            validation_score REAL,
            validation_ok INTEGER NOT NULL DEFAULT 1,
            UNIQUE(article_id, alias_idx)
        );
        CREATE INDEX idx_classifications_article ON classifications(article_id);
        CREATE VIRTUAL TABLE classifications_fts USING fts5(
            classification,
            content='classifications',
            content_rowid='id',
            tokenize='unicode61 remove_diacritics 2'
        );
        CREATE TRIGGER classifications_ai AFTER INSERT ON classifications BEGIN
            INSERT INTO classifications_fts(rowid, classification)
            VALUES (new.id, new.classification);
        END;
        CREATE TRIGGER classifications_ad AFTER DELETE ON classifications BEGIN
            INSERT INTO classifications_fts(
                classifications_fts, rowid, classification
            ) VALUES('delete', old.id, old.classification);
        END;
        CREATE TRIGGER classifications_au AFTER UPDATE ON classifications BEGIN
            INSERT INTO classifications_fts(
                classifications_fts, rowid, classification
            ) VALUES('delete', old.id, old.classification);
            INSERT INTO classifications_fts(rowid, classification)
            VALUES (new.id, new.classification);
        END;

        CREATE TABLE article_sources (
            article_id INTEGER PRIMARY KEY REFERENCES articles(id) ON DELETE CASCADE,
            source_url TEXT NOT NULL,
            source_revision_id TEXT,
            source_revision_timestamp TEXT
        );
        """
    )


def _git_commit() -> str:
    result = subprocess.run(
        ["git", "-C", str(Path(__file__).resolve().parents[2]), "rev-parse", "HEAD"],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def build_pack(
    *,
    source_db: Path,
    selection_path: Path,
    output_dir: Path,
    pack_id: str,
    source_dump_date: str,
    source_dump_url: str,
    language: str = "de",
    lead_chars: int = 1600,
    max_pack_bytes: int = 512 * 1024 * 1024,
    created_at: Optional[str] = None,
    builder_commit: Optional[str] = None,
) -> dict:
    """Erzeugt atomar einen neuen Pack-Ordner; bestehende Ziele bleiben unberührt."""

    if output_dir.exists():
        raise ValueError(f"Ausgabe existiert bereits, überschreiben verboten: {output_dir}")
    if not source_db.is_file():
        raise ValueError(f"Quell-DB fehlt: {source_db}")
    if not re.fullmatch(r"[a-z0-9][a-z0-9._-]{2,127}", pack_id):
        raise ValueError("pack-id darf nur Kleinbuchstaben, Ziffern, Punkt, _ und - enthalten")
    if lead_chars < 300 or lead_chars > 10_000:
        raise ValueError("lead-chars muss zwischen 300 und 10000 liegen")
    if max_pack_bytes < 0:
        raise ValueError("max-pack-bytes darf nicht negativ sein")
    if not source_dump_url.startswith("https://"):
        raise ValueError("source-dump-url muss eine öffentliche HTTPS-URL sein")
    try:
        date.fromisoformat(source_dump_date)
    except ValueError as exc:
        raise ValueError("source-dump-date muss YYYY-MM-DD sein") from exc
    selections = _selection(selection_path)
    created = created_at or datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    commit = builder_commit or _git_commit()
    transform_hash = hashlib.sha256(TRANSFORM_ID.encode()).hexdigest()

    output_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        prefix=f".{output_dir.name}.building-",
        dir=output_dir.parent,
    ) as temp_name:
        temp_dir = Path(temp_name)
        db_path = temp_dir / DATABASE_FILE
        compressor = zstd.ZstdCompressor(level=10)

        source_uri = (
            "file:"
            + urllib.parse.quote(str(source_db.resolve()), safe="/")
            + "?mode=ro"
        )
        with (
            sqlite3.connect(source_uri, uri=True, timeout=30.0) as source,
            sqlite3.connect(db_path) as target,
        ):
            source.row_factory = sqlite3.Row
            _create_schema(target)
            target.execute("BEGIN")
            copied_ids: set[int] = set()
            revision_count = 0
            for item in selections:
                row, aliases = _resolve_article(source, item)
                if row["id"] in copied_ids:
                    raise ValueError(
                        f"Mehrere Auswahltitel zeigen auf denselben Artikel: {row['title']!r}"
                    )
                if row["is_disambig"] or row["is_stopword"]:
                    raise ValueError(f"Disambig-/Stopword-Artikel nicht packen: {row['title']!r}")
                lead = compact_lead(_decompress(row), lead_chars)
                if not lead:
                    raise ValueError(f"Artikel {row['title']!r} liefert keinen brauchbaren Lead")
                lead_raw = lead.encode("utf-8")
                target.execute(
                    "INSERT INTO articles("
                    "id,title,title_norm,redirect_to,is_disambig,is_stopword,"
                    "plaintext_zstd,plaintext_bytes,kern,kern_gen_at,kern_model,"
                    "inserted_at,updated_at,kern_emb"
                    ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (
                        row["id"],
                        row["title"],
                        row["title_norm"],
                        None,
                        0,
                        0,
                        compressor.compress(lead_raw),
                        len(lead_raw),
                        None,
                        None,
                        None,
                        created,
                        created,
                        None,
                    ),
                )
                alias_text = " ".join(aliases)
                searchable = re.sub(
                    r"\s+",
                    " ",
                    f"WIKIPEDIA {row['title']} {alias_text} {lead}",
                ).strip()
                target.execute(
                    "INSERT INTO classifications("
                    "article_id,alias_idx,classification,perspective,gen_model,"
                    "gen_at,prompt_hash,validation_score,validation_ok"
                    ") VALUES (?,?,?,?,?,?,?,?,?)",
                    (
                        row["id"],
                        0,
                        searchable,
                        "deterministic-title-alias-lead",
                        "none",
                        created,
                        transform_hash,
                        1.0,
                        1,
                    ),
                )
                target.execute(
                    "INSERT INTO article_sources("
                    "article_id,source_url,source_revision_id"
                    ") VALUES (?,?,?)",
                    (
                        row["id"],
                        f"https://de.wikipedia.org/?curid={row['id']}",
                        item.revision_id,
                    ),
                )
                copied_ids.add(row["id"])
                revision_count += int(item.revision_id is not None)
            target.commit()
            target.execute("INSERT INTO classifications_fts(classifications_fts) VALUES('optimize')")
            target.commit()
            target.execute("VACUUM")

        database_size = db_path.stat().st_size
        if max_pack_bytes and database_size > max_pack_bytes:
            raise ValueError(
                "Pack überschreitet das lokale Größenbudget: "
                f"{database_size} > {max_pack_bytes} Bytes"
            )
        database_sha256 = _sha256(db_path)
        (temp_dir / NOTICE_FILE).write_text(
            "\n".join(
                [
                    f"# {pack_id}",
                    "",
                    "This pack contains modified extracts from the German-language Wikipedia.",
                    "",
                    f"- Source dump: {source_dump_url}",
                    f"- Source dump date: {source_dump_date}",
                    "- Content license: CC BY-SA 4.0",
                    "- License: https://creativecommons.org/licenses/by-sa/4.0/",
                    "",
                    "Modifications: wiki markup and image-caption remnants were removed; "
                    "articles were shortened to deterministic lead extracts; titles, "
                    "public aliases and lead text were indexed with SQLite FTS5. "
                    "No generated facts or private runtime data are included.",
                    "",
                    "Every article keeps its Wikipedia page ID and canonical source URL. "
                    "The linked page history provides the contributor attribution.",
                    "",
                ]
            ),
            encoding="utf-8",
        )
        manifest = {
            "schemaVersion": 1,
            "releaseStatus": "forensic-non-release",
            "packId": pack_id,
            "language": language,
            "createdAt": created,
            "source": {
                "name": "Wikipedia",
                "url": source_dump_url,
                "dumpDate": source_dump_date,
                "license": "CC-BY-SA-4.0",
                "noticeFile": NOTICE_FILE,
                "revisionCoverage": (
                    "per-article" if revision_count == len(copied_ids) else "page-id-only"
                ),
                "provenanceStatus": "caller-asserted-unverified",
            },
            "builder": {
                "commit": commit,
                "transform": TRANSFORM_ID,
                "selection": "explicit-public-title-list",
                "selectionSha256": _sha256(selection_path),
                "modelDerivedFeatures": [],
            },
            "database": {
                "file": DATABASE_FILE,
                "sha256": database_sha256,
                "sizeBytes": db_path.stat().st_size,
                "articleCount": len(copied_ids),
            },
            "retrieval": {
                "method": "fts5-title-alias-lead",
                "tokenizer": "unicode61 remove_diacritics 2",
                "denseIndex": None,
            },
            "budget": {
                "maxPackBytes": max_pack_bytes or None,
            },
        }
        (temp_dir / MANIFEST_FILE).write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        os.rename(temp_dir, output_dir)
    return manifest


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-db", required=True, type=Path)
    parser.add_argument("--selection", required=True, type=Path)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--pack-id", required=True)
    parser.add_argument("--source-dump-date", required=True)
    parser.add_argument("--source-dump-url", required=True)
    parser.add_argument("--language", default="de")
    parser.add_argument("--lead-chars", type=int, default=1600)
    parser.add_argument(
        "--max-pack-bytes",
        type=int,
        default=512 * 1024 * 1024,
        help="Default 512 MiB; 0 nur für bewusst unbeschränkten Full-Pack",
    )
    parser.add_argument("--created-at")
    parser.add_argument("--builder-commit")
    args = parser.parse_args()
    try:
        manifest = build_pack(
            source_db=args.source_db.expanduser().resolve(),
            selection_path=args.selection.expanduser().resolve(),
            output_dir=args.output_dir.expanduser().resolve(),
            pack_id=args.pack_id,
            source_dump_date=args.source_dump_date,
            source_dump_url=args.source_dump_url,
            language=args.language,
            lead_chars=args.lead_chars,
            max_pack_bytes=args.max_pack_bytes,
            created_at=args.created_at,
            builder_commit=args.builder_commit,
        )
    except (OSError, sqlite3.Error, ValueError, subprocess.CalledProcessError) as exc:
        print(f"[knowledge-pack-build] FATAL: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
