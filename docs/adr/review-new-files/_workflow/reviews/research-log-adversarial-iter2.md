<!-- MANIFEST
findings: 0   severity: {blocker: 0, should-fix: 0, suggestion: 0}
index: []
verdicts:
  - {id: A1, verdict: REJECTED}
  - {id: A2, verdict: REJECTED}
  - {id: A3, verdict: STILL OPEN}
  - {id: A4, verdict: STILL OPEN}
overall: PASS
evidence_base: {section: "## Evidence base", certs: 4, matches: 4}
cert_index:
  - {id: C1, verdict: MATCHES}
  - {id: C2, verdict: MATCHES}
  - {id: C3, verdict: MATCHES}
  - {id: C4, verdict: MATCHES}
flags: [CONTRACT_OK]
-->

## Findings

No new findings. All iter1 blockers and should-fix findings are addressed by
the D1 revision; the two suggestions (A3, A4) remain recorded, non-gating.

## Evidence base

#### C1 [MATCHES] Verdict on A1 (blocker) — D1 fix scope widened to all four locations per file
- **Prior finding**: D1 scoped the fix to the bash loop + context block, leaving the preamble prose and post-loop narration (which also encode the "only when a live counterpart exists" logic) to go stale and contradict the new else-branch — a rule-coherence defect.
- **Re-check against the revised log**: D1 (`research-log.md:35-55`) is retitled "Fix both files, all four defect locations per file" and enumerates them: (1) preamble prose "…and when the live file exists write `diff <live> <staged>`" (track ~271, step ~486) with the explicit requirement to state the no-live-counterpart NEW-marker case; (2) the bash loop else-branch (track ~283-289, step ~498-504); (3) post-loop narration "…it fires only on a new-file add … that has a live counterpart" (track ~293, step ~508), flagged as wrong post-fix because the loop now records every add under the staged prefix (delta when a live counterpart exists, NEW marker when not) — this covers both the "that has a live counterpart" and the "empty when no freshly-created staged copy is in range" clauses of that narration; (4) the context block. Eight edit points total, one logical fix (line 55).
- **Location-set completeness**: the four enumerated locations match the exact on-disk sites I verified in iter1 (`track-code-review.md:269-272/283-289/293-303/454-465`; byte-parallel `step-implementation.md:484-487/498-504/508-517/610-621`). No location is left out. The "scope confirmed closed" note (lines 80-83) correctly records the iter1 grep result that the loop/context block live in exactly these two files and `conventions.md §1.7(k)` is only a pointer.
- **Verdict**: REJECTED (addressed). The C1 counter-argument no longer applies — the widened scope removes the prose/code contradiction the blocker was raised on.

#### C2 [MATCHES] Verdict on A2 (should-fix) — context block specified as a per-entry rewrite, not an appended note
- **Prior finding**: appending a NEW-file note under the unchanged blanket "when non-empty … the rest is out of scope" sentence leaves a NEW-marked file still reading as out-of-scope, because the marker makes the delta file non-empty.
- **Re-check against the revised log**: A2 is folded into D1 (`research-log.md:57-66`) as an explicit REWRITE requirement. It quotes the current blanket sentence, states why an appended note fails ("a NEW-file marker makes the delta file non-empty, so an appended note would still sit under that blanket 'out of scope' sentence"), and requires the block to make delta-scoped vs NEW a **per-entry, mutually exclusive** distinction: `=== delta: … ===` → scope to the delta (rest of the whole-file add out of scope); `=== NEW staged file … ===` → no live baseline, review in full.
- **Verdict**: REJECTED (addressed). The rewrite framing is exactly the fix C2 proposed; the mixed-file ambiguity is closed at the decision level.

#### C3 [MATCHES] Verdict on A3 (suggestion) — opt-out self-validation residue recorded
- **Prior finding**: the §1.7(k) opt-out disables the step-8 staged-delta prep on this branch, so the fix ships without in-workflow self-validation; acceptable, suggestion-level.
- **Re-check**: recorded in the gate record (`research-log.md:151-153`) and the baseline section (138-141) still states the manual drift/coherence check stands in. D3 unchanged (correctly — it survives). Not gating.
- **Verdict**: STILL OPEN (recorded, non-gating). Nothing to close; the suggestion is captured and the residue is a benign consequence of the correct opt-out choice. A manual coherence trace of the eight edit points remains the stand-in validation, as A3 recommended.

#### C4 [MATCHES] Verdict on A4 (suggestion) — staged-path marker survives
- **Prior finding**: the NEW marker names only the staged path; the reviewer prefix-strips to recover the live rule. Survives.
- **Re-check**: D2 (`research-log.md:85-94`) unchanged; the gate record (line 153-154) captures A4 as a surviving suggestion. The staged path is the diff locator, and the loop already computes the prefix-strip.
- **Verdict**: STILL OPEN (recorded, non-gating). D2 holds; optional cosmetic two-path marker parity is the only open item, not required for the gate.
