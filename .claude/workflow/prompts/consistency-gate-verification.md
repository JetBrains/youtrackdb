You are re-checking a plan and design document after fixes were applied
based on your previous consistency review findings.

Inputs:
- Updated plan file: {plan_path}
- Backlog file: {backlog_path}
- Updated design document: {design_path}
- Previous findings (context only, finalized in earlier iterations):
  {previous_findings}
- Findings under re-check (verify these): {findings}

For each finding under re-check:
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

Before verifying any finding whose fix touched a pending track's
description, re-read that track's description (the
`**What/How/Constraints/Interactions**` subsections and any track-level
Mermaid diagram) from the backlog's `## Track N: <title>` section. For
**completed** (`[x]`) and **skipped** (`[~]`) tracks, read from the
plan-file entry (intro paragraph + track episode for completed; intro +
`**Skipped:**` reason for skipped). Read Architecture Notes, Decision
Records, and other strategic context from the plan regardless.

For each ACCEPTED finding being verified, you must produce a
**verification certificate** — not just assert "looks fixed." The
certificate traces the same code reference or flow that was originally
flagged and confirms the fix resolves it.

For Java symbol re-checks (does this method now exist / have these
callers / live in this class), use mcp-steroid PSI find-usages /
find-implementations when the IDE is reachable; fall back to
Grep/Glob with a reference-accuracy caveat only when mcp-steroid is
unreachable. The original finding may have been generated against
grep — verifying the fix with PSI catches subtle mismatches that grep
missed.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

```markdown
#### Verify CR<N>: <finding title>
- **Original issue**: <what was wrong — from the finding>
- **Fix applied**: <what changed in the plan, backlog, or design text>
- **Re-check**:
  - Search/trace performed: <PSI find-usages / find-implementations
    query when the IDE is reachable; Grep/Glob query or flow trace
    otherwise. Record which tool was used.>
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
- For each finding under re-check: the verification certificate above
- New findings (if any) in the same format with cumulative numbering
  (continue from the highest CR number)
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
