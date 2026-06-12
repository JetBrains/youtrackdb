<!-- MANIFEST
findings: 9   severity: {blocker: 1, should-fix: 5, suggestion: 3}
index:
  - {id: A1, sev: blocker,    loc: .claude/workflow/conventions.md:1118, anchor: "### A1 ", cert: C6,  basis: "D5's 'sanctioned path' premise contradicts the §1.7(b) planner duty and the §1.7(h) binding clause; re-decide with the real options on the table"}
  - {id: A2, sev: should-fix, loc: .claude/workflow/workflow-drift-check.md:366, anchor: "### A2 ", cert: C7,  basis: "per-session Suppress habituates the user and masks post-rebase drift; the stamp-advance alternative (migrate-workflow §4.8) is never listed"}
  - {id: A3, sev: should-fix, loc: .claude/skills/readability-feedback/SKILL.md:41, anchor: "### A3 ", cert: C2,  basis: "sync inventory is approximate and short by at least three sites, including the governance grep that is itself the drift detector"}
  - {id: A4, sev: should-fix, loc: docs/adr/explanation-style/_workflow/research-log.md:47, anchor: "### A4 ", cert: C10, basis: "no atomicity constraint recorded; a split landing makes the branch's own Phase-C consistency reviews flag the four-vs-five window"}
  - {id: A5, sev: should-fix, loc: .claude/workflow/conventions.md:561, anchor: "### A5 ", cert: C3,  basis: "D2 misstates the §1.5 tier structure, and the orientation criterion does not transfer to code-comment scale without restatement"}
  - {id: A6, sev: should-fix, loc: .claude/output-styles/house-style.md:379, anchor: "### A6 ", cert: C4,  basis: "generalizing leaves the document-shape scoping sentence contradicting the new top-level rule unless D3 names the reconciliation edit"}
  - {id: A7, sev: suggestion, loc: .claude/workflow/prompts/design-review.md:168, anchor: "### A7 ", cert: C5,  basis: "D4's coverage claim is overstated: the Step-4b cold-read runs before the exemplar prose accrues; the always-on wiring is what covers it"}
  - {id: A8, sev: suggestion, loc: docs/adr/explanation-style/_workflow/research-log.md:58, anchor: "### A8 ", cert: C1,  basis: "the stronger defense of full sync over centralization is per-spawn self-containedness, not only sync discipline; record it so option B's follow-up case is honest"}
  - {id: A9, sev: suggestion, loc: .claude/scripts/design-mechanical-checks.py:99, anchor: "### A9 ", cert: C11, basis: "the new regexes self-apply at the branch's own Phase-4 design-final authoring; a false-positive-prone pattern blocks with exit 1; pin the demote fallback"}
evidence_base: {section: "## Evidence base", certs: 11, matches: 4}
cert_index:
  - {id: C1,  verdict: YES,           anchor: "#### C1 "}
  - {id: C2,  verdict: WEAK,          anchor: "#### C2 "}
  - {id: C3,  verdict: WEAK,          anchor: "#### C3 "}
  - {id: C4,  verdict: WEAK,          anchor: "#### C4 "}
  - {id: C5,  verdict: YES,           anchor: "#### C5 "}
  - {id: C6,  verdict: NO,            anchor: "#### C6 "}
  - {id: C7,  verdict: CONSTRUCTIBLE, anchor: "#### C7 "}
  - {id: C8,  verdict: HOLDS,         anchor: "#### C8 "}
  - {id: C9,  verdict: HOLDS,         anchor: "#### C9 "}
  - {id: C10, verdict: CONSTRUCTIBLE, anchor: "#### C10 "}
  - {id: C11, verdict: FRAGILE,       anchor: "#### C11 "}
flags: [CONTRACT_OK]
-->

# Research-log adversarial review — explanation-style, iteration 1

Phase 0→1 gate over `docs/adr/explanation-style/_workflow/research-log.md` (D1-D5, S1-S3, no open questions). Lens: Workflow machinery (rule coherence, instruction completeness, context-budget impact). One blocker: D5's central evidence claim about §1.7(b) is contradicted by the convention's own text, so the no-marker/live-edit decision must be re-grounded before `design.md` derives from it. Five should-fix findings strengthen D1-D3 and D5's Suppress sub-decision; three suggestions are recorded. Every claim below was verified by reading the named files; no PSI was needed (no Java symbol audits).

## Findings

### A1 [blocker]
**Certificate**: C6 (Challenge: D5 — marker omission)
**Target**: D5 — No staging; live-edit all surfaces, no workflow-modifying marker
**Challenge**: D5's load-bearing claim, "§1.7(b) documents marker-omission as a sanctioned path", is false. §1.7(b)'s forfeit sentence (`conventions.md:923-927`) describes the degraded consequences of omission; the very next sentence assigns a duty: "Planners who recognise their plan as workflow-modifying are responsible for adding the marker before Phase A review; reviewers verify the marker's presence on any plan whose Plan-of-Work references `.claude/workflow/**`, `.claude/skills/**`, or `.claude/agents/**` paths" (`conventions.md:927-931`). §1.7(h) closes the door explicitly: "every workflow-modifying branch that opens after this section lands is bound by the full convention" (`conventions.md:1118-1120`). This branch opened after the convention landed and edits all three covered prefixes, so it is bound. A design derived over D5 as recorded carries a plan whose `### Constraints` deliberately violates the convention its own reviewers are told to verify.
**Evidence**: Mechanically, nothing breaks on omission: every marker consumer is conditional ("when the marker is present" — `prompts/adversarial-review.md:280-282`, `prompts/structural-review.md:127`, `prompts/technical-review.md:111-113`, and siblings), and §1.7(c) confirms the inverse case (staged content without declaration) is the only unreachable one. The conflict is normative, not mechanical, and §1.7's role annotation (`orchestrator,implementer,planner,final-designer` — reviewers excluded) makes reviewer enforcement uncertain rather than absent. Two of D5's supporting arguments do survive scrutiny: the largest surfaces (`house-style.md`, `house-conversation.md`, `design-mechanical-checks.py`) sit outside §1.7(a)'s covered prefixes ("No other prefixes participate", `conventions.md:877-880`), so even full staging gives only partial isolation; and the schema-safety premise holds (certificate C8). The substance of live-editing has a real case; the recorded justification does not.
**Proposed fix**: Re-decide D5 with the real options stated: (i) keep live-edit and add an in-scope §1.7 amendment defining an explicit, user-approved opt-out for prose-rule changes whose self-application is the goal (a small `.claude/workflow/**` edit this branch can carry; under live-edit it self-applies immediately, resolving the contradiction for later phases); (ii) keep live-edit and record an explicit user waiver in the log plus a plan-`### Constraints` deviation note that pre-empts reviewer findings; (iii) adopt staging for the covered prefixes and accept the hybrid split D5 rejected. Any of the three can clear the gate; the current "sanctioned path" wording cannot.

### A2 [should-fix]
**Certificate**: C7 (Violation scenario: Suppress masks real drift)
**Target**: D5 — the "Suppress each session" resolution
**Challenge**: Suppress is the weakest of the three drift-gate resolutions for this branch, and the log never lists the alternative that both silences the false positive and keeps the gate armed: advancing the workflow-sha stamps. Stamps move only at artifact creation, migration replay, and no-drift normalization (`.claude/skills/create-plan/SKILL.md:25`), and normalization runs only when the range log is empty (`workflow-drift-check.md:122-137`). So after the branch's first live workflow commit the gate fires every session for the rest of the branch, the user must pick a resolution with no default (`workflow-drift-check.md:262-266`), and the planned answer is Suppress every time. A habituated Suppress is indistinguishable from a considered one: if develop is ever rebased in mid-branch, develop's genuine workflow-format commits enter the same range and the same prompt, and the reflexive Suppress masks them. The log's only guard is the sentence "Re-evaluate if a mid-branch rebase of develop ever happens", recorded in an artifact nothing re-reads at the suppress moment.
**Evidence**: `/migrate-workflow` §4.5 "Advance stamps in lockstep" and §4.8 "Final stamp-to-HEAD batch" rewrite every artifact's line-1 stamp to HEAD after replaying the range. The branch's own commits change prose rules, not `_workflow/**` artifact schema (certificate C8), so a replay over them is a no-op for the artifacts and the run reduces to a stamp advance. After it, the gate is silent for subsequent sessions and re-arms for any later real drift; a post-rebase drift prompt would then be a true signal again. The skill's stated trigger is develop-side format changes, so this is an off-label use of a generic mechanism (range = per-artifact stamp base through HEAD, computed on the branch itself per `SKILL.md:48`).
**Proposed fix**: Strengthen D5's cost paragraph: either adopt the stamp-advance resolution (one `/migrate-workflow` run after the last workflow-editing track, recorded in the plan), or keep Suppress and move the re-evaluation trigger somewhere the suppress moment actually surfaces it (a plan-`### Constraints` line stating "on any rebase of develop, the next drift prompt must be read, not suppressed"). Naming the alternative and rejecting it with reasons is the minimum.

### A3 [should-fix]
**Certificate**: C2 (Challenge: D1 — inventory basis)
**Target**: D1 / S1 — the ~50-site sync inventory
**Challenge**: The Baseline section promised the sync-site inventory would be "enumerated and pinned during research"; S1 delivers approximate category counts ("~19, ~10, ~10"), and the roster misses at least three sites that the change must touch: (1) `readability-feedback/SKILL.md` — its `## Rule sync map` (lines 41-55) is the codified procedure for adding a rule, and its governance grep (`grep -rn 'Banned vocabulary\|Banned sentence patterns\|Banned analysis patterns\|Em-dash discipline' .claude/ CLAUDE.md`, line 54) must gain `Orientation` in the same commit or every future rename audit silently skips the fifth section; (2) the same grep's twin at `conventions.md:570`; (3) `ai-tells/SKILL.md` — one of the four named readers at `house-style.md:20`, whose `## Catalogue lookups` routing table (line 27) needs an Orientation row for Pass 1 to walk the new section. A subtler defect: the ~29-site blurb says "the four banned-section heading slugs" (`prompts/adversarial-review.md:43` and siblings); a count bump to "five banned-section heading slugs" would be false, because `## Orientation` is a positive floor, not a ban. The blurb prose must be re-worded once, canonically, and pasted identically, or fifty hand edits produce fifty slightly different sentences — exactly what `review-workflow-consistency` flags.
**Evidence**: `grep -rln "Banned analysis patterns" .claude/ CLAUDE.md` returns 50 files; `grep -rln "four banned-section heading slugs"` returns 29; `dr-audit.md:22` carries a variant phrasing the slug-grep misses, proving phrasing variants already exist and a single-pattern inventory under-counts. The no-generator claim is verified (certificate C9), so every one of these is a hand edit.
**Proposed fix**: Before Phase 1 derivation, pin the inventory as exact grep output (union of the section-name grep, the slug-phrase grep, and an "AI-tell subset" grep), add the three missed sites to S1, and record one canonical replacement blurb sentence in the log so the design copies bytes, not intent. Extend the governance grep (both copies) and the Rule sync map as in-scope edits.

### A4 [should-fix]
**Certificate**: C10 (Violation scenario: mid-branch enumeration window)
**Target**: D1 + D5 jointly — landing order of the ~50-site sync
**Challenge**: D1 and D5 together create a hazard neither records: under live-edit, any split of the ~50-site sync across steps or tracks leaves a committed window where the canonical sites enumerate five names while remaining blurbs enumerate four. The log's own rejection of option C concedes that `review-workflow-consistency` and the governance grep flag exactly this four-of-five state as drift. Phase C of any track that lands inside the window dispatches the workflow-review agents over a workflow-machinery diff, and the consistency agent's charter ("cross-file consistency: phantom references … glossary drift") reads beyond the diff by design. The branch would be flagged by its own review machinery for an intermediate state it created deliberately, costing a fix iteration or an orchestrator override per affected track.
**Violation construction**: (1) Track 1 lands `house-style.md` + canonical sites + hook + tests (five names); (2) Track 1 Phase C runs `review-workflow-consistency`; (3) the agent compares `conventions.md §1.5` (five) against ~40 untouched blurbs (four) and emits a should-fix; (4) the fix either expands Track 1 into Track 2's scope or is overridden as known-deferred, repeating at every subsequent track until the last blurb lands. Feasibility: CONSTRUCTIBLE.
**Proposed fix**: Record the derived constraint in the log so the planner inherits it: the subset enumeration sync lands as one atomic step (one commit), or at minimum canonical sites and all blurbs land inside a single track with the consistency-relevant window closed before that track's Phase C. The edits are one-line prose changes; a single commit is feasible.

### A5 [should-fix]
**Certificate**: C3 (Challenge: D2 — code-comment tier)
**Target**: D2 — Orientation joins both subset tiers
**Challenge**: Two defects in the rationale, neither fatal to the decision. First, the evidence is misstated: D2 says Orientation joins "both tiers conventions.md §1.5 defines it on: chat-scale prose and `*.java`/`*.kt` code comments". §1.5's table (`conventions.md:561-566`) has no chat-scale row; its tiers are full-style Markdown/PR/commit/YouTrack and the `*.java`/`*.kt` AI-tell subset. Chat-scale lives in the per-file blurbs and `house-conversation.md`, and the hook's Tier A is Markdown-full, not chat (`house-style-write-reminder.sh:260`). Second, the rule's defining criterion does not transfer: YTDB-1106's defect is prose "too terse to follow without opening the code", but a Javadoc reader has the code open by definition. Without a tier-appropriate restatement (for example: rationale comments must not assume context outside the file — distant call-site behavior, issue history, reviewer-thread knowledge), the Tier-B row and the hook's `tier_b_body` would name a rule whose test is incoherent on that surface, and reviewers cannot apply it consistently. Third, the "exact inconsistency D1 is avoiding" framing is a false equivalence: D1's discipline binds sites enumerating the same subset; a deliberate tier difference recorded once in the §1.5 table is a documented scope split, not enumeration drift.
**Evidence**: `conventions.md:557-566` (two tiers, no chat row); `house-style-write-reminder.sh:260-262` (Tier A = Markdown full-style body, Tier B = the four names verbatim); `test_house_style_hook.py:694-697` (anchor-drift guard pins the four headings). The decision's direction is still defensible: CLAUDE.md's "Comment non-obvious code" already points the same way for rationale comments.
**Proposed fix**: Correct D2's evidence (name the blurbs + `house-conversation.md` as the chat-tier carriers, §1.5's table as the code-comment carrier) and add one sentence committing the design to a code-comment-scale restatement of the orientation criterion for the Tier-B row and `tier_b_body`.

### A6 [should-fix]
**Certificate**: C4 (Challenge: D3 — generalization scoping)
**Target**: D3 — Generalize § Explanatory register into ## Orientation
**Challenge**: Generalizing the terse-prose principle to a top-level always-on rule leaves a sentence in the file contradicting it unless D3 names the reconciliation. `house-style.md:379` scopes the document-shape family: "They are not enforced on issue bodies, PR descriptions, or status prose, which use the BLUF rule alone." Once `## Orientation` sits at top level and applies to every prose surface (the issue's explicit intent), that sentence is stale on its face: those surfaces would be governed by BLUF plus Orientation plus the always-on tells. The current `### Explanatory register` (`house-style.md:427-431`) also categorizes too-terse prose as "a finding under § Why-before-what", a document-shape rule; the generalized rule needs its own finding category, or every Orientation finding outside `docs/adr/**` cites a section that line 379 says does not apply there. Same family: Self-check item 8 (`house-style.md:444`) lists explanatory register under "Document shape (design/ADR only)" and needs the Orientation entry placed in an always-on item instead.
**Evidence**: `house-style.md:377-379` (scoping sentence), `:427-431` (current rule and its Why-before-what cross-reference), `:433-448` (Self-check items 1-10, with the design-only bracket on item 8). The decision itself (one general rule, one design specialization that points up) is sound and the duplication risk it avoids is real.
**Proposed fix**: Amend D3 to name the reconciliation set: rewrite the line-379 scoping sentence to carve Orientation out of the document-shape exclusion, give `## Orientation` its own finding category, and move/add the matching Self-check item outside the design-only bracket. Cheap to record now; silent contradiction later if the design author derives only "generalize + cross-link" from the entry.

### A7 [suggestion]
**Certificate**: C5 (Challenge: D4 — both-targets coverage)
**Target**: D4 — New reviewer block runs for design AND tracks
**Challenge**: The decision is right and cheap, but its Why overstates what the tracks half buys. The Step-4b `target=tracks` cold-read runs once, at plan creation, before the Step 5 commit (`create-plan/SKILL.md:648-659`; `design-review.md:182-188`). The YTDB-1106 exemplar surface named in D4's Why (terse decision-log findings) accrues during Phase 3: live Decision Log entries, episodes, and review findings are written long after Step 4b, and no cold-read ever re-runs on them. What enforces the orientation floor there is D1/D2's always-on blurb wiring on the writers, not D4's reviewer block. Separately, the new block inherits an asymmetry the design must spell out: the sibling `### Human-reader cold-read additions` applies only to the three design-target mutation kinds (`design-review.md:168`), so the new block cannot copy its applies-to line and needs its own statement covering `target=tracks`, plus the Rule sync map's design-review row (which currently knows only Human-reader bullets, `readability-feedback/SKILL.md:47`) gains the new block.
**Evidence**: `design-review.md:165-180` (Human-reader block, design kinds only), `:406` ("the five Human-reader rules" count to sync), `:182-264` (track-scoped cold-read, creation-time target set).
**Proposed fix**: Reword D4's Why to claim the creation-time track surface only, crediting the always-on wiring for the Phase-3 exemplar; note the applies-to asymmetry so the design writes an explicit two-target applicability line for the new block.

### A8 [suggestion]
**Certificate**: C1 (Challenge: D1 — full sync vs centralize)
**Target**: D1 — Faithful full sync of the subset enumeration
**Challenge**: The strongest argument for rejecting option B (centralize-then-add) is absent from the entry. "Scope expansion" is a process reason; the structural reason the ~50 inline enumerations exist is per-spawn self-containedness: a sub-agent reads its prompt blurb and knows the applicable sections without opening another file, while a pointer-only blurb either adds one file read to every spawn (a real per-spawn context-budget cost across ~29 agent/prompt files) or loses the inline names and weakens compliance. Recording this matters because D1 defers B to "a possible follow-up": whoever picks up that follow-up should inherit the actual trade-off, not just "it was out of scope".
**Evidence**: `house-style.md:20` names the four readers that consume rules "without restating them" — yet 50 files restate the subset's section names anyway, which only makes sense as a boot-cost optimization; the MEMORY-tracked sub-agent boot-cost work (YTDB-1094) documents the same economics from the other direction.
**Proposed fix**: Add one sentence to D1's rejection of B naming the per-spawn self-containedness trade-off, so the follow-up issue (if filed) starts from the real fork.

### A9 [suggestion]
**Certificate**: C11 (Assumption test: D5(c) mid-branch script edits)
**Target**: D5 — live-editing `design-mechanical-checks.py` mid-branch
**Challenge**: The half-written-regex hazard the gate asked about is bounded: the script runs only at `edit-design` mutations (`design-mechanical-checks.py:2-9`), the branch's `design.md` is frozen before the script edits land in Phase 3, and `test_dsc_ai_tell.py` gates each commit. The residual is self-application at Phase 4: `design-final.md` is authored through `edit-design` with the branch's own new regexes live (`design-mechanical-checks.py:30-32`), and a blocker-severity false positive exits 1 and blocks the authoring loop. The "X, not Y" faux-symmetry pattern is the risky one; legitimate contrastive "A, not B" clauses are common in this repo's prose. The script already documents the escape hatch for exactly this situation: ship, record the observed false-positive count, "Demote-to-`suggestion` is the documented fallback" (`design-mechanical-checks.py:103-106`).
**Evidence**: Verdict FRAGILE: holds under the existing test-plus-fallback pattern, but only if the new patterns follow it.
**Proposed fix**: Record in the log (one sentence, feeding the design) that the two new regexes ship at the same demotable severity discipline as the Tier-1 vocabulary rule, with the false-positive count observed on the branch's own Phase-4 authoring as the calibration point.

## Evidence base

#### C1 Challenge: D1 — Faithful full sync of the subset enumeration
- **Chosen approach**: Update all ~50 sites that enumerate the four-name subset as a closed set; the subset becomes five everywhere.
- **Best rejected alternative**: (B) centralize-then-add — replace the duplicated enumerations with a pointer to one canonical list, then add Orientation only at the canonical sites.
- **Counterargument trace**:
  1. The next subset change repeats the ~50-edit cost; B makes it one edit. S1 itself concedes the duplication is the root cause ("the same duplication this change is forced to confront").
  2. B touches a similar file count once and leaves a durable win; the §1.5 stable-headings note and the governance grep exist because duplication already hurts (`conventions.md:568-570`).
  3. But B's pointer-only blurbs cost every spawn either an extra file read or the inline names: the enumerations are a per-spawn boot-cost optimization, and that defense is stronger than D1's recorded "scope expansion" reason.
- **Codebase evidence**: `house-style.md:20` (readers consume "without restating" — yet 50 files restate, deliberately); 29 files carry the byte-similar slug blurb (slug-phrase grep).
- **Survival test**: YES — the chosen approach holds; the rationale should add the self-containedness argument (finding A8).

#### C2 Challenge: D1 — Inventory basis for the ~50-site sync
- **Chosen approach**: S1's hand-rostered category inventory (~19 agents, ~10 prompts, ~10 skills/docs, 4 enumerations, 3 canonical, hook, 2 tests) backs D1's edit set.
- **Best rejected alternative**: A grep-pinned exact inventory, unioned over the known phrasing variants, recorded in the log.
- **Counterargument trace**:
  1. `grep -rln "Banned analysis patterns" .claude/ CLAUDE.md` returns 50 files; S1's roster sums to ~49 but omits `readability-feedback/SKILL.md` (Rule sync map lines 41-55 + governance grep line 54) and `ai-tells/SKILL.md` (catalogue routing line 27), both of which the change must edit.
  2. The governance grep itself (`conventions.md:570` and `readability-feedback/SKILL.md:54`) enumerates the four names; left unextended, every future rename audit skips `Orientation`. The drift detector is a sync site.
  3. `dr-audit.md:22` enumerates the subset in a variant phrasing the 29-file slug grep misses, proving single-pattern inventories under-count.
  4. The blurb noun phrase "four banned-section heading slugs" cannot be count-bumped: Orientation is not a banned section, so ~29 sites need a re-worded sentence, and without one canonical replacement sentence fifty hand edits diverge.
- **Codebase evidence**: grep counts above; `prompts/adversarial-review.md:43` (slug blurb shape); S1's roster vs the 50-file list.
- **Survival test**: WEAK — D1 survives, but its execution basis needs the pinned inventory, the missed sites, and the canonical blurb sentence (finding A3).

#### C3 Challenge: D2 — Orientation joins the code-comment tier
- **Chosen approach**: `## Orientation` joins the always-on subset for chat-scale prose and `*.java`/`*.kt` code comments.
- **Best rejected alternative**: Chat-only, with the tier difference recorded once in the §1.5 table.
- **Counterargument trace**:
  1. D2's Why claims §1.5 defines the subset on a chat-scale tier; §1.5's table (`conventions.md:561-566`) has no chat row — chat lives in the blurbs and `house-conversation.md`, and the hook's Tier A is Markdown-full (`house-style-write-reminder.sh:260`). The cited evidence is misstated.
  2. The rule's defining test ("too terse to follow without opening the code") is incoherent for Javadoc, whose reader has the code open; applying it to Tier B without restatement gives reviewers an inapplicable criterion.
  3. D2's drift argument ("the exact inconsistency D1 is avoiding") conflates a deliberate, table-recorded tier difference with enumeration drift across blurbs; the rejected alternative would not produce D1-style drift.
- **Codebase evidence**: `conventions.md:557-566`; `house-style-write-reminder.sh:260-262`; `test_house_style_hook.py:694-697`.
- **Survival test**: WEAK — the decision plausibly survives (rationale via CLAUDE.md's comment-non-obvious-code rule), but its evidence and the missing code-scale criterion need fixing (finding A5).

#### C4 Challenge: D3 — Generalize § Explanatory register into ## Orientation
- **Chosen approach**: One top-level always-on `## Orientation` rule; `### Explanatory register` reduced to a design-specific specialization cross-linking up.
- **Best rejected alternative**: Cross-link only, keeping the general statement inside Document-shape rules.
- **Counterargument trace**:
  1. `house-style.md:379` says document-shape rules "are not enforced on issue bodies, PR descriptions, or status prose, which use the BLUF rule alone"; with Orientation top-level and always-on, that sentence becomes false unless rewritten — a self-contradiction inside the canonical style file.
  2. The current rule categorizes too-terse prose as "a finding under § Why-before-what" (`house-style.md:431`), a document-shape rule; outside `docs/adr/**` that category is excluded by line 379, so the generalized rule needs its own finding category.
  3. Self-check item 8 brackets explanatory register under "(design/ADR only)" (`house-style.md:444`); Orientation's check item must live in an always-on item.
- **Codebase evidence**: `house-style.md:377-379, 427-431, 433-448`.
- **Survival test**: WEAK — generalize is the right call (two parallel statements would drift, as D3 says), but the entry must name the reconciliation edits or the derived design leaves the file contradicting itself (finding A6).

#### C5 Challenge: D4 — Reviewer block runs for design AND tracks
- **Chosen approach**: The new `### Prose AI-tell additions` block runs for `target=design` and `target=tracks`.
- **Best rejected alternative**: Design-only (the log's listed rejection).
- **Counterargument trace**:
  1. The tracks cold-read runs once at Step 4b, before the Step 5 commit (`create-plan/SKILL.md:648-659`); track files at that point carry plan-at-start sections and seeded records only.
  2. The exemplar surface D4's Why cites (terse decision-log findings) accrues during Phase 3 — live DR entries, episodes, review findings — which no cold-read revisits; the always-on blurbs on the writers (D1/D2) are the enforcement there.
  3. The sibling `### Human-reader cold-read additions` applies only to `phase1-creation`/`phase4-creation`/`design-sync` (`design-review.md:168`), so the new block needs its own two-target applies-to line; the Rule sync map's design-review row knows only Human-reader bullets (`readability-feedback/SKILL.md:47`).
- **Codebase evidence**: `design-review.md:165-180, 182-264, 406`.
- **Survival test**: YES — both-targets is still right (it is cheap and covers creation-time track prose); the Why overstates coverage and the asymmetry needs spelling out (finding A7).

#### C6 Challenge: D5 — No staging, no workflow-modifying marker
- **Chosen approach**: Omit the §1.7(b) marker, keep the staging gate inactive, land every edit live, treat the drift-gate trip as a benign false positive resolved by Suppress.
- **Best rejected alternative**: Live-edit with an in-scope §1.7 amendment (or recorded user waiver) that makes the omission explicit and convention-compliant — an option the entry never lists; its listed alternatives are full staging and a hybrid.
- **Counterargument trace**:
  1. D5's Why asserts "§1.7(b) documents marker-omission as a sanctioned path". §1.7(b) actually says omission "forfeit[s] the staging mechanism" (`conventions.md:923-927`), then assigns the planner the duty to add the marker and tells reviewers to verify its presence on any plan referencing the covered paths (`conventions.md:927-931`).
  2. §1.7(h): "every workflow-modifying branch that opens after this section lands is bound by the full convention" (`conventions.md:1118-1120`). This branch opened after; it edits agents, skills, and workflow files; by §1.7's opening definition it is workflow-modifying regardless of what the plan declares.
  3. Mechanically nothing breaks: all marker consumers are conditional (`prompts/*.md` staged-read clauses; §1.7(c)'s two signals), and §1.7's role annotation excludes reviewer roles, so enforcement in practice is uncertain. But a design derived over D5 bakes a convention violation into the plan's Constraints on the strength of a misquote.
  4. Genuine D5 strengths survive: §1.7(a) confirms output-styles/scripts are outside the covered prefixes (`conventions.md:877-880`), so staging gives only partial isolation, and self-application of style rules is a coherent goal. The precedent cuts the other way: the plan-slimization branch (#1140), also workflow-modifying, ran full §1.7 staging end-to-end.
- **Codebase evidence**: as cited; plus certificate C8 (the schema-safety premise holds, so the false-positive characterization of the drift trip is itself correct).
- **Survival test**: NO — the decision as recorded should be reconsidered; re-grounding options that preserve live-edit exist (finding A1).

#### C7 Violation scenario: "Suppress is safe because there is no real develop drift to mask"
- **Invariant claim**: Per-session Suppress masks only the branch's own authoring; real drift cannot be hidden.
- **Violation construction**:
  1. Start state: branch has landed live workflow commits; stamps still at creation SHA (direct mutations never move stamps, `create-plan/SKILL.md:25`; no-drift normalization requires an empty range, `workflow-drift-check.md:122-137`). Gate fires every session; user picks Suppress every time.
  2. Action sequence: weeks in, the user rebases onto develop to pick up an unrelated fix; develop's range includes a genuine workflow-format commit (track-file schema change).
  3. Intermediate state: next session's gate prompt shows N commits on the workflow pathspecs — the branch's own prose commits plus develop's format commit, in the same list shape (`workflow-drift-check.md:248-263`).
  4. Violation point: the habituated user picks Suppress without reading `first_commits`; nothing distinguishes this prompt from the previous twenty. The log's only guard ("Re-evaluate if a mid-branch rebase…") lives in an artifact not surfaced at the prompt.
  5. Observable consequence: the branch's `_workflow/**` artifacts silently miss a required schema migration; the failure surfaces later as "confused reviewers in Phase C, missing required sections during track completion, or auto-resume tripping on a schema field the branch never gained" (`workflow-drift-check.md:24-27`).
- **Feasibility**: CONSTRUCTIBLE — every step is the documented behavior of the existing machinery. Mitigation exists and is unlisted: one `/migrate-workflow` run after the workflow edits land advances all stamps to HEAD (§4.5, §4.8), silencing the gate and re-arming it (finding A2).

#### C8 Assumption test: D5 premise — "changes no `_workflow/**` artifact schema"
- **Claim**: Every planned edit (house-style rules, ~50 blurbs, the design-review block, dsc-ai-tell regexes, §1.5 table, hook, tests) alters prose rules and review criteria, never the section names, mandatory artifacts, or step-file schema that the drift gate protects.
- **Stress scenario**: Search the planned surfaces for anything a `_workflow/**` artifact's resume/review machinery parses: track-file section headings, stamp format, manifest schema, episode fields.
- **Code evidence**: The drift gate's stated protection target is "section names, mandatory artifacts, step-file schema" (`workflow-drift-check.md:28-30`). The planned surfaces define writing rules and reviewer judgment, consumed at authoring/review time, not parsed from artifacts; `design-mechanical-checks.py` runs only inside `edit-design` mutations on fresh edits (`design-mechanical-checks.py:2-9`), never as a resume-path parser. No planned edit touches `§1.6` stamp machinery, `§2.5` manifest schema, or track-file section names.
- **Verdict**: HOLDS — the false-positive characterization of the drift trip is correct; D5's failure is normative (C6), not mechanical.

#### C9 Assumption test: S1 — "no generator emits these blurbs"
- **Claim**: The ~50 enumeration sites are hand-maintained; `workflow-reindex.py` only rebuilds TOC regions and stamps.
- **Stress scenario**: If a generator existed, the sync would be one template edit and D1's cost model (and option B's value) would collapse.
- **Code evidence**: `grep -n "blurb\|house-style\|Banned" .claude/scripts/workflow-reindex.py` returns nothing; the script's surface is TOC/stamp maintenance. The hook's reminder bodies are literals in `house-style-write-reminder.sh:260-262`, pinned by exact-match assertions in `test_house_style_hook.py`.
- **Verdict**: HOLDS — every enumeration edit is a hand edit; D1's cost model stands.

#### C10 Violation scenario: split landing of the subset sync under live-edit
- **Invariant claim** (implicit in D1+D5): the branch can land the five-name sync incrementally without its own machinery objecting.
- **Violation construction**:
  1. Start state: Track 1 lands the canonical sites (five names) live; ~40 blurbs still say four. All committed.
  2. Action sequence: Track 1's Phase C dispatches the workflow-review agents on the workflow-machinery diff; `review-workflow-consistency`'s charter is cross-file consistency including glossary drift, which reads beyond the diff.
  3. Intermediate state: canonical sites and blurbs disagree on the subset's cardinality — the exact four-of-five drift D1's own rejection of option C predicts the agent will flag.
  4. Violation point: a should-fix finding lands against intentional intermediate state; resolution costs a fix iteration that drags Track 2's scope forward, or an orchestrator override, repeated for every track inside the window.
  5. Observable consequence: review noise and scope bleed in the branch's own Phase C, at every track until the last blurb lands.
- **Feasibility**: CONSTRUCTIBLE — the log itself supplies the detection premise (D1, rejection of C). Mitigation is an atomicity constraint the log does not record (finding A4).

#### C11 Assumption test: D5(c) — live-editing `design-mechanical-checks.py` mid-branch is safe
- **Claim**: The branch can land dsc-ai-tell regex additions live without breaking its own design-check machinery.
- **Stress scenario**: A buggy or false-positive-prone regex lands in Phase 3; the branch's own Phase-4 `design-final.md` authoring then runs it (`design-mechanical-checks.py:30-32`), where any blocker finding exits 1 and blocks the `edit-design` loop.
- **Code evidence**: Invocation is `edit-design`-only on fresh edits (`design-mechanical-checks.py:2-9`); `design.md` freezes before Phase 3, so no mid-branch artifact is re-validated; `test_dsc_ai_tell.py` gates commits. The script documents the calibration pattern for exactly this risk: fire unconditionally, record observed false positives, "Demote-to-`suggestion` is the documented fallback" (`design-mechanical-checks.py:99-106`). The "X, not Y" faux-symmetry pattern is the high-FP candidate (legitimate contrastive clauses are common in this repo's prose).
- **Verdict**: FRAGILE — holds under the existing test-plus-demotion discipline, but only if the new patterns adopt it explicitly (finding A9).
