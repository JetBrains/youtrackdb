# Review Agent Selection

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Baseline agents (always run) | orchestrator | 3A,3B,3C | The four baseline review agents (code quality, bugs/concurrency, test behavior, test completeness) and when they skip. |
| §Conditional agents | orchestrator | 3A,3B,3C | Add crash-safety, security, performance, or test-structure agents based on what the step or track touches. |
| §Examples | orchestrator | 3A,3B,3C | Worked agent-selection examples for storage, refactor, public-API, and WAL-replay changes. |
| §Step-level vs track-level routing | orchestrator | 3A,3B,3C | Non-mirrored note: which baseline and workflow reviewers run at a high step versus the cumulative track pass. |
| §Workflow-review agents | orchestrator | 3A,3B,3C | The six workflow-machinery review agents and their finding prefixes; they ignore Java code. |
| §Workflow-machinery file set | orchestrator | 3A,3B,3C | What counts as workflow-machinery (.claude/, root CLAUDE.md, docs/adr/<dir>/); exclusive with docs-only. |
| §Per-agent file-pattern triggers | orchestrator | 3A,3B,3C | Which workflow-review agents fire on which changed-file patterns; consistency and context-budget always launch. |
| §Workflow-machinery override (baseline-skip) | orchestrator | 3A,3B,3C | Three cases governing baseline/workflow-review/conditional interaction when workflow-machinery is in the diff. |
| §Selection process | orchestrator | 3A,3B,3C | Read the description and changed files, match against the tables, and spawn the selected groups in one parallel call. |
| §Examples — workflow-machinery override | orchestrator | 3A,3B,3C | Worked override examples: workflow-only, docs+workflow mix, and mixed Java+workflow diffs. |
| §Maintenance | orchestrator | 3A,3B,3C | The mirrored sections must stay in sync with the /code-review skill; drift is a consistency-review defect. |

<!--Document index end-->

Characteristic-based selection of review sub-agents for step-level (Phase B)
and track-level (Phase C) code reviews. Referenced from `step-implementation.md`
and `track-code-review.md`.

Step-level review fires only for steps tagged `risk: high` per
risk-tagging.md:decomposer,orchestrator:3A,3B; for `medium` and `low` steps,
selection is moot because no step-level agents are spawned. Track-level
review always runs. The selection rules below apply identically whenever
either review actually fires.

---

## Baseline agents (always run)
<!-- roles=orchestrator phases=3A,3B,3C summary="The four baseline review agents (code quality, bugs/concurrency, test behavior, test completeness) and when they skip." -->

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

The "always run" in the heading is the track-level reading. At a high
step only `review-bugs-concurrency` runs from this group; the other
three (`review-code-quality`, `review-test-behavior`,
`review-test-completeness`) read identically on the cumulative diff and
defer to the Phase C track pass that runs the full group. The
step-vs-track timing for both the baseline group and the workflow-review
group lives in §Step-level vs track-level routing below, not here; this
carve-out stays subordinate to the baseline-skip override, so a
workflow-only or docs-only diff still skips the whole group,
`review-bugs-concurrency` included.

---

## Conditional agents
<!-- roles=orchestrator phases=3A,3B,3C summary="Add crash-safety, security, performance, or test-structure agents based on what the step or track touches." -->

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
<!-- roles=orchestrator phases=3A,3B,3C summary="Worked agent-selection examples for storage, refactor, public-API, and WAL-replay changes." -->

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

## Step-level vs track-level routing
<!-- roles=orchestrator phases=3A,3B,3C summary="Non-mirrored note: which baseline and workflow reviewers run at a high step versus the cumulative track pass." -->

Which agents fire at a high step differs from which review the cumulative
track diff at Phase C. An agent runs at a step only when its findings are
localized to that step's diff and would be buried if deferred to the
cumulative diff; otherwise it defers to the track pass, which loses no
coverage because the track pass runs the full selection. This note is the
single source of truth for that timing; the dispatch points in
`step-implementation.md` (step) and `track-code-review.md` (track) consume
it. The note sits outside the four `§Maintenance`-mirrored sections below,
which mirror `code-review/SKILL.md` verbatim and carry no step/track
notion.

**Baseline group (§Baseline agents).** At a high step only
`review-bugs-concurrency` runs; `review-code-quality`,
`review-test-behavior`, and `review-test-completeness` defer to the track
pass. `review-bugs-concurrency` catches bug, logic-error, resource-leak,
and null-safety defects that get buried once a step's diff folds into the
cumulative diff, so it must see each step's diff in isolation. Deferring
`review-code-quality` loses only style, DRY, and readability findings: its
buriable error-handling subset is already covered by
`review-bugs-concurrency` at the step. The two test-review baselines read
whole-suite quality off the cumulative diff identically, so the step adds
nothing.

**Workflow-review group (§Workflow-review agents).** The step-level
workflow reviewers are selected by their existing per-agent file-pattern
globs in §Per-agent file-pattern triggers, not by the risk taxonomy. The
taxonomy decides only whether a step is tagged `high`; once a step reaches
step-level review, its changed-file globs decide which workflow reviewers
fire. Of the six, `review-workflow-hook-safety` and
`review-workflow-prompt-design` run at the step: hook-safety's findings
(script correctness, `/tmp` collisions, JSON validity) and prompt-design's
findings (one prompt's internal decision rules, frontmatter, `$ARGUMENTS`)
are localized to the changed file. `prompt-design` stays at the step
because its core is that localized per-prompt surface; its cross-file
references are secondary and the track pass re-checks them. The other four
defer to the track pass: `review-workflow-consistency` (cross-file pairs,
where one step lands one side), `review-workflow-context-budget`
(whole-system always-loaded surface), `review-workflow-writing-style`
(diff-agnostic, identical per file), and
`review-workflow-instruction-completeness` (its gate and resume-path
checks span files, so a step lands false positives a later step resolves).

**High step editing only `.claude/workflow/*.md`.** Such a step matches
neither step-level workflow trigger (neither `hook-safety`'s
script/settings globs nor `prompt-design`'s `SKILL.md` / agent /
prompt globs), so it draws zero step-level reviewers and fully defers to
the track pass. This is correct on its own terms, independent of the
prose-only cap (which governs a disjoint capped-`low` population): the
defect class a `.claude/workflow/*.md` high step risks is a gate or
control-flow change whose resume-path correctness can only be judged
against the cumulative diff, which is exactly what the track pass reviews.
A single step's slice of a multi-file gate change cannot be checked for
completeness in isolation.

**Exclusion from workflow-machinery changes.** `review-bugs-concurrency`
is a Java-code reviewer and never reviews workflow machinery. On a
workflow-only diff the baseline-skip override removes the whole baseline
group; on a mixed Java + workflow diff `IN_SCOPE_FILES` scopes
`review-bugs-concurrency` to the Java files. The Java review path
(`review-bugs-concurrency`) and the workflow review path (the six
workflow reviewers) are deliberately disjoint.

The split changes only which mandatory reviewers run at the step. No
conditional reviewer's trigger is widened and no agent is forced on; the
track-level selection is unchanged.

---

## Workflow-review agents
<!-- roles=orchestrator phases=3A,3B,3C summary="The six workflow-machinery review agents and their finding prefixes; they ignore Java code." -->

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
<!-- roles=orchestrator phases=3A,3B,3C summary="What counts as workflow-machinery (.claude/, root CLAUDE.md, docs/adr/<dir>/); exclusive with docs-only." -->

A file is **workflow-machinery** when it is any of:

- A file under `.claude/` — skills, agents, hooks, scripts, settings,
  workflow rules (`.claude/workflow/*.md`), workflow prompts
  (`.claude/workflow/prompts/*.md`), output styles, docs.
- The project root `CLAUDE.md`.
- Any file under `docs/adr/<dir>/` — both the `_workflow/` working
  files (implementation plan, track files, design draft, reviews,
  step episodes) and the durable post-merge top-level artifacts
  (`design-final.md`, `adr.md`).

`workflow-machinery` is **exclusive with `docs-only`**: anything under
`docs/` outside `docs/adr/<dir>/` is `docs-only`, anything inside is
`workflow-machinery`. The definition mirrors `/code-review` `SKILL.md`
Step 5a verbatim so the standalone and in-workflow paths categorise
the same diff the same way.

### Per-agent file-pattern triggers
<!-- roles=orchestrator phases=3A,3B,3C summary="Which workflow-review agents fire on which changed-file patterns; consistency and context-budget always launch." -->

Each row matches the `/code-review` `SKILL.md` Step 5b workflow-review
table verbatim. `review-workflow-consistency` and
`review-workflow-context-budget` are **always launched** for this
group — they decide internally whether the diff actually affects
their dimension and emit an empty findings list otherwise. The other
four agents fire only when the diff touches one of their listed
patterns. `review-workflow-context-budget` checks three axes
(always-loaded surface, load-on-demand discipline, instant
per-operation consumption) and emits an empty findings list when none
are affected.

| Agent | Fires when changed files include |
|---|---|
| `review-workflow-consistency` | `workflow-machinery` is present — **always launched** for this group |
| `review-workflow-prompt-design` | `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, or `.claude/workflow/prompts/*.md` |
| `review-workflow-instruction-completeness` | `.claude/skills/*/SKILL.md`, `.claude/agents/*.md`, `.claude/workflow/*.md`, or `.claude/workflow/prompts/*.md` |
| `review-workflow-hook-safety` | `.claude/hooks/*.sh`, `.claude/scripts/**`, or `.claude/settings*.json` |
| `review-workflow-context-budget` | `workflow-machinery` is present — **always launched** for this group. |
| `review-workflow-writing-style` | `.claude/**/*.md`, root `CLAUDE.md`, or `docs/adr/**/*.md` |

### Workflow-machinery override (baseline-skip)
<!-- roles=orchestrator phases=3A,3B,3C summary="Three cases governing baseline/workflow-review/conditional interaction when workflow-machinery is in the diff." -->

**Staged-path normalization (run first).** On a workflow-modifying plan
the authored `.claude/...` edits live under
`docs/adr/<dir>/_workflow/staged-workflow/.claude/...` (per `§1.7`) while
the live tree stays at develop's state. The §Per-agent file-pattern
triggers globs name live `.claude/...` paths, so a staged path matches
none of them. Three glob-gated reviewers therefore miss and fail to
launch: `review-workflow-prompt-design`,
`review-workflow-instruction-completeness`, and
`review-workflow-hook-safety`. (`review-workflow-consistency` and
`review-workflow-context-budget` always run for this group;
`review-workflow-writing-style` already fires via its `docs/adr/**/*.md`
glob, and since its `.claude/**/*.md` glob also matches the normalized
path, it fires regardless of which form this row is evaluated against.)
Before evaluating the per-agent triggers below against the
workflow-machinery subset of the diff, normalize each changed path: a
path matching the anchored prefix
`docs/adr/<any-dir>/_workflow/staged-workflow/(\.claude/…)` is replaced by
its captured `.claude/…` remainder; the match is anchored after the
`docs/adr/<dir>/` head (the `<dir>` segment is variable). A path that
does not match this exact anchored prefix passes through unchanged,
including one that merely contains `.claude/` lower down. A staged file
then evaluates exactly as its live counterpart would, and the three
glob-gated reviewers launch on the staged edit. Normalization runs ahead
of the per-agent glob match only; it does not edit the globs themselves
(those live in §Per-agent file-pattern triggers) and does not change the
file-set categorization (a staged file is already `workflow-machinery` by
the `docs/adr/<dir>/` rule).

The three cases below mirror `/code-review` `SKILL.md` Step 5d bullets 1,
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
   filtered-dispatch shape mirrors `/code-review` `SKILL.md` Step 6 so
   cross-contamination is bounded. Per-agent file-pattern triggers
   from the table above are evaluated against the workflow-machinery
   subset of the diff to determine which workflow-review agents fire;
   `IN_SCOPE_FILES` is then narrowed to that subset per Step 6 of
   `/code-review` `SKILL.md`.

In every other case (no `workflow-machinery` at all in the diff) the
override does not fire: baseline runs as usual, conditional agents
fire by their triggers, and no workflow-review agents launch.

---

## Selection process
<!-- roles=orchestrator phases=3A,3B,3C summary="Read the description and changed files, match against the tables, and spawn the selected groups in one parallel call." -->

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
<!-- roles=orchestrator phases=3A,3B,3C summary="Worked override examples: workflow-only, docs+workflow mix, and mixed Java+workflow diffs." -->

- **Workflow-only diff.** Step rewrites
  `.claude/workflow/conventions-execution.md` `§2.1` → only category is
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
  groups both launch, each filtered to its in-scope files. The
  workflow-machinery subset (`.claude/workflow/*.md`) fires
  `review-workflow-consistency` +
  `review-workflow-instruction-completeness` +
  `review-workflow-context-budget` + `review-workflow-writing-style`
  (same four as the workflow-only example above; neither
  `review-workflow-prompt-design` nor `review-workflow-hook-safety`
  triggers, since no `.claude/skills/`, `.claude/agents/`,
  `.claude/workflow/prompts/`, `.claude/hooks/`, `.claude/scripts/`,
  or `.claude/settings*.json` file is touched). Any matching
  conditional agents (e.g., `review-performance` for the B-tree
  change) join the baseline group with the same Java-only filter.
- **Staged-path diff on a workflow-modifying plan.** Step edits
  `docs/adr/<dir>/_workflow/staged-workflow/.claude/skills/code-review/SKILL.md`
  → the staged-path normalization above strips the
  `docs/adr/<dir>/_workflow/staged-workflow/` prefix, so the path
  evaluates as `.claude/skills/code-review/SKILL.md`. The only category
  is `workflow-machinery` (the staged file is workflow-machinery by the
  `docs/adr/<dir>/` rule); baseline skipped (case 1). The normalized
  `.claude/skills/*/SKILL.md` path matches the
  `review-workflow-prompt-design` and
  `review-workflow-instruction-completeness` globs, so both launch
  alongside the always-run `review-workflow-consistency` +
  `review-workflow-context-budget` pair and
  `review-workflow-writing-style` (which fires via its
  `docs/adr/**/*.md` glob against the un-normalized staged path).
  `review-workflow-hook-safety` does not trigger (no `.claude/hooks/`,
  `.claude/scripts/`, or `.claude/settings*.json` file is touched).
  5 agents. Without normalization the two glob-gated reviewers would
  miss the staged path and fail to launch.
- **Staged path that does not normalize.** Step edits
  `docs/adr/<dir>/_workflow/staged-workflow/notes/.claude/x.md` → after
  the `docs/adr/<dir>/_workflow/staged-workflow/` head the remainder is
  `notes/.claude/x.md`, which does not begin with `.claude/`, so the
  anchored prefix does not match and the path passes through
  un-normalized (the rule's "merely contains `.claude/` lower down"
  case). The file is still `workflow-machinery` by the
  `docs/adr/<dir>/` rule, so the always-run
  `review-workflow-consistency` + `review-workflow-context-budget` pair
  and `review-workflow-writing-style` (via `docs/adr/**/*.md`) still
  fire, but the three glob-gated reviewers do not, because no
  normalized `.claude/...` path is produced. Only a `.claude/...`
  segment immediately after `staged-workflow/` normalizes.

### Maintenance
<!-- roles=orchestrator phases=3A,3B,3C summary="The mirrored sections must stay in sync with the /code-review skill; drift is a consistency-review defect." -->

`.claude/workflow/review-agent-selection.md` §Workflow-review agents,
§Workflow-machinery file set, §Per-agent file-pattern triggers, and
§Workflow-machinery override mirror `/code-review` `SKILL.md`
Step 5a/5b/5d/6 verbatim per `implementation-plan.md` D8. Any edit to
either file's mirrored sections MUST update both files in the same
commit and bump the date in the trailing `<!-- Last sync-checked … -->`
comment below. Drift between the two files is a defect for
`review-workflow-consistency`. The step-vs-track timing for the baseline
and workflow-review groups lives in §Step-level vs track-level routing, a
non-mirrored section, because `SKILL.md` carries no step/track notion.

<!-- Last sync-checked against `.claude/skills/code-review/SKILL.md` Step 5a/5b/5d/6 on 2026-06-01 (YTDB-1032 staged-path normalization). Future drift sweeps update this date. -->
