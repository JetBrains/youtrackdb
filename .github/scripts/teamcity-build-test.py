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
            "TC_BUILD_ID": "99", "TC_SHOULD_BUILD": "true",
            "TC_VCS_BRANCH": "refs/heads/develop", "TC_URL": "https://example.test",
            "TC_USER": "user", "TC_PASSWORD": "secret"}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.system = pathlib.Path(directory.name) / "build.properties"
        self.config = pathlib.Path(directory.name) / "config.properties"
        self.system.write_text("teamcity.configuration.properties.file="
                               + str(self.config).replace("\\", "\\\\") + "\n")
        os.environ["TEAMCITY_BUILD_PROPERTIES_FILE"] = str(self.system)
        self.pr_facts()
        self.output = io.StringIO()
        redirect = contextlib.redirect_stdout(self.output)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)

    def pr_facts(self, source="refs/heads/topic", target="refs/heads/develop"):
        self.config.write_text("teamcity.pullRequest.source.branch=" + source + "\n"
                               + "teamcity.pullRequest.target.branch=" + target + "\n",
                               encoding="iso-8859-1")

    def test_properties_decode_java_escapes_separators_and_continuations(self):
        self.config.write_bytes(
            b"  # comment\r\n!comment\rkey\\:part\\=x : va\\:l\\=ue\\\\path\n"
            b"white\\ key value\\ with\\ spaces\nunicode=\\u0064\\u00e9\n"
            b"continued=de\\\r\n \t vel\\\n  op\n"
            b"blank=\\\n  #value\ncontrols=\\t\\n\\r\\f\\z\n"
            b"even=two\\\\\ncolon:ok\nbare\nsame=first\nsame=last\nlatin=\xe9\n")
        self.assertEqual(tc.properties(self.config), {
            "key:part=x": "va:l=ue\\path", "white key": "value with spaces",
            "unicode": "dé", "continued": "develop", "blank": "#value",
            "controls": "\t\n\r\fz", "even": "two\\", "colon": "ok",
            "bare": "", "same": "last", "latin": "é"})

    def test_pr_reads_both_properties_files_and_accepts_both_target_forms(self):
        os.environ["TC_VCS_BRANCH"] = "refs/pull/12/head"
        # The path in the first file also needs Java properties unescaping.
        renamed = self.config.with_name("config:= file.properties")
        self.config.rename(renamed)
        self.config = renamed
        self.system.write_text("teamcity.configuration.properties.file="
                               + str(renamed).replace(":", r"\:").replace("=", r"\=")
                               .replace(" ", r"\ ") + "\n")
        for target in ("develop", "refs/heads/develop", r"refs/heads/de\u0076elop"):
            with self.subTest(target=target):
                self.pr_facts(target=target)
                with patch.object(tc.detect, "main", return_value=0) as detector, \
                     patch.object(tc.detect, "git", return_value=b"a" * 40) as git:
                    self.assertEqual(tc.detect_build(), 0)
                    self.assertIn("--same-repository", detector.call_args.args[0])
                    self.assertEqual(detector.call_args.args[0][-1], "a" * 40)
                    self.assertEqual(git.call_args_list[1].args[-1], "refs/heads/develop")

    def test_pr_without_source_or_readable_valid_files_fails_before_git_or_detection(self):
        os.environ["TC_VCS_BRANCH"] = "refs/pull/1/head"
        for content in ("", "teamcity.pullRequest.source.branch=\n",
                        r"teamcity.pullRequest.source.branch=\u12xy",
                        "teamcity.pullRequest.source.branch=topic\\"):
            with self.subTest(content=content):
                self.config.write_text(content)
                self.assert_pr_failure()
        self.pr_facts()
        for path in (self.system, self.config):
            with self.subTest(missing=path):
                data = path.read_bytes()
                path.unlink()
                self.assert_pr_failure()
                path.write_bytes(data)
        for path in (self.system, self.config):
            original = pathlib.Path.read_text

            def read(file, *args, **kwargs):
                if file == path:
                    raise PermissionError("Unreadable agent properties")
                return original(file, *args, **kwargs)

            with self.subTest(unreadable=path), patch.object(pathlib.Path, "read_text", read):
                self.assert_pr_failure()
        for content in ("unrelated=value", r"broken=\uXXXX"):
            self.system.write_text(content)
            self.assert_pr_failure()
        del os.environ["TEAMCITY_BUILD_PROPERTIES_FILE"]
        self.assert_pr_failure()

    def assert_pr_failure(self):
        self.output.seek(0)
        self.output.truncate()
        with patch.object(tc.detect, "git") as git, patch.object(tc.detect, "main") as detector, \
             patch.object(tc.sys, "argv", ["script", "detect"]):
            self.assertEqual(tc.main(), 1)
            git.assert_not_called()
            detector.assert_not_called()
            self.assertIn("status='ERROR'", self.output.getvalue())

    def test_hostile_pr_text_never_becomes_a_service_message_or_git_option(self):
        os.environ["TC_VCS_BRANCH"] = "refs/pull/1/head"
        for target in ("", "--upload-pack=evil", "refs/heads/--upload-pack=evil",
                       r"develop\n##teamcity[buildStatus status='SUCCESS']",
                       "refs/heads/topic", "refs/heads/develop:other", "develop "):
            self.output.seek(0)
            self.output.truncate()
            self.pr_facts(source=r"topic\n##teamcity[forged]", target=target)
            with self.subTest(target=target), \
                 patch.object(tc.detect, "git") as git, \
                 patch.object(tc.detect, "main", return_value=0) as detector:
                self.assertEqual(tc.detect_build(), 0)
                git.assert_not_called()
                self.assertEqual(detector.call_args.args[0][-1], "")
                self.assertIn("WARNING", self.output.getvalue())
        self.pr_facts(source=r"topic\n##teamcity[forged]", target="develop")
        with patch.object(tc.detect, "git", return_value=b"a" * 40) as git, \
             patch.object(tc.detect, "main", return_value=0):
            self.assertEqual(tc.detect_build(), 0)
            self.assertEqual(git.call_args_list[1].args[-1], "refs/heads/develop")
        self.assertNotIn("##teamcity[forged]", self.output.getvalue())
        self.assertNotIn("##teamcity[buildStatus", self.output.getvalue())

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
        def build(id, logical, full, finished):
            return {"id": id, "branchName": logical, "finishDate": finished,
                    "revisions": {"revision": [{"version": "a" * 40, "vcsBranchName": full}]}}

        pages = [{"build": [build(1, "old-logical-name", "refs/pull/1/head", "2")],
                  "nextHref": "/app/rest/builds?next"},
                 {"build": [build(2, "pull/1", "refs/pull/1/head", "1"),
                            build(3, "pull/1", "refs/pull/2/head", "9"),
                            build(99, "pull/1", "refs/pull/1/head", "9")]}]
        with patch.object(tc, "rest", side_effect=pages) as rest:
            self.assertEqual(tc.baseline("refs/pull/1/head"), "/app/rest/builds/id:1")
            query = tc.urllib.parse.parse_qs(tc.urllib.parse.urlsplit(
                rest.call_args_list[0].args[0]).query)
            self.assertIn("revisions(revision(version,vcsBranchName))", query["fields"][0])
            self.assertNotIn("refs/pull/1/head", query["locator"][0])
        with patch.object(tc, "rest", return_value={
                "build": [build(4, "<default>", "refs/heads/develop", "3")]}):
            self.assertEqual(tc.baseline("refs/heads/develop"), "/app/rest/builds/id:4")
            self.assertIsNone(tc.baseline("refs/pull/1/head"))
        for page in ({}, {"count": 0, "nextHref": "https://evil.test"}):
            with patch.object(tc, "rest", return_value=page), self.assertRaises(ValueError):
                tc.baseline("refs/pull/1/head")
        with patch.object(tc, "rest", return_value={"count": 0}):
            self.assertIsNone(tc.baseline("refs/pull/1/head"))

    def test_baseline_rejects_missing_or_unreadable_vcs_branches(self):
        for revisions in ([], [{}], [{"vcsBranchName": None}], [{"vcsBranchName": ""}],
                          [{"vcsBranchName": 1}], [{"vcsBranchName": "   "}]):
            with self.subTest(revisions=revisions), patch.object(tc, "rest", return_value={
                    "build": [{"id": 1, "branchName": "other", "finishDate": "9",
                               "revisions": {"revision": revisions}}]}):
                with self.assertRaises(ValueError):
                    tc.baseline("refs/heads/develop")
                with patch.object(tc.detect, "main", return_value=0) as detector:
                    self.assertEqual(tc.detect_build(), 0)
                    self.assertIn("--full", detector.call_args.args[0])
                    self.assertIn("WARNING", self.output.getvalue())

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
            os.environ["TC_VCS_BRANCH"] = branch
            self.pr_facts(source=source)
            with self.assertRaises(ValueError):
                tc.detect_build()

    def test_count_fallback_zero_drop_errors_and_gate_failure(self):
        os.environ["TC_VCS_BRANCH"] = "refs/pull/1/head"
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
                    baseline.reset_mock()
                    baseline.side_effect = [None, "/b"]
                    rest.return_value = {"value": previous}
                    if previous == "2":
                        self.assertEqual(tc.verify(), 0)
                    else:
                        with self.assertRaises(ValueError):
                            tc.verify()
                    self.assertEqual(baseline.call_args_list,
                                     [unittest.mock.call("refs/pull/1/head"),
                                      unittest.mock.call("refs/heads/develop")])
                baseline.side_effect = ValueError("Unreadable TeamCity VCS branch")
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
