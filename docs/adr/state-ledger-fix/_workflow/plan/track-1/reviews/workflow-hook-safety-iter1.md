<!--MANIFEST
dimension: workflow-hook-safety
iteration: 1
target: Track 1 Step 1 (YTDB-1140 fix) — new regression test in test_workflow_startup_precheck.py
findings: 0
index: []
evidence_base:
  certs: 0
cert_index: []
flags: []
-->

## Findings

findings: 0 — the new regression guard `test_track_review_step6_carries_ac_ledger_append` is operationally correct. All five in-scope correctness claims were verified by executing the staged test function in isolation:

- Anchor: the guard reads `track-review.md` via the module's `REPO_ROOT` (`parents[3]`), confirmed at runtime to resolve to the staged-workflow root and read the staged *fixed* copy. `LIVE_REPO_ROOT` resolves to the real repo root (develop-state unfixed copy); anchoring there would false-fail during Phase B, as D2 warns. The test uses `REPO_ROOT` correctly.
- Region slice: the `^6\. \*\*Append the A.C ledger boundary` start to next `^### ` heading slices lines 581–617, terminating at `### Tier-driven review selection…`. The 1617-char region excludes `## Phase A Completion` step 2 (lines 1030–1062), so the real self-match trap there (decoy `phase=C track=<N>` literal and a full `--phase C --track <N>` recovery snippet) cannot satisfy the guard.
- Order-independence: each of `--append-ledger`, `--phase C`, `--track` is matched independently with `flag in region`, never as a contiguous string. The canonical real call interleaves `--ctx <level>` between `--append-ledger` and `--phase C`; a contiguous-string guard would false-negative against it.
- Assertion messages: the `step6_start`, `is_file()`, and per-flag assertions each carry diagnostic messages (resolved path + `REPO_ROOT`, missing flag + region dump).
- Regression proof: the guard empirically FAILS on the unfixed live copy (step-6 anchor absent → `step6_start is None` assertion) and PASSES on the fixed staged copy. No proof gap.

No `/tmp` usage, pure-read and idempotent, registered once in `TESTS`, `py_compile` clean.

## Evidence base
