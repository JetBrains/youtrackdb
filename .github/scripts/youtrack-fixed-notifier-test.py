#!/usr/bin/env python3
"""Stand-alone tests for youtrack-fixed-notifier.py.

Run directly (no pytest dependency, matching the other .github/scripts
test runners):

    python3 .github/scripts/youtrack-fixed-notifier-test.py

Exit code 0 means all tests passed; non-zero prints the failing cases.
The notifier module is loaded by path because its filename contains
hyphens and is not importable as a normal module.
"""

import importlib.util
import pathlib
import sys

_MODULE_PATH = pathlib.Path(__file__).with_name("youtrack-fixed-notifier.py")
_spec = importlib.util.spec_from_file_location("notifier", _MODULE_PATH)
notifier = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(notifier)


class FakeClient:
    """In-memory stand-in for YouTrackClient.

    `states` maps issue ID -> (state_name, is_resolved). A missing key
    models a 404 (issue not found). Recorded `comments` and `set_fixed`
    let tests assert that writes happened (or did not, under --dry-run).
    """

    def __init__(self, states):
        self.states = states
        self.comments = []
        self.set_fixed = []

    def get_issue_state(self, issue_id):
        if issue_id not in self.states:
            return {"found": False, "state": None, "is_resolved": False}
        name, is_resolved = self.states[issue_id]
        return {"found": True, "state": name, "is_resolved": is_resolved}

    def add_comment(self, issue_id, text):
        self.comments.append((issue_id, text))

    def set_state_fixed(self, issue_id):
        self.set_fixed.append(issue_id)


_failures = []


def check(name, condition):
    if condition:
        print(f"  PASS  {name}")
    else:
        print(f"  FAIL  {name}")
        _failures.append(name)


def test_extract_issue_ids():
    # Colon form, the most common shape on develop.
    check("colon form", notifier.extract_issue_ids(["YTDB-123: fix a bug"]) == ["YTDB-123"])
    # Bracket form, also produced by the PR-title prefixer.
    check("bracket form", notifier.extract_issue_ids(["[YTDB-958] do a thing"]) == ["YTDB-958"])
    # Comma-separated pair in a single title -> both issues.
    check(
        "comma pair",
        notifier.extract_issue_ids(["YTDB-1, YTDB-2: two issues"]) == ["YTDB-1", "YTDB-2"],
    )
    # A PR-number suffix uses '#', not YTDB-, so it must not match.
    check("no false positive on pr number", notifier.extract_issue_ids(["Tidy things (#1101)"]) == [])
    # A title with no reference at all.
    check("no reference", notifier.extract_issue_ids(["Set default effort to xhigh"]) == [])
    # The same issue across several commits collapses to one entry, order kept.
    check(
        "dedup across commits, order preserved",
        notifier.extract_issue_ids(["YTDB-5: part one", "YTDB-3: other", "YTDB-5: part two"])
        == ["YTDB-5", "YTDB-3"],
    )
    # Lower-case reference is normalized to upper case.
    check("case-insensitive normalize", notifier.extract_issue_ids(["ytdb-9: lower"]) == ["YTDB-9"])
    # `\b` must prevent matching inside a larger leading token.
    check("no match inside larger token", notifier.extract_issue_ids(["MYTDB-1: noise"]) == [])


def test_process_issue_skips_resolved():
    client = FakeClient({"YTDB-10": ("Fixed", True)})
    outcome = notifier.process_issue(client, "YTDB-10", "v1", dry_run=False)
    check("resolved -> skipped_resolved", outcome == notifier.SKIPPED_RESOLVED)
    check("resolved -> no comment", client.comments == [])
    check("resolved -> no state change", client.set_fixed == [])


def test_process_issue_updates_open():
    client = FakeClient({"YTDB-11": ("Open", False)})
    outcome = notifier.process_issue(client, "YTDB-11", "v2", dry_run=False)
    check("open -> updated", outcome == notifier.UPDATED)
    check("open -> comment carries version", client.comments == [("YTDB-11", "Fixed in v2")])
    check("open -> state set to fixed", client.set_fixed == ["YTDB-11"])


def test_process_issue_not_found():
    client = FakeClient({})
    outcome = notifier.process_issue(client, "YTDB-99", "v3", dry_run=False)
    check("missing -> not_found", outcome == notifier.NOT_FOUND)
    check("missing -> no writes", client.comments == [] and client.set_fixed == [])


def test_process_issue_dry_run_writes_nothing():
    client = FakeClient({"YTDB-12": ("In progress", False)})
    outcome = notifier.process_issue(client, "YTDB-12", "v4", dry_run=True)
    check("dry-run open -> would_update", outcome == notifier.WOULD_UPDATE)
    check("dry-run -> no comment", client.comments == [])
    check("dry-run -> no state change", client.set_fixed == [])


def test_process_issue_dry_run_still_skips_resolved():
    # A resolved issue is skipped even in dry-run, so the preview count
    # matches what a live run would actually touch.
    client = FakeClient({"YTDB-13": ("Verified", True)})
    outcome = notifier.process_issue(client, "YTDB-13", "v5", dry_run=True)
    check("dry-run resolved -> skipped_resolved", outcome == notifier.SKIPPED_RESOLVED)


def main():
    test_extract_issue_ids()
    test_process_issue_skips_resolved()
    test_process_issue_updates_open()
    test_process_issue_not_found()
    test_process_issue_dry_run_writes_nothing()
    test_process_issue_dry_run_still_skips_resolved()
    if _failures:
        print(f"\n{len(_failures)} test(s) failed.")
        return 1
    print("\nAll tests passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
