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
