# design.md mutation log

## Mutation 1 — 2026-05-11 — content-edit (design.md)

**Diff summary**: rewrote §"Allocation discovery surface" during the
inline-replan that retired the original Track 3. The TL;DR now
describes a dual approach (logical surface where one exists + Track 5
gated helpers otherwise); the per-component table gained a
`PaginatedCollectionStateV2` row and a post-table note stating that
`FreeSpaceMap`, `CollectionDirtyPageBitSet`, and `IndexHistogramManager`
have no EntryPoint at all (PSI-verified); Migration shape was
restructured from a 2-group split (9 pure-sizing vs 7 probes) into a
4-group partition (1 logical migration + 9 probe collapses + 6
stay-on-physical via gated helper + 1 backup); Why addPage is deletable
gained a parenthetical noting the 2 growth-loop probes absorbed from
the retired Track 3; Edge cases / Gotchas gained four new bullets for
the EP-less components, FSM-rebuild recovery, chicken-and-egg
bootstrap, and the IndexHistogramManager defensive presence probe;
References footer marks D2 and D4 as revised after the Track 3 audit.
Triggered by the Phase A audit finding that 3 of 7 in-scope components
have no EntryPoint and 4 sites are physical-by-design (technical /
risk / adversarial reviews unanimous; user-approved ESCALATE →
inline replan; plan + backlog already updated, design.md follows).

**Mechanical checks** (target=design): PASS — 0 findings.
**Cold-read** (scope: bounded — "Allocation discovery surface"
+ "Cache primitive: loadOrAdd" (preceding) + "Concurrency model"
(following) + Overview): PASS — 0 blockers, 0 should-fix, 2
suggestions.

**Findings**:
- suggestion: "≥ 7" → "7" in Migration shape probe-collapse bullet
  header (the named-site enumeration that follows is exact).
  **Applied** as a follow-up edit after the cold-read PASS.
- suggestion: in §"Why `addPage` is deletable", a short clarifier
  distinguishing the "19 external addPage sites" axis from the "2
  growth-loop pure-sizing collapses" axis would prevent a reader
  from double-counting on first read. **Deferred** — comprehension
  survives without the clarifier; the next mutation-pass (or a
  Phase 4 distillation if a `*-final` artifact rewrites the
  section) can pick it up.

**Iterations**: 1 of 3 (PASS).
