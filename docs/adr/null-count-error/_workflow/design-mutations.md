# Design mutations log

## Mutation 1 — 2026-05-22 — phase1-creation (design.md)

**Diff summary**: Seeded `design.md` with Overview, Core Concepts, Class Design (Mermaid classDiagram), Workflow (Mermaid sequenceDiagram), and five topic sections (pure-delta encoding for clear/buildInitialHistogram, two-flag holder design, endAtomicOperation lifecycle, cascade containment, why pure-delta over collection-style self-healing). Each topic section carries TL;DR, mechanism overview, edge cases, and References footer. No `design-mechanics.md` companion — single-file design under the length trigger.

**Mechanical checks** (target=design): PASS after 1 iteration (4 should-fix findings fixed: Overview body length + 3 fragmented-header overlaps).
**Cold-read** (scope: whole-doc): PASS — 3 suggestions, no blockers, no should-fix.

**Findings**:
- suggestion: glossary-introduction — "InError mode" used in Overview without inline gloss. Not load-bearing; reader can follow the cascade narrative without the term.
- suggestion: audience-fit — Overview names prerequisite knowledge but does not name the reader role. House style permits implicit framing.
- suggestion: decisions cross-pointer — D-record rationale lives in `implementation-plan.md § Architecture Notes`; design.md References footers cite the codes but the prose lives in the plan. Acceptable Phase 1 division.

**Iterations**: 1 of 3 (PASS)

## Mutation 2 — 2026-05-22 — structural-rewrite (design.md)

**Diff summary**: Converted underflow-handler terminology from "warn" to "error" across four sections: Overview (clamp+warn → clamp+error; "single warn line" → "single error line"); Core Concepts table row for Cascade containment; Cascade containment section (TL;DR + 4-row mechanism table + sketch subheading + 2 `LogManager.instance().warn(this,` calls in the code sketch + edge case 3); "Why pure-delta, not collection-style self-healing" closing prose. Also added a new Edge case (4) to Cascade containment recording the PSI-backed finding that `addToApproximate{Entries,Null}Count` has exactly one production caller (`AbstractStorage.applyIndexCountDeltas` at lines 2489–2490) invoked inside the per-index lock held from `lockIndexes` (AbstractStorage:2233) through `ensureThatComponentsUnlocked` (AbstractStorage:2368), so concurrent updates cannot produce transient underflows and every underflow at apply time signals real divergence justifying error-level visibility. The two `LogManager.instance().warn(...)` calls in the lifecycle-hook sketches under "Two-flag holder design" and "endAtomicOperation lifecycle" are intentionally untouched — those are the apply-failure cache-only contract, a distinct concept.

**Mechanical checks** (target=design): PASS — 0 findings.
**Cold-read** (scope: whole-doc): PASS — 3 suggestions, no blockers, no should-fix.

**Findings**:
- suggestion: Edge case (4) length asymmetry — the new bullet runs ~250 words vs ~30 each for (1)/(2)/(3). Reads as a mini-essay inside a list. Suggested split into three shorter sentences.
- suggestion: Two-flag holder L211 "the second warn is redundant" could conflate with the just-converted underflow handler; clarifier "the second apply-failure warn is redundant" would remove ambiguity.
- suggestion: Cascade containment References footer could append `Invariant: addToApproximate*Count callers serialize through per-index lock` so Edge case (4) has an explicit anchor in the invariants set.

**Iterations**: 1 of 3 (PASS)

## Mutation 3 — 2026-05-22 — structural-rewrite (design.md)

**Diff summary**: Consolidated counter sync into a single lifecycle gate after research surfaced that today's manual `applyIndexCountDeltas` at `AbstractStorage:2333` runs *after* `endAtomicOperation`'s inner-finally `releaseLocks` has released the per-index lock acquired by `lockIndexes` at line 2233, leaving a read-stale-in-mem race in `clear()` / `buildInitialHistogram`. Changes: rewrote the Overview's "fix has two layers" framing to two structural invariants (Invariant 1 pure-delta encoding; Invariant 2 apply-under-lock); updated Core Concepts (added Single lifecycle gate and Lock-window invariant rows, removed Two-flag holder row, kept Cascade containment); removed the `persistedToPage` / `appliedToMemory` fields from the IndexCountDelta classDiagram and added `getHistogramDeltas` / `isOpen()`; redrew the Workflow sequence diagram as a single linear path through `endAtomicOperation` (Hook A persist → commitChanges → Hook B apply → releaseLocks), eliminating the prior `alt main commit only` branches and the manual+hook split; updated the Pure-delta worked example to show persist landing inside Hook A and apply inside Hook B before releaseLocks; **deleted the entire "Two-flag holder design" section**; rewrote the "endAtomicOperation lifecycle" section around the new placement, adding the four correctness properties (persist→rollback conversion covering `IOException | RuntimeException | AssertionError`, apply log-and-swallow, per-index-lock-held-during-apply via Hook B placement before releaseLocks, state==OPEN gate as defense-in-depth); updated Cascade containment's mechanism table to show lines 2334 and 2346 deleted (formerly broadened) and Hook A/Hook B catches added, plus Edge case (4) reframed around Track 2's consolidation closing the lock window. The "Why pure-delta, not collection-style self-healing" section is unchanged.

**Mechanical checks** (target=design): PASS — 0 findings after 1 iteration (1 should-fix `dsc-ai-tell` em-dash density on Overview paragraph 2 fixed: two unpaired em-dashes after the Invariant intros replaced with comma-pause + colon).
**Cold-read** (scope: whole-doc): PASS — 3 suggestions, no blockers, no should-fix.

**Findings**:
- suggestion: endAtomicOperation lifecycle TL;DR opens with a baseline statement before the change description; house-style § BLUF tolerates this when the second sentence lands the decision crisply (verified).
- suggestion: Cascade containment TL;DR (173 words) lightly restates the mechanism table that follows it; under house-style § Padding-based finding criterion this stays under the threshold.
- suggestion: Class Design narrative paragraph (~180 words) could split into a bullet list after the Mermaid diagram for scannability; not a rule violation, comprehension survives.

**Iterations**: 1 of 3 (PASS)

## Mutation 4 — 2026-05-22 — content-edit (design.md)

**Diff summary**: Dropped the `storage.isOpen()` / `status == OPEN` defense-in-depth gate from design.md. Class Design — removed `+isOpen()` from the `AbstractStorage` classDiagram block and the prose sentence introducing the accessor. endAtomicOperation lifecycle — removed `&& storage.isOpen()` from both Hook A and Hook B conditions, deleted the "Fourth, the state gate prevents recovery-time fires." correctness-property bullet, renamed the section header from "Four correctness properties" to "Three correctness properties", and dropped the "D4: state==OPEN gate as defense-in-depth" line from the References footer. Rationale: the existing `if (holder == null) return;` early-exit in `persistIndexCountDeltas` / `applyIndexCountDeltas` / `applyHistogramDeltas` already short-circuits every recovery-time atomic op (no recovery-time call site accumulates `IndexCountDelta` today per the PSI audit at `AbstractStorage.open` lines 766–860), and the gate could silently drop counter syncs when an atomic op fires during `STATUS.CLOSING` or a hypothetical future recovery-time accumulator that needs both sides to advance. Iteration 1 cold-read also surfaced a stale TL;DR sentence in the lifecycle section and a pre-existing invariant-numbering drift between the Overview (declared two invariants) and the References footers (cite Invariant 1, 2, 3). Iteration 2 fixed both: removed the stale TL;DR sentence; renumbered the Overview to three invariants matching the plan and the footers (Invariant 1 in-memory mutates only after WAL commit, Invariant 2 AssertionError stays contained — promoted from the trailing defense-in-depth sentence, Invariant 3 apply runs with the per-index lock held — renumbered from old Invariant 2). D4 in implementation-plan.md was deleted prior to this mutation; the numbering gap (D1, D2, D3, D5, D6) is intentional.

**Mechanical checks** (target=design): PASS — 0 findings on both iterations.
**Cold-read** (scope: whole-doc): PASS at iteration 2 — 0 findings. Iteration 1: 2 blockers + 1 should-fix + 1 suggestion.

**Findings**:
- iter 1 blocker (resolved): stale TL;DR sentence in `endAtomicOperation lifecycle` ("A `status == OPEN` gate guards against recovery-time atomic ops accumulating deltas in the future.") — deleted in iteration 2.
- iter 1 blocker (resolved): Overview / footer invariant-numbering mismatch (Overview declared 2 invariants; footers cite 3) — Overview rewritten in iteration 2 to declare three invariants matching the footer citations.
- iter 1 should-fix (verified non-issue): Class Design prose mentions `Visibility of those three methods rises from private to package-private.` — visibility raise is still in scope for Track 2; only the `isOpen()` accessor was removed.
- iter 1 suggestion (not retried): edge case (1) prose "Hook A skips its early-exit" reads ambiguously; could be re-phrased to "Hook A is skipped via its `if (holder == null)` early-exit". Logged for future polish; the mechanism block above the edge cases disambiguates.

**Iterations**: 2 of 3 (PASS)
