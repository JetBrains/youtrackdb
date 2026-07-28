<!-- MANIFEST
findings: 6   severity: {blocker: 0, should-fix: 3, suggestion: 3}
index:
  - {id: TB1, sev: should-fix, loc: YTDBMatchPlanStepTest.java:921, anchor: "### TB1 ", cert: C1, basis: "flat-map laziness verify is non-falsifiable on a 1-row stream; eager stage passes too"}
  - {id: TB2, sev: suggestion, loc: YTDBMatchPlanStepTest.java:980, anchor: "### TB2 ", cert: C2, basis: "startsWith on a fully-deterministic group payload where isEqualTo is available"}
  - {id: TC1, sev: should-fix, loc: AbstractMatchPlanStep.java:331, anchor: "### TC1 ", cert: C3, basis: "null-as-payload never driven through rowProjectionSource; hasBuffered regression ships green"}
  - {id: TC2, sev: should-fix, loc: AbstractMatchPlanStep.java:249, anchor: "### TC2 ", cert: C4, basis: "reopen-rebuild of shapedPayloads (Track 8 advance-on-drain dependency) untested with a stateful op"}
  - {id: TC3, sev: suggestion, loc: ListShapingOp.java:38, anchor: "### TC3 ", cert: C5, basis: "N->1 window-drain cardinality class (fold/tail) never exercised by a placeholder op"}
  - {id: TC4, sev: suggestion, loc: ResultShaping.java:61, anchor: "### TC4 ", cert: C6, basis: "no test that the seven pre-existing withX builders preserve listShapingOps"}
evidence_base: {section: "## Evidence base", certs: 8, matches: 6}
cert_index:
  - {id: C1, verdict: CONFIRMED, anchor: "#### C1 "}
  - {id: C2, verdict: CONFIRMED, anchor: "#### C2 "}
  - {id: C3, verdict: CONFIRMED, anchor: "#### C3 "}
  - {id: C4, verdict: CONFIRMED, anchor: "#### C4 "}
  - {id: C5, verdict: CONFIRMED, anchor: "#### C5 "}
  - {id: C6, verdict: CONFIRMED, anchor: "#### C6 "}
  - {id: C7, verdict: REFUTED, anchor: "#### C7 "}
  - {id: C8, verdict: REFUTED, anchor: "#### C8 "}
flags: [CONTRACT_OK]
-->

## Findings

### TB1 [should-fix] Flat-map "laziness" assertion is non-falsifiable on a single-row stream

**File:** `core/src/test/java/com/jetbrains/youtrackdb/internal/core/gremlin/translator/step/YTDBMatchPlanStepTest.java`, method `listShaping_flatMapOp_oneRowYieldsTwoTraversers_lazily` (line 903; assertions at 921 and 931).

**Issue:** The test name and both `verify(stream, times(1)).next(ctx)` assertions advertise that the cardinality-changing op streams lazily, but the stubbed stream holds exactly one row (`when(stream.hasNext(ctx)).thenReturn(true, false)`). With one source row, an eager collect-apply-emit stage would also pull `stream.next(ctx)` exactly once, so the assertion passes whether the framework is lazy or eager. The laziness claim (the property adversarial A3 / risk R3 care about) is not actually pinned by this test.

**Evidence:** FALSIFIABILITY CHECK — mutation: replace the structural pipe in `applyListShaping` with an eager `var all = new ArrayList<>(); source.forEachRemaining(all::add); return applyOps(all.iterator());`. Draining a 1-row source calls `stream.next` once → both `times(1)` verifies still hold → the test PASSES the mutation. The cardinality assertions (`first`/`second` equal `"X:5"`, `NoSuchElementException` on the third pull) remain sound and do catch a row-mapper regression; only the laziness sub-claim is vacuous. Genuine cross-row laziness is pinned separately by `listShaping_emptyOps_...` (line 876), which uses three rows — the flat-map test cannot show it with one.

**Missing behavior:** that emitting row 1's expansion does not eagerly pull row 2 (streaming laziness of a 1→N stage across source rows).

**Suggested fix:**
```java
when(stream.hasNext(ctx)).thenReturn(true, true, false);
when(stream.next(ctx)).thenReturn(row1, row2);
when(row1.getPropertyNames()).thenReturn(List.of("c"));
when(row1.getProperty("c")).thenReturn(5L);
when(row2.getPropertyNames()).thenReturn(List.of("c"));
when(row2.getProperty("c")).thenReturn(6L);
var step = shapedStep("v", BoundaryOutputType.SCALAR,
    ResultShaping.NONE.withListShapingOps(List.of(new TagRepeatOp("X", 2))));

assertThat(step.processNextStart().get()).isEqualTo("X:5");
verify(stream, times(1)).next(ctx);            // row2 NOT pulled yet — the expansion streamed
assertThat(step.processNextStart().get()).isEqualTo("X:5"); // 2nd copy served from the op buffer
verify(stream, times(1)).next(ctx);            // still no extra source pull
assertThat(step.processNextStart().get()).isEqualTo("X:6"); // only now is row2 pulled
verify(stream, times(2)).next(ctx);
```

### TB2 [suggestion] Group-barrier payload asserted with `startsWith` where `isEqualTo` is available

**File:** `YTDBMatchPlanStepTest.java`, method `listShaping_appliesOnGroupBarrierPath` (line 957; assertion at line 980).

**Issue:** `assertThat(String.valueOf(first)).startsWith("G:{k1=7}")` uses a prefix match on a fully deterministic payload. `assertThat(first).isEqualTo(second)` only checks the two copies match each other, not the exact value. A regression that appended trailing garbage or reshaped the accumulated map would slip past a prefix check.

**Evidence:** ASSERTION PRECISION CHECK — production value: `convertMapColumn("key","k1")` → `"k1"`, `convertGroupValue(7L)` → `7L`, so the drained `LinkedHashMap` is `{k1=7}` and `TagRepeatOp("G",2)` emits `"G:{k1=7}"`. The exact string is known, so a PRECISE `isEqualTo` is available and the `startsWith` is SHALLOW.

**Missing behavior:** the exact accumulated-map payload string on both emitted copies.

**Suggested fix:**
```java
assertThat(first).isEqualTo("G:{k1=7}");
assertThat(second).isEqualTo("G:{k1=7}");
```

### TC1 [should-fix] Null-as-legitimate-payload never driven through `rowProjectionSource`

**File:** `YTDBMatchPlanStepTest.java` — no test drives a null payload through `processNextStart`.

**Production code:** `AbstractMatchPlanStep.java:326-365` (`rowProjectionSource`; the `hasBuffered` flag at :331 / :342 / :355) and `projectVertex` (returns `null` when the alias binds no vertex).

**Missing scenario:** an ELEMENT row whose alias binds no vertex (an optional / unmatched node → `projectVertex` returns `null` → `projectOrSkip` returns `null`, which is *not* the `SKIP` sentinel) driven through `processNextStart`, asserting a traverser carrying `null` is emitted — not dropped and not read as exhaustion — and that ordering is preserved when a null-projecting row precedes a non-null one.

**Why it matters:** `rowProjectionSource` distinguishes "a legitimately-null payload is buffered" from "nothing buffered" via the boolean `hasBuffered` field, precisely because `null` is a valid payload (documented on `projectVertex` and called out in the Step 2 episode as the reason `hasBuffered` exists rather than a null sentinel). A regression to a null sentinel (`bufferedPayload != null` as the buffered test) would treat a null-projecting row as "not buffered": `hasNext()` would loop past it, silently dropping the null optional and shifting the emitted sequence. Nothing currently exercises a null payload through the new lazy iterator, so that regression ships green.

**Evidence:** INPUT DOMAIN TABLE — `projectOrSkip` return ∈ {vertex, `null` (optional miss), `SKIP` (drop)} crossed with `rowProjectionSource`. The `null` case is NOT tested through `processNextStart`. `projectElement_missingAlias_returnsNull` (line 287) exercises `projectElement` in isolation (returns `null`) but never routes that `null` through `rowProjectionSource` / `hasBuffered`.

**Refutation considered:** (a) reachability — `optional` is a Phase-2 non-goal, so a recognised traversal may not emit a null ELEMENT binding *today*; but the null branch is live, documented production behaviour of the new base and the sole reason `hasBuffered` exists, and Track 8 union revives multi-row bindings where a child may not bind the alias. (b) indirect coverage — `ProjectionEquivalenceTest` covers recognised shapes, but none produces a null ELEMENT binding through `rowProjectionSource`. Gap confirmed.

**Suggested test:**
```java
@Test
public void listShaping_nullPayloadIsEmittedNotDropped_andOrderPreserved() {
  var row1 = mock(Result.class); // optional miss → null vertex
  var row2 = mock(Result.class); // real vertex
  var rawVertex = mock(com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex.class);
  when(stream.hasNext(ctx)).thenReturn(true, true, false);
  when(stream.next(ctx)).thenReturn(row1, row2);
  when(row1.getVertex("v")).thenReturn(null);   // legitimate null payload
  when(row2.getVertex("v")).thenReturn(rawVertex);

  var step = elementStep("v");

  var first = step.processNextStart().get();     // must be the null payload, not row2
  var second = step.processNextStart().get();
  assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);

  assertThat(first).isNull();                    // null buffered + emitted via hasBuffered
  assertThat(assertRawEntityOf(second)).isSameAs(rawVertex);
}
```

### TC2 [should-fix] Reopen/clone rebuild of `shapedPayloads` untested with a stateful op

**File:** `YTDBMatchPlanStepTest.java` — every reset/clone test uses `ResultShaping.NONE` (empty ops).

**Production code:** `AbstractMatchPlanStep.java:249` (`shapedPayloads = null;` on the NEW/REARMED reopen), with the field also nulled at :457 / :475 / :555 (release / clone).

**Missing scenario:** a partial-consume → `reset()` → reopen with a non-empty, internally-buffering op (e.g. `TagRepeatOp`), asserting the reopen rebuilds `shapedPayloads` fresh (re-projects from the rewound plan) rather than resuming the stale op iterator's leftover buffered copies.

**Why it matters:** `shapedPayloads` is per-arming state, and Track 8's advance-on-drain path is explicitly built on line 249 — the Step 2 episode and Surprises log state that a per-child-plan reopen "goes through the NEW / REARMED branch, which already rebuilds `shapedPayloads` fresh, so each child plan gets its own shaped iterator." A regression removing the reopen-null reuses the stale iterator (leftover buffered payloads; for the empty-op case a `rowProjectionSource` still closed over the previous arming's stream), producing wrong results or reads against a released stream. The existing `reset_afterPartialConsume_deferStreamClose_thenReRunsKeepingPlan` (line 774) cannot catch this: it reuses one stateless `stream` mock across armings (`plan.start()` always returns the same mock) and asserts only interaction counts, never post-reset payload content, so a stale iterator over the same mock still "works."

**Evidence:** FALSIFIABILITY CHECK — mutation: delete `shapedPayloads = null;` at :249. With a buffering op and a partial consume, the post-reset first pull serves the op's leftover buffered copy instead of a freshly-projected one, and the underlying stream is not re-pulled. The empty-op reset test PASSES the mutation (shared stateless mock, count-only assertions). Gap confirmed.

**Refutation considered:** clone isolation of `shapedPayloads` as a standalone finding is refuted — it is nulled in `resetLifecycleForClone` (:555) *and* again at :249 on the driven path, doubly protecting the empty-op clone lifecycle already covered by `clone_startsOwnPlanCopyIndependentlyOfOriginal` and `clone_twoClonesDrivenConcurrently`. The one residual untested surface is the stateful-op reopen, captured here (see C8).

**Suggested test:** (the `stream.next` count is the airtight discriminator — a rebuilt iterator re-projects the row, a resumed one serves its buffer without touching the stream)
```java
@Test
public void listShaping_resetAfterPartialConsume_rebuildsShapedIteratorFresh() {
  var row = mock(Result.class);
  when(stream.hasNext(ctx)).thenReturn(true, false, true, false); // arming1: 1 row; arming2: 1 row
  when(stream.next(ctx)).thenReturn(row);
  when(row.getPropertyNames()).thenReturn(List.of("c"));
  when(row.getProperty("c")).thenReturn(5L);

  var step = shapedStep("v", BoundaryOutputType.SCALAR,
      ResultShaping.NONE.withListShapingOps(List.of(new TagRepeatOp("X", 3))));

  assertThat(step.processNextStart().get()).isEqualTo("X:5"); // 1 of 3; 2 left in the op buffer
  step.reset();                                                // arming 1 abandoned mid-buffer

  assertThat(step.processNextStart().get()).isEqualTo("X:5"); // fresh projection, not the leftover
  verify(stream, times(2)).next(ctx);   // rebuilt re-projects (2); a resumed stale buffer would be 1
  verify(plan, times(2)).start();
  verify(plan, times(1)).reset(ctx);
}
```

### TC3 [suggestion] N→1 / window-drain cardinality class (fold / tail) never exercised

**File:** `YTDBMatchPlanStepTest.java` — the only placeholder op, `TagRepeatOp`, is a 1→N flat-map (or 1→1 map at `times=1`); no op drains N→1.

**Production code:** `ListShapingOp.java:29-49` (the contract Javadoc names three cardinality classes, including "N→1 / window drain"); `AbstractMatchPlanStep.java:255-262` (`openShapedPayloads` built inside `processNextStart`'s try so an eager drain failure releases the plan).

**Missing scenario:** an N→1 window-drain placeholder op — the class `fold` (→ `LIST`) and `tail` actually use — driven through `processNextStart`, asserting the many source rows collapse to a bounded output, and (ideally) a second test where the op throws mid-drain and the plan is released.

**Why it matters:** the Decision Log and the `ListShapingOp` Javadoc justify the stream-stage-not-row-mapper contract precisely by the N→1 drain class. Track 9's `fold` and `tail` eagerly consume the whole upstream inside `processNextStart`'s try. Today that eager-drain-inside-try + post-drain stream-release path is exercised only by the group-barrier source (`accumulatedGroupMapSource`) and never by a list-shaping op, so the class the plan singles out as the reason for the contract is the one class left unverified for the op path.

**Evidence:** INPUT DOMAIN TABLE — cardinality class × placeholder op: 1→1 (`TagRepeatOp` `times=1`, declared-order test) tested; 1→N (`times=2`) tested; N→1 drain NOT tested; N→0 (`tail(0)` "emits nothing") NOT tested.

**Refutation considered:** the framework is structurally generic (it chains `op.apply(iterator)`), so the 1→N test already proves "not a row-mapper" and satisfies the literal acceptance line — hence suggestion, not should-fix. The distinct, unverified interaction is the N→1 drain's eager consume inside the try followed by stream release, which Track 9 leans on directly.

**Suggested test:**
```java
private record DrainToSizeOp() implements ListShapingOp { // N→1 window drain
  @Override public Iterator<Object> apply(Iterator<Object> upstream) {
    return new Iterator<>() {
      private Boolean emitted; private Object value;
      @Override public boolean hasNext() {
        if (emitted == null) { long n = 0; while (upstream.hasNext()) { upstream.next(); n++; } value = n; emitted = false; }
        return !emitted;
      }
      @Override public Object next() { if (!hasNext()) throw new NoSuchElementException(); emitted = true; return value; }
    };
  }
}

@Test
public void listShaping_drainOp_manyRowsCollapseToOne() {
  var r1 = mock(Result.class); var r2 = mock(Result.class); var r3 = mock(Result.class);
  when(stream.hasNext(ctx)).thenReturn(true, true, true, false);
  when(stream.next(ctx)).thenReturn(r1, r2, r3);
  for (var r : List.of(r1, r2, r3)) {
    when(r.getPropertyNames()).thenReturn(List.of("c"));
    when(r.getProperty("c")).thenReturn(1L);
  }
  var step = shapedStep("v", BoundaryOutputType.SCALAR,
      ResultShaping.NONE.withListShapingOps(List.of(new DrainToSizeOp())));

  assertThat(step.processNextStart().get()).isEqualTo(3L); // three rows drained into one payload
  assertThatExceptionOfType(NoSuchElementException.class).isThrownBy(step::processNextStart);
  verify(stream, times(3)).next(ctx);
}
```

### TC4 [suggestion] No test that the seven pre-existing `withX` builders preserve `listShapingOps`

**File:** `YTDBMatchPlanStepTest.java` / `WalkerContextResultShapingTest.java` — neither pins it.

**Production code:** `ResultShaping.java:61-90` — the seven pre-existing `withX` builders were each edited to thread `listShapingOps` as the new 8th constructor argument.

**Missing scenario:** assert each of the seven pre-existing `withX` builders preserves a previously-set `listShapingOps` (e.g. `NONE.withListShapingOps(ops).withDropNullRows(true).listShapingOps()` equals `ops`), symmetric to the already-covered direction.

**Why it matters:** the seven `withX` methods were mechanically edited. Omitting the arg is a compile error (8-arg canonical constructor), but a copy-paste that passes `List.of()` instead of `listShapingOps` would silently reset the ops. Terminators chain flags and ops in arbitrary order (Track 6 pins flags, Track 9 will pin ops), so a `withX` that drops the ops corrupts any traversal pinning ops before a later flag override.

**Evidence:** ASSERTION PRECISION CHECK — only `NONE.withDropNullRows(true).withDropOnAbsent(true)` is exercised (`WalkerContextResultShapingTest:37`). `listShaping_appliesOnGroupBarrierPath` chains `withAccumulateMap(true)` *then* `withListShapingOps(...)`, so it proves only the `withListShapingOps → accumulateMap` direction, never a `withX → listShapingOps` direction.

**Refutation considered:** an omitted trailing arg cannot compile, lowering the risk to a silent value-swap only — hence suggestion.

**Suggested test:**
```java
@Test
public void resultShaping_withBuilders_preserveListShapingOps() {
  var ops = List.<ListShapingOp>of(new TagRepeatOp("X", 1));
  var base = ResultShaping.NONE.withListShapingOps(ops);
  assertThat(base.withDropNullRows(true).listShapingOps()).isEqualTo(ops);
  assertThat(base.withDropOnAbsent(true).listShapingOps()).isEqualTo(ops);
  assertThat(base.withPresencePropertyKeys(List.of("k")).listShapingOps()).isEqualTo(ops);
  assertThat(base.withWrapMapValuesInLists(true).listShapingOps()).isEqualTo(ops);
  assertThat(base.withAccumulateMap(true).listShapingOps()).isEqualTo(ops);
  assertThat(base.withUnwrapSingletonMap(true).listShapingOps()).isEqualTo(ops);
  assertThat(base.withElementMapTokens(true).listShapingOps()).isEqualTo(ops);
}
```

## Evidence base

#### C1 [TB1] CONFIRMED
1-row stubbed stream (`hasNext` → true, false) makes `verify(stream, times(1)).next(ctx)` pass for both a lazy pipe and an eager collect-apply-emit stage; the laziness claim is unfalsifiable while the cardinality assertions stay sound.

#### C2 [TB2] CONFIRMED
Group payload is deterministic (`"G:{k1=7}"`); `startsWith` + copy-equality is strictly weaker than the available `isEqualTo`.

#### C3 [TC1] CONFIRMED
`projectVertex` returns `null` for an unmatched alias and `rowProjectionSource` buffers it via `hasBuffered`; no test drives a null payload through `processNextStart`, so a revert to a null sentinel drops the null optional undetected.

#### C4 [TC2] CONFIRMED
Deleting `shapedPayloads = null;` at :249 is not caught by any reset/clone test — all use empty ops over a shared stateless `stream` mock with count-only assertions; a stateful-op reopen with a `stream.next` count discriminator is needed.

#### C5 [TC3] CONFIRMED
Placeholder ops cover 1→1 and 1→N only; the N→1 window-drain class (fold/tail), and its eager-drain-inside-try + release path, is never exercised by a list-shaping op.

#### C6 [TC4] CONFIRMED
Only `withListShapingOps → accumulateMap` preservation is indirectly covered; no test proves the seven `withX` builders carry a pre-set `listShapingOps` forward (silent `List.of()` value-swap risk).

#### C7 [candidate, empty-op group-barrier path untested] REFUTED
Candidate: the refactored `accumulatedGroupMapSource` empty-op path (every `group()` / `groupCount()` today drains to one map and emits one traverser through the new bypass) is not unit-tested in `YTDBMatchPlanStepTest`. Checked `ProjectionEquivalenceTest`: `group_bare_matchNative`, `group_byName_matchNative`, `groupCount_bare_matchNative`, `groupCount_byName_matchNative` run the `accumulateMap` path end-to-end against a real graph with multiset equivalence to native Gremlin. The behaviour-preserving group path is covered by the equivalence suite the acceptance criteria require green; no finding.

#### C8 [candidate, clone isolation of shapedPayloads] REFUTED
Candidate: clone does not isolate `shapedPayloads`, so a clone could iterate the original's stale shaped iterator. Traced: `resetLifecycleForClone` (:555) nulls `shapedPayloads` on the fresh clone, and `processNextStart`'s NEW/REARMED branch nulls it again (:249) before first use on the driven path — doubly protected. Existing `clone_startsOwnPlanCopyIndependentlyOfOriginal` and `clone_twoClonesDrivenConcurrently_eachRunsOwnPlanCopy` cover the empty-op clone lifecycle. The only residual untested surface is a stateful-op reopen, which is raised as TC2 (C4) rather than a separate clone finding.
