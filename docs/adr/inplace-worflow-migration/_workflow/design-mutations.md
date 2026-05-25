# Design Mutations Log

Append-only log of every mutation to `design.md`. Read by `edit-design`'s
`design-sync` step to find the last sync point.

## Mutation 1 — 2026-05-21 — phase1-creation

**Diff summary**: initial seed of `design.md` for the in-place workflow
migration plan. Single-file design (no `design-mechanics.md` companion —
small design, no `# Part N` headings, no anticipated long-form
derivations). Sections: Overview, Core Concepts (five terms),
Workflow (three sequence diagrams — drift check, migration replay,
reflection), Stamp range computation, Per-commit replay and lockstep
advance, Reflection parameterization. Five DRs and four invariants
defined in the plan are referenced from per-section footers with
short labels inline.

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 findings)
**Cold-read** (scope: whole-doc): NEEDS REVISION resolved to PASS after one revision pass

**Findings (resolved in iteration 1)**:
- blocker: References footers in three sections cited D/I codes by bare IDs without short labels — fixed by normalizing to the `D<n> (short label)` form already used by the first two footers.
- blocker: D-records and Invariants are load-bearing in the design but defined in the plan — partially resolved by inline short labels in every footer; the design and plan are read together (canonical workflow), so a full `## References` document-level catalogue was not added to avoid duplication with `implementation-plan.md`.
- should-fix: `## Reflection parameterization` opened mechanism-first — renamed to `## Reflection parameterization — one knob, three conditional clauses` and reshaped TL;DR to lead with the user-visible effect.
- should-fix: `## Stamp range computation` TL;DR opened with the mechanism — prepended the motivation sentence ("Drift detection, migration range derivation, and the post-migration synced-to-X claim all need the same lower bound...") so the why arrives before the what.
- should-fix (deferred): `## Workflow` is 129 lines across three sibling subsections (drift check / migration / reflection). Sibling-consolidation form was considered and rejected — the three runtime flows are semantically distinct (different participants, different triggers, different terminal states); a side-by-side comparison table would obscure the per-flow sequence diagrams. Kept as-is.
- suggestion (accepted as-is): Audience-fit cue not made explicit in Overview — the framing ("Today the user runs the skill from a `develop` worktree...") strongly implies the workflow-tooling-maintainer audience per the house-style "strongly imply through concrete framing" allowance.

**Iterations**: 2 of 3 (PASS — one revision pass after cold-read)

## Mutation 2 — 2026-05-21 — content-edit

**Diff summary**: replaced misleading "min" terminology for SHA ordering throughout the design. SHAs have no inherent ordering — the load-bearing primitive is `git merge-base` (pairwise common ancestor). Renamed the bash variable `MIN_SHA` to `BASE_SHA`, replaced the `git merge-base --is-ancestor` test with a `git merge-base $SHA $BASE_SHA` fold so divergent histories yield the right common ancestor rather than keeping the first seen, and rewrote every "min over stamps" prose mention to "fold pairwise through `git merge-base`" or "oldest stamp in develop's commit graph." Also added one explanatory paragraph under §"Stamp range computation" naming the primitive explicitly and reshaped two paragraphs (Overview, Core Concepts §"Stamp range") that exceeded em-dash density after the rewrite. Companion edits to `implementation-plan.md`, `plan/track-3.md`, `plan/track-4.md` carry the same vocabulary.

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 findings)
**Cold-read** (scope: whole-doc): SKIPPED — terminology fix is mechanical; mechanical checks cover the structural surface that cold-read protects.

**Findings**: none.

**Iterations**: 1 of 3 (PASS)

## Mutation 3 — 2026-05-21 — content-edit

**Diff summary**: dropped the silent fork-point fallback for unstamped artifacts. User identified that `git merge-base origin/develop HEAD` shifts forward under rebase, so a legacy branch rebased onto a newer develop would have its fork-point land near the new develop tip and silently mark unstamped artifacts as already-current, skipping the migration. New design: drift check signals drift unconditionally on any unstamped artifact (no fold, no `git log`); migration adds a Step 3.0 that prompts the user once for a base SHA covering the unstamped set, validates against `origin/develop` with up to 3 retry attempts, then folds the user SHA in with the stamped set. Touched §"Overview", §"Core Concepts" (renamed "Fork-point fallback" entry to "Unstamped-artifact bootstrap"), §"Workflow → Drift detection" (sequence diagram + TL;DR), §"Workflow → Migration replay loop" (sequence diagram + TL;DR), §"Stamp range computation" (split into Phase 1 walk + Phase 2 caller-specific; added explicit "Why no silent fork-point fallback" subsection), Edge cases (added user-SHA validation failure + semantically-wrong-SHA cases). Added new Decision Record D8 to the plan covering the design choice. Companion edits across `implementation-plan.md` (constraint, D5 rationale, I3 invariant, integration points, Track 1/3/4 checklist intros) and `track-1.md` / `track-3.md` / `track-4.md` (Plan of Work + Validation + Interfaces).

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 findings)
**Cold-read** (scope: whole-doc): SKIPPED — semantically scoped change to one design decision; the new content was validated against the user's stated failure scenario (rebase-shifts-fork-point) directly.

**Findings**: none.

**Iterations**: 1 of 3 (PASS)

## Mutation 4 — 2026-05-21 — content-edit (design.md)

**Diff summary**: extended §"Workflow → Drift detection at /execute-tracks startup" to reflect Track 6 (drift gate at `/create-plan` startup). TL;DR opening sentence now names both callers — `/execute-tracks` turn 1 and `/create-plan` between Step 1 and Step 1a — and notes they run the same detection block. Added one Edge-cases bullet about caller-specific skip-#1 ordering: Step 1.5 must run before Step 1b (mkdir) so the skip sees the pre-creation state on a fresh branch. Added D9 (gate fires at /create-plan startup too) to the References footer's D-records list. No changes to the sequence diagram, the section heading, the per-commit replay loop, or any other section.

**Mechanical checks** (target=design, scope=bounded): PASS (0 findings)
**Cold-read** (scope: bounded): PASS (0 findings)

**Findings**: none.

**Iterations**: 1 of 3 (PASS)

## Mutation 5 — 2026-05-21 — structural-rewrite (design.md)

**Diff summary**: pivoted the design from develop-relative comparison to HEAD-relative comparison. The drift gate and the migration both now compute their range as `BASE_SHA..HEAD`; no `git fetch origin develop` runs anywhere; the branch is framed as a self-contained capsule into which workflow commits enter only via explicit rebase or merge. Three new decision records (D10 capsule, D11 no-drift normalization + commit, D12 migration preflight refusal on dirty `_workflow/**`) carry the rationale; D2 retargets the post-migration stamp from "last replayed commit's SHA" to `HEAD`'s SHA via a new Step 5.7 final batch; I2 updated to state stamps equal `git rev-parse HEAD` after migration; new I5 covers post-normalization uniformity. Sections rewritten: Overview (paras 2-3), Core Concepts (Stamp range / Unstamped-artifact bootstrap / Lockstep stamp advance rewritten; new No-drift normalization term), Workflow → Drift detection (renamed from "/execute-tracks startup" to "session startup"; sequence diagram replaces ET with Caller and develop with local Git; adds no-drift normalization branch), Workflow → Migration replay loop (preflight refusal added; final stamp-to-HEAD batch added before final summary), Stamp range computation (bash blocks drop fetch + develop verify; range changes to `BASE_SHA..HEAD`; "Why no silent fork-point fallback" renamed to "Why no silent auto-computed reference"), Per-commit replay and lockstep advance (final HEAD batch bash added; crash-recovery semantics across both phases clarified). References footers updated to add D10, D11, D12, I5. Companion edits across `implementation-plan.md` (D2/D8/I3 updates, new D10/D11/D12, I2 update, new I5, Component Map relabels, Integration Points additions, Track 3/4/6 intro rewrites) and `plan/track-1.md` / `track-3.md` / `track-4.md` / `track-6.md` (Context, Plan of Work, Validation, External interfaces updated to use HEAD).

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 findings) — initial run reported 5 em-dash density should-fixes and 1 fragmented-header should-fix; all resolved in iteration 1.
**Cold-read** (scope: whole-doc): PASS (0 findings) — sub-agent confirmed cross-section consistency on the HEAD-vs-develop pivot, no residual `origin/develop` references outside historical / counter-example contexts, every D-record (D10, D11, D12) and invariant (I5) reachable in both `design.md` and `implementation-plan.md`.

**Findings**:
- should-fix (resolved iter 1): em-dash density 2/paragraph at lines 25, 129, 179, 246 — replaced em dashes with periods, commas, or colons.
- should-fix (resolved iter 1): em-dash density 3/paragraph at line 229 ("Why no silent auto-computed reference" rationale) — restructured the long aside to use parentheses + colon.
- should-fix (resolved iter 1): fragmented header at line 244 ("Per-commit replay and lockstep advance") — orientation paragraph echoed all four heading content words; merged the orienting sentence into the TL;DR so the heading stands alone.

**Iterations**: 2 of 3 (PASS — mechanical iterated once after fixes; cold-read PASS on first run)

## Mutation 6 — 2026-05-21 — content-edit (design.md)

**Diff summary**: replaced `;` with `,` in three Mermaid `sequenceDiagram` message labels — two inside the drift-detection diagram (`### Drift detection at session startup`, lines 55 and 65) and one inside the migration-replay diagram (`### Migration replay loop`, line 126). Mermaid's `sequenceDiagram` grammar treats `;` as a statement separator inside a message, so each label truncated at the `;` and the parser then expected an arrow before the next newline. Confirmed by parsing both diagrams with `mermaid.parse()` from the mermaid npm package: pre-fix throws `Parse error on line 18` (drift detection block) and `line 34` (migration replay block); post-fix both parse cleanly. No prose, headings, Decision-Record refs, `**Full design**` refs, invariants, or References footers touched.

**Mechanical checks** (target=design, scope=bounded): PASS (0 findings)
**Cold-read** (scope: bounded): PASS (0 findings)

**Findings**: none.

**Iterations**: 1 of 3 (PASS)

## Mutation 7 — 2026-05-21 — structural-rewrite (design.md)

**Diff summary**: tightened drift detection and migration scope from branch-wide (every `docs/adr/*/_workflow/`) to active-plan-only (the single plan directory the caller resolved at startup). Added new Decision Record **D13** carrying the rationale: each plan is migrated independently; cross-plan folding yields a `BASE_SHA` older than the active plan needs and inflates the replay range; today's `/migrate-workflow` already targets exactly one plan via a zero/one/many ladder, so the drift check now matches. Touched sections: §"Drift detection at session startup" (TL;DR + Edge cases + References footer), §"Migration replay loop" (TL;DR + References footer), §"Stamp range computation" (TL;DR + bash block introducing `$PLAN_DIR` + Edge cases + References footer), §"Per-commit replay and lockstep advance" (TL;DR + crash-recovery prose + bash batch label + References footer). Companion edits across `implementation-plan.md` (Constraints, Component Map for `workflow-drift-check.md` and `migrate-workflow`, new D13 with Tracks 1/3/4/6 in Implemented-in, I2 + I3 + I5 invariants tightened, Integration Points entries for drift-check Detection / migration preflight / migration Step 3, Track 3 and Track 4 checklist intros) and `plan/track-3.md` / `plan/track-4.md` / `plan/track-6.md` (Purpose, intro, Context, Plan of Work, Validation, Interfaces).

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 findings) — initial run reported 1 should-fix (Tier-1 banned vocabulary `journey` in two places); resolved in iteration 1.
**Cold-read** (scope: whole-doc): PASS (0 findings) — initial run reported 6 should-fix; resolved in iteration 1.

**Findings**:
- should-fix (resolved mechanical iter 1): Tier-1 banned vocabulary `journey` in `design.md:79` and `implementation-plan.md` D13 Rationale — replaced with "migrated independently" in both places.
- should-fix (resolved cold-read iter 1): References footer in §"Drift detection at session startup" omitted D13 despite TL;DR and first Edge case citing it — appended `D13 (active-plan scope)`.
- should-fix (resolved cold-read iter 1): References footer in §"Migration replay loop" omitted D13 despite TL;DR citing it — appended `D13 (active-plan scope)`.
- should-fix (resolved cold-read iter 1): References footer in §"Stamp range computation" omitted D13 despite TL;DR and Phase 1 prose citing it — appended `D13 (active-plan scope)`.
- should-fix (resolved cold-read iter 1): §"Per-commit replay and lockstep advance" TL;DR + crash-recovery prose used branch-wide framing while the bash batch label below had been tightened to "active plan's `_workflow/`" — TL;DR rewritten to "every stamped artifact in the active plan's `_workflow/`" and "every artifact in the active plan to `git rev-parse HEAD` (D2 + D13)"; crash-recovery sentence changed to "advances every stamp in the active plan to HEAD's SHA"; References footer appended D13.
- should-fix (resolved cold-read iter 1): invariant I2 in `implementation-plan.md` used branch-wide framing while I3 and I5 already scoped to the active plan — I2 now reads "every stamped artifact in the active plan's `_workflow/` has its line-1 SHA equal to `git rev-parse HEAD`".
- should-fix (resolved cold-read iter 1): `plan/track-4.md` Purpose and intro paragraph used branch-wide framing without citing D13 — Purpose now cites D13 explicitly; intro paragraph adds the same scope qualifier and tightens "every stamp" / "every artifact" mentions to "in the active plan".
- should-fix (resolved cold-read iter 1): D13 Implemented-in list omitted Track 1's canonical-definition role — appended "Track 1 defines the active-plan scope inline in `conventions.md` §1.6 so the drift check and the migration cite one source of truth."
- suggestion (deferred, pre-existing): design.md line 13 Overview roadmap says "Core Concepts (the five new domain terms)" but Core Concepts lists six entries. Same mismatch surfaced in the Mutation 6 cold-read as out-of-scope; not acted on here either. Would naturally fold into a future Overview-touching content-edit.

**Iterations**: 2 of 3 (PASS — mechanical iterated once after the `journey` fix; cold-read iterated once after the six structural findings)

## Mutation 8 — 2026-05-22 — content-edit (design.md)

**Diff summary**: excluded `design-mutations.md` from the stamped-artifact surface across three axes — (a) no stamp written on the file, (b) file omitted from the fold-input enumeration, (c) file omitted from the migration replay enumeration. Rationale: `design-mutations.md` is an append-only log whose stamp would always equal `design.md`'s stamp (both created in the same `phase1-creation` invocation, both advanced together by migration's lockstep, neither touched by direct mutations per I4), so it contributes zero new information to the fold input; schema commits affecting the log are replay-immune by virtue of the log's append-only contract, so migration replay against this file would always classify as `noop` or `manual-review-needed`. Track 2's prior "appends don't touch the stamp" carve-out is promoted to full exclusion: cleaner rule, one less special case. Companion fix folded in: Overview line 13 and Core Concepts intro line 17 both updated from "five new domain terms" to "six new domain terms" to match the six-entry Core Concepts list — this resolved the deferral noted in Mutation 7's suggestion (and surfaced earlier in Mutation 6's cold-read). Sites touched in design.md: Overview line 13 (count), Core Concepts intro line 17 (count + em-dash → colon swap), §"Stamp range computation" bash block (dropped `design-mutations.md` enumeration line), §"Stamp range computation" Edge cases bullet (rewrote "five stamped artifact types" → "four" with appended rationale for the exclusion). Companion edits across `implementation-plan.md` (Component Map node label drops `design-mutations.md`; Component Map `edit-design` bullet drops "first append" mention; Integration Points `phase1-creation` bullet drops "first append" mention; Track 2 checklist intro rewrites "Five sites total" → "Four sites total" and scope indicator from "~3-4 steps" to "~3 steps"; new Non-Goal bullet "Stamping `design-mutations.md`") and `plan/track-1.md` (Plan of Work item (f) drops `design-mutations.md` from the stamped-artifact-types list and adds exclusion rationale) and `plan/track-2.md` (intro paragraph rewrites "Five sites total" → "Four sites total"; Context paragraph reframes Step 7's role; Context H1 list drops the `design-mutations.md` entry; Plan of Work paragraph rewrites the Step 7 instructions from "prepend stamp" to "deliberately NOT touched"; Validation bullet flips from "initializes with stamp" to "carries NO line-1 stamp").

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 findings)
**Cold-read** (scope: whole-doc): PASS (0 findings, after one iteration to fix the Core Concepts intro count)

**Findings**:
- blocker (resolved cold-read iter 1): design.md line 17 still read "Five new domain terms appear throughout this design" while Overview line 13 had been updated to "six" and Core Concepts enumerates six entries — fixed to "Six new domain terms appear throughout this design" and the line's em dash swapped for a colon to keep em-dash density safe.
- suggestion (retained, deliberate idiom): the bash enumeration at line 187 includes `design-mechanics.md` even though this design has no mechanics companion; the enumeration is the canonical template for the drift check and migration, and `ls … 2>/dev/null` handles the absence gracefully on designs without a companion. Not a finding to act on.

**Iterations**: 2 of 3 (PASS — mechanical PASS on first run; cold-read iterated once after the line-17 blocker)

## Mutation 6 — 2026-05-22 — content-edit

**Diff summary**: Phase-2 State-0 consistency-review fixes CR1 and CR6 landed atomically as a content-edit pair targeting two named sections. CR6: added a sentence to Core Concepts → "Workflow-SHA stamp" (line 19) naming the SHA-computation one-liner `git log -1 --format=%H HEAD -- .claude/workflow .claude/skills` with a cross-link to `conventions.md §1.6` and the `create-plan` / `edit-design` SKILLs in Track 2. CR1: rewrote the §"Per-commit replay and lockstep advance" TL;DR (line 246) and the per-crash-window prose (line 276) to land the lockstep advance BETWEEN sub-step 4.4 (apply edits) and sub-step 4.6 (progress sentinel), matching the §"Workflow → Migration replay loop" sequenceDiagram order; the old prose claimed the advance fired after both 5.4 and 5.5. Updated step numbers throughout the section from the legacy 5.x to Track 4's renumbered 4.x scheme (4.4 / 4.5 / 4.6 / 4.7 / 4.8). Edge-cases bullets at lines 280-282 also renumbered.

**Mechanical checks** (target=design, scope=whole-doc): PASS (0 blockers, 1 should-fix recorded as known debt)
**Cold-read** (scope: whole-doc): PASS (0 blockers, 0 should-fix, 4 suggestions recorded as known debt)

**Findings**:
- should-fix (mechanical, pre-existing, deferred): `dsc-ai-tell` at line 244 — section heading "Per-commit replay and lockstep advance" shares 75% content words with the first paragraph (threshold 50%). Existed before CR1's edit (the previous TL;DR opened with "Inside the migration's Step 5 per-commit loop"); the rewrite preserved the structural overlap. Not addressed in this mutation because the fragmentation pre-dates the edit and the budget for State 0 mechanical fixes is consistency-driven, not narrative-shape-driven. Carry forward as known debt for a future content-edit.
- suggestion (cold-read, accepted as known debt): line 19 embeds "in Track 2" — an internal working-file identifier — inside design.md prose. Tolerable while `_workflow/` survives; Phase 4 will scrub on creation of `design-final.md`.
- suggestion (cold-read, accepted as known debt): line 276 uses "→ ... → final HEAD batch (4.8)" with an ellipsis between the per-commit task flip (4.7) and the post-loop final batch (4.8). The ellipsis hides the loop boundary but the sequenceDiagram and Track-4's Plan-of-Work item 7 both make the boundary explicit.
- suggestion (cold-read, accepted as known debt): line 276 mentions "the no-drift normalization path" in a parenthetical describing the 4.8 final HEAD batch — the phrase is also used for the drift gate's D11 path (committed by the gate, not the skill). The conflation is mild because the rest of design.md keeps the two writers separate (line 131 calls them out explicitly).
- suggestion (cold-read, pre-existing, accepted as known debt): line 13 section-roster preamble doesn't list §"Reflection parameterization". Pre-existing — not introduced by CR1/CR6.

**Iterations**: 1 of 3 (PASS — mechanical and cold-read both PASS on first run; only suggestions surfaced)

## Mutation 7 — 2026-05-22 — content-edit

**Diff summary**: Phase-2 State-0 consistency-gate regression fix CR7. Two step-number substitutions inside §"Reflection parameterization — one knob, three conditional clauses": (1) the example SKILL snippet's `## Step 7 — Self-improvement reflection` heading becomes `## Step 6 — Self-improvement reflection`, since Track 4's renumber-down made today's Step 6 (final summary) into Step 5, so the new reflection step lands as Step 6 rather than Step 7; (2) the edge-cases bullet referencing "`migrate-workflow/SKILL.md` Step 6 (final summary)" becomes "Step 5 (final summary, post-Track-4 renumber)". Both edits also include a short clarifying parenthetical naming the Track 4 renumber convention so a cold reader can trace the number choice without external context.

**Mechanical checks** (target=design, scope=bounded on `Reflection parameterization — one knob, three conditional clauses`): PASS (0 findings)
**Cold-read** (scope: bounded): SKIPPED — numeric step-renumber is mechanical; cold-read for the parent §"Reflection parameterization" section ran in Mutation 6 (whole-doc PASS) and the diff carries no narrative shift. Precedent: Mutation 2 took the same skip path for the equivalent BASE_SHA-rename substitution.

**Findings**: none.

**Iterations**: 1 of 3 (PASS)

## Mutation 9 — 2026-05-22 — content-edit (design.md)

**Diff summary**: Phase-2 State-0 structural-review fix S2 propagation into design.md. The structural review split the original Track 4 (In-branch migrate-workflow, ~6-8 steps) into Track 4a (Migration preflight + range computation, ~4-5 steps) and Track 4b (Migration replay + final batch, ~3-4 steps). Three cross-references in design.md narrate the original Track 4's renumber-down operation; all three now reference the split. Sites touched: §"Per-commit replay and lockstep advance" TL;DR line 246 ("Track 4's renumbering" → "Tracks 4a/4b's renumbering"); §"Step 6 — Self-improvement reflection" opening prose line 300 ("Track 4's renumber-down" → "Tracks 4a/4b's renumber-down"); §"Reflection parameterization → Edge cases / Gotchas" bullet line 314 ("post-Track-4 renumber" → "post-Tracks-4a/4b renumber"). The third site (line 314) was missed by the initial literal-text replacement pair because the hyphenated form "Track-4" did not match the unhyphenated anchors "Track 4's"; cold-read caught it as a should-fix in iteration 1 and the iteration-2 fix landed before the mutation closed. Companion edits on `implementation-plan.md` (DR Implemented-in lines for D1/D2/D5/D8/D10/D12/D13 updated to name 4a vs 4b, Component Map `migrate-workflow` Touched-in line, D10 Risks/Caveats reference to "Track 4b's intro", Integration Points Step-2 renumber annotation, Non-Goals renames-tracker reference, Checklist split into Track 4a + Track 4b entries with new intros, Track 5 Depends-on changed to Track 4b, S4 `**Full design**` bullets added to D1/D2/D7/D8/D10/D11/D13, S1 intro trims for Tracks 2/3/6) and `plan/track-2.md` / `plan/track-3.md` / `plan/track-5.md` / `plan/track-6.md` (Track 4 cross-references retargeted to Track 4a/4b as appropriate) and `plan/track-4.md` → `plan/track-4a.md` (renamed via `git mv`, full content rewrite to the preflight + range scope) plus new `plan/track-4b.md` (replay + final batch scope, Depends-on Track 4a). Track files outside `_workflow/design.md` are processed via native `Edit`/`Write` per the skill's scope rule (mutation discipline applies to design.md / design-mechanics.md, not plan or track files).

**Mechanical checks** (target=design, scope=bounded on `Per-commit replay and lockstep advance`): PASS (0 blockers, 1 should-fix carried forward as known debt)
**Cold-read** (scope: bounded): iteration 1 NEEDS REVISION (1 should-fix at line 314 — Track-4 / Tracks-4a/4b inconsistency); iteration 2 fix landed, grep-verified zero remaining bare "Track 4" or "Track-4" tokens; iteration-2 sub-agent re-run skipped per the orchestrator's warning-threshold context check (the only iter-1 cold-read finding was mechanically verifiable post-fix).

**Findings**:
- should-fix (cold-read iter 1, resolved iter 2): design.md line 314 inside §"Reflection parameterization → Edge cases / Gotchas" carried "post-Track-4 renumber" while sibling sites on lines 246 and 300 had been updated to "Tracks 4a/4b" — fixed to "post-Tracks-4a/4b renumber"; grep confirms zero remaining bare Track 4 / Track-4 tokens in design.md.
- should-fix (mechanical, pre-existing, deferred): `dsc-ai-tell` at line 244 — section heading "Per-commit replay and lockstep advance" shares 75% content words with the TL;DR paragraph at line 246 (threshold 50%). Pre-existed this mutation (also documented as deferred known debt in Mutation 6, line 130 above). The current mutation's two literal-text edits did not introduce new content-word overlap; not addressed here.

**Iterations**: 2 of 3 (PASS — mechanical PASS on iters 1 and 2; cold-read iter 1 NEEDS REVISION resolved in iter 2 via the line-314 fix + grep verification; iter-2 cold-read sub-agent re-run skipped to stay within the orchestrator's context warning budget)

## Mutation 10 — 2026-05-23 — section-add (design.md)

**Diff summary**: appended a new section §"Staging for workflow-modifying branches" to design.md, formalizing the convention that branches whose plan modifies `.claude/workflow/**` or `.claude/skills/**` accumulate workflow document changes under `<plan-dir>/_workflow/staged-workflow/.claude/{workflow,skills}/...` during execution; a "Promote workflow changes" commit at Phase 4 copies the staged subtree to the live paths immediately before the existing final-artifacts commit, and the existing cleanup commit removes `_workflow/` (staged subtree included). Companion edits: Overview roadmap line updated "six new domain terms" → "seven" and added the new sub-topic; Core Concepts intro updated "Six" → "Seven"; new Core Concept entry "Staged workflow subtree" inserted after "Session-type parameter". Section body covers TL;DR, bootstrap-problem rationale, staging-tree shape, implementer-rulebook routing rule, promotion-step bash, drift-gate exclusion, plus Edge cases (rebase, deleted live target, mid-branch testing, aborted promotion, plan-file exclusion) and References footer (D10, D13, D14, I6). The mutation lands during inline-replanning triggered by Track 4a Pre-Flight ESCALATE; D14 + Track 7 + I6 are added to `implementation-plan.md` in the same logical iteration, and `plan/track-7.md` is created with the new track's scope.

**Mechanical checks** (target=design, scope=bounded on `Staging for workflow-modifying branches`): PASS (0 findings) on iter 1; PASS (0 findings) on iter 2 after the Finding-2 reword.
**Cold-read** (scope: bounded — new section + Overview + Core Concepts + structure roadmap): iter 1 PASS with 2 should-fix and 2 suggestion findings; iter 2 cold-read sub-agent re-run skipped per the Mutation 7 precedent (Finding-2 fix is a single-sentence rewording with no narrative shift; the sub-agent's suggested fix was applied verbatim).

**Findings**:
- should-fix (cold-read iter 1, design-side resolved iter 2): line 345 said "lands in `workflow.md § Final Artifacts` as a new Step 0, immediately before the final-artifacts commit" — the actual structure of `workflow.md § Final Artifacts` uses numbered items "1. Final-artifacts commit" and "2. Cleanup commit" with no "Step" prefix, and the surrounding prose states "Phase 4 lands exactly two commits on the branch." Reworded to: "lands in `workflow.md § Final Artifacts` as a new commit immediately before the existing final-artifacts commit, changing Phase 4 from two commits to three (promote-staged-workflow → final-artifacts → cleanup):". Mechanical re-run PASS.
- should-fix (cold-read iter 1, plan-side resolved in the same logical iteration): References footer cites D14 + I6 that did not yet exist in `implementation-plan.md` (D records stopped at D13, I records at I5). Resolved by landing D14 + I6 in the same inline-replanning commit as this design-section addition; the design's References footer then resolves end-to-end.
- suggestion (cold-read iter 1, not retried): TL;DR's "design-final and adr" uses bare file basenames where surrounding prose uses `design-final.md` / `adr.md`. Carry forward as known cosmetic debt; the Phase 4 mutation that produces `design-final.md` is the natural place to sweep these.
- suggestion (cold-read iter 1, not retried): Core Concepts entry could note "during Phase 3 execution" to distinguish the accumulation phase from the Phase 4 promotion phase. The body section makes the timing explicit; the one-paragraph definition is tolerable without it.

**Iterations**: 2 of 3 (PASS — mechanical PASS on iter 1 against the as-paused state; cold-read iter 1 returned PASS verdict with 2 should-fix findings; iter 2 applied the design-side fix and re-ran mechanical; cold-read iter 2 sub-agent run skipped per Mutation 7 precedent; the plan-side should-fix is resolved by the companion plan edits landing in the same inline-replanning commit)

## Mutation 11 — 2026-05-24 — content-edit (design.md)

**Diff summary**: synced the §"Stamp range computation" bash + prose to the canonical `conventions.md` §1.6 contract. CR4 from the State 0 consistency review surfaced two drifts: (a) the Phase 1 walk used the unanchored regex `grep -oE '[0-9a-f]{40}'` that §1.6(a1) explicitly rejects, and (b) the Phase 2 fold treated `git merge-base` failure as fatal (`exit 1`) instead of routing failing stamps to caller-specific recovery per §1.6(c). Track 3 implemented §1.6(c) recovery in `workflow-drift-check.md` but design.md was missed at the time. Edits: switched the Phase 1 walk regex to the anchored `workflow-sha:`-prefixed form; rewrote the fold to collect failed stamps in `MERGE_BASE_FAILED` and signpost caller-specific recovery; extended the Migration Phase 2 bullet with the merge-base-failure re-prompt loop and the both-arrays-empty `no artifacts to migrate` halt (§1.6(h) final paragraph); updated the "git merge-base is the load-bearing primitive" paragraph to describe recovery instead of fatal abort; updated the Edge Cases bullet "directory has zero artifacts" to name the canonical migration halt.

**Mechanical checks** (target=design, scope=bounded on `Stamp range computation`): iter 1 had 1 should-fix (em-dash density on line 207); iter 2 PASS (0 findings) after replacing the trailing em-dash with `per`.
**Cold-read** (scope: bounded — Stamp range computation + Migration replay loop + Per-commit replay and lockstep advance + Overview + Core Concepts): iter 2 PASS with 3 suggestion-tier findings (References footer doesn't list the §1.6 anchors the body now cites; in-block comment near-duplicates the post-fold paragraph; Migration replay loop TL;DR doesn't cross-link the recovery contract). Suggestions are not retried per the mutation discipline.

**Findings**:
- should-fix (mechanical iter 1, resolved iter 2): em-dash density on line 207 — replaced the trailing em-dash before `conventions.md §1.6(h) final paragraph` with `per`.
- suggestion (cold-read iter 2, not retried): References footer at line 255 omits the conventions.md anchors the body cites. Carry as known debt.
- suggestion (cold-read iter 2, not retried): in-block comment at lines 232-236 duplicates the post-fold paragraph. Carry as known debt.
- suggestion (cold-read iter 2, not retried): §"Migration replay loop" TL;DR doesn't cross-link the merge-base-failure recovery contract. Carry as known debt.

**Iterations**: 2 of 3 (PASS — mechanical iter 1 should-fix resolved in iter 2; cold-read iter 2 PASS with three suggestions held)

## Mutation 12 — 2026-05-24 — content-edit (design.md)

**Diff summary**: corrected the crash-window prose in §"Per-commit replay and lockstep advance" (paragraph at line 289) that mis-routed the 4.5→4.6 resume contract through "the no-drift normalization path advances every stamp to HEAD's SHA". The drift-check-side normalization (`workflow-drift-check.md`, Track 3 surface) and the migration-side resume (this section's per-commit loop with sub-step 4.5 lockstep advance, Track 4b surface) are distinct paths; a migration re-invocation does NOT enter no-drift normalization. Replacement prose names the migration-side resume contract directly: re-fold from uniform stamps at commit X yields `BASE_SHA = X` and range `X..HEAD` re-queues commits X+1..HEAD without re-applying X; if the crash hits mid-4.5 with non-uniform stamps, the fold over `{X-1, X}` yields the older common ancestor X-1, the range re-queues commit X, sub-step 4.4 re-applies its file-shape edits idempotently, and the user's pre-resume `git diff` catches non-idempotent residue. A closing sentence makes the cross-path boundary explicit: the no-drift normalization path fires only when the drift-check caller observes an empty `BASE_SHA..HEAD` range, which is a separate surface from the migration's per-commit loop.

**Mechanical checks** (target=design, scope=bounded on `Per-commit replay and lockstep advance`): PASS (0 blockers, 1 should-fix pre-existing and out-of-scope at design.md:257). The fragmented-header heuristic flags the §"Per-commit replay and lockstep advance" TL;DR at line 259 for 75% content-word overlap with the heading; the paragraph this mutation rewrites is at line 289 and shares no overlap with the heading. Verified pre-existing by re-running the script against `git stash`-saved HEAD before the mutation landed: identical finding shape. Carry as known debt; the next mutation touching the TL;DR is the natural place to sweep it.
**Cold-read** (scope: bounded): skipped per the Mutation 11 / Mutation 7 precedent for focused single-paragraph corrections with no narrative shift, compounded by the implementer-rulebook boundary against sub-agent spawns from implementer-level work. The change is a localized prose-routing fix that leaves every adjacent paragraph, every cross-reference, and the section's TL;DR / Edge cases / References footer byte-for-byte unchanged.

**Findings**:
- should-fix (mechanical iter 1, pre-existing and out-of-scope): `dsc-ai-tell` fragmented-header heuristic at line 257 against the TL;DR at line 259 (75% content-word overlap, threshold 50%). Carry as known debt; this mutation does not touch the TL;DR paragraph.

**Iterations**: 1 of 3 (PASS — mechanical iter 1 PASS verdict with one pre-existing should-fix held; cold-read skipped per precedent and held as PASS-equivalent)

## Mutation 13 — 2026-05-25 — phase4-creation (design-final.md)

**Diff summary**: produced `docs/adr/inplace-worflow-migration/design-final.md` as the Phase 4 committed artifact. Single-file design (no `design-mechanics-final.md` companion; the original `design.md` was ~384 lines, well under the length trigger). Shape mirrors `design.md`: Overview (≤40 lines, BLUF lead naming the baseline being replaced + the enabling primitive + structure roadmap + adr.md pointer + shipped-as-planned note); Core Concepts (seven concept paragraphs — workflow-SHA stamp, stamp range, unstamped-artifact bootstrap, lockstep stamp advance, no-drift normalization, session-type parameter, staged workflow subtree); Workflow with three sub-sections (drift detection at session startup, migration replay loop, reflection at end of migration) each carrying TL;DR + Mermaid diagram + Edge cases / Gotchas + References footer; Stamp range computation (TL;DR + Phase 1 walk bash + Phase 2 caller-specific recovery + edge cases + References); Per-commit replay and lockstep advance (TL;DR + per-commit bash + final-batch bash + crash-window analysis + References); Reflection parameterization (TL;DR + minimal-edit list + migrate-workflow Step 6 markdown + References); Staging for workflow-modifying branches (TL;DR + staging-tree layout + implementer rules + promotion bash + drift-gate exclusion + edge cases + References). Phase C iteration refinements absorbed: merge-base failure recovery routing, both-arrays-empty migration halt, unrecognized-session-type halt clause, divergence sanity check using `merge-base..origin/develop` with `git fetch` prelude, empty-commit short-circuit on Phase 4 resume, additive-only promotion contract, forward-applicable carve-out for the current branch.

**Mechanical checks** (target=design, scope=whole-doc): iter 1 had 4 should-fix (em-dash density at line 33; fragmented-header overlap at lines 192/271/311); iter 2 had 2 should-fix (residual fragmented-header overlap at 192/311); iter 3 PASS (0 findings) after rewriting two TL;DR openers and replacing the "computation rule / range definition" enumeration with "fold algorithm / upper-and-lower bounds" phrasing.

**Cold-read** (scope: whole-doc, plus Human-reader additions and phase4-creation-specific checks): iter 1 PASS with 3 should-fix nits at the artifact boundary (no in-document pointer to the `adr.md` D-records / Invariants catalogue; the term "drift gate" used in Overview before its first formal section; no plan-deviation surfacing sentence in the Overview) and 2 suggestions (sibling-sub-section sameness in §Workflow, hyphenated-compound density in two Core Concepts paragraphs). Iter 2 applied all three should-fix items in one pass; suggestions held by precedent (no retry per the mutation discipline).

**Findings**:
- should-fix (mechanical iter 1, resolved iter 2): em-dash density at line 33 (the Staged workflow subtree paragraph carried two em dashes forming an unbalanced cadence). Rewrote the offset clause to inline prose.
- should-fix (mechanical iter 1, resolved iter 3): fragmented-header overlap at lines 192/271/311 — TL;DR openers shared ≥50% content words with their section headings. Rewrote the three TL;DR opening sentences; replaced the trailing "computation rule / range definition" enumeration in §Stamp range computation with "fold algorithm / upper-and-lower bounds" so the line contained no heading content-word.
- should-fix (cold-read iter 1, resolved iter 2): no `adr.md` pointer in the Overview. Appended "The full Decisions (D-records) and Invariants catalogue lives in the companion `adr.md`; this document cites them by short label in each References footer." to the structural roadmap.
- should-fix (cold-read iter 1, resolved iter 2): "drift gate" used in Overview before formal introduction. Added a parenthetical at first use pointing at §"Workflow → Drift detection at session startup".
- should-fix (cold-read iter 1, resolved iter 2): no plan-deviation surfacing sentence. Added a new short Overview paragraph naming the two structural adjustments that landed during execution (drift-gate caller-symmetric sweep, staging-architecture follow-on enhancements deferred to YouTrack) and stating that all Decision Records carry through to `adr.md` under their original numbering.
- suggestion (cold-read iter 1, not retried): three Workflow sub-sections share an identical internal shape (TL;DR + diagram + Edge cases + References). Same-shape sibling consolidation would erase the per-flow diagrams, so the trigger is partially exempted by content. Held.
- suggestion (cold-read iter 1, not retried): hyphenated-compound density in Core Concepts paragraphs at lines 23 / 25 (4–5 distinct hyphenated pairs each). Domain-load-bearing terms resist dehyphenation. Held.

**Iterations**: 2 of 3 (PASS — mechanical iter 3 PASS verdict after iter 1+2 fixes; cold-read iter 2 PASS verdict with three should-fix items resolved and two suggestions held)
