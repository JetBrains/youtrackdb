You are re-checking a track of the plan after fixes were applied.

Inputs:
- Updated plan file: {plan_path}
- Track reviewed: {track_name}
- Previous findings: {findings}
- Review type: {technical|risk|adversarial}

For each previous finding:
1. If the finding was ACCEPTED: check if the fix was applied correctly
   and if the fix introduced new issues.
2. If the finding was REJECTED: verify the rejection reason is sound
   and no downstream issue was introduced. Mark as REJECTED.

## Semi-Formal Verification Protocol

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific location:

```markdown
#### Verify <PREFIX><N>: <finding title>
- **Original issue**: <what was wrong>
- **Fix applied**: <what changed in the plan/track description>
- **Re-check**:
  - Code/plan location: <where the fix was applied>
  - Current state: <what it now says vs. original issue>
  - Criteria met: <which review criteria are now satisfied>
- **Regression check**: <did the fix introduce new issues?
  Checked [which areas] — [clean / new issue]>
- **Verdict**: VERIFIED | STILL OPEN (explain) | REGRESSION (new issue)
```

For REJECTED findings:

```markdown
#### Verify <PREFIX><N> (REJECTED): <finding title>
- **Rejection reason**: <from the previous iteration>
- **Downstream check**: <any downstream issues from leaving unfixed?>
- **Verdict**: REJECTED (no action needed) | RECONSIDER (downstream issue)
```

---

Output:
- For each finding: the verification certificate above
- New findings (if any) with cumulative numbering
- Summary: PASS or FAIL
