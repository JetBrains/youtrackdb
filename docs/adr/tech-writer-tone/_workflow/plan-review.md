# Plan Review

- Plan review (consistency; structural pass dropped — single-track change, no `implementation-plan.md`) — passed at iteration 3.

Axis config: `design_gate=yes` (design half ran — design ↔ code and design ↔ track verified), single track (plan-content cross-check dropped, structural pass dropped). Workflow-machinery change: the cross-checked "code" is Markdown + Python under `.claude/**`, verified with Read/Grep against the live tree; `s17=staged` but the staged subtree does not exist yet, so every `.claude/**` read resolved to the live file (§1.7(d)). The current-state surface verified clean — all 20 in-scope live files exist and every cited symbol, anchor, agent pin, and rename site matched.

**Auto-fixed (mechanical)**:
- **CR3** (gate iter2) — `track-1.md` § Purpose + § Context: the CR1 fix carved the `dsc-ai-tell` checker out of the "inherit for free" group but left `ai-tells/SKILL.md` and `house-conversation.md` in it. Corrected so only `design-review.md` inherits the D1 deletions for free; `ai-tells/SKILL.md`, `house-conversation.md`, and the `dsc-ai-tell` checker each hold a named/mirrored copy and take an explicit edit — matching the in-scope list.
- **CR4** (gate iter3) — `track-1.md` D1 Decision-Record Rationale (`:38`) and the § Scope blockquote (`:17`) carried the same false-propagation claim verbatim. Rationale reworded to the correct one-free-inheritor framing; Scope's "four mirrored consumers" → "consumer surfaces".

**Escalated (design decisions — user away at escalation; proceeded with the recommended resolutions, revisitable)**:
- **CR1** [should-fix] — `design.md` § Overview (and its `track-1` echo) claimed all four `house-style.md` consumers "inherit the deletion for free," wrongly including the `dsc-ai-tell` regex checker (independent hard-coded regexes needing explicit deletion). The design contradicts its own § Class Design ("regex subset" / "two-file edit") and § Removing (regex/test/fixture one-unit coupling). Resolution: `track-1` echo corrected in Phase 2 (see CR3/CR4); the frozen `design.md` § Overview correction is deferred to Phase 4.
- **CR2** [suggestion] — `design.md:630` frames "the `dsc` test suite where it pins the TL;DR shapes" as an existing rename site, but no existing test pins the section TL;DR shape (`track-1` item 10 correctly plans a *new* test). Resolution: deferred to Phase 4; no track edit needed.

**Phase-4 `design-final.md` reconciliation items (`design.md` frozen — Phase 2 does not mutate it)**:
1. `design.md` § Overview: split the consumer set — only the `design-review.md` cold-read prompt inherits a rule-text deletion for free; the `dsc-ai-tell` checker, `ai-tells/SKILL.md`, and `house-conversation.md` each hold a named/mirrored copy requiring an explicit same-change edit (root cause of CR1, CR3, CR4).
2. `design.md:630`: reword the `dsc` TL;DR-shape test from existing-site framing to forward-looking ("the `dsc` shape test to be added") (CR2).

Durable review files: `plan/track-1/reviews/consistency-iter1.md`, `consistency-gate-verification-iter2.md`, `consistency-gate-verification-iter3.md`.
