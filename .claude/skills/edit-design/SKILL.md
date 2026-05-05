---
name: edit-design
description: "Apply an edit to design.md or design-mechanics.md through the mutation discipline: apply → auto-review → iterate → present. Use this instead of directly Editing those files."
argument-hint: "<plan-dir-path> [<mutation-kind>]"
user-invocable: false
---

Apply an edit to `design.md` (or `design-mechanics.md`) through the **mutation
discipline** defined in `.claude/workflow/design-document-rules.md`. The skill
bundles `(apply edit → auto-review → bounded iterate → present)` into one
atomic action so the structural rules are self-enforcing.

**You MUST use this skill — not raw `Edit`/`Write` — for every modification to
`design.md` / `design-mechanics.md` and for every Phase 4 creation of
`design-final.md` / `design-mechanics-final.md`.** That includes initial
creation in Phase 1 (`phase1-creation`), interactive iteration ("add a
section about X"), inline replanning during Phase 3 ESCALATE, and Phase 4
production of the final committed artifacts (`phase4-creation`).

## Two operational modes

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
targeting the `*-final.md` paths and skipping plan/backlog ref
propagation (those refs point at the original `design.md`, not at the
new final artifact). No follow-up `mechanics-edit` / `design-sync` cycle:
Phase 4 is committed once.

Full rationale, sub-phase diagram, and sync-trigger rules live in
`design-document-rules.md § Two-mode editing — working vs sync`.

## Skill inputs

The invoking agent supplies these when calling the skill:

| Input | What it carries |
|---|---|
| `design_path` | Absolute path to `design.md` (or `design-final.md` in Phase 4). |
| `design_mechanics_path` | Absolute path to `design-mechanics.md` (or `null` if no companion). |
| `plan_path` | Absolute path to `implementation-plan.md` (for `**Full design**` link resolution). |
| `backlog_path` | Absolute path to `implementation-backlog.md` (same purpose). |
| `target` | `design`, `mechanics`, or `both` — the file(s) the edit touches. Threaded through to the script's `--target` flag verbatim. (No `.md` suffix — the script's argparse choices are `design`/`mechanics`/`both`.) |
| `intended_edit` | Either `(old_string, new_string)` for a focused edit, or full new content for a section-add / section-rewrite / file creation. |
| `mutation_kind` | One of the values listed in the mode table above. |
| `changed_section` | Title of the section being changed (for bounded cold-read scope). For `section-rename`, supply the **new** name. Optional for `mechanics-edit` and `design-sync`. |
| `iteration_budget` | Default `3` — max number of (apply → review) rounds. |

If any required input is missing, **ask the user before proceeding.** The
mutation discipline depends on the agent stating the mutation kind explicitly
so the cold-read scope and check-set are correct; do not guess.

## Cold-read scope and check-set by mutation kind

The `--target` column reads as a function of whether
`design-mechanics.md` exists at the time of the mutation. When a value
is written `design \| both`, resolve to `design` if the mutation
touches only `design.md` (the common case for small designs without a
mechanics companion) or `both` if the mutation also propagates into
`design-mechanics.md`.

| Mutation kind | Touches | Mechanical script `--target` | Cold-read scope |
|---|---|---|---|
| `phase1-creation` | `design.md` only when the design will not need a mechanics companion (small designs under ~5 sections), or both files when the design will exceed the length trigger / already plans for mechanics | `design \| both` | `whole-doc` on design.md (mechanics is exempt from cold-read since it's agent-targeted) |
| `mechanics-edit` | mechanics only | `mechanics` | **NONE** — cold-read deferred to next `design-sync` |
| `design-sync` | both files (re-distill design.md from updated mechanics) | `both` | `whole-doc` on design.md, plus mechanics-link-resolution sweep |
| `content-edit` | design.md | `design` | `bounded` — changed section + 1-2 surrounding sections + Overview + (when present) Core Concepts |
| `section-add` | design.md | `design` | `bounded` — new section + Overview + (when present) Core Concepts + structure roadmap |
| `section-remove` | design.md (+ plan/backlog ref cleanup — `**Full design**` lines pointing at the removed section must be updated in the same mutation, otherwise `**Full design**` link resolution fails) | `design` | `whole-doc` |
| `section-rename` | design.md + (when mechanics exists) the matching section in `design-mechanics.md` + plan/backlog ref propagation | `design \| both` | `whole-doc` |
| `section-move` | design.md | `design` | `whole-doc` |
| `structural-rewrite` | design.md + (when mechanics exists and any rename or split propagates) the matching sections in `design-mechanics.md` | `design \| both` | `whole-doc` |
| `length-trigger-crossing` | both files (split into design-mechanics) | `both` | `whole-doc` |
| `phase4-creation` | `design-final.md` + (optional) `design-mechanics-final.md` | `both` if mechanics-final exists, else `design` | `whole-doc` on `design-final.md` (mechanics-final is exempt — agent-targeted long-form). Skip plan/backlog ref propagation: omit `--plan-path` / `--backlog-path` so the cross-file ref check is naturally skipped. |

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

See `design-document-rules.md § Mutation discipline § Cold-read scope by
mutation kind` for the canonical statement of both counters.

## Workflow

The high-level steps are the same across all mutation kinds; the differences
are in which checks fire and whether cold-read runs.

### Step 1: Apply the edit

Use the `Edit` tool (for focused edits) or `Write` (for full-file rewrites or
new section creation). Read the target file first to satisfy the `Edit`
precondition.

For `phase1-creation`: decide first whether the design needs a mechanics
companion. **Default is single file.** Most designs (under ~5 sections,
no `# Part N` headings, no anticipated long-form derivations) seed only
`design.md` — pass `target=design` and leave `design_mechanics_path=null`.
Seed `design.md` with Overview (concept-first elevator pitch), Core
Concepts (when the doc will have Parts or ≥3 new domain terms), Class
Design, Workflow, and TL;DR-shaped Part sections.

Seed both files only when the design genuinely needs the split — typically
when the user has signaled it up front ("this will have a mechanics
companion") or when a single-file seed would already exceed the
2,000-line / 50,000-token length trigger. In that case, pass
`target=both` and `design_mechanics_path=<abs path>`; seed
`design-mechanics.md` with the long-form mechanism content that supports
each design.md section, with section names matching between the two
files from the start. A design that doesn't need mechanics on day 1
crosses into one later via `length-trigger-crossing`, not by retroactively
re-running `phase1-creation`.

For `phase4-creation`: same as `phase1-creation` but the file paths are
`design-final.md` and (optional) `design-mechanics-final.md`, and the
content reflects what was *actually built* — not the planned design. The
caller (`prompts/create-final-design.md`) is expected to have run the
PSI-backed verification tables before invoking the skill, so each diagram
element traces to a real code location. Do **not** pass `--plan-path` /
`--backlog-path` (the cross-file ref check is naturally skipped — see the
table above).

For `design-sync`: see Step 1.5 below — sync has a distillation sub-step
before the apply.

Do not retry the apply — if `Edit` fails because `old_string` is not unique
or doesn't match, surface that to the user and stop. The mutation action
does not paper over a malformed edit.

### Step 1.5: Distillation (only for `design-sync`)

Sync re-distills `design.md` from the current state of
`design-mechanics.md`. The agent does the distillation:

1. Read the most recent `design-sync` entry in
   `<plan-dir>/reviews/design-mutations.md` to find the last sync point.
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
6. **Update plan/backlog `**Full design**` refs** for any section that was
   added/removed/renamed in this sync.

Apply the distilled `design.md` to disk via `Edit`/`Write`.

### Step 2: Determine cold-read scope

Per the table above. For `mechanics-edit`, scope is `none` (cold-read is
skipped) — proceed straight to Step 3 mechanical checks.

For other mutations: track a mutation counter from the review log. Count
all design-touching entries (everything except `mechanics-edit`) since the
log was created. If `count % 5 == 0` (i.e., this is the 5th, 10th, 15th
mutation), escalate the cold-read scope to `whole-doc`.

### Step 3: Run mechanical checks

```bash
python3 .claude/scripts/design-mechanical-checks.py \
    --design-path <design_path> \
    --design-mechanics-path <design_mechanics_path or omit> \
    --plan-path <plan_path or omit> \
    --backlog-path <backlog_path or omit> \
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
the design.md file is not touched by this mutation kind — it is the
reference for cross-file ref checks and reverse-direction-ref
detection. Treat `design.md` as read-only inputs to the script for
this kind.

The script prints JSON to stdout. Exit code `0` ⇒ no blockers; `1` ⇒ NEEDS
REVISION. Capture and parse the JSON; do not act on the exit code alone —
the findings list is what drives iteration.

### Step 4: Run the cold-read sub-agent

**Skip cold-read entirely for `mechanics-edit`.** Mechanics is agent-
targeted long-form content, not the human-facing summary; comprehension is
not the discipline that protects it. The next `design-sync` will run
cold-read against the re-distilled `design.md`.

**Skip cold-read when mechanical checks have any `blocker` finding.** No
point asking a sub-agent to assess comprehension if the structure is broken
— iterate on mechanical first, then cold-read once the doc is structurally
sound.

For all other kinds, when mechanical has zero blockers, spawn the cold-read
sub-agent via the `Agent` tool:

- `subagent_type`: `general-purpose`
- `description`: `"Cold-read design review (<mutation_kind>)"`
- `prompt`: the full content of `.claude/workflow/prompts/design-review.md`,
  with the `## Inputs` block at the top extended by literal substitutions:

```
- design_path: <abs path>
- design_mechanics_path: <abs path or "(none)">
- scope: <bounded|whole-doc>
- bounded_scope: <changed_section name + surrounding section names, when bounded>
- mutation_kind: <kind>
- plan_path: <abs path or "(none)">
- backlog_path: <abs path or "(none)">
```

For `design-sync`, also include in the prompt body: *"This sync re-distills
design.md from the current state of design-mechanics.md. Verify that every
TL;DR and mechanism overview in design.md accurately summarizes the current
mechanics file's content for the same-named section."*

The sub-agent returns a structured Markdown verdict per the prompt's output
format. Parse the **Verdict** line (`PASS` or `NEEDS REVISION`) and the
**Structural findings** list. Map cold-read findings into the same severity
schema as mechanical findings.

### Step 5: Merge findings

Combine mechanical + cold-read findings into a single list. Sort by
severity: `blocker` → `should-fix` → `suggestion`. Mechanical findings
carry a structured `rule` field; cold-read findings are free-form
bullets and won't usually duplicate the mechanical set, but if a
cold-read bullet plainly restates a mechanical finding (same severity,
same location, same shape rule), drop the cold-read copy.

### Step 6: Iterate

Each iteration runs in this order until either the budget is exhausted
or no findings remain:

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
3. **Re-run mechanical checks** and, if mechanical now passes and
   cold-read was applicable for this kind, re-run cold-read. Replace the
   prior findings list with the new one.
4. **Decrement the iteration budget.** Stop when the budget reaches
   zero or all blocker + should-fix findings are gone.

Outcomes when the loop exits:

- **All blockers and should-fix findings cleared**: PASS — proceed to
  Step 7.
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

Resolve the log path from `mutation_kind` and `design_path` using
the rule below. The log always lives under `_workflow/reviews/` so
the Phase 4 cleanup commit reliably removes it; never write the log
to the top-level `<dir>/reviews/`.

- **For all mutation kinds *except* `phase4-creation`**:
  `design_path = docs/adr/<dir>/_workflow/design.md` (or
  `design-mechanics.md`), so the plan dir is `design_path`'s parent
  (`docs/adr/<dir>/_workflow/`) and the log lives at
  `docs/adr/<dir>/_workflow/reviews/design-mutations.md`.
- **For `phase4-creation`** (special case): `design_path =
  docs/adr/<dir>/design-final.md` (top-level, intentionally
  outside `_workflow/` because `design-final.md` itself is a
  durable artifact). The log path is **not** derived from
  `design_path`'s parent — instead, it is forced to
  `<design_path's parent>/_workflow/reviews/design-mutations.md`,
  i.e., `docs/adr/<dir>/_workflow/reviews/design-mutations.md`.
  This appends to the existing Phase 1 / inline-replanning log
  under `_workflow/`, preserving the full mutation history of the
  design and ensuring the Phase 4 cleanup commit removes the
  entire log along with everything else under `_workflow/`.

Append to the resolved path (create the `_workflow/reviews/`
directory and file if they don't exist). Format per
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

After a `mechanics-edit` mutation completes, count `mechanics-edit` entries
in the review log since the most recent `design-sync` (or since
`phase1-creation` if no sync has happened yet). If `count >= 5`:

> Surface to the user at the next conversational turn: *"5 mechanics edits
> have accumulated since the last design.md sync. The polished view in
> design.md is N edits behind. Want me to run a `design-sync` now, or keep
> iterating?"*

Do not auto-trigger the sync. The user is the gate — they may want to
iterate further before publishing. The suggestion fires once per turn until
either (a) the user says yes (run sync), (b) the user says no/defer (skip
the prompt for this turn; it'll fire again next turn), or (c) a sync runs
(counter resets to 0).

The user can also explicitly request a sync at any count: "let's update
design.md", "run design-sync", "publish the polished version" — any
phrasing that conveys intent. Treat the request as authorization to run a
`design-sync` mutation.

### Step 9: Present to the user

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

The full Phase 1 lifecycle (sub-phases, sync triggers, working-mode
counter) lives in `design-document-rules.md § Two-mode editing — working
vs sync`. The one operational protocol anchored here — because rules.md
cross-refs to it — is the staleness-reconciliation prompt.

During Phase 1.2 (`mechanics-edit` rounds), `design.md` is **frozen**
relative to mechanics. The user reads `design.md` to review and issues
feedback against it. If the user's request references a `design.md`
statement that mechanics has already moved past, the agent reconciles
explicitly:

> *"Your request references design.md saying X. Mechanics has accumulated
> N edits since the last sync, and X has been updated to Y. Should I
> (a) revert mechanics to X then apply your new request, (b) apply your
> request on top of the current state Y, or (c) sync design.md first so
> you can see Y, then issue the request?"*

The user picks. Default to (b) when the user's intent is clear and the
delta between X and Y is incidental; default to (c) when the delta changes
the meaning of the request.

## Tools used

- `Read` — verify file state, read review log for mutation count and last
  sync point.
- `Edit` / `Write` — apply the edit, distill design.md during sync, apply
  any auto-fixes.
- `Bash` — run the mechanical-checks script.
- `Agent` — spawn the cold-read sub-agent (skipped for `mechanics-edit`).
- `Edit` (append-mode via full-content read) — write the review-log entry.

## When NOT to use this skill

- Edits to `implementation-plan.md`, `implementation-backlog.md`, or any
  other workflow file. Those have their own gates (Phase 2 structural
  review). The `**Full design**` ref-propagation that lands in plan and
  backlog during a `section-rename` or `design-sync` is part of this
  skill's scope, but isolated plan-only edits are not.
- Edits to source code, tests, or docs outside `docs/adr/<plan-dir>/`.
- Pre-Phase-1 scratch notes that haven't been promoted to `design.md`
  yet — only the canonical paths above trigger the discipline.

## Failure modes and recovery

- **Script not found** at `.claude/scripts/design-mechanical-checks.py`:
  the project may not have the discipline wired up. Stop and ask the
  user; do not silently fall back to direct `Edit`.
- **Cold-read sub-agent times out or returns malformed output**: re-run
  once. If it fails again, treat the cold-read half as `INCONCLUSIVE`,
  log the result, and continue with mechanical findings only. Add a
  `should-fix` line to the review log noting the cold-read failure.
- **Budget exhausted**: do not loop further. The user is the gate when
  the action can't self-correct.
- **Sync distillation produces an empty diff** (mechanics has no changes
  since last sync): no-op the sync, log a one-line entry noting the
  zero-delta sync, and skip mechanical / cold-read for this round.

## Examples

Two intricate cases worth showing concretely. The simpler kinds
(`phase1-creation`, `mechanics-edit`, `content-edit`) follow the
Workflow steps directly with no special handling.

**Example 1 — Sync (`design-sync`).**
After 5 mechanics-edits accumulate, the user says "OK, update
design.md". The skill:

1. Reads the review log, identifies all `mechanics-edit` entries since
   the most recent `design-sync` (or since `phase1-creation` if no sync
   has happened yet).
2. Reads `design-mechanics.md` to see the current state.
3. Distills `design.md` — updates each affected section's TL;DR +
   overview + edge cases + references to match current mechanics.
4. Updates plan/backlog `**Full design**` refs for any renamed/added/
   removed sections.
5. Runs mechanical checks with `--target=both`.
6. Spawns cold-read with `whole-doc` scope, including the sync-specific
   "verify design.md reflects current mechanics" instruction.
7. Iterates as needed.
8. Appends `Mutation N — ... — design-sync` to the review log; the
   working-mode counter resets to 0.
9. Presents the user a "what changed in mechanics since last sync"
   summary alongside the diff and log entry.

**Example 2 — Section rename (`section-rename`).**
The user asks to rename `## DPB (D33)` to `## Architectural redesign:
Dirty Page Bitset (D33)`. This design has a `design-mechanics.md`
companion, so the rename has to propagate. The skill:

1. Applies the rename `Edit` in `design.md`.
2. Updates the matching section name in `design-mechanics.md` — per
   the "section names match" rule in
   `design-document-rules.md` § Length-triggered split into
   `design-mechanics.md`.
3. Updates every `**Full design**: design.md §"DPB (D33)"` line in
   `implementation-plan.md` and `implementation-backlog.md` to use the
   new name.
4. Runs mechanical checks with `--target=both`, `--scope=whole-doc` —
   confirms zero broken refs. (`both` because mechanics was touched;
   the cold-read scope table resolves `design \| both` to `both` for
   this case.)
5. Spawns cold-read with `whole-doc` scope.
6. Logs and presents.

If the design has **no** `design-mechanics.md` companion, step 2 is
skipped and step 4 runs with `--target=design`. Step 3 still runs —
plan/backlog ref propagation is independent of whether mechanics
exists.

## Reference

- Rules: `.claude/workflow/design-document-rules.md` § Mutation discipline
  and § Two-mode editing — working vs sync
- Cold-read prompt: `.claude/workflow/prompts/design-review.md`
- File layout: `.claude/workflow/conventions.md` §1.2
- Mechanical script: `.claude/scripts/design-mechanical-checks.py`
