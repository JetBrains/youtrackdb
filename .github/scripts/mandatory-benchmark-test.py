#!/usr/bin/env python3
"""Regression tests for pull request and commit benchmark comparison support."""

import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import yaml

SCRIPT = Path(__file__).with_name("mandatory-benchmark.py")
ROOT = SCRIPT.parents[2]
COMPARE_SCRIPT = ROOT / "jmh-ldbc" / "jmh-compare.py"
SPEC = importlib.util.spec_from_file_location("mandatory_benchmark", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
COMPARE_SPEC = importlib.util.spec_from_file_location("jmh_compare", COMPARE_SCRIPT)
COMPARE_MODULE = importlib.util.module_from_spec(COMPARE_SPEC)
COMPARE_SPEC.loader.exec_module(COMPARE_MODULE)
MISSING = object()


def result(name, params=None, score=1.0, score_error=MISSING):
  metric = {"score": score}
  if score_error is not MISSING:
    metric["scoreError"] = score_error
  return {"benchmark": name, "params": params or {}, "primaryMetric": metric}


class TargetTest(unittest.TestCase):

  def test_target_requires_exactly_one_valid_identity(self):
    """Manual targets accept one positive PR number or one full hexadecimal SHA."""
    self.assertEqual(("pr", "12"), MODULE.select_target(" 12 ", ""))
    self.assertEqual(("sha", "a" * 40), MODULE.select_target("", "A" * 40))
    for pr, sha in (("", ""), ("12", "a" * 40), ("zero", ""), ("", "abc")):
      with self.assertRaises(MODULE.ContractError):
        MODULE.select_target(pr, sha)

  def make_repository(self):
    temporary = tempfile.TemporaryDirectory()
    root = Path(temporary.name)
    subprocess.run(["git", "init", "-q", "-b", "develop", str(root)], check=True)
    subprocess.run([
        "git", "-C", str(root), "config", "user.email", "test@example.invalid"], check=True)
    subprocess.run(["git", "-C", str(root), "config", "user.name", "Test"], check=True)
    return temporary, root

  def commit(self, root, name):
    (root / "value.txt").write_text(name)
    subprocess.run(["git", "-C", str(root), "add", "."], check=True)
    subprocess.run(["git", "-C", str(root), "commit", "-qm", name], check=True)
    return subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()

  def test_owned_commit_uses_first_parent_and_rejects_root(self):
    """Owned history resolves the ordered first parent and rejects a root target."""
    temporary, root = self.make_repository()
    try:
      first = self.commit(root, "first")
      second = self.commit(root, "second")
      subprocess.run([
          "git", "-C", str(root), "update-ref", "refs/remotes/origin/develop", second],
          check=True)
      self.assertEqual(first, MODULE.resolve_owned_commit(root, second))
      with self.assertRaises(MODULE.ContractError):
        MODULE.resolve_owned_commit(root, first)
    finally:
      temporary.cleanup()

  def test_merge_commit_uses_ordered_first_parent(self):
    """A merge target selects its first parent rather than the merged side parent."""
    temporary, root = self.make_repository()
    try:
      base = self.commit(root, "base")
      subprocess.run(["git", "-C", str(root), "checkout", "-qb", "side"], check=True)
      (root / "side.txt").write_text("side")
      subprocess.run(["git", "-C", str(root), "add", "."], check=True)
      subprocess.run(["git", "-C", str(root), "commit", "-qm", "side"], check=True)
      side = subprocess.check_output(
          ["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
      subprocess.run(["git", "-C", str(root), "checkout", "develop"], check=True,
                     stdout=subprocess.DEVNULL)
      first_parent = self.commit(root, "develop")
      subprocess.run([
          "git", "-C", str(root), "merge", "--no-ff", "-m", "merge", "side"],
          check=True, stdout=subprocess.DEVNULL)
      merge = subprocess.check_output(
          ["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
      subprocess.run([
          "git", "-C", str(root), "update-ref", "refs/remotes/origin/develop", merge],
          check=True)
      self.assertEqual(first_parent, MODULE.resolve_owned_commit(root, merge))
      self.assertNotEqual(side, first_parent)
      self.assertNotEqual(base, merge)
    finally:
      temporary.cleanup()

  def test_owned_commit_accepts_historical_tag_and_rejects_foreign_object(self):
    """Owned branches and tags admit ancestors while unreachable objects remain foreign."""
    temporary, root = self.make_repository()
    other_temporary, other = self.make_repository()
    try:
      root_commit = self.commit(root, "root")
      tagged = self.commit(root, "tagged")
      subprocess.run([
          "git", "-C", str(root), "tag", "-a", "-m", "owned", "owned", tagged],
          check=True)
      self.commit(root, "child")
      self.assertEqual(root_commit, MODULE.resolve_owned_commit(root, tagged))
      foreign = self.commit(other, "foreign-root")
      foreign_child = self.commit(other, "foreign-child")
      subprocess.run([
          "git", "-C", str(root), "fetch", str(other), foreign_child], check=True,
          stdout=subprocess.DEVNULL)
      with self.assertRaises(MODULE.ContractError):
        MODULE.resolve_owned_commit(root, foreign_child)
      self.assertNotEqual(foreign, tagged)
    finally:
      temporary.cleanup()
      other_temporary.cleanup()

  def test_latest_exact_maintainer_approval_is_required(self):
    """A later changes request or dismissal supersedes an approval on the executed SHA."""
    head = "a" * 40
    reviews = [{
        "id": 1, "submitted_at": "2026-01-01T00:00:00Z", "state": "APPROVED",
        "commit_id": head, "user": {"login": "owner"}}]
    self.assertTrue(MODULE.has_exact_maintainer_approval(
        reviews, {"owner": "maintain"}, head))
    reviews.append({
        "id": 2, "submitted_at": "2026-01-02T00:00:00Z",
        "state": "CHANGES_REQUESTED", "commit_id": head,
        "user": {"login": "owner"}})
    self.assertFalse(MODULE.has_exact_maintainer_approval(
        reviews, {"owner": "maintain"}, head))
    reviews[-1]["state"] = "DISMISSED"
    self.assertFalse(MODULE.has_exact_maintainer_approval(
        reviews, {"owner": "admin"}, head))


class RunnerProfileTest(unittest.TestCase):

  def test_runner_profile_defaults_and_maps_only_approved_sizes(self):
    """Omitted, eight, and 32-vCPU requests map to fixed dedicated runner profiles."""
    self.assertEqual(
        {"requested_cores": 8, "label": "type-ccx33", "memory_gb": 32},
        MODULE.resolve_runner_profile(""))
    self.assertEqual(MODULE.resolve_runner_profile(""), MODULE.resolve_runner_profile("8"))
    self.assertEqual(
        {"requested_cores": 32, "label": "type-ccx53", "memory_gb": 128},
        MODULE.resolve_runner_profile("32"))

  def test_runner_profile_rejects_unsupported_or_malformed_values(self):
    """Whitespace, provider labels, signs, and unapproved sizes fail before provisioning."""
    for value in (" 8", "8 ", "08", "+8", "16", "type-ccx53", "", None):
      if value in ("", None):
        continue
      with self.assertRaises(MODULE.ContractError):
        MODULE.resolve_runner_profile(value)


class FilterTest(unittest.TestCase):

  def test_filter_language_preserves_case_whitespace_duplicates_and_boundaries(self):
    """Closed query tokens normalize safely without merging IC1 into IC10 or IC11."""
    self.assertEqual(
        [".*ic1_.*", ".*ic10_.*", ".*ic11_.*"],
        MODULE.query_patterns(" ic1, IC10 ,ic11,IC1 "))
    self.assertEqual([], MODULE.query_patterns(""))
    self.assertEqual(
        [".*is1_.*", ".*LdbcGremlinTranslatorBenchmark.*"],
        MODULE.query_patterns("IS1,gremlin"))

  def test_filter_rejects_empty_unknown_internal_space_and_metacharacters(self):
    """Invalid tokens fail before any benchmark command can execute."""
    for value in ("IS1,", ",IS1", "IS1,,IC2", "IS 1", "IC14", "IS1|IC2", ".*"):
      with self.assertRaises(MODULE.ContractError):
        MODULE.query_patterns(value)

  def test_query_union_selects_linked_and_translator_only_methods(self):
    """SQL IDs select linked shapes while gremlin adds translator-only methods."""
    names = [
        "x.LdbcSingleThreadICBenchmark.ic1_friends",
        "x.LdbcGremlinTranslatorBenchmark.gremlin_ic1_shape",
        "x.LdbcGremlinTranslatorBenchmark.gremlin_valuesV_union",
        "x.LdbcSingleThreadICBenchmark.ic10_recommendation",
        "x.LdbcSingleThreadBothEBenchmark.ic1_write",
    ]
    import re
    selected = {
        name for name in names
        if any(re.search(pattern, name) for pattern in MODULE.query_patterns("IC1,gremlin"))
        and MODULE.FIXED_EXCLUSION not in name
    }
    self.assertIn(names[0], selected)
    self.assertIn(names[1], selected)
    self.assertIn(names[2], selected)
    self.assertNotIn(names[3], selected)
    self.assertNotIn(names[4], selected)

  def test_multiple_filters_survive_the_actual_inner_shell_transport(self):
    """Quoted positional includes reach the inner shell as two arguments without a pipe."""
    selection = MODULE.shell_filter_args("IS1,IC2")
    command = (
        "python3 -c 'import json,sys; print(json.dumps(sys.argv[1:]))' " + selection)
    output = subprocess.check_output(["/bin/sh", "-c", command], text=True)
    self.assertEqual([".*is1_.*", ".*ic2_.*"], json.loads(output))


class ResultTest(unittest.TestCase):

  def test_attempt_artifact_selection_supports_manual_retry(self):
    """A retried job selects the newest live producer from this or an earlier attempt."""
    artifacts = [
        {"id": 1, "name": "data-attempt1", "expired": False},
        {"id": 2, "name": "data-attempt2", "expired": False},
        {"id": 3, "name": "data-attempt3", "expired": True},
    ]
    self.assertEqual(1, MODULE.select_attempt_artifact(artifacts, "data", 1))
    self.assertEqual(2, MODULE.select_attempt_artifact(artifacts, "data", 3))

  def setUp(self):
    self.temporary = tempfile.TemporaryDirectory()
    self.root = Path(self.temporary.name)

  def tearDown(self):
    self.temporary.cleanup()

  def write(self, name, value):
    path = self.root / name
    path.write_text(json.dumps(value))
    return str(path)

  def arguments(self, **changes):
    values = {
        "base_results": self.write("base.json", [result("A")]),
        "head_results": self.write("head.json", [result("B")]),
        "base_inventory": self.write("base-inventory.json", ["A"]),
        "head_inventory": self.write("head-inventory.json", ["B"]),
        "gremlin_arms": "on",
    }
    values.update(changes)
    return argparse.Namespace(**values)

  def test_each_commit_uses_its_own_complete_inventory(self):
    """Benchmark additions and removals validate against independent result sets."""
    value = MODULE.validate_comparison(self.arguments())
    self.assertEqual(["B"], value["additions"])
    self.assertEqual(["A"], value["removals"])
    empty = self.write("empty.json", [])
    with self.assertRaises(MODULE.ContractError):
      MODULE.validate_comparison(self.arguments(head_results=empty))

  def test_result_scores_require_renderer_compatible_finite_numbers(self):
    """Validation rejects unrenderable numbers and transports accepted integer boundaries."""
    max_finite_integer = int(float.fromhex("0x1.fffffffffffffp+1023"))
    finite = (
        0.0, -0.0, float.fromhex("0x0.0000000000001p-1022"),
        -float.fromhex("0x0.0000000000001p-1022"),
        float.fromhex("0x1.fffffffffffffp+1023"),
        -float.fromhex("0x1.fffffffffffffp+1023"),
        max_finite_integer, -max_finite_integer)
    for score in finite:
      self.assertEqual(("A", ()), MODULE.result_key(result("A", score=score)))
    for score in (True, None, "1", float("nan"), float("inf"), float("-inf")):
      with self.assertRaises(MODULE.ContractError):
        MODULE.result_key(result("A", score=score))

    benchmark = "x.LdbcSingleThreadICBenchmark.ic1_test"
    inventory = self.write("boundary-inventory.json", [benchmark])
    for score in (max_finite_integer, -max_finite_integer):
      results = self.write("boundary-results.json", [result(benchmark, score=score)])
      MODULE.validate_comparison(self.arguments(
          base_results=results, head_results=results,
          base_inventory=inventory, head_inventory=inventory))
      parsed = COMPARE_MODULE.parse_jmh_results([result(benchmark, score=score)])
      self.assertEqual(1, len(COMPARE_MODULE.build_suite_table(
          parsed, parsed, "SingleThread")))

    for score in (10 ** 400, -(10 ** 400)):
      results = self.write("overflow-results.json", [result(benchmark, score=score)])
      with self.assertRaises(MODULE.ContractError):
        MODULE.validate_comparison(self.arguments(
            base_results=results, head_results=results,
            base_inventory=inventory, head_inventory=inventory))

  def test_score_errors_require_renderer_compatible_numeric_range(self):
    """Validation rejects overflowing errors while preserving renderer uncertainty semantics."""
    benchmark = "x.LdbcSingleThreadICBenchmark.ic1_test"
    inventory = self.write("error-inventory.json", [benchmark])
    accepted = (MISSING, None, float("nan"), float("inf"), float("-inf"),
                True, "not-a-number", [1])
    for score_error in accepted:
      row = (result(benchmark) if score_error is MISSING
             else result(benchmark, score_error=score_error))
      results = self.write("error-results.json", [row])
      MODULE.validate_comparison(self.arguments(
          base_results=results, head_results=results,
          base_inventory=inventory, head_inventory=inventory))
      parsed = COMPARE_MODULE.parse_jmh_results([row])
      self.assertEqual(1, len(COMPARE_MODULE.build_suite_table(
          parsed, parsed, "SingleThread")))

    for score_error in (10 ** 400, -(10 ** 400)):
      results = self.write(
          "overflow-error-results.json",
          [result(benchmark, score_error=score_error)])
      with self.assertRaises(MODULE.ContractError):
        MODULE.validate_comparison(self.arguments(
            base_results=results, head_results=results,
            base_inventory=inventory, head_inventory=inventory))

  def test_gremlin_parameter_arms_are_complete(self):
    """Production runs require one translator arm and A/B investigations require both."""
    name = "x.LdbcGremlinTranslatorBenchmark.gremlin_is1"
    inventory = self.write("gremlin-inventory.json", [name])
    both = self.write("gremlin-results.json", [
        result(name, {"translatorEnabled": "true"}),
        result(name, {"translatorEnabled": "false"})])
    MODULE.validate_comparison(self.arguments(
        base_results=both, head_results=both, base_inventory=inventory,
        head_inventory=inventory, gremlin_arms="both"))
    one = self.write(
        "one.json", [result(name, {"translatorEnabled": "true"})])
    with self.assertRaises(MODULE.ContractError):
      MODULE.validate_comparison(self.arguments(
          base_results=one, head_results=one, base_inventory=inventory,
          head_inventory=inventory, gremlin_arms="both"))


class CompletionTest(unittest.TestCase):

  def fields(self, **changes):
    value = {
        "repository": "JetBrains/youtrackdb",
        "pr_number": 12,
        "executed_repository": "fork/youtrackdb",
        "head_sha": "a" * 40,
        "base_sha": "b" * 40,
        "baseline_sha": "c" * 40,
        "queries": "",
        "gremlin_arms": "on",
        "validation_contract": MODULE.CONTRACT,
        "result_digest": "sha256:" + "d" * 64,
        "validator_workflow": MODULE.WORKFLOW_IDENTITY,
        "validator_revision": "e" * 40,
        "completed_at": "2026-09-18T00:00:00Z",
    }
    value.update(changes)
    return value

  def test_completion_bytes_are_canonical_and_full_pr_only(self):
    """Signed bytes contain the exact full-suite PR contract in deterministic order."""
    payload = MODULE.canonical_completion(self.fields())
    decoded = json.loads(payload)
    self.assertEqual(payload, MODULE.canonical_completion(decoded))
    self.assertEqual(set(self.fields()), set(decoded))
    self.assertNotIn("runner_cores", decoded)
    self.assertTrue(payload.endswith(b"\n"))
    for changes in ({"queries": "IS1"}, {"gremlin_arms": "both"},
                    {"head_sha": "short"}, {"result_digest": "bad"},
                    {"validator_workflow": "other/workflow.yml"}):
      with self.assertRaises(MODULE.ContractError):
        MODULE.canonical_completion(self.fields(**changes))

  def test_comment_round_trip_is_bounded_and_detects_mutation(self):
    """Portable completion JSON and bundle round-trip without truncation or locator authority."""
    completion = MODULE.canonical_completion(self.fields())
    body = MODULE.completion_comment(completion, b'{"bundle":true}')
    decoded, bundle, fields = MODULE.parse_completion_comment(body)
    self.assertEqual(completion, decoded)
    self.assertEqual(b'{"bundle":true}', bundle)
    self.assertEqual(self.fields(), fields)
    with self.assertRaises(MODULE.ContractError):
      MODULE.completion_comment(completion, b"x" * (MODULE.MAX_BUNDLE_BYTES + 1))
    with self.assertRaises(MODULE.ContractError):
      MODULE.parse_completion_comment(body.replace("completion-base64", "changed"))

  def test_comment_discovery_ignores_invalid_candidates_and_checks_signed_fields(self):
    """Malformed or wrong records never hide a later valid signed completion."""
    completion = MODULE.canonical_completion(self.fields())
    valid = MODULE.completion_comment(completion, b'{"bundle":true}')
    comments = [
        {"user": {"login": "github-actions[bot]"}, "body": "malformed"},
        {"user": {"login": "attacker"}, "body": valid},
        {"user": {"login": "github-actions[bot]"}, "body": valid},
        {"user": {"login": "github-actions[bot]"}, "body": valid},
    ]
    verifier = mock.Mock(side_effect=[
        MODULE.ContractError("invalid signature"), [{"verificationResult": {}}]])
    result = MODULE.find_verified_completion(
        comments, {"pr_number": 12, "head_sha": "a" * 40}, {"e" * 40}, verifier)
    self.assertEqual(self.fields(), result)
    self.assertEqual(2, verifier.call_count)
    with self.assertRaises(MODULE.ContractError):
      MODULE.find_verified_completion(
          comments, {"pr_number": 13}, {"e" * 40}, verifier)

  def test_attestation_verifier_uses_signer_workflow_and_revision(self):
    """Verification constrains repository, signer workflow, and approved signer digest."""
    completed = mock.Mock(returncode=0, stdout='[{"verificationResult":{}}]', stderr="")
    with mock.patch.object(MODULE.subprocess, "run", return_value=completed) as invoked:
      MODULE.verify_attestation("completion.json", "bundle.json", "JetBrains/youtrackdb", "e" * 40)
    command = invoked.call_args.args[0]
    self.assertIn("--bundle", command)
    self.assertEqual(MODULE.WORKFLOW_IDENTITY, command[command.index("--signer-workflow") + 1])
    self.assertEqual("e" * 40, command[command.index("--signer-digest") + 1])
    self.assertNotIn("--source-digest", command)
    failed = mock.Mock(returncode=1, stdout="", stderr="invalid signature")
    with mock.patch.object(MODULE.subprocess, "run", return_value=failed):
      with self.assertRaises(MODULE.ContractError):
        MODULE.verify_attestation("completion.json", "bundle.json", "repo", "e" * 40)


class CliAndBoundaryTest(unittest.TestCase):

  def test_parser_builds_every_command_contract(self):
    """Every deployed command rejects missing required arguments through the shared parser."""
    parser = MODULE.parser()
    self.assertEqual("target", parser.parse_args(["target"]).command)
    self.assertEqual("runner-profile", parser.parse_args(["runner-profile"]).command)
    self.assertEqual("filter", parser.parse_args(["filter"]).command)
    for command in (
        "owned-commit", "select-artifact", "install-inputs", "approval", "validate",
        "completion", "comment"):
      with self.assertRaises(SystemExit):
        parser.parse_args([command])

  def test_json_and_input_installation_fail_cleanly(self):
    """Malformed JSON and missing canonical input files become contract failures."""
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      invalid = root / "invalid.json"
      invalid.write_text("{")
      with self.assertRaises(MODULE.ContractError):
        MODULE.load_json(invalid)
      oversized = root / "oversized.json"
      oversized.write_text('["large"]')
      with self.assertRaises(MODULE.ContractError):
        MODULE.load_json(oversized, max_bytes=2)
      with self.assertRaises(OSError):
        MODULE.install_canonical_inputs(root, root / "output")

  def test_attestation_verifier_rejects_invalid_and_empty_output(self):
    """Verifier transport failures and empty verification sets fail closed."""
    for completed in (
        mock.Mock(returncode=0, stdout="not-json", stderr=""),
        mock.Mock(returncode=0, stdout="[]", stderr=""),
        mock.Mock(returncode=1, stdout="", stderr="denied"),
    ):
      with mock.patch.object(MODULE.subprocess, "run", return_value=completed):
        with self.assertRaises(MODULE.ContractError):
          MODULE.verify_attestation("record", "bundle", "repo/name", "a" * 40)

  def test_main_dispatches_every_deployed_command(self):
    """The command entry point reaches every workflow-facing helper without shell parsing."""
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      (root / "completion").write_bytes(b"record")
      (root / "bundle").write_bytes(b"bundle")
      cases = [
          argparse.Namespace(command="target", pr_number="1", commit_sha=""),
          argparse.Namespace(command="runner-profile", cores="32"),
          argparse.Namespace(command="filter", queries="IS1", format="json"),
          argparse.Namespace(command="owned-commit", repository=".", sha="a" * 40),
          argparse.Namespace(
              command="select-artifact", artifacts="items", prefix="data", max_attempt=1),
          argparse.Namespace(command="install-inputs", source="source", destination="target"),
          argparse.Namespace(
              command="approval", reviews="reviews", permissions="permissions",
              head_sha="a" * 40),
          argparse.Namespace(
              command="validate", base_results="a", head_results="b",
              base_inventory="c", head_inventory="d", gremlin_arms="on",
              output=str(root / "validation")),
          argparse.Namespace(
              command="completion", fields="fields", output=str(root / "record")),
          argparse.Namespace(
              command="comment", completion=str(root / "completion"),
              bundle=str(root / "bundle"), output=str(root / "comment")),
      ]
      parser_mock = mock.Mock()
      with mock.patch.object(MODULE, "parser", return_value=parser_mock), \
          mock.patch.object(MODULE, "resolve_owned_commit", return_value="b" * 40), \
          mock.patch.object(MODULE, "select_attempt_artifact", return_value=1), \
          mock.patch.object(MODULE, "install_canonical_inputs"), \
          mock.patch.object(MODULE, "has_exact_maintainer_approval", return_value=True), \
          mock.patch.object(MODULE, "validate_comparison", return_value={}), \
          mock.patch.object(MODULE, "canonical_completion", return_value=b"record\n"), \
          mock.patch.object(MODULE, "completion_comment", return_value="comment"), \
          mock.patch.object(MODULE, "load_json", side_effect=lambda path: {
              "items": [], "reviews": [], "permissions": {}, "fields": {}}[path]):
        for arguments in cases:
          parser_mock.parse_args.return_value = arguments
          self.assertEqual(0, MODULE.main())

  def test_completion_comment_rejects_wrong_shapes_and_noncanonical_bytes(self):
    """Comment parsing rejects absent markers, invalid encodings, and changed JSON bytes."""
    for body in (None, "plain text", MODULE.COMPLETION_MARKER + "\nmissing"):
      with self.assertRaises(MODULE.ContractError):
        MODULE.parse_completion_comment(body)
    fields = CompletionTest().fields()
    pretty = json.dumps(fields, indent=2).encode()
    body = MODULE.completion_comment(pretty, b"bundle")
    with self.assertRaises(MODULE.ContractError):
      MODULE.parse_completion_comment(body)


class WorkflowContractTest(unittest.TestCase):

  def setUp(self):
    path = ROOT / ".github/workflows/ldbc-jmh-compare.yml"
    self.text = path.read_text()
    self.workflow = yaml.load(self.text, Loader=yaml.BaseLoader)

  def test_manual_inputs_are_xor_and_concurrency_never_cancels(self):
    """Manual dispatch exposes PR or SHA targets without a branch backdoor or cancellation."""
    inputs = self.workflow["on"]["workflow_dispatch"]["inputs"]
    self.assertEqual(
        {"pr_number", "commit_sha", "queries", "gremlin_arms", "runner_cores"},
        set(inputs))
    self.assertNotIn("base_branch", inputs)
    self.assertEqual("false", self.workflow["concurrency"]["cancel-in-progress"])
    self.assertIn("pr-", self.workflow["concurrency"]["group"])
    self.assertIn("sha-", self.workflow["concurrency"]["group"])

  def test_benchmark_sets_up_jdk_before_maven_execution(self):
    """The benchmark job provisions Temurin 21 before either side invokes Maven."""
    steps = self.workflow["jobs"]["benchmark"]["steps"]
    setup_index = next(
        index for index, step in enumerate(steps)
        if step.get("name") == "Set up JDK 21")
    benchmark_index = next(
        index for index, step in enumerate(steps)
        if step.get("name") == "Run baseline and target benchmarks")
    setup = steps[setup_index]
    self.assertLess(setup_index, benchmark_index)
    self.assertEqual("actions/setup-java@v5", setup["uses"])
    self.assertEqual("21", setup["with"]["java-version"])
    self.assertEqual("temurin", setup["with"]["distribution"])
    self.assertEqual("maven", setup["with"]["cache"])
    self.assertEqual("false", setup["with"]["overwrite-settings"])
    self.assertNotIn("if", setup)

  def test_runner_and_permissions_preserve_job_boundaries(self):
    """The resolved fixed runner executes target code while only the validator has authority."""
    benchmark = self.workflow["jobs"]["benchmark"]
    validator = self.workflow["jobs"]["validate"]
    self.assertIn("needs.resolve.outputs.runner_label", str(benchmark["runs-on"]))
    self.assertNotIn("id-token", benchmark["permissions"])
    self.assertNotIn("attestations", benchmark["permissions"])
    self.assertEqual("write", validator["permissions"]["id-token"])
    self.assertEqual("write", validator["permissions"]["attestations"])
    self.assertEqual("write", validator["permissions"]["artifact-metadata"])
    self.assertEqual("write", validator["permissions"]["pull-requests"])

  def test_runner_inputs_mapping_sequencing_and_reporting_are_trusted(self):
    """Both entry points default safely, one job runs both sides, and reports disclose profile."""
    call_inputs = self.workflow["on"]["workflow_call"]["inputs"]
    dispatch_inputs = self.workflow["on"]["workflow_dispatch"]["inputs"]
    self.assertEqual("8", call_inputs["runner_cores"]["default"])
    self.assertEqual("string", call_inputs["runner_cores"]["type"])
    self.assertEqual("8", dispatch_inputs["runner_cores"]["default"])
    self.assertEqual(["8", "32"], dispatch_inputs["runner_cores"]["options"])
    resolve = str(self.workflow["jobs"]["resolve"])
    benchmark = str(self.workflow["jobs"]["benchmark"])
    validator = str(self.workflow["jobs"]["validate"])
    self.assertIn("runner-profile", resolve)
    self.assertIn("runner_label", resolve)
    self.assertEqual(1, benchmark.count('run_side base "$BASELINE_SHA"'))
    self.assertEqual(1, benchmark.count('run_side head "$HEAD_SHA"'))
    self.assertIn("requested configuration, not physical hardware proof", benchmark)
    self.assertIn("requested configuration, not physical hardware proof", validator)

  def test_signed_completion_is_pr_full_suite_only(self):
    """Standalone and filtered runs cannot sign or publish pull request completion."""
    validator = str(self.workflow["jobs"]["validate"])
    self.assertIn("actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6", validator)
    self.assertGreaterEqual(validator.count("needs.resolve.outputs.full_pr == 'true'"), 4)
    self.assertIn("job_workflow_ref", validator)
    self.assertIn("job_workflow_sha", validator)
    self.assertNotIn("github.event_name == 'workflow_dispatch'", validator)
    self.assertNotIn("statuses", validator)

  def test_privileged_validator_actions_are_pinned(self):
    """Every external validator action uses the verified commit behind its documented version."""
    expected = {
        "actions/github-script": "3a2844b7e9c422d3c10d287c895573f7108da1b3",
        "actions/checkout": "3d3c42e5aac5ba805825da76410c181273ba90b1",
        "actions/download-artifact": "3e5f45b2cfb9172054b4087a40e8e0b5a5461e7c",
        "actions/upload-artifact": "043fb46d1a93c77aae656e7c1c64a875d1fc6a0a",
        "actions/attest": "1e69f48acb82d1966a394da916b4c1698aa569d6",
    }
    actions = [
        step["uses"] for step in self.workflow["jobs"]["validate"]["steps"]
        if "uses" in step]
    self.assertTrue(actions)
    for action in actions:
      name, revision = action.split("@", 1)
      self.assertEqual(expected[name], revision)
      self.assertRegex(revision, r"^[0-9a-f]{40}$")
    for version in ("v9", "v7", "v8", "v4"):
      self.assertIn(f"# {version}", self.text)

  def test_maven_pipeline_remains_outside_stage_one(self):
    """The first delivery does not add automatic scheduling or CI Status integration."""
    pipeline = (ROOT / ".github/workflows/maven-pipeline.yml").read_text()
    self.assertNotIn("mandatory-benchmark.py", pipeline)
    self.assertNotIn("ldbc-jmh-compare.yml@", pipeline)


if __name__ == "__main__":
  unittest.main()
