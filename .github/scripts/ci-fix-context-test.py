#!/usr/bin/env python3
"""Stand-alone tests for the CI fix agent's failure-context pre-fetch.

Run directly, with no pytest dependency, like the other .github/scripts
helpers:

    python3 .github/scripts/ci-fix-context-test.py

Exit code 0 means all checks passed; non-zero prints the failing cases.
Test functions (any module-level `test_*`) are discovered automatically, so
adding one needs no edit to `main()`.

The subject under test is the "Gather CI failure context" shell block inside
`.github/workflows/ci-failure-fix-agent.yml`. That block is the fix agent's
only source of truth about the failure it was dispatched to fix, and it runs
just once per dispatch on a self-hosted runner, so a regression in it is
invisible until an agent has already produced a useless report. The block is
extracted from the workflow as text and executed under `bash -e` with a stub
`gh` on PATH, which is what lets these checks run anywhere with no token and
no network.

Only stdlib is used, deliberately: the workflow that runs this installs a bare
Python via actions/setup-python, which carries no PyYAML, so the workflow is
parsed by text rather than with a YAML library.
"""

import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

_WORKFLOW = (pathlib.Path(__file__).parents[1]
             / "workflows" / "ci-failure-fix-agent.yml")

_GATHER_STEP = "Gather CI failure context"
_PREREQ_STEP = "Install CLI prerequisites (jq, gh)"

_failures = []


def check(name, condition):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}")
        _failures.append(name)


# --- workflow extraction ----------------------------------------------------


def _workflow_text():
    return _WORKFLOW.read_text(encoding="utf-8")


def _step_names_in_order():
    """Every `- name:` under the job's steps, in file order."""
    return re.findall(r"^\s*- name: (.+)$", _workflow_text(), re.MULTILINE)


def _extract_run_block(step_name):
    """Return the dedented body of `run: |` for the named step.

    Hand-rolled instead of using a YAML parser (see the module docstring). The
    block scalar's indentation is taken from its first non-blank line, which is
    what YAML itself does, so a re-indentation of the workflow cannot silently
    change what this test executes.
    """
    lines = _workflow_text().splitlines()
    start = None
    for i, line in enumerate(lines):
        if line.strip() == f"- name: {step_name}":
            start = i
            break
    if start is None:
        raise AssertionError(f"step not found in workflow: {step_name!r}")

    run_at = None
    for i in range(start, len(lines)):
        if re.match(r"^\s+run: \|\s*$", lines[i]):
            run_at = i
            break
        # A following `- name:` means this step has no literal `run: |` block.
        if i > start and lines[i].strip().startswith("- name: "):
            break
    if run_at is None:
        raise AssertionError(f"no 'run: |' block in step {step_name!r}")

    body = []
    block_indent = None
    for line in lines[run_at + 1:]:
        if not line.strip():
            body.append("")
            continue
        indent = len(line) - len(line.lstrip())
        if block_indent is None:
            block_indent = indent
        elif indent < block_indent:
            break
        body.append(line[block_indent:])
    while body and not body[-1]:
        body.pop()
    return "\n".join(body) + "\n"


def _gather_script():
    """The gather block with Actions expressions resolved, as the runner does."""
    script = _extract_run_block(_GATHER_STEP)
    script = script.replace("${{ github.repository }}", "JetBrains/youtrackdb")
    leftover = re.findall(r"\$\{\{.*?\}\}", script)
    if leftover:
        raise AssertionError(
            f"unresolved Actions expressions in gather block: {leftover}. "
            "Add a substitution here so the test executes what CI executes.")
    return script


# --- gh stub ----------------------------------------------------------------

# Each scenario is a case in one stub script. `run view --json jobs`,
# `run view --log-failed` and plain `run view --json ...` are distinguished the
# same way the real gh distinguishes them: by flags.
_GH_STUB = r"""#!/usr/bin/env bash
run_kind() {
  case "$*" in
    *--log-failed*) echo logs ;;
    *"--json jobs"*) echo jobs ;;
    *) echo run ;;
  esac
}
case "$STUB_CASE" in
  absent)
    # Exactly what bash reports for a command that is not installed.
    echo "gh: command not found" >&2; exit 127 ;;
  allok)
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        logs) echo "test-linux  Run Maven Test  FooTest.bar FAILED"; exit 0 ;;
        jobs) echo '{"jobs":[{"databaseId":111,"conclusion":"failure"},
                             {"databaseId":222,"conclusion":"success"}]}'; exit 0 ;;
        run)  echo '{"headSha":"08eaf8c966","conclusion":"failure","event":"push"}'; exit 0 ;;
      esac
    fi
    if [ "$1" = api ]; then
      case "$2" in
        *annotations) echo '[{"path":"Foo.java","message":"boom"}]'; exit 0 ;;
        *pulls)       echo '[{"number":1295}]'; exit 0 ;;
        *comments)    echo '[{"body":"coverage 91%"}]'; exit 0 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[{"number":42,"headRefName":"ci-fix/x"}]'; exit 0; }
    exit 0 ;;
  partial_annotations)
    # Two failed jobs; annotations for the second 404 with a MULTI-LINE error.
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        logs) echo "log line"; exit 0 ;;
        jobs) echo '{"jobs":[{"databaseId":111,"conclusion":"failure"},
                             {"databaseId":333,"conclusion":"failure"}]}'; exit 0 ;;
        run)  echo '{"headSha":"08eaf8c966"}'; exit 0 ;;
      esac
    fi
    if [ "$1" = api ]; then
      case "$2" in
        *check-runs/111/annotations) echo '[{"message":"boom"}]'; exit 0 ;;
        *check-runs/333/annotations)
          printf 'HTTP 404: Not Found\nsecond line of the error\n' >&2; exit 1 ;;
        *pulls) echo '[]'; exit 0 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[]'; exit 0; }
    exit 0 ;;
  no_jobs)
    # The jobs fetch fails; everything else works.
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        jobs) echo "HTTP 502 Bad Gateway" >&2; exit 1 ;;
        logs) echo "log line"; exit 0 ;;
        run)  echo '{"headSha":"08eaf8c966"}'; exit 0 ;;
      esac
    fi
    if [ "$1" = api ]; then
      case "$2" in
        *pulls)    echo '[{"number":7}]'; exit 0 ;;
        *comments) echo '[{"body":"hi"}]'; exit 0 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[]'; exit 0; }
    exit 0 ;;
  no_run)
    # The run fetch fails, so headSha is unobtainable.
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        jobs) echo '{"jobs":[]}'; exit 0 ;;
        logs) echo "log line"; exit 0 ;;
        run)  echo "could not find any workflow run" >&2; exit 1 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[]'; exit 0; }
    exit 0 ;;
  all_annotations_fail)
    # Two failed jobs, BOTH annotation calls fail: nothing arrived, so the
    # aggregate is `failed`, not `partial`.
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        logs) echo "log line"; exit 0 ;;
        jobs) echo '{"jobs":[{"databaseId":111,"conclusion":"failure"},
                             {"databaseId":333,"conclusion":"failure"}]}'; exit 0 ;;
        run)  echo '{"headSha":"08eaf8c966"}'; exit 0 ;;
      esac
    fi
    if [ "$1" = api ]; then
      case "$2" in
        *annotations) echo "HTTP 403: rate limit exceeded" >&2; exit 1 ;;
        *pulls)       echo '[]'; exit 0 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[]'; exit 0; }
    exit 0 ;;
  no_failed_jobs)
    # The run has jobs but none failed, so the annotations loop body never
    # runs. Zero failures over zero attempts is a real empty answer: `ok`.
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        logs) echo "log line"; exit 0 ;;
        jobs) echo '{"jobs":[{"databaseId":222,"conclusion":"success"}]}'; exit 0 ;;
        run)  echo '{"headSha":"08eaf8c966"}'; exit 0 ;;
      esac
    fi
    if [ "$1" = api ]; then
      case "$2" in
        *annotations) echo "should never be called" >&2; exit 1 ;;
        *pulls)       echo '[]'; exit 0 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[]'; exit 0; }
    exit 0 ;;
  partial_pr_comments)
    # Two associated PRs, one comments call fails: the pr-comments aggregate
    # takes the same three-way rule as annotations.
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        logs) echo "log line"; exit 0 ;;
        jobs) echo '{"jobs":[]}'; exit 0 ;;
        run)  echo '{"headSha":"08eaf8c966"}'; exit 0 ;;
      esac
    fi
    if [ "$1" = api ]; then
      case "$2" in
        *pulls)                 echo '[{"number":11},{"number":22}]'; exit 0 ;;
        *issues/11/comments)    echo '[{"body":"coverage 91%"}]'; exit 0 ;;
        *issues/22/comments)    echo "HTTP 404: Not Found" >&2; exit 1 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[]'; exit 0; }
    exit 0 ;;
  malformed_json)
    # Every call "succeeds" but the annotations payload is not JSON, so the
    # jq merge fails. The aggregate must report `failed`, not `ok`.
    if [ "$1" = run ]; then
      case "$(run_kind "$@")" in
        logs) echo "log line"; exit 0 ;;
        jobs) echo '{"jobs":[{"databaseId":111,"conclusion":"failure"}]}'; exit 0 ;;
        run)  echo '{"headSha":"08eaf8c966"}'; exit 0 ;;
      esac
    fi
    if [ "$1" = api ]; then
      case "$2" in
        *annotations) echo 'this is not json at all {{{'; exit 0 ;;
        *pulls)       echo '[]'; exit 0 ;;
      esac
    fi
    [ "$1" = pr ] && { echo '[]'; exit 0; }
    exit 0 ;;
esac
exit 0
"""


class GatherRun:
    """Result of executing the gather block once against a stub-gh scenario."""

    def __init__(self, workdir, returncode, output, status, files):
        self.workdir = workdir
        self.returncode = returncode
        self.output = output
        self.status = status
        self.files = files

    def warnings(self):
        return [ln for ln in self.output.splitlines() if ln.startswith("::warning::")]

    def degraded_warning(self):
        for line in self.warnings():
            if "INCOMPLETE" in line:
                return line
        return None


def run_gather(case):
    """Execute the gather block under `bash -e` with the stub gh for `case`."""
    workdir = tempfile.mkdtemp(prefix=f"ci-fix-context-{case}-")
    bindir = os.path.join(workdir, "bin")
    os.makedirs(bindir)
    stub = os.path.join(bindir, "gh")
    with open(stub, "w", encoding="utf-8") as fh:
        fh.write(_GH_STUB)
    os.chmod(stub, 0o755)

    runner_temp = os.path.join(workdir, "runner-temp")
    os.makedirs(runner_temp)
    github_env = os.path.join(workdir, "github_env")
    open(github_env, "w", encoding="utf-8").close()

    script = os.path.join(workdir, "gather.sh")
    with open(script, "w", encoding="utf-8") as fh:
        fh.write(_gather_script())

    env = dict(os.environ)
    env.update({
        "PATH": bindir + os.pathsep + env["PATH"],
        "RUNNER_TEMP": runner_temp,
        "GITHUB_ENV": github_env,
        "FAILED_RUN_URL":
            "https://github.com/JetBrains/youtrackdb/actions/runs/33870767133",
        "STUB_CASE": case,
    })
    proc = subprocess.run(["bash", "-e", script], env=env, capture_output=True,
                          text=True, timeout=120)

    ctx = pathlib.Path(runner_temp) / "ci-fix-context"
    status = {}
    status_file = ctx / "fetch-status.json"
    if status_file.exists():
        status = json.loads(status_file.read_text(encoding="utf-8"))
    files = {p.name: p.read_text(encoding="utf-8")
             for p in sorted(ctx.glob("*"))} if ctx.is_dir() else {}
    return GatherRun(workdir, proc.returncode, proc.stdout + proc.stderr,
                     status, files)


_DATA_KEYS = ["run", "jobs", "logs", "annotations", "associated-prs",
              "pr-comments", "existing-fix-prs"]


# --- static guards ----------------------------------------------------------


def test_prerequisite_step_installs_and_verifies_both_tools():
    """`gh` must be a checked prerequisite, installed before anything reads it.

    The original defect: only `jq` was installed, so every `gh` call in the
    gather step exited 127 and the step's `||` fallbacks quietly wrote empty
    placeholders. These checks pin the three properties that prevent the
    regression -- both tools named, absence is fatal, and the install happens
    before the fetch that needs it.
    """
    block = _extract_run_block(_PREREQ_STEP)
    # Both loops -- the one that decides what to install and the one that
    # verifies the result -- must cover both tools. A bare `"gh" in block`
    # would pass on a comment mentioning gh, so the tool list is matched
    # exactly and both occurrences are required.
    tool_lists = re.findall(r"for TOOL in ([^;]+); do", block)
    check(f"prerequisite step has two tool loops (found {len(tool_lists)})",
          len(tool_lists) == 2)
    check(f"both loops cover jq and gh (found {tool_lists})",
          all(lst.split() == ["jq", "gh"] for lst in tool_lists)
          and len(tool_lists) == 2)
    check("prerequisite step fails the job when a tool is missing",
          "::error::" in block and re.search(r"^\s*exit 1\s*$", block,
                                             re.MULTILINE) is not None)

    names = _step_names_in_order()
    check("prerequisite step runs before the gather step",
          _PREREQ_STEP in names and _GATHER_STEP in names
          and names.index(_PREREQ_STEP) < names.index(_GATHER_STEP))


def test_prompt_documents_the_status_ledger():
    """The embedded agent prompt must describe fetch-status.json and its values.

    The ledger is useless if the agent is not told to read it, and the prompt
    previously asserted the opposite ("Everything about the failure has been
    pre-fetched"), which is what led an agent to read placeholders as facts.
    """
    text = _workflow_text()
    check("prompt tells the agent about fetch-status.json",
          "fetch-status.json" in text)
    for value in ("`ok`", "`failed`", "`unavailable`", "`partial`"):
        check(f"prompt documents status value {value}", value in text)
    check("prompt no longer promises a complete context",
          "Everything about the failure has been pre-fetched" not in text)
    check("prompt qualifies the empty associated-prs reading",
          "status is `ok`" in text)


# --- behaviour: the happy path ---------------------------------------------


def test_all_fetches_ok():
    """Every datum reachable: all statuses `ok`, no degraded warning, real data."""
    r = run_gather("allok")
    check("allok: step succeeds", r.returncode == 0)
    check("allok: every datum is ok",
          all(r.status.get(k) == "ok" for k in _DATA_KEYS))
    check("allok: ledger covers exactly the known data",
          sorted(r.status) == sorted(_DATA_KEYS))
    check("allok: no degraded warning", r.degraded_warning() is None)
    check("allok: no per-fetch warnings", r.warnings() == [])
    check("allok: run.json holds the fetched payload",
          "08eaf8c966" in r.files.get("run.json", ""))
    check("allok: annotations.json holds the merged payload",
          "boom" in r.files.get("annotations.json", ""))
    check("allok: pr-comments.json holds the merged payload",
          "coverage 91%" in r.files.get("pr-comments.json", ""))
    shutil.rmtree(r.workdir, ignore_errors=True)


# --- behaviour: the regression this suite exists for -----------------------


def test_gh_absent_degrades_every_datum_loudly():
    """With no `gh` on PATH, nothing may be reported as a successful empty result.

    This is the exact production failure of run 33870767133: the agent received
    `{}`, `[]` and "No failed-job logs available." for every datum and had no
    way to tell them from real answers.
    """
    r = run_gather("absent")
    check("absent: step still exits 0 so investigation can continue",
          r.returncode == 0)
    check("absent: no datum claims ok",
          all(r.status.get(k) != "ok" for k in _DATA_KEYS))
    check("absent: directly fetched data are marked failed",
          all(r.status.get(k) == "failed"
              for k in ("run", "jobs", "logs", "existing-fix-prs")))
    check("absent: derived data are marked unavailable",
          all(r.status.get(k) == "unavailable"
              for k in ("annotations", "associated-prs", "pr-comments")))
    check("absent: the degraded warning is emitted",
          r.degraded_warning() is not None)
    check("absent: the warning names the missing binary",
          any("command not found" in w for w in r.warnings()))
    check("absent: placeholders are still written so readers do not crash",
          r.files.get("run.json", "").strip() == "{}"
          and r.files.get("associated-prs.json", "").strip() == "[]")
    shutil.rmtree(r.workdir, ignore_errors=True)


# --- behaviour: dependent data --------------------------------------------


def test_datum_derived_from_a_failed_parent_is_unavailable():
    """A derived datum must not inherit `ok` from an empty placeholder parent.

    annotations comes from jobs.json and associated-prs comes from run.json's
    headSha. If the parent fetch failed there is nothing to enumerate, so `ok`
    on an empty file would assert something the step never checked.
    """
    r = run_gather("no_jobs")
    check("no_jobs: jobs marked failed", r.status.get("jobs") == "failed")
    check("no_jobs: annotations marked unavailable, not ok",
          r.status.get("annotations") == "unavailable")
    check("no_jobs: data not derived from jobs stay ok",
          r.status.get("run") == "ok" and r.status.get("associated-prs") == "ok")
    check("no_jobs: degraded warning names both affected data",
          "jobs" in (r.degraded_warning() or "")
          and "annotations" in (r.degraded_warning() or ""))
    shutil.rmtree(r.workdir, ignore_errors=True)

    r = run_gather("no_run")
    check("no_run: run marked failed", r.status.get("run") == "failed")
    check("no_run: associated-prs unavailable without a headSha",
          r.status.get("associated-prs") == "unavailable")
    check("no_run: pr-comments unavailable in turn",
          r.status.get("pr-comments") == "unavailable")
    check("no_run: jobs and logs are unaffected",
          r.status.get("jobs") == "ok" and r.status.get("logs") == "ok")
    shutil.rmtree(r.workdir, ignore_errors=True)


def test_partial_aggregate_keeps_what_arrived():
    """One failed item in an aggregate marks it `partial` without discarding the rest.

    Two failed jobs, annotations for the second 404. Reporting `failed` would
    throw away the first job's annotations -- usually the most specific evidence
    available -- and reporting `ok` would hide the gap.
    """
    r = run_gather("partial_annotations")
    check("partial: annotations marked partial",
          r.status.get("annotations") == "partial")
    check("partial: the successful job's annotations are retained",
          "boom" in r.files.get("annotations.json", ""))
    check("partial: the failing job is named in a warning",
          any("333" in w for w in r.warnings()))
    check("partial: degraded warning is emitted",
          r.degraded_warning() is not None)
    shutil.rmtree(r.workdir, ignore_errors=True)


def test_aggregate_status_distinguishes_all_failed_from_some_failed():
    """`partial` must mean "some of it arrived", so all-failed reports `failed`.

    An agent that reads `partial` will use the file and note the gap. An agent
    that reads `failed` will not use the file at all. Reporting `partial` over
    an aggregate where every call failed therefore points the agent at an empty
    file it has been told is usable -- the same confusion between absent and
    empty that this whole change exists to remove.
    """
    r = run_gather("all_annotations_fail")
    check("all-failed: annotations marked failed, not partial",
          r.status.get("annotations") == "failed")
    check("all-failed: the file is the empty placeholder",
          r.files.get("annotations.json", "").strip() == "[]")
    check("all-failed: both jobs are named in warnings",
          any("111" in w for w in r.warnings())
          and any("333" in w for w in r.warnings()))
    shutil.rmtree(r.workdir, ignore_errors=True)

    r = run_gather("partial_pr_comments")
    check("partial pr-comments: marked partial",
          r.status.get("pr-comments") == "partial")
    check("partial pr-comments: the successful PR's comments are retained",
          "coverage 91%" in r.files.get("pr-comments.json", ""))
    check("partial pr-comments: the failing PR is named in a warning",
          any("#22" in w for w in r.warnings()))
    shutil.rmtree(r.workdir, ignore_errors=True)


def test_zero_failed_jobs_is_a_real_empty_answer():
    """A run with no failed jobs reports annotations `ok` over an empty list.

    The annotations loop body never executes, so zero calls failed out of zero
    attempted. That is genuinely "GitHub has no annotations for this run", not
    a fetch that did not happen, and marking it `failed` or `unavailable` would
    send the agent hunting for data that does not exist.
    """
    r = run_gather("no_failed_jobs")
    check("no failed jobs: annotations marked ok", r.status.get("annotations") == "ok")
    check("no failed jobs: annotations file is an empty list",
          r.files.get("annotations.json", "").strip() == "[]")
    check("no failed jobs: no degraded warning", r.degraded_warning() is None)
    shutil.rmtree(r.workdir, ignore_errors=True)


def test_unparseable_payload_is_reported_as_failed():
    """A fetch that succeeds but returns non-JSON must not be reported `ok`.

    `gh` exiting 0 is not proof the body is usable: a proxy error page or a
    truncated response still exits 0. The jq merge is what discovers this, and
    its failure has to reach the ledger, otherwise the agent is handed `[]`
    under an `ok` label.
    """
    r = run_gather("malformed_json")
    check("malformed: annotations marked failed", r.status.get("annotations") == "failed")
    check("malformed: annotations file left as a valid empty list",
          r.files.get("annotations.json", "").strip() == "[]")
    check("malformed: the merge failure is reported",
          any("Merging annotations failed" in w for w in r.warnings()))
    check("malformed: fetch-status.json is still valid JSON",
          isinstance(r.status, dict) and r.status != {})
    shutil.rmtree(r.workdir, ignore_errors=True)


def test_multiline_fetch_error_is_flattened_to_one_line():
    """A fetch error must reach the log as a single `::warning::` line.

    Two reasons. A newline inside a workflow command truncates it, so the
    warning would lose its tail; and a second line beginning with `::` would be
    parsed by the runner as a fresh workflow command sourced from remote error
    text. The stub's 404 spans two lines to cover both.
    """
    r = run_gather("partial_annotations")
    offending = [w for w in r.warnings() if "404" in w]
    check("multiline: the 404 produced exactly one warning line",
          len(offending) == 1)
    check("multiline: both source lines survive on that single line",
          bool(offending) and "Not Found" in offending[0]
          and "second line of the error" in offending[0])
    shutil.rmtree(r.workdir, ignore_errors=True)


def test_scratch_files_do_not_leak_into_runner_temp():
    """The step's intermediate files must not be left beside the context dir.

    $RUNNER_TEMP is shared with the result directory the agent writes, and a
    stray `.ndjson` or `.tsv` there is one more thing for a reader to mistake
    for input.
    """
    r = run_gather("allok")
    leftovers = sorted(
        p.name for p in (pathlib.Path(r.workdir) / "runner-temp").glob("*")
        if p.is_file())
    check(f"cleanup: no scratch files remain (found: {leftovers})",
          leftovers == [])
    check("cleanup: fetch-status.json is published in the context dir",
          "fetch-status.json" in r.files)
    shutil.rmtree(r.workdir, ignore_errors=True)


def main():
    # Discover and run every module-level test_* function, so adding a test
    # needs no manual registration here.
    tests = sorted(
        ((name, fn) for name, fn in globals().items()
         if name.startswith("test_") and callable(fn)),
        key=lambda kv: kv[0],
    )
    for name, fn in tests:
        print(f"\n{name}")
        fn()
    if _failures:
        print(f"\n{len(_failures)} check(s) failed.")
        return 1
    print(f"\nAll checks passed across {len(tests)} test function(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
