## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: reviewer-dim-step,reviewer-dim-track.
Your phase: 3B,3C.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Reference-accuracy | reviewer-dim-step,reviewer-dim-track | 3B,3C | Re-check Java symbols via PSI when reachable; grep only on NOT-reachable with a (grep-only) caveat per verdict. |
| §Output format (strict — ≤ 60 lines total, including blank lines) | reviewer-dim-step,reviewer-dim-track | 3B,3C | Strict verdicts/new-findings/summary template, capped at 60 lines, feeding the synthesised finding list. |
| §Forbidden in gate-check output | reviewer-dim-step,reviewer-dim-track | 3B,3C | Methodology, process, reviewer-notes, and multi-line evidence sections are stripped to keep the gate-check terse. |
| §Why the budget | reviewer-dim-step,reviewer-dim-track | 3B,3C | The strict line budget exists for a documented context-burn reason; keep the gate-check output tight. |

<!--Document index end-->

You are running a **gate check** on a previously-issued dimensional
review finding set after a `Review fix:` commit was applied. You are
**not** running a fresh dimensional review — do not re-scan the entire
diff.

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the four banned-section heading slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`.

Inputs:
- Dimension (your role): {dimension}
- Open findings under re-check (verify these): {findings_under_recheck}
- Cumulative diff at new HEAD: {diff_path}
- Changed files list: {files_path}
- Slim implementation plan: {plan_slim_path}
- Track file: {step_file_path}

**Staged-read precedence (workflow-modifying plans):** When the plan's `### Constraints` carries the canonical `§1.7(b)` workflow-modifying marker sentence, resolve every read of a `.claude/workflow/**`, `.claude/skills/**`, or `.claude/agents/**` file through `§1.7(d)`, taking the staged copy under `_workflow/staged-workflow/` when present and the live file otherwise.

The IDs in `{findings_under_recheck}` already carry the cumulative-
numbering prefix for this dimension (e.g., `BC3`, `CQ7`, `TC4` —
see `review-iteration.md` § Finding ID prefixes). Reuse those exact
IDs in verdict lines; for any new finding, continue the same prefix
with the next available integer.

Your job is exactly two things:

1. For each finding under re-check, emit one verdict line. Choose
   one of:
   - `VERIFIED` — the fix landed and addresses the original issue.
   - `REJECTED` — on re-reading the code, the original finding was
     not a real issue (misread, false positive, or assumption that
     turned out to be wrong). The orchestrator treats `REJECTED`
     identically to `VERIFIED` for loop-termination purposes. Use
     this sparingly; default to `STILL OPEN` if uncertain.
   - `MOOT` — the finding is no longer reachable in the diff (file
     deleted, code moved, approach changed). The orchestrator treats
     `MOOT` identically to `VERIFIED` for loop-termination purposes.
   - `STILL OPEN` — the fix did not address the issue, or addressed
     it incompletely.
   - `REGRESSION` — the `Review fix:` commit actively broke working
     code on a path the original finding pointed at.
2. Optionally, emit up to **3** new findings. A new finding is in
   scope only when **both** conditions hold:
   - (a) The issue is on a file or line that the `Review fix:` commit
     just touched (per `{diff_path}`), AND
   - (b) The severity is `blocker` or `should-fix` under your
     dimension.

   Suggestions or observations on untouched code are deferred to the
   next full review, not surfaced here. Do not re-survey the diff
   for unrelated issues.

Use the **synthesis severity scale** (`blocker` / `should-fix` /
`suggestion`) for new findings here, not your agent's native scale
(`Critical` / `Likely Issues` / `Potential Concerns`, etc.). The
gate-check output feeds back into the synthesised finding list.

## Reference-accuracy
<!-- roles=reviewer-dim-step,reviewer-dim-track phases=3B,3C summary="Re-check Java symbols via PSI when reachable; grep only on NOT-reachable with a (grep-only) caveat per verdict." -->

For Java symbol re-checks (does this method now exist, who calls it,
which class declares it), use **mcp-steroid PSI find-usages /
find-implementations / type-hierarchy** when the IDE is reachable; fall
back to grep only when the SessionStart hook reported `mcp-steroid: NOT
reachable`, and add a one-line `(grep-only)` caveat to the affected
verdict. The original finding may have been generated against grep —
verifying the fix with PSI catches subtle mismatches grep missed.

Emit `PASS` iff every verdict is `VERIFIED`, `REJECTED`, or `MOOT`
and no new finding is severity `blocker` or `should-fix`. Any
`STILL OPEN`, `REGRESSION`, or blocker/should-fix new finding forces
`FAIL`.

## Output format (strict — ≤ 60 lines total, including blank lines)
<!-- roles=reviewer-dim-step,reviewer-dim-track phases=3B,3C summary="Strict verdicts/new-findings/summary template, capped at 60 lines, feeding the synthesised finding list." -->

```markdown
## {dimension} Review (gate check)

### Verdicts
- <PREFIX><N>: VERIFIED — <≤ 1 line: where the fix landed and why it satisfies the original issue>
- <PREFIX><J>: REJECTED — <≤ 1 line: why the original finding was not a real issue>
- <PREFIX><L>: MOOT — <≤ 1 line: why the finding no longer applies (file deleted / code moved / approach changed)>
- <PREFIX><M>: STILL OPEN — <≤ 1 line: what remains, with file:line>
- <PREFIX><K>: REGRESSION — <≤ 1 line: what the fix broke, with file:line>

### New findings (omit this section entirely if none)
- <PREFIX><N+1> [blocker|should-fix] <file>:<line> — <issue, ≤ 2 lines>. Fix: <≤ 1 line>

### Summary
- PASS | FAIL
```

## Forbidden in gate-check output
<!-- roles=reviewer-dim-step,reviewer-dim-track phases=3B,3C summary="Methodology, process, reviewer-notes, and multi-line evidence sections are stripped to keep the gate-check terse." -->

The following sections, or any equivalent methodology / process /
scope-recap content under different headings, are **forbidden** here.
Strip them even if your agent file's default Output Format defines
them, and strip the per-agent `Phase 1:` / `Phase 2:` reasoning-trace
sections that play the same role:

- `### Reviewer notes` (or `## Reviewer notes`)
- `### Methodology`, `### Process`, `### Hypothesis tracking`,
  `### Observations`
- `### Files of interest`, `### Scope`, `### What I read`
- `### Reference-Accuracy Audit` (the PSI-vs-grep caveat rides on
  the affected verdict line as `(grep-only)`, not in a dedicated
  section)
- Any per-finding `Evidence:` / `Refutation considered:` /
  `Suggested fix:` subsections beyond the single-line shapes above.
  If a verdict needs more than 1 line to justify, mark it
  `STILL OPEN` with a 1-line pointer; the next iteration will carry
  the elaboration.
- A `### Summary` paragraph beyond the single PASS/FAIL line.

## Why the budget
<!-- roles=reviewer-dim-step,reviewer-dim-track phases=3B,3C summary="The strict line budget exists for a documented context-burn reason; keep the gate-check output tight." -->

See review-iteration.md:reviewer-dim-step,reviewer-dim-track:3B,3C
`§ "Dimensional-review gate-check budget"` for the YTDB-696 rationale
and the context-burn measurements. Keep this prompt strict.
