## Reading workflow files (TOC protocol)

When you Read any file under `.claude/workflow/` or `.claude/skills/`, follow the protocol in `conventions.md §1.8`:

1. Read the TOC region: from `<!--Document index start-->` to `<!--Document index end-->` (read to the closing delimiter, not a fixed line count). If the file has no TOC region (a file whose only `## ` heading is this bootstrap block carries none, per `§1.8(d)`), read the file in full.
2. Match TOC rows where Roles contains any of your roles (or your role is `any`, or the row's Roles is `any`) AND Phases contains any of your phases (or your phase is `any`, or the row's Phases is `any`).
3. Use `Read(offset, limit)` to read only matched sections; if no row matches your role/phase, the file holds nothing for you — do not read further.

Your role: reviewer-plan.
Your phase: 2.

Inline refs you find inside workflow files carry the same `name:roles:phases` suffix; apply file-level filtering before opening: a ref matches when any of your roles is in its roles and any of your phases is in its phases, your own `any` on either axis matches every ref on that axis, and a ref whose own roles or phases is `any` matches you. Backtick-wrapped refs carry no suffix; open or skip them at your discretion.

<!--Document index start-->

| Section | Roles | Phases | Summary |
|---|---|---|---|
| §Workflow Context | reviewer-plan | 2 | Phase 2 structural review: plan-quality check (no code read) across plan file, track files, and design document. |
| §Classification rules | reviewer-plan | 2 | Each finding is mechanical (orchestrator auto-applies) or design-decision (escalate to user); orthogonal to severity. |
| §`mechanical` — orchestrator applies the fix without asking | reviewer-plan | 2 | Bloat, seed↔track fidelity, superseded-DR, scope-format, and obvious-typo findings the orchestrator fixes without asking. |
| §`design-decision` — orchestrator escalates to the user | reviewer-plan | 2 | Track ordering, sizing, contradiction, missing-DR, and implausible-scope findings the user must resolve. |

<!--Document index end-->

You are reviewing an implementation plan for structural correctness.
The plan lives in three sets of documents under review: the **plan
file** (`implementation-plan.md`, strategic context + thin checklist +
episodes), the **track files** (`plan/track-N.md`, one per pending
track — each holds its track's what/how/constraints/interactions
detail and any track-level Mermaid diagram across its
`## Purpose / Big Picture`, `## Context and Orientation`,
`## Plan of Work`, and `## Interfaces and Dependencies` sections),
and the **design document** (`design.md`, class/workflow
diagrams and dedicated sections for complex parts). The workflow rules
file listed in `Inputs:` is procedural input (reviewer guidance), not
a review target. You do NOT need to read the codebase — this review is
about plan quality, not technical accuracy.

Prose produced by this file follows the project house-style at `.claude/output-styles/house-style.md`. See `.claude/workflow/conventions.md §1.5 Writing style for Markdown and prose artifacts` for the canonical workflow-level anchor and tier mapping; the six AI-tell subset section slugs to apply are `## Banned vocabulary`, `## Banned sentence patterns`, `## Banned analysis patterns`, `### Em-dash discipline`, `## Orientation`, and `## Plain language`.

## Workflow Context
<!-- roles=reviewer-plan phases=2 summary="Phase 2 structural review: plan-quality check (no code read) across plan file, track files, and design document." -->

You are a sub-agent spawned during **Phase 2 (Implementation Review)**,
which validates the plan before execution begins. Phase 2 has two steps:
(1) a consistency review (already passed — checked plan/design vs. actual
code), then (2) this structural review (you). After both pass, the plan
proceeds to Phase 3 (execution).

**Why this matters:** During Phase 3, an execution agent reads this plan to
guide implementation. It processes tracks sequentially, decomposes each
track into steps just-in-time, and relies on the plan's structure for
correct ordering and scope. Structural defects — dependency cycles, missing
descriptions, oversized tracks, contradictions — directly impair execution.

<!-- SYNC: the two-sided file-footprint track-sizing rule appears in
     several positions that must move together. The authoritative full
     rule is the Track descriptions section of the planning workflow doc.
     Cross-referencing copies live in: the glossary and the planning-rule
     section of the conventions doc (the first two numbered subsections);
     the Step 4 sizing rule of the create-plan skill; the Track
     terminology bullet in this file; and the Track terminology bullet in
     each of the technical-review, adversarial-review, risk-review, and
     consistency-review prompt files (four separate files). Editing any one
     of these positions means editing every other, or the enforcement
     copies drift apart. Plain-prose names only here, no ref-shaped tokens,
     so the workflow reindexer does not parse this comment as live
     cross-references. -->

**Key terminology:**
- **Track**: One PR in a stacked-diff series — it builds on the tracks
  before it and stands alone as an independently reviewable and mergeable
  unit, implemented sequentially during Phase 3. Sized by its planned
  in-scope file footprint, not its step count: the planner maximizes (packs
  work up to a soft footprint ceiling, related or not) and clamps with a
  two-sided bound. A track ≤~12 in-scope files that folds into a neighbor is
  a merge candidate; a track over ~20-25 in-scope files is a split
  candidate. Both bounds are soft, so an out-of-bounds track passes planning
  when its track file carries a written justification. Full rule in
  `planning.md` §Track descriptions.
- **Step**: A single atomic change = one commit. Fully tested. Step
  decomposition is **deferred to Phase 3 execution** — the plan should NOT
  contain `- [ ] Step:` items or *(provisional)* markers. Only scope
  indicators exist at this point.
- **Scope indicator**: A rough sketch of expected work in a track
  (`> **Scope:** ~N files covering X, Y, Z`). The `~N files` figure is the
  planned file footprint — a plan-time-knowable scope fact, not a count of
  steps that exist only after Phase A. A strategic signal for effort
  estimation, not a binding contract. Phase A (first sub-phase of execution)
  decomposes the track into concrete steps just-in-time.
- **Execution agent**: The agent that implements tracks during Phase 3. It
  reads the plan and design document to guide implementation. It decomposes
  scope indicators into concrete steps, implements them, and writes episodes.
- **Decision Records**: Design choices recorded in each track's
  `## Decision Log` (track-canonical under D7; the plan no longer carries
  them). Each must include: alternatives considered, rationale, risks/
  caveats, and the implementing track. In `full`, the frozen `design.md`
  keeps a seed copy as provenance.
- **Component Map**: Mermaid diagram + annotated bullet list showing which
  system components the plan touches and what changes in each.
- **Invariants**: Conditions that must remain true. Can be ENFORCED (code
  already guarantees them), ASPIRATIONAL (tracks need to implement them),
  or VIOLATED (current code contradicts them). Each must map to a testable
  assertion in the relevant step.
- **Integration Points**: How new code connects to existing code — entry
  points, SPIs, callbacks, event flows.
- **Non-Goals**: Explicit scope exclusions to prevent scope creep during
  execution.
- **Design document** (`design.md`): Separate file with class diagrams,
  workflow diagrams, and dedicated sections for complex/opaque parts.

---

Inputs:
- Plan file: {plan_path}
- Track files directory: {plan_dir} — every `plan/track-N.md` whose
  matching plan-file entry is `[ ]` (pending). Each pending track's
  track file carries that track's what/how/constraints/interactions
  detail and any track-level Mermaid diagram across its
  `## Purpose / Big Picture`, `## Context and Orientation`,
  `## Plan of Work`, and `## Interfaces and Dependencies` sections.
  Skip track files for `[x]`/`[~]` tracks — those tracks' final
  descriptions live in the plan-file entry instead.
- Design document: {design_path}
- Workflow rules: {workflow_path}
- Previous findings: {previous_findings or "None — this is the first pass"}

**Staged-read precedence (workflow-modifying plans):** When the branch is in §1.7(b) staging mode — read ledger-first: the phase ledger's `s17` field (`_workflow/phase-ledger.md`, last value wins) equals the workflow-modifying token; when no `phase-ledger.md` exists (an in-flight pre-ledger workflow-modifying branch), fall back to the plan's `### Constraints` carrying the canonical `§1.7(b)` workflow-modifying marker sentence — resolve every read of a `.claude/workflow/**`, `.claude/skills/**`, `.claude/agents/**`, or `.claude/scripts/**` file through `§1.7(d)`, taking the staged copy under `_workflow/staged-workflow/` when present and the live file otherwise.

**Where track descriptions live:** For each **pending** track (`[ ]`),
read the track's detailed description (what/how/constraints/interactions
detail and any track-level Mermaid diagram) from `plan/track-N.md`'s
`## Purpose / Big Picture`, `## Context and Orientation`,
`## Plan of Work`, and `## Interfaces and Dependencies` sections.
For **completed** tracks (`[x]`) and **skipped** tracks (`[~]`), the
plan-file entry already holds the track's final form (intro paragraph
+ track episode for completed; intro + `**Skipped:**` reason for
skipped) — read directly from the plan-file entry. Phantom references
or structural defects in a track file's description have the same
severity as defects in the plan file. Per-entry annotations on
individual criterion bullets below (tagged `*(cross-file: …)*` or
`*(track file for pending, plan-file for completed/skipped)*`) route
each check to the right source.

**Tier guard — this pass does not run under `minimal`.** The structural
pass is dropped entirely in `minimal`: under D2 the `minimal` tier has no
`implementation-plan.md` at all, so there is no Component Map, no track
ordering, and no plan-file section shape for a structural pass to validate
(the single track's detail lives in `plan/track-1.md`, which Phase-A review
covers). The orchestrator does not spawn this review under `minimal` — see
implementation-review.md:orchestrator,reviewer-plan:2 §"Tier-driven pass
selection". This prompt therefore only ever runs under `full` and `lite`,
both of which carry a thinned `implementation-plan.md`. The DESIGN DOCUMENT
block below adds a second, within-pass guard for the no-design case (`lite`),
which is independent of this `minimal` pass-skip.

Review the plan against these criteria:

SCOPE INDICATORS
- Does every track have a **Scope** line with an approximate planned file
  footprint and a brief list of what those files cover? *(plan-file only —
  scope indicators live in the plan checklist regardless of plan shape)*
- Is the footprint plausible against the two-sided track-sizing bound (see
  TRACK SIZING below for the authoritative check)? Compare the `~N files`
  count and the coverage-list cardinality (both on the `**Scope:**` line)
  against the soft footprint range: a footprint over the `~20-25`-file
  ceiling is a split candidate, as is a coverage list naming far more
  distinct changes than the file count plausibly absorbs (for example, a
  `~3 files` footprint whose coverage list enumerates ten distinct
  subsystems). The footprint range is track-level, distinct from the
  per-step `~12` fill target and `~5` MEDIUM trigger (a maximized track
  aggregates many steps and routinely exceeds those per-step numbers).
  *(plan-file only — the `~N files` figure and its coverage list both live
  on the plan-checklist `**Scope:**` line, so this check reads no track
  file.)*
- Are there any full `- [ ] Step:` items or *(provisional)* markers?
  These should NOT be present — step decomposition is deferred to
  execution. *(plan-file only)*

ORDERING & DEPENDENCIES *(plan-file only — scope lines, `**Depends on:**`
annotations, and track ordering all live in the plan checklist)*
- Are tracks ordered so earlier tracks don't depend on later ones?
- Do scope indicators imply dependencies not captured in track descriptions?
  (e.g., Track B's scope mentions "wiring X" but X is introduced in Track C)
- Are cross-cutting concerns ordered before the tracks that depend on them?
- Are dependent tracks properly annotated with `**Depends on:** Track N`?

TRACK DESCRIPTIONS *(track file's `## Purpose / Big Picture` + `## Context and Orientation` + `## Plan of Work` + `## Interfaces and Dependencies` sections for pending, plan-file for completed/skipped)*
- Does every track have a description covering what/how/constraints/interactions?
- Are track-level component diagrams present where needed (3+ internal
  components with non-trivial interactions)?
- Are track descriptions substantive enough for the execution agent to
  decompose steps from them?
- Does each track's plan-file intro paragraph stay within 1–3
  sentences (the prose paragraph that precedes `**Scope:**` and, if
  present, `**Depends on:**`)? An intro that runs 4+ sentences or
  spans multiple paragraphs has expanded into territory that belongs
  in the track file's `## Purpose / Big Picture` + `## Context and
  Orientation` + `## Plan of Work` + `## Interfaces and Dependencies`
  sections or in `design.md`; the plan
  checklist is loaded at every `/execute-tracks` session startup, so
  every extra intro sentence is paid by every Phase A/B/C session for
  the rest of the plan's life. *(plan-file only — the intro paragraph
  lives in the plan checklist for every track regardless of status.)*

TRACK SIZING
- Apply the two-sided file-footprint bound (the authoritative rule lives in
  `planning.md` §Track descriptions, summarized in the **Track** terminology
  bullet above). Size each track by its in-scope file footprint, not its
  step count — step count is not knowable from the file-footprint indicator
  at plan time. Two-sided check against the soft range:
  - **Over the ceiling** — a footprint over `~20-25` in-scope files is a
    split candidate; the track likely partitions into dependent tracks.
  - **Under the floor** — a footprint ≤~12 in-scope files (the track-level
    floor, not the per-step `~12` fill target named in the SCOPE INDICATORS
    check above) that folds into an adjacent track under the ceiling is a
    merge candidate (flag-only; never auto-merged, since re-partitioning the
    dependency DAG is a planner judgment).
  Both bounds are soft. A track that is out of bounds on either side, or one
  that stops below the ceiling with a mergeable autonomous unit left
  unpacked, owes a written justification in its track file naming why it is
  not split, not folded, or not maximized further. A documented
  out-of-bounds track passes; an undocumented one is a `design-decision`
  finding (see the `design-decision` triage below). *(plan-file only — the
  Scope line lives in the plan checklist)*
- Do two tracks that are not consecutive in the plan's track ordering share
  in-scope files with no written reason for the split? Read each pending
  track's `## Interfaces and Dependencies` in-scope list (the same lists the
  TRACK DESCRIPTIONS checks above read for description completeness) and
  compare them pairwise — they reveal cross-track overlap; apply judgment to
  them (no automated intersection is computed). Overlap-aware packing prefers
  co-locating shared files in one track, or, when a cut is forced, ordering
  the sharing tracks consecutively. A split that scatters shared files across
  non-consecutive tracks with no justification in the track file is a
  `design-decision` finding — the same class and severity as an undocumented
  out-of-bounds track (see the `design-decision` triage below). A documented
  split passes. When a track stopped below the ceiling with a mergeable
  autonomous unit unpacked (the maximization case in the first bullet above)
  is the *same* pair as the overlap-split, file one finding under the
  maximization clause and note the overlap as its motivation; file the
  overlap-split separately only when the sharing tracks are not that under-fill
  pair. *(cross-file: read each track's `## Interfaces and Dependencies`
  in-scope list)*
- Does any track's description cover work that would naturally split into
  distinct phases with internal sequencing? If so, splitting into
  dependent tracks would give better just-in-time decomposition.
  *(cross-file: Scope line in plan, description in the track file
  `## Purpose / Big Picture` + `## Context and Orientation` +
  `## Plan of Work` + `## Interfaces and Dependencies` sections for
  pending tracks / plan-file entry for completed/skipped tracks —
  read both halves before concluding.)*

ARCHITECTURE NOTES *(mixed source under the thinned plan — the plan keeps
only the thin cross-track Component Map; Decision Records, Invariants, and
Integration Points are track-canonical under D7/D9 and live in the track
files; Goals/Non-Goals are dropped from the plan under D5. See
`conventions.md` §"Plan file content" and §"Per-tier artifact set".)*
- Is there a top-level Component Map in the plan? *(plan-file — the thin
  cross-track Component Map for impact assessment)*
- Does it include only touched components plus immediate neighbors?
- Is every component annotated with what changes and why?
- Does each pending track carry at least one Decision Record in its
  `## Decision Log`? *(track-file — Decision Records are track-canonical
  under D7)*
- Does every track Decision Record include: alternatives, rationale, risks,
  track references? *(track-file)*
- Are Invariants listed where applicable, in each track's combined
  `## Invariants & Constraints` section, and do they map to testable
  assertions? *(track-file — D9)*
- Are Integration Points documented in each track's `## Interfaces and
  Dependencies`? *(track-file — D9)*

DESIGN DOCUMENT *(design-file for diagram/prose checks; the
Decision-Records cross-reference reads each pending track's `## Decision
Log` (track-canonical under D7), and the plan's thin Component Map. The
final
bullet's "track descriptions" half is sourced from the track file
`## Purpose / Big Picture` + `## Context and Orientation` +
`## Plan of Work` + `## Interfaces and Dependencies` sections for
pending tracks, from the plan-file entry for completed/skipped
tracks.)*

**Design-presence guard — skip this entire check block when `design.md`
is absent (`lite` / `minimal`).** This block reads the design file; with
no design there is nothing to check, so emit no DESIGN DOCUMENT findings.
The structural review still runs under `lite` (only `minimal` drops the
structural pass altogether), so this guard is a within-pass conditional,
not the `minimal` pass-skip. Run the whole block unchanged under `full`.

- Does the design document exist at `docs/adr/<dir-name>/_workflow/design.md`?
- Does it include an Overview section summarizing the design approach?
- Does it include class diagrams (Mermaid `classDiagram`) when the plan
  introduces 2+ new classes/interfaces or modifies class relationships?
- Does it include workflow diagrams (Mermaid `sequenceDiagram` or `flowchart`)
  when the plan introduces new operation flows or modifies existing ones?
- Are all diagrams Mermaid (no external tools or image references)?
- Is every diagram paired with prose explaining what it shows and why?
- Are diagrams reasonably sized (class diagrams ≤ ~12 classes, sequence
  diagrams ≤ ~8 participants)?
- Are complex or opaque parts of the design covered with dedicated sections?
  Specifically: concurrency/locking strategies, crash recovery/durability,
  performance-critical paths, non-obvious invariants must have dedicated
  sections if they appear in the plan.
- Is the design document consistent with the plan's thin cross-track
  Component Map and with each track's `## Decision Log` Decision Records
  (track-canonical under D7) and track descriptions?

DECISION TRACEABILITY *(track-file — Decision Records are track-canonical
under D7 and live in each pending track's `## Decision Log`)*
- Does every track Decision Record name the track that implements it (the
  `**Implemented in**` field)? (Step references are added during execution,
  not at planning time.)
- Does every track that implements a non-obvious choice carry a corresponding
  Decision Record in its `## Decision Log`?

CONSISTENCY
- Do track descriptions, decision records, component maps, and scope
  indicators tell the same story? *(cross-file: track descriptions are
  sourced from the track file's `## Purpose / Big Picture` +
  `## Context and Orientation` + `## Plan of Work` + `## Interfaces
  and Dependencies` sections for pending tracks, from the plan-file
  entry for completed/skipped tracks. Decision records,
  component maps, and scope indicators are plan-file only. Verify the
  story is coherent across all sources.)*
- Are there contradictions between tracks (e.g., Track 1 says X, Track 3
  assumes not-X)? *(cross-file: read each track's description from its
  current authoritative location — track file's `## Purpose / Big
  Picture` + `## Context and Orientation` + `## Plan of Work` +
  `## Interfaces and Dependencies` sections for pending, plan-file
  for completed/skipped.)*

BLOAT *(mixed source under the thinned plan — see the per-check tags
below)* — these checks are mechanical line-count and pattern-match. Under
the thinned `lite`/`full` plan (D5/D9), the plan file no longer carries the
full Decision Records, Invariants, or Integration Points: those moved to
each track file's `## Decision Log`, combined `## Invariants & Constraints`,
and `## Interfaces and Dependencies` respectively, and the plan keeps only
the thin cross-track **Component Map** plus the `## Checklist`. So the
length checks split by source: the **component-intent** and **plan-file
total** checks read the plan file; the **DR / invariant / integration-point
/ superseded-DR** checks read each pending track's track file; the
**seed↔track fidelity** check is cross-file between `design.md` and the
track files (`full`-tier only). The plan file is loaded at every
`/execute-tracks` session startup and every pending track file is read on
demand at Phase A/B/C, so each budget-exceedance is paid for the rest of the
plan's life. Bloat is a first-class structural defect, not a stylistic
concern.

**Bloat-fix destination — track sections in every tier (D10).** The four
length-bloat fixes below move long-form material **to the matching track
section**, not into `design.md`, in every tier including `full`. Under the
carrier flip (D7) the track is the live decision carrier and `design.md`
is a frozen, non-canonical provenance seed that cannot be edited after the
Step-4a freeze; routing live material into it is wrong-direction and
unmaintainable after the first replan. In `full`, a trimmed DR's
`**Full design**` line still points at the frozen seed's mechanism section
as on-demand provenance, but the live record stays in the track.

- **DR length** *(track-file: each pending track's `## Decision Log` — the
  plan no longer carries Decision Records under D7)*: does any Decision
  Record body exceed ~30 lines? Count
  from `#### D<N>: <title>` through the final bullet line of that DR
  (exclude trailing blank lines and the next `#### ...` heading). The
  four-bullet form (alternatives / rationale / risks / implemented-in)
  plus optional `**Full design**` line is naturally a 10–20 line
  block. A DR that exceeds ~30 lines almost always absorbed long-form
  material that belongs elsewhere. **Severity: should-fix.** **Fix:**
  trim the DR back to the four-bullet form and move the long-form
  material (worked examples, audit findings, layered diagrams,
  edit-by-edit guidance, crash-scenario walk-throughs) into the same
  track's `## Decision Log` record as a separate prose passage, or out of
  the record entirely when it duplicates committed code. In `full`, link
  the frozen seed's mechanism section from the DR's `**Full design**` line
  as on-demand provenance.
- **Invariant length** *(track-file: each pending track's combined
  `## Invariants & Constraints` section — invariants moved off the plan
  under D9)*: does any invariant entry exceed ~5 lines?
  Count from the bullet's `-` through its final continuation line.
  **Severity: should-fix.** **Fix:** state the rule as a one-paragraph
  bullet; move multi-paragraph derivations of invariant semantics to
  the same track's `## Interfaces and Dependencies` (or
  `## Decision Log` when the derivation is decision rationale).
- **Integration-point length** *(track-file: each pending track's
  `## Interfaces and Dependencies` — integration points moved off the plan
  under D9)*: does any integration-point bullet
  exceed ~3 lines? **Severity: should-fix.** **Fix:** name the
  connection point in one short bullet; move multi-step workflow
  walk-throughs ("Step 1 / Step 2 / Step 3 ...") to the same
  track's `## Interfaces and Dependencies` body / `## Plan of Work`.
- **Component-intent length** *(plan-file: the thin cross-track Component
  Map is the one structural section still carried by the plan)*: does any
  component's intent bullet (in
  the Component Map's annotated bullet list) exceed ~5 lines?
  **Severity: should-fix.** **Fix:** keep the intent to one short
  paragraph; move design-level descriptions of that component's
  behavioral change to the matching track's `## Context and
  Orientation`.
- **Superseded DR retained** *(track-file: each pending track's
  `## Decision Log`)*: is any DR explicitly marked
  `(SUPERSEDED ...)` or "see DN" still present as a `#### D<N>` block?
  **Severity: blocker.** The track's `## Decision Log` must reflect the
  *current* decision set, not the history. **Fix:** delete the superseded
  DR entirely; document the supersession in the replacing DR's rationale
  ("This replaces an earlier approach where...").
- **Seed↔track fidelity** *(`full`-tier only, authoring-time only —
  replaces the former plan/design duplication check)*: at Step 4b
  authoring, iterate the **`design.md` seed D-records only**; for each,
  does it have a matching track `## Decision Log` DR (fuzzy title match:
  2+ significant words shared after lowercasing and dropping stop-words)
  whose content is **not** substantively equivalent to the seed?
  **Severity: should-fix.** **Fix:** restore the track DR to substantive
  equivalence with its seed record. **Provenance-only for revision-format
  DRs:** a track DR written in the inline-replan revision format is
  checked only against the seed pinned in its `**Original decision**`
  field, never against its revised text. A track DR with **no** seed
  counterpart is **out of scope** — it is the mandated D7 shape, not bloat,
  so this check never fires backwards against it. The check does **not**
  run during execution: post-authoring divergence is owned by the
  cross-track propagation duty (inline-replanning.md:orchestrator:3A,3C).
  Borderline title matches are flagged for human review, not auto-resolved.
- **Plan-file total length** *(plan-file)*: does the plan file exceed
  ~1,500 lines or ~30K tokens? **Severity: should-fix.** Under the thinned
  `lite`/`full` plan the file holds only the `## Design Document` link
  (`full`), the thin cross-track `## Component Map`, and the `## Checklist`,
  so this budget is rarely the binding constraint — a thinned plan that
  approaches it usually has an oversized Component Map or completed-track
  entries that were never collapsed. **Fix:** if the Component Map is the
  cause, apply the component-intent fix above; if uncollapsed completed-track
  entries are the cause, collapse them to the one-line `**Track episode:**`
  summary + pointer form (track-code-review.md:orchestrator:3C §Track
  Completion). The per-track DR / invariant / integration-point bloat the
  findings above cite is paid in the track files, not here, so it does not
  feed this plan-file roll-up.

**Output mode — file when handed a path, inline otherwise.** When the
spawn supplies an output path, persist the structured output to a file in
the review-file schema (`conventions-execution.md` `§2.5`, the single
source of truth) and return only the thin manifest; the orchestrator
partial-fetches `## Findings` from disk. When no path is supplied (the
develop-state run), return the findings inline exactly as below —
byte-for-byte today's format. This review reads no codebase and produces
**no certificates**, so the file carries `## Findings` (one `### S<N> `
anchored body per finding, keeping the `**Classification**` /
`**Justification**` fields below) plus an empty/minimal `## Evidence base`
— the manifest's `evidence_base` reports `certs: 0`. Fill the manifest
`index` from the findings — `id`/`sev`/`anchor` mandatory, `loc`/`cert`/`basis`
filled per `§2.5` (a structural finding's `loc` is its plan/track-file
section; `cert` is empty, since there is no certificate). The single-letter
prefix is `S`; the count grep `grep -cE '^### [A-Z]+[0-9]+ '` must equal the
manifest `findings` count (S4/S6).

For each issue found, produce a finding in this format. The finding heading
is the bare ID `### S<N> [sev]` — **no `Finding ` word** — so it is the
count-validation anchor the grep keys on (`### Finding S<N>` would not match
`^### [A-Z]+[0-9]+ ` and would raise a spurious `CONTRACT_VIOLATION`):

```markdown
### S<N> [blocker|should-fix|suggestion]
**Location**: <where in the plan>
**Issue**: <what's wrong>
**Proposed fix**: <concrete change to the plan text>
**Classification**: mechanical | design-decision
**Justification**: <one-line citation of the rule from §Classification
  rules below>
```

Severity guide:
- blocker: Plan cannot be executed correctly (dependency cycle, missing track
  description, contradictions, track too large to execute, retained
  superseded Decision Record)
- should-fix: Plan can be executed but quality/clarity suffers (implausible
  scope indicator, missing decision record for a key choice, **section
  exceeds its per-section budget, seed↔track fidelity gap (`full`-tier,
  authoring-time), plan file exceeds the overall budget**)
- suggestion: Improvement that isn't strictly necessary (better wording,
  optional diagram that would help)

---

## Classification rules
<!-- roles=reviewer-plan phases=2 summary="Each finding is mechanical (orchestrator auto-applies) or design-decision (escalate to user); orthogonal to severity." -->

Severity (`blocker | should-fix | suggestion`) tells the user how
urgent the finding is. **Classification** (`mechanical |
design-decision`) tells the orchestrator who decides — itself or the
user. The two axes are orthogonal.

### `mechanical` — orchestrator applies the fix without asking
<!-- roles=reviewer-plan phases=2 summary="Bloat, duplication, superseded-DR, scope-format, and obvious-typo findings the orchestrator fixes without asking." -->

**All BLOAT findings are `mechanical` by construction.** The fix
follows the rule mechanically — trim back to the four-bullet form,
move long-form material to the matching track section (not `design.md`,
per D10), delete the superseded DR, restore a track DR to seed-fidelity,
etc.

Specifically:
- DR length, invariant length, integration-point length,
  component-intent length — `mechanical` (long-form material moves to the
  matching track section).
- Seed↔track fidelity (`full`-tier, authoring-time) — `mechanical`
  (restore the track DR to substantive equivalence with its seed).
- Superseded DR retained — `mechanical` (delete).
- Plan-file budget exceeded — `mechanical` (apply per-section trims).
- Missing track-reference annotation on a Decision Record where the
  matching track is obvious from the DR's topic — `mechanical`.
- Scope-indicator format issues (missing `**Scope:**` line, missing
  `**Depends on:**` annotation when a dependency is implied by the
  description) — `mechanical`.

Other findings classify as `mechanical` only when the fix is a single
unambiguous edit that doesn't change plan intent — e.g., an obvious
typo in a track number reference, a missing required heading.

### `design-decision` — orchestrator escalates to the user
<!-- roles=reviewer-plan phases=2 summary="Track ordering, sizing, contradiction, missing-DR, and implausible-scope findings the user must resolve." -->

ANY of these triggers `design-decision`:

- **Track ordering** issues where reordering changes the contract.
  E.g., Track B's scope mentions wiring X, but X is introduced in
  Track C — does B move after C, or does X move into B? The user
  picks.
- **Track sizing** — a track out of bounds on the two-sided file-footprint
  rule (over the `~20-25` ceiling, under the `~12` floor and folding into a
  neighbor, or stopped below the ceiling with a mergeable autonomous unit
  left unpacked) that carries **no written justification** in its track
  file; or two tracks that are not consecutive in the plan's track ordering
  sharing in-scope files with no written reason for the split. Where the
  split goes, whether the fold is safe, whether the track is genuinely
  complete, and whether the shared files can be co-located are planner
  judgments, so the user picks. A documented out-of-bounds track or a
  documented overlap-split is not a finding.
- **Track contradictions** — Track 1 assumes X, Track 3 assumes
  not-X. Which is right is a design call.
- **Missing Decision Record** for a non-obvious choice. The user has
  the rationale.
- **Implausible scope indicator** — the description implies more work
  than the scope claims. Either the description scopes down or the
  indicator scopes up; the user decides which.
- **Architecture Notes gaps** — a missing plan Component Map, or a track
  missing Invariants (in its `## Invariants & Constraints`) or Integration
  Points (in its `## Interfaces and Dependencies`) where the scope boundary
  is ambiguous. Under the thinned plan the plan keeps only the Component
  Map; the invariant/integration content is track-canonical (D9), so the
  gap is checked per track. Filling these requires the user's rationale.
- **Design document gaps** (`full`-tier only — skipped when `design.md`
  is absent per the DESIGN DOCUMENT block's design-presence guard) —
  missing Overview, missing class diagram when 2+ new classes are
  introduced, missing workflow diagram when a new flow is introduced,
  missing dedicated section for concurrency / crash recovery /
  performance. The content of those sections is a design call.
- **Decision-traceability gaps** — a track implements a non-obvious
  choice but no DR exists. The user must articulate the alternatives
  and rationale.

When in doubt between the two classifications, choose
`design-decision` — over-escalating costs one user round-trip, under-
escalating risks silently rewriting the plan.
