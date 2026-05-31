## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (files with no `## ` headings carry none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains your role (or your role is `any`, or the row's Roles is `any`) AND Phases contains your phase (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections.

Your role: reviewer-plan.
Your phase: 2.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Semi-Formal Verification Protocol | reviewer-plan | 2 | Re-check each consistency finding with a verification certificate, scan for fix-shifted regressions, emit PASS/FAIL. |

<!--Document index end-->

You are re-checking a plan and design document after fixes were applied
based on your previous consistency review findings.

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`.

Inputs:
- Updated plan file: {plan_path}
- Track files directory: {plan_dir} — every `plan/track-N.md` whose
  matching plan-file entry is `[ ]` (pending). Read each pending
  track's `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, and `## Interfaces and Dependencies` sections for
  that track's what/how/constraints/interactions detail and any
  track-level Mermaid diagram.
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
<!-- roles=reviewer-plan phases=2 summary="Re-check each consistency finding with a verification certificate, scan for fix-shifted regressions, emit PASS/FAIL." -->

Before verifying any finding whose fix touched a pending track's
description, re-read that track's `## Purpose / Big Picture`,
`## Context and Orientation`, `## Plan of Work`, and `## Interfaces
and Dependencies` sections (and any track-level Mermaid diagram those
sections carry) from `plan/track-N.md`. For
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

The re-check examples above are **illustrative, not exhaustive**. The
operative criterion is reference accuracy — would a missed or spurious
match make a verification verdict (VERIFIED / STILL OPEN / REGRESSION)
wrong? When in doubt, route through PSI. `CLAUDE.md` § MCP Steroid →
"Grep vs PSI — when to switch" is the last authoritative source for
edge cases.

**How to invoke:**
- The MCP server is `mcp-steroid`. Its tools are deferred, so load their schemas via ToolSearch first.
- Call `steroid_list_projects` once at session start to confirm the IDE has the right project open and matches the working tree.
- Run PSI queries (find-usages, find-implementations, type-hierarchy) via `steroid_execute_code`, which evaluates a Kotlin snippet against the PSI tree — there is no dedicated `find_usages` tool.
- For Kotlin recipes, fetch the `coding-with-intellij-psi` skill via `steroid_fetch_resource`.

```markdown
#### Verify CR<N>: <finding title>
- **Original issue**: <what was wrong — from the finding>
- **Fix applied**: <what changed in the plan, track file, or design text>
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
- New findings (if any) in the same format as the consistency review's
  finding template — including `**Classification**: mechanical |
  design-decision` and `**Justification**:` fields per the rules in
  `prompts/consistency-review.md` § Classification rules. Apply the
  intent-axis pre-screen before emitting any new finding. Cumulative
  numbering (continue from the highest CR number).
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
