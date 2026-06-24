<!--
MANIFEST
dimension: hook-safety
prefix: WH
iteration: 1
high_water_mark: 1
findings_total: 1
blockers: 1
evidence_base:
  certs: 0
cert_index: []
flags:
  evidence_trail_exempt: true
  exempt_reason: "(a) no refutation or certificate phase to persist"
index:
  - id: WH1
    sev: blocker
    anchor: "### WH1 [blocker] Staged test suite fails from its authoritative staged location — LIVE_REPO_ROOT collapses to the staged root once conventions.md is staged"
    loc: "docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck.py:56-80,4071-4079"
    cert: n/a
    basis: "ran the staged suite (114/115; track_review_step6_carries_ac_ledger_append fails); confirmed REPO_ROOT == LIVE_REPO_ROOT from the staged location; bisected green@Step1 / red@Step2 to the Step-2 staging of conventions.md"
-->

## Findings

### WH1 [blocker] Staged test suite fails from its authoritative staged location — `LIVE_REPO_ROOT` collapses to the staged root once `conventions.md` is staged

- **File:** `docs/adr/mid-track-resume/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck.py` (lines 56-80 `_resolve_live_repo_root` / `LIVE_REPO_ROOT`; lines 4071-4079 the `track_review` dual-anchor resolution)
- **Axis:** Python script (test-suite correctness / exit-code discipline)
- **Cost:** the §1.7(d)-authoritative staged suite exits non-zero (1 of 115 FAILED) when run from the staged location it lives in, so the track's merge gate is red and the track file's "Tests: 115/115 pass" claim is stale.
- **Certainty:** confirmed by execution, not inferred.

**What is unsafe.** Run from its staged home, the suite reports:

```
1 of 115 test(s) FAILED: track_review_step6_carries_ac_ledger_append
```

The failing test is the pre-existing `test_track_review_step6_carries_ac_ledger_append`, whose `track-review.md` resolution this track's delta rewrote (delta lines 26-46) to a dual-anchor form:

```python
staged_track_review = REPO_ROOT / ".claude" / "workflow" / "track-review.md"
live_track_review = LIVE_REPO_ROOT / ".claude" / "workflow" / "track-review.md"
track_review = staged_track_review if staged_track_review.is_file() else live_track_review
assert track_review.is_file(), (...)   # <-- fails here
```

The delta's own comment promises `LIVE_REPO_ROOT` is "walked up to the real repo root." It is not. `LIVE_REPO_ROOT` comes from `_resolve_live_repo_root()` (line 56), which walks up to the first ancestor that holds `.claude/workflow/conventions.md`. This track **stages `conventions.md`** (Step 2, the delta's third file), so the staged subtree now carries `.claude/workflow/conventions.md`. The walk stops at the staged-workflow root, which is exactly `REPO_ROOT` (`parents[3]`). Both anchors therefore resolve to:

```
.../staged-workflow/.claude/workflow/track-review.md
```

— a path that does not exist, because `track-review.md` is *not* staged on this branch (the A→C append already lives in the live file). Neither anchor reaches the real repo's live `track-review.md` (which does exist and carries the asserted `--phase C` append), so the `is_file()` guard fails.

**Cross-step interaction (why the per-step pass missed it).** At Step 1 the suite was green: `conventions.md` was not yet staged, so the `conventions.md`-anchored walk passed the staged root and continued up to the real repo root, and `LIVE_REPO_ROOT` reached the live `track-review.md`. Step 2 staged `conventions.md` — a legitimate edit — which silently moved where `LIVE_REPO_ROOT` resolves and broke a test in an unrelated file. `git ls-tree` confirms `conventions.md` is absent from Step 1's tree and present at HEAD; the bug only exists in the cumulative track diff, which is what this track-level pass reviews.

**Suggestion.** Decouple the live-file anchor from `conventions.md` presence so it cannot stop inside the staged subtree. Options, cheapest first:

1. In `_resolve_live_repo_root()`, skip any ancestor whose path contains `_workflow/staged-workflow` before accepting it (continue the walk through the staged mirror to the real root). This keeps both the conformance byte-source and the `track-review.md` fallback anchored at the real repo even when the branch stages `conventions.md`.
2. Anchor `LIVE_REPO_ROOT` on a marker that a staged subtree never carries (e.g. the first ancestor holding a `.git` entry, or `docs/adr` + `.git`), rather than on a workflow file that staging legitimately copies.

Either fix restores 115/115 from the staged location and survives a branch that stages any subset of workflow files. Re-run the staged suite to confirm green before merge.

## Evidence base
