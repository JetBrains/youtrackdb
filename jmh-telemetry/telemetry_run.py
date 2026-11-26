"""Trusted local paired execution. Candidate jars execute with the operator's privileges."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile

import off_compare as oc
import telemetry_compare as tc

VM = ("-Xms2g", "-Xmx2g", "-XX:+UseG1GC",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.cs=ALL-UNNAMED")
PACKAGE = tc.PREFIX.rsplit(".", 2)[0]


def listing(text, catalog):
    """Read presence only. No candidate token becomes a command argument."""
    if len(text.encode()) > tc.MAX_FILE_BYTES:
        raise ValueError("listing size limit exceeded")
    found, current = set(), None
    seen = set()
    for line in text.splitlines():
        if line.startswith(PACKAGE + "."):
            current = line.strip()
            if current in seen:
                raise ValueError("duplicate benchmark listing")
            seen.add(current)
        elif line.startswith("  param "):
            match = re.fullmatch(r'  param "([A-Za-z0-9_]+)" = \{([^{}]*)\}', line)
            if not match or current is None:
                raise ValueError("invalid candidate parameter listing")
            key, values = match.groups()
            values = values.split(", ")
            if key == tc.SCENARIO:
                found.update((current, value) for value in values)
        elif line.strip():
            current = None
    return [dict(benchmark=a.benchmark, arm=a.arm) for a in catalog
            if (a.benchmark, a.arm) in found]


def command(java, jar, scratch):
    return [java, *VM, "-Djava.io.tmpdir=" + str(scratch), "-jar", str(jar)]


def schedule(run, catalog):
    """Every new pass repeats all pairs. Repetition parity controls pair order."""
    for measurement_pass in range(1, run.passes + 1):
        for repetition in range(1, run.repetitions + 1):
            for benchmark in dict.fromkeys(a.benchmark for a in catalog):
                arms = [a for a in catalog if a.benchmark == benchmark]
                arms.sort(key=lambda a: (a.arm != "off", a.arm))
                for arm in arms if repetition % 2 else reversed(arms):
                    yield arm, repetition, measurement_pass


def jar_metadata(jar, revision):
    with zipfile.ZipFile(jar) as archive:
        manifest = archive.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        if "Implementation-Build: " + revision not in manifest.splitlines():
            raise ValueError("jar revision does not match requested revision")
        versions = {n: archive.read(n).decode() for n in archive.namelist()
                    if n.endswith("/pom.properties")}
        code = hashlib.sha256(b"".join(archive.read(n) for n in sorted(archive.namelist())
                                      if n.startswith(PACKAGE.replace(".", "/") + "/")
                                      and n.endswith(".class"))).hexdigest()
    if not versions:
        raise ValueError("missing software versions")
    return versions, code


def validate_measurement(rows, arm, warmup):
    oc.validate_rows(rows, require_nonempty=True)
    for row in rows:
        if (row["benchmark"] != arm.benchmark or
                row["params"] != {"rows": row["params"]["rows"], tc.SCENARIO: arm.arm} or
                row["mode"] != "thrpt" or row["threads"] != 1 or row["forks"] != 3 or
                not row["jdkVersion"].startswith("21") or
                row["warmupIterations"] != warmup or row["warmupTime"] != "1 s" or
                row["measurementIterations"] != 10 or row["measurementTime"] != "1 s" or
                not all(v in row["jvmArgs"] for v in VM)):
            raise ValueError("unexpected measurement configuration")
        raw = row["primaryMetric"]["rawData"]
        if (len(raw) != 3 or any(len(fork) != 10 for fork in raw) or
                not all(oc.finite(v) and v > 0 for fork in raw for v in fork)):
            raise ValueError("missing or failed fork")


def execute(args, *, _catalog=None):
    catalog = tc.CATALOG if _catalog is None else _catalog
    if not any(a.arm != "off" for a in catalog):
        raise ValueError("not available: trusted catalog has no on arm")
    run = tc.Run(args.run, args.producing_attempt, args.revision,
                 args.repetitions, len(args.warmup), args.artifact)
    if run.repetitions < 2:
        raise ValueError("at least two repetitions required for alternating arm order")
    inventory = tc.expected(run, catalog)
    if any(n < 5 for n in args.warmup) or any(
            b <= a for a, b in zip(args.warmup, args.warmup[1:])):
        raise ValueError("each stabilization pass must lengthen warmup from at least five")
    for arm in catalog:
        if (not re.fullmatch(r"[A-Za-z0-9_]+", arm.arm) or
                not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_.]*", arm.benchmark) or
                not arm.rows or any(not re.fullmatch(r"[1-9][0-9]*", s) for s in arm.rows)):
            raise ValueError("invalid trusted catalog")
    output = Path(args.directory).absolute()
    if output.exists() or output.is_symlink():
        raise FileExistsError("output already exists")
    jar, scratch = (Path(p).resolve() for p in (args.jar, args.scratch))
    if not jar.is_file() or not scratch.is_dir() or "target" in scratch.parts:
        raise ValueError("jar required and scratch must exist outside target")
    java = shutil.which(args.java)
    if java is None:
        raise ValueError("Java executable not found")
    java = str(Path(java).resolve())
    checkout_dir = jar.parent
    checkout = subprocess.check_output(["git", "rev-parse", "HEAD"],
                                       cwd=checkout_dir, text=True).strip()
    if subprocess.check_output(["git", "status", "--porcelain"],
                               cwd=checkout_dir, text=True).strip():
        raise ValueError("run requires a clean revision")
    if checkout != run.revision:
        raise ValueError("checkout revision does not match requested revision")
    hardware = oc.capture()
    if not all(oc.present(hardware.get(k)) for k in oc.HARDWARE):
        raise ValueError("incomplete hardware evidence")

    def launch(argv):
        try:
            return subprocess.run(argv, cwd=checkout_dir, check=True, text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT).stdout
        except subprocess.CalledProcessError as error:
            # JSON escaping keeps candidate diagnostics readable without terminal controls.
            print("launch output: " + json.dumps(error.output or "", ensure_ascii=True),
                  file=sys.stderr)
            raise

    # Snapshot once so a rebuild cannot change the measured jar between arms.
    with tempfile.TemporaryDirectory(prefix="telemetry-pair-", dir=scratch) as temporary:
        temporary = Path(temporary)
        snapshot = temporary / "candidate.jar"
        shutil.copyfile(jar, snapshot)
        jar = snapshot
        versions, code = jar_metadata(jar, run.revision)
        base = command(java, jar, scratch)
        version = launch([java, "-version"])
        if not re.search(r'version "21[."]', version):
            raise ValueError("JDK 21 required")
        present = listing(launch(base + ["-lp"]), catalog)
        if not present:
            raise ValueError("missing candidate listing")
        # Reserve evidence only after preflight. mkdir refuses existing entries, including symlinks.
        output.mkdir()
        manifest = dict(run=run.run, producingAttempt=run.producing_attempt, revision=run.revision,
                        artifact=run.artifact, listing=present, files=[])
        setup_file = temporary / "setup.xml"
        launch([*base[:base.index("-jar")], "-cp", str(jar),
                PACKAGE + ".SetupMetadata", str(setup_file)])
        if setup_file.stat().st_size > tc.MAX_FILE_BYTES:
            raise ValueError("setup size limit exceeded")
        setup = {e.attrib["key"]: e.text for e in ET.parse(setup_file).getroot().findall("entry")}
        names = {value: name for name, value in inventory.items()}
        for arm, repetition, measurement_pass in schedule(run, catalog):
            name = names[(arm, repetition, measurement_pass)]
            raw = output / name
            available = dict(benchmark=arm.benchmark, arm=arm.arm) in present
            if available:
                # The exact trusted benchmark regex prevents broader candidate selection.
                with raw.open("x"):
                    pass
                launch(base + ["^" + re.escape(arm.benchmark) + "$",
                               "-p", tc.SCENARIO + "=" + arm.arm,
                               "-p", "rows=" + ",".join(arm.rows),
                               "-f", "3", "-t", "1", "-bm", "thrpt", "-tu", "s",
                               "-wi", str(args.warmup[measurement_pass - 1]), "-w", "1s",
                               "-i", "10", "-r", "1s", "-rf", "json", "-rff", str(raw),
                               "-foe", "true"])
                rows = tc.bounded_json(raw)
                validate_measurement(rows, arm, args.warmup[measurement_pass - 1])
            else:
                with raw.open("x") as stream:
                    json.dump([], stream)
                rows = []
            path = arm.benchmark.rsplit(".", 1)[-1]
            # An on fixture must supply its own scenario metadata. Do not invent it.
            prefix = path + "." + arm.arm if path + "." + arm.arm + ".scenario" in setup else path
            metadata = dict(revision=run.revision, checkoutRevision=checkout,
                            revisionMatchesCheckout=checkout == run.revision, harness=args.harness,
                            machineType=args.machine_type, hardware=hardware,
                            softwareVersions=versions, setup={})
            for size in arm.rows:
                values = {k: setup.get(prefix + "." + k) for k in oc.SETUP}
                values.update(expectedCount=setup.get("count." + size), fixtureCode=code)
                metadata["setup"][size] = values
            if rows and (any(v["scenario"] != arm.arm for v in metadata["setup"].values()) or
                         any(not oc.compatible(metadata, metadata,
                                 dict(r, setup=metadata["setup"][r["params"]["rows"]]),
                                 dict(r, setup=metadata["setup"][r["params"]["rows"]]))
                             for r in rows)):
                raise ValueError("incomplete setup evidence")
            manifest["files"].append(dict(name=name, execution="ok" if available else "unavailable",
                                          sha256=hashlib.sha256(raw.read_bytes()).hexdigest(),
                                          metadata=metadata))
        stage = temporary / "validation"
        stage.mkdir()
        for name in inventory:
            shutil.copyfile(output / name, stage / name)
        with (stage / "manifest.json").open("x") as stream:
            json.dump(manifest, stream, indent=2)
        tc.load(stage, run, catalog)
        with (output / "manifest.json").open("x") as stream:
            json.dump(manifest, stream, indent=2)
    if len(present) != len(catalog):
        raise ValueError("not available: catalog arm missing from candidate listing")
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("jar", "scratch", "directory", "run", "producing-attempt", "revision",
                 "artifact", "harness", "machine-type"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--repetitions", type=int, default=2)
    parser.add_argument("--warmup", type=int, nargs="+", default=[5],
                        help="warmup iterations for each pass, increasing from at least five")
    args = parser.parse_args(argv)
    try:
        return execute(args)
    except (OSError, ValueError, KeyError, TypeError, IndexError, AttributeError,
            RecursionError, ET.ParseError, zipfile.BadZipFile, subprocess.SubprocessError) as error:
        print("fatal: " + json.dumps(str(error), ensure_ascii=True), file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        print("fatal: interrupted, evidence is incomplete", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
