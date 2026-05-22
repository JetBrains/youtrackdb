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
