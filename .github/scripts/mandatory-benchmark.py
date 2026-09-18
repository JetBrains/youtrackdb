#!/usr/bin/env python3
"""Validate comparison targets, filters, results, and signed completion records."""

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import tempfile

CONTRACT = "ldbc-pr-completion-v3"
WORKFLOW_PATH = ".github/workflows/ldbc-jmh-compare.yml"
WORKFLOW_IDENTITY = "JetBrains/youtrackdb/.github/workflows/ldbc-jmh-compare.yml"
FIXED_EXCLUSION = "LdbcSingleThreadBothEBenchmark"
COMPLETION_MARKER = "<!-- ldbc-jmh-completion-v3 -->"
MAX_COMPLETION_BYTES = 16 * 1024
MAX_BUNDLE_BYTES = 48 * 1024
MAX_COMMENT_BYTES = 64 * 1024
MAX_RESULT_JSON_BYTES = 128 * 1024 * 1024
QUERY_IDS = {f"is{number}" for number in range(1, 8)} | {
    f"ic{number}" for number in range(1, 14)}
RUNNER_PROFILES = {
    "8": {"label": "type-ccx33", "memory_gb": 32},
    "32": {"label": "type-ccx53", "memory_gb": 128},
}


class ContractError(RuntimeError):
  """A benchmark contract was not satisfied."""


def select_target(pr_number, commit_sha):
  pr = str(pr_number or "").strip()
  sha = str(commit_sha or "").strip().lower()
  if bool(pr) == bool(sha):
    raise ContractError("provide exactly one pull request number or commit SHA")
  if pr:
    if not pr.isdigit() or int(pr) < 1:
      raise ContractError("pull request number must be a positive integer")
    return "pr", pr
  if not re.fullmatch(r"[0-9a-f]{40}", sha):
    raise ContractError("commit SHA must contain exactly 40 hexadecimal characters")
  return "sha", sha


def resolve_runner_profile(cores):
  requested = str(cores or "8")
  if requested not in RUNNER_PROFILES:
    raise ContractError("runner cores must be exactly 8 or 32")
  return {"requested_cores": int(requested), **RUNNER_PROFILES[requested]}


def parse_query_tokens(queries):
  if queries == "":
    return []
  raw_tokens = queries.split(",")
  if any(not token.strip() for token in raw_tokens):
    raise ContractError("query selection contains an empty token")
  normalized = []
  for raw in raw_tokens:
    token = raw.strip().lower()
    if any(character.isspace() for character in token):
      raise ContractError(f"query token contains internal whitespace: {raw!r}")
    if token != "gremlin" and token not in QUERY_IDS:
      raise ContractError(f"unsupported query identifier: {raw!r}")
    if token not in normalized:
      normalized.append(token)
  return normalized


def query_patterns(queries):
  patterns = []
  for token in parse_query_tokens(queries):
    if token == "gremlin":
      patterns.append(".*LdbcGremlinTranslatorBenchmark.*")
    else:
      patterns.append(f".*{token}_.*")
  return patterns


def shell_filter_args(queries):
  return " ".join(shlex.quote(pattern) for pattern in query_patterns(queries))


def has_exact_maintainer_approval(reviews, permissions, head_sha):
  latest = {}
  for position, review in enumerate(reviews):
    login = review.get("user", {}).get("login") if isinstance(review, dict) else None
    if not login or review.get("state") not in {
        "APPROVED", "CHANGES_REQUESTED", "DISMISSED"}:
      continue
    order = (review.get("submitted_at") or "", review.get("id") or position)
    if login not in latest or order > latest[login][0]:
      latest[login] = order, review
  return any(
      permissions.get(login) in {"maintain", "admin"}
      and review.get("state") == "APPROVED"
      and review.get("commit_id") == head_sha
      for login, (_, review) in latest.items())


def run_git(repository, *arguments, check=True):
  result = subprocess.run(
      ["git", "-C", str(repository), *arguments], text=True,
      stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
  if check and result.returncode != 0:
    raise ContractError(result.stderr.strip() or "git command failed")
  return result


def resolve_owned_commit(repository, sha):
  if not re.fullmatch(r"[0-9a-f]{40}", sha):
    raise ContractError("commit SHA must contain exactly 40 lowercase hexadecimal characters")
  run_git(repository, "cat-file", "-e", f"{sha}^{{commit}}")
  refs = run_git(
      repository, "for-each-ref", "--format=%(refname)",
      "refs/remotes/origin", "refs/tags").stdout.splitlines()
  owned = any(
      run_git(repository, "merge-base", "--is-ancestor", sha, ref, check=False).returncode == 0
      for ref in refs if ref != "refs/remotes/origin/HEAD")
  if not owned:
    raise ContractError("commit is not reachable from a repository-owned branch or tag")
  fields = run_git(repository, "rev-list", "--parents", "-n", "1", sha).stdout.split()
  if len(fields) < 2:
    raise ContractError("root commits cannot be compared because they have no first parent")
  return fields[1]


def select_attempt_artifact(artifacts, prefix, max_attempt):
  pattern = re.compile(rf"^{re.escape(prefix)}-attempt([0-9]+)$")
  candidates = []
  for artifact in artifacts:
    if not isinstance(artifact, dict):
      continue
    match = pattern.fullmatch(artifact.get("name", ""))
    if (match and not artifact.get("expired")
        and int(match.group(1)) <= max_attempt
        and isinstance(artifact.get("id"), int)):
      candidates.append((int(match.group(1)), artifact["id"]))
  if not candidates:
    raise ContractError(f"no live {prefix} artifact exists for this attempt")
  return max(candidates)[1]


def install_canonical_inputs(source, destination):
  source_path = Path(source)
  destination_path = Path(destination)
  destination_path.mkdir(parents=True, exist_ok=True)
  factor_target = destination_path / "factor-tables.json"
  curated_target = destination_path / "curated-params-v3.json"
  shutil.copy2(source_path / factor_target.name, factor_target)
  shutil.copy2(source_path / curated_target.name, curated_target)
  factor_time = factor_target.stat().st_mtime_ns
  curated_time = curated_target.stat().st_mtime_ns
  if curated_time < factor_time:
    curated_target.touch()
  if factor_target.stat().st_mtime_ns > curated_target.stat().st_mtime_ns:
    raise ContractError("canonical parameter timestamps are invalid")


def load_json(path, max_bytes=MAX_RESULT_JSON_BYTES):
  try:
    with Path(path).open("rb") as stream:
      data = stream.read(max_bytes + 1)
    if len(data) > max_bytes:
      raise ContractError(f"JSON input exceeds its size limit: {path}")
    return json.loads(data)
  except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
    raise ContractError(f"cannot read valid JSON from {path}: {error}") from error


def load_inventory(path):
  value = load_json(path)
  if not isinstance(value, list) or not value:
    raise ContractError(f"inventory {path} must be a nonempty list")
  inventory = set()
  for item in value:
    if not isinstance(item, str) or not item or FIXED_EXCLUSION in item:
      raise ContractError(f"inventory {path} contains an invalid benchmark")
    inventory.add(item)
  return inventory


def result_key(row):
  if not isinstance(row, dict) or not isinstance(row.get("benchmark"), str):
    raise ContractError("each result needs a benchmark name")
  params = row.get("params") or {}
  metric = row.get("primaryMetric")
  score = metric.get("score") if isinstance(metric, dict) else None
  try:
    finite_score = (
        not isinstance(score, bool)
        and isinstance(score, (int, float))
        and math.isfinite(float(score)))
  except OverflowError:
    finite_score = False
  try:
    if isinstance(metric, dict) and "scoreError" in metric:
      float(metric["scoreError"])
  except OverflowError as error:
    raise ContractError("score error exceeds the renderer numeric range") from error
  except (TypeError, ValueError):
    # The renderer intentionally treats unsupported and missing uncertainty as zero.
    pass
  if not isinstance(params, dict) or not finite_score:
    raise ContractError("each result needs parameters and a finite numeric score")
  return row["benchmark"], tuple(sorted((str(key), str(value)) for key, value in params.items()))


def expected_keys(inventory, gremlin_arms):
  keys = set()
  for name in inventory:
    if "LdbcGremlinTranslatorBenchmark" in name:
      values = ("true",) if gremlin_arms == "on" else ("true", "false")
      keys.update((name, (("translatorEnabled", value),)) for value in values)
    else:
      keys.add((name, ()))
  return keys


def validate_side(results_path, inventory_path, gremlin_arms):
  rows = load_json(results_path)
  if not isinstance(rows, list) or not rows:
    raise ContractError("benchmark results must be a nonempty list")
  inventory = load_inventory(inventory_path)
  keys = [result_key(row) for row in rows]
  if len(keys) != len(set(keys)) or set(keys) != expected_keys(inventory, gremlin_arms):
    raise ContractError("benchmark results do not match the expected inventory and parameters")
  return {
      "inventory": sorted(inventory),
      "measurements": len(keys),
      "parameter_sets": [
          {"benchmark": name, "params": dict(params)} for name, params in sorted(keys)],
  }


def validate_comparison(arguments):
  base = validate_side(arguments.base_results, arguments.base_inventory, arguments.gremlin_arms)
  head = validate_side(arguments.head_results, arguments.head_inventory, arguments.gremlin_arms)
  return {
      "base": base,
      "head": head,
      "additions": sorted(set(head["inventory"]) - set(base["inventory"])),
      "removals": sorted(set(base["inventory"]) - set(head["inventory"])),
  }


def canonical_completion(fields):
  required = {
      "repository", "pr_number", "executed_repository", "head_sha", "base_sha",
      "baseline_sha", "queries", "gremlin_arms", "validation_contract",
      "result_digest", "validator_workflow", "validator_revision", "completed_at"}
  if not isinstance(fields, dict) or set(fields) != required:
    raise ContractError("completion record fields do not match the contract")
  for key in required:
    if not isinstance(fields[key], (str, int)) or isinstance(fields[key], bool):
      raise ContractError(f"completion field has an invalid type: {key}")
  if (fields["queries"] != "" or fields["gremlin_arms"] != "on"
      or fields["validation_contract"] != CONTRACT
      or fields["validator_workflow"] != WORKFLOW_IDENTITY):
    raise ContractError("only a full production-arm PR comparison can complete")
  for key in ("head_sha", "base_sha", "baseline_sha", "validator_revision"):
    if not re.fullmatch(r"[0-9a-f]{40}", str(fields[key])):
      raise ContractError(f"completion field is not a full SHA: {key}")
  if not re.fullmatch(r"sha256:[0-9a-f]{64}", str(fields["result_digest"])):
    raise ContractError("completion result digest is invalid")
  try:
    timestamp = datetime.fromisoformat(str(fields["completed_at"]).replace("Z", "+00:00"))
    if not str(fields["completed_at"]).endswith("Z") or timestamp.tzinfo != timezone.utc:
      raise ValueError("timestamp is not UTC")
  except ValueError as error:
    raise ContractError("completion timestamp is invalid") from error
  payload = json.dumps(fields, sort_keys=True, separators=(",", ":"), ensure_ascii=True) + "\n"
  encoded = payload.encode("utf-8")
  if len(encoded) > MAX_COMPLETION_BYTES:
    raise ContractError("completion record exceeds its size limit")
  return encoded


def completion_comment(completion_bytes, bundle_bytes):
  if len(completion_bytes) > MAX_COMPLETION_BYTES or len(bundle_bytes) > MAX_BUNDLE_BYTES:
    raise ContractError("signed completion record exceeds comment limits")
  body = (
      f"{COMPLETION_MARKER}\n"
      "## LDBC JMH signed completion\n\n"
      f"completion-base64: `{base64.b64encode(completion_bytes).decode()}`\n\n"
      f"bundle-base64: `{base64.b64encode(bundle_bytes).decode()}`\n")
  if len(body.encode("utf-8")) > MAX_COMMENT_BYTES:
    raise ContractError("signed completion comment exceeds its size limit")
  return body


def parse_completion_comment(body):
  if not isinstance(body, str) or COMPLETION_MARKER not in body:
    raise ContractError("comment is not a completion record")
  completion_match = re.search(r"completion-base64: `([A-Za-z0-9+/=]+)`", body)
  bundle_match = re.search(r"bundle-base64: `([A-Za-z0-9+/=]+)`", body)
  if not completion_match or not bundle_match:
    raise ContractError("completion comment payload is malformed")
  try:
    completion = base64.b64decode(completion_match.group(1), validate=True)
    bundle = base64.b64decode(bundle_match.group(1), validate=True)
  except ValueError as error:
    raise ContractError("completion comment encoding is invalid") from error
  if len(completion) > MAX_COMPLETION_BYTES or len(bundle) > MAX_BUNDLE_BYTES:
    raise ContractError("completion comment payload exceeds its size limit")
  try:
    fields = json.loads(completion)
  except json.JSONDecodeError as error:
    raise ContractError("completion JSON is invalid") from error
  if canonical_completion(fields) != completion:
    raise ContractError("completion JSON bytes are not canonical")
  return completion, bundle, fields


def verify_attestation(completion_path, bundle_path, repository, signer_digest, gh="gh"):
  command = [
      gh, "attestation", "verify", str(completion_path), "--bundle", str(bundle_path),
      "--repo", repository, "--signer-workflow", WORKFLOW_IDENTITY,
      "--signer-digest", signer_digest, "--format", "json"]
  result = subprocess.run(command, text=True, capture_output=True, check=False)
  if result.returncode != 0:
    raise ContractError(result.stderr.strip() or "attestation verification failed")
  try:
    output = json.loads(result.stdout)
  except json.JSONDecodeError as error:
    raise ContractError("attestation verifier returned invalid JSON") from error
  if not isinstance(output, list) or not output:
    raise ContractError("attestation verifier returned no trusted attestations")
  return output


def find_verified_completion(comments, expected, allowed_revisions, verifier=verify_attestation):
  if not isinstance(comments, list) or not isinstance(expected, dict):
    raise ContractError("completion discovery inputs have invalid shapes")
  for comment in comments:
    if not isinstance(comment, dict):
      continue
    user = comment.get("user")
    if not isinstance(user, dict) or user.get("login") != "github-actions[bot]":
      continue
    try:
      completion, bundle, fields = parse_completion_comment(comment.get("body"))
      if any(fields.get(key) != value for key, value in expected.items()):
        continue
      revision = fields.get("validator_revision")
      if revision not in allowed_revisions:
        continue
      with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        completion_path = root / "completion.json"
        bundle_path = root / "bundle.json"
        completion_path.write_bytes(completion)
        bundle_path.write_bytes(bundle)
        verifier(completion_path, bundle_path, fields["repository"], revision)
      return fields
    except (ContractError, OSError):
      continue
  raise ContractError("no valid signed completion record was found")


def parser():
  root = argparse.ArgumentParser()
  commands = root.add_subparsers(dest="command", required=True)
  target = commands.add_parser("target")
  target.add_argument("--pr-number", default="")
  target.add_argument("--commit-sha", default="")
  runner = commands.add_parser("runner-profile")
  runner.add_argument("--cores", default="8")
  filters = commands.add_parser("filter")
  filters.add_argument("--queries", default="")
  filters.add_argument("--format", choices=("json", "shell"), default="json")
  owned = commands.add_parser("owned-commit")
  owned.add_argument("--repository", required=True)
  owned.add_argument("--sha", required=True)
  selector = commands.add_parser("select-artifact")
  selector.add_argument("--artifacts", required=True)
  selector.add_argument("--prefix", required=True)
  selector.add_argument("--max-attempt", type=int, required=True)
  install = commands.add_parser("install-inputs")
  install.add_argument("--source", required=True)
  install.add_argument("--destination", required=True)
  approval = commands.add_parser("approval")
  approval.add_argument("--reviews", required=True)
  approval.add_argument("--permissions", required=True)
  approval.add_argument("--head-sha", required=True)
  validation = commands.add_parser("validate")
  for name in ("base-results", "head-results", "base-inventory", "head-inventory"):
    validation.add_argument(f"--{name}", required=True)
  validation.add_argument("--gremlin-arms", choices=("on", "both"), default="on")
  validation.add_argument("--output", required=True)
  completion = commands.add_parser("completion")
  completion.add_argument("--fields", required=True)
  completion.add_argument("--output", required=True)
  comment = commands.add_parser("comment")
  comment.add_argument("--completion", required=True)
  comment.add_argument("--bundle", required=True)
  comment.add_argument("--output", required=True)
  return root


def main():
  arguments = parser().parse_args()
  try:
    if arguments.command == "target":
      mode, value = select_target(arguments.pr_number, arguments.commit_sha)
      print(json.dumps({"mode": mode, "value": value}))
    elif arguments.command == "runner-profile":
      print(json.dumps(resolve_runner_profile(arguments.cores), sort_keys=True))
    elif arguments.command == "filter":
      value = shell_filter_args(arguments.queries) if arguments.format == "shell" \
          else json.dumps(query_patterns(arguments.queries))
      print(value)
    elif arguments.command == "owned-commit":
      print(resolve_owned_commit(arguments.repository, arguments.sha))
    elif arguments.command == "select-artifact":
      artifacts = load_json(arguments.artifacts)
      if not isinstance(artifacts, list):
        raise ContractError("artifact response must be a list")
      print(select_attempt_artifact(
          artifacts, arguments.prefix, arguments.max_attempt))
    elif arguments.command == "install-inputs":
      install_canonical_inputs(arguments.source, arguments.destination)
    elif arguments.command == "approval":
      reviews = load_json(arguments.reviews)
      permissions = load_json(arguments.permissions)
      if not isinstance(reviews, list) or not isinstance(permissions, dict):
        raise ContractError("approval inputs have invalid JSON shapes")
      if not has_exact_maintainer_approval(reviews, permissions, arguments.head_sha):
        raise ContractError("the exact fork head needs an approving maintainer review")
    elif arguments.command == "validate":
      result = validate_comparison(arguments)
      Path(arguments.output).write_text(json.dumps(result, sort_keys=True) + "\n")
    elif arguments.command == "completion":
      fields = load_json(arguments.fields)
      Path(arguments.output).write_bytes(canonical_completion(fields))
    else:
      body = completion_comment(
          Path(arguments.completion).read_bytes(), Path(arguments.bundle).read_bytes())
      Path(arguments.output).write_text(body)
  except (ContractError, OSError) as error:
    print(f"error: {error}", file=sys.stderr)
    return 1
  return 0


if __name__ == "__main__":
  sys.exit(main())
