# Risk-Tagging for Step Decomposition

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Where this file is loaded | decomposer,orchestrator | 3A,3B | When the risk-tagging rules are read. |
| §Risk levels — quick reference | decomposer,orchestrator | 3A,3B | The low/medium/high levels and what each implies for review. |
| §HIGH-risk triggers | decomposer | 3A | Conditions that force a step to high risk. |
| §Concurrency | decomposer | 3A | Concurrency changes that trigger high risk. |
| §Crash-safety / Durability | decomposer | 3A | Crash-safety and durability changes that trigger high risk. |
| §Public API | decomposer | 3A | Public-API or serialized-form changes that trigger high risk. |
| §Security | decomposer | 3A | Security-sensitive changes that trigger high risk. |
| §Architecture / cross-component coordination | decomposer | 3A | Architectural or cross-component changes that trigger high risk. |
| §Performance hot path | decomposer | 3A | Hot-path performance changes that trigger high risk. |
| §Workflow machinery | decomposer | 3A | Workflow-machinery edits (.claude/**, root CLAUDE.md) that trigger high risk. |
| §Gate 1 reuse (change-level) | planner | 1 | The seven HIGH categories above are also tier Gate 1's source, read at the change level (central, not merely touched). |
| §Track-level complexity tag | planner,orchestrator | 1,3A | The seven HIGH triggers run at track granularity over planned work, predicting a tag reconciled to max(step tags). |
| §MEDIUM-risk triggers | decomposer | 3A | Conditions that put a step at medium risk. |
| §LOW-risk default | decomposer | 3A | The default low-risk classification for routine steps. |
| §Tests-only steps | decomposer | 3A | Risk handling for steps that add only tests. |
| §Prose-only workflow steps | decomposer | 3A | The prose-only cap for workflow-machinery steps that change no behavior. |
| §Override rules | decomposer,orchestrator | 3A,3B | How and when a risk tag may be overridden. |
| §Decomposer-time override | decomposer | 3A | Overriding the computed risk during decomposition. |
| §User override at Phase A end | orchestrator | 3A | Letting the user adjust a risk tag at the end of Pre-Flight. |
| §Phase B upgrade | orchestrator,implementer | 3B | Upgrading a step's risk when implementation reveals more scope. |
| §Risk locking | orchestrator | 3B,3C | Locking risk tags at the end of step implementation. |
| §Track-file format | decomposer,orchestrator | 3A | How the risk tag is written on the step roster line. |

<!--Document index end-->

Each step in a track gets a risk tag — `low`, `medium`, or `high` —
assigned by the decomposer at Phase A and locked once the step is
implemented. The tag controls whether Phase B runs step-level dimensional
review for that step (`high` → yes; `low`/`medium` → no). Track-level
review (Phase C) always runs against the cumulative track diff regardless
of the risk distribution.

The point of the tag is to spend review attention where tests can't easily
catch the issue — concurrency, durability, public API surface, security,
load-bearing architecture, performance hot path. For mechanical changes
well-covered by tests, step-level dimensional review is largely redundant
with tests plus track-level review, and the Phase B context cost isn't
justified.

## Where this file is loaded
<!-- roles=decomposer,orchestrator phases=3A,3B summary="When the risk-tagging rules are read." -->

- **Phase A (`track-review.md`)** — loaded when the decomposer assigns
  risk per step. Primary reader.
- **Phase B (`step-implementation-recovery.md`)** — loaded only on
  the rare upgrade path, when implementation reveals a step is more
  invasive than the plan suggested. Normal Phase B execution does NOT
  load this file; it reads the per-step inline `risk:` field from the
  `## Concrete Steps` roster line in the track file and gates sub-step
  4 on the tag value alone. The recovery file is itself loaded on
  demand and is where the upgrade handlers
  (`apply_upgrade_then_decide`, `rollback_and_upgrade`) live.
- **Phase C (`track-code-review.md`)** — does NOT load this file. The
  Phase C synthesizer reads the per-step risk tags from the track file
  and treats `medium` and `high` step ranges as focal points; no
  knowledge of the underlying criteria is needed.

## Risk levels — quick reference
<!-- roles=decomposer,orchestrator phases=3A,3B summary="The low/medium/high levels and what each implies for review." -->

| Level | Implementer model | Step-level review (sub-step 4) | Track-level review treatment |
|---|---|---|---|
| `high` | `opus` | Step-level dimensional review: `review-bugs` always + `review-concurrency` when the `concurrency` category is present (both subordinate to the workflow/docs-only baseline-skip override) + triggered conditional + step-level workflow reviewers (`hook-safety`, `prompt-design`), up to 3 iterations | Focal point |
| `medium` | `opus` | None | Focal point |
| `low` | `opus` | None | Default coverage |

**All implementer spawns use `opus` regardless of risk tag.** Sonnet's
reliability on multi-step implementation work is below the threshold
required for this workflow — even at `risk: low`, Sonnet implementers
intermittently execute steps with errors (skipped sub-steps, incorrect
test invocations, malformed return blocks) that Opus does not. The
implementer model is therefore not allocated by risk tag; only the
step-level dimensional review (sub-step 4) and the focal-point
treatment in track-level review remain risk-tag-driven.

The implementer-model column is informational — it documents the
model used by the Phase B orchestrator's spawn template. The operative
source is step-implementation.md:orchestrator:3B
`§Implementer Prompt Template`, which hard-codes `model: "opus"`
regardless of the step's risk tag (the orchestrator does not look the
model up from this column at spawn time). Because every row resolves
to `opus`, the `low → high` upgrade path (see §"Phase B upgrade"
below) does not change the model — it only enables sub-step 4's
dimensional review and promotes the step to a focal point in
track-level review.

Phase A reviews, the dimensional-review fan-out agents (which fire only
on `risk: high` steps), the Phase C track-level review, and the Phase
B/C orchestrators themselves also run on Opus — review and
orchestration capacity is not allocated by step tag.

## HIGH-risk triggers
<!-- roles=decomposer phases=3A summary="Conditions that force a step to high risk." -->

A step is `high` if it does ANY of the following.

### Concurrency
<!-- roles=decomposer phases=3A summary="Concurrency changes that trigger high risk." -->
- Introduces or modifies synchronization (locks, atomics, volatile,
  memory barriers)
- Changes lock acquisition order, lock scope, or which thread holds a lock
- Adds new thread spawning, executor submission, or async callback
- Touches shared mutable state (static caches, shared collections,
  singletons)
- Modifies code in or around `*StampedLock*`, `*ConcurrentHashMap*`,
  `Atomic*`, or other synchronization classes
- Modifies happens-before relationships or publication ordering

### Crash-safety / Durability
<!-- roles=decomposer phases=3A summary="Crash-safety and durability changes that trigger high risk." -->
- Modifies WAL records, WAL replay, or recovery code
- Changes page-level operations, atomic operation boundaries, or
  page-level consistency rules
- Touches storage components (`DiskStorage`, `AbstractStorage`
  subclasses, `StorageComponent` and its durable subclasses)
- Changes durability ordering (when `fsync` is called, when WAL is
  flushed, when checksums are validated)
- Modifies on-disk format or record serialization
- Adds or modifies double-write log behavior

### Public API
<!-- roles=decomposer phases=3A summary="Public-API or serialized-form changes that trigger high risk." -->
- Adds, removes, or changes signatures of types in
  `com.jetbrains.youtrackdb.api.*`
- Changes interfaces or abstract classes that have public-API
  implementers
- Modifies SPI interfaces (`META-INF/services/*`)
- Changes the serialized form of any public type

### Security
<!-- roles=decomposer phases=3A summary="Security-sensitive changes that trigger high risk." -->
- Touches authentication, authorization, or permission logic
- Handles user-supplied input at a system boundary (network, file path,
  query string)
- Modifies query construction (SQL or Gremlin), especially code that
  builds query strings dynamically
- Changes file path resolution or symlink handling
- Modifies cryptographic operations or key handling

### Architecture / cross-component coordination
<!-- roles=decomposer phases=3A summary="Architectural or cross-component changes that trigger high risk." -->
- Changes interfaces between major modules (core ↔ server, core ↔ driver)
- Modifies Component Map relationships listed in the plan
- Introduces a new abstraction layer or moves a load-bearing one
- Adds a new SPI registration or modifies how an existing SPI is loaded

### Performance hot path
<!-- roles=decomposer phases=3A summary="Hot-path performance changes that trigger high risk." -->
- Changes the record-read or index-read path
- Changes the query-execution inner loop
- Introduces or removes allocation in a known hot path
- Modifies cache lookup, hashing, or eviction logic

### Workflow machinery
<!-- roles=decomposer phases=3A summary="Workflow-machinery edits (.claude/**, root CLAUDE.md) that trigger high risk." -->
A workflow step is `high` if it does ANY of the following. The other
HIGH categories above are Java/storage-shaped, so a workflow-machinery
edit (a file under `.claude/**`, or root `CLAUDE.md`) matches none of
them; this category supplies the missing criteria, keyed to the same
blast-radius logic recast for machinery: does the artifact execute or
drive control flow, and how many sessions does a defect reach before a
human notices.

- Edits a hook, script, or `settings*.json` that runs automatically —
  a defect wedges every session that triggers it.
- Edits a load-bearing gate or control-flow protocol: the auto-resume
  state machine, the drift/divergence gate, the review-iteration
  protocol, the `§1.7` staging convention, or the `§1.6` stamp scheme.
- Edits the shared schema every file keys off: the `§1.8` role/phase
  enums, the document-index TOC format, or a closed glossary term.
- Edits the always-loaded context surface, root `CLAUDE.md`, whose
  content reaches every session of every project regardless of which
  workflow path runs. Always-loaded content has every-session blast
  radius, so it is HIGH and not MEDIUM.

### Gate 1 reuse (change-level)
<!-- roles=planner phases=1 summary="The seven HIGH categories above are also tier Gate 1's source, read at the change level (central, not merely touched)." -->

The seven HIGH-risk categories above — `Concurrency`,
`Crash-safety / Durability`, `Public API`, `Security`,
`Architecture / cross-component coordination`, `Performance hot path`,
and `Workflow machinery` — are also the source for the change-tier
**Gate 1** ("does the change need a `design.md`?", `planning.md` §Tier
classification). The same list drives two decisions from one source of
truth: the **per-step** tagging above (a step matches a category) and the
**change-level** Gate 1 (a category is central to the whole change). The
two readings differ in granularity, not in vocabulary.

Change-level is not "contains one high-risk step." A mostly-mechanical
change with a single risky line does not flip Gate 1 to yes. Gate 1 is
yes only when a category is **central to the change's purpose**, not
merely touched by one incidental edit; that judgment is the planner's and
the user ratifies it at tier confirmation. The set of *centrally-matched*
categories is recorded at confirmation because those same categories
prime the relocated adversarial review's lenses (a touched-but-not-central
category generates no lens). When a downstream doc surfaces these
category names for Gate 1, it quotes the headings above **verbatim** —
`Crash-safety / Durability`, not "durability"; `Architecture /
cross-component coordination`, not "load-bearing architecture" — so a
paraphrase never drifts the Gate-1 vocabulary from the live per-step
labels.

## Track-level complexity tag
<!-- roles=planner,orchestrator phases=1,3A summary="The seven HIGH triggers run at track granularity over planned work, predicting a tag reconciled to max(step tags)." -->

The per-track **complexity tag** runs the same seven HIGH triggers at
**track granularity**: `Concurrency`, `Crash-safety / Durability`,
`Public API`, `Security`, `Architecture / cross-component coordination`,
`Performance hot path`, and `Workflow machinery`. The tag is `high` if a
trigger fires centrally on the track's planned work, `medium` if only a
MEDIUM trigger does, `low` otherwise. It is the intensity knob the Phase-A
strategic panel and the Phase-C rigor dial read (`track-review.md`
§"Tier-driven review selection and which reviews to run").

The triggers run over the track's **planned work** — its `## Plan of Work`
(the prose sequence of edits) plus its `## Interfaces and Dependencies`
(the in-scope file set) — **not a bare file-path list**. The seven HIGH
triggers are **content predicates**: they test what a change *does*
("introduces synchronization", "modifies WAL recovery", "adds an
abstraction layer / SPI registration"), and a path list cannot answer a
verb-on-change predicate. The planner has described the planned edits by
the end of Phase 1, so the content needed to evaluate the triggers exists
in the track's prose.

**Three granularities read the same seven triggers** — keep them distinct
so the track-level read is not mistaken for the others:

- **Change-level** (Gate 1 reuse, §"Gate 1 reuse (change-level)" above):
  "is this category central to the whole change?" — the design-gate source.
- **Track-level** (this rule): "is this category central to *this track's*
  planned work?" — the complexity-tag prediction.
- **Step-level** (the per-step `risk:` tag, the HIGH/MEDIUM/LOW sections
  above): "does *this step* introduce it?" — the per-step focal-point tag.

The track-level read is a **prediction**: it judges the planner's described
work, not the realized diff. The reconciliation in `track-review.md`
§"Tier-driven review selection and which reviews to run" closes the gap —
after Phase A decomposes the track into steps, the predicted tag is
reconciled to `max(step tags)` (the largest per-step tag, computed from the
content-based step tags), and that reconciled tag governs Phase C. A thin or
vague `## Plan of Work` yields a weak prediction; the reconciliation against
the content-based step tags is the safety net that corrects an
under-prediction.

The complexity tag (the seven triggers) and the thirteen `code-review`
categories (which drive Phase-C reviewer selection) stay distinct and serve
two different purposes. The seven triggers answer *how hard* a track is
(Phase-A breadth + Phase-C rigor); the thirteen categories answer *which
dimensions* a track touches (which Phase-C reviewers run). The design maps one
onto the other.

The Phase-1 *request* for this prediction is wired into `planning.md` by
Track 1; this section owns the computation rule that request points at. The
orchestrator reconciles the prediction against `max(step tags)` at Phase A
per `track-review.md` §"Tier-driven review selection and which reviews to
run".

## MEDIUM-risk triggers
<!-- roles=decomposer phases=3A summary="Conditions that put a step at medium risk." -->

A step is `medium` if no HIGH trigger fires AND it has any of:

- New non-public methods or classes that change observable behavior of
  one component (i.e., not pure refactoring)
- Logic changes touching more than ~5 files within one module. This
  `~5` and the `~12` fill/split cap from `track-review.md`
  §"Step Decomposition" measure the same edited-file count for two
  different decisions, so they are complementary, not rival: `~5`
  raises a logic step to `medium`, while `~12` bounds how large a
  coherent step should grow. Fill-toward-`~12` will routinely push
  ordinary single-module steps past `~5`, producing a larger
  `medium`-tagged population that reaches Phase C focal-point review.
  That is intended (larger diffs warrant more focal-point attention),
  not a miscalibration; the `~5` value is unchanged.
  - **Tagging a merged step.** When the decomposer merges several
    `low`/`medium` changes into one step toward the `~12` fill target
    (per `track-review.md` §"Step Decomposition"), tag the merged step
    by re-applying these criteria to its *combined* content, not by
    carrying any constituent's tag forward. The result equals the max
    of the constituents' tags: `low+low → low`, `low+medium → medium`,
    `medium+medium → medium`. The `~5`-files-of-logic trigger above
    does the only non-obvious work — it raises a `low+low` logic merge
    to `medium` when the combined logic footprint crosses five files,
    with no merge-specific rule needed. A `high` change is never merged
    (it stays its own isolated step per `track-review.md`), so a merged
    step is never `high`; re-applying the criteria is always at least
    as heavy as the largest constituent, never less.
- Changes to test infrastructure or shared test fixtures
- New Maven dependencies, version bumps, or non-trivial build-config
  changes
- Logging changes that affect operational behavior (introduces a new
  log channel, changes log levels of known signals)
- Changes to error-handling code (exception types, retry logic, fallback
  paths) that aren't covered by a HIGH trigger
- Workflow machinery that is behavioral but bounded: one phase prompt's
  or skill's decision/dispatch logic, a single review-agent spec, adding
  or removing or renaming a section other files cross-reference, or
  multi-file prose that changes agent-observable behavior. (Edits that
  run automatically or drive a load-bearing gate are HIGH per
  §"Workflow machinery" above.)

## LOW-risk default
<!-- roles=decomposer phases=3A summary="The default low-risk classification for routine steps." -->

A step is `low` if no HIGH or MEDIUM trigger fires. Typical cases:
- Pure refactoring with provable behavior preservation (extract method,
  rename, move type — no semantic change)
- Adding new unit tests for existing code
- Updating Javadoc, in-line comments, or `docs/`
- One-line bug fixes with clearly isolated scope (e.g., null check on a
  reference that is documented as nullable)
- Extracting helpers without changing their behavior
- Adding configuration constants or new enum values that aren't yet
  wired to behavior
- Spotless / formatting fixes
- Workflow machinery that is prose or clarity only, with no behavioral
  change: a house-style reword, a typo fix, a TOC reindex, a glossary
  gloss that preserves meaning, a non-load-bearing example edit, or
  single-file prose touching no gate, dispatch, or schema

## Tests-only steps
<!-- roles=decomposer phases=3A summary="Risk handling for steps that add only tests." -->

A step that ONLY adds or modifies tests (no production code change) is
at most `medium`. It is `medium` only if it touches shared test
infrastructure or test fixtures (which can hide bugs across many tests).
Otherwise `low`.

If a step adds production code AND its tests in one commit, rate by the
production code.

## Prose-only workflow steps
<!-- roles=decomposer phases=3A summary="The prose-only cap for workflow-machinery steps that change no behavior." -->

A workflow-machinery step that edits ONLY prose (no hook/script/settings
change AND no gate/dispatch/schema change) is at most `low`. This is the
workflow analog of the tests-only cap above: the same way a test-only
commit cannot break production behavior, a prose-only workflow commit
cannot wedge a session or redirect control flow, so it skips the
step-level dimensional review path.

The cap is a ceiling for prose-only edits at every tier, not a carve-out
that fires only on files the HIGH category would otherwise tag. A step
qualifies for the cap on the content of the change, never on the
identity of the file: a wording-preserving edit to root `CLAUDE.md` is
prose-only and `low`, even though a control-flow-changing edit to the
same file is HIGH.

The hinge is whether the edit changes meaning. A meaning-changing
glossary, TOC-format, or enum edit alters the shared schema other files
key off and is HIGH per §"Workflow machinery" above (a TOC edit reaches
HIGH only when it changes the table's schema, not when it renames a
single row — a single-section rename is MEDIUM per §"MEDIUM-risk
triggers"); a gloss that reindexes a TOC or rewords a definition while
preserving its meaning changes no schema and is prose-only/`low`. The full qualifier ("no
hook/script/settings change AND no gate/dispatch/schema change")
prevents the cap from firing on a control-flow-driving prose edit that
the HIGH taxonomy also matches: if either half of the qualifier fails,
the step is not prose-only and the cap does not apply.

This risk bucket is orthogonal to the `review-agent-selection.md`
"workflow-machinery" file-set predicate: that predicate decides which
files the workflow reviewers scope to, while this cap decides how
dangerous a prose-only edit to such a file is. A file can be in the
reviewers' workflow-machinery set and still be capped at `low` here.

## Override rules
<!-- roles=decomposer,orchestrator phases=3A,3B summary="How and when a risk tag may be overridden." -->

### Decomposer-time override
<!-- roles=decomposer phases=3A summary="Overriding the computed risk during decomposition." -->
The decomposer applies the criteria above and may override the result
with a written reason in the step's risk note. Two specific cases:

- **Upgrade to `high` (safe direction):** when the decomposer is
  uncertain whether a step matches a HIGH category. "When in doubt,
  high" — the cost of an extra step-level review is much lower than
  the cost of missing a concurrency or durability bug.
- **Downgrade from a HIGH category (cautious direction):** when the
  step technically touches a HIGH category but the change is provably
  trivial (e.g., Javadoc-only edit inside a `*ConcurrentHashMap*`
  class). Requires a written justification in the risk note.

### User override at Phase A end
<!-- roles=orchestrator phases=3A summary="Letting the user adjust a risk tag at the end of Pre-Flight." -->
After the decomposer writes the track file, the user reviews the step
list and may change any risk tag before approving. This is the primary
safety net for criteria-application errors.

### Phase B upgrade
<!-- roles=orchestrator,implementer phases=3B summary="Upgrading a step's risk when implementation reveals more scope." -->
If implementing a step reveals that the change is more invasive than
the plan suggested (e.g., the "trivial refactor" turned out to require
lock ordering changes), the implementer flags the upgrade by returning
`RESULT: RISK_UPGRADE_REQUESTED` (per
implementer-rules.md:implementer:3B,3C `§Detection rules`).

The orchestrator's response depends on **when** the upgrade surfaces:

- **Pre-commit** (during the implementer's first attempt at the
  step, `mode=INITIAL` or `mode=WITH_GUIDANCE`) — the orchestrator
  rewrites the inline `risk:` field on the `## Concrete Steps`
  roster line and respawns from `mode=INITIAL` BEFORE running the
  dimensional review for that step. See
  step-implementation-recovery.md:orchestrator:3B
  `§apply_upgrade_then_decide`.
- **Post-commit** (during a `mode=FIX_REVIEW_FINDINGS` respawn — the
  upgrade surfaces only when applying review findings) — the
  orchestrator rolls back the original implementer commit and any
  prior `Review fix:` commits via `git revert`, rewrites the inline
  `risk:` field on the `## Concrete Steps` roster line, and respawns
  from `mode=INITIAL`. The next attempt re-runs implementation with
  full dim-review pressure from the start at the new risk level — not
  stacked on top of an implementation that was already reviewed under
  the old tag. See
  step-implementation-recovery.md:orchestrator:3B
  `§rollback_and_upgrade`.

In both cases, `medium → high` auto-applies; `low → high` pauses
for user confirmation. Upgrades are recorded in the step's risk note
(post-commit upgrades carry an additional `during dim review` marker
in the override). Downgrades are NOT permitted mid-Phase B — once the
step has been planned at a given risk level, neither implementer nor
orchestrator can self-relax review pressure.

### Risk locking
<!-- roles=orchestrator phases=3B,3C summary="Locking risk tags at the end of step implementation." -->
After a step is implemented (committed + episode written), the risk tag
is locked. Track-level review reads the locked risk tags and treats
`medium` and `high` as focal points when reviewing the cumulative track
diff.

## Track-file format
<!-- roles=decomposer,orchestrator phases=3A summary="How the risk tag is written on the step roster line." -->

Each step in `plan/track-N.md` is a thin numbered roster line in
the `## Concrete Steps` section (per D9 — no nested blockquote). The
risk tag rides inline on the same line, naming the level and the
triggering category (or `default` / `override: <reason>`). The step's
episode lives separately in `## Episodes` once Phase B writes it
(per D11).

```markdown
## Concrete Steps

1. Add StampedLock acquisition path for histogram updates — risk: high (concurrency: introduces optimistic-read-then-upgrade pattern in PageFrame)  [x] commit: 1a2b3c4d5e
2. Extract HistogramHeader struct from BTreePage — risk: low (default: pure refactoring; no semantic change)  [ ]
3. Wire histogram counter through tx-finalization path — risk: medium (multi-file logic in core; no HIGH triggers)  [ ]
4. Update Javadoc on AtomicLongFieldUpdater usage — risk: low (override: touches a HIGH category file but the change is Javadoc-only with no behavioral impact)  [ ]
```

The checkbox flips to `[x]` once the step is committed (or `[!]` if
the step failed); the inline `risk:` field stays in place and is
locked once implementation lands. Per-step episode content lives in
`## Episodes ### Step N — commit <SHA>, <ISO> [ctx=<level>]` blocks,
joined to the roster line by step number (see
episode-format-reference.md:orchestrator:3A,3B,3C).
