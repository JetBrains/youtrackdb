#!/usr/bin/env python3
"""Stand-alone tests for youtrack-fixed-notifier.py.

Run directly, with no pytest dependency, like the other .github/scripts
helpers:

    python3 .github/scripts/youtrack-fixed-notifier-test.py

Exit code 0 means all checks passed; non-zero prints the failing cases.
Test functions (any module-level `test_*`) are discovered automatically, so
adding one needs no edit to `main()`. The notifier module is loaded by path
because its filename contains hyphens and is not importable as a normal module.
"""

import contextlib
import importlib.util
import os
import pathlib
import sys
import urllib.request

_MODULE_PATH = pathlib.Path(__file__).with_name("youtrack-fixed-notifier.py")
_spec = importlib.util.spec_from_file_location("notifier", _MODULE_PATH)
notifier = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(notifier)

# Repo root = <repo>/.github/scripts/<this file>; used by the is_ancestor test
# so it works regardless of the caller's cwd (CI runs from the repo root, but a
# local run may not).
_REPO_DIR = str(_MODULE_PATH.parents[2])


class FakeClient:
    """In-memory stand-in for YouTrackClient.

    `states` maps issue ID -> (state_name, is_resolved); a state_name of None
    models a found issue that exposes no State value. A missing key models a
    404 (issue not found). `comments` and `set_fixed` record writes for "did it
    happen" assertions; `calls` records them in order for ordering assertions.
    """

    def __init__(self, states):
        self.states = states
        self.comments = []
        self.set_fixed = []
        self.calls = []

    def get_issue_state(self, issue_id):
        if issue_id not in self.states:
            return {"found": False, "state": None, "is_resolved": False}
        name, is_resolved = self.states[issue_id]
        return {"found": True, "state": name, "is_resolved": is_resolved}

    def add_comment(self, issue_id, text):
        self.comments.append((issue_id, text))
        self.calls.append(("comment", issue_id, text))

    def set_state_fixed(self, issue_id):
        self.set_fixed.append(issue_id)
        self.calls.append(("set_fixed", issue_id))


_failures = []


def check(name, condition):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}")
        _failures.append(name)


# --- helpers ---------------------------------------------------------------


def _raises_youtrack_error(fn):
    """True iff calling `fn` raises notifier.YouTrackError."""
    try:
        fn()
        return False
    except notifier.YouTrackError:
        return True


def _client_returning(status, data):
    """A real YouTrackClient whose _request yields a canned (status, data).

    Lets the tests drive the genuine get_issue_state parser (the FakeClient
    seam bypasses it everywhere else) with no network.
    """
    client = notifier.YouTrackClient("https://example.test", "token")
    client._request = lambda method, path, body=None: (status, data)
    return client


@contextlib.contextmanager
def _patched_attr(obj, name, value):
    """Temporarily replace obj.name with value, restoring it afterward."""
    original = getattr(obj, name)
    setattr(obj, name, value)
    try:
        yield
    finally:
        setattr(obj, name, original)


@contextlib.contextmanager
def _env(name, value):
    """Set env var `name` to `value` (or delete it when value is None) for the
    duration of the block, then restore the previous value."""
    original = os.environ.get(name)
    if value is None:
        os.environ.pop(name, None)
    else:
        os.environ[name] = value
    try:
        yield
    finally:
        if original is None:
            os.environ.pop(name, None)
        else:
            os.environ[name] = original


# --- extract_issue_ids -----------------------------------------------------


def test_extract_issue_ids():
    """The recognized YTDB reference shapes are extracted, deduplicated in
    first-occurrence order, and normalized to upper case; a token with a larger
    leading prefix (MYTDB-1) is rejected by the leading word boundary."""
    # Colon form, the most common shape on develop.
    check("colon form",
          notifier.extract_issue_ids(["YTDB-123: fix a bug"]) == ["YTDB-123"])
    # Bracket form, also produced by the PR-title prefixer.
    check("bracket form",
          notifier.extract_issue_ids(["[YTDB-958] do a thing"]) == ["YTDB-958"])
    # Comma-separated pair in a single title -> both issues.
    check("comma pair",
          notifier.extract_issue_ids(["YTDB-1, YTDB-2: two issues"]) == ["YTDB-1", "YTDB-2"])
    # A PR-number suffix uses '#', not YTDB-, so it must not match.
    check("no false positive on pr number",
          notifier.extract_issue_ids(["Tidy things (#1101)"]) == [])
    # A title with no reference at all.
    check("no reference",
          notifier.extract_issue_ids(["Set default effort to xhigh"]) == [])
    # The same issue across several commits collapses to one entry, order kept.
    check("dedup across commits, order preserved",
          notifier.extract_issue_ids(
              ["YTDB-5: part one", "YTDB-3: other", "YTDB-5: part two"]) == ["YTDB-5", "YTDB-3"])
    # Lower-case reference is normalized to upper case.
    check("case-insensitive normalize",
          notifier.extract_issue_ids(["ytdb-9: lower"]) == ["YTDB-9"])
    # Leading `\b` prevents matching inside a larger leading token.
    check("no match inside larger leading token",
          notifier.extract_issue_ids(["MYTDB-1: noise"]) == [])


def test_extract_issue_ids_boundaries():
    """Boundary and degenerate inputs: the trailing word boundary rejects a
    digit run glued to letters or an underscore, an empty range and a bare
    'YTDB-' yield nothing, a repeated reference within one title dedups, and
    surrounding punctuation is tolerated."""
    # Trailing `\b`: letters right after the digits break the match, so a glued
    # build/coordinate token is not mis-parsed as an issue (symmetric to the
    # MYTDB-1 leading-boundary case).
    check("no match with trailing letters",
          notifier.extract_issue_ids(["YTDB-12abc: x"]) == [])
    # '_' is a word character too, so the trailing boundary also rejects this.
    check("no match with trailing underscore",
          notifier.extract_issue_ids(["YTDB-5_x: x"]) == [])
    # An empty range (e.g. only merge commits, all filtered out) yields no IDs.
    check("empty input", notifier.extract_issue_ids([]) == [])
    # A bare 'YTDB-' with no number must not match (the digit group is required).
    check("no digits after prefix",
          notifier.extract_issue_ids(["YTDB-: nothing"]) == [])
    # The same issue twice within a single title collapses to one entry.
    check("dedup within one title",
          notifier.extract_issue_ids(["YTDB-5 and YTDB-5 again"]) == ["YTDB-5"])
    # Surrounding punctuation is fine.
    check("parenthesized ref matches",
          notifier.extract_issue_ids(["(YTDB-5) done"]) == ["YTDB-5"])


def test_extract_issue_ids_skips_reverts():
    """A revert commit must not re-mark the reverted issue as fixed: a subject
    beginning with 'revert ' (matched case-insensitively) is skipped entirely.
    Covers the canonical git/GitHub form ('Revert "<original>"') and a
    hand-written lower-case revert. A title that merely contains the word
    'revert' later is unaffected."""
    check("revert title skipped",
          notifier.extract_issue_ids(['Revert "YTDB-5: fix a bug"']) == [])
    # The match is case-insensitive, so a hand-written lower-case revert subject
    # is skipped too.
    check("lower-case revert title skipped",
          notifier.extract_issue_ids(['revert "YTDB-6: fix a bug"']) == [])
    # A revert alongside a genuine fix in the same range: only the fix counts.
    check("revert skipped, real fix kept",
          notifier.extract_issue_ids(
              ['Revert "YTDB-5: fix"', "YTDB-6: real fix"]) == ["YTDB-6"])
    # 'revert' appearing mid-title (not as the subject prefix) still matches.
    check("word revert mid-title still matches",
          notifier.extract_issue_ids(["YTDB-7: revert the cache flag"]) == ["YTDB-7"])


# --- is_ancestor (the rewritten-develop range guard) -----------------------


def test_is_ancestor():
    """is_ancestor gates the range diff: a commit is its own ancestor (git exit
    0 -> True), while an unknown SHA (git exit 128) is rejected (-> False) so a
    rewritten or garbage-collected boundary becomes a clean no-op in main()
    instead of a crash or a silently wrong range."""
    check("HEAD is its own ancestor",
          notifier.is_ancestor("HEAD", "HEAD", _REPO_DIR) is True)
    check("unknown sha is not an ancestor",
          notifier.is_ancestor("0" * 40, "HEAD", _REPO_DIR) is False)


# --- get_issue_state (the real parser, via a canned _request) --------------


def test_get_issue_state_parsing():
    """get_issue_state turns a raw YouTrack response into {found, state,
    is_resolved}. Exercises the real parser: the 404 skip, the hard-error raise
    on 5xx and malformed bodies, the missing/null/empty State fallbacks, the
    robustness path for a null customFields, and the isResolved-flag (not the
    state name) deciding resolution."""
    # 404 -> a benign "not found" skip, never a hard failure.
    check("404 -> not found",
          _client_returning(404, "Not Found").get_issue_state("YTDB-1")["found"] is False)
    # 500 -> hard failure so the CI step is flagged, not silently skipped.
    check("500 -> raises",
          _raises_youtrack_error(lambda: _client_returning(500, "boom").get_issue_state("YTDB-1")))
    # A non-dict 200 body is treated as a hard failure.
    check("malformed body -> raises",
          _raises_youtrack_error(
              lambda: _client_returning(200, "not-an-object").get_issue_state("YTDB-1")))
    # Issue present but no State field at all -> unresolved (so it gets updated).
    no_state = _client_returning(
        200, {"customFields": [{"name": "Priority", "value": {"name": "Major"}}]}
    ).get_issue_state("YTDB-1")
    check("no State field -> found and unresolved",
          no_state["found"] and not no_state["is_resolved"] and no_state["state"] is None)
    # State present with a null value -> unresolved.
    null_value = _client_returning(
        200, {"customFields": [{"name": "State", "value": None}]}
    ).get_issue_state("YTDB-1")
    check("null State value -> unresolved",
          null_value["found"] and null_value["state"] is None and not null_value["is_resolved"])
    # Robustness: a null customFields (not the normal []) must not crash.
    null_fields = _client_returning(200, {"customFields": None}).get_issue_state("YTDB-1")
    check("null customFields -> found and unresolved",
          null_fields["found"] and not null_fields["is_resolved"])
    # Resolution is decided by the isResolved flag, not the state name: a
    # non-obvious resolved name like 'Obsolete' is still skipped.
    obsolete_value = {"name": "Obsolete", "isResolved": True}
    obsolete = _client_returning(
        200, {"customFields": [{"name": "State", "value": obsolete_value}]}
    ).get_issue_state("YTDB-1")
    check("isResolved flag drives skip regardless of name", obsolete["is_resolved"])
    # A value with no isResolved key defaults to unresolved.
    open_value = _client_returning(
        200, {"customFields": [{"name": "State", "value": {"name": "Open"}}]}
    ).get_issue_state("YTDB-1")
    check("missing isResolved -> unresolved",
          open_value["state"] == "Open" and not open_value["is_resolved"])


# --- YouTrackClient construction -------------------------------------------


def test_client_timeout_configured():
    """YouTrackClient carries a per-request timeout so an unresponsive YouTrack
    server fails the CI step fast instead of hanging for the full runner budget:
    the default is 30s and an explicit value overrides it."""
    default = notifier.YouTrackClient("https://example.test", "token")
    check("default request timeout is 30s", default.timeout == 30)
    custom = notifier.YouTrackClient("https://example.test", "token", timeout=5)
    check("explicit timeout overrides default", custom.timeout == 5)


# --- _AuthStrippingRedirectHandler (token-leak guard) ----------------------


def test_redirect_strips_auth_cross_host():
    """The redirect handler is a token-leak guard: on a cross-host 30x it drops
    the Authorization header so the Bearer token is never forwarded to another
    host, while keeping non-secret headers; on a same-host redirect it keeps
    Authorization so the retried request still authenticates."""
    handler = notifier._AuthStrippingRedirectHandler()
    req = urllib.request.Request(
        "https://youtrack.jetbrains.com/api/issues/YTDB-1",
        headers={"Authorization": "Bearer SECRET", "Accept": "application/json"},
        method="GET",
    )
    cross = handler.redirect_request(
        req, fp=None, code=302, msg="Found", headers={},
        newurl="https://evil.example.com/x")
    check("cross-host redirect strips Authorization",
          not cross.has_header("Authorization"))
    check("cross-host redirect keeps non-secret headers",
          cross.has_header("Accept"))
    same = handler.redirect_request(
        req, fp=None, code=302, msg="Found", headers={},
        newurl="https://youtrack.jetbrains.com/api/other")
    check("same-host redirect keeps Authorization",
          same.has_header("Authorization"))


# --- process_issue ---------------------------------------------------------


def test_process_issue_skips_resolved():
    """An already-resolved issue is skipped with no writes, so a
    Verified/Closed/Fixed issue is never re-opened or re-commented."""
    client = FakeClient({"YTDB-10": ("Fixed", True)})
    outcome = notifier.process_issue(client, "YTDB-10", "v1", dry_run=False)
    check("resolved -> skipped_resolved", outcome == notifier.SKIPPED_RESOLVED)
    check("resolved -> no comment", client.comments == [])
    check("resolved -> no state change", client.set_fixed == [])


def test_process_issue_updates_open():
    """An open (unresolved) issue is commented and moved to Fixed, with the
    comment posted before the state change so a later rejected transition still
    leaves the 'Fixed in <version>' audit note."""
    client = FakeClient({"YTDB-11": ("Open", False)})
    outcome = notifier.process_issue(client, "YTDB-11", "v2", dry_run=False)
    check("open -> updated", outcome == notifier.UPDATED)
    check("open -> comment carries version",
          client.comments == [("YTDB-11", "Fixed in v2")])
    check("open -> state set to fixed", client.set_fixed == ["YTDB-11"])
    check("open -> comment precedes state change",
          client.calls == [("comment", "YTDB-11", "Fixed in v2"), ("set_fixed", "YTDB-11")])


def test_process_issue_updates_when_state_missing():
    """A found issue with no State value is unresolved by definition and must
    be updated, not skipped — otherwise a stateless issue would never be marked
    fixed."""
    client = FakeClient({"YTDB-14": (None, False)})
    outcome = notifier.process_issue(client, "YTDB-14", "v6", dry_run=False)
    check("no-state -> updated", outcome == notifier.UPDATED)
    check("no-state -> comment + state set",
          client.comments == [("YTDB-14", "Fixed in v6")] and client.set_fixed == ["YTDB-14"])


def test_process_issue_not_found():
    """A referenced issue that 404s (typo or cross-project ref) is reported as
    not-found and never written to."""
    client = FakeClient({})
    outcome = notifier.process_issue(client, "YTDB-99", "v3", dry_run=False)
    check("missing -> not_found", outcome == notifier.NOT_FOUND)
    check("missing -> no writes", client.comments == [] and client.set_fixed == [])


def test_process_issue_dry_run_writes_nothing():
    """In dry-run an open issue reports would_update but performs no writes, so
    a preview never mutates the tracker."""
    client = FakeClient({"YTDB-12": ("In progress", False)})
    outcome = notifier.process_issue(client, "YTDB-12", "v4", dry_run=True)
    check("dry-run open -> would_update", outcome == notifier.WOULD_UPDATE)
    check("dry-run -> no comment", client.comments == [])
    check("dry-run -> no state change", client.set_fixed == [])


def test_process_issue_dry_run_still_skips_resolved():
    """A resolved issue is skipped even in dry-run, so the preview count matches
    what a live run would actually touch."""
    client = FakeClient({"YTDB-13": ("Verified", True)})
    outcome = notifier.process_issue(client, "YTDB-13", "v5", dry_run=True)
    check("dry-run resolved -> skipped_resolved", outcome == notifier.SKIPPED_RESOLVED)


def test_process_issue_propagates_api_error():
    """A hard API error must propagate out of process_issue (not be swallowed
    into a normal outcome), because main()'s per-issue handler relies on the
    exception to record a failure and exit non-zero. Covers both the read path
    (get_issue_state) and a write path (add_comment)."""
    class RaisingOnRead(FakeClient):
        def get_issue_state(self, issue_id):
            raise notifier.YouTrackError("GET YTDB-14 returned 500: boom")

    check("read error propagates",
          _raises_youtrack_error(
              lambda: notifier.process_issue(RaisingOnRead({}), "YTDB-14", "v", dry_run=False)))

    class RaisingOnComment(FakeClient):
        def add_comment(self, issue_id, text):
            raise notifier.YouTrackError("comment on YTDB-15 returned 403")

    write_client = RaisingOnComment({"YTDB-15": ("Open", False)})
    check("write error propagates",
          _raises_youtrack_error(
              lambda: notifier.process_issue(write_client, "YTDB-15", "v", dry_run=False)))
    # The comment failed before the state write, so no Fixed transition leaked.
    check("failed comment -> no state change", write_client.set_fixed == [])


# --- main() orchestration --------------------------------------------------


def test_main_no_op_on_empty_from_sha():
    """An empty --from-sha (first run / no prior successful deploy) is a clean
    no-op returning 0, short-circuiting before any git or network access."""
    check("empty from-sha -> exit 0",
          notifier.main(["--from-sha", "", "--version", "v1"]) == 0)


def test_main_no_token():
    """With commits in range but YOUTRACK_TOKEN unset, a live run exits 1 (so a
    misconfigured secret fails the CI step) while --dry-run lists the issues and
    exits 0. The ancestor check and range walk are stubbed so no real history is
    needed."""
    with _patched_attr(notifier, "is_ancestor", lambda *a, **k: True), \
            _patched_attr(notifier, "get_commit_titles", lambda *a, **k: ["YTDB-1: x"]), \
            _env("YOUTRACK_TOKEN", None):
        live = notifier.main(["--from-sha", "aaa", "--to-sha", "bbb", "--version", "v1"])
        check("no token + live -> exit 1", live == 1)
        dry = notifier.main(
            ["--from-sha", "aaa", "--to-sha", "bbb", "--version", "v1", "--dry-run"])
        check("no token + dry-run -> exit 0", dry == 0)


def test_main_partial_failure_exit_code():
    """One failing issue (a raised YouTrackError) must not abort the others: the
    run still updates the healthy issues and exits 1 to flag the failure for
    follow-up. YouTrackClient and the range walk are stubbed."""
    built = {}

    class OneBadClient(FakeClient):
        def get_issue_state(self, issue_id):
            if issue_id == "YTDB-2":
                raise notifier.YouTrackError("GET YTDB-2 returned 500")
            return {"found": True, "state": "Open", "is_resolved": False}

    def fake_client_factory(base_url, token):
        built["client"] = OneBadClient({})
        return built["client"]

    with _patched_attr(notifier, "is_ancestor", lambda *a, **k: True), \
            _patched_attr(notifier, "get_commit_titles",
                          lambda *a, **k: ["YTDB-1: a", "YTDB-2: b", "YTDB-3: c"]), \
            _patched_attr(notifier, "YouTrackClient", fake_client_factory), \
            _env("YOUTRACK_TOKEN", "dummy-token"):
        rc = notifier.main(["--from-sha", "aaa", "--to-sha", "bbb", "--version", "v9"])

    client = built["client"]
    check("partial failure -> exit 1", rc == 1)
    check("partial failure -> healthy issues still updated",
          client.set_fixed == ["YTDB-1", "YTDB-3"])
    check("partial failure -> both healthy issues commented",
          client.comments == [("YTDB-1", "Fixed in v9"), ("YTDB-3", "Fixed in v9")])


def test_main_handles_network_oserror():
    """A network-level OSError (e.g. the socket TimeoutError raised when the
    request timeout fires) is caught like any other per-issue failure: the issue
    is recorded as failed and the run exits 1, while the remaining healthy issues
    are still updated rather than the whole run crashing. Guards the broadened
    `except (YouTrackError, OSError, ValueError)` — a bare urllib.error.URLError
    catch would have let this TimeoutError abort the run."""
    built = {}

    class TimingOutClient(FakeClient):
        def get_issue_state(self, issue_id):
            if issue_id == "YTDB-2":
                raise TimeoutError("timed out")
            return {"found": True, "state": "Open", "is_resolved": False}

    def fake_client_factory(base_url, token):
        built["client"] = TimingOutClient({})
        return built["client"]

    with _patched_attr(notifier, "is_ancestor", lambda *a, **k: True), \
            _patched_attr(notifier, "get_commit_titles",
                          lambda *a, **k: ["YTDB-1: a", "YTDB-2: b", "YTDB-3: c"]), \
            _patched_attr(notifier, "YouTrackClient", fake_client_factory), \
            _env("YOUTRACK_TOKEN", "dummy-token"):
        rc = notifier.main(["--from-sha", "aaa", "--to-sha", "bbb", "--version", "v9"])

    client = built["client"]
    check("network OSError -> exit 1", rc == 1)
    check("network OSError -> healthy issues still updated",
          client.set_fixed == ["YTDB-1", "YTDB-3"])


def main():
    # Discover and run every module-level test_* function, so adding a test
    # needs no manual registration here.
    tests = sorted(
        ((name, fn) for name, fn in globals().items()
         if name.startswith("test_") and callable(fn)),
        key=lambda kv: kv[0],
    )
    for _name, fn in tests:
        fn()
    if _failures:
        print(f"\n{len(_failures)} check(s) failed.")
        return 1
    print(f"\nAll checks passed across {len(tests)} test function(s).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
