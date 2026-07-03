# Plan Review

## Post-replan re-run — 2026-07-03 (authoritative; supersedes the 2026-07-02 pass below)

- Plan review (consistency; structural pass dropped — single-track change, no `implementation-plan.md`) — **passed at iteration 3**.

Axis config: `design_gate=yes` (design half ran — design ↔ code and design ↔ track verified), single track (plan-content cross-check dropped, structural pass dropped). Workflow-machinery change: the cross-checked "code" is Markdown + Python under `.claude/**`, verified with Read/Grep against the live tree (mcp-steroid unreachable this session; grep is authoritative for Markdown/Python text, not polymorphic Java symbols). `s17=staged`, but the staged subtree does not exist yet (implementation has not run post-replan), so every `.claude/**` read resolved to the live develop-state file per §1.7(d) — correct, since the revised track cites develop-state line numbers. This run re-validated the revised `track-1.md` after the D12 inline replan reset the ledger to `phase=0`.

**Auto-fixed (mechanical)**:
- **CR2** (iter1, track ↔ code) — the drop-site line list for `.claude/agents/review-workflow-writing-style.md` was wrong: it named line 28 (the kept `**BLUF lead**` rule that D10 *hardens*, not a removal site) and omitted line 185 (a live "It's not X — it's Y" restatement). Following it literally would leave line 185 enforcing a removed rule — the exact R4 leftover-site failure. Corrected in `## Plan of Work` and `## Interfaces and Dependencies` item 6.
- **CR3** (iter1) — the cited identifier `` `ANCHORED` `` does not exist verbatim; the real symbol in `test_dsc_ai_tell.py:138` is `ANCHORED_REGRESSION_CASES`. Fixed at all three citation sites (`## Plan of Work`, `## Validation and Acceptance`, item 8).
- **CR4** (iter2 gate) — the drop-site enumeration still omitted line 89 (`### Heading style` → "Sentence case for headings"), a further restatement of the removed sentence-case-heading mandate. Added.
- **CR5** (iter3 gate) — line 38 (`## Tooling` grep-target "negative-parallelism markers") and line 200 (the finding-format template's `Cost:` examples "negative parallelism …" / "closing-phrase filler") also name removed rules. Because three iterations each found the hand-maintained line list incomplete, the enumeration was reframed as **grep-derived**: the contract is "drop every case-insensitive grep hit of the six removed-rule names," with the develop-state snapshot {29, 34, 38, 71, 89, 185, 188, 200} as an illustrative list, plus surgical-keep guidance for the kept-rule mentions and the "banned sentence patterns" category label. This ends the drift.

**Escalated (design decisions)**: none — all findings were mechanical; the orchestrator applied the track-side fixes (CR2/CR3/CR4/CR5) and deferred the one design.md-scoped finding (CR1) without user input.

**Phase-4 `design-final.md` reconciliation items** (`design.md` is frozen — Phase 2 does not mutate it):
1. **CR1** — `design.md` § Overview claims all four `house-style.md` consumers "restate no rule" and that deletion "propagates to all of them at once." True only for the `design-review.md` cold-read prompt; the `dsc-ai-tell` checker holds its own hard-coded regexes and `ai-tells/SKILL.md` hard-codes rule names, so each needs an explicit same-change edit. `design.md` contradicts its own § Class Design and § "Removing the disguise-only style rules" here. The track's `## Context and Orientation` already carries the accurate one-free / three-copy split, so no track edit is needed — only `design-final.md`. (Same root cause as the pre-replan pass's CR1/CR3/CR4.)
2. **D12 supersedes** `design.md` § "Staging and promotion under §1.7." The frozen section assumed the standard four-prefix §1.7 surface suffices; the replan extends §1.7 (plus the `implementer-rules.md` gate and the `create-final-design.md` promotion) to `.claude/output-styles/**` and root `CLAUDE.md`. Reconcile the as-built surface in `design-final.md`. (Recorded in track D12 and its `**Full design**` line; expected inline-replan divergence, not a Phase-2 finding.)
3. (carried from the 2026-07-02 pre-replan pass) `design.md:630` frames the `dsc` TL;DR-shape test as an existing rename site, but no existing test pins the section TL;DR shape (track item 10 correctly plans a *new* test). Reword to forward-looking framing.

Durable review files: `plan/track-1/reviews/consistency-iter1.md`, `consistency-gate-verification-iter2.md`, `consistency-gate-verification-iter3.md`.

---

## Pre-replan pass — 2026-07-02 (superseded by the run above)

- Plan review (consistency; structural pass dropped — single-track change, no `implementation-plan.md`) — passed at iteration 3.

Axis config: `design_gate=yes` (design half ran — design ↔ code and design ↔ track verified), single track (plan-content cross-check dropped, structural pass dropped). Workflow-machinery change: the cross-checked "code" is Markdown + Python under `.claude/**`, verified with Read/Grep against the live tree; `s17=staged` but the staged subtree does not exist yet, so every `.claude/**` read resolved to the live file (§1.7(d)). The current-state surface verified clean — all 20 in-scope live files exist and every cited symbol, anchor, agent pin, and rename site matched.

**Auto-fixed (mechanical)**:
- **CR3** (gate iter2) — `track-1.md` § Purpose + § Context: the CR1 fix carved the `dsc-ai-tell` checker out of the "inherit for free" group but left `ai-tells/SKILL.md` and `house-conversation.md` in it. Corrected so only `design-review.md` inherits the D1 deletions for free; `ai-tells/SKILL.md`, `house-conversation.md`, and the `dsc-ai-tell` checker each hold a named/mirrored copy and take an explicit edit — matching the in-scope list.
- **CR4** (gate iter3) — `track-1.md` D1 Decision-Record Rationale (`:38`) and the § Scope blockquote (`:17`) carried the same false-propagation claim verbatim. Rationale reworded to the correct one-free-inheritor framing; Scope's "four mirrored consumers" → "consumer surfaces".

**Escalated (design decisions — user away at escalation; proceeded with the recommended resolutions, revisitable)**:
- **CR1** [should-fix] — `design.md` § Overview (and its `track-1` echo) claimed all four `house-style.md` consumers "inherit the deletion for free," wrongly including the `dsc-ai-tell` regex checker. Resolution: `track-1` echo corrected in Phase 2 (see CR3/CR4); the frozen `design.md` § Overview correction is deferred to Phase 4.
- **CR2** [suggestion] — `design.md:630` frames "the `dsc` test suite where it pins the TL;DR shapes" as an existing rename site, but no existing test pins the section TL;DR shape (`track-1` item 10 correctly plans a *new* test). Resolution: deferred to Phase 4; no track edit needed.
