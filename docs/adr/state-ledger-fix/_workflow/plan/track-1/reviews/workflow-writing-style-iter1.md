<!--MANIFEST
reviewer: review-workflow-writing-style
target: "Track 1, Step 1 — YTDB-1140 fix: new prose in track-review.md §What You Do step 6 + §Phase A Completion step 2, plus the new regression-test docstring"
commit_range: b40d358a00~1..b40d358a00
findings: 2
blockers: 0
evidence_base: "## Evidence base"
cert_index: 2
flags: []
index:
  - id: WS1
    sev: Recommended
    anchor: "### WS1 [Recommended] em-dash overuse — docstring para 1 (X — Y — Z triple-clause cadence)"
    loc: ".claude/scripts/tests/test_workflow_startup_precheck.py docstring of test_track_review_step6_carries_ac_ledger_append (delta lines 4-9)"
    cert: C1
    basis: script
  - id: WS2
    sev: Recommended
    anchor: "### WS2 [Recommended] em-dash overuse — track-review.md step-6 paragraph 1"
    loc: ".claude/workflow/track-review.md staged copy lines 581-587 (delta lines 83-89)"
    cert: C2
    basis: script
-->

## Findings

### WS1 [Recommended] em-dash overuse — docstring para 1 (X — Y — Z triple-clause cadence)

- File: `docs/adr/state-ledger-fix/_workflow/staged-workflow/.claude/scripts/tests/test_workflow_startup_precheck.py` (docstring of `test_track_review_step6_carries_ac_ledger_append`, delta lines 4-9), Axis: em-dash overuse, Cost: two em dashes in one paragraph forming the banned `X — Y — Z` triple-clause cadence. Issue: `§ Punctuation and typography → Em-dash discipline` caps em dashes at one per paragraph and explicitly bans the `X — Y — Z` triple-clause cadence; the first docstring paragraph reads "Reproduces the YTDB-1140 bug — the absence of any A→C `--append-ledger` site let a resumed session re-run Phase A — at the doc level, the layer the bug actually lived in". Suggestion: convert both dashes to a colon and a period: "Reproduces the YTDB-1140 bug: the absence of any A→C `--append-ledger` site let a resumed session re-run Phase A. The bug lives at the doc level — the script already supports `--phase C`; the missing piece was the instruction to call it." (one em dash, two sentences).

### WS2 [Recommended] em-dash overuse — track-review.md step-6 paragraph 1

- File: `docs/adr/state-ledger-fix/_workflow/staged-workflow/.claude/workflow/track-review.md` (lines 581-587, delta lines 83-89), Axis: em-dash overuse, Cost: two em dashes in one paragraph (appositive pair). Issue: `§ Punctuation and typography → Em-dash discipline` caps em dashes at one per paragraph; the rewritten step-6 lead reads "Phase A's on-disk writes — the populated `## Concrete Steps` section, the new Progress entry, and the A→C ledger line — must be committed". The pair pre-existed, but the new line ("and the A→C ledger line") rewrote this paragraph, putting it in scope. Suggestion: drop the bracketing dashes for a parenthetical or a colon split: "Phase A's on-disk writes must be committed before Phase B spawns the first implementer for this track: the populated `## Concrete Steps` section, the new Progress entry, and the A→C ledger line." Then continue with the `git reset --hard HEAD` sentence unchanged.

## Evidence base

#### C1 — em-dash count, docstring para 1
Confirmed. `grep -o "—"` over delta lines 4-9 returns 2; the `→` in "A→C" is a rightwards arrow, not an em dash (verified by line-level grep — the two em dashes sit at "bug — the absence" and "Phase A — at the doc level"). Two clauses bracketed by em dashes form the `X — Y — Z` cadence the rule names by example. Violation stands.

#### C2 — em-dash count, track-review.md step-6 paragraph 1
Confirmed. `grep -o "—"` over staged lines 581-587 returns 2, at "writes — the populated" and "ledger line — must be committed" — an appositive pair, two mechanical instances in one blank-line-bounded paragraph. The new line "and the A→C ledger line" is part of the rewrite (delta lines 83-89), so the paragraph is in-scope new prose. Violation stands.
