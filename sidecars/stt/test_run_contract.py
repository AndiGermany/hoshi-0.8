#!/usr/bin/env python3
"""Isolierte Vertragstests fuer run.sh; startet nie den echten STT-Sidecar."""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


RUN_SCRIPT = Path(__file__).with_name("run.sh")
PIN = "a" * 40
LOCKED_MODEL = "example/whisper-locked"


class SttRunContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.sidecar = self.root / "sidecars" / "stt"
        self.venv_bin = self.sidecar / ".venv" / "bin"
        self.tools = self.root / "tools"
        self.fake_bin = self.root / "fake-bin"
        for directory in (self.venv_bin, self.tools, self.fake_bin):
            directory.mkdir(parents=True)

        shutil.copy2(RUN_SCRIPT, self.sidecar / "run.sh")
        (self.sidecar / "server.py").write_text("# fixture\n", encoding="utf-8")
        (self.tools / "verified_fetch.py").write_text("# fixture\n", encoding="utf-8")
        (self.root / "models.json").write_text(
            json.dumps(
                {
                    "version": 2,
                    "models": [
                        {
                            "id": "stt-whisper",
                            "type": "hf",
                            "hf_repo": LOCKED_MODEL,
                            "pinned_revision": PIN,
                        }
                    ],
                }
            ),
            encoding="utf-8",
        )

        fake_python = self.venv_bin / "python"
        fake_python.write_text(
            """#!/usr/bin/env bash
set -eu
case "${1:-}" in
  -) exec python3 "$@" ;;
  -c) exit 0 ;;
  *verified_fetch.py)
    printf 'verify:%s offline:%s\n' "$*" "${HF_HUB_OFFLINE:-unset}" >> "$HOSHI_TEST_CAPTURE"
    exit "${HOSHI_TEST_VERIFY_RC:-0}"
    ;;
  *server.py)
    printf 'server:%s offline:%s\n' "$*" "${HF_HUB_OFFLINE:-unset}" >> "$HOSHI_TEST_CAPTURE"
    exit 0
    ;;
  *) exit 91 ;;
esac
""",
            encoding="utf-8",
        )
        fake_python.chmod(0o755)
        ffmpeg = self.fake_bin / "ffmpeg"
        ffmpeg.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        ffmpeg.chmod(0o755)
        self.capture = self.root / "capture.log"

    def tearDown(self):
        self.temp.cleanup()

    def run_script(self, **overrides: str) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env.update(
            {
                "HOME": str(self.root / "home"),
                "PATH": f"{self.fake_bin}:{env['PATH']}",
                "HOSHI_TEST_CAPTURE": str(self.capture),
            }
        )
        env.update(overrides)
        return subprocess.run(
            [str(self.sidecar / "run.sh")],
            cwd=self.root,
            env=env,
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )

    def captured(self) -> str:
        return self.capture.read_text(encoding="utf-8") if self.capture.exists() else ""

    def test_verify_only_hashes_lock_and_never_starts_server(self):
        result = self.run_script(HOSHI_STT_VERIFY_ONLY="1")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("verify:", self.captured())
        self.assertNotIn("server:", self.captured())
        self.assertIn("Verify-only OK", result.stderr)

    def test_unknown_model_override_fails_before_verify(self):
        result = self.run_script(
            HOSHI_STT_VERIFY_ONLY="1",
            HOSHI_STT_MODEL="example/not-locked",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("kein ungepinnter Start", result.stderr)
        self.assertEqual(self.captured(), "")

    def test_hash_failure_never_starts_server_and_names_fetch_command(self):
        result = self.run_script(
            HOSHI_STT_VERIFY_ONLY="1",
            HOSHI_TEST_VERIFY_RC="2",
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("verified_fetch.py fetch stt-whisper", result.stderr)
        self.assertNotIn("server:", self.captured())

    def test_normal_exec_is_offline_and_uses_locked_repo(self):
        result = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        capture = self.captured()
        self.assertIn("verify:", capture)
        self.assertIn(
            f"server:{self.sidecar / 'server.py'} --host 0.0.0.0 --port 9001 "
            f"--model {LOCKED_MODEL} offline:1",
            capture,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
