<!-- MANIFEST
findings: 10   severity: {blocker: 0, should-fix: 6, suggestion: 4}
index:
  - {id: A1, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:143", anchor: "### A1 ", cert: V1, basis: "name-only grep criterion provably misses the exemplar-only drop site :185 the track's own snapshot lists"}
  - {id: A2, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:223", anchor: "### A2 ", cert: C2, basis: "D2 hybrid gets a consumer edit at create-plan Step-4b but not at edit-design's own gate-return parse contract"}
  - {id: A3, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:187", anchor: "### A3 ", cert: V2, basis: "augmented promotion runs after the live block's git commit, so 'same promote commit' is unexecutable as written"}
  - {id: A4, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:197", anchor: "### A4 ", cert: V3, basis: "post-rebase re-promotion copies a stale CLAUDE.md mirror over develop's changes; no mirror-refresh step exists"}
  - {id: A5, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:246", anchor: "### A5 ", cert: V4, basis: "live-write leak window on the three out-of-surface files is track-level only; per-step guard is missing"}
  - {id: A6, sev: should-fix, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:222", anchor: "### A6 ", cert: AT4, basis: "item-13 hand-list misses two agent-facing TL;DR sites in design-review.md (:131-134, :296)"}
  - {id: A7, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:170", anchor: "### A7 ", cert: C3, basis: "D3 false-clean mitigations are not pinned by any acceptance criterion"}
  - {id: A8, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:234", anchor: "### A8 ", cert: C12, basis: "single-track sizing survives challenge; both split candidates fall below the ~12 floor and overlap Track 1's files"}
  - {id: A9, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:210", anchor: "### A9 ", cert: C4, basis: "D7 remove/keep line survives; kept self-check items embed removed-rule sub-clauses needing the same surgical grep"}
  - {id: A10, sev: suggestion, loc: "docs/adr/tech-writer-tone/_workflow/plan/track-1.md:199", anchor: "### A10 ", cert: AT2, basis: "corpus-deferral risk is near-nil by the zero-false-positive contract; a cheap Phase-B spot-check retires it entirely"}
evidence_base: {section: "## Evidence base", certs: 25, matches: 17}
cert_index:
  - {id: C1, verdict: WEAK, anchor: "#### C1 "}
  - {id: C2, verdict: WEAK, anchor: "#### C2 "}
  - {id: C3, verdict: YES, anchor: "#### C3 "}
  - {id: C4, verdict: YES, anchor: "#### C4 "}
  - {id: C5, verdict: YES, anchor: "#### C5 "}
  - {id: C6, verdict: WEAK, anchor: "#### C6 "}
  - {id: C7, verdict: YES, anchor: "#### C7 "}
  - {id: C8, verdict: YES, anchor: "#### C8 "}
  - {id: C9, verdict: YES, anchor: "#### C9 "}
  - {id: C10, verdict: YES, anchor: "#### C10 "}
  - {id: C11, verdict: YES, anchor: "#### C11 "}
  - {id: C12, verdict: YES, anchor: "#### C12 "}
  - {id: V1, verdict: CONSTRUCTIBLE, anchor: "#### V1 "}
  - {id: V2, verdict: CONSTRUCTIBLE, anchor: "#### V2 "}
  - {id: V3, verdict: CONSTRUCTIBLE, anchor: "#### V3 "}
  - {id: V4, verdict: CONSTRUCTIBLE, anchor: "#### V4 "}
  - {id: V5, verdict: INFEASIBLE, anchor: "#### V5 "}
  - {id: V6, verdict: INFEASIBLE, anchor: "#### V6 "}
  - {id: V7, verdict: INFEASIBLE, anchor: "#### V7 "}
  - {id: V8, verdict: INFEASIBLE, anchor: "#### V8 "}
  - {id: AT1, verdict: HOLDS, anchor: "#### AT1 "}
  - {id: AT2, verdict: HOLDS, anchor: "#### AT2 "}
  - {id: AT3, verdict: HOLDS, anchor: "#### AT3 "}
  - {id: AT4, verdict: BREAKS, anchor: "#### AT4 "}
  - {id: AT5, verdict: HOLDS, anchor: "#### AT5 "}
flags: [CONTRACT_OK]
-->

# Adversarial review — Track 1, iteration 1 (post-replan re-run, Phase 3A)

Reviewer: reviewer-adversarial. This re-run supersedes the pre-replan iteration-1 file (the A1/A2 staging-surface blocker it raised is resolved by D12; the replan folded its should-fixes). Per the spawn's Track-1 exception the cross-track-episode-reality challenge is dropped (first and only track); scope-and-sizing, invariant-violation, and decision/assumption challenges run with extra weight. Settled research decisions were not re-litigated from scratch; challenges target track realization. Branch is §1.7(b) staged (`phase-ledger.md` `s17=staged`); no `_workflow/staged-workflow/` exists yet, so every read resolved to the live file per §1.7(d), which is correct pre-Phase-B. mcp-steroid is unreachable; the surface is Markdown and Python prose, so grep+Read path/anchor verification is the correct tooling here — no Java symbol audit was load-bearing.

## Findings

### A1 [should-fix]
**Certificate**: V1 (with C1)
**Target**: D1/D7 — grep-derived drop-site criterion (Plan of Work, track-1.md:143; Removal-completeness invariant, track-1.md:242)
**Challenge**: The criterion "whatever a case-insensitive grep of the six removed-rule names returns across the file" is self-inconsistent with the track's own snapshot. `review-workflow-writing-style.md:185` contains no removed-rule name — only the exemplar phrase `"It's not X — it's Y" anti-pattern` — so the stated name-grep returns {29, 34, 38, 71, 89, 188, 200} while the track's snapshot claims {29, 34, 38, 71, 89, **185**, 188, 200}. An implementer executing the criterion as written drops the :185 edit (the Critical-bucket description keyed on the negative-parallelism exemplar), leaving the Phase-C reviewer's severity template enforcing a removed rule — the exact R4 failure the grep-derivation was adopted to prevent.
**Evidence**: `grep -niE "negative.parallel|roundabout|closing.phrase|hyphenated|curly|title.case|sentence.case"` over `.claude/agents/review-workflow-writing-style.md` misses :185; `grep -niE "it.s not|in conclusion"` hits :29, :71, :185. Line 185 reads `[Hard violations — "It's not X — it's Y" anti-pattern in load-bearing position, …]`.
**Proposed fix**: Broaden the criterion to "removed-rule names **plus their canonical exemplar phrases** (the quoted pattern literals from `house-style.md` — `It's not X`, `In conclusion`, curly-quote characters, etc.)", and state it applies to every in-scope prose file, not only item 6. Keep the snapshot as an authoritative floor: the grep-derived set must be a superset of {29, 34, 38, 71, 89, 185, 188, 200}.

### A2 [should-fix]
**Certificate**: C2
**Target**: D2 / A4 — hybrid narrative consumer completeness (Plan of Work, track-1.md:149; item 14, track-1.md:223; item 21, track-1.md:230)
**Challenge**: The replan added the consumer edit at `create-plan` Step-4b (A4, item 21) but missed the second pinned consumer: `edit-design/SKILL.md`'s own gate-return parse contract. Lines 664-676 instruct the orchestrator, for every kind including `phase1-creation`, to "Parse the **Verdict** line (`PASS` or `NEEDS REVISION`) and the inline **Structural findings** list" — no narrative slot. D2's rationale is that the gate narrative has a real consumer — "the orchestrator's gate verdict plus the author's rework seed" — and the frozen design's § Workflow diagram shows `G-->>O: hybrid narrative + structural findings + PASS / NEEDS REVISION` inside the *edit-design* loop. Without an item-14 edit, the narrative arrives at an orchestrator whose instructions ignore it and is never routed into the Step-6 author rework — reproducing on the gate path the exact "output with no consumer" defect D2 cites to reject the uniform hybrid.
**Evidence**: `edit-design/SKILL.md:664-676` (parse contract, both output-path branches); `design.md:185` (sequence-diagram edge); `design.md` §"Persona readers" ("its consumer is real — the orchestrator's gate verdict and the author's rework seed"); item 14's scope is only "the roughly five `TL;DR` sites (D4); the D9 link-staleness re-read reminder" (track-1.md:223).
**Proposed fix**: Extend item 14 (same file, no footprint growth): update `edit-design/SKILL.md` § Spawning the comprehension gate's return-shape paragraph to name the hybrid (narrative + Verdict + findings) on both output-path branches, and route the narrative into the Step-6 author rework input. Add a matching Validation bullet beside the A4 one.

### A3 [should-fix]
**Certificate**: V2
**Target**: D12 — augmented Phase-4 promotion procedure (Idempotence and Recovery, track-1.md:187-197)
**Challenge**: The recorded procedure is unexecutable as written. It says "After the standard Step-4 block, run the augmented promotion in the same promote commit" — but the live standard block *ends with the commit*: `create-final-design.md:547-549` runs `cp -r`, `git add` (four prefixes), then `git diff --cached --quiet || git commit`. By the time the augmented `cp`/`git add` run, the promote commit already exists; the augmented block contains no commit of its own. The three out-of-surface files then either require a second promote commit (breaking the Phase-4 two-commit shape and I6's "single intra-branch authoring transition" wording, conventions.md:1238-1247) or sit staged until Step 5 sweeps them into the final-artifacts commit — the wrong commit. The idempotence rationale ("the Step-4 `git diff --cached --quiet || git commit` short-circuit yields no second commit") silently assumes the commit runs *after* the augmented adds, contradicting the "after the standard block" ordering two sentences earlier.
**Evidence**: `create-final-design.md:537-549` (guarded block with trailing commit); track-1.md:187 ("After the standard Step-4 block … in the same promote commit"); track-1.md:197 (idempotence claim).
**Proposed fix**: Restate the ordering explicitly: run the augmented `cp` + `git add` lines **inside** the guarded block, immediately before the standard block's `git diff --cached --quiet || git commit` line (or, when the standard block was executed verbatim first, fold the three paths in with `git commit --amend` before pushing). Update the idempotence sentence to match.

### A4 [should-fix]
**Certificate**: V3
**Target**: D12 — divergence-halt recovery for the hand-bootstrapped mirrors (Idempotence and Recovery, track-1.md:197; D12 Risks, track-1.md:115)
**Challenge**: The track extends the divergence check so a develop-side change "halts promotion rather than being silently overwritten" — but it records no recovery step after the halt. §1.7(f)'s prescribed recovery is a rebase, and after the rebase the divergence check passes (the merge-base moves), yet the staged mirrors — full-file copies taken at branch time — still hold pre-rebase content. Re-running promotion then `cp`s the stale `_workflow/staged-workflow/CLAUDE.md` over the freshly rebased live `CLAUDE.md`, silently reverting develop's changes with nothing left to catch it. Root `CLAUDE.md` is the highest-churn file in the extended surface (several concurrent workflow branches in flight touch it), so the scenario is likely over this branch's lifetime, not theoretical.
**Evidence**: conventions.md:1207-1229 (§1.7(f): rebase is the manual-reconciliation path; its claim that post-rebase "the only live workflow content remaining … is the staged subtree's authoring against that current base" is untrue for a file whose develop side moved — a rebase replays branch commits and leaves the staged mirror bytes untouched); track-1.md:197 (extend-the-check instruction, no post-halt procedure).
**Proposed fix**: Add one recovery sentence to `## Idempotence and Recovery`: after any divergence-halt rebase, for each hand-bootstrapped mirror (`CLAUDE.md`, the two output-styles files), diff the mirror's baseline against the rebased live file and re-mirror (re-copy live → mirror, re-apply this branch's edits) before re-running promotion. Scope note: this is this track's recovery procedure, not a §1.7 redesign — the four-prefix surface shares the latent gap but is out of this track's scope.

### A5 [should-fix]
**Certificate**: V4
**Target**: Invariant "Live-tree isolation (§1.7 / I6)" (track-1.md:246; D12 Risks, track-1.md:115)
**Challenge**: The live enforcement gate leaves a multi-step detection hole for exactly the three files the track bootstraps by hand. Violation trace: (1) a Phase-B implementer spawn is told to edit `house-style.md`; (2) it consults its rulebook — `implementer-rules.md:284-289` routes only paths beginning with the four prefixes, so `.claude/output-styles/…` falls through to "write live normally"; (3) the pre-commit gate (`implementer-rules.md:412`, `git diff --cached --name-only -- .claude/workflow/ .claude/skills/ .claude/agents/ .claude/scripts/`) does not watch the path, so the live-edit commit passes; (4) the new style rules go live mid-branch — the cross-branch contamination D12 exists to prevent — and detection waits for the *track-level* acceptance check, potentially many steps later, forcing history rework. The track names manual discipline as the guard but places the check only at track level.
**Evidence**: implementer-rules.md:284-289 (routing names four prefixes), :412 (gate pathspec); track-1.md:175 (track-level `git diff --name-only` acceptance); track-1.md:134 (the by-hand mirror instruction lives in prose an implementer spawn may not carry into its step).
**Proposed fix**: Instruct Phase-A decomposition to stamp every step touching the three files with (a) the copy-then-edit-into-mirror instruction inline in the step text, and (b) a per-step acceptance line: `git diff --cached --name-only -- .claude/output-styles CLAUDE.md` must be empty before each commit. This shrinks the detection window from track-level to per-commit at zero footprint cost.

### A6 [should-fix]
**Certificate**: AT4 (with C6)
**Target**: D4 — rename-site enumeration for `design-review.md` (item 13, track-1.md:222)
**Challenge**: Item 13's hand-list ("the Summary structural finding and TOC row") under-enumerates the file. Grep shows seven `TL;DR` sites in `design-review.md`; the structural-findings cluster (:308, :323, :407) and TOC row (:26) are covered, but the design-sync accuracy clause (:131-134, "verify that every TL;DR … accurately summarizes the current mechanics") and comprehension question 4 (:296, "Read the changed section's TL;DR; restate…") are not. These are direct instructions to the comprehension gate: post-promotion, a reviewer reading them literally against a `### Summary` document reports a spurious missing-TL;DR or fails to locate the block it is told to read. Three plan-review iterations already proved hand-lists rot on exactly this class of edit (the item-6 lesson); the track applied the grep-derived rule to item 6 but not to the D4 sites.
**Evidence**: `grep -n "TL;DR" .claude/workflow/prompts/design-review.md` → :26, :131, :134, :296, :308, :323, :407; track-1.md:222 names two site clusters; track-1.md:215 (item 6) shows the grep-derived phrasing the D4 items lack.
**Proposed fix**: Reword item 13 (and the D4 Plan-of-Work paragraph) to the same shape as item 6: "every `TL;DR` site a grep over the file returns — the parenthetical clusters are orientation, not the edit set." Same files, no footprint change.

### A7 [suggestion]
**Certificate**: C3
**Target**: D3 — Sonnet reader-proxy false-clean mitigations (Validation and Acceptance, track-1.md:166-175)
**Challenge**: The load-bearing direction of the proxy claim (does Sonnet *report* what it failed to follow?) rests on the persona contract ("you will not fill in gaps") and the gate's cited comprehension answers — yet no acceptance criterion pins either into the staged agent files. Validation greps S4 ownership; the model pins are implied; a drafting slip that drops the contract phrase or the citation obligation would pass every listed check while removing two of the three mitigations. The decision itself survives (residual risk is named and accepted at the research gate; not re-litigated).
**Evidence**: track-1.md:166-175 (acceptance bullets — no persona-contract or model-pin assert); design.md §"Reader-proxy model pins" (mitigations 1-3); readability-auditor.md:5 / comprehension-review.md:5 (`model: opus` today — the pins are real edit targets).
**Proposed fix**: Add one acceptance bullet: the staged `readability-auditor.md` carries the will-not-fill-in-gaps persona contract, the staged `comprehension-review.md` carries the cited-answers obligation, and both frontmatters read `model: sonnet`.

### A8 [suggestion]
**Certificate**: C12
**Target**: Sizing — single ~21-file track (track-1.md:234)
**Challenge**: Argued both splits and both fail. (a) A separate D12 §1.7-extension track would hold 3 files (`conventions.md`, `implementer-rules.md`, `create-final-design.md`) — far below the ~12-file merge-candidate floor of planning.md:680-687 — and Track 1 cannot promote its own central files without it, so it is an enabling mechanism, not an independently mergeable unit. (b) A separate YTDB-1163 BLUF track would hold ~3 files that all overlap Track 1's edit regions (the D1/D7 removals rewrite `house-style.md` § Banned sentence patterns and renumber the self-check the BLUF item anchors into; `review-workflow-writing-style.md` is rewritten at the same lines), paying a second review round over the same regions — the cost D10's alternatives record names. 21 files sits inside the ~20-25 soft ceiling with the written justification the rule requires.
**Evidence**: planning.md:630-697 (footprint sizing, maximize-then-clamp, soft bounds, justification clause); track-1.md:234 (Sizing justification); track-1.md:102-103 (D10 overlap rationale).
**Proposed fix**: None — the single-track decision holds; this finding records the mandated sizing challenge and its survival.

### A9 [suggestion]
**Certificate**: C4
**Target**: D7 — remove/keep criterion, and its realization inside kept self-check items (item 1, track-1.md:210)
**Challenge**: Pressed the sharpest boundary case: negative parallelism sometimes *is* deletable padding ("It's not X —" prefixing a strawman nobody claimed), which satisfies the keep criterion's "deleting shortens the text" arm. The design's answer (the form usually carries an X-contrast a positive rewrite re-states, and its trailing-negation regex is the checker's most false-positive-prone) holds at the settled-research level. Realization risk remains in the surgical edits: the removals live as sub-clauses *inside kept* self-check items — item 1 (both clauses removed, item disappears whole), items 2 and 4 (mixed kept/removed), and the mid-list clause "sentence case on H2+" inside kept item 5 at house-style.md:409 — the same partial-edit hazard class the track flags as T2.
**Evidence**: house-style.md:405-409 (self-check items 1, 2, 4, 5 mix removed and kept clauses); design.md §"Removing the disguise-only style rules" (criterion and the negative-parallelism/throat-clearing contrast).
**Proposed fix**: In item 1, name the self-check surgical sub-edits explicitly (items 1/2/4/5 including the :409 "sentence case on H2+" clause), or extend the A1 name+exemplar grep rule to house-style.md's own body so the sub-clauses are enumerated mechanically. The decision itself stands.

### A10 [suggestion]
**Certificate**: AT2 (with AT1)
**Target**: R1/A3 — deferred corpus-calibration assertions (Idempotence and Recovery, track-1.md:199)
**Challenge**: The track treats a red corpus assertion at promotion as a live promotion-blocker risk. The code says the risk is near-nil: `assert_calibration_adrs` enforces a *zero*-false-positive contract (`test_dsc_ai_tell.py:279-304`), `run_script` filters to `rule == "dsc-ai-tell"` findings only (:151-186), and this track only *removes* regexes — a removal can never raise a zero count — while the `### Summary` set additions touch shape checks the corpus filter never sees. The deferral is therefore safe, and the residual worry can be retired for the cost of one Phase-B command.
**Evidence**: test_dsc_ai_tell.py:279-304 (zero-finding contract; its docstring notes prior removals "could only ever have lowered this count"), :151-186 (dsc-ai-tell filter), :59-67 (path math — AT1 confirms the fixture assertions do run in place against the staged script).
**Proposed fix**: Add one optional Phase-B validation line: run the staged `design-mechanical-checks.py` by hand over the three live calibration ADRs (`docs/adr/{persist-visible-count,index-gc,non-durable-wow}/adr.md`) and confirm zero `dsc-ai-tell` findings — converting the promotion-time verification into a mid-track no-surprise check.

## Evidence base

**Decision challenges**

#### C1 Challenge: D1/D7 — drop-site enumeration criterion
- **Chosen approach**: derive the `review-workflow-writing-style.md` drop-site set by case-insensitive grep of the six removed-rule names (track-1.md:143).
- **Best rejected alternative**: the hand-maintained line list (rejected after three plan-review iterations found it incomplete).
- **Counterargument trace**: (1) the name-grep over the live file returns {29, 34, 38, 71, 89, 188, 200}; (2) the track's snapshot claims {…, 185, …}; (3) :185 carries only the exemplar `"It's not X — it's Y"`, no rule name — so the criterion adopted *because* hand-lists rot under-derives its own claimed set.
- **Codebase evidence**: review-workflow-writing-style.md:185 vs :29/:71 (name-bearing lines); grep transcripts in V1.
- **Survival test**: WEAK — grep-derivation is right in spirit; the predicate needs exemplar phrases added. → A1.

#### C2 Challenge: D2 — hybrid output consumers
- **Chosen approach**: hybrid narrative-plus-findings on the whole-doc gate; producer edit at `design-review.md` (item 13) plus one consumer edit at `create-plan` Step-4b (item 21, A4).
- **Best rejected alternative**: enumerate consumers by grep of the gate-return contract instead of assuming Step-4b is the only pinned consumer.
- **Counterargument trace**: (1) `edit-design/SKILL.md:664-676` pins "Parse the **Verdict** line … and the inline **Structural findings** list" for every kind including `phase1-creation`; (2) the design's own sequence diagram (design.md:185) hands the hybrid to the edit-design orchestrator, and D2's rationale names "the author's rework seed" as a consumer; (3) with item 14 limited to TL;DR sites plus the link reminder, the narrative lands unparsed and unrouted — the no-consumer defect D2's own alternatives analysis treats as disqualifying.
- **Codebase evidence**: edit-design/SKILL.md:664-676; create-plan/SKILL.md:950-956 (the A4 target, correctly in scope — "the inline return stays small … bounded comprehension verdict plus a summary-shaped `## Structural findings` list").
- **Survival test**: WEAK — the decision holds; the realization misses one of two pinned consumers. → A2.

#### C3 Challenge: D3 — Sonnet reader proxy, false-clean direction
- **Chosen approach**: both readers to Sonnet; three mitigations (persona contract, symmetric Opus error, dsc + Phase-C backstops); author pinned Opus.
- **Best rejected alternative**: one Opus backstop on the outer gate.
- **Counterargument trace**: (1) the two named backstops catch AI-tells and style, not comprehension gaps, so the comprehension false-clean rests on mitigation 1 plus the gate's cited answers; (2) an instruction cannot guarantee a model notices its own gap-filling — the mitigation is a probability reducer, not a proof; (3) but the design names exactly this residual, pre-commits the revisit trigger (human complaints on dual-clean docs), and the research gate settled it — the challenge cannot beat an accepted, named residual with a revisit plan.
- **Codebase evidence**: readability-auditor.md:5, comprehension-review.md:5 (`model: opus` today); design.md §"Reader-proxy model pins" (mitigations, one-directionality, revisit trigger).
- **Survival test**: YES — with a realization gap: no acceptance pin on the mitigation text. → A7.

#### C4 Challenge: D7 — remove/keep line (negative parallelism vs throat-clearing)
- **Chosen approach**: remove on disguise-only-plus-enforcement-cost; keep on deletes-text-or-forces-substance; closure default keeps.
- **Best rejected alternative**: keep negative parallelism's *trailing* variant as an additive-padding rule (its "just/merely/simply" cases are often deletable strawmen).
- **Counterargument trace**: (1) some negative-parallelism instances are deletable padding, satisfying the keep arm; (2) the design counters that the form usually carries an X-contrast a rewrite re-states (near-zero net brevity) and that the trailing variant is the checker's most false-positive-prone regex — the enforcement-cost arm dominates; (3) splitting the rule (keep trailing, remove leading) would reintroduce exactly the curated-discriminator maintenance the removal retires.
- **Codebase evidence**: design-mechanical-checks.py:103-127 (two regex objects, the trailing one carrying the intensifier discriminator); house-style.md:100 (the rule), :405-409 (self-check sub-clauses mixing removed and kept).
- **Survival test**: YES — realization note on the self-check surgical edits. → A9.

#### C5 Challenge: D12 — extend §1.7 vs the unlisted alternative (defer the three files)
- **Chosen approach**: extend §1.7 to `.claude/output-styles/**` + root `CLAUDE.md`, stage the mechanism edits, bootstrap this branch's three files by hand.
- **Best rejected alternative (unlisted)**: land the §1.7 extension staged here, but defer the three out-of-surface file edits to a tiny follow-up branch that runs after promotion makes the surface live — no manual bootstrap at all.
- **Counterargument trace**: (1) the follow-up branch avoids the by-hand mirror discipline and the augmented promotion; (2) but `house-style.md` is the source of truth for the entire change — deferring it guts the branch (the removals, the voice swap, and the D10 sites all live there), leaving Track 1 unable to deliver its Purpose; (3) a rules change split across two branches also opens a window where the live tree carries the new agents against the old style source. The listed live-edit alternative fails on cross-branch contamination as recorded.
- **Codebase evidence**: conventions.md:991-994 ("No other prefixes participate"); create-final-design.md:547-548 (`cp -r` reaches staged output-styles, `git add` omits them — D12's mechanism account verified accurate); track-1.md:210 (item 1 — house-style.md carries five of the track's ten decision surfaces).
- **Survival test**: YES — D12 stands; its recorded procedure has the V2/V3/V4 realization defects.

#### C6 Challenge: D4 — rename-site completeness
- **Chosen approach**: rename at every site that hard-codes the spelling, "enumerated by grep over `.claude/`", with per-item parentheticals.
- **Best rejected alternative**: state the grep-derived rule per file (as item 6 does) so the parentheticals are non-limiting.
- **Counterargument trace**: (1) file-level coverage is complete — `grep -rlE "TL;DR" .claude/` returns exactly the in-scope items plus the `d11-*.md` fixtures, which both-spellings acceptance keeps green with no edit; (2) within `design-review.md` the parenthetical names two clusters while the file carries seven sites; (3) the two uncovered sites are agent-facing instructions that misdirect the comprehension gate on every post-promotion `### Summary` document.
- **Codebase evidence**: design-review.md:26/:131/:134/:296/:308/:323/:407; `section_has_tldr` (design-mechanical-checks.py:708-717) keeps legacy spellings green for the fixtures; edit-design/SKILL.md TL;DR count = 5 ("roughly five" verified); planning.md:774, conventions.md:149, create-final-design.md:187, review-workflow-pr/SKILL.md:114 — one site each, verified.
- **Survival test**: WEAK on the item-13 parenthetical, YES on the decision. → A6.

#### C7 Challenge: D5 — voice scope (prose trio)
- **Chosen approach**: technical-writer voice on design/track prose only; track-file trio exhaustive; `## Validation and Acceptance` and `## Idempotence and Recovery` registry-terse.
- **Best rejected alternative**: include `## Validation and Acceptance` in the voice (it is author-written prose).
- **Counterargument trace**: (1) V&A lines are read by implementer spawns as test obligations under a context budget; (2) narrativizing them inflates every implementer read with no benefiting reader — the same argument that rejects whole-file voice; (3) the trio boundary matches the ExecPlan reader split exactly.
- **Codebase evidence**: conventions-execution.md §2.1 (fifteen-section ExecPlan, structured surfaces enumerated); track-1.md:68-69 (D5 records the carve-out and its caveat).
- **Survival test**: YES.

#### C8 Challenge: D6 — persona recast, no new spawns
- **Chosen approach**: recast the two existing cold readers; fold the veteran into the gate verdict; mechanical checks personless.
- **Best rejected alternative**: the third veteran spawn.
- **Counterargument trace**: (1) a third spawn adds per-round cost across up to three rounds; (2) its signal (skepticism toward dressed-up shallow content) is a mental-model judgment the whole-doc gate already renders; (3) the S4 single-owner discipline is easier to hold with two readers than three.
- **Codebase evidence**: comprehension-review.md:39 and readability-auditor.md:68 (the S4 one-owner wording both files already carry — the recast edits, not invents, this machinery); edit-design/SKILL.md:505-511 (roster and allow-lists).
- **Survival test**: YES.

#### C9 Challenge: D8 — frozen design stays the seed
- **Chosen approach**: dual-register design document; frozen `design.md` remains the full-tier track seed.
- **Best rejected alternative**: research log as seed.
- **Counterargument trace**: (1) the log is append-only with superseded entries — Step-4b's derivation spawn would re-derive mechanism from code and could diverge from the reviewed design; (2) the register boundary (records four-bullet, summaries plain-claim, footers lists, diagrams stay) keeps extraction lossless; (3) the D12 episode itself shows divergence handled correctly: the superseded design § Staging premise is carried by the track record with a Phase-4 reconciliation pointer, keeping the seed authoritative for everything else.
- **Codebase evidence**: create-plan/SKILL.md:943-947 (Step-4b forwards `design_path` in `full` for the seed↔track fidelity criterion); track-1.md:117 (D12 supersession note).
- **Survival test**: YES.

#### C10 Challenge: D9 — book-rule transfer and precedence rankings
- **Chosen approach**: eight rules (five verbatim, two adapted, one present); two precedence rankings; greppable heading links plus a mutation-discipline re-read reminder.
- **Best rejected alternative**: leave the rule sets unranked and let reviewers reconcile case-by-case.
- **Counterargument trace**: (1) two enforcement agents with contradictory targets (length cap vs worked-example opener; consolidation vs one-concept) thrash the bounded dual-clean loop, burning the shared `iteration_budget`; (2) the rankings are cheap prose landing in the file both enforcers already read (`design-document-rules.md`, item 12); (3) the link-staleness residual is real but named, with the reminder as guard — no mechanical check is claimed.
- **Codebase evidence**: house-style.md:283-285 (the length-cap and padding-criterion rules the worked-example ranking must outrank); design-mechanical-checks.py:1335 (the sibling-similarity machinery the one-concept ranking interacts with); item 14 (the reminder's `edit-design` home).
- **Survival test**: YES.

#### C11 Challenge: D10 — BLUF hardening, composition rule, three sites
- **Chosen approach**: self-contained plain-claim lead + body-stands-without-the-lead; plain claim ranked ahead of the D9 backward link; three acceptance sites, self-check item anchored by name.
- **Best rejected alternative**: defer YTDB-1163 to its own branch.
- **Counterargument trace**: (1) the D1/D7 removals rewrite `house-style.md` § Banned sentence patterns and renumber the self-check — the exact regions the BLUF rules land in — so a second branch pays a second review round over the same lines; (2) all three named sites resolve today: `house-style.md` § BLUF lead (:22) and § Orientation with its worked exemplar (:68-74), `review-workflow-writing-style.md` BLUF criterion (:28), self-check BLUF item (:413) — and name-anchoring is necessary because items 1-5 shift; (3) the composition rule gives every section-opening enforcer one ranked rule, preventing the same loop-thrash C10 addresses.
- **Codebase evidence**: house-style.md:22-38, :68-74, :413; review-workflow-writing-style.md:28.
- **Survival test**: YES.

**Scope challenges**

#### C12 Challenge: single ~21-file track sizing
- **Chosen approach**: one track carrying the whole change, D12 extension included (track-1.md:234).
- **Best rejected alternative**: split out (a) the D12 §1.7 extension or (b) the YTDB-1163 BLUF hardening.
- **Counterargument trace**: see A8 — (a) yields a 3-file track below the ~12 floor whose only consumer is Track 1's own promotion; (b) yields a ~3-file track fully overlapping Track 1's edit regions, paying a duplicate review round; 21 files sits inside the ~20-25 soft ceiling with the required written justification present.
- **Codebase evidence**: planning.md:630-697 (footprint sizing, maximize-then-clamp, soft bounds, justification clause); track-1.md:102-103, :234.
- **Survival test**: YES. → A8 (record of survival).

**Invariant challenges**

#### V1 Violation scenario: Removal completeness (track-1.md:242)
- **Invariant claim**: after the removals, no removed-pattern enforcement text remains on any consumer surface.
- **Violation construction**: (1) start: implementer executes item 6 by the stated criterion — "a case-insensitive grep of the six removed-rule names"; (2) action: grep returns :29, :34, :38, :71, :89, :188, :200 — not :185 (no rule name there, only the `"It's not X — it's Y"` exemplar); (3) intermediate: implementer edits the seven returned sites, notices the snapshot mismatch, and per "the set is grep-derived, not a hand-maintained list" trusts the grep; (4) violation point: review-workflow-writing-style.md:185 keeps `"It's not X — it's Y" anti-pattern in load-bearing position` as the Critical-severity exemplar; (5) consequence: every post-promotion Phase-C style review grades negative parallelism Critical while `house-style.md` no longer bans it — R4 realized.
- **Feasibility**: CONSTRUCTIBLE — the grep transcript above is the scenario. → A1.

#### V2 Violation scenario: §1.7 surface extension — promotion commit shape (track-1.md:187-197)
- **Invariant claim**: the three out-of-surface files promote "in the same promote commit" via the augmented block.
- **Violation construction**: (1) start: Phase 4, staged subtree present; (2) action: orchestrator executes live `create-final-design.md:537-549` verbatim — `cp -r`, `git add` (four prefixes), `git commit` fires; (3) intermediate: the promote commit exists without output-styles/CLAUDE.md; the orchestrator then runs the track's augmented block ("after the standard Step-4 block") — `cp` + `git add`, no commit line; (4) violation point: the three paths sit in the index; the next scripted commit is Step 5's final-artifacts commit, which sweeps them in; (5) consequence: promoted style files land in the final-artifacts commit — the Phase-4 two-commit contract and I6's "single authoring transition" wording (conventions.md:1238-1247) break, and the §1.7(j) crash-resume window now straddles half-promoted state.
- **Feasibility**: CONSTRUCTIBLE — follows both texts literally. → A3.

#### V3 Violation scenario: stale mirror clobbers develop's CLAUDE.md (track-1.md:197)
- **Invariant claim**: a develop-side change to the extended paths "halts promotion rather than being silently overwritten".
- **Violation construction**: (1) start: branch open for weeks; a sibling branch merges a `CLAUDE.md` section edit to develop; this branch's `_workflow/staged-workflow/CLAUDE.md` was copied earlier and carries only the § Writing Style parenthetical removal; (2) action: Phase 4 runs the extended divergence check → non-empty → halt; recovery per §1.7(f) is a rebase onto `origin/develop`; (3) intermediate: post-rebase, live `CLAUDE.md` carries the sibling's section; the staged mirror bytes are unchanged (a rebase replays commits — it does not merge unrelated mirror files); the divergence check now passes because the merge-base moved; (4) violation point: the augmented `cp "$STAGED_DIR/CLAUDE.md" ./CLAUDE.md` overwrites the sibling's section; `git add CLAUDE.md` commits the revert; (5) consequence: develop's change is silently reverted in the promote commit — the exact outcome the halt was added to prevent, one rebase later.
- **Feasibility**: CONSTRUCTIBLE — requires only one develop-side `CLAUDE.md` edit during the branch's lifetime, which the active-branch roster makes likely. → A4.

#### V4 Violation scenario: Live-tree isolation on the three out-of-surface files (track-1.md:246)
- **Invariant claim**: the branch diff touches no live `.claude/output-styles/**` and no live root `CLAUDE.md`.
- **Violation construction**: (1) start: Phase B step "edit `house-style.md` § Voice and tone"; the implementer spawn reads `implementer-rules.md`; (2) action: write-routing (:284-289) matches only the four prefixes → the path falls through to a normal live write; the implementer edits live `house-style.md`; (3) intermediate: the pre-commit gate (:412) checks `-- .claude/workflow/ .claude/skills/ .claude/agents/ .claude/scripts/` → empty → the commit passes; (4) violation point: the live tree carries the new style rules mid-branch; this branch's own later authoring and the concurrent branches review under half-new rules; (5) consequence: cross-branch contamination until the track-level `git diff --name-only` acceptance runs — potentially many steps later — then history rework to relocate the edits into the mirror.
- **Feasibility**: CONSTRUCTIBLE — the rulebook the implementer is bound to actively signals "live" for these paths; only track-file prose says otherwise. → A5.

#### V5 Violation scenario: Both-spellings acceptance — the bold `**Summary.**` form
- **Invariant claim**: `**TL;DR.**`, `### TL;DR`, and `### Summary` all pass the shape regexes.
- **Violation construction attempted**: an author writes `**Summary.**` (bold-prefix, the D4-rejected form); the post-edit `section_has_tldr` (design-mechanical-checks.py:708-717) accepts the three listed forms only → the section flags missing-Summary.
- **Feasibility**: INFEASIBLE as a defect — D4 explicitly rejected the bold-prefix rename because it reproduces the separation failure; flagging it is intended behavior, and the invariant's three-form list is exact.

#### V6 Violation scenario: Summary-added-case-correctly (T1)
- **Invariant claim**: `MANDATORY_OR_FORM_SUBHEADINGS` gains lowercase `"summary"`, `SHAPE_EXEMPT_SECTION_NAMES` gains display-case `"Summary"`.
- **Violation construction attempted**: swap the cases. The exempt check compares raw titles (`section["title"] in SHAPE_EXEMPT_SECTION_NAMES`, design-mechanical-checks.py:737) — a lowercase entry never matches `## Summary`; the sibling check compares lowercased (`sub.lower() not in …`, :1335) — a display-case entry never matches. Both failure shapes are exactly what the track's T1 text prescribes against, and the item-10 shape tests plus acceptance bullet 2 pin both directions, including the Part-level `## Summary` exemption.
- **Feasibility**: INFEASIBLE given the plan as written — the compare-case semantics are verified in code and the assertion coverage is specified.

#### V7 Violation scenario: Single prose-axis owner S4 (track-1.md:245)
- **Invariant claim**: exactly one staged agent claims the prose AI-tell axis.
- **Violation construction attempted**: a naive `grep -c "prose AI-tell axis"` over the staged agents counts 5 hits today (comprehension-review.md description + the runs-it-nowhere disclaimer at :39; readability-auditor.md description, :59 checklist, :68 ownership), so a count-based assert either false-fails or, tuned to 5, false-passes a real double-claim. The track pre-empts this: R6 requires the grep to be "scoped and ownership-distinguishing — it confirms exactly one agent *claims* the axis, not merely that the phrase appears once".
- **Feasibility**: INFEASIBLE given R6 — the invariant's assert is specified against the exact failure shape constructed here.

#### V8 Violation scenario: D10 rules present at three sites (track-1.md:248)
- **Invariant claim**: the two hardening rules are greppable at the three named sites.
- **Violation construction attempted**: a site fails to resolve. All three anchors exist on the live tree: `house-style.md` § BLUF lead (:22) and § Orientation with its worked exemplar (:68-74), `review-workflow-writing-style.md` BLUF criterion (:28), and the self-check BLUF item (:413) — and the by-name (not ordinal) anchor survives the D1/D7 renumbering by construction.
- **Feasibility**: INFEASIBLE — every named drop site resolves; the renumbering hazard is already designed around.

**Assumption tests**

#### AT1 Assumption test: fixture-based dsc assertions stay green while staged
- **Claim**: `test_dsc_ai_tell.py`'s fixture assertions run in place from the staged path; only the corpus assertions dangle (R1/A3).
- **Stress scenario**: run the staged test file. `REPO_ROOT = parents[3]` resolves to `staged-workflow/`, so `SCRIPT` → the staged `design-mechanical-checks.py` and `FIXTURE` → the staged fixture — both exist (items 7 and 9 stage them); `CALIBRATION_ADRS` → `staged-workflow/docs/adr/…` — absent, and the runner reports them as missing failures rather than skipping.
- **Code evidence**: test_dsc_ai_tell.py:59-67 (path derivations), :290-292 (missing-ADR branch appends a failure).
- **Verdict**: HOLDS in substance — the fixture assertions verify the staged unit in place; note the whole-runner exit code stays red until the corpus block is filtered out, so Phase-B "run the fixture-based assertions" needs a filtered invocation or a tolerated corpus-missing tail (the track's V&A wording already scopes green to the fixture assertions; decomposition should make the invocation explicit).

#### AT2 Assumption test: corpus-calibration deferral is a live promotion risk
- **Claim (track's)**: "A red corpus assertion at promotion is a promotion blocker" — treated as a real risk to schedule around.
- **Stress scenario**: what change in this track could turn the corpus red? `assert_calibration_adrs` enforces zero `dsc-ai-tell` findings per ADR; `run_script` filters `rule == "dsc-ai-tell"`; the track removes regexes (a removal can only lower a zero count) and adds `### Summary` handling to shape checks the filter never returns; the three calibration ADRs each carry `## Summary` at line 3, which the `SHAPE_EXEMPT` addition exempts — also invisible to the filter.
- **Code evidence**: test_dsc_ai_tell.py:151-186, :279-304 (its docstring: prior removals "could only ever have lowered this count"); docs/adr/{persist-visible-count,index-gc,non-durable-wow}/adr.md:3 (`## Summary`).
- **Verdict**: HOLDS (the deferral is safe) — A10's one-command Phase-B spot-check retires even the residual.

#### AT3 Assumption test: no removed-rule consumer outside the 21-file list
- **Claim**: the in-scope list plus grep-derivation reaches every surface that names or mirrors a removed rule.
- **Stress scenario**: repo-wide `grep -rnilE "negative.parallel|roundabout negation|closing.phrase|hyphenated (word.)?pair|curly quote|title.case"` over `.claude/` + `CLAUDE.md` (excluding `docs/adr`) returns exactly nine files, all in scope (items 1, 2, 6, 7, 8, 9, 11, 12, 19). The one un-listed style consumer — the live hook `.claude/hooks/house-style-write-reminder.sh` and its `test_house_style_hook.py` §1.5 anchor-drift guard — cites only the four Tier-B section headings (`## Orientation`, `## Plain language`, `## Banned sentence patterns`, `## Banned analysis patterns`), all of which survive the removals (the removals delete bullets and `###` subsections, never those `##` headings), so the hook and its guard test stay green through promotion.
- **Code evidence**: grep transcript; test_house_style_hook.py:694-727 (`TIER_B_HEADINGS` + anchored heading assert); hook lines 260-262 (reminder bodies name surviving section groups only).
- **Verdict**: HOLDS.

#### AT4 Assumption test: per-item rename parentheticals are complete (D4)
- **Claim**: item 13's "(the Summary structural finding and TOC row)" covers `design-review.md`'s rename surface.
- **Stress scenario**: grep the file for `TL;DR` → seven sites; the parenthetical's clusters cover :26/:308/:323/:407 but not the design-sync accuracy clause (:131-134) or comprehension question 4 (:296) — both live instructions to the gate agent.
- **Code evidence**: design-review.md:131-134, :296.
- **Verdict**: BREAKS (for the parenthetical as an edit-set definition) — the file-level list is complete, the within-file hand-list is not. → A6.

#### AT5 Assumption test: stable-prefix marker property absorbs the D12 enumeration growth
- **Claim (D12)**: only the descriptive tail of the §1.7(b) marker needs editing; develop-era markers keep matching.
- **Stress scenario**: a pre-extension plan (or this branch's ledger `s17=staged`) meets the extended staged definition. Consumers match the case-sensitive stable prefix `This plan is workflow-modifying:` only (conventions.md:1031-1042), and the ledger token carries no path enumeration at all; the (b) text names precisely this bootstrap ("keep its develop-state marker verbatim while the staged definition adds prefixes").
- **Code evidence**: conventions.md:1023-1042; phase-ledger.md (`s17=staged`).
- **Verdict**: HOLDS.
