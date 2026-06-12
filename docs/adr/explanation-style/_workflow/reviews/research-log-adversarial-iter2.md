<!-- MANIFEST
findings: 4   severity: {blocker: 0, should-fix: 3, suggestion: 1}
index:
  - {id: A10, sev: should-fix, loc: docs/adr/explanation-style/_workflow/research-log.md:236, anchor: "### A10 ", cert: C12, basis: "D6's inverse-marker shape switches off the marker-keyed reviewer criteria re-pointing; the rider shape mis-routes its own bootstrap commit; D6 names no consumer-set rewiring"}
  - {id: A11, sev: should-fix, loc: docs/adr/explanation-style/_workflow/research-log.md:252, anchor: "### A11 ", cert: C13, basis: "the §1.7 amendment's landing is unordered; until it lands, live §1.7 binds the branch and review-workflow-consistency or Phase-2 reference checks can flag the plan — same window class D1 now records for the blurb sync"}
  - {id: A12, sev: should-fix, loc: docs/adr/explanation-style/_workflow/research-log.md:235, anchor: "### A12 ", cert: C14, basis: "opt-out criterion (2) is intent-keyed and its 'prompt-text case' admits execution-procedure edits, which recreate the §1.7 hazard the schema exclusion does not cover"}
  - {id: A13, sev: suggestion, loc: docs/adr/explanation-style/_workflow/research-log.md:297, anchor: "### A13 ", cert: C15, basis: "the 11-site chat-blurb append string is specified with colliding backticks; a planner copying bytes copies the glitch"}
verdicts:
  - {id: A1, verdict: VERIFIED}
  - {id: A2, verdict: VERIFIED}
  - {id: A3, verdict: VERIFIED}
  - {id: A4, verdict: VERIFIED}
  - {id: A5, verdict: VERIFIED}
  - {id: A6, verdict: VERIFIED}
  - {id: A7, verdict: VERIFIED}
  - {id: A8, verdict: VERIFIED}
  - {id: A9, verdict: VERIFIED}
overall: FAIL
evidence_base: {section: "## Evidence base", certs: 5, matches: 1}
cert_index:
  - {id: C12, verdict: WEAK,          anchor: "#### C12 "}
  - {id: C13, verdict: CONSTRUCTIBLE, anchor: "#### C13 "}
  - {id: C14, verdict: WEAK,          anchor: "#### C14 "}
  - {id: C15, verdict: FRAGILE,       anchor: "#### C15 "}
  - {id: C16, verdict: HOLDS,         anchor: "#### C16 "}
flags: [CONTRACT_OK]
-->

# Research-log adversarial review — explanation-style, iteration 2

All nine iteration-1 findings are resolved: nine VERIFIED, zero STILL OPEN, zero REJECTED. Every verdict below was grounded by re-reading the revised log against the cited files (conventions.md §1.5/§1.7, the migrate-workflow skill, workflow-drift-check.md, house-style.md, design-review.md, implementer-rules.md, step-implementation.md, the marker-consumer grep, and a fresh run of S1's three pinned greps). The revision's new text — D6 and the expanded S1 — takes its first adversarial pass here and yields four new findings: three should-fix, all against D6's opt-out mechanics, and one suggestion against S1's chat-blurb append string. No blocker, so D5/D6's substance stands; the should-fixes gate, so the overall verdict is FAIL pending one more revision of D6 (marker shape + consumer rewiring, amendment ordering, criterion-2 tightening).

## Verdicts

- **A1 — VERIFIED.** The false "§1.7(b) sanctions marker omission" claim is removed. D5 now states the current §1.7 binds the branch (matching the forfeiture-plus-duties text at `conventions.md:923-931` and the §1.7(h) binding clause at `:1117-1120`) and routes legitimacy through D6's amendment — iteration 1's proposed option (i), with the user's choice over the waiver alternative recorded in D5's rejections. The re-decision happened with the real options on the table, which is what A1 demanded. The defects remaining in D6's mechanics are fresh findings on fresh text (A10-A12), not a reopening of A1.
- **A2 — VERIFIED.** The Suppress habituation hazard is replaced by stamp-advance with Suppress bounded to the interim window, and the duty is recorded as mandatory in D6. Mechanism verified against the machinery (certificate C16): `/migrate-workflow` derives its range from per-artifact stamps through HEAD on the branch itself (`SKILL.md:48`), its §4.3 classification includes a `noop` short-name for commits with no artifact impact, §4.5/§4.8 advance every stamp to HEAD, and the drift gate's fold over the same stamp base then sees an empty range — silent until genuine later drift re-arms it.
- **A3 — VERIFIED.** All three pinned counts reproduce exactly on the branch tip: `Banned analysis patterns` = 50 files, `banned-section heading slugs` = 30, `follows the AI-tell subset of` = 11. The three missed sites are added to S1 (readability-feedback Rule sync map plus its governance grep with the `conventions.md:570` twin folded in; the ai-tells Catalogue lookups table). The "Orientation is a floor, not a ban" point is recorded and the 30-site canonical replacement sentence is byte-copyable. The 11-site append spec carries a backtick glitch — recorded as new suggestion A13, not a hold on A3.
- **A4 — VERIFIED.** D1 carries the atomic-sync constraint addressed to the planner: one commit, or at minimum canonical sites plus all blurbs inside a single track with the four-vs-five window closed before that track's Phase C.
- **A5 — VERIFIED.** D2's evidence now matches `conventions.md:557-570` (two tiers, no chat-scale row; chat carriers named as the 11 blurbs plus `house-conversation.md`; code-comment carrier as the §1.5 Tier-B row plus the hook's `tier_b_body`), and the entry commits to a code-comment-scale restatement of the orientation criterion (no out-of-file context; gloss the load-bearing entity).
- **A6 — VERIFIED.** D3 names the full reconciliation set, each item verified against the live file: rewrite the `house-style.md:379` scoping sentence, give `## Orientation` its own finding category (replacing the § Why-before-what citation at `:427-431`), and move the Self-check entry out of item 8's design-only bracket (`:444`).
- **A7 — VERIFIED.** D4's Why is bounded to the creation-time Step-4b track surface, credits the D1/D2 always-on wiring for the Phase-3 exemplar surface, and records the applies-to asymmetry (the sibling Human-reader block applies to the three design-target kinds only, `design-review.md:168`) plus the three sync sites including the line-406 count.
- **A8 — VERIFIED.** D1's option-B rejection now carries the per-spawn self-containedness trade-off with the boot-cost cross-reference, so a future follow-up inherits the real fork.
- **A9 — VERIFIED.** D5's new-regex paragraph ships both `dsc-ai-tell` additions at the documented demotable severity discipline, calibrated on the false-positive count from the branch's own Phase-4 `design-final.md` authoring.

## Findings

### A10 [should-fix]
**Certificate**: C12 (Challenge: D6 — opt-out marker mechanics)
**Target**: D6 — "a named opt-out marker, the inverse of today's workflow-modifying marker" (with D5's title: "no workflow-modifying marker")
**Challenge**: The §1.7(b) marker is not only the staging switch. It is also the switch for the reviewer criteria re-pointing: the "Workflow-machinery criteria (workflow-modifying plans)" blocks at `technical-review.md:113`, `risk-review.md:110`, and `adversarial-review.md:282` fire only "when the plan's `### Constraints` carries the canonical §1.7(b) workflow-modifying marker sentence", and they are what make Phase-3A strategic reviews verify prose references as paths/anchors rather than Java FQNs and supersede the Java criteria (WAL, crash, hot-caller lenses) with the five prose criteria. Under D6's inverse-marker shape, an all-prose plan runs its entire Phase-3A review series with that switch off. The opposite shape has its own trap: keeping the canonical marker and adding a rider deadlocks at bootstrap, because the live rulebook keys write routing on the marker prefix alone (`implementer-rules.md:256-300`) and would route the very commit that introduces rider awareness into the staged subtree, defeating live-edit before the rider exists. Either shape demands a named consumer-set rewiring; D6 names none, so a design deriving from it inherits whichever silent breakage the author happens to pick.
**Evidence**: Marker-consumer grep returns 11 files. Criteria-switch consumers: `technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282`. Staging-mechanics consumers the opt-out legitimately disables or degrades: `implementer-rules.md` path mapping (:256-300) and live-workflow-path pre-commit gate (:387), `step-implementation.md:312-329` and the staged-delta prep at `:466-486`, `track-code-review.md:250-260`, and six staged-read precedence blocks that already degrade to live reads when nothing is staged.
**Proposed fix**: Amend D6 to record the marker shape and the consumer audit. Two coherent shapes: (i) keep the canonical marker, add an opt-out rider, and land rider awareness in `conventions.md §1.7(b)/(c)/(e)`, `implementer-rules.md` (path mapping + gate), and `step-implementation.md` in the branch's first commit so the live rulebook never sees marker-without-rider; or (ii) keep the inverse marker and extend the three criteria-switch blocks (plus `track-code-review.md`) to also fire on it. D6 must pick one and enumerate which consumers stay active versus which the opt-out disables.

### A11 [should-fix]
**Certificate**: C13 (Violation scenario: pre-amendment window)
**Target**: D6 — "Implemented in: the track that edits conventions.md" (no ordering pin) and the claim "no reviewer or gate flags the plan for violating a convention it has amended"
**Challenge**: The self-application claim holds only "once committed", as D6 itself phrases it, and nothing orders that commit. Between plan authoring (Step 5) and the conventions track's land, live §1.7 still reads as the binding stage-everything convention and the opt-out marker in the plan's `### Constraints` cites a clause that does not exist in any live file. The branch built a recorded constraint for exactly this window class in D1 (the four-vs-five blurb window, A4); D6 has the same shape of self-inflicted review noise and records nothing.
**Violation construction**: (1) Phase 1 derives the plan; `### Constraints` carries the opt-out marker referencing §1.7's opt-out clause; (2) Phase-2 consistency review verifies plan references resolve — the cited clause is absent from live `conventions.md`, a phantom-reference finding; (3) alternatively, a workflow-editing track (say the design-review.md block) lands before the conventions track; its Phase C dispatches `review-workflow-consistency`, whose charter reads cross-file beyond the diff; (4) the agent compares live §1.7 ("branches must stage"; §1.7(h) binds every branch opened after landing) against a diff of live `.claude` edits with no workflow-modifying marker and flags the branch; (5) each occurrence costs a fix iteration or an orchestrator override until the amendment lands. Feasibility: CONSTRUCTIBLE.
**Proposed fix**: Pin the ordering in D6: the §1.7 amendment (with A10's consumer rewiring) lands in the branch's first workflow-editing commit, or the conventions track is ordered first among workflow-editing tracks. Until it lands, the Constraints opt-out note is written self-justifying — citing D6 in the research log and naming the in-flight amendment — so a reviewer reading unamended §1.7 sees an acknowledged deviation rather than an omission.

### A12 [should-fix]
**Certificate**: C14 (Challenge: D6 — criterion (2) breadth)
**Target**: D6 — opt-out criterion (2): "self-application of the edits during the branch is the intent (the prose-rule / prompt-text case)"
**Challenge**: §1.7's preamble names three protections: plan citations resolve against a stable surface, reviewers see one consistent rule body, and the drift gate stays quiet on the branch's own authoring (`conventions.md:835-844`). Criterion (1) covers only the artifact-schema/drift slice of that, and criterion (2) is keyed on self-declared intent — always claimable — while its parenthetical explicitly invites "prompt-text". A future branch rewriting `step-implementation.md`'s execution procedure or `implementer-rules.md`'s pre-commit gates passes both criteria (no artifact schema touched; self-application asserted), yet a buggy mid-branch live edit there breaks the branch's own remaining execution and mixes procedure versions across tracks — the failure staging exists to prevent, and one that judgment-layer prose does not share (a wrong style rule produces noisy findings; a wrong gate refuses commits or corrupts resume). D6's Risks paragraph claims the schema exclusion is what keeps the criteria tight; this case shows the schema exclusion alone is not tight.
**Evidence**: `conventions.md:835-844` (the three protections); D6's Risks sentence ("the schema-change exclusion is load-bearing"); the consumer-class split — style rules, reviewer criteria, and blurbs are consumed as judgment at authoring/review time, while the implementer rulebook, phase state machines, and resume/gate protocols drive the branch's own execution.
**Proposed fix**: Tighten criterion (2) from intent to consumer class: the opt-out covers edits whose in-branch consumers are judgment-layer (style rules, review criteria, prompt blurbs, reviewer blocks); edits to execution-procedure files stay staged, or require a per-file justification naming which of the branch's own remaining phases consume the edited file and why mixed-version execution is acceptable. One sentence in D6 feeding the clause text.

### A13 [suggestion]
**Certificate**: C15 (Assumption test: S1 byte-copyability)
**Target**: S1 — the 11-site chat-blurb append specification
**Challenge**: S1's post-A3 contract is "copy bytes, not intent", and the 30-site replacement sentence honors it, but the 11-site append is written with colliding backticks (`research-log.md:297`), so the raw text is garbled markdown rather than a clean literal. A planner copying bytes copies the glitch into 11 files.
**Evidence**: `research-log.md:297`; contrast with the cleanly quoted 30-site canonical sentence at `:293-296`.
**Proposed fix**: Restate the append as an unambiguous literal — a fenced one-liner or an explicitly delimited string — for example: append the text `, and ## Orientation` with `## Orientation` backtick-wrapped, shown in a fenced block.

## Evidence base

#### C12 Challenge: D6 — opt-out marker mechanics
- **Chosen approach**: The plan records a named opt-out marker described as "the inverse of today's workflow-modifying marker"; D5's title confirms the canonical marker is omitted.
- **Best rejected alternative**: Keep the canonical workflow-modifying marker (the plan is workflow-modifying by §1.7's path-based definition) and add the opt-out as a rider that disables only the staging mechanics.
- **Counterargument trace**:
  1. The marker's consumers split into two families. Criteria-switch: `technical-review.md:113`, `risk-review.md:110`, `adversarial-review.md:282` re-point reference verification and supersede Java criteria with the five prose criteria, all conditional on the marker. Staging mechanics: `implementer-rules.md:256-300` (write routing) and `:387` (live-path pre-commit gate), `step-implementation.md:312-329` and `:466-486`, `track-code-review.md:250-260`, six staged-read precedence blocks.
  2. Inverse shape: the criteria-switch never fires, so every Phase-3A strategic review of this all-prose branch runs Java-oriented criteria — `findClass`-style resolution on prose references, WAL/crash lenses on style rules.
  3. Rider shape without same-commit consumer rewiring: the live rulebook keys routing on the marker prefix alone, so the implementer routes the rider-awareness edits themselves into `staged-workflow/`, defeating live-edit at bootstrap.
  4. D6 records neither the shape choice nor the consumer audit; the design author inherits a silent breakage either way.
- **Codebase evidence**: marker grep (11 files); the conditional clauses quoted above; `implementer-rules.md:264-268` (stable-prefix match).
- **Survival test**: WEAK — the D6 decision (make the opt-out real) survives; its marker mechanics underspecify the consumer set (finding A10).

#### C13 Violation scenario: the pre-amendment window
- **Invariant claim** (D6): once the §1.7 amendment is committed, no reviewer or gate flags the plan for violating the convention it amends.
- **Violation construction**:
  1. Start state: plan committed at Step 5; `### Constraints` carries the opt-out marker; the conventions track has not landed; live §1.7 is develop's text.
  2. Action sequence: Phase-2 consistency review resolves plan references; the opt-out marker cites a §1.7 clause absent from the live file. Or: a workflow-editing track lands before the conventions track and its Phase C dispatches `review-workflow-consistency`, which reads cross-file beyond the diff.
  3. Intermediate state: live §1.7 says workflow-modifying branches stage and reviewers verify the marker (`conventions.md:923-931`, `:1117-1120`); the branch's diff shows live `.claude` edits with no canonical marker.
  4. Violation point: a phantom-reference or convention-violation finding lands against the branch's intentional interim state.
  5. Observable consequence: a fix iteration or orchestrator override per occurrence until the amendment lands — the same self-inflicted review noise D1's atomic-sync constraint was added to prevent for the blurb window.
- **Feasibility**: CONSTRUCTIBLE — every step is documented behavior; D6's "later phases" wording concedes the window exists without bounding it (finding A11).

#### C14 Challenge: D6 — criterion (2) admits execution-procedure edits
- **Chosen approach**: Opt-out criteria = (1) no `_workflow/**` artifact schema change, and (2) self-application intended, glossed "(the prose-rule / prompt-text case)".
- **Best rejected alternative**: A consumer-class criterion — judgment-layer prose qualifies; execution-procedure prose (implementer rulebook, state machines, resume/gate protocols) stays staged or needs per-file justification.
- **Counterargument trace**:
  1. §1.7 protects three things (`conventions.md:835-844`); criterion (1) covers only the schema/drift slice.
  2. Criterion (2) is self-declared intent, and "prompt-text" sweeps in procedural prompts: a branch rewriting `implementer-rules.md` gates passes both criteria.
  3. A buggy live procedural edit mid-branch breaks the branch's own remaining tracks and mixes procedure versions — hard failure, unlike a wrong style rule's soft degradation.
  4. D6's own Risks sentence asserts the schema exclusion delivers tightness; the trace shows it does not.
- **Codebase evidence**: `conventions.md:835-844`; D6 text at `research-log.md:231-235` and Risks at `:246-251`.
- **Survival test**: WEAK — the opt-out decision survives; the criteria need the consumer-class discrimination (finding A12).

#### C15 Assumption test: S1's canonical strings are byte-copyable
- **Claim**: Both canonical strings (the 30-site replacement sentence and the 11-site append) can be pasted byte-identically across all sites.
- **Stress scenario**: Read the raw log text of each string as a planner copying bytes would.
- **Code evidence**: The 30-site sentence at `research-log.md:293-296` is cleanly delimited. The 11-site append at `:297` nests `` `## Orientation` `` inside a backtick-wrapped string, producing colliding delimiters; the literal bytes are ambiguous.
- **Verdict**: FRAGILE — holds for the 30-site sentence, breaks for the 11-site append (finding A13).

#### C16 Assumption test: stamp-advance via /migrate-workflow works branch-side
- **Claim** (D5/D6, the A2 fix): one `/migrate-workflow` run after the last workflow-editing commit reduces to a stamp advance, silences the drift gate, and leaves it armed for real develop drift.
- **Stress scenario**: The skill's docstring trigger is develop-side format changes; the branch uses it on its own prose commits.
- **Code evidence**: `SKILL.md:48` — the range derives from per-artifact stamps and HEAD, "never from a develop-relative fork point", so branch-side use is in-spec mechanically. §4.3's canonical classifications include `noop` for commits with no artifact-format impact; §4.5 advances stamps in lockstep, §4.8 batch-rewrites every stamp to HEAD. The drift gate folds the same stamps to its range base, so stamps-at-HEAD yield an empty range (silent), and later pathspec commits re-fire it; resolutions remain [migrate]/[defer]/[suppress] with no default.
- **Verdict**: HOLDS — the mechanism claim in D5/D6 is accurate (verdict A2 VERIFIED).
