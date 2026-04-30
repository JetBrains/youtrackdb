You are re-checking a track of the plan after fixes were applied.

Inputs:
- Plan file: {plan_path} (strategic context — Architecture Notes,
  Decision Records, Component Map)
- Step file: {step_file_path} (the track's `## Description` section —
  authoritative source for the track's What/How/Constraints/Interactions
  and any track-level diagram.)
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
the step file's `## Description` section. Read the relevant Decision
Records from the plan.

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific location.

For Java symbol re-checks (does this method now exist / have these
callers / live in this class / override this interface), use
mcp-steroid PSI find-usages / find-implementations when the IDE is
reachable; fall back to Grep/Glob with a reference-accuracy caveat
only when mcp-steroid is unreachable. The original finding may have
been generated against grep — verifying the fix with PSI catches
subtle mismatches that grep missed.

The re-check examples above are **illustrative, not exhaustive**. The
operative criterion is reference accuracy — would a missed or spurious
match make a verification verdict (VERIFIED / STILL OPEN / REGRESSION)
wrong? When in doubt, route through PSI. `~/.claude/CLAUDE.md`
(sections "MCP Steroid" and "Grep vs PSI — when to switch") is the
last authoritative source for edge cases.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

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
