"""End-to-end offline evidence checks with a test-only on arm."""
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import telemetry_compare as tc
import test_off_compare as fixtures


class TelemetryTest(unittest.TestCase):
    def setUp(self):
        fixture = fixtures.ComparisonTest()
        fixture.setUp()
        self.row, self.metadata = fixture.row, fixture.doc
        self.metadata.pop("results")
        self.metadata["setup"] = {"1": self.row.pop("setup")}
        self.run = tc.Run("run", "producer", "revision", 2, 2, "artifact")
        self.catalog = (tc.Arm(tc.PREFIX + "sql", "off", rows=("1",)),
                        tc.Arm(tc.PREFIX + "sql", "on", 10, True, rows=("1",)))
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.manifest = dict(run="run", producingAttempt="producer", revision="revision",
                             artifact="artifact", listing=[dict(benchmark=a.benchmark, arm=a.arm)
                                                          for a in self.catalog], files=[])
        self.raw = {}
        for name, (arm, repetition, measurement_pass) in tc.expected(self.run, self.catalog).items():
            row, metadata = copy.deepcopy(self.row), copy.deepcopy(self.metadata)
            row["params"][tc.SCENARIO] = metadata["setup"]["1"]["scenario"] = arm.arm
            score = 80 if arm.arm == "on" else 100
            row["primaryMetric"].update(score=score, scoreError=.1,
                                       scoreConfidence=[score - .1, score + .1])
            self.raw[name] = [row]
            self.manifest["files"].append(dict(name=name, execution="ok", metadata=metadata))
        self.save()

    def save(self):
        for entry in self.manifest["files"]:
            name = entry["name"]
            path = self.directory / name
            path.write_text(json.dumps(self.raw[name]))
            entry["sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        (self.directory / "manifest.json").write_text(json.dumps(self.manifest))

    def report(self):
        self.save()
        return tc.compare(self.directory, self.run, "validator", _catalog=self.catalog)

    def rebuild(self, catalog):
        """Create complete evidence independently for every benchmark, size and pass."""
        for entry in self.manifest["files"]:
            (self.directory / entry["name"]).unlink()
        self.catalog = catalog
        self.raw = {}
        self.manifest["files"] = []
        self.manifest["listing"] = [dict(benchmark=a.benchmark, arm=a.arm) for a in catalog]
        for name, (arm, _, _) in tc.expected(self.run, catalog).items():
            rows = []
            metadata = copy.deepcopy(self.metadata)
            metadata["setup"] = {}
            for size in arm.rows:
                row = copy.deepcopy(self.row)
                row["benchmark"] = arm.benchmark
                row["params"].update(rows=size, telemetryScenario=arm.arm)
                score = 80 if arm.arm == "on" else 100
                row["primaryMetric"].update(score=score, scoreError=.1,
                                           scoreConfidence=[score - .1, score + .1])
                setup = dict(self.metadata["setup"]["1"], scenario=arm.arm, expectedCount=size)
                metadata["setup"][size] = setup
                rows.append(row)
            self.raw[name] = rows
            self.manifest["files"].append(dict(name=name, execution="ok", metadata=metadata))

    def test_thread_series_keep_distinct_decisions_and_require_all_repetitions(self):
        # Complete 1-thread and 8-thread series reach independent, valid decisions.
        self.catalog = (self.catalog[0], tc.Arm(tc.PREFIX + "sql", "on", 15, True, ("1",)))
        for name, rows in self.raw.items():
            extra = copy.deepcopy(rows[0])
            extra["threads"] = 8
            rows.append(extra)
            if "-on-" in name:
                rows[0]["primaryMetric"].update(score=95, scoreConfidence=[94.9, 95.1])
        report = self.report()[1]
        self.assertEqual(2, report.count("Combined: within budget"))
        self.assertEqual(2, report.count("Combined: budget breach"))
        # Each dimension now lacks one required repetition, despite aggregate completeness.
        for name, rows in self.raw.items():
            self.raw[name] = [rows[0] if "-r1-" in name else rows[1]]
        report = self.report()[1]
        self.assertEqual(4, report.count("Combined: inconclusive: repetitions disagree"))
        self.assertNotIn("Combined: budget breach", report)

    def test_complete_passes_use_their_own_off_denominator(self):
        # Pass 1 loses 20 percent. Pass 2 gains 25 percent and must not borrow pass 1.
        for name, rows in self.raw.items():
            if "-p2-" in name:
                score = 80 if "-off-" in name else 100
                rows[0]["primaryMetric"].update(score=score,
                                               scoreConfidence=[score - .1, score + .1])
        report = self.report()[1]
        self.assertEqual(1, report.count("Combined: budget breach"))
        self.assertEqual(1, report.count("Combined: within budget"))

    def test_zero_seven_and_ten_percent_uncertainty_are_accepted_end_to_end(self):
        # All three legal boundaries retain separated intervals at on 80 and off 100.
        for relative in (0, .07, .10):
            with self.subTest(relative=relative):
                for rows in self.raw.values():
                    metric = rows[0]["primaryMetric"]
                    score = metric["score"]
                    error = score * relative
                    metric.update(scoreError=error, scoreConfidence=[score - error, score + error])
                self.assertEqual(2, self.report()[1].count("Combined: budget breach"))

    def test_isolated_manifest_guards_reject_duplicate_inventory_run_and_revision(self):
        # Empty unavailable duplicates cannot fail through duplicate row identity instead.
        entry = self.manifest["files"][0]
        entry["execution"] = "unavailable"
        self.raw[entry["name"]] = []
        self.manifest["files"].append(copy.deepcopy(entry))
        with self.assertRaisesRegex(ValueError, "duplicate or missing manifest files"):
            self.report()
        self.manifest["files"].pop()
        self.save()
        extra = self.directory / "extra.json"
        extra.write_text("[]")
        with self.assertRaisesRegex(ValueError, "unexpected artifact files"):
            tc.compare(self.directory, self.run, "v", _catalog=self.catalog)
        extra.unlink()
        for field in ("run", "revision"):
            original = self.manifest[field]
            self.manifest[field] = "other"
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "identity mismatch"):
                self.report()
            self.manifest[field] = original

    def test_missing_off_listing_does_not_remove_its_files(self):
        # Only the off listing entry is absent. All raw files remain valid and complete.
        self.manifest["listing"] = [item for item in self.manifest["listing"] if item["arm"] != "off"]
        report = self.report()[1]
        self.assertEqual(2, report.count("Combined: inconclusive: off arm missing"))
        self.assertNotIn("Combined: budget breach", report)

    def test_setup_scenario_and_software_versions_have_independent_negative_controls(self):
        for field in ("scenario", "softwareVersions"):
            original = copy.deepcopy(self.manifest)
            for entry in self.manifest["files"]:
                if "-on-" in entry["name"]:
                    if field == "scenario":
                        entry["metadata"]["setup"]["1"]["scenario"] = "off"
                    else:
                        entry["metadata"]["softwareVersions"] = {"database": "different"}
            with self.subTest(field=field):
                self.assertEqual(2, self.report()[1].count("Combined: inconclusive: incompatible setup"))
            self.manifest = original

    def test_file_names_separate_benchmarks_and_identifier_components(self):
        # A constant hash must collide for these two off benchmarks, independent of expected().
        catalog = (self.catalog[0], tc.Arm(tc.PREFIX + "translated", "off", rows=("1",)),
                   self.catalog[1])
        self.rebuild(catalog)
        names = [entry["name"] for entry in self.manifest["files"]]
        self.assertEqual(12, len(set(names)))
        sql_hash = hashlib.sha256((tc.PREFIX + "sql").encode()).hexdigest()
        translated_hash = hashlib.sha256((tc.PREFIX + "translated").encode()).hexdigest()
        self.assertNotEqual(sql_hash, translated_hash)
        self.assertTrue(any("-off-r1-p1-" + sql_hash + ".json" in n for n in names))
        self.assertTrue(any("-off-r1-p1-" + translated_hash + ".json" in n for n in names))
        for run, attempt in (("a-b", "c"), ("a", "b-c")):
            with self.assertRaisesRegex(ValueError, "identifier"):
                tc.Run(run, attempt, "revision", 2, 2, "artifact")

    def test_setup_expected_count_is_specific_to_each_row_size(self):
        self.rebuild(tuple(tc.Arm(a.benchmark, a.arm, a.budget, a.approved, ("1", "10000"))
                           for a in self.catalog))
        self.save()
        _, records = tc.load(self.directory, self.run, self.catalog)
        for key, (row, metadata, _) in records.items():
            size = row["params"]["rows"]
            self.assertEqual(size, metadata["setup"]["expectedCount"])
            self.assertEqual(size, json.loads(key[2])["expectedCount"])
        self.assertEqual(4, self.report()[1].count("Combined: budget breach"))

    def test_all_outcomes_survive_honest_and_padded_evidence_truncation(self):
        for padded in (False, True):
            with self.subTest(padded=padded):
                self.run = tc.Run("run", "producer", "revision", 3, 2, "artifact")
                catalog = tuple(tc.Arm(tc.PREFIX + benchmark, arm, 10, True, ("1", "10000"))
                                for benchmark in ("sql", "translated", "fallback")
                                for arm in ("off", "on"))
                self.rebuild(catalog)
                if padded:
                    for entry in self.manifest["files"]:
                        entry["metadata"].update({str(i): "<@|" * 100 for i in range(200)})
                status, report = self.report()
                self.assertEqual(0, status)
                self.assertIn("Report truncated.", report)
                self.assertEqual(12, report.count("- Combined: budget breach"))
                for repetition in range(1, 4):
                    self.assertEqual(12, report.count(f"- Repetition {repetition}: budget breach"))
                self.assertLess(len(report), 60000)
                # Incompatible versions must retain their reasons and rerun advice too.
                for entry in self.manifest["files"]:
                    if "-on-" in entry["name"]:
                        entry["metadata"]["softwareVersions"] = {"database": "other"}
                status, report = self.report()
                self.assertEqual(0, status)
                self.assertEqual(12, report.count("Combined: inconclusive: incompatible setup"))
                self.assertEqual(12, report.count("Rerun both arms in a new pass"))
                for repetition in range(1, 4):
                    self.assertEqual(12, report.count(
                        f"- Repetition {repetition}: inconclusive: incompatible setup"))

    def test_wholly_unavailable_pass_still_has_required_outcomes(self):
        # Pass 2 has empty files for both arms. Pass 1 cannot certify that missing pass.
        for entry in self.manifest["files"]:
            if "-p2-" in entry["name"]:
                entry["execution"] = "unavailable"
                self.raw[entry["name"]] = []
        report = self.report()[1]
        self.assertEqual(1, report.count("Combined: budget breach"))
        self.assertEqual(1, report.count("Combined: inconclusive: missing pair"))
        self.assertEqual(2, report.count("- Repetition 1:"))
        self.assertEqual(2, report.count("- Repetition 2:"))

    def test_outcome_overflow_is_visibly_fatal(self):
        # Full dimension series can exceed the report budget even with small raw files.
        for rows in self.raw.values():
            template = copy.deepcopy(rows[0])
            for threads in range(2, 120):
                rows.append(dict(copy.deepcopy(template), threads=threads))
        status, report = self.report()
        self.assertEqual(2, status)
        self.assertIn("Fatal: comparison outcomes exceed report capacity", report)

    def test_input_limits_fail_before_rendering(self):
        for field in ("records", "params", "metadata", "value", "file"):
            original = copy.deepcopy((self.raw, self.manifest))
            name = self.manifest["files"][0]["name"]
            if field == "records":
                self.raw[name] *= tc.MAX_RECORDS + 1
            elif field == "params":
                self.raw[name][0]["params"].update({str(i): "v" for i in range(tc.MAX_PARAMS)})
            elif field == "metadata":
                self.manifest["files"][0]["metadata"].update(
                    {str(i): "v" for i in range(tc.MAX_FIELDS)})
            elif field == "value":
                self.manifest["files"][0]["metadata"]["padding"] = "x" * (tc.MAX_VALUE + 1)
            else:
                self.save()
                (self.directory / name).write_bytes(b" " * (tc.MAX_FILE_BYTES + 1))
            with self.subTest(field=field), patch.object(tc, "safe", side_effect=AssertionError):
                # Header values are trusted. Test load directly to isolate candidate rendering.
                with self.assertRaises(ValueError):
                    if field == "file":
                        tc.load(self.directory, self.run, self.catalog)
                    else:
                        self.save()
                        tc.load(self.directory, self.run, self.catalog)
            self.raw, self.manifest = original

    def test_denominator_budget_and_passes_are_not_pooled(self):
        status, report = self.report()
        self.assertEqual(0, status)  # A slowdown is report-only.
        self.assertIn("Diagnostic loss percent, not a budget decision: " + tc.safe("20"), report)
        self.assertEqual(2, report.count("- Combined: budget breach"))
        # A gain in one repetition cannot hide a breach in another.
        for name, rows in self.raw.items():
            if "-on-r2-p1-" in name:
                rows[0]["primaryMetric"].update(score=120, scoreConfidence=[119.9, 120.1])
        report = self.report()[1]
        self.assertEqual(1, report.count("- Combined: budget breach"))
        self.assertIn("Combined: inconclusive: repetitions disagree", report)
        self.assertIn("Rerun both arms in a new pass", report)

    def test_exact_budget_boundary_and_unmatched_off_dimensions(self):
        for name, rows in self.raw.items():
            if "-on-" in name:
                rows[0]["primaryMetric"].update(score=90, scoreConfidence=[89.9, 90.1])
        self.assertEqual(2, self.report()[1].count("Combined: within budget"))
        # An extra off thread count must not disappear from the required pairs.
        for name, rows in self.raw.items():
            if "-off-" in name:
                extra = copy.deepcopy(rows[0])
                extra["threads"] = 2
                rows.append(extra)
        self.assertIn("Combined: within budget", self.report()[1])
        self.assertIn("Combined: inconclusive: missing pair", self.report()[1])

    def test_pairing_retains_every_dimension_and_never_crosses_passes(self):
        for field in ("threads", "forks", "mode", "unit", "parameter", "setup"):
            with self.subTest(field=field):
                original = copy.deepcopy((self.raw, self.manifest))
                for entry in self.manifest["files"]:
                    if "-on-" not in entry["name"]:
                        continue
                    row = self.raw[entry["name"]][0]
                    if field in ("threads", "forks", "mode"):
                        row[field] = {"threads": 2, "forks": 4, "mode": "avgt"}[field]
                    elif field == "unit":
                        row["primaryMetric"]["scoreUnit"] = "ops/ms"
                    elif field == "parameter":
                        row["params"]["extra"] = "candidate"
                    else:
                        entry["metadata"]["setup"]["1"]["query"] = "other"
                self.assertIn("inconclusive: missing pair or incompatible setup", self.report()[1])
                self.raw, self.manifest = original
        # The only on row and the only off row exist in different passes.
        for entry in self.manifest["files"]:
            if "-on-" in entry["name"] and "-p1-" in entry["name"] or (
                    "-off-" in entry["name"] and "-p2-" in entry["name"]):
                entry["execution"] = "unavailable"
                self.raw[entry["name"]] = []
        self.assertNotIn("Combined: budget breach", self.report()[1])
        self.assertIn("inconclusive: missing pair", self.report()[1])

    def test_invalid_statistics_and_overlap_are_inconclusive_not_zero_error(self):
        for change in (dict(score=0), dict(score=-1), dict(score=float("inf")),
                       dict(score=float("nan")), dict(scoreError=None),
                       dict(scoreError="0"), dict(scoreError=-1), dict(scoreError=True),
                       dict(scoreError=float("inf")), dict(scoreConfidence=[79, 81]),
                       dict(scoreError=9, scoreConfidence=[71, 89]),
                       dict(score=100, scoreConfidence=[99.9, 100.1])):
            original = copy.deepcopy(self.raw)
            for name, rows in self.raw.items():
                if "-on-" in name:
                    rows[0]["primaryMetric"].update(change)
            report = self.report()[1]
            self.assertIn("Combined: inconclusive", report, change)
            self.assertNotIn("Combined: within budget", report)
            self.raw = original

    def test_unavailable_catalog_and_missing_candidate_are_separate(self):
        self.assertEqual(2, tc.compare(self.directory, self.run, "v")[0])
        self.assertIn("not available", tc.compare(self.directory, self.run, "v")[1])
        self.manifest["listing"] = []
        self.assertIn("catalog arm missing from candidate listing", self.report()[1])
        # Listing absence never excuses a missing required file.
        (self.directory / self.manifest["files"][0]["name"]).unlink()
        with self.assertRaises(ValueError):
            tc.compare(self.directory, self.run, "v", _catalog=self.catalog)
        self.save()
        self.catalog = self.catalog[1:]
        # Without a trusted off partner the on evidence cannot become an off comparison.
        for entry in list(self.manifest["files"]):
            if "-off-" in entry["name"]:
                (self.directory / entry["name"]).unlink()
                self.manifest["files"].remove(entry)
        self.manifest["listing"] = [dict(benchmark=self.catalog[0].benchmark, arm="on")]
        self.assertIn("catalog arm has no matching off partner", self.report()[1])

    def test_integrity_errors_are_fatal_even_with_missing_listing(self):
        self.manifest["listing"] = []
        for mutation in ("duplicate", "missing", "producer", "artifact", "execution",
                         "revision", "duplicate-row", "empty-ok"):
            original = copy.deepcopy((self.raw, self.manifest))
            if mutation == "duplicate":
                self.manifest["files"].append(self.manifest["files"][0])
            elif mutation == "missing":
                self.manifest["files"].pop()
            elif mutation in ("producer", "artifact"):
                self.manifest[{"producer": "producingAttempt"}.get(mutation, mutation)] = "other"
            elif mutation == "execution":
                self.manifest["files"][0]["execution"] = "failed"
            elif mutation == "revision":
                self.manifest["files"][0]["metadata"]["revision"] = "other"
            elif mutation == "duplicate-row":
                self.raw[self.manifest["files"][0]["name"]] *= 2
            else:
                self.raw[self.manifest["files"][0]["name"]] = []
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.report()
            self.raw, self.manifest = original
        self.save()
        path = self.directory / self.manifest["files"][0]["name"]
        path.write_text("corrupt")
        with self.assertRaisesRegex(ValueError, "digest"):
            tc.compare(self.directory, self.run, "v", _catalog=self.catalog)

    def test_manifest_paths_and_symlinks_are_not_candidate_authority(self):
        entry = self.manifest["files"][0]
        name = entry["name"]
        entry["name"] = "../injected<script>"
        (self.directory / "manifest.json").write_text(json.dumps(self.manifest))
        with self.assertRaises(ValueError):
            tc.compare(self.directory, self.run, "v", _catalog=self.catalog)
        entry["name"] = name
        self.save()
        path = self.directory / name
        path.unlink()
        path.symlink_to(self.directory / "manifest.json")
        with self.assertRaisesRegex(ValueError, "regular file"):
            tc.compare(self.directory, self.run, "v", _catalog=self.catalog)

    def test_parameter_and_metadata_rendering_have_independent_negative_controls(self):
        parameter, metadata = "@parameter|<p>", "@metadata|<m>"
        for name, rows in self.raw.items():
            rows[0]["params"]["candidate"] = parameter
        for entry in self.manifest["files"]:
            entry["metadata"]["softwareVersions"] = {"version": metadata}
        report = self.report()[1]
        self.assertIn(tc.safe(parameter), report)
        self.assertIn(tc.safe({"version": metadata}), report)
        self.assertNotIn(parameter, report)
        self.assertNotIn(metadata, report)
        self.assertIn("Combined: budget breach", report)

    def test_rendering_bounds_names_params_metadata_and_dedicated_marker(self):
        hostile = "<script>|@someone #123\n`" + "x" * 1000
        self.catalog = tuple(tc.Arm(hostile, a.arm, a.budget, a.approved, a.rows)
                             for a in self.catalog)
        old = copy.deepcopy(self.manifest["files"])
        self.manifest["files"] = []
        self.manifest["listing"] = [dict(benchmark=hostile, arm=a.arm) for a in self.catalog]
        for (name, _), entry in zip(tc.expected(self.run, self.catalog).items(), old):
            rows = self.raw[entry["name"]]
            (self.directory / entry["name"]).unlink()
            entry["name"] = name
            rows[0]["benchmark"] = hostile
            rows[0]["params"]["hostile"] = hostile
            entry["metadata"]["softwareVersions"] = {hostile: hostile}
            self.raw[name] = rows
            self.manifest["files"].append(entry)
        status, report = self.report()
        self.assertEqual(0, status)
        self.assertIn("<!-- ytdb-telemetry-comparison -->", report)
        self.assertIn(tc.safe(hostile), report)
        self.assertNotIn("<script>", report)
        self.assertNotIn("@someone", report)
        self.assertLess(len(report), 60000)
        self.assertIn("Candidate-reported", report)
        self.assertIn("Producing attempt: " + tc.safe("producer"), report)
        self.assertIn("Validation attempt: " + tc.safe("validator"), report)

    def test_unapproved_budget_and_exclusive_output_and_unique_file_names(self):
        self.catalog = (self.catalog[0], tc.Arm(tc.PREFIX + "sql", "on", 5, False, ("1",)))
        self.assertIn("proposed budget is unapproved", self.report()[1])
        self.assertNotIn("Combined: budget breach", self.report()[1])
        names = tc.expected(self.run, self.catalog)
        self.assertEqual(8, len(names))
        for name in names:
            self.assertIn("run-producer-revision", name)
        args = ["--" + k.replace("_", "-") + "=" + str(v) for k, v in
                dict(directory=self.directory, run="run", producing_attempt="producer",
                     revision="revision", artifact="artifact", validation_attempt="validator",
                     output=self.directory / "report.md", repetitions=2, passes=2).items()]
        self.assertEqual(2, tc.main(args))  # No shipped on arm exists.
        before = (self.directory / "report.md").read_bytes()
        self.assertEqual(2, tc.main(args))  # Exclusive creation refuses overwrite.
        self.assertEqual(before, (self.directory / "report.md").read_bytes())
        with patch.object(tc, "CATALOG", self.catalog):
            self.assertEqual(2, tc.main(args))  # Unexpected artifact file is fatal.
        (self.directory / "report.md").unlink()
        with patch.object(tc, "CATALOG", self.catalog):
            self.assertEqual(0, tc.main(args))
            (self.directory / "report.md").unlink()
            (self.directory / next(iter(names))).write_text("corrupt")
            self.assertEqual(2, tc.main(args))
            self.assertFalse((self.directory / "report.md").exists())
        result = subprocess.run([sys.executable, tc.__file__, *args, "--catalog=on"],
                                capture_output=True)
        self.assertNotEqual(0, result.returncode)
