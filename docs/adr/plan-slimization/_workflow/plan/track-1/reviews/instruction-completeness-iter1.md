<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
<!--MANIFEST
review_type: instruction-completeness
track: 1
iteration: 1
findings: 4
index:
  - id: WI1
    sev: should-fix
    anchor: "#wi1-should-fix-phase-01-adversarial-gate-loop-has-no-max-iteration-cap"
    loc: "staged create-plan/SKILL.md:397-410, 1026-1052"
    cert: C1
    basis: judgment
  - id: WI2
    sev: should-fix
    anchor: "#wi2-should-fix-escape-hatch-cold-read-has-no-loop-back-complement"
    loc: "staged create-plan/SKILL.md:1054-1062"
    cert: C2
    basis: judgment
  - id: WI3
    sev: suggestion
    anchor: "#wi3-suggestion-open-questions-consumed-by-the-gate-with-no-handling-rule"
    loc: "staged adversarial-review.md:136,1653-1684; staged research.md:71"
    cert: C3
    basis: judgment
  - id: WI4
    sev: suggestion
    anchor: "#wi4-suggestion-step-4b-cold-read-loop-imports-its-budget-by-prose-reference-only"
    loc: "staged create-plan/SKILL.md:655-656"
    cert: C4
    basis: judgment
evidence_base: 4
cert_index: [C1, C2, C3, C4]
flags: []
-->

## Findings

### WI1 [should-fix] Phase-0→1 adversarial gate loop has no max-iteration cap

- **File**: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 397-410, and the D15 batch gate-run at 1026-1037)
- **Axis**: loop termination
- **Cost**: a gate that cannot clear (a decision the reviewer keeps blocking, or a should-fix the rationale never satisfies) loops the orchestrator forever — re-spawning the adversarial reviewer at full D14 model cost each iteration with no exit and no escalation to the user.
- **Issue**: Step 4 part 2's gate semantics say "the gate **loops** — re-spawn the reviewer (incrementing `<N>`) ... until no blocker remains" (line 400) and "a `should-fix` **gates**: the log's rationale must strengthen before the gate clears" (line 406). No maximum iteration count and no budget-exhausted recovery is defined. The D15 batch's step-1 gate run inherits the same unbounded loop: "the gate re-runs whole-batch until no blocker remains and every should-fix has been addressed" (lines 1033-1035). This contrasts sharply with the loop this gate is modeled on: the sibling `edit-design` cold-read iterate loop carries an explicit `iteration_budget` (default 3, `edit-design/SKILL.md:108`) and a defined "Budget exhausted with blockers remaining" / "...with only should-fix remaining" recovery (`edit-design/SKILL.md:569-574`). The Phase-0→1 gate is a hand-rolled `Agent`-tool re-spawn loop in `create-plan`, *not* routed through `edit-design`, so it does not inherit that cap. A research-log decision the adversarial reviewer keeps blocking has no terminal state.
- **Suggestion**: give the gate loop the same bounded contract as `edit-design`: cap the re-spawn count (reuse the default-3 `iteration_budget` value for symmetry), and on exhaustion escalate to the user as the gate (mirroring `edit-design/SKILL.md` § Failure modes "the user is the gate when the budget is exhausted") rather than looping. State it once in the Step 4 part 2 gate-semantics block and have the D15 step-1 batch run cite it.

### WI2 [should-fix] Escape-hatch single-decision route has no cold-read loop-back complement

- **File**: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 1054-1062)
- **Axis**: error and recovery path
- **Cost**: an escape-hatched finding whose terminal cold-read surfaces a *new* decision-shaped finding lands in an undefined state — the route has run "one cold-read" and ended, but a `[decision]`-shaped cold-read finding now exists with no defined home, and a later escape-hatch or review-done batch could reach a cold-read with that log entry still open, violating S3.
- **Issue**: The three-step *batch* explicitly closes its loop: step 3 says "A decision-shaped cold-read finding **re-enters the gate step** (step 1) and the batch cannot close ... while a log entry is open" (lines 1046-1047). The escape hatch reuses the same machinery but describes it as strictly singular: "runs the single-decision route (one gate run, one mutation, one cold-read for the lone finding) ... The processed finding is then dropped from the in-session queue" (lines 1055-1060). There is no complement for the case where that one cold-read produces a new decision-shaped finding — the batch's "re-enters the gate step" loop-back is not restated for the escape-hatch route, and the route terminates after one cold-read. The complement (cold-read clean → drop) is handled; the complement (cold-read surfaces a decision → ?) is not.
- **Suggestion**: state that the escape-hatch single-decision route carries the same step-3 loop-back as the batch — a decision-shaped cold-read finding from an escape-hatched fix re-enters the gate for that lone finding (or is enqueued as a new `[decision]` item) before the finding is dropped, so S3 holds on the escape-hatch path too.

### WI3 [suggestion] `## Open Questions` is consumed by the gate with no handling rule

- **File**: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/workflow/prompts/adversarial-review.md` (line 136 and the criteria block, delta lines 1653-1684); `research.md` line 71
- **Axis**: phase output → next-phase input
- **Cost**: an unresolved open question recorded in Phase 0 can be silently carried past the Phase-0→1 gate and into a derived plan/design with no decision behind it — the artifact crystallizes over a known-open fork that nobody was forced to resolve or explicitly waive.
- **Issue**: `research.md` defines `## Open Questions` as "Unresolved questions carried toward planning" (line 71), and both the Step 4 classifier (`create-plan/SKILL.md:300`) and the adversarial reviewer read it (`adversarial-review.md:136` names it among the sections the reviewer reads). But the reviewer's criteria block raises DECISION/ASSUMPTION/INVARIANT challenges only on `## Decision Log` and `## Surprises & Discoveries` (delta lines 1653-1664); `## Open Questions` is an input with no consuming rule. The gate has a complete severity map for decisions (blocker / should-fix / suggestion / no-skip→blocker) but says nothing about what an open question does to the verdict. An open question is precisely a not-yet-made decision; deriving artifacts over it is the gap the research log was meant to close.
- **Suggestion**: add one line to the research-log scope criteria: an unresolved `## Open Questions` entry that bears on a load-bearing decision is at least a `should-fix` (resolve it into the Decision Log, or have the user explicitly waive it as out-of-scope) before the gate clears — so the gate cannot pass with a load-bearing question still open.

### WI4 [suggestion] Step-4b cold-read loop imports its budget by prose reference only

- **File**: `docs/adr/plan-slimization/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (lines 655-656)
- **Axis**: loop termination
- **Cost**: the Step-4b cold-read re-run loop has no locally-stated cap; its only termination guarantee is the phrase "the iterate loop mirrors `edit-design`," which a reader must follow to a different skill to discover the default-3 budget and the budget-exhausted recovery actually live there.
- **Issue**: The Step-4b cold-read is spawned **directly via the Agent tool** in `create-plan` (lines 627-648), not routed through `edit-design`'s mutation loop where `iteration_budget` and the budget-exhausted escalation are defined. The loop contract is asserted only as "A `blocker` re-opens Step-4b derivation in the **same session** (the iterate loop mirrors `edit-design`)" (lines 655-656). Unlike WI1's gate loop this is a soft gap — the "mirrors `edit-design`" phrase arguably imports the cap by reference — but the load-bearing termination rule is one indirection away from the loop it governs, so a future edit to either side can silently desync them.
- **Suggestion**: restate the cap inline at the Step-4b cold-read spawn — "re-run at most `iteration_budget` (default 3) times; on exhaustion escalate to the user as `edit-design` § Failure modes does" — so the loop carries its own termination contract rather than borrowing it across skills.

## Evidence base

#### C1
Confirmed gap. `create-plan/SKILL.md:399-407` defines the gate as an unbounded re-spawn loop ("until no blocker remains"; should-fix "gates ... before the gate clears") with no iteration count. Grep for `budget|max iter|cap|exhaust` across the staged `create-plan/SKILL.md` returns only the D15 mutation-budget line (1051) and the per-spawn context-cost note (392) — neither bounds the gate re-spawn count. The modeled-on loop in `edit-design/SKILL.md` carries `iteration_budget` (line 108, default 3) and explicit budget-exhausted recovery (lines 562-574, 743), so the contrast is real: the gate is a separate hand-rolled loop that does not pass through `edit-design` and inherits no cap. Termination depends entirely on the reviewer eventually emitting no blocker, which an adversarial loop on a contested decision cannot guarantee.

#### C2
Confirmed gap by asymmetry. The batch step 3 (lines 1044-1049) explicitly closes the cold-read→gate loop ("re-enters the gate step (step 1) and the batch cannot close ... while a log entry is open"). The escape-hatch paragraph (1054-1062) describes a strictly linear "one gate run, one mutation, one cold-read for the lone finding" then "dropped from the in-session queue," with no restatement of the step-3 loop-back. The decision-shaped-cold-read-finding complement that the batch handles is therefore unhandled on the escape-hatch route; the terminal cold-read can produce a `[decision]` finding the route has no step to absorb, and S3 (lines 1048-1049) is asserted only "across the whole loop" of the batch, not the escape-hatch route.

#### C3
Confirmed: `## Open Questions` is a consumed input with no consuming rule. `adversarial-review.md` lists it among the sections the reviewer reads (line 136), and the Step 4 classifier reads all three continuous logs (`create-plan/SKILL.md:300`), but the reviewer's "Criteria" enumeration (delta 1653-1664) attaches challenges to Decision Log and Surprises only. No staged text states what an unresolved open question does to the gate verdict or requires it be resolved/waived before artifacts derive. `research.md:71` frames open questions as "carried toward planning," confirming they survive into Phase 1 unresolved by design — which is exactly why a gate handling rule is the missing complement.

#### C4
Confirmed (soft). The Step-4b cold-read spawn (lines 627-648) is a direct `Agent` call; the only termination clause is "the iterate loop mirrors `edit-design`" (line 656). The actual cap (`iteration_budget` default 3) and budget-exhausted recovery live in `edit-design/SKILL.md` (108, 562-574, 743), one skill removed from the loop they govern. The reference arguably imports the cap, so this is a maintainability/explicitness gap rather than a true missing-branch — graded suggestion, not should-fix, on that basis.
