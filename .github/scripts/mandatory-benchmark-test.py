#!/usr/bin/env python3
"""Regression tests for pull request and commit benchmark comparison support."""

import argparse
import importlib.util
import json
import itertools
import os
import re
from pathlib import Path
import subprocess
import sys
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
              command="completion", mode="normal", fields="fields", output=str(root / "record")),
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


COMPARE_JOBS = ("resolve", "retrieve-dataset", "benchmark",
                "telemetry-benchmark", "telemetry-validate", "validate")
TELEMETRY_JOBS = ("telemetry-benchmark", "telemetry-validate")
PREFLIGHT = "Check telemetry availability before runner allocation"
TRUSTED_IMPORT = ('    sys.path.insert(0, "jmh-telemetry")\n'
                  '    from telemetry_compare import CATALOG\n')


FORBIDDEN = ("jmh-telemetry", "telemetry_run", "telemetry_compare",
             "telemetry-benchmark", "telemetry-validate")
MAVEN_TOKEN = re.compile(r"\bmvn|MAVEN_")
# Pin complete logical lines, including arguments and shell redirections.
MAVEN_COMMANDS = frozenset((
    "chmod +x mvnw",
    "./mvnw -pl jmh-ldbc -am clean package -DskipTests -Dspotless.check.skip=true "
    "-q",
    "./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests "
    "-Dspotless.check.skip=true -Dcentral.skip=true -Djmh.args=\"${SELECTION} -e "
    "$FIXED_EXCLUSION ${PARAM} -f 0 -wi 0 -i 1 -r 1s\" "
    ">/tmp/mandatory-output/$side-warm.log",
    "./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests "
    "-Dspotless.check.skip=true -Dcentral.skip=true -Djmh.args=\"${SELECTION} -e "
    "$FIXED_EXCLUSION ${PARAM} -l\" | grep '^com\\.jetbrains\\..*Benchmark\\.' | sort "
    "-u > \"/tmp/mandatory-output/$side-inventory.txt\"",
    "./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests "
    "-Dspotless.check.skip=true -Dcentral.skip=true -Djmh.args=\"${SELECTION} -e "
    "$FIXED_EXCLUSION ${PARAM} -rf json -rff "
    "/tmp/mandatory-output/$side-results.json\" 2>&1 | tee "
    "\"/tmp/mandatory-output/$side.log\"",
    "./mvnw -pl jmh-ldbc -am package -DskipTests -Dspotless.check.skip=true -q",
    "./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests "
    "-Dspotless.check.skip=true -Dcentral.skip=true "
    "-Djmh.args=\"LdbcSingleThreadIC.*ic5_newGroups -f 0 -wi 0 -i 1 -r 1s -t 1\" "
    "2>&1 | tee /tmp/jmh-load-${{ github.run_id }}.txt",
    "./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests "
    "-Dspotless.check.skip=true -Dcentral.skip=true -Djmh.args=\"-e "
    "LdbcSingleThreadBothEBenchmark,LdbcGremlinTranslatorBenchmark -f 0 -wi 0 -i "
    "1 -r 5s -t 1\" 2>&1 | tee /tmp/jmh-warm-${{ github.run_id }}.txt",
    "./mvnw -pl jmh-ldbc -am verify -P bench -DskipTests "
    "-Dspotless.check.skip=true -Dcentral.skip=true -Djmh.args=\"-e "
    "LdbcSingleThreadBothEBenchmark,LdbcGremlinTranslatorBenchmark -rf json -rff "
    "/tmp/jmh-results-${{ github.run_id }}.json\" 2>&1 | tee "
    "/tmp/jmh-bench-log-${{ github.run_id }}.txt",
))


def logical_lines(value):
  return re.sub(r"\\\n[ \t]*", " ", value).split("\n")


def assert_selection_isolated(text, expected_jobs, exempt_jobs=(), trusted=False):
  """Check literal workflow selection in both source text and decoded YAML.

  Runtime shell assembly, workflow expressions, GitHub YAML interpretation and
  effects from other files or programs are outside this literal check.
  """
  def require(condition, message):
    if not condition:
      raise AssertionError(message)

  def fields(node):
    require(isinstance(node, yaml.MappingNode), "Expected a mapping")
    pairs = node.value
    require(all(isinstance(k, yaml.ScalarNode) for k, _ in pairs), "Non-scalar key")
    keys = [k.value for k, _ in pairs]
    require(len(keys) == len(set(keys)), "Duplicate mapping key")
    require("<<" not in keys, "Merge key")
    return dict((k.value, v) for k, v in pairs)

  try:
    root = yaml.compose(text, Loader=yaml.BaseLoader)
  except yaml.YAMLError as error:
    raise AssertionError("Invalid or multi-document workflow") from error
  top = fields(root)
  require("jobs" in top, "Missing jobs mapping")
  jobs = fields(top["jobs"])
  require(sorted(jobs) == sorted(expected_jobs), "Unexpected job keys")
  require(set(exempt_jobs) <= set(jobs), "Unknown exempt job")
  run_path = None
  if trusted:
    resolver = fields(jobs["resolve"])
    steps = resolver.get("steps")
    require(isinstance(steps, yaml.SequenceNode), "Missing resolver steps")
    preflights = [(index, fields(step)) for index, step in enumerate(steps.value)
                  if fields(step).get("name") is not None
                  and fields(step)["name"].value == PREFLIGHT]
    require(len(preflights) == 1, "Expected one preflight")
    index, preflight = preflights[0]
    run_path = ("jobs", "resolve", "steps", index, "run")
    require("if" not in preflight and "continue-on-error" not in preflight,
            "Preflight must not be optional")
    run_node = preflight.get("run")
    require(isinstance(run_node, yaml.ScalarNode), "Missing preflight run")
    require(run_node.value.count(TRUSTED_IMPORT) == 1, "Expected exact trusted import")

  for name in exempt_jobs:
    job = fields(jobs[name])
    condition = "inputs.mode == 'telemetry'"
    if name == "telemetry-validate":
      condition = ("!cancelled() && " + condition
                   + " && needs.telemetry-benchmark.outputs.artifact != ''")
    require(job.get("if") is not None and job["if"].value == condition,
            "Exempt job must be telemetry-only")

  def scan(value, scope):
    for forbidden in FORBIDDEN:
      require(forbidden not in value, "Forbidden selection: " + forbidden)
    for line in logical_lines(value):
      if MAVEN_TOKEN.search(line):
        require(scope == "benchmark", "Maven outside benchmark jobs")
        require(" ".join(line.split()) in MAVEN_COMMANDS,
                "Maven command must match the exact allowlist")

  scalars = set()

  def walk(node, path=(), active=None):
    active = set() if active is None else active
    require(id(node) not in active, "Cyclic YAML alias")
    active.add(id(node))
    exempt = len(path) >= 2 and path[0] == "jobs" and path[1] in exempt_jobs
    scope = path[1] if len(path) >= 2 and path[0] == "jobs" else None
    if isinstance(node, yaml.ScalarNode):
      scalars.add(node)
      if not exempt:
        value = node.value
        # The exemption belongs to this path, not to an aliased node identity.
        if trusted and path == run_path:
          value = value.replace(TRUSTED_IMPORT, "", 1)
        scan(value, scope)
    elif isinstance(node, yaml.MappingNode):
      fields(node)
      for key, value in node.value:
        child = path + (key.value,)
        walk(key, child, active)
        walk(value, child, active)
    elif isinstance(node, yaml.SequenceNode):
      for index, value in enumerate(node.value):
        walk(value, path + (index,), active)
    active.remove(id(node))

  walk(root)

  # Scalar tokens start after tags and anchors, unlike composed node marks.
  # Keep those properties and block headers in source so comments there count.
  tokens = {token.end_mark.index: token for token in yaml.scan(text, Loader=yaml.BaseLoader)
            if isinstance(token, yaml.ScalarToken)}
  pieces = []
  end = 0
  for node in sorted(scalars, key=lambda scalar: scalar.start_mark.index):
    token = tokens.get(node.end_mark.index)
    # Implicit empty values have a composed node but no scalar token.
    if token is None:
      require(node.value == "" and node.start_mark.index == node.end_mark.index,
              "Missing scalar token")
      continue
    start = token.start_mark.index
    if node.style in ("|", ">"):
      newline = text.find("\n", start)
      start = newline + 1 if newline != -1 else node.end_mark.index
    pieces.append(text[end:start])
    end = node.end_mark.index
  pieces.append(text[end:])
  source_text = "".join(pieces)
  # Maven prose is forbidden in source syntax, not in decoded scalar values.
  require(re.search(r"\bmaven\b", source_text, re.IGNORECASE) is None,
          "Maven outside scalar values")
  scan(source_text, None)


class WorkflowContractTest(unittest.TestCase):

  def setUp(self):
    path = ROOT / ".github/workflows/ldbc-jmh-compare.yml"
    self.text = path.read_text()
    self.workflow = yaml.load(self.text, Loader=yaml.BaseLoader)

  def test_selection_isolation_on_real_workflows(self):
    """Normal jobs and nightly stay isolated while telemetry jobs are opt-in."""
    assert_selection_isolated(self.text, COMPARE_JOBS, TELEMETRY_JOBS, trusted=True)
    nightly = (ROOT / ".github/workflows/ldbc-jmh-nightly.yml").read_text()
    assert_selection_isolated(nightly, ("benchmark",))

  def test_selection_isolation_canaries(self):
    """Reject literal selection leaks and accept exempt block content and benign YAML."""
    source = self.text
    def step(body):
      return source.replace("    steps:\n", "    steps:\n" + body, 1)
    def job(body, name="resolve"):
      header = "  " + name + ":\n"
      return source.replace(header, header + body, 1)
    def env_scalar(value, name="resolve"):
      return job("    env:\n      SCRIPT: " + value + "\n", name)
    mutations = {
        "c1 normal-mode alternative": source.replace(
            "if: inputs.mode == 'telemetry'",
            "if: inputs.mode == 'telemetry' || inputs.mode == 'normal'", 1),
        "c2 direct resolver launch": step(
            "      - run: python3 jmh-telemetry/telemetry_run.py\n"),
        "c3 env-bound resolver launch": step(
            '      - run: python3 "$SCRIPT"\n'
            "        env:\n          SCRIPT: jmh-telemetry/telemetry_run.py\n"),
        "c4 with value": step(
            "      - uses: example/action@v1\n"
            "        with:\n          script: telemetry_run.py\n"),
        "c5 job-level env": env_scalar("jmh-telemetry/telemetry_run.py"),
        "c6 action reference": step("      - uses: ./jmh-telemetry/action\n"),
        "c7 other job scalar": job("    extra: telemetry-benchmarks.jar\n"),
        "c8 resolver build": step(
            "      - run: ./mvnw -pl jmh-telemetry -am package\n"),
        "c9 import outside preflight": step(
            "      - run: from telemetry_compare import CATALOG\n"),
        "c10 extra module path": source.replace(
            "              from telemetry_compare import CATALOG\n",
            '              from telemetry_compare import CATALOG\n'
            '              sys.path.insert(0, "jmh-telemetry")\n', 1),
        "c11 inline job comment": source.replace(
            "  validate:\n", "  validate:  # signer\n"
            "    extra: jmh-telemetry\n"),
        "block scalar": step(
            "      - run: echo ok\n        env:\n"
            "          SCRIPT: |-\n            jmh-telemetry/telemetry_run.py\n"),
        "hash step env scalar": step(
            '      - run: python3 "${SCRIPT:1}" --help\n'
            "        env:\n          SCRIPT: |-\n"
            "            #jmh-telemetry/telemetry_run.py\n"),
        "hash job env scalar": env_scalar(
            "|-\n        #jmh-telemetry/telemetry_run.py"),
        "quoted job key": source.replace("  resolve:\n", '  "resolve":\n'
            "    extra: jmh-telemetry\n", 1),
        "quoted nested key": job('    env:\n      "SCRIPT": telemetry_run.py\n'),
        "quoted step key": step('      - "run": python3 telemetry_run.py\n'),
        "split trusted import": source.replace(
            'sys.path.insert(0, "jmh-telemetry")',
            'sys.path.insert(\n                  0, "jmh-telemetry")', 1),
        "blank line in trusted import": source.replace(
            'sys.path.insert(0, "jmh-telemetry")\n',
            'sys.path.insert(0, "jmh-telemetry")\n\n', 1),
        "second preflight": step("      - name: " + PREFLIGHT + "\n        run: echo ok\n"),
        "step shell": step(
            "      - run: echo ok\n        shell: jmh-telemetry/telemetry_run.py {0}\n"),
        "job defaults shell": job(
            "    defaults:\n      run:\n        shell: jmh-telemetry/telemetry_run.py {0}\n"),
        "hash resolve shell line": step(
            "      - run: |\n          #jmh-telemetry/telemetry_run.py\n"),
        "hash signer env scalar": env_scalar("|-\n        #telemetry_run.py", "validate"),
        "hash benchmark env scalar": env_scalar("|-\n        #telemetry_run.py", "benchmark"),
        "telemetry alias into signer": source.replace(
            "  telemetry-benchmark:\n", "  telemetry-benchmark:\n"
            "    env: &telemetry_env\n      SCRIPT: telemetry_run.py\n", 1).replace(
                "  validate:\n", "  validate:\n    env: *telemetry_env\n", 1),
        "workflow env": source.replace(
            "env:\n", "env:\n  SCRIPT: telemetry_run.py\n", 1),
        "workflow defaults shell": source.replace(
            "jobs:\n", "defaults:\n  run:\n"
            "    shell: jmh-telemetry/telemetry_run.py {0}\n\njobs:\n", 1),
        "input default expression": source.replace(
            "        default: ''", "        default: telemetry_run.py", 1).replace(
                "    steps:\n", "    steps:\n"
                "      - run: python3 ${{ inputs.commit_sha }}\n", 1),
        "double-quoted escape": env_scalar('"jmh-telemetr\\x79/telemetry_run.py"'),
        "decoded-only escape": env_scalar('"jmh-telemetr\\x79/tool.py"'),
        "duplicate run": step(
            "      - run: echo ok\n        run: python3 telemetry_run.py\n"),
        "duplicate benign run": step("      - run: echo ok\n        run: echo other\n"),
        "duplicate job": source + "\n  resolve:\n    steps: []\n",
        "duplicate jobs mapping": source + "\njobs: {}\n",
        "second YAML document": source + "\n---\njobs: {}\n",
        "merge key": job("    <<: {env: {SCRIPT: telemetry_run.py}}\n"),
        "benign merge key": job("    <<: {env: {SCRIPT: harmless}}\n"),
        "cyclic alias": job("    env: &cycle {SELF: *cycle}\n"),
        "non-scalar key": job("    env: {? [one, two]: harmless}\n"),
        "workflow comment": source + "\n# telemetry_run.py\n",
        "unscoped Maven": source.replace("env:\n", "env:\n  CMD: ./mvnw package\n", 1),
        "resolver LDBC build": step("      - run: ./mvnw -pl jmh-ldbc -am package\n"),
        "resolver plain Maven": step("      - run: mvn package\n"),
        "resolver selected plain Maven": step("      - run: mvn -pl jmh-ldbc -am package\n"),
        "workflow selected Maven comment": source + "\n# mvnw -pl jmh-ldbc -am package\n",
        "workflow selected plain Maven comment": source + "\n# mvn -pl jmh-ldbc -am package\n",
        "gap selected Maven comment": source.replace(
            "  validate:\n", "    # mvnw -pl jmh-ldbc -am package\n\n  validate:\n", 1),
        "normal broad build": job("    extra: ./mvnw package\n", "benchmark"),
        "comment before normal validate": source.replace(
            "  validate:\n", "# jmh-telemetry/telemetry_run.py\n  validate:\n", 1),
        "comment between exempt jobs": source.replace(
            "  telemetry-validate:\n",
            "# jmh-telemetry/telemetry_run.py\n  telemetry-validate:\n", 1),
        "comment after last exempt job": source.replace(
            "  validate:\n", "    # jmh-telemetry/telemetry_run.py\n\n  validate:\n", 1),
        "bare Maven after shell line": job(
            "    env:\n      CMD: |\n        echo ok\n        mvnw package\n", "benchmark"),
        "trusted run alias into benchmark": source.replace(
            "        run: |\n          python3 - <<'PY'",
            "        run: &catalog_run |\n          python3 - <<'PY'", 1).replace(
                "  benchmark:\n", "  benchmark:\n    env: {SCRIPT: *catalog_run}\n", 1),
    }
    for command in (
        'env PATH="$PWD:$PATH" mvnw package -DskipTests',
        "foo; mvnw package", "$(mvnw package)", 'bash -c "mvnw package"',
        "mvn package", "foo && mvnw package", "foo || mvnw package",
        "foo | mvnw package", "`mvnw package`", "echo mvnw package",
        "./mvnw package; ./mvnw -pl jmh-ldbc -am package",
        "chmod +x mvnw; mvnw package", "env PATH=x mvn package",
    ):
      mutations["Maven token: " + command] = job(
          "    env:\n      CMD: |-\n        " + command + "\n", "benchmark")
    selected = "./mvnw -pl jmh-ldbc -am"
    for suffix in (
        "--projects=core --also-make-dependents", "-amd",
        "--also-make-dependents", "--projects .", "-pl .",
        "-f core/pom.xml", "-rf core", "--proj core -amd",
        "-pl jmh-ldbc,core -amd",
    ):
      mutations["widening: " + suffix] = source.replace(
          selected + " clean", selected + " " + suffix + " clean", 1)
    mutations["continued widening"] = source.replace(
        selected + " clean", selected + " \\\n              -amd clean", 1)
    for command in (
        "mvnd package", "mvnDebug package", "/opt/maven/bin/mvn package",
        "sudo " + selected + " package", "echo package | xargs ./mvnw",
        "./scripts/../mvnw package", '"./mvnw" package', "sh mvnw package",
        "./mvnw clean verify", "export MAVEN_ARGS=-amd",
        'echo "-amd" >> .mvn/maven.config',
        selected + " clean package -DskipTests -Dspotless.check.skip=true -q; ./mvnw package",
        "$(" + selected + " clean package -DskipTests -Dspotless.check.skip=true -q)",
        "true && " + selected + " clean package -DskipTests -Dspotless.check.skip=true -q",
    ):
      mutations["literal Maven: " + command] = job(
          "    env:\n      CMD: |-\n        " + command + "\n", "benchmark")
    mutations["job MAVEN_ARGS"] = job(
        "    env:\n      MAVEN_ARGS: -amd\n", "benchmark")
    mutations["workflow MAVEN_ARGS"] = source.replace(
        "env:\n", "env:\n  MAVEN_ARGS: -amd\n", 1)
    for indent in range(13):
      for token in ("jmh-telemetry/telemetry_run.py", "mvn"):
        comment = " " * indent + "# " + token + "\n"
        mutations[f"gap comment {indent}: {token}"] = source.replace(
            "  validate:\n", comment + "  validate:\n", 1)
        mutations[f"EOF comment {indent}: {token}"] = source + "\n" + comment
    mutations["normal block header comment"] = step(
        "      - run: | # jmh-telemetry\n          echo ok\n")
    mutations["exempt job comment"] = job(
        "    # jmh-telemetry\n", "telemetry-benchmark")
    # Properties are source syntax, not decoded scalar values.
    mutations["telemetry anchor"] = env_scalar("&jmh-telemetry harmless")
    mutations["telemetry tag"] = env_scalar("!jmh-telemetry harmless")
    mutations["unicode escape"] = env_scalar('"jmh-telemetr\\u0079/tool.py"')
    # Maven comments are forbidden at EOF, at job boundaries and inside normal jobs.
    for token in ("Maven", "maven"):
      comment = "# " + token + "\n"
      mutations["Maven EOF comment: " + token] = source + "\n" + comment
      mutations["Maven job boundary comment: " + token] = source.replace(
          "  benchmark:\n", comment + "  benchmark:\n", 1)
      mutations["Maven normal job comment: " + token] = job(
          "    " + comment, "benchmark")
    for name, mutation in mutations.items():
      with self.subTest(mutation=name):
        self.assertNotEqual(source, mutation, name)
        with self.assertRaises(AssertionError):
          assert_selection_isolated(mutation, COMPARE_JOBS, TELEMETRY_JOBS, trusted=True)

    # A final shell comment is block-scalar content, not a YAML comment.
    must_pass = {}
    for name in TELEMETRY_JOBS:
      next_job = "telemetry-validate" if name == "telemetry-benchmark" else "validate"
      header = "  " + next_job + ":\n"
      for style in ("|", "|-", "|+", ">", ">-"):
        body = ("      - run: " + style + "\n          echo ok\n"
                "          # jmh-telemetry/telemetry_run.py\n")
        must_pass[name + " final " + style] = source.replace(header, body + header, 1)
      for label, body in (
          ("comment-only", "      - run: |\n          # jmh-telemetry/telemetry_run.py\n"),
          ("trailing blanks", "      - run: |+\n          # jmh-telemetry/telemetry_run.py\n\n\n"),
      ):
        must_pass[name + " " + label] = source.replace(header, body + header, 1)
    must_pass.update({
        "flow mapping": job("    env: {SCRIPT: harmless}\n"),
        "implicit empty scalar": env_scalar(""),
        "empty block": job("    extra: |\n", "telemetry-benchmark"),
        "quoted key": source.replace("  resolve:\n", '  "resolve":\n', 1),
        "tab scalar": env_scalar('"harmless\tvalue"'),
        "string tag": env_scalar("!!str harmless"),
        "harmless comments": job("    # harmless\n") + "\n# harmless\n",
        "harmless alias": job(
            "    env: {FIRST: &safe harmless, SECOND: *safe}\n"),
        "alias from exempt job": job(
            "    env: {FIRST: &safe harmless}\n", "telemetry-benchmark").replace(
                "  validate:\n", "  validate:\n    env: {SECOND: *safe}\n", 1),
        "extra Maven whitespace": source.replace(
            selected + " clean", "./mvnw  -pl\tjmh-ldbc   -am clean", 1),
    })
    for name in TELEMETRY_JOBS:
      # Moving an exempt job to EOF must not change its scalar exemption.
      document = yaml.compose(source, Loader=yaml.BaseLoader)
      jobs_node = dict((key.value, value) for key, value in document.value)["jobs"]
      pairs = jobs_node.value
      index = next(i for i, (key, _) in enumerate(pairs) if key.value == name)
      start = source.rfind("\n", 0, pairs[index][0].start_mark.index) + 1
      end = source.rfind("\n", 0, pairs[index + 1][0].start_mark.index) + 1
      moved = source[:start] + source[end:] + "\n" + source[start:end]
      for ending in ("\n", ""):
        must_pass[name + " EOF " + repr(ending)] = (
            moved.rstrip() + "\n      - run: |\n"
            "          # jmh-telemetry/telemetry_run.py" + ending)
    for name, mutation in must_pass.items():
      with self.subTest(accepted=name):
        self.assertNotEqual(source, mutation, name)
        assert_selection_isolated(mutation, COMPARE_JOBS, TELEMETRY_JOBS, trusted=True)

    nightly = (ROOT / ".github/workflows/ldbc-jmh-nightly.yml").read_text()
    nightly_mutations = {
        "nightly widening": nightly.replace(
            "./mvnw -pl jmh-ldbc -am package",
            "./mvnw -pl jmh-ldbc -am -amd package", 1),
        "nightly MAVEN_ARGS": nightly.replace(
            "env:\n", "env:\n  MAVEN_ARGS: -amd\n", 1),
        "nightly escape": nightly.replace(
            "env:\n", 'env:\n  SCRIPT: "jmh-telemetr\\x79/tool.py"\n', 1),
        "nightly hash scalar": nightly.replace(
            "env:\n", "env:\n  SCRIPT: |-\n    #telemetry_run.py\n", 1),
    }
    # Nightly has one job, so its job-boundary comment goes before that job.
    for token in ("Maven", "maven"):
      comment = "# " + token + "\n"
      nightly_mutations["Maven EOF comment: " + token] = nightly + "\n" + comment
      nightly_mutations["Maven job boundary comment: " + token] = nightly.replace(
          "  benchmark:\n", comment + "  benchmark:\n", 1)
      nightly_mutations["Maven normal job comment: " + token] = nightly.replace(
          "  benchmark:\n", "  benchmark:\n    " + comment, 1)
    for name, mutation in nightly_mutations.items():
      with self.subTest(mutation=name):
        self.assertNotEqual(nightly, mutation, name)
        with self.assertRaises(AssertionError):
          assert_selection_isolated(mutation, ("benchmark",))

  def test_manual_inputs_are_xor_and_concurrency_never_cancels(self):
    """Manual dispatch exposes PR or SHA targets without a branch backdoor or cancellation."""
    inputs = self.workflow["on"]["workflow_dispatch"]["inputs"]
    self.assertEqual(
        {"pr_number", "commit_sha", "queries", "gremlin_arms", "runner_cores", "mode"},
        set(inputs))
    self.assertNotIn("base_branch", inputs)
    self.assertEqual("false", self.workflow["concurrency"]["cancel-in-progress"])
    self.assertIn("pr-", self.workflow["concurrency"]["group"])
    self.assertIn("sha-", self.workflow["concurrency"]["group"])


  def evaluate(self, expression, values):
    """Evaluate the workflow's boolean, format and context expression subset."""
    def evaluate_part(source):
      tokens = re.findall(r"'[^']*'|[A-Za-z_][A-Za-z0-9_.-]*|!=|==|&&|\|\||[!(),]", source)
      translated = []
      for token in tokens:
        if token in ("&&", "||", "!"):
          translated.append({"&&": "and", "||": "or", "!": "not"}[token])
        elif token.startswith("'") or token in ("!=", "==", "(", ")", ","):
          translated.append(token)
        elif token == "format":
          translated.append("format_value")
        elif token in ("always", "cancelled"):
          translated.append(token)
        else:
          self.assertIn(token, values, "Unknown workflow context: " + token)
          translated.append(repr(values[token]))
      return eval(" ".join(translated), {"__builtins__": {}},
                  {"format_value": lambda template, value: template.format(value),
                   "always": lambda: True, "cancelled": lambda: values.get("cancelled", False)})
    if "${{" in expression:
      return re.sub(r"\$\{\{(.*?)\}\}", lambda m: str(evaluate_part(m[1])), expression)
    return evaluate_part(expression)

  def test_computed_concurrency_keys_are_disjoint_for_every_entry_point(self):
    """Telemetry never shares a pending-run key with any normal target or entry point."""
    normal, telemetry = set(), set()
    for entry, pr, arms in itertools.product(
        ("workflow_dispatch", "workflow_call"), ("17", ""), ("on", "both")):
      values = {"github.event_name": entry, "inputs.pr_number": pr,
                "inputs.commit_sha": "" if pr else "a" * 40, "inputs.gremlin_arms": arms}
      for mode in ("", "normal", "telemetry"):
        values["inputs.mode"] = mode
        key = self.evaluate(self.workflow["concurrency"]["group"], values)
        if mode == "telemetry":
          telemetry.add(key)
        else:
          normal.add(key)
          self.assertEqual("ldbc-jmh-" + ("pr-17" if pr else "sha-" + "a" * 40), key)
    self.assertTrue(normal.isdisjoint(telemetry))
    # No job-level group may silently put telemetry back in a normal pending queue.
    for job in self.workflow["jobs"].values():
      self.assertNotIn("concurrency", job)

  def test_real_job_conditions_and_permissions_exclude_telemetry_from_signing(self):
    """Even a successful benchmark with normal completion inputs cannot enable the signer."""
    jobs = self.workflow["jobs"]
    for mode in ("", "normal", "telemetry"):
      values = {"inputs.mode": mode, "needs.benchmark.result": "success",
                "needs.telemetry-benchmark.outputs.artifact": "artifact"}
      for name in ("retrieve-dataset", "benchmark", "validate"):
        self.assertEqual(mode != "telemetry", self.evaluate(jobs[name]["if"], values))
      for name in ("telemetry-benchmark", "telemetry-validate"):
        self.assertEqual(mode == "telemetry", self.evaluate(jobs[name]["if"], values))
    for name in ("telemetry-benchmark", "telemetry-validate"):
      job = jobs[name]
      self.assertTrue(all(v == "read" for v in job["permissions"].values()))
      self.assertNotIn("secrets.", str(job))
      for step in job["steps"]:
        self.assertNotIn("${{", step.get("run", ""))
        self.assertNotIn("mandatory-benchmark.py completion", str(step))
        if "upload-artifact@" in step.get("uses", ""):
          self.assertEqual("90", step["with"]["retention-days"])
    self.assertEqual("resolve", jobs["telemetry-benchmark"]["needs"])
    self.assertIn(MODULE.RUNNER_PROFILES["8"]["label"],
                  jobs["telemetry-benchmark"]["runs-on"])
    self.assertEqual({"contents": "read"}, jobs["telemetry-validate"]["permissions"])
    condition = jobs["telemetry-validate"]["if"]
    for cancelled, artifact in itertools.product((False, True), ("", "artifact")):
      self.assertEqual(not cancelled and bool(artifact), bool(self.evaluate(condition, {
          "inputs.mode": "telemetry", "cancelled": cancelled,
          "needs.telemetry-benchmark.outputs.artifact": artifact})))

  def test_real_preflight_refuses_unavailable_catalog_and_wrong_machine_before_allocation(self):
    """Run the actual trusted resolver shell, without GitHub or benchmark-runner access."""
    steps = self.workflow["jobs"]["resolve"]["steps"]
    preflight = next(s for s in steps if s.get("name", "").startswith("Check telemetry"))
    # Absent conditions use success(), so the preflight cannot be skipped or ignored.
    self.assertNotIn("if", preflight)
    self.assertNotIn("continue-on-error", preflight)
    resolver = self.workflow["jobs"]["resolve"]
    self.assertNotIn("if", resolver)
    self.assertNotIn("continue-on-error", resolver)
    benchmark = self.workflow["jobs"]["telemetry-benchmark"]
    self.assertEqual("resolve", benchmark["needs"])
    # No status function overrides GitHub's implicit success() dependency gate.
    self.assertEqual("inputs.mode == 'telemetry'", benchmark["if"])
    self.assertLess(next(i for i, s in enumerate(steps) if s is preflight),
                    next(i for i, s in enumerate(steps) if s.get("id") == "identity"))
    cases = (("normal", "32", "workflow_call", 0, ""),
             ("telemetry", "32", "workflow_dispatch", 1, "fixed eight-vCPU"),
             ("telemetry", "8", "workflow_dispatch", 1, "not available"),
             ("telemetry", "8", "workflow_call", 1, "manual dispatch"),
             ("bogus", "8", "workflow_dispatch", 1, "invalid comparison mode"))
    for mode, cores, entry, status, message in cases:
      values = {"inputs.mode": mode, "inputs.runner_cores": cores, "github.event_name": entry}
      env = dict(os.environ, **{k: self.evaluate(v, values) for k, v in preflight["env"].items()})
      run = subprocess.run(["bash", "-e", "-c", preflight["run"]], cwd=ROOT, env=env,
                           text=True, capture_output=True)
      self.assertEqual(status, run.returncode, run.stderr)
      self.assertIn(message, run.stderr)


  def test_real_execution_and_validation_shells_keep_producer_identity(self):
    """Offline command stubs trace the real shells across a later validation attempt."""
    jobs = self.workflow["jobs"]
    producer = next(s for s in jobs["telemetry-benchmark"]["steps"] if s.get("id") == "identity")
    execution = next(s for s in jobs["telemetry-benchmark"]["steps"]
                     if s.get("name", "").startswith("Build candidate"))
    validation = next(s for s in jobs["telemetry-validate"]["steps"]
                      if s.get("name") == "Render with trusted validator")
    with tempfile.TemporaryDirectory() as directory:
      root = Path(directory)
      (root / "candidate").mkdir()
      (root / "bin").mkdir()
      (root / "scratch").mkdir()
      # Only command stubs execute. No Maven, Java, GitHub or candidate code runs.
      scripts = {
          "candidate/mvnw": '#!/bin/sh\necho "$PWD $*" >> "$TRACE/maven"\n',
          "bin/git": '#!/bin/sh\ncase "$*" in *rev-parse*) echo "$REVISION";; esac\n',
          "bin/python3": "#!" + sys.executable + "\n" + (
              "import json, os, pathlib, sys\n"
              "with open(os.environ['TRACE'] + '/commands', 'a') as f:\n"
              "    f.write(json.dumps(sys.argv[1:]) + '\\n')\n"
              "if '--output' in sys.argv:\n"
              "    pathlib.Path(sys.argv[sys.argv.index('--output') + 1]).write_text('report')\n"),
      }
      for name, content in scripts.items():
        path = root / name
        path.write_text(content)
        path.chmod(0o755)
      values = {"needs.resolve.outputs.head_sha": "a" * 40}
      env = dict(os.environ, PATH=str(root / "bin") + ":" + os.environ["PATH"],
                 TRACE=str(root), GITHUB_RUN_ID="11", GITHUB_RUN_ATTEMPT="2",
                 GITHUB_WORKSPACE=str(root), RUNNER_TEMP=str(root / "scratch"),
                 GITHUB_OUTPUT=str(root / "outputs"), GITHUB_STEP_SUMMARY=str(root / "summary"))
      def run_step(step):
        env.update({k: self.evaluate(v, values) for k, v in step.get("env", {}).items()})
        subprocess.run(["bash", "-e", "-c", step["run"]], cwd=root, env=env, check=True)
      run_step(producer)
      outputs = dict(line.split("=", 1) for line in (root / "outputs").read_text().splitlines())
      values["steps.identity.outputs.artifact"] = outputs["artifact"]
      run_step(execution)
      values.update({"needs.telemetry-benchmark.outputs.artifact": outputs["artifact"],
                     "needs.telemetry-benchmark.outputs.producing_attempt": outputs["attempt"]})
      env["GITHUB_RUN_ATTEMPT"] = "9"
      run_step(validation)
      execute, validate = [json.loads(line) for line in (root / "commands").read_text().splitlines()]
      for command in (execute, validate):
        for flag, value in (("--run", "11"), ("--producing-attempt", "2"),
                            ("--revision", "a" * 40), ("--artifact", outputs["artifact"]),
                            ("--repetitions", "2")):
          self.assertEqual(value, command[command.index(flag) + 1])
      self.assertEqual("9", validate[validate.index("--validation-attempt") + 1])
      self.assertEqual(MODULE.RUNNER_PROFILES["8"]["label"].replace("-", "_"),
                       execute[execute.index("--machine-type") + 1])
      self.assertIn("candidate -pl jmh-telemetry -am clean package",
                    (root / "maven").read_text())
      self.assertEqual("report", (root / "summary").read_text())

  def test_completion_command_uses_real_mode_predicate_without_changing_record(self):
    """Otherwise valid fields serialize identically in normal mode and fail in telemetry."""
    fields = CompletionTest().fields()
    # Fixed wire bytes are independent of the current serializer and its constants.
    normal = (
        b'{"base_sha":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",'
        b'"baseline_sha":"cccccccccccccccccccccccccccccccccccccccc",'
        b'"completed_at":"2026-09-18T00:00:00Z","executed_repository":"fork/youtrackdb",'
        b'"gremlin_arms":"on","head_sha":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",'
        b'"pr_number":12,"queries":"","repository":"JetBrains/youtrackdb",'
        b'"result_digest":"sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",'
        b'"validation_contract":"ldbc-pr-completion-v3",'
        b'"validator_revision":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",'
        b'"validator_workflow":"JetBrains/youtrackdb/.github/workflows/ldbc-jmh-compare.yml"}\n')
    self.assertEqual(normal, MODULE.canonical_completion(fields))
    self.assertEqual(normal, MODULE.canonical_completion(fields, "normal"))
    self.assertNotIn("mode", json.loads(normal))
    with tempfile.TemporaryDirectory() as directory:
      source, output = Path(directory) / "fields", Path(directory) / "completion"
      source.write_text(json.dumps(fields))
      for mode in ("telemetry", None, "normal"):
        mode_args = [] if mode is None else ["--mode", mode]
        run = subprocess.run(["python3", str(SCRIPT), "completion", *mode_args,
                              "--fields", str(source), "--output", str(output)],
                             text=True, capture_output=True)
        self.assertEqual(1 if mode == "telemetry" else 0, run.returncode, run.stderr)
        if mode == "telemetry":
          self.assertFalse(output.exists())
        else:
          self.assertEqual(normal, output.read_bytes())

  def test_telemetry_artifact_bindings_preserve_attempts_and_missing_evidence_fails(self):
    """Uploads retain evidence for 90 days and validation reruns read the producer bundle."""
    jobs = self.workflow["jobs"]
    producer = next(s for s in jobs["telemetry-benchmark"]["steps"]
                    if s.get("uses", "").startswith("actions/upload-artifact@"))
    download = next(s for s in jobs["telemetry-validate"]["steps"]
                    if s.get("uses", "").startswith("actions/download-artifact@"))
    report = next(s for s in jobs["telemetry-validate"]["steps"]
                  if s.get("uses", "").startswith("actions/upload-artifact@"))
    self.assertEqual("${{ steps.identity.outputs.artifact }}", producer["with"]["name"])
    self.assertEqual("${{ needs.telemetry-benchmark.outputs.artifact }}",
                     download["with"]["name"])
    self.assertEqual(
        "telemetry-report_${{ github.run_id }}_"
        "${{ needs.telemetry-benchmark.outputs.producing_attempt }}_"
        "${{ needs.resolve.outputs.head_sha }}_validation${{ github.run_attempt }}",
        report["with"]["name"])
    # Same-run downloads need no API token or elevated actions permission.
    self.assertEqual({"name", "path"}, set(download["with"]))
    for upload in (producer, report):
      self.assertEqual("error", upload["with"]["if-no-files-found"])
      self.assertEqual("90", upload["with"]["retention-days"])

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
    self.assertEqual("actions/setup-java@v6", setup["uses"])
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
