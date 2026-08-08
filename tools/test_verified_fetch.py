#!/usr/bin/env python3
"""Offline-Vertragstests fuer tools/verified_fetch.py."""

from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest import mock


MODULE_PATH = Path(__file__).with_name("verified_fetch.py")
SPEC = importlib.util.spec_from_file_location("hoshi_verified_fetch", MODULE_PATH)
assert SPEC and SPEC.loader
vf = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = vf
SPEC.loader.exec_module(vf)


def digest(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


class FakeResponse:
    def __init__(self, payload: bytes, *, status: int, headers: dict[str, str] | None = None):
        self._stream = io.BytesIO(payload)
        self.status = status
        self.headers = headers or {}

    def getcode(self) -> int:
        return self.status

    def read(self, amount: int = -1) -> bytes:
        return self._stream.read(amount)

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class DownloadContractTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.target = self.root / "artifact.bin"
        self.payload = b"abcdef"
        self.sha = digest(self.payload)

    def tearDown(self):
        self.tmp.cleanup()

    def fetch(self, **kwargs):
        return vf.download_verified(
            "http://example.test/artifact.bin",
            self.target,
            len(self.payload),
            self.sha,
            label="test",
            stream=io.StringIO(),
            allow_http_for_tests=True,
            progress_interval=0,
            **kwargs,
        )

    def test_valid_target_never_opens_network(self):
        self.target.write_bytes(self.payload)
        with mock.patch.object(vf, "open_verified_url", side_effect=AssertionError("network")):
            self.assertEqual(self.fetch(), self.target)

    def test_complete_partial_is_verified_without_network(self):
        partial = self.target.with_name(self.target.name + ".partial")
        partial.write_bytes(self.payload)
        with mock.patch.object(vf, "open_verified_url", side_effect=AssertionError("network")):
            self.fetch()
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertFalse(partial.exists())

    def test_range_resume_keeps_prefix_and_activates_atomically(self):
        partial = self.target.with_name(self.target.name + ".partial")
        partial.write_bytes(b"abc")
        self.target.write_bytes(b"old-active")
        seen_range = []

        def open_request(request, timeout, allowed_schemes):
            self.assertEqual(timeout, 30.0)
            self.assertEqual(allowed_schemes, {"http", "https"})
            seen_range.append(request.get_header("Range"))
            return FakeResponse(
                b"def",
                status=206,
                headers={"Content-Range": "bytes 3-5/6"},
            )

        with mock.patch.object(vf, "open_verified_url", side_effect=open_request):
            self.fetch()
        self.assertEqual(seen_range, ["bytes=3-"])
        self.assertEqual(self.target.read_bytes(), self.payload)
        self.assertFalse(partial.exists())

    def test_server_ignoring_range_restarts_partial(self):
        partial = self.target.with_name(self.target.name + ".partial")
        partial.write_bytes(b"abc")
        with mock.patch.object(
            vf,
            "open_verified_url",
            return_value=FakeResponse(self.payload, status=200),
        ):
            self.fetch()
        self.assertEqual(self.target.read_bytes(), self.payload)

    def test_interrupted_download_leaves_resumable_partial(self):
        partial = self.target.with_name(self.target.name + ".partial")
        with mock.patch.object(
            vf,
            "open_verified_url",
            return_value=FakeResponse(b"abc", status=200),
        ):
            with self.assertRaisesRegex(vf.FetchError, "unvollstaendig"):
                self.fetch()
        self.assertEqual(partial.read_bytes(), b"abc")
        self.assertFalse(self.target.exists())

    def test_hash_mismatch_removes_partial_but_preserves_active_target(self):
        active = b"old-active"
        self.target.write_bytes(active)
        with mock.patch.object(
            vf,
            "open_verified_url",
            return_value=FakeResponse(b"ABCDEF", status=200),
        ):
            with self.assertRaisesRegex(vf.FetchError, "SHA-256-Mismatch"):
                self.fetch()
        self.assertEqual(self.target.read_bytes(), active)
        self.assertFalse(self.target.with_name(self.target.name + ".partial").exists())

    def test_insufficient_space_stops_before_network(self):
        downloader = mock.Mock(side_effect=AssertionError("must not download"))
        with mock.patch.object(vf.shutil, "disk_usage", return_value=mock.Mock(free=0)):
            with mock.patch.object(vf, "open_verified_url", downloader):
                with self.assertRaisesRegex(vf.FetchError, "zu wenig freier Platz"):
                    self.fetch()
        downloader.assert_not_called()

    def test_https_redirect_cannot_downgrade_to_http(self):
        handler = vf.StrictRedirectHandler({"https"})
        request = vf.urllib.request.Request("https://example.test/artifact.bin")
        with self.assertRaisesRegex(vf.FetchError, "Redirect auf unerlaubtes Ziel"):
            handler.redirect_request(
                request,
                None,
                302,
                "Found",
                {},
                "http://example.test/artifact.bin",
            )

    def test_http_error_is_reported_without_partial_activation(self):
        error = vf.urllib.error.HTTPError(
            "http://example.test/artifact.bin", 404, "Not Found", {}, None
        )
        with mock.patch.object(vf, "open_verified_url", side_effect=error):
            with self.assertRaisesRegex(vf.FetchError, "Download fehlgeschlagen"):
                self.fetch()
        error.close()
        self.assertFalse(self.target.exists())


class HuggingFaceContractTest(unittest.TestCase):
    REVISION = "a" * 40

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.cache = Path(self.tmp.name)
        self.payload = b"model"
        self.entry = {
            "id": "brain-test",
            "type": "hf",
            "hf_repo": "example/model",
            "pinned_revision": self.REVISION,
            "download_bytes": len(self.payload),
            "license_acceptance": "gemma",
            "license_url": "https://example.test/terms",
            "artifacts": [
                {"path": "model.bin", "bytes": len(self.payload), "sha256": digest(self.payload)}
            ],
        }

    def tearDown(self):
        self.tmp.cleanup()

    @property
    def snapshot(self) -> Path:
        return self.cache / "models--example--model" / "snapshots" / self.REVISION

    def test_license_must_be_explicitly_accepted(self):
        with self.assertRaisesRegex(vf.FetchError, "Lizenzentscheidung fehlt"):
            vf.fetch_hf_model(
                self.entry,
                self.cache,
                accepted_licenses=set(),
                snapshot_downloader=lambda **_kwargs: "unused",
                stream=io.StringIO(),
            )

    def test_missing_file_resumes_exact_revision_then_activates_ref(self):
        self.snapshot.mkdir(parents=True)
        calls = []

        def downloader(**kwargs):
            calls.append(kwargs)
            (self.snapshot / "model.bin").write_bytes(self.payload)
            return str(self.snapshot)

        returned = vf.fetch_hf_model(
            self.entry,
            self.cache,
            accepted_licenses={"gemma"},
            snapshot_downloader=downloader,
            stream=io.StringIO(),
        )
        self.assertEqual(returned, self.snapshot)
        self.assertEqual(calls[0]["revision"], self.REVISION)
        self.assertEqual(calls[0]["allow_patterns"], ["model.bin"])
        ref = self.cache / "models--example--model" / "refs" / "main"
        self.assertEqual(ref.read_bytes(), self.REVISION.encode("ascii"))

    def test_corrupted_existing_snapshot_fails_closed_without_downloader(self):
        self.snapshot.mkdir(parents=True)
        (self.snapshot / "model.bin").write_bytes(b"wrong")
        downloader = mock.Mock(side_effect=AssertionError("must not download"))
        with self.assertRaisesRegex(vf.FetchError, "(Groesse|SHA-256) falsch"):
            vf.fetch_hf_model(
                self.entry,
                self.cache,
                accepted_licenses={"gemma"},
                snapshot_downloader=downloader,
                stream=io.StringIO(),
            )
        downloader.assert_not_called()

    def test_valid_snapshot_needs_no_downloader(self):
        self.snapshot.mkdir(parents=True)
        (self.snapshot / "model.bin").write_bytes(self.payload)
        downloader = mock.Mock(side_effect=AssertionError("must not download"))
        vf.fetch_hf_model(
            self.entry,
            self.cache,
            accepted_licenses={"gemma"},
            snapshot_downloader=downloader,
            stream=io.StringIO(),
        )
        downloader.assert_not_called()


class ArchiveContractTest(unittest.TestCase):
    def test_rejects_symlink_escape(self):
        member = tarfile.TarInfo("jdk-root/Contents/Home/escape")
        member.type = tarfile.SYMTYPE
        member.linkname = "../../../../outside"
        with self.assertRaisesRegex(vf.FetchError, "verlaesst"):
            vf.validate_tar_members([member], "jdk-root")

    def test_rejects_second_archive_root(self):
        member = tarfile.TarInfo("other-root/bin/java")
        with self.assertRaisesRegex(vf.FetchError, "ausserhalb"):
            vf.validate_tar_members([member], "jdk-root")

    def test_jdk_extracts_to_temp_and_is_attested(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            source = root / "source" / "jdk-root" / "Contents" / "Home"
            (source / "bin").mkdir(parents=True)
            java = source / "bin" / "java"
            java.write_text("#!/bin/sh\n", encoding="utf-8")
            java.chmod(0o755)
            (source / "release").write_text(
                'IMPLEMENTOR="Example"\nJAVA_VERSION="21"\nOS_ARCH="aarch64"\n',
                encoding="utf-8",
            )
            archive = root / "jdk.tar.gz"
            with tarfile.open(archive, "w:gz") as bundle:
                bundle.add(root / "source" / "jdk-root", arcname="jdk-root")
            entry = {
                "id": "jdk-test",
                "sha256": digest(archive.read_bytes()),
                "installed_bytes": 1,
                "archive_root": "jdk-root",
                "java_home": "jdk-root/Contents/Home",
                "target_dir": "jdk-installed",
                "release": {"IMPLEMENTOR": "Example", "JAVA_VERSION": "21", "OS_ARCH": "aarch64"},
            }
            java_home = vf.install_jdk_archive(entry, archive, root / "artifacts")
            self.assertTrue((java_home / "bin" / "java").is_file())
            attestation = json.loads(
                (root / "artifacts" / "toolchains" / "jdk-installed" / ".hoshi-artifact.json").read_text()
            )
            self.assertEqual(attestation, {"id": "jdk-test", "sha256": entry["sha256"]})
            self.assertFalse(list((root / "artifacts" / "toolchains").glob("*.extracting-*")))


class LockContractTest(unittest.TestCase):
    def test_real_locks_are_self_consistent(self):
        locks = vf.load_locks(vf.DEFAULT_MODELS_LOCK, vf.DEFAULT_TOOLCHAINS_LOCK)
        self.assertIn("brain-e4b", locks.by_id)
        self.assertIn("jdk-21-macos-aarch64", locks.by_id)

    def test_floating_revision_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "models.json").write_text(
                json.dumps(
                    {
                        "version": 2,
                        "models": [
                            {
                                "id": "floating",
                                "type": "hf",
                                "pinned_revision": "main",
                                "download_bytes": 1,
                                "artifacts": [{"path": "x", "bytes": 1, "sha256": "0" * 64}],
                            }
                        ],
                    }
                )
            )
            (root / "toolchains.json").write_text(json.dumps({"version": 1, "artifacts": []}))
            with self.assertRaisesRegex(vf.FetchError, "40-stellige"):
                vf.load_locks(root / "models.json", root / "toolchains.json")


if __name__ == "__main__":
    unittest.main(verbosity=2)
