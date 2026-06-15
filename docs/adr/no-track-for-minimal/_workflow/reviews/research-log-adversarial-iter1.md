<!-- workflow-sha: 0000000000000000000000000000000000000000 -->
# Research-log adversarial review (iter 1) — no-track-for-minimal

Phase 0 → 1 gate. Target: `_workflow/research-log.md` (`## Decision Log`,
`## Surprises & Discoveries`, `## Open Questions`). Matched categories:
Workflow machinery, Architecture / cross-component coordination — the
workflow-machinery prose-scrutiny stance applies (rule coherence,
instruction completeness, context-budget impact); no `review-workflow-*`
dispatch, no `.claude/**` diff exists at this boundary. All references
verified via grep + Read against live workflow Markdown/Bash, not PSI.

## Manifest

```yaml
verdict: NEEDS REVISION
findings: 5
blockers: 1
should_fix: 2
suggestions: 2
index:
  - id: A1
    sev: blocker
    anchor: "#a1-blocker"
    loc: ".claude/skills/create-plan/SKILL.md:131-210"
    cert: "Assumption: minimal drop of implementation-plan.md leaves no second resume consumer"
    basis: "create-plan Step 1c keys resume routing on plan presence; minimal drop lands the Neither-file-exists fresh-start branch"
  - id: A2
    sev: should-fix
    anchor: "#a2-should-fix"
    loc: ".claude/workflow/inline-replanning.md:150-164"
    cert: "Assumption: tier-line consumers are all readers (the five-reader map)"
    basis: "inline-replanning ESCALATE is a tier-line WRITER and a minimal->lite/full upgrade must materialize the dropped plan+design"
  - id: A3
    sev: should-fix
    anchor: "#a3-should-fix"
    loc: "docs/adr/no-track-for-minimal/_workflow/research-log.md:30-214"
    cert: "Assumption: the branch's own §1.7 staging mode need not be declared in the log"
    basis: "§1.7(k) criterion 1 fails (resume-state field + track-file section move) -> branch is staging-bound; the log never states its mode"
  - id: A4
    sev: suggestion
    anchor: "#a4-suggestion"
    loc: ".claude/workflow/conventions.md:625-655 (§1.6(f))"
    cert: "Open question: ledger + plan-review-doc stamp status"
    basis: "ledger is replay-immune like research-log; exclusion rationale already exists -> deferrable, but the plan-review-doc half is genuinely open"
  - id: A5
    sev: suggestion
    anchor: "#a5-suggestion"
    loc: "docs/adr/no-track-for-minimal/_workflow/research-log.md:109-125"
    cert: "Decision: Phase-2 audit trail -> new plan-review.md doc"
    basis: "a cheaper alternative (keep audit in the ledger as a cold event) was not listed; the new doc adds a third _workflow artifact for a fact minimal rarely reads"
evidence_base:
  challenges: 4
  violation_scenarios: 0
  assumption_tests: 3
```

## Evidence base

### DECISION CHALLENGES

#### Challenge: Decision 2 — minimal drops the plan; lite/full keep a thinned plan
- **Chosen approach**: `minimal` (one track) drops `implementation-plan.md`
  entirely; the ledger owns resume state so there is no regression.
- **Best rejected alternative**: "all tiers keep a (one-track) summary
  plan" — listed and rejected as vacuous-for-one-track. The user picked
  drop.
- **Counterargument trace**:
  1. The log's Surprise #1 reasons the drop is safe because the three
     machinery consumers (`determine_state`, `detect_drift`, Phase-2
     routing) all dissolve once the ledger owns phase state. Verified:
     `determine_state` (`workflow-startup-precheck.sh:1439`) reads exactly
     the Plan-Review token, the Checklist track glyphs + first-`[ ]` track
     number, and the Final-Artifacts token; `detect_drift` (`:474`) folds
     the stamp of any `_workflow/**` artifact and the always-present
     stamped `track-1.md` satisfies the fold alone. Both checks hold.
  2. But the dissolution analysis enumerates only those THREE consumers.
     A fourth, independent consumer of `implementation-plan.md` presence
     exists and is not in the map: `create-plan` Step 1c
     (`SKILL.md:131-210`), the tier-aware resume check. It keys on the
     presence of two files (`design.md`, `implementation-plan.md`) and
     routes a minimal resume with neither file to the
     "**Neither file exists — fresh start**" branch (`:203-210`), which
     re-runs Step 4 from scratch — the research, the tier classifier, and
     this very adversarial gate.
  3. Outcome: a `minimal` change interrupted after planning, resumed in a
     fresh `/create-plan` session, loses its tier classification and
     re-derives from the aim prompt. That is the resume regression the
     ledger was introduced to prevent (Decision 3), reappearing through a
     consumer the log did not survey.
- **Codebase evidence**: `SKILL.md:203` reasons explicitly "with no
  `implementation-plan.md` on disk there is no tier line to read, so the
  resume correctly reads as a fresh start" — true today, false once
  minimal legitimately ships without a plan.
- **Survival test**: WEAK. The decision survives only if Step 1c is
  rewired to read the ledger's tier line (the disambiguator becomes
  "ledger present + tier line readable", not "plan file present"). That
  rewire is not in the Open-Question ripple list, which names create-plan
  edits as "drop minimal stub, thin lite/full templates, add track
  Constraints, seed ledger" — the resume-routing rewrite is missing.

#### Challenge: Decision 7 — Phase-2 audit trail → a new dedicated plan-review doc
- **Chosen approach**: move the Phase-2 audit summary out of the plan's
  `## Plan Review` section into a new `_workflow/plan-review.md`; split
  review *state* (→ ledger, hot path) from review *fact + summary*
  (→ new doc, cold record); minimal gets the doc too.
- **Best rejected alternative (not listed)**: keep the audit fact AND the
  summary in the ledger as a single cold `phase=2 review=passed` event
  with the summary inline, and add no new artifact. The ledger is already
  the branch-level cold/hot record; a one-line summary on the passed
  event needs no separate file.
- **Counterargument trace**:
  1. The log itself says the doc is "rarely read by agents during
     development — mostly states the fact" (`research-log.md:117`). A fact
     that is rarely read and mostly states "passed" is exactly the shape
     of a ledger event, not a standalone document.
  2. The split rationale (hot state vs cold record) justifies moving the
     *state* to the ledger, but does not justify a *new file* for the
     summary — the ledger entry can carry the summary text the same way an
     episode entry carries a one-line summary.
  3. Outcome: Decision 7 adds a third `_workflow/` artifact type (with its
     own open sub-questions: filename, single-vs-per-iteration, stamp
     status) to hold a fact the chosen "everything has one canonical home"
     principle could place on the ledger at zero new-artifact cost.
- **Codebase evidence**: the ledger format (Decision 6) already carries
  "optional field update" per entry — a `review=passed` field with a
  summary is within the agreed grammar.
- **Survival test**: WEAK. The decision survives if the audit summary is
  genuinely large enough to bloat the ledger tail `determine_state`
  greps; if it is "mostly states the fact", the new doc is unjustified
  ceremony. Strengthen the rationale with the expected summary size, or
  fold it into the ledger.

#### Challenge: Decision 9 — combined `## Invariants & Constraints` track section (15th section)
- **Chosen approach**: collapse per-track Constraints and Architecture-Notes
  Invariants into one NEW track section.
- **Best rejected alternative**: "separate `## Constraints` + Invariants in
  `## Validation and Acceptance`" — listed, rejected as scattering two
  like concepts.
- **Counterargument trace**: the collapse is defensible on the
  "X-must-remain-true-backed-by-a-test" identity. The challenge is not the
  collapse but its classification: adding a track-file `##` section is a
  track-file SCHEMA change, which is load-bearing for Finding A3 (it makes
  the branch staging-bound under §1.7(k) criterion 1). No counterargument
  against the merge itself survives — the merge is sound.
- **Codebase evidence**: `plan-slim-rendering.md:263-281` enumerates the
  existing track sections; the new section is genuinely additive.
- **Survival test**: YES (the merge is sound). Recorded here only because
  it is the schema-move that drives A3.

#### Challenge: Decision 6 — ledger as append-only event log; orchestrator writes via subcommand
- **Chosen approach**: append-only event log, last-occurrence-wins,
  orchestrator calls a new `workflow-startup-precheck.sh --append-ledger`
  subcommand at the points it flips checkboxes today.
- **Best rejected alternative**: "current-state file rewritten each
  boundary" — listed, rejected (needs in-place atomic rewrite).
- **Counterargument trace**: the append-only choice is strictly better
  than the rewrite alternative for crash safety — `detect_drift`'s
  exclusion rationale for `research-log.md` (`conventions.md:625-655`)
  shows append-only logs are replay-immune by construction, so the ledger
  inherits a proven non-stamping path. The "script infers boundaries"
  alternative is correctly rejected (the script would reconstruct
  orchestrator actions).
- **Codebase evidence**: `conventions.md §1.6(f)` excludes `research-log.md`
  precisely because it is an append-only ledger no walk enumerates and no
  phase re-derives — the new ledger is the same shape.
- **Survival test**: YES. The decision is well-grounded; the only residual
  is the event vocabulary (Open Question #1), correctly deferred to design.

### ASSUMPTION CHALLENGES

#### Assumption test: the three-consumer dissolution map is complete
- **Claim**: Surprise #1 — "nothing in the machinery independently requires
  the minimal stub once the ledger owns phase state and the tier line."
- **Stress scenario**: a `/create-plan` session resumed after a minimal
  plan was authored but before any track-implementation session ran.
- **Code evidence**: `create-plan/SKILL.md:131-210` (Step 1c) is a fourth
  consumer of plan presence, not in the map. It runs in the planning
  session, before `determine_state` is ever consulted by `/execute-tracks`,
  so the ledger-owns-state fix for `determine_state` does not cover it.
- **Verdict**: BREAKS. The "nothing independently requires the stub" claim
  is false as stated; the create-plan resume disambiguator does.

#### Assumption test: tier-line consumers are all read-only
- **Claim**: Open Question #4 ripple — "the five tier-line readers →
  ledger."
- **Stress scenario**: a `minimal` change ESCALATEs to `lite`/`full`
  mid-execution (the tier-upgrade path).
- **Code evidence**: `inline-replanning.md:150-164` — the upgrade's first
  artifact is "the D18 tier line **rewrite**" written into
  `implementation-plan.md`; a `lite`→`full` upgrade "writes the new design
  seed alongside the tier-line rewrite". So inline-replanning is a tier-line
  WRITER, and a `minimal`→lite/full upgrade must now CREATE the plan (and,
  for full, the design) that minimal deliberately lacks. The ripple list
  treats all five as readers and routes them "→ ledger"; the writer/upgrade
  carrier and the plan-materialization step are absent.
- **Verdict**: FRAGILE. The five-reader count is right, but at least one of
  the five is also a writer with a structural side effect the log does not
  carry. Deriving the design over this leaves the escalate path
  under-specified for the minimal tier.

#### Assumption test: the branch need not declare its own §1.7 mode
- **Claim**: implicit across the log — the §1.7 markers are discussed only
  as content to relocate (Decision 4: marker → ledger), never as a property
  the no-track-for-minimal branch itself must declare.
- **Stress scenario**: classify this branch against the §1.7(k) opt-out
  criteria.
- **Code evidence**: `conventions.md §1.7(k):1232-1245` — criterion 1
  disqualifies any plan that moves a "resume-state field" or "track-file
  section". This branch moves the resume-state field from plan checkboxes
  to the ledger (Decisions 3 + 6) AND adds a track-file section (Decision
  9). Both independently fail criterion 1. The branch is therefore
  staging-bound (§1.7(b)), not opt-out-eligible — consistent with the
  MEMORY-recorded hidden-research-log gate finding that a SKILL.md-touching
  prose change is staging-bound. The log never states which mode it takes,
  so the design could derive under the wrong assumption (e.g. editing
  precheck.sh + create-plan SKILL live instead of staged).
- **Verdict**: BREAKS (as an unstated invariant). The mode is determined by
  the criteria, not free; the log should record "this branch is
  workflow-modifying (§1.7(b)); it stages" so the design and the §1.7(b)
  `### Constraints` marker derive correctly.

## Findings

### A1 [blocker]
**Certificate**: Assumption test "the three-consumer dissolution map is
complete" + Challenge Decision 2.
**Target**: Decision 2 (minimal drops the plan) / Surprise #1 (machinery
dissolution).
**Challenge**: Surprise #1 enumerates three consumers of the minimal stub
(`determine_state`, `detect_drift`, Phase-2 routing) and concludes nothing
independently needs it. A fourth consumer is missed: `create-plan` Step 1c
(`SKILL.md:131-210`), the tier-aware resume disambiguator, keys on
`implementation-plan.md` presence and routes a minimal resume with no plan
and no `design.md` to the "Neither file exists — fresh start" branch
(`:203-210`), re-running Step 4 (research + tier classifier + this gate)
from scratch. That is the exact resume regression Decision 3's ledger was
introduced to prevent, reappearing through an unsurveyed consumer.
**Evidence**: `SKILL.md:203` reasons "with no `implementation-plan.md` on
disk there is no tier line to read, so the resume correctly reads as a fresh
start" — sound today, broken once minimal legitimately ships without a plan.
The Open-Question ripple list names create-plan edits ("drop minimal stub,
thin templates, add track Constraints, seed ledger") but omits the Step 1c
resume-routing rewrite.
**Proposed fix**: Add a decision (or extend Decision 3) that rewires
`create-plan` Step 1c to disambiguate on the LEDGER (ledger present + tier
line readable from it), not on `implementation-plan.md` presence, and add
"create-plan Step 1c resume routing → ledger" to the Open-Question ripple
list. Until the rewire is decided, the minimal-drop decision is incomplete:
a derived design over it would ship a resume regression.

### A2 [should-fix]
**Certificate**: Assumption test "tier-line consumers are all read-only" +
Challenge Decision 2.
**Target**: Decision 2 (minimal drops the plan) / Open Question #4 (the
five tier-line readers → ledger).
**Challenge**: The ripple list routes "the five tier-line readers → ledger"
as if all five only read. `inline-replanning.md:150-164` shows one of them
is a tier-line WRITER: the ESCALATE tier-upgrade lands "the D18 tier line
rewrite" into `implementation-plan.md`, and a `lite`→`full` upgrade writes a
new design seed alongside it. Under Decision 2 a `minimal`→lite/full upgrade
must materialize the very plan (and, for full, the design) that minimal
dropped — a structural step the log does not carry.
**Evidence**: `inline-replanning.md:151` — "The Phase-2/3A/4 selectors all
read the tier line in `implementation-plan.md`"; `:162` — "A `lite`→`full`
upgrade that also gains a `design.md` writes the new design seed alongside
the tier-line rewrite." The minimal→{lite,full} transition is the
unaddressed case: the destination tier requires artifacts the source tier
no longer has.
**Proposed fix**: Add an Open-Question entry (or a Decision) for the
minimal→lite/full ESCALATE path: the upgrade carrier writes the tier line to
the ledger and CREATES `implementation-plan.md` (and `design.md` for full)
as part of the upgrade, since the minimal source has neither. Note
explicitly that inline-replanning is a writer, not just a reader, so the
"→ ledger" routing for it means "write the upgraded tier to the ledger" plus
"materialize the now-required plan/design".

### A3 [should-fix]
**Certificate**: Assumption test "the branch need not declare its own §1.7
mode" + Challenge Decision 9.
**Target**: Assumption (the branch's §1.7 staging mode) bearing on Decision
4 (§1.7 markers → ledger).
**Challenge**: The log treats the §1.7 markers only as content to relocate
and never states which §1.7 mode the no-track-for-minimal branch itself
takes. The §1.7(k) opt-out criteria settle this deterministically:
criterion 1 disqualifies any plan that moves a resume-state field or a
track-file section. This branch does both — Decisions 3+6 move resume state
to the ledger, Decision 9 adds a track-file section. So the branch is
staging-bound under §1.7(b), not opt-out-eligible. Leaving the mode unstated
risks the design deriving under the wrong assumption (e.g. editing
`workflow-startup-precheck.sh`, `create-plan/SKILL.md`, `conventions.md`,
`planning.md` live rather than under `_workflow/staged-workflow/`).
**Evidence**: `conventions.md §1.7(k)` criterion 1 (`:1232-1245`) — "changes
no `_workflow/**` artifact schema — no track-file section, resume-state
field, drift-gate format, or stamp format moves." Both disqualifiers are
triggered. The MEMORY index records the parallel hidden-research-log finding
that a SKILL.md-touching prose change is staging-bound; this branch is more
clearly staging-bound because it also moves the resume-state field.
**Proposed fix**: Add a Decision Log entry recording "this branch is
workflow-modifying (§1.7(b)) and stages all `.claude/**` edits; it does NOT
qualify for the §1.7(k) opt-out because it moves the resume-state field and
adds a track-file section." This fixes the §1.7(b) `### Constraints` marker
the derived plan must carry and the staged-read precedence every
implementer/reviewer step uses.

### A4 [suggestion]
**Certificate**: Open question "ledger + plan-review-doc stamp status".
**Target**: Open Question #3 (§1.6(f) stamp exclusion additions).
**Challenge**: The ledger half of this open question is effectively
pre-decided and deferrable: an append-only event log that no §1.6(h) walk
enumerates and no phase re-derives is replay-immune by construction, the
exact rationale §1.6(f) already records for `research-log.md` and
`design-mutations.md`. Leaving the ledger unstamped (D19-style) needs no
walk change. The plan-review-doc half is genuinely open and couples to
Finding A5 (whether the doc exists at all).
**Evidence**: `conventions.md §1.6(f):625-655` — the `research-log.md`
exclusion paragraph gives the verbatim rationale the ledger inherits; the
positive stamped enumeration is the four track/plan/design types only, so a
non-enumerated ledger is not walked and not flagged.
**Proposed fix**: Record in the log that the ledger follows the
`research-log.md` precedent (unstamped, not added to the §1.6(h) walk), so
the open question collapses to just the plan-review-doc stamp status —
which A5 may dissolve entirely.

### A5 [suggestion]
**Certificate**: Challenge Decision 7 (Phase-2 audit → new plan-review doc).
**Target**: Decision 7.
**Challenge**: Decision 7 adds a third `_workflow/` artifact type to hold a
fact the log itself calls "rarely read… mostly states the fact". An
unlisted cheaper alternative: carry the review fact + one-line summary as a
ledger event (`review=passed`, within Decision 6's "optional field update"
grammar) and add no new file. This keeps the "one canonical home" principle
without a new artifact whose own sub-questions (filename, per-iteration,
stamp status) the log already flags as open.
**Evidence**: `research-log.md:117` ("rarely read… mostly states the fact");
Decision 6 grammar already supports per-entry field updates. The split
rationale (hot state vs cold record) justifies moving review *state* to the
ledger but not a *new file* for a one-line summary.
**Proposed fix**: Either justify the new doc with the expected summary size
(if large enough to bloat the ledger tail `determine_state` greps), or fold
the summary into a ledger event and drop the plan-review doc. Decision 7
holds if the summary is large; it is unjustified ceremony if it "mostly
states the fact".
