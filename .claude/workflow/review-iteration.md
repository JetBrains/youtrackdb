# Review Iteration Protocol

Shared by structural review ([`structural-review.md`](structural-review.md)),
track pre-execution reviews ([`track-review.md`](track-review.md)), and
track-level code review ([`track-code-review.md`](track-code-review.md)).

Load this document when running any of those review loops. It is **not**
needed at session startup.

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
`track-code-review.md` §Review loop step 3 — for the
`review-code-quality`, `review-bugs-concurrency`, `review-test-behavior`,
`review-test-completeness`, `review-crash-safety`, `review-security`,
`review-performance`, `review-test-structure`, `review-test-concurrency`,
`review-test-crash-safety` agents) **MUST** be spawned with the prompt
template at
[`prompts/dimensional-review-gate-check.md`](prompts/dimensional-review-gate-check.md).
That template enforces:

- **Total budget: ≤ 60 lines** of output per re-spawned agent.
- One verdict line per open finding (`VERIFIED` / `STILL OPEN` /
  `REGRESSION`), each ≤ 1 line of justification.
- **≤ 3 new findings**, each ≤ 5 lines total — surface regressions or
  obvious misses directly tied to the listed open findings; do not
  re-survey the diff.
- One-line `PASS` / `FAIL` summary.

The following sections — present in every dimensional review agent's
default Output Format — are **forbidden** at gate-check time:

- `Reviewer notes`
- `Files of interest`, `Scope`, `What I read`
- `Reference-Accuracy Audit` (the PSI-vs-grep caveat rides on the
  affected verdict line as `(grep-only)`)
- `Methodology`, `Process`, `Hypothesis tracking`, `Observations`
- Per-finding `Evidence:` / `Refutation considered:` / `Suggested fix:`
  subsections beyond the single-line shapes in the template
- Multi-paragraph `Summary` prose

**Why this matters (YTDB-696).** A Phase C session that runs 8
dimensional reviewers plus a 5-agent gate-check fan-out routinely
pushes the orchestrator's context past the 30 % `warning` threshold
before iter-2 begins, forcing a mid-Phase-C session pause. The dense
100–300-line gate-check reports are the dominant burn; the verbose
sections above carry almost no signal at gate-check time because
the orchestrator already has the original finding text. Stripping
them recovers ~70 % of the gate-check token budget.

The other gate-verification flows — structural review
([`prompts/structural-gate-verification.md`](prompts/structural-gate-verification.md)),
Phase A review gate
([`prompts/review-gate-verification.md`](prompts/review-gate-verification.md)),
consistency gate
([`prompts/consistency-gate-verification.md`](prompts/consistency-gate-verification.md))
— are out of scope for this budget. Each has its own dedicated
prompt file with a per-finding verification certificate format that
is already terse; do not retrofit the dimensional-review template
onto them.
