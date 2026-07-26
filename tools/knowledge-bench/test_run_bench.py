"""Metrik-, Schema- und Gate-Tests ohne laufende Bridge."""

import hashlib
import importlib.util
import json
import os
import stat
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).with_name("run_bench.py")
SPEC = importlib.util.spec_from_file_location("knowledge_bench_runner", MODULE_PATH)
bench = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules["knowledge_bench_runner"] = bench
SPEC.loader.exec_module(bench)


def _query(
    query_id: str,
    *,
    answerable: bool,
    title: str = "",
    evidence: tuple[str, ...] = (),
    exact: bool = False,
) -> bench.Query:
    passages = (
        (bench.GoldPassage(title=title, evidence=evidence),)
        if answerable
        else ()
    )
    return bench.Query(
        id=query_id,
        split="holdout",
        query=f"Frage {query_id}",
        search_query=title.casefold() if title else query_id,
        answerable=answerable,
        gold_passages=passages,
        exact_title_required=exact,
        topic_group=f"topic-{query_id}",
        stratum="test",
    )


def _queries() -> list[bench.Query]:
    return [
        _query(
            "a",
            answerable=True,
            title="Albert Einstein",
            evidence=("Physiker",),
        ),
        _query(
            "b",
            answerable=True,
            title="Algebra",
            evidence=("Teilgebiet",),
            exact=True,
        ),
        _query("c", answerable=False),
        _query("d", answerable=False),
    ]


def test_metrics_separate_recall_false_grounding_and_errors():
    probes = [
        bench.Probe("a", 10.0, 8, ("Albert Einstein",), (-20.0,), 1, 100, None),
        bench.Probe(
            "b",
            20.0,
            18,
            ("Algebra (Band)", "Algebra"),
            (-12.0, -10.0),
            2,
            200,
            None,
        ),
        bench.Probe("c", 30.0, 28, (), (), None, 0, None),
        bench.Probe("d", 40.0, 38, ("Strompreis",), (-4.0,), None, 50, None),
    ]

    result = bench.metrics(_queries(), probes)

    assert result["recallAt1"] == 0.5
    assert result["recallAt3"] == 1.0
    assert result["passageRecallAt1"] == 0.5
    assert result["passageRecallAt3"] == 1.0
    assert result["falseRetrievalCandidateRate"] == 0.5
    assert result["p95WallMs"] == 40.0
    assert result["errors"] == 0


def test_evidence_must_occur_in_its_associated_gold_title():
    query = _query(
        "bound",
        answerable=True,
        title="Albert Einstein",
        evidence=("theoretischer Physiker",),
    )
    hits = [
        {
            "title": "Physik",
            "extract": "Albert Einstein war ein theoretischer Physiker.",
        },
        {
            "title": "Albert Einstein",
            "extract": "Er entwickelte die Relativitätstheorie.",
        },
    ]

    assert bench.evidence_rank(query, hits) is None

    hits[1]["summary"] = "Ein deutscher theoretischer Physiker."
    assert bench.evidence_rank(query, hits) == 2


def test_gate_requires_real_holdout_and_both_classes():
    baseline = {
        "n": 32,
        "answerableN": 21,
        "noAnswerN": 11,
        "errors": 0,
        "recallAt3": 0.50,
        "passageRecallAt3": 0.50,
        "falseRetrievalCandidateRate": 0.04,
        "p95WallMs": 100.0,
    }
    candidate = {
        **baseline,
        "recallAt3": 0.65,
        "passageRecallAt3": 0.65,
        "p95WallMs": 220.0,
    }

    result = bench.gate(
        baseline,
        candidate,
        min_recall_gain=0.10,
        max_added_p95_ms=150.0,
        minimum_n=20,
        minimum_answerable_n=20,
        minimum_unanswerable_n=10,
        holdout_only=True,
    )

    assert result["passed"]
    failed = bench.gate(
        baseline,
        {**candidate, "noAnswerN": 9},
        min_recall_gain=0.10,
        max_added_p95_ms=150.0,
        minimum_n=20,
        minimum_answerable_n=20,
        minimum_unanswerable_n=10,
        holdout_only=False,
    )
    assert not failed["passed"]
    assert not failed["checks"]["holdoutOnly"]
    assert not failed["checks"]["minimumNoAnswerSize"]


def test_query_reader_migrates_unambiguous_v1(tmp_path):
    path = tmp_path / "legacy.jsonl"
    path.write_text(
        json.dumps(
            {
                "id": "legacy",
                "split": "dev",
                "query": "Wer war Marie Curie?",
                "answerable": True,
                "goldTitles": ["Marie Curie"],
                "goldEvidence": ["Physikerin"],
                "exactTitleRequired": False,
            }
        )
        + "\n",
        encoding="utf-8",
    )

    query = bench.read_queries(path, "dev")[0]

    assert query.source_schema_version == 1
    assert query.gold_passages == (
        bench.GoldPassage("Marie Curie", ("Physikerin",)),
    )


def test_query_reader_rejects_ambiguous_v1_ground_truth(tmp_path):
    path = tmp_path / "queries.jsonl"
    path.write_text(
        json.dumps(
            {
                "id": "bad",
                "split": "holdout",
                "query": "Wer ist gemeint?",
                "answerable": True,
                "goldTitles": ["Artikel A", "Artikel B"],
                "goldEvidence": ["gemeinsamer Satz"],
            }
        )
        + "\n",
        encoding="utf-8",
    )

    with pytest.raises(ValueError, match="nicht eindeutig migrierbar"):
        bench.read_queries(path, "holdout")


def test_v2_is_strict_and_report_metadata_hides_absolute_path(tmp_path):
    item = {
        "schemaVersion": 2,
        "id": "q",
        "split": "holdout",
        "query": "Was ist Algebra?",
        "searchQuery": "algebra",
        "answerable": True,
        "goldPassages": [{"title": "Algebra", "evidence": ["Teilgebiet"]}],
        "exactTitleRequired": True,
        "topicGroup": "math-algebra",
        "stratum": "definition",
    }
    path = tmp_path / "holdout.jsonl"
    path.write_text(json.dumps(item) + "\n", encoding="utf-8")
    queries = bench.read_queries(path, "holdout")

    metadata = bench.query_set_metadata(path.resolve(), queries)

    assert metadata["file"] == "holdout.jsonl"
    assert str(tmp_path) not in json.dumps(metadata)
    item["surprise"] = True
    path.write_text(json.dumps(item) + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="unbekannte v2-Felder"):
        bench.read_queries(path, "holdout")


def _write_frozen_set(tmp_path: Path, items: list[dict]) -> tuple[Path, Path]:
    tmp_path.mkdir(parents=True, exist_ok=True)
    query_path = tmp_path / "holdout.jsonl"
    dev_path = tmp_path / "dev.jsonl"
    selection_path = tmp_path / "candidate-selection.jsonl"
    dev_path.write_bytes(b"")
    selection_path.write_text(
        '{"aliases":[],"title":"Testartikel"}\n',
        encoding="utf-8",
    )
    query_path.write_text(
        "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in items),
        encoding="utf-8",
    )
    answerable = sum(item["answerable"] for item in items)
    manifest = {
        "schemaVersion": 1,
        "datasetSchemaVersion": 2,
        "datasetId": "test-v1",
        "datasetSha256": hashlib.sha256(
            dev_path.read_bytes()
            + b"\0"
            + query_path.read_bytes()
            + b"\0"
            + selection_path.read_bytes()
        ).hexdigest(),
        "sourceDump": {
            "url": (
                "https://dumps.wikimedia.org/dewiki/20260701/"
                "dewiki-20260701-pages-articles-multistream.xml.bz2"
            ),
            "sha1": "1" * 40,
            "sha256": "2" * 64,
        },
        "baseline": {
            "databaseSha256": "9" * 64,
            "sizeBytes": 123,
        },
        "candidateSelection": {
            "file": "candidate-selection.jsonl",
            "sha256": bench._sha256(selection_path),
            "entries": 1,
            "sealId": "8" * 64,
            "sealedAt": "2026-07-26T12:00:00Z",
            "freezeOrder": "single-use-seal-before-random-split-v1",
        },
        "counts": {
            "total": len(items),
            "dev": 0,
            "holdout": len(items),
            "answerable": answerable,
            "noAnswer": len(items) - answerable,
            "holdoutAnswerable": answerable,
            "holdoutNoAnswer": len(items) - answerable,
        },
        "files": {
            "dev": {
                "file": dev_path.name,
                "sha256": bench._sha256(dev_path),
                "queries": 0,
            },
            "holdout": {
                "file": query_path.name,
                "sha256": bench._sha256(query_path),
                "queries": len(items),
            }
        },
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    return query_path, manifest_path


def _v2_item(query_id: str, *, split: str = "holdout", answerable: bool = True) -> dict:
    return {
        "schemaVersion": 2,
        "id": query_id,
        "split": split,
        "query": f"Was ist {query_id}?",
        "searchQuery": query_id,
        "answerable": answerable,
        "goldPassages": (
            [{"title": query_id, "evidence": [f"Evidenz {query_id}"]}]
            if answerable
            else []
        ),
        "exactTitleRequired": answerable,
        "topicGroup": f"topic-{query_id}",
        "stratum": "definition" if answerable else "no-answer-live",
    }


def test_loopback_and_endpoint_validation_are_fail_closed():
    assert bench.validate_loopback_url("http://localhost:8035/", "x") == (
        "http://127.0.0.1:8035"
    )
    assert bench.validate_loopback_url("http://[::1]:8035", "x") == (
        "http://[::1]:8035"
    )
    for value in (
        "https://knowledge.example.org",
        "http://127.0.0.1@evil.example",
        "http://localhost:8035/path",
        "http://localhost:8035?next=evil",
    ):
        with pytest.raises(ValueError, match="Loopback"):
            bench.validate_loopback_url(value, "x")
    for endpoint in ("https://evil.example/search", "//evil/search", "/../secret"):
        with pytest.raises(ValueError, match="URL-Pfad"):
            bench.validate_endpoint(endpoint, "x")


def test_baseline_identity_binds_frozen_bytes_and_runtime_file(tmp_path, monkeypatch):
    database = tmp_path / "legacy.sqlite"
    database.write_bytes(b"immutable baseline bytes")
    repo_root = MODULE_PATH.parents[2]
    runtime_code = {
        "attestation": "self-reported-source-sha256-v1",
        "serverSha256": bench._sha256(
            repo_root / "sidecars" / "knowledge" / "server.py"
        ),
        "packManifestSha256": bench._sha256(
            repo_root / "sidecars" / "knowledge" / "pack_manifest.py"
        ),
    }
    dataset = {
        "baseline": {
            "databaseSha256": hashlib.sha256(database.read_bytes()).hexdigest(),
            "sizeBytes": database.stat().st_size,
        }
    }
    monkeypatch.setattr(
        bench,
        "fetch_baseline_health",
        lambda _url: {
            "dbPath": str(database),
            "articleCount": 42,
            "runtimeCode": runtime_code,
        },
    )

    state, binding = bench.verify_baseline_identity(
        "http://127.0.0.1:8035",
        database,
        dataset,
    )

    assert binding == {
        "identityMethod": "legacy-health-path-plus-local-sha256-v1",
        "databaseSha256": dataset["baseline"]["databaseSha256"],
        "sizeBytes": database.stat().st_size,
        "articleCount": 42,
        "runtimeStatus": "ok",
        "runtimeCode": runtime_code,
    }
    state.assert_unchanged()

    database.write_bytes(b"IMMUTABLE baseline bytes")
    with pytest.raises(ValueError, match="änderte sich"):
        state.assert_unchanged()


def test_frozen_manifest_binds_hash_count_and_single_split(tmp_path):
    query_path, manifest_path = _write_frozen_set(
        tmp_path,
        [_v2_item("Algebra"), _v2_item("Livewert", answerable=False)],
    )
    queries = bench.read_queries(query_path, "all")

    manifest, actual_split = bench.load_dataset_manifest(
        manifest_path,
        query_path,
        queries,
        "holdout",
    )

    assert actual_split == "holdout"
    assert manifest["datasetId"] == "test-v1"
    query_path.write_text(query_path.read_text(encoding="utf-8") + "\n", encoding="utf-8")
    with pytest.raises(ValueError, match="SHA-256"):
        bench.load_dataset_manifest(
            manifest_path,
            query_path,
            bench.read_queries(query_path, "all"),
            "holdout",
        )

    mixed = [_v2_item("A"), _v2_item("B", split="dev")]
    mixed_path, mixed_manifest = _write_frozen_set(tmp_path / "mixed", mixed)
    with pytest.raises(ValueError, match="gemischte"):
        bench.load_dataset_manifest(
            mixed_manifest,
            mixed_path,
            bench.read_queries(mixed_path, "all"),
            "all",
        )


def test_dataset_snapshot_binds_parsed_bytes_and_detects_source_swap(tmp_path):
    query_path, manifest_path = _write_frozen_set(
        tmp_path,
        [_v2_item("Algebra"), _v2_item("Livewert", answerable=False)],
    )
    snapshot = bench.snapshot_frozen_dataset(query_path, manifest_path)
    original_snapshot_bytes = snapshot.query_path.read_bytes()

    original_source_bytes = query_path.read_bytes()
    query_path.write_bytes(
        original_source_bytes.replace(b"Algebra", b"Geometr", 1)
    )

    assert snapshot.query_path.read_bytes() == original_snapshot_bytes
    with pytest.raises(ValueError, match="änderte sich"):
        snapshot.verify_and_cleanup()
    assert not snapshot.temporary_dir.exists()


class _JsonResponse:
    def __init__(self, value: dict):
        self._body = json.dumps(value).encode("utf-8")

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self):
        return self._body


class _JsonOpener:
    def __init__(self, value: dict):
        self.value = value
        self.urls: list[str] = []

    def open(self, url, timeout):
        self.urls.append(str(url))
        return _JsonResponse(self.value)


def _full_local_verification(dataset: dict) -> dict:
    return {
        "releaseEligible": True,
        "artifactVerified": True,
        "sourceAuthorityVerified": True,
        "sourceAuthoritySha256": "5" * 64,
        "sourceDumpBytesVerified": True,
        "logicalContentVerified": True,
        "logicalRecordsSha256": "6" * 64,
        "ftsIntegrityVerified": True,
        "byteRebuildVerified": True,
        "canonicalDatabaseSha256": "3" * 64,
        "releaseStatus": "release-candidate",
        "packId": "hoshi-wikipedia-de-core",
        "manifestSha256": "4" * 64,
        "selectionSha256": dataset["candidateSelection"]["sha256"],
        "manifestFile": "manifest.json",
        "database": "/private/local/pack.sqlite",
        "sha256": "3" * 64,
        "sourceDump": dataset["sourceDump"],
    }


def _content_verified_runtime_manifest(dataset: dict) -> dict:
    repo_root = MODULE_PATH.parents[2]
    return {
        "status": "manifest-content-verified",
        "verification": {
            "contentSha256Verified": True,
            "actualDatabaseSha256": "3" * 64,
        },
        "packId": "hoshi-wikipedia-de-core",
        "releaseStatus": "release-candidate",
        "source": {
            "url": dataset["sourceDump"]["url"],
            "dump": {"sha256": "2" * 64},
        },
        "database": {"sha256": "3" * 64},
        "runtimeCode": {
            "attestation": "self-reported-source-sha256-v1",
            "serverSha256": bench._sha256(
                repo_root / "sidecars" / "knowledge" / "server.py"
            ),
            "packManifestSha256": bench._sha256(
                repo_root / "sidecars" / "knowledge" / "pack_manifest.py"
            ),
        },
    }


def test_candidate_manifest_binds_runtime_to_full_release_proof(monkeypatch):
    dataset = {
        "sourceDump": {
            "url": "https://dumps.wikimedia.org/dewiki/20260701/dump.xml.bz2",
            "sha256": "2" * 64,
        },
        "candidateSelection": {"sha256": "7" * 64},
    }
    local_verification = _full_local_verification(dataset)
    candidate = _content_verified_runtime_manifest(dataset)
    opener = _JsonOpener(candidate)
    monkeypatch.setattr(bench.urllib.request, "build_opener", lambda *_: opener)

    raw, binding = bench.fetch_candidate_manifest(
        "http://localhost:8035",
        "/v1/manifest",
        dataset,
        local_verification,
    )

    assert raw == {
        key: value
        for key, value in candidate.items()
        if key != "runtimeCode"
    }
    assert binding["runtimeCode"] == candidate["runtimeCode"]
    assert binding["packId"] == "hoshi-wikipedia-de-core"
    assert binding["databaseSha256"] == "3" * 64
    assert binding["runtimeActualDatabaseSha256"] == "3" * 64
    assert binding["sourceAuthorityVerified"] is True
    assert binding["sourceAuthoritySha256"] == "5" * 64
    assert binding["sourceDumpBytesVerified"] is True
    assert binding["logicalContentVerified"] is True
    assert binding["logicalRecordsSha256"] == "6" * 64
    assert binding["ftsIntegrityVerified"] is True
    assert binding["byteRebuildVerified"] is True
    assert binding["canonicalDatabaseSha256"] == "3" * 64
    assert binding["selectionSha256"] == "7" * 64
    assert "manifestPath" not in json.dumps(binding)
    assert "/private/local" not in json.dumps(binding)
    assert opener.urls == ["http://127.0.0.1:8035/v1/manifest"]
    mismatched_selection = {
        **local_verification,
        "selectionSha256": "9" * 64,
    }
    with pytest.raises(ValueError, match="vor dem Holdout-Split"):
        bench.fetch_candidate_manifest(
            "http://127.0.0.1:8035",
            "/v1/manifest",
            dataset,
            mismatched_selection,
        )
    candidate["source"]["dump"]["sha256"] = "3" * 64
    with pytest.raises(ValueError, match="SHA-256"):
        bench.fetch_candidate_manifest(
            "http://127.0.0.1:8035",
            "/v1/manifest",
            dataset,
            local_verification,
        )


def test_candidate_manifest_rejects_incomplete_local_release_proof(monkeypatch):
    dataset = {
        "sourceDump": {
            "url": "https://dumps.wikimedia.org/dewiki/20260701/dump.xml.bz2",
            "sha256": "2" * 64,
        },
        "candidateSelection": {"sha256": "7" * 64},
    }
    candidate = _content_verified_runtime_manifest(dataset)
    opener = _JsonOpener(candidate)
    monkeypatch.setattr(bench.urllib.request, "build_opener", lambda *_: opener)
    proof = _full_local_verification(dataset)

    for weakened in (
        {**proof, "releaseEligible": False},
        {**proof, "artifactVerified": False},
        {**proof, "sourceAuthorityVerified": False},
        {**proof, "sourceDumpBytesVerified": False},
        {**proof, "logicalContentVerified": False},
        {**proof, "ftsIntegrityVerified": False},
        {**proof, "byteRebuildVerified": False},
    ):
        with pytest.raises(
            ValueError,
            match=(
                "Releasebeweis|Artefakt|autoritativ|Quelldump|deterministisch|"
                "FTS|bytegenauen"
            ),
        ):
            bench.fetch_candidate_manifest(
                "http://127.0.0.1:8035",
                "/v1/manifest",
                dataset,
                weakened,
            )


def test_candidate_manifest_rejects_metadata_only_runtime_and_actual_hash_drift(
    monkeypatch,
):
    dataset = {
        "sourceDump": {
            "url": "https://dumps.wikimedia.org/dewiki/20260701/dump.xml.bz2",
            "sha256": "2" * 64,
        },
        "candidateSelection": {"sha256": "7" * 64},
    }
    proof = _full_local_verification(dataset)
    candidate = _content_verified_runtime_manifest(dataset)
    opener = _JsonOpener(candidate)
    monkeypatch.setattr(bench.urllib.request, "build_opener", lambda *_: opener)

    candidate["status"] = "manifest-valid-metadata-only"
    with pytest.raises(ValueError, match="manifest-content-verified"):
        bench.fetch_candidate_manifest(
            "http://127.0.0.1:8035",
            "/v1/manifest",
            dataset,
            proof,
        )

    candidate["status"] = "manifest-content-verified"
    candidate["verification"]["contentSha256Verified"] = False
    with pytest.raises(ValueError, match="vollständig gehasht"):
        bench.fetch_candidate_manifest(
            "http://127.0.0.1:8035",
            "/v1/manifest",
            dataset,
            proof,
        )

    candidate["verification"]["contentSha256Verified"] = True
    candidate["verification"]["actualDatabaseSha256"] = "7" * 64
    with pytest.raises(ValueError, match="Tatsächliche Runtime-DB"):
        bench.fetch_candidate_manifest(
            "http://127.0.0.1:8035",
            "/v1/manifest",
            dataset,
            proof,
        )


def test_candidate_source_dump_is_required_and_forwarded(tmp_path, monkeypatch):
    manifest = tmp_path / "pack" / "manifest.json"
    source_dump = tmp_path / "dump.xml.bz2"
    calls = []

    def fake_verify(path, **kwargs):
        calls.append((path, kwargs))
        return {"releaseEligible": True}

    monkeypatch.setattr(bench, "verify_knowledge_pack", fake_verify)
    resolved_manifest, verification = bench.verify_candidate_artifacts(
        "http://127.0.0.1:8135",
        manifest,
        source_dump,
    )

    assert resolved_manifest == manifest.resolve()
    assert verification == {
        "releaseEligible": True,
        "manifestFile": "manifest.json",
    }
    assert calls == [
        (
            manifest.resolve(),
            {
                "fast": False,
                "verify_source_online": True,
                "source_dump_path": source_dump.resolve(),
            },
        )
    ]

    with pytest.raises(ValueError, match="candidate-source-dump"):
        bench.verify_candidate_artifacts(
            "http://127.0.0.1:8135",
            manifest,
            None,
        )
    with pytest.raises(ValueError, match="candidate-pack-manifest"):
        bench.verify_candidate_artifacts(
            "http://127.0.0.1:8135",
            None,
            source_dump,
        )
    for manifest_without_url, dump_without_url in (
        (manifest, None),
        (None, source_dump),
        (manifest, source_dump),
    ):
        with pytest.raises(ValueError, match="ohne --candidate-url"):
            bench.verify_candidate_artifacts(
                None,
                manifest_without_url,
                dump_without_url,
            )


def test_probe_uses_proxy_free_loopback_transport(monkeypatch):
    opener = _JsonOpener(
        {
            "durationMs": 3,
            "hits": [
                {
                    "title": "Algebra",
                    "bm25Score": -10.0,
                    "extract": "Algebra ist ein Teilgebiet.",
                }
            ],
        }
    )
    handlers = []

    def build_opener(*values):
        handlers.extend(values)
        return opener

    monkeypatch.setattr(bench.urllib.request, "build_opener", build_opener)
    probe = bench.request_probe(
        "http://localhost:8035",
        "/search",
        _query(
            "algebra",
            answerable=True,
            title="Algebra",
            evidence=("Teilgebiet",),
        ),
        3,
        -3.0,
    )

    assert probe.error is None
    assert probe.evidence_rank == 1
    assert len(handlers) == 2
    assert isinstance(handlers[0], bench.urllib.request.ProxyHandler)
    assert handlers[0].proxies == {}
    assert isinstance(handlers[1], bench.NoRedirectHandler)
    assert opener.urls[0].startswith("http://127.0.0.1:8035/search?")
    with pytest.raises(ValueError, match="Loopback"):
        bench.request_probe(
            "https://knowledge.example.org",
            "/search",
            _query("remote", answerable=False),
            3,
            -3.0,
        )


def test_probe_never_follows_a_loopback_redirect_with_private_query():
    leaked_paths = []

    class Sink(BaseHTTPRequestHandler):
        def do_GET(self):
            leaked_paths.append(self.path)
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b'{"durationMs":1,"hits":[]}')

        def log_message(self, *_):
            pass

    sink = ThreadingHTTPServer(("127.0.0.1", 0), Sink)

    class Redirect(BaseHTTPRequestHandler):
        def do_GET(self):
            self.send_response(302)
            self.send_header(
                "Location",
                f"http://127.0.0.1:{sink.server_port}/leak",
            )
            self.end_headers()

        def log_message(self, *_):
            pass

    redirect = ThreadingHTTPServer(("127.0.0.1", 0), Redirect)
    threads = [
        threading.Thread(target=server.serve_forever, daemon=True)
        for server in (sink, redirect)
    ]
    for thread in threads:
        thread.start()
    try:
        query = _query("private", answerable=False)
        query = bench.Query(
            **{
                **query.__dict__,
                "query": "PRIVATE HOLDOUT QUESTION",
            }
        )
        probe = bench.request_probe(
            f"http://127.0.0.1:{redirect.server_port}",
            "/search",
            query,
            3,
            -3.0,
        )
    finally:
        redirect.shutdown()
        sink.shutdown()
        redirect.server_close()
        sink.server_close()

    assert probe.error is not None
    assert leaked_paths == []


def test_repeats_must_be_retrieval_consistent_and_paired_exact_is_one_sided():
    queries = [
        _query("a", answerable=True, title="A", evidence=("A",)),
        _query("b", answerable=True, title="B", evidence=("B",)),
        _query("c", answerable=False),
    ]
    baseline = [
        bench.Probe(query.id, 10.0, 8, (), (), None, 0, None, repeat=repeat)
        for repeat in range(3)
        for query in queries
    ]
    candidate = [
        bench.Probe(
            query.id,
            11.0,
            9,
            ((query.gold_titles[0],) if query.answerable else ()),
            ((-9.0,) if query.answerable else ()),
            (1 if query.answerable else None),
            10,
            None,
            repeat=repeat,
        )
        for repeat in range(3)
        for query in queries
    ]

    paired = bench.paired_statistics(queries, baseline, candidate)

    assert paired["passageRecallAt3"]["improved"] == 2
    assert paired["passageRecallAt3"]["exactOneSidedP"] == 0.25
    inconsistent = list(candidate)
    inconsistent[-2] = bench.Probe(
        "b",
        11.0,
        9,
        ("Andere Seite",),
        (-9.0,),
        None,
        10,
        None,
        repeat=2,
    )
    with pytest.raises(ValueError, match="nichtdeterministisches Retrieval"):
        bench.metrics(queries, inconsistent)


def test_interleaved_schedule_is_counterbalanced_with_warmup(monkeypatch):
    queries = [
        _query("a", answerable=False),
        _query("b", answerable=False),
    ]
    calls = []

    def fake_probe(base_url, endpoint, query, limit, bm25_max, **kwargs):
        variant = "candidate" if base_url.endswith("8135") else "baseline"
        calls.append((kwargs["repeat"], query.id, variant))
        return bench.Probe(
            query.id,
            1.0,
            1,
            (),
            (),
            None,
            0,
            None,
            repeat=kwargs["repeat"],
        )

    monkeypatch.setattr(bench, "request_probe", fake_probe)
    baseline, candidate = bench.run_interleaved(
        queries,
        baseline_url="http://127.0.0.1:8035",
        baseline_endpoint="/search",
        candidate_url="http://127.0.0.1:8135",
        candidate_endpoint="/v1/search",
        limit=3,
        bm25_max=-3.0,
        warmup_rounds=1,
        repeats=3,
        candidate_manifest={"packId": "test"},
    )

    assert calls[:4] == [
        (-1, "a", "baseline"),
        (-1, "a", "candidate"),
        (-1, "b", "candidate"),
        (-1, "b", "baseline"),
    ]
    assert len(baseline) == len(candidate) == 6
    assert {probe.repeat for probe in baseline} == {0, 1, 2}


def test_production_thresholds_cannot_be_weakened():
    common = {
        "min_recall_gain": 0.10,
        "max_added_p95_ms": 150.0,
        "minimum_n": 20,
        "minimum_answerable_n": 20,
        "minimum_unanswerable_n": 10,
        "max_false_retrieval_candidate_rate": 0.05,
    }
    bench.validate_production_thresholds(**common)
    for field, value in (
        ("min_recall_gain", 0.09),
        ("max_added_p95_ms", 151.0),
        ("minimum_n", 19),
        ("minimum_answerable_n", 19),
        ("minimum_unanswerable_n", 9),
        ("max_false_retrieval_candidate_rate", 0.051),
    ):
        weakened = {**common, field: value}
        with pytest.raises(ValueError, match="Production"):
            bench.validate_production_thresholds(**weakened)


def test_production_execution_contract_is_exact_and_rejects_nan():
    contract = {
        "limit": 3,
        "bm25_max": -3.0,
        "warmup_rounds": 1,
        "repeats": 3,
        "baseline_endpoint": "/search",
        "candidate_endpoint": "/v1/search",
        "candidate_manifest_endpoint": "/v1/manifest",
    }
    bench.validate_production_execution_contract(**contract)

    for field, value in (
        ("limit", 4),
        ("bm25_max", float("nan")),
        ("bm25_max", float("inf")),
        ("bm25_max", -2.99),
        ("warmup_rounds", 2),
        ("repeats", 4),
        ("baseline_endpoint", "/v1/search"),
        ("candidate_endpoint", "/search"),
        ("candidate_manifest_endpoint", "/manifest"),
    ):
        with pytest.raises(ValueError, match="Production-Bench"):
            bench.validate_production_execution_contract(
                **{**contract, field: value}
            )


def test_report_write_is_private_atomic_and_no_overwrite(tmp_path):
    output = tmp_path / "report"
    report = {
        "split": "holdout",
        "baseline": {
            "metrics": {
                "n": 1,
                "recallAt3": 1.0,
                "passageRecallAt1": 1.0,
                "passageRecallAt3": 1.0,
                "falseRetrievalCandidateRate": 0.0,
                "p95WallMs": 1.0,
                "errors": 0,
            }
        },
    }

    bench.write_report_atomic(output, report)

    assert stat.S_IMODE(output.stat().st_mode) == 0o700
    assert stat.S_IMODE((output / "report.json").stat().st_mode) == 0o600
    assert stat.S_IMODE((output / "report.md").stat().st_mode) == 0o600
    original = (output / "report.json").read_bytes()
    with pytest.raises(ValueError, match="existiert bereits"):
        bench.write_report_atomic(output, report)
    assert (output / "report.json").read_bytes() == original

    concurrent = tmp_path / "concurrent-report"
    concurrent.mkdir()
    with pytest.raises(ValueError, match="existiert bereits"):
        bench.write_report_atomic(concurrent, report)
    assert list(concurrent.iterdir()) == []


def test_empty_strata_are_strict_json_null_never_nan(tmp_path):
    answerable = _query(
        "answerable",
        answerable=True,
        title="Antwort",
        evidence=("Evidenz",),
    )
    answerable = bench.Query(
        **{**answerable.__dict__, "stratum": "definition"}
    )
    no_answer = _query("live", answerable=False)
    no_answer = bench.Query(
        **{**no_answer.__dict__, "stratum": "no-answer-live"}
    )
    probes = [
        bench.Probe("answerable", 1.0, 1, ("Antwort",), (-9.0,), 1, 8, None),
        bench.Probe("live", 1.0, 1, (), (), None, 0, None),
    ]
    metrics = bench.metrics([answerable, no_answer], probes)
    report = {
        "split": "holdout",
        "baseline": {"metrics": metrics},
    }

    encoded = json.dumps(report, allow_nan=False)
    assert "NaN" not in encoded
    assert metrics["byStratum"]["definition"]["falseRetrievalCandidateRate"] is None
    assert metrics["byStratum"]["no-answer-live"]["passageRecallAt3"] is None
    output = tmp_path / "strict-json-report"
    bench.write_report_atomic(output, report)
    strict = json.loads(
        (output / "report.json").read_text(encoding="utf-8"),
        parse_constant=lambda token: (_ for _ in ()).throw(ValueError(token)),
    )
    assert strict["baseline"]["metrics"]["byStratum"]["definition"][
        "falseRetrievalCandidateRate"
    ] is None
