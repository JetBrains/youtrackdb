# Structural Review — Gremlin-to-MATCH Translator

## Outcome: PASS

No blockers. One should-fix applied; two suggestions accepted-as-noted
without code changes (per user direction).

## Findings

### S1 [should-fix] — APPLIED
**Location**: implementation-plan.md, D2 Decision Record
**Issue**: D2 body was ~49 lines, exceeding the ~30-line DR budget. The
Rationale paragraph enumerated every `MatchPlanInputs` field — content
already covered by the design doc's `MatchPlanInputs` class diagram.
The DR also re-explained the internal `handleProjectionsBlock` call site
that design.md's Workflow sequence already shows.
**Fix applied**: Trimmed D2 from 49 to ~25 lines. Removed full field
enumeration (replaced with a one-line link to design.md's
`MatchPlanInputs` class entry). Compressed Rationale paragraph.
Consolidated Implemented-in track list.

### S2 [suggestion] — DEFERRED
**Location**: implementation-plan.md, Component Map annotated bullet list
**Issue**: Three component bullets exceed the ~5-line per-component
budget: `GremlinToMatchStrategy` (9 lines),
`GremlinToMatchTranslator package` (14 lines),
`Shared MATCH IR builder package` (11 lines). Collaborator/class API
surface is duplicated in design.md class diagram.
**Decision**: Suggestion noted — not applied. The detail aids first-time
readers of the plan; the duplication cost is acceptable.

### S3 [suggestion] — DEFERRED
**Location**: implementation-plan.md, Integration Points
**Issue**: Several integration-point bullets are 4-5 lines, slightly
exceeding the ~3-line budget. Polymorphism flag bullet duplicates
content from design.md's "Schema polymorphism" section.
**Decision**: Suggestion noted — not applied. Bullets are mildly verbose
but readable and integration-relevant.

## Structural Soundness

All 12 tracks have:
- Scope lines (within ~3-6 step budget — none over 7)
- Substantive descriptions covering what/how/constraints/interactions
- Proper `Depends on:` annotations forming a strict linear chain T1→T12
  with no cycles
- No `- [ ] Step:` items or `(provisional)` markers (deferred to Phase 3)

Architecture Notes complete:
- Component Map (Mermaid + annotated bullets)
- 8 Decision Records (D1–D8), each with alternatives, rationale, risks,
  and track refs
- 6 Invariants
- 5 Integration Points
- Non-Goals (8 items)

Design document complete:
- Overview
- Class Design (Mermaid `classDiagram`) with all new and existing
  classes including `MatchPlanInputs` record and `MultiPlanMatchStep`
  subclass
- Workflow (Mermaid `sequenceDiagram`) with corrected
  `hasNext(ctx)/next(ctx)` API
- Seven dedicated sections for complex parts: Hybrid boundary mechanics,
  Predicate translation, Optional/Union semantics divergence, Strategy
  idempotency, Schema polymorphism, GQL refactor, Aggregation barrier
  semantics

No SUPERSEDED Decision Records retained. No `Step:` items. Plan length
< 1500 lines. Design length < 1500 lines.

Plan is structurally sound and ready for Phase 3 execution.
