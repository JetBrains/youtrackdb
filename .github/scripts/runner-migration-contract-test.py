#!/usr/bin/env python3
"""Test lasting and migration-only contracts for the larger-runner change."""

import argparse
import copy
from pathlib import Path
import shutil
import subprocess
import tempfile

import yaml

ROOT = Path(__file__).parents[2]
WORKFLOW_DIR = Path(".github/workflows")
X64 = "ubuntu-latest-8-cores-x64-public"
ARM = "ubuntu-24.04-8-cores-arm64-public"
WINDOWS = "windows-latest-8-cores-x64-public"
ALLOWED_RUNNERS = {X64, ARM, WINDOWS}
EXCLUDED_WORKFLOWS = {
    "jmh-alerter-tests.yml",
    "ldbc-jmh-compare.yml",
    "ldbc-jmh-nightly.yml",
}
BOUNDARY_PATHS = [
    ".github/workflows/jmh-alerter-tests.yml",
    ".github/workflows/ldbc-jmh-nightly.yml",
    ".github/workflows/maven-mirror",
    ".github/workflows/testflows-orchestrator.pkr.hcl",
    ".github/workflows/testflows-orchestrator",
]
RUNNER_REPLACEMENTS = {
    "ubuntu-latest": X64,
    "ubuntu-24.04-arm": ARM,
    "windows-latest": WINDOWS,
}
TEST_WORKFLOW = "runner-migration-contract-tests.yml"


def assert_true(condition, message):
    if not condition:
        raise AssertionError(message)


def load_workflow(root, name):
    with (root / WORKFLOW_DIR / name).open() as stream:
        return yaml.safe_load(stream)


def active_workflows(root):
    workflows = root / WORKFLOW_DIR
    return sorted(
        path.name
        for pattern in ("*.yml", "*.yaml")
        for path in workflows.glob(pattern)
        if path.name not in EXCLUDED_WORKFLOWS
    )


def matrix_values(job, expression):
    prefix = "${{ matrix."
    assert_true(expression.startswith(prefix) and expression.endswith(" }}"),
                f"unsupported runs-on expression: {expression}")
    keys = expression[len(prefix):-3].split(".")
    matrix = job.get("strategy", {}).get("matrix", {})
    values = []
    if len(keys) == 1:
        direct = matrix.get(keys[0], [])
        if isinstance(direct, list):
            values.extend(direct)
        for entry in matrix.get("include", []):
            if keys[0] in entry:
                values.append(entry[keys[0]])
    else:
        for entry in matrix.get(keys[0], []):
            value = entry
            for key in keys[1:]:
                value = value[key]
            values.append(value)
    assert_true(values, f"runs-on expression has no matrix values: {expression}")
    return values


def resolved_runners(job):
    runner = job["runs-on"]
    if isinstance(runner, str) and runner.startswith("${{"):
        return matrix_values(job, runner)
    return [runner]


def notification_step(job):
    return next(step for step in job["steps"] if "zulip" in step.get("uses", ""))


def check_normal(root=ROOT):
    workflows = root / WORKFLOW_DIR
    assert_true(not (workflows / "ci-failure-fix-agent.yml").exists(),
                "repair workflow remains")
    active_text = "\n".join((workflows / name).read_text() for name in active_workflows(root))
    for forbidden in ("ci-failure-fix-agent.yml", "Dispatch CI Fix Agent", "macos-latest"):
        assert_true(forbidden not in active_text, f"active reference remains: {forbidden}")

    for name in active_workflows(root):
        document = load_workflow(root, name)
        for job_name, job in document.get("jobs", {}).items():
            if "uses" in job:
                assert_true("runs-on" not in job,
                            f"reusable workflow caller declares runs-on: {name}:{job_name}")
                continue
            assert_true("runs-on" in job, f"job has no runs-on: {name}:{job_name}")
            for runner in resolved_runners(job):
                assert_true(runner in ALLOWED_RUNNERS,
                            f"unapproved runner in {name}:{job_name}: {runner}")
            timeout = job.get("timeout-minutes")
            if isinstance(timeout, int):
                assert_true(timeout <= 360, f"timeout exceeds hosted cap: {name}:{job_name}")

    pipeline = load_workflow(root, "maven-pipeline.yml")
    integration = load_workflow(root, "maven-integration-tests-pipeline.yml")
    assert_true(integration["permissions"]["actions"] == "read",
                "integration actions read permission missing")
    linux_matrix = pipeline["jobs"]["test-linux"]["strategy"]["matrix"]["include"]
    x64_options = next(entry["mvn_opts"] for entry in linux_matrix if entry["arch"] == "x86")
    arm_options = next(entry["mvn_opts"] for entry in linux_matrix if entry["arch"] == "arm")
    assert_true("coverage" in x64_options, "x64 coverage profile was removed")
    assert_true("coverage" not in arm_options, "ARM coverage behavior changed")

    small_cache = pipeline["jobs"]["test-small-cache-linux"]
    assert_true(small_cache["permissions"] == {"contents": "read", "checks": "write"},
                "small-cache check publication permissions changed")
    small_cache_command = next(
        step["run"] for step in small_cache["steps"]
        if step.get("name") == "Run Small Cache Integration Tests"
    )
    assert_true("small-cache-it" in small_cache_command,
                "small-cache Maven profile was removed")
    small_cache_notice = next(
        step for step in small_cache["steps"]
        if step.get("name") == "Send Zulip notification on failure"
    )
    assert_true(small_cache_notice.get("if") == "failure()",
                "small-cache failure guard changed")

    for document in (pipeline, integration):
        job = document["jobs"]["notify-failure"]
        assert_true(job.get("permissions") == {},
                    "notification job must not inherit workflow token permissions")
        notification = notification_step(job)
        content = notification["with"]["content"]
        assert_true("View Run Logs" in content, "failure notification lacks run link")
        assert_true("Manual investigation" in content, "manual repair message missing")
        assert_true(notification["with"]["to"] == "ytdb", "notification stream changed")
        assert_true(notification["with"]["topic"] == "ci status",
                    "notification topic changed")

    contract_workflow = load_workflow(root, TEST_WORKFLOW)
    test_steps = contract_workflow["jobs"]["contract-tests"]["steps"]
    commands = "\n".join(str(step.get("run", "")) for step in test_steps)
    assert_true("runner-migration-contract-test.py --self-test" in commands,
                "normal CI does not run contract tests and negative controls")


def git_output(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def baseline_workflow(root, base, name):
    return yaml.safe_load(git_output(root, "show", f"{base}:{WORKFLOW_DIR / name}"))


def normalize_runners(value):
    if isinstance(value, dict):
        return {key: normalize_runners(item) for key, item in value.items()}
    if isinstance(value, list):
        if "self-hosted" in value:
            return X64
        return [normalize_runners(item) for item in value]
    return RUNNER_REPLACEMENTS.get(value, value)


def command_map(document):
    commands = {}
    for job_name, job in document.get("jobs", {}).items():
        if job_name == "test-macos":
            continue
        for index, step in enumerate(job.get("steps", [])):
            if step.get("name") == "Dispatch CI Fix Agent" or "run" not in step:
                continue
            key = (job_name, step.get("name", f"step-{index}"))
            commands[key] = step["run"]
    return commands


def remove_macos_status_handling(step):
    result = copy.deepcopy(step)
    result["env"].pop("MACOS_RESULT")
    macos_block = (
        "# macOS is non-blocking during soak period: we wait for it\n"
        "# (it's in needs:) and log its result, but do not fail\n"
        "# ci-status on a macOS-only regression. The notify-failure\n"
        "# job observes test-macos separately and still alerts.\n"
        'echo "macOS result (non-blocking during soak): $MACOS_RESULT"\n'
    )
    assert_true(result["run"].count(macos_block) == 1,
                "baseline macOS status block is missing or ambiguous")
    result["run"] = result["run"].replace(macos_block, "")
    return result


def assert_boundary_unchanged(root, base, paths=BOUNDARY_PATHS):
    result = subprocess.run(
        ["git", "-C", str(root), "diff", "--quiet", base, "--", *paths],
        check=False,
    )
    assert_true(result.returncode == 0,
                "benchmark or shared provisioning boundary changed from explicit base")
    untracked = git_output(
        root, "ls-files", "--others", "--exclude-standard", "--", *paths
    ).decode().strip()
    assert_true(not untracked,
                f"untracked benchmark or shared provisioning file remains: {untracked}")


def migrate_expected_document(old, new, name):
    expected = copy.deepcopy(old)
    if name == "maven-pipeline.yml":
        del expected["jobs"]["test-macos"]
        for job_name in ("ci-status", "notify-failure"):
            needs = expected["jobs"][job_name]["needs"]
            expected["jobs"][job_name]["needs"] = [
                dependency for dependency in needs if dependency != "test-macos"
            ]
        old_status = expected["jobs"]["ci-status"]["steps"]
        status_index = next(
            i for i, step in enumerate(old_status) if step.get("name") == "Check results"
        )
        old_status[status_index] = remove_macos_status_handling(old_status[status_index])
        permissions = expected["jobs"]["test-small-cache-linux"]["permissions"]
        permissions.pop("actions")
    if name == "maven-integration-tests-pipeline.yml":
        expected["permissions"]["actions"] = "read"

    for job_name, job in expected.get("jobs", {}).items():
        runner = job.get("runs-on")
        if runner == "windows-latest":
            job["runs-on"] = WINDOWS
        elif runner == "ubuntu-24.04-arm":
            job["runs-on"] = ARM
        elif runner == "ubuntu-latest" or isinstance(runner, list):
            job["runs-on"] = X64
        if "strategy" in job and "matrix" in job["strategy"]:
            job["strategy"]["matrix"] = normalize_runners(job["strategy"]["matrix"])
        if isinstance(job.get("timeout-minutes"), int) and job["timeout-minutes"] > 360:
            job["timeout-minutes"] = 360
        job["steps"] = [
            step for step in job.get("steps", [])
            if step.get("name") != "Dispatch CI Fix Agent"
        ]
        if job_name == "notify-failure":
            job["permissions"] = {}
            expected_notice = notification_step(job)
            current_notice = notification_step(new["jobs"][job_name])
            expected_notice["with"]["content"] = current_notice["with"]["content"]
    return expected


def assert_workflow_preserved(old, new, name):
    for job_name, old_job in old.get("jobs", {}).items():
        if job_name == "test-macos":
            assert_true(job_name not in new["jobs"], "macOS job was not deleted")
            continue
        assert_true(job_name in new["jobs"], f"baseline job was removed: {name}:{job_name}")
        old_matrix = normalize_runners(old_job.get("strategy", {}).get("matrix"))
        new_matrix = new["jobs"][job_name].get("strategy", {}).get("matrix")
        assert_true(old_matrix == new_matrix,
                    f"matrix or architecture behavior changed: {name}:{job_name}")
    expected = migrate_expected_document(old, new, name)
    assert_true(command_map(expected) == command_map(new),
                f"run commands changed outside approved removals: {name}")
    assert_true(expected == new,
                f"workflow changed outside approved migration fields: {name}")


def check_baseline(base, root=ROOT):
    assert_boundary_unchanged(root, base)

    for name in active_workflows(root):
        if name == TEST_WORKFLOW:
            continue
        try:
            old = baseline_workflow(root, base, name)
        except subprocess.CalledProcessError:
            continue
        new = load_workflow(root, name)
        assert_workflow_preserved(old, new, name)


def expect_failure(action, description):
    try:
        action()
    except AssertionError:
        return
    raise AssertionError(f"negative control did not fail: {description}")


def mutate_copy(mutator):
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    shutil.copytree(ROOT / ".github", root / ".github")
    mutator(root)
    return temporary, root


def replace_once(path, old, new):
    text = path.read_text()
    assert_true(text.count(old) >= 1, f"mutation target missing: {old}")
    path.write_text(text.replace(old, new, 1))


def committed_boundary_negative_control(relative_path):
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        subprocess.run(["git", "init", "-q", str(root)], check=True)
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("baseline\n")
        subprocess.run(["git", "-C", str(root), "add", "."], check=True)
        subprocess.run(
            ["git", "-C", str(root), "-c", "user.name=Contract Test",
             "-c", "user.email=contract@example.invalid", "commit", "-qm", "baseline"],
            check=True,
        )
        base = git_output(root, "rev-parse", "HEAD").decode().strip()
        path.write_text("baseline\ncommitted mutation\n")
        subprocess.run(["git", "-C", str(root), "add", "."], check=True)
        subprocess.run(
            ["git", "-C", str(root), "-c", "user.name=Contract Test",
             "-c", "user.email=contract@example.invalid", "commit", "-qm", "mutation"],
            check=True,
        )
        expect_failure(
            lambda: assert_boundary_unchanged(root, base, [relative_path]),
            f"committed boundary mutation: {relative_path}",
        )


def baseline_fixture_with_macos(current):
    old = copy.deepcopy(current)
    old["jobs"]["test-macos"] = {}
    for job_name in ("ci-status", "notify-failure"):
        old["jobs"][job_name]["needs"].append("test-macos")
    old["jobs"]["test-small-cache-linux"]["permissions"]["actions"] = "write"

    status = next(
        step for step in old["jobs"]["ci-status"]["steps"]
        if step.get("name") == "Check results"
    )
    status["env"]["MACOS_RESULT"] = "${{ needs.test-macos.result }}"
    marker = 'if [[ "$LINUX_RESULT" != "success" ]] || \\\n'
    macos_block = (
        "# macOS is non-blocking during soak period: we wait for it\n"
        "# (it's in needs:) and log its result, but do not fail\n"
        "# ci-status on a macOS-only regression. The notify-failure\n"
        "# job observes test-macos separately and still alerts.\n"
        'echo "macOS result (non-blocking during soak): $MACOS_RESULT"\n'
    )
    assert_true(status["run"].count(marker) == 1,
                "current status predicate is missing or ambiguous")
    status["run"] = status["run"].replace(marker, macos_block + marker)
    return old


def check_negative_controls():
    mutations = [
        ("block-merge runner typo", "block-merge-commits.yml", X64,
         "ubuntu-latest-8-core-x64-public"),
        ("integration matrix runner typo", "maven-integration-tests-pipeline.yml", ARM,
         "ubuntu-24.04-8-core-arm64-public"),
        ("x64 coverage removal", "maven-pipeline.yml", "docker-images,coverage",
         "docker-images"),
        ("notification permission inheritance", "maven-pipeline.yml", "    permissions: {}\n",
         ""),
    ]
    for description, name, old, new in mutations:
        temporary, root = mutate_copy(
            lambda fixture, n=name, before=old, after=new: replace_once(
                fixture / WORKFLOW_DIR / n, before, after
            )
        )
        try:
            expect_failure(lambda: check_normal(root), description)
        finally:
            temporary.cleanup()

    temporary, root = mutate_copy(
        lambda fixture: (fixture / WORKFLOW_DIR / "invalid-runner.yaml").write_text(
            "name: Invalid runner fixture\non: push\njobs:\n"
            "  invalid-runner:\n    runs-on: self-hosted\n    steps: []\n"
        )
    )
    try:
        expect_failure(lambda: check_normal(root), ".yaml workflow runner typo")
    finally:
        temporary.cleanup()

    temporary, root = mutate_copy(
        lambda fixture: (fixture / WORKFLOW_DIR / "reusable-caller.yml").write_text(
            "name: Reusable caller fixture\non: workflow_dispatch\njobs:\n"
            "  call:\n    uses: owner/repository/.github/workflows/called.yml@main\n"
        )
    )
    try:
        check_normal(root)
        replace_once(
            root / WORKFLOW_DIR / "reusable-caller.yml",
            "    uses: owner/repository/.github/workflows/called.yml@main\n",
            "    steps: []\n",
        )
        expect_failure(
            lambda: check_normal(root),
            "ordinary job without runs-on after reusable caller mutation",
        )
    finally:
        temporary.cleanup()

    # Exercise the explicit baseline comparator, not only lasting fixed-value checks.
    old = baseline_fixture_with_macos(load_workflow(ROOT, "maven-pipeline.yml"))
    mutations = [
        ("Windows result predicate removal",
         '[[ "$WINDOWS_RESULT" != "success" ]]', "false"),
        ("coverage result predicate removal",
         '[[ "$COVERAGE_RESULT" != "success"', "[[ false"),
    ]
    for description, before, after in mutations:
        mutated = load_workflow(ROOT, "maven-pipeline.yml")
        status = next(
            step for step in mutated["jobs"]["ci-status"]["steps"]
            if step.get("name") == "Check results"
        )
        assert_true(status["run"].count(before) == 1,
                    f"status mutation target is missing or ambiguous: {description}")
        status["run"] = status["run"].replace(before, after, 1)
        expect_failure(
            lambda document=mutated: assert_workflow_preserved(
                old, document, "maven-pipeline.yml"
            ),
            description,
        )

    mutated = load_workflow(ROOT, "maven-pipeline.yml")
    matrix = mutated["jobs"]["test-linux"]["strategy"]["matrix"]["include"]
    matrix[0]["mvn_opts"] = matrix[0]["mvn_opts"].replace(",coverage", "")
    expect_failure(
        lambda: assert_workflow_preserved(old, mutated, "maven-pipeline.yml"),
        "baseline matrix comparison after x64 coverage removal",
    )

    committed_boundary_negative_control(".github/workflows/ldbc-jmh-compare.yml")
    committed_boundary_negative_control(
        ".github/workflows/testflows-orchestrator/scripts/setup.sh"
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", help="verify migration-only preservation against this Git ref")
    parser.add_argument("--self-test", action="store_true",
                        help="run lasting checks and their negative controls")
    arguments = parser.parse_args()

    check_normal()
    if arguments.base:
        check_baseline(arguments.base)
    if arguments.self_test:
        check_negative_controls()
    print("runner migration contract checks passed")


if __name__ == "__main__":
    main()
