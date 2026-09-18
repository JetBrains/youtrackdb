#!/usr/bin/env python3
"""Focused regression tests for nightly LDBC storage installation and downloads."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import yaml

ROOT = Path(__file__).parents[2]
INSTALLER = ROOT / ".github/scripts/install-aws-cli-v2.sh"
DOWNLOADER = ROOT / ".github/scripts/download-ldbc-inputs.sh"
WORKFLOW = ROOT / ".github/workflows/ldbc-jmh-nightly.yml"
FINGERPRINT = "FB5DB77FD5C118B80511ADA8A6310ACC4672475C"


def write_executable(path, content):
    path.write_text(content, encoding="utf-8")
    path.chmod(0o755)


class InstallerTests(unittest.TestCase):
    """Verify authenticated installation, prerequisite failures, and startup failures."""

    def installer_environment(self, root, mock_bin):
        environment = os.environ.copy()
        environment.update({
            "PATH": f"{mock_bin}:{environment['PATH']}",
            "RUNNER_TEMP": str(root),
            "AWS_CLI_INSTALL_ROOT": str(root / "installed"),
            "AWS_CLI_BIN_DIR": str(root / "bin"),
        })
        return environment

    def install_mocks(self, root, *, valid_signature=True, startup_ok=True):
        mock_bin = root / "mock-bin"
        mock_bin.mkdir()
        write_executable(
            mock_bin / "curl",
            "#!/usr/bin/env bash\n"
            "while [ $# -gt 0 ]; do\n"
            "  if [ \"$1\" = --output ]; then printf payload > \"$2\"; exit 0; fi\n"
            "  shift\n"
            "done\nexit 2\n",
        )
        signature = FINGERPRINT if valid_signature else "0" * 40
        write_executable(
            mock_bin / "gpg",
            "#!/usr/bin/env bash\n"
            "case \" $* \" in\n"
            f"  *' --fingerprint '*) echo 'fpr:::::::::{FINGERPRINT}:' ;;\n"
            f"  *' --verify '*) echo '[GNUPG:] VALIDSIG {signature} 2026 0 0 0 0 0 0 0 0' ;;\n"
            "esac\n",
        )
        version = "aws-cli/2.36.48 Python/3.13.7 Linux/mock x86_64" if startup_ok else "broken"
        write_executable(
            mock_bin / "unzip",
            "#!/usr/bin/env bash\n"
            "destination=''\n"
            "while [ $# -gt 0 ]; do\n"
            "  if [ \"$1\" = -d ]; then destination=$2; break; fi\n"
            "  shift\n"
            "done\n"
            "mkdir -p \"$destination/aws\"\n"
            "cat > \"$destination/aws/install\" <<'INSTALL'\n"
            "#!/usr/bin/env bash\n"
            "while [ $# -gt 0 ]; do\n"
            "  case $1 in\n"
            "    --install-dir) install_dir=$2; shift 2 ;;\n"
            "    --bin-dir) bin_dir=$2; shift 2 ;;\n"
            "  esac\n"
            "done\n"
            "mkdir -p \"$install_dir\" \"$bin_dir\"\n"
            "cat > \"$bin_dir/aws\" <<'AWS'\n"
            "#!/usr/bin/env bash\n"
            f"echo '{version}'\n"
            "AWS\n"
            "chmod +x \"$bin_dir/aws\"\n"
            "INSTALL\n"
            "chmod +x \"$destination/aws/install\"\n",
        )
        return mock_bin

    def test_verified_installer_starts_exact_pinned_version(self):
        """A valid publisher signature installs and starts exactly the pinned AWS CLI release."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mock_bin = self.install_mocks(root)
            result = subprocess.run(
                ["bash", str(INSTALLER)],
                env=self.installer_environment(root, mock_bin),
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertIn("AWS CLI v2.36.48 installed and verified", result.stdout)

    def test_traversal_paths_fail_without_external_modification(self):
        """Traversal in either install path fails before creating an external directory."""
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            runner = base / "runner"
            runner.mkdir()
            mock_bin = self.install_mocks(runner)
            for variable, outside_name in (
                ("AWS_CLI_INSTALL_ROOT", "outside-install"),
                ("AWS_CLI_BIN_DIR", "outside-bin"),
            ):
                with self.subTest(variable=variable):
                    outside = base / outside_name
                    environment = self.installer_environment(runner, mock_bin)
                    environment[variable] = str(runner / ".." / outside_name)
                    result = subprocess.run(
                        ["bash", str(INSTALLER)],
                        env=environment,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("must remain below RUNNER_TEMP", result.stderr)
                    self.assertFalse(outside.exists())

    def test_symlink_descendants_fail_without_external_modification(self):
        """A symlink descendant in either install path cannot modify its external target."""
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            runner = base / "runner"
            outside = base / "outside"
            runner.mkdir()
            outside.mkdir()
            (runner / "escape").symlink_to(outside, target_is_directory=True)
            mock_bin = self.install_mocks(runner)
            for variable, child_name in (
                ("AWS_CLI_INSTALL_ROOT", "installed"),
                ("AWS_CLI_BIN_DIR", "bin"),
            ):
                with self.subTest(variable=variable):
                    environment = self.installer_environment(runner, mock_bin)
                    environment[variable] = str(runner / "escape" / child_name)
                    result = subprocess.run(
                        ["bash", str(INSTALLER)],
                        env=environment,
                        capture_output=True,
                        text=True,
                        check=False,
                    )
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("must remain below RUNNER_TEMP", result.stderr)
                    self.assertFalse((outside / child_name).exists())

    def test_canonical_valid_paths_under_symlinked_runner_temp_succeed(self):
        """Canonical paths below a symlinked RUNNER_TEMP remain valid installation targets."""
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            runner = base / "actual-runner"
            runner.mkdir()
            runner_alias = base / "runner-alias"
            runner_alias.symlink_to(runner, target_is_directory=True)
            mock_bin = self.install_mocks(runner)
            environment = self.installer_environment(runner_alias, mock_bin)
            environment["AWS_CLI_INSTALL_ROOT"] = str(
                runner_alias / "unused" / ".." / "installed"
            )
            environment["AWS_CLI_BIN_DIR"] = str(
                runner_alias / "unused" / ".." / "bin"
            )
            result = subprocess.run(
                ["bash", str(INSTALLER)],
                env=environment,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue((runner / "installed").is_dir())
            self.assertTrue((runner / "bin/aws").is_file())

    def test_unexpected_signature_prevents_archive_extraction(self):
        """A signature from any other key fails before the archive extraction command runs."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mock_bin = self.install_mocks(root, valid_signature=False)
            marker = root / "mock-bin/unzip"
            marker.write_text(
                "#!/usr/bin/env bash\ntouch \"$RUNNER_TEMP/unzip-ran\"\n",
                encoding="utf-8",
            )
            marker.chmod(0o755)
            result = subprocess.run(
                ["bash", str(INSTALLER)],
                env=self.installer_environment(root, mock_bin),
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertFalse((root / "unzip-ran").exists())

    def test_wrong_started_version_fails_visibly(self):
        """Installer success still fails when startup does not report the exact pinned release."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mock_bin = self.install_mocks(root, startup_ok=False)
            result = subprocess.run(
                ["bash", str(INSTALLER)],
                env=self.installer_environment(root, mock_bin),
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Installed AWS CLI version is unexpected", result.stderr)

    def test_missing_unzip_fails_with_named_prerequisite(self):
        """A missing unzip fails before downloads and names the prerequisite."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            mock_bin = root / "mock-bin"
            mock_bin.mkdir()
            for command in (
                "awk", "bash", "chmod", "curl", "gpg",
                "grep", "mkdir", "mktemp", "realpath", "rm", "uname",
            ):
                (mock_bin / command).symlink_to(shutil.which(command))
            result = subprocess.run(
                [str(mock_bin / "bash"), str(INSTALLER)],
                env={"PATH": str(mock_bin), "RUNNER_TEMP": str(root)},
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertIn("Required installer tool is unavailable: unzip", result.stderr)


class DownloaderTests(unittest.TestCase):
    """Verify exact downloads, failure semantics, endpoint checks, and ambient-state isolation."""

    def run_downloader(
        self,
        root,
        behavior="success",
        endpoint="https://fsn1.your-objectstorage.com",
    ):
        aws = root / "aws"
        log = root / "aws.log"
        write_executable(
            aws,
            "#!/usr/bin/env bash\n"
            "printf 'profile=%s token=%s config=%s credentials=%s home=%s\\n' "
            f"\"${{AWS_PROFILE-unset}}\" \"${{AMBIENT_TOKEN-unset}}\" \"$AWS_CONFIG_FILE\" "
            f"\"$AWS_SHARED_CREDENTIALS_FILE\" \"$HOME\" >> \"{log}\"\n"
            f"printf '%s\\n' \"$*\" >> \"{log}\"\n"
            f"if [[ \" $* \" == *' s3 ls '* ]] "
            f"&& [ '{behavior}' = list-fails ]; then exit 9; fi\n"
            "if [[ \" $* \" == *' s3 cp '* ]]; then\n"
            "  destination=${@: -2:1}\n"
            f"  [ '{behavior}' = command-fails ] && exit 8\n"
            f"  [ '{behavior}' = empty-output ] || printf object > \"$destination\"\n"
            "fi\n",
        )
        destinations = [
            root / "curated.json",
            root / "factors.json",
            root / "dataset.zst",
        ]
        environment = os.environ.copy()
        environment.update({
            "RUNNER_TEMP": str(root),
            "AWS_CLI": str(aws),
            "AWS_ACCESS_KEY_ID": "step-access",
            "AWS_SECRET_ACCESS_KEY": "step-secret",
            "HETZNER_S3_ENDPOINT": endpoint,
            "AWS_PROFILE": "ambient-profile",
            "AMBIENT_TOKEN": "ambient-token",
        })
        result = subprocess.run(
            ["bash", str(DOWNLOADER), *(str(path) for path in destinations)],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )
        return result, destinations, log

    def test_exact_inputs_download_with_isolated_environment(self):
        """Success lists once, downloads three exact keys, and hides inherited AWS state."""
        with tempfile.TemporaryDirectory() as directory:
            result, destinations, log = self.run_downloader(Path(directory))
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(all(path.stat().st_size > 0 for path in destinations))
            text = log.read_text(encoding="utf-8")
            self.assertEqual(1, text.count(" s3 ls "))
            self.assertEqual(3, text.count(" s3 cp "))
            self.assertIn("curated-params-v3.json", text)
            self.assertIn("factor-tables.json", text)
            self.assertIn("ldbc-sf1-composite-merged-fk.tar.zst", text)
            self.assertIn("profile=unset token=unset", text)
            self.assertNotIn("step-secret", text)
            self.assertNotIn("ambient-profile", text)
            self.assertEqual([], list(Path(directory).glob("aws-config.*")))

    def test_diagnostic_listing_failure_is_only_a_warning(self):
        """The diagnostic prefix listing can fail while all three mandatory downloads continue."""
        with tempfile.TemporaryDirectory() as directory:
            result, destinations, _ = self.run_downloader(Path(directory), "list-fails")
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertTrue(all(path.exists() for path in destinations))
            self.assertIn("::warning::Could not list", result.stdout)

    def test_failed_download_stops_remaining_inputs(self):
        """A nonzero required copy fails the step and prevents later benchmark inputs."""
        with tempfile.TemporaryDirectory() as directory:
            result, destinations, log = self.run_downloader(Path(directory), "command-fails")
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(any(path.exists() for path in destinations))
            self.assertEqual(1, log.read_text(encoding="utf-8").count(" s3 cp "))

    def test_empty_download_is_rejected(self):
        """An empty destination after copy is treated as partial failure."""
        with tempfile.TemporaryDirectory() as directory:
            result, destinations, _ = self.run_downloader(Path(directory), "empty-output")
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(any(path.exists() for path in destinations))
            self.assertIn("missing or empty", result.stderr)

    def test_non_https_or_unknown_endpoint_is_rejected_before_aws(self):
        """An unofficial or insecure endpoint fails before credentials are used."""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            result, _, log = self.run_downloader(
                root,
                endpoint="http://fsn1.your-objectstorage.com",
            )
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(log.exists())
            self.assertIn("official HTTPS Hetzner", result.stderr)


class WorkflowWiringTests(unittest.TestCase):
    """Verify actual nightly wiring and the focused test workflow execution path."""

    def test_nightly_workflow_wires_pinned_helpers_and_step_only_secrets(self):
        """The nightly job installs AWS CLI and passes secrets only to the download step."""
        document = yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))
        steps = document["jobs"]["benchmark"]["steps"]
        install = next(
            step for step in steps
            if step.get("name", "").startswith("Install CLI tools")
        )
        download = next(
            step for step in steps
            if step.get("name") == "Download nightly LDBC inputs from S3"
        )
        self.assertIn("install-aws-cli-v2.sh", install["run"])
        self.assertIn("download-ldbc-inputs.sh", download["run"])
        self.assertEqual(3, download["run"].count("/tmp/"))
        self.assertIn("HETZNER_S3_ACCESS_KEY", download["env"]["AWS_ACCESS_KEY_ID"])
        workflow_text = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("mc alias set", workflow_text)
        self.assertNotIn("MC_CONFIG_DIR=", workflow_text)
        self.assertIn("Remove stale object storage alias", workflow_text)

    def test_regressions_have_automatic_pull_request_execution(self):
        """The focused test workflow runs this suite when any storage migration file changes."""
        path = ROOT / ".github/workflows/ldbc-nightly-storage-tests.yml"
        document = yaml.safe_load(path.read_text(encoding="utf-8"))
        paths = document[True]["pull_request"]["paths"]
        self.assertIn(".github/scripts/ldbc-nightly-storage-test.py", paths)
        commands = "\n".join(
            str(step.get("run", ""))
            for step in document["jobs"]["tests"]["steps"]
        )
        self.assertIn("ldbc-nightly-storage-test.py", commands)


if __name__ == "__main__":
    unittest.main(verbosity=2)
