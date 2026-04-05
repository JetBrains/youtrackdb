You are re-checking a plan and design document after fixes were applied
based on your previous consistency review findings.

Inputs:
- Updated plan file: {plan_path}
- Updated design document: {design_path}
- Previous findings: {findings_file}

For each previous finding:
1. If the finding was ACCEPTED: verify the fix was applied correctly by
   re-checking the code reference, call flow, or cross-reference that was
   originally flagged. Confirm it no longer has the reported inconsistency.
   Check if the fix introduced any new inconsistencies (regressions).
2. If the finding was REJECTED: verify the rejection reason is sound
   and no downstream inconsistency was introduced by leaving it unfixed.
   Mark as REJECTED (no action needed).

Then briefly re-scan the areas that were modified — fixes sometimes shift
inconsistencies rather than resolving them (e.g., fixing a class name in
the design document but not updating the corresponding sequence diagram).

## Semi-Formal Verification Protocol

For each ACCEPTED finding being verified, you must produce a
**verification certificate** — not just assert "looks fixed." The
certificate traces the same code reference or flow that was originally
flagged and confirms the fix resolves it.

```markdown
#### Verify CR<N>: <finding title>
- **Original issue**: <what was wrong — from the finding>
- **Fix applied**: <what changed in the plan/design text>
- **Re-check**:
  - Search/trace performed: <Grep/Glob query or flow trace>
  - Code location: <file:line — same reference as original, or updated>
  - Current state: <what the document now says vs. what the code shows>
- **Regression check**: <did the fix introduce new inconsistencies
  in related sections? Checked [which sections] — [clean / new issue]>
- **Verdict**: VERIFIED | STILL OPEN (explain) | REGRESSION (new issue)
```

For REJECTED findings, verify the rejection reason with a lighter check:

```markdown
#### Verify CR<N> (REJECTED): <finding title>
- **Rejection reason**: <from the previous iteration>
- **Downstream check**: <does leaving this unfixed cause any inconsistency
  elsewhere? Checked [which sections] — [clean / downstream issue]>
- **Verdict**: REJECTED (no action needed) | RECONSIDER (downstream issue found)
```

---

Output:
- For each previous finding: the verification certificate above
- New findings (if any) in the same format with cumulative numbering
  (continue from the highest CR number)
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
