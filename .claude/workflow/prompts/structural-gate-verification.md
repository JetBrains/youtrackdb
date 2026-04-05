You are re-checking a plan after fixes were applied based on your previous
structural review findings.

Inputs:
- Updated plan file: {plan_path}
- Design document: {design_path}
- Previous findings: {findings_file}

For each previous finding:
1. If the finding was ACCEPTED: check if the fix was applied correctly
   and if the fix introduced any new issues (regressions).
2. If the finding was REJECTED: verify the rejection reason is sound
   and no downstream issue was introduced by leaving it unfixed.
   Mark as REJECTED (no action needed).

Then briefly scan for any new issues in the areas that were modified —
fixes sometimes shift problems rather than solving them.

## Semi-Formal Verification Protocol

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific plan location:

```markdown
#### Verify S<N>: <finding title>
- **Original issue**: <what was wrong — from the finding>
- **Fix applied**: <what changed in the plan text>
- **Re-check**:
  - Plan location: <section and line where the fix was applied>
  - Current state: <what the plan now says>
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
- For each previous finding: the verification certificate above
- New findings (if any) in the same format, with cumulative numbering
  (continue from the highest finding number)
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
