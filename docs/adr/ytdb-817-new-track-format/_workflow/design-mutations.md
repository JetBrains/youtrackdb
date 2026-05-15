# Design mutation log

## Mutation 1 — 2026-05-15 — structural-rewrite (design.md)

**Diff summary**: Apply Option 1 from the conversation — split the "Plan-at-start section" concept into two tiers: Phase 1 track-level sections (Purpose, Context, Plan of Work approach, Validation behavioral criteria, Interfaces) and Phase A step-aware sections (Concrete Steps, Idempotence and Recovery, step-referencing parts of Plan of Work, per-step EARS/Gherkin in Validation and Acceptance). Edits in 4 sections: `## Core Concepts` (replaced one bullet with two), `## Workflow` (sequenceDiagram updated and one prose paragraph added below it), `## New per-track file shape` (template body of `## Plan of Work` and `## Idempotence and Recovery` updated to reflect the tier split), `## Lifecycle table` (4 rows updated: Plan of Work, Concrete Steps was already correct, Validation and Acceptance, Idempotence and Recovery). Rationale: `## Idempotence and Recovery` is defined as naming specific steps and per-step recovery paths — content that cannot exist before Phase A decomposes the track into a step roster. Extending the existing "details at latest possible point" principle to step-aware sections keeps the workflow internally consistent. Discarded alternatives: Option 2 (move Idempotence to per-step roster annotation, fights OpenAI's template); Option 3 (drop Idempotence and Recovery entirely, loses a useful Phase A forcing function).

**Mechanical checks** (target=design): 30 blockers + 2 should-fix — **all pre-existing**, not introduced by this mutation. Per-section TL;DR + References-footer absent across 14 sections (the doc was created before the per-section-shape discipline was tightened). 4 `full-design-link-resolution` mismatches on `"Slot reservation for Moves 1–3"` (actual heading uses commas: `"Slot reservation for Moves 1, 2, 3"`) and `"Step episode storage"` (actual heading is longer: `"Step episode storage — ## Artifacts and Notes is the home"`) — referenced from `implementation-plan.md:129`, `tracks/track-2.md:10,12`, `tracks/track-3.md:17`. Should-fix: `overview-body` (3 lines, needs ≥5); `top-level-cap` (17 sections, cap ~15).

**Cold-read** (scope: skipped): SKIPPED — mechanical has blockers; cold-read deferred per workflow rules.

**Findings**:
- blocker (×28): `per-section-shape:tldr` and `per-section-shape:references-footer` missing across 14 sections. Pre-existing structural debt.
- blocker (×4): `full-design-link-resolution` — naming mismatches between plan/track refs and design.md headings (en-dash vs comma; truncated section name). Pre-existing.
- should-fix (×1): `overview-body` — Overview has only 3 non-empty lines, needs ≥5 per design-document-rules.md § Overview. Pre-existing.
- should-fix (×1): `top-level-cap` — 17 `##` sections, cap ~15. Pre-existing.

**Iterations**: 0 of 3 (BLOCKER REMAINS — pre-existing structural debt out of scope of this mutation; user gate invoked before iteration to confirm scope expansion).

**Verification of mutation isolation**: None of the 6 edits in this mutation added, removed, renamed, or moved a section; no heading text changed. The new content is content-only inside existing sections. Re-running the script against the pre-mutation HEAD of design.md would produce the same 30 blockers + 2 should-fix items.

## Mutation 2 — 2026-05-15 — content-edit (design.md)

**Diff summary**: One-word fix in §"Slot reservation for Moves 1, 2, 3", final paragraph: changed `Track 1 adds this exemption to the structural review prompt` to `Track 4 adds this exemption to the structural review prompt`. Track 1 in the plan is scoped to `review-agent-selection.md` only (Phase B/C dimensional-review triage); it does not touch `structural-review.md`. Track 4 owns sub-agent prompt updates per its description, which is where structural-review.md lives. The edit aligns this stray Track-1 reference with D6 (`Implemented in: Track 2 + Track 3`), the new D10 (`Implemented in: Track 3 + Track 4`), and the plan's actual Track 4 scope.

**Mechanical checks** (target=design): 6 blockers + 2 should-fix — **all pre-existing**, identical to Mutation 1's debt list (the bounded-scope check narrows from 30 → 6 because only the changed section's per-section-shape rule runs, but the 4 cross-file-ref blockers and the 2 should-fix items are unchanged). The 2 new-looking blockers are `per-section-shape:tldr` and `per-section-shape:references-footer` on §"Slot reservation for Moves 1, 2, 3" itself (line 352) — the section was missing TL;DR + References footer before our one-word change and still is; the bounded scope is what brought them into view.

**Cold-read** (scope: skipped): SKIPPED — mechanical has blockers; cold-read deferred per workflow rules.

**Findings**:
- blocker (×2): `per-section-shape:tldr` and `per-section-shape:references-footer` on §"Slot reservation for Moves 1, 2, 3" (line 352). Pre-existing.
- blocker (×4): `full-design-link-resolution` — same naming mismatches as Mutation 1, no change.
- should-fix (×1): `overview-body` — pre-existing.
- should-fix (×1): `top-level-cap` — pre-existing.

**Iterations**: 0 of 3 (BLOCKER REMAINS — same pre-existing debt as Mutation 1; per-user disposition given at Mutation 1, scope of this mutation is the one-word fix only).

**Verification of mutation isolation**: One byte-level edit on one line, no heading text changed, no section added/removed/renamed/moved. The diff is `Track 1` → `Track 4` and nothing else.

## Mutation 3 — 2026-05-15 — structural-rewrite (design.md)

**Diff summary**: Dedicated debt-clearance mutation to bring `design.md` into per-section-shape conformance. Five changes: (1) expanded `## Overview` from 3 paragraphs to 5 covering the required elements — baseline / change / enabling primitives / restructured to fit / document-structure roadmap; (2) added a `**TL;DR.**` paragraph (≤5 lines, no D/S parenthetical asides) plus a `### References` footer (Decisions + Related sections) to every non-exempt `## ` section — New per-track file shape, Root index, Section mapping, Continuous-log discipline, Step episode storage, Slot reservation for Moves 1/2/3, Directory and terminology rename mechanics, Lifecycle table, Sub-agent prompt updates, Phase B/C dimensional review triage update, and References; (3) merged `## Root index versus per-track ExecPlan` into `## Root index — implementation-plan.md` as subsection `### Distinct from per-track ExecPlan`; (4) merged `## Section ordering rationale` into `## Continuous-log discipline` as subsection `### Why continuous-log sections come first` — both merges drop the section count from 17 to 15 to meet the top-level cap; (5) fixed 4 stale cross-file `**Full design**` refs (`implementation-plan.md:97`, `implementation-plan.md:105`, `implementation-plan.md:129`, `tracks/track-2.md:10`, `tracks/track-2.md:12`, `tracks/track-3.md:17`) plus 2 more that surfaced after the section merges, all pointing the refs at the post-merge headings.

**Mechanical checks** (target=design): PASS — 0 blockers, 0 should-fix. The 30 pre-existing blockers + 2 should-fix items from Mutation 1 and Mutation 2 are all cleared. Each non-exempt section now satisfies `per-section-shape:tldr` and `per-section-shape:references-footer`; section count is 15 (was 17); Overview body is 5 non-empty lines covering all required elements; every `**Full design**` ref in the plan and track files resolves to a real heading in `design.md`.

**Cold-read** (scope: whole-doc): PASS — verdict "YES, a cold reader can build a working mental model". No blocker or should-fix findings. Three cosmetic suggestions noted: (a) Class Design and Workflow sections carry no TL;DR — informational, they're rule-exempt; (b) the terminal `## References` section + nested `### References` heading is structurally odd (recursive name) but consistent with the per-section shape rule; (c) the Overview "Document structure" paragraph is dense and could be bullet-list-formatted. All three are cosmetic; none drive a follow-up mutation.

**Findings**: None.

**Iterations**: 0 of 3 (PASS on first try, no iteration needed).

**Verification of mutation isolation**: The mutation deliberately addresses pre-existing debt (per user disposition at Mutation 2 → "do option 1"). No content of the original 17 sections was lost — sections that merged retain their body prose under the new parent heading. The 4 stale cross-file refs are now correct; one Sequence-diagram side-effect (the two merges introduced new stale refs that the script caught immediately) was resolved in a second pass before the cold-read.

## Mutation 4 — 2026-05-15 — structural-rewrite (design.md)

**Diff summary**: Split per-step episodes out of `## Artifacts and Notes` into a new dedicated `## Episodes` section (D11, added to the plan). `## Artifacts and Notes` retains its OpenAI-template position but is now reserved for cross-step content only (focused transcripts, snippets, review-iteration logs that span multiple steps). Updates across the design: (1) `## Core Concepts` — added "Episodes section" concept bullet; updated "Continuous-log section" to list 5 sections incl. Episodes; updated "Housekeeping section" to disambiguate from Episodes; (2) `## Class Design` classDiagram — added `Episodes~continuous-log~` field on TrackFile between `ConcreteSteps` and `ValidationAcceptance`; reordered `ArtifactsNotes` to its new template position; the diagram narrative below reflects the new ordering; (3) `## Workflow` sequenceDiagram — `CP` now seeds empty Episodes; `PB` per-step write now "flip Concrete Steps checkbox + append Episodes block"; (4) `## New per-track file shape` template — added the full `## Episodes` section with per-step block template between `## Concrete Steps` and `## Validation and Acceptance`; updated `## Artifacts and Notes` body to describe cross-step content only; (5) `## Section mapping — old shape to new` — Steps-blockquote rows now map to `## Episodes ### Step N` (was Artifacts and Notes); (6) `## Continuous-log discipline` — TL;DR now lists five sections; body added Episodes bullet; "Why continuous-log sections come first" subsection now explains Episodes' adjacent-to-Concrete-Steps placement exception; (7) `## Step episode storage` (renamed from `Step episode storage — \`## Artifacts and Notes\` is the home`) — substantial body updates throughout: intro mentions Episodes as the home; "Why separate" lifecycle table adds a separate Episodes row and clarifies Artifacts and Notes as cross-step-only; "Episode-write at Phase B sub-step 7" checklist writes to Episodes (was Artifacts); back-references "See Episodes §Step N" (was Artifacts and Notes); "Failed steps" template references Episodes; "Section-join pattern" references Episodes; "Drift mitigation" authoritative-copies updated to canonicalize Episodes for per-step content and add a fourth rule for cross-step Artifacts and Notes; (8) `## Lifecycle table` — added a new Episodes row between Concrete Steps and Validation and Acceptance; updated the Artifacts and Notes row to reflect cross-step-only use; (9) `## Sub-agent prompt updates` — added the two workflow-specific sections to the structural-review.md section-order check; clarified that per-step episode content is read from `## Episodes`; create-final-design.md aggregation list now names Episodes.

Also updated in this mutation (cross-file refs and plan-file decision-record propagation):
- `implementation-plan.md` D4 — added a sentence at the end of Rationale referencing D11.
- `implementation-plan.md` D5 — Full design line updated to new "Step episode storage" heading (no `— \`## Artifacts and Notes\` is the home` suffix).
- `implementation-plan.md` D9 — title and body rewritten: "Per-step episode is one block in a dedicated section, not a blockquote inside the Concrete Steps item"; rationale now references D11 (the `## Episodes` section); Full design line updated.
- `implementation-plan.md` — added D11 between D10 and §Invariants: full DR with title, alternatives, rationale, risks, Implemented in (Track 2 + Track 3 + Track 4), Full design.
- `tracks/track-2.md` — line 10 step that references conventions-execution.md §2.1 rewrite now lists `## Episodes` and updates the lifecycle-table writer/reader bullet to "Concrete Steps vs Episodes vs Artifacts and Notes per design.md §"Step episode storage"".
- `tracks/track-3.md` — multiple updates: the §Description paragraph names D11; the create-plan SKILL.md template-rewrite step names Episodes and Artifacts and Notes (cross-step); the step-implementation.md sub-step 7 rewrite spec's `## Artifacts and Notes` references all become `## Episodes` (with a `(per D11 — was \`## Artifacts and Notes\` in the original design)` annotation on the first one for traceability); the four-section episode-write summary names Episodes and the section-join pattern names Concrete Steps roster ↔ Episodes block.

**Mechanical checks** (target=design): PASS — 0 blockers, 0 should-fix after one iteration. First pass had 4 `full-design-link-resolution` blockers because the renamed `## Step episode storage` section left 4 stale refs in `implementation-plan.md:121, 153` and `tracks/track-2.md:10`, `tracks/track-3.md:17` — all resolved by updating to the new heading.

**Cold-read** (scope: whole-doc): PASS — verdict "YES, a cold reader can build a working mental model". No blockers, no should-fix; one cosmetic `suggestion` flagged: Class Design tagged `ArtifactsNotes~continuous-log~` but Continuous-log discipline lists only five sections (excluding Artifacts and Notes). Resolved in-mutation by dropping the `~continuous-log~` tag from `ArtifactsNotes` in the class diagram (Artifacts and Notes is now framed as a cross-step section, not a peer of the five continuous-log sections; rare appends still happen but the section's primary character is cross-step storage).

**Findings**: None after iteration.

**Iterations**: 1 of 3 (PASS).

**Verification of mutation isolation**: The mutation deliberately introduces a structural change (new section) that propagates across 9 design.md sections + 3 plan-file Decision Records + 2 track files. Cross-file ref check verified all `**Full design**` refs in plan and track files resolve to real headings. The diff is large but coherent — every touchpoint references D11 as the rationale anchor.

## Mutation 5 — 2026-05-15 — content-edit (design.md)

**Diff summary**: Add D12 ("Mandatory `[ctx=<level>]` field on every Progress entry and Episodes block") into the design. Two related sections changed:

1. `## Continuous-log discipline`: appended a new subsection "### Mandatory `[ctx=<level>]` field" between "Why continuous-log sections come first" and References, explaining where the field reflects from (orchestrator's window, not implementer's), why mandatory (forcing function for the existing inline context-window gates), what `ctx=warning` / `ctx=critical` trigger (existing mid-phase-handoff protocol, not a passive audit), and the write-time-only enforcement rationale (post-factum audit considered and rejected: backfilling is fiction, the gate-skip cost has already paid). Worked examples for Progress entries and Episodes block headers included. Extended TL;DR with a one-line clause naming the new field. Updated References footer to list D12.

2. `## Step episode storage`: rewrote the Episode-write at Phase B sub-step 7 checklist to insert a sub-step 0 (statusline read, fallback to `unknown`) and inline `[ctx=<level>]` into the Episodes block header (sub-step 1) and the Progress entry (sub-step 2). Updated the Failed steps example block and the Progress logging line to carry `[ctx=<level>]`. Reworded the intro line from "four-section checklist" to "one statusline read followed by up to four section writes (two always-run, two conditional)" to clarify the count and conditionality. Extended TL;DR with a one-line clause naming sub-step 0. Updated References footer to list D12.

Rationale: a periodic forcing function for context-window monitoring is more reliable than gate-at-phase-boundary alone — making the statusline read load-bearing on every Progress / Episodes write observes `safe → warning` transitions at the next continuous-log write rather than at the next explicit gate. Conditional sections (Surprises, Decision Log) and post-factum audits add no periodicity benefit; the write-time canonical order in `step-implementation.md` sub-step 7 (and the same order applied to Phase A / Phase C Progress writers) makes the field present by construction.

**Mechanical checks** (target=design, scope=whole-doc): PASS — 0 blockers, 0 should-fix, 0 suggestions on first pass. Re-run after applying the three cold-read suggestions also PASS (0/0/0).

**Cold-read** (scope: whole-doc — Mutation 5 triggers the N=5 periodic counter): PASS — verdict "YES, a cold reader can build a working mental model". Zero blockers / should-fix; three `suggestion`-severity TL;DR-polish findings, all addressed in-mutation: (1) TL;DR of Continuous-log discipline extended to name the new mandatory field, (2) TL;DR of Step episode storage extended to name the sub-step 0 statusline read, (3) intro line of the Episode-write checklist reworded for count + conditionality clarity.

**Findings**: None after polish pass.

**Iterations**: 0 of 3 (PASS on first cold-read with suggestions; suggestions don't trigger iteration per workflow but were applied in-mutation for TL;DR completeness).

**Periodic whole-doc check**: This is Mutation 5 — the N=5 periodic counter fires, so cold-read scope was escalated from the default `content-edit` bounded scope to `whole-doc`. All `**Full design**` refs in plan and track files verified to resolve. No additional structural drift surfaced.

**Working-mode counter**: N/A (this is a content-edit, not a mechanics-edit / design-sync).

## Mutation 6 — 2026-05-15 — section-add (design.md)

**Diff summary**: Add new top-level section `## Self-modification handling` between `## Phase B/C dimensional review triage update` and `## References`, closing the D13 → design.md anchor gap that was flagged in State 0 consistency review (CR2). The section carries a BLUF-shaped TL;DR; states the self-consistency invariant (workflow-doc spec under `.claude/workflow/` must match the on-disk shape under `_workflow/` at every orchestrator re-entry boundary); presents three rejected alternatives (split commits, dual-shape tooling, full-branch freeze) with concrete failure modes; enumerates the four changes Track 2 step 6 rolls into one atomic commit (writer rewire, on-disk directory rename, per-track shape migration with `[ctx=unknown]` backfill, path-reference cleanup in `implementation-plan.md`); captures the Episode-write contingency that step 6 cannot use the orchestrator's standard Phase B sub-step 7 logic (because the target `## Steps` blockquotes no longer exist after the commit) and must write directly in the new shape, ending the session immediately so Phase C resumes fresh; notes the risk-tag implication (Phase A marks step 6 `high`). Also drops the `(to be added by the next edit-design pass)` parenthetical from D13's "Full design" line in `implementation-plan.md` since the section now exists.

**Mechanical checks** (target=design, scope=bounded): PASS — 0 blockers, 1 should-fix (top-level-cap: 16 `##` sections vs cap ~15). The should-fix is the documented cost of adding a new top-level section to capture a distinct concern (atomic-step self-modification rationale); consolidation alternatives (folding into §"Directory and terminology rename mechanics" or §"Step episode storage") would mix concerns — the rename section covers the two paired renames of Track 2 steps 1+2, and the episode-storage section covers the steady-state Phase B write contract, neither of which is the right home for the self-modification invariant. Carrying one section over the soft cap is the better tradeoff. Accepted as known debt.

**Cold-read** (scope: bounded): PASS — verdict "Yes, a cold reader can build a working mental model". Zero blockers / should-fix; two polish suggestions surfaced and applied in-mutation: (G1) the Episode-write contingency opening clause was tightened from "the orchestrator's session-start episode-write logic" to "the orchestrator's standard Phase B sub-step 7 episode-write logic" so the bypassed procedure is named exactly; (G4) the risk-tag paragraph referenced four changes ("writer rewire + directory rename + shape migration + path-reference cleanup in implementation-plan.md") but the numbered enumeration only listed three — added a fourth numbered bullet for the path-reference cleanup and reworded the intro from "all three changes" to "all four changes" so the enumeration matches the prose.

**Findings**:
- should-fix (×1): `top-level-cap` — 16 `##` sections (cap ~15). Accepted as known debt; consolidation alternatives would mix concerns. The same cap is already over-by-one (was 15 after Mutation 3's debt clearance; Mutation 4 added `## Episodes` to the design narrative? — no, that was a per-track-template change; this is the first new top-level section since Mutation 3's debt clearance).

**Iterations**: 1 of 3 (PASS — first iteration produced PASS verdict; the two cold-read polish suggestions were applied without triggering a re-run, since the second mechanical run after the polish was identical PASS-with-known-debt).

**Verification of mutation isolation**: The new section was inserted between two existing sections (`## Phase B/C dimensional review triage update` and `## References`); no existing content was renamed, moved, or removed. The D13 "Full design" parenthetical drop in `implementation-plan.md` is a single-character-string edit aligned with the new section's existence — not a structural change to the plan.

**Working-mode counter**: N/A (this is a section-add, not a mechanics-edit / design-sync).
