---
name: edit-design
description: "Apply an edit to `design.md` or `design-mechanics.md` through the mutation discipline: apply → auto-review → iterate → present. Use this instead of directly Editing those files."
argument-hint: "<plan-dir-path> [<mutation-kind>]"
user-invocable: false
---

## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: orchestrator, planner, or final-designer (whichever invoked this skill).
Your phase: determined by the auto-resume State in `workflow.md` § Startup Protocol.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Two operational modes | orchestrator,planner,final-designer | 1,4 | Working mode edits the polished design; sync mode re-distills it from the mechanics companion. |
| §Skill inputs | orchestrator,planner,final-designer | 1,4 | The mutation kind, target file(s), and edit payload the skill consumes on each invocation. |
| §Cold-read scope and check-set by mutation kind | orchestrator,planner,final-designer | 1,4 | The per-mutation-kind table mapping each kind to its target files, cold-read scope, and mechanical check set. |
| §Workflow | orchestrator,planner,final-designer | 1,4 | The mutation loop: two shapes — the dual-clean multi-agent loop for the creation kinds, the single-agent loop for the interactive kinds. |
| §Step 1: Apply the edit | orchestrator,planner,final-designer | 1,4 | Spawn the code-grounded author for the creation kinds or edit inline for the interactive kinds; stamp only on the creation kinds. |
| §Step 1.5: Distillation (only for `design-sync`) | orchestrator,planner,final-designer | 1,4 | For design-sync only, re-distill the polished design from the current mechanics companion before the cold read. |
| §Step 2: Determine cold-read scope | orchestrator,planner,final-designer | 1,4 | Pick the cold-read scope (bounded or whole-doc) for this mutation kind from the check-set table. |
| §Step 3: Run mechanical checks | orchestrator,planner,final-designer | 1,4 | Run the mutation kind's mechanical checks (link resolution, stamp position, section presence) before the cold read. |
| §Step 4: Run the review sub-agents | orchestrator,planner,final-designer | 1,4 | Spawn the review roles: the per-round pair plus the S3-gated comprehension gate for creation kinds, or the single comprehension read for interactive kinds. |
| §Step 5: Merge findings | orchestrator,planner,final-designer | 1,4 | Merge the mechanical-check and review findings into one deduplicated list for the iterate step. |
| §Step 6: Iterate | orchestrator,planner,final-designer | 1,4 | Run the dual-clean inner loop for creation kinds or the single-agent fix loop for interactive kinds until findings clear or the cap is reached. |
| §Step 7: Append to the review log | orchestrator,planner,final-designer | 1,4 | Append the mutation's record to the design-mutations log, which is itself exempt from stamping. |
| §Step 8: Auto-suggest sync at N=5 (working mode only) | orchestrator,planner,final-designer | 1 | In working mode, suggest a design-sync once five mechanics edits have accumulated since the last sync. |
| §Step 9: Present to the user | orchestrator,planner,final-designer | 1,4 | Present the merged result and surviving findings to the user as the mutation's final output. |
| §Staleness reconciliation | orchestrator,planner,final-designer | 1,4 | The prompt shown when a request references a polished design that mechanics edits have since outpaced. |
| §Tools used | orchestrator,planner,final-designer | 1,4 | The tools the skill invokes: the mechanical-check script, Edit/Write, and the author and review-role spawns. |
| §When NOT to use this skill | orchestrator,planner,final-designer | 1,4 | The cases that bypass the mutation discipline: non-design files and pure workflow-artifact edits. |
| §Failure modes and recovery | orchestrator,planner,final-designer | 1,4 | How the skill recovers when a check fails, the cold read stalls, or the iteration budget is exhausted. |
| §Examples | orchestrator,planner,final-designer | 1,4 | Worked examples of a content edit and a section rename run through the full mutation discipline. |
| §Reference | orchestrator,planner,final-designer | 1,4 | On-demand pointers to the design-document rules, the file layout, and the mutation-kind definitions. |

<!--Document index end-->

Apply an edit to `design.md` (or `design-mechanics.md`) through the **mutation
discipline** defined in `.claude/workflow/design-document-rules.md`. The skill
bundles `(apply edit → auto-review → bounded iterate → present)` into one
atomic action so the structural rules are self-enforcing. On the **creation
kinds** (`phase1-creation`, `phase4-creation`) the skill is a multi-agent
orchestrator: it spawns the code-grounded author to write the document and runs
the dual-clean inner loop (the cold readability auditor plus a per-round second
check) followed by the cold comprehension gate. The **interactive kinds** keep
the original single-agent shape (edit inline, one cold-read, iterate).

> **Stamp discipline.** `design.md` and `design-mechanics.md` carry a line-1 `<!-- workflow-sha: <40-char SHA> -->` stamp written at creation only: by this skill on the `phase1-creation` and `length-trigger-crossing` kinds, or by `/create-plan`'s planning-transition step when it seeds `design.md` directly. Every other mutation kind (`content-edit`, `section-add`, `section-remove`, `section-rename`, `section-move`, `structural-rewrite`, `mechanics-edit`, `design-sync`) leaves the stamp untouched and preserves its line-1 position; only creation, migration replay, and no-drift normalization write the stamp. The prepend is performed via `Edit`/`Write` against the now-existing file, not a shell redirect. `design-mutations.md` is deliberately excluded from stamping (see the review-log append step for the rationale). Phase 4 final artifacts (`design-final.md`, `design-mechanics-final.md`) are not stamped either; they survive the merge into `develop` where per-branch migration never applies. Format definition, parser idioms, and the paired SHA-computation idiom that the `phase1-creation` and `length-trigger-crossing` kinds copy verbatim are anchored in conventions.md:orchestrator,planner,final-designer:1,3A,3C,4 `§1.6`. Read that section for the single source of truth.

**You MUST use this skill — not raw `Edit`/`Write` — for every modification to
`design.md` / `design-mechanics.md` and for every Phase 4 creation of
`design-final.md` / `design-mechanics-final.md`.** That includes initial
creation in Phase 1 (`phase1-creation`), interactive iteration ("add a
section about X"), and Phase 4 production of the final committed artifacts
(`phase4-creation`). The design is frozen after Phase 1 (`design-document-rules.md`
Rule 15), so Phase 3 inline replanning never invokes this skill — replan design
intent is recorded in the plan's Decision Records and the track narrative
instead (see inline-replanning.md:orchestrator:3A,3C § Process).

## Two operational modes
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Working mode edits the polished design; sync mode re-distills it from the mechanics companion." -->

The skill supports two complementary workflows. Pick by where you are in
the plan lifecycle:

- **Working / sync** (Phase 1 and large iterative revisions): mutation
  kinds `phase1-creation`, `mechanics-edit`, `design-sync`. `design.md`
  stays frozen between syncs as a stable reference; cold-read is deferred
  to sync.
- **Direct mutation** (small post-publication edits): mutation kinds
  `content-edit`, `section-add`, `section-remove`, `section-rename`,
  `section-move`, `structural-rewrite`, `length-trigger-crossing`. Full
  discipline runs on every mutation.

**Phase 4 special case.** Phase 4 produces `design-final.md` (and
`design-mechanics-final.md` if the original had a mechanics companion).
Use the `phase4-creation` kind — structurally similar to
`phase1-creation` (one-shot creation, full discipline; one or both
files depending on whether a mechanics companion is needed) but
targeting the `*-final.md` paths and skipping plan / track-file ref
propagation (those refs point at the original `design.md`, not at the
new final artifact). No follow-up `mechanics-edit` / `design-sync` cycle:
Phase 4 is committed once.

Full rationale, sub-phase diagram, and sync-trigger rules live in
`design-document-rules.md § Two-mode editing — working vs sync`.

## Skill inputs
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="The mutation kind, target file(s), and edit payload the skill consumes on each invocation." -->

The invoking agent supplies these when calling the skill:

| Input | What it carries |
|---|---|
| `design_path` | Absolute path to `design.md` (or `design-final.md` in Phase 4). |
| `design_mechanics_path` | Absolute path to `design-mechanics.md` (or `null` if no companion). |
| `plan_path` | Absolute path to `implementation-plan.md` (for `**Full design**` link resolution). |
| `plan_dir` | Absolute path to the `plan/` directory containing every `plan/track-N.md` track file (same purpose — each track file's `## Decision Log` may carry `**Full design**` references that the cross-file ref check has to resolve). |
| `target` | `design`, `mechanics`, or `both` — the file(s) the edit touches. Threaded through to the script's `--target` flag verbatim. (No `.md` suffix — the script's argparse choices are `design`/`mechanics`/`both`.) |
| `intended_edit` | Either `(old_string, new_string)` for a focused edit, or full new content for a section-add / section-rewrite / file creation. |
| `mutation_kind` | One of the values listed in the mode table above. |
| `changed_section` | Title of the section being changed (for bounded cold-read scope). For `section-rename`, supply the **new** name. Optional for `mechanics-edit` and `design-sync`. |
| `iteration_budget` | Default `3` — max number of (apply → review) rounds. |

If any required input is missing, **ask the user before proceeding.** The
mutation discipline depends on the agent stating the mutation kind explicitly
so the cold-read scope and check-set are correct; do not guess.

## Cold-read scope and check-set by mutation kind
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="The per-mutation-kind table mapping each kind to its target files, cold-read scope, and mechanical check set." -->

The `--target` column reads as a function of whether
`design-mechanics.md` exists at the time of the mutation. When a value
is written `design \| both`, resolve to `design` if the mutation
touches only `design.md` (the common case for small designs without a
mechanics companion) or `both` if the mutation also propagates into
`design-mechanics.md`.

| Mutation kind | Touches | Mechanical script `--target` | Cold-read scope |
|---|---|---|---|
| `phase1-creation` | `design.md` only when the design will not need a mechanics companion (small designs under ~5 sections), or both files when the design will exceed the length trigger / already plans for mechanics | `design \| both` | `whole-doc` on `design.md` (mechanics is exempt from cold-read since it's agent-targeted) |
| `mechanics-edit` | mechanics only | `mechanics` | **NONE** — cold-read deferred to next `design-sync` |
| `design-sync` | both files (re-distill `design.md` from updated mechanics) | `both` | `whole-doc` on `design.md`, plus mechanics-link-resolution sweep |
| `content-edit` | `design.md` | `design` | `bounded` — changed section + 1-2 surrounding sections + Overview + (when present) Core Concepts |
| `section-add` | `design.md` | `design` | `bounded` — new section + Overview + (when present) Core Concepts + structure roadmap |
| `section-remove` | `design.md` (+ plan / track-file ref cleanup — `**Full design**` lines pointing at the removed section must be updated in the same mutation, otherwise `**Full design**` link resolution fails) | `design` | `whole-doc` |
| `section-rename` | `design.md` + (when mechanics exists) the matching section in `design-mechanics.md` + plan / track-file ref propagation | `design \| both` | `whole-doc` |
| `section-move` | `design.md` | `design` | `whole-doc` |
| `structural-rewrite` | `design.md` + (when mechanics exists and any rename or split propagates) the matching sections in `design-mechanics.md` | `design \| both` | `whole-doc` |
| `length-trigger-crossing` | both files (split into design-mechanics) | `both` | `whole-doc` |
| `phase4-creation` | `design-final.md` + (optional) `design-mechanics-final.md` | `both` if mechanics-final exists, else `design` | `whole-doc` on `design-final.md` (mechanics-final is exempt — agent-targeted long-form). Skip plan / track-file ref propagation: omit `--plan-path` / `--plan-dir` so the cross-file ref check is naturally skipped. |

**Periodic whole-doc check.** Independent of mode: every Nth design-touching
mutation (default `N=5`, counted from the review log) escalates the cold-read
scope to `whole-doc` regardless of the kind. `mechanics-edit` mutations do
NOT increment this counter.

**Two distinct N=5 counters.** Both fire at "5", but they count different
things and trigger different actions; do not collapse them mentally:

| Counter | Counts | Resets on | Triggers |
|---|---|---|---|
| Periodic whole-doc counter | All mutation log entries except `mechanics-edit` | Never resets — running modulo over the log | Cold-read scope is escalated to `whole-doc` for the current mutation, regardless of its declared scope |
| Working-mode counter | `mechanics-edit` entries since the most recent `design-sync` (or since `phase1-creation` if no sync has happened yet) | Resets to 0 on every `design-sync` | The skill surfaces *"5 mechanics edits have accumulated since the last sync — want me to run `design-sync`?"* at the next conversational turn (Step 8) |

See design-document-rules.md:planner,final-designer:1,4 `§ Mutation discipline § Cold-read scope by mutation kind` for the canonical statement of both counters.

## Workflow
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="The mutation loop: a dual-clean multi-agent loop for creation kinds, a single-agent loop for interactive kinds." -->

The high-level steps are the same across all mutation kinds; what differs is
who does the work inside the apply and review steps, and how the cold-read pass
is gated. Two shapes run under one frame:

- **The creation kinds (`phase1-creation` and `phase4-creation`) run the
  dual-clean multi-agent loop.** Step 1 spawns the code-grounded author instead
  of authoring inline; Step 4 spawns the per-round readability-auditor plus its
  second per-round check (the warm absorption check at `phase1-creation`, the
  fidelity check at `phase4-creation`), then — after the inner loop converges —
  the cold comprehension gate; Step 6 is the bounded dual-clean inner loop.
  These are the kinds the design-creation callers route through: `create-plan`
  Step 4a routes `phase1-creation`, `create-final-design.md` routes
  `phase4-creation`.
- **Every other (interactive) mutation kind keeps the single-agent shape.**
  Step 1 applies the edit inline; Step 4 spawns the cold comprehension gate
  (plus, on `design-sync` only, a cold `readability-auditor` prose pass so the
  re-distilled human-facing prose keeps its one prose owner, S4); Step 6
  iterates on the merged findings. These kinds touch a frozen, already-reviewed
  `design.md` post-publication, so the author spawn buys nothing and the lighter
  loop is correct.

There is no in-skill adversarial pass on any kind. The decision/assumption
challenge for `phase1-creation` was relocated onto the research log at the
Phase 0 → 1 gate (D6, `prompts/adversarial-review.md` §Research-log-scoped
review (Phase 0→1)), so for `phase1-creation` the Step 4 cold comprehension
gate is **gated** behind that log-adversarial gate clearing (the S3 freeze-order
gate; see Step 4) rather than preceded by a local adversarial step. Every other
mutation kind runs its cold-read with no gate.

### Step 1: Apply the edit
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Spawn the code-grounded author for the creation kinds or edit inline for the interactive kinds; stamp only on creation." -->

The apply step has two shapes. **The creation kinds (`phase1-creation`,
`phase4-creation`) spawn the code-grounded author** to write the document; the
skill never writes the seed content inline. **Every interactive mutation kind**
(`content-edit`, `section-add`, `section-remove`, `section-rename`,
`section-move`, `structural-rewrite`, `length-trigger-crossing`,
`mechanics-edit`, `design-sync`) applies the edit inline with `Edit`/`Write` as
before — read the target file first to satisfy the `Edit` precondition, then
apply the focused edit or full-file rewrite. The stamp directives below apply
to both shapes (the author writes content; the skill owns the line-1 stamp).

**Spawn the author for the creation kinds.** For `phase1-creation` and
`phase4-creation`, decide the companion-file shape and the seed scope exactly as
described below, then hand that decision to the `design-author` agent rather
than writing the content yourself. The author is the sole writer of the document
(`.claude/agents/design-author.md`): it reads the research log and the live
codebase through PSI — never this authoring conversation — and drafts cold-readable
prose for a reader who has only the finished document. The author write happens on
round 1 of the inner loop and again on each later round against the auditor's
flagged passages; the spawn mechanics, the params-file contract, and the
ground-once-with-targeted-re-grounding lever live in Step 6 (the inner loop)
where the per-round author re-spawn is wired. On round 1 the author writes the
full seed; Step 1's job is to settle the companion-file and seed-scope decision
the round-1 author spawn carries.

For `phase1-creation`: decide first whether the design needs a mechanics
companion. **Default is single file.** Most designs (under ~5 sections,
no `# Part N` headings, no anticipated long-form derivations) seed only
`design.md` — pass `target=design` and leave `design_mechanics_path=null`.
The author seeds `design.md` with Overview (concept-first elevator pitch), Core
Concepts (when the doc will have Parts or ≥3 new domain terms), Class
Design, Workflow, and TL;DR-shaped Part sections.

Seed both files only when the design genuinely needs the split — typically
when the user has signaled it up front ("this will have a mechanics
companion") or when a single-file seed would already exceed the
2,000-line / 50,000-token length trigger. In that case, pass
`target=both` and `design_mechanics_path=<abs path>`; the author seeds
`design-mechanics.md` with the long-form mechanism content that supports
each `design.md` section, with section names matching between the two
files from the start. A design that doesn't need mechanics on day 1
crosses into one later via `length-trigger-crossing`, not by retroactively
re-running `phase1-creation`.

**Stamp the seeded file(s) with an idempotency guard.** Apply this
directive **after** the initial `Write` lands the seeded content on
disk; the presence check then runs against the just-written file. A
missing file is treated identically to an unstamped file.
`phase1-creation` is the canonical writer for `design.md` (and
`design-mechanics.md` when `target=both`), but `/create-plan`'s
planning-transition step also writes `design.md` directly from its own
template with the stamp already in place. Both invocation paths
converge here, so the directive below must stamp an unstamped file and
skip the prepend on an already-stamped one.

For each path the kind touches (`design_path`; `design_mechanics_path`
as well when `target=both`), run the presence check from
conventions.md:orchestrator,planner,final-designer:1,3A,3C,4 `§1.6(a1)`:

```bash
head -1 <path> | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'
```

A zero exit code means the file is already stamped — skip the prepend
for that path (this is the post-`/create-plan` case, where
`design.md`'s line 1 already carries the stamp written by the
planning-transition step's template, or the `target=both` case where
`/create-plan` seeded the dual files and both files already carry the
stamp). A non-zero exit code means the file is unstamped — compute
`$WORKFLOW_SHA` via the `§1.6(b)` paired idiom and prepend
`<!-- workflow-sha: $WORKFLOW_SHA -->` (followed by a newline) above the
H1, then re-read the file to satisfy the next `Edit` precondition:

```bash
WORKFLOW_SHA="$(git log -1 --format=%H HEAD -- .claude/workflow .claude/skills .claude/agents)"
[ -z "$WORKFLOW_SHA" ] && WORKFLOW_SHA="$(git rev-parse HEAD)"
```

Compute `$WORKFLOW_SHA` at most once per invocation — when both paths
need a stamp (a direct `phase1-creation` invocation outside
`/create-plan` with `target=both`), reuse the same value so the two
sibling files start life with matching stamps. The guard is symmetric
across `design_path` and `design_mechanics_path`: a same-invocation
run where `/create-plan` pre-stamped one file and the other was added
after (an edge case the guard tolerates by design) is handled by the
per-path presence check.

Cross-session `target=both` may produce non-matching stamps on
`design.md` and `design-mechanics.md` when the `phase1-creation`
invocation lands in a later session than `/create-plan`'s preamble.
The drift gate's no-drift normalization collapses the divergence on
the next clean gate run, and the per-branch migration reunifies the
stamps end-of-migration.

For `phase4-creation`: same as `phase1-creation` — the author writes the
document — but the file paths are `design-final.md` and (optional)
`design-mechanics-final.md`, and the content reflects what was *actually built*
(not the planned design). The author grounds on the step and track episodes and
the live code rather than the research log (the Phase 4 second check is fidelity,
not absorption; see Step 6). The caller (`prompts/create-final-design.md`) is
expected to have run the PSI-backed verification tables before invoking the
skill, so each diagram element traces to a real code location. Do **not** pass
`--plan-path` / `--plan-dir` (the cross-file ref check is naturally skipped; see
the table above). **Skip the idempotency-guarded stamp directive above.**
Phase 4 final artifacts are not stamped: see the Stamp-discipline
blockquote at the top of this file and `conventions.md` `§1.6(f)`.

For `length-trigger-crossing`: split a single-file `design.md` that has
grown past the ~2,000-line / ~50,000-token threshold into the canonical
pair. Caller-supplied `design_mechanics_path` carries the absolute path
of the new sibling file; `target=both`. Move every long-form mechanism
walk-through, full state-machine table, exhaustive worked example, and
file:line citation out of `design.md` and into the freshly-created
`design-mechanics.md`. Keep Overview, Core Concepts, every section's
TL;DR + mechanism overview + edge cases + references footer in
`design.md`; keep diagrams in `design.md` and duplicate any diagram into
`design-mechanics.md` only when the mechanics-side prose needs the same
visual context. Every section name in `design-mechanics.md` matches the
corresponding section name in `design.md` byte-for-byte so that each
section's `Mechanics: design-mechanics.md §"<exact same section name>"`
link resolves and the plan / track-file `**Full design**` references
land in either file by name. See design-document-rules.md:planner,final-designer:1,4 `§ Length-triggered split into design-mechanics.md` for the
canonical split rule.

Stamp the freshly-created `design-mechanics.md` before continuing.
The file is unstamped at creation, so the per-path presence check from
conventions.md:orchestrator,planner,final-designer:1,3A,3C,4 `§1.6(a1)` will always
return non-zero on this path — but applying the guard keeps the
directive symmetric with the `phase1-creation` paragraph above and
tolerates a re-invocation against an already-split pair:

```bash
head -1 <design_mechanics_path> | grep -qE '<!-- workflow-sha: [0-9a-f]{40} -->'
```

A non-zero exit code (the expected case) means the file is unstamped —
compute `$WORKFLOW_SHA` via the `§1.6(b)` paired idiom and prepend
`<!-- workflow-sha: $WORKFLOW_SHA -->` (followed by a newline) above the
H1 in `design-mechanics.md`, then re-read the file to satisfy the next
`Edit` precondition:

```bash
WORKFLOW_SHA="$(git log -1 --format=%H HEAD -- .claude/workflow .claude/skills .claude/agents)"
[ -z "$WORKFLOW_SHA" ] && WORKFLOW_SHA="$(git rev-parse HEAD)"
```

The `$WORKFLOW_SHA` value is computed at trigger time, not at the
original `phase1-creation` moment, so the new `design-mechanics.md`'s
stamp can differ from its `design.md` sibling's stamp by however many
workflow-format commits landed between the two creation events. The
asymmetry is expected — the no-drift normalization in the drift gate
collapses the divergence on the next clean gate run, and the
per-branch migration reunifies the stamps when it next runs end-to-end.
`design.md` already carries a stamp from its earlier creation; leave
that stamp byte-for-byte intact (the move of mechanism content from
`design.md` is a `content-edit`-shaped mutation against line 1's
position-preservation contract from `§1.6(a)`). The intra-invocation
SHA reuse rule from the `phase1-creation` paragraph does not apply
here: only `design_mechanics_path` is stamped, and `design.md`'s
line-1 stamp is preserved byte-for-byte under `§1.6(a)`.

For `design-sync`: see Step 1.5 below — sync has a distillation sub-step
before the apply.

Do not retry the apply — if `Edit` fails because `old_string` is not unique
or doesn't match, surface that to the user and stop. The mutation action
does not paper over a malformed edit.

### Step 1.5: Distillation (only for `design-sync`)
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="For design-sync only, re-distill the polished design from the current mechanics companion before the cold read." -->

Sync re-distills `design.md` from the current state of
`design-mechanics.md`. The agent does the distillation:

1. Read the most recent `design-sync` entry in
   `<plan-dir>/design-mutations.md` to find the last sync point.
2. Walk every `mechanics-edit` entry after that point — each entry's "Diff
   summary" tells you what changed in mechanics.
3. For each section in mechanics whose content moved since the last sync,
   update the corresponding section in `design.md`:
   - **TL;DR**: re-write to reflect the current mechanism.
   - **Mechanism overview**: update the prose to match the new mechanics.
   - **Edge cases / Gotchas**: add/remove/edit bullets to mirror mechanics.
   - **References footer**: ensure the `Mechanics:` link still resolves
     and any new D/S codes are listed.
4. For sections **added** in mechanics: create a corresponding section in
   `design.md` following the per-section mandatory shape.
5. For sections **removed** in mechanics: remove from `design.md` (or, if
   the section was renamed, propagate the rename and update the
   `Mechanics:` link).
6. **Update plan / track-file `**Full design**` refs** for any section
   that was added/removed/renamed in this sync — the plan-file checklist
   entries' Decision Records and every track file's `## Decision Log`
   may carry references to the affected section.

Apply the distilled `design.md` to disk via `Edit`/`Write`.

### Step 2: Determine cold-read scope
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Pick the cold-read scope (bounded or whole-doc) for this mutation kind from the check-set table." -->

Per the table above. For `mechanics-edit`, scope is `none` (cold-read is
skipped) — proceed straight to Step 3 mechanical checks.

For other mutations: track a mutation counter from the review log. Count
all design-touching entries (everything except `mechanics-edit`) since the
log was created. If `count % 5 == 0` (i.e., this is the 5th, 10th, 15th
mutation), escalate the cold-read scope to `whole-doc`.

### Step 3: Run mechanical checks
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Run the mutation kind's mechanical checks (link resolution, stamp position, section presence) before the cold read." -->

```bash
python3 .claude/scripts/design-mechanical-checks.py \
    --design-path <design_path> \
    --design-mechanics-path <design_mechanics_path or omit> \
    --plan-path <plan_path or omit> \
    --plan-dir <plan_dir or omit> \
    --changed-section "<title>" \
    --target <design|mechanics|both> \
    --scope <bounded|whole-doc>
```

Two flags need derivation:

- **`--target`** comes from the cold-read scope table above (column 3).
  When the table writes `design \| both`, resolve to `design` if the
  mutation only edits `design.md` (no `design-mechanics.md` companion
  exists, or the rename / rewrite did not propagate into mechanics)
  and to `both` if both files are touched in this mutation.
- **`--scope`** is the **mechanical-check scope** — orthogonal to the
  cold-read scope conveyed to the sub-agent. Pass `--scope=bounded`
  (and supply `--changed-section`) when column 4's cold-read scope
  starts with `bounded`; the script then runs the per-section shape
  check only on `<changed-section>` instead of every section. Other
  checks (per-section length cap, parenthetical asides, top-level cap,
  mechanics-link resolution, full-design-link resolution, reverse-
  direction refs) always run whole-doc regardless of `--scope`.
  Pass `--scope=whole-doc` for any kind whose cold-read scope is
  `whole-doc` (or for `mechanics-edit`, where there is no cold-read
  but the script still runs in whole-doc mode for the parenthetical-
  aside scan over the mechanics file). The cold-read scope itself is
  passed through the sub-agent prompt's `Inputs` block, not via this
  CLI flag.

For `mechanics-edit`, `--design-path` is still required even though
the `design.md` file is not touched by this mutation kind — it is the
reference for cross-file ref checks and reverse-direction-ref
detection. Treat `design.md` as read-only inputs to the script for
this kind.

The script prints JSON to stdout. Exit code `0` ⇒ no blockers; `1` ⇒ NEEDS
REVISION. Capture and parse the JSON; do not act on the exit code alone —
the findings list is what drives iteration.

### Step 4: Run the review sub-agents
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Spawn the review roles: the per-round pair plus the S3-gated gate for creation kinds, else one comprehension read." -->

Step 4 owns the review-spawn contracts; Step 6 owns the round-by-round
sequencing that calls them. The review surface has two shapes:

- **Creation kinds (`phase1-creation`, `phase4-creation`) run three review
  roles.** Each round of the inner loop (Step 6) spawns the cold
  **readability-auditor** plus the round's **second check**; after the inner
  loop converges to dual-clean, Step 4 spawns the cold **comprehension-review**
  gate once. The second check is the warm **absorption-check** for
  `phase1-creation` and the **fidelity check** for `phase4-creation` (the
  fidelity-check role and its `phase4-creation` wiring are built in the Phase 4
  track; this skill calls the same auditor and comprehension gate on both kinds
  and swaps only the second check).
- **Interactive kinds run the cold `comprehension-review` gate** — no author
  spawn (Step 1 edited inline), no inner loop, no second check. For every
  interactive kind except `design-sync` the comprehension gate is the only
  review role; the lighter single-pass cold-read is unchanged in substance,
  only re-pointed onto the `comprehension-review` agent definition (its
  `Read`,`Grep` allow-list is the D13/D14 tool-surface cut).
- **`design-sync` also spawns a `readability-auditor` prose pass.** `design-sync`
  re-distills the human-facing `design.md` from the mechanics companion, so its
  freshly rewritten prose is judged on the prose AI-tell axis like any other
  human-facing surface. The de-warmed comprehension gate runs that axis nowhere
  (D9), so leaving `design-sync` on the comprehension gate alone would strand
  the prose axis on neither reviewer — the "never neither" case S4 forbids.
  `design-sync` therefore stays an interactive kind (no author spawn, no inner
  loop, no absorption check) but gains one cold `readability-auditor` spawn
  alongside its `comprehension-review` gate, so the auditor is the single prose
  owner on `design-sync` (S4 holds), matching what `design-document-rules.md`
  and `prompts/design-review.md` assert. The auditor's findings merge into the
  same single-agent fix loop (Step 6) as the comprehension-gate findings.

**Skip review entirely for `mechanics-edit`.** Mechanics is agent-targeted
long-form content, not the human-facing summary; comprehension is not the
discipline that protects it. The next `design-sync` will cold-read against the
re-distilled `design.md`.

**Skip the review when mechanical checks have any `blocker` finding.** No point
asking a sub-agent to assess comprehension if the structure is broken — iterate
on mechanical first, then review once the doc is structurally sound. On the
creation kinds this skip applies to the per-round pair as well: a round whose
just-written draft fails a mechanical blocker iterates mechanical-first before
the auditor and second check run.

**The per-round pair spawns are wired in Step 6** (the inner loop), because they
re-spawn every round with that round's slice ranges and flagged-passage lists.
This step defines their spawn contracts (the agent types, the params-file
contract, the cache warm-up) so Step 6 can call them; the comprehension gate
below is the one role Step 4 spawns directly, after Step 6 reports dual-clean.

#### Spawn contract shared by all review roles (D13/D14 cost levers)

Every review role is an agent definition with a minimal `tools:` allow-list, not
a `general-purpose` spawn — the per-spawn tool surface is the largest fixed cost,
so cutting it is the first cost lever (D13). Spawn each via the `Agent` tool with
`subagent_type` set to the agent's basename:

| Role | `subagent_type` | Allow-list | Reads the log? |
|---|---|---|---|
| code-grounded author | `design-author` | `Read`,`Write`,`Edit`,`Bash`,PSI | yes (the sanctioned authoring read, S2) |
| cold readability auditor | `readability-auditor` | `Read`,`Grep` | **never** (S1) |
| warm absorption check (`phase1-creation`) | `absorption-check` | `Read`,`Grep` | yes (the sanctioned absorption read, S2) |
| cold comprehension gate | `comprehension-review` | `Read`,`Grep` | **never** |

**Per-agent parameters go in a params file, not the spawn prompt (D13).** The
spawn prompt body stays **byte-identical** across the fan-out so the shared
prompt body (including the injected `CLAUDE.md`, which cannot be skipped
per-agent, D14) caches. Write each round's per-agent inputs — the auditor's
slice `range`, each role's `target` / `target_path` / `output_path`, the
author's `target` / `research_log_path` / `output_path` / `design_path`
(track/full seed) / `round` / `flagged_passages` (its full set matches
`design-author.md § Inputs` key-for-key, so the round-1 author has the
`research_log_path` it grounds from), the absorption check's
`research_log_path` and `draft_path` — to a params file under
`_workflow/plan/` (one file per spawn), and pass only that file's path in the
spawn prompt. Each agent reads its params file as its first action (its agent
definition mandates this). A varying spawn-prompt tail would bust the whole
shared body, so never inline a per-agent value into the prompt.

**Fan-out cache warm-up (D13, the tunable cost lever).** When a round fans out
more than one auditor slice, sequence the fan-out instead of racing it: spawn one
auditor, wait a short fixed delay (the warm-up delay, default about a minute) for
its cold prefix write to land and propagate, then spawn the rest concurrently
against the now-warm prefix. This takes the fan-out from N cold prefixes to
roughly one cold plus the rest at a fraction. Do **not** block until the first
agent finishes — the cache TTL could age the prefix out and serializing loses the
parallelism; wait only the fixed warm-up delay. **The wait mechanism is an
implementation choice deferred to wiring (gate A7): use whatever non-blocking
fixed delay the harness offers between the first spawn and the rest; if no such
delay mechanism is available, disable the warm-up and pay N cold prefixes.** The
warm-up is a **cost lever, not a correctness dependency**: its delay is a tunable
with a measured fallback (a
heavy author's long first turn can push its cold write past the default delay, so
the delay may need to be role-specific), and the loop must produce correct
dual-clean output with the warm-up disabled (the disabled path just pays N cold
prefixes). Confirm the byte-identical-prompt assumption against the live
`Agent`-tool prompt assembly when wiring this; the assumption is what makes the
shared body cache, and it is the lever's only correctness-adjacent precondition.

#### The S3 freeze-order gate (creation kinds, before the comprehension gate)

**Block the comprehension gate while a log-adversarial entry is open (the S3
freeze-order gate).** Under D6 the decision/assumption challenge runs as a gate
on the research log at the Phase 0 → 1 boundary, not as a local adversarial pass
here. So for `phase1-creation` — after the inner loop reports dual-clean and
before spawning the comprehension gate — read the research log's
`## Adversarial gate record` section (the gate's durable verdict carrier; the
heading shape and the open/resolved and latest-dated-entry rules are defined once
in `research.md` §The research log under Gate-record cadence). The gate's own
review files under `_workflow/reviews/` are ephemeral and not the carrier. The
gate's verdict is encoded in the section's headings: when the gate has looped,
**match the latest dated heading**, and a `NEEDS REVISION` heading with any open
blocker or should-fix is an **open** entry. The comprehension gate **must not run
while the latest log-adversarial entry is open**: that is the S3 invariant — a
`design.md` draft cannot reach the comprehension gate while a log-adversarial
entry is open, so a load-bearing decision surfaced while authoring the design
(whether the author appended it to the log or the absorption check surfaced a
draft-invents-decision finding) is re-challenged at the gate and cleared before
the comprehension gate assesses it. When the gate is clear (every log-adversarial
entry resolved), proceed to the comprehension gate; the comprehension pass then
assesses a design whose decisions have already survived challenge. The de-warmed
comprehension reviewer reads no log itself, so the gate guards it on the loop's
behalf, not for the reviewer's own sake — the author and the absorption check are
the log readers the gate protects.

**The S3 gate holds across the D15 batch loop-back.** Once `design.md` is
presented for review, post-presentation findings queue and batch through the
D15 review-iteration loop (`create-plan/SKILL.md` § Step 4 review-hold
batching). A decision-shaped finding (a comprehension-gate finding, or an
absorption-surfaced draft-invents-decision) re-enters the gate step — it is
appended to the log and re-challenged — so the gate re-opens and the
comprehension gate does not re-run until the log entry is resolved again. There
is no path where the comprehension gate runs with an open log entry, on the
first pass or on any batch loop-back iteration.

#### Spawning the comprehension gate

For the creation kinds, spawn the comprehension gate only after Step 6 reports
the inner loop dual-clean and (for `phase1-creation`) the S3 gate is clear. For
the interactive kinds, the comprehension read is the cold-read pass (the only
one, except on `design-sync`, which also runs the `readability-auditor` prose
pass below) — there is no inner loop and no gate; spawn it once mechanical has
zero blockers. Either way, spawn the `comprehension-review` agent via the
`Agent` tool:

- `subagent_type`: `comprehension-review`
- `description`: `"Cold comprehension gate (<mutation_kind>)"`
- `prompt`: a byte-identical body that names the params file (per the shared
  spawn contract above). The params file carries the `## Inputs` block the
  `comprehension-review` agent forwards to `prompts/design-review.md`:

```
- design_path: <abs path>
- design_mechanics_path: <abs path or "(none)">
- scope: <bounded|whole-doc>
- bounded_scope: <changed_section name + surrounding section names, when bounded>
- mutation_kind: <kind>
- plan_path: <abs path or "(none)">
- plan_dir: <abs path or "(none)">
```

**No `research_log_path` is ever passed to the comprehension gate.** The
absorption-completeness cross-check moved off this role onto the per-round
`absorption-check` spawn (below), so the comprehension gate's params file names
no log path; the `comprehension-review` agent reads no log regardless (S1/S2). If
a log path is wired into its params file, that is a wiring error.

**Inject `output_path` only for `phase4-creation`.** When
`mutation_kind == phase4-creation`, the comprehension gate's params file carries
one more line so the Phase 4 cold-read persists its output to a file and returns
a summary (`prompts/design-review.md` § Output format, the path-conditional
branch; the review-file coverage rule in `conventions-execution.md` `§2.5`):

```
- output_path: <abs path under _workflow/plan/ for the cold-read output>
```

For every other kind — including `phase1-creation` — omit the `output_path` line.
The comprehension gate's no-path branch then returns its verdict inline.

For `design-sync`, also include in the params file body: *"This sync re-distills
`design.md` from the current state of `design-mechanics.md`. Verify that every
TL;DR and mechanism overview in `design.md` accurately summarizes the current
mechanics file's content for the same-named section."*

**Also spawn the `readability-auditor` for `design-sync` (the one prose owner,
S4).** `design-sync` is the only interactive kind whose review surface adds a
prose pass, because it rewrites the human-facing `design.md` prose. Spawn the
cold `readability-auditor` over the re-distilled `design.md` so the prose
AI-tell axis lands on exactly one reviewer (the auditor) and is never stranded
on the de-warmed comprehension gate that runs it nowhere (D9/S4). This is a
single cold prose pass, not the creation-kind inner loop: no author spawn, no
absorption check, no per-round re-grounding, no cache warm-up (one auditor over
the whole document). Spawn it via the `Agent` tool with `subagent_type:
readability-auditor` and `description: "Readability audit (design-sync)"`,
following the same params-file-plus-byte-identical-prompt contract. The
auditor's params file carries `target=design`, `target_path=<design_path>`, and
a whole-document `range` (the sync re-distills the whole `design.md`, so it is
not range-sliced like a creation-kind fan-out); it names **no**
`research_log_path` (S1 — the auditor never reads the log). The auditor's
findings merge into the same single-agent fix loop (Step 6) as the
comprehension-gate findings; the loop's inline fixes re-distill the flagged
prose, and the loop re-runs the auditor on the next iteration like any other
finding source.

The comprehension gate returns a structured Markdown verdict per the prompt's
output format, in one of two shapes split by whether `output_path` was injected
(`prompts/design-review.md` § Output format, the path-conditional branch):

- **`output_path` absent** (the default — every kind except `phase4-creation`):
  the sub-agent returns the full Markdown inline. Parse the **Verdict** line
  (`PASS` or `NEEDS REVISION`) and the inline **Structural findings** list.
- **`output_path` supplied** (`phase4-creation`): the sub-agent returns only
  a summary (the **Verdict** line plus the blocker/should-fix counts) and
  writes the `## Structural findings` detail to the file at `output_path`.
  Parse the **Verdict** and counts from the return, then partial-fetch the
  written file's `## Structural findings` section for the finding detail that
  Step 5 merges.

#### Spawning the per-round auditor and absorption check (creation kinds)

Step 6 spawns these every round of the inner loop; their contracts live here. Both
follow the shared params-file-plus-byte-identical-prompt contract above.

**The readability auditor** (`subagent_type: readability-auditor`,
`description: "Readability audit (<mutation_kind> round <N>)"`) is range-sliced:
each slice gets its own spawn whose params file carries `target=design`,
`target_path=<design_path>`, and the slice `range`. The auditor reads only
`house-style.md`, its slice, and the standing anchors (the `## Overview` and
`## Core Concepts` of `design.md`); its params file names **no** research-log path
(S1). It returns the enumerated readability findings for its slice.

**The absorption check** (`phase1-creation` only —
`subagent_type: absorption-check`,
`description: "Absorption check (phase1-creation round <N>)"`) is the role that
carries the `research_log_path` (the injection that pre-de-warm lived on the
comprehension cold-read). Its params file carries `target=design`,
`research_log_path=<abs path to _workflow/research-log.md>`, and
`draft_path=<design_path>`. It does two-way coverage matching: every load-bearing
research-log decision (each `## Decision Log` entry whose
`**Alternatives rejected:**` field names a real fork) appears as a seed D-record
in `design.md`, and no seed D-record invents a decision the log lacks. A missing
seed D-record is a finding the Step 6 inner loop must resolve before dual-clean; a
draft-invents-decision that is load-bearing re-opens the S3 gate (above) exactly
as a decision-shaped comprehension finding does. For `phase4-creation` the second
check is the fidelity check instead (built in the Phase 4 track), which grounds on
the episodes and the code, not the log; no `research_log_path` is passed on the
Phase 4 path.

Map every review role's findings into the same severity schema as mechanical
findings.

### Step 5: Merge findings
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Merge the mechanical-check and review findings into one deduplicated list for the iterate step." -->

Combine mechanical + review findings into a single list. Which review findings
enter the merge depends on the shape:

- **Creation kinds, per round:** the readability-auditor findings (one set per
  slice) plus the round's second-check findings (absorption at `phase1-creation`,
  fidelity at `phase4-creation`). These are the findings Step 6's inner loop
  iterates on.
- **Creation kinds, post-loop:** the comprehension-gate findings, whichever
  source Step 4 produced — the inline list for the no-path case, or the
  `## Structural findings` partial-fetched from the written file for the
  `phase4-creation` file-write path.
- **Interactive kinds:** the single comprehension-gate cold-read's findings.

Sort by severity: `blocker` → `should-fix` → `suggestion`. Mechanical findings
carry a structured `rule` field; review findings are free-form bullets and won't
usually duplicate the mechanical set, but if a review bullet plainly restates a
mechanical finding (same severity, same location, same shape rule), drop the
duplicate. There is no in-skill adversarial finding source to merge: for
`phase1-creation` the decision/assumption challenge ran as the relocated
log-adversarial gate (D6), which Step 4's S3 gate confirmed clear before the
comprehension gate ran, so the challenge findings were already resolved on the
log and never enter this merge.

### Step 6: Iterate
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Run the dual-clean inner loop for creation kinds or the single-agent fix loop for interactive kinds, budget-bounded." -->

Step 6 has two shapes, matching Step 1 and Step 4. The **creation kinds**
(`phase1-creation`, `phase4-creation`) run the dual-clean inner loop; the
**interactive kinds** run the single-agent fix loop. Both are bounded by the
`iteration_budget` (default 3) and both exit to the user on budget exhaustion,
identical to today's behavior (S5). The comprehension gate (Step 4) is the outer
gate that runs **after** the creation-kind inner loop converges, not inside it.

#### The dual-clean inner loop (creation kinds)

Each round revises the draft, then runs the per-round pair; the round passes only
when **both** the readability auditor and the round's second check are clean (the
dual-clean exit, S5). Run each round in this order:

1. **Re-spawn the author to revise the draft (the ground-once lever, D13).**
   - **Round 1:** spawn the author (Step 1 settled the companion-file and
     seed-scope decision) with the round-1 params file carrying `round=1`. The
     author grounds the whole document — reads the research log / seed and the
     code, then writes every section to `output_path`. It returns a thin summary
     only, never the draft (the by-reference contract; the author's context
     isolation is what keeps the orchestrator's context bounded).
   - **Later rounds:** spawn the author with a params file carrying the round
     number and the `flagged_passages` list — the too-terse auditor findings that
     demand new prose. The author re-grounds **only** those passages (a density or
     word-choice finding needs no new code read; only the too-terse subset that
     wants a worked example triggers targeted re-grounding). Do not re-ground the
     whole document on a later round.
   - The author is the **only writer**. The auditor, absorption check, and
     comprehension gate are read-only and never edit the draft; all fixes route
     through an author re-spawn. (This is the structural difference from the
     interactive loop below, where the skill itself applies fixes inline.)

2. **Run mechanical checks** (Step 3) on the just-written draft. If any
   `blocker`, iterate mechanical-first (re-spawn the author with the mechanical
   findings as flagged passages) before spending the round's auditor and
   second-check spawns — Step 4's blocker-skip applies to the per-round pair.
   **A mechanical-first re-spawn consumes a round: decrement the iteration
   budget (step 5's counter) and loop back to step 1.** This re-spawn skips the
   pair (steps 3-4) but is not free — without the decrement an author that
   cannot clear a mechanical blocker (a stamp-position check it cannot satisfy,
   a link-resolution failure that is not the author's to fix) would re-spawn
   forever, since steps 3-5 are never reached. With the decrement the
   mechanical-first sub-loop is bounded by the same `iteration_budget` as the
   rest of Step 6: when the budget reaches zero on a still-blocking mechanical
   finding, exit to the user as a non-self-correcting mechanical failure rather
   than re-spawning.

3. **Spawn the per-round pair** per Step 4's contract: the readability-auditor
   fan-out (one spawn per slice, sequenced behind the cache warm-up) and the
   round's second check (the `absorption-check` for `phase1-creation`, the
   fidelity check for `phase4-creation`). Merge their findings (Step 5).

4. **Evaluate the dual-clean condition.** The round is **dual-clean** when the
   auditor returns no `blocker`/`should-fix` finding **and** the second check
   returns none. If either is unclean, the loop continues: the auditor's findings
   become the next round's `flagged_passages` and any absorption
   log-missing-from-draft finding becomes a decision the author must seed.

5. **Decrement the iteration budget.** Stop when the budget reaches zero or the
   round is dual-clean.

The two checks converge because they re-open the loop for disjoint reasons:
fixing a readability finding adds code-accurate prose (it never drops a decision),
and adding a dropped decision is new prose the next round's auditor polishes like
any other. Neither fix re-triggers the other in a cycle, so the loop moves
monotonically toward dual-clean — typically one or two rounds. The budget plus
escalation is the backstop for a pathological case, not the expected path.

After the inner loop reports dual-clean, run the **comprehension gate** (Step 4),
S3-gated for `phase1-creation`. A decision-shaped comprehension-gate finding
re-opens the log-adversarial gate and re-enters the inner loop (the author seeds
the surfaced decision; the absorption check confirms coverage; the auditor
re-checks the prose), so the comprehension gate re-runs only once the gate clears
again (Step 4's S3 batch-loop-back clause). The whole creation-kind review
therefore exits when the inner loop is dual-clean **and** the comprehension gate
passes, or the budget is spent.

#### The single-agent fix loop (interactive kinds)

For every interactive mutation kind, Step 6 is the single-agent fix loop, applied
to the merged mechanical + comprehension-gate findings (Step 5), plus the
`readability-auditor` prose findings on `design-sync` (the one prose owner, S4).
Each iteration runs in this order until the budget is exhausted or no findings
remain:

1. **Address blockers first.** For each `blocker` finding:
   - **`auto_applicable: true`**: the script has flagged this finding as
     mechanically resolvable from its `suggested_fix` text. The
     `auto_applicable` flag does **not** mean a literal regex
     replacement; the agent reads the `suggested_fix` and applies it
     via `Edit`, using the matched substring (e.g., the offending
     `(per D27)` aside) as the `old_string`. The current auto-
     applicable rule is `dsc-parenthetical-aside`.
   - **`auto_applicable: false` (or unset)**: read `suggested_fix` and
     the surrounding context; apply the fix via `Edit`.
2. **Then address `should-fix` findings** in the same iteration, using
   the same auto-vs-manual flow. `suggestion` findings are not retried —
   they are recorded in the review log only.
3. **Re-run mechanical checks; then, if mechanical now passes, re-run the
   comprehension-gate cold-read — and on `design-sync`, re-run the
   `readability-auditor` prose pass as well. Replace the prior findings list
   with the new one.** No interactive mutation kind runs an in-skill adversarial
   sub-agent or the S3 gate (those are `phase1-creation`-only). The loop's exit
   conditions below are unchanged.
4. **Decrement the iteration budget.** Stop when the budget reaches
   zero or all blocker + should-fix findings are gone.

#### Outcomes when either loop exits

- **All blockers and should-fix findings cleared** (and, for the creation kinds,
  the comprehension gate passed): PASS — proceed to Step 7.
- **Budget exhausted with blockers remaining**: the action does **not**
  succeed. Leave the partial edits on disk (do not revert), append a
  clear warning to the review log (Step 7 still runs), and present
  findings + diff to the user for manual resolution. The user is the
  gate when the action can't self-correct.
- **Budget exhausted with only `should-fix` findings remaining**: the
  action completes with a warning. Log the unresolved findings and
  proceed to Step 7. The mutation can stand; the residual findings
  carry forward to the next mutation as known debt.

### Step 7: Append to the review log
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Append the mutation's record to the design-mutations log, which is itself exempt from stamping." -->

Resolve the log path from `mutation_kind` and `design_path` using
the rule below. The log always lives under `_workflow/` so the
Phase 4 cleanup commit reliably removes it; never write the log to
the top-level `<dir>/`.

- **For all mutation kinds *except* `phase4-creation`**:
  `design_path = docs/adr/<dir>/_workflow/design.md` (or
  `design-mechanics.md`), so the plan dir is `design_path`'s parent
  (`docs/adr/<dir>/_workflow/`) and the log lives at
  `docs/adr/<dir>/_workflow/design-mutations.md`.
- **For `phase4-creation`** (special case): `design_path =
  docs/adr/<dir>/design-final.md` (top-level, intentionally
  outside `_workflow/` because `design-final.md` itself is a
  durable artifact). The log path is **not** derived from
  `design_path`'s parent — instead, it is forced to
  `<design_path's parent>/_workflow/design-mutations.md`,
  i.e., `docs/adr/<dir>/_workflow/design-mutations.md`.
  This appends to the existing Phase 1 / inline-replanning log
  under `_workflow/`, preserving the full mutation history of the
  design and ensuring the Phase 4 cleanup commit removes the
  entire log along with everything else under `_workflow/`.

**`design-mutations.md` is not stamped.** Unlike `design.md` and
`design-mechanics.md`, the review log carries no line-1
`workflow-sha` comment — neither at creation nor on later appends.
The log is append-only by contract: a workflow-format commit that
rewrites entries on disk would violate the contract, so the log
is replay-immune by construction and a stamp would be dead weight
on its surface. `conventions.md` `§1.6(f)` lists the file as an
explicit exclusion alongside the Phase 4 final artifacts; the
drift check and the migration both scope to the stamped set in
`§1.6(f)` and skip this file by enumeration. Do not add a stamp
here out of mistaken uniformity with the design files.

Append to the resolved path (create the `_workflow/` directory and
file if they don't exist). Format per
`design-document-rules.md § Mutation discipline § Review log`:

```markdown
## Mutation N — <ISO date YYYY-MM-DD> — <mutation kind> (<design.md | design-final.md>)

**Diff summary**: <one paragraph describing the change>

**Mechanical checks** (target=<design|mechanics|both>): <PASS / N findings>
**Cold-read** (scope: <bounded|whole-doc|skipped>): <PASS / N findings / SKIPPED — mechanics-edit defers cold-read to next design-sync>

**Findings**:
- <severity>: <description>

**Iterations**: <i> of <budget> (PASS | BLOCKER REMAINS | SHOULD-FIX REMAINS)

**Working-mode counter**: <K mechanics-edits since last design-sync> (only for `mechanics-edit` and `design-sync` entries)
```

The header includes the target file's basename (`design.md` for normal
mutations, `design-final.md` for `phase4-creation`) so the log is
unambiguous when an entry follows a Phase 4 entry — both `phase1-creation`
and `phase4-creation` look structurally similar otherwise.

Use `Read` to find the highest existing mutation number and increment by
one. The first mutation is `## Mutation 1 — ...`.

### Step 8: Auto-suggest sync at N=5 (working mode only)
<!-- roles=orchestrator,planner,final-designer phases=1 summary="In working mode, suggest a design-sync once five mechanics edits have accumulated since the last sync." -->

After a `mechanics-edit` mutation completes, count `mechanics-edit` entries
in the review log since the most recent `design-sync` (or since
`phase1-creation` if no sync has happened yet). If `count >= 5`:

> Surface to the user at the next conversational turn: *"5 mechanics edits
> have accumulated since the last `design.md` sync. The polished view in
> `design.md` is N edits behind. Want me to run a `design-sync` now, or keep
> iterating?"*

Do not auto-trigger the sync. The user is the gate — they may want to
iterate further before publishing. The suggestion fires once per turn until
either (a) the user says yes (run sync), (b) the user says no/defer (skip
the prompt for this turn; it'll fire again next turn), or (c) a sync runs
(counter resets to 0).

The user can also explicitly request a sync at any count: "let's update
`design.md`", "run design-sync", "publish the polished version" — any
phrasing that conveys intent. Treat the request as authorization to run a
`design-sync` mutation.

### Step 9: Present to the user
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Present the merged result and surviving findings to the user as the mutation's final output." -->

Show:

1. The diff applied (`git diff <design_path>` or a manual summary if not
   in a git repo).
2. The review-log entry just written.
3. A one-line outcome: `PASS`, `BLOCKER REMAINS — manual resolution
   required`, or `COMPLETE WITH SHOULD-FIX FINDINGS`.

For `design-sync`: also surface a "what changed in mechanics since the
last sync" summary — a bulleted list of the section-level changes the
distillation incorporated, so the user can verify the new polished view
matches their mental model of the iteration.

The action is then complete. The agent returns control to the user / parent
flow.

## Staleness reconciliation
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="The prompt shown when a request references a polished design that mechanics edits have since outpaced." -->

The full Phase 1 lifecycle (sub-phases, sync triggers, working-mode
counter) lives in design-document-rules.md:planner,final-designer:1,4 `§ Two-mode editing — working vs sync`. The one operational protocol anchored here — because that doc
cross-refs to it — is the staleness-reconciliation prompt.

During Phase 1.2 (`mechanics-edit` rounds), `design.md` is **frozen**
relative to mechanics. The user reads `design.md` to review and issues
feedback against it. If the user's request references a `design.md`
statement that mechanics has already moved past, the agent reconciles
explicitly:

> *"Your request references `design.md` saying X. Mechanics has accumulated
> N edits since the last sync, and X has been updated to Y. Should I
> (a) revert mechanics to X then apply your new request, (b) apply your
> request on top of the current state Y, or (c) sync `design.md` first so
> you can see Y, then issue the request?"*

The user picks. Default to (b) when the user's intent is clear and the
delta between X and Y is incidental; default to (c) when the delta changes
the meaning of the request.

## Tools used
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="The tools the skill invokes: the mechanical-check script, Edit/Write, and the author and review-role spawns." -->

- `Read` — verify file state, read review log for mutation count and last
  sync point, read the research log's `## Adversarial gate record` for the S3
  gate.
- `Edit` / `Write` — apply interactive-kind edits, distill `design.md` during
  sync, apply any auto-fixes, write the per-spawn params files. (On the creation
  kinds the document content is written by the author spawn, not inline.)
- `Bash` — run the mechanical-checks script; take the fan-out cache warm-up
  delay if the harness offers a non-blocking fixed-delay mechanism (the warm-up
  is disabled otherwise, see the fan-out warm-up paragraph).
- `Agent` — spawn the review roles. On the interactive kinds, one
  `comprehension-review` spawn (skipped for `mechanics-edit`), plus one cold
  `readability-auditor` prose pass on `design-sync` (the one prose owner, S4).
  On the creation kinds, the `design-author` (per round), the
  `readability-auditor` fan-out (per round, per slice), the round's second check
  (`absorption-check` at `phase1-creation`), and the post-loop
  `comprehension-review` gate.
- `Edit` (append-mode via full-content read) — write the review-log entry.

## When NOT to use this skill
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="The cases that bypass the mutation discipline: non-design files and pure workflow-artifact edits." -->

- Edits to `implementation-plan.md`, the per-track track files under
  `plan/`, or any other workflow file. Those have their own gates
  (Phase 2 structural review). The `**Full design**` ref-propagation
  that lands in the plan and the track files during a `section-rename`
  or `design-sync` is part of this skill's scope, but isolated plan-only
  or track-file-only edits are not.
- Edits to source code, tests, or docs outside `docs/adr/<plan-dir>/`.
- Pre-Phase-1 scratch notes that haven't been promoted to `design.md`
  yet — only the canonical paths above trigger the discipline.

## Failure modes and recovery
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="How the skill recovers when a check fails, the cold read stalls, or the iteration budget is exhausted." -->

- **Script not found** at `.claude/scripts/design-mechanical-checks.py`:
  the project may not have the discipline wired up. Stop and ask the
  user; do not silently fall back to direct `Edit`.
- **A review sub-agent times out or returns malformed output**: re-run that
  role once (the comprehension gate, an auditor slice, or the second check). If
  it fails again, treat that review half as `INCONCLUSIVE`, log the result, and
  continue with the findings you do have. Add a `should-fix` line to the review
  log noting which role failed. On the creation kinds, an `INCONCLUSIVE` auditor
  slice or second check means the round cannot be declared dual-clean — do not
  advance past the inner loop on inconclusive evidence; let the budget govern.
- **The author spawn returns the draft inline instead of a thin summary** (a
  by-reference contract violation): the orchestrator's context is at risk, but
  the draft is on disk at `output_path`, so do not re-spawn. Proceed with the
  written draft and note the contract violation in the review log.
- **Budget exhausted**: do not loop further. The user is the gate when
  the action can't self-correct.
- **Sync distillation produces an empty diff** (mechanics has no changes
  since last sync): no-op the sync, log a one-line entry noting the
  zero-delta sync, and skip mechanical / cold-read for this round.

## Examples
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="Worked examples of a content edit and a section rename run through the full mutation discipline." -->

Two intricate interactive-kind cases worth showing concretely. The simpler
interactive kinds (`mechanics-edit`, `content-edit`) follow the Workflow steps
directly with no special handling. The creation kinds (`phase1-creation`,
`phase4-creation`) run the dual-clean multi-agent loop described in Steps 1, 4,
and 6 — the author spawn, the per-round auditor/second-check pair, then the
S3-gated comprehension gate — rather than the single-agent path these two
examples show.

**Example 1 — Sync (`design-sync`).**
After 5 mechanics-edits accumulate, the user says "OK, update
`design.md`". The skill:

1. Reads the review log, identifies all `mechanics-edit` entries since
   the most recent `design-sync` (or since `phase1-creation` if no sync
   has happened yet).
2. Reads `design-mechanics.md` to see the current state.
3. Distills `design.md` — updates each affected section's TL;DR +
   overview + edge cases + references to match current mechanics.
4. Updates plan / track-file `**Full design**` refs for any renamed /
   added / removed sections.
5. Runs mechanical checks with `--target=both`.
6. Spawns cold-read with `whole-doc` scope, including the sync-specific
   "verify `design.md` reflects current mechanics" instruction.
7. Iterates as needed.
8. Appends `Mutation N — ... — design-sync` to the review log; the
   working-mode counter resets to 0.
9. Presents the user a "what changed in mechanics since last sync"
   summary alongside the diff and log entry.

**Example 2 — Section rename (`section-rename`).**
The user asks to rename `## DPB (D33)` to `## Architectural redesign: Dirty Page Bitset (D33)`. This design has a `design-mechanics.md`
companion, so the rename has to propagate. The skill:

1. Applies the rename `Edit` in `design.md`.
2. Updates the matching section name in `design-mechanics.md` — per
   the "section names match" rule in
   `design-document-rules.md` § Length-triggered split into
   `design-mechanics.md`.
3. Updates every `**Full design**: design.md §"DPB (D33)"` line in
   `implementation-plan.md` and in every `plan/track-N.md` track file
   to use the new name.
4. Runs mechanical checks with `--target=both`, `--scope=whole-doc` —
   confirms zero broken refs. (`both` because mechanics was touched;
   the cold-read scope table resolves `design \| both` to `both` for
   this case.)
5. Spawns cold-read with `whole-doc` scope.
6. Logs and presents.

If the design has **no** `design-mechanics.md` companion, step 2 is
skipped and step 4 runs with `--target=design`. Step 3 still runs —
plan / track-file ref propagation is independent of whether mechanics
exists.

## Reference
<!-- roles=orchestrator,planner,final-designer phases=1,4 summary="On-demand pointers to the design-document rules, the file layout, and the mutation-kind definitions." -->

- Rules: `.claude/workflow/design-document-rules.md` § Mutation discipline
  and § Two-mode editing — working vs sync
- Comprehension-gate prompt: `.claude/workflow/prompts/design-review.md`
- Review-role agent definitions: `.claude/agents/design-author.md`,
  `.claude/agents/readability-auditor.md`, `.claude/agents/absorption-check.md`,
  `.claude/agents/comprehension-review.md`
- File layout: `.claude/workflow/conventions.md` `§1.2`
- Mechanical script: `.claude/scripts/design-mechanical-checks.py`
