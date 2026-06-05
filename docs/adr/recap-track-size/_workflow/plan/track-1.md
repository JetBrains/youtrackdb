<!-- workflow-sha: 59c7dd338fc472a21ea2bd40876edb7ae96ee13b -->
# Track 1: Two-sided sizing, phase-aware enforcement, design freeze, and design-first authoring

## Purpose / Big Picture
After this track lands, a track is sized as one stacked-diff PR (two-sided
footprint bound + maximize), each size metric is checked at the phase where
it is knowable, `design.md` is frozen after Phase 1, and `/create-plan`
authors the design first in its own reviewed session.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Land all four YTDB-1060 threads as one stacked-diff PR. The threads share
files heavily (the sizing files, the design-doc files, the review prompts),
and applying the maximize directive by hand puts the ~17-file change under the
soft ceiling with no autonomy break, so it is one track, not four. This track
is its own first test case: it is decomposed by the rule it introduces, and
its size is the first calibration data point for the threshold open questions.

## Progress
- [x] 2026-06-05T16:58Z [ctx=info] Review + decomposition complete
- [x] 2026-06-05T17:22Z [ctx=safe] Step 1 complete (commit a3a1be4a77)
- [x] 2026-06-05T17:40Z [ctx=info] Step 2 complete (commit 004c706616)
- [x] 2026-06-05T17:55Z [ctx=info] Step 3 complete (commit da75f3c7d8)
- [x] 2026-06-05T18:19Z [ctx=info] Step 4 complete (commit e758ccba20)
- [x] 2026-06-05T18:19Z [ctx=info] Step implementation
- [ ] Track-level code review
- [ ] Track-level code review
- [ ] Track completion

## Base commit
`d88f667dc8`

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- 2026-06-05T17:55Z Step 3 found a pre-existing reindex lint (rule_8: an
  unstamped §1.2-citing note at the staged `conventions.md:301`, in step 1's
  §Scope indicators region). It is `--write-fixable` and was left untouched as
  out of step-3 scope; the Phase 4 promotion reindex should resolve it. See
  Episodes §Step 3.
- 2026-06-05T18:19Z Step 4 left an open question for Phase C / Phase 4: now that
  the adversarial reviewer runs in the Phase-1 `edit-design` loop, should the
  §1.3 Review Iteration Protocol TOC-row phase set (`2,3A,3B,3C`) widen to
  include phase 1? Left untouched because `edit-design`'s §Step 6 iterate
  protocol is distinct from §1.3's loop. The Phase C consistency /
  instruction-completeness reviewers should evaluate it on the cumulative diff.
  See Episodes §Step 4.
- 2026-06-05T18:19Z Step 4's create-plan two-session split changes the
  create-plan commit cadence: the single "Add initial implementation plan and
  design" commit becomes "Add initial design" (Step 4a) plus "Add initial
  implementation plan" (Step 4b). Any doc that cites the single-commit
  create-plan flow needs reconciling at Phase 4. See Episodes §Step 4.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

**DL1 (Phase A, review-driven).** The technical/risk/adversarial reviews
found the plan's Goals and D5 frame the file-footprint ceiling as net-new,
but `step-size-recap` already landed a `~20-25` ceiling on this branch (A1,
R1). Decision: handle this as decomposition guidance, not a plan edit and not
ESCALATE. The seven Decision Records all survive adversarial challenge, so no
decision changes; the sizing edits consolidate-and-extend the existing rule
(captured in §Context and Orientation), and the Goals/D5 prose-staleness is
deferred to the Phase 4 `design-final.md` reconciliation. Editing immutable
plan Decision Records mid-execution would itself require ESCALATE, which is
disproportionate for a rationale-precision gap the reviews unanimously called
decomposition-addressable.

**DL2 (Phase A, review-driven).** The freeze (Thread 3) touches three
inline-replan→`design.md`-mutation trigger sites, not one
(`design-document-rules.md`, `inline-replanning.md`, `edit-design/SKILL.md:59`
— R2, A5), plus a multi-site `3A,3C` annotation sweep and an
`implementer-rules.md:73`/`:89` reconciliation. The in-scope file count stays
~17 (no new files); the per-file edit descriptions in §Interfaces and
Dependencies and §Plan of Work arc 3 are expanded to name every site so
Phase B does not leave a dependent prompt contradicting the freeze.

**DL3 (Phase B, dependency-reveal).** Step 4's design-scoped role/phase
addition (Thread 4) rippled into a file the §Interfaces table listed only for
threads 1 and 3: extending `reviewer-adversarial` to Phase 1 made the
`conventions.md` §1.8(a) role-enum gloss stale, so step 4 also edited that one
gloss line (review finding M2). The §Interfaces row for `conventions.md`
predates this reveal and still reads "threads 1, 3"; the authoritative
execution record is the §Episodes Step 4 Key-files list, which names the
§1.8(a) edit. No design or threshold change; the file-set expansion is one line
keeping the closed-enum gloss consistent with the role change arc 4 makes. See
Episodes §Step 4.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (3 findings, 3 accepted; 0 blockers). T1
  (the §1.1 Phase-enum-row twin of the §1.8 edit-design span), T2 (surgical
  one-clause §1.8(b) narrowing + the conflated `design-document-rules.md`
  Direct-mutation example), T3 (narrow the §Mutation-discipline phase
  annotations, not just prose). All folded into §Context and Orientation,
  §Plan of Work arc 3, and §Interfaces. All 17 file paths, all 12 sync-list
  line numbers, and the D6 contradiction were verified accurate.
- [x] Risk: PASS at iteration 1 (5 findings, 5 accepted; 0 blockers). R1
  (`structural-review.md:174` is a step-size-recap reconciliation block, fold
  it), R2 (`edit-design/SKILL.md:59` third trigger site), R3 (narrow
  `edit-design` annotations alongside `design-document-rules.md`), R4
  (acceptance-grep tightened to name expected residuals), R5
  (`inline-replanning.md` full-block excision, not one clause). The
  design-freeze recovery path was verified non-dead-ending.
- [x] Adversarial: PASS at iteration 1 (5 findings, 5 accepted; 0 blockers).
  All seven Decision Records survive. A1 (consolidate the pre-existing
  `~20-25` ceiling, not introduce) and A5 (arc-3 multi-site sweep +
  `implementer-rules.md:73`) folded as scope precision; A3 (consistency note
  covers section-mechanism divergence) applied to acceptance. A2 (soften D2
  "no more" rationale) and A4 (state the zero-hard-gates posture + the
  size-escalation loosening in the D2/D3 rationale) target immutable plan
  Decision-Record prose, so they are deferred to the Phase 4 `design-final.md`
  reconciliation per DL1; A4's track-acceptance half is applied to
  §Validation.

## Context and Orientation

The workflow's only track-sizing rule today is the one-sided "more than
~5-7 steps, split", stated in step count. Mining the 42 committed tracks
under `docs/adr/` shows it never bound anything (min 1 step, median 3, max
5); file footprint is the dimension that goes unbounded. The rule is
duplicated across **12 positions**, five of which are review prompts that
enforce it on reviewers (see the sync-list in §Interfaces and Dependencies).

Current-state correction (Phase A, from the technical/risk/adversarial
reviews): a two-sided footprint check is already partly live on this branch.
After that mining, `step-size-recap` (YTDB-1062/1068) landed a track-level
`~20-25` in-scope-files ceiling in `conventions.md` (§Scope indicators,
lines ~280-301, the `~20-25`-ceiling-vs-per-step-`~12`-cap distinction) and
in `structural-review.md` (the plan-file sizing check at lines ~127-135 and
the prose checklist at ~171-177), each worded to coexist with `~5-7` ("the
`~5-7` steps track-sizing rule still guides planning; step count is not
knowable from the file-footprint indicator at plan time"). The `~20-25`
there is the same split-candidate threshold `design.md` names, so the
*top* of the footprint range is already bounded; what is still missing is
the floor, the maximize directive, the `>~40` overblown tier, the
argumentation gate, and the phase-aware Phase B/C checks. Every sizing edit
therefore **consolidates and extends** the existing rule and retires `~5-7`
only as the *sizing metric* — it reads `conventions.md`'s footprint prose and
the `structural-review.md` sizing spots as current state and folds them into
one coherent rule rather than editing them line-by-line against the
sync-list, so no duplicate or contradictory ceiling survives. (The plan's
Goals and D5 still frame the ceiling as net-new; that prose-staleness is
deferred to the Phase 4 `design-final.md` reconciliation per DL1 below —
the decisions themselves are unaffected.)

The `design.md` freeze (Thread 3) touches more sites than the
`design-document-rules.md` mutation trigger alone. The Phase-3
inline-replan→`design.md`-mutation path is wired in **three** places, not
one: `design-document-rules.md` (the §Mutation discipline trigger plus the
Direct-mutation example that cites a "Phase 3 inline-replanning bullet
add"), `inline-replanning.md` (its design-coherence / working-vs-direct-
mutation / `edit-design`-invocation blocks), and `edit-design/SKILL.md:59`
(the MUST-use-this-skill list entry "inline replanning during Phase 3
ESCALATE"). All three carry `3A,3C` phase annotations that go stale once the
trigger is removed, so the freeze narrows the annotations across each file's
TOC rows and section comments, not just the prose. Separately,
`implementer-rules.md` (`:73` "only if the step requires it", `:89` "read on
demand only") must be reconciled with the new frozen-design guard: the
implementer never *resolves a decision* from frozen `design.md`, but may
still read it for context. The `conventions.md` §1.8(b) sentence bundles
three clauses (ESCALATE/inline-replanning `3A,3C`; review-mode `3A,3C`;
`edit-design` mutations `1,3A,3C,4`) — the freeze narrows **only** the
`edit-design` clause and its §1.1 Phase-enum-row twin (`conventions.md:84`),
leaving the ESCALATE/inline-replanning and review-mode `3A,3C` clauses
verbatim, because inline replanning itself still runs in Phase 3 — only its
*design-mutation* intent is rerouted to the Decision Records and the track
narrative.

`design.md` carries a live self-contradiction: `design-document-rules.md`
lists inline replanning as a `design.md` mutation trigger in its §Mutation
discipline enumeration, while Rule 15 in §Rules states `design.md` is "never
modified after planning". No Phase 3A or 3C reviewer
receives `design.md`, and the plan's immutable Decision Records are already
the de-facto source of truth during execution. `create-plan` Step 4 authors
Architecture Notes and the track checklist first and writes `design.md` last
(sub-step 8), so the design back-fills the plan rather than seeding it.

The Phase 2 structural reviewer classifies findings into exactly two classes
— `mechanical` (orchestrator auto-fixes) and `design-decision` (escalate) —
with no advisory tier; track sizing is already a `design-decision`. The
branch is workflow-modifying, so all edits stage under
`_workflow/staged-workflow/.claude/...` and promote at Phase 4.

Concrete deliverables: the retired metric replaced everywhere by the
two-sided cap; the maximize directive in `planning.md`; the file-based floor
and footprint ceiling with the argumentation gate; the Phase B running-diff
early-warning and the Phase C review-burden check; the `design.md` freeze
(mutation paths removed, replan intent rerouted); and the design-first
`create-plan` reorder with the adversarial-then-cold-read `edit-design`
ordering.

## Plan of Work

The edits cluster into four arcs that share files, so they land in dependency
order and later edits read earlier edits' staged copies:

1. **Sizing definition.** Reframe the Track glossary entry and the §1.2
   planning rule in `conventions.md`; write the maximize directive, the
   two-sided bound, the floor, and the argumentation rule into `planning.md`
   §Track descriptions; mirror the decomposition guidance in `track-review.md`
   §Step Decomposition and the `create-plan/SKILL.md` Step 4 sizing rule.
   Retire "~5-7 steps" as the sizing metric (the step *definition* in
   `conventions.md:70` is unchanged).
2. **Enforcement propagation.** Move the five review prompts (`structural`
   ×3 spots, `technical`, `adversarial`, `risk`, `consistency`) to the
   two-sided cap and add a sync-list anchor so the set cannot drift apart.
   Add the Phase B running diff-stat early-warning to the step loop and the
   Phase C review-burden line check to `track-code-review.md`.
3. **Design freeze.** Remove the inline-replan `design.md`-mutation trigger
   at all three sites named in §Context and Orientation: the
   `design-document-rules.md` §Mutation discipline trigger and Direct-mutation
   "Phase 3 inline-replanning bullet add" example; the `inline-replanning.md`
   design-coherence / working-vs-direct-mutation / `edit-design`-invocation
   blocks (excise the blocks, do not just drop one clause), replaced by a
   short clause routing design intent to the DR-revision format and the track
   narrative; and the `edit-design/SKILL.md:59` MUST-use-this-skill entry.
   Narrow the now-stale `3A,3C` phase annotations across each file's TOC rows
   and section comments (not just prose) so the TOC reader-filter stops
   routing Phase-3 agents into the removed discipline; leave Phase-4
   (`phase4-creation`) annotations intact. Narrow only the `edit-design`
   clause of the three-clause `conventions.md` §1.8(b) sentence and its §1.1
   Phase-enum-row twin (`:84`), leaving the ESCALATE/inline-replanning and
   review-mode `3A,3C` clauses verbatim. Add the implementer's frozen-design
   guard in `implementer-rules.md` and reconcile its `:73`/`:89` read-on-demand
   lines with the guard (read for context, never resolve a decision from
   frozen `design.md`). Add the divergence-is-expected note to
   `consistency-review.md`, worded broadly enough to cover a DR that diverges
   from a frozen design *section's mechanism*, not only from a `Full design:`
   link.
4. **Design-first reorder.** Reorder `create-plan/SKILL.md` Step 4 to author
   the design first, add the design→plan session boundary and auto-resume
   condition; re-frame `planning.md` §Goal and §Design Document; list the
   design/plan sub-phases in `workflow.md`; add the design-scoped role/phase
   to `adversarial-review.md`; note the cold-read-after-adversarial order in
   `design-review.md`; insert the adversarial step into the `edit-design`
   `phase1-creation` loop.

Ordering constraints: arc 1 establishes the vocabulary the review prompts in
arc 2 cite, so arc 1 precedes arc 2. Arcs 3 and 4 compose (Thread 3 freezes,
Thread 4 moves the freeze point earlier); the design-first reorder must read
the frozen-design rules, so arc 3 precedes arc 4. Invariants to preserve: the
step definition is unchanged; the `§1.7(b)` marker stays in the plan's
Constraints; no whole-file deletions (promotion is additive-only).

**Per-step sequencing (Phase A).** The four arcs map one-to-one to the four
`## Concrete Steps`: step 1 = arc 1 (sizing definition), step 2 = arc 2
(enforcement propagation), step 3 = arc 3 (design freeze), step 4 = arc 4
(design-first reorder). All four are `high` (workflow machinery) and run in
the strict linear order 1→2→3→4. The order is forced by shared-file staged
reads, not just the arc1→2 / arc3→4 logical dependencies: `conventions.md`
§1.1 is touched by step 1 (Track row) and step 3 (Phase-enum row `:84`);
`consistency-review.md` by step 2 (sizing `:70`) and step 3 (divergence note);
`adversarial-review.md` by step 2 (sizing) and step 4 (design-scoped
role/phase); `edit-design/SKILL.md` by step 3 (`:59` + annotations) and step 4
(`phase1-creation`); `create-plan/SKILL.md` by step 1 (Step-4 sizing rule) and
step 4 (Step-4 reorder); `planning.md` by step 1 (§Track descriptions) and
step 4 (§Goal/§Design Document). Each later step reads the earlier step's
staged copy under `_workflow/staged-workflow/.claude/...` per §1.7(d). No two
steps are parallel (every pair shares at least one file or a logical
dependency).

## Concrete Steps

1. Sizing definition (arc 1, D1/D2/D3): write the two-sided footprint rule, the maximize directive, the file-based floor (≤~12), and the argumentation gate across `conventions.md` §1.1 Track row + §1.2 planning rule, `planning.md` §Track descriptions, `track-review.md` §Step Decomposition, and `create-plan/SKILL.md` Step-4 sizing rule; consolidate the existing `~20-25` ceiling prose into the new rule; retire `~5-7` as the *sizing metric* (step definition at `conventions.md:70` unchanged). — risk: high (workflow machinery: edits the §1.1 closed glossary term and the sizing gate the Phase 2 classifier keys on)  [x] commit: a3a1be4a77
2. Enforcement propagation (arc 2, D4/D5): move the five review prompts (`structural` ×3 spots, `technical`, `adversarial`, `risk`, `consistency`) to the two-sided cap and add the sync-list anchor, folding the `structural-review.md` two-tier sizing block into the new rule rather than nulling lines; add the Phase B running `git diff base..HEAD --stat` early-warning to `step-implementation.md` and the Phase C review-burden line check (>~2,000 / >~4,000 total +/-, generated excluded, test kept) to `track-code-review.md`. — risk: high (workflow machinery: edits the Phase 2 sizing gate prompt and adds enforcement to the Phase B/C control-flow protocols)  [x] commit: 004c706616
3. Design freeze (arc 3, D6): remove the inline-replan `design.md`-mutation trigger at all three sites (`design-document-rules.md` §Mutation discipline + Direct-mutation example; `inline-replanning.md` design-coherence/working-vs-direct/invocation blocks, replaced by a short DR-revision routing clause; `edit-design/SKILL.md:59` MUST-use entry); narrow the stale `3A,3C` annotations across each file's TOC rows + section comments (keep Phase 4); narrow only the `edit-design` clause of `conventions.md` §1.8(b) and its §1.1 Phase-enum-row twin (`:84`); add the `implementer-rules.md` frozen-design guard and reconcile `:73`/`:89`; add the divergence-is-expected note (covering section-mechanism divergence, not only links) to `consistency-review.md`. — risk: high (workflow machinery: edits the §1.8 phase-enum schema and the inline-replan control-flow protocol)  [x] commit: da75f3c7d8
4. Design-first reorder (arc 4, D7): reorder `create-plan/SKILL.md` Step 4 to author `design.md` first, add the design→plan session boundary and the auto-resume condition (design.md exists, implementation-plan.md absent); re-frame `planning.md` §Goal and §Design Document; list the design/plan sub-phases in `workflow.md`; add the design-scoped role/phase to `adversarial-review.md`; note cold-read-after-adversarial in `design-review.md`; insert the adversarial step before cold-read in the `edit-design/SKILL.md` `phase1-creation` loop. — risk: high (workflow machinery: reorders the create-plan control flow / state machine and edits the §1.8 role/phase schema)  [x] commit: e758ccba20

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per completed step,
identified by step number + commit SHA. Empty at Phase 1. -->

### Step 1 — commit a3a1be4a77, 2026-06-05T17:22Z [ctx=safe]
**What was done:** Authored arc 1 (sizing definition) by first-touch-copying
the four arc-1 live files into the staged tree and editing the staged copies.
Retired "~5-7 steps" as the track-sizing metric in all five arc-1 enforcement
positions (`conventions.md` §1.1 Track row + §1.2 planning rule, `planning.md`
§Track descriptions, `track-review.md` §Step Decomposition,
`create-plan/SKILL.md` Step 4) and replaced it with the two-sided file-footprint
rule: the maximize directive stated before any clamp, the ≤~12-file
merge-candidate floor, the >~20-25-file split-candidate ceiling, and the
argumentation gate. The full rule lives once in `planning.md` §Track
descriptions; the other four positions point there. Consolidated the
pre-existing `~20-25` ceiling prose in `conventions.md` §Scope indicators into
the new rule so no duplicate ceiling survives; the step definition at
`conventions.md:70` is unchanged. Primary commit `713d7de51a`; review-fix
commit `a3a1be4a77` applied finding M1.

**What was discovered:** The `conventions.md` §Scope indicators intro still
called the per-step `~12` a "split cap," while the live `track-review.md`
machinery (from `step-size-jsutification`) had reframed `~12` as a "fill
target." The consolidated purpose-1 bullet uses "fill target," so the intro was
aligned to match — a coherence fix inside the consolidated section, not the
broader `~12`-terminology cleanup, which is a separate-branch concern.

**What changed from the plan:** No design change; arc 1 landed as planned, with
the `>~40` overblown tier and the Phase B/C line-burden checks deferred to step
2 per the arc split. The dimensional review (prompt-design) raised finding M1:
the argumentation gate's middle trigger ("below the maximize target") had no
observable anchor, so a decomposer could not reproducibly decide when a
mid-range track owes a justification. The fix reworded the trigger to fire on
"a mergeable autonomous unit left unpacked," preserving D3's two-sided
argumentation and the `~12`/`~20-25` thresholds. Step 2's review-prompt
propagation should cite the gate's observable wording, not "below the maximize
target," to keep the enforcement copies reproducible.

**Key files:**
- `…/staged-workflow/.claude/workflow/conventions.md` (new — staged)
- `…/staged-workflow/.claude/workflow/planning.md` (new — staged)
- `…/staged-workflow/.claude/workflow/track-review.md` (new — staged)
- `…/staged-workflow/.claude/skills/create-plan/SKILL.md` (new — staged)

### Step 2 — commit 004c706616, 2026-06-05T17:40Z [ctx=info]
**What was done:** Authored arc 2 (enforcement propagation) by
first-touch-copying seven live files into the staged tree. Moved the five
Phase 2 review prompts (`structural` ×3 spots, `technical`, `adversarial`,
`risk`, `consistency`) off the retired "~5-7 steps" ceiling onto the two-sided
file-footprint cap, each Track-terminology bullet citing the authoritative
`planning.md` §Track descriptions rule, and added a sync-list anchor comment in
`structural-review.md` binding the rule-citing positions. Folded the
`step-size-recap` two-tier sizing block: the SCOPE INDICATORS plausibility check
now defers to a single TRACK SIZING check (no duplicate ceiling survives) and
the `design-decision` trigger fires only on an undocumented out-of-bounds track.
Added the flag-only Phase B running diff-stat early-warning and the flag-only
Phase C review-burden line check (>~2,000 / >~4,000 total +/-, generated
excluded, test kept). Primary commit `c2c648672e`; review-fix `004c706616`.

**What was discovered:** `step-implementation.md` labels its loop "sub-steps
4–7" / "1–8" in six places, so a new "sub-step 6.5" would break the integer
enumeration; the early-warning was folded into sub-step 6 as a second always-run
check instead of minting a new number. Separately, the "page through with
`offset`/`limit` for diffs over 2,000 lines" instruction already lives in the
`track-code-review.md` `## Input` contract and every review-agent spec — which
is why 2,000 is the review-burden threshold (the `Read`-tool truncation
boundary).

**What changed from the plan:** No design change. The dimensional review raised
M1 (the Phase C review-burden check had dropped Decision Record D4's
">~2,000 → page the diff" action, mapping 2,000 to a bare "record") and M2 (the
`structural-review.md` `~12` was ambiguous between the per-step fill target and
the track-level floor). M1 was a should-fix bringing the check into conformance
with the immutable D4 — fixed without touching D4 or `design.md`; M2 a one-line
clarity qualification. The only structural nuance is the early-warning's
placement as a sub-step 6 continuation rather than a new numbered sub-step.

**Key files:**
- `…/staged-workflow/.claude/workflow/prompts/structural-review.md` (new — staged)
- `…/staged-workflow/.claude/workflow/prompts/technical-review.md` (new — staged)
- `…/staged-workflow/.claude/workflow/prompts/adversarial-review.md` (new — staged)
- `…/staged-workflow/.claude/workflow/prompts/risk-review.md` (new — staged)
- `…/staged-workflow/.claude/workflow/prompts/consistency-review.md` (new — staged)
- `…/staged-workflow/.claude/workflow/step-implementation.md` (new — staged)
- `…/staged-workflow/.claude/workflow/track-code-review.md` (new — staged)

### Step 3 — commit da75f3c7d8, 2026-06-05T17:55Z [ctx=info]
**What was done:** Authored arc 3 (design freeze, D6). Removed the Phase-3
inline-replan→`design.md`-mutation path at all three sites:
`design-document-rules.md` dropped the §Mutation-discipline trigger bullet
(replaced with a frozen-after-Phase-1 statement routing to the Decision Records
+ track narrative) and rewrote the Direct-mutation example from a Phase-3 case
to a Phase-1 iteration case; `inline-replanning.md` excised the design-coherence
/ working-vs-direct-mutation / `edit-design`-invocation blocks, replacing them
with a clause routing replan design intent to the revised Decision Record + the
track narrative; `edit-design/SKILL.md:59` struck the "inline replanning during
Phase 3 ESCALATE" MUST-use entry. Narrowed every now-stale `3A,3C`
design-mutation annotation to `1,4` (Step 8 to `1`) across TOC rows, section
comments, and cross-file ref suffixes, keeping `phase4-creation` /
`phase1-creation` intact. Narrowed only the `edit-design` clause of
`conventions.md` §1.8(b) and its §1.1 Phase-enum-row twin (`:84`), leaving the
ESCALATE/inline-replanning and review-mode `3A,3C` clauses verbatim. Added the
`implementer-rules.md` frozen-design guard (read for context, return
`DESIGN_DECISION_NEEDED` rather than resolve a decision from frozen `design.md`)
and reconciled `:73`/`:89`. Added the divergence-is-expected note to
`consistency-review.md` § DESIGN↔PLAN, covering both a stale `Full design` link
and a DR diverging from a frozen section's mechanism.

**What was discovered:** The `edit-design/SKILL.md` cross-refs to
`conventions.md` §1.6 (stamp discipline) correctly stay `1,3A,3C,4` — §1.6's
phase set is unaffected by the freeze, so narrowing them would make the suffix
an invalid subset. A pre-existing reindex lint (rule_8, an unstamped
§1.2-citing note at the staged `conventions.md:301`, inside step 1's §Scope
indicators region) is `--write-fixable` and out of this step's scope; the Phase
4 promotion reindex resolves it.

**What changed from the plan:** No design change; arc 3 landed as planned, and
the freeze closes the live self-contradiction (the §Mutation-discipline trigger
versus Rule 15). For step 4: `edit-design/SKILL.md` and `conventions.md` §1.1
are now staged with this step's edits, so step 4 reads the staged copies and
layers its `phase1-creation` adversarial step and §1.1 edits on top, leaving the
§1.8(b) ESCALATE/inline-replanning and review-mode `3A,3C` clauses untouched.

**Key files:**
- `…/staged-workflow/.claude/workflow/design-document-rules.md` (new — staged)
- `…/staged-workflow/.claude/workflow/inline-replanning.md` (new — staged)
- `…/staged-workflow/.claude/workflow/implementer-rules.md` (new — staged)
- `…/staged-workflow/.claude/skills/edit-design/SKILL.md` (new — staged)
- `…/staged-workflow/.claude/workflow/conventions.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/consistency-review.md` (modified — staged)

### Step 4 — commit e758ccba20, 2026-06-05T18:19Z [ctx=info]
**What was done:** Authored arc 4 (design-first reorder, D7) across all seven
in-scope files, layering on the frozen-design state arc 3 left.
`create-plan/SKILL.md` Step 4 split into Step 4a (author `design.md` via
`edit-design` `phase1-creation`, freeze on review pass, end session) and Step 4b
(derive the plan from the frozen design in a fresh session), with a new Step 1c
resume check wiring the auto-resume condition. `planning.md` re-framed §Goal and
§Design Document; `workflow.md` listed the 4a/4b sub-phases with the
session-boundary contract; `adversarial-review.md` gained a design-scoped
role/phase; `design-review.md` noted cold-read-after-adversarial;
`edit-design/SKILL.md` inserted Step 3.5 (adversarial sub-agent,
`phase1-creation` only) before cold-read; `design-document-rules.md` pinned the
adversarial-then-cold-read ordering. Primary commit `a0777fec57`; review-fix
`e758ccba20` applied M1/M2/M3.

**What was discovered:** The role/phase enums are closed, so the design-scoped
adversarial slot reuses `reviewer-adversarial` at phase 1 with no new token. The
Step 1c auto-resume had to gate on a committed-and-clean `design.md`, not bare
file presence: `edit-design` writes `design.md` before its review passes, so an
interrupted Step 4a would otherwise derive a plan from an unreviewed design
(review finding M1). An open question stays for Phase C / Phase 4: whether the
§1.3 Review Iteration Protocol TOC-row phase set (`2,3A,3B,3C`) should widen to
include phase 1 now that the adversarial reviewer runs in the Phase-1
`edit-design` loop. It was left untouched because `edit-design`'s §Step 6 has
its own iterate protocol distinct from §1.3.

**What changed from the plan:** No design change. Two scope additions surfaced
during execution and review, both mechanical and within arc-4 intent: a new
Step 1c control-flow gate to enforce the auto-resume condition (the design
described the behavior but gave no enforcement point), and a `conventions.md`
§1.8(a) `reviewer-adversarial` gloss update to the dual-phase scope (review
finding M2, a ripple of the design-scoped-role change into a file the
§Interfaces table listed only for threads 1 and 3). For Phase C / Phase 4: the
create-plan two-session split replaces the single
"Add initial implementation plan and design" commit with "Add initial design"
(4a) plus "Add initial implementation plan" (4b); any doc citing the
single-commit flow should reconcile.

**Key files:**
- `…/staged-workflow/.claude/skills/create-plan/SKILL.md` (modified — staged)
- `…/staged-workflow/.claude/skills/edit-design/SKILL.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/design-document-rules.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/planning.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/adversarial-review.md` (modified — staged)
- `…/staged-workflow/.claude/workflow/prompts/design-review.md` (new — staged)
- `…/staged-workflow/.claude/workflow/workflow.md` (new — staged)
- `…/staged-workflow/.claude/workflow/conventions.md` (modified — staged; M2 §1.8(a) gloss)

## Validation and Acceptance

Track-level behavioral acceptance criteria:

- The string "~5-7 steps" (and the one-sided step ceiling it states) appears
  in zero *enforcement* positions: no planning rule, no glossary sizing
  metric, no review-prompt check. The only places the token or concept may
  survive are the step *definition* line (`conventions.md:70`, unchanged) and
  the reconciled two-tier `structural-review.md` sizing block, and each must
  read coherently with the new two-sided cap. A reviewer running a naive
  `grep -c '~5-7'` and expecting zero would mis-flag these expected residuals,
  so the check is "zero *enforcement* occurrences", not "zero occurrences".
- `planning.md` states the maximize directive — "extend the track to the
  bound, split only when forced" — before any split clamp, and the sizing
  prose names files and steps, never a line count.
- A track outside the bounds (under-floor, below the maximize target, or
  over the ceiling) that carries no argumentation block is a `design-decision`
  finding at Phase 2; a documented one passes without escalation.
- The floor is file-based (≤~12 files) and flag-only; nothing auto-merges
  tracks.
- The Phase B step loop reads a running `git diff base..HEAD --stat`; the
  Phase C code review checks review burden at >~2,000 / >~4,000 total +/-
  lines (generated excluded, test kept).
- `design-document-rules.md` lists no Phase 3 `design.md` mutation trigger,
  and Rule 15's freeze no longer self-contradicts; the same trigger is gone
  from `inline-replanning.md` and `edit-design/SKILL.md:59`; no surviving
  `3A,3C` annotation routes a Phase-3 agent into the removed discipline; an
  inline replan routes design intent into the Decision Records and the track
  narrative. `implementer-rules.md` reads frozen `design.md` for context only
  and never resolves a decision from it.
- `consistency-review.md` § DESIGN↔PLAN tells the re-run reviewer that a
  revised-DR-vs-frozen-design divergence is expected, not a finding, including
  a DR that diverges from a frozen design *section's mechanism*, not only from
  a `Full design:` link.
- The change adds no hard gates: the floor, the ceiling, and the Phase B
  running-diff are all advisory (flag-only / orchestrator judgment). A
  *documented* oversize track no longer escalates on size alone, a deliberate
  loosening from the prior step-count escalation that the autonomous Phase 2's
  binary `mechanical | design-decision` classifier (no quality tier) makes
  unavoidable.
- `create-plan/SKILL.md` authors `design.md` before the Architecture Notes
  and track checklist, and auto-resumes plan derivation when `design.md`
  exists and `implementation-plan.md` does not; the `edit-design`
  `phase1-creation` loop runs adversarial before cold-read.

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used verbatim as
test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Every step is a prose/staging edit to `.claude/**` files mirrored under
`_workflow/staged-workflow/`, with no runtime state, migration, or data
change. Per-step recovery is therefore a clean `git revert` (or `git reset
--hard HEAD` before the commit lands) of that step's single commit; no
fixups, replays, or cleanup are needed, and a reverted step leaves the live
`.claude/**` tree untouched (the I6 invariant holds until the Phase 4
promotion). Re-running a step is idempotent: the staged-copy-then-edit on
first touch (§1.7(e)) re-copies the live file only if no staged copy exists,
so a re-attempt edits the already-staged copy rather than re-seeding it.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't belong
to one specific step. Often empty. -->

## Interfaces and Dependencies

**In-scope files (~17, all under §1.7 staging):**

| File | Threads | Edit |
|---|---|---|
| `conventions.md` | 1, 3 | Track glossary §1.1 (Track row + Phase-enum row `:84`), §1.2 planning rule, §1.8(b) edit-design phase span (narrow only the edit-design clause); consolidate the existing `~20-25` ceiling prose (§Scope indicators) into the two-sided rule |
| `planning.md` | 1, 2, 4 | maximize + two-sided bound; line-free sizing prose; §Goal + §Design Document re-frame |
| `track-review.md` | 1 | §Step Decomposition track-sizing guidance |
| `create-plan/SKILL.md` | 1, 4 | Step 4 sizing rule; design-first reorder + session boundary + auto-resume |
| `prompts/structural-review.md` | 1 | track-sizing terminology + finding template (3 spots) → two-sided cap |
| `prompts/technical-review.md` | 1 | sizing guidance → two-sided cap |
| `prompts/adversarial-review.md` | 1, 4 | sizing guidance; design-scoped role/phase |
| `prompts/risk-review.md` | 1 | sizing guidance → two-sided cap |
| `prompts/consistency-review.md` | 1, 3 | track-definition sizing; divergence-is-expected note |
| `step-implementation.md` | 2 | Phase B running diff-stat early-warning |
| `track-code-review.md` | 2 | Phase C review-burden line check |
| `design-document-rules.md` | 3, 4 | remove mutation trigger + narrow annotations; pin adversarial-then-cold-read ordering |
| `inline-replanning.md` | 3 | excise the design-coherence / working-vs-direct-mutation / `edit-design`-invocation blocks (not just one clause); replace with a short clause routing design intent to the DR-revision format + track narrative |
| `implementer-rules.md` | 3 | frozen-design guard (escalate `DESIGN_DECISION_NEEDED`); reconcile `:73`/`:89` read-on-demand with the guard |
| `edit-design/SKILL.md` | 3, 4 | insert adversarial step before cold-read for `phase1-creation`; strike inline-replan-ESCALATE from the MUST-use list (`:59`); narrow stale `3A,3C` mutation annotations |
| `workflow.md` | 4 | list design/plan sub-phases with the A/B/C session-boundary contract |
| `prompts/design-review.md` | 4 | note cold-read runs after the adversarial pass |

**Sync-list — the 12 "~5-7 steps" occurrences to update (re-verified on this
branch):** `planning.md:424`, `conventions.md:69`, `conventions.md:228`,
`track-review.md:702`, `create-plan/SKILL.md:207`, `structural-review.md:58`,
`:174`, `:370`, `technical-review.md:45`, `adversarial-review.md:46`,
`risk-review.md:45`, `consistency-review.md:70`. Phase A confirmed all 12
line numbers accurate on this branch (none drifted; the count of 12 is
correct, and the step *definition* at `conventions.md:70` is excluded). Two
of these are not plain occurrences and must be reconciled, not deleted:
`structural-review.md:174` and the sizing check at `structural-review.md:127-135`
already pair `~5-7` with the `~20-25` ceiling (the step-size-recap two-tier
block) — fold the whole block into the new two-sided rule rather than
nulling the `~5-7` line in isolation, or a duplicate ceiling survives.

**Out of scope:** the semantic-staleness lint for `Full design:` links
(YTDB-1079); re-implementing the design-first reorder in YTDB-975 (corrected
on a separate branch); pinning final threshold values.

**Dependencies:** none — single track. Internal ordering (arc 1 → 2, arc 3 →
4) is handled by staged-first reads within the plan; the design-doc anchors
were verified against current `develop` during Phase 0 and may shift under
rebase before Phase 4 promotion.
