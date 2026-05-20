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

## Mutation 3 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Three mechanical fixes from a Phase 2 consistency re-validation (manual `/review-plan`, current session):

1. § Concurrency and lifecycle → TL;DR (CR1): `assertOnOwningThread` line citations relabeled — `224/250 (commit)` → `224 (commitInternalImpl), 250 (getRecord)`. Line 224 is `commitInternalImpl`; line 250 is `getRecord(RID)` (verified at FrontendTransactionImpl.java:248-256), not commit.
2. § Invariants → I2 (CR1): same relabeling — `lines 165, 224/250, 474, 511` → `lines 165, 224 (commitInternalImpl), 250 (getRecord), 474, 511`.
3. § Class Design → CachedEntry class diagram (CR2): added a one-line `> Note:` annotation immediately after the diagram fence closes, calling out that Tracks 7 and 8 extend `CachedEntry` with additional fields (`skip`, `limit`, `aliasClasses`, `aliasWheres`, `contributingRids`, `reverseIndex`) not shown in the diagram. Cross-references § SKIP support and § MATCH per-tuple sharp-merge for the full field listing.

No sections added, removed, renamed, or moved. No class-diagram class added or removed.

**Mechanical checks** (target=design, scope=bounded): 1 should-fix (`dsc-ai-tell` em-dash density at the TL;DR paragraph after Class Design — pre-existing house-style debt from the 14-finding set carried forward since Phase 1). **No NEW findings introduced**. The line shifted from 112 to 114 because the new `> Note:` line + blank line pushed subsequent content down by 2; the finding is the same paragraph as before.

**Cold-read** (scope: skipped): same rationale as Mutations 1 and 2 — three textual identifier/citation swaps + one one-line annotation note in pre-existing prose / diagram locations carry zero narrative impact. The surrounding sentences are unchanged. Cold-read deferred per pragmatic interpretation of `implementation-review.md` § Mutation discipline for design.md fixes ("the cold-read sub-agent is the safety net for narrative breakage" — no breakage to catch here).

**Findings**:
- (pre-existing, not addressed): 4 blockers + 14 should-fix carried forward from Mutations 1 and 2.

**Iterations**: 1 of 3 (PASS — no NEW findings introduced)

## Mutation 4 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Two clarification fixes in § Cache invalidation → § fromClasses scope, user-spotted during the Phase 2 re-validation conversation:

1. **Added a "Lifecycle" call-out** as the opening paragraph of § fromClasses scope. The previous design didn't state the lifecycle of `fromClasses` in any single place — readers had to reconstruct it from § Class Design (the field exists on `CachedEntry`) + § Cache invalidation Path 1 (it's read on every mutation) + § fromClasses scope (per-shape extraction rules). The new paragraph anchors: `fromClasses` is computed **once** per `CachedEntry` at construction time on the cache-miss path in `DatabaseSessionEmbedded.query()` (Track 2 wires entry construction; Track 4 step 1 captures the set); it is then **read on every `FrontendTransactionImpl.addRecordOperation(record, status)` call** by `invalidateOnMutation`, which iterates a snapshot of entries and skips any whose `fromClasses` does not intersect `record`'s class via the Entity-guarded `isSubClassOf` gate; the set is **never recomputed** after entry construction.
2. **Rewrote the `SQLMatchStatement` extraction bullet**: label changed from "(always `NONE`)" — a pre-Track-8 artifact — to "(either `MATCH_TUPLE` or `NONE`)". After D8 / Track 8 introduces `MergeKind.MATCH_TUPLE`, `SharpMergePredicate.classify(SQLMatchStatement)` returns `MATCH_TUPLE` when every pattern node has `class:`, no LET/UNWIND, and no cross-alias-state WHEREs; `NONE` otherwise. The body now explains that extraction is identical in both classifications (union of `class:` annotations across every pattern node) and the K1 vs K0 decision is orthogonal to `fromClasses` extraction. Aligns with § MATCH per-tuple sharp-merge line 268 ("The `fromClasses` for the polymorphism gate is the union of every `aliasClasses` value — see §"fromClasses scope" above"). Removes both em-dashes from the new bullet body to avoid contributing to the pre-existing em-dash density debt.

No sections added, removed, renamed, or moved. No class-diagram class added or removed.

**Mechanical checks** (target=design, scope=bounded; changed-section="Cache invalidation"): 3 should-fix (`dsc-ai-tell` em-dash density at lines 297/302 and fragmented-header at 305 — **all pre-existing** house-style debt). **No NEW findings introduced**. The em-dash density at 305 (Lifecycle paragraph) reports 1 unpaired em-dash, below the 2-unpaired threshold; the fragmented-header pattern at 305 was pre-existing (the original short opening paragraph triggered the same rule).

**Cold-read** (scope: bounded — § Cache invalidation parent including § fromClasses scope subsection + § Overview + § Class Design + § MATCH per-tuple sharp-merge as cross-reference): PASS. Cross-checks confirmed:
- Lifecycle paragraph anchors construction (Track 2 + Track 4 step 1) and read site (`addRecordOperation`) correctly; cross-reference to Polymorphism gotcha at line 287 resolves.
- SQLMatchStatement bullet consistent with § MATCH per-tuple sharp-merge line 268; classify-returns-NONE caveat aligns with lines 278 (cross-alias-state) and 284 (LET).
- Path 1 / Path 2 / Path 3 of § Cache invalidation TL;DR remain consistent.
- No contradictions, dangling references, or narrative breakage.

**Findings**:
- (pre-existing, not addressed): 3 should-fix listed above (subset of the 14-finding em-dash density + fragmented-header debt set carried forward from Phase 1).

**Iterations**: 1 of 3 (PASS — no NEW findings introduced)

## Mutation 5 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Simplified the Lifecycle call-out's first sentence in § fromClasses scope. User-spotted: the prior wording said "at construction time on the cache-miss path in `DatabaseSessionEmbedded.query()`" — both qualifiers add no information.

1. **"cache-miss path"** is redundant: `CachedEntry` is created **only** in the miss path (hits return an existing entry with `fromClasses` already populated). Saying "at construction" already implies miss.
2. **"in `DatabaseSessionEmbedded.query()`"** is actually too narrow: per `plan/track-2.md` step 4, the cache-lookup helper is invoked both from the three `query()` overloads AND from the `executeInternal` idempotent branch, so entries can be created from either site.

Replaced the opening sentence with "computed **once** at `CachedEntry` construction (Track 2 wires the cache-lookup helper; Track 4 step 1 captures `fromClasses`)". The rest of the Lifecycle paragraph (read-on-every-`addRecordOperation`, never recomputed, fast-path-filter rationale) is unchanged.

No sections added, removed, renamed, or moved. No class-diagram class added or removed.

**Mechanical checks** (target=design, scope=bounded; changed-section="Cache invalidation"): same 3 pre-existing `dsc-ai-tell` findings (lines 297/302 em-dash density and fragmented-header at 305) as Mutation 4. **No NEW findings introduced**.

**Cold-read** (scope: skipped): pure compression swap of pre-existing prose with no narrative impact — the Lifecycle call-out's structure and remaining sentences are unchanged. Cold-read skipped per the same pragmatic interpretation used in Mutations 1-3 ("the cold-read sub-agent is the safety net for narrative breakage" — no breakage to catch here).

**Findings**:
- (pre-existing, not addressed): 3 should-fix listed above (same as Mutation 4).

**Iterations**: 1 of 3 (PASS — no NEW findings introduced)
