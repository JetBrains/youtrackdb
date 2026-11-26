"""Paired execution tests use candidate-shaped output without executing candidate code."""
import copy
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import telemetry_compare as tc
import telemetry_run as tr
import test_off_compare as fixtures


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.args = SimpleNamespace(jar=str(self.root / "candidate.jar"), scratch=str(self.root),
                                    directory=str(self.root / "output"), java="java",
                                    run="run", producing_attempt="producer", revision="revision",
                                    repetitions=2, warmup=[5, 8], artifact="artifact",
                                    harness="fixture-v1", machine_type="local")
        self.catalog = (tc.Arm(tc.PREFIX + "sql", "off", rows=("1",)),
                        tc.Arm(tc.PREFIX + "sql", "on", 10, True, ("1",)))
        fixture = fixtures.ComparisonTest()
        fixture.setUp()
        self.row, self.hardware = fixture.row, fixture.doc["hardware"]
        self.row.pop("setup")
        self.commands = []
        self.listed = self.catalog
        self.failure = None
        self.list_text = None
        self.version = 'openjdk version "21.0.6"'
        with zipfile.ZipFile(self.args.jar, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Implementation-Build: revision\n")
            archive.writestr("META-INF/maven/sdk/pom.properties", "version=1")
        self.jar_bytes = Path(self.args.jar).read_bytes()
        self.enterContext(patch.object(tr.shutil, "which", return_value="/jdk21/bin/java"))
        self.enterContext(patch.object(
            tr.subprocess, "check_output",
            side_effect=lambda argv, **kw: "revision" if argv[1] == "rev-parse" else ""))
        self.hardware_patch = patch.object(tr.oc, "capture", return_value=self.hardware)
        self.process_patch = patch.object(tr.subprocess, "run", side_effect=self.process)
        self.hardware_patch.start()
        self.process_patch.start()
        self.addCleanup(self.hardware_patch.stop)
        self.addCleanup(self.process_patch.stop)

    def process(self, argv, **kwargs):
        self.assertIsInstance(argv, list)
        self.assertTrue(kwargs["check"])
        self.assertEqual(Path(self.args.jar).parent, kwargs["cwd"])
        self.commands.append(argv)
        if "-version" in argv:
            return SimpleNamespace(stdout=self.version)
        snapshot = Path(argv[argv.index("-jar") + 1] if "-jar" in argv else
                        argv[argv.index("-cp") + 1])
        self.assertNotEqual(Path(self.args.jar), snapshot)
        self.assertEqual(self.jar_bytes, snapshot.read_bytes())
        if "-lp" in argv:
            listing = "Benchmarks:\n"
            for benchmark in dict.fromkeys(a.benchmark for a in self.listed):
                arms = [a.arm for a in self.listed if a.benchmark == benchmark]
                listing += benchmark + '\n  param "rows" = {1}\n'
                listing += '  param "telemetryScenario" = {' + ", ".join(arms) + '}\n'
            return SimpleNamespace(stdout=listing if self.list_text is None else self.list_text)
        if "-cp" in argv:
            root = ET.Element("properties")
            ET.SubElement(root, "entry", key="count.1").text = "1"
            for arm in self.catalog:
                prefix = arm.benchmark.rsplit(".", 1)[-1] + "." + arm.arm
                for field in tr.oc.SETUP:
                    value = arm.arm if field == "scenario" else "same"
                    ET.SubElement(root, "entry", key=prefix + "." + field).text = value
            ET.ElementTree(root).write(argv[-1])
        else:
            if self.failure:
                raise self.failure
            # Pin the emitted command independently of the runner's constants and fake metadata.
            self.assertEqual(["-Xms2g", "-Xmx2g", "-XX:+UseG1GC",
                              "--add-opens=java.base/java.lang=ALL-UNNAMED",
                              "--add-opens=java.base/java.nio=ALL-UNNAMED",
                              "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED"], argv[1:7])
            expected = {"-f": "3", "-t": "1", "-bm": "thrpt", "-tu": "s",
                        "-w": "1s", "-i": "10", "-r": "1s", "-rf": "json", "-foe": "true"}
            for option, value in expected.items():
                self.assertEqual(value, argv[argv.index(option) + 1])
            row = copy.deepcopy(self.row)
            arm = argv[argv.index("-p") + 1].split("=")[1]
            row["benchmark"] = next(a.benchmark for a in self.catalog
                                    if argv[argv.index("-jar") + 2] ==
                                    "^" + tr.re.escape(a.benchmark) + "$")
            row["params"][tc.SCENARIO] = arm
            row.update(jdkVersion="21.0.6", jvmArgs=argv[1:argv.index("-jar")],
                       warmupIterations=int(argv[argv.index("-wi") + 1]), warmupTime="1 s",
                       measurementIterations=10, measurementTime="1 s")
            score = 80 if arm == "on" else 100
            row["primaryMetric"].update(score=score, scoreError=1,
                                       scoreConfidence=[score - 1, score + 1],
                                       rawData=[[score] * 10 for _ in range(3)])
            Path(argv[argv.index("-rff") + 1]).write_text(json.dumps([row]))
        return SimpleNamespace(stdout="")

    def execute(self):
        return tr.execute(self.args, _catalog=self.catalog)

    def test_round_trip_alternates_and_keeps_stabilization_passes_separate(self):
        # Both full passes keep their own pairs, with reversed order in repetition two.
        self.assertEqual(0, self.execute())
        measured = [c for c in self.commands if "-rff" in c]
        self.assertEqual(["off", "on", "on", "off"] * 2,
                         [c[c.index("-p") + 1].split("=")[1] for c in measured])
        self.assertEqual(["5"] * 4 + ["8"] * 4,
                         [c[c.index("-wi") + 1] for c in measured])
        self.assertEqual(8, len({c[c.index("-rff") + 1] for c in measured}))
        self.assertEqual(1, sum("-lp" in c for c in self.commands))
        self.assertTrue(all(c[c.index("-f") + 1] == "3" for c in measured))
        run = tc.Run("run", "producer", "revision", 2, 2, "artifact")
        manifest, records = tc.load(self.args.directory, run, self.catalog)
        self.assertEqual(8, len(records))
        self.assertEqual(self.hardware, manifest["files"][0]["metadata"]["hardware"])
        status, report = tc.compare(self.args.directory, run, "validation", _catalog=self.catalog)
        self.assertEqual(0, status)
        self.assertEqual(2, report.count("Combined: budget breach"))

    def test_existing_directory_is_refused_without_launch_or_overwrite(self):
        # An interrupted output directory is evidence too, and cannot be reused.
        Path(self.args.directory).mkdir()
        sentinel = Path(self.args.directory) / "raw"
        sentinel.write_text("retained")
        with self.assertRaises(FileExistsError):
            self.execute()
        self.assertEqual("retained", sentinel.read_text())
        self.assertEqual([], self.commands)

    def test_missing_arm_is_recorded_but_never_launched_and_returns_non_success(self):
        # Presence comes from this benchmark's listing, not another benchmark's arms.
        self.catalog += (tc.Arm(tc.PREFIX + "fallback", "off", rows=("1",)),
                         tc.Arm(tc.PREFIX + "fallback", "on", 10, True, ("1",)))
        self.listed = (self.catalog[0], self.catalog[2], self.catalog[3])
        with self.assertRaisesRegex(ValueError, "not available"):
            self.execute()
        measured = [c for c in self.commands if "-rff" in c]
        self.assertEqual([
            ("^" + tr.re.escape(tc.PREFIX + path) + "$", "telemetryScenario=" + arm)
            for path, arm in [("sql", "off"), ("fallback", "off"), ("fallback", "on"),
                              ("sql", "off"), ("fallback", "on"), ("fallback", "off")] * 2
        ], [(c[c.index("-jar") + 2], c[c.index("-p") + 1]) for c in measured])
        manifest = json.loads((Path(self.args.directory) / "manifest.json").read_text())
        self.assertEqual(4, sum(e["execution"] == "unavailable" for e in manifest["files"]))

    def test_failure_empty_listing_and_interrupt_never_publish_manifest(self):
        # Failure signals survive all three execution boundaries.
        for failure, listing in ((subprocess.CalledProcessError(1, ["java"]), None),
                                 (KeyboardInterrupt(), None), (None, "")):
            with self.subTest(failure=failure, listing=listing):
                self.args.directory = str(self.root / ("output" + str(len(self.commands))))
                self.failure, self.list_text = failure, listing
                with self.assertRaises((subprocess.SubprocessError, KeyboardInterrupt, ValueError)):
                    self.execute()
                self.assertFalse((Path(self.args.directory) / "manifest.json").exists())

    def test_crashed_fork_and_wrong_schedule_fail_measurement_validation(self):
        # A zero-exit launcher cannot hide truncated fork data or altered VM settings.
        self.execute()
        path = next(Path(self.args.directory).glob("*-off-*.json"))
        row = json.loads(path.read_text())[0]
        for field, value in (("forks", 2), ("warmupIterations", 4), ("jdkVersion", "25"),
                             ("jvmArgs", [])):
            broken = dict(row, **{field: value})
            with self.assertRaises(ValueError):
                tr.validate_measurement([broken], self.catalog[0], 5)
        row["primaryMetric"]["rawData"].pop()
        with self.assertRaisesRegex(ValueError, "fork"):
            tr.validate_measurement([row], self.catalog[0], row["warmupIterations"])

    def test_no_on_arm_and_invalid_inputs_refuse_before_launch(self):
        # Availability depends only on trusted configuration, not candidate data.
        with self.assertRaisesRegex(ValueError, "not available"):
            tr.execute(self.args)
        for warmup in ([4], [5, 5], [8, 5]):
            self.args.warmup = warmup
            with self.assertRaises(ValueError):
                self.execute()
        self.assertEqual([], self.commands)
        self.assertFalse(Path(self.args.directory).exists())

    def test_dangling_output_symlink_never_creates_target_or_launches(self):
        # A dangling symlink cannot redirect a new evidence directory.
        target = self.root / "absent"
        Path(self.args.directory).symlink_to(target)
        with self.assertRaises(FileExistsError):
            self.execute()
        self.assertFalse(target.exists())
        self.assertEqual([], self.commands)

    def test_incomplete_hardware_and_revision_mismatch_refuse_before_java(self):
        # Neither missing machine evidence nor a mislabeled jar can launch a benchmark.
        with patch.object(tr.oc, "capture", return_value={}):
            with self.assertRaisesRegex(ValueError, "hardware"):
                self.execute()
        self.args.directory = str(self.root / "wrong_revision")
        self.args.revision = "other"
        with self.assertRaisesRegex(ValueError, "revision"):
            self.execute()
        self.assertEqual([], self.commands)

    def test_missing_result_file_fails_without_complete_manifest(self):
        # A successful process status cannot replace the required raw evidence.
        process = self.process
        def omit(argv, **kwargs):
            result = process(argv, **kwargs)
            if "-rff" in argv:
                Path(argv[argv.index("-rff") + 1]).unlink()
            return result
        with patch.object(tr.subprocess, "run", side_effect=omit):
            with self.assertRaises(OSError):
                self.execute()
        self.assertFalse((Path(self.args.directory) / "manifest.json").exists())

    def test_candidate_tokens_never_select_additional_benchmarks_or_options(self):
        # Even option-shaped or shell-shaped candidate arm values remain presence data.
        text = (tc.PREFIX + 'sql\n  param "telemetryScenario" = {off, on; touch x, -f 0}\n'
                + tc.PREFIX + 'fallback\n  param "telemetryScenario" = {on}\n')
        self.assertEqual([dict(benchmark=self.catalog[0].benchmark, arm="off")],
                         tr.listing(text, self.catalog))
        with self.assertRaisesRegex(ValueError, "invalid"):
            tr.listing(tc.PREFIX + 'sql\n  param "bad" = {x', self.catalog)

    def test_cli_shipped_catalog_reports_readable_not_available_without_launch(self):
        # Exercise the real CLI refusal, not a mocked execute function.
        argv = []
        for name in ("jar", "scratch", "directory", "run", "producing_attempt",
                     "revision", "artifact", "harness", "machine_type"):
            argv += ["--" + name.replace("_", "-"), getattr(self.args, name)]
        with patch.object(sys, "stderr", new_callable=io.StringIO) as stderr:
            self.assertEqual(2, tr.main(argv))
        self.assertIn("not available", stderr.getvalue())
        self.assertNotIn("&#32;", stderr.getvalue())
        self.assertEqual([], self.commands)

    def test_failed_launch_output_is_visible_with_terminal_controls_escaped(self):
        # A failed JMH launch exposes its diagnostic even when no JSON was written.
        self.failure = subprocess.CalledProcessError(
            1, ["java"], output="fork failed\n\x1b[31mred")
        with patch.object(sys, "stderr", new_callable=io.StringIO) as stderr:
            with self.assertRaises(subprocess.CalledProcessError):
                self.execute()
        self.assertIn("fork failed", stderr.getvalue())
        self.assertIn(r"\u001b", stderr.getvalue())
        self.assertNotIn("\x1b", stderr.getvalue())
        self.assertFalse((Path(self.args.directory) / "manifest.json").exists())

    def test_rebuild_after_listing_cannot_change_snapshot_used_by_later_launches(self):
        # Replacing the source jar between launches leaves every classpath on the same copy.
        process = self.process
        def rebuild(argv, **kwargs):
            result = process(argv, **kwargs)
            if "-lp" in argv:
                Path(self.args.jar).write_bytes(b"replacement build")
            return result
        with patch.object(tr.subprocess, "run", side_effect=rebuild):
            self.assertEqual(0, self.execute())
        jars = {c[c.index(flag) + 1] for c in self.commands for flag in ("-jar", "-cp")
                if flag in c}
        self.assertEqual(1, len(jars))
        self.assertFalse(Path(next(iter(jars))).exists())

    def test_wrong_jdk_leaves_no_output_and_corrected_retry_succeeds(self):
        # Preflight rejection must not reserve the output directory.
        self.version = 'openjdk version "25"'
        with self.assertRaisesRegex(ValueError, "JDK 21"):
            self.execute()
        self.assertFalse(Path(self.args.directory).exists())
        self.version = 'openjdk version "21.0.6"'
        self.assertEqual(0, self.execute())

    def test_symlink_created_during_preflight_cannot_redirect_evidence(self):
        # Exclusive mkdir on the original output path closes the preflight symlink race.
        target = self.root / "absent"
        process = self.process
        def redirect(argv, **kwargs):
            result = process(argv, **kwargs)
            if "-lp" in argv:
                Path(self.args.directory).symlink_to(target)
            return result
        with patch.object(tr.subprocess, "run", side_effect=redirect):
            with self.assertRaises(FileExistsError):
                self.execute()
        self.assertFalse(target.exists())
        self.assertFalse(any("-rff" in c for c in self.commands))

    def test_dirty_or_mismatched_checkout_refuses_before_java(self):
        # Manifest provenance must come from the observed clean checkout, not the jar label.
        for head, status in (("other", ""), ("revision", " M source.java")):
            with self.subTest(head=head, status=status):
                with patch.object(tr.subprocess, "check_output", side_effect=[head, status]):
                    with self.assertRaisesRegex(ValueError, "revision"):
                        self.execute()
                self.assertFalse(Path(self.args.directory).exists())
        self.assertEqual([], self.commands)

    def test_relative_java_resolves_against_callers_directory_before_changing_cwd(self):
        # A relative Java path is made absolute before any subprocess changes directory.
        self.args.java = "jdk21/bin/java"
        with patch.object(tr.shutil, "which", return_value=self.args.java) as which:
            self.assertEqual(0, self.execute())
        which.assert_called_once_with("jdk21/bin/java")
        expected = str(Path(self.args.java).resolve())
        self.assertTrue(all(c[0] == expected for c in self.commands))

    def test_bare_java_lookup_is_absolute_and_missing_java_leaves_no_output(self):
        # PATH lookup happens before launch, and a missing executable creates no evidence.
        with patch.object(tr.shutil, "which", return_value=None):
            with self.assertRaisesRegex(ValueError, "Java executable not found"):
                self.execute()
        self.assertEqual([], self.commands)
        self.assertFalse(Path(self.args.directory).exists())
        with patch.object(tr.shutil, "which", return_value="/jdk21/bin/java") as which:
            self.assertEqual(0, self.execute())
        which.assert_called_once_with("java")
        self.assertTrue(all(c[0] == "/jdk21/bin/java" for c in self.commands))

    def test_single_repetition_refuses_before_launch_or_output_creation(self):
        # At least two independent repetitions are required to reverse arm order.
        self.args.repetitions = 1
        with self.assertRaisesRegex(ValueError, "two repetitions"):
            self.execute()
        self.assertEqual([], self.commands)
        self.assertFalse(Path(self.args.directory).exists())


if __name__ == "__main__":
    unittest.main()
