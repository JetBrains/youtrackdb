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
processed once. A commit whose subject begins with `Revert ` is skipped:
reverting a change undoes it, so it must not re-mark the reverted issue as
fixed (git and GitHub both produce the canonical `Revert "<subject>"` form).

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
import urllib.parse
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

# Cap on how much of a YouTrack response body we put into an error message.
# Those messages are printed to a public-by-default CI log, and an API error
# body can echo back request context; GitHub's secret masking only redacts the
# exact token string, not adjacent content, so we keep the logged slice small.
_MAX_ERROR_DETAIL = 200


def _truncate(detail, limit=_MAX_ERROR_DETAIL):
    """Shorten a server response body for safe, bounded CI logging."""
    text = str(detail)
    if len(text) > limit:
        return f"{text[:limit]}... [{len(text)} chars, truncated]"
    return text


def extract_issue_ids(titles):
    """Return the unique YTDB issue IDs across the given commit titles.

    Order is preserved (first occurrence wins) so the log reads in commit
    order; IDs are normalized to upper case so `ytdb-9` and `YTDB-9`
    collapse to one entry.
    """
    seen = []
    seen_set = set()
    for title in titles:
        # A revert undoes a change, so a "Revert "...""  commit must not
        # re-mark the reverted issue as fixed. Skip the canonical git/GitHub
        # revert subject; a title that merely contains the word later (e.g.
        # "YTDB-7: revert the cache flag") is unaffected.
        if title.lstrip().startswith("Revert "):
            continue
        for match in ISSUE_RE.finditer(title):
            issue_id = "YTDB-" + match.group(1)
            if issue_id not in seen_set:
                seen_set.add(issue_id)
                seen.append(issue_id)
    return seen


def is_ancestor(from_sha, to_sha, repo_dir):
    """Return True only if `from_sha` is reachable as an ancestor of `to_sha`.

    This guards the range diff against a rewritten develop. If the recorded
    last-success SHA is no longer an ancestor of the deployed SHA — develop was
    rebased/force-pushed and the old SHA was garbage-collected, or it now sits
    on an abandoned line of history — then `git log from..to` would either fail
    with an unknown-object error (exit 128) or silently compute the
    `merge-base(from,to)..to` range and mark the wrong set of issues fixed.
    `git merge-base --is-ancestor` distinguishes the three cases by exit code:
    0 = ancestor, 1 = reachable but not an ancestor, 128 = unknown object. Only
    exit 0 is safe to diff, so anything else is treated as "do not run".
    """
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", from_sha, to_sha],
        cwd=repo_dir,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


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


class _AuthStrippingRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Strip the Authorization header on a cross-host redirect.

    `urllib` follows redirects automatically and, by default, re-sends every
    original request header — including `Authorization: Bearer <token>` — to
    the redirect target. A 30x from the YouTrack host to a different host would
    otherwise forward the token to that host. We keep the header on a same-host
    redirect and drop it the moment the host changes.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req is not None:
            old_host = urllib.parse.urlsplit(req.full_url).netloc
            new_host = urllib.parse.urlsplit(newurl).netloc
            if old_host != new_host:
                # Request stores header keys capitalized ("Authorization").
                new_req.headers.pop("Authorization", None)
        return new_req


class YouTrackClient:
    """Thin REST wrapper over the YouTrack issue + comment endpoints."""

    def __init__(self, base_url, token):
        # Refuse to send the Bearer token over a non-TLS connection.
        scheme = urllib.parse.urlsplit(base_url).scheme
        if scheme != "https":
            raise ValueError(
                f"base-url must be https (got {scheme or 'no scheme'!r}); "
                "refusing to send the YouTrack token in cleartext."
            )
        self.base_url = base_url.rstrip("/")
        self.token = token
        # Own opener so a cross-host redirect cannot forward the token.
        self._opener = urllib.request.build_opener(_AuthStrippingRedirectHandler())

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
            with self._opener.open(req) as resp:
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
            raise YouTrackError(f"GET {issue_id} returned {status}: {_truncate(data)}")
        # `or []` covers both an absent key and an explicit null; the per-field
        # isinstance guards tolerate a null array entry or a non-object value
        # so an abnormal payload yields "unresolved" rather than crashing the
        # whole run with a TypeError/AttributeError that bypasses the caller's
        # per-issue handler.
        for field in data.get("customFields") or []:
            if not isinstance(field, dict):
                continue
            if field.get("name") == "State":
                value = field.get("value")
                if not isinstance(value, dict) or not value:
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
            raise YouTrackError(f"comment on {issue_id} returned {status}: {_truncate(data)}")

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
            raise YouTrackError(f"set State on {issue_id} returned {status}: {_truncate(data)}")


def process_issue(client, issue_id, version, dry_run):
    """Apply the fixed-notification policy to a single issue.

    Returns one of the module-level outcome constants. Propagates
    `YouTrackError` from the client on a hard API error so the caller can
    record the issue as a failure and continue with the rest.
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

    # If develop was rewritten, the recorded last-success SHA may no longer be
    # an ancestor of the deployed SHA. Diffing such a range would crash or
    # silently compute the wrong commit set (see is_ancestor), so skip cleanly.
    if not is_ancestor(args.from_sha, args.to_sha, args.repo_dir):
        print(
            f"from-sha {args.from_sha} is not an ancestor of {args.to_sha} "
            "(history rewritten or SHA unreachable); nothing to do.",
            file=sys.stderr,
        )
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
        except (YouTrackError, urllib.error.URLError, ValueError) as exc:
            # ValueError also covers a malformed (non-JSON) 200 body, whose
            # json.loads failure propagates out of _request. One bad issue is
            # recorded and the loop continues to the next.
            failures.append(issue_id)
            print(f"  FAILED  {issue_id}: {_truncate(exc)}", file=sys.stderr)
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
