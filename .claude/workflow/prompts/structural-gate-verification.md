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

Output:
- For each previous finding: VERIFIED, STILL OPEN (with explanation),
  or REJECTED (no action needed)
- New findings (if any) in the same format, with cumulative numbering
  (continue from the highest finding number)
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
