You are re-checking a track of the plan after fixes were applied.

Inputs:
- Plan file: {plan_path} (strategic context — Architecture Notes,
  Decision Records, Component Map)
- Step file: {step_file_path} (the track's `## Description` section —
  authoritative source for the track's What/How/Constraints/Interactions
  and any track-level diagram. If the step file lacks a `## Description`
  section, fall back to the plan-file entry for the track.)
- Track reviewed: {track_name}
- Codebase root: {codebase_path}
- Episodes from completed tracks: {prior_episodes}
- Previous findings (context only, finalized in earlier iterations):
  {previous_findings}
- Findings under re-check (verify these): {findings}
- Review type: {technical|risk|adversarial}

For each finding under re-check:
1. If the finding was ACCEPTED: check if the fix was applied correctly
   and if the fix introduced new issues.
2. If the finding was REJECTED: verify the rejection reason is sound
   and no downstream issue was introduced. Mark as REJECTED.

## Semi-Formal Verification Protocol

Before verifying any finding whose fix touched the track description,
re-read the track description and any track-level component diagram from
the step file's `## Description` section — if the step file lacks this
section, fall back to the plan-file entry for the track. Read the
relevant Decision Records from the plan.

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific location:

```markdown
#### Verify <PREFIX><N>: <finding title>
- **Original issue**: <what was wrong>
- **Fix applied**: <what changed in the step file, plan file, or codebase>
- **Re-check**:
  - Step-file/plan/codebase location: <where the fix was applied>
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
