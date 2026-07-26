"""Vertragstests für manifestierte Packs und ehrlichen Legacy-Betrieb."""

import hashlib
import json

import pytest

from pack_manifest import ManifestError, load_pack_state, parse_manifest


def _manifest(db, **overrides):
    (db.parent / "NOTICE.md").write_text("CC BY-SA 4.0\n", encoding="utf-8")
    root = {
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
            "file": db.name,
            "sha256": "a" * 64,
            "sizeBytes": db.stat().st_size,
            "articleCount": 2,
        },
        "retrieval": {"method": "fts5-title-alias-lead"},
    }
    root.update(overrides)
    return root


def test_valid_manifest_is_loaded_and_public_summary_excludes_builder_noise(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"public-pack")
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(_manifest(db)), encoding="utf-8")

    state = load_pack_state(db)

    assert state.status == "manifest-valid-metadata-only"
    assert state.pack_id == "hoshi-wikipedia-de-test"
    summary = state.public_summary()
    assert summary["source"]["revisionCoverage"] == "per-article"
    assert summary["database"]["sizeBytes"] == len(b"public-pack")
    assert "builder" not in summary
    assert "manifestPath" not in summary
    assert summary["verification"] == {
        "contentSha256Verified": False,
        "actualDatabaseSha256": None,
    }


def test_public_summary_exposes_bound_dump_hash_without_local_paths(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"public-pack")
    manifest = _manifest(db)
    manifest["source"]["dump"] = {
        "sizeBytes": 8_191_590_940,
        "sha1": "1" * 40,
        "sha256": "2" * 64,
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    summary = parse_manifest(manifest_path, db).public_summary()

    assert summary["source"]["dump"] == {
        "sizeBytes": 8_191_590_940,
        "sha1": "1" * 40,
        "sha256": "2" * 64,
    }
    assert str(tmp_path) not in json.dumps(summary)


def test_public_summary_exposes_release_status_for_benchmark_binding(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"public-pack")
    manifest = _manifest(db, releaseStatus="release-candidate")
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    summary = parse_manifest(manifest_path, db).public_summary()

    assert summary["releaseStatus"] == "release-candidate"


def test_absent_manifest_keeps_legacy_startable_but_visible(tmp_path):
    db = tmp_path / "articles.db"
    db.write_bytes(b"legacy")

    state = load_pack_state(db, require_manifest=False)

    assert state.status == "legacy-unmanifested"
    assert state.manifest is None
    assert state.public_summary()["packId"] is None


def test_required_or_explicit_manifest_must_exist(tmp_path):
    db = tmp_path / "articles.db"
    db.write_bytes(b"legacy")

    with pytest.raises(ManifestError, match="erforderlich"):
        load_pack_state(db, require_manifest=True)
    with pytest.raises(ManifestError, match="erforderlich"):
        load_pack_state(db, explicit_manifest=str(tmp_path / "missing.json"))


def test_manifest_rejects_wrong_database_and_size(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"123")
    manifest_path = tmp_path / "manifest.json"
    wrong_file = _manifest(db)
    wrong_file["database"]["file"] = "other.sqlite"
    manifest_path.write_text(json.dumps(wrong_file), encoding="utf-8")
    with pytest.raises(ManifestError, match="exakt pack.sqlite"):
        parse_manifest(manifest_path, db)

    wrong_size = _manifest(db)
    wrong_size["database"]["sizeBytes"] = 99
    manifest_path.write_text(json.dumps(wrong_size), encoding="utf-8")
    with pytest.raises(ManifestError, match="DB-Größe"):
        parse_manifest(manifest_path, db)


def test_manifest_rejects_unsupported_schema_and_invalid_hash(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"123")
    manifest_path = tmp_path / "manifest.json"
    wrong_schema = _manifest(db, schemaVersion=2)
    manifest_path.write_text(json.dumps(wrong_schema), encoding="utf-8")
    with pytest.raises(ManifestError, match="nicht unterstützt"):
        parse_manifest(manifest_path, db)

    bad_hash = _manifest(db)
    bad_hash["database"]["sha256"] = "not-a-hash"
    manifest_path.write_text(json.dumps(bad_hash), encoding="utf-8")
    with pytest.raises(ManifestError, match="SHA-256"):
        parse_manifest(manifest_path, db)

    bad_dump_hash = _manifest(db)
    bad_dump_hash["source"]["dump"] = {
        "sizeBytes": 1,
        "sha1": "1" * 40,
        "sha256": "not-a-hash",
    }
    manifest_path.write_text(json.dumps(bad_dump_hash), encoding="utf-8")
    with pytest.raises(ManifestError, match="source.dump.sha256"):
        parse_manifest(manifest_path, db)

    invalid_release = _manifest(db, releaseStatus="trust-me")
    manifest_path.write_text(json.dumps(invalid_release), encoding="utf-8")
    with pytest.raises(ManifestError, match="releaseStatus"):
        parse_manifest(manifest_path, db)


def test_manifest_rejects_percent_encoded_database_name(tmp_path):
    db = tmp_path / "safe%2F..%2Fpack.sqlite"
    db.write_bytes(b"123")
    manifest_path = tmp_path / "manifest.json"
    manifest = _manifest(db)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(ManifestError, match="exakt pack.sqlite"):
        parse_manifest(manifest_path, db)


def test_opt_in_content_hash_is_publicly_visible_and_path_free(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"public-pack")
    manifest = _manifest(db)
    digest = hashlib.sha256(db.read_bytes()).hexdigest()
    manifest["database"]["sha256"] = digest
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    state = load_pack_state(db, verify_content=True)

    assert state.status == "manifest-content-verified"
    assert state.content_sha256_verified is True
    assert state.actual_database_sha256 == digest
    assert state.database_fingerprint is not None
    summary = state.public_summary()
    assert summary["verification"] == {
        "contentSha256Verified": True,
        "actualDatabaseSha256": digest,
    }
    assert str(tmp_path) not in json.dumps(summary)


def test_content_hash_rejects_same_size_mutation_and_sqlite_sidecar(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"public-pack")
    manifest = _manifest(db)
    manifest["database"]["sha256"] = hashlib.sha256(db.read_bytes()).hexdigest()
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    db.write_bytes(b"PUBLIC-pack")
    with pytest.raises(ManifestError, match="DB-SHA-256"):
        load_pack_state(db, verify_content=True)

    db.write_bytes(b"public-pack")
    (tmp_path / "pack.sqlite-wal").write_bytes(b"not allowed")
    with pytest.raises(ManifestError, match="SQLite-Sidecars"):
        load_pack_state(db, verify_content=True)


def test_verified_state_rejects_database_drift_after_ready(tmp_path):
    db = tmp_path / "pack.sqlite"
    db.write_bytes(b"public-pack")
    manifest = _manifest(db)
    manifest["database"]["sha256"] = hashlib.sha256(db.read_bytes()).hexdigest()
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    state = load_pack_state(db, verify_content=True)

    db.write_bytes(b"PUBLIC-pack")

    with pytest.raises(ManifestError, match="änderte sich nach dem Start"):
        state.assert_database_unchanged(db)


def test_content_verification_requires_manifest(tmp_path):
    db = tmp_path / "articles.db"
    db.write_bytes(b"legacy")

    with pytest.raises(ManifestError, match="erforderlich"):
        load_pack_state(db, require_manifest=False, verify_content=True)
