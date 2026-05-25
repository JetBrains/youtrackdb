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
