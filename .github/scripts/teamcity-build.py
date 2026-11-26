#!/usr/bin/env python3
"""Connect TeamCity branch detection, test reports and finished-build baselines."""

import base64
import importlib.util
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

SPEC = importlib.util.spec_from_file_location(
    "detect_changes", pathlib.Path(__file__).with_name("detect-changes.py"))
detect = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(detect)
BUILD = "YouTrackDB_UnitTestsLinuxX86"


def message(kind, **values):
    print("##teamcity[" + kind + " " + " ".join(
        f"{key}='{detect.escape(value)}'" for key, value in values.items()) + "]")


class BuildFailure(ValueError):
    """Carry only fixed, safe failure text and a stable TeamCity identity."""

    def __init__(self, identity, text):
        super().__init__(text)
        self.identity = identity
        self.text = text


def failure(identity, text):
    message("message", text=text, status="ERROR")
    message("buildProblem", description=text, identity=identity)
    return 1


def rest(path, plain=False):
    # Refuse redirects so build-scoped credentials cannot leave the server.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args):
            raise ValueError("Unexpected TeamCity redirect")

    url = os.environ["TC_URL"].rstrip("/") + path
    auth = base64.b64encode(
        (os.environ["TC_USER"] + ":" + os.environ["TC_PASSWORD"]).encode()).decode()
    request = urllib.request.Request(url, headers={
        "Authorization": "Basic " + auth, "Accept": "text/plain" if plain else "application/json"})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
        return response.read() if plain else json.load(response)


def baseline(branch, successful=False):
    # Filter branch names locally, not inside REST locator syntax from PR text.
    query = urllib.parse.urlencode({
        "locator": f"buildType:{BUILD},state:finished,tag:ran-tests,defaultFilter:false,count:100",
        "fields": ("count,build(id,status,finishDate,"
                   "revisions(revision(version,vcsBranchName))),nextHref")})
    path = "/app/rest/builds?" + query
    builds = []
    while path:
        if not path.startswith("/app/rest/builds?"):
            raise ValueError("Unexpected TeamCity pagination path")
        page = rest(path)
        count = page.get("count")
        entries = page.get("build", [] if type(count) is int and count == 0 else None)
        if not isinstance(entries, list):
            raise ValueError("Unreadable TeamCity build list")
        # TeamCity count describes this page, not the full paginated history.
        if "count" in page:
            if type(count) is not int or count < 0 or count != len(entries):
                raise ValueError("Unreadable TeamCity build count")
        elif not entries:
            raise ValueError("Unreadable TeamCity build list")
        builds.extend(entries)
        path = page.get("nextHref")
    matches = []
    for build in builds:
        revisions = build["revisions"]["revision"]
        if not isinstance(revisions, list) or not revisions:
            raise ValueError("Unreadable TeamCity build revisions")
        branches = [revision.get("vcsBranchName") for revision in revisions]
        if any(not isinstance(name, str) or not name.strip() for name in branches):
            raise ValueError("Unreadable TeamCity VCS branch")
        if branch in branches and str(build["id"]) != os.environ["TC_BUILD_ID"]:
            if successful:
                status = build.get("status")
                if not isinstance(status, str) or not status.strip():
                    raise ValueError("Unreadable TeamCity build status")
                if status != "SUCCESS":
                    continue
            matches.append(build)
    if not matches:
        return None
    latest = max(matches, key=lambda b: b["finishDate"])
    return "/app/rest/builds/id:" + str(int(latest["id"]))


def properties(path):
    """Read Java properties, including escaped separators and continued lines."""
    def unescape(value):
        result = []
        index = 0
        while index < len(value):
            char = value[index]
            index += 1
            if char == "\\":
                if index == len(value):
                    raise ValueError("Incomplete properties escape")
                char = value[index]
                index += 1
                if char == "u":
                    digits = value[index:index + 4]
                    if not re.fullmatch(r"[0-9a-fA-F]{4}", digits):
                        raise ValueError("Invalid properties Unicode escape")
                    char = chr(int(digits, 16))
                    index += 4
                else:
                    char = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}.get(char, char)
            result.append(char)
        return "".join(result)

    result = {}
    pending = ""
    continuing = False
    for line in re.split(r"\r\n|\r|\n", pathlib.Path(path).read_text(encoding="iso-8859-1")):
        line = line.lstrip(" \t\f")
        if not continuing and (not line or line.startswith(("#", "!"))):
            continue
        pending += line
        trailing = len(pending) - len(pending.rstrip("\\"))
        if trailing % 2:
            pending = pending[:-1]
            continuing = True
            continue
        continuing = False
        match = re.fullmatch(r"((?:\\.|[^ \t\f:=\\])*)[ \t\f]*[=:]?[ \t\f]*(.*)",
                             pending)
        if match is None:
            raise ValueError("Malformed properties entry")
        result[unescape(match[1])] = unescape(match[2])
        pending = ""
    if continuing:
        raise ValueError("Incomplete properties continuation")
    return result


def pr_properties():
    # Read only at run time. PR parameters do not exist on other build phases.
    system = properties(os.environ["TEAMCITY_BUILD_PROPERTIES_FILE"])
    return properties(system["teamcity.configuration.properties.file"])


def detect_build():
    branch = os.environ["TC_VCS_BRANCH"]
    args = ["--root", ".", "--head", "HEAD"]
    if re.fullmatch(r"(?:refs/)?pull/[0-9]+(?:/head)?", branch):
        try:
            facts = pr_properties()
            if not facts.get("teamcity.pullRequest.source.branch"):
                raise ValueError("Missing source")
        except (OSError, ValueError, KeyError):
            raise BuildFailure("pr-source", "PR has no readable same-repository source.") from None
        kind, base = "pr", facts.get("teamcity.pullRequest.target.branch", "")
        args += ["--same-repository"]
    elif branch.startswith("refs/heads/gh-readonly-queue/develop/"):
        kind, base = "merge-queue", "refs/heads/develop"
    elif branch == "refs/heads/develop":
        with tempfile.TemporaryDirectory() as directory:
            try:
                previous = baseline("refs/heads/develop")
                if previous is None:
                    raise ValueError("No previous develop test build")
                revisions = rest(previous + "/revisions")["revision"]
                revision = revisions[0]["version"]
                if not re.fullmatch(r"[0-9a-fA-F]{40}", revision):
                    raise ValueError("Invalid baseline revision")
                paths = detect.git(".", "diff", "--no-renames", "--name-only",
                                   "-z", revision, "HEAD", "--")
                changes = pathlib.Path(directory) / "changes.z"
                changes.write_bytes(paths)
                return detect.main(args + ["--commit-type", "develop", "--changes", str(changes)])
            except Exception as error:
                message("message", text="Full develop build fallback: " + type(error).__name__,
                        status="WARNING")
                return detect.main(args + ["--commit-type", "develop", "--full"])
    else:
        raise BuildFailure("vcs-branch", "Unsupported VCS branch.")
    try:
        # Only develop is a target in this phase table. Never pass PR text to git.
        if base not in ("develop", "refs/heads/develop"):
            raise ValueError("Invalid target branch")
        base = "refs/heads/develop"
        detect.git(".", "check-ref-format", base)
        detect.git(".", "-c", "credential.helper=", "-c", "http.extraHeader=",
                   "-c", "http.https://github.com/.extraheader=", "fetch",
                   "https://github.com/JetBrains/youtrackdb.git", base)
        base = detect.git(".", "rev-parse", "FETCH_HEAD").strip().decode("ascii")
    except Exception as error:
        message("message", text="Target fetch failed: " + type(error).__name__, status="WARNING")
        base = ""  # The detector retains its full-build fallback.
    return detect.main(args + ["--commit-type", kind, "--base", base])


def guard():
    value = os.environ.get("TC_SHOULD_BUILD")
    if value not in ("true", "false"):
        raise BuildFailure("detect-should-build", "detect.should_build is missing or malformed.")
    if value == "false":
        message("buildStatus", text="No build needed: no build-relevant files changed")
    return 0


def verify():
    guard()
    if os.environ["TC_SHOULD_BUILD"] != "true":
        return 0
    gate_text = "Test-failure gate failed: failed tests or missing or unreadable reports."
    try:
        gate = subprocess.run([sys.executable, ".github/scripts/test-failure-gate.py",
                               "--root", ".", "--require", "surefire"], capture_output=True)
    except OSError:
        raise BuildFailure("test-failure-gate", gate_text) from None
    # The reused gate prints PR-controlled report names. Escape its entire output.
    print(detect.escape(
        gate.stdout.decode(errors="replace") + gate.stderr.decode(errors="replace")))
    # Report the gate now, but still evaluate the count so neither cause hides the other.
    result = failure("test-failure-gate", gate_text) if gate.returncode else 0
    try:
        roots = [ET.parse(p).getroot() for p in pathlib.Path(".").glob(
            "**/target/surefire-reports/TEST-*.xml")]
        suites = [s for root in roots
                  for s in ([root] if root.tag == "testsuite" else root.iter("testsuite"))]
        count = sum(int(s.get("tests", "0")) for s in suites)
        skipped = sum(int(s.get("skipped", "0")) for s in suites)
    except (OSError, ValueError, ET.ParseError):
        raise BuildFailure("test-failure-gate", gate_text) from None
    if count <= 0:
        raise BuildFailure("no-tests", "No tests were reported.")
    if count - skipped <= 0:
        result = failure("no-executed-tests", "No reported tests were executed.")
    message("buildStatisticValue", key="ytdb.tests", value=count)
    print("##teamcity[addBuildTag 'ran-tests']")
    stage = "history lookup"
    try:
        previous = baseline(os.environ["TC_VCS_BRANCH"], successful=True)
        if previous is None and os.environ["TC_VCS_BRANCH"] != "refs/heads/develop":
            previous = baseline("refs/heads/develop", successful=True)
        stage = "statistic read"
        data = rest(previous + "/statistics/ytdb.tests", plain=True) if previous else None
    except Exception:
        raise BuildFailure("comparison-read",
                           f"Test-count comparison {stage} failed.") from None
    if previous is None:
        print("No test-count comparison point exists.")
    else:
        try:
            # The single-statistic endpoint returns plain text, not a JSON property.
            if not isinstance(data, bytes) or not re.fullmatch(rb"[0-9]+", data.strip()):
                raise ValueError("Invalid count")
            old = int(data.strip())
            if old <= 0:
                raise ValueError("Nonpositive count")
        except ValueError:
            raise BuildFailure("comparison-count",
                               "Test-count comparison count is invalid.") from None
        if count < old:
            return failure("test-count-drop", f"Test count dropped: {count}, comparison: {old}.")
        print(f"Test count: {count}, baseline: {old}.")
    return result


def ready_decision():
    """Validate the whole PR decision, even when the job will skip Maven."""
    build, run, reason, scope, modules = (os.environ.get("TC_" + key) for key in (
        "SHOULD_BUILD", "RUN_INTEGRATION_TESTS", "INTEGRATION_SKIP_REASON",
        "INTEGRATION_SCOPE", "INTEGRATION_MODULES"))
    skip_reasons = {
        "no-it-tests": "[no-it-tests] in the head commit subject",
        "markdown-only": "only Markdown files changed",
        "no-java-changes": "no Java files changed",
        "no-it-modules": "no changed Java files in modules with integration tests",
    }
    invalid = BuildFailure("detect-ready", "Detect changes returned a malformed PR decision.")
    if (build not in ("true", "false")
            or run not in ("true", "false")
            or (reason != "" if run == "true" else reason not in skip_reasons)
            or (reason == "markdown-only" and build != "false")
            or (run == "true" and build != "true")):
        raise invalid
    if scope == "selected" and run == "true" and modules:
        names = modules.split(",")
        try:
            spec = importlib.util.spec_from_file_location(
                "select_it_modules", pathlib.Path(__file__).with_name("select-it-modules.py"))
            selector = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(selector)
            reactor = selector.reactor_modules(pathlib.Path("."))
        except (OSError, ValueError, ET.ParseError):
            raise invalid from None
        if len(set(names)) != len(names) or any(not re.match(r"[A-Za-z0-9]", name)
                                                or name not in reactor for name in names):
            raise invalid
    elif scope != "full" or modules != "":
        raise invalid
    return build == "true", skip_reasons.get(reason), modules


def ready_build(kind, action):
    build, skip, modules = ready_decision()
    if not build or (kind == "failsafe" and skip):
        if action == "guard":
            text = ("No build needed: no build-relevant files changed" if not build
                    else "No integration tests needed: " + skip)
            message("buildStatus", text=text)
        return 0
    selected = ["-pl", modules] if modules else []
    if action == "guard":
        return 0
    if action == "maven":
        # Module paths are one argument and never enter a shell.
        maven = ["./mvnw", "-s", ".github/workflows/settings.xml"] + selected
        profile = ["-P", "ci-integration-tests"]
        if selected:
            result = subprocess.run(maven + ["-am", "clean", "install", "-B", "-DskipTests"]
                                    + profile).returncode
            if result:
                return result
        return subprocess.run(maven + ["clean", "verify", "-B", "-Dmaven.test.failure.ignore=true",
                                       "-Dit.storage.callFsync=false"] + profile).returncode
    gate = [sys.executable, ".github/scripts/test-failure-gate.py", "--root", ".", "--require", kind]
    if kind == "failsafe" and modules:
        gate += ["--expected-modules", modules]
    result = subprocess.run(gate, capture_output=True)
    output = result.stdout + result.stderr
    print(detect.escape(output.decode(errors="replace")))
    if result.returncode:
        return failure("test-failure-gate", "Test-failure gate failed. See the report diagnostics.")
    # The shared gate checks per-module executions for Failsafe. Also reject an
    # entirely skipped required report kind, without recording a count baseline.
    for path in pathlib.Path(".").glob(f"**/target/{kind}-reports/TEST-*.xml"):
        root = ET.parse(path).getroot()
        if any(int(suite.get("tests", "0")) > int(suite.get("skipped", "0"))
               for suite in root.iter("testsuite")):
            return 0
    return failure("no-executed-tests", "No reported tests were executed.")


def main():
    try:
        args = sys.argv[1:]
        if args[0] == "ready":
            return ready_build(*args[1:])
        return {"detect": detect_build, "guard": guard, "verify": verify}[args[0]]()
    except BuildFailure as error:
        return failure(error.identity, error.text)
    except Exception as error:
        return failure("unexpected-error",
                       "TeamCity connecting script failed: " + type(error).__name__)


if __name__ == "__main__":
    raise SystemExit(main())
