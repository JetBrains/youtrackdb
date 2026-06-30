# Review Agent Selection

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Baseline agents (always run) | orchestrator | 3A,3B,3C | The baseline review agents (code quality, bugs, test quality, plus concurrency on its category) and when they skip. |
| §Conditional agents | orchestrator | 3A,3B,3C | Add crash-safety, security, performance, or test-structure agents based on what the step or track touches. |
| §Examples | orchestrator | 3A,3B,3C | Worked agent-selection examples for storage, refactor, public-API, and WAL-replay changes. |
| §Step-level vs track-level routing | orchestrator | 3A,3B,3C | Non-mirrored note: which baseline and workflow reviewers run at a high step versus the cumulative track pass. |
| §Complexity sets the Phase-C rigor dial, never the set | orchestrator | 3A,3B,3C | Domain selects the Phase-C set at every level; complexity moves only iteration depth and termination; the floor is never suppressed. |
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
<!-- roles=orchestrator phases=3A,3B,3C summary="The baseline review agents (code quality, bugs, test quality, plus concurrency on its category) and when they skip." -->

These agents cover the dimensions relevant to every code change. The
first three are always-on; `review-concurrency` joins them whenever the
`concurrency` category is present:

| Agent | `subagent_type` | Finding prefix |
|---|---|---|
| Code quality | `review-code-quality` | `CQ1, CQ2, ...` |
| Bugs | `review-bugs` | `BG1, BG2, ...` |
| Concurrency (on the `concurrency` category) | `review-concurrency` | `CN1, CN2, ...` |
| Test quality | `review-test-quality` | `TB1, TB2, ...` / `TC1, TC2, ...` |

`review-bugs` owns every defect findable by single-threaded sequential
reasoning (logic, null safety, resource leaks, RID handling,
state-machine / lifecycle); `review-concurrency` owns every defect whose
detection needs reasoning about two or more threads interleaving and
fires only on the `concurrency` category. `review-test-quality` merges
the former test-behavior and test-completeness reviewers, carrying both
sub-protocols and both the `TB` and `TC` prefixes verbatim. The
cognitive-mode ownership boundary and the `review-bugs` triage backstop
are specified in the `review-bugs` / `review-concurrency` agent files.

The baseline group runs **unless the diff is workflow-only or
workflow-machinery + docs-only** — see the baseline-skip override under
*Workflow-machinery override* below.

The "always run" in the heading is the track-level reading. At a high
step only `review-bugs` runs from this group's always-on members (joined
by `review-concurrency` when the `concurrency` category is present); the
others (`review-code-quality`, `review-test-quality`) read identically on
the cumulative diff and defer to the Phase C track pass that runs the
full group. The step-vs-track timing for both the baseline group and the
workflow-review group lives in §Step-level vs track-level routing below,
not here; this carve-out stays subordinate to the baseline-skip override,
so a workflow-only or docs-only diff still skips the whole group,
`review-bugs` and `review-concurrency` included.

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
  6 agents.
- **Step refactors an internal utility class** → baseline only. 3 agents.
- **Step adds a new Gremlin traversal step with public API** → baseline +
  security (public API) + test-structure (likely new test fixtures).
  5 agents.
- **Step modifies WAL replay with lock changes** → all 10 agents
  (storage + performance + concurrency all apply; `review-concurrency`
  joins the baseline because the `concurrency` category is present).

*These counts assume the track-level baseline group, which is `review-code-quality` + `review-bugs` + `review-test-quality` (three always-on), joined by `review-concurrency` only when the `concurrency` category is present; at a high step the baseline group narrows to `review-bugs` (plus `review-concurrency` when the `concurrency` category is present) per §Step-level vs track-level routing.*

---

## Step-level vs track-level routing
<!-- roles=orchestrator phases=3A,3B,3C summary="Non-mirrored note: which baseline and workflow reviewers run at a high step versus the cumulative track pass." -->

Which agents fire at a high step differs from which review the cumulative
track diff at Phase C. An agent runs at a step only when its findings are
localized to that step's diff and would be buried if deferred to the
cumulative diff; otherwise it defers to the track pass, which loses no
coverage because the track pass runs the full selection. The per-agent
assignment in the paragraphs below is the operative rule; the
localized-versus-buried test explains why each agent lands where it does and
is not re-evaluated at dispatch time. This note is the
single source of truth for that timing; the dispatch points in
`step-implementation.md` (step) and `track-code-review.md` (track) consume
it. The note sits outside the four `§Maintenance`-mirrored sections below,
which mirror `code-review/SKILL.md` verbatim and carry no step/track
notion.

**Single-step-high override (read first).** Every narrowing below assumes
the high step is one of **several** steps in its track, so the deferred
reviewers it drops still run later at the Phase C track pass. When the high
step under review is the **sole step of its track**, that assumption fails:
the single-step-high Phase C skip (`code-review-protocol.md` §Single-step
tracks, `track-code-review.md` §Single-Step Track) skips the track pass, so
a deferred reviewer would run nowhere. In that case the step-level
selection runs the **full track-pass-equivalent selection** instead of
narrowing: every baseline and every workflow reviewer the Phase C track
pass would run for that diff. Apply this override before any group
narrowing below; each narrowing paragraph carries a lead clause pointing
back here so a dispatch reader sees the exception before the rule it
qualifies.

**Baseline group (§Baseline agents).** Sole-step-of-its-track exception:
the full selection runs at the step per the single-step-high override
above. Otherwise, at a high step of a **multi-step** track
`review-bugs` runs always, and `review-concurrency` runs when the
`concurrency` category is present; `review-code-quality` and
`review-test-quality` defer to the track pass. `review-bugs` catches
bug, logic-error, resource-leak, and null-safety defects that get buried
once a step's diff folds into the cumulative diff, so it must see each
step's diff in isolation; `review-concurrency` inherits the same burial
role for races and other interleaving defects in the step diff, which is
why it joins at the step whenever its `concurrency` category is present.
Deferring `review-code-quality` loses only style, DRY, and readability
findings: its buriable error-handling subset is already covered by
`review-bugs` at the step. The merged `review-test-quality` baseline
reads whole-suite quality off the cumulative diff identically, so the
step adds nothing.

**Workflow-review group (§Workflow-review agents).** Unless the high step
is the sole step of its track (then every workflow reviewer the track pass
would run fires at the step per the single-step-high override above), at a
high step of a **multi-step** track the step-level
workflow reviewers are selected by their existing per-agent file-pattern
globs in §Per-agent file-pattern triggers, not by the risk taxonomy. The
taxonomy decides only whether a step is tagged `high`; once a step reaches
step-level review, its changed-file globs decide which workflow reviewers
fire. Those globs are evaluated after the §Workflow-machinery override
staged-path normalization (the same normalization the track-level path
inherits), so a step's staged `docs/adr/<dir>/_workflow/staged-workflow/.claude/...`
edits match their live-path globs rather than silently missing. Of the six, `review-workflow-hook-safety` and
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

**High step editing only `.claude/workflow/*.md`.** Unless the high step is
the sole step of its track (then the full track-pass-equivalent selection
runs at the step per the single-step-high override above — for a bare
`.claude/workflow/*.md` diff that is the four deferred workflow reviewers
plus the two deferred baselines), at a high step of a **multi-step**
track such a step matches
neither step-level workflow trigger (neither `hook-safety`'s
script/settings globs nor `prompt-design`'s `SKILL.md` / agent /
prompt globs), so it draws zero step-level reviewers and fully defers to
the track pass. For a multi-step track this is correct on its own terms,
independent of the
prose-only cap (which governs a disjoint capped-`low` population): the
defect class a `.claude/workflow/*.md` high step risks is a gate or
control-flow change whose resume-path correctness can only be judged
against the cumulative diff, which is exactly what the track pass reviews.
A single step's slice of a multi-file gate change cannot be checked for
completeness in isolation. That rationale is what fails for a single-step
track: with one step the cumulative diff equals the step's diff and the
Phase C cumulative pass never runs, so the override above runs the full
selection at the step instead.

**Exclusion from workflow-machinery changes.** `review-bugs` and
`review-concurrency` are Java-code reviewers and never review workflow
machinery. On a workflow-only diff the baseline-skip override removes the
whole baseline group; on a mixed Java + workflow diff `IN_SCOPE_FILES`
scopes both to the Java files. The same Case-3
`IN_SCOPE_FILES` pre-filter complementarily scopes the step-level workflow
reviewers (`hook-safety`, `prompt-design`) to the workflow-machinery subset
of the diff (see the §Workflow-machinery override Case 3); the mechanics
live there and are not duplicated here. The Java review path
(`review-bugs` / `review-concurrency`) and the workflow review path (the
six workflow reviewers) are deliberately disjoint.

The split changes only which mandatory reviewers run at the step. No
conditional reviewer's trigger is widened and no agent is forced on; the
track-level selection is unchanged.

**Complexity does not drive step-level selection.** Step-level review
stays gated on the per-*step* `risk: high` tag plus the
localized-versus-buried routing above; the per-track complexity tag plays
no part in deciding which reviewers run at a step. The complexity tag
drives Phase-A panel breadth and the Phase-C rigor dial only — see
§"Complexity sets the Phase-C rigor dial, never the set" below.

---

## Complexity sets the Phase-C rigor dial, never the set
<!-- roles=orchestrator phases=3A,3B,3C summary="Domain selects the Phase-C set at every level; complexity moves only iteration depth and termination; the floor is never suppressed." -->

The category-driven selection above takes **no complexity input**: domain
(category presence) selects the dimensional reviewer set identically at
every per-track complexity level. The per-track complexity tag moves only
the **rigor dial** — what terminates the Phase-C review-iteration loop.
Blockers loop until clear at every level; the tag scales the should-fix
depth:

- `low` → uncapped blocker loop only; should-fix never drives iteration
  (remaining should-fix surface at track completion).
- `medium` → uncapped blocker loop plus up to three iterations to clear
  should-fix.
- `high` → uncapped on both blocker and should-fix.

The uncapped loops terminate by no-progress detection rather than a fixed
cap.

(The iteration mechanics live in
review-iteration.md:orchestrator,reviewer-plan,reviewer-dim-step,reviewer-dim-track:2,3A,3B,3C
§Limits; `track-code-review.md` §Review loop is the Phase-C dispatch
point that reads the per-track reconciled tag and applies the dial.)

The **floor** plus the domain-matched set is **never suppressed** by a
low complexity. Complexity never drops a reviewer the domain selected — a
track tagged `low` that nonetheless touches a dangerous category still
gets that category's specialist, because the Phase-C specialists are
gated on largely the same HIGH triggers that make a track `high`, so
letting complexity suppress a domain-selected specialist would subtract
review in the dangerous direction. A `high` tag adds **no** extra Phase-C
finding-verification beyond deeper iteration; it only lengthens the loop.

The floor every track gets: `review-code-quality`, `review-bugs`,
`review-test-quality` (plus `review-test-structure` when tests changed).
The workflow-machinery analog is `review-workflow-consistency` +
`review-workflow-context-budget`, joined by `review-workflow-writing-style`
(any `*.md`), `review-workflow-prompt-design` /
`review-workflow-instruction-completeness` (the SKILL / agent / prompt
globs), and `review-workflow-hook-safety` (`.claude/scripts/**`,
`.claude/hooks/*.sh`, or `.claude/settings*.json`) when the diff matches
their globs, per §"Per-agent file-pattern triggers".

The Phase-A panel reads the same per-track tag for breadth (how many of
the strategic trio run): `low` → Technical only; `medium` → +Adversarial;
`high` → +Risk +Adversarial. That panel logic is owned by
`track-review.md` §"Tier-driven review selection and which reviews to
run"; this section governs only the Phase-C dimensional set and rigor.

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
   `review-bugs`, `review-concurrency`,
   `review-test-quality`); launch the workflow-review group via
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
