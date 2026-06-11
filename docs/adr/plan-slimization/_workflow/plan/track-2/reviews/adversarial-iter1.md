<!-- workflow-sha: e9377f7f133f5cd6ec3028936f28be2819e4ae96 -->
# Adversarial review — Track 2 (Phase 3A, iteration 1)

## Manifest

```yaml
review_type: adversarial
role: reviewer-adversarial
phase: 3A
track: "Track 2: Execution-side tier consumption"
iteration: 1
verdict: NEEDS_REVISION
findings: 7
blockers: 0
index:
  - id: A1
    sev: should-fix
    anchor: "A1"
    loc: "track-2.md §Interfaces and Dependencies — Signatures and contracts"
    cert: "Assumption test: the 3A adversarial spawn can deliver the xhigh effort pin"
    basis: "staged create-plan SKILL.md:373-385; .claude/agents/ listing; D14 caveat"
  - id: A2
    sev: should-fix
    anchor: "A2"
    loc: "track-2.md §Plan of Work step 2; §Interfaces (In-scope file 8)"
    cert: "Assumption test: slim-track rendering is implementable doc-only without a script edit"
    basis: "render-slim-plan.py:404 (parses --plan-path only); plan-slim-rendering.md TOC (3B,3C); S1"
  - id: A3
    sev: suggestion
    anchor: "A3"
    loc: "track-2.md §Plan of Work step 5; structural-review.md:75"
    cert: "Challenge: D10 — repurpose vs delete the plan/design duplication check"
    basis: "structural-review.md:75,81-82 (fuzzy title-match heuristic, 50-line gate)"
  - id: A4
    sev: suggestion
    anchor: "A4"
    loc: "track-2.md §Interfaces (Out of scope); carryover item 3"
    cert: "Assumption test: the open §2.1 reconciliation items have a Track-2 home"
    basis: "staged adversarial-review.md:254-255 (Track-1 file, not in Track-2 scope)"
  - id: A5
    sev: should-fix
    anchor: "A5"
    loc: "track-2.md §Plan of Work step 6 — track-1 episode-challenge drop"
    cert: "Violation scenario: the episode challenge is dropped on track 1 only"
    basis: "design Part 6 (drop cross-track-episode on track 1 only); track-2 step 6 wording"
  - id: A6
    sev: should-fix
    anchor: "A6"
    loc: "track-2.md §Plan of Work step 4 — minimal consistency lightening"
    cert: "Violation scenario: minimal consistency 'track-vs-code' vs design's 'track-vs-code only'"
    basis: "design Part 6 matrix row (minimal = track-vs-code only); track-2 step 4 + acceptance"
  - id: A7
    sev: suggestion
    anchor: "A7"
    loc: "track-2.md §Plan of Work step 3 — copy-shape rule"
    cert: "Assumption test: the copy-shape rule pins the seed in **Original decision**"
    basis: "design Part 4 §Cross-track propagation; Part 5 fidelity domain"
```

## Evidence base

### DECISION CHALLENGES

#### Challenge: D10 — repurpose vs delete the plan/design duplication check
- **Chosen approach**: Step 5 repurposes the live `structural-review.md` "Plan/design duplication" check (line 75) into the seed↔track fidelity verification, with Part 5's domain (iterate `design.md` seed records), a provenance-only qualifier for revision-format DRs, and authoring-time-only restoration.
- **Best rejected alternative**: Delete the duplication check outright in `full` and let the Part-5 fidelity criterion (a cold-read criterion in `design-review.md`, a Track-1 file) carry the whole load, leaving `structural-review.md` with no fidelity role.
- **Counterargument trace**:
  1. The live check at `structural-review.md:75` fires on "a DR body or Architecture Notes subsection is >50 lines AND `design.md` has a section whose title matches the DR's topic," with a fuzzy 2+-shared-word title match (lines 81-82). Under D7 every track DR is now mandated to match its `design.md` seed section, so the un-repurposed check would fire backwards on every full-tier track DR — the exact failure the track names.
  2. The rejected alternative (delete here, rely on the cold-read) would instead leave fidelity checked only at authoring time inside `design-review.md`, which is a Track-1 file. Track 2 would then edit `structural-review.md` only to *remove* a check, not add one.
  3. The repurpose is the stronger choice: it keeps a structural-review-resident fidelity check that runs on the written plan/tracks (Phase 2, not just authoring), complementing the cold-read. But the track's acceptance criterion ("its domain iterates `design.md` seed records only, and a track DR with no seed counterpart is out of scope by construction") is the load-bearing safety property, and the track must verify the repurposed check actually inverts its trigger direction (seed→track, not track-length→bloat) rather than merely renaming it.
- **Codebase evidence**: `.claude/workflow/structural-review.md:75` (the duplication row), `:81-82` (the fuzzy title-match note that would match a seed-derived track DR).
- **Survival test**: YES — the repurpose is correct and design-grounded (Part 7 §"Re-routing design-bound findings" explicitly says the full-tier check "repurposes rather than fires backwards"). The decision holds; the finding is a suggestion to make the trigger-inversion explicit, not a reconsideration.

### SCOPE CHALLENGES

#### Assumption test: slim-track rendering is implementable doc-only without a script edit
- **Claim**: Step 2 defines "the slim-track rendering in `plan-slim-rendering.md` (new — the live file covers slim plan rendering only)," and the track's Out-of-scope list asserts "this track edits no script (S1)."
- **Stress scenario**: The live slim-plan path is implemented by `render-slim-plan.py`, which the doc explicitly says "implements the rendering rule … do not re-derive the transform inline." If slim-track rendering follows the same architecture (a doc rule backed by a script), defining it doc-only either (a) leaves a prose rule with no executor, or (b) forces a `render-slim-plan.py` extension — an S1 violation, since track files are not parsed by that script today.
- **Code evidence**: `render-slim-plan.py:404` opens `args.plan_path` only and writes `out_path`; it never opens a `track-N.md` file. `plan-slim-rendering.md:82` ("the whole reason this script exists") establishes that the slim *plan* render is script-backed, not an inline orchestrator transform. Track files today are passed **whole** via `step_file_path` (`step-implementation.md` passes `step_file_path` directly; `plan-slim-rendering.md:88-92` confirms `[ ]` track detail "lives in the track file … the transform is a no-op"). So no track-side rendering exists anywhere.
- **Verdict**: FRAGILE — the design (Part 4 §"Track-canonical live decisions": "the slim plan plus the slim track with its full DRs inline") and D7 ("bounded … by the slim-track rendering") clearly intend a new rendering, and a doc-only rule the orchestrator applies inline is defensible (track files are small; the implementer could be handed a rendered slim track via a prose rule rather than a script). But the parallel with the script-backed plan render is close enough that the track should state explicitly whether the slim-track rendering is (a) an inline orchestrator prose rule or (b) a script-backed transform — and if (b), reconcile it with the "edits no script" S1 claim. As written, the ambiguity could surface mid-implementation as an unplanned script edit.

### INVARIANT CHALLENGES

#### Violation scenario: S4 — the tier and the per-step risk tag never stack
- **Invariant claim**: No staged sentence combines the tier and the per-step `risk` tag into one selection signal; Phase 3B/3C text is unchanged apart from the D7 carrier rewording.
- **Violation construction**:
  1. Start state: live `track-review.md:607-621` selects the 3A panel by a step-count complexity axis (Simple 1-2 / Moderate 3-5 / Complex 6-7), and the per-step `risk: high` tag (separately) gates Phase 3B (`track-review.md:602-605`).
  2. Action sequence: step 6 replaces the step-count axis with the tier as the 3A change-level selector and (per S4) leaves the per-step `risk` tag as the 3B gate.
  3. Intermediate state: the staged `track-review.md` carries a tier-keyed 3A panel table plus the unchanged 3B `risk: high` gate.
  4. Violation point: a violation would occur only if a staged sentence made a 3A pass selection *also* read the per-step `risk` tag (e.g., "run Adversarial when the tier is `full` OR any step is `risk: high`"). The current live "Complex (6-7 steps, **or critical path / high-risk**)" row at `:611` and `:621` already mixes step count with a "high-risk" characteristic; a careless tier rewrite could carry that "or high-risk" clause forward and re-introduce the stacking.
  5. Observable consequence: the tier (change-level) and the risk tag (per-step) would combine into one inflated 3A signal, the exact S4 failure.
- **Feasibility**: CONSTRUCTIBLE — the live text step 6 edits already contains an "or … high-risk" clause that, if mechanically ported into the tier-keyed table, violates S4. The track's acceptance ("no staged sentence combines the tier and the per-step risk tag") names the property but the step text does not call out the specific live clause that must be dropped. This is a should-fix on A5's sibling axis; recorded under the S4 hunt as confirmation the invariant is preserved *if* the live "or high-risk" clause is excised — the step should say so explicitly.

#### Violation scenario: the episode challenge is dropped on track 1 only
- **Invariant claim** (track realization): step 6 narrows the 3A adversarial to "track realization (scope/sizing, cross-track-episode reality, invariant violations) **with the episode challenge dropped on track 1 only**," matching design Part 6 ("only cross-track-episode reality needs a prior episode, so it is the one challenge dropped on the first track").
- **Violation construction**:
  1. Start state: design Part 6 §"The narrowed Phase-3A adversarial pass" mandates that on track 1 *only the cross-track-episode challenge* drops; scope/sizing and invariant-violation challenges still run on track 1.
  2. Action sequence: step 6's parenthetical lists three sub-checks "(scope/sizing, cross-track-episode reality, invariant violations) with the episode challenge dropped on track 1 only."
  3. Intermediate state: the wording is correct but compressed — it does not state the complement (that scope/sizing and invariant-violation *do* run on track 1), which design Part 6 §"Edge cases" calls out explicitly ("the scope/sizing and invariant-violation challenges still run").
  4. Violation point: an implementer reading only step 6 could drop *all three* sub-checks on track 1 (reading "episode challenge dropped on track 1" as "the adversarial pass is track-1-light") rather than dropping only the one. The Phase-3A matrix row in design Part 6 says the pass "runs, narrowed (track 1: episode challenge dropped)" — the narrowing on track 1 is one challenge, not the pass.
  5. Observable consequence: track 1's foundational sizing and invariants — "the track whose realization is most worth challenging" — would go un-challenged, the precise outcome design Part 6 warns against.
- **Feasibility**: CONSTRUCTIBLE — the design is unambiguous but step 6 states only the dropped half. The realized `track-review.md` text must carry the complement (scope/sizing + invariant-violation still run on track 1) so the narrowing is not over-applied.

#### Violation scenario: minimal consistency "track-vs-code" vs design's "track-vs-code only"
- **Invariant claim**: step 4 says "`minimal` … lightens consistency to track-vs-code"; the design Part 6 matrix row reads "Phase 2 consistency | … | track-vs-code only."
- **Violation construction**:
  1. Start state: live `consistency-review.md` reads three inputs — implementation plan, design document, code (`consistency-review.md:50-53`, "Design document (`design.md`)").
  2. Action sequence: step 4 must, for `minimal`, drop both the design half *and* the plan half (the stub plan has no content to cross-check, per design Part 6 §2: "its Phase-2 consistency lightens to track-vs-code since the stub plan has no content to cross-check").
  3. Intermediate state: step 4's phrasing "lightens consistency to track-vs-code" is correct but the acceptance criterion ("a dry-run read of the staged Phase-2 flow under `lite` and `minimal` reaches no instruction that opens, cites, or routes findings to a design file") only guards the *design* half — it does not assert that `minimal` also drops the *plan* half.
  4. Violation point: an implementer could realize `minimal` consistency as "plan + track + code minus design" (the `lite` shape) rather than "track + code only," leaving a plan-vs-code cross-check that the stub plan cannot satisfy.
  5. Observable consequence: a `minimal` consistency pass that tries to cross-check a ~10-line stub plan against code would produce spurious gap findings, the failure the design's "nothing to cross-check" rationale avoids.
- **Feasibility**: CONSTRUCTIBLE — `lite` is "plan + tracks" and `minimal` is "track-vs-code only" in the design matrix; step 4 collapses both under "design-presence guards" and one "lightens to track-vs-code" clause without distinguishing the plan-half drop that is unique to `minimal`. Should-fix: the step and the acceptance criterion must name the `minimal` plan-half drop, not only the design-half guard.

### ASSUMPTION CHALLENGES

#### Assumption test: the 3A adversarial spawn can deliver the xhigh effort pin
- **Claim**: track-2.md §"Signatures and contracts" — "The 3A adversarial spawn mirrors Track 1's gate spawn contract: Agent call with `model` per D14 **and the xhigh effort pin**."
- **Stress scenario**: Track 1 already resolved this exact harness question while implementing the *gate* spawn. The staged `create-plan` SKILL.md:379-385 states verbatim: "The `Agent` tool has **no per-spawn effort field**, and there is no adversarial-reviewer agent file under `.claude/agents/` to carry effort in frontmatter … the xhigh-effort half rides the session default (it cannot be pinned per-spawn through this surface)." The 3A spawn rides the identical `general-purpose` Agent surface, so the same constraint binds.
- **Code evidence**: `.claude/agents/` contains no `reviewer-adversarial` / `adversarial` agent file (listing: `code-reviewer`, `pr-reviewer`, `review-workflow-*`, `review-*`, `test-quality-reviewer`, `dr-audit` — none is the panel adversarial reviewer). Staged `create-plan` SKILL.md:373-385 pins only `model: fable`/`model: opus` and explicitly degrades effort to the session default. D14's own caveat (design Part 3 §"Reviewer model triage": "the effort half may degrade to the session default — neither outcome reopens the decision").
- **Verdict**: BREAKS — the track text claims a capability the harness does not expose and Track 1 already documented as unavailable. "Agent call with `model` per D14 and the xhigh effort pin" overstates what step 6 can deliver. The §Signatures line should be reconciled to D14's degradation caveat: pin `model` by tier (`full`→fable, `lite`→opus for the 3A pass; `minimal` drops the pass), and acknowledge the effort half rides the session default with no per-spawn surface — mirroring the staged create-plan resolution exactly. This is the carryover-item-2 reality the prior episode flagged.

#### Assumption test: the open §2.1 reconciliation items have a Track-2 home
- **Claim** (prior episode, carryover item 3): "two §2.1 reconciliation items stay open for Track 2: the stale 'four sections' framing in `adversarial-review.md`'s `## Workflow Context`, and the third-scope review-file home versus the canonical `plan/track-N/reviews/`."
- **Stress scenario**: Track 2's Out-of-scope list explicitly excludes "every Track 1 file." The stale "four sections" framing lives at staged `adversarial-review.md:254-255` ("split across four sections") — and `prompts/adversarial-review.md` is a **Track 1** in-scope file (it is in the Track-1 staged set and not in Track-2's eleven-file list). So the carryover item names a fix with no Track-2 home: Track 2 cannot edit a Track-1 file without violating its own scope boundary and I6's staged-file ownership split.
- **Code evidence**: staged `adversarial-review.md:254-255` (the "four sections" text, in a Track-1-owned file); track-2.md §Interfaces "Out of scope: every Track 1 file." The actual track shape is now 14 sections (`conventions-execution.md` §2.1:57-58), so "four sections" is genuinely stale — but it is stale in a Track-1 file.
- **Verdict**: FRAGILE — the carryover item is real (the framing is stale) but mis-assigned to Track 2. Track 2 should either (a) explicitly note these two items are deferred to a Phase-4 reconciliation / Track-1 follow-up because the owning file is out of Track-2 scope, or (b) the orchestrator should record them as known-deferred so they are not silently lost. The third-scope review-file home (`_workflow/reviews/` per staged create-plan:346 vs the canonical `plan/track-N/reviews/`) is likewise a `conventions-execution.md` §2.5 question — §2.5 is Track 1's, not Track 2's (the track's own In-scope note: "§2.5 belongs to Track 1"). Suggestion: the track text should acknowledge these two items are out of its file scope rather than implying step 1 (§2.1) addresses them.

#### Assumption test: the copy-shape rule pins the seed in **Original decision**
- **Claim**: step 3 — "the copy-shape rule (any post-seed copy of an ever-revised decision carries the inline-replan revision format, seed decision pinned in `**Original decision**`)."
- **Stress scenario**: design Part 4 §"Cross-track propagation" requires the copy-shape rule be **decision-state-based, not replan-event-based**: "any post-seed copy of a decision that has ever been revised is written in the inline-replan revision format (the seed decision stays in its `**Original decision**` field), never as clean revised text." The Part-5 fidelity check then compares the `**Original decision**` field "provenance-only … which stays seed-pinned across repeated revisions." If step 3 implements the rule as replan-event-based (only stamping copies touched in *this* replan) rather than decision-state-based (every post-seed copy of an ever-revised decision), a copy in a track not touched by the current replan would lose the marker and route to the wrong fidelity path.
- **Code evidence**: design Part 4 ("decision-state-based, not replan-event-based") and Part 5 ("A track DR carrying the inline-replan revision format, or named by a supersession note, is compared provenance-only — its `**Original decision**` field, which stays seed-pinned across repeated revisions"). Track-2 step 3's parenthetical names the format and the `**Original decision**` pin correctly but uses "any post-seed copy of an ever-revised decision," which is the decision-state framing — so the assumption holds in the text.
- **Verdict**: HOLDS — step 3's wording ("any post-seed copy of an ever-revised decision") matches the design's decision-state-based rule and correctly names the `**Original decision**` seed pin. Suggestion only: the realized `inline-replanning.md` text must preserve "decision-state-based, not replan-event-based" explicitly so the fidelity-path routing (Part 5) cannot desync; the cross-track propagation duty's owner (orchestrator), scope (every not-yet-completed carrying track), and completed-track mechanism (supersession note to `## Decision Log`) are all named in step 3 and in the acceptance criterion, so the propagation completeness check passes.

## Findings

### A1 [should-fix]
**Certificate**: Assumption test — "the 3A adversarial spawn can deliver the xhigh effort pin"
**Target**: D14 / track-2.md §"Signatures and contracts"
**Challenge**: The §Signatures claim "Agent call with `model` per D14 and the xhigh effort pin" asserts a harness capability that does not exist. Track 1 already resolved this for the gate spawn: the `Agent` tool has no per-spawn effort field and there is no adversarial-reviewer agent file to carry effort in frontmatter, so the xhigh-effort half rides the session default. The 3A spawn uses the identical `general-purpose` Agent surface, so the same constraint binds — the track text overstates what step 6 can deliver.
**Evidence**: staged `create-plan` SKILL.md:379-385 ("The `Agent` tool has no per-spawn effort field … the xhigh-effort half rides the session default"); `.claude/agents/` has no adversarial-reviewer file; D14's own degradation caveat (design Part 3, "the effort half may degrade to the session default — neither outcome reopens the decision").
**Proposed fix**: Reconcile §"Signatures and contracts" to D14's degradation caveat, mirroring Track 1's resolution: pin `model` by tier on the Agent `model` field (`full`→fable, `lite`→opus; `minimal` drops the 3A pass), and state that the xhigh-effort half rides the session default because the surface exposes no per-spawn effort field. Drop "and the xhigh effort pin" from the claim or qualify it as session-default. This is the carryover-item-2 reality the prior episode flagged.

### A2 [should-fix]
**Certificate**: Assumption test — "slim-track rendering is implementable doc-only without a script edit"
**Target**: D7 / track-2.md §Plan of Work step 2; §Interfaces (In-scope file 8, `plan-slim-rendering.md`)
**Challenge**: Step 2 defines a new "slim-track rendering" in `plan-slim-rendering.md` while the track's Out-of-scope list asserts "this track edits no script (S1)." But the live slim-*plan* render is script-backed (`render-slim-plan.py`), and that script parses only the plan file — track files are passed whole today, with no track-side rendering anywhere. The track does not state whether the new slim-track rendering is an inline orchestrator prose rule or a script-backed transform; if it turns out to need a script, that is an unplanned S1 collision surfacing mid-implementation.
**Evidence**: `render-slim-plan.py:404` opens `args.plan_path` only, never a `track-N.md`; `plan-slim-rendering.md:82` ("the whole reason this script exists"), `:88-92` (`[ ]` track detail "lives in the track file … the transform is a no-op"); track files reach sub-agents whole via `step_file_path` (`step-implementation.md`, `implementer-rules.md` Inputs).
**Proposed fix**: State explicitly in step 2 (and §Interfaces) that the slim-track rendering is a **doc-only orchestrator prose rule** (the orchestrator renders the slim track inline from the track file before passing it to sub-agents), with no `render-slim-plan.py` change — preserving S1. If a script proves necessary at decomposition, that is an ESCALATE, not a silent script edit. Add a one-line rationale that track files are small enough to render inline without a script, distinguishing the slim-track rule from the script-backed slim-plan rule.

### A3 [suggestion]
**Certificate**: Challenge — "D10: repurpose vs delete the plan/design duplication check"
**Target**: D10 / track-2.md §Plan of Work step 5 (`structural-review.md`)
**Challenge**: Step 5 repurposes the live "Plan/design duplication" check into the seed↔track fidelity verification. The decision is sound (design Part 7 mandates the repurpose so the check does not fire backwards on mandated track DRs), but the step describes the new behavior without naming the specific live trigger that must invert: the check fires today on "DR body >50 lines AND a title-matching `design.md` section" with a fuzzy 2+-shared-word match — exactly what a seed-derived track DR now satisfies.
**Evidence**: `structural-review.md:75` (the >50-line + title-match trigger), `:81-82` (fuzzy 2+-word match). Design Part 7 §"Re-routing design-bound findings" ("repurposes rather than fires backwards").
**Proposed fix**: In step 5, name the live trigger being inverted (length+title-match bloat → seed-record-domain fidelity) so the implementer rewrites the trigger direction rather than renaming the row. The acceptance criterion already states the safety property (domain iterates seed records only); tie the step text to it.

### A4 [suggestion]
**Certificate**: Assumption test — "the open §2.1 reconciliation items have a Track-2 home"
**Target**: Assumption / carryover item 3
**Challenge**: The prior episode hands Track 2 two open §2.1 reconciliation items, but both target files outside Track 2's scope: the stale "four sections" framing lives in `prompts/adversarial-review.md` (a Track-1 file, excluded by "Out of scope: every Track 1 file"), and the third-scope review-file home is a `conventions-execution.md` §2.5 question (the track itself notes "§2.5 belongs to Track 1"). Track 2 cannot fix either without crossing its own scope boundary, so the items risk being silently lost.
**Evidence**: staged `adversarial-review.md:254-255` ("split across four sections" — stale, the shape is now 14 sections per `conventions-execution.md` §2.1:57-58, and that file is Track-1-owned); track-2.md §Interfaces "Out of scope: every Track 1 file"; "§2.5 belongs to Track 1."
**Proposed fix**: Add a note (in §Interfaces Out-of-scope or §Plan of Work) that these two carryover items are out of Track-2 file scope and are deferred to a Track-1 follow-up or the Phase-4 promotion reconciliation, so the orchestrator records them as known-deferred rather than assuming step 1 (§2.1) covers them.

### A5 [should-fix]
**Certificate**: Violation scenario — "the episode challenge is dropped on track 1 only"
**Target**: D9 / track-2.md §Plan of Work step 6
**Challenge**: Step 6's narrowed-adversarial parenthetical lists three sub-checks "with the episode challenge dropped on track 1 only" but does not state the complement — that scope/sizing and invariant-violation challenges *still run* on track 1. An implementer reading only step 6 could over-apply the narrowing and skip the whole adversarial pass on track 1, exempting the foundational track whose sizing and invariants most constrain everything downstream.
**Evidence**: design Part 6 §"Edge cases" ("only the cross-track-episode challenge is dropped; the scope/sizing and invariant-violation challenges still run, since the foundational track most constrains the downstream ones") and the matrix row ("runs, narrowed (track 1: episode challenge dropped)"). Separately, the live `track-review.md:611,621` "Complex (6-7 steps, or critical path / high-risk)" clause mixes step count with a risk characteristic; a mechanical tier rewrite could carry that "or high-risk" clause into the tier table and re-introduce S4 stacking.
**Proposed fix**: In step 6, state the complement explicitly (track 1 still runs scope/sizing + invariant-violation; only cross-track-episode drops). Also instruct the step to excise the live "or … high-risk" clause when porting the panel table to the tier axis, so the tier-keyed selector reads no per-step risk signal (S4).

### A6 [should-fix]
**Certificate**: Violation scenario — "minimal consistency 'track-vs-code' vs design's 'track-vs-code only'"
**Target**: D9/D10 / track-2.md §Plan of Work step 4 + §Validation
**Challenge**: Step 4 collapses the `lite` and `minimal` consistency shapes under one "lightens to track-vs-code" clause plus a design-presence guard. But `lite` is plan + tracks (design half dropped) while `minimal` is track-vs-code *only* (both the design half *and* the plan half dropped, because the stub plan has no content to cross-check). The acceptance criterion guards only the design-half drop ("reaches no instruction that opens … a design file"); it does not assert the `minimal` plan-half drop. An implementer could realize `minimal` consistency as the `lite` shape and produce spurious gap findings against the ~10-line stub.
**Evidence**: design Part 6 matrix ("Phase 2 consistency | full | plan + tracks | track-vs-code only") and §2 ("its Phase-2 consistency lightens to track-vs-code since the stub plan has no content to cross-check"); live `consistency-review.md:50-53` reads plan + design + code.
**Proposed fix**: Split step 4's consistency clause by tier — `lite` drops the design half (plan + tracks + code); `minimal` drops both the design half and the plan-content cross-check (track + code only). Extend the acceptance criterion to assert the `minimal` plan-half drop, not only the design-half guard.

### A7 [suggestion]
**Certificate**: Assumption test — "the copy-shape rule pins the seed in **Original decision**"
**Target**: D7 / track-2.md §Plan of Work step 3
**Challenge**: Step 3's copy-shape rule wording ("any post-seed copy of an ever-revised decision carries the inline-replan revision format, seed decision pinned in `**Original decision**`") correctly matches the design's decision-state-based rule, so the assumption holds. The residual risk is the realized `inline-replanning.md` text drifting to a replan-event-based reading (stamping only copies touched in the current replan), which would desync the Part-5 fidelity-path routing for a duplicated copy in a track the replan did not touch.
**Evidence**: design Part 4 §"Cross-track propagation" ("decision-state-based, not replan-event-based"); Part 5 (the `**Original decision**` field "stays seed-pinned across repeated revisions"). Step 3 already names the propagation owner (orchestrator), scope (every not-yet-completed carrying track), and completed-track mechanism (supersession note to `## Decision Log`), and the live `inline-replanning.md` cases 2-4 are the right edit sites (cases 2-3 lack `## Decision Log` in their updatable lists today; case 4 is the completed-track pause needing the documentation-only carve-out).
**Proposed fix**: When realizing step 3 in `inline-replanning.md`, carry the design's "decision-state-based, not replan-event-based" phrasing verbatim so the marker is applied to every post-seed copy of an ever-revised decision, not only those touched by the current replan. No structural change to the step; this is a fidelity guard on the realized prose.
