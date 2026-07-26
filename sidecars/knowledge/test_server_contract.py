"""Kleine Vertrags- und Read-only-Tests ohne echte Wikipedia-Datenbank."""
import hashlib
import json
import os
import sqlite3
import sys

import pytest
from fastapi import HTTPException

os.environ.setdefault("HOSHI_WIKI_DB_PATH", "/dev/null")
sys.path.insert(0, os.path.dirname(__file__))

import server  # noqa: E402
from pack_manifest import load_pack_state  # noqa: E402


def _minimal_db(path) -> None:
    with sqlite3.connect(path) as conn:
        conn.execute("CREATE TABLE articles (id INTEGER PRIMARY KEY)")
        conn.executemany("INSERT INTO articles(id) VALUES (?)", [(1,), (2,)])


def test_health_reads_external_db_without_mutation(tmp_path, monkeypatch):
    db = tmp_path / "articles.db"
    _minimal_db(db)
    monkeypatch.setattr(server, "DB_PATH", db)
    monkeypatch.setattr(server, "_article_count_cache", None)

    response = server.health()

    assert response.status == "ok"
    assert response.articleCount == 2
    assert response.dbPath == str(db)


def test_open_conn_is_read_only(tmp_path, monkeypatch):
    db = tmp_path / "articles.db"
    _minimal_db(db)
    monkeypatch.setattr(server, "DB_PATH", db)

    with server.open_conn() as conn:
        assert conn.execute("SELECT count(*) FROM articles").fetchone()[0] == 2
        with pytest.raises(sqlite3.OperationalError):
            conn.execute("CREATE TABLE must_not_exist (id INTEGER)")


def test_open_conn_quotes_percent_encoded_path(tmp_path, monkeypatch):
    literal = tmp_path / "safe%2F..%2Fevil.sqlite"
    _minimal_db(literal)
    (tmp_path / "safe").mkdir()
    evil = tmp_path / "evil.sqlite"
    with sqlite3.connect(evil) as conn:
        conn.execute("CREATE TABLE articles (id INTEGER PRIMARY KEY)")
        conn.execute("INSERT INTO articles(id) VALUES (99)")
    monkeypatch.setattr(server, "DB_PATH", literal)

    with server.open_conn() as conn:
        ids = [row[0] for row in conn.execute("SELECT id FROM articles ORDER BY id")]

    assert ids == [1, 2]


def test_v1_health_exposes_legacy_truth_without_local_path(tmp_path, monkeypatch):
    db = tmp_path / "articles.db"
    _minimal_db(db)
    monkeypatch.setattr(server, "DB_PATH", db)
    monkeypatch.setattr(server, "_article_count_cache", None)

    response = server.health_v1()

    assert response["status"] == "ok"
    assert response["articleCount"] == 2
    assert response["pack"]["status"] == "legacy-unmanifested"
    assert response["runtimeCode"] == server.RUNTIME_CODE
    assert "dbPath" not in response
    assert str(tmp_path) not in json.dumps(response)


def test_v1_manifest_refuses_to_invent_legacy_provenance():
    with pytest.raises(HTTPException) as exc:
        server.manifest_v1()

    assert exc.value.status_code == 404
    assert "legacy-unmanifested" in exc.value.detail


def test_v1_manifest_uses_runtime_state_summary(monkeypatch):
    class _VerifiedState:
        manifest = object()

        @staticmethod
        def assert_database_unchanged(_db_path):
            return None

        @staticmethod
        def public_summary():
            return {
                "status": "manifest-content-verified",
                "verification": {
                    "contentSha256Verified": True,
                    "actualDatabaseSha256": "a" * 64,
                },
            }

    monkeypatch.setattr(server, "PACK_STATE", _VerifiedState())

    response = server.manifest_v1()

    assert response["status"] == "manifest-content-verified"
    assert response["verification"]["contentSha256Verified"] is True


def test_v1_manifest_never_reports_verified_after_database_drift(tmp_path, monkeypatch):
    db = tmp_path / "pack.sqlite"
    _minimal_db(db)
    (tmp_path / "NOTICE.md").write_text("CC BY-SA 4.0\n", encoding="utf-8")
    digest = hashlib.sha256(db.read_bytes()).hexdigest()
    manifest = {
        "schemaVersion": 1,
        "packId": "hoshi-wikipedia-de-test",
        "language": "de",
        "source": {
            "name": "Wikipedia",
            "url": "https://dumps.wikimedia.org/dewiki/20260701/",
            "dumpDate": "2026-07-01",
            "license": "CC-BY-SA-4.0",
            "noticeFile": "NOTICE.md",
            "revisionCoverage": "per-article",
        },
        "database": {
            "file": "pack.sqlite",
            "sha256": digest,
            "sizeBytes": db.stat().st_size,
            "articleCount": 2,
        },
        "retrieval": {"method": "fts5-title-alias-lead"},
    }
    (tmp_path / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    state = load_pack_state(db, verify_content=True)
    monkeypatch.setattr(server, "DB_PATH", db)
    monkeypatch.setattr(server, "PACK_STATE", state)

    with db.open("r+b") as handle:
        handle.seek(100)
        original = handle.read(1)
        handle.seek(100)
        handle.write(bytes([original[0] ^ 0x01]))

    with pytest.raises(HTTPException) as exc:
        server.manifest_v1()

    assert exc.value.status_code == 409
    assert "knowledge-pack-drift" in exc.value.detail


def test_v1_search_refuses_legacy_database_before_retrieval():
    with pytest.raises(HTTPException) as exc:
        server.search_v1(q="albert einstein")

    assert exc.value.status_code == 409
    assert "knowledge-pack-required" in exc.value.detail
