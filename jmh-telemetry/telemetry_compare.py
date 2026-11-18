"""Offline trusted on-against-off validation. This tool never launches candidate code."""
import argparse
from dataclasses import dataclass
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import re
import sys

from off_compare import (PREFIX, ReportLines, compatible, finite, identity as row_identity,
                         interval, read_json, safe)

MARKER = "<!-- ytdb-telemetry-comparison -->"
SCENARIO = "telemetryScenario"
MAX_FILE_BYTES = 4 * 1024 * 1024
MAX_RECORDS = 256
MAX_FIELDS = 256
MAX_PARAMS = 32
MAX_VALUE = 4096
MAX_FILES = 2048


def bounded_json(path):
    """Reject oversized candidate data before interpreting or rendering it."""
    if path.stat().st_size > MAX_FILE_BYTES:
        raise ValueError("input file size limit exceeded")
    value = read_json(path)

    def check(item, depth=0):
        if depth > 12:
            raise ValueError("input nesting limit exceeded")
        if isinstance(item, str) and len(item) > MAX_VALUE:
            raise ValueError("input value length limit exceeded")
        if isinstance(item, (dict, list)):
            limit = MAX_FIELDS if isinstance(item, dict) else MAX_FILES
            if len(item) > limit:
                raise ValueError("input field or item count limit exceeded")
            children = list(item) + list(item.values()) if isinstance(item, dict) else item
            for child in children:
                check(child, depth + 1)
    check(value)
    return value


@dataclass(frozen=True)
class Arm:
    benchmark: str
    arm: str
    budget: float | None = None
    approved: bool = False
    rows: tuple = ("1", "10000")


# Only trusted source changes can register on arms and their approved budgets.
CATALOG = tuple(Arm(PREFIX + path, "off") for path in ("sql", "translated", "fallback"))


@dataclass(frozen=True)
class Run:
    run: str
    producing_attempt: str
    revision: str
    repetitions: int
    passes: int
    artifact: str

    def __post_init__(self):
        for value in (self.run, self.producing_attempt, self.revision, self.artifact):
            if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_]{1,100}", value):
                raise ValueError("invalid trusted run identifier")
        if any(type(n) is not int or n < 1 for n in (self.repetitions, self.passes)):
            raise ValueError("invalid repetition or pass count")


def expected(run, catalog):
    """The manifest cannot add, remove or rename any required evidence file."""
    result = {}
    for arm in catalog:
        for repetition in range(1, run.repetitions + 1):
            for measurement_pass in range(1, run.passes + 1):
                name = (f"{run.run}-{run.producing_attempt}-{run.revision}-{arm.arm}-"
                        f"r{repetition}-p{measurement_pass}-"
                        + hashlib.sha256(arm.benchmark.encode()).hexdigest() + ".json")
                if name in result:
                    raise ValueError("duplicate trusted catalog entry")
                result[name] = (arm, repetition, measurement_pass)
    if len(result) > MAX_FILES:
        raise ValueError("trusted inventory limit exceeded")
    return result


def identity(row, metadata, arm, repetition, measurement_pass, run, *, partner=False):
    """One key for validation, pairing and rendering. Only scenario changes for pairing."""
    setup = dict(metadata.get("setup") or {})
    if partner:
        setup["scenario"] = "off"
    return (row_identity(row, scenario="off" if partner else None), run.revision,
            json.dumps(setup, sort_keys=True), "off" if partner else arm,
            repetition, measurement_pass, run.run, run.producing_attempt)


def load(directory, run, catalog):
    directory = Path(directory)
    files = expected(run, catalog)
    if (directory / "manifest.json").is_symlink():
        raise ValueError("manifest must be a regular file")
    manifest = bounded_json(directory / "manifest.json")
    if any(manifest[name] != value for name, value in (
            ("run", run.run), ("producingAttempt", run.producing_attempt),
            ("revision", run.revision), ("artifact", run.artifact))):
        raise ValueError("producer or artifact identity mismatch")
    listing = manifest["listing"]
    if not isinstance(listing, list) or not all(isinstance(v, dict) and
            set(v) == {"benchmark", "arm"} and all(isinstance(s, str) for s in v.values())
            for v in listing):
        raise ValueError("invalid candidate listing")
    entries = manifest["files"]
    if not isinstance(entries, list) or not all(isinstance(e, dict) for e in entries):
        raise ValueError("invalid manifest entries")
    names = [e["name"] for e in entries]
    if len(set(names)) != len(names) or set(names) != set(files):
        raise ValueError("duplicate or missing manifest files")
    actual = set()
    for path in directory.iterdir():
        actual.add(path.name)
        if len(actual) > MAX_FILES + 1:
            raise ValueError("artifact inventory limit exceeded")
    if actual != set(files) | {"manifest.json"}:
        raise ValueError("missing or unexpected artifact files")
    records = {}
    for entry in entries:
        name = entry["name"]
        path = directory / name
        if path.is_symlink() or not path.is_file():
            raise ValueError("artifact must be a regular file")
        if path.stat().st_size > MAX_FILE_BYTES:
            raise ValueError("input file size limit exceeded")
        if hashlib.sha256(path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError("artifact digest mismatch")
        arm, repetition, measurement_pass = files[name]
        if entry["execution"] not in ("ok", "unavailable"):
            raise ValueError("execution failed")
        rows = bounded_json(path)
        if (not isinstance(rows, list) or len(rows) > MAX_RECORDS
                or (entry["execution"] == "unavailable" and rows)):
            raise ValueError("invalid raw JMH evidence")
        metadata = entry["metadata"]
        if not isinstance(metadata, dict) or metadata.get("revision") != run.revision:
            raise ValueError("result revision mismatch")
        seen = set()
        for row in rows:
            if not isinstance(row["params"], dict) or len(row["params"]) > MAX_PARAMS:
                raise ValueError("parameter count limit exceeded")
            row_identity(row)
            # SetupMetadata supplies a distinct expectedCount for each row size.
            row_metadata = dict(metadata, setup=metadata["setup"][row["params"]["rows"]])
            if (row["benchmark"] != arm.benchmark or
                    row["params"].get(SCENARIO) != arm.arm):
                raise ValueError("result does not match catalog arm")
            key = identity(row, row_metadata, arm.arm, repetition, measurement_pass, run)
            if key in records:
                raise ValueError("duplicate result identity")
            records[key] = (row, row_metadata, entry)
            seen.add(tuple(sorted((k, row["params"][k]) for k in ("rows", SCENARIO))))
        wanted = {tuple(sorted({"rows": size, SCENARIO: arm.arm}.items())) for size in arm.rows}
        if entry["execution"] == "ok" and seen != wanted:
            raise ValueError("missing or unexpected result parameters")
    return manifest, records


def decision(left, right, budget):
    a, ma, _ = left
    b, mb, _ = right
    a, b = dict(a, setup=ma.get("setup")), dict(b, setup=mb.get("setup"))
    if (any((r.get("setup") or {}).get("scenario") != r["params"][SCENARIO]
            for r in (a, b)) or not compatible(ma, mb, a, b, scenario_pair=True) or
            ma.get("softwareVersions") != mb.get("softwareVersions")):
        return "inconclusive: incompatible setup"
    bounds = interval(a), interval(b)
    if None in bounds:
        return "inconclusive: invalid uncertainty or relative error above 10%"
    if any(r["mode"] != "thrpt" or r["primaryMetric"]["scoreUnit"] != "ops/s"
           or r["forks"] < 3 for r in (a, b)):
        return "inconclusive: unsupported measurement configuration"
    x, y = bounds
    if max(x[0], y[0]) <= min(x[1], y[1]):
        return "inconclusive: overlapping intervals"
    # Fractions retain the exact approved boundary without floating-point cancellation.
    loss = 100 * (1 - Fraction(str(b["primaryMetric"]["score"])) /
                  Fraction(str(a["primaryMetric"]["score"])))
    return "budget breach" if loss > Fraction(str(budget)) else "within budget"


def compare(directory, run, validation_attempt, *, _catalog=None):
    # This injection seam is Python-test-only. Neither CLI nor candidate JSON selects it.
    catalog = CATALOG if _catalog is None else _catalog
    lines = ReportLines([MARKER, "# Telemetry on-against-off comparison", "",
                         "All input values below are candidate-reported.",
                         "Inconclusive is not a pass. Rerun with complete, compatible evidence.",
                         "A point-estimate breach does not prove an interval exceeds the budget.",
                         "- Producing attempt: " + safe(run.producing_attempt),
                         "- Artifact identity: " + safe(run.artifact),
                         "- Validation attempt: " + safe(validation_attempt)])
    if not any(a.arm != "off" for a in catalog):
        lines.append("not available: trusted catalog has no on arm")
        return 2, "\n".join(lines) + "\n"
    manifest, records = load(directory, run, catalog)
    # Index full normalized identities without repetition. Keep passes isolated.
    # Include off-only dimensions so an absent on series cannot disappear.
    for arm in catalog:
        if arm.arm == "off":
            continue
        groups = {}
        for key, record in records.items():
            row, metadata, _ = record
            if row["benchmark"] != arm.benchmark or key[3] not in ("off", arm.arm):
                continue
            normalized = identity(row, metadata, arm.arm, key[4], key[5], run, partner=True)
            group = normalized[:4] + normalized[5:]
            series = groups.setdefault(group, {})
            series[(key[3], key[4])] = record
        # A wholly unavailable size/pass has no observed dimensions to index.
        for size in arm.rows:
            for measurement_pass in range(1, run.passes + 1):
                if not any(group[4] == measurement_pass and
                           dict(group[0][1]).get("rows") == size
                           for group in groups if len(group) == 7):
                    groups[("unavailable", arm.benchmark, size, measurement_pass)] = {}
        for group, series in groups.items():
            lines.append("## Candidate-reported comparison: " + safe((arm.arm, group)))
            lines.append("- Trusted budget: " + safe(arm.budget) +
                         (" approved" if arm.approved else " unapproved"))
            outcomes = []
            for repetition in range(1, run.repetitions + 1):
                listed = {"benchmark": arm.benchmark, "arm": arm.arm} in manifest["listing"]
                off = next((a for a in catalog if a.benchmark == arm.benchmark
                            and a.arm == "off"), None)
                right = series.get((arm.arm, repetition))
                left = None
                if right:
                    partner = identity(right[0], right[1], arm.arm, repetition,
                                       group[4], run, partner=True)
                    left = records.get(partner)
                if not listed:
                    outcome = "inconclusive: catalog arm missing from candidate listing"
                elif off is None:
                    outcome = "inconclusive: catalog arm has no matching off partner"
                elif {"benchmark": arm.benchmark, "arm": "off"} not in manifest["listing"]:
                    outcome = "inconclusive: off arm missing from candidate listing"
                elif not finite(arm.budget) or arm.budget < 0 or not arm.approved:
                    outcome = "inconclusive: proposed budget is unapproved"
                else:
                    outcome = (decision(left, right, arm.budget) if left and right else
                               "inconclusive: missing pair or incompatible setup")
                outcomes.append(outcome)
                lines.append(f"- Repetition {repetition}: " + outcome)
            combined = (outcomes[0] if len(set(outcomes)) == 1
                        else "inconclusive: repetitions disagree")
            lines.append("- Combined: " + combined)
            if combined.startswith("inconclusive"):
                lines.append("Rerun both arms in a new pass with complete, compatible evidence.")
    # Evidence may be truncated, but decisions must fit completely or fail visibly.
    if lines.truncated:
        return 2, MARKER + "\nFatal: comparison outcomes exceed report capacity.\n"
    lines.append("- Candidate-reported manifest: " + safe(manifest))
    for key, (row, metadata, entry) in records.items():
        lines.append("- Candidate-reported identity: " + safe(key))
        for field in ("benchmark", "mode", "threads", "forks", "primaryMetric"):
            lines.append("- Candidate-reported " + field + ": " + safe(row[field]))
        for field, value in row["params"].items():
            lines.append("- Candidate-reported parameter " + safe(field) + ": " + safe(value))
        for field, value in metadata.items():
            lines.append("- Candidate-reported metadata " + safe(field) + ": " + safe(value))
        if key[3] != "off":
            partner = identity(row, metadata, key[3], key[4], key[5], run, partner=True)
            left = records.get(partner)
            if left and all(finite(r["primaryMetric"].get("score")) and
                            r["primaryMetric"]["score"] > 0 for r in (left[0], row)):
                loss = 100 * (1 - Fraction(str(row["primaryMetric"]["score"])) /
                              Fraction(str(left[0]["primaryMetric"]["score"])))
                lines.append("- Diagnostic loss percent, not a budget decision: " + safe(str(loss)))
    report = "\n".join(lines) + "\n"
    if lines.truncated:
        report += "Report truncated. Read the raw JSON for complete evidence.\n"
    return 0, report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("directory", "run", "producing-attempt", "revision", "artifact",
                 "validation-attempt", "output"):
        parser.add_argument("--" + name, required=True)
    for name in ("repetitions", "passes"):
        parser.add_argument("--" + name, type=int, required=True)
    args = parser.parse_args(argv)
    try:
        run = Run(args.run, args.producing_attempt, args.revision, args.repetitions,
                  args.passes, args.artifact)
        status, report = compare(args.directory, run, args.validation_attempt)
        with open(args.output, "x") as output:
            output.write(report)
        return status
    except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError,
            RecursionError) as error:
        print("fatal: " + safe(str(error)), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
