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

Output:
- For each previous finding: VERIFIED, STILL OPEN (with explanation),
  or REJECTED (no action needed)
- New findings (if any) in the same format with cumulative numbering
  (continue from the highest CR number)
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
