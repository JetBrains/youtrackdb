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

The baseline group runs **unless the diff is workflow-only or
workflow-machinery + docs-only** — see the baseline-skip override under
*Workflow-machinery override* below.

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

## Workflow-review agents

These six agents review changes to the workflow machinery itself (skills,
agents, hooks, scripts, settings, workflow rules and prompts, output styles,
plan / design artifacts, and `CLAUDE.md`). They ignore Java code — the
baseline and conditional agents handle that.

| Agent | `subagent_type` | Finding prefix |
|---|---|---|
| Workflow consistency | `review-workflow-consistency` | `WC1, WC2, ...` |
| Workflow prompt design | `review-workflow-prompt-design` | `WP1, WP2, ...` |
| Workflow instruction completeness | `review-workflow-instruction-completeness` | `WI1, WI2, ...` |
| Workflow hook safety | `review-workflow-hook-safety` | `WH1, WH2, ...` |
| Workflow context budget | `review-workflow-context-budget` | `WB1, WB2, ...` |
| Workflow writing style | `review-workflow-writing-style` | `WS1, WS2, ...` |

### Workflow-machinery file set

A file is **workflow-machinery** when it is any of:

- A file under `.claude/` — skills, agents, hooks, scripts, settings,
  workflow rules (`.claude/workflow/*.md`), workflow prompts
  (`.claude/workflow/prompts/*.md`), output styles, docs.
- The project root `CLAUDE.md`.
- Any file under `docs/adr/<dir>/` — both the `_workflow/` working
  files (implementation plan, step files, design draft, reviews,
  step episodes) and the durable post-merge top-level artifacts
  (`design-final.md`, `adr.md`).

`workflow-machinery` is **exclusive with `docs-only`**: anything under
`docs/` outside `docs/adr/<dir>/` is `docs-only`, anything inside is
`workflow-machinery`. The definition mirrors `/code-review` SKILL.md
Step 5a verbatim so the standalone and in-workflow paths categorise
the same diff the same way.

### Per-agent file-pattern triggers

Each row matches the `/code-review` SKILL.md Step 5b workflow-review
table verbatim. `review-workflow-consistency` and
`review-workflow-context-budget` are **always launched** for this
group — they decide internally whether the diff actually affects
their dimension and emit an empty findings list otherwise. The other
four agents fire only when the diff touches one of their listed
patterns.

| Agent | Fires when changed files include |
|---|---|
| `review-workflow-consistency` | `workflow-machinery` is present — **always launched** for this group |
| `review-workflow-prompt-design` | `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, or `.claude/workflow/prompts/*.md` |
| `review-workflow-instruction-completeness` | `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, `.claude/workflow/*.md`, or `.claude/workflow/prompts/*.md` |
| `review-workflow-hook-safety` | `.claude/hooks/*.sh`, `.claude/scripts/**`, or `.claude/settings*.json` |
| `review-workflow-context-budget` | `workflow-machinery` is present — **always launched** for this group. The agent decides whether the diff affects any of three axes (always-loaded surface, load-on-demand discipline, instant per-operation consumption) and emits an empty findings list when none are affected. |
| `review-workflow-writing-style` | `.claude/**/*.md`, root `CLAUDE.md`, or `docs/adr/**/*.md` |

### Workflow-machinery override (baseline-skip)

The three cases below mirror `/code-review` SKILL.md Step 5d bullets 1,
2, and 3 verbatim. They govern how the baseline group, workflow-review
group, and conditional group interact when `workflow-machinery` is
present in the diff.

1. **Workflow-only diff** — `workflow-machinery` is the only category
   present. **Skip the baseline group** (`review-code-quality`,
   `review-bugs-concurrency`, `review-test-behavior`,
   `review-test-completeness`); launch the workflow-review group via
   the per-agent triggers above. Conditional agents do not fire (their
   triggers depend on Java characteristics).
2. **`docs-only` + `workflow-machinery` mix** — any combination of
   these two categories with no Java / test categories. Treat as
   workflow-machinery-only: skip the baseline group; launch the
   workflow-review group on the `workflow-machinery` files. The
   `docs-only` files do not dispatch any agent.
3. **`workflow-machinery` mixed with production-code or test
   categories** — launch each group's agents on its in-scope files.
   Pre-filter each group with an `IN_SCOPE_FILES` list so baseline
   and conditional agents see only Java / test files and the
   workflow-review group sees only workflow-machinery files. The
   filtered-dispatch shape mirrors `/code-review` SKILL.md Step 6 so
   cross-contamination is bounded.

In every other case (no `workflow-machinery` at all in the diff) the
override does not fire: baseline runs as usual, conditional agents
fire by their triggers, and no workflow-review agents launch.

---

## Selection process

1. Read the step description (or track description for Phase C).
2. Read the list of changed files (`git diff --name-only`).
3. Match against the characteristic table above (baseline triggers
   on the workflow-machinery override; conditional triggers on the
   Java characteristic table; workflow-review triggers on the
   per-agent file-pattern table).
4. Spawn the baseline agents (subject to the three-case override
   above) plus any matching conditional agents plus any matching
   workflow-review agents, each group dispatched with its filtered
   `IN_SCOPE_FILES` on mixed diffs, in a single parallel tool call.

The same selection process applies to both step-level and track-level
reviews. For track-level reviews, assess characteristics across the
full track diff.

### Examples — workflow-machinery override

- **Workflow-only diff.** Step rewrites
  `.claude/workflow/conventions-execution.md` §2.1 → only category is
  `workflow-machinery`; baseline skipped (case 1); workflow-review
  group fires `review-workflow-consistency` +
  `review-workflow-instruction-completeness` +
  `review-workflow-writing-style` + `review-workflow-context-budget`.
  4 agents.
- **`docs-only` + `workflow-machinery` mix.** Step edits
  `docs/architecture.md` (non-ADR `docs/` content, so `docs-only`)
  plus `.claude/workflow/track-review.md` (`workflow-machinery`) →
  case 2 fires; baseline skipped; the workflow-review group fires on
  the `.claude/` file only. The `docs/architecture.md` change
  dispatches no agent.
- **Mixed Java + workflow.** Step changes `core/.../BTree.java`
  (Java production code) plus `.claude/workflow/step-implementation.md`
  (`workflow-machinery`) → case 3 fires; baseline + workflow-review
  groups both launch, each filtered to its in-scope files. Any
  matching conditional agents (e.g., `review-performance` for the
  B-tree change) join the baseline group with the same Java-only
  filter.

<!-- Last sync-checked against .claude/skills/code-review/SKILL.md Step 5a/5b/5d/6 on 2026-05-15 (YTDB-817 Track 1 Step 3). Future drift sweeps update this date. -->
