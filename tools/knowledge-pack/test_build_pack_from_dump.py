"""Synthetische Verträge für den direkten, releasefähigen Dump→Pack-Pfad."""

import bz2
import hashlib
import importlib.util
import json
import sqlite3
import sys
from collections import namedtuple
from pathlib import Path

import pytest


HERE = Path(__file__).resolve().parent


def _module(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


builder = _module("knowledge_pack_dump_builder", "build_pack_from_dump.py")
verifier = _module("knowledge_pack_dump_verifier", "verify_pack.py")


def _page(
    *,
    title="Albert Einstein",
    namespace="0",
    page_id="42",
    revision_id="123456789",
    timestamp="2026-06-30T12:34:56Z",
    text=None,
    redirect=None,
):
    content = text or (
        "{{Personendaten|NAME=Einstein, Albert}}\n"
        "'''Albert Einstein''' war ein [[deutsch]]er [[Physiker]]. "
        "<ref>Eine Referenz</ref> Er entwickelte die Relativitätstheorie.\n"
        "== Leben ==\nDieser Abschnitt gehört nicht in den Lead."
    )
    redirect_xml = (
        f'<redirect title="{redirect}" />' if redirect is not None else ""
    )
    escaped = (
        content.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )
    return (
        "<page>"
        f"<title>{title}</title><ns>{namespace}</ns><id>{page_id}</id>"
        f"{redirect_xml}"
        "<revision>"
        f"<id>{revision_id}</id><timestamp>{timestamp}</timestamp>"
        f'<text xml:space="preserve">{escaped}</text>'
        "</revision>"
        "</page>"
    )


def _dump_bytes(*pages):
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<mediawiki xmlns="http://www.mediawiki.org/xml/export-0.11/">'
        + "".join(pages)
        + "</mediawiki>"
    ).encode("utf-8")
    return bz2.compress(xml)


def _spec_and_cache(tmp_path, content):
    sha1 = hashlib.sha1(content, usedforsecurity=False).hexdigest()
    spec = builder.DumpSpec.create("20260701", len(content), sha1)
    cache = tmp_path / "cache"
    cache.mkdir(exist_ok=True)
    (cache / spec.filename).write_bytes(content)
    return spec, cache


def _dumpstatus_bytes(spec):
    return json.dumps(
        {
            "version": "1.0",
            "jobs": {
                "articlesmultistreamdumprecombine": {
                    "status": "done",
                    "updated": "2026-07-02 12:00:00",
                    "files": {
                        spec.filename: {
                            "size": spec.expected_size,
                            "url": f"/dewiki/{spec.dump_date}/{spec.filename}",
                            "sha1": spec.expected_sha1,
                        }
                    },
                }
            },
        },
        sort_keys=True,
    ).encode("utf-8")


def _status_opener(spec):
    raw = _dumpstatus_bytes(spec)

    def open_status(request, timeout):
        assert request.full_url == spec.dumpstatus_url
        return _Response(raw, spec.dumpstatus_url, len(raw))

    return open_status


def _selection(path, title="Albert Einstein", **extra):
    item = {"title": title, "aliases": [], **extra}
    path.write_text(json.dumps(item, ensure_ascii=False) + "\n", encoding="utf-8")


def _build(tmp_path, *, pages=None, selection_title="Albert Einstein"):
    content = _dump_bytes(*(pages or [_page()]))
    spec, cache = _spec_and_cache(tmp_path, content)
    selection = tmp_path / "selection.jsonl"
    _selection(selection, selection_title)
    output = tmp_path / "de-core"
    manifest = builder.build_pack_from_dump(
        spec=spec,
        cache_dir=cache,
        selection_path=selection,
        output_dir=output,
        pack_id="hoshi-wikipedia-de-core-test",
        created_at="2026-07-01T20:00:00Z",
        opener=_status_opener(spec),
    )
    return output, manifest


def _source_dump_path(tmp_path, manifest):
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )
    return tmp_path / "cache" / spec.filename


def test_direct_builder_creates_release_candidate_with_dump_bound_provenance(tmp_path):
    output, manifest = _build(tmp_path)

    assert manifest["releaseStatus"] == "release-candidate"
    assert manifest["source"]["url"].endswith(
        "/dewiki-20260701-pages-articles-multistream.xml.bz2"
    )
    assert manifest["source"]["dumpStatusUrl"].endswith("/dumpstatus.json")
    assert manifest["source"]["revisionCount"] == 1
    assert manifest["source"]["revisionTimestampCount"] == 1
    assert len(manifest["source"]["dump"]["sha256"]) == 64
    assert len(manifest["source"]["noticeSha256"]) == 64
    assert manifest["builder"]["selection"] == "explicit-public-title-list"
    assert manifest["builder"]["selectionFile"] == "selection.jsonl"
    assert len(manifest["builder"]["selectionSha256"]) == 64
    assert len(manifest["builder"]["logicalRecordsSha256"]) == 64
    assert manifest["builder"]["parameters"] == {
        "leadChars": 1600,
        "zstdLevel": 10,
    }
    assert manifest["builder"]["toolchain"] == builder.toolchain_contract()
    assert manifest["builder"]["modelDerivedFeatures"] == []
    bundled_status = json.loads((output / "dumpstatus.json").read_text(encoding="utf-8"))
    assert set(bundled_status) == {"schema", "job", "status", "updated", "file"}
    assert set(bundled_status["file"]) == {"url", "size", "sha1"}

    result = verifier.verify_pack(output / "manifest.json", fast=False)
    assert result["status"] == "ok"
    assert result["artifactVerified"] is True
    assert result["sourceAuthorityVerified"] is False
    assert result["sourceDumpBytesVerified"] is False
    assert result["logicalContentVerified"] is False
    assert result["ftsIntegrityVerified"] is False
    assert result["byteRebuildVerified"] is False
    assert result["releaseEligible"] is False
    assert result["releaseStatus"] == "release-candidate"
    assert verifier.verify_pack(output / "manifest.json", fast=True)[
        "releaseEligible"
    ] is False
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )
    release_result = verifier.verify_pack(
        output / "manifest.json",
        fast=False,
        verify_source_online=True,
        source_dump_path=_source_dump_path(tmp_path, manifest),
        source_opener=_status_opener(spec),
    )
    assert release_result["sourceAuthorityVerified"] is True
    assert release_result["sourceDumpBytesVerified"] is True
    assert release_result["logicalContentVerified"] is True
    assert release_result["ftsIntegrityVerified"] is True
    assert release_result["byteRebuildVerified"] is True
    assert (
        release_result["canonicalDatabaseSha256"]
        == manifest["database"]["sha256"]
    )
    assert (
        release_result["logicalRecordsSha256"]
        == manifest["builder"]["logicalRecordsSha256"]
    )
    assert release_result["releaseEligible"] is True
    assert release_result["sha256"] == manifest["database"]["sha256"]
    assert str(tmp_path) not in json.dumps(release_result)

    with sqlite3.connect(output / "pack.sqlite") as conn:
        row = conn.execute(
            "SELECT a.id,a.title,s.source_url,s.source_revision_id,"
            "s.source_revision_timestamp,c.classification "
            "FROM articles a JOIN article_sources s ON s.article_id=a.id "
            "JOIN classifications c ON c.article_id=a.id"
        ).fetchone()
    assert row[0:5] == (
        42,
        "Albert Einstein",
        "https://de.wikipedia.org/w/index.php?oldid=123456789",
        "123456789",
        "2026-06-30T12:34:56Z",
    )
    assert "Relativitätstheorie" in row[5]
    assert "Personendaten" not in row[5]
    assert "Dieser Abschnitt" not in row[5]

    notice = (output / "NOTICE.md").read_text(encoding="utf-8")
    for token in ("CC BY-SA 4.0", "Attribution", "Modifications", "ShareAlike"):
        assert token in notice


def test_dump_contract_is_canonical_and_23gb_preflight_has_headroom(tmp_path):
    spec = builder.DumpSpec.create(
        "20260701",
        8_191_590_940,
        "78b9aefc316c07ffe7c6044aabb16be2759b49ec",
    )
    assert spec.url == (
        "https://dumps.wikimedia.org/dewiki/20260701/"
        "dewiki-20260701-pages-articles-multistream.xml.bz2"
    )
    assert spec.dumpstatus_url == (
        "https://dumps.wikimedia.org/dewiki/20260701/dumpstatus.json"
    )

    Usage = namedtuple("Usage", "total used free")
    usage_23gb = lambda _: Usage(100_000_000_000, 77_000_000_000, 23_000_000_000)
    observed = builder.preflight_disk(
        spec=spec,
        cache_dir=tmp_path / "cache",
        output_parent=tmp_path / "packs",
        dump_present=False,
        max_pack_bytes=512 * 1024 * 1024,
        disk_usage=usage_23gb,
    )
    assert observed

    usage_too_small = lambda _: Usage(10_000_000_000, 1_000_000_000, 9_000_000_000)
    with pytest.raises(ValueError, match="Zu wenig freier Speicher"):
        builder.preflight_disk(
            spec=spec,
            cache_dir=tmp_path / "cache",
            output_parent=tmp_path / "packs",
            dump_present=False,
            max_pack_bytes=512 * 1024 * 1024,
            disk_usage=usage_too_small,
        )


def test_dumpstatus_must_authoritatively_bind_size_sha1_and_canonical_url(tmp_path):
    content = _dump_bytes(_page())
    spec, _ = _spec_and_cache(tmp_path, content)
    valid = json.loads(_dumpstatus_bytes(spec))
    valid["jobs"]["articlesmultistreamdumprecombine"]["files"][spec.filename][
        "sha1"
    ] = "0" * 40
    wrong = json.dumps(valid).encode("utf-8")

    with pytest.raises(ValueError, match="dumpstatus"):
        builder.fetch_dumpstatus_evidence(
            spec,
            opener=lambda request, timeout: _Response(
                wrong,
                spec.dumpstatus_url,
                len(wrong),
            ),
        )
    with pytest.raises(ValueError, match="umgeleitet"):
        builder.fetch_dumpstatus_evidence(
            spec,
            opener=lambda request, timeout: _Response(
                _dumpstatus_bytes(spec),
                "https://mirror.invalid/dumpstatus.json",
            ),
        )


def test_default_authority_openers_disable_proxy_and_redirect(monkeypatch):
    captured = []

    class _CapturedOpener:
        def open(self, *_args, **_kwargs):
            raise AssertionError("Netzwerk darf in diesem Test nicht geöffnet werden")

    def build_opener(*handlers):
        captured.append(handlers)
        return _CapturedOpener()

    monkeypatch.setattr(builder.urllib.request, "build_opener", build_opener)
    builder._network_opener()
    monkeypatch.setattr(verifier.urllib.request, "build_opener", build_opener)
    verifier._authority_opener()

    assert len(captured) == 2
    for handlers in captured:
        proxy = next(
            handler
            for handler in handlers
            if isinstance(handler, builder.urllib.request.ProxyHandler)
        )
        assert proxy.proxies == {}
        assert any(
            isinstance(
                handler,
                (
                    builder._NoRedirectHandler,
                    verifier._NoRedirectHandler,
                ),
            )
            for handler in handlers
        )


class _Response:
    def __init__(self, content, url, announced_size=None):
        self._content = content
        self._offset = 0
        self._url = url
        self.headers = {}
        if announced_size is not None:
            self.headers["Content-Length"] = str(announced_size)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def geturl(self):
        return self._url

    def read(self, size=-1):
        if size is None or size < 0:
            size = len(self._content) - self._offset
        chunk = self._content[self._offset : self._offset + size]
        self._offset += len(chunk)
        return chunk


def test_download_is_hash_checked_atomic_and_never_overwrites(tmp_path):
    content = _dump_bytes(_page())
    spec = builder.DumpSpec.create(
        "20260701",
        len(content),
        hashlib.sha1(content, usedforsecurity=False).hexdigest(),
    )
    cache = tmp_path / "cache"
    calls = []

    def opener(request, timeout):
        calls.append((request.full_url, timeout))
        return _Response(content, spec.url, len(content))

    artifact = builder.ensure_dump(spec, cache, opener=opener)
    assert artifact.path.read_bytes() == content
    assert artifact.sha256 == hashlib.sha256(content).hexdigest()
    assert calls == [(spec.url, 60)]
    assert not list(cache.glob("*.part"))

    existing = artifact.path
    existing.write_bytes(b"do-not-overwrite")
    with pytest.raises(ValueError, match="Dump-Größe"):
        builder.ensure_dump(spec, cache, opener=opener)
    assert existing.read_bytes() == b"do-not-overwrite"

    bad_cache = tmp_path / "bad-cache"
    bad_spec = builder.DumpSpec.create("20260701", len(content), "0" * 40)
    with pytest.raises(ValueError, match="Dump-SHA-1"):
        builder.ensure_dump(bad_spec, bad_cache, opener=opener)
    assert not (bad_cache / bad_spec.filename).exists()
    assert not list(bad_cache.glob("*.part"))


@pytest.mark.parametrize(
    ("pages", "selection_title", "message"),
    [
        ([_page(title="Einstein", redirect="Albert Einstein")], "Einstein", "Redirect"),
        ([_page(title="Kategorie:Physik", namespace="14")], "Kategorie:Physik", "Main namespace"),
        ([_page()], "Marie Curie", "fehlen im Dump"),
    ],
)
def test_redirect_namespace_and_missing_selection_fail_without_output(
    tmp_path, pages, selection_title, message
):
    content = _dump_bytes(*pages)
    spec, cache = _spec_and_cache(tmp_path, content)
    selection = tmp_path / "selection.jsonl"
    _selection(selection, selection_title)
    output = tmp_path / "must-not-exist"

    with pytest.raises(ValueError, match=message):
        builder.build_pack_from_dump(
            spec=spec,
            cache_dir=cache,
            selection_path=selection,
            output_dir=output,
            pack_id="hoshi-wikipedia-de-core-test",
            created_at="2026-07-01T20:00:00Z",
            opener=_status_opener(spec),
        )
    assert not output.exists()


def test_selection_must_be_public_and_revision_metadata_cannot_be_supplied(tmp_path):
    content = _dump_bytes(_page())
    spec, cache = _spec_and_cache(tmp_path, content)
    selection = tmp_path / "selection.jsonl"
    _selection(selection, sourceRevisionId="caller-value")

    with pytest.raises(ValueError, match="nicht-öffentliche"):
        builder.build_pack_from_dump(
            spec=spec,
            cache_dir=cache,
            selection_path=selection,
            output_dir=tmp_path / "must-not-exist",
            pack_id="hoshi-wikipedia-de-core-test",
            created_at="2026-07-01T20:00:00Z",
            opener=_status_opener(spec),
        )


def test_pack_output_is_no_overwrite_and_release_timestamps_are_verified(tmp_path):
    output, _ = _build(tmp_path)
    original_manifest = (output / "manifest.json").read_bytes()

    with pytest.raises(ValueError, match="überschreiben verboten"):
        _build(tmp_path)
    assert (output / "manifest.json").read_bytes() == original_manifest

    with sqlite3.connect(output / "pack.sqlite") as conn:
        conn.execute(
            "UPDATE article_sources SET source_revision_timestamp=?",
            ("not-a-timestamp",),
        )
    with pytest.raises(Exception, match="source_revision_timestamp"):
        verifier.verify_pack(output / "manifest.json", fast=False)


def test_release_verifier_rejects_missing_dump_binding(tmp_path):
    output, _ = _build(tmp_path)
    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    del manifest["source"]["dump"]["sha256"]
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(Exception, match="source.dump.sha256"):
        verifier.verify_pack(manifest_path, fast=False)


def test_release_manifest_rejects_unexpected_fields_in_public_distribution(tmp_path):
    output, _ = _build(tmp_path)
    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["localBuildPath"] = "/private/path/that-must-not-ship"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(Exception, match="Release-Schema"):
        verifier.verify_pack(manifest_path, fast=False)


def test_release_directory_rejects_unexpected_sidecar_file(tmp_path):
    output, _ = _build(tmp_path)
    (output / "local-notes.txt").write_text("must not ship", encoding="utf-8")

    with pytest.raises(Exception, match="öffentlichen Pack-Dateien"):
        verifier.verify_pack(output / "manifest.json", fast=False)


def test_release_snapshot_rejects_oversized_file_before_copy(tmp_path, monkeypatch):
    output, _ = _build(tmp_path)
    db_path = output / "pack.sqlite"
    monkeypatch.setitem(
        verifier._RELEASE_FILE_MAX_BYTES,
        "pack.sqlite",
        db_path.stat().st_size - 1,
    )

    with pytest.raises(Exception, match="überschreitet das Größenlimit"):
        verifier.verify_pack(output / "manifest.json", fast=False)


def test_release_verifier_rejects_tampered_bundled_or_online_dumpstatus(tmp_path):
    output, manifest = _build(tmp_path)
    status_path = output / "dumpstatus.json"
    status = json.loads(status_path.read_text(encoding="utf-8"))
    status["file"]["sha1"] = "0" * 40
    status_path.write_text(json.dumps(status), encoding="utf-8")

    with pytest.raises(Exception, match="dumpstatus"):
        verifier.verify_pack(output / "manifest.json", fast=False)

    second = tmp_path / "second"
    second.mkdir()
    output2, manifest2 = _build(second)
    spec = builder.DumpSpec.create(
        "20260701",
        manifest2["source"]["dump"]["sizeBytes"],
        manifest2["source"]["dump"]["sha1"],
    )
    wrong = json.loads(_dumpstatus_bytes(spec))
    wrong["jobs"]["articlesmultistreamdumprecombine"]["files"][spec.filename][
        "size"
    ] += 1
    raw = json.dumps(wrong).encode("utf-8")
    with pytest.raises(Exception, match="Online-dumpstatus"):
        verifier.verify_pack(
            output2 / "manifest.json",
            fast=False,
            verify_source_online=True,
            source_opener=lambda request, timeout: _Response(
                raw,
                spec.dumpstatus_url,
            ),
        )


def test_release_verifier_rejects_rehashed_extra_dumpstatus_payload(tmp_path):
    output, _ = _build(tmp_path)
    status_path = output / "dumpstatus.json"
    status = json.loads(status_path.read_text(encoding="utf-8"))
    status["privatePayload"] = "must-not-ship"
    status_path.write_text(json.dumps(status), encoding="utf-8")
    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["source"]["dumpStatus"]["sha256"] = hashlib.sha256(
        status_path.read_bytes()
    ).hexdigest()
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(Exception, match="dumpstatus.json.*Release-Schema"):
        verifier.verify_pack(manifest_path, fast=False)


def test_release_verifier_rejects_payload_in_dumpstatus_timestamp(tmp_path):
    output, _ = _build(tmp_path)
    status_path = output / "dumpstatus.json"
    status = json.loads(status_path.read_text(encoding="utf-8"))
    status["updated"] = "2026-07-02 12:00:00 PRIVATE"
    canonicalized = (
        json.dumps(
            status,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    )
    status_path.write_text(canonicalized, encoding="utf-8")
    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["source"]["dumpStatus"]["updated"] = status["updated"]
    manifest["source"]["dumpStatus"]["sha256"] = hashlib.sha256(
        status_path.read_bytes()
    ).hexdigest()
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(Exception, match="YYYY-MM-DD HH:MM:SS"):
        verifier.verify_pack(manifest_path, fast=False)


def test_release_verifier_rejects_fast_online_claim(tmp_path):
    output, manifest = _build(tmp_path)
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="vollständige DB-Prüfung"):
        verifier.verify_pack(
            output / "manifest.json",
            fast=True,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


def test_release_verifier_rejects_forged_database_despite_rehashed_manifest(tmp_path):
    output, manifest = _build(tmp_path)
    db_path = output / "pack.sqlite"
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            "UPDATE classifications SET classification=?",
            ("WIKIPEDIA frei erfundener Inhalt",),
        )
    manifest_path = output / "manifest.json"
    forged = json.loads(manifest_path.read_text(encoding="utf-8"))
    forged["database"]["sizeBytes"] = db_path.stat().st_size
    forged["database"]["sha256"] = hashlib.sha256(db_path.read_bytes()).hexdigest()
    manifest_path.write_text(json.dumps(forged), encoding="utf-8")
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="logischer Wiederaufbau"):
        verifier.verify_pack(
            manifest_path,
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


def test_release_verifier_rejects_unbound_source_sha256(tmp_path):
    output, manifest = _build(tmp_path)
    manifest_path = output / "manifest.json"
    forged = json.loads(manifest_path.read_text(encoding="utf-8"))
    forged["source"]["dump"]["sha256"] = "0" * 64
    manifest_path.write_text(json.dumps(forged), encoding="utf-8")
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="Quelldump.*SHA-256"):
        verifier.verify_pack(
            manifest_path,
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


def test_release_verifier_rejects_noncanonical_selection_even_if_rehashed(tmp_path):
    output, manifest = _build(tmp_path)
    selection_path = output / "selection.jsonl"
    selection_path.write_text(
        ' { "aliases": [], "title": "Albert Einstein" }\n',
        encoding="utf-8",
    )
    manifest_path = output / "manifest.json"
    forged = json.loads(manifest_path.read_text(encoding="utf-8"))
    forged["builder"]["selectionSha256"] = hashlib.sha256(
        selection_path.read_bytes()
    ).hexdigest()
    manifest_path.write_text(json.dumps(forged), encoding="utf-8")
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="kanonische Builder-Ausgabe"):
        verifier.verify_pack(
            manifest_path,
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


def test_release_verifier_rejects_fts_index_drift_hidden_by_external_content(tmp_path):
    output, manifest = _build(tmp_path)
    db_path = output / "pack.sqlite"
    with sqlite3.connect(db_path) as conn:
        row = conn.execute(
            "SELECT id,classification FROM classifications LIMIT 1"
        ).fetchone()
        conn.execute(
            "INSERT INTO classifications_fts("
            "classifications_fts,rowid,classification"
            ") VALUES('delete',?,?)",
            row,
        )
    manifest_path = output / "manifest.json"
    updated = json.loads(manifest_path.read_text(encoding="utf-8"))
    updated["database"]["sizeBytes"] = db_path.stat().st_size
    updated["database"]["sha256"] = hashlib.sha256(db_path.read_bytes()).hexdigest()
    manifest_path.write_text(json.dumps(updated), encoding="utf-8")
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="FTS5"):
        verifier.verify_pack(
            manifest_path,
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


def test_release_verifier_rejects_rehashed_but_noncanonical_notice(tmp_path):
    output, manifest = _build(tmp_path)
    notice_path = output / "NOTICE.md"
    notice_path.write_text(
        notice_path.read_text(encoding="utf-8") + "\nLocal alteration.\n",
        encoding="utf-8",
    )
    manifest_path = output / "manifest.json"
    updated = json.loads(manifest_path.read_text(encoding="utf-8"))
    updated["source"]["noticeSha256"] = hashlib.sha256(
        notice_path.read_bytes()
    ).hexdigest()
    manifest_path.write_text(json.dumps(updated), encoding="utf-8")
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="NOTICE.*gebundenen Release-Builders"):
        verifier.verify_pack(
            manifest_path,
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


@pytest.mark.parametrize(
    "tamper",
    ("extra-column", "appended-bytes", "reserved-header-bytes"),
)
def test_release_verifier_rejects_noncanonical_sqlite_container(tmp_path, tamper):
    output, manifest = _build(tmp_path)
    db_path = output / "pack.sqlite"
    if tamper == "extra-column":
        with sqlite3.connect(db_path) as conn:
            conn.execute("ALTER TABLE articles ADD COLUMN local_note TEXT")
            conn.execute(
                "UPDATE articles SET local_note='must not ship' WHERE id=42"
            )
    elif tamper == "appended-bytes":
        with db_path.open("ab") as handle:
            handle.write(b"must-not-ship")
    else:
        with db_path.open("r+b") as handle:
            handle.seek(72)
            handle.write(b"PRIVATE_PAYLOAD_1234")
    manifest_path = output / "manifest.json"
    updated = json.loads(manifest_path.read_text(encoding="utf-8"))
    updated["database"]["sizeBytes"] = db_path.stat().st_size
    updated["database"]["sha256"] = hashlib.sha256(db_path.read_bytes()).hexdigest()
    manifest_path.write_text(json.dumps(updated), encoding="utf-8")
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(
        Exception,
        match=(
            "SQLite-Schema|außerhalb der SQLite-Seiten|"
            "nicht bytegleich zur frischen Ausgabe"
        ),
    ):
        verifier.verify_pack(
            manifest_path,
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


def test_release_verifier_requires_exact_pinned_toolchain(tmp_path):
    output, manifest = _build(tmp_path)
    manifest_path = output / "manifest.json"
    changed = json.loads(manifest_path.read_text(encoding="utf-8"))
    changed["builder"]["toolchain"]["sqliteVersion"] = "0.0-forged"
    manifest_path.write_text(json.dumps(changed), encoding="utf-8")
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="Toolchain"):
        verifier.verify_pack(
            manifest_path,
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )


def test_release_verifier_detects_pack_file_swap_during_proof(tmp_path, monkeypatch):
    output, manifest = _build(tmp_path)
    original_validate = verifier._validate_release_manifest
    original_selection = output / "selection.jsonl"

    def validate_and_swap(*args, **kwargs):
        result = original_validate(*args, **kwargs)
        original_selection.write_text(
            '{"aliases":[],"title":"Marie Curie"}\n',
            encoding="utf-8",
        )
        return result

    monkeypatch.setattr(
        verifier,
        "_validate_release_manifest",
        validate_and_swap,
    )
    spec = builder.DumpSpec.create(
        "20260701",
        manifest["source"]["dump"]["sizeBytes"],
        manifest["source"]["dump"]["sha1"],
    )

    with pytest.raises(Exception, match="änderte sich während der Verifikation"):
        verifier.verify_pack(
            output / "manifest.json",
            fast=False,
            verify_source_online=True,
            source_dump_path=_source_dump_path(tmp_path, manifest),
            source_opener=_status_opener(spec),
        )
