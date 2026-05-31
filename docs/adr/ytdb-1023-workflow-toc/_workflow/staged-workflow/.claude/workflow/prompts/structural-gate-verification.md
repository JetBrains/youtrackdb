## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region — from `<!--Document index start-->` to `<!--Document index end-->`. On large files like `conventions.md` this exceeds 30 lines; read to the closing delimiter rather than stopping at a fixed count.
2. Match TOC rows where Roles contains your role (or your role is `any`, or the row's Roles is `any`) AND Phases contains your phase (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections.

Your role: reviewer-plan.
Your phase: 2.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Semi-Formal Verification Protocol | reviewer-plan | 2 | Re-check each structural finding with a verification certificate, scan for fix-shifted regressions, emit PASS/FAIL. |

<!--Document index end-->

You are re-checking a plan after fixes were applied based on your previous
structural review findings.

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`.

Inputs:
- Updated plan file: {plan_path}
- Track files directory: {plan_dir} — every `plan/track-N.md` whose
  matching plan-file entry is `[ ]` (pending). Read each pending
  track's `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, and `## Interfaces and Dependencies` sections for
  that track's what/how/constraints/interactions detail and any
  track-level Mermaid diagram.
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
<!-- roles=reviewer-plan phases=2 summary="Re-check each structural finding with a verification certificate, scan for fix-shifted regressions, emit PASS/FAIL." -->

Before verifying any finding whose fix touched a pending track's
description, re-read that track's `## Purpose / Big Picture`,
`## Context and Orientation`, `## Plan of Work`, and `## Interfaces
and Dependencies` sections (and any track-level Mermaid diagram those
sections carry) from `plan/track-N.md`. For
**completed** (`[x]`) and **skipped** (`[~]`) tracks, read from the
plan-file entry (intro paragraph + track episode for completed; intro +
`**Skipped:**` reason for skipped). Read Architecture Notes, Decision
Records, and other strategic context from the plan regardless.

For each ACCEPTED finding being verified, produce a **verification
certificate** that re-checks the specific plan location:

```markdown
#### Verify S<N>: <finding title>
- **Original issue**: <what was wrong — from the finding>
- **Fix applied**: <what changed in the plan, track file, or design text>
- **Re-check**:
  - Plan / track file / design location: <section and line where the fix was applied>
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
- New findings (if any) in the same format as the structural review's
  finding template — including `**Classification**: mechanical |
  design-decision` and `**Justification**:` fields per the rules in
  `prompts/structural-review.md` § Classification rules. Bloat
  findings are always `mechanical`; ordering, sizing, and
  contradiction findings are `design-decision`. Cumulative numbering
  (continue from the highest finding number).
- Summary: PASS (all verified/rejected, no new blockers) or FAIL (with
  list of remaining blockers)
