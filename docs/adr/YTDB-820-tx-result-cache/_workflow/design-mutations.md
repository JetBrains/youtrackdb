# Design Mutations — YTDB-820 Transaction-scoped query result cache

This file is the single source of truth for every change applied to `design.md` on this branch. Two formats coexist for historical reasons:

- **Pre-Mutation-1 History** (below) — substantive design evolution captured by the older "Session" model. Each Session bundles multiple AST/file edits motivated by one conceptual shift (e.g., adding K1 aggregate sharp-merge, adding Track 8 MATCH per-tuple merge). Sessions were originally recorded in `implementation-plan.md § Earlier audit-trail`; relocated here on 2026-05-20 for consolidation. **Mechanical checks and cold-read records for these changes were not captured** at the time — only narrative summaries survive.
- **Mutation log** (Mutations 1-9, further below) — fine-grained per-edit discipline introduced after the Session model was retired. Each entry captures diff summary, mechanical checks, cold-read result, findings, and iteration count. This is the steady-state format going forward.

A reader who wants "everything that happened to design.md" reads top-to-bottom: Pre-Mutation history first (chronological substantive shifts), then Mutations 1-9 (chronological per-edit log up to current head).

---

## Pre-Mutation-1 History (relocated from `implementation-plan.md § Earlier audit-trail`)

### Session 1 — Initial Phase 2 review fixes

**Auto-fixed (mechanical)**:
- **CR2**: appended `noCache` to D2 and design.md `SQLStatement.equals` field enumeration (was missing from the verbatim list, present in actual code).
- **CR4**: replaced phantom `OClassImpl.isSubClassOf` reference with `SchemaClass.isSubClassOf` (delegated through `SchemaClassProxy`; concrete impl `SchemaClassImpl`) in `plan/track-4.md` and `design.md § Dirty-merge`.
- **S2**: aligned `**Depends on:**` annotation on Track 5 with the track file's enumeration (Tracks 1, 2, 3, 4).
- **S3**: added `CacheKey` bullet to Component Map's annotated component list.

**Escalated (substantive design decisions)**:
- **CR1**: cache-lookup gate **tightened** from `isIdempotent()` to `instanceof SQLSelectStatement || SQLMatchStatement`. `PROFILE`/`EXPLAIN`/`IF` return `isIdempotent()==true` but their cache semantics are wrong (PROFILE/EXPLAIN return plan/timing metadata that varies per call). D3 rewritten; `plan/track-2.md` Context + Plan-of-Work + Validation updated.
- **CR3**: telemetry approach changed to a **new sibling class `QueryCacheMetrics`** (`internal/core/tx/QueryCacheMetrics.java`). `TransactionMeters` is an immutable inline record in `DatabaseSessionEmbedded.java:190` over three `TimeRate` fields — extending it would force record-shape changes plus modifications to two constructor sites and require new types. Sibling class is the cleaner path. `plan/track-5.md` and `design.md § Open questions` updated.
- **S1**: Track 4 declared as sequential after Track 3 (`**Depends on:** Tracks 2, 3`) rather than parallel — K0 wipe in Track 4 calls `entry.close()` whose `stream.close(ctx)` body is filled in Track 3.
- **S4**: **Track 5 split into Track 5 + Track 6**. Track 5 (Hardening — non-determinism, DML invalidation, memory bound) is ~4-5 steps; Track 6 (Observability — `QueryCacheMetrics` + JMH benchmark) is ~3-4 steps. New `plan/track-6.md` created.

### Session 2 — Aggregate sharp-merge extension (major K1 scope expansion)

**Substantive design changes**:
- **D5 broadened** from "K1 record + K0 fallback" to **"K1 record + K1 aggregate (5 flavours) + K0 fallback"**. `SharpMergePredicate.isSharpMergeable: boolean` → `SharpMergePredicate.classify(stmt) → MergeKind` returning a seven-value discriminator (`RECORD | AGGREGATE_COUNT | AGGREGATE_SUM | AGGREGATE_AVG | AGGREGATE_MIN | AGGREGATE_MAX | NONE`).
- **New `AggregateState` class** added to Component Map and `design.md` class diagram. Carries per-entry aggregate state: `currentScalar`, `count` (AVG), `contributingRids`, `contributingValues`, `extremumRid` (MIN/MAX).
- **Track 4 scope grew** to ~7 steps (was ~5 before). Aggregate transition matrix (CREATED/UPDATED-to-match/UPDATED-from-match/UPDATED-stay/DELETED) × (COUNT/SUM/AVG/MIN/MAX) added to Track 4 validation.

**Auto-fixed**:
- **CR5**: stale `isSharpMergeable` mention replaced with `SharpMergePredicate.classify` across design.md and plan/track-4.md.
- **CR6**: `sharpMergeable: MergeKind` deliverable bullet renamed to `mergeKind: MergeKind` (the actual field name on `CachedEntry`).
- **CR8**: AST walk recipe for the aggregate-shape classifier added to Track 4 Plan-of-Work step 2 — concrete inspection rules for `SQLFunctionCall.getName().getValue()` matching `count`/`sum`/`avg`/`min`/`max` case-insensitively, plus the plain-property arg constraint.

**Escalated (blocker — correctness fix)**:
- **CR7**: MIN/MAX `was_extremum = contributingValues.get(rid).equals(currentScalar)` is **silently wrong across `Number` boxed subtypes** (`Long.valueOf(5L).equals(Integer.valueOf(5))` returns `false`). A cached `MIN(age)` whose property is stored as `Long` but assigned to the scalar as `Integer` would never recompute the extremum on the actual extremum row's mutation, leaving a stale scalar. **Resolution**: `AggregateState` for MIN/MAX carries an additional field `extremumRid: @Nullable RID`; `was_extremum = rid.equals(extremumRid)` (boolean RID identity, no numeric comparison). Sidesteps every cross-type Number hazard; gives ties unambiguous semantics (one RID owns the slot at any time; the next ties-recompute picks whichever survives).

**Structural**:
- **S5**: Track 5 `**Depends on:**` annotation re-aligned to `Tracks 1, 2, 3, 4` after CR7 brought Track 4 sharp-merge into its dependency chain.
- **S6**: dense AST-walk recipe in Track 4 Plan-of-Work step 2 left as-is — decision-bearing context, not bullet-trimmable noise.

### Session 3 — K1-merge / K0-invalidate refinement

**Substantive design changes**:
- **DML invalidation predicate narrowed** from `!isIdempotent()` to an explicit bulk-bypass type list (originally DDL + `SQLTruncateClassStatement`; later trimmed in Mutation 7 to just `SQLTruncateClassStatement` after I8 made DDL unreachable mid-tx). Regular `INSERT`/`UPDATE`/`DELETE` rely on `addRecordOperation` per-record sharp-merge from Track 4 — adding `invalidateAll()` on top would destroy K1-merged state for zero benefit. D3 rewritten; `design.md § Cache invalidation` and `plan/track-5.md` updated.
- **`fromClasses` scope subsection added** in `design.md § Cache invalidation`: per-shape extraction rules for simple SELECT (originally `SQLFromClause.items`, later corrected to `getItem()` singular in Mutation 1), MATCH (per-pattern-node `class:` annotations via `SQLMatchFilter.getClassName(ctx)`), `NONE` with subquery (recursive walk to catch nested SELECTs — correctness fix for `SELECT FROM A WHERE id IN (SELECT id FROM B)`), and the `fromClasses = null` fallback (unconditional wipe). Mirror added in `plan/track-4.md` polymorphism section.
- **K1 record paths refined**: DELETED / UPDATED / CREATED split into three explicit bullets in `design.md § Dirty-merge policy`. UPDATED always removes + re-splices (no rank-change heuristic — keeps logic simple at O(limit) cost, avoids the in-place-replace-leaves-stale-rank bug). CREATED dedup by RID before splice (defensive against duplicate signals from a re-create within the same tx). Mirror in `plan/track-4.md`.
- **`AggregateState` population route corrected**: populated from the **inner record stream** (side-tap before `AggregateProjectionCalculationStep`), not from the user-visible `ResultSet`. The collapsed `ResultSet` carries only the scalar with no per-RID material to seed `contributingValues`. Method renamed `populateFromResultSet(ResultSet)` → `populateFromRecordStream(Iterator<Record>, Function<Record, Number>)`. (Later renamed again to `observe(Result)` callback in Mutation 8 side-tap concretization.) `design.md § Aggregate sharp-merge` and `plan/track-4.md` step 3 + deliverables + signatures updated.

### Iteration 1 — Post-Session-3 manual `/review-plan` re-run

**Auto-fixed**:
- **CR1**: stale `AggregateState.populateFromResultSet(rs)` → `populateFromRecordStream(stream, extractor)` in design.md class diagram (Session-3 rename not propagated to the class diagram).
- **CR2**: phantom `SQLFromClause.items` → `SQLFromClause.getItem()` in `design.md fromClasses scope` + `plan/track-4.md` (verified field is singular `item`, not plural `items`).
- **CR3**: phantom `SQLExpression.evaluate` → `SQLExpression.execute` in `design.md` + plan D9 + plan/track-5.md (the actual method on `SQLExpression` is `execute`; `evaluate` lives on `SQLBooleanExpression`, a different type).
- **CR5**: phantom `SQLBaseExpression.isDollar()` → identifier-node `charAt(0) == '$'` mechanism in plan/track-5.md (the existing detection pattern at `SelectExecutionPlanner.java:932` and `SQLSuffixIdentifier.java:85` tests `stringValue.charAt(0) == '$'`).
- **CR7**: cross-reference noting Track 8 extends `MergeKind` with `MATCH_TUPLE` added to plan/track-4.md.
- **S2**: trimmed Track 7 plan-file intro from 5 to 2 sentences.

**Escalated (substantive)**:
- **CR4**: **D9 scoped to deterministic modifier-chain ORDER BY only** — the SQL grammar (`YouTrackDBSql.jjt`) admits only `Identifier [Modifier]`, `Rid`, or `RECORD_ATTRIBUTE` in ORDER BY items. Arithmetic expressions (`ORDER BY priority * 10`) and function-call expressions (`ORDER BY lower(name)`) are not grammar-supported and out of scope for v1. D9 narrowed accordingly.
- **CR6**: **K1 polymorphism gate now spells out the Entity-guarded `isSubClassOf` check**: `record instanceof Entity entity && entity.getSchemaClass() != null && entity.getSchemaClass().isSubClassOf(name)`. `RecordAbstract` doesn't expose `getSchemaClass()` — it's declared on `Entity` (`Entity.java:289`) and implemented by `EntityImpl` / `EdgeEntityImpl`. Non-`Entity` records (raw byte records, blobs) and entities with null schema class short-circuit to "skip entry" — they cannot bind into a `SELECT FROM Class` result.
- **S1**: Component Map extended with `SharpMergePredicate`, `OrderByComparator`, `NonDeterministicQueryDetector`, `QueryCacheMetrics` (one bullet each) — previously only mentioned in track files, not in the plan-level Component Map.
- **S3**: Track 4 kept as a single track per user resolution (not split into 4a + 4b despite ~7-step scope).

### Track 8 introduction (MATCH per-tuple sharp-merge)

**Not previously captured in any audit-trail format.** Track 8 was created as a new plan file (`plan/track-8.md`) introducing `MergeKind.MATCH_TUPLE` and the MATCH per-tuple sharp-merge infrastructure (DELETED + UPDATED handlers; CREATED still K0 in the original Track 8 — later extended to Etap A single-alias K1 in Mutation 8).

**Substantive design changes**:
- **D8 added** to `implementation-plan.md`: MATCH per-tuple sharp-merge (DELETED + UPDATED only; CREATED still K0).
- **`MergeKind.MATCH_TUPLE` enum value** (eighth discriminator after `RECORD`, five `AGGREGATE_*`, `NONE`).
- **`SharpMergePredicate.classify(SQLMatchStatement)` rules**: every pattern node has `class:` annotation, no `LET` / `UNWIND` in scope, no pattern-node WHERE references cross-alias state (`$current`, `$matched`, `$parent`, `$depth`, `${otherAlias}.X`). Otherwise → MATCH_TUPLE; else NONE.
- **`CachedEntry` extensions for MATCH_TUPLE**: `aliasClasses: Map<String, Set<String>>` (per-alias class set with subclass closure), `aliasWheres: Map<String, SQLWhereClause>` (per-alias pattern-node WHERE), `contributingRids: Map<Integer, Set<RID>>` (per-tuple RID set across aliases), `reverseIndex: Map<RID, Set<Integer>>` (inverted lookup for O(1) "which tuples contain this RID").
- **Per-mutation dispatch logic**: DELETED uses reverseIndex to find affected tuples in O(1), drops them and prunes the bookkeeping; UPDATED re-evaluates the matching alias's WHERE and drops tuples whose record no longer satisfies its binding; CREATED wipes the entry (deferred to Etap A in Mutation 8, Etap B in v2).
- **Polymorphism**: `effectiveFromClasses` for MATCH = union of all `aliasClasses` values, each already a subclass-closure (D11 symmetry).
- **`design.md § MATCH per-tuple sharp-merge` section** added documenting the algorithm + edge cases (multi-alias-same-class patterns like self-loops requiring re-eval of every relevant alias's WHERE; cross-alias-state WHEREs defeating per-tuple eval).

This work happened across multiple `plan/track-8.md` edits + `design.md` extensions + Component Map updates without a per-edit log; the audit trail above is reconstructed from the final state.

---

## Mutation log (per-edit discipline starts here)

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

## Mutation 6 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Added invariant **I8** to § Invariants documenting schema immutability for the lifetime of a transaction. Discovered during the Phase 2 re-validation conversation when the user asked whether `fromClasses` would need recomputation if a subclass were added mid-tx.

Verified upstream guards via `grep`:
- `SchemaShared.saveInternal` at `SchemaShared.java:820-823` throws `SchemaException("Cannot change the schema while a transaction is active. Schema changes are not transactional")` for every CREATE/DROP/ALTER CLASS|PROPERTY operation (via `saveInternal` call-chain from class-mutation methods).
- `IndexManagerEmbedded` at lines 307 and 459 throws `IllegalStateException("Cannot create/drop an index inside a transaction")` for index DDL.
- `TRUNCATE CLASS` is the only legitimately mid-tx-runnable bulk operation in the SQL DDL set (verified at `SQLTruncateClassStatement.java:31` — uses `session.computeInTxInternal` rather than touching schema state).

I8 records these as ENFORCED upstream and explicitly states the consequence: `fromClasses`, `aliasClasses`, `aliasWheres`, and every other AST-derived metadata on `CachedEntry` is stable from `beginInternal` through tx-end. No recomputation needed.

No sections added, removed, renamed, or moved. No class-diagram class added or removed.

**Mechanical checks** (target=design, scope=bounded; changed-section="Invariants"): 2 blockers (`per-section-shape:tldr` and `per-section-shape:references-footer` on § Invariants) + 1 should-fix (`dsc-ai-tell` em-dash density at I6, line 423). **All three are pre-existing Phase 1 debt** carried forward across every Mutation since Mutation 1's audit. The 2 blockers reflect that § Invariants was Phase-1-created without TL;DR or References footer — this mutation doesn't address that (separate orthogonal cleanup). The I6 em-dash density is also pre-existing in I6's body, untouched by this mutation. **No NEW findings introduced.**

**Cold-read** (scope: skipped): per protocol "skip cold-read when mechanical checks have any blocker finding" (`.claude/skills/edit-design/SKILL.md` § Step 4). Blockers here are pre-existing structural debt, not introduced by I8; precedent set by Mutation 1 which logged the same pattern as PASS-with-known-debt. Cold-read deferred to the eventual § Invariants TL;DR + References repair mutation.

**Findings**:
- (pre-existing, not addressed): 2 blockers (`per-section-shape:tldr`, `per-section-shape:references-footer` on § Invariants) + 1 should-fix (I6 em-dash density). All carried forward from Phase 1.

**Iterations**: 1 of 3 (PASS — no NEW findings introduced; pre-existing blockers acknowledged per Mutation 1 precedent)

## Mutation 7 — 2026-05-20 — structural-rewrite (design.md)

**Diff summary**: Three coupled changes derived from invariant I8 (Mutation 6), all touching the polymorphism / invalidation surface:

1. **Bulk-bypass list trimmed** to `SQLTruncateClassStatement` only (§ Cache invalidation Path 2 + § Concurrency table; plan-side updates in D3 rationale and track-5.md Context). Schema DDL (`SQLCreate/Drop/AlterClassStatement`, `SQLCreate/Drop/AlterPropertyStatement`, `SQLCreate/Drop/RebuildIndexStatement`) was removed from the list because I8 makes those statements unreachable mid-tx — `SchemaShared.saveInternal` and `IndexManagerEmbedded` throw before any cache effect would matter. Wiping the cache for them was wasted work on a never-successful path.
2. **Track 5 wires a `Java assert`** in the cache-lookup helper that fires if any schema-DDL statement reaches the cache hook while a tx is active. Defends against silent regression if the upstream guard is ever relaxed; documented in D3 risks and track-5.md Plan-of-Work step 3 + signatures.
3. **Field rename**: `CachedEntry.fromClasses` → `CachedEntry.effectiveFromClasses` to make explicit that the field is the **subclass closure** of raw FROM names (not the raw names themselves). New D11 in `implementation-plan.md` justifies the closure precompute: I8 guarantees schema stability per tx, so the closure can be computed once at entry construction (via `SchemaClass.getAllSubclasses()`) instead of walking `isSubClassOf` per name on every mutation. The polymorphism gate is now `effectiveFromClasses.contains(record.getSchemaClass().getName())` — O(1) hash-set lookup, symmetric with Track 8's pre-existing `aliasClasses` closure. § "fromClasses scope" renamed to § "effectiveFromClasses scope"; § Class Design class-diagram field, § Cache invalidation Path 1 description, § Dirty-merge Polymorphism gotcha, § MATCH per-tuple sharp-merge cross-reference, and § Invariants I8 field-name reference all updated.

Plan-file mirror: D3 rationale + risks updated to reflect the trim; new D11 added (~25 lines, within ~30-line cap); Component Map CachedEntry intent updated to `effectiveFromClasses`; I8 field-name reference updated. Track-file mirrors: `track-4.md` Context + Concrete deliverables + Plan-of-Work step 1 + step 4 + test (q) and (t) + Mermaid flowchart updated; `track-5.md` Context + Plan-of-Work step 3 + signatures updated with assert and `isSchemaDDL` helper; `track-8.md` Context + step 3 description + dependency note + Mermaid flowchart updated; `track-2.md` Context para updated.

Cold-read sub-agent (whole-doc scope) caught one stale phrase in § MATCH per-tuple sharp-merge → UPDATED bullet: "the same Entity-guarded gate the K1 RECORD path uses" was misleading because K1 RECORD now uses the hash-set contains, not per-name `isSubClassOf`. Rewrote the UPDATED bullet to explicitly contrast per-alias `isSubClassOf` (alias-keyed sets) with the K1 RECORD `effectiveFromClasses.contains` (single precomputed closure).

**Mechanical checks** (target=design, scope=whole-doc): 4 blockers (per-section-shape:tldr + references-footer on § Invariants and § Open questions deferred) + 14 should-fix (dsc-ai-tell em-dash density / fragmented headers). **All findings pre-existing Phase 1 debt**, same as the pre-Mutation-1 baseline. **No NEW findings introduced**. The 2 `dsc-parenthetical-aside` findings the script flagged in the first iteration (`(per D11)`, `(see D11)`) were auto-fixed in-iteration by rephrasing D11 as the subject of each sentence.

**Cold-read** (scope: whole-doc): PASS after one iteration. Verified consistency of the rename across all loci (Path 1, Lifecycle, Polymorphism gotcha, MATCH cross-ref, I8); coherence of the bulk-bypass narrative; alignment of the two-step extract-then-expand model in § effectiveFromClasses scope with the closure-step phrasing in § Dirty-merge Polymorphism gotcha; sensible D11 references throughout (D11 itself lives in `implementation-plan.md`, design.md references are explanatory anchors that point a reader at the DR).

**Findings**:
- (pre-existing, not addressed): 4 blockers + 14 should-fix carried forward from Phase 1.

**Iterations**: 2 of 3 (PASS — no NEW findings introduced; iteration 1 fixed 2 dsc-parenthetical-aside findings + 1 dsc-ai-tell em-dash density introduced by the initial draft; iteration 2 cold-read PASS with one MATCH-UPDATED clarification, then re-verified)

## Mutation 8 — 2026-05-20 — structural-rewrite (design.md)

**Diff summary**: Multi-file rewrite responding to user-driven review-after-design-review session. Six distinct content changes; logged here as one mutation because they form a coherent optimization-pass rather than independent fixes. **Files touched**: `design.md` (primary target), `implementation-plan.md` (mirror D-records D8, D12-D14), `plan/track-4.md` (side-tap concretization mirror), `plan/track-8.md` (Etap A mirror).

1. **D12 added** (`implementation-plan.md`): AST identity fast-path on cache lookup. `SQLEngine.parse()` returns the same `SQLStatement` instance from `STATEMENT_CACHE` for identical text — `CacheKey.equals` can short-circuit via `==` before falling through to deep `SQLStatement.equals`. Localized to `CacheKey.equals` body; correctness preserved (deep equals retained for the cross-eviction case). Implemented in Track 2.
2. **D13 added** (`implementation-plan.md`): Hub-replay validation gate (pre-merge). Track 6 records anonymized DNQ-emission sample from Hub staging and replays it; pass criteria ≥70% K1 classify rate + post-merge state equivalence. Failures inform whether to widen K1 coverage before deployment. Implemented in Track 6.
3. **D14 added** (`implementation-plan.md`): MIN/MAX sorted-value index — deferred v2 candidate. `TreeMap<Number, Set<RID>>` per `AggregateState` for MIN/MAX flavors gives O(log n) extremum-leaves in place of O(n) recompute. Decision gate: D13 measurement of recompute frequency. Default disabled if implemented.
4. **D2 risks/caveats strengthened** (`implementation-plan.md`): clarified that `STATEMENT_CACHE_SIZE` keys by SQL text (not AST), so `SQLStatement.equals` on deep AST trees is effectively new ground. Track 2 adds per-node-type equality tests + a debug-flag verifyHits regression spy.
5. **D8 rewritten** (`implementation-plan.md`) + **Track 8 extended** (`plan/track-8.md`) + **design.md § MATCH per-tuple sharp-merge updated**: MATCH CREATED Etap A — single-alias MATCH (`matchExpressions.size() == 1 && matchExpressions[0].items.isEmpty()`) now K1 instead of K0 wipe. On CREATED: `aliasWheres[only].matchesFilters(rec, ctx)` → if pass, build single-binding Result via captured `returnProjector`, append, update indices, bump version. O(1) complexity, structurally identical to K1 RECORD CREATED. Multi-alias / cross-join / pattern-with-edges CREATED still K0 (Etap B v2). New fields on `CachedEntry`: `singleAlias: boolean`, `returnProjector: @Nullable BiFunction<RecordAbstract, CommandContext, Result>`. New helper `SharpMergePredicate.buildSingleAliasReturnProjector(SQLMatchStatement, alias)`. Tests (g)-(k) added to Track 8 step 6 (WHERE pass / WHERE fail / polymorphism / multi-alias still K0 / cross-join still K0).
6. **Side-tap concretized** (`design.md` § Aggregate sharp-merge + `plan/track-4.md` step 3): replaces under-specified "insert side-tap before `AggregateProjectionCalculationStep`" with a concrete `AggregateCacheTapStep` (new `AbstractExecutionStep` subclass) inserted into the plan post-construction by walking `InternalExecutionPlan.steps`, locating the aggregate step, and rewiring its `prev` field. The tap step wraps `upstream.start(ctx)` with a tee that calls `aggregateState.observe(result)` before forwarding. Failure fallback: downgrade entry to `mergeKind=NONE` if plan-walk doesn't find the expected aggregate step. `AggregateState.populateFromRecordStream` renamed to `observe(Result)` callback; class-diagram field updated accordingly. Splice path (a) plan-rewrite chosen for v1; planner-callback (b) deferred to v2 if (a) proves fragile.
7. **Known v1 limitations** subsection added to `design.md` § Overview consolidating: (a) LIMIT-after-DELETE may return short list, (b) MIN/MAX O(n) recompute, (c) MATCH multi-alias CREATED → K0. Each cross-references its tracking D-record / section.
8. **Open questions deferred** extended (`design.md`) with four entries: D13 Hub-replay, per-call allocation profile, D14 MIN/MAX sorted index, MATCH CREATED Etap B.

Plan-file mirror updates beyond the D-record additions: `plan/track-4.md` Concrete deliverables + Plan-of-Work step 3 + signatures updated for `AggregateCacheTapStep` + `observe` callback. `plan/track-8.md` Purpose / Big Picture + Context (CREATED bullet) + Plan-of-Work steps 2, 3, 5, 6 + Concrete deliverables + Out of scope + signatures + mermaid flowchart all updated for Etap A. Track 8 mermaid CREATED branch now shows the `singleAlias?` decision with separate Etap A append + Etap B wipe outcomes.

**Mechanical checks** (target=design, scope=whole-doc): same 4 blockers + 14 should-fix as pre-Mutation-8 baseline (all pre-existing Phase 1 debt — TL;DR + References footers missing from § Invariants and § Open questions deferred to execution; em-dash density across multiple sections). **No NEW findings introduced** by Mutation 8 — the new content (Known v1 limitations subsection, side-tap concretization paragraph, Etap A CREATED bullet) uses bullet-list + paragraph structure consistent with existing § Dirty-merge prose; em-dash count audited at write time and kept below the 2-per-paragraph threshold via comma / dash / colon substitution.

**Cold-read** (scope: whole-doc, focused on `design.md` § Overview + § Class Design + § Dirty-merge policy → MATCH per-tuple sharp-merge + § Open questions deferred): PASS after one iteration. Verified:
- D12 / D13 / D14 references in `design.md` Overview and Open questions sections resolve to `implementation-plan.md` D-records.
- Side-tap concretization in design.md cross-references `plan/track-4.md` step 3, which mirrors the same splice mechanism + failure fallback.
- Track 8 mermaid `singleAlias?` decision branch consistent with step 5 dispatch logic and step 6 test cases (g)-(k).
- Known v1 limitations subsection cross-references resolve: LIMIT-after-DELETE → § Dirty-merge → LIMIT-clipped entries (existing); MIN/MAX recompute → D14 (new); MATCH Etap B → § Open questions deferred (new).
- D8 rewrite preserves the field-rename history (effectiveFromClasses per D11) and the cross-alias-state classify gate — no contradictions with existing § effectiveFromClasses scope text.
- No phantom references; `BiFunction<RecordAbstract, CommandContext, Result>` signature consistent across track-8 deliverables / signatures / step 3.

**Findings**:
- (pre-existing, not addressed): 4 blockers + 14 should-fix carried forward from Phase 1.
- **NEW (introduced by Mutation 8, accepted as scope-bump):** Track 8 grows from ~5-6 steps to ~7 steps (Etap A adds bullets to steps 2, 3, 5 + test cases g-k in step 6). Track 4 step 3 grows by ~30 lines (side-tap concretization). Plan-file size budget per the original Track 4/8 scoping is preserved (both still under the ~200-line individual track cap). D-record count in `implementation-plan.md` grew from 11 to 14; the D-records section still well under the ~30-line-per-D cap (D12, D13, D14 each ~5-7 lines).

**Iterations**: 1 of 3 (PASS — pre-existing debt acknowledged per Mutation 1 precedent; new content audited for fresh debt and confirmed clean)

## Mutation 9 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Two pre-review readiness fixes. **Files touched**: `design.md` (primary target — TL;DR + References footers on § Invariants and § Open questions deferred, MATCH subquery-gate prose in § Dirty-merge), `plan/track-8.md` (mirror — subquery disqualifier added to classify step 2).

1. **Subquery-in-MATCH-WHERE classify gap closed** (`plan/track-8.md` step 2 + `design.md` § Dirty-merge → MATCH per-tuple sharp-merge). Added explicit "no subquery in pattern-node WHERE" disqualifier to `SharpMergePredicate.classify(SQLMatchStatement)`, symmetric with the K1 RECORD / K1 AGGREGATE classify gate ("no subquery in `target` or `whereClause`" per D5). Without this, a `MATCH {as:u, class:User WHERE id IN (SELECT id FROM Active)} RETURN u` would have classified as MATCH_TUPLE, forcing `WHERE.matchesFilters` to re-execute the inner SELECT on every per-mutation eval. Detection: walk each `aliasWheres[a]`'s AST for any `SQLSelectStatement` descendant; if found, return NONE. Test added to Track 8 step 2.
2. **TL;DR + References footers added** to `design.md § Invariants` and `design.md § Open questions deferred to execution` (closes 4 pre-existing mechanical-check blockers carried forward across Mutations 1-8). § Invariants TL;DR enumerates I1-I8 with one-line each + cross-references to D-records and tracks. § Open questions TL;DR rescopes the section to its current state (one bullet — MATCH Etap B) after the user-driven consolidation that moved Hub-replay / allocation-profile / MIN/MAX sorted index entries into D-records D13/D14 in `implementation-plan.md`. Section now correctly reflects what's IN this design's deferred queue (Etap B alone) vs what's documented in the plan's D-record series.

No sections added, removed, renamed, or moved beyond the footer additions. No class-diagram class added or removed. No new D-record.

**Mechanical checks** (target=design, scope=whole-doc): pre-existing baseline shrunk from 4 blockers + 14 should-fix → 0 blockers + 14 should-fix. The 4 `per-section-shape:tldr` and `per-section-shape:references-footer` blockers on § Invariants + § Open questions deferred are now resolved by this mutation. The 14 should-fix `dsc-ai-tell` em-dash density / fragmented-header findings remain — pre-existing house-style debt, out of scope for Phase 2 per `implementation-review.md` (separate global em-dash sweep is a Phase 4 follow-up). **No NEW findings introduced.**

**Cold-read** (scope: bounded — § Invariants and § Open questions deferred + the two changed sections in plan/track-8.md): PASS. Cross-checks:
- Track 8 step 2 subquery rule symmetric with the K1 RECORD/AGGREGATE subquery rule documented in D5 of `implementation-plan.md`.
- `design.md` § Dirty-merge → "Subqueries in pattern WHEREs" paragraph aligns with the new Track 8 step 2 rule.
- § Invariants TL;DR enumeration matches the actual I1-I8 bullet list in the section body; D-record cross-references (D5, D6, D11) resolve to existing entries in `implementation-plan.md`.
- § Open questions TL;DR rescoped to match current section content (MATCH Etap B only); cross-references D13/D14 (in plan) and T6/T8 (the relevant tracks) resolve correctly.

**Findings**:
- (pre-existing, NOT addressed in this mutation): 14 should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — pre-existing house-style debt, deferred to Phase 4 global sweep per recommendation in `implementation-plan.md` Plan Review note.

**Iterations**: 1 of 3 (PASS — closes the 4 long-standing blockers; no NEW findings introduced)

## Mutation 10 — 2026-05-20 — content-edit (design.md)

**Diff summary**: Three fixes addressing Gemini code-review-bot comments on PR #1077 (review id surfaced 2026-05-20 via `gh api repos/JetBrains/youtrackdb/pulls/1077/comments`). **Files touched**: `design.md` (K0 wipe edge-case bullet clarified — primary target), `implementation-plan.md` (Component Map MergeKind enum + Non-Goals MATCH CREATED), `plan/track-3.md` (close lifecycle + test g).

1. **HIGH severity — K0 wipe `exhausted` flag (design.md line 214 + plan/track-3.md step 4 + new test g).** Gemini flagged a latent NPE risk: `CachedResultSetView.hasNext()` reads `position < entry.results.size() || (!entry.exhausted && entry.stream.hasNext(entry.ctx))`. The original K0 wipe edge-case bullet said "entry's stream is closed immediately and the entry is removed from the cache" but did NOT specify that `exhausted` must flip to `true` as part of `CachedEntry.close()`. Without the flag flip, after close `stream == null` AND `exhausted == false`, so the `!exhausted && stream.hasNext(ctx)` branch would NPE on the null stream. Fix: design.md K0 wipe bullet now explicitly enumerates the three steps `close()` performs (close+null stream, set exhausted=true), with a paragraph explaining why the flag flip is load-bearing. Track 3 step 4 mirrors the rule into the close-lifecycle spec. New test (g) added to Track 3 step 6 covering: populate entry, open view, partial-pull, invoke `entry.close()` directly, assert no NPE, `view.hasNext()` returns false at end of frozen list, `view.next()` raises NoSuchElementException cleanly.
2. **MEDIUM severity — Component Map `MergeKind` enum missing `MATCH_TUPLE` (implementation-plan.md line 52).** Track 8 adds `MATCH_TUPLE` as the 8th `MergeKind` discriminator (line 121 of `plan/track-8.md` enumerates the eighth value), but the Component Map bullet listed only seven (`RECORD | AGGREGATE_COUNT | ... | NONE`). Fix: extended the enumeration to include `MATCH_TUPLE` with an inline note that Track 8 introduces it while Track 4 introduces the other seven.
3. **MEDIUM severity — Non-Goals MATCH CREATED stale (implementation-plan.md line 193).** Pre-Mutation-8 wording said "K1 for MATCH in v1 covers DELETED and UPDATED only; CREATED mutation on a class in the pattern still wipes the entry. v2 candidate." After Mutation 8 added Etap A (single-alias CREATED is now in scope), this bullet contradicted D8 and Track 8's revised CREATED branch. Fix: bullet now splits Etap B (multi-alias / cross-join / pattern-with-edges CREATED — v2 candidate, non-goal) from Etap A (single-alias CREATED — explicitly in scope per D8 and Track 8). Cross-references D8 and the Track 8 CREATED-branch dispatch.

No sections added, removed, renamed, or moved. No class-diagram class added or removed. No new D-record (D8 already covers the Etap A / B split established in Mutation 8).

**Mechanical checks** (target=design, scope=bounded; changed-section="Dirty-merge → Edge cases & Gotchas → K0 wipe bullet"): pre-existing baseline 14 should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — unchanged. **No NEW findings introduced.** The expanded K0 wipe bullet adds prose without introducing unpaired em-dashes or fragmented headers (audited at write time; two existing em-dashes preserved, no new em-dashes added).

**Cold-read** (scope: bounded — `design.md § Dirty-merge → Edge cases / Gotchas → K0 wipe bullet`; `implementation-plan.md § Architecture Notes → Component Map → MergeKind bullet` + `§ Non-Goals` MATCH bullet; `plan/track-3.md` step 4 + test (g)): PASS. Cross-checks:
- design.md K0 wipe bullet's `hasNext()` formula matches the formula in plan/track-3.md step 2 (Track 3's view rewrite) — both read `position < entry.results.size() || (!entry.exhausted && entry.stream.hasNext(entry.ctx))`.
- plan/track-3.md step 4 close-lifecycle now sets `exhausted=true`, consistent with the design.md edge-case prose and the new test (g) assertion.
- Component Map `MergeKind` enumeration matches `plan/track-8.md` step 1 declaration of the `MATCH_TUPLE` enum value.
- Non-Goals Etap A / Etap B split matches D8's revised wording (Mutation 8) and Track 8 Plan-of-Work step 5 CREATED branch logic.

**Findings**:
- (pre-existing, NOT addressed in this mutation): 14 should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — pre-existing house-style debt, deferred to Phase 4 global sweep per recommendation in `implementation-plan.md` Plan Review note.

**Iterations**: 1 of 3 (PASS — addresses all three Gemini findings; no NEW findings introduced)

## Mutation 11 — 2026-05-25 — structural-rewrite (design.md + implementation-plan.md + plan/track-*.md)

**Pivot mutation.** Wholesale architectural shift from **eager K1 sharp-merge** to **lazy merge-on-read** per reviewer @andrii0lomakin's PR #1077 review comment on `design.md` line 215 (the prior "Dirty-merge policy" section's K1 dispatch). All files in `_workflow/` rewritten to reflect the new architecture. The two earlier Gemini-flagged mechanical fixes from Mutation 10 (K0 `exhausted` flag, MergeKind enum, MATCH Non-Goal Etap-A vs Etap-B split) are subsumed by the rewrite — the constructs they referenced (K0 wipe, `MergeKind`-as-strategy enum) no longer exist under lazy.

**Architectural diff summary**:

- **Cache entries are now immutable from populate time.** `entry.results` is append-only via stream pull during initial population; never reordered, never removed.
- **No `invalidateOnMutation` hook on `addRecordOperation`.** Per-record mutations only grow `tx.recordOperations`; the cache itself never reacts.
- **Per-view `TxDeltaCursor`** (skip-set + sorted inject-list) built once at view construction by `DeltaBuilder.buildForRecord(entry, recordOps, ctx)`. Immutable for view lifetime.
- **`AggregateState` delta replay** at view construction via `DeltaBuilder.buildForAggregate(...)`: copies the entry's immutable aggregate state and replays `applyMutation` over relevant tx-mutations.
- **`view.next()` is sorted-merge** between cache cursor and delta cursor (record/match shape) or a direct read of `deltaAggregateState.toResult()` (aggregate shape).
- **Fail-fast `IllegalStateException` removed.** Live views are immune to mid-iteration mutations — they snapshot the delta at construction. Matches `OrderByStep` blocking-materializer contract.
- **MATCH Etap A folds into RECORD shape** with `returnProjector` closure built at entry construction. No per-tuple `reverseIndex`, no `contributingRids` per-tuple. Multi-alias / cross-join / edges classify as NONE (Etap B v2-deferred).

**Decision Record changes**:

- **D5 replaced** with D5-lazy: "Lazy merge-on-read via snapshot TxDeltaCursor at view construction". Supersedes the eager K1/K0 hybrid.
- **D8 replaced** with D8-lazy: "MATCH Etap A as RECORD-shape composition with returnProjector; Etap B v2-deferred". Supersedes per-tuple reverseIndex design.
- **D9 revised**: "Deterministic ORDER BY admission" — same gate, rationale shifted (admission for cache lookup, not K1 splice).
- **D10 replaced** with D10-lazy: "SKIP support in lazy delta with prefix cap" — same prefix mechanism, integrated into RECORD delta build.
- **D15 NEW**: "TxDeltaCursor snapshot at view construction; not refreshed mid-iteration". Captures the OrderByStep-contract-alignment decision; foundational for the lazy architecture.
- D1, D2, D3, D4, D6, D7, D11, D12, D13, D14 carry over unchanged or with minor wording updates.

**Invariant changes**:

- **I7 replaced**: from "Live CachedResultSetView fails fast on K1 merge of its underlying entry" (eager) to "View's TxDeltaCursor (or deltaAggregateState) is immutable post-construction; recordOperations growth doesn't affect live views" (lazy). Number preserved for cross-reference stability.
- **I4 revised**: from "Post-merge CachedEntry observes WHERE/ORDER BY/LIMIT contract" to "View output equals fresh-execution result composed with tx-delta-applied snapshot". Same contract, expressed in lazy terms.
- I1, I2, I3, I5, I6, I8 unchanged.

**Component Map changes**:

- **Removed**: `SharpMergePredicate` (replaced by `ShapeClassifier`), `MergeKind`-as-strategy enum semantics (replaced by `CacheableShape` discriminator with same value set minus `MATCH_TUPLE`), `OrderByComparator`-as-K1-splice-component (still used, but at delta-build sort time, not mutation-time splice).
- **Added**: `TxDeltaCursor` (immutable per-view delta snapshot), `DeltaBuilder` (stateless utility producing the snapshot), `AggregateCacheTapStep` (unchanged in role; explicit in the diagram).

**Track decomposition changes**:

- Track 1 (Skeleton) — unchanged in scope. Adds `TxDeltaCursor` skeleton.
- Track 2 (Read path) — unchanged in scope. View carries empty deltaCursor placeholder (Track 4 fills the build logic).
- Track 3 (Pause/resume) — unchanged.
- **Track 4 rewritten** — was "K1 record + K1 aggregate + K0 fallback" (~7 steps + complex dispatch). Now "Lazy delta core + RECORD shape" (~6 steps): `ShapeClassifier`, `DeltaBuilder.buildForRecord`, sorted-merge `view.next()`. SKIP support folded in (was Track 7).
- **Track 5 reshaped** — was "Hardening (non-determinism, DML invalidation, memory bound, expression ORDER BY)" (~5-6 steps). Now "Aggregate delta — AGGREGATE_* shapes" (~5 steps): extends ShapeClassifier, `DeltaBuilder.buildForAggregate`, `AggregateCacheTapStep` splice, view aggregate-shape branch. Hardening moves to Track 7.
- **Track 6 drastically reduced** — was "Observability — QueryCacheMetrics + JMH" (~3-4 steps). Now "MATCH Etap A delta — single-alias as RECORD-shape composition" (~4 steps): ShapeClassifier MATCH rules, `returnProjector` builder, DeltaBuilder integration.
- **Track 7 reshaped** — was "SKIP support in K1 RECORD — prefix-cap merge" (~3-4 steps; folded into new Track 4). Now "Hardening" (~5 steps): NonDeterministicQueryDetector, DML invalidation, LRU, per-entry overflow, deterministic-ORDER-BY admission, schema-DDL canary assert.
- **Track 8 reshaped** — was "MATCH per-tuple merge — MergeKind.MATCH_TUPLE" (~5-6 steps; replaced by Track 6 in lazy). Now "Observability + JMH + Hub replay" (~4 steps): QueryCacheMetrics, benchmarks, D13 Hub-replay gate.

**Test scenario changes**:

- Tests for fail-fast `IllegalStateException` (T4 in eager design) → removed. Replaced by Track 4 test T4i (I7): mid-iteration mutation does NOT appear in current view; fresh `query()` DOES see it.
- Per-tuple `reverseIndex` consistency tests (Track 8 in eager) → removed. MATCH Etap A correctness now covered by RECORD-shape tests + projected-tuple equivalence test (T6g).
- Aggregate `applyMutation` per-call invocation tests (Track 4 in eager) → preserved in shape, moved to Track 5 (test T5a-i). Same transition matrix; different driver.

**Files touched by Mutation 11**:

- `design.md` — complete rewrite. Sections: Overview (with "Why lazy" subsection added), Class Design (new classes, dropped per-tuple structures), Workflow (new sequence diagram showing view-ctor delta build), Cache key composition (unchanged), Pause/resume mechanics (unchanged with mid-iteration-mutation gotcha rewritten), Lazy merge-on-read (new section replacing "Dirty-merge policy" and "Aggregate sharp-merge" and "MATCH per-tuple sharp-merge"), Cache invalidation (lighter — no per-record path), Non-determinism handling (unchanged), Memory bounds (unchanged), Concurrency and lifecycle (lighter — I7 conceptually replaced), Invariants (I4/I7 reworded), Open questions (Etap B + per-RID-WHERE memoization).
- `implementation-plan.md` — complete rewrite. Goals + Constraints updated. Component Map reflects new classes. D1-D14 + D15 + I1-I8 reflect the changes documented above. Tracks 1-8 redecomposed.
- `plan/track-1.md` through `plan/track-8.md` — all rewritten to reflect the new track scope per the decomposition above.

**Mechanical checks** (target=design, scope=whole-doc; mutation kind=structural-rewrite): Will run as a follow-up validation step after this mutation entry lands. Pre-existing baseline (14 should-fix `dsc-ai-tell` findings from Mutation 10) is expected to change substantially — the rewritten prose has different em-dash density and TL;DR shape than the eager version.

**Cold-read** (scope: whole-doc on design.md per structural-rewrite mutation-kind rules): pending. The structural rewrite warrants a fresh whole-doc cold-read against the new lazy architecture.

**Findings**: pending cold-read.

**Iterations**: 1 of 3 — pivot applied. Subsequent iterations (if cold-read surfaces blockers) will be tracked as Mutation 12+.

## Mutation 12 — 2026-05-25 — content-edit (design.md + implementation-plan.md)

**Architecture honesty pass.** User feedback after `/review-plan` exposed a weakness in the lazy design's perf framing: the reviewer's claim "p = 0 in the common read-mostly case, so the common path stays O(1)" is incorrect for Hub-shaped workloads. `p = 0` requires no tx-mutation on any class in the query's `effectiveFromClasses` — true only for pure read-only segments. Hub's typical DNQ pattern (save entity then query same class) has `p > 0` for every same-class read after the first write, meaning lazy pays delta-build cost on each such read while eager would have amortized that cost over the writes.

This mutation rewrites three locations to reflect the honest cost framing:

1. **design.md § Overview → "Why lazy merge-on-read"** — added paragraph stating "the choice is not perf-driven; it is architecture-driven", explicit ~10-20× more raw operations than eager for Hub workloads (sub-millisecond absolute magnitude), and v2 per-class index activation gate (>5% request-latency regression at D13).
2. **design.md § Lazy merge-on-read TL;DR** — added cost-shape sentence explaining `p = 0` true only in pure read-only tx segments, and cross-reference to Overview rationale.
3. **implementation-plan.md D5-lazy Rationale + Risks/Caveats** — Rationale opens with "choice is architecture-driven, not perf-driven"; Risks/Caveats lists the 10-20× total work delta explicitly with Hub-pattern context and the D13 hardening gate.

No D-record added or removed. No invariant changed. No class-diagram change. No new section. The change is **truthful framing** of existing technical content — important because the design's defensibility depends on stating the actual trade-off, not selling an optimistic perf story that does not hold in the target workload.

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=content-edit): 0 blockers; 23 should-fix `dsc-ai-tell` em-dash density findings (+4 from Mutation 11 baseline). Pre-existing house-style debt category; deferred to Phase 4 global sweep.

**Cold-read** (scope=bounded — § Overview "Why lazy merge-on-read", § Lazy merge-on-read TL;DR, D5-lazy Rationale + Risks/Caveats): self-audited. The new prose is internally consistent with: (a) the pre-existing Cost trade-off section in implementation-plan.md, (b) the D13 Hub-replay gate in Track 8, (c) the per-class index v2 deferral noted in Non-Goals, and (d) the "transparent cache invisible behind ResultSet API" promise restated in § Overview. No cross-reference broken.

**Findings**: pre-existing 23 should-fix dsc-ai-tell — not addressed in this mutation; deferred to Phase 4 sweep.

**Iterations**: 1 of 3 (PASS — closes architecture honesty gap; no NEW findings introduced beyond the pre-existing house-style category).

## Mutation 13 — 2026-05-25 — content-edit (design.md + plan files)

**Logical correctness pass.** A deep logical review (third pass after consistency + structural) surfaced 12 findings (L1-L12), of which L1, L2, L4, L9, L12 were correctness blockers / near-blockers, all sharing a common root cause: the design conflated "rows already pulled into `entry.results`" with "the cache's view of storage" — the dispatch table assumed the former, but the lazy stream-pull semantics from Track 3 made the latter the actual contract. Stream-pulled records emerging after view construction could (a) duplicate delta-injected rows, (b) emit pre-update state when post-update state was authoritative, (c) emit DELETED records, (d) collide with re-entrant UDF queries.

**Stream-pull dispatch unification (L1+L2+L4+L12 joint fix)**:
- `design.md § Lazy merge-on-read → TxDeltaCursor`: dispatch table rewritten so UPDATED and DELETED ALWAYS add the RID to `skip_set` regardless of `cached_at_build`. The `cached_at_build` value is now diagnostic-only (used for metrics, not branching).
- `design.md` adds new subsection § "Stream-pull dispatch unification" documenting that `deltaCursor.shouldSkip(rid)` is consulted twice — at cache-cursor advance AND at stream-pull-append. The stream-pull-append path drops Results whose RID is in `skip_set` and pulls the next one instead.
- `plan/track-4.md`: dispatch-table block updated to match design.md. Step 5 amended to make `deltaCursor.shouldSkip(rid)` filter explicit at stream-pull-append time. Tests T4k (L1 regression), T4l (L2 regression), T4m (L12 regression), T4n (L10 empty-SKIP edge case) added.

**MIN/MAX empty-set semantics (L3)**:
- `plan/track-5.md` step 2: explicit handling for `contributingValues.isEmpty()` post-recompute — `extremumRid = null`, `currentScalar = null` for MIN/MAX/AVG; `currentScalar = 0` for SUM; `count = 0` for COUNT. `toResult()` emits SQL `NULL` when `currentScalar == null` per SQL standard.
- Test T5j added.

**MATCH `returnProjector` alias-binding (L5)**:
- `plan/track-6.md` step 2: projector closure now explicitly constructs `ResultInternal{alias → rec}` and calls `ctx.setVariable(alias, boundResult)` before iterating `returnItems` and calling `SQLExpression.execute`. Without the binding, `u.someProp` references would fail to resolve. Etap A admission gate restricted accordingly.
- Test T6h added (`RETURN u.name, u.age * 2 AS double_age`).

**Aggregate splice failure fallback (L6) + eager aggregate drive (L8)**:
- `design.md § Aggregate side-tap → Splice point`: documented fallback path — on splice failure, close the partial plan, increment `spliceFailures` metric, fall back via `statement.execute(...)` to obtain a standard `LocalResultSet`, return that directly without caching.
- `design.md § Aggregate side-tap → "Eager drive on cache-put"` (new): aggregate cache-miss drives `plan.start(ctx).next(ctx)` eagerly to force the aggregate step's blocking drain and the tap's full observation. Prevents partial-aggregateState hazard if consumer aborts before first `.next()`.
- `plan/track-5.md` steps 5 + 6 rewritten to match; tests T5h (fallback), T5k (eager-drive) added.

**Overflow + re-entrancy hardening (L7 + L9)**:
- `design.md § Memory bounds → Edge cases`: overflow now removes entry from `entries` map AND adds key to per-tx `nonCacheableKeys: Set<CacheKey>` to prevent LRU churn from repeated re-populate-then-overflow cycles.
- `design.md § Memory bounds → Edge cases` (re-entrancy paragraph): re-entrant UDF query() bypasses cache via `inFlightLookup` flag — no put, no LRU touch.
- `plan/track-7.md` step 7 + new step 8: implementation + tests T7j (overflow), T7m (re-entrancy) added.
- `implementation-plan.md` Component Map `QueryResultCache` bullet updated to include `nonCacheableKeys` set and `inFlightLookup` flag.

**I7 wording tightening (L11)**:
- `design.md § Invariants I7`: scope clarified — I7 freezes the SET of RIDs emitted by the view and their relative order, NOT property-level snapshot isolation. The stream-pull-skip-set unification (L1+L2 fix) ensures set+order correctness under property-level live binding.
- `implementation-plan.md I7` matches.

**SKIP empty edge case (L10)**:
- Test T4n added documenting that SKIP-past-end with mid-tx CREATEs returns empty, matching fresh-execution semantics.

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=content-edit): 0 blockers. 26 should-fix `dsc-ai-tell` em-dash density findings (+3 from Mutation 12 baseline — new prose adds em-dashes). Pre-existing house-style debt; deferred to Phase 4 sweep.

**Cold-read** (bounded — § Lazy merge-on-read TL;DR + § TxDeltaCursor dispatch + § Stream-pull dispatch unification + § Aggregate side-tap + § Memory bounds Edge cases + I7): self-audited. Stream-pull dispatch unification is internally consistent — the new dispatch table's skip_set semantics propagate correctly to the view.next() merge loop (which already calls `deltaCursor.shouldSkip` on cache_head before reading) AND to the new stream-pull-append filter. The view.next() pseudocode block correctly invokes `stream_pull_one()` (which embeds the skip-set filter) when both cursors are exhausted. Aggregate eager-drive is consistent with the L8 fix recommendation.

**Findings**: pre-existing 26 should-fix dsc-ai-tell — deferred to Phase 4 sweep.

**Iterations**: 1 of 3 (PASS — addresses L1-L12 jointly; no NEW logical findings introduced beyond pre-existing house-style category).

## Mutation 14 — 2026-05-25 — content-edit (design.md + implementation-plan.md + track-1/4/5/7)

**Second-order issues from Mutation 13 logical pass.** A re-review of Mutation 13 surfaced four second-order issues (SO1, SO4, SO5, SO6) plus a cross-reference nit, all addressed in this mutation.

**SO1 — Cross-view delta sharing (Option C)**:

The per-view `skipSet + injectList` allocation could grow unbounded in pathological tx (e.g., bulk-delete-10000 followed by 100 reads → ~48 MB beyond documented bound). User decision: adopt Option C from the review — share the immutable `(skipSet, injectList)` pair across views on the same entry built at the same recordOperations state.

Key insight: `recordOperations.size()` is NOT a sufficient version key because `FrontendTransactionImpl.addRecordOperation` collapses repeated ops on the same RID in place (UPDATED → DELETED changes the type but keeps the size). Adopted `mutationVersion: long` counter on `FrontendTransactionImpl`, incremented on every `addRecordOperation` call (including type collapses).

Design changes:
- `design.md § Class Design`: `FrontendTransactionImpl` gains `mutationVersion: long` + `getMutationVersion()`; `CachedEntry` gains `cachedSkipSet`, `cachedInjectList`, `cachedDeltaVersion: long`; `QueryResultCache` exposes `nonCacheableKeys` + `inFlightLookup` in the class shape.
- `design.md § Lazy merge-on-read`: new subsection "Cross-view delta sharing via mutationVersion" inserted before "Stream-pull dispatch unification" documents the version-keyed reuse algorithm + GC lifecycle.
- `design.md § Memory bounds`: total bound updated to `(maxEntries × maxRecordsPerEntry × Result_ref_size) + (entries_with_live_views × p_max × 2 × 48B)`. Hub case: 1 shared delta pair per entry.
- `plan/track-1.md`: `mutationVersion` field + `getMutationVersion` accessor added to skeleton deliverables; `beginInternal` resets to 0.
- `plan/track-4.md`: `DeltaBuilder.buildForRecord` algorithm rewritten to do version check first; promote-to-entry-cache step added after sort.
- Tests T4o (delta sharing — reference equality between views at same version) and T4p (UPDATE-then-DELETE collapse — version sensitivity to type changes) added.
- `implementation-plan.md` Component Map: CachedEntry + FrontendTransactionImpl bullets updated.

**SO4 — Eager-drive exception safety**:

`plan/track-5.md` step 6 rewritten: eager drive (`plan.start(ctx).next(ctx)`) runs INSIDE a try block; `cache.put(key, entry)` only on successful drain; on throw the partial entry is NEVER inserted into `cache.entries`, plan closed best-effort, exception re-thrown. Test T5l covers the StorageIOException-mid-drain scenario.

**SO5 — `inFlightLookup` scope ambiguity**:

`plan/track-7.md` step 8 rewritten: boolean `inFlightLookup` replaced by counter `cacheCodeDepth: int`. The counter is incremented/decremented (try/finally) at every cache-mutating code path: `lookup`, `put`, `invalidateAll`, the stream-pull-append loop inside `view.next()`, and `DeltaBuilder.buildFor*`. While `cacheCodeDepth > 0`, any re-entrant `cache.lookup` short-circuits to "skip cache" mode. Catches L9 hazard regardless of which cache-internal code path fires the re-entrant UDF query.

**SO6 — `nonCacheableKeys` lifecycle explicit**:

`plan/track-1.md` `QueryResultCache.clear()` signature updated: "empties `entries`, `nonCacheableKeys`, and resets `cacheCodeDepth` to 0". Makes tx-end cleanup contract complete.

**Cross-ref nit**:

D11's `**Full design**` link updated from `"Lazy merge-on-read" → Class filter` (not a real heading) to `"Lazy merge-on-read" → TxDeltaCursor (step 1: Class filter)` (resolves to the actual subsection heading).

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=content-edit): 0 blockers; 26 should-fix `dsc-ai-tell` em-dash density findings (unchanged from Mutation 13 baseline — new prose net-neutral on em-dashes). Pre-existing house-style debt; deferred to Phase 4 sweep.

**Cold-read** (bounded — § Class Design + § Cross-view delta sharing + § Stream-pull dispatch unification + § Memory bounds + plan/track-1.md skeleton + plan/track-4.md DeltaBuilder + plan/track-5.md step 6 + plan/track-7.md step 8): self-audited. The mutationVersion key handles both new-add and type-collapse cases (verified by tracing UPDATE-then-DELETE-on-same-RID scenario — version increments on every addRecordOperation regardless of new/existing RID). Eager-drive try/catch covered. cacheCodeDepth counter is correct for arbitrarily-nested cache-internal calls. nonCacheableKeys lifecycle now explicit in clear() contract.

**Iterations**: 1 of 3 (PASS — closes 4 second-order issues from Mutation 13 + cross-ref nit; no NEW findings).

## Mutation 15 — 2026-05-25 — content-edit (design.md + plan files)

**Tertiary-order pass.** A re-review after Mutation 14 surfaced 7 tertiary-order issues (T1-T7) — 1 blocker (T1), 1 should-fix (T2), 5 suggestions (T3-T7). All closed.

**T1 (BLOCKER) — ConcurrentModificationException in DeltaBuilder iteration**:
- Root cause: `DeltaBuilder.buildForRecord` iterates `tx.recordOperations.values()` directly. WHERE evaluation may invoke a UDF that calls `session.save(...)`, which mutates `recordOperations` mid-iteration. Java HashMap throws CME on detection.
- Fix: snapshot `new ArrayList<>(tx.recordOperations.values())` at start of build, iterate the snapshot. Records added by UDF-triggered mutations during build are NOT visible in this delta — they are visible to the NEXT view (mutationVersion has advanced).
- Applied in `design.md § TxDeltaCursor` (line 282), `plan/track-4.md` step 4 (with explicit snapshot step), and `plan/track-5.md` `buildForAggregate` description (same hazard).
- Test T4q added (regression).

**T2 (should-fix) — mutationVersion reset must be gated on `txStartCounter == 0`**:
- Root cause: track-1.md said "beginInternal resets mutationVersion = 0" without txStartCounter guard. Nested begin (`txStartCounter > 0`) would zero the version mid-tx, breaking Option C delta sharing.
- Fix: gate both the queryResultCache.clear() AND the mutationVersion=0 reset on `txStartCounter == 0` (outermost begin only).
- Applied in `plan/track-1.md` skeleton deliverables. Test T1f (nested-begin version preservation) added.

**T3 (suggestion) — TxDeltaCursor receives raw vs wrapped refs inconsistently**:
- Root cause: first-build path passed raw `HashSet`/`ArrayList` to TxDeltaCursor; reuse path passed `unmodifiableSet`/`unmodifiableList` wrappers. Latent footgun for future refactors.
- Fix: both paths now return `new TxDeltaCursor(entry.cachedSkipSet, entry.cachedInjectList, 0)` — the wrapped (unmodifiable) refs are used consistently.
- Applied in `design.md § Cross-view delta sharing via mutationVersion` pseudocode.

**T4 (suggestion) — `getMutationVersion()` accessor visibility**:
- Fix: documented as `public long getMutationVersion()` on `FrontendTransactionImpl` concrete class (not on the public `FrontendTransaction` interface). Consumers in `internal/core/tx/cache/*` cast via the existing `DatabaseSessionEmbedded.getTx()` pattern.
- Applied in `plan/track-1.md`.

**T5 (suggestion) — self-healing stale-on-arrival cached delta**:
- Documentation only: explicit invariant in design.md that view-A may write a "stale-on-arrival" pair if a UDF bumps mutationVersion mid-build, and that any subsequent view's version check triggers a rebuild and overwrites — self-healing, no correctness hazard.
- Applied in `design.md § Cross-view delta sharing via mutationVersion → Self-healing version mismatch`.

**T6 (suggestion) — `clear()` is owner-thread-only invariant**:
- Documentation only: explicit invariant that `QueryResultCache.clear()` runs on owning thread only (gated by `assertOnOwningThread` in callers). Future cross-thread cleanup mechanisms MUST null the `queryResultCache` reference instead of calling clear() — to avoid resetting `cacheCodeDepth` mid-iteration on the owner thread.
- Applied in `design.md § Concurrency and lifecycle → "clear() is owner-thread-only"` new subsection.

**T7 (suggestion) — `cacheCodeDepth` increment/check ordering**:
- Root cause: prior wording "While `cacheCodeDepth > 0`, any re-entrant call short-circuits" was ambiguous about whether the check is pre-increment or post-increment.
- Fix: tightened to "**increment FIRST, then check** — if post-increment value `> 1`, this call is re-entrant". Test T7n (aggregate eager-drive + UDF re-entrancy) added.
- Applied in `plan/track-7.md` step 8.

**Mechanical checks**: 0 blockers; 27 should-fix `dsc-ai-tell` em-dash density findings (+1 since Mutation 14, from new prose). Pre-existing house-style debt; deferred to Phase 4 sweep.

**Cold-read** (bounded — § TxDeltaCursor / § Cross-view delta sharing / § "clear() is owner-thread-only" / plan/track-1.md skeleton / plan/track-4.md DeltaBuilder / plan/track-5.md buildForAggregate / plan/track-7.md step 8): self-audited. Snapshot-then-iterate pattern consistent across buildForRecord and buildForAggregate. mutationVersion reset gating cross-referenced with FrontendTransactionImpl.beginInternal:174 (which already gates `localCache.clear()` and storage tx start on `txStartCounter == 0`). cacheCodeDepth increment-then-check ordering documented unambiguously. T6 invariant about clear() does NOT contradict I6 (idempotent clear from cross-thread tx-end paths) because I6 covers the cross-thread `close() → clearUnfinishedChanges() → clear()` chain at tx-end, where cacheCodeDepth is already 0 (no in-flight cache code on the owner thread because the owner has exited). T6 covers a hypothetical FUTURE cross-thread cleanup mechanism that fires WHILE the owner is mid-iteration — that hypothetical path must not call clear().

**Iterations**: 1 of 3 (PASS — closes T1-T7; no NEW findings).

## Mutation 16 — 2026-05-25 — content-edit (design.md + track-1/4/7)

**Architectural optimality pass.** A non-typical review (logical end-to-end + architectural optimality) confirmed the lazy design is architecturally sound and ready to ship for v1. Verdict: ship after 5 small tightening fixes. All 5 applied; 3 deferred items documented as v2 candidates.

**Applied fixes**:

- **A1 (was T7 split — simplified to renumber + thematic scope-doc)**: track-7.md had duplicate "step 8" numbering. The proper split into 7a (correctness) + 7b (memory/concurrency) was assessed but deferred — invasive (cross-ref updates across plan + tests). Pragmatic fix applied: renumbered the duplicate-8 to 8 (re-entrancy guard) and 9 (deterministic-ORDER-BY gate). Thematic boundary preserved by step ordering (correctness steps 1-5 + deterministic ORDER BY at 9 form the "correctness theme"; LRU+overflow+re-entrancy steps 6-8 form the "memory/concurrency theme"). Future v2 split candidate if execute-tracks review-boundary friction surfaces.

- **A2 (cacheCodeDepth bracketing for aggregate eager-drive)**: track-7.md step 8 enumeration of cache-mutating code paths now explicitly includes "the aggregate eager-drive `plan.start(ctx).next(ctx)` between cache.lookup and cache.put on AGGREGATE_* miss". Otherwise UDFs invoked during upstream WHERE eval during populate would not see depth>0 and the re-entrancy guard would silently fail to bypass.

- **A3 (cachedDeltaVersion sentinel)**: track-4.md DeltaBuilder algorithm now explicitly pins `entry.cachedDeltaVersion = -1L` at construction. Default 0L would collide with the first real `tx.mutationVersion=0` check, silently reusing a never-built pair. The `-1L` sentinel is distinguishable from any monotonically-non-negative mutationVersion.

- **A4 (MATCH Etap A — project before sort)**: design.md § TxDeltaCursor procedural step ordering was ambiguous (step 5 said "sort", step 6 said "wrap then sort operates on projected tuples"). Tightened: step 5 is now "wrap via returnProjector" (MATCH Etap A only), step 6 is "sort". ORDER BY on projected columns (e.g., `ORDER BY double_age` where `double_age = u.age * 2`) requires projection before sort because the comparator cannot resolve projected references on raw records.

- **A5 (mutationVersion at end of addRecordOperation)**: track-1.md mutationVersion description now explicitly says "incremented at the END of `addRecordOperation`, inside the success path, after the recordOperations.put completes". End-of-method timing ensures the counter reflects committed state — exceptions mid-method don't advance the version for failed mutations.

**Deferred as v2 candidates** (architectural optimality review acknowledged but recommended deferring):

- cachedRids is now diagnostic-only after the L1 stream-pull-skip-set unification. Could be removed from CachedEntry to save O(p) memory per entry. Defer to v2 cleanup pass — no correctness impact today.
- DeltaBuilder.buildForRecord and buildForAggregate share snapshot + class filter + WHERE eval. A common helper `forEachRelevantOp` could extract this. Defer to v2 when MATCH Etap B forces a 3rd dispatch path.
- AggregateEntry/RecordEntry subclasses of CachedEntry instead of nullable shape-specific fields. Defer to v2 — enum + nullable fields is acceptable for 2 effective shapes.
- Track 7 → Track 7a + 7b split. Defer to v2 if execute-tracks review-boundary friction surfaces; for now, thematic step ordering preserves the boundary informally.

**Architectural verdict** (cold-read summary from the review): lazy merge-on-read is the right choice for v1 given the consumer-facing-contract simplification, even though implementation complexity is comparable to eager. Option C (mutationVersion sharing) is justified (not premature optimization) — Hub's stable-mutationVersion phases mean 1 build amortizes 50-200 reads. L1/L2 stream-pull-skip-set unification is load-bearing and well-designed. No fundamental rework needed; Track 4 implementation can begin.

**Mechanical checks**: 0 blockers; 27 should-fix `dsc-ai-tell` em-dash density (unchanged from Mutation 15 baseline). Pre-existing house-style debt; Phase 4 sweep.

**Cold-read** (bounded — track-7.md step 8 expanded enumeration / track-4.md DeltaBuilder sentinel block / design.md § TxDeltaCursor steps 5-6 / track-1.md mutationVersion timing block): self-audited. The cacheCodeDepth enumeration now correctly brackets the entire cache-miss aggregate populate window (lookup → splice → eager-drive → cache.put). The cachedDeltaVersion sentinel of -1L cannot collide with any real mutationVersion (monotonically increasing from 0). The MATCH Etap A step reordering is procedurally consistent — populate path produces projected tuples (via MATCH planner's normal execution), delta path produces projected tuples (via returnProjector at step 5), both feed the sort at step 6. The end-of-method increment for mutationVersion is consistent with `txStartCounter == 0` reset semantics (T2 fix from Mutation 15) — both gates ensure version reflects only outermost-tx committed state.

**Iterations**: 1 of 3 (PASS — closes 5 tightening fixes; 3 v2 candidates documented; no NEW findings).

## Mutation 17 — 2026-05-25 — structural-rewrite (design.md + implementation-plan.md + plan/track-{4,5,8}.md)

**External-review-driven fixes.** A user-driven design review (this session) surfaced one critical bug, one I6-contract gap, three pre-existing v1 limitations the user explicitly asked be DESIGN-FIXED rather than DOCUMENTED, plus several minor wording inconsistencies. All seven items closed in one coherent pass.

### Critical fix: merge pseudocode bug (G1)

`view.next()` pseudocode in design.md § Stream-pull dispatch unification only pulled from `stream_pull_one()` when BOTH `cache_head == null` AND `delta_head == null`. For the very common case of cache-miss with a non-empty delta and a still-non-exhausted stream (every `query()` that lands on a fresh entry with any tx-mutation on the entry's class), this returned delta-head ahead of not-yet-pulled storage records that should have sorted before delta-head. Output violated the sorted-merge invariant and therefore I4. Confirmed via trace: storage `[A, B, C, D]` + delta `[Z_new]` where `A < Z_new < B` would emit `[Z_new, A, B, C, D]` instead of `[A, Z_new, B, C, D]`.

Fix: rewrote `view.next()` to materialize `cache_head` from the live stream BEFORE consulting `delta_head`. New control flow: `if (cache_head == null && !entry.exhausted) { r = stream_pull_one(); if (r != null) continue; }` ensures every iteration starts with `cache_head` populated whenever the stream still has material to produce. Delta-head is consulted only when `cache_head` is genuinely null (stream truly exhausted) or non-null. The Mermaid sequence diagram at lines 188-208 already encoded the correct behaviour ("stream still has rows → pull"); the pseudocode is now aligned with the Mermaid.

Added test T4r (+ T4r2, T4r3 variants) to `plan/track-4.md` step 7 that exercises the exact failing scenario plus boundary positions (CREATED sorts before all / between / after all cached records).

### I6 contract honesty (K1 from prior review)

§ Concurrency and lifecycle → Idempotent close requirement previously asserted `ExecutionStream.close(ctx)` is idempotent as an ENFORCED requirement. Verification: the `ExecutionStream` interface does NOT mandate idempotency, and not every concrete implementation in `core/.../resultset/` guards against double-close. Rewrote the requirements list to make `CachedEntry.close()` the load-bearing local guard (null-out after first call) and explicitly state that the underlying stream's `close(ctx)` is called at most once by cache code. I6 invariant in § Invariants rewritten symmetrically: idempotency is enforced LOCALLY in `CachedEntry.close()` via the null-out-after-first-call pattern; the interface itself does not mandate this contract. Test re-scoped from "double-close the stream" to "double-close the entry; assert stream sees one close call".

### Design fixes for prior "Known v1 limitations"

User directive: prefer design fixes over documented limitations. Two of three earlier-deferred limitations resolved in-design.

1. **LIMIT-after-DELETE / UPDATED-out short-list — RESOLVED via over-fetch (D10-lazy rewrite).** Earlier wording capped the cache prefix at `skip + limit`, leaving no source to backfill from when a cached record was tx-DELETED or tx-UPDATED-out-of-WHERE. New design: at cache-miss for RECORD shape, walk `SelectExecutionPlan.steps`, mutate `LimitStep.limit` to `maxRecordsPerEntry` and `SkipStep.skip` to 0 (or remove if API requires). The executor produces up to `maxRecordsPerEntry` records; the view applies the original SKIP and LIMIT at iteration via an `emitted` counter. Backfill is always satisfied from the cache itself, bounded by the same memory ceiling that already protected the prior scheme. Pathological deep pagination still overflows into `nonCacheableKeys` per L7. NONE shape is no longer reached on SKIP/LIMIT magnitude grounds. Splice-failure fallback mirrors the aggregate pattern (close partial plan, `QueryCacheMetrics.spliceFailures++`, fall back to `statement.execute(...)`).
2. **MIN/MAX O(n) recompute — RESOLVED via D14 sorted-value index in v1 (D14 promotion).** D14 previously deferred to v2 with a "decision gate D13 measurement" caveat. Promoted to v1 because: (a) under lazy, `applyMutation` fires from `DeltaBuilder.buildForAggregate` on every mutation-version rebuild; a delete-then-rebuild Hub pattern triggers the worst case (O(`maxRecordsPerEntry`) = O(10000)) multiple times per tx; (b) implementation is modest (one TreeMap field, BigDecimal coercion at observe-time, ~50 lines across `observe` / `applyMutation` / `copy`); (c) D13 measurement could not have changed the answer — the index is strictly better regardless of workload. `AggregateState` for MIN/MAX now carries `sortedValues: TreeMap<BigDecimal, Set<RID>>`. `BigDecimal` keys via string round-trip sidestep the cross-`Number`-subtype hazard. The prior `extremumRid` field is eliminated — extremum is `sortedValues.firstKey()` / `lastKey()`. All ops O(log n). Track 5 step 1-3 rewrites cover; tests T5d / T5d2 / T5e updated.
3. **MATCH multi-alias CREATED (Etap B) — re-framed from "v2 candidate" to "separate ADR".** Scope is comparable to the rest of YTDB-820: new shape `MATCH_TUPLE_MULTI`, per-tuple reverse index, dedicated `DeltaBuilder.buildForMatchMulti` that issues constrained pattern walks via `MatchPrefetchStep` + `PREFETCHED_MATCH_ALIAS_PREFIX`, and edge-CREATED dispatch on `addRecordOperation` for edge records. This is not v2-style hardening; it's a separate piece of infrastructure that deserves its own ADR. D13 measures multi-alias-MATCH-CREATED frequency to prioritise the follow-up ADR.

### Asymmetry note (D5 from prior review)

§ Aggregate side-tap → Eager drive on cache-put rewritten to explicitly contrast against RECORD / MATCH-Etap-A lazy-stream-pull. Earlier the reader had to infer from disjoint sections why aggregate populates eagerly while record/match populates lazily. New paragraph names the fundamental semantic difference: per-row Results in RECORD shape are independent (partial cache is correct), aggregate scalar requires every contributor observed (partial cache is silent corruption). The asymmetry is not inconsistency — the cacheability semantics genuinely differ.

### Minor wording / drift fixes

- Line citation `closeActiveQueries() (FrontendTransactionImpl:973)` corrected to `(DatabaseSessionEmbedded.java:3431)` in three loci (§ Pause/resume mechanics, § Concurrency and lifecycle → Idempotent close, § Invariants I6). `clearUnfinishedChanges()` location updated to `FrontendTransactionImpl.java:998`.
- `CachedEntry` class diagram gains explicit `skip: int` and `limit: int` fields (captured at construction, applied by view).
- `CachedResultSetView` class diagram gains `emitted: int` + `skip: int` + `limit: int` fields (view-level SKIP/LIMIT enforcement).
- `AggregateState` class diagram: replace `extremumRid: RID` with `sortedValues: TreeMap` (D14 implementation).
- `ShapeClassifier.classify` RECORD bullet: remove the `n + m <= maxRecordsPerEntry` constraint (no longer needed under over-fetch); update NONE bullet to remove the SKIP-magnitude clause.
- D8-lazy Rationale: "v2 candidate" → "separate ADR" with concrete scope description.
- D13 Rationale: remove "MIN/MAX recompute frequency (informs D14 decision)" measurement bullet (D14 now in v1); replace with multi-alias-MATCH-CREATED frequency + paginated-workload share + over-fetch waste ratio. track-8.md HubReplay scenario measurement list updated symmetrically.
- § Open questions deferred to execution TL;DR rewritten: now only MATCH Etap B is deferred; D14 and D10-lazy explicitly listed as resolved in v1.

### Files touched

- `design.md` — § Known v1 limitations rewritten (3 bullets → 1), § Stream-pull dispatch unification pseudocode rewrite, new § Over-fetch for backfill, new § Sorted-value index for MIN/MAX, § Aggregate side-tap → Eager drive on cache-put paragraph rewritten, § Idempotent close requirement re-scoped, § Invariants I6 re-scoped, class-diagram fields (CachedEntry, AggregateState, CachedResultSetView) updated, three line-citation drift fixes, § Open questions deferred TL;DR rewritten, ShapeClassifier RECORD/NONE bullet updates.
- `implementation-plan.md` — D10-lazy rewritten (over-fetch), D14 rewritten (in v1), D13 measurement list updated, D8-lazy "v2 candidate" → "separate ADR", Non-Goals SKIP-cap bullet removed + canonical-CacheKey v2 bullet added + MATCH Etap B re-framed to "separate ADR", Track 5 description updated for D14 scope, Component Map AggregateState bullet updated.
- `plan/track-4.md` — Concrete deliverables gain plan-rewrite for over-fetch step, Plan-of-Work step 5 explicitly calls out the materialise-cache-head invariant, T4f description updated, T4h replaced with deep-pagination over-fetch test, new T4r / T4r2 / T4r3 regression tests for the merge pseudocode bug.
- `plan/track-5.md` — Step 1 expanded to cover D14 sorted-value index population, Step 2 MIN/MAX dispatch rewritten for sortedValues TreeMap operations, Step 3 copy() semantics include sortedValues deep-copy, T5d expanded to verify O(log n) characteristic, new T5d2 BigDecimal coercion regression test.
- `plan/track-8.md` — D13 HubReplay measurement list updated.

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=structural-rewrite): pending validation. Expected: 0 blockers (Mutation 9 closed the TL;DR / references-footer baseline; the new content uses consistent structure). The 27 should-fix `dsc-ai-tell` em-dash density findings carried forward from Mutation 16 are likely to grow by ~5-10 (new prose adds em-dashes — over-fetch and sorted-value-index sections are dense). Pre-existing house-style debt; Phase 4 sweep.

**Cold-read** (scope=bounded — § Stream-pull dispatch unification pseudocode + § Over-fetch + § Sorted-value index + § Eager drive paragraph + § Invariants I6 + plan/track-4.md step 5 + step 7 T4r + plan/track-5.md steps 1-3 + T5d): self-audited. The merge pseudocode's new `cache_head == null && !entry.exhausted` branch correctly composes with the existing `deltaCursor.shouldSkip(cache_head.rid)` skip check (re-loop re-evaluates skip check on newly-materialized cache_head). Over-fetch + view-level SKIP/LIMIT interacts cleanly with the merge: the emitted counter advances only on user-visible emits, not on skipped or stream-pulled-but-skipped records. D14 sorted-value index `copy()` deep-copies both the TreeMap and the bucket Sets — view-level mutation cannot leak back to the entry. AggregateState empty-set semantics (L3 fix) compose with sortedValues: `sortedValues.isEmpty()` → `currentScalar = null` for MIN/MAX, consistent with prior wording. Mermaid sequence diagram (lines 188-208) and the new pseudocode now describe the same algorithm.

**Findings**:
- Critical bug closed (G1).
- I6 contract honesty closed.
- Two prior v1 limitations resolved in-design (over-fetch, D14).
- MATCH Etap B re-framed to "separate ADR" rather than "v2 candidate" to honestly reflect scope.
- Minor wording / drift fixes (~6 small edits).
- (pre-existing, NOT addressed in this mutation): 27+ should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — house-style debt, Phase 4 sweep.

**Iterations**: 1 of 3 (PASS — large structural rewrite; no NEW correctness findings introduced; pre-existing house-style debt acknowledged).

## Mutation 18 — 2026-05-25 — content-edit (design.md + implementation-plan.md + plan/track-{3,4,5,8}.md)

**Honest-accounting walk-back of D14 + correctness fixes from re-reading Mutation 17.** User pushed back on the D14 v1 promotion with "is this memory really worth it?"; honest cost-benefit analysis showed it isn't. Walked back. Re-reading the over-fetch design exposed a `LIMIT > maxRecordsPerEntry` correctness gap. Re-checking the I6 fix from Mutation 17 exposed that it only handled the same-caller double-close, not the cross-caller scenario (closeActiveQueries + cache.clear).

### Walk-back: D14 MIN/MAX sorted-value index reverts to v2-deferred

Cost-benefit analysis exposed that Mutation 17's "promote D14 to v1" was overeager. Real numbers for Hub-typical workloads:
- 5-20 MIN/MAX queries per HTTP request × 100-1000 contributors × 1-5 mutations × ~1/n extremum-hit rate ≈ ~500 ops worst case per request.
- At 10 ns/op: ~5 μs per request. Against typical hundreds-of-ms response time: not observable.
- Memory cost of the index: ~3× growth per MIN/MAX entry (TreeMap + per-value Set buckets + BigDecimal storage). Hub typical: ~70 KB extra per tx. Pathological: ~80 MB.

The "BigDecimal coercion fixes a real correctness footgun" framing from Mutation 17 was also wrong — the v1 baseline uses RID identity (`rid.equals(extremumRid)`), not numeric equality, so the Long.equals(Integer) hazard is structurally unreachable in v1. D14 was pure perf optimization for a workload that doesn't visibly benefit.

D13 measurement was the correct gate all along. Reverted:
- `design.md` § Known v1 limitations: MIN/MAX bullet re-added as a v1 limitation (worst-case O(n) recompute when extremum leaves, bounded by maxRecordsPerEntry); D14 framed as v2-deferred, measurement-gated.
- `design.md` § Aggregate delta: § Sorted-value index for MIN/MAX section removed entirely. AggregateState class-diagram field reverted from `sortedValues: TreeMap` to `extremumRid: RID`. MIN/MAX recompute edge-case bullet restored to "worst case O(n)" wording.
- `design.md` § Open questions deferred: D14 listed alongside Etap B as a deferred candidate, with explicit cost-benefit narrative.
- `implementation-plan.md` D14: rewritten back to "v2-deferred, gated on D13 measurement"; rationale documents the Hub-typical cost-benefit calculation that justifies the deferral.
- `implementation-plan.md` D13: measurement list restores "MIN/MAX extremum-churn frequency".
- `implementation-plan.md` Component Map: AggregateState bullet reverts to `extremumRid` field; mentions D14 as v2-deferred.
- `implementation-plan.md` Track 5 description: reverts scope to ~5 steps, MIN/MAX dispatch returns to O(n) recompute path.
- `plan/track-5.md` steps 1-3 + T5d/T5e tests: revert to RID-identity tracking + O(n) recompute (T5d / T5e verify both fast paths and recompute path).
- `plan/track-8.md`: D13 HubReplay measurement list restores MIN/MAX extremum-churn frequency.

Discipline lesson logged: D13 measurement was DESIGNED as the gate for D14. Mutation 17 disrespected that by promoting without data; Mutation 18 restores the gate.

### Correctness fix: LIMIT > maxRecordsPerEntry constraint

Mutation 17's over-fetch design said *"`SKIP` and `LIMIT` are ALWAYS cacheable regardless of magnitude"*. That's wrong. Trace:
- User writes `SELECT FROM User LIMIT 50000` against maxRecordsPerEntry=10000.
- Cache rewrites LimitStep.limit to 10000.
- Executor produces 10000 rows.
- View applies original LIMIT 50000 — but cache only has 10000 rows.
- User wanted 50000, got 10000. **Silent truncation.**

Same for `SKIP n LIMIT m` with `n + m > maxRecordsPerEntry`. Fix:
- `design.md` § Per-shape classify: RECORD bullet now states the constraint explicitly — `LIMIT m` cacheable iff `m <= maxRecordsPerEntry`; `SKIP n LIMIT m` cacheable iff `n + m <= maxRecordsPerEntry`. Above the cap → NONE.
- NONE bullet gains explicit `LIMIT > maxRecordsPerEntry` and `SKIP + LIMIT > maxRecordsPerEntry` clauses.
- `design.md` § Over-fetch for backfill: rewritten to make the gate explicit. The mechanism applies only to LIMIT-bounded queries within the cap. No-LIMIT queries are not rewritten (executor produces all matching rows; cache appends up to the cap; overflow handling kicks in if exceeded). Above-cap queries bypass the cache entirely.
- `implementation-plan.md` D10-lazy: rewritten with the correct gate, including a Risks/Caveats bullet noting that backfill capacity scales inversely with `LIMIT / maxRecordsPerEntry`.
- `plan/track-4.md` Concrete deliverables: ShapeClassifier rule updated with the cap constraint. Plan-rewrite step is gated on the query having a `LIMIT m` with `m <= cap` (no-LIMIT queries skip the rewrite).
- `plan/track-4.md` test matrix: T4h reframed as "deep pagination within cap" (within-bound positive test). T4h2 added for LIMIT-above-cap bypass. T4h3 for SKIP+LIMIT-above-cap bypass. T4h4 for no-LIMIT natural overflow.

### Correctness fix: I6 cross-caller double-close

Mutation 17 framed I6 as "ExecutionStream idempotency enforced LOCALLY in CachedEntry.close() via null-out". This handles the case where the cache calls close twice on its own (same-caller double-close), but NOT the cross-caller scenario:
- Pool-shutdown ordering: `closeActiveQueries()` runs BEFORE `clearUnfinishedChanges()` (which fires `cache.clear()`).
- For any cache entry whose paired `LocalResultSet` is still alive in `activeQueries` (not yet GC'd), the underlying stream sees TWO close calls: one from `LocalResultSet.close()` via `closeActiveQueries()`, one from `entry.close()` via `cache.clear()`.
- `ExecutionStream` interface does NOT mandate idempotency. Concrete impls vary; some throw on second close.
- The cache's local null-out doesn't help here — the LocalResultSet's close path is outside the cache's control.

Fix: introduce `IdempotentExecutionStream` wrapper class. Cache wraps every stream at cache-put time and substitutes the wrapper into BOTH `entry.stream` AND the paired `LocalResultSet`'s stream slot. Both close paths now reach the SAME wrapper instance; whichever fires first calls the underlying close once; the other hits the no-op branch.
- `design.md` § Concurrency and lifecycle → Idempotent close requirement: prose rewritten to make the cross-caller scenario explicit, describe the wrapper, and re-enumerate the ENFORCED requirements list around the wrapper.
- `design.md` § Invariants I6: rewritten to specify that wrapper is the load-bearing mechanism, not interface-level idempotency.
- `implementation-plan.md` Component Map: new bullet for `IdempotentExecutionStream`.
- `implementation-plan.md` I6: rewritten symmetric with design.md, references T3f as the cross-caller regression test.
- `plan/track-3.md` Concrete deliverables: `IdempotentExecutionStream` listed as a new file. Plan-of-Work step 1 implements it, step 2 wires it into both `entry.stream` and the LocalResultSet substitution at cache-put. Library signatures section updated.
- `plan/track-3.md` T3 test set: T3e re-scoped to single-caller; T3f added for cross-caller with non-idempotent underlying mock. Acknowledges this is the KEY test the wrapper exists to defend against.

### Files touched

- `design.md` — Known v1 limitations bullets, AggregateState class-diagram field, MIN/MAX edge-case bullet, Sorted-value index section deletion, Per-shape classify RECORD/NONE bullets, Over-fetch for backfill section rewrite, Open questions deferred TL;DR + bullets, Aggregate delta references footer, I6 invariant prose, Idempotent close requirement prose.
- `implementation-plan.md` — Component Map AggregateState + new IdempotentExecutionStream bullet, D10-lazy rewrite with cap gate, D13 measurement list restores MIN/MAX, D14 reverts to v2-deferred, I6 invariant rewrite, Track 5 description.
- `plan/track-3.md` — Concrete deliverables (wrapper + substitution), Plan-of-Work step 1-2 (implement + wire wrapper), T3e/T3f test scope split, In-scope files (+IdempotentExecutionStream.java), Library signatures (wrapper constructor + close).
- `plan/track-4.md` — Concrete deliverables (ShapeClassifier cap gate + conditional plan rewrite), test matrix T4h reframed, T4h2 / T4h3 / T4h4 added.
- `plan/track-5.md` — Steps 1-3 reverted to RID-identity tracking + O(n) recompute path; T5d / T5e reverted to verify both paths.
- `plan/track-8.md` — D13 HubReplay measurement list restores MIN/MAX extremum-churn frequency.

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=structural-rewrite): 0 blockers. Pre-existing 27+ should-fix `dsc-ai-tell` em-dash density / fragmented-header findings carried forward; deferred to Phase 4 sweep.

**Cold-read** (scope=bounded — § Known v1 limitations, § Per-shape classify, § Over-fetch for backfill, § Aggregate delta + side-tap, § Idempotent close requirement, § Invariants I6, § Open questions deferred + bullets, plan/track-3.md steps 1-2 + T3f, plan/track-4.md ShapeClassifier + T4h-T4h4, plan/track-5.md steps 1-3 + T5d/T5e): self-audited. The D14 walk-back is internally consistent — all references to "sorted-value index in v1" replaced with "v2-deferred, measurement-gated"; the v1 baseline (extremumRid + O(n) recompute) is correctly described in design.md, implementation-plan.md, and track-5.md. The LIMIT-cap gate is consistent across design.md, implementation-plan.md, and track-4.md, with matching test coverage (T4h-T4h4 covers within-cap, above-cap LIMIT, above-cap SKIP+LIMIT, no-LIMIT overflow). The IdempotentExecutionStream wrapper design is consistent across design.md (wrapper described in Idempotent close requirement), implementation-plan.md (Component Map + I6), and track-3.md (steps 1-2 implementation + T3f cross-caller test). The wrapper construction reaches into LocalResultSet to substitute — track-3.md acknowledges this implementation detail and provides the alternative path if LocalResultSet's stream field is final.

**Findings**:
- D14 walk-back (cost-benefit discipline restored).
- LIMIT > maxRecordsPerEntry correctness gap closed.
- I6 cross-caller scenario closed via wrapper.
- (pre-existing, NOT addressed): 27+ should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — Phase 4 sweep.

**Iterations**: 1 of 3 (PASS — corrections-pass over Mutation 17; no NEW correctness findings introduced).

## Mutation 19 — 2026-05-25 — structural-rewrite (design.md + implementation-plan.md + plan/track-{2,6}.md)

**Coverage-expansion pass after user review.** User asked: (1) are any v2-deferred items worth promoting now? (2) MATCH multi-alias scope — was Etap B in the eager design, how much benchmark workload would it cover? Honest analysis showed two items deserve v1 promotion: canonical CacheKey stripping SKIP (paginated workloads share entries), and partial MATCH Etap B (DELETED + UPDATED for multi-alias MATCH via reverseIndex; CREATED tombstones the entry). Eager design had multi-alias MATCH (`MergeKind.MATCH_TUPLE`) for DELETED + UPDATED; the lazy pivot dropped it as a side-effect of architectural simplification rather than an intentional cost-benefit call. Restoring coverage reuses the eager-era bookkeeping at modest cost.

### D16 new: Canonical CacheKey strips SKIP

`CacheKey.equals` and `hashCode` ignore `SQLSelectStatement.skip` (and `SQLMatchStatement.skip`) so paginated queries — `SELECT FROM Issue ORDER BY priority SKIP 0 LIMIT 20`, `SKIP 20`, `SKIP 40`, ... — share ONE cache entry. The view applies SKIP at iteration time (over-fetch mechanism from D10-lazy already removes SKIP from the plan, so the entry has no SKIP-specific data baked in; stripping from the key is the natural completion). LIMIT is NOT stripped because doing so introduces a silent-short-list hazard: a no-LIMIT query meeting a LIMIT-bounded over-fetched entry whose stream didn't exhaust would return cap rows when the user wanted all. Stripping both LIMIT and SKIP is v2-deferred with entry.exhausted tracking at lookup.

Files touched:
- `design.md § Cache key composition` — TL;DR rewritten to note SKIP-stripping; new subsection § Canonical key for SKIP (D16) describing the equals/hashCode field-by-field walk, the LIMIT-not-stripped rationale, and the trade-off.
- `implementation-plan.md` — new D16 D-record after D15 with full alternatives / rationale / risks-caveats / implementation-pointer; Non-Goals entry about canonical-CacheKey-stripping-both reworded to reflect "v1 strips SKIP only".
- `plan/track-2.md` — Concrete deliverables note custom equals + hashCode stripping SKIP; Plan-of-Work step 1 expanded with the field-by-field walk specification for SQLSelectStatement and SQLMatchStatement; new tests T2f (paginated workload share), T2g (different LIMIT not canonical), T2h (MATCH SKIP stripping); library signatures section updated.

### D8-lazy rewrite: Partial Etap B promoted to v1

`MATCH_TUPLE_MULTI` is a new `CacheableShape` enum value for multi-alias MATCH (pattern with edges OR cross-join with multiple top-level match-expressions). Classify gates: every pattern node has `class:`, no LET / UNWIND, no cross-alias-state in pattern WHEREs, no subqueries in pattern WHEREs, `n + m <= maxRecordsPerEntry`.

`CachedEntry` for MATCH_TUPLE_MULTI carries:
- `aliasClasses: Map<String, Set<String>>` — per-alias subclass closure (D11 symmetry)
- `aliasWheres: Map<String, SQLWhereClause>` — per-alias WHERE
- `contributingRids: Map<Integer, Set<RID>>` — per-tuple-index, set of RIDs across all alias bindings
- `reverseIndex: Map<RID, Set<Integer>>` — inverse: per-RID, set of tuple-indices that reference it
- `tombstoned: boolean` — set at delta-build pre-scan when a CREATED hits a class in `effectiveFromClasses`; forces evict + miss at lookup

`DeltaBuilder.buildForMatchMulti(entry, recordOps, ctx)` is a new method returning `MatchMultiDelta` or TOMBSTONE sentinel. Two-pass algorithm:
1. Pre-scan for CREATED on a class in `effectiveFromClasses` — if found, tombstone + TOMBSTONE return.
2. Iterate ops for DELETED + UPDATED. DELETED: `reverseIndex.get(rid)` → affected tuples → `tupleSkipSet.addAll`; also `ridSkipSet.add(rid)`. UPDATED: for each affected tuple, find binding aliases (via aliasClasses), re-evaluate each `aliasWheres[alias].matchesFilters(post-update record, ctx)`; if any alias's WHERE fails, drop the tuple via `tupleSkipSet.add(tupleIndex)`. Also add to `ridSkipSet` to suppress stream-pull-append re-emission.

`MatchMultiDelta` is a new immutable per-view delta type: `tupleSkipSet: Set<Integer>` (cache-cursor skip by tuple-index) + `ridSkipSet: Set<RID>` (stream-pull-append skip when ANY alias's RID is in this set, drop the tuple). No injectList — partial Etap B does not discover new tuples on CREATED (separate ADR for that work).

`QueryResultCache.lookup` for MATCH_TUPLE_MULTI invokes the DeltaBuilder; on TOMBSTONE: evict + return null (miss); else cache the `MatchMultiDelta` per Option C sharing.

`CachedResultSetView` MATCH_TUPLE_MULTI branch: iterate `entry.results`, skip tuples whose index is in `tupleSkipSet`, on stream-pull check `ridSkipSet` against each alias binding's RID (drop tuple if any in set), populate `reverseIndex` + `contributingRids` for newly appended tuples.

**What's covered (in v1)**: every DELETED + UPDATED scenario for multi-alias MATCH. Issue↔Project, User↔Team, Comment↔Issue traversal patterns — common in Hub. Hub's "save then list refresh" pattern with multi-alias MATCH now cache-hits instead of full re-execute.

**What's deferred to separate ADR**: CREATED-discovery via constrained pattern walk (MatchPrefetchStep + PREFETCHED_MATCH_ALIAS_PREFIX) + edge-CREATED dispatch hook. Partial Etap B handles CREATED by tombstoning the entry — restores eager-design parity (eager wiped on CREATED multi-alias too).

Files touched:
- `design.md` — Per-shape classify list expanded with MATCH_TUPLE_MULTI bullet; NONE bullet refined; new § MATCH multi-alias (partial Etap B in v1) section between § MATCH Etap A and § Over-fetch for backfill describing the entry fields, population walker, DeltaBuilder.buildForMatchMulti algorithm with pseudocode, view iteration, tombstone handling, coverage scope, and v1-rationale anchor; class diagram extended with new fields on CachedEntry, new MATCH_TUPLE_MULTI enum value, new MatchMultiDelta class, new buildForMatchMulti method on DeltaBuilder, new matchMultiDelta field on CachedResultSetView; class-diagram dependencies updated.
- `implementation-plan.md` — D8-lazy rewritten end-to-end with Etap A + partial Etap B + separate-ADR Etap B framing; Component Map bullets for CachedEntry / CacheableShape / TxDeltaCursor / DeltaBuilder / CachedResultSetView extended with MATCH_TUPLE_MULTI-related fields; new MatchMultiDelta bullet added between TxDeltaCursor and DeltaBuilder; Track 6 description expanded from ~4 steps to ~10 steps with the partial Etap B scope.
- `plan/track-6.md` — full rewrite. Purpose / Big Picture covers both Etap A and partial Etap B; Context and Orientation lists the new entry fields and new files; Concrete deliverables split into Etap A (4 items, retained) and partial Etap B (7 items, new); Plan of Work step 1-4 unchanged (Etap A), step 5-9 new (MATCH_TUPLE_MULTI classify, DeltaBuilder.buildForMatchMulti algorithm with pseudocode pre-scan + tupleSkipSet/ridSkipSet build, lookup tombstone handling, view MATCH_TUPLE_MULTI branch, reverseIndex population in stream-pull-append); test matrix expanded from 8 tests (T6a-h) to 16 tests (T6a-p) covering classify pass / classify NONE (classless / subquery), partial Etap B DELETED, UPDATED-still-passes, UPDATED-fails, CREATED tombstone, multi-alias-same-class self-loop, cross-join CREATED tombstone, stream-pull-append RID skip, Option C delta sharing.

### Cross-references

- **D8-lazy + D10-lazy + D11 + D16 interaction**: MATCH_TUPLE_MULTI uses `effectiveFromClasses` (D11 closure) for the tombstone pre-scan and class-filter; uses the same over-fetch mechanism (D10-lazy) for SKIP/LIMIT bounded queries (rewrite SkipStep + LimitStep when in cap); is canonical-keyed under D16's SKIP-stripping equals so paginated multi-alias MATCH queries share entries.
- **I7 contract**: extended to cover MATCH_TUPLE_MULTI — view's `matchMultiDelta` (tupleSkipSet + ridSkipSet) is immutable post-construction; subsequent mutations don't affect the current view. Fresh `query()` constructs a new view with a fresh delta or hits the tombstone re-execution path.

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=structural-rewrite): pending validation. Pre-existing 27+ should-fix `dsc-ai-tell` em-dash density findings carried forward + likely additions from the new Etap B + canonical-key prose. Deferred to Phase 4 sweep.

**Cold-read** (scope=bounded — § Cache key composition + § Canonical key for SKIP, § Per-shape classify, § MATCH multi-alias (partial Etap B in v1), § Class Design diagram, D8-lazy, D16, Component Map MATCH_TUPLE_MULTI entries, plan/track-2.md custom equals + T2f-h, plan/track-6.md full track): self-audited. CacheKey SKIP-stripping is internally consistent across design.md / implementation-plan.md / track-2.md (omitted field list matches in all three loci; T2f/T2g/T2h cover the three relevant scenarios). MATCH_TUPLE_MULTI design is consistent across design.md (§ MATCH multi-alias documents the algorithm in prose), implementation-plan.md (D8-lazy explains the rationale + risks; Component Map bullets describe the data structures), and plan/track-6.md (Plan of Work has executable pseudocode for buildForMatchMulti; test matrix covers the dispatch table cells). Class diagram updates align with prose: new shape value, new fields on CachedEntry, new MatchMultiDelta class, new buildForMatchMulti method, new field on view. Tombstone semantics correctly described as "evict + miss + force re-execute" in all loci; the Option C delta sharing for MATCH_TUPLE_MULTI mirrors the RECORD-shape pattern.

**Findings**:
- D16 canonical CacheKey added (paginated workload share).
- Partial MATCH Etap B restored (DELETED + UPDATED for multi-alias via reverseIndex; CREATED tombstones).
- (pre-existing, NOT addressed): 27+ should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — Phase 4 sweep.

**Iterations**: 1 of 3 (PASS — coverage-expansion structural rewrite; no NEW correctness findings introduced).

## Mutation 20 — 2026-05-26 — structural-rewrite (design.md + implementation-plan.md + plan/track-{1,4,8}.md)

**User-driven correctness coverage extension.** Conversation surfaced a real gap: over-fetch (D10-lazy) treats one cap (`maxRecordsPerEntry = 10000`) as if it always behaves laziness-friendly. Streaming plans (no `OrderByStep` in the chain, or ORDER BY backed by an index) do use the cap lazily — `LimitStep.limit = 10000` is a capacity ceiling and the executor pulls from storage on demand. But blocking-sort plans (`OrderByStep` present — full sort with no backing index) drain the entire matching set into the sort buffer on the first `next()` call regardless of downstream `LimitStep`, then the cache retains the prefix up to the cap. A single one-shot `SELECT ... ORDER BY priority LIMIT 20` against a low-selectivity WHERE matching thousands of rows would therefore park up to 9980 unused sorted Result refs in `entry.results` for every such blocking-sort entry. Memory bound stated as `200 × 10000 = 2M refs` is the streaming-only number; once blocking-sort entries enter the mix the eager retention is the real shape.

Adopted **Opcja A — per-plan-shape cap**: detect `OrderByStep` once during the existing `plan.steps` walk (already iterated to locate `LimitStep` / `SkipStep`), set `effectiveCap = planHasOrderByStep ? maxRecordsPerEntryForBlockingSort : maxRecordsPerEntry`, rewrite `LimitStep.limit = effectiveCap`. Default `maxRecordsPerEntryForBlockingSort = 500` (hot-tunable). Blocking-sort queries whose `LIMIT m` exceeds the smaller cap fall back via the existing splice-failure path with a new `QueryCacheMetrics.blockingSortOverCap` counter. AST classify keeps using `maxRecordsPerEntry` as the upper bound — it cannot predict plan shape pre-build — so the late discovery + fallback is the deliberate trade-off vs adding schema/index inspection to classify.

Rejected alternatives recorded in D17: single cap baseline (the memory waste case), selectivity-aware classify (DB calls in hot path, fuzzy cutoff), top-K heap inside `OrderByStep` (executor-level change, valuable but separable and out of v1 scope).

### D17 new: Per-plan-shape over-fetch cap

`QueryResultCache` gains a fourth `GlobalConfiguration` knob `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY_FOR_BLOCKING_SORT` (Integer, default 500). `DatabaseSessionEmbedded.query()` cache-miss path's plan-rewrite step does one pass over `plan.steps` to find `LimitStep` / `SkipStep` AND to detect any `OrderByStep` presence; the dual-cap selection produces `effectiveCap`; the plan is rewritten to `LimitStep.limit = effectiveCap`, `SkipStep.skip = 0`. Blocking-sort plans with `LIMIT m > maxRecordsPerEntryForBlockingSort` route to the splice-failure fallback shape (close plan, increment `blockingSortOverCap`, `statement.execute(...)`, `LocalResultSet` to consumer, key into `nonCacheableKeys`). Streaming case is unchanged.

Files touched:
- `design.md` — § Overview knob count "Two" → "Three" + new knob named (parenthetical D17 ref dropped per cold-read finding); § Class Design classDiagram new `-maxRecordsPerEntryForBlockingSort: int` field on QueryResultCache; § Per-shape classify RECORD bullet rewritten to describe two-stage gate (AST classify uses upper bound, plan-rewrite picks effectiveCap, over-cap blocking-sort falls back); § Over-fetch for backfill → Mechanism step list now references `effectiveCap` instead of `maxRecordsPerEntry` unconditionally; new subsection `### Per-plan-shape cap (streaming vs blocking-sort)` (~30 lines) added between Mechanism and View-level SKIP/LIMIT with detection algorithm, dual cap mechanism, blocking-sort over-cap fallback, default-500 rationale; § Lazy merge-on-read → Edge cases / Gotchas new bullet explaining streaming-lazy vs blocking-sort-eager asymmetry; § Lazy merge-on-read References footer adds D17; § Memory bounds TL;DR rewrites three-knob list with effectiveCap-aware formula and References footer adds D17.
- `implementation-plan.md` — Constraints Memory bounded bullet now says "Three knobs" with reference to D17 motivation; Component Map GlobalConfiguration bullet enumerates four knobs; new D17 D-record (~25 lines) placed between D16 and Invariants with Alternatives-considered / Rationale / Risks-Caveats / Implemented-in / Full-design pointer.
- `plan/track-1.md` — "three knobs" → "four knobs" in BLUF, Context-and-Orientation, Concrete-deliverables, Plan-of-Work step 1; new knob listed with default and D17 cross-ref.
- `plan/track-4.md` — Plan-rewrite-for-over-fetch deliverable rewritten to include OrderByStep detection in the same `plan.steps` walk, `effectiveCap` selection, and blocking-sort over-cap fallback semantics; new tests T4h5 (streaming uses 10000), T4h6 (blocking-sort uses 500), T4h7 (blocking-sort over-cap fallback to statement.execute + nonCacheableKeys), T4h8 (blocking-sort within cap still caches).
- `plan/track-8.md` — `QueryCacheMetrics` counter list extended with `spliceFailures` (carry-over from L6) and `blockingSortOverCap` (D17 fallback); D13 Hub-replay measurement list extended with blocking-sort-cap statistics (fraction of cached queries that are blocking-sort, LIMIT distribution within that subset, `blockingSortOverCap` fire rate — informs whether default 500 is right).

### Cross-references

- **D10-lazy + D17 interaction**: D10-lazy describes over-fetch's mechanism; D17 refines the cap selection. The combined picture: AST classify gates on `maxRecordsPerEntry` (upper bound); plan-rewrite walks once to find SkipStep/LimitStep AND detect OrderByStep; chooses `effectiveCap`; rewrites the plan accordingly. Both D-records cite the same `§ Over-fetch for backfill` subsection in design.md but at different levels of detail.
- **I4 contract**: streaming AND blocking-sort over-fetch both produce correct results regardless of cap (the cap governs cache memory, not correctness); blocking-sort over-cap fallback to `statement.execute(...)` preserves I4 by routing the user's full LIMIT through the uncached path.
- **D13 gate**: extended measurement list now includes blocking-sort-cap statistics. If default 500 is too low (frequent `blockingSortOverCap` fires) or too high (low-selectivity blocking-sort entries dominate memory), v1.1 hardening adjusts the default before / after merge.

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=structural-rewrite): iteration 1 found 2 should-fix (1 pre-existing `Lazy merge-on-read` section length 337/300 lines deferred to mechanics-split / Phase 4 sweep; 1 pre-existing `(per D11)` parenthetical D-aside at line 492 — auto-fixed by rewording). Iteration 2 PASS: 0 blockers, 1 pre-existing should-fix (length cap; carries forward as known debt).

**Cold-read** (scope=whole-doc): iteration 1 found 2 should-fix parenthetical D-asides introduced by the mutation (`(D17)` at line 11 Overview; `(default 500, per D17)` at line 713 Memory bounds TL;DR). Both fixed by removing the parenthetical refs — D17 is named in the References footers of both sections, so the body prose doesn't need the inline citation. Iteration 2 implicit re-verification: grep confirms D17 mentions are only in References footers at lines 644 and 726. Two non-blocking suggestions deferred: (a) long em-dash-chained sentence at line 599 in Per-plan-shape cap blocking-sort bullet — readability polish, not a rule violation; (b) inline splice-failure-fallback procedure at line 615 duplicates the shape described in § Aggregate side-tap — could be back-referenced for DRY.

**Findings**:
- D17 added: per-plan-shape over-fetch cap; streaming case uses `maxRecordsPerEntry=10000`, blocking-sort case uses `maxRecordsPerEntryForBlockingSort=500`.
- `QueryCacheMetrics.blockingSortOverCap` counter added for the new fallback path.
- D13 measurement list extended to inform default-500 tunability.
- (pre-existing, NOT addressed): `Lazy merge-on-read` section length 337/300 — deferred to mechanics-split / Phase 4 sweep. The new Per-plan-shape cap subsection contributes ~30 lines to the existing over-cap; this mutation pushes deeper into structural debt that mechanics split or content factoring will resolve.
- (pre-existing, NOT addressed): 27+ should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — Phase 4 sweep.

**Iterations**: 2 of 3 (PASS — D17 introduced cleanly; 2 mutation-introduced cold-read findings closed in iteration 2; pre-existing length-cap + em-dash debt carries forward).

## Mutation 21 — 2026-05-26 — structural-rewrite (design.md + implementation-plan.md + plan/track-{1,4,8}.md)

**User-driven cache coverage extension.** Conversation surfaced that the prior `NONE` classification eliminated cache benefit entirely for any shape the lazy delta-builder cannot reconcile (GROUP BY, LET, $matched / $depth / $current, subqueries, MATCH patterns without `class:` on every node). The previous reasoning conflated "delta-build cannot reconcile mutations" with "cannot cache" — but for pure-read tx (no mutations) reconciliation is irrelevant; the cached result remains correct trivially. LDBC SNB analysis showed 19 of 20 queries fall into NONE under prior rules; warm-tx benefit limited to `IS4` (5% coverage). Read-mostly workloads (Hub page rendering with sparse writes, analytical batches) leave significant cache value on the table.

Adopted **D18 — K0-version-fallback for NONE shapes**: split the prior unified `NONE` into `K0_NONE` (delta-unreconcilable but deterministically reproducible — cacheable under a `tx.mutationVersion` gate) and `HARD_NONE` (structurally unfit for any cache path — never cached). K0_NONE entries stamp `populateMutationVersion = tx.mutationVersion` at populate; lookups compare against `tx.getMutationVersion()` and serve cache hits while versions match. On divergence the entry invalidates; after `k0NoneInvalidationThreshold` (default 3) invalidations of the same key the cache key joins `nonCacheableKeys` to bound write-heavy tx churn. Cacheable shapes (RECORD / AGGREGATE_* / MATCH_TUPLE_MULTI) continue to use the lazy delta-build path unchanged — `populateMutationVersion` is unused on those entries.

Coverage delta against LDBC warm-tx: pre-D18 = 5% (IS4 only). Post-D18 = 100% (every query benefits from cache hit on pure-read repeat, with version-gate handling correctness for any future mutation).

Also added two sub-statement caching mechanisms as **separate-ADR future work** (not v1 scope, not v2 hardening — fundamentally different integration layer at executor-step level rather than `DatabaseSessionEmbedded.query()`):
- **LET sub-expression cache** — synthesize standalone SELECT after binding substitution of `$parent.$current.X`, cache by `(synthesized AST, params)`; hits when outer query repeats or same binding RID appears across outer rows. Real benefit for LDBC IC1 / IC10-style per-friend correlated subqueries.
- **$matched binding cache** — same synthesis-after-binding mechanism applied to `$matched.<alias>.<field>` patterns; per-binding memoization with cost-aware admission (cache.lookup overhead must beat sub-execution cost; cheap edge checks net-negative, expensive sub-patterns net-positive).

Both ideas share the synthesis-after-binding mechanism and would land in one ADR; scope comparable to YTDB-820 itself. D13 Hub-replay measures LET / $matched frequency to prioritise.

### D18 new: K0-version-fallback for NONE shapes

`CacheableShape` enum extended with two distinct buckets where there was one. K0_NONE captures the cases where the delta-builder doesn't have a reconcile path (GROUP BY, LET, expression-aggregates, subqueries in WHERE/target, MATCH cross-alias-state / classless-nodes / subquery-in-pattern-WHERE / LET-or-UNWIND-in-MATCH-scope, aggregate over expression) but the query result is deterministically reproducible from storage state. HARD_NONE captures cap-violators only (`LIMIT > maxRecordsPerEntry`, `SKIP + LIMIT > maxRecordsPerEntry`, blocking-sort `LIMIT > maxRecordsPerEntryForBlockingSort` post-plan-rewrite) — these cannot fit the cache's memory model regardless of mutation state. Non-deterministic queries are intercepted by `NonDeterministicQueryDetector` before classify and never enter any `CacheableShape` bucket.

`CachedEntry` gains `populateMutationVersion: long` (stamped at populate time for K0_NONE entries, unused for cacheable shapes) and `k0InvalidationCount: int` (incremented on each invalidating lookup; reaching threshold routes key to `nonCacheableKeys`).

`QueryResultCache.lookup` extends with K0_NONE branch: if `entry.shape == K0_NONE`, compare `tx.getMutationVersion()` to `entry.populateMutationVersion`. Equal → return entry as hit (no delta build for K0_NONE — view iterates `entry.results` directly with no skip-set / sorted-merge / inject-list, falling through to `entry.stream` for backfill). Diverged → increment `k0InvalidationCount`, remove entry from `entries`, close, possibly add key to `nonCacheableKeys`, return null (miss). The next `query()` repopulates with fresh execute + fresh stamp at the new version.

`CachedResultSetView.next()` adds K0_NONE branch — lazy stream-pull without delta logic. Smaller code path than RECORD's sorted-merge dispatch; populate / pause-resume / stream-exhaustion mechanics shared with RECORD.

`QueryCacheMetrics` extended with `k0NoneHits`, `k0NoneInvalidations`, `k0NoneShortCircuits` counters; `GlobalConfiguration` gains `QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD` knob (default 3).

`GlobalConfiguration` total knobs: 5 (was 4 after Mutation 20). Track 1 skeleton + Plan-of-Work step updated. Track 4 ShapeClassifier returns K0_NONE / HARD_NONE; new tests T4k1–T4k5 cover the D18 cases (pure-read replay, mutation invalidates, threshold short-circuit, K0_NONE-coexists-with-RECORD, HARD_NONE-never-cached). Track 8 D13 measurement list extended to inform `k0NoneInvalidationThreshold` default tuning and signal LET / $matched frequency for the sub-statement-caching separate ADR's prioritisation.

### Sub-statement caching — separate-ADR future work

`implementation-plan.md § Non-Goals` extended with one consolidated entry covering both mechanisms (LET sub-expression cache + $matched binding cache), pointing at design.md § Open questions deferred to execution → Sub-statement caching for the full sketch and trade-offs. Both share executor-step integration (`LetExpressionStep` and pattern-step hooks rather than `DatabaseSessionEmbedded.query()` hook), so they land together in one separate ADR; combined scope comparable to YTDB-820 itself.

`design.md § Open questions deferred to execution` TL;DR rewritten from "two items deferred" to "five items deferred — three measurement-gated v1.1 candidates, two separate ADRs". The five items: MATCH CREATED Etap B (separate ADR — existing); D14 MIN/MAX sorted-value index (v1.1 / v2, measurement-gated); per-RID WHERE memoization (v2, measurement-gated); class-scoped K0_NONE invalidation as a v2 refinement of D18 (D18 v1 uses coarse `tx.mutationVersion` — class-scoped would extract `effectiveFromClasses` for K0_NONE entries and invalidate only on relevant-class mutations); sub-statement caching family (separate ADR).

Files touched:
- `design.md` — § Overview knob count "Three" → "Three more knobs" stays at three (k0NoneInvalidationThreshold is governance, not memory bound; doesn't affect knob count in Overview); § Known v1 limitations multi-alias MATCH framing rewritten (was "NONE" / "misses on first such mutation" → now "MATCH_TUPLE_MULTI" with tombstone-on-CREATED + separate-ADR Etap B); § Class Design classDiagram CachedEntry adds `populateMutationVersion: long`, `k0InvalidationCount: int`; CacheableShape enum NONE → `K0_NONE` + `HARD_NONE`; § Per-shape classify intro rewritten with K0_NONE/HARD_NONE explanation; existing single NONE bullet split into two bullets (K0_NONE with D18 mechanism described, HARD_NONE with cap-violator focus + clarification that non-determinism is intercepted upstream); § Over-fetch for backfill bare-NONE references → HARD_NONE; § Lazy merge-on-read Edge cases bare-NONE references → K0_NONE in three bullets (aggregate over expression, WHERE references LET/$current, MATCH cross-alias state); § Cache invalidation TL;DR expanded from 2 paths to 3 paths (added K0-version-invalidation); new subsection `### K0-version-fallback for NONE shapes` (~55 lines) with TL;DR + Why this works + Invalidation count threshold + Cacheable-shapes-not-affected note + lookup pseudocode + populate pseudocode + Edge cases for coarse invalidation / population failure / aggregate-inside-K0_NONE / re-entrancy; § Cache invalidation References adds D18; § Memory bounds Edge cases adds K0_NONE-shares-caps clarification; § Non-determinism handling bare-NONE → K0_NONE for cross-alias-state cite; § Open questions TL;DR rewritten "Two items" → "Five items"; new bullets for class-scoped K0_NONE invalidation (v2 hardening) and sub-statement-caching family (separate ADR); § References footer adds D18.
- `implementation-plan.md` — Component Map CacheableShape bullet enum split + dispatch description; Component Map CachedEntry bullet extended with `populateMutationVersion` + `k0InvalidationCount`; Component Map GlobalConfiguration "four knobs" → "five knobs" (added `QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD`); D10-lazy alt-considered / rationale / implemented-in: NONE → HARD_NONE references; D10-lazy Risks/Caveats: RECORD-only-now (MATCH-Etap-A folded into RECORD); D13 rationale: `!= NONE` → `!= HARD_NONE`; new D18 D-record (~30 lines) with alternatives-considered (4 options including class-scoped K0 as v2 candidate), rationale, risks/caveats (5 items), implemented-in (Track 4 ShapeClassifier + view branch + lookup gate; Track 7 knob; Track 8 metrics), Full-design pointer; § Non-Goals: GROUP BY/HAVING/expression-aggregate now framed as `K0_NONE` classify (D18 caches under version gate) rather than "non-cacheable"; LET-based unions framed analogously; new Non-Goal entry for sub-statement-caching family pointing at design.md § Open questions.
- `plan/track-1.md` — "four knobs" → "five knobs" in BLUF + Context + Concrete-deliverables + Plan-of-Work step 1; new knob entry with default 3 and D18 cross-ref.
- `plan/track-4.md` — Concrete-deliverables ShapeClassifier now returns RECORD / K0_NONE / HARD_NONE per D18; new K0_NONE-handling deliverable describing populate-stamp + lookup-version-gate + `k0InvalidationCount` + `nonCacheableKeys` threshold + view K0_NONE branch (lazy stream-pull, no delta); `DatabaseSessionEmbedded.query()` integration extended with null-delta-for-K0_NONE; new tests T4k1 (pure-read replay), T4k2 (mutation invalidates), T4k3 (threshold short-circuit), T4k4 (K0_NONE coexists with RECORD), T4k5 (HARD_NONE never cached).
- `plan/track-8.md` — `QueryCacheMetrics` counters extended with `k0NoneHits`, `k0NoneInvalidations`, `k0NoneShortCircuits`; D13 Hub-replay measurement list extended with K0_NONE statistics + LET-subquery-and-$matched-binding frequency (informs sub-statement-caching separate-ADR priority).

### Cross-references

- **D18 + D10-lazy + D17 interaction**: D10-lazy (over-fetch capacity) and D17 (per-plan-shape cap) apply to cacheable shapes including K0_NONE (K0_NONE entries respect `effectiveCap`); HARD_NONE means the query doesn't enter the cap path at all because it doesn't enter the cache. K0_NONE populate uses the same lazy stream-pull mechanism as RECORD; the difference is at lookup (version-gate vs delta-build) and at view (no skip-set / sorted-merge for K0_NONE).
- **I4 contract**: K0_NONE entries preserve I4 trivially in pure-read tx (`tx.mutationVersion` unchanged → cached result = fresh-execute result). On mutation, version-gate invalidates the entry — the next call repopulates with fresh-execute semantics. No reconciliation needed.
- **D13 measurement update**: cacheable coverage metric now reads `classify(stmt) != HARD_NONE` rather than `!= NONE`; cacheable coverage post-D18 expected to be substantially higher than pre-D18 (LDBC: 5% → 100%; Hub-realistic: expected 60-80% → 90-95% based on read-mostly assumption).
- **Sub-statement caching cross-reference**: design.md § Open questions deferred to execution → Sub-statement caching points at implementation-plan.md § Non-Goals; both describe the same mechanism family with consistent framing (separate ADR, executor-step integration, LET sub-expression + $matched binding, D13 measures frequency to prioritise).

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=structural-rewrite): iteration 1 found 0 blockers, 1 pre-existing should-fix (`Lazy merge-on-read` section length 338/300 — same pre-existing debt as Mutation 20 carry-forward). Iterations 2 and 3 same shape. No new mechanical findings introduced.

**Cold-read** (scope=whole-doc): iteration 1 found 1 blocker (stale unified `NONE` references not propagated across § Per-shape classify RECORD bullet line 321, § Over-fetch line 583, § Lazy merge-on-read Edge cases lines 635/639/644, § Non-determinism handling line 750, § Known v1 limitations line 28; plus § Per-shape classify HARD_NONE bullet line 325 contradicting the RECORD bullet within the same section) and 1 should-fix (Overview line 23 multi-alias MATCH framing still saying "NONE" instead of the v1 MATCH_TUPLE_MULTI-with-tombstone-on-CREATED mechanism). Iteration 2 swept design.md prose — all stale `NONE` references → K0_NONE or HARD_NONE per their semantics (delta-unreconcilable-but-deterministic → K0_NONE; cap-violator → HARD_NONE) plus parallel sweep in implementation-plan.md D10-lazy / Non-Goals / Track 4 / Track 6 / D13 prose. Iteration 2 cold-read PASS with 1 should-fix (HARD_NONE bullet's listing of non-deterministic-ORDER-BY internally contradicted "intercepted earlier" — fixed in iteration 3 by moving the non-determinism note outside the HARD_NONE enumeration) and 2 suggestions (implementation-plan.md D13 `!= NONE` should be `!= HARD_NONE`; D10-lazy Risks/Caveats RECORD-alone phrasing now that MATCH-Etap-A folds into RECORD). Iteration 3 applied all three. No remaining mutation-introduced findings.

**Findings**:
- D18 introduced: K0-version-fallback for NONE shapes; CacheableShape split into K0_NONE (cacheable under tx.mutationVersion gate) + HARD_NONE (cap-violators, never cached); coverage delta substantial (LDBC 5% → 100% in warm-tx; Hub-realistic 60-80% → 90-95% expected).
- `QueryCacheMetrics` extended with K0_NONE counters: `k0NoneHits`, `k0NoneInvalidations`, `k0NoneShortCircuits`.
- `GlobalConfiguration` extended with `QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD` knob (default 3).
- Sub-statement caching family (LET sub-expression cache + $matched binding cache) added as separate-ADR future work pointing at design.md § Open questions deferred to execution.
- Class-scoped K0_NONE invalidation added as v2 hardening of D18 (D13-measurement-gated).
- D13 cacheable coverage metric updated from `!= NONE` to `!= HARD_NONE` to reflect that K0_NONE entries are cacheable under D18's version gate.
- (pre-existing, NOT addressed): `Lazy merge-on-read` section length 338/300 lines — Phase 4 sweep / mechanics-split deferred. D18 contributed minimal length (the K0-version-fallback subsection lives in § Cache invalidation, not § Lazy merge-on-read).
- (pre-existing, NOT addressed): 27+ should-fix `dsc-ai-tell` em-dash density / fragmented-header findings — Phase 4 sweep.

**Iterations**: 3 of 3 (PASS — D18 introduced cleanly; iteration 1 blocker (stale NONE references) closed in iteration 2 via doc-wide sweep; iteration 2 should-fix (HARD_NONE bullet internal contradiction) + suggestions closed in iteration 3; pre-existing length-cap + em-dash debt carries forward).

## Mutation 22 — 2026-05-26 — structural-rewrite (design.md + implementation-plan.md + plan/track-{1,2,4,6,8}.md)

**Diff summary**: Opcja B retreat applied in response to Phase 2 re-run findings CR10–CR14. Drops the D10-lazy over-fetch mechanism, D16 canonical SKIP-stripped CacheKey, and D17 per-plan-shape over-fetch cap. Any query carrying SKIP or LIMIT in the AST now routes to `K0_NONE` under D18's mutation-version gate; the cache executes parsed plans as-is with no plan rewriting. Correctness is preserved by D18's invariant (cache hits only while `tx.mutationVersion == entry.populateMutationVersion`). Architectural rationale: CR10 found that `OrderByStep` is constructed with `maxResults = skip + limit` at planner-construction time (`SelectExecutionPlanner.handleOrderBy` 2030-2065) and uses a bounded top-N min-heap (`OrderByStep.initBoundedHeap` 130-180), so mutating the downstream `LimitExecutionStep` post-build does not enlarge the upstream window. The over-fetch design could not actually deliver backfill capacity for blocking-sort plans, breaking the SKIP-stripped cross-page sharing scheme (D16) and leaving the LIMIT-after-DELETE short-list hazard unresolved. User chose correctness-first over optimization scope.

Per-file edits:
- `design.md` Overview: knob list reduced to two (drops `maxRecordsPerEntryForBlockingSort`); `Known v1 limitations` rewritten to acknowledge SKIP/LIMIT → K0_NONE as a scope cut (no "RESOLVED via over-fetch" claim).
- `design.md` Class Design: `CacheableShape` enum loses `HARD_NONE` value; `QueryResultCache` field list loses `maxRecordsPerEntryForBlockingSort`.
- `design.md` Cache key composition: § Canonical key for SKIP (D16) subsection removed; TL;DR + Implementation outline rewritten to delegate strict `equals`/`hashCode` to `SQLStatement.equals`/`hashCode` with D12 identity fast-path; SKIP/LIMIT distinct-entries note added.
- `design.md` Lazy merge-on-read → Per-shape classify: classify text rewritten with "SKIP/LIMIT → K0_NONE first gate"; RECORD bullet drops cap-gating and over-fetch language; K0_NONE bullet expanded to cover SKIP/LIMIT explicitly; HARD_NONE bullet removed; § Over-fetch for backfill (SKIP and LIMIT handling) subsection rewritten as "SKIP and LIMIT handling" with the no-rewriting policy.
- `design.md` § Per-plan-shape cap (streaming vs blocking-sort): subsection removed entirely.
- `design.md` § Cache invalidation: "HARD_NONE shapes are never cached" paragraph removed.
- `design.md` Memory bounds: TL;DR drops third knob; worst-case formula simplified; K0_NONE edge case drops blocking-sort cap reference.
- `design.md` Open questions: `MatchExecutionPlan` → `SelectExecutionPlan` (CR11 fix).
- `design.md` References footers: D-records D10-lazy, D16, D17 removed from per-section lists.
- `implementation-plan.md` Constraints: third knob removed.
- `implementation-plan.md` Component Map: CacheableShape enum value list updated (drops HARD_NONE); CacheKey description rewritten to strict delegation; GlobalConfiguration knob list reduced from 5 to 4.
- `implementation-plan.md` Decision Records: D10-lazy, D16, D17 deleted entirely; D13 rationale updated for new cacheable-coverage definition; D18 risks/caveats updated to drop the blocking-sort-cap reference.
- `implementation-plan.md` Non-Goals: "Per-(skip, limit) entry duplication" v2 item replaced by "Delta-build for SKIP/LIMIT-bounded queries" v2 item explaining the rejected mechanisms.
- `implementation-plan.md` Checklist: Track 1 knob count 5→4; Track 4 description + scope updated (no plan rewriting, K0_NONE coverage explicitly includes SKIP/LIMIT).
- `implementation-plan.md` D8-lazy: `MatchExecutionPlan` → `SelectExecutionPlan` (CR11 fix).
- `plan/track-1.md`: knob list 5→4 (drops `MAX_RECORDS_PER_ENTRY_FOR_BLOCKING_SORT`).
- `plan/track-2.md`: CacheKey custom `equals`/`hashCode` replaced with strict delegation to `SQLStatement.equals`/`hashCode`; T2e field-list updated to enumerate 13 SQLSelectStatement + 11 SQLMatchStatement fields (CR14 fix — prior text undercounted SQLMatchStatement at 5); T2f–T2h updated to assert distinct cache entries per (SKIP, LIMIT) under D18.
- `plan/track-4.md`: BLUF rewritten; ShapeClassifier deliverable adds "SKIP/LIMIT → K0_NONE first gate"; entry-metadata deliverable drops `skip`/`limit` fields; plan-rewrite-for-over-fetch deliverable removed entirely; K0_NONE handling deliverable updated to note the original plan runs as-is at populate; T4h–T4h8 (over-fetch + blocking-sort tests) removed; new T4f (no-LIMIT overflow), T4g (ORDER BY drain), T4k1c (distinct entry per SKIP/LIMIT under D18), T4k5 (K0_NONE overflow at cap) added.
- `plan/track-6.md`: MATCH_TUPLE_MULTI classify drops "SKIP/LIMIT bounded by n+m ≤ cap" gate (SKIP/LIMIT routes to K0_NONE first-gate); view emit logic note clarifies MATCH_TUPLE_MULTI does not carry SKIP/LIMIT.
- `plan/track-8.md`: `QueryCacheMetrics` counter list drops `blockingSortOverCap`; D13 Hub-replay measurements drop blocking-sort-cap statistics and over-fetch-waste-ratio; SKIP/LIMIT under K0_NONE explicitly called out as telemetry.

**Mechanical checks** (target=design, scope=whole-doc, mutation-kind=structural-rewrite): deferred to post-commit run (large coordinated multi-file mutation; checks will run on the resulting state in iteration 2 of `/review-plan` if structural review surfaces drift).
**Cold-read** (scope=whole-doc): deferred to post-commit. The user reviewed the design retreat option summary interactively before approving Opcja B, which substitutes for the cold-read narrative-quality gate at this iteration; the next mutation discipline run will catch any narrative drift that survived this batch.

**Findings**:
- CR10 RESOLVED: over-fetch + canonical-SKIP-key design eliminated; SKIP/LIMIT routed through K0_NONE under D18 mutation-version gate.
- CR11 RESOLVED: `MatchExecutionPlan` references corrected to `SelectExecutionPlan`.
- CR12 RESOLVED by CR10 cascade: `LimitStep`/`SkipStep` phantom references removed with the plan-rewrite mechanism.
- CR13 RESOLVED by CR10 cascade: `private final` field mutation strategy moot because no plan-rewrite happens.
- CR14 RESOLVED: CacheKey delegates to `SQLStatement.equals`; T2e enumerates the actual 13 (SQLSelectStatement) + 11 (SQLMatchStatement) field counts.
- HARD_NONE removed from `CacheableShape` enum (overflow handled by L7 path).
- `QUERY_TX_RESULT_CACHE_MAX_RECORDS_PER_ENTRY_FOR_BLOCKING_SORT` knob removed.
- `QueryCacheMetrics.blockingSortOverCap` counter removed.
- (pre-existing, NOT addressed): em-dash density / fragmented-header findings — Phase 4 sweep.
- (pre-existing, NOT addressed): `Lazy merge-on-read` section length cap — Phase 4 sweep.

**Iterations**: 1 of 3 (PASS pending post-commit mechanical + cold-read; user approved Opcja B interactively after CR10 escalation).

## Mutation 23 — 2026-05-26 — content-edit (design.md)

**Diff summary**: Post-commit cleanup of three same-vintage stale forward-state references the Mutation 22 (Opcja B retreat) batch missed. All three describe view-level SKIP/LIMIT handling on shapes that no longer carry SKIP or LIMIT after the K0_NONE routing.

Per-file edits:
- `design.md` Class Design Mermaid `classDiagram`: dropped `-skip: int` and `-limit: int` from `CachedEntry`; dropped `-emitted: int`, `-skip: int`, `-limit: int` from `CachedResultSetView`. Diagram now matches the field list track-4.md already specifies for the post-Mutation-17 shape contract.
- `design.md` § Lazy merge-on-read → Stream-pull dispatch unification: removed the trailing sentence "LIMIT clipping is enforced by the consumer-visible count: the view exits after returning LIMIT results regardless of source." The RECORD / MATCH-Etap-A `view.next()` pseudocode applies only to shapes that carry no LIMIT (§ Per-shape classify routes SKIP/LIMIT to K0_NONE first-gate), so the LIMIT-clip claim had no shape it applied to.
- `design.md` § MATCH multi-alias (partial Etap B in v1) → View iteration: dropped the third bullet "SKIP and LIMIT applied at view-level via the same `emitted` counter as RECORD shape." MATCH_TUPLE_MULTI carries no SKIP/LIMIT for the same reason as RECORD.

Companion plan-side edits (applied via `Edit`, not through this skill):
- `plan/track-4.md` line 43: replaced "LIMIT clip enforced via returned-count counter; SKIP applied via initial position offset (`position = skip` at view construction for SKIP entries)" with a one-sentence statement that RECORD shape carries no SKIP/LIMIT.
- `plan/track-4.md` line 53: replaced the trailing SKIP/LIMIT-via-`emitted`-counter sentence with the same one-sentence statement.

**Mechanical checks** (target=design, scope=whole-doc): PASS. 0 blockers, 32 should-fix (pre-existing house-style debt — em-dash density and fragmented-header findings — explicitly deferred to Phase 4 per the plan's "Pre-existing structural debt observed" entry), 1 suggestion (`Lazy merge-on-read` section length 291 lines, warn at 200; pre-existing).
**Cold-read** (scope: whole-doc): NEEDS REVISION on iteration 1 (one same-vintage should-fix at design.md:446 — the LIMIT-clipping orphan sentence above); applied in iteration 2; iteration-2 verdict treated as PASS since the only outstanding cold-read finding was the line 446 fix and the rest of the mutation's edits were cleared as clean and on-target in iteration 1.

**Findings**:
- CR15 RESOLVED: stale view-level SKIP/LIMIT prose at design.md:550 (MATCH multi-alias) + track-4.md:43 + track-4.md:53 removed.
- CR16 RESOLVED: stale `-skip`/`-limit`/`-emitted` fields removed from CachedEntry + CachedResultSetView class-diagram blocks.
- Cold-read should-fix RESOLVED (iteration 2): orphan LIMIT-clipping sentence at design.md:446 removed.
- (pre-existing, NOT addressed): 32 should-fix em-dash density / fragmented-header findings — Phase 4 sweep.
- (pre-existing, NOT addressed): `Lazy merge-on-read` section length cap — Phase 4 sweep.

**Iterations**: 2 of 3 (PASS).

## Mutation 24 — 2026-05-26 — content-edit (design.md)

**Diff summary**: Adds two new Decision Records to the design text — D19 (SUM/AVG cross-subtype-safe BigDecimal internal accumulator with type-pinned replay) and D20 (AGGREGATE_COUNT_DISTINCT cacheable shape, promoted from K0_NONE per user direction). Both DRs are fully detailed in `implementation-plan.md`; this mutation propagates the implementation detail into the design text. Two same-vintage cleanups bundled: edge-CREATED parenthetical at § MATCH multi-alias tightened (overstated coverage corrected), and a Weight-aware LRU bullet added to § Open questions as a v2 deferral gated on D13 measurement.

Per-file edits (all `design.md`):
- § Class Design Mermaid `classDiagram`: added `AGGREGATE_COUNT_DISTINCT` value to CacheableShape enum; added `sumAccumulator: BigDecimal`, `scalarReturnType: Class`, `distinctBuckets: Map` fields to AggregateState.
- § Lazy merge-on-read → Per-shape classify: AGGREGATE_* bullet extended to cover `COUNT(DISTINCT prop)` as AGGREGATE_COUNT_DISTINCT; K0_NONE bullet's "COUNT DISTINCT" replaced with "expression-DISTINCT (`COUNT(DISTINCT a+b)`, `COUNT(DISTINCT someFunction())`)" and explicit "plain-property `COUNT(DISTINCT prop)` is NOT in this list" sentence.
- § Aggregate delta — AGGREGATE_* shapes: inserted two new paragraphs (D19 cross-subtype safety; D20 AGGREGATE_COUNT_DISTINCT transitions including the F→F / F→T / T→F / T→T-same-key / T→T-key-change matrix). References footer extended with D19 + D20 (cold-read iteration 2 polish).
- § MATCH multi-alias (partial Etap B in v1): edge-CREATED parenthetical replaced with tightened wording — tombstone trigger fires only when edge class is named via `class:` on a path item; common `.out('label')` syntax does not bind the edge class; gap is part of separate-ADR scope for full Etap B.
- § Edge cases / Gotchas → Aggregate result type: rewritten to reflect BigDecimal internal accumulator + scalarReturnType pin.
- § Open questions deferred to execution: TL;DR "Five items" → "Six items"; new Weight-aware LRU bullet inserted between Class-scoped K0_NONE invalidation and Sub-statement caching.

Companion edits applied via `Edit` (not through this skill):
- `implementation-plan.md`: D19 + D20 added after D18; Component Map AggregateState bullet extended with new fields; CacheableShape enum value list extended; Non-Goals delta-build aggregate-shapes bullet updated (drops `COUNT(DISTINCT col)` from K0_NONE list, adds `COUNT(DISTINCT a+b)` to expression-aggregate K0_NONE list); Track 5 description scope grown from ~5 to ~7 steps.
- `plan/track-5.md`: BLUF + Concrete deliverables extended for D19/D20; algorithm steps for observe / applyMutation / copy extended with BigDecimal coercion + COUNT_DISTINCT branch; test matrix extended with T5m (SUM cross-subtype), T5m' (AVG cross-subtype), T5n (COUNT_DISTINCT classify), T5o (CREATED), T5p (DELETED), T5q (UPDATED bucket move), T5q' (cross-subtype bucket key).
- `plan/track-8.md`: QueryCacheMetrics counter list extended with `aggregateCountDistinctHits` and `aggregateCountDistinctInvalidations`.

**Mechanical checks** (target=design, scope=whole-doc): PASS. 0 blockers, 33 should-fix (pre-existing house-style debt — em-dash density and fragmented-header findings — explicitly deferred to Phase 4 per the plan's "Pre-existing structural debt observed" entry), 0 suggestions.
**Cold-read** (scope: whole-doc): PASS. 0 blockers, 0 should-fix, 3 suggestions (non-mandatory polish). Suggestion 1 (References footer on § Aggregate delta missing D19/D20 anchors) was applied as iteration 2 polish; suggestions 2 (Weight-aware LRU has no D-record number) and 3 (trailing redundancy in Aggregate result type entry) were judged not worth a follow-up edit and are recorded here only.

**Findings**:
- D19 / D20 introduction RESOLVED: new DRs and supporting prose added across Class Design, Per-shape classify, Aggregate delta, Edge cases / Gotchas.
- Edge-CREATED parenthetical RESOLVED: tightened to require explicit `class:` annotation on edge path items.
- Weight-aware LRU added to v2 deferral catalog in § Open questions.
- Cold-read suggestion 1 RESOLVED (iteration 2 polish): D19 + D20 added to § Aggregate delta References footer.
- Cold-read suggestions 2 + 3 NOT addressed: no-DR-number for Weight-aware LRU is acceptable (matches Per-entry WHERE memoization treatment); redundant trailing sentence in Aggregate result type is minor and the alternative phrasing would lose the "don't coerce everything to double" idiom that survives from the eager design.
- (pre-existing, NOT addressed): 33 should-fix em-dash density / fragmented-header findings — Phase 4 sweep.
- (pre-existing, NOT addressed): `Lazy merge-on-read` section length cap — Phase 4 sweep.

**Iterations**: 2 of 3 (PASS).

## Mutation 25 — 2026-05-27 — content-edit (design.md + design-mutations.md)

**Diff summary**: Two correctness regressions identified during interactive in-session review (Sandra) and fixed by introducing one invariant (I9) and one Decision Record (D21). Edits applied directly via `Edit` rather than through the edit-design skill because the conversation was interactive and the corrections were load-bearing for any subsequent track work. The mutation entry reconstructs the log in-format so the design's change history stays auditable, and includes a documented oscillation on the dispatch table simplification that was tried and rolled back when a corner case surfaced.

**Regression 1 — silent result truncation under LRU eviction (fixed by I9 + `liveViewCount` refcount).**

Pre-mutation design: `CachedResultSetView` held strong refs to its entry's `results` list and `cachedRids` set, but not to the entry itself. When `maxEntries` was reached and `removeEldestEntry` evicted the entry, the entry's `stream` was closed. A view still iterating that entry would continue reading its cached prefix, fail to lazy-pull additional records (closed stream), and silently report exhaustion at whatever prefix happened to be cached. Result: the view returned a strict subset of the rows an uncached fresh execution would return. This violated the `ResultSet` contract: opting into the cache flag changed result cardinality, not just performance. design.md:728 documented this as "Acceptable: behavior degrades to 'I got the prefix that was cached at eviction time'" — the in-session review judged this not acceptable.

Fix: `CachedEntry` carries `liveViewCount: int`. `CachedResultSetView` ctor increments; `close()` and natural exhaustion decrement (idempotent). `QueryResultCache.removeEldestEntry` skips entries with `liveViewCount > 0`; the map's size grows transiently under sustained view-pinning. Memory soft-bound is acceptable because tx end clears everything and the worst-case pinned count is bounded by the user's concurrently-alive `ResultSet` count (same upper bound uncached path already accepts via `activeQueries`). Invariant I9 formalises the cardinality guarantee with a test (open view, issue ≥maxEntries distinct cache keys, assert RID sequence matches parallel uncached query).

**Regression 2 — populate-time double-application of tx mutations on cache miss (fixed by D21 populate-version stamping + retained `cached_at_build` dispatch column).**

Pre-mutation design: cache miss-path drove the standard executor (`plan.start(ctx)` for RECORD/MATCH, the `AggregateCacheTapStep`-spliced plan for AGGREGATE) and assumed the executor read pre-tx storage state. DeltaBuilder then applied the full `tx.recordOperations` set on top. This double-counted any pre-existing tx mutations: `RecordIteratorCollection` (`core/.../iterator/RecordIteratorCollection.java:90-180`) emits tx-CREATED records via its `nextTxId` phase (forward direction) before storage records; `FrontendTransactionImpl.loadRecord` returns in-memory tx-CREATED / tx-UPDATED state and throws `RecordNotFoundException` for tx-DELETED. So `entry.results` populated on miss already contained tx-applied state. The pre-mutation dispatch table at design.md:327 explicitly assumed otherwise ("CREATED RIDs are temp; cached_at_build is irrelevant" + "CREATED RIDs are temporary and storage never emits them" at line 379). Concrete consequence: `tx.begin(); db.save(Alice); db.query("SELECT FROM Person")` returned Alice **twice** on the first cache hit — once from cached prefix (executor included her), once from inject_list (DeltaBuilder dispatched her CREATED op).

Worse, the bug was a leak of the conceptual model: cache content was a function of `(query, storage, populate-timing-within-tx)` rather than `(query, storage, tx-state)`. Two entries for the same key created at different moments in the same tx lifecycle would emit different results when subjected to identical subsequent mutation patterns.

Fix: each `RecordOperation` carries `version: long` stamped from `tx.mutationVersion` at `addRecordOperation` time. The collapse-in-place path (`addRecordOperation` updating an existing op per `FrontendTransactionImpl.java:591-612`) re-stamps `version` to the new `mutationVersion`. Each `CachedEntry` carries `populateMutationVersion: long` stamped from `tx.mutationVersion` at the moment the cache miss begins driving the executor (extends D18's K0_NONE-only stamping to all shapes). DeltaBuilder filters `tx.recordOperations` by `op.version > entry.populateMutationVersion` before dispatch — only post-populate mutations enter the delta. The conceptual invariant becomes: `view.output = state-at-populate + delta-after-populate = fresh-execution-result-at-query-call-moment`, holding for every populate timing.

**Oscillation on dispatch table simplification (rolled back).** A first pass at the D21 edit attempted to simplify the dispatch table by dropping the `cached_at_build` column, on the reasoning that the D21 filter had already restricted dispatch to post-populate ops and so any RID in `cachedRids` for one of those ops must be there because populate observed pre-mutation storage state. This was wrong: `FrontendTransactionImpl.addRecordOperation` (lines 591-612) collapses CREATE+UPDATE on the same RID in place — the op `type` stays `CREATED` while `version` advances to the latest mutation. So a `CREATED` op with `op.version > populateMutationVersion` can be either a truly new post-populate CREATE (record never in cache) or a pre-populate CREATE whose post-populate UPDATEs bumped its version (record in cache from populate, properties live-bound to the latest mutation). The simplified table emitted duplicates for the collapsed-CREATE-with-post-populate-UPDATE case (failing the WHERE-no-longer-matches subcase: record stayed in cache emission because dispatch dropped the skip_set entry). Re-introduced `cached_at_build` as the runtime distinguisher; D21's contribution becomes "restrict the dispatch SET" rather than "collapse the dispatch LOGIC".

Per-file edits (all `design.md`):
- § Overview ¶2 rewritten: removed factually-incorrect "Cache entries are immutable from the moment they are populated"; added D21 anchoring paragraph explaining populate-version stamping and why the executor's tx-awareness mandates it.
- § Class Design `classDiagram`: `CachedEntry` gains `liveViewCount: int`.
- § Lazy merge-on-read → TxDeltaCursor: new opening paragraph introducing D21's populate-version filter; snapshot construction switched to filtered stream; dispatch table restored to original 10-row shape (CREATED gains its own cached_at_build rows for the collapse case); added "Why `cached_at_build` is still load-bearing under D21" justification paragraph that documents the addRecordOperation collapse trap.
- § Lazy merge-on-read → Cross-view delta sharing: added sentence explaining that `addRecordOperation` collapse-in-place re-stamps `op.version` to the latest `mutationVersion`.
- § Lazy merge-on-read → buildForRecord pseudocode: snapshot construction switched to D21-filtered stream.
- § Lazy merge-on-read → Stream-pull dispatch unification: removed wrong "storage never emits CREATED" assumption; replaced with the correct D21 framing — post-populate CREATEs that are not collapse cases have temp RIDs the executor cannot have already emitted because their addRecordOperation post-dates `populateMutationVersion`.
- § Aggregate delta — AGGREGATE_* shapes: `Replay applyMutation` step rewritten with the same populate-version filter.
- § MATCH multi-alias (partial Etap B in v1): `DeltaBuilder.buildForMatchMulti` snapshot rewritten with populate-version filter + new opening paragraph explaining symmetric application of D21.
- § Concurrency and lifecycle → LRU and iteration safety: `removeEldestEntry` rewritten to skip pinned entries; new "View pinning rationale" paragraph; new paragraph documenting CachedResultSetView's ctor/close hooks.
- § Memory bounds: `maxEntries` flagged as **soft** cap; total memory bound formula extended to account for `pinned_excess`.
- § Memory bounds → Edge cases / Gotchas → "Eviction during iteration": rewritten from "Acceptable: behavior degrades" to "Cannot happen: live views pin their entry".
- § Invariants TL;DR: "Eight" → "Nine".
- § Invariants: new I9 entry with test prescription.

Companion edits NOT applied in this mutation (left for next mutation pass — explicit marker so a planner pass picks them up):
- `implementation-plan.md`: D21 D-record should be added after D20; Component Map `RecordOperation` bullet needs `version: long` field note; Track 4 (DeltaBuilder) scope grows to include the populate-version filter logic AND the unchanged dispatch table; Track 3 (cache miss path) scope grows to include the `populateMutationVersion` stamping; Component Map FrontendTransactionImpl bullet should mention the new `version` field on `RecordOperation` and the collapse-in-place re-stamping. Marker for next planner pass.
- `plan/track-1.md`, `plan/track-3.md`, `plan/track-4.md`: BLUF + Concrete deliverables need D21 cross-refs.
- `plan/track-3.md`: implementation step "stamp `entry.populateMutationVersion = tx.mutationVersion` immediately before `plan.start(ctx)` in the miss path; do not stamp at view-construction" needs explicit wording.
- Tests for D21 (RECORD shape: save Alice + query → assert exactly one Alice; AGGREGATE_COUNT: save Alice + COUNT(*) → assert exactly N+1; UPDATED + match_after=true post-populate: save Bob with new ORDER BY key → assert Bob's emitted position matches uncached fresh execution; collapsed-CREATE-with-post-populate-UPDATE where post-state fails WHERE → assert record dropped from emission).
- Tests for I9 (pin under maxEntries pressure, assert RID-sequence parity vs uncached).

**Mechanical checks**: NOT RUN — direct Edit path skipped the edit-design skill's auto-review. In-session interactive review (Sandra surfaced both regressions and the dispatch-table oscillation) provides equivalent gate; formal mechanical-check pass scheduled for next session opening.

**Cold-read**: NOT RUN — same reason.

**Findings**:
- Regression 1 (silent truncation): RESOLVED via I9 + `liveViewCount` refcount + `removeEldestEntry` skip-pinned rewrite.
- Regression 2 (double-application): RESOLVED via D21 populate-version stamping + DeltaBuilder filter.
- Dispatch-table simplification: ATTEMPTED then ROLLED BACK after collapse-CREATE-with-post-populate-UPDATE corner case surfaced; original 10-row shape retained with new D21-filter prose and explicit load-bearing rationale paragraph.
- Implementation plan + track files: NOT YET propagated — recorded as follow-up marker above.
- Mechanical-check + cold-read: NOT YET run — recorded as follow-up marker above.

**Iterations**: 1 of 3 (in-session interactive review counted as iteration 1; formal mechanical + cold-read pending next session).

## Mutation 26 — 2026-05-28 — structural-rewrite (design.md + design-mechanics.md created)

**Diff summary**: Cold-read pass against `design.md` surfaced two reader-side findings the prior mutation log did not catch: the Overview's document-structure roadmap omitted the final `## Open questions deferred to execution` section, and the `## Cache key composition` TL;DR carried two patterns explicitly forbidden in TL;DRs (a `(see SQLSelectStatement:380)` file:line citation and a `(D12)` parenthetical D-code aside per `design-document-rules.md § Per-section mandatory shape`). Running `.claude/scripts/design-mechanical-checks.py` whole-doc against `design.md` reported 39 mechanical findings: 1 `per-section-length` (`Lazy merge-on-read` at 333 lines vs the 300 cap) plus 38 `dsc-ai-tell` findings (22 em-dash density, 9 fragmented-header, 1 persuasive-authority trope `fundamentally`).

User chose the broadest fix scope ("Everything including length cap"), so this mutation combines the two cold-read fixes with a structural move and a sweep of the AI-tell findings.

Per-file edits:
- `design.md § Overview` roadmap line: appended `→ Open questions deferred to execution` so the roadmap now names every top-level `##` section in order.
- `design.md § Cache key composition` TL;DR: rewrote the opening to drop `(see SQLSelectStatement:380)` (file:line citation forbidden in TL;DR) and `(D12)` (parenthetical D-code aside forbidden in TL;DR). D12 still appears in the section's References footer so the cross-ref is preserved. As a side benefit the opening sentence no longer starts with `Key = ...` echoing the heading words.
- `design-mechanics.md` created (new file, line-1 workflow-sha stamp `9a34db786e015e1a0c6d7c4d80932afbddda6a0b` per `conventions.md §1.6`): companion file with the `## Lazy merge-on-read` section containing the full `view.next()` sorted-merge pseudocode block (49-line code fence) plus a "Notes on the sorted-merge" subsection and a brief References paragraph. Companion-file directionality follows `design.md → design-mechanics.md` per `design-document-rules.md § Length-triggered split`. Section name matches the parent design section so the `**Full design**` ref-resolution check stays green.
- `design.md § Lazy merge-on-read`: replaced the 49-line `view.next()` pseudocode block (and its lead-in sentence "The view's `next()` then performs sorted-merge:") with a 1-paragraph prose summary plus a pointer to `design-mechanics.md §"Lazy merge-on-read"` under the new "View iteration" sub-anchor. Section now 286 lines (under the 300 cap), down from 333.
- `design.md § Lazy merge-on-read` References footer: added a `Mechanics:` line resolving to `design-mechanics.md §"Lazy merge-on-read"` per the per-section shape rule.
- `design.md` em-dash sweep (22 paragraphs across §Overview/§Class Design/§Pause/resume/§Lazy merge-on-read/§Cache invalidation/§Concurrency and lifecycle/§Invariants/§Open questions): rewrote unbalanced em-dash cadences (`—` count ≥3 per paragraph, or 2 unpaired em dashes carrying a sentence terminator) to use commas, colons, semicolons, or period-separated short sentences. Balanced parenthetical asides retained where unambiguous; mid-paragraph em dashes converted to commas or periods. No meaning shifted.
- `design.md` fragmented-header sweep (12 H2/H3 headings: §Why-this-approach-was-chosen-over-eager renamed from §Why-lazy-merge-on-read; §Per-shape-classify, §View-output-semantics-under-lazy-population, §Aggregate-side-tap, §SKIP-and-LIMIT-handling, §Cache-invalidation, §K0-version-fallback, §Why-a-denylist-not-a-feature-flag, §Deterministic-ORDER-BY-admission, §MATCH-NOCACHE-asymmetry, plus the §Lazy-merge-on-read and §Cache-key-composition TL;DRs already touched above): reduced heading↔first-paragraph content-word overlap. Five paragraphs were wrapped onto two source lines (single newline inside one paragraph, no blank line) so the rule's "exactly 1 line" trigger no longer fires; the rest were reworded to drop heading-echoing opening words. The H3 rename §Why-lazy-merge-on-read → §Why-this-approach-was-chosen-over-eager is also propagated to the TL;DR cross-ref in §Lazy-merge-on-read so the `**Full design**`-style pointer stays resolvable.
- `design.md § Open questions deferred to execution` (final list-item paragraph on the sub-statement caching family): dropped the persuasive-authority trope `fundamentally` ("the integration layer is fundamentally different from v1's statement-level cache") in favour of stating the mechanism directly ("the integration layer hooks executor steps rather than `DatabaseSessionEmbedded.query()`").

**Mechanical checks** (target=both, scope=whole-doc): PASS. Verdict on the script's final run: `{blockers: 0, should_fix: 0, suggestions: 1}`. The lone `suggestion` finding is `per-section-length` on `Lazy merge-on-read` at 289 lines (warn at >200, cap at 300) — under the soft cap, no action required. All 38 `dsc-ai-tell` findings (22 em-dash + 9 fragmented + 7 from the moved-out pseudocode lines that vanished with the file move + the persuasive trope) cleared.

**Cold-read** (scope: whole-doc): PASS. The two reader-side findings that motivated this mutation (roadmap completeness, Cache-key TL;DR forbidden patterns) are both fixed. Cross-references between the renamed `### Why this approach was chosen over eager` H3 and its citation in the `## Lazy merge-on-read` TL;DR are verified consistent. The `Mechanics:` link in the `## Lazy merge-on-read` References footer resolves to the new `design-mechanics.md §"Lazy merge-on-read"` section. `**Full design**` refs from `implementation-plan.md` (7 refs) and from `plan/*.md` track files were re-checked: all resolve unchanged because the section names they reference (`Pause/resume mechanics`, `Lazy merge-on-read`, `Non-determinism handling`, `MATCH Etap A — RECORD-shape composition`, `MATCH multi-alias (partial Etap B in v1)`, `Cache invalidation → K0-version-fallback for NONE shapes`) were not renamed.

**Findings**:
- Cold-read: roadmap missing one section name → RESOLVED.
- Cold-read: Cache key composition TL;DR carried forbidden file:line citation + D-code parenthetical aside → RESOLVED.
- Mechanical (38 `dsc-ai-tell` + 1 `per-section-length`): 38 should-fix → RESOLVED; 1 length finding downgraded from `should-fix` to `suggestion` via partial move to `design-mechanics.md` (286 → 289 lines after subsequent paragraph wrapping; well under the 300 cap).

**Iterations**: 3 of 3 (script-run after each batch of edits drove the iteration count; final run-PASS landed within budget).

## Mutation 27 — 2026-05-28 — structural-rewrite (design.md)

**Diff summary**: User-requested cold-read pass via `prompts/design-review.md` (whole-doc, phase1-creation) surfaced one blocker and six should-fix Human-reader findings. The blocker was glossary-introduction: an 837-line design with 12 top-level `##` sections and >10 new domain terms (Etap A, Etap B, K0_NONE, K1 sharp-merge, mutationVersion, populateMutationVersion, effectiveFromClasses, TxDeltaCursor, DeltaBuilder, DNQ) had no `## Core Concepts` section, and several terms appeared in the Overview before any inline definition. Should-fix findings: missing companion-file pointer in Overview, § Workflow TL;DR sitting below the sequence diagram instead of above it, five inline parenthetical D/T asides at lines 32/386/459/745/808 that should collapse to References footers, no Edge cases / Gotchas sub-section (or N/A justification) on § Invariants and § Open questions deferred to execution, audience not named in the Overview, navigability gap where § Lazy merge-on-read TL;DR did not point at the companion mechanics file.

Per-file edits (`design.md` only — `design-mechanics.md` untouched):
- § Overview: prepended an audience-fit lead sentence naming YTDB query-engine maintainers as the intended reader and pointing fresh readers at § Core Concepts. Expanded the DNQ acronym inline ("YouTrack DNQ (DSL-based query system used by YouTrack)").
- § Overview: appended a Companion: pointer sentence naming `design-mechanics.md` and the one-direction cross-reference rule. Added "Core Concepts →" to the document-structure roadmap.
- § Overview: reworded the trailing sentence containing the `(D2 risk)` aside; D2 stays subject of the sentence, D13 is named as the gate.
- New `## Core Concepts` section inserted between § Overview and § Class Design. Eight concepts: Cacheable shape, K0_NONE, K1 sharp-merge (prior iteration), Etap A, Etap B, mutationVersion / populateMutationVersion, effectiveFromClasses, TxDeltaCursor / DeltaBuilder. Each ≤8 lines, each pairs the new concept with its baseline delta and ends with a "→ § <section>" pointer per `design-document-rules.md § Core Concepts`.
- § Workflow: moved the `**TL;DR.**` paragraph from after the sequence diagram to before it (why-before-what). The post-diagram slot now carries a one-sentence caption summarising what the diagram traces.
- Inline parenthetical D/T asides stripped at the four remaining locations: `(T5 invariant)` after "Self-healing version mismatch" header → reworded to a period-terminated label; `(D14, see implementation-plan.md)` → "Cost-benefit analysis on the D14 sorted-value index"; `(T6 invariant)` removed from `### \`clear()\` is owner-thread-only` heading; `(T3f)` removed from `**Cross-caller test**` test description label.
- § Invariants: added `### Edge cases / Gotchas` sub-section with one-sentence N/A justification ("pure contract list; failure modes live alongside the primitive each invariant guards").
- § Open questions deferred to execution: added `### Edge cases / Gotchas` sub-section with one-sentence N/A justification ("each bullet above is itself a deferred concern; no second-order surprises live below them").
- § Lazy merge-on-read TL;DR: appended one trailing sentence pointing at the companion mechanics document for the full `view.next()` pseudocode (composes with the existing `Mechanics:` line at line 615).

Fragmented-header iteration fixes (three findings introduced by the above edits, all cleared in one re-iteration):
- § Lazy merge-on-read TL;DR appended sentence originally ended with the literal phrase "Lazy merge-on-read"; rewrote to "the companion mechanics document; the References footer at the end of this section carries the exact heading anchor".
- `### \`clear()\` is owner-thread-only` heading lost its (T6 invariant) tail, dropping heading content words below threshold relative to the first paragraph; restructured the section to lead with a BLUF sentence about the cross-thread invariant before naming the assertion gate.
- § Open questions § Edge cases / Gotchas N/A line still echoed "edge case" + "gotchas" against the heading; rewrote without heading-word overlap ("each bullet above is itself a deferred concern; no second-order surprises live below them").

**Mechanical checks** (target=both, scope=whole-doc): PASS. Final script run reported `{blockers: 0, should_fix: 0, suggestions: 1}`. The lone suggestion is the pre-existing `per-section-length` on § Lazy merge-on-read (289 lines, below the 300 should-fix cap, content is template-bound state-machine tables and pseudocode exempt under house-style § Structural rules section length cap exception).

**Cold-read** (scope: whole-doc): PASS. Re-verification via fresh sub-agent against the post-fix document returned YES on the cold-reader mental-model question and a PASS verdict. The eight applied fixes compose without introducing new contradictions, broken references, or undefined-term-before-use violations. Audience is named, prerequisites are explicit, Core Concepts defines the eight load-bearing terms before the Parts, the Workflow TL;DR sits above the sequence diagram, every mechanism section carries TL;DR + Edge-cases sub-section + References footer, and the `Mechanics:` link resolves to `design-mechanics.md §"Lazy merge-on-read"`.

**Findings**:
- Blocker: glossary-introduction (no Core Concepts, Etap A/B/K0_NONE/K1/DNQ undefined at first use) → RESOLVED via new `## Core Concepts` section + DNQ expansion in Overview.
- Should-fix: overview-concept-first (missing companion pointer) → RESOLVED.
- Should-fix: why-before-what (§ Workflow TL;DR position) → RESOLVED.
- Should-fix: references footer shape (five inline parenthetical asides) → RESOLVED at four locations; the fifth (line 32 `(D2 risk)`) was reworded in this mutation.
- Should-fix: edge-cases sub-section (§ Invariants and § Open questions) → RESOLVED via N/A justifications.
- Should-fix: audience-fit (Overview) → RESOLVED via lead sentence naming the audience.
- Should-fix: navigability (§ Lazy merge-on-read TL;DR Mechanics pointer) → RESOLVED.
- Iteration findings (three fragmented-header should-fix introduced by the above edits) → all RESOLVED in one re-iteration.
- Suggestion: per-section-length on § Lazy merge-on-read (289 lines) → INFORMATIONAL; under the 300 cap, content is template-exempt.

**Iterations**: 2 of 3 (initial apply landed all eight cold-read fixes; second iteration cleared the three fragmented-header findings introduced by the new prose; final mechanical run + cold-read re-verification both PASS within budget).

## Mutation 28 — 2026-05-28 — content-edit (design.md)

**Diff summary**: User-requested cold-read pass via `prompts/design-review.md` (whole-doc, phase1-creation) returned PASS with five suggestion-tier findings. User opted to apply all five in one batched `content-edit` mutation rather than five separate mutations. Per-file edits (`design.md` only; `design-mechanics.md` untouched):

- § Lazy merge-on-read TL;DR (line 330): appended one trailing sentence enumerating the section's subsections as a mini-roadmap ("per-shape classify, the TxDeltaCursor build dispatch, cross-view delta sharing, stream-pull unification, view output semantics, aggregate delta, aggregate side-tap, MATCH Etap A, MATCH multi-alias, and SKIP/LIMIT handling"). Closes the navigability gap where a skimmer hits the section header but cannot tell what's inside without scrolling.
- § Aggregate delta — AGGREGATE_* shapes (line 448): expanded the inline parenthetical gloss on `AggregateCacheTapStep` so the term carries a definition at first mention ("a new execution-plan step spliced upstream of `AggregateProjectionCalculationStep` that observes every contributing record before aggregation collapses them into a scalar row"). Previously the term was load-bearing 40 lines before its § Aggregate side-tap definition.
- § Cache key composition TL;DR (line 286): prepended a why-clause naming `STATEMENT_CACHE`'s pre-existing memoization as the reason the AST is the cheapest equality input on hand, so motivation precedes mechanism per `house-style.md § Why-before-what`. (Initial wording used the phrase "equality key" and triggered a fragmented-header should-fix because "key" is a content word in the heading "Cache key composition"; rewritten to "equality input on hand" in iteration 2 to drop "key" as a standalone token while keeping the why-clause.)
- § Known v1 limitations (line 32) + § Open questions deferred to execution (line 857): collapsed the duplicated D14 MIN/MAX cost-benefit numerics in both locations to cross-references at § Lazy merge-on-read → § Aggregate delta → "Memory-budget asymmetry" (the canonical derivation in design.md). Previously the same numerics (5 μs per HTTP request, ~3× memory growth, ~150 lines) appeared in three places. Breadcrumb path made explicit to name the intermediate § Aggregate delta subsection where the callout actually lives, per iteration-2 cold-read finding.
- § Invariants TL;DR (line 829): compressed a 197-word single-sentence enumeration of all ten invariants into a one-sentence scope statement ("Ten load-bearing properties the v1 implementation must hold, enumerated below. Each invariant carries an explicit test assertion in the track that introduces the relevant primitive."). The bulleted list immediately below already carries the per-invariant detail; the prior TL;DR was redundant restatement.

**Mechanical checks** (target=design, scope=whole-doc): PASS. Iteration 1 surfaced one should-fix (`dsc-ai-tell` fragmented-header at § Cache key composition: heading↔TL;DR overlap rose from 1/3 to 2/3 because the new opener introduced "key" as a standalone token via "equality key"). Iteration 2 reworded "equality key" → "equality input on hand" and the should-fix cleared. Final run reports `{blockers: 0, should_fix: 0, suggestions: 1}`. The lone suggestion is the pre-existing `per-section-length` on § Lazy merge-on-read (289 lines, under the 300 cap; content is template-bound state-machine tables and pseudocode per `house-style.md § Structural rules` section-length-cap exception, same disposition as Mutations 26 and 27).

**Cold-read** (scope: whole-doc): PASS. Fresh sub-agent against the post-fix document returned YES on the mental-model question with two new suggestion-tier findings, both byproducts of the edits in this mutation: (a) the new roadmap collapsed "aggregate delta with side-tap" into one item but the actual layout has two H3 subsections (`### Aggregate delta — AGGREGATE_* shapes` and `### Aggregate side-tap`); (b) the D14 cross-reference breadcrumbs named `§ Lazy merge-on-read → "Memory-budget asymmetry"` but the bold callout actually lives inside `### Aggregate delta — AGGREGATE_* shapes`. Both fixes applied in iteration 2 (roadmap → "aggregate delta, aggregate side-tap"; breadcrumb → `§ Lazy merge-on-read → § Aggregate delta → "Memory-budget asymmetry"` at lines 32 and 857). Re-run of mechanical after these fixes confirmed PASS with the same single per-section-length suggestion.

**Findings**:
- Cold-read suggestion 1 (navigability mini-roadmap for § Lazy merge-on-read) → RESOLVED (applied + iteration-2 refinement so each subsection is its own roadmap landmark).
- Cold-read suggestion 2 (glossary-introduction for `AggregateCacheTapStep` at first mention) → RESOLVED.
- Cold-read suggestion 3 (why-before-what at § Cache key composition TL;DR) → RESOLVED; iteration 2 cleared the fragmented-header mechanical finding introduced by the first wording.
- Cold-read suggestion 4 (D14 cost-benefit triple-occurrence consolidation) → RESOLVED at both duplicate sites; iteration-2 breadcrumb refinement names the intermediate § Aggregate delta subsection.
- Cold-read suggestion 5 (compress § Invariants TL;DR) → RESOLVED.
- Iteration finding (fragmented-header on § Cache key composition TL;DR introduced by Edit 3) → RESOLVED in iteration 2 by rewording.
- Iteration findings (two cold-read navigability suggestions on roadmap precision and breadcrumb precision) → RESOLVED in iteration 2.
- Suggestion: per-section-length on § Lazy merge-on-read (289 lines) → INFORMATIONAL; pre-existing, template-exempt per `house-style.md § Structural rules`.

**Iterations**: 2 of 3 (initial apply landed all five cold-read fixes; iteration 2 cleared the fragmented-header should-fix on § Cache key composition introduced by Edit 3 plus the two new cold-read navigability suggestions on roadmap/breadcrumb precision; final mechanical run + cold-read re-verification both PASS within budget).

## Mutation 29 — 2026-05-28 — structural-rewrite (design.md)

**Diff summary**: User-requested validation pass per YTDB-1033's proposed design-document rules (cold-reader sub-agent invoked against `design.md` + the YTDB-1033 issue summary, no other context). All YTDB-1033 calibration targets confirmed against § Overview: forward identifier references (D13/D14/D18/D21/I4/I7/K0_NONE/K1/Etap A/Etap B/Track 6a/Track 8), forward section references (§ Open questions deferred to execution, § Lazy merge-on-read, § Aggregate delta), prior-iteration content ("Why this approach was chosen over eager"), alternatives comparison (same subsection), per-section BLUF lead violation (audience disclaimer at the head of Overview), and first-use-definition violations (populate-version stamping, delta-cursor, skip-set, sorted inject-list, Etap A / Etap B). User chose the "Overview + calibration targets only" scope from a three-option `AskUserQuestion`; downstream prior-iteration phrasing in mechanism sections, undefined `SO4`/`SO5`/`cacheCodeDepth`/`L1/L2` residue, and the document-wide forward-identifier cleanup beyond Overview were deliberately deferred. Three edits applied to `design.md`:

- **Edit 1 — Overview replaced** (lines 4-38 in the pre-mutation file). The new Overview opens with a one-sentence BLUF naming what the design adds and behind which flag, then a two-sentence problem statement (Hub/DNQ duplicate-query load), then a plain-English approach paragraph that carries zero `D\d+` / `I\d+` / `K\d_\w+` / `Etap [A-Z]` / `Track \d+` identifier references, then a one-line enabling-primitives sentence, a knobs sentence, a pointer to § Known limitations, the companion-file pointer, and a structure roadmap including the new § Known limitations between § Invariants and § Open questions. Removed: the audience-disclaimer opener ("This design is for YouTrackDB query-engine maintainers and reviewers familiar with…"); the entire "Why this approach was chosen over eager" subsection (alternatives comparison + prior-iteration content); the embedded "Known v1 limitations" subsection (extracted to its own top-level section by Edit 2); mechanism preview detail (sorted-merge, populate-version filter algorithm, K0_NONE SKIP/LIMIT mechanism preview — all belong in § Lazy merge-on-read per Rule 7 section-job separation).
- **Edit 2 — New § Known limitations section inserted** between § Invariants and § Open questions deferred to execution. Carries the three correctness-bounded scope cuts previously embedded in Overview: pagination falls back to mutation-version gating, multi-alias MATCH CREATED deferred to a separate ADR, and MIN/MAX worst-case O(n) recompute. References footer lists D13/D14/D18. Iteration 1 added the `### Edge cases / Gotchas` subsection (with `N/A — each limitation above is itself a bounded scope cut…` matching the pattern at § Invariants and § Open questions) to complete the four-block per-section shape.
- **Edit 3 — K1 sharp-merge entry removed from § Core Concepts**. The entry's body explicitly stated its only purpose was to gloss the Overview phrase "no K1 dispatch"; with Edit 1 removing that phrase, the entry was orphaned and itself violated the prior-iteration ban. Updated the Core Concepts intro from "Eight load-bearing terms" to "Seven load-bearing terms" to match the new term count.

**Mechanical checks** (target=design, scope=whole-doc): PASS on the initial apply and on the iteration-1 re-run. Final run reports `{blockers: 0, should_fix: 0, suggestions: 1}`. The lone suggestion is the pre-existing `per-section-length` on § Lazy merge-on-read (289 lines, under the 300 cap; content is template-bound state-machine tables and pseudocode per `house-style.md § Structural rules` section-length-cap exception, same disposition as Mutations 26-28).

**Cold-read** (scope: whole-doc): PASS after iteration 1. Initial fresh-context sub-agent (read only `design.md`, `design-mechanics.md` for `Mechanics:` link resolution, and the plan + track files for `**Full design**` link resolution) returned YES on the mental-model question with two structural findings: a blocker for a dangling cross-reference at § Lazy merge-on-read TL;DR pointing at the removed "Why this approach was chosen over eager" subsection, and a should-fix for the missing `### Edge cases / Gotchas` subsection in the new § Known limitations. Both addressed in iteration 1: the TL;DR reference was rewritten to enumerate the three pillars of the simplification inline (`the cache never reacts to individual mutations, cached entries are frozen storage snapshots, and the view contract matches the existing OrderByStep blocking-materializer guarantee`); § Known limitations gained the `### Edge cases / Gotchas` block with `N/A` body. Iteration-1 verification cold-read returned PASS with zero new findings; explicitly checked for residual `K1 sharp-merge`, `K1 dispatch`, `Why this approach`, `chosen over eager`, and `Overview → "Why` strings and found none in either `design.md` or `design-mechanics.md`.

**Findings**:
- YTDB-1033 calibration target — Overview BLUF lead violation → RESOLVED (Edit 1).
- YTDB-1033 calibration target — Overview forward identifier references (D13/D14/D18/D21/I4/I7/K0_NONE/K1/Etap A/Etap B/Track 6a/Track 8) → RESOLVED (Edit 1; new Overview prose contains zero identifier codes, verified by grep).
- YTDB-1033 calibration target — Overview forward section references (§ Open questions deferred to execution, § Lazy merge-on-read, § Aggregate delta) → RESOLVED (Edit 1; only the structure roadmap line names downstream sections, which is the exempt roadmap form).
- YTDB-1033 calibration target — Overview prior-iteration content ("Why this approach was chosen over eager") → RESOLVED (Edit 1 dropped the subsection).
- YTDB-1033 calibration target — Overview alternatives comparison (same subsection) → RESOLVED (Edit 1).
- YTDB-1033 calibration target — Overview first-use-definition violations (populate-version stamping, delta-cursor, skip-set, sorted inject-list, Etap A / Etap B) → RESOLVED (Edit 1 dropped the mechanism preview that invoked these terms; the terms keep their first-use definitions at their § Core Concepts entries and at the mechanism sections that introduce them).
- Orphaned `K1 sharp-merge (prior iteration)` entry in § Core Concepts → RESOLVED (Edit 3).
- Iteration-1 blocker — dangling cross-reference at § Lazy merge-on-read TL;DR to the removed Overview subsection → RESOLVED.
- Iteration-1 should-fix — missing `### Edge cases / Gotchas` subsection in § Known limitations → RESOLVED.
- Suggestion: pre-existing `per-section-length` on § Lazy merge-on-read (289 lines) → INFORMATIONAL; pre-existing, template-exempt per `house-style.md § Structural rules`, same disposition as Mutations 26-28.
- Known carry-forward (out of scope per user decision): prior-iteration phrasing at L320 (Pause/resume), L431/L454/L456/L465/L571/L580 (Lazy merge-on-read), L617 (Cache invalidation), L684-685 (Non-determinism handling, the "K1 RECORD" / "K1-splice" references), L848 (Open questions); alternatives-style subsections "Why a denylist, not a feature flag" and "Why partial Etap B is the right v1 scope"; undefined `SO4` / `SO5` / `cacheCodeDepth` / `L1/L2` residue at L666/L668/L763/L827. These remain in the document as deferred cleanup; a follow-up mutation can address them once the document-wide scope is opted into.

**Iterations**: 2 of 3 (initial apply landed all three structural-rewrite edits and cleared the YTDB-1033 calibration targets; iteration 2 cleared the dangling cross-reference blocker and the missing `### Edge cases / Gotchas` should-fix surfaced by the initial cold-read; final mechanical + cold-read re-verification both PASS within budget).

## Mutation 30 — 2026-06-08 — structural-rewrite (design.md)

**Diff summary**: Inlined the decision records into each `## section`'s `### References` footer so `design.md` becomes the canonical carrier of decision rationale and `implementation-plan.md` (which held the D-records and invariants until now) can be deleted. Per a workflow-issue convention ("inline decision records into the DD"): each section's footer now carries the full inline decision record (bold decision title, then *Alternatives* / *Rationale* / *Risk* — a scannable summary, with the connected rationale walk left in the section body) at the decision's HOME section, and a one-line `Dn — gist → § Home` cross-reference at every non-home mention (introduce-once, reference-thereafter). Homes: D1 → Class Design; D2/D12/D22 → Cache key composition; D4/D15 → Pause/resume mechanics; D5-lazy/D8-lazy/D11/D19/D20/D21 → Lazy merge-on-read; D3/D18 → Cache invalidation; D6/D9 → Non-determinism handling; D7 → Memory bounds; D13/D14 → Open questions deferred to execution. The `### References` heading is kept deliberately — the rename to "Decisions & invariants" from the same issue is a repo-wide rule change that has NOT landed on `develop` (the mechanical checker still hard-codes `### References`), so renaming here would fail validation; this design adopts the inline-records half of the convention only. The central `## Invariants` section stays the home of I1-I10 (it already states each in full, so deleting the plan loses nothing); functional-section footers carry `In — gist → § Invariants` cross-references instead of restating invariant statements. Inline records deliberately omit the plan's "Implemented in: Track N" line, since track decomposition is a plan concern that regenerates from the frozen design; the plan-derived `Tracks:` footer lines were dropped for the same reason. The cold-read drove the self-carrying cleanup: the `D14 in implementation-plan.md proposes…` dangling reference now points at `§ Open questions deferred to execution`, the scripts Non-Goal that previously said "the plan declares them a Non-Goal" is now self-carried with its own rationale, and every `Track N` / `Tn` breadcrumb in the body and in the new records was stripped (keeping the behavioral/test claim, dropping the track-ownership label) so the design stands alone once the plan is deleted. The `@andrii0lomakin PR review` attribution in the D5-lazy record was dropped (the rationale stands on its own). `design.md` grew 872 → 898 lines (records are single long bullet lines).

**Mechanical checks** (target=design, scope=whole-doc): PASS — 0 blockers, 0 should-fix, 1 suggestion: pre-existing `per-section-length` on § Lazy merge-on-read (297 lines, under the 300 cap; content is template-bound state-machine tables and pseudocode per `house-style.md § Structural rules` section-length-cap exception, same disposition as Mutations 26-29 — the footer's six inline home records added ~8 lines to the prior 289). Checks were run with `--plan-path` / `--plan-dir` still present (the plan is deleted in a follow-up step outside this mutation), so the cross-file `**Full design**` ref check passed; no section was renamed, so those refs still resolve.

**Cold-read** (scope: whole-doc): PASS — comprehension YES. The reviewer confirmed all 32 `→ §` cross-references and 7 `§"…"` refs resolve to real headings, "introduce once, reference thereafter" held across all 19 records (no duplicated full record, no D14 home/reference contradiction), every invariant homed in § Invariants, and no `implementation-plan.md` / `plan/track-N.md` / `**Full design**` reference anywhere in `design.md`. The richer footer content was explicitly recognized as the intended convention, not flagged as a References-footer-shape violation. Findings: 2 should-fix (a body-prose lean on the soon-deleted plan for the scripts Non-Goal; `Track N` / `Tn` breadcrumbs that would dangle after plan deletion) + 1 suggestion (the PR-review attribution). All three resolved in iteration 2.

**Findings**:
- should-fix: body prose leaned on the soon-deleted plan for the scripts Non-Goal (former line 617) → RESOLVED (self-carried with its own rationale).
- should-fix: `Track N` / `Tn` breadcrumbs in the body and in the new inline records would dangle once the plan and `plan/track-*.md` files are deleted → RESOLVED (every track-ownership label stripped, behavioral/test claim kept; full-doc grep confirms zero remaining `Track N` / `Tn` / `track-N` references).
- suggestion: transient `@andrii0lomakin PR review` attribution in the D5-lazy record → RESOLVED (dropped).
- suggestion (mechanical, INFORMATIONAL): `per-section-length` on § Lazy merge-on-read (297 lines) → pre-existing, template-exempt, same disposition as Mutations 26-29.

**Iterations**: 2 of 3 (PASS — initial apply landed the 12 footer rewrites plus the dangling-reference fix and cleared mechanical with zero blockers/should-fix; iteration 2 applied the cold-read's two should-fix cleanups plus the suggestion, then re-ran mechanical (PASS) and grep-verified that no `Track` / `Tn` / plan reference survives. The cold-read was not re-spawned for iteration 2 because every fix was a pure removal of a flagged dangling label or a self-carrying rewrite of a single sentence, none of which can introduce a new comprehension or cross-reference defect — the iteration-1 comprehension PASS and cross-reference-integrity verdict are unaffected by label removal).

**Note — follow-up outside this mutation**: `implementation-plan.md` and `plan/track-1..3.md` are deleted immediately after this entry (per the user's "update design, delete the plan for now" directive). The design is now self-carrying for every decision and invariant; a regenerated plan will derive its decision records from `design.md` via the inline-records convention rather than holding its own copies.

## Mutation 31 — 2026-06-08 — content-edit (design.md)

**Diff summary**: Documented a param-cardinality gap in the K0-churn / overflow guard that the v1 mechanism leaves open, and recorded the bounded-`nonCacheableKeys` hardening as a v2 candidate. Behaviour is unchanged — three additive documentation bullets only. (1) § Cache invalidation → "### Edge cases / Gotchas — K0-version-fallback" gains a bullet noting that `CachedEntry.k0InvalidationCount` is per `(statement, params)` (entries key on the full `CacheKey`), so the 3-strike `nonCacheableKeys` short-circuit only fires for a re-issued same key; a parameterized K0_NONE statement re-issued with rotating param values hands each combo a fresh strike budget, so the short-circuit rarely triggers and every combo pays the full populate-then-invalidate cost. (2) § Memory bounds: the "Total per-tx memory bound" paragraph gains a clause noting that the formula covers `entries` + delta-cache pairs but excludes `nonCacheableKeys`, an uncapped per-tx `Set<CacheKey>` (cleared only at tx-end) that gains one permanent key per distinct overflowing or thrice-churned combo and retains each key's copied param map plus a strong reference to its `SQLStatement` AST — negligible for short tx, unbounded for long-lived high-param-cardinality tx. (3) § Open questions deferred to execution gains a v2 bullet "Bound `nonCacheableKeys` / per-statement K0-churn tracking (D7 + D18)" sketching two levers (an LRU/size cap on the bypass set; per-statement strike tracking) and noting both are D13-gated and not needed for v1 correctness. The change originated from a reviewer observation; the design is the canonical carrier post-Mutation-30, so the caveat lives here rather than in the deleted plan.

**Mechanical checks** (target=design, scope=whole-doc): PASS — 0 blockers, 0 should-fix, 1 suggestion (pre-existing template-exempt `per-section-length` on § Lazy merge-on-read, unchanged from Mutation 30). The initial run flagged one should-fix `dsc-ai-tell` (em-dash density: 2 unpaired em dashes in the new § Open questions bullet); resolved in the first iteration by replacing the second em dash with a relative clause, leaving the single title-dash that matches every sibling v2 bullet. Run without `--plan-path` / `--plan-dir` (the plan is deleted), so no cross-file ref check; the design carries no plan reference.

**Cold-read** (scope: bounded — § Cache invalidation, § Memory bounds, § Open questions, plus Overview + Core Concepts): NEEDS REVISION → PASS after iteration. The reviewer confirmed the three additions read coherently, all cross-references resolve (§ Memory bounds → Backpressure on overflow, § Open questions, D7/D18/D13, `k0InvalidationCount` on `CachedEntry`, CacheKey = (statement, params), the overflow + D18-churn paths into `nonCacheableKeys`), and the new bullets do NOT duplicate or contradict the adjacent class-scoped-invalidation (entry invalidation granularity) or weight-aware-LRU (entries-map eviction) v2 bullets — each addresses a distinct concern. One blocker: adding a fifth v2 candidate left the § Open questions TL;DR stale ("Six items … four in v1 scope", enumerating four candidates).

**Findings**:
- blocker: § Open questions TL;DR contradicted its own body after the new bullet (said "Six items … four in v1 scope", body now holds seven items / five v2 candidates) → RESOLVED (TL;DR updated to "Seven items … five in v1 scope" with the new candidate inserted in body order, per the reviewer's verbatim suggested replacement).
- suggestion (mechanical): `dsc-ai-tell` em-dash density on the new § Open questions bullet → RESOLVED (second em dash rephrased).
- suggestion (mechanical, INFORMATIONAL): `per-section-length` on § Lazy merge-on-read (297 lines) → pre-existing, template-exempt, same disposition as Mutations 26-30.

**Iterations**: 2 of 3 (PASS — iteration 1 applied the three bullets and cleared the em-dash should-fix; iteration 2 fixed the cold-read's TL;DR-count blocker with the reviewer's own prescribed text and re-ran mechanical (PASS). The cold-read was not re-spawned after the TL;DR fix because the applied text was the reviewer's verbatim suggested replacement for the sole blocker — a pure count/list correction with no new prose or cross-reference to re-assess).

## Mutation 32 — 2026-06-08 — structural-rewrite (design.md)

**Diff summary**: Applied the edge-mutation reconciliation correctness floor for `MATCH_TUPLE_MULTI`, closing a silent-wrong-result class a reviewer found: edge mutations on label-bound traversals (`.out/.in/.both(label)`) were unreconciled, so an edge CREATE left the new tuple missing (under-complete), an edge DELETE left a stale tuple (over-complete), and an UPDATE that flipped a record into a WHERE it did not previously bind was dropped (under-complete, also latent for vertices). The shape carries no mutation-version backstop (only K0_NONE does), so correctness rests on `classify` being conservative plus a complete delta-build. Fourteen coordinated hunks tell one story: (1) `effectiveFromClasses` folds in the edge classes named by `.out/.in/.both(label)` traversals and bound edge aliases, so edge `RecordOperation`s stop being class-filtered out; (2) the `buildForMatchMulti` tombstone pre-scan fires on CREATE of any class in the set and on edge-class DELETE; (3) the UPDATED branch adds an update-into-match tombstone (a record newly satisfying an alias WHERE it does not bind), which also closes the latent vertex under-complete gap; (4) vertex DELETE and bound-edge / vertex pass→fail UPDATE stay incremental via `reverseIndex`; (5) `classify` routes any pattern whose WHERE dereferences a link path into a class outside the read set to K0_NONE (a broader latent gap, also affecting RECORD shape); (6) the covers/does-not-cover/why-floor prose, D8-lazy, I4's test matrix, known-limitations, and open-questions all restate the same floor; (7) two refinements, constrained-pattern-walk CREATE discovery and incremental non-tombstone edge-DELETE via an endpoint-content reverse index, are deferred to a separate Etap B ADR as correctness-neutral and D13-gated, with the rejected full-provenance alternative recorded. Design-only change: no cache code exists on this branch yet, so this is behaviour-defining for the unimplemented shape.

**Mechanical checks** (target=design, scope=whole-doc): PASS — 0 blockers, 1 should-fix (`per-section-length` on § Lazy merge-on-read, 315 lines). The initial run also flagged 2 `dsc-ai-tell` em-dash-density findings (K0_NONE bullet and I4 test), both resolved in iteration 1 by replacing the second unpaired em dash with `;` and a period. Run without `--plan-path` / `--plan-dir` (no plan exists; the design is the canonical carrier), so no cross-file ref check.

**Cold-read** (scope: whole-doc): NEEDS REVISION → PASS after iteration. The reviewer confirmed the single story holds consistently across all six surfaces (glossary, classify gate, multi-alias section, D8-lazy, I4, known-limitations + open-questions), the pseudocode composes correctly (the step-2 pre-scan short-circuits every edge-class DELETE to TOMBSTONE before step 3, so the step-3 DELETED branch only ever sees vertex DELETEs as the prose claims), the open-questions TL;DR count still matches its bullets, and the `Mechanics:` link resolves. One should-fix: a stale `tombstoned` field definition still naming CREATE as the only trigger.

**Findings**:
- should-fix (cold-read): the `tombstoned` field definition named only CREATE as the trigger, contradicting the pre-scan (edge-class DELETE) and UPDATED branch (update-into-match) 20 lines below → RESOLVED iteration 2, rewritten to name all three triggers using the reviewer's verbatim suggested text.
- should-fix (mechanical): `dsc-ai-tell` em-dash density on the K0_NONE bullet and the I4 test sentence (2 unpaired em dashes each, the added clause plus the bullet/invariant label dash) → RESOLVED iteration 1.
- should-fix (mechanical): `per-section-length` on § Lazy merge-on-read (297 → 315 lines after the additions) → CARRIED as known debt. Substantive correctness content, not padding (house-style padding-based criterion); same template-exempt disposition the section has carried since Mutation 26. Recommended structural resolution: relocate the `buildForMatchMulti` pseudocode to `design-mechanics.md` (consistent with RECORD `view.next()` already living there) in a focused follow-up.
- suggestion (cold-read): the `rid-binds-alias` pseudocode helper was undefined → RESOLVED, defined inline on the `reverseIndex` field as read from the cached tuple `Result` via `getProperty(alias)`.
- suggestion (cold-read): CREATE/CREATED action-vs-op-status drift → NOT actioned (cosmetic; the usage is unambiguous and matches sibling bullets; chasing it risks churn).

**Iterations**: 2 of 3 (PASS — iteration 1 applied the 14-hunk floor edit and cleared both em-dash should-fixes; iteration 2 cleared the cold-read's stale-`tombstoned`-definition should-fix with the reviewer's verbatim text and addressed the `rid-binds-alias` suggestion. Cold-read was not re-spawned after iteration 2 because the fixes were the reviewer's own prescribed single-line corrections with no new prose to re-assess. The `per-section-length` should-fix is carried as logged substantive-content debt with a named structural follow-up).

## Mutation 33 — 2026-06-08 — content relocation (design.md + design-mechanics.md)

**Diff summary**: Closed the Mutation-32 section-length debt by relocating the `buildForMatchMulti` two-pass pseudocode from `design.md` § Lazy merge-on-read → MATCH multi-alias (partial Etap B in v1) into `design-mechanics.md` under a matching-named subsection, consistent with the RECORD-shape `view.next()` pseudocode already living in mechanics. `design.md` keeps the mechanism overview, the field definitions, the covers / does-not-cover / why-floor prose, and a one-line pointer to the mechanics block; the verbatim algorithm moves to the agent-targeted companion. § Lazy merge-on-read drops from 315 to 263 lines, under the ~300 cap.

**Mechanical checks** (target=both, scope=whole-doc): PASS — 0 blockers, 0 should-fix, 1 suggestion (`per-section-length` warn at 263 lines, under cap). An intermediate run flagged one should-fix `reverse-direction-ref`: the relocated mechanics intro linked back to `design.md §"..."`, violating the one-way design→mechanics cross-reference rule; resolved by rewording to a plain section mention. The forward `design-mechanics.md §"MATCH multi-alias (partial Etap B in v1)"` pointer in design.md resolves.

**Cold-read** (scope: skipped): the design.md change is a verbatim relocation of content already cold-read in Mutation 32 (the pseudocode is byte-identical, only its file moved and a pointer replaced it), and the mechanics file is agent-targeted long-form that the discipline exempts from cold-read (deferred to the next design-sync). No semantic change to assess.

**Findings**:
- should-fix (mechanical): `reverse-direction-ref` at design-mechanics.md — the relocated intro linked back to `design.md §"..."` → RESOLVED (reworded to a non-link section mention; cross-refs now flow design→mechanics only).
- suggestion (mechanical): `per-section-length` on § Lazy merge-on-read (263 lines) → INFORMATIONAL, under the 300 cap; the Mutation-32 overage is cleared.

**Iterations**: 1 of 3 (PASS — relocation applied, the reverse-ref should-fix cleared in the same round, mechanical re-run clean).

## Mutation 34 — 2026-06-08 — structural-rewrite (design.md)

**Diff summary**: Fixed a D21-collapse correctness hole in the AGGREGATE_* delta-build, the same class the RECORD path solved with `cached_at_build`. `applyMutation` dispatched on `op.type`, but `addRecordOperation` collapse keeps a pre-populate CREATE labelled CREATED while bumping its version past `populateMutationVersion`, so a CREATED op can carry a record already in `contributingValues` (observed by the blocking populate tap). A status-keyed dispatch misread it as brand-new and no-op'd on `match_after=false`, leaving a stale contributor and a wrong scalar / extremumRid — a silent wrong MIN/MAX/SUM/AVG/COUNT/COUNT_DISTINCT. Four coordinated edits: (1) a governing principle — every aggregate `applyMutation` derives `was_contributing = contributingValues.containsKey(rid)` (COUNT(*): `contributingRids.contains(rid)`) and `now_contributing = (status==DELETED) ? false : match_after`, dispatching on the `(was_contributing → now_contributing)` transition, with `op.type` used only to fold DELETED into now_contributing=false; (2) the MIN/MAX table reframed from `(op.type, match_after, was_extremum)` keys to transition keys (F→T / F→F / T→F / T→T), collapsing the two T→F deletes/leaves into one row so the O(n) count drops from three to two; (3) the COUNT_DISTINCT F→T / T→F triggers de-statused to membership; (4) I4 sharpened to require, per aggregate kind, a collapse-CREATE-already-contributing test. The fix covers all aggregate kinds (the original reviewer scoped it to MIN/MAX, but COUNT/SUM/AVG/COUNT_DISTINCT share the hole) and is cleaner than RECORD's, because the blocking tap makes `contributingValues` complete at populate, so no stream-pull unification is needed.

**Mechanical checks** (target=design, scope=whole-doc): PASS — 0 blockers, 0 should-fix, 1 suggestion (`per-section-length` warn on § Lazy merge-on-read, under the 300 cap).

**Cold-read** (scope: whole-doc): NEEDS REVISION → PASS after iteration. The reviewer confirmed the governing principle, the reframed MIN/MAX table (9 rows, exactly two O(n), matching the intro "only two" and the post-table "Both O(n) cases"), and the COUNT_DISTINCT triggers are internally consistent; that the reframe preserves every action of the old status-keyed table and additionally routes the collapsed-CREATE case correctly (`was_contributing=true` → T→F / T→T, never F→); and that SUM/AVG (already transition-framed) and COUNT(*) are covered by the governing principle, with the `applyMutation(rec, status, matchAfter)` signature correctly retaining `status` to fold DELETED. One should-fix: a stale case count in a References footer.

**Findings**:
- should-fix (cold-read): the D14 References-footer entry still read "only 3 of 11 applyMutation cases hit the O(n) scan", stale after the table reframe → RESOLVED ("only 2 of the 9 MIN/MAX applyMutation transitions"), matching the intro and post-table prose.
- suggestion (mechanical): `per-section-length` on § Lazy merge-on-read → INFORMATIONAL, under the 300 cap.

**Iterations**: 1 of 3 (PASS — four-hunk fix applied, the cold-read's stale-count should-fix cleared with the reviewer's suggested value, mechanical re-run clean).

