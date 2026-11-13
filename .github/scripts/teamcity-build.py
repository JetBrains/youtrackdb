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


def rest(path):
    # Refuse redirects so build-scoped credentials cannot leave the server.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args):
            raise ValueError("Unexpected TeamCity redirect")

    url = os.environ["TC_URL"].rstrip("/") + path
    auth = base64.b64encode(
        (os.environ["TC_USER"] + ":" + os.environ["TC_PASSWORD"]).encode()).decode()
    request = urllib.request.Request(url, headers={
        "Authorization": "Basic " + auth, "Accept": "application/json"})
    with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
        return json.load(response)


def baseline(branch):
    # Filter branch names locally, not inside REST locator syntax from PR text.
    query = urllib.parse.urlencode({
        "locator": f"buildType:{BUILD},state:finished,tag:ran-tests,defaultFilter:false,count:100",
        "fields": "count,build(id,finishDate,revisions(revision(version,vcsBranchName))),nextHref"})
    path = "/app/rest/builds?" + query
    builds = []
    while path:
        if not path.startswith("/app/rest/builds?"):
            raise ValueError("Unexpected TeamCity pagination path")
        page = rest(path)
        if "build" not in page and page.get("count") != 0:
            raise ValueError("Unreadable TeamCity build list")
        builds.extend(page.get("build", []))
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
        facts = pr_properties()
        if not facts.get("teamcity.pullRequest.source.branch"):
            raise ValueError("Refusing a PR without a same-repository source")
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
        raise ValueError("Unsupported VCS branch")
    try:
        # Only develop is a target in this phase table. Never pass PR text to git.
        if base not in ("develop", "refs/heads/develop"):
            raise ValueError("Invalid target branch")
        base = "refs/heads/develop"
        detect.git(".", "check-ref-format", base)
        detect.git(".", "-c", "credential.helper=", "-c", "http.extraHeader=", "fetch",
                   "https://github.com/JetBrains/youtrackdb.git", base)
        base = detect.git(".", "rev-parse", "FETCH_HEAD").strip().decode("ascii")
    except Exception as error:
        message("message", text="Target fetch failed: " + type(error).__name__, status="WARNING")
        base = ""  # The detector retains its full-build fallback.
    return detect.main(args + ["--commit-type", kind, "--base", base])


def guard():
    value = os.environ.get("TC_SHOULD_BUILD")
    if value not in ("true", "false"):
        raise ValueError("Missing or malformed detect.should_build")
    if value == "false":
        message("buildStatus", text="No build needed: no build-relevant files changed")
    return 0


def verify():
    guard()
    if os.environ["TC_SHOULD_BUILD"] != "true":
        return 0
    gate = subprocess.run([sys.executable, ".github/scripts/test-failure-gate.py",
                           "--root", ".", "--require", "surefire"], capture_output=True)
    # The reused gate prints PR-controlled report names. Escape its entire output.
    print(detect.escape(gate.stdout.decode(errors="replace") + gate.stderr.decode(errors="replace")))
    suites = [ET.parse(p).getroot() for p in pathlib.Path(".").glob(
        "**/target/surefire-reports/TEST-*.xml")]
    count = sum(int(s.get("tests", "0")) for root in suites
                for s in ([root] if root.tag == "testsuite" else root.iter("testsuite")))
    if count <= 0:
        raise ValueError("No tests were reported")
    message("buildStatisticValue", key="ytdb.tests", value=count)
    print("##teamcity[addBuildTag 'ran-tests']")
    previous = baseline(os.environ["TC_VCS_BRANCH"])
    if previous is None and os.environ["TC_VCS_BRANCH"] != "refs/heads/develop":
        previous = baseline("refs/heads/develop")
    if previous is None:
        print("No test-count comparison point exists.")
    else:
        old = int(rest(previous + "/statistics/ytdb.tests")["value"])
        if old <= 0 or count < old:
            raise ValueError("Test count dropped or baseline count is invalid")
        print(f"Test count: {count}, baseline: {old}.")
    return gate.returncode


def main():
    try:
        return {"detect": detect_build, "guard": guard, "verify": verify}[sys.argv[1]]()
    except Exception as error:
        message("message", text="TeamCity connecting script failed: " + type(error).__name__,
                status="ERROR")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
