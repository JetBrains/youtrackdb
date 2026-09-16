#!/usr/bin/env python3
"""Stand-alone tests for two shell steps of .github/workflows/ci-failure-fix-agent.yml.

Both steps decide what happens when a command-line tool is missing or a GitHub read
fails, and both used to answer that question silently: the job installed jq but not gh,
so every `gh` call exited 127, discarded its stderr and left a placeholder behind, and
the agent investigated a failure it could not see.

Each test extracts a step's `run:` body straight out of the workflow and executes it
under `bash -e` — the shell GitHub uses for `run:` on Linux — with a stubbed `gh`,
`sudo` and `apt-get` on PATH. So these tests pin the YAML that ships rather than a copy
of it, and a step renamed without renaming it here fails as a reported check.

Requires PyYAML (`pip install pyyaml`); everything else is the standard library plus jq.
Run directly: `python3 .github/scripts/ci-failure-fix-agent-test.py`.
"""

import atexit
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import tempfile

try:
    import yaml
except ImportError:  # pragma: no cover - reported like the other prerequisites
    yaml = None

_DEFAULT_WORKFLOW = (
    pathlib.Path(__file__).resolve().parents[1] / "workflows" / "ci-failure-fix-agent.yml"
)
_WORKFLOW = pathlib.Path(os.environ.get("CI_FIX_AGENT_WORKFLOW", _DEFAULT_WORKFLOW)).resolve()

# The step bodies run under this shell, resolved here because the PATH handed to the
# child omits every directory that is not part of the toolbox.
_BASH = shutil.which("bash")

_PREREQ_STEP = "Install CLI prerequisites (jq, gh)"
_CONTEXT_STEP = "Gather CI failure context"

# Fixtures from the 2026-09-16 nightly failure this harness was written for. The values
# are real so the payloads read like the ones the agent sees; the only coupling that
# matters is between _RUN_JSON's headSha and the `commits/<sha>/pulls` dispatch key, and
# a drift there hits the stub's catch-all arm and fails the no-warning check.
_REPO = "JetBrains/youtrackdb"
_RUN_ID = "35043736406"
_HEAD_SHA = "460a873f516f94f861bd161657f39ab8ca494fc6"
_PR_NUMBER = "1291"
# This agent job's own run id, as distinct from the failed run it investigates.
_AGENT_RUN_ID = "35050000001"

# The step's request is a canned answer here — the stub branches on `--json` containing
# `jobs` and otherwise ignores the field list — so the request itself is asserted
# separately, against _RUN_FIELDS.
_RUN_JSON = json.dumps(
    {
        "databaseId": int(_RUN_ID),
        "conclusion": "failure",
        "event": "schedule",
        "headBranch": "develop",
        "headSha": _HEAD_SHA,
        "displayTitle": "Bump actions/setup-java from 4 to 5",
        "workflowName": "Java CI/CD Integration Tests Pipeline",
        "status": "completed",
        "url": f"https://github.com/{_REPO}/actions/runs/{_RUN_ID}",
    }
)

# A matrix run: three legs that did not succeed and one green one, because a
# single-failure run is the exception for this pipeline, the green leg must never be
# queried, and two surviving responses are needed to tell a merge from a first-page
# read.
_FAILED_JOB = "777"
_GREEN_JOB = "888"
_OTHER_FAILED_JOB = "999"
_THIRD_FAILED_JOB = "1001"
_JOBS_JSON = json.dumps(
    {
        "jobs": [
            {"databaseId": int(_FAILED_JOB), "conclusion": "failure", "name": "Linux x86 JDK 21"},
            {"databaseId": int(_GREEN_JOB), "conclusion": "success", "name": "Linux arm JDK 21"},
            {
                "databaseId": int(_OTHER_FAILED_JOB),
                "conclusion": "failure",
                "name": "Windows x64 JDK 25",
            },
            {
                "databaseId": int(_THIRD_FAILED_JOB),
                "conclusion": "failure",
                "name": "Linux x86 JDK 25",
            },
        ]
    }
)
# The same run with every unsuccessful leg killed rather than failed, plus the two
# shapes a job can take without being readable: still running (gh marshals an
# unfinished conclusion as an empty string) and cancelled before it reached a check
# run, which leaves it with no databaseId to read annotations for.
_TIMED_OUT_JOBS_JSON = json.dumps(
    {
        "jobs": [
            {"databaseId": int(_FAILED_JOB), "conclusion": "timed_out", "name": "Windows x64"},
            {"databaseId": int(_GREEN_JOB), "conclusion": "success", "name": "Linux arm"},
            {"databaseId": int(_OTHER_FAILED_JOB), "conclusion": "cancelled", "name": "Linux x86"},
            {"databaseId": 2002, "conclusion": "", "name": "Docker image build"},
            {"conclusion": "cancelled", "name": "Never started"},
        ]
    }
)
_SINGLE_JOB_JSON = json.dumps(
    {"jobs": [{"databaseId": int(_FAILED_JOB), "conclusion": "failure", "name": "Linux x86"}]}
)
_LOGS_TEXT = "Linux x86 JDK 21\tRun Maven Test\tFooTest.bar expected 1 but was 2"
_ANNOTATION = json.dumps(
    [
        {
            "path": "core/src/test/java/FooTest.java",
            "start_line": 42,
            "annotation_level": "failure",
            "title": "FooTest.bar",
            "message": "expected 1 but was 2",
        }
    ]
)
_OTHER_ANNOTATION = json.dumps(
    [
        {
            "path": "core/src/test/java/BarTest.java",
            "start_line": 7,
            "annotation_level": "failure",
            "title": "BarTest.baz",
            "message": "windows-only NPE",
        }
    ]
)
_OTHER_PR_NUMBER = "1290"
_PRS_JSON = json.dumps(
    [
        {"number": int(_PR_NUMBER), "title": "Bump actions/setup-java from 4 to 5"},
        {"number": int(_OTHER_PR_NUMBER), "title": "Bump docker-maven-plugin"},
    ]
)
_COMMENTS_JSON = json.dumps([{"body": "Test count gate: core 1373 -> 1373"}])

# Fields the prompt tells the agent to read out of run.json and existing-fix-prs.json.
# Dropping one from the request leaves a key the prompt promises and nothing supplies.
_RUN_FIELDS = (
    "databaseId,conclusion,event,headBranch,headSha,displayTitle,workflowName,status,url"
)
_PR_LIST_FIELDS = "number,title,headRefName,body,labels"
_OTHER_COMMENTS_JSON = json.dumps([{"body": "Coverage gate: 88% line, 74% branch"}])

# One temp tree for the whole suite; nothing is left in the host /tmp. Cleanup is
# registered here rather than in main() so the prerequisite checks, which return before
# main()'s body runs, cannot leak the directory.
_SESSION_ROOT = pathlib.Path(tempfile.mkdtemp(prefix="ci-fix-agent-test-"))
atexit.register(shutil.rmtree, _SESSION_ROOT, ignore_errors=True)

_failures = []


def check(name, condition):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}")
        _failures.append(name)


def step_body(step_name):
    """Return the `run:` script of the named step, with GitHub expressions resolved.

    Only `github.repository` and `github.run_id` are substituted. Any other
    `${{ ... }}` expression fails the test on purpose: the step would then depend on a
    value this harness does not supply, and an unsubstituted expression is a shell
    syntax error at run time.
    """
    workflow = yaml.safe_load(_WORKFLOW.read_text(encoding="utf-8"))
    steps = workflow["jobs"]["fix-ci-failure"]["steps"]
    matches = [step for step in steps if step.get("name") == step_name]
    if len(matches) != 1:
        raise AssertionError(f"expected exactly one step named {step_name!r}, found {len(matches)}")
    body = matches[0]["run"]
    body = re.sub(r"\$\{\{\s*github\.repository\s*\}\}", _REPO, body)
    body = re.sub(r"\$\{\{\s*github\.run_id\s*\}\}", _AGENT_RUN_ID, body)
    leftover = re.findall(r"\$\{\{[^}]*\}\}", body)
    # ${{ }} is GitHub's syntax; ${VAR:-default} is the shell's and must survive.
    leftover = [expression for expression in leftover if expression.startswith("${{")]
    if leftover:
        raise AssertionError(f"step {step_name!r} carries unsupported expressions: {leftover}")
    return body


def sandbox(name):
    """A fresh directory inside the suite's temp tree."""
    path = pathlib.Path(tempfile.mkdtemp(prefix=f"{name}-", dir=_SESSION_ROOT))
    return path


def write_stub(directory, name, script):
    """Put an executable stub named `name` in `directory` and return its path."""
    path = directory / name
    path.write_text(script, encoding="utf-8")
    path.chmod(0o755)
    return path


# The tools the two step bodies call, other than shell builtins and the ones a test
# stubs itself. PATH is rebuilt from exactly this list, so a gh installed on the host
# cannot mask a missing-gh case. bash and env are included because the stubs carry a
# `#!/usr/bin/env bash` shebang and env resolves the interpreter through this PATH.
_TOOLBOX = (
    "bash",
    "env",
    "jq",
    "sed",
    "timeout",
    "mktemp",
    "tr",
    "cut",
    "cat",
    "rm",
    "ls",
    "mkdir",
    "chmod",
    "basename",
    "dirname",
    "head",
    "printf",
)


def build_toolbox():
    """Symlink the allowlisted tools into one directory, which becomes the whole PATH."""
    directory = _SESSION_ROOT / "toolbox"
    if directory.is_dir():
        return directory
    directory.mkdir()
    for tool in _TOOLBOX:
        resolved = shutil.which(tool)
        if resolved is None:
            raise AssertionError(f"{tool} is required to run these tests")
        (directory / tool).symlink_to(resolved)
    return directory


def run_step(body, stub_dir, env_extra=None):
    """Execute a step body with `stub_dir` first on PATH and the toolbox behind it.

    Returns (CompletedProcess, root). `root` holds the step's `$RUNNER_TEMP`, its
    `$TMPDIR` (so the step's own `mktemp` files stay inside the sandbox and can be
    asserted on), the `$GITHUB_ENV` file and the redirected abort summary.
    """
    root = sandbox("run")
    script = root / "step.sh"
    script.write_text(body, encoding="utf-8")
    (root / "runner-temp").mkdir()
    (root / "github-env").touch()

    env = {
        "PATH": os.pathsep.join([str(stub_dir), str(build_toolbox())]),
        "HOME": str(root),
        "TMPDIR": str(root),
        "RUNNER_TEMP": str(root / "runner-temp"),
        "GITHUB_ENV": str(root / "github-env"),
        "GITHUB_OUTPUT": str(root / "github-output"),
        "FAILED_RUN_URL": f"https://github.com/{_REPO}/actions/runs/{_RUN_ID}",
        "FIX_AGENT_SUMMARY_FILE": str(root / "fix-agent-summary.md"),
        "GH_TOKEN": "stub-token",
    }
    env.update(env_extra or {})

    completed = subprocess.run(
        [_BASH, "-e", str(script)],
        capture_output=True,
        text=True,
        env=env,
        cwd=root,
        # A step body that waits on stdin would otherwise hang until the job timeout,
        # with nothing in the output naming the test that hung.
        stdin=subprocess.DEVNULL,
        timeout=60,
    )
    return completed, root


def context_dir(root):
    return root / "runner-temp" / "ci-fix-context"


def summary_text(root):
    path = root / "fix-agent-summary.md"
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def log_annotations(stdout, level="warning"):
    """The workflow-command lines GitHub parses, as a list of message texts.

    Asserting on this rather than on a substring of the whole output separates the two
    things that matter — that a `::warning::` was emitted at all, and which datum it
    names — from the English phrasing, which is free to change.
    """
    return [
        match.group(2)
        for match in re.finditer(r"^::(\w+)::(.*)$", stdout, re.MULTILINE)
        if match.group(1) == level
    ]


def degraded(root):
    """The context step's machine-readable ledger of files whose fetch failed.

    An absent ledger reads as no entries, so a workflow that does not write one fails
    the checks that expect a name in it rather than crashing the harness.
    """
    path = context_dir(root) / "degraded.txt"
    if not path.is_file():
        return []
    return path.read_text(encoding="utf-8").splitlines()


def argv_log(stub_dir):
    """Every argument every stubbed gh invocation received, one per line."""
    path = stub_dir / "gh-argv.log"
    return path.read_text(encoding="utf-8") if path.is_file() else ""


class Response:
    """One stubbed gh outcome: exit status, stdout body, stderr text."""

    def __init__(self, status=0, out="", err=""):
        self.status = status
        self.out = out
        self.err = err


def _arm(response):
    """Render the body of a stub case arm for one Response."""
    lines = []
    if response.out:
        lines.append(f"cat <<'STUB_OUT'\n{response.out}\nSTUB_OUT")
    if response.err:
        lines.append(f"cat >&2 <<'STUB_ERR'\n{response.err}\nSTUB_ERR")
    lines.append(f"exit {response.status}")
    return "\n".join(f"      {line}" for line in lines)


def gh_stub(
    run=None,
    jobs=None,
    logs=None,
    pr_list=None,
    api=None,
    jobs_json=None,
    expect_run_id=_RUN_ID,
):
    """Render a `gh` stub that answers the six calls the context step makes.

    The three `gh run view` calls are separated by the value of `--json` and by
    `--log-failed` rather than by a bare positional token, so extending a field list
    (`--json jobs,conclusion`) cannot silently reroute a call to the wrong arm. Every
    invocation's argv is appended to `gh-argv.log`, and an unexpected call exits 9 so it
    surfaces as a failure instead of a plausible-looking empty answer.
    """
    run = run or Response(0, _RUN_JSON)
    jobs = jobs or Response(0, jobs_json or _SINGLE_JOB_JSON)
    logs = logs or Response(0, _LOGS_TEXT)
    pr_list = pr_list or Response(0, "[]")
    api = dict(api or {})

    api_arms = "\n".join(
        f'    "{path}")\n{_arm(response)} ;;' for path, response in api.items()
    )
    return f"""#!/usr/bin/env bash
for arg in "$@"; do printf '%s\\n' "$arg" >> "$(dirname "$0")/gh-argv.log"; done

json_fields=""
prev=""
log_failed=0
for arg in "$@"; do
  [ "$prev" = "--json" ] && json_fields="$arg"
  [ "$arg" = "--log-failed" ] && log_failed=1
  prev="$arg"
done

case "$1 $2" in
  "run view")
    if [ -n "{expect_run_id}" ] && [ "$3" != "{expect_run_id}" ]; then
      echo "gh: could not find any run with ID $3" >&2
      exit 1
    fi
    if [ "$log_failed" = 1 ]; then
{_arm(logs)}
    fi
    case "$json_fields" in
      *jobs*)
{_arm(jobs)}
        ;;
      *)
{_arm(run)}
        ;;
    esac ;;
  "pr list")
{_arm(pr_list)}
    ;;
esac

if [ "$1" = api ]; then
  api_path=""
  for arg in "${{@:2}}"; do
    case "$arg" in
      --*) ;;
      *) api_path="$arg"; break ;;
    esac
  done
  case "$api_path" in
{api_arms}
  esac
fi

echo "gh: unexpected invocation: $*" >&2
exit 9
"""


def _api_path(suffix):
    return f"repos/{_REPO}/{suffix}"


def all_ok_api(annotation=None, comments=None, prs=None):
    """The `gh api` table for a run whose every read succeeds.

    The associated-PR read returns two PRs so the comments loop iterates more than
    once; with an empty list the whole comments block would be dead code, and with one
    entry a merge would be indistinguishable from keeping the first response.
    """
    return {
        _api_path(f"check-runs/{_FAILED_JOB}/annotations"): annotation or Response(0, _ANNOTATION),
        _api_path(f"check-runs/{_OTHER_FAILED_JOB}/annotations"): Response(0, _OTHER_ANNOTATION),
        _api_path(f"check-runs/{_THIRD_FAILED_JOB}/annotations"): Response(0, _ANNOTATION),
        _api_path(f"commits/{_HEAD_SHA}/pulls"): prs or Response(0, _PRS_JSON),
        _api_path(f"issues/{_PR_NUMBER}/comments"): comments or Response(0, _COMMENTS_JSON),
        _api_path(f"issues/{_OTHER_PR_NUMBER}/comments"): Response(0, _OTHER_COMMENTS_JSON),
    }


def prereq_stubs(apt_status=0, provides=()):
    """A stub dir for the prerequisite step: sudo, a logging apt-get, and its effects.

    `provides` names the tools the simulated install makes resolvable afterwards, so a
    test can distinguish "apt was asked for gh" from "gh became usable".
    """
    stub_dir = sandbox("prereq-stubs")
    write_stub(stub_dir, "sudo", '#!/usr/bin/env bash\nexec "$@"\n')
    materialise = "\n".join(
        f"""  if [ "$arg" = {tool} ]; then
    printf '#!/usr/bin/env bash\\necho "{tool} version 2.45.0"\\n' > "$STUB_DIR/{tool}"
    chmod +x "$STUB_DIR/{tool}"
  fi"""
        for tool in provides
    )
    write_stub(
        stub_dir,
        "apt-get",
        f"""#!/usr/bin/env bash
STUB_DIR="$(dirname "$0")"
for arg in "$@"; do printf '%s\\n' "$arg" >> "$STUB_DIR/apt-argv.log"; done
for arg in "$@"; do
{materialise if materialise else "  :"}
done
exit {apt_status}
""",
    )
    return stub_dir


def apt_log(stub_dir):
    path = stub_dir / "apt-argv.log"
    return path.read_text(encoding="utf-8").split() if path.is_file() else []


# ---------------------------------------------------------------- prerequisite step


def test_prereq_step_installs_the_missing_tool_and_then_succeeds():
    """A runner image without gh must install it, not merely detect its absence.

    The apt-get stub records its argument list and makes gh resolvable afterwards, so
    the step takes the install branch and then passes its own verification loop. This
    pins the fix itself: gh has to be named in the install request, as its own argv
    word, and jq — already present — must not be reinstalled.
    """
    stub_dir = prereq_stubs(provides=("gh",))
    completed, _ = run_step(step_body(_PREREQ_STEP), stub_dir)
    requested = apt_log(stub_dir)

    check("install: gh is requested from the package manager", "gh" in requested)
    check("install: jq, already present, is not reinstalled", "jq" not in requested)
    check("install: step succeeds once the install provides gh", completed.returncode == 0)
    check("install: the resolved versions are logged", "gh version 2.45.0" in completed.stdout)


def test_prereq_step_makes_no_apt_call_when_both_tools_are_present():
    """With jq and gh both resolvable the step touches no package manager.

    The job runs on a pool where an image may already carry both tools, so the step has
    to be idempotent rather than reinstall on every dispatch.
    """
    stub_dir = prereq_stubs()
    write_stub(stub_dir, "gh", '#!/usr/bin/env bash\necho "gh version 2.45.0"\n')
    completed, _ = run_step(step_body(_PREREQ_STEP), stub_dir)

    check("idempotent: no apt-get invocation", apt_log(stub_dir) == [])
    check("idempotent: step succeeds", completed.returncode == 0)


def test_prereq_step_fails_when_the_install_does_not_provide_gh():
    """An install that reports success but yields no gh must still stop the job.

    gh is needed by the pre-fetch and by `gh pr create` in "Publish fix", and both fail
    quietly and late — the second only after the fix branch has been pushed. So the
    verification loop has to exit nonzero naming the tool, and leave the Zulip summary
    step a file that says the agent never started.
    """
    stub_dir = prereq_stubs()
    completed, root = run_step(step_body(_PREREQ_STEP), stub_dir)
    errors = log_annotations(completed.stdout, "error")

    check("install-no-gh: step fails", completed.returncode != 0)
    check("install-no-gh: exactly one error annotation", len(errors) == 1)
    check("install-no-gh: the annotation names gh",
          errors and errors[0].startswith("gh is required"))
    check("install-no-gh: a summary is left for the Zulip report",
          "INVESTIGATION_ONLY" in summary_text(root))
    check("install-no-gh: the summary names the missing tool",
          "gh is not installed" in summary_text(root))


def test_prereq_step_reports_the_missing_tool_even_when_apt_itself_fails():
    """A package manager error must not pre-empt the step's own diagnosis.

    `apt-get install` exiting nonzero is the likeliest shape of this failure (no such
    package, mirror down). Under the default `bash -e` an unguarded call would abort the
    step right there, leaving only apt's message in the log and never reaching the
    annotation that names which tool the job cannot run without.
    """
    stub_dir = prereq_stubs(apt_status=100)
    completed, _ = run_step(step_body(_PREREQ_STEP), stub_dir)
    errors = log_annotations(completed.stdout, "error")
    warnings = log_annotations(completed.stdout)

    check("apt-fails: step fails", completed.returncode != 0)
    check("apt-fails: the error annotation still names gh",
          any("gh is required" in error for error in errors))
    check("apt-fails: the apt failure is reported too",
          any("apt-get install" in warning for warning in warnings))


# --------------------------------------------------------------------- context step


def test_context_step_collects_every_datum_when_all_calls_succeed():
    """The success path writes real content to every file the prompt promises.

    The agent is told to read eight files; a file the step never writes is a placeholder
    it cannot even detect. The gh argv log is asserted too, so a dropped `--repo`, a
    dropped `--json` field or a changed `pr list` filter fails here rather than in
    production.
    """
    stub_dir = sandbox("ctx-stubs")
    write_stub(stub_dir, "gh", gh_stub(api=all_ok_api()))
    completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)

    ctx = context_dir(root)
    sent = argv_log(stub_dir)
    check("all-ok: step succeeds", completed.returncode == 0)
    check("all-ok: no warning annotation", log_annotations(completed.stdout) == [])
    check("all-ok: the degradation ledger exists and is empty", degraded(root) == [])
    for name in (
        "degraded.txt",
        "run.json",
        "jobs.json",
        "logs.txt",
        "annotations.json",
        "associated-prs.json",
        "pr-comments.json",
        "existing-fix-prs.json",
    ):
        check(f"all-ok: {name} is produced", (ctx / name).is_file())

    run_meta = json.loads((ctx / "run.json").read_text(encoding="utf-8"))
    check("all-ok: run.json is the fetched metadata", run_meta.get("headSha") == _HEAD_SHA)
    check("all-ok: the failed job's annotation is kept", "expected 1 but was 2" in
          (ctx / "annotations.json").read_text(encoding="utf-8"))
    check("all-ok: the failed job's logs are kept", "FooTest.bar" in
          (ctx / "logs.txt").read_text(encoding="utf-8"))
    check("all-ok: the associated PR is kept", _PR_NUMBER in
          (ctx / "associated-prs.json").read_text(encoding="utf-8"))
    check("all-ok: that PR's comments are kept", "Test count gate" in
          (ctx / "pr-comments.json").read_text(encoding="utf-8"))
    check("all-ok: the result directory for the sentinel exists",
          (root / "runner-temp" / "ci-fix").is_dir())
    check("all-ok: the directory contract reaches the later steps", "CI_FIX_RESULT_FILE=" in
          (root / "github-env").read_text(encoding="utf-8"))
    check("all-ok: no scratch file is left behind",
          not any(p.name.endswith(".ndjson") for p in ctx.iterdir())
          and not any(p.name.startswith("tmp") for p in root.iterdir() if p.is_file()))

    # Three `gh run view` reads plus `gh pr list`; the `gh api` reads carry the
    # repository in their path instead. Counting rather than testing membership: the
    # log concatenates every invocation, so one scoped read would satisfy `in`.
    check("all-ok: every gh read is scoped to the repository",
          sent.count(f"--repo\n{_REPO}") == 4)
    check("all-ok: the run-view read asks for every field the prompt promises",
          _RUN_FIELDS in sent)
    check("all-ok: the open-PR read asks for the head branch the agent checks out",
          _PR_LIST_FIELDS in sent)
    check("all-ok: open fix PRs are listed for duplicate detection",
          "--base\ndevelop\n--state\nopen" in sent and "--limit\n50" in sent)


def test_context_step_aborts_when_run_metadata_is_unavailable():
    """Losing the run metadata must stop the job instead of starting a blind agent.

    Without the head SHA and the run's conclusion the agent cannot tell which commit to
    compare against or which jobs failed, and every derived fetch keys on it. The step
    has to exit nonzero, name the run, record the file as degraded, carry the failed
    call's own stderr into the log, and leave a truthful summary behind.
    """
    stub_dir = sandbox("ctx-stubs")
    write_stub(stub_dir, "gh", gh_stub(run=Response(1, "", "gh: HTTP 404: Not Found")))
    completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)
    errors = log_annotations(completed.stdout, "error")
    warnings = log_annotations(completed.stdout)

    check("lost-run: step fails", completed.returncode != 0)
    check("lost-run: the error names the run", any(_RUN_ID in e for e in errors))
    check("lost-run: run.json is recorded as degraded", degraded(root) == ["run.json"])
    check("lost-run: the warning carries the exit status", any("exit 1" in w for w in warnings))
    check("lost-run: the warning carries gh's own diagnosis",
          any("404" in w for w in warnings))
    check("lost-run: the derived reads are not attempted",
          not (context_dir(root) / "existing-fix-prs.json").exists())
    check("lost-run: a summary is left for the Zulip report",
          "INVESTIGATION_ONLY" in summary_text(root))


def test_context_step_reports_a_missing_gh_as_exit_127_and_stops():
    """The incident itself: no gh on PATH when the context step runs.

    Status 127 is what tells an operator the binary is missing rather than the token
    being wrong, and the captured diagnosis names the binary. No gh stub is written, so
    the toolbox PATH genuinely lacks it.
    """
    completed, root = run_step(step_body(_CONTEXT_STEP), sandbox("empty-stubs"))
    warnings = log_annotations(completed.stdout)

    check("ctx-no-gh: step fails", completed.returncode != 0)
    check("ctx-no-gh: the exit status reaches the annotation",
          any("exit 127" in w for w in warnings))
    check("ctx-no-gh: the diagnosis names the binary that is missing",
          any("gh" in w and "run.json" in w for w in warnings))
    check("ctx-no-gh: run.json holds the documented placeholder",
          (context_dir(root) / "run.json").read_text(encoding="utf-8").strip() == "{}")


def test_context_step_aborts_when_the_run_metadata_body_is_unusable():
    """A body that arrives with exit 0 but is unusable must abort deliberately.

    A 502 served as HTML, a proxy interstitial and a truncated response all exit 0, so
    the resilience path never runs while every later read of the body fails. Under
    `bash -e` an unguarded read turns that into a bare nonzero exit with no annotation.
    The two gates report different causes, so both messages are pinned: an unparsable
    body is an unavailable read, and a parsable body without a usable head SHA is a
    metadata problem — the second matters because `//` substitutes only for null, so an
    empty string would key the associated-PR read on `commits//pulls`.
    """
    cases = (
        ("html", "<html>502 Bad Gateway</html>", "is unavailable"),
        ("empty", "", "is unavailable"),
        ("no-sha", '{"conclusion":"failure"}', "head SHA"),
        ("blank-sha", '{"conclusion":"failure","headSha":""}', "head SHA"),
        ("null-sha", '{"conclusion":"failure","headSha":null}', "head SHA"),
    )
    for label, body, expected in cases:
        stub_dir = sandbox("ctx-stubs")
        write_stub(stub_dir, "gh", gh_stub(run=Response(0, body)))
        completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)
        errors = log_annotations(completed.stdout, "error")

        check(f"unusable-{label}: step fails", completed.returncode != 0)
        check(f"unusable-{label}: the failure is annotated, not silent",
              any(expected in error for error in errors))
        check(f"unusable-{label}: a summary is left for the Zulip report",
              "INVESTIGATION_ONLY" in summary_text(root))


def test_context_step_warns_and_continues_when_one_datum_is_lost():
    """A single failed fetch degrades that file only, loudly, and the job goes on.

    The job list is the datum dropped here. Its placeholder is what the agent reads, so
    the ledger has to name it; and because no failed job can be derived from a
    placeholder, the step also has to say that no annotations will be fetched — which
    otherwise looks identical to a run in which nothing failed.
    """
    stub_dir = sandbox("ctx-stubs")
    write_stub(stub_dir, "gh", gh_stub(jobs=Response(4, "", "gh: authentication required"),
                                       api=all_ok_api()))
    completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)
    ctx = context_dir(root)
    warnings = log_annotations(completed.stdout)

    check("lost-jobs: step still succeeds", completed.returncode == 0)
    check("lost-jobs: jobs.json is recorded as degraded", degraded(root) == ["jobs.json"])
    check("lost-jobs: the warning names the file", any("jobs.json" in w for w in warnings))
    check("lost-jobs: the unauthenticated status is reported", any("exit 4" in w for w in warnings))
    check("lost-jobs: the placeholder is the documented one",
          (ctx / "jobs.json").read_text(encoding="utf-8").strip() == '{"jobs":[]}')
    check("lost-jobs: the absence of readable jobs is called out",
          any("no unsuccessful job" in w for w in warnings))
    check("lost-jobs: run metadata is still real",
          json.loads((ctx / "run.json").read_text(encoding="utf-8")).get("headSha") == _HEAD_SHA)
    check("lost-jobs: the surviving reads still ran",
          (ctx / "existing-fix-prs.json").is_file())


def test_context_step_merges_annotations_from_every_failed_job():
    """A matrix run with two failed legs, one of whose annotation reads is refused.

    The step must query the failed jobs only, keep the response that arrived, name the
    refused job, and merge what it kept into one flat array — not keep just the first
    response. An HTTP error body is valid JSON, so the refused read must contribute
    nothing: {"message": "Not Found"} in annotations.json would read to the agent as one
    of the failure's findings.
    """
    stub_dir = sandbox("ctx-stubs")
    api = all_ok_api()
    api[_api_path(f"check-runs/{_OTHER_FAILED_JOB}/annotations")] = Response(
        1, '{"message":"Not Found","status":"404"}', "gh: HTTP 404"
    )
    # Job 1001 answers with the same body as 777, so a merge that kept only the first
    # response would still hold one annotation; the message set is what separates them.
    api[_api_path(f"check-runs/{_THIRD_FAILED_JOB}/annotations")] = Response(0, _OTHER_ANNOTATION)
    write_stub(stub_dir, "gh", gh_stub(api=api, jobs_json=_JOBS_JSON))
    completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)

    kept = json.loads((context_dir(root) / "annotations.json").read_text(encoding="utf-8"))
    warnings = log_annotations(completed.stdout)
    sent = argv_log(stub_dir)
    check("multi-job: step still succeeds", completed.returncode == 0)
    check("multi-job: both surviving pages are merged, not just the first", len(kept) == 2)
    check("multi-job: every message that arrived is present",
          {annotation["message"] for annotation in kept}
          == {"expected 1 but was 2", "windows-only NPE"})
    check("multi-job: the error body is not merged in", "Not Found" not in json.dumps(kept))
    check("multi-job: the refused job is named", any(_OTHER_FAILED_JOB in w for w in warnings))
    check("multi-job: gh's own diagnosis reaches the annotation",
          any("404" in w for w in warnings if _OTHER_FAILED_JOB in w))
    check("multi-job: it is recorded in the ledger under the file it degrades",
          degraded(root) == [f"annotations.json (job {_OTHER_FAILED_JOB})"])
    check("multi-job: the job that arrived is not reported as lost",
          not any(f"job {_FAILED_JOB}" in w for w in warnings))
    check("multi-job: the green job is never queried", _GREEN_JOB not in sent)
    # Keyed on the paths rather than counted, so the check survives a fixture change
    # and covers both loops: a floor was satisfied by the annotations loop alone.
    paginated = re.findall(r"--paginate\n(\S+)", sent)
    check("multi-job: every per-job annotation read is paginated",
          sum("check-runs" in path for path in paginated) == 3)
    check("multi-job: every PR-comment read is paginated",
          sum(path.endswith("/comments") for path in paginated) == 2)


def test_context_step_keeps_an_api_error_body_out_of_the_pr_comments():
    """The comments loop carries the same error-body guard as the annotations loop.

    Both append `gh api` stdout, and an HTTP error body is valid JSON, so a 404 on one
    PR has to produce a warning and an empty array rather than a comment whose text
    reads {"message": "Not Found"} — the gate comments are what the agent mines for
    test-count and coverage numbers.
    """
    stub_dir = sandbox("ctx-stubs")
    api = all_ok_api(comments=Response(1, '{"message":"Not Found","status":"404"}', "gh: HTTP 404"))
    write_stub(stub_dir, "gh", gh_stub(api=api))
    completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)

    comments = (context_dir(root) / "pr-comments.json").read_text(encoding="utf-8")
    warnings = log_annotations(completed.stdout)
    check("lost-comments: step still succeeds", completed.returncode == 0)
    check("lost-comments: the error body is not merged in", "Not Found" not in comments)
    check("lost-comments: the other PR's comments are kept", "Coverage gate" in comments)
    check("lost-comments: the PR is named", any(f"#{_PR_NUMBER}" in w for w in warnings))
    check("lost-comments: gh's own diagnosis reaches the annotation",
          any("404" in w for w in warnings if f"#{_PR_NUMBER}" in w))
    check("lost-comments: it is recorded in the ledger under the file it degrades",
          degraded(root) == [f"pr-comments.json (PR #{_PR_NUMBER})"])


def test_context_step_reports_pages_that_arrive_unparsable():
    """A read that succeeds with a non-JSON body must be reported, not swallowed.

    `jq -s` rejecting the accumulated pages was the one surviving silent path: it used
    to write an empty array with nothing in the log, which is the failure mode this
    whole change exists to remove.
    """
    for label, body, reported in (
        ("html", "<html>502 Bad Gateway</html>", True),
        # A literal null parses, so it is not reported; `add // []` is what keeps it
        # from becoming the value of a file the prompt promises is an array.
        ("null", "null", False),
    ):
        stub_dir = sandbox("ctx-stubs")
        api = {path: Response(0, body) for path in all_ok_api()
               if path.endswith("/annotations")}
        api.update({path: response for path, response in all_ok_api().items()
                    if not path.endswith("/annotations")})
        write_stub(stub_dir, "gh", gh_stub(api=api))
        completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)

        warnings = log_annotations(completed.stdout)
        check(f"bad-page-{label}: step still succeeds", completed.returncode == 0)
        check(f"bad-page-{label}: annotations.json holds the promised array",
              (context_dir(root) / "annotations.json").read_text(encoding="utf-8").strip()
              == "[]")
        if reported:
            check(f"bad-page-{label}: the unparsable page is reported",
                  any("annotations.json" in w and "valid JSON" in w for w in warnings))
            check(f"bad-page-{label}: it is recorded in the ledger",
                  "annotations.json" in degraded(root))
        else:
            check(f"bad-page-{label}: a page that parsed is not called degraded",
                  degraded(root) == [])


def test_context_step_extracts_the_run_id_from_realistic_url_shapes():
    """The run URL is a free-form, human-supplied dispatch input.

    A trailing slash, a query string, and the job-page URL an operator copies from the
    address bar must all still key the reads on the run id, because every fetch and
    every annotation names whatever this parse produced. The stub refuses any other id,
    so a misparse surfaces as a lost-metadata abort rather than passing silently.
    """
    body = step_body(_CONTEXT_STEP)
    base = f"https://github.com/{_REPO}/actions/runs/{_RUN_ID}"
    for label, url in (
        ("plain", base),
        ("trailing slash", base + "/"),
        ("query string", base + "?pr=1291"),
        ("job page", base + "/job/98765"),
        ("attempt page", base + "/attempts/2"),
    ):
        stub_dir = sandbox("ctx-stubs")
        write_stub(stub_dir, "gh", gh_stub(api=all_ok_api()))
        completed, _ = run_step(body, stub_dir, env_extra={"FAILED_RUN_URL": url})
        check(f"url: a {label} URL still names the run", completed.returncode == 0)


def test_context_step_rejects_a_run_url_with_no_numeric_run_id():
    """A malformed URL must fail with its own message, and must not be echoed back.

    The input reaches `::error::` and `::warning::` lines, and the runner parses those,
    so a newline in the value would split one line into a second, caller-controlled
    workflow command. Rejecting the value up front closes that, and reporting the shape
    rather than the value keeps the injection out of the log.
    """
    body = step_body(_CONTEXT_STEP)
    for label, url in (
        ("no run segment", "https://github.com/JetBrains/youtrackdb/actions"),
        ("non-numeric", f"https://github.com/{_REPO}/actions/runs/not-a-run"),
        ("empty", ""),
        ("injected newline", f"https://github.com/{_REPO}/actions/runs/1\n::error::forged"),
    ):
        stub_dir = sandbox("ctx-stubs")
        write_stub(stub_dir, "gh", gh_stub(api=all_ok_api()))
        completed, root = run_step(body, stub_dir, env_extra={"FAILED_RUN_URL": url})
        errors = log_annotations(completed.stdout, "error")

        check(f"bad-url-{label}: step fails", completed.returncode != 0)
        check(f"bad-url-{label}: exactly one error annotation", len(errors) == 1)
        check(f"bad-url-{label}: it reports the URL shape",
              errors and "failed_run_url" in errors[0])
        check(f"bad-url-{label}: the value is not echoed back",
              "forged" not in completed.stdout and "not-a-run" not in completed.stdout)
        check(f"bad-url-{label}: a summary is left for the Zulip report",
              "INVESTIGATION_ONLY" in summary_text(root))


def test_context_step_keeps_a_hostile_stderr_inside_one_annotation():
    """Captured stderr reaches the public run log, so it is sanitised before it does.

    A workflow annotation is one line, so an embedded newline would forge a second
    command, and its body is percent-decoded by the runner, so a literal %0A would add
    a line inside the message. A kilobyte of response body would bury the message. The
    request URL is elided as well: the log-download hop carries a signed one.
    """
    hostile = (
        "gh: HTTP 500\n::error::forged annotation\n"
        "percent %0A escape\n"
        "https://pipelines.actions.githubusercontent.com/logs?sig=SECRETSIGNATURE&se=2026\n"
        "https://x-access-token:SECRETCREDENTIAL@github.com/JetBrains/youtrackdb\n"
        + "x" * 2000
    )
    stub_dir = sandbox("ctx-stubs")
    write_stub(stub_dir, "gh", gh_stub(jobs=Response(1, "", hostile), api=all_ok_api()))
    completed, _ = run_step(step_body(_CONTEXT_STEP), stub_dir)
    warnings = log_annotations(completed.stdout)

    # The lost job list also raises the no-failed-job warning, so select the one
    # carrying the hostile text rather than counting every warning.
    reported = [w for w in warnings if "jobs.json" in w]
    check("hostile-stderr: the failure is reported exactly once", len(reported) == 1)
    check("hostile-stderr: no forged annotation is emitted",
          log_annotations(completed.stdout, "error") == [])
    check("hostile-stderr: the message stays inside one capped line",
          reported and len(reported[0]) <= 480)
    check("hostile-stderr: a literal percent escape is neutralised",
          reported and "%0A" not in reported[0])
    check("hostile-stderr: the signed query string is elided",
          reported and "SECRETSIGNATURE" not in reported[0])
    check("hostile-stderr: URL userinfo is elided",
          reported and "SECRETCREDENTIAL" not in reported[0])
    check("hostile-stderr: gh's own status still reaches the log",
          reported and "exit 1" in reported[0])


def test_context_step_degrades_each_optional_datum_on_its_own():
    """Every read the job can continue without degrades that one file, and says so.

    The three files here are the ones the agent can work around, and each has a
    documented placeholder and a ledger entry the prompt tells it to look for — Step 0c
    branches on `existing-fix-prs.json` being listed. A read failing must leave the
    other files intact and the step exiting 0.
    """
    body = step_body(_CONTEXT_STEP)
    cases = (
        ("logs.txt", "No failed-job logs available.", dict(logs=Response(1, "", "gh: HTTP 500"))),
        ("associated-prs.json", "[]",
         dict(api=all_ok_api(prs=Response(1, '{"message":"Not Found"}', "gh: HTTP 404")))),
        ("existing-fix-prs.json", "[]", dict(pr_list=Response(1, "", "gh: HTTP 403"))),
    )
    for name, placeholder, failing in cases:
        stub_dir = sandbox("ctx-stubs")
        arguments = dict(api=all_ok_api())
        arguments.update(failing)
        write_stub(stub_dir, "gh", gh_stub(**arguments))
        completed, root = run_step(body, stub_dir)
        ctx = context_dir(root)

        check(f"degrade-{name}: the step still succeeds", completed.returncode == 0)
        check(f"degrade-{name}: the documented placeholder lands",
              (ctx / name).read_text(encoding="utf-8").strip() == placeholder)
        check(f"degrade-{name}: the ledger names it", degraded(root) == [name])
        check(f"degrade-{name}: a warning names it",
              any(name in warning for warning in log_annotations(completed.stdout)))
        check(f"degrade-{name}: the run metadata is untouched",
              json.loads((ctx / "run.json").read_text(encoding="utf-8")).get("headSha")
              == _HEAD_SHA)


def test_context_step_degrades_a_body_that_arrives_unparsable_with_exit_zero():
    """An HTML error page served with a 200 must degrade the file, not pass as data.

    The prompt tells the agent that a file the ledger does not name arrived intact, so a
    body that exits 0 and is not JSON has to reach the ledger like any other failure.
    For the job list the alternative is actively misleading: the step would report that
    the run lists no failed job, on a run it was dispatched because it failed.
    """
    body = step_body(_CONTEXT_STEP)
    cases = (
        ("jobs.json", '{"jobs":[]}', dict(jobs=Response(0, "<html>502 Bad Gateway</html>"))),
        ("associated-prs.json", "[]",
         dict(api=all_ok_api(prs=Response(0, "<html>301 Moved</html>")))),
    )
    for name, placeholder, failing in cases:
        stub_dir = sandbox("ctx-stubs")
        arguments = dict(api=all_ok_api())
        arguments.update(failing)
        write_stub(stub_dir, "gh", gh_stub(**arguments))
        completed, root = run_step(body, stub_dir)

        check(f"unparsable-{name}: the step still succeeds", completed.returncode == 0)
        check(f"unparsable-{name}: the documented placeholder lands",
              (context_dir(root) / name).read_text(encoding="utf-8").strip() == placeholder)
        check(f"unparsable-{name}: the ledger names it", degraded(root) == [name])
        check(f"unparsable-{name}: the warning says the body was unusable",
              any(name in w and "not valid JSON" in w
                  for w in log_annotations(completed.stdout)))


def test_every_github_read_is_time_bounded():
    """No read may run unbounded: a hung one holds a self-hosted runner for hours.

    The job's timeout is 360 or 720 minutes and gh applies no default of its own, so an
    unbounded read is the difference between a degraded file and a whole runner lost.
    Asserted against the step source because the bound is a property of every call site,
    and a new read added without one is exactly the regression worth catching.
    """
    workflow = yaml.safe_load(_WORKFLOW.read_text(encoding="utf-8"))
    context = next(
        step["run"]
        for step in workflow["jobs"]["fix-ci-failure"]["steps"]
        if step.get("name") == _CONTEXT_STEP
    )
    # Join continuations so a call split over several lines reads as one.
    joined = context.replace("\\\n", " ")
    reads = [
        line
        for line in joined.splitlines()
        # Comments mention the commands by name, so skip them rather than the quoting.
        if re.search(r"\bgh (run|api|pr) ", line) and not line.lstrip().startswith("#")
    ]
    check("timeout: the step issues the reads under test", len(reads) >= 6)
    for read in reads:
        check(f"timeout: bounded -> {read.strip()[:60]}", "timeout \"$" in read)


def test_context_step_reads_annotations_for_a_job_that_timed_out():
    """A killed leg reports `timed_out`, not `failure`, and must still be read.

    This pipeline's Windows leg is regularly killed by GitHub's hosted job cap, and a
    matrix leg dropped when a sibling fails reports `cancelled`. Selecting only
    `failure` would request no annotations at all for such a run and leave the ledger
    clean, which is precisely the state the prompt tells the agent means "nothing
    failed" — on a run the agent was dispatched because it failed.
    """
    stub_dir = sandbox("ctx-stubs")
    api = all_ok_api()
    write_stub(stub_dir, "gh", gh_stub(api=api, jobs_json=_TIMED_OUT_JOBS_JSON))
    completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)

    sent = argv_log(stub_dir)
    kept = json.loads((context_dir(root) / "annotations.json").read_text(encoding="utf-8"))
    check("timed-out: step succeeds", completed.returncode == 0)
    check("timed-out: the killed leg is read", f"check-runs/{_FAILED_JOB}/annotations" in sent)
    check("timed-out: the cancelled leg is read",
          f"check-runs/{_OTHER_FAILED_JOB}/annotations" in sent)
    check("timed-out: the green leg is still not read",
          f"check-runs/{_GREEN_JOB}/annotations" not in sent)
    check("timed-out: a job still running is not read as unsuccessful",
          "check-runs/2002/annotations" not in sent)
    check("timed-out: a job with no id is skipped rather than read as null",
          "check-runs/null/annotations" not in sent
          and not any("job null" in line for line in degraded(root)))
    check("timed-out: whatever they reported is kept", len(kept) == 2)
    check("timed-out: nothing is falsely reported as degraded", degraded(root) == [])
    check("timed-out: the conclusions reach the agent",
          "timed_out" in (context_dir(root) / "jobs.json").read_text(encoding="utf-8"))

    # The shape the prompt's paragraph describes: the leg was killed before it could
    # report, so the read succeeds with nothing in it. That emptiness must not reach
    # the ledger, because a ledger entry would tell the agent the read failed.
    stub_dir = sandbox("ctx-stubs")
    empty = {
        path: (Response(0, "[]") if path.endswith("/annotations") else response)
        for path, response in all_ok_api().items()
    }
    write_stub(stub_dir, "gh", gh_stub(api=empty, jobs_json=_TIMED_OUT_JOBS_JSON))
    completed, root = run_step(step_body(_CONTEXT_STEP), stub_dir)
    check("timed-out-empty: an empty report is the datum, not a degradation",
          degraded(root) == [])
    check("timed-out-empty: the promised array is still written",
          (context_dir(root) / "annotations.json").read_text(encoding="utf-8").strip() == "[]")


def test_context_step_degrades_a_read_that_hangs():
    """A read that never returns must be killed, degrade one file, and be named.

    Unbounded, one hung read holds a self-hosted runner for the job's whole 360- or
    720-minute timeout. `timeout` writes nothing to stderr, so the annotation has to
    name the cause rather than print a bare exit code.
    """
    body = step_body(_CONTEXT_STEP).replace("READ_TIMEOUT=120", "READ_TIMEOUT=2")
    stub_dir = sandbox("ctx-stubs")
    (stub_dir / "sleep").symlink_to(shutil.which("sleep"))
    # Hang on the job-list read only, by intercepting it ahead of the dispatch table.
    hanging = gh_stub(api=all_ok_api()).replace(
        'json_fields=""',
        'for a in "$@"; do case "$a" in *jobs*) sleep 60 ;; esac; done\njson_fields=""',
        1,
    )
    write_stub(stub_dir, "gh", hanging)
    completed, root = run_step(body, stub_dir)
    warnings = log_annotations(completed.stdout)

    check("hung-read: step still succeeds", completed.returncode == 0)
    check("hung-read: the hung read's file is degraded", degraded(root) == ["jobs.json"])
    check("hung-read: the timeout is named, not just numbered",
          any("timed out" in warning for warning in warnings))
    check("hung-read: the surviving reads still ran",
          (context_dir(root) / "existing-fix-prs.json").is_file())


def test_read_timeouts_actually_bound_a_read():
    """`timeout 0` means no limit, so the values need a floor, not just a wrapper.

    The structural check asserts every read is wrapped; a one-character edit to either
    value would restore the unbounded hang with the wrapper still in place.
    """
    body = step_body(_CONTEXT_STEP)
    for name in ("READ_TIMEOUT", "LOG_READ_TIMEOUT"):
        match = re.search(rf"^\s*{name}=(\d+)$", body, re.MULTILINE)
        check(f"timeout: {name} is declared", match is not None)
        if match:
            check(f"timeout: {name} bounds the read", 0 < int(match.group(1)) <= 900)


def test_zulip_summary_confines_a_hostile_dispatch_input_to_one_line():
    """The dispatch inputs reach a channel report, so a newline must not author it.

    "Prepare Zulip summary" is always() gated, so it also runs on the path where the
    context step rejected the run URL as malformed — the one case where the value is
    known to be hostile. A newline in it would otherwise let the caller write the body
    of a report the channel trusts, channel-wide mention included.
    """
    stub_dir = sandbox("zulip-stubs")
    completed, root = run_step(
        step_body("Prepare Zulip summary"),
        stub_dir,
        env_extra={
            "FAILED_WORKFLOW_NAME": "nightly\n@**all** the agent approved this fix",
            "FAILED_RUN_URL": "https://example.invalid/runs/1\n## Status\nFIX_READY",
            "TRIGGER_SHA": _HEAD_SHA,
        },
    )
    check("zulip: the step succeeds", completed.returncode == 0)
    written = re.search(r"zulip_message_file=(\S+)",
                        (root / "github-output").read_text(encoding="utf-8"))
    check("zulip: the message file path is exported", written is not None)
    message = pathlib.Path(written.group(1)).read_text(encoding="utf-8") if written else ""
    check("zulip: the forged mention cannot start a line",
          not any(line.startswith("@**all**") for line in message.splitlines()))
    check("zulip: the forged status heading cannot start a line",
          not any(line.startswith("## Status") for line in message.splitlines()))
    # Compared against a benign run rather than against a fixed number, so the check
    # states the invariant — hostile input adds no line — and survives a template edit.
    benign_completed, benign_root = run_step(
        step_body("Prepare Zulip summary"),
        sandbox("zulip-stubs"),
        env_extra={"FAILED_WORKFLOW_NAME": "nightly", "TRIGGER_SHA": _HEAD_SHA},
    )
    benign_written = re.search(r"zulip_message_file=(\S+)",
                               (benign_root / "github-output").read_text(encoding="utf-8"))
    benign = (pathlib.Path(benign_written.group(1)).read_text(encoding="utf-8")
              if benign_written else "")
    check("zulip: the benign run is the baseline", benign_completed.returncode == 0 and benign)
    check("zulip: a hostile input adds no line to the report",
          message.count("\n") == benign.count("\n"))


def test_summary_file_is_written_where_the_zulip_step_reads_it():
    """The abort summary and the Zulip report must resolve to the same path.

    Three step bodies hold that path separately — the two aborts write it, "Prepare
    Zulip summary" reads it — and the tests redirect it, so nothing else would catch a
    typo in one default. A mismatch restores the misreport this change removed: the
    channel would be told an agent that never started "completed without writing a
    structured summary".
    """
    workflow = yaml.safe_load(_WORKFLOW.read_text(encoding="utf-8"))
    bodies = {
        step.get("name"): step["run"]
        for step in workflow["jobs"]["fix-ci-failure"]["steps"]
        if "run" in step
    }
    default = '"${FIX_AGENT_SUMMARY_FILE:-/tmp/fix-agent-summary.md}"'
    for name in (_PREREQ_STEP, _CONTEXT_STEP, "Prepare Zulip summary"):
        check(f"summary-path: {name} resolves the same default",
              name in bodies and default in bodies[name])

    # Both aborts hand the same document shape to the same reader, so the Zulip post
    # looks like every other one.
    sections = ("## Status", "## Problem", "## Root Cause", "## Fix Applied", "## PR")
    for name in (_PREREQ_STEP, _CONTEXT_STEP):
        body = bodies.get(name, "")
        check(f"summary-shape: {name} writes the documented sections",
              all(section in body for section in sections))


def test_placeholder_contract_matches_what_the_prompt_teaches():
    """Every placeholder the step writes must be one the prompt tells the agent about.

    The agent's only way to reason about a degraded file is the list in Step 0a. The
    step and the prompt hold that list separately, so nothing but this check keeps a
    new placeholder from being invisible to the reader it exists for.
    """
    workflow = yaml.safe_load(_WORKFLOW.read_text(encoding="utf-8"))
    steps = workflow["jobs"]["fix-ci-failure"]["steps"]
    context = next(step["run"] for step in steps if step.get("name") == _CONTEXT_STEP)
    prompt = next(step["run"] for step in steps if step.get("name") == "Build agent prompt")

    # The placeholder is the second argument of every fetch_into call.
    written = set(re.findall(r"fetch_into\s+\"[^\"]+\"\s+'([^']*)'", context))
    check("contract: placeholders are discoverable in the step", len(written) >= 3)
    for placeholder in sorted(written):
        check(f"contract: the prompt documents the placeholder {placeholder!r}",
              placeholder in prompt)
    check("contract: the prompt points the agent at the ledger", "degraded.txt" in prompt)

    # Every ledger label must start with the name of the file it degrades: that is what
    # the prompt tells the agent a line of degraded.txt means. fetch_into derives its
    # label from the destination path, so only the two loops carry a literal one.
    literal_labels = re.findall(r"fetch_append \"[^\"]+\" \"([^\" (]+)", context)
    literal_labels += re.findall(r"merge_pages \"[^\"]+\" \"[^\"]+\" \"([^\"]+)\"", context)
    check("contract: the loops label their entries", len(literal_labels) >= 4)
    for label in sorted(set(literal_labels)):
        check(f"contract: the ledger label {label!r} is a documented context file",
              label.endswith((".json", ".txt")) and label in prompt)


def main():
    if yaml is None:
        print("PyYAML is required to run these tests (pip install pyyaml)")
        return 1
    if not _WORKFLOW.is_file():
        print(f"workflow not found: {_WORKFLOW}")
        return 1
    for tool, present in (("jq", shutil.which("jq")), ("bash", _BASH)):
        if not present:
            print(f"{tool} is required to run these tests")
            return 1

    # Auto-discovery, like the sibling harnesses: adding a test needs no manual
    # registration.
    for name, test in sorted(globals().items()):
        if not name.startswith("test_") or not callable(test):
            continue
        print(f"{name}:")
        try:
            test()
        except Exception as error:  # noqa: BLE001 - a crash is a failed check
            # Deliberately broad: a renamed step, a hung step body or a harness bug
            # must be reported as one failed check with the rest of the suite still
            # running, not abort the run and hide every later result.
            check(f"{name} could run at all ({type(error).__name__}: {error})", False)

    if _failures:
        print(f"\n{len(_failures)} check(s) failed:")
        for name in _failures:
            print(f"  - {name}")
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
