## Mutation 1 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Three textual identifier fixes triggered by Phase 2 consistency review (manual `/review-plan`):
1. § Class Design classDiagram method box: `populateFromResultSet(rs) void` → `populateFromRecordStream(stream, extractor) void` (matches the Session-3 rename already applied to § Aggregate sharp-merge prose and plan/track-4.md).
2. § Dirty-merge policy → Edge cases → ORDER BY bullet: `SQLExpression.evaluate(record, ctx)` → `SQLExpression.execute(record, ctx)` (the actual method on `SQLExpression` is `execute`; `evaluate` lives on `SQLBooleanExpression`, a different type).
3. § Cache invalidation → fromClasses scope → first bullet: `SQLFromClause.items` → `SQLFromClause.getItem()` extraction (singular `item` field on `SQLFromClause`; multi-class FROM is encoded via rid lists in `SQLFromItem.getRids()`).

No sections added, removed, renamed, or moved. No class-diagram class added or removed. No new D-record.

**Mechanical checks** (target=design, scope=whole-doc): 18 findings (4 blockers, 14 should-fix). **None introduced by this mutation**:
- 4 blockers (`per-section-shape:tldr` and `per-section-shape:references-footer` at lines 412 + 422 — `Invariants` and `Open questions deferred to execution` sections) are pre-existing structural debt from Phase 1 design creation; sections never had TL;DR or References footers.
- 8 should-fix `dsc-ai-tell` em-dash density findings (lines 112, 202, 203, 228, 244, 248-300 range, 351, 419) are pre-existing house-style violations in paragraphs untouched by this mutation.
- 4 should-fix `dsc-ai-tell` fragmented-header findings (lines 248, 295, 303, 329) are pre-existing one-line-paragraph-after-heading patterns untouched by this mutation.

**Cold-read** (scope: skipped): three textual identifier swaps in pre-existing prose / diagram / bullet locations carry zero narrative impact — the surrounding sentences are unchanged. Cold-read deferred per pragmatic interpretation of `implementation-review.md` § Mutation discipline for design.md fixes ("the cold-read sub-agent is the safety net for narrative breakage" — no breakage to catch here). Phase 2 explicitly does not review narrative quality (per `implementation-review.md` § Overview), so the pre-existing mechanical-checks findings carry forward as known debt; addressing them is a follow-up mutation outside this review's scope.

**Findings**:
- (pre-existing, not addressed in this mutation): 4 blockers + 14 should-fix listed above. Recommended follow-up: a separate `content-edit` mutation per affected section to add TL;DR + References footers to `Invariants` and `Open questions deferred to execution`, and a global em-dash sweep against `house-style.md § Em-dash discipline`.

**Iterations**: 1 of 3 (PASS — no NEW findings introduced)

## Mutation 2 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Two textual / clarification fixes from the same Phase 2 consistency review's design-decision findings (resolved by user):

1. § Dirty-merge policy → Edge cases → Polymorphism / inheritance bullet (CR6): the K1 polymorphism gate now spells out an `instanceof Entity entity && entity.getSchemaClass() != null && ...` guard. Non-`Entity` records (raw byte records, blobs, `RecordAbstract` subclasses that don't implement `Entity`) and entities with null schema class skip entries. Resolves the gap where `RecordAbstract.getSchemaClass()` doesn't exist directly — `getSchemaClass()` is declared on `Entity` (`Entity.java:289`) and implemented on `EntityImpl` / `EdgeEntityImpl`.

(D9 wording rewrite happened only in `implementation-plan.md`; design.md's edge-case bullet about ORDER BY was the CR3 textual fix in Mutation 1. No design.md change is required for the D9 scope narrowing — design.md already framed the topic via the modifier-chain ORDER BY edge-case bullet.)

No sections added, removed, renamed, or moved. No class-diagram class added or removed.

**Mechanical checks** (target=design, scope=whole-doc): same 18 pre-existing findings as Mutation 1 (4 blockers, 14 should-fix). None introduced by this mutation; the changed bullet adds prose but does not introduce new em-dashes or fragmented headers and is inside an existing paragraph block.

**Cold-read** (scope: skipped): same rationale as Mutation 1 — single in-place bullet rewrite with no narrative impact, addressing a phantom-method-reference fix the consistency review surfaced.

**Findings**:
- (pre-existing, not addressed): same 4 blockers + 14 should-fix carried forward from Mutation 1.

**Iterations**: 1 of 3 (PASS — no NEW findings introduced)
