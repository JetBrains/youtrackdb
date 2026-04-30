You are re-checking a plan after fixes were applied based on your previous
structural review findings.

Inputs:
- Updated plan file: {plan_path}
- Backlog file: {backlog_path}
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
Mermaid diagram) from the backlog's `## Track N: <title>` section. For
**completed** (`[x]`) and **skipped** (`[~]`) tracks, read from the
plan-file entry (intro paragraph + track episode for completed; intro +
`**Skipped:**` reason for skipped). Read Architecture Notes, Decision
Records, and other strategic context from the plan regardless.

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific plan location:

```markdown
#### Verify S<N>: <finding title>
- **Original issue**: <what was wrong — from the finding>
- **Fix applied**: <what changed in the plan, backlog, or design text>
- **Re-check**:
  - Plan/backlog/design location: <section and line where the fix was applied>
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
- New findings (if any) in the same format, with cumulative numbering
  (continue from the highest finding number)
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
