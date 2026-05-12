You are re-checking a plan after fixes were applied based on your previous
structural review findings.

Inputs:
- Updated plan file: {plan_path}
- Step files directory: {tracks_dir} — every `tracks/track-N.md` whose
  matching plan-file entry is `[ ]` (pending). Read each pending
  track's `## Description` for that track's
  `**What/How/Constraints/Interactions**` detail and any track-level
  Mermaid diagram.
- Design document: {design_path}
- Previous findings (context only, finalized in earlier iterations):
  {previous_findings}
- Findings under re-check (verify these): {findings}

For each finding under re-check:
1. If the finding was ACCEPTED: check if the fix was applied correctly
   and if the fix introduced any new issues (regressions).
2. If the finding was REJECTED: verify the rejection reason is sound
   and no downstream issue was introduced by leaving it unfixed.
   Mark as REJECTED (no action needed).

Then briefly scan for any new issues in the areas that were modified —
fixes sometimes shift problems rather than solving them.

## Semi-Formal Verification Protocol

Before verifying any finding whose fix touched a pending track's
description, re-read that track's description (the
`**What/How/Constraints/Interactions**` subsections and any track-level
Mermaid diagram) from `tracks/track-N.md` `## Description`. For
**completed** (`[x]`) and **skipped** (`[~]`) tracks, read from the
plan-file entry (intro paragraph + track episode for completed; intro +
`**Skipped:**` reason for skipped). Read Architecture Notes, Decision
Records, and other strategic context from the plan regardless.

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific plan location:

```markdown
#### Verify S<N>: <finding title>
- **Original issue**: <what was wrong — from the finding>
- **Fix applied**: <what changed in the plan, step file, or design text>
- **Re-check**:
  - Plan / step file / design location: <section and line where the fix was applied>
  - Current state: <what the document now says>
  - Criteria met: <which structural criteria from the review checklist
    are now satisfied>
- **Regression check**: <did the fix shift the problem elsewhere?
  E.g., reordering tracks may fix one dependency but create another.
  Checked [which sections] — [clean / new issue]>
- **Verdict**: VERIFIED | STILL OPEN (explain) | REGRESSION (new issue)
```

For REJECTED findings:

```markdown
#### Verify S<N> (REJECTED): <finding title>
- **Rejection reason**: <from the previous iteration>
- **Downstream check**: <does leaving this unfixed cause inconsistency
  elsewhere? Checked [which sections] — [clean / downstream issue]>
- **Verdict**: REJECTED (no action needed) | RECONSIDER (downstream issue found)
```

---

Output:
- For each finding under re-check: the verification certificate above
- New findings (if any) in the same format as the structural review's
  finding template — including `**Classification**: mechanical |
  design-decision` and `**Justification**:` fields per the rules in
  `prompts/structural-review.md` § Classification rules. Bloat
  findings are always `mechanical`; ordering, sizing, and
  contradiction findings are `design-decision`. Cumulative numbering
  (continue from the highest finding number).
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
