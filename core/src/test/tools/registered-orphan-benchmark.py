#!/usr/bin/env python3
"""Alternating physical-reopen benchmark. See registered-orphan-benchmark.md."""
import argparse
import json
import os
from pathlib import Path
import random
import re
import shutil
import statistics
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--baseline", type=Path, required=True, help="bootstrap checkout")
parser.add_argument("--current", type=Path, required=True, help="Track 8 checkout")
parser.add_argument("--baseline-cp", type=Path, required=True, help="absolute classpath file")
parser.add_argument("--current-cp", type=Path, required=True, help="absolute classpath file")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--files", type=int, default=10000)
parser.add_argument("--samples", type=int, default=30)
parser.add_argument("--warmup", type=int, default=3)
parser.add_argument("--smoke", action="store_true")
parser.add_argument("--disk-test-command", help="same targeted disk-mode command in both checkouts")
args = parser.parse_args()
if args.output.exists():
    parser.error("choose a new output path")
if not args.smoke and (args.files < 10000 or args.samples < 30 or not args.disk_test_command):
    parser.error("full runs require >=10000 files, >=30 samples and --disk-test-command")
source = Path(__file__).resolve().parents[1] / (
    "java/com/jetbrains/youtrackdb/internal/core/storage/impl/local/"
    "RegisteredOrphanOpenBenchmark.java")
main = "com.jetbrains.youtrackdb.internal.core.storage.impl.local.RegisteredOrphanOpenBenchmark"
flags = ["-ea", "-Xms1g", "-Xmx4g", "-Dyoutrackdb.storage.diskCache.bufferSize=256"]
# Keep module access equal across builds, without the unit suite's fsync/checkpoint overrides.
pom = (args.current / "core/pom.xml").read_text()
flags += re.findall(r"--add-(?:opens|exports)(?:=|\s+)[\w./=-]+", pom)
flags = [part for flag in flags for part in flag.split()]
records = []
result = {"smoke": args.smoke, "files_requested": args.files,
          "samples": args.samples, "warmup_per_build_per_scenario_per_lane": args.warmup,
          "os_cache": "copy-warmed/uncontrolled, fresh JVM per open, no OS cache drop",
          "fsync": True, "jvm_flags": flags, "records": records}
rng = random.Random(889)
with tempfile.TemporaryDirectory(prefix="ytdb-owner-open-") as scratch:
    scratch = Path(scratch)
    builds = {}
    for label in ("baseline", "current"):
        checkout = getattr(args, label).resolve()
        cp = getattr(args, label + "_cp").read_text().strip()
        out = scratch / label
        out.mkdir()
        subprocess.run(["javac", "-cp", cp, "-d", str(out), str(source)], check=True)
        builds[label] = (checkout, str(out) + os.pathsep + cp)
        result[label + "_head"] = subprocess.check_output(
            ["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
        if label == "baseline" and not args.smoke and not result["baseline_head"].startswith("6d8762ba90"):
            raise RuntimeError("baseline must be bootstrap 6d8762ba90")

    def probe(label, action, fixture, scenario, lane):
        checkout, cp = builds[label]
        log = scratch / "process.log"
        with log.open("w") as output:
            subprocess.run(["java", *flags, "-cp", cp, main, action, str(fixture),
                            str(args.files), scenario, lane], cwd=checkout, stdout=output,
                           stderr=subprocess.STDOUT, check=True)
        found = re.findall(r"BENCH (.*)", log.read_text())
        if not found:
            raise RuntimeError("probe produced no result: " + log.read_text()[-4000:])
        values = dict(part.split("=", 1) for row in found for part in row.split())
        return {key: int(value) if value.lstrip("-").isdigit() else value
                for key, value in values.items()}

    for scenario, lane in [(s, lane) for s in ("clean", "crash", "populated", "upgrade")
                           for lane in ("no-orphan", "sentinel", "bulk")]:
        templates = {}
        for label in ("baseline", "current"):
            template = scratch / ("template-" + label)
            # First open after upgrade always consumes a bootstrap-generated image.
            creator = "baseline" if scenario == "upgrade" else label
            start = time.perf_counter()
            preparation = probe(creator, "prepare", template,
                                "clean" if scenario == "upgrade" else scenario, lane)
            preparation["prepare_seconds"] = time.perf_counter() - start
            # Samples never open a template. copyfile creates writable, independent files.
            for path in template.rglob("*"):
                if path.is_file():
                    path.chmod(0o444)
            templates[label] = (template, preparation)
        for index in range(-args.warmup, args.samples):
            order = ("baseline", "current") if index % 2 == 0 else ("current", "baseline")
            for label in order:
                fixture = scratch / "fixture"
                if fixture.exists():
                    shutil.rmtree(fixture)
                template, preparation = templates[label]
                start = time.perf_counter()
                shutil.copytree(template, fixture, copy_function=shutil.copyfile)
                copy_seconds = time.perf_counter() - start
                measurement = probe(label, "sample", fixture, scenario, lane)
                if (label == "current" or args.smoke) and measurement["pass_verified"] != "true":
                    raise RuntimeError(label + " build has no verified owner pass")
                if index >= 0:
                    records.append(dict(scenario=scenario, lane=lane, sample=index, build=label,
                                        template_id=f"{scenario}/{lane}/{label}",
                                        copy_seconds=copy_seconds, **preparation, **measurement))
        for template, _ in templates.values():
            shutil.rmtree(template)
    if args.disk_test_command:
        result["disk_test_seconds"] = {}
        for label in ("baseline", "current"):
            start = time.perf_counter()
            subprocess.run(args.disk_test_command, shell=True, cwd=builds[label][0], check=True)
            result["disk_test_seconds"][label] = time.perf_counter() - start

def ratio_interval(base, current):
    estimates = []
    for _ in range(10000):
        indices = rng.choices(range(len(base)), k=len(base))
        estimates.append(statistics.median([current[i] for i in indices])
                         / statistics.median([base[i] for i in indices]))
    estimates.sort()
    return [estimates[249], estimates[9749]]

result["comparisons"] = {}
for scenario in ("clean", "crash", "populated", "upgrade"):
    base = [r["open_ns"] for r in records if r["scenario"] == scenario and r["lane"] == "no-orphan" and r["build"] == "baseline"]
    current = [r["open_ns"] for r in records if r["scenario"] == scenario and r["lane"] == "no-orphan" and r["build"] == "current"]
    interval = ratio_interval(base, current)
    result["comparisons"][scenario] = {
        "median_ratio": statistics.median(current) / statistics.median(base),
        "paired_bootstrap_95_percent_ci": interval,
        "accepted": not args.smoke and interval[1] <= 1.05}
args.output.write_text(json.dumps(result, indent=2) + "\n")
print(json.dumps(result["comparisons"], indent=2))
