"""Local off references. This module also owns the untrusted-data renderer."""
import argparse
from fractions import Fraction
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile
import xml.etree.ElementTree as ET

PREFIX = "com.jetbrains.youtrackdb.benchmarks.telemetry.OffBenchmark."
HARDWARE = ("processors", "processorModel", "memory", "cpuQuota", "memoryLimit")
JMH = ("jmhVersion", "jdkVersion", "vmVersion", "vmName", "jvm", "jvmArgs",
       "warmupIterations", "warmupTime", "warmupBatchSize", "measurementIterations",
       "measurementTime", "measurementBatchSize")
SETUP = ("query", "data", "expectedCount", "path", "scenario", "transaction", "cache", "fixtureCode")


def read_json(path):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=unique)


def safe(value):
    # Encode punctuation too. No candidate byte can become Markdown or HTML syntax.
    full = json.dumps(value, ensure_ascii=True)
    raw = full if len(full) <= 160 else full[:130] + "... [sha256:" + hashlib.sha256(
        full.encode()).hexdigest()[:16] + "]"
    # Code spans suppress GitHub mentions, references and commit autolinks.
    return "`" + "".join(c if c.isascii() and c.isalnum() else f"&#{ord(c)};"
                         for c in raw) + "`"


def evidence(path, missing=None, empty=None):
    try:
        return Path(path).read_text().strip() or empty
    except FileNotFoundError:
        return missing
    except OSError:
        return None


def capture():
    cpu = evidence("/proc/cpuinfo")
    model = next((line.split(":", 1)[1].strip() for line in (cpu or "").splitlines()
                  if line.startswith("model name")), None)
    memory = evidence("/proc/meminfo")
    total = next((line for line in (memory or "").splitlines() if line.startswith("MemTotal:")), None)
    return dict(processors=sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity")
                else None, processorModel=model, memory=total,
                cpuQuota=cgroup_limit("cpu"), memoryLimit=cgroup_limit("memory"))


def v2_limit(current, root, controller):
    name, unlimited = ("cpu.max", "max 100000") if controller == "cpu" else ("memory.max", "max")
    value = evidence(current / name, missing=False)
    if value is not False:
        return value
    # The root has no limit interfaces. Disabled controllers have none at this level.
    # Both cases still require checking ancestors for an inherited limit.
    if current == root:
        return unlimited
    controllers = evidence(current / "cgroup.controllers", empty="")
    if controllers is not None and controller not in controllers.split():
        return unlimited
    return None


def cgroup_limit(controller):
    """Resolve the current cgroup and all visible ancestors, on v1 or v2."""
    groups, mounts = evidence("/proc/self/cgroup"), evidence("/proc/self/mountinfo")
    if groups is None or mounts is None:
        return None
    try:
        for group in groups.splitlines():
            _, controllers, location = group.split(":", 2)
            v2 = controllers == ""
            if not v2 and controller not in controllers.split(","):
                continue
            for mount in mounts.splitlines():
                before, after = mount.split(" - ", 1)
                fields, fs = before.split(), after.split()
                if fs[0] != ("cgroup2" if v2 else "cgroup"):
                    continue
                if not v2 and controller not in fs[2].split(","):
                    continue
                # A non-root mount can hide stricter ancestors. Do not invent evidence.
                if fields[3] != "/" or "\\" in fields[4] or ".." in location.split("/"):
                    return None
                root = Path(fields[4])
                current = root / location.lstrip("/")
                limits = []
                while True:
                    if controller == "cpu":
                        if v2:
                            quota, period = v2_limit(current, root, controller).split()
                        else:
                            quota = evidence(current / "cpu.cfs_quota_us")
                            period = evidence(current / "cpu.cfs_period_us")
                        if quota not in ("max", "-1"):
                            value = Fraction(int(quota), int(period))
                            if value <= 0:
                                return None
                            limits.append(value)
                    else:
                        if not v2 and evidence(current / "memory.use_hierarchy") != "1":
                            return None
                        value = (v2_limit(current, root, controller) if v2 else
                                 evidence(current / "memory.limit_in_bytes"))
                        if value is None:
                            return None
                        page = os.sysconf("SC_PAGE_SIZE")
                        unlimited = ((1 << 63) - 1) // page * page
                        if value != "max" and (v2 or int(value) != unlimited):
                            limits.append(Fraction(int(value)))
                    if current == root:
                        return str(min(limits)) if limits else "unlimited"
                    current = current.parent
            return None
        # A controller not attached to this process imposes no cgroup limit.
        return "unlimited"
    except (AttributeError, ValueError, TypeError, ZeroDivisionError, IndexError):
        return None


def run(args):
    # Refuse before executing Java, and still use exclusive creation against races.
    if Path(args.output).exists() or Path(args.output).is_symlink():
        raise ValueError("output already exists")
    hardware = capture()
    checkout = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if subprocess.check_output(["git", "status", "--porcelain"], text=True).strip():
        raise ValueError("run requires a clean revision")
    if getattr(args, "record_absence", False):
        with open(args.output, "x") as output:
            json.dump(dict(results=[], revision=None, checkoutRevision=checkout,
                           absence="operator-recorded absence of OffBenchmark fixture",
                           hardware=hardware, harness=args.harness,
                           machineType=args.machine_type), output, indent=2)
        return
    if not all(present(hardware.get(name)) for name in HARDWARE):
        raise ValueError("incomplete hardware evidence")
    jar_index = args.command.index("-jar")
    jar = args.command[jar_index + 1]
    class_path = PREFIX.replace(".", "/").rsplit("/", 1)[0]
    with zipfile.ZipFile(jar) as archive:
        versions = {name: archive.read(name).decode("utf-8", errors="replace")
                    for name in archive.namelist() if name.endswith("/pom.properties")}
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        revision = next((line.split(": ", 1)[1] for line in manifest.splitlines()
                         if line.startswith("Implementation-Build: ")), None)
        has_fixture = class_path + ".class" in archive.namelist()
        has_metadata = class_path.rsplit("/", 1)[0] + "/SetupMetadata.class" in archive.namelist()
        # Hash the actual fixture bytecode as an additional workload-identity guard.
        code = hashlib.sha256(b"".join(archive.read(n) for n in sorted(archive.namelist())
                                      if (n.startswith(class_path) or n.endswith("/SetupMetadata.class"))
                                      and n.endswith(".class"))).hexdigest()
    result = dict(revision=revision, checkoutRevision=checkout,
                  revisionMatchesCheckout=bool(revision) and revision == checkout,
                  harness=args.harness, machineType=args.machine_type,
                  hardware=hardware, softwareVersions=versions, results=[])
    with tempfile.TemporaryDirectory(prefix="telemetry-", dir=args.scratch) as temporary:
        raw, setup = Path(temporary) / "jmh.json", Path(temporary) / "setup.xml"
        listing = subprocess.run(args.command + ["-l"], check=True, capture_output=True,
                                 text=True).stdout if has_fixture else ""
        if not any(line.startswith(PREFIX) for line in listing.splitlines()):
            result["absence"] = "no matching OffBenchmark fixture"
        else:
            metadata = {}
            if has_metadata:
                subprocess.run(args.command[:jar_index] + ["-cp", jar,
                               PREFIX.rsplit(".", 2)[0] + ".SetupMetadata", str(setup)], check=True)
                metadata = {entry.attrib["key"]: entry.text
                            for entry in ET.parse(setup).getroot().findall("entry")}
            subprocess.run(args.command + ["-rf", "json", "-rff", str(raw), "-foe", "true"],
                           check=True)
            rows = read_json(raw)
            validate_rows(rows, require_nonempty=True)
            for row in rows:
                path = row["benchmark"].removeprefix(PREFIX)
                row["setup"] = ({name: metadata.get(path + "." + name) for name in SETUP}
                                if metadata and row["params"].get("telemetryScenario") == "off"
                                else None)
                if row["setup"] is not None:
                    row["setup"]["expectedCount"] = metadata.get("count." + row["params"].get("rows", ""))
                    row["setup"]["fixtureCode"] = code
            result["results"] = rows
        if result.get("absence") or not result["results"] or not all(
                compatible(result, result, row, row) for row in result["results"]):
            raise ValueError("capture lacks a matching fixture or complete setup evidence")
        with open(args.output, "x") as output:
            json.dump(result, output, indent=2)


def validate_rows(rows, require_nonempty=False):
    if not isinstance(rows, list) or (require_nonempty and not rows):
        raise ValueError("expected non-empty JMH result list")
    result = {}
    for row in rows:
        key = identity(row)
        if key in result:
            raise ValueError("duplicate result identity")
        result[key] = row
    return result


def identity(row, *, scenario=None):
    params = row["params"]
    if not isinstance(params, dict) or not all(isinstance(k, str) and isinstance(v, str)
                                               for k, v in params.items()):
        raise ValueError("params must map strings to strings")
    if not isinstance(row["benchmark"], str) or not isinstance(row["mode"], str):
        raise ValueError("benchmark and mode must be strings")
    for key in ("threads", "forks"):
        if type(row[key]) is not int or row[key] < 1:
            raise ValueError("threads and forks must be positive integers")
    metric = row["primaryMetric"]
    if not isinstance(metric, dict) or not isinstance(metric["scoreUnit"], str):
        raise ValueError("invalid primary metric")
    params = dict(params)
    if scenario is not None:
        params["telemetryScenario"] = scenario
    return (row["benchmark"], tuple(sorted(params.items())), row["mode"], row["threads"],
            row["forks"], metric["scoreUnit"])


def load(path):
    document = read_json(path)
    if not isinstance(document, dict) or not isinstance(document["results"], list):
        raise ValueError("expected a result envelope")
    return document, validate_rows(document["results"])


def present(value):
    if isinstance(value, dict):
        return bool(value) and all(present(k) and present(v) for k, v in value.items())
    if isinstance(value, list):
        return bool(value) and all(present(v) for v in value)
    return value is not None and value != ""


def compatible(base, candidate, left, right, *, scenario_pair=False):
    # Validate stored identities rather than trusting a serialized match flag.
    if not all(isinstance(doc.get("revision"), str) and present(doc["revision"])
               and doc["revision"] == doc.get("checkoutRevision")
               and doc.get("revisionMatchesCheckout") is True for doc in (base, candidate)):
        return False
    for name in ("revision", "harness", "machineType", "softwareVersions"):
        if not present(base.get(name)) or not present(candidate.get(name)):
            return False
    if (base["harness"], base["machineType"]) != (candidate["harness"], candidate["machineType"]):
        return False
    for name in HARDWARE:
        a, b = (base.get("hardware") or {}).get(name), (candidate.get("hardware") or {}).get(name)
        if not present(a) or not present(b) or a != b:
            return False
    for name in JMH:
        if (not ((isinstance(left.get(name), list) and all(
                isinstance(v, str) and present(v) for v in left[name]))
                if name == "jvmArgs" else present(left.get(name)))
                or left.get(name) != right.get(name)):
            return False
    for name in SETUP:
        if scenario_pair and name == "scenario":
            continue
        a, b = (left.get("setup") or {}).get(name), (right.get("setup") or {}).get(name)
        if not present(a) or not present(b) or a != b:
            return False
    return all(present(r["primaryMetric"].get("scoreUnit")) for r in (left, right))


def finite(value):
    try:
        return type(value) in (float, int) and math.isfinite(value)
    except OverflowError:
        return False


def interval(row):
    metric = row["primaryMetric"]
    score, error, bounds = (metric.get(k) for k in ("score", "scoreError", "scoreConfidence"))
    if not finite(score) or score <= 0 or not finite(error) or error < 0:
        return None
    if not isinstance(bounds, list) or len(bounds) != 2 or not all(map(finite, bounds)):
        return None
    low, high = bounds
    # Compare deviations to the uncertainty, not to the much larger score.
    if any(abs(actual - expected) > error * 1e-7
           for actual, expected in ((low, score - error), (high, score + error))):
        return None
    if low > score or high < score or error / score > .10:
        return None
    # Use the reported half-width so rounding cannot narrow the overlap check.
    return score - error, score + error


def verdict(base, candidate, left, right):
    if left is None or right is None:
        return "inconclusive: missing pair"
    if not compatible(base, candidate, left, right):
        return "inconclusive: missing metadata or incompatible setup"
    a, b = interval(left), interval(right)
    if a is None or b is None:
        return "inconclusive: invalid uncertainty or excessive noise"
    if any(r["mode"] != "thrpt" or r["threads"] != 1 or r["forks"] < 3
           for r in (left, right)):
        return "inconclusive: unsupported measurement configuration"
    if max(a[0], b[0]) <= min(a[1], b[1]):
        return "inconclusive: overlapping intervals"
    breach = Fraction(str(right["primaryMetric"]["score"])) * 100 < (
        Fraction(str(left["primaryMetric"]["score"])) * 99)
    return "budget breach" if breach else "within budget"


class ReportLines(list):
    """Bound memory and preserve complete code spans at the report boundary."""

    def __init__(self, values):
        super().__init__()
        self.size = 0
        self.truncated = False
        for value in values:
            self.append(value)

    def append(self, value):
        if self.truncated or self.size + len(value) + 1 > 59000:
            self.truncated = True
            return
        self.size += len(value) + 1
        super().append(value)


def compare(base_paths, candidate_paths):
    bases, candidates = list(map(load, base_paths)), list(map(load, candidate_paths))
    mixed = any(len({doc.get("revision") for doc, _ in side}) != 1
                for side in (bases, candidates))
    keys = set().union(*(set(rows) for _, rows in bases + candidates))
    lines = ReportLines(["# Local off comparison", "",
                         "All input is candidate-reported, including base metadata.",
                         "Inconclusive is not a pass. Rerun with complete, compatible evidence.", ""])
    for label, side in (("base", bases), ("candidate", candidates)):
        for index, (doc, _) in enumerate(side):
            if doc.get("absence"):
                lines.append(f"- {label}, repetition {index + 1}, candidate-reported absence: "
                             + safe(doc["absence"]))
    if not keys:
        lines.append("inconclusive: no matching fixtures")
    for key_index, key in enumerate(sorted(keys)):
        if key_index == 100:
            lines.append("Fixture list truncated. Read the JSON for complete evidence.")
            break
        outcomes = []
        lines.append("## " + safe(key))
        for index in range(max(len(bases), len(candidates))):
            if index >= len(bases) or index >= len(candidates):
                outcomes.append("inconclusive: missing repetition")
                continue
            (base, a), (candidate, b) = bases[index], candidates[index]
            left, right = a.get(key), b.get(key)
            outcomes.append("inconclusive: mixed revisions" if mixed else
                            verdict(base, candidate, left, right))
            for label, doc, row in (("base", base, left), ("candidate", candidate, right)):
                lines.append(f"- {label}, repetition {index + 1}, candidate-reported:")
                for name in ("revision", "harness", "machineType"):
                    lines.append("  - " + name + ": " + safe(doc.get(name)))
                for name in HARDWARE:
                    lines.append("  - hardware " + name + ": "
                                 + safe((doc.get("hardware") or {}).get(name)))
                # Fixed field names only. Nested keys and values share the same escaping boundary.
                for entry_index, (name, value) in enumerate((doc.get("softwareVersions") or {}).items()):
                    if entry_index == 32:
                        lines.append("  - Software list truncated. Read the JSON for complete evidence.")
                        break
                    lines.append("  - software " + safe(name) + ": " + safe(value))
                for name in ("benchmark", "params", "mode", "threads", "forks", *JMH):
                    lines.append("  - " + name + ": " + safe((row or {}).get(name)))
                for group, names in (("setup", SETUP), ("primaryMetric",
                                     ("score", "scoreError", "scoreConfidence", "scoreUnit"))):
                    for name in names:
                        lines.append("  - " + group + " " + name + ": "
                                     + safe(((row or {}).get(group) or {}).get(name)))
            lines.append(f"- Repetition {index + 1}: " + outcomes[-1])
        combined = outcomes[0] if len(set(outcomes)) == 1 else "inconclusive: repetitions disagree"
        lines.append("- Combined: " + combined)
    report = "\n".join(lines) + "\n"
    if lines.truncated:
        report += "\nReport truncated. Read the JSON for complete evidence.\n"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="action", required=True)
    runner = commands.add_parser("run")
    for name in ("output", "harness", "machine-type", "scratch"):
        runner.add_argument("--" + name, required=True)
    runner.add_argument("--record-absence", action="store_true",
                        help="record an unavailable fixture without launching Java")
    runner.add_argument("command", nargs=argparse.REMAINDER)
    comparison = commands.add_parser("compare")
    comparison.add_argument("--base", nargs="+", required=True)
    comparison.add_argument("--candidate", nargs="+", required=True)
    args = parser.parse_args()
    try:
        if args.action == "run":
            run(args)
        else:
            print(compare(args.base, args.candidate), end="")
    except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError, RecursionError,
            zipfile.BadZipFile, ET.ParseError,
            subprocess.SubprocessError) as error:
        print("fatal: " + json.dumps(str(error), ensure_ascii=True)[1:-1][:500], file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
