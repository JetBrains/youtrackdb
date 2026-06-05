<!-- workflow-sha: 786f441e224ba6c8c4240dde5d9368866fb9b405 -->
# Track 3: File-footprint scope indicators

## Purpose / Big Picture
After this track, the plan-checklist scope indicator reports a planned file footprint (`~N files covering X, Y, Z`) instead of a step count, and structural and consistency review's sizing check keys off that footprint instead of a pre-decomposition step count.

<!-- Reserved for Move 2 — ADDED/MODIFIED/REMOVED triad. Empty until Move 2 lands. -->

Rewrite the scope indicator from `~N steps` to `~N files` (D8). A step count pre-judges Phase A decomposition; a planned file footprint is knowable at plan time and reads in the same file unit as the per-step sizing rules — a per-track soft heuristic, not the per-step `~12` split cap (A3). Edits the convention spec (`conventions.md` §Scope indicators / §1.2), the writers (`create-plan/SKILL.md`, `planning.md`), the checkers (`prompts/structural-review.md`, `prompts/consistency-review.md`, the three Phase A review-prompt glossaries, and the `track-code-review.md:1070` straggler), and the renderer (`plan-slim-rendering.md`). Three files first listed as targets are verify-only — they carry no format literal (`implementation-review.md`, `inline-replanning.md`, `review-workflow-consistency.md`; DL3).

## Base commit
dc558590300ee0bbdb199fe40763a4d363a86037

## Progress
- [x] Review + decomposition
- [x] Step implementation
- [ ] Track-level code review
- [ ] Track completion

- [x] 2026-06-04T17:27Z [ctx=safe] Review + decomposition complete
- [x] 2026-06-04T19:20Z [ctx=safe] Step 1 complete (commit 25a74f4394172e5484f9652fd50b73f1bdbfdc21)
- [x] 2026-06-04T19:27Z [ctx=safe] Step 2 complete (commit a97bea18b5696a454f9ca2b121a1b03f76755beb)
- [x] 2026-06-04T20:21Z [ctx=info] Step 3 complete (commit 6cb1c00ec7b3192e8fe74c6242412b485a0c9e7c)
- [x] 2026-06-04T20:24Z [ctx=info] Step 4 complete (commit 2591bcbf2fc72c1a4f9a21083aacc20d147f33b3)
- [x] 2026-06-04T20:31Z [ctx=info] Step 5 complete (commit 7ab849e8f299cda7746d7c2aef07a138383a0d6e)
- [x] 2026-06-05T03:39Z [ctx=info] Track-level code review iteration 1 complete (1/3 iterations) — fix commit 637eb406c6805580b398707e07aefea2d1048699

## Surprises & Discoveries
<!-- Continuous-log. Promoted by the orchestrator from per-step "What was
discovered" when the finding affects future steps or other tracks. Empty
at Phase 1. -->
- 2026-06-04T20:20Z Step 3 surfaced that the per-step `~12`/`~5` thresholds leaked into the track-footprint checks (`conventions.md` §Scope indicators purpose #1, `structural-review.md:124`) and that the unenumerated `structural-review.md:166` TRACK SIZING check still keyed off a step count D8 makes unreadable. Resolved by DL6: a distinct track-level `~20-25`-file ceiling, applied across all three checks in Step 5. See Decision Log DL6 and Episodes §Step 3.

## Decision Log
<!-- Continuous-log. Execution-time decisions: inline-replan choices,
scope-downs, dependency reveals, gate-override reasons. -->

<!-- Reserved for Move 1 — per-track inlined Decision Records. -->

**Phase A decomposition decisions (2026-06-04):**

- **DL1 — The sizing-check rekey is plan-file-only by removing the current cross-file read, not by preserving a status quo (T1/R2/A2 reconciled to D8).** All three reviews found that the structural-review sizing check is cross-file today: its `*(cross-file: … Compare both halves.)*` annotation at `prompts/structural-review.md:127`–`:134` reads the track file for the "described" half. The track file mis-framed the rekey as "structural review keeps reading scope indicators plan-file-only (no track-file read introduced)." D8 (design.md:192) decides the rekeyed check stays plan-file-only by comparing the footprint count and the coverage-list cardinality — both on the `**Scope:**` line — against the `~12`/`~5` norm, which means the rekey **removes** the existing track-file read. Step 3 rewrites the annotation, not just the `~2 steps` example. R2/A2's alternative — keep the check cross-file by comparing `~N files` against the §Interfaces in-scope file list — is **declined**: it contradicts the immutable D8 plan-file-only decision. The Plan of Work and Invariants wording was corrected to match D8; the resulting check is a coarser plausibility signal than the old one, a trade D8 accepts.
- **DL2 — Scope expanded to the `track-code-review.md:1070` straggler (R1/A1).** Risk and adversarial review found a scope-indicator writer outside the planned target set: `track-code-review.md:1070` ("Update the scope indicator if the addition meaningfully changes the **expected step count**") in the inline-replan Categorize path. It is Track-2-owned (was listed out-of-scope). After D8 the indicator carries `~N files`, so the instruction goes stale, and `review-workflow-consistency.md:86` will not catch it — that check keys on the term "Scope indicator" (unchanged by D8), not on the `steps`/`files` unit. Step 5 re-keys this one line on the already-staged `track-code-review.md` copy (§1.7(d) accumulation; disjoint from Track 2's completed sections). The §Interfaces lists were amended to move the `:1070` line in-scope. This mirrors Track 2's DL1 sweep precedent: a unit change in a shared spec leaves stale step-count copies elsewhere.
- **DL3 — Three enumerated targets re-scoped to verify-only (T2; R1/A4 concur).** `implementation-review.md`, `inline-replanning.md`, and `.claude/agents/review-workflow-consistency.md` carry no `~N steps`/`steps covering` format literal (grep returned zero). Their scope-indicator mentions are format-agnostic: the "scope indicators changed substantially" re-run triggers, the abstract `**Scope:**` marker, and the closed glossary term "Scope indicator" whose name D8 does not change. Step 5 verifies the mentions and the term survive; it stages and edits these files only if a literal is found on first touch, otherwise leaves them byte-unchanged and unstaged. The §Interfaces and Validation lines were amended to verify-only.
- **DL4 — All five steps tagged `risk: low` under the live taxonomy (§Self-application limit).** As with Track 1 and Track 2 (DL3), the I6 invariant keeps the live `risk-tagging.md` at develop's state, which has no workflow-machinery category, so workflow-prose edits fall to the LOW default. The staged `### Workflow machinery` taxonomy (Track 1) is the deliverable, not this branch's operative rulebook. No step reaches step-level dimensional review; Phase C reviews the cumulative staged-vs-live delta.
- **DL5 — Accepted prose suggestions baked into step bodies, no Decision Record change.** A3: the §Scope indicators rewrite (Step 1) leads with "the footprint is a per-track soft heuristic, not the per-step `~12` split cap" — the per-track-vs-per-step distinction surfaces where a planner reads the spec cold, rather than trailing as a gotcha. A4: step bodies use the full path `prompts/structural-review.md`; the unit-agnostic re-run triggers in the `.claude/workflow/structural-review.md` wrapper, `implementation-review.md`, and `review-iteration.md` ("scope indicators changed substantially") are deliberate non-targets. A5/T1: Step 3 describes the rekeyed check accurately as a coarser plan-file-only signal. T3: the `conventions.md §1.1` "Scope indicator" row is format-neutral; Step 1 touches it only to add a format pointer if harmonizing. R3: the already-staged `conventions.md` (Track 1) and `track-code-review.md` (Track 2) are edited in place, never re-copied from live, or the prior tracks' staged edits are lost (§1.7(d)).

**Phase B execution decisions:**

- **DL6 — Track-footprint checks compare against a track-level `~20-25`-file ceiling, not the per-step `~12`/`~5` (user-approved mid-execution; amends D8's secondary "same axis" detail).** Step 3's orchestrator review found that the per-step thresholds had leaked into the track-footprint checks: Step 1's `conventions.md` §Scope indicators purpose #1 and Step 3's `structural-review.md:124` keyed footprint-vs-norm to `~12`/`~5`, and the unenumerated `structural-review.md:166` TRACK SIZING check still read "scope indicator suggest more than `~5-7` steps" — all inconsistent with D8 once the indicator reports files. `~12` is the per-step split cap and `~5` the per-step MEDIUM trigger; a 5-7-step track aggregates many steps and routinely exceeds 12 files, so the per-step numbers mis-flag normal-sized tracks. The user approved a distinct track-level ceiling of `~20-25` in-scope files for all three track-footprint checks. D8's core decision (footprint, not steps) stands; the per-step `~12`/`~5` (Track 1's domain in `risk-tagging.md`/`track-review.md`) stay untouched. `design.md` §"Scope indicators measure file footprint, not steps" was amended via the edit-design mutation discipline (`design-mutations.md` Mutation 4); `implementation-plan.md` D8 carries the revision note. Step 5 applies `~20-25` in place to `conventions.md` purpose #1, `structural-review.md:124`, and the `:166` straggler. This mirrors DL2's straggler-sweep precedent — `:166` is the same class of unenumerated scope-indicator reference. See Episodes §Step 3.

## Outcomes & Retrospective
<!-- Continuous-log. Review iteration outcomes and the track-completion
summary at Phase C. -->
- [x] Technical: PASS at iteration 1 (3 findings, all accepted: T1 should-fix, T2 should-fix, T3 suggestion). No `skip`; D8 survives. T1 corrected the plan-file-only framing (DL1); T2 re-scoped three targets to verify-only (DL3).
- [x] Risk: PASS at iteration 1 (3 findings, all accepted: R1 should-fix, R2 should-fix, R3 suggestion). R1 expanded scope to the `track-code-review.md:1070` straggler (DL2). R2's "keep the check cross-file" recommendation was declined as contradicting D8, but its observation folded into the DL1 wording correction. R3 hardened the already-staged-copy edit-in-place rule (R3 → §Context, §Interfaces).
- [x] Adversarial: PASS at iteration 1 (5 findings, all accepted: A1/A2/A3 should-fix, A4/A5 suggestions). No `skip`; the core premise (a file footprint is more knowable at plan time than a step count) survived the challenge. A1 is the same straggler R1 found (DL2). A2 mirrors R2 (declined cross-file alternative, observation absorbed into DL1). A3 drove the per-track-soft-heuristic lead in Step 1 (DL5). A4/A5 baked into step-body wording (DL5).

## Context and Orientation

All edit targets are workflow `.md` files under `.claude/workflow/**`, `.claude/skills/**`, and `.claude/agents/**`. This plan is workflow-modifying, so every edit routes to a staged copy under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...` per `conventions.md §1.7`; the live `.claude/**` tree stays at develop's state until the Phase 4 promotion. On first touch of a **not-yet-staged** file, copy the live version into the staged path verbatim, then edit the staged copy (§1.7(e)). **Two targets are ALREADY staged by earlier tracks — edit those in place, never re-copy from live (§1.7(d)), or the earlier track's edit is silently reverted:** `conventions.md` (Track 1 staged it for the §1.1 "Step" row) and `track-code-review.md` (Track 2 staged it; Track 3 corrects only the `:1070` straggler line — see the checker note below).

The scope indicator is defined once and consumed in many places. Line numbers are against `cb5eec65`/develop's live tree; verify on first touch of each staged copy.

- **Spec** — `conventions.md` §Scope indicators (required) (`:276`; format `~N steps covering X, Y, Z` at `:285`; the three purposes at `:287`, where purpose #1 is structural review's sizing check), the §1.1 Glossary "Scope indicator" row (`:72`), and the §1.2 Checklist examples (`:203`, `:206`).
- **Writers** — `create-plan/SKILL.md` (`:210`, `:329`, `:333`) and `planning.md` (§Scope indicators `:455`, the `**Scope:**` caller-tree estimate-refinement note `:554`).
- **Checkers** — `prompts/structural-review.md` (glossary def `:65`; sizing check `:124`/`:128` "describing 8 distinct changes but claiming ~2 steps is suspect"). The sizing check is **currently cross-file** — its `*(cross-file: … Compare both halves.)*` annotation at `:127`–`:134` reads the track file for the "described" half. D8's rekey makes it plan-file-only by **removing** that track-file read (the footprint count and the coverage-list cardinality both live on the plan-checklist `**Scope:**` line), so the annotation itself must be rewritten, not just the `~2 steps` example. `prompts/consistency-review.md` (glossary def `:81`; sizing check `:239`, which stays a plan↔design comparison by its nature). The Phase A review-prompt glossaries `prompts/technical-review.md` / `prompts/risk-review.md` / `prompts/adversarial-review.md` (each defines "Scope indicator" with the `~N steps` format). **`track-code-review.md:1070`** — the `:1070` "expected step count" scope-indicator writer in the inline-replan Categorize path; a Phase A review straggler (DL2), corrected on the already-staged copy. **Verify-only (no format literal — grep-confirmed):** `implementation-review.md` carries only format-agnostic "scope indicators changed substantially" re-run triggers (`:205`/`:255`/`:356`), no `~N steps` text; edit only if a literal turns up on inspection (DL3).
- **Renderers / other readers** — `plan-slim-rendering.md` (`:166`, example `~6 steps`). **Verify-only (no format literal — grep-confirmed):** `inline-replanning.md` (`:235`/`:271` name the `**Scope:**` marker abstractly, no `~N steps` text) and `.claude/agents/review-workflow-consistency.md` (`:86` lists "Scope indicator" as a closed glossary *term*; D8 changes the format/unit, not the term name, so the term-consistency check needs no edit). Confirm the term and the mentions survive; stage and edit only if a literal is found (DL3).

Non-obvious terminology (full gloss in design.md §"Scope indicators measure file footprint, not steps"): **file-footprint scope indicator** (the `~N files covering X, Y, Z` form), **size-versus-norm check** (the rekeyed structural/consistency sizing check), **plan-file-only** (the check reads only the plan-checklist `**Scope:**` line, no track-file read).

Concrete deliverables: a rewritten §Scope indicators spec stating the `~N files` format with the rekeyed purpose #1; updated §1.2 examples (the §1.1 glossary row is format-neutral, T3); writer updates in `create-plan/SKILL.md` and `planning.md`; checker updates in `prompts/structural-review.md` (sizing-check rekey to plan-file-only + glossary def), `prompts/consistency-review.md` (sizing check + glossary def), the three Phase A review-prompt glossaries, and the `track-code-review.md:1070` straggler; and the renderer update in `plan-slim-rendering.md`. The three verify-only files (`implementation-review.md`, `inline-replanning.md`, `review-workflow-consistency.md`) get no edit unless a `~N steps` literal turns up on first touch.

## Plan of Work

The work is four coherent edits along a spec → writers → checkers → renderers axis. The ordering below is a sensible default; the spec edit should land first so the writer/checker/renderer edits can cite the new format.

1. **Rewrite the convention spec** (D8) in `conventions.md`: change §Scope indicators (required) to the `~N files covering X, Y, Z` format, lead with the per-track-soft-heuristic framing (A3), rekey purpose #1 (structural sizing check) to footprint-vs-track-size, keep the "estimates, not exact counts" rule; update the §1.2 Checklist examples. The §1.1 Glossary "Scope indicator" row is format-neutral — touch only to add a format pointer if harmonizing (T3).
2. **Update the writers** (D8): `create-plan/SKILL.md` and `planning.md` — the scope-indicator format the planner emits and the caller-tree estimate-refinement note.
3. **Update the checkers** (D8): rekey the `prompts/structural-review.md` and `prompts/consistency-review.md` sizing checks from claimed-vs-described to size-vs-norm. For structural review this means **removing** its current cross-file track-file read: the `*(cross-file: … Compare both halves.)*` annotation at `:127`–`:134` is rewritten to a plan-file-only check that compares the footprint count and the coverage-list cardinality (both on the `**Scope:**` line) against the `~12`/`~5` track-size norm. Consistency review's check stays a plan↔design comparison. Plus the Phase A review-prompt glossaries (`prompts/technical-review.md`, `prompts/risk-review.md`, `prompts/adversarial-review.md`) and the `track-code-review.md:1070` straggler. (`implementation-review.md` is verify-only — no format literal.)
4. **Update the renderers** (D8): the `plan-slim-rendering.md` example. (`inline-replanning.md` and `review-workflow-consistency.md` are verify-only — no format literal; the "Scope indicator" glossary term is unchanged.)

Invariants to preserve: the scope indicator stays a required signal; the "estimates, not exact counts" rule stays; the `~12`/`~5` thresholds are unchanged (Track 1 owns those values; this track only points the check at them); this branch's own `implementation-plan.md` scope lines stay `~N steps` under the live convention. One invariant was corrected during Phase A review (DL1): structural review's sizing check is **currently cross-file**, so the rekey **establishes** a plan-file-only check by removing the existing track-file read — it does not preserve a plan-file-only status quo that never existed. The resulting check is a coarser plausibility signal than the old cross-file claim-vs-described comparison; D8 (design.md:192) accepts that trade.

<!-- Phase A appends a per-step sequencing summary referencing the Concrete Steps roster. -->

## Concrete Steps
<!-- Phase A placeholder — decomposition writes a thin numbered
roster here: one entry per step with description, `risk:` tag, and a
`[ ]` status checkbox. Per-step episodes do NOT live here; they live
in `## Episodes` below. The roster is immutable after Phase A except
for the status checkbox flip and the optional `commit:` annotation
Phase B appends. -->

Step 1 writes the spec (the source of truth for the `~N files` format). Steps 2-5 each cite that format but touch disjoint file sets, so they may run in any order after Step 1.

1. Rewrite the convention spec in the already-staged `conventions.md`: §Scope indicators (required) format `~N steps`→`~N files covering X, Y, Z`, rekey purpose #1 to the footprint size-vs-norm check, keep the "estimates, not exact counts" rule (the `~3-5` example becomes files), and update the §1.2 Checklist examples. Lead with "the footprint is a per-track soft heuristic, not the per-step `~12` split cap" (A3). The §1.1 "Scope indicator" row is format-neutral — touch only to add a format pointer if harmonizing (T3). Edit the staged copy in place; never re-copy from live (R3, §1.7(d)) — risk: low (default; live taxonomy, §Self-application limit)  [x] commit: 25a74f4394172e5484f9652fd50b73f1bdbfdc21
2. Update the writers (`create-plan/SKILL.md`, `planning.md`): the scope-indicator format the planner emits and the `**Scope:**` caller-tree estimate-refinement note, swapping `~N steps`→`~N files`. Leave the unrelated `~5-7 steps` track-sizing rule untouched — risk: low (default)  [x] commit: a97bea18b5696a454f9ca2b121a1b03f76755beb  *(parallel with Step 3, Step 4, Step 5)*
3. Rekey the checker sizing checks (`prompts/structural-review.md`, `prompts/consistency-review.md`): swap each file's "Scope indicator" glossary def to `~N files`; rekey the sizing check from claimed-vs-described to footprint size-vs-norm. For structural review, rewrite the `*(cross-file: … Compare both halves.)*` annotation (`:127`–`:134`) to a plan-file-only check comparing footprint count + coverage-list cardinality against the `~12`/`~5` norm — not just the `~2 steps` example (T1, DL1). Consistency review's check stays plan↔design — risk: low (default)  [x] commit: 6cb1c00ec7b3192e8fe74c6242412b485a0c9e7c  *(parallel with Step 2, Step 4, Step 5)*
4. Swap the Phase A review-prompt glossary definitions (`prompts/technical-review.md`, `prompts/risk-review.md`, `prompts/adversarial-review.md`): each "Scope indicator" def from the `~N steps` to the `~N files` format — risk: low (default)  [x] commit: 2591bcbf2fc72c1a4f9a21083aacc20d147f33b3  *(parallel with Step 2, Step 3, Step 5)*
5. Renderer + straggler + verify-only sweep: rewrite the `plan-slim-rendering.md` `~6 steps` example to files; correct the `track-code-review.md:1070` "expected step count" straggler to file-footprint phrasing on the already-staged copy, in place (DL2, R3); and verify the three format-agnostic files (`implementation-review.md`, `inline-replanning.md`, `review-workflow-consistency.md`) carry no `~N steps` literal — leave them byte-unchanged and unstaged unless one turns up (DL3) — risk: low (default)  [x] commit: 7ab849e8f299cda7746d7c2aef07a138383a0d6e  *(parallel with Step 2, Step 3, Step 4; scope expanded by DL6 — see episode)*

## Episodes
<!-- Continuous-log. Phase B sub-step 7 appends one block per
completed step, identified by step number + commit SHA. Empty at
Phase 1; Phase A does not populate. -->

### Step 1 — commit 25a74f4394172e5484f9652fd50b73f1bdbfdc21, 2026-06-04T19:20Z [ctx=safe]
**What was done:** Rewrote §Scope indicators (required) in the staged `conventions.md` from `~N steps covering X, Y, Z` to `~N files covering X, Y, Z`. The intro leads with the per-track-soft-heuristic framing — the footprint estimates whole-track file count, not the per-step `~12` split cap — and notes that a file count is plan-time-knowable (the in-scope set lives in each track's §Interfaces) where a step count pre-judges Phase A decomposition. Purpose #1, structural review's sizing check, is rekeyed to a plan-file-only footprint-vs-norm comparison against the `~12`/`~5` thresholds. The "estimates, not exact counts" rule survives with its example moved to `~3-5 files`; the two §1.2 Checklist examples now read `~N files`; the §1.1 "Scope indicator" glossary row gained a format pointer (T3). Edited the already-staged copy in place per §1.7(d).

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/conventions.md` (modified — edited in place; already staged by Track 1)

**Critical context:** This commit is the source-of-truth `~N files covering X, Y, Z` wording Steps 2-5 cite. The `~5-7 steps` track-sizing rule (glossary "Track" row) is byte-unchanged, a distinct concept D8 does not touch, and Track 1's §1.1 "Step" edit is intact.

### Step 2 — commit a97bea18b5696a454f9ca2b121a1b03f76755beb, 2026-06-04T19:27Z [ctx=safe]
**What was done:** Swapped the planner-facing scope-indicator unit from steps to files in both writer files. In the staged `create-plan/SKILL.md`, the format directive became `~N files covering X, Y, Z`, the description now reads "approximate file footprint" with a per-track-soft-heuristic clause, and the two Checklist examples read `~N files`. In the staged `planning.md`, the §Scope indicators TOC row, the section HTML-comment summary, and the body sentence all read `~N files covering X, Y, Z`, and the call-hierarchy estimate-refinement note was reworded to enumerate the caller sites a signature change touches and refine the `**Scope:** ~N files` footprint estimate. Both files were first touched here: copied verbatim from live, then edited (§1.7(e)).

**What was discovered:** The caller-tree estimate-refinement note in `planning.md` carried no `steps`/`files` unit literal in the live file — it named only the abstract `**Scope:**` marker. The reword makes the file-footprint framing explicit (caller-tree breadth maps to files touched) without changing the recipe's meaning.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/skills/create-plan/SKILL.md` (new — staged copy of the live file with the writer-format edits)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/planning.md` (new — staged copy of the live file with the writer-format edits)

**Critical context:** The `~5-7 steps` track-sizing rule (`create-plan/SKILL.md:207`, `planning.md:424`) is byte-verified unchanged. Both files are now staged for the first time, so any later step touching them edits in place per §1.7(d).

### Step 3 — commit 6cb1c00ec7b3192e8fe74c6242412b485a0c9e7c, 2026-06-04T20:21Z [ctx=info]
**What was done:** Rekeyed the checker sizing checks in the staged `structural-review.md` and `consistency-review.md` (both first touched here: copied verbatim from live, then edited). Each "Scope indicator" glossary def now reads `~N files covering X, Y, Z`. Structural review's SCOPE INDICATORS plausibility check moved from the claimed-versus-described cross-file comparison to a plan-file-only footprint-vs-norm check — the `*(cross-file: … Compare both halves.)*` annotation and its track-file read are gone (DL1). Consistency review's check stays a plan↔design comparison; only its example moved to `~2 files`.

**What was discovered:** Two scope-indicator references the Phase A plan did not enumerate. (1) `structural-review.md:166`'s TRACK SIZING check still reads "Does any track's scope indicator suggest more than `~5-7` steps?" — D8 leaves the file-based indicator unable to report a step count. (2) The per-step `~12`/`~5` thresholds had leaked into the track-footprint plausibility check at this step's `:124` rewrite and at Step 1's `conventions.md` purpose #1; a 5-7-step track aggregates many steps and routinely exceeds 12 files, so those per-step numbers mis-flag normal tracks. Both resolved by a user-approved track-level `~20-25`-file ceiling. See Decision Log DL6 and Surprises.

**What changed from the plan:** Per DL6 the `:124` track-footprint threshold committed here (`~12`/`~5`) is superseded by the `~20-25` track-level ceiling; the reconciliation of `:124`, `conventions.md` purpose #1, and the `:166` straggler all land in Step 5. D8 was amended via the edit-design mutation discipline (`design-mutations.md` Mutation 4). See Decision Log DL6.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md` (new — staged copy with the sizing-check rekey + glossary def)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/prompts/consistency-review.md` (new — staged copy with the glossary def + example swap)

### Step 4 — commit 2591bcbf2fc72c1a4f9a21083aacc20d147f33b3, 2026-06-04T20:24Z [ctx=info]
**What was done:** Swapped the "Scope indicator" glossary definition in the three Phase A review-prompt files — `technical-review.md`, `risk-review.md`, `adversarial-review.md` — from `> **Scope:** ~N steps covering X, Y, Z` to `> **Scope:** ~N files covering X, Y, Z`, matching Step 1's source-of-truth spec. Each is a pure glossary def with no track-footprint threshold, so the edit is a one-line format swap per file. All three were first touched here: staged verbatim from live, then edited (§1.7(e)).

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/prompts/technical-review.md` (new — staged copy with the glossary-def swap)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/prompts/risk-review.md` (new — staged copy with the glossary-def swap)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/prompts/adversarial-review.md` (new — staged copy with the glossary-def swap)

**Critical context:** The `~5-7 steps` per-track sizing rule (the "Track" glossary line) is byte-unchanged in all three files. These glossaries carry no track-footprint threshold, so the DL6 `~20-25` calibration does not reach them.

### Step 5 — commit 7ab849e8f299cda7746d7c2aef07a138383a0d6e, 2026-06-04T20:31Z [ctx=info]
**What was done:** Four-part sweep closing out the track. (a) DL6 track-footprint calibration applied in place to the three checks: `conventions.md` §Scope indicators purpose #1, `structural-review.md`'s SCOPE INDICATORS plausibility check (`:124`), and its TRACK SIZING check (`:166`) now compare against a track-level `~20-25`-file ceiling, stated as distinct from the per-step `~12` split cap and `~5` MEDIUM trigger; the `:166` rewrite keeps the `~5-7` steps track-sizing rule and notes step count is not knowable from a file-footprint indicator at plan time. (b) `plan-slim-rendering.md` (first touch, staged from live) example moved from `~6 steps` to `~6 files`. (c) the `track-code-review.md` inline-replan Categorize-path straggler corrected from "expected step count" to "expected file footprint" on the Track-2-staged copy, in place. (d) the three format-agnostic files verified clean and left unstaged (DL3).

**What was discovered:** No `~12`/`~5`-as-track-footprint comparison or "scope indicator → steps" derivation beyond the three Part (a) spots. The other `~12`/`~5` occurrences in `structural-review.md` are unrelated (a `~12 classes` diagram count, a `~5 lines` invariant-length cap) and were left untouched. The `~5-7 steps` rule survives at all five sites (`conventions.md` Track-glossary row + Planning rule; `structural-review.md` intro, the kept TRACK SIZING reference, and the Track-sizing red-flag entry).

**What changed from the plan:** Step 5's roster scope (renderer + `:1070` straggler + verify sweep) was expanded mid-execution by DL6 to also apply the `~20-25` track-footprint calibration in place to `conventions.md` purpose #1 and `structural-review.md:124`/`:166`. No future steps affected — this is the track's final step. See Decision Log DL6.

**Key files:**
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/conventions.md` (modified in place — purpose #1 ~20-25 calibration)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/prompts/structural-review.md` (modified in place — :124 + :166 ~20-25 calibration)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/plan-slim-rendering.md` (new — staged copy with the `~6 files` example)
- `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/workflow/track-code-review.md` (modified in place — :1070 straggler)

**Critical context:** The staged scope-indicator surface is now uniformly file-footprint: zero `~N steps` scope-indicator literals remain anywhere in the staged tree. The `~5-7 steps` track-sizing rule and the per-step `~12`/`~5` thresholds (`risk-tagging.md`/`track-review.md`) are intact.

## Validation and Acceptance

Track-level behavioral acceptance (Phase A turns these into per-step EARS/Gherkin lines):

- `conventions.md` §Scope indicators (required) states the `~N files covering X, Y, Z` format and leads with the per-track-soft-heuristic framing; the §1.2 Checklist examples match; the "estimates, not exact counts" rule survives.
- Structural review's sizing check reads in file-footprint terms (footprint count + coverage-list cardinality vs the `~12`/`~5` norm) and is plan-file-only — the `*(cross-file: … Compare both halves.)*` annotation is gone. Consistency review's check stays plan↔design. No `~N steps`/step-count scope-indicator phrasing remains in either checker.
- The Phase A review-prompt glossaries (`technical-review.md`, `risk-review.md`, `adversarial-review.md`) define the scope indicator in the `~N files` form.
- `create-plan/SKILL.md` and `planning.md` instruct the planner to emit `~N files`; the unrelated `~5-7 steps` track-sizing rule is untouched.
- `plan-slim-rendering.md` carries the `~N files` example; `track-code-review.md:1070` reads "expected file footprint", not "expected step count"; `implementation-review.md`, `inline-replanning.md`, and `review-workflow-consistency.md` carry no stale `~N steps` scope-indicator references and were not staged.
- Every edit lives under `docs/adr/step-size-recap/_workflow/staged-workflow/.claude/...`; the live `.claude/**` tree is byte-unchanged from develop; this branch's `implementation-plan.md` scope lines remain `~N steps`; Track 1's staged `conventions.md` §1.1 "Step" edit and Track 2's staged `track-code-review.md` sections are intact.

Per-step acceptance:

- **Step 1.** Given the already-staged `conventions.md`, then §Scope indicators (required) states the `~N files covering X, Y, Z` format, leads with "per-track soft heuristic, not the per-step `~12` split cap", rekeys purpose #1 to the footprint size-vs-norm check, and keeps the "estimates, not exact counts" rule (example now in files); the §1.2 Checklist examples read `~N files`; the §1.1 "Scope indicator" row is unchanged or carries only an added format pointer; Track 1's §1.1 "Step" edit is intact.
- **Step 2.** Given the staged `create-plan/SKILL.md` and `planning.md`, then every scope-indicator format string and the `**Scope:**` caller-tree estimate-refinement note read `~N files`; the `~5-7 steps` track-sizing rule at `create-plan/SKILL.md:207` and `planning.md:424` is byte-unchanged.
- **Step 3.** Given the staged `prompts/structural-review.md` and `prompts/consistency-review.md`, then each "Scope indicator" glossary def reads `~N files`; structural review's sizing check compares footprint count + coverage-list cardinality against the `~12`/`~5` norm with no `*(cross-file … Compare both halves)*` annotation and no track-file read; consistency review's check stays a plan↔design comparison with the example in files.
- **Step 4.** Given the staged `prompts/technical-review.md`, `prompts/risk-review.md`, and `prompts/adversarial-review.md`, then each "Scope indicator" glossary def reads `~N files covering X, Y, Z`.
- **Step 5.** `plan-slim-rendering.md`'s example reads `~N files`; `track-code-review.md:1070` reads "expected file footprint" on the already-staged copy with Track 2's sections intact; `implementation-review.md`, `inline-replanning.md`, and `review-workflow-consistency.md` carry no `~N steps` literal and have no staged copy (unless a literal was found on first touch).

<!-- Phase A placeholder for per-step EARS/Gherkin lines. -->

<!-- Reserved for Move 3 — EARS or Gherkin acceptance lines used
verbatim as test method names. Empty until Move 3 lands. -->

## Idempotence and Recovery
<!-- Phase A placeholder — names per-step idempotence and recovery
paths once steps are decomposed. -->

All five steps are prose edits to staged workflow copies, idempotent under re-application: each edit either matches its target text or is already applied. First touch of a not-yet-staged file (`create-plan/SKILL.md`, `planning.md`, `prompts/structural-review.md`, `prompts/consistency-review.md`, `prompts/technical-review.md`, `prompts/risk-review.md`, `prompts/adversarial-review.md`, `plan-slim-rendering.md`) copies the live file verbatim into the staged subtree, then edits. `conventions.md` (Track 1) and `track-code-review.md` (Track 2) are already staged: edit in place, never re-copy. The three verify-only files are read, not edited, so they are idempotent by construction — they get a staged copy only if a `~N steps` literal is found. Recovery from a failed step is `git reset --hard HEAD` on the uncommitted staged file, then re-run. No data migration, no runtime state, no test fixtures.

## Artifacts and Notes
<!-- Continuous-log (rare). Cross-step artifact references that don't
belong to one specific step. Per-step episode content lives in
`## Episodes` above. Often empty. -->

## Interfaces and Dependencies

**In-scope files (staged copies under `_workflow/staged-workflow/.claude/...`):**
- `.claude/workflow/conventions.md` — §Scope indicators (required), the §1.2 Checklist examples; the §1.1 Glossary "Scope indicator" row is format-neutral (DL5/T3 — edit only to add a format pointer if harmonizing). **Already staged by Track 1: edit in place.**
- `.claude/skills/create-plan/SKILL.md` — the scope-indicator writer instruction and examples (leave the unrelated `~5-7 steps` track-sizing rule at `:207` untouched).
- `.claude/workflow/planning.md` — §Scope indicators and the `**Scope:**` estimate-refinement note (leave the `~5-7 steps` track-sizing rule at `:424` untouched).
- `.claude/workflow/prompts/structural-review.md`, `.claude/workflow/prompts/consistency-review.md` — the sizing-check rekey plus their "Scope indicator" glossary defs.
- `.claude/workflow/prompts/technical-review.md`, `.claude/workflow/prompts/risk-review.md`, `.claude/workflow/prompts/adversarial-review.md` — the Phase A "Scope indicator" glossary definitions.
- `.claude/workflow/plan-slim-rendering.md` — the `~N steps` rendering example.
- `.claude/workflow/track-code-review.md` — **only** the `:1070` "expected step count" scope-indicator writer (DL2 straggler). **Already staged by Track 2: edit that one line in place, disjoint from Track 2's completed sections.**

**Verify-only (no `~N steps` format literal — grep-confirmed; stage and edit only if a literal turns up on first touch, DL3):**
- `.claude/workflow/implementation-review.md` — format-agnostic "scope indicators changed substantially" re-run triggers.
- `.claude/workflow/inline-replanning.md` — abstract `**Scope:**` marker mentions.
- `.claude/agents/review-workflow-consistency.md` — "Scope indicator" as a closed glossary *term* (name unchanged by D8).

**Out-of-scope (owned by other tracks or deliberately not edited):**
- `conventions.md §1.1 "Step" row`, `track-review.md`, `risk-tagging.md` — Track 1; `review-agent-selection.md`, `step-implementation.md`, `code-review/SKILL.md` — Track 2. `track-code-review.md` is Track-2-owned **except** the `:1070` scope-indicator-writer line corrected here (DL2).
- The unrelated `~5-7 steps` **track-sizing rule** (how many steps a track may hold) wherever it appears — a distinct concept D8 does not touch.
- The `~12`/`~5` threshold values themselves — Track 1 owns the sizing thresholds; this track only points the scope-indicator check at them.
- This branch's own `implementation-plan.md` scope lines — they stay `~N steps` (live convention) until the Phase 4 promotion, if migrated at all.

**Dependencies:**
- **Independent track** — no dependency on Track 1 or Track 2, and neither depends on it.
- **Cross-track file:** `conventions.md` is also touched by Track 1 (the §1.1 "Step" row), disjoint from this track's §1.2 / §Scope indicators / §1.1 "Scope indicator" row. Under §1.7 staging the staged copy accumulates both tracks' edits; each track's Phase C review delta-scopes to its own sections.

**Staging contract:** workflow-modifying marker present in `implementation-plan.md` §Constraints; writes route to the staged subtree; the staged-vs-live delta gets the Phase C `§1.7(h)` review, delta-scoped to the live-vs-staged diff (D5 convention), not the whole-file staged copy.
