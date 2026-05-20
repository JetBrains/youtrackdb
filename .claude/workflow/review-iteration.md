# Review Iteration Protocol

Shared by structural review ([`structural-review.md`](structural-review.md)),
track pre-execution reviews ([`track-review.md`](track-review.md)), and
track-level code review ([`track-code-review.md`](track-code-review.md)).

Load this document when running any of those review loops. It is **not**
needed at session startup.

**House style for chat-scale prose.** User-facing prose produced from this file (status updates, escalation prompts, replanning summaries, review-mode loop turns, handoff notes, whichever apply) follows the AI-tell subset of `.claude/output-styles/house-style.md`: `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, and `### Em-dash discipline`. Structural rules (`§ BLUF lead`, `§ Structural rules` for the ≤200-word section cap, `§ Document-shape rules (design / ADR-specific)`) do not apply to chat-scale prose. See [conventions.md §1.5 Writing style for Markdown and prose artifacts](conventions.md) for the workflow-level anchor and tier mapping.

---

## Limits

- Max 3 iterations per review type.
- If blockers persist after 3 iterations, escalate.

---

## Finding ID prefixes

Finding IDs are cumulative across iterations (e.g., `T1`, `T2`, ... — not
reset between iterations).

| Prefix | Review type |
|---|---|
| `CR` | Consistency review |
| `S` | Structural review |
| `T` | Technical review |
| `R` | Risk review |
| `A` | Adversarial review |
| `CQ` | Code quality review |
| `BC` | Bugs & concurrency review |
| `CS` | Crash safety review |
| `SE` | Security review |
| `PF` | Performance review |
| `TB` | Test behavior review |
| `TC` | Test completeness review |
| `TS` | Test structure review |
| `TX` | Test concurrency review |
| `TY` | Test crash safety review |
| `WC` | Workflow consistency review |
| `WP` | Workflow prompt design review |
| `WI` | Workflow instruction completeness review |
| `WH` | Workflow hook safety review |
| `WB` | Workflow context budget review |
| `WS` | Workflow writing style review |

## Severity levels

**blocker** / **should-fix** / **suggestion** / **skip** (the `skip` severity
is only valid in track reviews — recommends skipping the entire track).

---

## Iteration flow

```
Iteration 1: Full review -> findings -> decisions -> apply fixes
Iteration 2: Gate check -> verify fixes + catch regressions -> if blockers, loop
Iteration 3: Gate check -> if still blockers, escalate
```

If structural fixes significantly restructure the plan (tracks reordered,
scope indicators changed substantially), re-run the full review instead of
the gate check to catch cascading issues.

---

## Finding format

```markdown
### Finding <PREFIX><N> [blocker|should-fix|suggestion|skip]
**Location**: <where in the plan or codebase>
**Issue**: <what's wrong>
**Proposed fix**: <concrete change>
```

## Gate verification output

For each previous finding: **VERIFIED**, **STILL OPEN** (with explanation),
or **REJECTED** (no action needed). New findings (if any) with cumulative
numbering. Summary: **PASS** or **FAIL**.

### Dimensional-review gate-check budget

Dimensional code-review gate-checks (the re-runs spawned at
`step-implementation.md` §Per-Step Orchestration Loop sub-step 4(d) and
`track-code-review.md` §Review loop step 3, for every dimensional
review agent listed in
[`review-agent-selection.md`](review-agent-selection.md)) **MUST** be
spawned with the prompt template at
[`prompts/dimensional-review-gate-check.md`](prompts/dimensional-review-gate-check.md).
That template enforces:

- **Total budget: ≤ 60 lines** of output per re-spawned agent.
- One verdict line per open finding (`VERIFIED` / `REJECTED` /
  `MOOT` / `STILL OPEN` / `REGRESSION`), each ≤ 1 line of
  justification.
- **≤ 3 new findings**, each ≤ 5 lines total. Surface regressions or
  obvious misses directly tied to the listed open findings; do not
  re-survey the diff.
- One-line `PASS` / `FAIL` summary.

### Gate-check verdict handling

The orchestrator processes each gate-check verdict as follows:

- `VERIFIED` — finding clears for this iteration; do not pass it to
  the next implementer spawn.
- `REJECTED` — the reviewer on re-read concluded the original
  finding was not a real issue (misread, false positive). Clears
  identically to `VERIFIED`; do not pass to the next implementer.
  Log a `REJECTED-VERDICT` entry in the §Synthesis audit trail of
  [`finding-synthesis-recipe.md`](finding-synthesis-recipe.md)
  Step 5 output so the next reviewer instance does not re-raise the
  same finding.
- `MOOT` — finding is no longer reachable (file deleted, code moved,
  approach changed). Clears for loop-termination purposes, identical
  to `VERIFIED`; do not pass to the next implementer.
- `STILL OPEN` — carry the finding into the next iteration's
  implementer input verbatim, with the same finding ID.
- `REGRESSION` — escalate as a blocker. Pass the regression's
  `file:line` plus the original finding ID to the next implementer
  with explicit `revert-or-repair` instructions: either revert the
  change that caused the regression and find a different approach to
  the original finding, or fix the regression in place if the new
  approach is sound. A `REGRESSION` forces the iteration `FAIL` even
  if every other verdict is `VERIFIED`.

### Gate-check synthesis routing

After collecting gate-check returns from all re-spawned agents,
re-run the canonical synthesis procedure in
[`finding-synthesis-recipe.md`](finding-synthesis-recipe.md) before
composing the next iteration's implementer input. The recipe's
§"Gate-check synthesis" section maps the five verdicts
(`VERIFIED` / `REJECTED` / `MOOT` / `STILL OPEN` / `REGRESSION`) to
forward / drop actions, then walks the aggregated `New findings`
blocks through dedup, severity, and bucketing as for any initial
synthesis. This deduplicates across dimensions and enforces the
global pre-spawn budget (~15 findings), so the per-agent ≤ 3
new-findings cap × N agents cannot silently inflate the total. The
routing applies identically at both review levels — track-level
re-enters from [`track-code-review.md`](track-code-review.md)
§Review loop, step-level from
[`step-implementation.md`](step-implementation.md) §Per-Step
Orchestration Loop sub-step 4(d) (gate-check collection), which
re-enters sub-step 4(b) for synthesis.

### Gate-check budget enforcement is best-effort

The ≤ 60-line cap is asked of the sub-agent but **not enforced** by
the orchestrator. If a returned report exceeds the budget, accept
the over-budget output and continue; do not retry, re-spawn, or
block. The cap is a steering signal, not a hard gate. Recurring
over-budget reports for a specific dimension across multiple
sessions are feedback to tune that agent file's default Output
Format, not a blocker for the current track.

### Forbidden sections at gate-check time

The following sections, present in every dimensional review agent's
default Output Format, are **forbidden** at gate-check time:

- `Reviewer notes`
- `Files of interest`, `Scope`, `What I read`
- `Reference-Accuracy Audit` (the PSI-vs-grep caveat rides on the
  affected verdict line as `(grep-only)`)
- `Methodology`, `Process`, `Hypothesis tracking`, `Observations`
- Per-finding `Evidence:` / `Refutation considered:` / `Suggested fix:`
  subsections beyond the single-line shapes in the template
- Multi-paragraph `Summary` prose

### Why the gate-check budget matters (YTDB-696)

A Phase C session that runs 8 dimensional reviewers plus a 5-agent
gate-check fan-out routinely pushes the orchestrator's context past
the 30 % `warning` threshold before iter-2 begins, forcing a
mid-Phase-C session pause. The dense 100–300-line gate-check reports
are the dominant burn; the verbose sections listed above carry
almost no signal at gate-check time because the orchestrator already
has the original finding text. Stripping them recovers ~70 % of the
gate-check token budget.

### Out of scope: structural / Phase A review-gate / consistency gates

The other gate-verification flows are out of scope for this budget:
structural review
([`prompts/structural-gate-verification.md`](prompts/structural-gate-verification.md)),
Phase A review gate
([`prompts/review-gate-verification.md`](prompts/review-gate-verification.md)),
and consistency gate
([`prompts/consistency-gate-verification.md`](prompts/consistency-gate-verification.md)).
Each has its own dedicated prompt file with a per-finding
verification certificate format that is already terse; do not
retrofit the dimensional-review template onto them.
