<!-- MANIFEST
findings: 5   severity: {blocker: 0, should-fix: 2, suggestion: 3}
index:
  - {id: R1, sev: should-fix, loc: "track-2.md step 5 / prompts/structural-review.md:253-275", anchor: "### R1 ", cert: E-D10-structural, basis: "lite tier runs structural with no design.md; unconditional DESIGN DOCUMENT check block fires spuriously; step 5 names only the minimal stub-skip"}
  - {id: R2, sev: should-fix, loc: "track-2.md step 3 / inline-replanning.md:236-259", anchor: "### R2 ", cert: A-D7-updatable, basis: "propagation duty writes ## Decision Log of not-yet-completed tracks, but cases 2-3 updatable-section lists omit ## Decision Log; step 3 says lists gain it but does not name both case bodies"}
  - {id: R3, sev: suggestion, loc: "track-2.md step 1 / conventions-execution.md (staged, shared with Track 1)", anchor: "### R3 ", cert: E-clobber-sharedfile, basis: "Track 2 edits §2.1 of a file Track 1 already staged; §1.7(e) copy-then-edit prevents clobber only if implementer reads staged copy first"}
  - {id: R4, sev: suggestion, loc: "track-2.md step 7 / workflow.md:635-647 + research.md (Track-1 carrier)", anchor: "### R4 ", cert: A-S2-foldcarrier, basis: "Phase-4 fold reads ## Adversarial gate record defined in a Track-1 file; cross-track read dep + must precede cleanup commit"}
  - {id: R5, sev: suggestion, loc: "track-2.md step 2 / plan-slim-rendering.md", anchor: "### R5 ", cert: A-slimtrack-new, basis: "slim-track rendering is wholly new in plan-slim-rendering.md (no track-side rendering exists today); no existing consumer wired to it"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 8}
flags: [CONTRACT_OK]
-->

## Findings

### R1 [should-fix]
**Certificate**: Exposure `E-D10-structural` (design-presence conditionals)
**Location**: Track 2 `## Plan of Work` step 5; `.claude/workflow/prompts/structural-review.md:253-275` (the unconditional `DESIGN DOCUMENT` check block)
**Issue**: The design's Part-6 matrix (design.md:893-896, frozen seed) keeps the Phase-2 **structural** pass running under `lite` (only `minimal` drops it), and the tier table (design.md:226) says `lite` carries **no `design.md`**. The current structural-review sub-agent prompt opens its `DESIGN DOCUMENT` block with an unconditional bullet — "Does the design document exist at `docs/adr/<dir-name>/_workflow/design.md`?" (line 260) — followed by class-diagram / workflow-diagram / Overview checks (lines 261-275). Under `lite`, structural runs but `design.md` is absent, so this whole block either flags the missing design as a structural defect or fires spuriously on the diagram bullets. Track 2 step 5 names only "the `minimal` stub skip (nothing to check)" and the duplication-check repurpose; it does not name a `lite`-tier guard around the `DESIGN DOCUMENT` block, even though that block runs under `lite` precisely because structural is not dropped there. The track-level acceptance criterion (track-2.md:178-180) does cover this implicitly ("a dry-run read of the staged Phase-2 flow under `lite` … reaches no instruction that opens, cites, or routes findings to a design file"), so the bar is set — but the plan-of-work prose under-specifies the work needed to satisfy it. Likelihood that a decomposer reading step 5 alone misses the `lite` case is meaningful; impact is a `lite` track tripping a spurious structural blocker on every run. D10's design intent (no-design tiers skip the design half) is sound; the gap is in the plan's enumeration of where the guard must land.
**Proposed fix**: In step 5, add an explicit clause: the `DESIGN DOCUMENT` check block in `prompts/structural-review.md` (and any design-existence/diagram bullet the workflow `structural-review.md` mirrors) is guarded by a design-presence conditional, not only the `minimal` stub-skip — under both `lite` and `minimal` (design absent) the block is skipped, while `full` runs it unchanged. Pair it with the existing acceptance criterion so the dry-run check at track-2.md:178-180 lands against named edits.

### R2 [should-fix]
**Certificate**: Assumption `A-D7-updatable` (propagation duty wiring)
**Location**: Track 2 `## Plan of Work` step 3; `.claude/workflow/inline-replanning.md:236-259` (cases 2 and 3 updatable-section lists)
**Issue**: The propagation duty (design.md:677-690, frozen) requires a replan that revises a duplicated decision to "update the copy in every relevant **not-yet-completed** track file in the same replan." Not-yet-completed tracks are status `[ ]` — they are revised through inline-replanning case 2 (not-yet-started) and case 3 (mid-execution). Both case bodies (inline-replanning.md:236-259) currently enumerate the updatable sections as `## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`, `## Interfaces and Dependencies`, `## Validation and Acceptance` — **`## Decision Log` is not in either list**. The propagation duty cannot write a revised duplicated DR into a not-yet-completed track unless `## Decision Log` is an updatable section in exactly those two cases. Track 2 step 3 says "the updatable-section lists gain `## Decision Log`" (singular "lists"), which is directionally correct, but it does not name **which** case bodies must gain it, and the natural misreading — adding it only to the case-4 completed-track carve-out where the supersession note lands — would leave the not-yet-completed write path (the duty's primary scope, every `[ ]` carrying track) still blocked. A miss here silently desynchronizes duplicated decisions: the replan revises the seed and the completed-track note, but the live `[ ]` track copies keep the stale text, defeating the "one logical live record" guarantee D7 exists to provide. The duty's four-part completeness (owner / scope / completed-track mechanism / copy-shape) is itself fully specified in design.md and faithfully restated in track-2.md:184-186; the gap is purely in which updatable-section lists the step touches.
**Proposed fix**: In step 3, name both targets explicitly: cases 2 and 3 (not-yet-started, mid-execution) gain `## Decision Log` in their updatable-section lists so the propagation duty's primary write path is open, AND case 4 (completed-track) gains the documentation-only carve-out for the supersession-note append. Add a track-level acceptance line asserting that a replan revising a duplicated DR can write `## Decision Log` of a `[ ]` track without an ESCALATE pause.

### R3 [suggestion]
**Certificate**: Exposure `E-clobber-sharedfile` (shared-file sequencing)
**Location**: Track 2 step 1 and in-scope file #9; `conventions-execution.md` (staged copy already exists from Track 1)
**Issue**: `conventions-execution.md` is the one in-scope file shared with Track 1. Track 1 already staged it (verified: `_workflow/staged-workflow/.claude/workflow/conventions-execution.md` exists; its diff vs develop touches the §2.5 TOC rows + §2.5 bodies + a new `### Third-scope review-file home` subsection — all in the §2.5 region, lines 13-17 and 474-667). Track 2 step 1 edits §2.1 (TOC row at line 7 + §2.1 body, lines 28-258) — disjoint section regions, so the sizing justification's "split on disjoint sections" claim holds. The residual risk is purely operational: if the Phase-B implementer copies the **develop** version of `conventions-execution.md` into the staged path instead of editing the already-present Track-1-staged copy, Track 1's §2.5 edits are silently lost. The mechanism that prevents this exists — `conventions.md §1.7(e)` (mirrored in `implementer-rules.md:285-288`) states first-touch copies the live file verbatim only when no staged copy exists; "subsequent writes to the same file edit the staged copy directly," and reads follow §1.7(d) (staged copy authoritative when present). So a compliant implementer edits the staged copy and the clobber cannot happen. This is a low-probability risk (mechanism is sound and the staged-read precedence is annotated in the prompt context), worth a note rather than a fix.
**Proposed fix**: No plan change required. At decomposition, the step-1 entry should carry a one-line note: "`conventions-execution.md` is already staged by Track 1 — edit the staged copy in place per §1.7(e); do NOT re-copy from develop." This makes the §1.7(e) obligation explicit at the step that touches the shared file.

### R4 [suggestion]
**Certificate**: Assumption `A-S2-foldcarrier` (Phase-4 fold reads the Track-1 carrier; S2 verdict-only boundary)
**Location**: Track 2 step 7; `workflow.md:635-647` (Phase-4 commit ordering) + `research.md` `## Adversarial gate record` (Track-1 file, carrier definition)
**Issue**: Step 7's Phase-4 fold must read "the log's resolved gate records." The canonical carrier for those records is `research.md`'s `## Adversarial gate record` section, which Track 1 created and which explicitly names "the Phase-4 fold" as one of its readers (research.md:76-82) and confirms the read is verdict/status-only, not decision-content, so S2 is preserved (research.md:112-118). Two facts the step relies on: (1) `research.md` is a **Track-1 file, out of Track 2's scope** — the fold (in `workflow.md` / `create-final-design.md`, Track-2 scope) reads a section defined upstream, so the step's correctness depends on Track 1 having landed that section (it has — verified in the staged copy). (2) The fold must run **before** the cleanup commit deletes the log: `workflow.md:635-647` orders the final-artifacts commit (step 2, writes `adr.md`) ahead of the cleanup commit (step 3, `git rm -r _workflow/`), so wiring the fold into `adr.md` production (create-final-design.md Artifact 2) keeps it on the safe side of the delete. Track-2.md step 7 states the ordering ("reads … before the `_workflow/` cleanup deletes them") but does not name the carrier section by its canonical heading. Low risk because both facts hold today; the value is making the cross-track read dependency explicit so a later edit to `research.md`'s heading does not silently strand the fold.
**Proposed fix**: No structural plan change. In step 7, name the carrier explicitly: the fold reads `research.md`'s `## Adversarial gate record` resolved entries (the canonical verdict carrier, matched by latest dated heading per `research.md` §Gate-record cadence), and the fold is wired into the final-artifacts (`adr.md`) commit so it precedes the cleanup `git rm`. Note the cross-track read dependency on Track 1's carrier in `## Interfaces and Dependencies`.

### R5 [suggestion]
**Certificate**: Assumption `A-slimtrack-new` (slim-track rendering is greenfield)
**Location**: Track 2 step 2; `.claude/workflow/plan-slim-rendering.md`
**Issue**: Track 2 step 2 introduces a slim-**track** rendering. The current `plan-slim-rendering.md` defines slim-**plan** rendering only; track files are passed whole to sub-agents today (no track-side rendering exists anywhere in the live machinery — confirmed by the track file's own `## Context and Orientation` and CR4 in the plan's auto-fix log). So this is a wholly new rendering, not an extension of an existing one. The risk is that a new rendering with no wired consumer is dead text: the slim-track render is only load-bearing if some sub-agent spawn actually receives the slim-track form. D7's consumption model (design.md ~602, ~715) wants "slim plan + slim track with full DRs inline, `design.md` path-only," but the step does not name which spawn path switches from whole-track to slim-track. Low likelihood of breaking a downstream phase (a whole-track pass-through is the safe default and still works), but a meaningful chance the rendering ships unconsumed and the intended context-budget saving never lands.
**Proposed fix**: No plan restructure. In step 2, name at least one consumer the slim-track rendering feeds (e.g., the Phase-3A/3B sub-agent spawns that today receive the whole track file), or state explicitly that wiring the consumer is in-scope for this step rather than left implicit. If no consumer is switched in this track, record that as a deliberate deferral so a reviewer does not read the new rendering as already in effect.

## Evidence base

#### E-D10-structural: lite-tier structural review with no design.md
- **Track claim**: step 5 — "the `minimal` stub skip (nothing to check) lands here and in step 4's selection (D10)"; the duplication check repurposes and design-destination bloat-fixes re-route to track sections.
- **Critical path trace**:
  1. Tier confirmed `lite` → Phase-2 selection (Track 2 step 4 reads D18 tier line).
  2. Per design Part-6 matrix (design.md:893-896): `lite` → Phase-2 structural = **runs** (only `minimal` drops it).
  3. Structural sub-agent invoked with `prompts/structural-review.md`; reaches the `DESIGN DOCUMENT` block (structural-review.md:253-275).
  4. First bullet (line 260): "Does the design document exist at `docs/adr/<dir-name>/_workflow/design.md`?" — unconditional, no tier guard.
  5. `lite` carries no design.md (design.md:226 tier table: "aggregator plan + tracks, no design") → check fails / fires spuriously; diagram bullets (261-275) have no design to inspect.
- **Blast radius**: every `lite` track's Phase-2 structural pass; a spurious "missing design document" structural finding on each run.
- **Existing safeguards**: track-2.md acceptance criterion (lines 178-180) sets the dry-run bar for the whole Phase-2 flow under `lite`/`minimal`; design Part-6 + Part-7 establish the no-design intent. No safeguard in the *plan-of-work prose* names the `lite` structural-block guard.
- **Residual risk**: MEDIUM — acceptance bar exists but step-5 enumeration omits the `lite` case, so a decomposer may guard only `minimal`.

#### A-D7-updatable: not-yet-completed track Decision Log is not an updatable section today
- **Track claim**: step 3 — "the updatable-section lists gain `## Decision Log`"; propagation updates "every not-yet-completed track copy."
- **Evidence search**: Read `inline-replanning.md:236-259` (cases 2-3 bodies) + grep for `Decision Log` in the updatable enumerations; design.md:677-690 propagation spec.
- **Code evidence**: inline-replanning.md cases 2 (236-246) and 3 (248-259) list `## Purpose / Big Picture`, `## Context and Orientation`, `## Plan of Work`, `## Interfaces and Dependencies`, `## Validation and Acceptance` — `## Decision Log` absent from both. design.md:679-680 requires the in-replan update of every not-yet-completed copy (status `[ ]` = case 2/3).
- **Verdict**: UNVALIDATED
- **Detail**: the duty's write target (`## Decision Log` of `[ ]` tracks) is not an updatable section in the two cases that revise `[ ]` tracks; step 3's plural "lists" does not name them, and the obvious add (case-4 carve-out only) leaves the primary path blocked.

#### E-clobber-sharedfile: conventions-execution.md staged region disjointness
- **Track claim**: in-scope #9 — "§2.1 only — §2.5 belongs to Track 1"; sizing justification — "the single shared file … is split on disjoint sections."
- **Critical path trace**:
  1. Track 1 staged `conventions-execution.md` (file present in staged mirror, verified).
  2. Diff vs develop: §2.5 TOC rows (TOC lines 13-17) + §2.5 bodies (474-628) + new `### Third-scope review-file home` (628a630-667) — all in §2.5 region.
  3. Track 2 step 1 edits §2.1 TOC row (line 7) + §2.1 body (28-258) — disjoint region.
  4. §1.7(e) (implementer-rules.md:285-288): subsequent writes edit the staged copy directly; reads follow §1.7(d) staged-authoritative.
- **Blast radius**: if implementer re-copies develop over the staged copy → Track 1's §2.5 edits lost.
- **Existing safeguards**: §1.7(e) copy-then-edit (no re-copy when staged copy exists) + §1.7(d) staged-read precedence (both annotated in the spawn prompt context).
- **Residual risk**: LOW — mechanism prevents clobber for a compliant implementer; risk is an operational mis-step only.

#### A-S2-foldcarrier: Phase-4 fold reads the Track-1 gate-record carrier, verdict-only
- **Track claim**: step 7 — "The fold reads the log's resolved gate records before the `_workflow/` cleanup deletes them … the §1.7(f) promotion runs unchanged."
- **Evidence search**: Read `research.md:76-123` (staged, Track-1 carrier) + `workflow.md:604-647` (Phase-4 commit ordering).
- **Code evidence**: research.md:76-82 names "the Phase-4 fold" as a reader of `## Adversarial gate record`; research.md:112-118 confirms it is a verdict/status read (S2 preserved, no third decision-content site). workflow.md:635-647 orders final-artifacts commit (adr.md) before cleanup `git rm -r _workflow/`.
- **Verdict**: VALIDATED
- **Detail**: carrier exists in the Track-1-staged research.md; ordering is correct (fold in adr.md production precedes cleanup). Residual is the cross-track read dependency not being named in track-2.md, so a future heading change could strand it silently.

#### A-slimtrack-new: no track-side rendering exists in the live machinery
- **Track claim**: step 2 — "Define the slim-track rendering in `plan-slim-rendering.md` (new — the live file covers slim plan rendering only)."
- **Evidence search**: grep `plan-slim-rendering.md` for track-side rendering; track-2.md `## Context and Orientation` ("track files are passed whole today (no track-side rendering exists anywhere in the live machinery)"); plan CR4 ("slim-track rendering is new, not an extension").
- **Code evidence**: `plan-slim-rendering.md` defines slim-plan rendering only; the track file and the plan's auto-fix log both record the greenfield status.
- **Verdict**: VALIDATED (claim is accurate)
- **Detail**: the rendering is genuinely new; the risk is a new render with no wired consumer shipping as dead text, not an incorrect claim.

#### E-S4-separation: tier vs per-step risk tag never stack (verification, no defect)
- **Track claim**: step 6 + acceptance (track-2.md:176-178) — tier replaces step-count as the 3A change-level selector; per-step risk tag stays the 3B gate, triage stays 3C; no staged rule combines them.
- **Critical path trace**: design Part-6 matrix (design.md:893-901) keys Phase-2/3A off tier; Phase 3B = `risk:high` steps, Phase 3C = triage-gated, both "run unchanged in every tier" (design.md:884-885, 900-901). Reconciliation (design.md:936-945): tier = change-level driver, risk tag = per-step driver, "never combine into a single inflated signal."
- **Blast radius**: n/a — separation is clean in the frozen seed; step 6 implements Part-6 verbatim.
- **Existing safeguards**: acceptance criterion track-2.md:176-178 ("no staged sentence combines the tier and the per-step risk tag"); current track-review.md:609-621 step-count axis is the thing being replaced, not augmented.
- **Residual risk**: LOW — no finding; recorded as positive evidence that S4 is structurally preserved by the plan.

#### A-D12-escalate: mid-flight tier upgrade rides existing ESCALATE (verification, no defect)
- **Track claim**: step 3 tail — "The D12 tier upgrade rides the existing ESCALATE path: new tier's artifacts and 3A passes from the upgrade point onward, no retroactive reviews, no automatic downgrade."
- **Evidence search**: Read design.md:285-296 (D12 rationale) + inline-replanning.md §When ESCALATE triggers (lines 20-53).
- **Code evidence**: D12 (design.md:289-292) — upgrade adds new tier's artifacts + runs 3A passes from upgrade point, cannot retroactively insert a passed review, downgrades not automatic. ESCALATE path (inline-replanning.md:20+) is the existing reentry the upgrade reuses.
- **Verdict**: VALIDATED
- **Detail**: D12 reuses the existing ESCALATE machinery; no new state-machine path, so resume paths are unchanged. No retroactive review insertion matches "reviews cannot be un-run."

#### A-propagation-complete: propagation duty four-part completeness (verification)
- **Track claim**: track-2.md:184-186 — duty names owner (orchestrator), scope (every not-yet-completed carrying track), completed-track mechanism (supersession note), copy-shape rule.
- **Evidence search**: Read design.md:675-690 (Cross-track propagation, frozen).
- **Code evidence**: design.md:679 owner = orchestrator; 679-680 scope = every relevant not-yet-completed track; 680-682 completed-track = supersession note to `## Decision Log`; 682-686 copy-shape = decision-state-based inline-replan revision format with `**Original decision**`.
- **Verdict**: VALIDATED
- **Detail**: the duty's specification is complete and faithfully mirrored in the track acceptance criteria. The only gap is the wiring of the updatable-section lists (see A-D7-updatable / R2), not the duty's own definition.
