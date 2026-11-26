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
BUILD = "YouTrackDB_UnitTestsLinuxArm64Push"
DEVELOP = "refs/heads/develop"


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


def branch_kind(branch):
    if re.fullmatch(r"(?:refs/)?pull/[0-9]+(?:/head)?", branch):
        return "pr"
    if re.fullmatch(r"refs/heads/gh-readonly-queue/develop/.+", branch):
        return "merge-queue"
    if branch == DEVELOP:
        return "develop"
    raise BuildFailure("vcs-branch", "Unsupported VCS branch.")


def detect_build():
    kind = branch_kind(os.environ.get("TC_VCS_BRANCH", ""))
    args = ["--root", ".", "--head", "HEAD", "--commit-type", kind]
    if kind == "pr":
        try:
            facts = pr_properties()
            if not facts.get("teamcity.pullRequest.source.branch"):
                raise ValueError("Missing source")
        except (OSError, ValueError, KeyError):
            raise BuildFailure("pr-source", "PR has no readable same-repository source.") from None
        base = facts.get("teamcity.pullRequest.target.branch", "")
        args += ["--same-repository"]
    elif kind == "merge-queue":
        base = DEVELOP
    else:
        with tempfile.TemporaryDirectory() as directory:
            try:
                previous = baseline(DEVELOP)
                if previous is None:
                    raise ValueError("No previous develop test build")
                revisions = rest(previous + "/revisions")["revision"]
                revision = revisions[0]["version"]
                if not re.fullmatch(r"[0-9a-fA-F]{40}", revision):
                    raise ValueError("Invalid baseline revision")
                head = detect.git(".", "rev-parse", "HEAD").strip().decode("ascii")
                if revision.lower() == head:
                    return detect.main(args + ["--full"])
                paths = detect.git(".", "diff", "--no-renames", "--name-only",
                                   "-z", revision, "HEAD", "--")
                changes = pathlib.Path(directory) / "changes.z"
                changes.write_bytes(paths)
                return detect.main(args + ["--changes", str(changes)])
            except Exception as error:
                message("message", text="Full develop build fallback: " + type(error).__name__,
                        status="WARNING")
                return detect.main(args + ["--full"])
    try:
        # Only develop is a target in this phase table. Never pass PR text to git.
        if base not in ("develop", DEVELOP):
            raise ValueError("Invalid target branch")
        base = DEVELOP
        detect.git(".", "check-ref-format", base)
        detect.git(".", "-c", "credential.helper=", "-c", "http.extraHeader=",
                   "-c", "http.https://github.com/.extraheader=", "fetch",
                   "https://github.com/JetBrains/youtrackdb.git", base)
        base = detect.git(".", "rev-parse", "FETCH_HEAD").strip().decode("ascii")
    except Exception as error:
        message("message", text="Target fetch failed: " + type(error).__name__, status="WARNING")
        base = ""  # The detector retains its full-build fallback.
    return detect.main(args + ["--base", base])


def build_needed():
    value = os.environ.get("TC_SHOULD_BUILD")
    if value not in ("true", "false"):
        raise BuildFailure("detect-should-build", "detect.should_build is missing or malformed.")
    if value == "false":
        message("buildStatus", text="No build needed: no build-relevant files changed")
    return value == "true"


def guard():
    branch_kind(os.environ.get("TC_VCS_BRANCH", ""))
    build_needed()
    return 0


def maven():
    kind = branch_kind(os.environ.get("TC_VCS_BRANCH", ""))
    if not build_needed():
        return 0
    profile = "docker-images,coverage" if kind == "pr" else "docker-images"
    return subprocess.run([
        "./mvnw", "-s", ".github/workflows/settings.xml", "clean", "package", "-B",
        "-Dmaven.test.failure.ignore=true", "-Dyoutrackdb.test.env=ci",
        "-P", profile, "-Ddocker.platforms=linux/arm64/v8",
    ]).returncode


def verify():
    branch = os.environ.get("TC_VCS_BRANCH", "")
    kind = branch_kind(branch)
    if not build_needed():
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
        return failure("no-executed-tests", "No reported tests were executed.")
    message("buildStatisticValue", key="ytdb.tests", value=count)
    print("##teamcity[addBuildTag 'ran-tests']")
    if kind != "pr":
        return result
    stage = "history lookup"
    try:
        previous = baseline(branch, successful=True)
        if previous is None:
            previous = baseline(DEVELOP, successful=True)
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


def main():
    try:
        return {"detect": detect_build, "guard": guard, "maven": maven,
                "verify": verify}[sys.argv[1]]()
    except BuildFailure as error:
        return failure(error.identity, error.text)
    except Exception as error:
        return failure("unexpected-error",
                       "TeamCity connecting script failed: " + type(error).__name__)


if __name__ == "__main__":
    raise SystemExit(main())
