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
