"""Kleine Vertrags- und Read-only-Tests ohne echte Wikipedia-Datenbank."""
import os
import sqlite3
import sys

import pytest

os.environ.setdefault("HOSHI_WIKI_DB_PATH", "/dev/null")
sys.path.insert(0, os.path.dirname(__file__))

import server  # noqa: E402


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
