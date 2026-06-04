<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 1: Sizing & risk taxonomy

## Purpose / Big Picture
After this track, decomposition sizes steps by coherence and a fill-toward-~12-files directive instead of the old `~3`-file cap, and `.claude/**` edits have a HIGH/MEDIUM/LOW risk taxonomy for the first time.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Replace the `~3`-file cap with the three sizing rules (coherence, high-risk isolation, fill-toward-~12) and add the workflow-machinery risk taxonomy that Track 2's reviewer triage depends on. Edits `track-review.md` (§ Step Decomposition rewrite + § Risk tagging summary sync), `conventions.md §1.1` (the "Step" glossary reword), and `risk-tagging.md` (the `~5` MEDIUM clarifying clause + the new `### Workflow machinery` HIGH/MEDIUM/LOW subsection with prose-only cap).

## Base commit
eada58a9b0c9a9ec9ec6563f94e747f5a6e0281a

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [x] Track-level code review
- [ ] Track completion

- [x] 2026-06-04T14:18Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-04T14:38Z [ctx=safe] Step 1 complete (commit 857072a5c41520dea84378d64ae4e5588faea987)
- [x] 2026-06-04T14:44Z [ctx=safe] Step 2 complete (commit 4c1804d4d332510e73aad43e58b2cbc227d966b5)
- [x] 2026-06-04T14:50Z [ctx=safe] Step 3 complete (commit ea12eade9896281b6f477157f476ed5b8dd9cf27)
- [x] 2026-06-04T14:54Z [ctx=safe] Step 4 complete (commit 394d8965dddcbfeabdf59672838479c2485fba67)
- [x] 2026-06-04T15:22Z [ctx=info] Track-level code review iteration 1 complete (1/3 iterations)
- [x] 2026-06-04T15:22Z [ctx=info] Track complete

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->

- Track 3 shares the staged `conventions.md`. Track 1 (step 2) reworded the §1.1 "Step" row; Track 3 will edit §1.2, §Scope indicators, and the §1.1 "Scope indicator" row. Track 3's first touch must edit the already-staged copy under `_workflow/staged-workflow/`, not re-copy from live — re-copying would drop the step-2 reword. The §1.7(d) staged-read precedence already mandates editing the staged copy when it exists. See Episodes §Step 2.
- Track 2's reviewer-triage trigger now exists. The staged `risk-tagging.md` carries the `### Workflow machinery` HIGH/MEDIUM/LOW taxonomy and the `## Prose-only workflow steps` cap (D6) that Track 2's step-vs-track triage (D5) keys off. Track 2 edits the same staged copy at a disjoint section (the `high` quick-ref row), and Step 4 edits it again (the `~5` clause); both must edit the already-staged copy, not re-copy from live. See Episodes §Step 3.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

- **Phase A risk tagging — all 4 steps `low`.** Decomposed and tagged under the LIVE `risk-tagging.md`, which classifies `.claude/**` prose edits as docs → LOW default. The workflow-machinery risk taxonomy that would reclassify some of these steps HIGH is exactly what this branch ADDS (D6) and is not yet live; per the §1.7(g) I6 invariant the branch executes under develop's live machinery, and the plan's §Self-application limit routes this branch's review to Phase C track-level (workflow-only diffs hit the baseline-skip override; `low` steps skip step-level review). Tagging any step `high` would pull the live glob-keyed workflow reviewers to the step level, against that intent. All review deferred to Phase C track-level — intentional.
- **A3 resolution — D2 scope.** The adversarial review found two compatible restatements of "a step is a single atomic change (one commit, fully tested)" in sub-agent preambles (`step-implementation.md:502`, `track-code-review.md:308`) that D2's glossary reword does not touch. Judged known-acceptable non-targets: terse, non-contradictory, and a reviewer preamble needs no footprint pointer. Not expanding Track 1's scope to sync them (that would be a cross-track scope change requiring replanning); recorded here so a later consistency reviewer does not flag them as missed sync sites. If the deeper sync is wanted, it is a separate decision.
- **Track Pre-Flight gate skipped (State C resume, user-confirmed).** First-ever Phase A entry; the Startup Protocol's State-C routing skips the gate, and the user chose "skip to reviews" over running Panel 2. No gate amendments are in the audit trail by design. The gate-skip-vs-refire split is the known YTDB-1058.

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->

- [x] Technical: PASS at iteration 1 (1 finding, 1 accepted) — all 12 current-state citations verified against the live `.claude/**` files; the load-bearing Track-1→Track-2 dependency (the workflow taxonomy is Track 2's triage trigger) confirmed against the live glob-keyed dispatch. T1 (suggestion, accepted): keep the trivial-merge floor's `(single import, single rename)` parenthetical when copying it across.
- [x] Risk: PASS at iteration 1 (3 findings, 3 accepted) — R1 (should-fix): state the schema-change-vs-gloss hinge so the HIGH "shared schema / glossary closed terms" line and the prose-only LOW cap do not overlap. R2/R3 (suggestions): keep the prose-only cap's cross-tier scope explicit; distinguish the risk bucket from the file-set predicate. No blockers; cross-session blast radius is bounded by advisory prose, the Phase A user override, and unchanged coverage gates.
- [x] Adversarial: PASS at iteration 1 (4 findings, 4 accepted) — A1 (should-fix): carry the design's full "no gate/dispatch/schema change" qualifier into the implemented prose-only cap (the track gloss dropped it). A2 (should-fix): fix the hard "Six HIGH categories" count in the § Risk tagging summary sync. A3/A4 (suggestions): record the two compatible "atomic" sub-prompt restatements as accepted non-targets; document the fill→more-mediums interaction. D1/D2/D3/D6 all survive challenge; `conventions-execution.md` edit-atomicity correctly left untouched (a different sense of "atomic").

## Context and Orientation

All edit targets are workflow `.md` files. This plan is workflow-modifying, so every edit routes to a staged copy under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...` per `conventions.md §1.7`; the live `.claude/**` tree stays at develop's state until the Phase 4 promotion. On first touch of a file, copy the live version into the staged path verbatim, then edit the staged copy (§1.7(e)).

The track touches three files, all verified current as of `cb5eec65` (HEAD has added only the planning handoff since, no `.claude/**` change):

- **`track-review.md` § Step Decomposition** — the cap lives at the line "If a step touches more than ~3 files or does unrelated things, split it." (currently around `:717`, under the `### Step Decomposition` heading at `:698`). The trivial-merge floor ("If a step feels trivial, merge it into a neighbor") sits nearby and must survive. The `#### Risk tagging` summary block (around `:723`) enumerates the categories ("Six HIGH categories ... one MEDIUM band ... a LOW default") and must gain a workflow mention so it does not drift from `risk-tagging.md`.
- **`conventions.md §1.1 Glossary`** — the "Step" row (currently `:70`): "A single atomic change = one commit. Fully tested." The glossary is annotated `roles=any phases=any`, so it is the most authoritative definition site; "atomic" reading as "smallest indivisible" is what fights the fill directive.
- **`risk-tagging.md`** — `## HIGH-risk triggers` heading (`:94`) is where the new `### Workflow machinery` subsection lands; `## MEDIUM-risk triggers` (`:156`) and `## LOW-risk default` (`:172`) take the workflow MEDIUM/LOW lines; the MEDIUM `~5`-file trigger "Logic changes touching more than ~5 files within one module" (`:163`) gets the clarifying clause; the § Tests-only steps section (`:187`) is the structural precedent for the new prose-only cap.

Non-obvious terminology this track introduces or sharpens (full glosses in design.md §"Core Concepts"): **footprint cap** (the `~12` edited-file ceiling, all tiers), **fill-toward-cap directive** (decompose to the largest coherent change, not the smallest), **high-risk isolation** (each HIGH change in its own `high`-tagged step, no file cap), **footprint cap vs risk classification** (the two distinct file-count numbers `~12` and `~5`), **workflow-machinery risk taxonomy** (HIGH/MEDIUM/LOW for `.claude/**`), **prose-only cap** (a prose-only workflow step is at most `low`).

Concrete deliverables: a rewritten § Step Decomposition stating the three rules; a reworded "Step" glossary row; a `### Workflow machinery` taxonomy subsection plus MEDIUM/LOW lines and a prose-only cap in `risk-tagging.md`; a `~5`-vs-`~12` clarifying clause at the MEDIUM trigger; and a workflow mention in the § Risk tagging summary.

## Plan of Work

The work is four coherent edits. They are loosely coupled, so the ordering below is a sensible default rather than a hard constraint; the one cross-track ordering rule that matters is that this track's workflow risk taxonomy (D6) lands before Track 2 consumes it as the workflow-reviewer triage trigger.

1. **Rewrite the `~3`-file cap as the three sizing rules** (D1) in `track-review.md` § Step Decomposition: split a step that does unrelated things (coherence, all tiers); isolate each HIGH change into its own `high`-tagged step sized by the change (no file cap); fill ordinary `low`/`medium` steps toward `~12` edited files and flag `~14+` as overblown. State the fill rule as a directive, not a permission. Keep the trivial-merge floor verbatim.
2. **Reword the "Step" glossary row** (D2) in `conventions.md §1.1` so "atomic" means one coherent, logically continuous change committed together, explicitly not a minimal file count, with a pointer to the footprint guidance. Keep it terse — this is a closed-term, every-phase surface.
3. **Add the workflow-machinery risk taxonomy** (D6) to `risk-tagging.md`: a `### Workflow machinery` subsection under `## HIGH-risk triggers` (executes/load-bearing-gate/schema/always-loaded), workflow lines under MEDIUM (bounded behavioral) and LOW (prose/clarity), and a prose-only cap as the analog of the tests-only cap. Root `CLAUDE.md` is a HIGH trigger.
4. **Add the `~5`-vs-`~12` clarifying clause** (D3) at the MEDIUM `~5`-file trigger, and **sync the § Risk tagging summary** in `track-review.md` to name the new workflow category so `review-workflow-consistency` finds no drift.

Invariants to preserve: the trivial-merge floor stays; the existing "when in doubt, high" decomposer override is unchanged (no workflow-specific override added); the MEDIUM `~5` value is unchanged (only its wording is clarified); coverage gates are untouched.

**Phase A sequencing (4 steps).** Steps 1 (`track-review.md` § Step Decomposition), 2 (`conventions.md` glossary), and 3 (`risk-tagging.md` taxonomy) touch three different files and are mutually independent; step 2's footprint pointer references the rule step 1 establishes, so 1-before-2 is the sensible default. Step 4 (the `~5`/`~12` clause plus the § Risk tagging summary sync) depends on step 3 — the summary names the taxonomy category step 3 defines — and re-touches `track-review.md` (step 1's file) and `risk-tagging.md` (step 3's file), so it runs last. All four steps are `low`-risk under the live taxonomy (see Decision Log); none reaches step-level dimensional review, and the cumulative diff is reviewed at Phase C.

## Concrete Steps

1. Rewrite `track-review.md` § Step Decomposition (staged copy): replace the `~3`-edited-file cap (`:717`) with the three sizing rules — coherence (all tiers: split a step that does unrelated things); high-risk isolation (each HIGH change in its own `high`-tagged step, sized by the change, no file cap); fill ordinary `low`/`medium` steps toward `~12` edited files and flag `~14+` as overblown, stated as a directive not a permission. Keep the trivial-merge floor verbatim, including its `(single import, single rename)` parenthetical [T1]. — risk: low (default — workflow prose; under the live taxonomy `.claude/**` doc edits are docs/LOW)  [x] commit: 857072a5c41520dea84378d64ae4e5588faea987
2. Reword `conventions.md §1.1` Glossary "Step" row (staged copy, `:70`): "atomic" = one coherent, logically continuous change committed together, explicitly not a minimal file count, with a pointer to the `track-review.md` footprint guidance. Keep it terse (closed-term, `roles=any phases=any` surface) and non-contradictory with the step-1 sizing rules. — risk: low (default — workflow prose/glossary; live taxonomy treats as docs/LOW)  [x] commit: 4c1804d4d332510e73aad43e58b2cbc227d966b5
3. Add the workflow-machinery risk taxonomy to `risk-tagging.md` (staged copy): a `### Workflow machinery` subsection under `## HIGH-risk triggers` (`:94`) keyed to whether the artifact executes or drives control flow plus always-loaded blast radius — root `CLAUDE.md` is HIGH; workflow MEDIUM (bounded behavioral) and LOW (prose/clarity) lines under `:156`/`:172`; and a prose-only cap modeled on the `## Tests-only steps` precedent (`:187`). The prose-only cap MUST carry the full qualifier "no hook/script/settings change AND no gate/dispatch/schema change" so it cannot fire on a control-flow-driving prose edit that the HIGH taxonomy also matches [A1]; state the schema-change-vs-gloss hinge explicitly so a meaning-changing glossary/TOC/enum edit reads HIGH while a wording-preserving gloss reads prose-only/LOW [R1]; keep the cap's cross-tier scope unambiguous (a ceiling for prose-only edits, not a HIGH-only carve-out) [R2]; optionally add a half-sentence distinguishing this risk bucket from the `review-agent-selection.md` "workflow-machinery" file-set predicate if it costs no meaningful length [R3]. — risk: low (default — workflow prose; the workflow risk taxonomy this step ADDS is not yet live per the §1.7(g) I6 invariant, so the step is classified under the live docs/LOW default)  [x] commit: ea12eade9896281b6f477157f476ed5b8dd9cf27
4. In `risk-tagging.md` (staged copy), add the `~5`-vs-`~12` clarifying clause at the MEDIUM `~5`-file trigger (`:163`) tying `~5` (raise to `medium`) and `~12` (split cap) as complementary, not rival; the clause should note that fill-toward-`~12` will routinely push ordinary single-module steps past `~5` → a larger `medium`-tagged population at Phase C, which is intended (larger diffs warrant more focal-point attention), not a miscalibration [A4]; the `~5` value itself is unchanged. Then sync `track-review.md` § Risk tagging summary (staged copy, `:733`) to name the new workflow-machinery category AND correct the hard count word "Six HIGH categories" so it matches `risk-tagging.md` after step 3 (no stale "Six") [A2], so `review-workflow-consistency` finds no drift. Depends on step 3 (needs the taxonomy's category name). — risk: low (default — workflow prose; live taxonomy docs/LOW)  [x] commit: 394d8965dddcbfeabdf59672838479c2485fba67

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 857072a5c41520dea84378d64ae4e5588faea987, 2026-06-04T14:38Z [ctx=safe]
**What was done:** Rewrote the staged § Step Decomposition in `track-review.md`, replacing the single `~3`-edited-file cap with three sizing rules: coherence (the only mandatory split, all tiers; file count alone never forces a split), high-risk isolation (each HIGH change in its own `high`-tagged step, sized by the change, no file cap), and a fill-toward-~12-edited-files directive for ordinary `low`/`medium` steps that flags ~14+ as overblown and is phrased as a directive, not a permission. The trivial-merge floor (with its `(single import, single rename)` parenthetical) and the cross-cutting-concerns rule survive verbatim. First touch copied the live file in byte-identical, so the live `.claude/workflow/track-review.md` is unchanged from develop.

**What was discovered:** The branch merge-base is `786f441e`; the diff against it is `_workflow/`-only with zero production source, so the implementer's test-additive shortcut applies even though `git diff origin/develop` lists unrelated production files that sit ahead on develop. Step 4 will sync the § Risk tagging summary block in this same staged file; that block still reads "Six HIGH categories …" unchanged by this step.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/track-review.md` (new — staged copy of the live file carrying the § Step Decomposition rewrite)

### Step 2 — commit 4c1804d4d332510e73aad43e58b2cbc227d966b5, 2026-06-04T14:44Z [ctx=safe]
**What was done:** Reworded the §1.1 Glossary "Step" row in the staged `conventions.md`. The old "A single atomic change = one commit. Fully tested." now defines "atomic" as one coherent, logically continuous change committed together, explicitly not a minimal file count, and points to the footprint guidance in `track-review.md` §Step Decomposition. The wording reuses the staged step-1 coherence phrasing verbatim, so the glossary and § Step Decomposition agree. First touch copied the live file in byte-identical; only the "Step" row changed, and the live `.claude/workflow/conventions.md` is unchanged from develop.

**What was discovered:** `conventions.md` is now staged with develop's content everywhere except the "Step" row. Track 3 also edits this file (§1.2, §Scope indicators, and the §1.1 "Scope indicator" row); its first touch must edit the already-staged copy rather than re-copy from live, or this step's reword is lost. The §1.7(d) staged-read precedence already mandates this. For a cross-file reference inside a glossary table cell, use the plain-backtick form (`track-review.md` §Step Decomposition) that the sibling rows use, not a `name:roles:phases` suffix.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/conventions.md` (new — staged copy of the live file with the §1.1 "Step" row reworded)

### Step 3 — commit ea12eade9896281b6f477157f476ed5b8dd9cf27, 2026-06-04T14:50Z [ctx=safe]
**What was done:** Added the workflow-machinery risk taxonomy to the staged `risk-tagging.md`. A `### Workflow machinery` subsection under `## HIGH-risk triggers` keys HIGH to whether the artifact executes or drives control flow plus always-loaded blast radius, and classifies root `CLAUDE.md` HIGH (stating why not MEDIUM). A workflow MEDIUM line (bounded behavioral) and a workflow LOW line (prose/clarity) sit under their respective sections. A new `## Prose-only workflow steps` section adds the prose-only cap, modeled on the `## Tests-only steps` precedent: it carries the full [A1] qualifier ("no hook/script/settings change AND no gate/dispatch/schema change") in both the intro and the hinge paragraph, states the [R1] schema-vs-gloss hinge, frames the [R2] cross-tier ceiling (a step qualifies on the content of the change, not the identity of the file), and includes the [R3] distinction from the `review-agent-selection.md` file-set predicate. Two document-index TOC rows keep the index in sync. First touch copied the live file in byte-identical; the `~5` MEDIUM clause and the § Risk tagging summary are untouched (Step 4's job), and the live `.claude/workflow/risk-tagging.md` is unchanged from develop.

**What was discovered:** The [A1] qualifier must stay on one source line — a mid-token line break after `hook/script/` renders as `hook/script/ settings` with a stray space.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/risk-tagging.md` (new — staged copy with the workflow-machinery taxonomy and the prose-only cap)

### Step 4 — commit 394d8965dddcbfeabdf59672838479c2485fba67, 2026-06-04T14:54Z [ctx=safe]
**What was done:** Added the `~5`-vs-`~12` clarifying clause to the MEDIUM `~5`-file trigger in the staged `risk-tagging.md`, then synced the § Risk tagging summary in the staged `track-review.md`. The clause ties `~5` (raises a logic step to `medium`) and `~12` (the fill/split cap) as two readings of the same edited-file count for two decisions, complementary not rival, and notes that fill-toward-`~12` routinely pushes single-module steps past `~5` into a larger `medium`-tagged Phase C population — intended, not a miscalibration. The `~5` value is unchanged. The summary's HIGH-category count moved from "Six" to "Seven", "workflow machinery" joined the HIGH enumeration, and the MEDIUM and LOW bands gained their workflow phrasings, so the summary no longer drifts from `risk-tagging.md`. Both edits landed on already-staged copies in place; the live files are unchanged from develop.

**What was discovered:** The staged `risk-tagging.md` has seven HIGH-category `###` subsections after step 3 (concurrency, crash-safety/durability, public API, security, architecture, performance hot path, workflow machinery), so the summary's "Six" was off by exactly one: the workflow-machinery category step 3 added. Any later edit that re-enumerates HIGH categories must hold this count at seven.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/risk-tagging.md` (modified — `~5`/`~12` clarifying clause)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/track-review.md` (modified — § Risk tagging summary sync)

## Validation and Acceptance

Track-level behavioral acceptance (Phase A turns these into per-step EARS/Gherkin lines):

- `track-review.md` § Step Decomposition no longer contains the `~3`-file cap; it states the three sizing rules (coherence, high-risk isolation, fill-toward-~12 with `~14+` flagged) and still carries the trivial-merge floor.
- `conventions.md §1.1` "Step" row defines "atomic" as one coherent change committed together, explicitly not a minimal file count, with a footprint pointer; it does not contradict the `track-review.md` rules.
- `risk-tagging.md` carries a `### Workflow machinery` HIGH subsection, workflow MEDIUM and LOW lines, and a prose-only cap; root `CLAUDE.md` is classified HIGH.
- The MEDIUM `~5`-file trigger carries a clause tying it to the `~12` split cap so the two numbers read as complementary, not rival; the `~5` value is unchanged.
- `track-review.md` § Risk tagging summary names the workflow category, matching `risk-tagging.md` (no consistency drift).
- Every edit lives under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...`; the live `.claude/**` tree is byte-unchanged from develop.

Per-step acceptance (Phase B verifies each against the staged copies):

- Step 1: `track-review.md` § Step Decomposition (staged) contains no `~3`-file cap; it states all three sizing rules (coherence; high-risk isolation with no file cap; fill-toward-`~12` with `~14+` flagged) and retains the trivial-merge floor verbatim, including the `(single import, single rename)` parenthetical.
- Step 2: `conventions.md §1.1` "Step" row (staged) defines "atomic" as one coherent change committed together, explicitly not a minimal file count, with a footprint pointer, and does not contradict the step-1 rules.
- Step 3: `risk-tagging.md` (staged) carries a `### Workflow machinery` HIGH subsection (root `CLAUDE.md` = HIGH), workflow MEDIUM and LOW lines, and a prose-only cap whose text contains the full "no hook/script/settings change AND no gate/dispatch/schema change" qualifier [A1] and a stated schema-change-vs-gloss hinge [R1].
- Step 4: the MEDIUM `~5`-file trigger (staged) carries a clause tying `~5` to the `~12` split cap (the `~5` value unchanged); `track-review.md` § Risk tagging summary (staged) names the workflow-machinery category and its HIGH-category count matches `risk-tagging.md` (no stale "Six") [A2], so `review-workflow-consistency` finds no drift.
- All steps: every edit lives under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...`; the live `.claude/**` tree is byte-unchanged from develop.

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery

Each step edits a staged copy under `_workflow/staged-workflow/.claude/...` (first touch copies the live file in verbatim per §1.7(e)) and commits once. Idempotence: re-running a step's edit on an already-edited staged copy is a no-op when the target text is already present, so the implementer checks the staged copy's current state before editing. Recovery: an uncommitted step edit is discarded by the implementer's `git reset --hard HEAD`, so each step commits before the next begins (§What You Do sub-step 6); a crashed step re-runs from the live-or-staged read. Step 4 must not run before step 3's commit — it reads the taxonomy's category name from the staged `risk-tagging.md`.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (staged copies under `_workflow/staged-workflow/.claude/...`):**
- `.claude/workflow/track-review.md` — § Step Decomposition (the `~3`-file cap line + trivial-merge floor) and the `#### Risk tagging` summary block.
- `.claude/workflow/conventions.md` — the §1.1 Glossary "Step" row only.
- `.claude/workflow/risk-tagging.md` — `## HIGH-risk triggers` (add `### Workflow machinery`), `## MEDIUM-risk triggers`, `## LOW-risk default`, the MEDIUM `~5`-file trigger clause, and the § Tests-only precedent for the prose-only cap.

**Out-of-scope (owned by Track 2 or deliberately not edited):**
- `review-agent-selection.md`, `step-implementation.md`, `track-code-review.md`, `code-review/SKILL.md`, and the `risk-tagging.md` `high` quick-ref row (`:65`) — all Track 2.
- Verified non-targets: `conventions.md` mcp-steroid refactor `~3`-files rule, `conventions-execution.md` edit-atomicity "atomic", the `step-implementation.md` high-only step-review gate and session-end context gate (cited as load-bearing guardrails, not edited).

**Dependencies:**
- **Downstream:** Track 2's workflow-reviewer triage (D5) keys off this track's `### Workflow machinery` taxonomy (D6); the taxonomy must land before Track 2 consumes it.
- **Cross-track file:** `risk-tagging.md` is also touched by Track 2 (the `high` quick-ref row at `:65`), a disjoint section. Under §1.7 staging the staged copy accumulates both tracks' edits; each track's Phase C review delta-scopes to its own sections.

**Staging contract:** workflow-modifying marker present in `implementation-plan.md` § Constraints; writes route to the staged subtree; the staged-vs-live delta gets the Phase C `§1.7(h)` review, delta-scoped to the live-vs-staged diff (D5 convention), not the whole-file staged copy.
