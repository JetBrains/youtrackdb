#!/usr/bin/env python3
"""Exercise TeamCity connecting rules with failures at REST and Git boundaries."""

import contextlib
import importlib.util
import io
import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import unittest
import urllib.error
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
        self.addCleanup(self.assert_safe_output)
        redirect = contextlib.redirect_stdout(self.output)
        redirect.__enter__()
        self.addCleanup(redirect.__exit__, None, None, None)

    def assert_safe_output(self):
        self.assertNotIn("secret", self.output.getvalue())
        self.assertNotIn("##teamcity[forged]", self.output.getvalue())

    def assert_failure(self, identity, text):
        self.assert_safe_output()
        self.assertIn(f"##teamcity[message text='{text}' status='ERROR']",
                      self.output.getvalue())
        self.assertIn(f"##teamcity[buildProblem description='{text}' identity='{identity}']",
                      self.output.getvalue())

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
            self.assert_failure("pr-source", "PR has no readable same-repository source.")

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
            with patch.object(tc.sys, "argv", ["script", "guard"]):
                self.assertEqual(tc.main(), 1)
                self.assert_failure("detect-should-build",
                                    "detect.should_build is missing or malformed.")
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

    @contextlib.contextmanager
    def transport(self, *responses):
        """Keep the real request and decoding code, replacing only the network opener."""
        pending = iter(responses)
        requests = []

        def open_response(request, timeout):
            self.assertEqual(timeout, 30)
            self.assertEqual(request.get_header("Authorization"), "Basic dXNlcjpzZWNyZXQ=")
            requests.append(request)
            response = next(pending)
            if callable(response):
                response = response(request)
            if isinstance(response, Exception):
                raise response
            return io.BytesIO(response) if isinstance(response, bytes) else response

        with patch.object(tc.urllib.request, "build_opener") as opener:
            opener.return_value.open.side_effect = open_response
            yield requests
            for call in opener.call_args_list:
                with self.assertRaises(ValueError):
                    call.args[0].redirect_request(None)

    def pilot_history(self):
        branch = "refs/pull/1454/head"
        return json.dumps({"count": 6, "build": [
            self.build(7300, branch, "SUCCESS", "0"),
            self.build(7336, branch, "SUCCESS", "1"),
            self.build(7409, branch, "UNKNOWN", "2"),
            self.build(7410, branch, "FAILURE", "3"),
            self.build(7428, branch, "SUCCESS", "4"),
            self.build(7430, "refs/heads/develop", "SUCCESS", "5")]}).encode()

    def test_plain_statistic_transport_compares_pilot_counts_and_keeps_gate_failure(self):
        # Older success 7300 has 78700 tests, so using it would hide the drop from 7336.
        # Newer failed/interrupted builds, this build and develop cannot replace 7336.
        def statistic(request):
            return {"/app/rest/builds/id:7300/statistics/ytdb.tests": b"78700\n",
                    "/app/rest/builds/id:7336/statistics/ytdb.tests": b"78740\n"}[
                        tc.urllib.parse.urlsplit(request.full_url).path]

        os.environ.update(TC_VCS_BRANCH="refs/pull/1454/head", TC_BUILD_ID="7428")
        for count in (78726, 78740, 78741):
            for gate_code in (0, 1):
                with self.subTest(count=count, gate=gate_code), self.reports() as (report, gate), \
                     self.transport(self.pilot_history(), statistic) as requests:
                    self.output.seek(0)
                    self.output.truncate()
                    report.write_text(f"<testsuite tests='{count}' failures='{gate_code}'/>")
                    gate.return_value.returncode = gate_code
                    result = tc.main()
                    self.assertEqual(len(requests), 2)
                    self.assertEqual(requests[0].get_header("Accept"), "application/json")
                    self.assertEqual(requests[1].get_header("Accept"), "text/plain")
                    self.assertEqual((requests[1].full_url, result), (
                        "https://example.test/app/rest/builds/id:7336/statistics/ytdb.tests",
                        int(bool(gate_code or count < 78740))))
                    if gate_code:
                        self.assert_failure(
                            "test-failure-gate",
                            "Test-failure gate failed: failed tests or missing or unreadable reports.")
                    if count < 78740:
                        self.assert_failure("test-count-drop",
                                            "Test count dropped: 78726, comparison: 78740.")
                    else:
                        self.assertIn(f"Test count: {count}, baseline: 78740.",
                                      self.output.getvalue())

    def test_statistic_bytes_require_a_positive_ascii_integer(self):
        # Only ordinary surrounding whitespace is accepted, not JSON or Unicode digits.
        os.environ.update(TC_VCS_BRANCH="refs/pull/1454/head", TC_BUILD_ID="7428")
        for raw in (b"", b"0", b"-1", b"+2", b"1.0", b"1 2", b'"2"',
                    b'{"value":"2"}', b"null", b"true", b"9" * 5000,
                    "２".encode(), "\u00a02\u00a0".encode(), b"\xff2",
                    b"secret##teamcity[forged]"):
            with self.subTest(raw=raw[:40]), self.reports(), \
                 self.transport(self.pilot_history(), raw):
                self.output.seek(0)
                self.output.truncate()
                self.assertEqual(tc.main(), 1)
                self.assert_failure("comparison-count", "Test-count comparison count is invalid.")
                self.assertNotIn("comparison-read", self.output.getvalue())
        with self.reports(), self.transport(self.pilot_history(), b" \t\r\n\v\f2 \n"):
            self.assertEqual(tc.main(), 0)

    def test_transport_read_failures_name_the_stage_without_leaking_details(self):
        # HTTP/open/read failures must not look like missing history or lose the report failure.
        hostile = "secret Authorization: ##teamcity[forged]"
        os.environ.update(TC_VCS_BRANCH="refs/pull/1454/head", TC_BUILD_ID="7428")

        class Unreadable(io.BytesIO):
            def read(self, *args):
                raise OSError(hostile)

        for stage in ("history lookup", "statistic read"):
            for kind in (403, 406, "open", "read", "json"):
                if kind == "json" and stage != "history lookup":
                    continue
                if isinstance(kind, int):
                    response = urllib.error.HTTPError(
                        "https://example.test/secret", kind, hostile, {}, None)
                    self.addCleanup(response.close)
                elif kind == "open":
                    response = OSError(hostile)
                elif kind == "read":
                    response = Unreadable()
                else:
                    response = hostile.encode()
                responses = ([self.pilot_history()] if stage == "statistic read" else [])
                with self.subTest(stage=stage, kind=kind), self.reports() as (_, gate), \
                     self.transport(*responses, response) as requests:
                    self.output.seek(0)
                    self.output.truncate()
                    gate.return_value.returncode = 1
                    self.assertEqual(tc.main(), 1)
                    self.assertEqual(len(requests), 2 if stage == "statistic read" else 1)
                    self.assert_failure("comparison-read",
                                        f"Test-count comparison {stage} failed.")
                    self.assert_failure(
                        "test-failure-gate",
                        "Test-failure gate failed: failed tests or missing or unreadable reports.")
                    self.assertNotIn("No test-count comparison point exists.", self.output.getvalue())
                    self.assertNotIn("example.test", self.output.getvalue())

    def test_baseline_paginates_and_uses_latest_finished_same_branch(self):
        def build(id, logical, full, finished):
            return {"id": id, "branchName": logical, "finishDate": finished,
                    "revisions": {"revision": [{"version": "a" * 40, "vcsBranchName": full}]}}

        pages = [{"build": [build(1, "old-logical-name", "refs/pull/1/head", "2")],
                  "nextHref": "/app/rest/builds?next"},
                 {"build": [build(2, "pull/1", "refs/pull/1/head", "1"),
                            build(3, "pull/1", "refs/pull/2/head", "9"),
                            build(99, "pull/1", "refs/pull/1/head", "9")]}]
        for counted in (False, True):
            if counted:
                for page in pages:
                    page["count"] = len(page["build"])
            with self.subTest(counted=counted), \
                 patch.object(tc, "rest", side_effect=pages) as rest:
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

    def test_inconsistent_build_pages_fail_closed_but_empty_history_passes(self):
        unrelated = self.build(1, "refs/pull/2/head")
        invalid = [
            {"count": 2, "build": [unrelated]},  # Nonempty truncated gate counterexample.
            {"count": 0, "build": [unrelated]},  # Count below the page length.
            {"count": 1, "build": []}, {"count": 1},
            {"count": 0, "build": {}}, {"count": 0, "build": None},
            {"count": 0, "build": "bad"}, {"build": None}, {"build": "bad"},
            {}, {"build": []}]
        for count in (None, "1", True, False, -1, 1.0, "secret##teamcity[forged]"):
            for entries in ([], [unrelated]):
                invalid.append({"count": count, "build": entries})
            invalid.append({"count": count})
        # The same validation applies to the first page and every continuation.
        for page in invalid:
            for later in (False, True):
                pages = ([{"count": 1, "build": [unrelated],
                           "nextHref": "/app/rest/builds?next"}] if later else []) + [page]
                with self.subTest(page=page, later=later), self.reports(), \
                     patch.object(tc, "rest", side_effect=pages):
                    self.output.seek(0)
                    self.output.truncate()
                    self.assertEqual(tc.main(), 1)
                    self.assert_failure(
                        "comparison-read",
                        "Test-count comparison history lookup failed.")
                    self.assertNotIn("No test-count comparison point exists.", self.output.getvalue())
                    self.assertNotIn("unexpected-error", self.output.getvalue())
                # Develop detection must warn and build fully instead of using partial history.
                with self.subTest(page=page, later=later, caller="detect"), \
                     patch.object(tc, "rest", side_effect=pages), \
                     patch.object(tc.detect, "git") as git, \
                     patch.object(tc.detect, "main", return_value=0) as detector:
                    self.output.seek(0)
                    self.output.truncate()
                    self.assertEqual(tc.detect_build(), 0)
                    self.assertEqual(detector.call_args.args[0][-1], "--full")
                    git.assert_not_called()
                    self.assertIn("Full develop build fallback: ValueError", self.output.getvalue())
                    self.assertIn("status='WARNING'", self.output.getvalue())
                    self.assert_safe_output()
        # Explicit zero may omit the list. An absent count needs a nonempty actual list.
        for page in ({"count": 0}, {"count": 0, "build": []},
                     {"count": 1, "build": [unrelated]}, {"build": [unrelated]}):
            with self.subTest(valid=page), self.reports(), \
                 patch.object(tc, "rest", return_value=page):
                self.output.seek(0)
                self.output.truncate()
                self.assertEqual(tc.main(), 0)
                self.assertIn("No test-count comparison point exists.", self.output.getvalue())
                self.assertNotIn("comparison-read", self.output.getvalue())

    def test_develop_real_git_and_detector_emit_decision_for_java_and_markdown(self):
        # Only REST is replaced. The real diff file must reach the real detector.
        with tempfile.TemporaryDirectory() as directory:
            old = os.getcwd()
            try:
                os.chdir(directory)
                tc.detect.git(".", "init", "-q")
                tc.detect.git(".", "config", "user.name", "Test")
                tc.detect.git(".", "config", "user.email", "test@example.test")
                pathlib.Path("pom.xml").write_text(
                    "<project xmlns='http://maven.apache.org/POM/4.0.0'>"
                    "<modules><module>core</module></modules></project>")
                pathlib.Path("core").mkdir()
                pathlib.Path("core/pom.xml").write_text("<project/>")
                tc.detect.git(".", "add", ".")
                tc.detect.git(".", "-c", "core.hooksPath=/dev/null", "commit", "-qm", "Base")
                previous = tc.detect.git(".", "rev-parse", "HEAD").strip().decode()
                for path, decision in (("README.md", "false"),
                                       ("core/Example.java", "true")):
                    pathlib.Path(path).write_text("Example")
                    tc.detect.git(".", "add", path)
                    tc.detect.git(".", "-c", "core.hooksPath=/dev/null",
                                  "commit", "-qm", "Change")
                    self.output.seek(0)
                    self.output.truncate()
                    with self.subTest(path=path), patch.object(tc, "rest", side_effect=[
                            {"build": [self.build(1, "refs/heads/develop")]},
                            {"revision": [{"version": previous}]}]):
                        self.assertEqual(tc.detect_build(), 0)
                        self.assertIn(
                            "setParameter name='detect.should_build' value='" + decision + "'",
                            self.output.getvalue())
                        self.assertNotIn("WARNING", self.output.getvalue())
            finally:
                os.chdir(old)

    def test_verify_real_surefire_gate_passes_green_and_names_failures(self):
        # Run the actual gate from the fixture checkout, without a subprocess mock.
        scripts = pathlib.Path(__file__).resolve().parent
        for attributes, code, identity, cause in (
                ("", 0, None, None),
                ("failures='1'", 1, "test-failure-gate",
                 "Test-failure gate failed: failed tests or missing or unreadable reports."),
                ("skipped='2'", 1, "no-executed-tests",
                 "No reported tests were executed."),
                ("skipped='1'", 0, None, None)):
            with self.subTest(attributes=attributes), tempfile.TemporaryDirectory() as directory:
                old = os.getcwd()
                try:
                    os.chdir(directory)
                    gate = pathlib.Path(".github/scripts/test-failure-gate.py")
                    gate.parent.mkdir(parents=True)
                    shutil.copyfile(scripts / gate.name, gate)
                    report = pathlib.Path("core/target/surefire-reports/TEST-example.xml")
                    report.parent.mkdir(parents=True)
                    report.write_text("<testsuite tests='2' " + attributes + "/>")
                    self.output.seek(0)
                    self.output.truncate()
                    with patch.object(tc, "rest", side_effect=[
                            {"build": [self.build(1, "refs/heads/develop")]},
                            b"2"]), \
                         patch.object(tc.sys, "argv", ["script", "verify"]):
                        self.assertEqual(tc.main(), code)
                    if identity:
                        self.assert_failure(identity, cause)
                    self.assertNotIn("unexpected-error", self.output.getvalue())
                    self.assertIn("key='ytdb.tests' value='2'", self.output.getvalue())
                finally:
                    os.chdir(old)

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
                self.assertIn("http.https://github.com/.extraheader=", git.call_args_list[1].args)
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
            with patch.object(tc.sys, "argv", ["script", "detect"]):
                self.assertEqual(tc.main(), 1)
                self.assert_failure(
                    "vcs-branch" if source else "pr-source",
                    "Unsupported VCS branch." if source
                    else "PR has no readable same-repository source.")

    @contextlib.contextmanager
    def reports(self):
        with tempfile.TemporaryDirectory() as directory:
            old = os.getcwd()
            try:
                os.chdir(directory)
                report = pathlib.Path("core/target/surefire-reports/TEST-example.xml")
                report.parent.mkdir(parents=True)
                report.write_text("<testsuite tests='2'/>")
                with patch.object(tc.subprocess, "run", return_value=subprocess.CompletedProcess(
                        [], 0, b"##teamcity[forged]", b"")) as gate, \
                     patch.object(tc.sys, "argv", ["script", "verify"]):
                    yield report, gate
            finally:
                os.chdir(old)

    def build(self, id, branch, status="SUCCESS", finished="1"):
        return {"id": id, "status": status, "finishDate": finished,
                "revisions": {"revision": [{"version": "a" * 40, "vcsBranchName": branch}]}}

    def test_comparison_ignores_newer_failed_build_and_uses_older_success(self):
        os.environ["TC_VCS_BRANCH"] = branch = "refs/pull/1/head"
        pages = [{"build": [self.build(2, branch, "FAILURE", "9")],
                  "nextHref": "/app/rest/builds?next"},
                 {"build": [self.build(1, branch), self.build(99, branch, None, "99"),
                            self.build(3, "refs/pull/2/head", None, "99")]},
                 b"2"]
        for counted in (False, True):
            if counted:
                for page in pages[:2]:
                    page["count"] = len(page["build"])
            with self.subTest(counted=counted), self.reports(), \
                 patch.object(tc, "rest", side_effect=pages) as rest:
                self.assertEqual(tc.main(), 0)
                self.assertEqual(rest.call_args_list[-1].args[0],
                                 "/app/rest/builds/id:1/statistics/ytdb.tests")
                query = tc.urllib.parse.parse_qs(tc.urllib.parse.urlsplit(
                    rest.call_args_list[0].args[0]).query)
                self.assertIn("status", query["fields"][0])
                self.assertNotIn(branch, query["locator"][0])

    def test_only_failed_branch_builds_fall_back_to_successful_develop_or_none(self):
        os.environ["TC_VCS_BRANCH"] = branch = "refs/pull/1/head"
        for status in ("SUCCESS", "FAILURE"):
            with self.subTest(develop_status=status), self.reports(), \
                 patch.object(tc, "rest", side_effect=[
                     {"build": [self.build(1, branch, "FAILURE")]},
                     {"build": [self.build(1, branch, "FAILURE"),
                                self.build(2, "refs/heads/develop", status)]},
                     b"2"]) as rest:
                self.output.seek(0)
                self.output.truncate()
                self.assertEqual(tc.main(), 0)
                if status == "SUCCESS":
                    self.assertEqual(rest.call_args_list[-1].args[0],
                                     "/app/rest/builds/id:2/statistics/ytdb.tests")
                else:
                    self.assertEqual(rest.call_count, 2)
                    self.assertIn("No test-count comparison point exists.", self.output.getvalue())

    def test_matching_unreadable_status_raises_and_comparison_fails_closed(self):
        for status in (None, "", "   ", 1, []):
            build = self.build(1, "refs/heads/develop", status)
            for missing in (False, True):
                if missing:
                    build.pop("status", None)
                with self.subTest(status=status, missing=missing), self.reports(), \
                     patch.object(tc, "rest", return_value={"build": [build]}):
                    self.output.seek(0)
                    self.output.truncate()
                    with self.assertRaises(ValueError):
                        tc.baseline("refs/heads/develop", successful=True)
                    self.assertEqual(tc.main(), 1)
                    self.assert_failure(
                        "comparison-read",
                        "Test-count comparison history lookup failed.")

    def test_develop_change_base_still_uses_failed_tagged_build(self):
        with patch.object(tc, "rest", side_effect=[
                {"build": [self.build(1, "refs/heads/develop", "FAILURE", "9"),
                           self.build(2, "refs/heads/develop", finished="1")]},
                {"revision": [{"version": "a" * 40}]}]) as rest, \
             patch.object(tc.detect, "git", return_value=b"README.md\0"), \
             patch.object(tc.detect, "main", return_value=0) as detector:
            self.assertEqual(tc.detect_build(), 0)
            self.assertEqual(rest.call_args_list[-1].args[0], "/app/rest/builds/id:1/revisions")
            self.assertIn("--changes", detector.call_args.args[0])

    def test_zero_missing_or_malformed_reports_have_fixed_failures(self):
        for xml in ("", "<testsuite tests='0'/>", "<testsuite tests='bad'/>", None):
            with self.subTest(xml=xml), self.reports() as (report, gate):
                self.output.seek(0)
                self.output.truncate()
                if xml is None:
                    report.unlink()
                    gate.return_value.returncode = 1
                else:
                    report.write_text(xml)
                self.assertEqual(tc.main(), 1)
                if xml in (None, "<testsuite tests='0'/>"):
                    self.assert_failure("no-tests", "No tests were reported.")
                if xml != "<testsuite tests='0'/>":
                    self.assert_failure(
                        "test-failure-gate",
                        "Test-failure gate failed: failed tests or missing or unreadable reports.")

    def test_gate_failure_and_count_drop_are_each_reported_alone_and_together(self):
        for gate_code, previous in ((0, "2"), (0, "3"), (1, "2"), (1, "3")):
            with self.subTest(gate=gate_code, previous=previous), self.reports() as (_, gate), \
                 patch.object(tc, "baseline", return_value="/b") as baseline, \
                 patch.object(tc, "rest", return_value=previous.encode()):
                self.output.seek(0)
                self.output.truncate()
                gate.return_value.returncode = gate_code
                self.assertEqual(tc.main(), int(bool(gate_code or previous == "3")))
                baseline.assert_called_once_with("refs/heads/develop", successful=True)
                if gate_code:
                    self.assert_failure(
                        "test-failure-gate",
                        "Test-failure gate failed: failed tests or missing or unreadable reports.")
                if previous == "3":
                    self.assert_failure("test-count-drop", "Test count dropped: 2, comparison: 3.")
                self.assertIn("key='ytdb.tests' value='2'", self.output.getvalue())
                self.assertIn("addBuildTag 'ran-tests'", self.output.getvalue())

    def test_invalid_comparison_counts_fail_with_fixed_text(self):
        for data in ({}, None, {"value": "bad"}, {"value": "0"}, {"value": "-1"},
                     {"value": None}, {"value": True}, {"value": "9" * 5000},
                     {"value": "secret##teamcity[forged]"}):
            with self.subTest(data=data), self.reports(), \
                 patch.object(tc, "baseline", return_value="/b"), \
                 patch.object(tc, "rest", return_value=data):
                self.output.seek(0)
                self.output.truncate()
                self.assertEqual(tc.main(), 1)
                self.assert_failure("comparison-count", "Test-count comparison count is invalid.")

    def test_rest_listing_gate_and_unexpected_errors_never_leak_exception_text(self):
        for target in ("baseline", "rest", "gate", "unexpected"):
            with self.subTest(target=target), self.reports() as (_, gate), \
                 patch.object(tc, "baseline", return_value="/b") as baseline, \
                 patch.object(tc, "rest", return_value=b"2") as rest:
                self.output.seek(0)
                self.output.truncate()
                error = OSError("secret Authorization: ##teamcity[forged]")
                if target == "unexpected":
                    context = patch.object(tc, "guard", side_effect=error)
                else:
                    {"baseline": baseline, "rest": rest, "gate": gate}[target].side_effect = error
                    context = contextlib.nullcontext()
                with context:
                    self.assertEqual(tc.main(), 1)
                if target in ("baseline", "rest"):
                    stage = "history lookup" if target == "baseline" else "statistic read"
                    self.assert_failure("comparison-read",
                                        f"Test-count comparison {stage} failed.")
                elif target == "gate":
                    self.assert_failure(
                        "test-failure-gate",
                        "Test-failure gate failed: failed tests or missing or unreadable reports.")
                else:
                    self.assert_failure("unexpected-error",
                                        "TeamCity connecting script failed: OSError")


class ReadyBuildTest(unittest.TestCase):
    def setUp(self):
        self.env = {
            "TC_SHOULD_BUILD": "true", "TC_RUN_INTEGRATION_TESTS": "true",
            "TC_INTEGRATION_SKIP_REASON": "", "TC_INTEGRATION_SCOPE": "full",
            "TC_INTEGRATION_MODULES": "",
        }
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        checkout = pathlib.Path(directory.name)
        self.addCleanup(os.chdir, pathlib.Path.cwd())
        os.chdir(checkout)
        modules = ("core", "server", "tinkerpop/gremlin-core")
        pathlib.Path("pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>'
            + "".join(f"<module>{name}</module>" for name in modules) + "</modules></project>")
        for name in modules:
            pom = pathlib.Path(name, "pom.xml")
            pom.parent.mkdir(parents=True)
            pom.touch()

    def run_ready(self, kind, action, **decision):
        self.output = io.StringIO()
        with patch.dict(os.environ, self.env | decision, clear=True), \
             patch.object(tc.sys, "argv", ["adapter", "ready", kind, action]), \
             contextlib.redirect_stdout(self.output):
            return tc.main()

    def test_unit_and_integration_guard_allow_full_and_selected_maven(self):
        for kind in ("surefire", "failsafe"):
            for scope, modules in (("full", ""), ("selected", "core,tinkerpop/gremlin-core")):
                with self.subTest(kind=kind, scope=scope):
                    self.assertEqual(self.run_ready(kind, "guard", TC_INTEGRATION_SCOPE=scope,
                                                    TC_INTEGRATION_MODULES=modules), 0)
                    self.assertEqual(self.output.getvalue(), "")

    def test_skip_only_suppresses_the_applicable_build(self):
        for kind in ("surefire", "failsafe"):
            with self.subTest(kind=kind), patch.object(tc.subprocess, "run") as run:
                skip = dict(TC_RUN_INTEGRATION_TESTS="false",
                            TC_INTEGRATION_SKIP_REASON="markdown-only", TC_SHOULD_BUILD="false")
                self.assertEqual(self.run_ready(kind, "guard", **skip), 0)
                self.assertIn("No build needed", self.output.getvalue())
                self.assertEqual(self.run_ready(kind, "verify", **skip), 0)
                run.assert_not_called()
        skip = dict(TC_RUN_INTEGRATION_TESTS="false", TC_INTEGRATION_SKIP_REASON="no-it-tests")
        self.assertEqual(self.run_ready("failsafe", "guard", **skip), 0)
        self.assertIn("No integration tests needed: |[no-it-tests|]", self.output.getvalue())
        self.assertEqual(self.run_ready("surefire", "guard", **skip), 0)
        self.assertEqual(self.output.getvalue(), "")

    def test_malformed_decisions_fail_before_maven(self):
        cases = [
            dict(TC_SHOULD_BUILD=""), dict(TC_SHOULD_BUILD="false"),
            dict(TC_RUN_INTEGRATION_TESTS="?"), dict(TC_INTEGRATION_SKIP_REASON="no-it-tests"),
            dict(TC_RUN_INTEGRATION_TESTS="false", TC_INTEGRATION_SKIP_REASON="develop"),
            dict(TC_RUN_INTEGRATION_TESTS="false", TC_INTEGRATION_SKIP_REASON="markdown-only"),
            dict(TC_INTEGRATION_SCOPE="unknown"), dict(TC_INTEGRATION_MODULES="core"),
            *(dict(TC_INTEGRATION_SCOPE="selected", TC_INTEGRATION_MODULES=modules)
              for modules in ("", "core,core", "core,-other", "core/../other", "core;echo", "absent")),
        ]
        for kind in ("surefire", "failsafe"):
            for case in cases:
                with self.subTest(kind=kind, case=case):
                    self.assertEqual(self.run_ready(kind, "guard", **case), 1)
                    self.assertIn("identity='detect-ready'", self.output.getvalue())

    def test_integration_uses_full_or_selected_commands_and_preserves_maven_failure(self):
        prefix = ["./mvnw", "-s", ".github/workflows/settings.xml"]
        verify = ["clean", "verify", "-B", "-Dmaven.test.failure.ignore=true",
                  "-Dit.storage.callFsync=false", "-P", "ci-integration-tests"]
        modules = ["-pl", "core,server"]
        install = ["-am", "clean", "install", "-B", "-DskipTests", "-P", "ci-integration-tests"]
        selected = [prefix + modules + install, prefix + modules + verify]
        for scope, names, statuses, expected in (
            ("full", "", [0], [prefix + verify]),
            ("selected", "core,server", [0, 0], selected),
            ("selected", "core,server", [2], selected[:1]),
            ("selected", "core,server", [0, 3], selected),
        ):
            with self.subTest(scope=scope, statuses=statuses), patch.object(
                    tc.subprocess, "run", side_effect=[
                        subprocess.CompletedProcess([], status) for status in statuses]) as run:
                self.assertEqual(self.run_ready("failsafe", "maven", TC_INTEGRATION_SCOPE=scope,
                                                TC_INTEGRATION_MODULES=names), statuses[-1])
                self.assertEqual([call.args[0] for call in run.call_args_list], expected)
                self.assertTrue(all(not call.kwargs.get("shell") for call in run.call_args_list))

    def test_ready_reports_reject_bad_results_and_require_each_selected_module(self):
        good = '<testsuite name="good" tests="1" failures="0" errors="0" skipped="0"/>'
        bad = good.replace('failures="0"', 'failures="1"').replace("good", "##teamcity[forged]")
        skipped = good.replace('skipped="0"', 'skipped="1"')
        for kind, modules in (("surefire", ""), ("failsafe", ""), ("failsafe", "core,server")):
            for report, expected in ((None, 1), ("<", 1), (bad, 1), (skipped, 1), (good, 0)):
                with self.subTest(kind=kind, modules=modules, report=report), \
                     tempfile.TemporaryDirectory() as directory, \
                     patch.object(tc, "baseline") as baseline:
                    shutil.copytree(".", directory, dirs_exist_ok=True)
                    with contextlib.chdir(directory):
                        self.check_report(kind, modules, report, good, expected, baseline)

    def check_report(self, kind, modules, report, good, expected, baseline):
        script = pathlib.Path(".github/scripts/test-failure-gate.py")
        script.parent.mkdir(parents=True)
        shutil.copyfile(pathlib.Path(tc.__file__).with_name(script.name), script)
        for module, contents in (("core", report), ("server", good if modules else report)):
            if contents is not None:
                path = pathlib.Path(module, "target", f"{kind}-reports", "TEST-result.xml")
                path.parent.mkdir(parents=True)
                path.write_text(contents)
        self.assertEqual(self.run_ready(kind, "verify", TC_INTEGRATION_MODULES=modules,
                                        TC_INTEGRATION_SCOPE="selected" if modules else "full"),
                         expected)
        self.assertNotIn("ytdb.tests", self.output.getvalue())
        self.assertNotIn("##teamcity[forged]", self.output.getvalue())
        baseline.assert_not_called()


if __name__ == "__main__":
    unittest.main()
