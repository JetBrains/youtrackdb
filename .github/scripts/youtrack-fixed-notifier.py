#!/usr/bin/env python3
"""Mark YouTrack issues fixed for the commits in a deployed range.

Walks the commit titles in a git range (typically "since the last
successful deploy" .. "the commit this run deployed"), extracts every
YTDB issue reference, and for each referenced issue:

  1. Reads the issue's current State.
  2. Skips the issue if that State is already resolved (Fixed, Verified,
     Closed, Won't fix, Duplicate, Obsolete, Done, ...). "Resolved" is
     decided by YouTrack's own `isResolved` flag on the state value, not
     by a hardcoded name list, so the set stays correct as states are
     added or renamed.
  3. Otherwise adds a "Fixed in <version>" comment and moves the State to
     Fixed.

Issue references are matched as `YTDB-<n>` (case-insensitive) anywhere in
a commit title, which covers all the shapes the repo produces:
`YTDB-123: ...`, `[YTDB-123] ...`, and the comma-separated pair
`YTDB-123, YTDB-456: ...`. Duplicate references across the range are
processed once.

A single bad issue (network error, transition rejected) does not abort
the run: the failure is logged and the script exits non-zero at the end
so the CI step is marked failed for follow-up, while every other issue is
still processed.

Usage:
    YOUTRACK_TOKEN=perm:... python3 youtrack-fixed-notifier.py \
        --from-sha <last-success-sha> \
        --to-sha <deployed-sha> \
        --version 0.5.0-20260529.101500-abc1234-dev-SNAPSHOT

    # Preview without writing anything (GETs still run if a token is set,
    # so skip decisions are shown; without a token only the extracted
    # issue list is printed):
    python3 youtrack-fixed-notifier.py \
        --from-sha <sha> --to-sha HEAD --version <v> --dry-run
"""

import argparse
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

DEFAULT_BASE_URL = "https://youtrack.jetbrains.com"

# `\b` anchors keep us from matching inside a larger token (e.g. a stray
# "MYTDB-1"); the digit run is the issue number. Case-insensitive because
# PR-title / branch-hook prefixing is normalized to upper case but we do
# not want to depend on that.
ISSUE_RE = re.compile(r"\bYTDB-(\d+)\b", re.IGNORECASE)

# Per-issue outcomes, used both for logging and for the test suite's
# assertions.
NOT_FOUND = "not_found"
SKIPPED_RESOLVED = "skipped_resolved"
WOULD_UPDATE = "would_update"
UPDATED = "updated"


def extract_issue_ids(titles):
    """Return the unique YTDB issue IDs across the given commit titles.

    Order is preserved (first occurrence wins) so the log reads in commit
    order; IDs are normalized to upper case so `ytdb-9` and `YTDB-9`
    collapse to one entry.
    """
    seen = []
    seen_set = set()
    for title in titles:
        for match in ISSUE_RE.finditer(title):
            issue_id = "YTDB-" + match.group(1)
            if issue_id not in seen_set:
                seen_set.add(issue_id)
                seen.append(issue_id)
    return seen


def get_commit_titles(from_sha, to_sha, repo_dir):
    """Return the subject line of every non-merge commit in from..to."""
    result = subprocess.run(
        ["git", "log", f"{from_sha}..{to_sha}", "--no-merges", "--format=%s"],
        cwd=repo_dir,
        check=True,
        capture_output=True,
        text=True,
    )
    return [line for line in result.stdout.splitlines() if line.strip()]


class YouTrackError(Exception):
    """A non-recoverable error talking to the YouTrack REST API."""


class YouTrackClient:
    """Thin REST wrapper over the YouTrack issue + comment endpoints."""

    def __init__(self, base_url, token):
        self.base_url = base_url.rstrip("/")
        self.token = token

    def _request(self, method, path, body=None):
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/json",
        }
        if data is not None:
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req) as resp:
                payload = resp.read().decode("utf-8")
                return resp.status, (json.loads(payload) if payload else None)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")
            return exc.code, detail

    def get_issue_state(self, issue_id):
        """Return {found, state, is_resolved} for the issue's State field.

        `found` is False on a 404 (typo'd or cross-project reference). A
        present issue with no State value yields state=None / is_resolved
        =False so it is treated as unresolved and gets updated.
        """
        path = f"/api/issues/{issue_id}?fields=customFields(name,value(name,isResolved))"
        status, data = self._request("GET", path)
        if status == 404:
            return {"found": False, "state": None, "is_resolved": False}
        if status >= 400 or not isinstance(data, dict):
            raise YouTrackError(f"GET {issue_id} returned {status}: {data}")
        for field in data.get("customFields", []):
            if field.get("name") == "State":
                value = field.get("value")
                if not value:
                    return {"found": True, "state": None, "is_resolved": False}
                return {
                    "found": True,
                    "state": value.get("name"),
                    "is_resolved": bool(value.get("isResolved")),
                }
        # Issue exists but exposes no State field at all.
        return {"found": True, "state": None, "is_resolved": False}

    def add_comment(self, issue_id, text):
        path = f"/api/issues/{issue_id}/comments?fields=id"
        status, data = self._request("POST", path, {"text": text})
        if status >= 400:
            raise YouTrackError(f"comment on {issue_id} returned {status}: {data}")

    def set_state_fixed(self, issue_id):
        path = f"/api/issues/{issue_id}?fields=id"
        body = {
            "customFields": [
                {
                    "name": "State",
                    "$type": "StateIssueCustomField",
                    "value": {"name": "Fixed"},
                }
            ]
        }
        status, data = self._request("POST", path, body)
        if status >= 400:
            raise YouTrackError(f"set State on {issue_id} returned {status}: {data}")


def process_issue(client, issue_id, version, dry_run):
    """Apply the fixed-notification policy to a single issue.

    Returns one of the module-level outcome constants. Raises on a hard
    API error so the caller can record it as a failure.
    """
    info = client.get_issue_state(issue_id)
    if not info["found"]:
        return NOT_FOUND
    if info["is_resolved"]:
        return SKIPPED_RESOLVED
    if dry_run:
        return WOULD_UPDATE
    client.add_comment(issue_id, f"Fixed in {version}")
    client.set_state_fixed(issue_id)
    return UPDATED


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--from-sha", required=True, help="Range start (exclusive)")
    parser.add_argument("--to-sha", default="HEAD", help="Range end (inclusive)")
    parser.add_argument("--version", required=True, help="Deployed version string")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--repo-dir", default=".")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)

    # No prior successful deploy (first run) or an empty boundary: nothing
    # to diff against, so this is a clean no-op rather than an error.
    if not args.from_sha:
        print("No from-sha given (no prior successful deploy); nothing to do.")
        return 0

    titles = get_commit_titles(args.from_sha, args.to_sha, args.repo_dir)
    issue_ids = extract_issue_ids(titles)
    print(
        f"Range {args.from_sha}..{args.to_sha}: {len(titles)} commit(s), "
        f"{len(issue_ids)} referenced issue(s)."
    )
    if not issue_ids:
        return 0

    token = os.environ.get("YOUTRACK_TOKEN")
    if not token:
        if args.dry_run:
            print("No YOUTRACK_TOKEN set; listing referenced issues only "
                  "(cannot check resolved state):")
            for issue_id in issue_ids:
                print(f"  would consider {issue_id}")
            return 0
        print("ERROR: YOUTRACK_TOKEN is not set.", file=sys.stderr)
        return 1

    client = YouTrackClient(args.base_url, token)
    counts = {NOT_FOUND: 0, SKIPPED_RESOLVED: 0, WOULD_UPDATE: 0, UPDATED: 0}
    failures = []
    for issue_id in issue_ids:
        try:
            outcome = process_issue(client, issue_id, args.version, args.dry_run)
        except (YouTrackError, urllib.error.URLError) as exc:
            failures.append(issue_id)
            print(f"  FAILED  {issue_id}: {exc}", file=sys.stderr)
            continue
        counts[outcome] += 1
        if outcome == NOT_FOUND:
            print(f"  skip    {issue_id}: not found")
        elif outcome == SKIPPED_RESOLVED:
            print(f"  skip    {issue_id}: already resolved")
        elif outcome == WOULD_UPDATE:
            print(f"  dry-run {issue_id}: would comment + set Fixed")
        else:
            print(f"  updated {issue_id}: commented + set Fixed in {args.version}")

    print(
        "Summary: "
        f"{counts[UPDATED]} updated, "
        f"{counts[WOULD_UPDATE]} would-update, "
        f"{counts[SKIPPED_RESOLVED]} already-resolved, "
        f"{counts[NOT_FOUND]} not-found, "
        f"{len(failures)} failed."
    )
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
