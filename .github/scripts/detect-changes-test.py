#!/usr/bin/env python3
"""Exercise pull request (PR) and integration test (IT) decisions with real Git history."""

import contextlib
import errno
import importlib.util
import io
import os
import pathlib
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from unittest import mock

sys.dont_write_bytecode = True
SCRIPT = pathlib.Path(__file__).with_name("detect-changes.py")
spec = importlib.util.spec_from_file_location("detect_changes", SCRIPT)
detect = importlib.util.module_from_spec(spec)
spec.loader.exec_module(detect)


class Decisions(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "Test")
        self.put("pom.xml", '<project xmlns="http://maven.apache.org/POM/4.0.0">'
                 '<modules><module>core</module><module>other</module>'
                 '<module>nested/inner</module></modules></project>')
        for module in ("core", "other", "nested/inner"):
            self.put(module + "/pom.xml", "<project/>")
        self.put("core/src/test/java/ExampleIT.java")
        self.put("nested/inner/src/test/java/ExampleIntegrateTest.java")
        self.base = self.commit("Initial")
        self.changes = self.root / "changes"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args],
                                       stderr=subprocess.PIPE).decode().strip()

    def put(self, path, text="content"):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(text)

    def commit(self, subject):
        self.git("add", ".")
        self.git("commit", "-q", "--allow-empty", "-m", subject)
        return self.git("rev-parse", "HEAD")

    def run_decision(self, *args, cli=False):
        argv = ["--root", str(self.root), *args]
        if cli:
            result = subprocess.run([sys.executable, str(SCRIPT), *argv],
                                    capture_output=True, text=True)
            code, out = result.returncode, result.stdout + result.stderr
        else:
            stream = io.StringIO()
            with contextlib.redirect_stdout(stream):
                code = detect.main(argv)
            out = stream.getvalue()
        self.assertEqual(code, 0, out)
        values = {}
        for line in out.splitlines():
            if line.startswith("##teamcity[message "):
                self.assertTrue(
                    line.startswith("##teamcity[message text='Full build fallback: "), out)
                self.assertTrue(line.endswith("' status='WARNING']"), out)
            elif line.startswith("##teamcity["):
                self.assertTrue(line.startswith("##teamcity[setParameter name='detect."), out)
                name, value = line.split("name='detect.", 1)[1].split("' value='")
                values[name] = value[:-2]
        self.assertEqual(set(values), {"should_build", "run_integration_tests",
                                     "integration_skip_reason", "integration_scope",
                                     "integration_modules"})
        self.assertIn("Decision:", out)
        return values, out

    def pr(self, *args, **kwargs):
        return self.run_decision("--commit-type", "pr", "--base", self.base, *args, **kwargs)

    def develop(self, paths):
        self.changes.write_bytes(b"".join(p.encode() + b"\0" for p in paths))
        return self.run_decision("--commit-type", "develop", "--changes", str(self.changes))

    def expected(self, values, build="true", reason="", modules=""):
        self.assertEqual(values, dict(should_build=build,
                         run_integration_tests=str(not reason).lower(),
                         integration_skip_reason=reason,
                         integration_scope="selected" if modules else "full",
                         integration_modules=modules))

    def full(self, values, out, reason="", cause=None):
        self.expected(values, reason=reason)
        self.assertIn("##teamcity[message text='Full build fallback:", out)
        self.assertIn("' status='WARNING']", out)
        if cause:
            self.assertIn(cause, out)

    def test_build_paths_and_markdown_exception(self):
        """Every GitHub build path and both TeamCity additions build, except .md."""
        paths = ["pom.xml", "mvnw", "mvnw.cmd", ".mvn/wrapper/x",
                 ".github/workflows/x", ".idea/inspectionProfiles/x",
                 ".teamcity/x", ".github/scripts/x",
                 "core/x", "other/x", "nested/inner/x"]
        for path in paths:
            with self.subTest(path=path):
                self.expected(self.develop([path])[0], reason="develop")
                self.expected(self.develop([path + ".md"])[0], build="false", reason="develop")
        for paths in ([], ["README.md"], ["lucene/A.java"], ["core-extra/A.java"],
                      [".idea/other.xml"], ["unrelated"], [".teamcity-extra/x"]):
            self.expected(self.develop(paths)[0], build="false", reason="develop")

    def test_selection_reasons_and_commit_types(self):
        """PR selects IT owners only, while merge queue and develop always skip."""
        for paths, build, reason, modules in (
            ([], "false", "no-java-changes", ""),
            (["core/readme.md"], "false", "markdown-only", ""),
            (["core/pom.xml"], "true", "no-java-changes", ""),
            (["other/A.java"], "true", "no-it-modules", ""),
            (["outside/A.java"], "false", "no-it-modules", ""),
            (["core/A.java", "nested/inner/A.java", "other/A.java"],
             "true", "", "core,nested/inner"),
        ):
            with self.subTest(paths=paths):
                self.git("checkout", "--detach", self.base)
                for path in paths:
                    self.put(path, "changed")
                self.commit("Change")
                self.expected(self.pr(cli=True)[0], build, reason, modules)
        for kind in ("merge-queue", "develop"):
            args = ["--base", self.base] if kind == "merge-queue" else ["--full"]
            self.expected(self.run_decision("--commit-type", kind, *args, cli=True)[0],
                          reason=kind)

    def test_merge_queue_empty_markdown_and_base_failures(self):
        """Merge queue skips irrelevant diffs but warns and builds on unknown history."""
        self.expected(self.run_decision("--commit-type", "merge-queue", "--base",
                                       self.base, cli=True)[0], "false", "merge-queue")
        self.put("core/readme.md")
        self.commit("Markdown only")
        self.expected(self.run_decision("--commit-type", "merge-queue", "--base",
                                       self.base, cli=True)[0], "false", "merge-queue")
        for args, cause in (([], "Missing or zero comparison base"),
                            (["--base", "0" * 40], "Missing or zero comparison base"),
                            (["--base", "absent"], "Git rev-parse failed")):
            with self.subTest(args=args):
                self.full(*self.run_decision("--commit-type", "merge-queue", *args, cli=True),
                          reason="merge-queue", cause=cause)
        self.git("checkout", "--orphan", "unrelated")
        orphan = self.commit("Unrelated root")
        self.full(*self.run_decision("--commit-type", "merge-queue", "--base", orphan,
                  "--head", self.base, cli=True), reason="merge-queue",
                  cause="Git merge-base failed")

    def test_source_scan_errors_total_and_partial(self):
        """A scan error in one Java owner's tree forces full scope, not a skip or subset."""
        scandir = detect.os.scandir
        for paths in (["core/A.java"], ["core/A.java", "nested/inner/A.java"]):
            with self.subTest(paths=paths):
                self.git("checkout", "--detach", self.base)
                for path in paths:
                    self.put(path)
                self.commit("Change Java owners")

                def unreadable(source):
                    if source == self.root / "core/src/test/java":
                        raise PermissionError("Cannot list core test sources")
                    return scandir(source)

                with mock.patch.object(detect.os, "scandir", side_effect=unreadable):
                    self.full(*self.pr(), cause="PermissionError: Cannot list core test sources")

    def test_entry_classification_error_falls_back(self):
        """An entry classification error warns and runs the full PR integration scope."""
        self.put("core/A.java")
        self.commit("Change Java owner")
        source_root = self.root / "core/src/test/java"
        scandir = detect.os.scandir
        entry = mock.Mock()
        entry.name = "loop"
        entry.is_dir.side_effect = OSError(errno.ELOOP, "Cannot classify test source entry")
        injected, closed = [], []

        @contextlib.contextmanager
        def scan(source):
            if pathlib.Path(source) == source_root and not injected:
                injected.append(source)
                try:
                    yield iter([entry])
                finally:
                    closed.append(source)
            else:
                with scandir(source) as entries:
                    yield entries

        with mock.patch.object(detect.os, "scandir", side_effect=scan):
            self.full(*self.pr(), cause=detect.escape(str(entry.is_dir.side_effect)))
        entry.is_dir.assert_called_once_with()
        self.assertEqual(closed, [source_root])

    def test_real_self_referencing_symlink_falls_back(self):
        """A self-link inside an IT owner's tree raises ELOOP and warns with full scope."""
        self.put("core/A.java")
        (self.root / "core/src/test/java/loop").symlink_to("loop")
        self.commit("Change Java owner with self-link")
        values, out = self.pr(cli=True)
        self.full(values, out, cause="OSError:")
        self.assertIn("Too many levels of symbolic links", out)

    def test_real_directory_symlink_cycle_falls_back(self):
        """Following a directory cycle reaches ELOOP and warns instead of hanging."""
        self.put("core/A.java")
        (self.root / "core/src/test/java/loop").symlink_to(".", target_is_directory=True)
        self.commit("Change Java owner with directory cycle")
        self.full(*self.pr(cli=True), cause="Too many levels of symbolic links")

    def test_real_broken_symlink_preserves_selection(self):
        """A dangling link is not a directory and does not prevent selecting its IT owner."""
        self.put("core/A.java")
        (self.root / "core/src/test/java/missing").symlink_to("absent")
        self.commit("Change Java owner with broken link")
        values, out = self.pr(cli=True)
        self.expected(values, modules="core")
        self.assertNotIn("WARNING", out)

    def test_file_in_place_of_test_source_tree_falls_back(self):
        """A file at the test source root warns and runs full scope instead of skipping ITs."""
        self.put("other/A.java")
        self.put("other/src/test/java")
        self.commit("Change Java owner with non-directory test root")
        self.full(*self.pr(cli=True), cause="NotADirectoryError:")

    @unittest.skipIf(os.geteuid() == 0, "Root bypasses filesystem permissions")
    def test_real_unreadable_source_tree_total_and_partial(self):
        """Real directory permission denial warns for both one and two changed owners."""
        # Keep a readable IT above the denied subtree to require a complete scan.
        denied = self.root / "core/src/test/java/denied"
        self.put("core/src/test/java/denied/HiddenIT.java")
        for paths in (["core/A.java"], ["core/A.java", "nested/inner/A.java"]):
            for path in paths:
                self.put(path)
            self.commit("Change Java owners")
            for folder in (self.root / "core/src/test/java", denied):
                with self.subTest(paths=paths, folder=folder):
                    folder.chmod(0)
                    try:
                        self.full(*self.pr(cli=True), cause="PermissionError")
                    finally:
                        folder.chmod(0o755)

    def test_option_shaped_reactor_module(self):
        """An option-shaped reactor module forces full scope before it can enter Maven -pl."""
        pom = self.root / "pom.xml"
        pom.write_text(pom.read_text().replace("other", "-DskipTests"))
        self.put("-DskipTests/pom.xml", "<project/>")
        self.put("-DskipTests/src/test/java/ExampleIT.java")
        self.put("-DskipTests/A.java")
        self.commit("Change option-shaped module")
        self.full(*self.pr(cli=True),
                  cause="Reactor module names must start with a letter or digit")

    def test_selector_outside_reactor(self):
        """An injected selector result outside the validated reactor warns and uses full scope."""
        selector = mock.Mock()
        selector.reactor_modules.return_value = ["core"]
        selector.changed_paths.return_value = []
        selector.select.return_value = (True, ["outside"])
        with mock.patch.object(detect.importlib.util, "spec_from_file_location"), \
                mock.patch.object(detect.importlib.util, "module_from_spec", return_value=selector):
            self.full(*self.pr(), cause="Selector returned a module outside the validated reactor")

    def test_root_pom_has_only_top_level_modules(self):
        """The real POM has no profile modules and its top-level names match the selector."""
        root = SCRIPT.resolve().parents[2]
        tree = ET.parse(root / "pom.xml")
        namespace = {"pom": "http://maven.apache.org/POM/4.0.0"}
        top = tree.findall("./pom:modules/pom:module", namespace)
        all_modules = [element for element in tree.iter()
                       if element.tag.rsplit("}", 1)[-1] == "module"]
        self.assertTrue(top)
        self.assertEqual(all_modules, top)
        spec = importlib.util.spec_from_file_location(
            "select_it_modules", SCRIPT.with_name("select-it-modules.py"))
        selector = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(selector)
        self.assertCountEqual(selector.reactor_modules(root),
                              [(element.text or "").strip() for element in top])

    def test_marker_trust_case_and_first_line(self):
        """Only the exact first-line marker on a same-repository PR skips ITs."""
        self.put("core/A.java")
        for message, trusted, reason in (
            ("Change [no-it-tests]", True, "no-it-tests"),
            ("Change [no-it-tests]", False, ""),
            ("Change [NO-it-tests]", True, ""),
            ("Change\n[no-it-tests]", True, ""),
            ("Change '|]\n##teamcity[forged] [no-it-tests]", True, ""),
        ):
            self.commit(message)
            args = ["--same-repository"] if trusted else []
            values, out = self.pr(*args, cli=True)
            self.expected(values, reason=reason, modules="" if reason else "core")
            self.assertNotIn("forged", out)
        # Marker takes precedence over Markdown and no-Java skips.
        self.git("checkout", "--detach", self.base)
        self.put("core/readme.md")
        self.commit("[no-it-tests]")
        self.expected(self.pr("--same-repository")[0], "false", "no-it-tests")

    def test_merge_base_rename_deletion_and_explicit_head(self):
        """Target-only edits do not enter the diff and a rename retains its old Java path."""
        self.put("core/Old.java")
        head = self.commit("PR Java")
        self.git("checkout", "--detach", self.base)
        self.put("nested/inner/Target.java")
        target = self.commit("Target advances")
        self.expected(self.pr("--base", target, "--head", head)[0], modules="core")
        self.git("checkout", "--detach", head)
        self.git("mv", "core/Old.java", "outside.txt")
        self.commit("Rename Java away")
        self.expected(self.pr("--base", head)[0], modules="core")
        self.git("rm", "core/src/test/java/ExampleIT.java")
        self.commit("Remove last IT")
        self.expected(self.pr("--base", head)[0], reason="no-it-modules")

    def test_missing_zero_unreachable_and_invalid_head(self):
        """Unknown comparison history always warns and chooses the full PR build."""
        for args, cause in (([], "Missing or zero comparison base"),
                            (["--base", "0" * 40], "Missing or zero comparison base"),
                            (["--base", "absent"], "Git rev-parse failed"),
                            (["--base", self.base, "--head", "absent"], "Git rev-parse failed")):
            self.full(*self.run_decision("--commit-type", "pr", *args, cli=True), cause=cause)
        self.git("checkout", "--orphan", "unrelated")
        orphan = self.commit("Unrelated root")
        self.full(*self.pr("--base", orphan, "--head", self.base, cli=True), cause="merge-base")

    def test_bad_lists_and_poms(self):
        """Malformed lists, unreadable files and invalid reactors warn instead of skipping."""
        for data in (b"unterminated", b"\0", b"/core/A.java\0", b"core/../x\0",
                     b"./core/A.java\0", b"core//A.java\0"):
            self.changes.write_bytes(data)
            self.full(*self.run_decision("--commit-type", "develop", "--changes",
                      str(self.changes)), reason="develop", cause="ValueError")
        self.changes.unlink()
        self.full(*self.run_decision("--commit-type", "develop", "--changes",
                  str(self.changes), cli=True), reason="develop", cause="FileNotFoundError")
        pom = self.root / "pom.xml"
        original = pom.read_text()
        for bad in ("broken", original.replace("other", "core"),
                    original.replace("other", "../outside"),
                    original.replace("other", "unsafe,'|]")):
            pom.write_text(bad)
            self.full(*self.pr())
        pom.unlink()
        self.full(*self.pr(), cause="FileNotFoundError")

    def test_git_subject_import_and_unexpected_failures(self):
        """Every Git stage and an unexpected exception produce visible full-build fallbacks."""
        self.put("core/A.java")
        self.commit("[no-it-tests]")
        original = detect.git
        for stage in ("rev-parse", "merge-base", "diff", "show"):
            def fail(root, *args):
                if args[0] == stage:
                    raise OSError("Unreadable " + stage)
                return original(root, *args)
            with mock.patch.object(detect, "git", side_effect=fail):
                self.full(*self.pr("--same-repository"), cause=stage)
        with mock.patch.object(detect.importlib.util, "spec_from_file_location",
                               side_effect=ImportError("selector unavailable")):
            self.full(*self.pr(), cause="ImportError")
        with mock.patch.object(detect, "decide", side_effect=RuntimeError("unexpected")):
            self.full(*self.pr(), cause="RuntimeError")
            self.full(*self.run_decision("--commit-type", "merge-queue"),
                      reason="merge-queue", cause="RuntimeError")

    def test_escaping_and_untrusted_logs(self):
        """Special values use TeamCity escapes and hostile filenames cannot forge messages."""
        hostile = "'|]\n##teamcity[forged]\ré😀"
        self.assertEqual(detect.escape(hostile),
                         "|'|||]|n##teamcity|[forged|]|r|0x00E9|0xD83D|0xDE00")
        self.put("core/" + hostile + ".java")
        self.commit(hostile)
        self.expected(self.pr("--same-repository", cli=True)[0], modules="core")
        self.expected(self.develop(["core/" + hostile + ".java"])[0], reason="develop")
        with mock.patch.object(detect, "decide", side_effect=ValueError(hostile)):
            values, out = self.pr()
            self.full(values, out)
            self.assertIn(detect.escape(hostile), out)
            self.assertEqual(out.count("##teamcity["), 6)

    def test_usage_errors(self):
        """Missing or conflicting arguments exit nonzero without unsafe caller text."""
        for args in ([], ["--commit-type", "unknown"], ["--commit-type", "develop"],
                     ["--commit-type", "pr", "--changes", "x"],
                     ["--commit-type", "develop", "--full", "--changes", "x"],
                     ["--commit-type", "pr", "--draft"],
                     ["--commit-type", "pr", "--same"],
                     ["--commit-type", "pr", "--bad\n##teamcity[forged]"]):
            result = subprocess.run([sys.executable, str(SCRIPT), "--root", str(self.root),
                                     *args], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("##teamcity[", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
