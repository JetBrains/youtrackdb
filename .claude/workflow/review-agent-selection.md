# Review Agent Selection

Characteristic-based selection of review sub-agents for step-level (Phase B)
and track-level (Phase C) code reviews. Referenced from `step-implementation.md`
and `track-code-review.md`.

Step-level review fires only for steps tagged `risk: high` per
[`risk-tagging.md`](risk-tagging.md); for `medium` and `low` steps,
selection is moot because no step-level agents are spawned. Track-level
review always runs. The selection rules below apply identically whenever
either review actually fires.

---

## Baseline agents (always run)

These four agents cover the dimensions relevant to every code change:

| Agent | `subagent_type` | Finding prefix |
|---|---|---|
| Code quality | `review-code-quality` | `CQ1, CQ2, ...` |
| Bugs & concurrency | `review-bugs-concurrency` | `BC1, BC2, ...` |
| Test behavior | `review-test-behavior` | `TB1, TB2, ...` |
| Test completeness | `review-test-completeness` | `TC1, TC2, ...` |

---

## Conditional agents

Add these based on what the step or track actually touches. The main agent
selects them using the step/track description and the list of changed files.
This is a judgment call, not a rigid filter — when in doubt, include the
agent.

| Code characteristic | Additional agents | Finding prefixes |
|---|---|---|
| WAL, storage engine, page cache, disk cache, durability, crash recovery, atomic operations | `review-crash-safety`, `review-test-crash-safety` | `CS`, `TY` |
| Public API, authentication, user input, network, serialization, deserialization | `review-security` | `SE` |
| Performance-sensitive paths, locks, contention, caching, large data structures, algorithmic complexity | `review-performance`, `review-test-concurrency` | `PF`, `TX` |
| Complex test setup, shared fixtures, test lifecycle, test isolation concerns | `review-test-structure` | `TS` |

### Examples

- **Step adds a histogram to a B-tree leaf page** → baseline + crash-safety
  + test-crash-safety (storage/durability) + performance (data structure).
  7 agents.
- **Step refactors an internal utility class** → baseline only. 4 agents.
- **Step adds a new Gremlin traversal step with public API** → baseline +
  security (public API) + test-structure (likely new test fixtures).
  6 agents.
- **Step modifies WAL replay with lock changes** → all 10 agents
  (storage + performance + concurrency all apply).

---

## Selection process

1. Read the step description (or track description for Phase C).
2. Read the list of changed files (`git diff --name-only`).
3. Match against the characteristic table above.
4. Spawn the baseline agents plus any matching conditional agents in a
   single parallel tool call.

The same selection process applies to both step-level and track-level
reviews. For track-level reviews, assess characteristics across the full
track diff.
