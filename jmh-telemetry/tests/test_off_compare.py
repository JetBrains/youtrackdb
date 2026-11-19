"""Offline evidence and rendering checks. No benchmark process is needed."""
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from types import SimpleNamespace

SCRIPT = Path(__file__).resolve().parents[1] / "off_compare.py"
spec = importlib.util.spec_from_file_location("off_compare", SCRIPT)
off = importlib.util.module_from_spec(spec)
spec.loader.exec_module(off)


class ComparisonTest(unittest.TestCase):
    def setUp(self):
        self.row = dict(benchmark=off.PREFIX + "sql",
                        params=dict(rows="1", telemetryScenario="off"),
                        mode="thrpt", threads=1, forks=3,
                        primaryMetric=dict(score=100, scoreError=1,
                                           scoreConfidence=[99, 101], scoreUnit="ops/s"))
        self.row.update({name: "fixed" for name in off.JMH})
        self.row["jvmArgs"] = []
        self.row["setup"] = dict(query="select from Fixture", data="vertices", expectedCount="1",
                                 path="sql", scenario="off", transaction="read rollback",
                                 cache="disabled", fixtureCode="bytecode")
        self.doc = dict(revision="revision", checkoutRevision="revision",
                        revisionMatchesCheckout=True, harness="fixture-v1", machineType="declared-label",
                        hardware={name: "evidence" for name in off.HARDWARE},
                        softwareVersions={"database": "version"}, results=[self.row])
        self.slower = copy.deepcopy(self.row)
        self.slower["primaryMetric"].update(score=90, scoreConfidence=[89, 91])

    def test_shared_scenario_helpers_preserve_off_defaults(self):
        # The local off route keeps scenario in its key and rejects changed setup.
        on = copy.deepcopy(self.row)
        on["params"]["telemetryScenario"] = on["setup"]["scenario"] = "on"
        self.assertNotEqual(off.identity(self.row), off.identity(on))
        self.assertEqual(off.identity(self.row), off.identity(on, scenario="off"))
        self.assertFalse(off.compatible(self.doc, self.doc, self.row, on))
        self.assertTrue(off.compatible(self.doc, self.doc, self.row, on, scenario_pair=True))

    def test_isolated_uncertainty_guards_and_exact_budget_boundary(self):
        # Every invalid case has separated intervals, so overlap cannot mask its guard.
        changes = [dict(score=70, scoreError=8, scoreConfidence=[62, 78]),
                   dict(scoreError=1, scoreConfidence=[89, 92]),
                   dict(scoreError=1, scoreConfidence=[88, 91]),
                   dict(scoreError=None, scoreConfidence=[90, 90])]
        for change in changes:
            row = copy.deepcopy(self.slower)
            row["primaryMetric"].update(change)
            self.assertEqual("inconclusive: invalid uncertainty or excessive noise",
                             self.verdict(row))
        for score, error in ((100, 5e-8), (1e-15, 5e-17)):
            row = copy.deepcopy(self.row)
            row["primaryMetric"].update(score=score, scoreError=error,
                                       scoreConfidence=[score, score])
            self.assertIsNone(off.interval(row))
        for base, candidate, expected in ((100, 99, "within budget"),
                                           (200, 198, "within budget"),
                                           (100, 98.99999, "budget breach")):
            left, right = copy.deepcopy(self.row), copy.deepcopy(self.row)
            for row, score in ((left, base), (right, candidate)):
                row["primaryMetric"].update(score=score, scoreError=.01,
                                           scoreConfidence=[score - .01, score + .01])
            self.assertEqual(expected, off.verdict(self.doc, self.doc, left, right))

    def test_partial_nested_evidence_and_machine_label_mismatch_are_inconclusive(self):
        for field, value in (("hardware", dict(self.doc["hardware"], processors=[])),
                             ("softwareVersions", {"database": None}),
                             ("softwareVersions", {"database": ""}),
                             ("machineType", "other")):
            doc = copy.deepcopy(self.doc)
            doc[field] = value
            self.assertIn("inconclusive", off.verdict(doc, self.doc, self.row, self.slower))
        row = copy.deepcopy(self.slower)
        row["primaryMetric"]["scoreUnit"] = ""
        self.assertIn("inconclusive", self.verdict(row))
        for row in (self.row, self.slower):
            row["jvmArgs"] = ["-Xmx2g"]
        self.assertEqual("budget breach", self.verdict(self.slower))

    def report(self, bases, candidates):
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, doc in enumerate(bases + candidates):
                path = Path(directory) / str(index)
                path.write_text(json.dumps(doc))
                paths.append(path)
            return off.compare(paths[:len(bases)], paths[len(bases):])

    def test_asymmetric_repetitions_do_not_pool_and_mixed_revisions_do_not_combine(self):
        docs = [copy.deepcopy(self.doc) for _ in range(4)]
        for doc, score, error in zip(docs, (1000, 10, 900, 11), (1, .01, 1, .01)):
            doc["results"][0]["primaryMetric"].update(
                score=score, scoreError=error, scoreConfidence=[score - error, score + error])
        report = self.report(docs[:2], docs[2:])
        self.assertIn("- Combined: inconclusive: repetitions disagree\n", report)
        docs[1]["revision"] = "different"
        self.assertIn("- Combined: inconclusive: mixed revisions\n",
                      self.report(docs[:2], docs[2:]))
        doc = copy.deepcopy(self.doc)
        doc["results"][0]["primaryMetric"]["scoreUnit"] = "ops/ms"
        self.assertIn("- Combined: inconclusive: missing pair\n",
                      self.report([self.doc], [doc]))
        doc["results"] = []
        self.assertIn("- Combined: inconclusive: missing pair\n",
                      self.report([self.doc], [doc]))

    def test_hardware_software_rendering_bounds_truncation_and_cli_success(self):
        hostile = "|<script>\n\x00` @someone #123 " + "a" * 10000
        doc = copy.deepcopy(self.doc)
        doc["hardware"] = {name: hostile for name in off.HARDWARE}
        doc["softwareVersions"] = {hostile + str(i): hostile for i in range(100)}
        report = self.report([doc] * 3, [doc] * 3)
        self.assertLess(len(report), 60000)
        self.assertIn("truncated", report)
        self.assertNotIn("<script>", report)
        self.assertNotIn("@someone", report)
        self.assertNotRegex(report, r"(?<!&)#123")
        for line in report.splitlines():
            if "hardware " in line or "software " in line:
                self.assertLess(len(line), 2100)
                self.assertNotIn("|", line)
                self.assertIn("&#", line)
        self.assertNotEqual(off.safe("a" * 200), off.safe("a" * 201))
        self.assertIn("sha256", off.safe(hostile))
        self.assertTrue(off.safe("a" * 40).startswith("`"))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "doc.json"
            path.write_text(json.dumps(self.doc))
            process = subprocess.run([sys.executable, str(SCRIPT), "compare", "--base",
                                      str(path), "--candidate", str(path)], capture_output=True)
            self.assertEqual(0, process.returncode)
            self.assertIn(b"Combined: inconclusive: overlapping intervals", process.stdout)

    def test_process_cgroup_limits_include_ancestors_and_explicit_unlimited(self):
        # The child allocation, not just the mount-root allocation, controls compatibility.
        for v2 in (True, False):
            for quota, memory in (("100000", "1048576"), ("400000", "4194304")):
                files = {
                    "/proc/self/cgroup": ("0::/bench" if v2 else
                                         "2:cpu:/bench\n3:memory:/bench"),
                    "/proc/self/mountinfo": ("1 0 0:1 / /cg rw - cgroup2 cgroup rw" if v2 else
                        "1 0 0:1 / /cg rw - cgroup cgroup rw,cpu,memory")}
                for parent in ("/cg", "/cg/bench"):
                    files[parent + "/cpu.max"] = ("max 100000" if parent == "/cg"
                                                  else quota + " 100000")
                    files[parent + "/memory.max"] = ("max" if parent == "/cg" else memory)
                    files[parent + "/cpu.cfs_quota_us"] = ("-1" if parent == "/cg" else quota)
                    files[parent + "/cpu.cfs_period_us"] = "100000"
                    files[parent + "/memory.use_hierarchy"] = "1"
                    files[parent + "/memory.limit_in_bytes"] = (str((1 << 63) - 4096) if parent == "/cg"
                                                                else memory)
                with patch.object(off, "evidence", side_effect=lambda p, missing=None, empty=None:
                                  files.get(str(p), missing)):
                    self.assertEqual(str(int(quota) // 100000), off.cgroup_limit("cpu"))
                    self.assertEqual(memory, off.cgroup_limit("memory"))
                    files["/proc/self/cgroup"] = "0::/" if v2 else "2:cpu:/\n3:memory:/"
                    self.assertEqual("unlimited", off.cgroup_limit("cpu"))
                    self.assertEqual("unlimited", off.cgroup_limit("memory"))
                    del files["/cg/" + ("cpu.max" if v2 else "cpu.cfs_quota_us")]
                    self.assertEqual("unlimited" if v2 else None, off.cgroup_limit("cpu"))
        with patch.object(off, "evidence", return_value=None):
            self.assertIsNone(off.cgroup_limit("cpu"))

    def test_v2_real_hierarchy_without_root_interfaces_resolves_limits(self):
        # Linux omits v2 root interfaces. Child and ancestor limits must still apply.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            parent, child = root / "bench", root / "bench" / "worker"
            child.mkdir(parents=True)
            proc = {"/proc/self/cgroup": "0::/bench/worker",
                    "/proc/self/mountinfo": f"1 0 0:1 / {root} rw - cgroup2 cgroup rw"}
            real_evidence = off.evidence
            def probe(path, missing=None, empty=None):
                return proc[str(path)] if str(path) in proc else real_evidence(path, missing, empty)
            with patch.object(off, "evidence", side_effect=probe):
                for current, cpu, memory in ((parent, "100000 100000", "1048576"),
                                             (child, "200000 100000", "2097152")):
                    (current / "cpu.max").write_text(cpu)
                    (current / "memory.max").write_text(memory)
                self.assertEqual("1", off.cgroup_limit("cpu"))
                self.assertEqual("1048576", off.cgroup_limit("memory"))
                for current in (parent, child):
                    (current / "cpu.max").write_text("max 100000")
                    (current / "memory.max").write_text("max")
                self.assertEqual("unlimited", off.cgroup_limit("cpu"))
                self.assertEqual("unlimited", off.cgroup_limit("memory"))
                # Missing non-root evidence and unreadable root evidence stay unavailable.
                (child / "cpu.max").unlink()
                self.assertIsNone(off.cgroup_limit("cpu"))
                # Disabled child controllers have no local interface, but inherit parent limits.
                (child / "cgroup.controllers").write_text("memory pids")
                (parent / "cpu.max").write_text("100000 100000")
                self.assertEqual("1", off.cgroup_limit("cpu"))
                (child / "cgroup.controllers").write_text("")
                self.assertEqual("1", off.cgroup_limit("cpu"))
                (child / "cgroup.controllers").write_text("cpu memory")
                self.assertIsNone(off.cgroup_limit("cpu"))
                (child / "cpu.max").write_text("max 100000")
                original_read = Path.read_text
                def denied(path, *args, **kwargs):
                    if path in (root / "cpu.max", root / "memory.max"):
                        raise PermissionError("root probe denied")
                    return original_read(path, *args, **kwargs)
                with patch.object(Path, "read_text", denied):
                    self.assertIsNone(off.cgroup_limit("cpu"))
                    self.assertIsNone(off.cgroup_limit("memory"))

    @unittest.skipUnless(sys.platform == "linux", "Linux cgroup evidence")
    def test_actual_host_v2_root_without_interfaces_has_readable_limits(self):
        # Exercise the real host, not a hierarchy with fabricated root interfaces.
        groups = off.evidence("/proc/self/cgroup") or ""
        mounts = off.evidence("/proc/self/mountinfo") or ""
        roots = [Path(line.split(" - ")[0].split()[4]) for line in mounts.splitlines()
                 if " - cgroup2 " in line and line.split()[3] == "/"]
        if not groups.startswith("0::/") or not roots:
            self.skipTest("host has no supported v2 mount")
        if any((roots[0] / name).exists() for name in ("cpu.max", "memory.max")):
            self.skipTest("host exposes root limit interfaces")
        hardware = off.capture()
        self.assertIsNotNone(hardware["cpuQuota"])
        self.assertIsNotNone(hardware["memoryLimit"])

    def test_revision_relationship_is_validated_on_both_sides(self):
        # A true cached flag cannot turn contradictory or absent revisions into a pass.
        faster = copy.deepcopy(self.slower)
        faster["primaryMetric"].update(score=110, scoreConfidence=[109, 111])
        self.assertEqual("within budget", off.verdict(self.doc, self.doc, self.row, faster))
        for field, value in (("checkoutRevision", "other-revision"),
                             ("checkoutRevision", None), ("revision", ""),
                             ("revisionMatchesCheckout", False)):
            changed = copy.deepcopy(self.doc)
            changed[field] = value
            for base, candidate in ((changed, self.doc), (self.doc, changed)):
                self.assertIn("inconclusive", off.verdict(base, candidate, self.row, faster))

    def verdict(self, row):
        return off.verdict(self.doc, self.doc, self.row, row)

    def test_separated_low_noise_scores_breach_one_percent_budget(self):
        self.assertEqual("budget breach", self.verdict(self.slower))
        faster = copy.deepcopy(self.slower)
        faster["primaryMetric"].update(score=110, scoreConfidence=[109, 111])
        self.assertEqual("within budget", self.verdict(faster))

    def test_invalid_uncertainty_never_becomes_zero_or_pass(self):
        cases = [dict(scoreError=None), dict(scoreError=-1), dict(scoreError=float("nan")),
                 dict(scoreError=float("inf")), dict(scoreError=0),
                 dict(scoreConfidence=[92, 89]), dict(scoreConfidence=[None, 91]),
                 dict(score=0), dict(score=float("inf")),
                 dict(scoreError=20, scoreConfidence=[70, 110])]
        for change in cases:
            with self.subTest(change=change):
                row = copy.deepcopy(self.slower)
                row["primaryMetric"].update(change)
                self.assertIn("inconclusive", self.verdict(row))
        del self.slower["primaryMetric"]["scoreError"]
        self.assertIn("inconclusive", self.verdict(self.slower))

    def test_missing_metadata_overlap_and_small_fork_count_are_inconclusive(self):
        self.assertIn("inconclusive", self.verdict(self.row))
        for field in off.JMH + ("setup",):
            row = copy.deepcopy(self.slower)
            del row[field]
            self.assertIn("inconclusive", self.verdict(row))
        for field in off.HARDWARE:
            doc = copy.deepcopy(self.doc)
            del doc["hardware"][field]
            self.assertIn("inconclusive", off.verdict(doc, self.doc, self.row, self.slower))
        row = copy.deepcopy(self.slower)
        row["setup"]["query"] = "different query"
        self.assertIn("inconclusive", self.verdict(row))
        row = copy.deepcopy(self.slower)
        row["forks"] = 1
        self.assertIn("inconclusive", self.verdict(row))

    def runner(self, directory, rows=None, failure=None, dirty="", revision="revision",
               fixture=True, metadata=True, hardware=None, race=False, listing=True):
        """Raw JMH rows get setup only from the jar entry point, before exclusive output."""
        jar, output = Path(directory) / "bench.jar", Path(directory) / "result.json"
        package = off.PREFIX.rsplit(".", 2)[0].replace(".", "/") + "/"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Implementation-Build: " + revision + "\n")
            archive.writestr("META-INF/maven/example/pom.properties", "version=1")
            if fixture:
                archive.writestr(package + "OffBenchmark.class", "bytecode")
            if metadata:
                archive.writestr(package + "SetupMetadata.class", "metadata bytecode")
        args = SimpleNamespace(command=["java", "-jar", str(jar)], scratch=directory,
                               output=output, harness="h1", machine_type="label")
        raw = copy.deepcopy(self.row)
        del raw["setup"]
        events = []
        def execute(command, check, **kwargs):
            events.append("java")
            self.assertTrue(check)
            self.assertFalse(output.exists())
            if "-l" in command:
                return SimpleNamespace(stdout=off.PREFIX + "sql\n" if listing else "")
            if "-cp" in command:
                Path(command[-1]).write_text(
                    '<properties><entry key="sql.query">jar query</entry>'
                    '<entry key="count.1">1</entry>' + "".join(
                        f'<entry key="sql.{name}">jar {name}</entry>'
                        for name in off.SETUP if name != "query") + '</properties>')
            else:
                Path(command[command.index("-rff") + 1]).write_text(
                    json.dumps([raw] if rows is None else rows))
                if failure:
                    raise failure
                if race:
                    output.write_text("raced")
        with patch.object(off, "capture", side_effect=lambda: events.append("hardware") or
                          (self.doc["hardware"] if hardware is None else hardware)), \
             patch.object(off.subprocess, "check_output", side_effect=["revision", dirty]), \
             patch.object(off.subprocess, "run", side_effect=execute):
            off.run(args)
        result = off.read_json(output)
        self.assertEqual("hardware", events[0])
        return result

    def test_runner_enriches_raw_rows_with_independent_jar_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            result = self.runner(directory)
            self.assertEqual("jar query", result["results"][0]["setup"]["query"])
            self.assertEqual("1", result["results"][0]["setup"]["expectedCount"])
            self.assertTrue(result["softwareVersions"])
            self.assertTrue(result["revisionMatchesCheckout"])
            self.assertEqual(self.doc["hardware"], result["hardware"])

    def test_runner_rejects_failed_java_dirty_tree_and_invalid_rows(self):
        # Even valid JSON from a failing child must not create an envelope.
        for options in (dict(failure=subprocess.CalledProcessError(1, ["java"])),
                        dict(dirty="modified"), dict(rows=[]), dict(rows={}),
                        dict(rows=[self.row, self.row]), dict(rows=[{}]),
                        dict(hardware={"processors": []}),
                        dict(hardware=dict(self.doc["hardware"], processors=[])), dict(fixture=False),
                        dict(listing=False), dict(metadata=False), dict(revision="stale"),
                        dict(revision="")):
            with self.subTest(options=options), tempfile.TemporaryDirectory() as directory:
                with self.assertRaises((ValueError, KeyError, subprocess.SubprocessError)):
                    self.runner(directory, **options)
                self.assertFalse((Path(directory) / "result.json").exists())

    def test_runner_refuses_existing_output_before_any_capture_or_java(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            output.write_text("retained")
            with patch.object(off, "capture") as capture:
                with self.assertRaisesRegex(ValueError, "already exists"):
                    off.run(SimpleNamespace(output=output))
                capture.assert_not_called()
            self.assertEqual("retained", output.read_text())

    def test_hardware_probe_failure_exits_nonzero_before_java(self):
        with tempfile.TemporaryDirectory() as directory:
            command = ["off_compare.py", "run", "--output", str(Path(directory) / "result"),
                       "--harness", "h", "--machine-type", "m", "--scratch", directory]
            with patch.object(sys, "argv", command), \
                 patch.object(off, "capture", side_effect=OSError("probe unavailable")), \
                 patch.object(off.subprocess, "run") as java:
                self.assertEqual(2, off.main())
                java.assert_not_called()

    def test_incomplete_hardware_cli_exits_nonzero_without_output_or_java(self):
        # Empty affinity is incomplete evidence, not a successful capture with a warning.
        with tempfile.TemporaryDirectory() as directory:
            output, jar = Path(directory) / "result.json", Path(directory) / "bench.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Implementation-Build: revision\\n")
                archive.writestr(off.PREFIX.replace(".", "/").rsplit("/", 1)[0] + ".class",
                                 "fixture bytecode")
            command = ["off_compare.py", "run", "--output", str(output),
                       "--harness", "h", "--machine-type", "m", "--scratch", directory,
                       "java", "-jar", str(jar)]
            with patch.object(sys, "argv", command), \
                 patch.object(off, "capture", return_value={"processors": []}), \
                 patch.object(off.subprocess, "check_output", side_effect=["revision", ""]), \
                 patch.object(off.subprocess, "run") as java:
                self.assertEqual(2, off.main())
                java.assert_not_called()
            self.assertFalse(output.exists())

    def test_exclusive_output_and_absence_cli_success(self):
        # A file created during Java execution is retained, and absence is a successful record.
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileExistsError):
                self.runner(directory, race=True)
            self.assertEqual("raced", (Path(directory) / "result.json").read_text())
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "absence.json"
            command = ["off_compare.py", "run", "--record-absence", "--output", str(output),
                       "--harness", "h", "--machine-type", "m", "--scratch", directory]
            with patch.object(sys, "argv", command), patch.object(off, "capture", return_value={}), \
                 patch.object(off.subprocess, "check_output", side_effect=["revision", ""]):
                self.assertEqual(0, off.main())
            self.assertIn("absence", off.read_json(output))

    def test_pairing_repetitions_hostile_strings_and_structural_failures(self):
        # Keep test files outside the checkout, as the project requires.
        with tempfile.TemporaryDirectory() as directory:
            base, candidate = [Path(directory) / name for name in ("base.json", "candidate.json")]
            base.write_text(json.dumps(self.doc))
            doc = copy.deepcopy(self.doc)
            doc["results"] = [self.slower]
            candidate.write_text(json.dumps(doc))
            self.assertIn("Combined: budget breach", off.compare([base], [candidate]))
            self.assertIn("inconclusive", off.compare([base, candidate], [candidate, base]))
            self.assertIn("- Combined: inconclusive: repetitions disagree\n",
                          off.compare([base], [candidate, candidate]))
            for dimension in ("threads", "forks", "mode", "benchmark", "params"):
                changed = copy.deepcopy(doc)
                changed["results"][0][dimension] = (2 if dimension in ("threads", "forks")
                                                         else {"rows": "10000"} if dimension == "params"
                                                         else "other")
                candidate.write_text(json.dumps(changed))
                self.assertIn("- Combined: inconclusive: missing pair\n",
                              off.compare([base], [candidate]))
            doc["results"] = []
            base.write_text(json.dumps(doc))
            candidate.write_text(json.dumps(self.doc))
            self.assertIn("missing pair", off.compare([base], [candidate]))
            hostile = "|\n<script>\x00`**[x](url)\r" + "z" * 10000
            self.assertLess(len(off.safe(hostile)), 2000)
            for char in "<>|\n\r\x00":
                self.assertNotIn(char, off.safe(hostile))
            doc["results"] = [self.row]
            doc["revision"] = hostile
            doc["results"][0]["benchmark"] = hostile
            doc["results"][0]["params"]["rows"] = hostile
            candidate.write_text(json.dumps(doc))
            report = off.compare([candidate], [candidate])
            self.assertIn("candidate-reported", report)
            self.assertNotIn("<script>", report)
            for content in ('{bad', '{"results":null}', '{"results":[],"results":[]}'):
                candidate.write_text(content)
                process = subprocess.run([sys.executable, str(SCRIPT), "compare", "--base",
                                          str(base), "--candidate", str(candidate)],
                                         capture_output=True)
                self.assertNotEqual(0, process.returncode)
