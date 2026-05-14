You are running a **gate check** on a previously-issued dimensional
review finding set after a `Review fix:` commit was applied. You are
**not** running a fresh dimensional review — do not re-scan the entire
diff.

Inputs:
- Dimension (your role): {dimension}
- Open findings under re-check (verify these): {findings_under_recheck}
- Cumulative diff at new HEAD: {diff_path}
- Changed files list: {files_path}
- Slim implementation plan: {plan_slim_path}
- Step file: {step_file_path}
- Review level: {level} (`step` or `track`)

Your job is exactly two things:

1. For each finding under re-check, emit one verdict line: `VERIFIED`,
   `STILL OPEN`, or `REGRESSION`.
2. Optionally, emit up to **3** new findings — only when the `Review
   fix:` commit introduced a regression directly tied to the listed
   open findings, or you observed an obvious miss while reading the
   fix. Do not re-survey the diff for unrelated issues; the next
   iteration's full review (if one is allocated) covers that.

## Reference-accuracy

For Java symbol re-checks (does this method now exist, who calls it,
which class declares it), use **mcp-steroid PSI find-usages /
find-implementations / type-hierarchy** when the IDE is reachable; fall
back to grep only when the SessionStart hook reported `mcp-steroid: NOT
reachable`, and add a one-line `(grep-only)` caveat to the affected
verdict. The original finding may have been generated against grep —
verifying the fix with PSI catches subtle mismatches grep missed.

## Output format (strict — ≤ 60 lines total, including blank lines)

```markdown
## {Dimension} gate check

### Verdicts
- <PREFIX><N>: VERIFIED — <≤ 1 line: where the fix landed and why it satisfies the original issue>
- <PREFIX><M>: STILL OPEN — <≤ 1 line: what remains, with file:line>
- <PREFIX><K>: REGRESSION — <≤ 1 line: what the fix broke, with file:line>

### New findings (omit this section entirely if none)
- <PREFIX><N+1> [blocker|should-fix|suggestion] <file>:<line> — <issue, ≤ 2 lines> — fix: <≤ 1 line>

### Summary
- PASS | FAIL
```

## Forbidden in gate-check output

The following sections (which a full dimensional review would normally
include) are **forbidden** here — strip them even if your agent file's
default Output Format defines them:

- `### Reviewer notes` (or `## Reviewer notes`)
- `### Methodology`, `### Process`, `### Hypothesis tracking`,
  `### Observations`
- `### Files of interest`, `### Scope`, `### What I read`
- `### Reference-Accuracy Audit` (the PSI-vs-grep caveat lives on the
  affected verdict line, not in a dedicated section)
- Any per-finding `Evidence:` / `Refutation considered:` /
  `Suggested fix:` subsections beyond the single-line shapes above —
  if a verdict needs more than 1 line to justify, mark it
  `STILL OPEN` with a 1-line pointer and the next iteration will
  carry the elaboration.
- A `### Summary` paragraph beyond the single PASS/FAIL line.

## Why the budget

Phase C ran 8 dimensional reviewers + 5 gate-check reviewers in a
single Track-21 session and pushed the orchestrator past the 30 %
context threshold before iter-2 could begin (YTDB-696). The original
spawn prompts asked for a verification format, but agents still
produced 100–300-line reports because the default Output Format
section in each `review-*.md` agent file was not overridden. This
prompt is the override — keep it strict.
