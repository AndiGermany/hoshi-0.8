"""Kleiner End-to-End-Beweis: Legacy-Quelle → öffentliches, verifizierbares Pack."""

import importlib.util
import json
import sqlite3
import sys
from pathlib import Path

import pytest
import zstandard as zstd


HERE = Path(__file__).resolve().parent


def _module(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


build_pack = _module("knowledge_pack_builder", "build_pack.py")
verify_pack = _module("knowledge_pack_verifier", "verify_pack.py")


def _source_db(path):
    text = (
        "Albert Einstein war ein Physiker. "
        "Er entwickelte die Relativitätstheorie und erhielt 1921 den Nobelpreis."
    )
    raw = text.encode("utf-8")
    blob = zstd.ZstdCompressor().compress(raw)
    with sqlite3.connect(path) as conn:
        conn.executescript(
            """
            CREATE TABLE articles (
                id INTEGER PRIMARY KEY,
                title TEXT NOT NULL,
                title_norm TEXT NOT NULL,
                redirect_to INTEGER,
                is_disambig INTEGER NOT NULL DEFAULT 0,
                is_stopword INTEGER NOT NULL DEFAULT 0,
                plaintext_zstd BLOB,
                plaintext_bytes INTEGER
            );
            CREATE INDEX idx_articles_title_norm ON articles(title_norm);
            """
        )
        conn.execute(
            "INSERT INTO articles VALUES (?,?,?,?,?,?,?,?)",
            (42, "Albert Einstein", "albert_einstein", None, 0, 0, blob, len(raw)),
        )


def test_builder_creates_sanitized_searchable_pack(tmp_path):
    source = tmp_path / "source.db"
    _source_db(source)
    selection = tmp_path / "selection.jsonl"
    selection.write_text(
        json.dumps(
            {
                "title": "Albert Einstein",
                "aliases": ["Einstein"],
                "sourceRevisionId": "123456789",
            }
        )
        + "\n",
        encoding="utf-8",
    )
    output = tmp_path / "de-core"

    manifest = build_pack.build_pack(
        source_db=source,
        selection_path=selection,
        output_dir=output,
        pack_id="hoshi-wikipedia-de-test",
        source_dump_date="2026-07-01",
        source_dump_url="https://dumps.wikimedia.org/dewiki/20260701/",
        created_at="2026-07-25T20:00:00Z",
        builder_commit="a" * 40,
    )

    assert manifest["releaseStatus"] == "forensic-non-release"
    assert manifest["source"]["provenanceStatus"] == "caller-asserted-unverified"
    assert manifest["builder"]["modelDerivedFeatures"] == []
    assert manifest["source"]["revisionCoverage"] == "per-article"
    result = verify_pack.verify_pack(output / "manifest.json", fast=False)
    assert result["status"] == "ok"
    assert result["articleCount"] == 1
    assert result["privateTables"] == []
    assert result["releaseEligible"] is False

    with sqlite3.connect(output / "pack.sqlite") as conn:
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type='table'"
            )
        }
        assert "external_lookups" not in tables
        assert "build_progress" not in tables
        assert conn.execute(
            "SELECT count(*) FROM classifications_fts "
            "WHERE classifications_fts MATCH 'physiker'"
        ).fetchone()[0] == 1
        assert conn.execute(
            "SELECT source_revision_id FROM article_sources WHERE article_id=42"
        ).fetchone()[0] == "123456789"


def test_builder_rejects_unknown_selection_fields_before_writing(tmp_path):
    source = tmp_path / "source.db"
    _source_db(source)
    selection = tmp_path / "selection.jsonl"
    selection.write_text(
        json.dumps(
            {
                "title": "Albert Einstein",
                "privateQuery": "Was eine Person gefragt hat",
                "goldTitles": ["Albert Einstein"],
            }
        )
        + "\n",
        encoding="utf-8",
    )
    output = tmp_path / "must-not-exist"

    with pytest.raises(ValueError, match="nicht-öffentliche"):
        build_pack.build_pack(
            source_db=source,
            selection_path=selection,
            output_dir=output,
            pack_id="hoshi-wikipedia-de-test",
            source_dump_date="2026-07-01",
            source_dump_url="https://dumps.wikimedia.org/dewiki/20260701/",
            created_at="2026-07-25T20:00:00Z",
            builder_commit="a" * 40,
        )
    assert not output.exists()


def test_builder_enforces_size_budget_atomically(tmp_path):
    source = tmp_path / "source.db"
    _source_db(source)
    selection = tmp_path / "selection.jsonl"
    selection.write_text(
        json.dumps({"title": "Albert Einstein", "aliases": []}) + "\n",
        encoding="utf-8",
    )
    output = tmp_path / "too-large"

    with pytest.raises(ValueError, match="Größenbudget"):
        build_pack.build_pack(
            source_db=source,
            selection_path=selection,
            output_dir=output,
            pack_id="hoshi-wikipedia-de-test",
            source_dump_date="2026-07-01",
            source_dump_url="https://dumps.wikimedia.org/dewiki/20260701/",
            max_pack_bytes=1,
            created_at="2026-07-25T20:00:00Z",
            builder_commit="a" * 40,
        )
    assert not output.exists()
