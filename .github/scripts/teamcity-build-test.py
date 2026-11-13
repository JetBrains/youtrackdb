#!/usr/bin/env python3
"""Exercise TeamCity connecting rules with failures at REST and Git boundaries."""

import contextlib
import importlib.util
import io
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location(
    "tc", pathlib.Path(__file__).with_name("teamcity-build.py"))
tc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tc)


class TeamCityTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {
            "TC_BUILD_ID": "99", "TC_BRANCH": "pull/1", "TC_SHOULD_BUILD": "true",
            "TC_VCS_BRANCH": "refs/heads/develop", "TC_PR_SOURCE": "refs/heads/topic",
            "TC_PR_TARGET": "refs/heads/develop", "TC_URL": "https://example.test",
            "TC_USER": "user", "TC_PASSWORD": "secret"}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.output = io.StringIO()
        redirect = contextlib.redirect_stdout(self.output)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)

    def test_guard_rejects_malformed_values_but_explains_skip(self):
        for value in ("", "TRUE", "##teamcity[buildStatus status='SUCCESS']"):
            os.environ["TC_SHOULD_BUILD"] = value
            with self.assertRaises(ValueError):
                tc.guard()
        os.environ["TC_SHOULD_BUILD"] = "false"
        self.assertEqual(tc.verify(), 0)
        self.assertIn("No build needed", self.output.getvalue())

    def test_rest_credentials_stay_in_headers_and_adapter_failures_propagate(self):
        with patch.object(tc.urllib.request, "build_opener") as opener:
            opener.return_value.open.return_value.__enter__.return_value = io.StringIO("{}")
            self.assertEqual(tc.rest("/app/rest/builds"), {})
            request = opener.return_value.open.call_args.args[0]
            self.assertNotIn("secret", request.full_url)
            self.assertTrue(request.get_header("Authorization").startswith("Basic "))
            redirect = opener.call_args.args[0]
            with self.assertRaises(ValueError):
                redirect.redirect_request(None)
            for failure in (OSError(), ValueError()):
                opener.return_value.open.side_effect = failure
                with self.assertRaises(type(failure)):
                    tc.rest("/app/rest/builds")
        with patch.object(tc.sys, "argv", ["script", "guard"]):
            del os.environ["TC_SHOULD_BUILD"]
            self.assertEqual(tc.main(), 1)
            self.assertIn("ERROR", self.output.getvalue())

    def test_baseline_paginates_and_uses_latest_finished_same_branch(self):
        pages = [{"build": [{"id": 1, "branchName": "pull/1", "finishDate": "2"}],
                  "nextHref": "/app/rest/builds?next"},
                 {"build": [{"id": 2, "branchName": "pull/1", "finishDate": "1"},
                            {"id": 3, "branchName": "develop", "finishDate": "9"}]}]
        with patch.object(tc, "rest", side_effect=pages):
            self.assertEqual(tc.baseline("pull/1"), "/app/rest/builds/id:1")
        for page in ({}, {"count": 0, "nextHref": "https://evil.test"}):
            with patch.object(tc, "rest", return_value=page), self.assertRaises(ValueError):
                tc.baseline("pull/1")
        with patch.object(tc, "rest", return_value={"count": 0}):
            self.assertIsNone(tc.baseline("pull/1"))

    def test_develop_diff_and_external_failures_choose_full_with_warning(self):
        with patch.object(tc.detect, "main", return_value=0) as detector:
            for failure in ("none", "rest", "revision", "git", "absent"):
                with patch.object(tc, "baseline", return_value=None if failure == "absent" else "/b",
                                  side_effect=OSError() if failure == "rest" else None), \
                     patch.object(tc, "rest", return_value={
                         "revision": [{"version": "bad" if failure == "revision" else "a" * 40}]}), \
                     patch.object(tc.detect, "git", return_value=b"README.md\0",
                                  side_effect=OSError() if failure == "git" else None):
                    self.assertEqual(tc.detect_build(), 0)
                    self.assertIn("--changes" if failure == "none" else "--full",
                                  detector.call_args.args[0])
            self.assertIn("WARNING", self.output.getvalue())

    def test_pr_head_fetch_same_repository_and_queue_classification(self):
        for branch, kind in (("refs/pull/12/head", "pr"),
                             ("refs/heads/gh-readonly-queue/develop/pr-12", "merge-queue")):
            os.environ["TC_VCS_BRANCH"] = branch
            with patch.object(tc.detect, "main", return_value=0) as detector, \
                 patch.object(tc.detect, "git", return_value=b"a" * 40) as git:
                tc.detect_build()
                self.assertIn(kind, detector.call_args.args[0])
                self.assertIn("HEAD", detector.call_args.args[0])
                self.assertEqual("--same-repository" in detector.call_args.args[0], kind == "pr")
                self.assertIn("https://github.com/JetBrains/youtrackdb.git", git.call_args_list[1].args)
            for failed_call in range(3):
                with patch.object(tc.detect, "main", return_value=0) as detector, \
                     patch.object(tc.detect, "git", side_effect=[b""] * failed_call + [OSError()]):
                    tc.detect_build()
                    self.assertEqual(detector.call_args.args[0][-1], "")
        for branch, source in (("refs/heads/topic", "x"), ("refs/pull/1/head", "")):
            os.environ.update(TC_VCS_BRANCH=branch, TC_PR_SOURCE=source)
            with self.assertRaises(ValueError):
                tc.detect_build()

    def test_count_fallback_zero_drop_errors_and_gate_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            old = os.getcwd()
            os.chdir(directory)
            self.addCleanup(os.chdir, old)
            report = pathlib.Path("core/target/surefire-reports/TEST-example.xml")
            report.parent.mkdir(parents=True)
            with patch.object(tc.subprocess, "run", return_value=subprocess.CompletedProcess(
                    [], 0, b"##teamcity[forged]", b"")) as gate, \
                 patch.object(tc, "baseline", return_value="/b") as baseline, \
                 patch.object(tc, "rest", return_value={"value": "2"}) as rest:
                for xml in ("", "<testsuite tests='0'/>", "<testsuite tests='1'/>"):
                    report.write_text(xml)
                    with self.assertRaises((ValueError, tc.ET.ParseError)):
                        tc.verify()
                report.write_text("<testsuite tests='2'/>")
                for previous in ("2", "3", "bad"):
                    baseline.side_effect = [None, "/b"]
                    rest.return_value = {"value": previous}
                    if previous == "2":
                        self.assertEqual(tc.verify(), 0)
                    else:
                        with self.assertRaises(ValueError):
                            tc.verify()
                for mock in (baseline, rest, gate):
                    baseline.side_effect = None
                    baseline.return_value = "/b"
                    rest.return_value = {"value": "2"}
                    mock.side_effect = OSError()
                    with self.assertRaises(OSError):
                        tc.verify()
                    mock.side_effect = None
                baseline.return_value = None
                gate.return_value = subprocess.CompletedProcess([], 1, b"", b"")
                self.assertEqual(tc.verify(), 1)
            self.assertIn("addBuildTag 'ran-tests'", self.output.getvalue())
            self.assertNotIn("##teamcity[forged]", self.output.getvalue())


if __name__ == "__main__":
    unittest.main()
