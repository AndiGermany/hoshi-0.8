"""Collector-/Freeze-Tests: rein lokal, ohne Audio- oder Netzwerkpersistenz."""

import argparse
import hashlib
import importlib.util
import json
import stat
import sys
from pathlib import Path

import pytest


MODULE_PATH = Path(__file__).with_name("collect.py")
SPEC = importlib.util.spec_from_file_location("knowledge_bench_collect", MODULE_PATH)
collection = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules["knowledge_bench_collect"] = collection
SPEC.loader.exec_module(collection)


def _add_args(root: Path, **overrides) -> argparse.Namespace:
    values = {
        "root": root,
        "dataset": "dataset-v1",
        "id": "q-one",
        "text": "Wer war Marie Curie?",
        "audio": None,
        "topic_group": "person-curie",
        "stratum": "person",
        "stt_url": "http://127.0.0.1:9001",
        "yes": True,
        "allow_duplicate": False,
        "acknowledge_privacy": False,
    }
    values.update(overrides)
    return argparse.Namespace(**values)


def _reviewed_record(
    record_id: str,
    topic_group: str,
    answerable: bool,
) -> dict:
    unique_term = "test" + "".join(char for char in record_id if char.isalnum())
    query = (
        f"Was ist Testbegriff {unique_term}?"
        if answerable
        else f"Wie ist der Livewert {unique_term}?"
    )
    return {
        "schemaVersion": 1,
        "id": record_id,
        "query": query,
        "topicGroup": topic_group,
        "stratum": "definition" if answerable else "no-answer-live",
        "captureMode": "text",
        "audioPersisted": False,
        "capturedAt": "2026-07-26T00:00:00Z",
        "state": "reviewed",
        "answerable": answerable,
        "goldPassages": (
            [
                {
                    "title": collection.search_query(query),
                    "evidence": [f"Evidenz {record_id}"],
                }
            ]
            if answerable
            else []
        ),
        "exactTitleRequired": answerable,
        "labeledAt": "2026-07-26T00:01:00Z",
        "reviewedAt": "2026-07-26T00:02:00Z",
    }


def _freeze_args(root: Path, output: Path, **overrides) -> argparse.Namespace:
    selection = root.parent / "candidate-selection.jsonl"
    baseline = root.parent / "baseline.sqlite"
    selection.parent.mkdir(parents=True, exist_ok=True)
    if not selection.exists():
        selection.write_text(
            '{"title":"Albert Einstein","aliases":[]}\n',
            encoding="utf-8",
        )
    if not baseline.exists():
        baseline.write_bytes(b"frozen baseline")
    seal_path = root / "dataset-v1" / collection.SELECTION_SEAL_FILE
    if not seal_path.exists():
        collection.command_seal_selection(
            argparse.Namespace(
                root=root,
                dataset="dataset-v1",
                candidate_selection=selection,
                baseline_database=baseline,
            )
        )
    values = {
        "root": root,
        "dataset": "dataset-v1",
        "output_dir": output,
        "source_dump_url": (
            "https://dumps.wikimedia.org/dewiki/20260701/"
            "dewiki-20260701-pages-articles-multistream.xml.bz2"
        ),
        "source_dump_sha1": "a" * 40,
        "source_dump_sha256": "b" * 64,
        "source_dump_file": None,
        "acknowledge_privacy": False,
    }
    values.update(overrides)
    return argparse.Namespace(**values)


def _test_policy(**overrides) -> collection._FreezePolicy:
    values = {
        "minimum_total": 1,
        "maximum_total": 100,
        "minimum_holdout_answerable": 1,
        "minimum_holdout_unanswerable": 1,
    }
    values.update(overrides)
    return collection._FreezePolicy(**values)


def _freeze_for_test(
    args: argparse.Namespace,
    **policy_overrides,
) -> None:
    collection._freeze_with_policy(args, _test_policy(**policy_overrides))


def test_private_intake_permissions_and_audio_is_never_copied(tmp_path, monkeypatch):
    root = tmp_path / "private-intake"
    audio = tmp_path / "sample.wav"
    audio.write_bytes(b"private-audio")
    monkeypatch.setattr(
        collection,
        "transcribe_audio",
        lambda path, url: "Was ist Photosynthese?",
    )

    collection.command_add(
        _add_args(
            root,
            id="q-audio",
            text=None,
            audio=audio,
            topic_group="concept-photosynthesis",
        )
    )

    directory = root / "dataset-v1"
    records_path = directory / "records.jsonl"
    record = json.loads(records_path.read_text(encoding="utf-8"))
    assert stat.S_IMODE(root.stat().st_mode) == 0o700
    assert stat.S_IMODE(directory.stat().st_mode) == 0o700
    assert stat.S_IMODE(records_path.stat().st_mode) == 0o600
    assert record["captureMode"] == "audio"
    assert record["audioPersisted"] is False
    assert record["query"] == "Was ist Photosynthese?"
    assert str(audio) not in records_path.read_text(encoding="utf-8")
    assert [path.name for path in directory.iterdir()] == ["records.jsonl"]


def test_audio_stt_rejects_remote_url_before_read_or_network(tmp_path, monkeypatch):
    audio = tmp_path / "voice.wav"
    audio.write_bytes(b"must-not-be-read")
    monkeypatch.setattr(
        Path,
        "read_bytes",
        lambda self: (_ for _ in ()).throw(AssertionError("Audio gelesen")),
    )
    monkeypatch.setattr(
        collection.urllib.request,
        "build_opener",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("Netzwerk geöffnet")
        ),
    )

    with pytest.raises(ValueError, match="Loopback"):
        collection.transcribe_audio(audio, "https://stt.example.org")
    assert (
        collection.validate_loopback_stt_url("http://localhost:9001/")
        == "http://127.0.0.1:9001"
    )
    assert (
        collection.validate_loopback_stt_url("http://[::1]:9001")
        == "http://[::1]:9001"
    )


def test_label_then_review_is_explicit_state_transition(tmp_path):
    root = tmp_path / "private-intake"
    collection.command_add(_add_args(root))
    collection.command_label(
        argparse.Namespace(
            root=root,
            dataset="dataset-v1",
            record_id="q-one",
            answerable="yes",
            gold_passage=["Marie Curie::Physikerin||Chemikerin"],
            exact_title_required="no",
        )
    )
    record = collection.load_records(root / "dataset-v1")[0]
    assert record["state"] == "draft"
    assert record["answerable"] is True

    collection.command_review(
        argparse.Namespace(
            root=root,
            dataset="dataset-v1",
            record_id="q-one",
            yes=True,
            acknowledge_privacy=False,
        )
    )

    reviewed = collection.load_records(root / "dataset-v1")[0]
    assert reviewed["state"] == "reviewed"
    assert reviewed["reviewedAt"]


def test_duplicate_and_privacy_warnings_are_fail_closed(tmp_path):
    root = tmp_path / "private-intake"
    collection.command_add(_add_args(root))
    with pytest.raises(ValueError, match="Duplikat-Warnung"):
        collection.command_add(_add_args(root, id="q-two"))
    with pytest.raises(ValueError, match="Privacy-Warnung"):
        collection.command_add(
            _add_args(
                root,
                id="q-private",
                text="Was weißt du über test@example.org?",
                topic_group="privacy",
            )
        )


def test_dump_provenance_accepts_only_canonical_dewiki_article_dump():
    source = collection.validate_dump_source(
        (
            "https://dumps.wikimedia.org/dewiki/20260701/"
            "dewiki-20260701-pages-articles-multistream.xml.bz2"
        ),
        "a" * 40,
        "b" * 64,
    )
    assert source["url"].startswith("https://dumps.wikimedia.org/dewiki/")
    assert source["operatorAsserted"] is True
    assert source["networkMetadataVerified"] is False
    assert source["localFileVerification"] == {"performed": False}

    with pytest.raises(ValueError, match="kanonische"):
        collection.validate_dump_source(
            (
                "https://example.org/dewiki/20260701/"
                "dewiki-20260701-pages-articles-multistream.xml.bz2"
            ),
            "a" * 40,
            "b" * 64,
        )
    with pytest.raises(ValueError, match="kanonische"):
        collection.validate_dump_source(
            (
                "https://dumps.wikimedia.org/dewiki/20260702/"
                "dewiki-20260701-pages-articles-multistream.xml.bz2"
            ),
            "a" * 40,
            "b" * 64,
        )


def test_dump_provenance_can_verify_a_local_file_without_recording_its_path(
    tmp_path,
):
    dump = tmp_path / "synthetic-dump.xml.bz2"
    dump.write_bytes(b"synthetic dump bytes")
    sha1 = hashlib.sha1(dump.read_bytes(), usedforsecurity=False).hexdigest()
    sha256 = hashlib.sha256(dump.read_bytes()).hexdigest()

    source = collection.validate_dump_source(
        (
            "https://dumps.wikimedia.org/dewiki/20260701/"
            "dewiki-20260701-pages-articles-multistream.xml.bz2"
        ),
        sha1,
        sha256,
        dump,
    )

    assert source["operatorAsserted"] is True
    assert source["localFileVerification"] == {
        "performed": True,
        "sizeBytes": dump.stat().st_size,
        "sha1": sha1,
        "sha256": sha256,
    }
    assert str(dump) not in json.dumps(source)

    with pytest.raises(ValueError, match="SHA-1"):
        collection.validate_dump_source(
            source["url"],
            "a" * 40,
            sha256,
            dump,
        )


def test_freeze_keeps_topic_groups_separate_and_writes_provenance(
    tmp_path,
    monkeypatch,
):
    root = tmp_path / "private-intake"
    directory = collection.dataset_dir(root, "dataset-v1")
    records = [
        _reviewed_record("q-a1", "answerable-a", True),
        _reviewed_record("q-a2", "answerable-a", True),
        _reviewed_record("q-a3", "answerable-b", True),
        _reviewed_record("q-a4", "answerable-c", True),
        _reviewed_record("q-a5", "answerable-c", True),
        _reviewed_record("q-n1", "no-answer-a", False),
        _reviewed_record("q-n2", "no-answer-a", False),
        _reviewed_record("q-n3", "no-answer-b", False),
        _reviewed_record("q-n4", "no-answer-c", False),
        _reviewed_record("q-n5", "no-answer-c", False),
    ]
    collection.save_records(directory, records)
    monkeypatch.setattr(
        collection,
        "verify_contract",
        lambda: {
            "version": "fts5-head-noun-v1",
            "sourceCommit": "deadbeef",
            "contractSha256": "c" * 64,
        },
    )
    output = tmp_path / "frozen"

    _freeze_for_test(_freeze_args(root, output))

    dev = [
        json.loads(line)
        for line in (output / "dev.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    holdout = [
        json.loads(line)
        for line in (output / "holdout.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
    split_by_group = {}
    for item in dev + holdout:
        previous = split_by_group.setdefault(item["topicGroup"], item["split"])
        assert previous == item["split"]
        assert item["schemaVersion"] == 2
        assert item["searchQuery"] == collection.search_query(item["query"])
    assert len(holdout) == 3
    assert any(item["answerable"] for item in holdout)
    assert any(not item["answerable"] for item in holdout)
    assert manifest["datasetSchemaVersion"] == 2
    assert manifest["sourceDump"]["url"].startswith("https://dumps.wikimedia.org/")
    assert manifest["sourceDump"]["sha1"] == "a" * 40
    assert manifest["sourceDump"]["sha256"] == "b" * 64
    assert manifest["sourceDump"]["operatorAsserted"] is True
    assert manifest["sourceDump"]["localFileVerification"] == {
        "performed": False
    }
    assert manifest["reducer"]["sourceCommit"] == "deadbeef"
    assert manifest["baseline"] == {
        "databaseSha256": hashlib.sha256(b"frozen baseline").hexdigest(),
        "sizeBytes": len(b"frozen baseline"),
    }
    assert manifest["candidateSelection"]["file"] == "candidate-selection.jsonl"
    assert manifest["candidateSelection"]["sha256"] == collection.sha256_file(
        output / "candidate-selection.jsonl"
    )
    assert manifest["candidateSelection"]["entries"] == 1
    assert (
        manifest["candidateSelection"]["freezeOrder"]
        == "single-use-seal-before-random-split-v1"
    )
    assert len(manifest["candidateSelection"]["sealId"]) == 64
    assert manifest["candidateSelection"]["sealedAt"].endswith("Z")
    assert (output / "candidate-selection.jsonl").read_text(encoding="utf-8") == (
        '{"aliases":[],"title":"Albert Einstein"}\n'
    )
    assert manifest["privacy"]["audioPersisted"] is False
    assert manifest["privacy"]["intakeIncluded"] is False
    assert manifest["counts"]["holdoutAnswerable"] >= 1
    assert manifest["counts"]["holdoutNoAnswer"] >= 1
    assert manifest["counts"]["strata"]["total"] == {
        "definition": 5,
        "no-answer-live": 5,
    }
    assert sum(manifest["counts"]["strata"]["dev"].values()) == len(dev)
    assert sum(manifest["counts"]["strata"]["holdout"].values()) == len(holdout)
    assert manifest["groundTruthValidation"] == {
        "schemaValidated": True,
        "exactTitleSearchQueryValidated": True,
        "evidenceAgainstSourceDump": {
            "performed": False,
            "isFreezeGate": False,
            "status": "open",
        },
    }
    assert manifest["files"]["dev"]["sha256"] == collection.sha256_file(
        output / "dev.jsonl"
    )
    assert manifest["files"]["holdout"]["sha256"] == collection.sha256_file(
        output / "holdout.jsonl"
    )
    expected_dataset_hash = hashlib.sha256(
        (output / "dev.jsonl").read_bytes()
        + b"\0"
        + (output / "holdout.jsonl").read_bytes()
        + b"\0"
        + (output / "candidate-selection.jsonl").read_bytes()
    ).hexdigest()
    assert manifest["datasetSha256"] == expected_dataset_hash
    assert str(tmp_path) not in json.dumps(manifest)
    assert stat.S_IMODE(output.stat().st_mode) == 0o500
    assert stat.S_IMODE((output / "dev.jsonl").stat().st_mode) == 0o400
    assert stat.S_IMODE((output / "holdout.jsonl").stat().st_mode) == 0o400
    assert (
        stat.S_IMODE((output / "candidate-selection.jsonl").stat().st_mode)
        == 0o400
    )
    assert stat.S_IMODE((output / "manifest.json").stat().st_mode) == 0o400

    with pytest.raises(ValueError, match="bereits.*verbraucht"):
        _freeze_for_test(_freeze_args(root, tmp_path / "second-freeze"))


def test_candidate_selection_is_canonical_and_rejects_private_fields(tmp_path):
    selection = tmp_path / "selection.jsonl"
    selection.write_text(
        '# Kommentar\n{"aliases":[],"title":"  Albert   Einstein "}\n',
        encoding="utf-8",
    )

    canonical, entries = collection.canonical_candidate_selection(selection)

    assert entries == 1
    assert canonical == b'{"aliases":[],"title":"Albert Einstein"}\n'

    selection.write_text(
        '{"title":"Albert Einstein","speakerId":"private"}\n',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="nur öffentliche Felder"):
        collection.canonical_candidate_selection(selection)

    selection.write_text(
        '{"title":"Albert Einstein","aliases":["private note"]}\n',
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="keine unbelegten Aliase"):
        collection.canonical_candidate_selection(selection)


def test_selection_seal_never_follows_preplaced_symlink(tmp_path):
    root = tmp_path / "private-intake"
    directory = collection.dataset_dir(root, "dataset-v1")
    source = tmp_path / "public-selection.jsonl"
    baseline = tmp_path / "baseline.sqlite"
    source.write_text('{"title":"Algebra","aliases":[]}\n', encoding="utf-8")
    baseline.write_bytes(b"baseline")
    outside = tmp_path / "must-not-exist.jsonl"
    (directory / collection.CANDIDATE_SELECTION_FILE).symlink_to(outside)

    with pytest.raises(ValueError, match="existiert bereits"):
        collection.command_seal_selection(
            argparse.Namespace(
                root=root,
                dataset="dataset-v1",
                candidate_selection=source,
                baseline_database=baseline,
            )
        )

    assert not outside.exists()
    assert not (directory / collection.SELECTION_SEAL_FILE).exists()


def test_freeze_rejects_unreviewed_duplicates_privacy_and_impossible_classes(
    tmp_path,
    monkeypatch,
):
    root = tmp_path / "private-intake"
    directory = collection.dataset_dir(root, "dataset-v1")
    monkeypatch.setattr(collection, "verify_contract", lambda: {"version": "test"})
    base = [
        _reviewed_record("q-a", "a", True),
        _reviewed_record("q-b", "b", True),
        _reviewed_record("q-n", "n", False),
        _reviewed_record("q-m", "m", False),
    ]

    unreviewed = [dict(record) for record in base]
    unreviewed[0]["state"] = "draft"
    unreviewed[0]["reviewedAt"] = None
    collection.save_records(directory, unreviewed)
    with pytest.raises(ValueError, match="reviewed"):
        _freeze_for_test(_freeze_args(root, tmp_path / "unreviewed"))

    duplicates = [dict(record) for record in base]
    duplicates[1]["query"] = duplicates[0]["query"].upper()
    duplicates[1]["exactTitleRequired"] = False
    collection.save_records(directory, duplicates)
    with pytest.raises(ValueError, match="doppelte"):
        _freeze_for_test(_freeze_args(root, tmp_path / "duplicate"))

    private = [dict(record) for record in base]
    private[0]["query"] = "Schreibe an test@example.org"
    private[0]["exactTitleRequired"] = False
    collection.save_records(directory, private)
    with pytest.raises(ValueError, match="Privacy"):
        _freeze_for_test(_freeze_args(root, tmp_path / "private"))

    collection.save_records(directory, base)
    with pytest.raises(ValueError, match="zwischen 5 und 100"):
        collection._freeze_with_policy(
            _freeze_args(root, tmp_path / "too-small"),
            _test_policy(minimum_total=5),
        )

    with pytest.raises(ValueError, match="Mindestklassen"):
        collection._freeze_with_policy(
            _freeze_args(root, tmp_path / "classes"),
            _test_policy(
                minimum_holdout_answerable=2,
                minimum_holdout_unanswerable=2,
            ),
        )


def test_production_freeze_limits_are_not_cli_configurable_or_lowerable(
    tmp_path,
    monkeypatch,
):
    freeze_parser = next(
        action
        for action in collection.build_parser()._actions
        if isinstance(action, argparse._SubParsersAction)
    ).choices["freeze"]
    exposed = {action.dest for action in freeze_parser._actions}
    assert {
        "minimum_total",
        "maximum_total",
        "minimum_holdout_answerable",
        "minimum_holdout_unanswerable",
    }.isdisjoint(exposed)

    root = tmp_path / "private-intake"
    directory = collection.dataset_dir(root, "dataset-v1")
    collection.save_records(
        directory,
        [
            _reviewed_record("q-a", "a", True),
            _reviewed_record("q-b", "b", True),
            _reviewed_record("q-n", "n", False),
            _reviewed_record("q-m", "m", False),
        ],
    )
    monkeypatch.setattr(collection, "verify_contract", lambda: {"version": "test"})
    forged = _freeze_args(root, tmp_path / "forged")
    forged.minimum_total = 1
    forged.minimum_holdout_answerable = 1
    forged.minimum_holdout_unanswerable = 1
    with pytest.raises(ValueError, match="zwischen 80 und 100"):
        collection.command_freeze(forged)


def test_freeze_rejects_cross_group_search_and_gold_title_leaks(
    tmp_path,
    monkeypatch,
):
    root = tmp_path / "private-intake"
    directory = collection.dataset_dir(root, "dataset-v1")
    monkeypatch.setattr(collection, "verify_contract", lambda: {"version": "test"})
    records = [
        _reviewed_record("q-a", "group-a", True),
        _reviewed_record("q-b", "group-b", True),
        _reviewed_record("q-n", "group-n", False),
        _reviewed_record("q-m", "group-m", False),
    ]

    search_leak = [dict(record) for record in records]
    search_leak[2]["query"] = (
        "Kannst du mir erklären, was Testbegriff testqa ist?"
    )
    collection.save_records(directory, search_leak)
    with pytest.raises(ValueError, match="searchQuery"):
        _freeze_for_test(_freeze_args(root, tmp_path / "search-leak"))

    title_leak = [dict(record) for record in records]
    title_leak[1] = {
        **title_leak[1],
        "exactTitleRequired": False,
        "goldPassages": [
            {
                "title": title_leak[0]["goldPassages"][0]["title"].upper(),
                "evidence": ["Andere Evidenz"],
            }
        ],
    }
    collection.save_records(directory, title_leak)
    with pytest.raises(ValueError, match="Goldtitel"):
        _freeze_for_test(_freeze_args(root, tmp_path / "title-leak"))


def test_exact_title_requires_search_query_to_match_a_gold_title():
    item = {
        "schemaVersion": 2,
        "id": "q-exact",
        "split": "dev",
        "query": "Was ist die Photosynthese?",
        "searchQuery": "photosynthese",
        "answerable": True,
        "goldPassages": [
            {"title": "Biologie", "evidence": ["Lichtenergie"]}
        ],
        "exactTitleRequired": True,
        "topicGroup": "concept-photosynthesis",
        "stratum": "definition",
    }
    with pytest.raises(ValueError, match="exactTitleRequired"):
        collection.parse_query_item(item, "test")

    item["goldPassages"][0]["title"] = "Photosynthese"
    parsed = collection.parse_query_item(item, "test")
    assert parsed.exact_title_required is True

    intake = _reviewed_record("q-structural", "structural", True)
    intake["goldPassages"][0]["title"] = "Falscher Titel"
    with pytest.raises(ValueError, match="exactTitleRequired"):
        collection.validate_record(intake, "test")

    intake["exactTitleRequired"] = False
    intake["goldPassages"].append(
        {"title": "falscher_titel", "evidence": ["Andere Evidenz"]}
    )
    with pytest.raises(ValueError, match="doppelter normalisierter Goldtitel"):
        collection.validate_record(intake, "test")


def test_real_reducer_contract_and_vectors_are_current():
    metadata = collection.verify_contract()

    assert metadata["version"] == "fts5-head-noun-v1"
    assert len(metadata["regionSha256"]) == 64
