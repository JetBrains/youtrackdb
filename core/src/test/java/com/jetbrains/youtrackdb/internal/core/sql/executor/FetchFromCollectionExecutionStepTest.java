package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link FetchFromCollectionExecutionStep}, the source step that scans a
 * single numeric collection ID rather than a whole class.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Both constructors (2-arg without planning info, 4-arg with planning info).
 *   <li>{@link FetchFromCollectionExecutionStep#setOrder(Object)} toggles ASC/DESC; default (null)
 *       iterates ASC.
 *   <li>{@code internalStart} iterates the records stored in the target collection and drains a
 *       predecessor step for side effects before iterating.
 *   <li>{@code prettyPrint} renders "+ FETCH FROM COLLECTION {id} {ASC|DESC}" correctly with and
 *       without profiling, with indentation, and distinguishes null order from explicit ASC.
 *   <li>{@code serialize}/{@code deserialize} round-trips {@code collectionId} and {@code order}
 *       for all three order states (null, ASC, DESC); deserialization failures wrap into
 *       {@link CommandExecutionException}.
 *   <li>{@code canBeCached} always returns {@code true}.
 *   <li>{@code copy} preserves the collection ID, profiling flag, and planning info (deep-copies
 *       the planning info via {@code QueryPlanningInfo.copy()} when non-null, passes null through
 *       when null).
 * </ul>
 */
public class FetchFromCollectionExecutionStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: scan semantics
  // =========================================================================

  /**
   * With default order (null → ASC per the {@code !ORDER_DESC.equals(order)} branch in
   * {@code internalStart}), the step iterates records from the target collection in ascending
   * collection-position order.
   *
   * <p>Note: YouTrackDB distributes a class's records across multiple polymorphic collections.
   * We seed enough records to guarantee one collection has at least three, then scan that
   * collection and verify (1) all results belong to it and (2) the positions are strictly
   * ascending.
   */
  @Test
  public void defaultOrderIteratesAscending() {
    var clazz = createClassInstance();

    session.begin();
    var allRids = new ArrayList<RID>();
    // Seed enough records to guarantee at least one collection has 3 insertions across the
    // round-robin distribution.
    for (var i = 0; i < 24; i++) {
      allRids.add(session.newEntity(clazz.getName()).getIdentity());
    }
    session.commit();

    // Pick the first collection ID that holds at least 3 of the seeded records.
    var collectionId = pickCollectionWithAtLeast(allRids, 3);
    // TB5 tightening: compute the exact expected subset so a mutation that silently drops
    // records (e.g. skips alternating rows) is caught, not just "size >= 3".
    var expected = allRids.stream()
        .filter(r -> r.getCollectionId() == collectionId)
        .sorted(Comparator.comparingLong(RID::getCollectionPosition))
        .toList();

    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(collectionId, ctx, false);
    // setOrder left unset: the internalStart branch !ORDER_DESC.equals(null) → true → ascending.

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var rids = results.stream().map(Result::getIdentity).toList();

      assertThat(rids).containsExactlyElementsOf(expected);
      assertThat(rids.size()).as("enough records to make ASC observable")
          .isGreaterThanOrEqualTo(3);
    } finally {
      session.rollback();
    }
  }

  /**
   * With {@code setOrder(ORDER_DESC)}, the step iterates records from the target collection in
   * strictly descending collection-position order. Cross-checked against the default ASC test to
   * ensure the ORDER_DESC sentinel flips the iteration direction.
   */
  @Test
  public void descendingOrderIteratesReversed() {
    var clazz = createClassInstance();

    session.begin();
    var allRids = new ArrayList<RID>();
    for (var i = 0; i < 24; i++) {
      allRids.add(session.newEntity(clazz.getName()).getIdentity());
    }
    session.commit();

    var collectionId = pickCollectionWithAtLeast(allRids, 3);
    // TB5 tightening: compute the exact expected subset (in reverse position order for DESC).
    var expectedDesc = allRids.stream()
        .filter(r -> r.getCollectionId() == collectionId)
        .sorted(Comparator.comparingLong(RID::getCollectionPosition).reversed())
        .toList();

    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(collectionId, ctx, false);
    step.setOrder(FetchFromCollectionExecutionStep.ORDER_DESC);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var rids = results.stream().map(Result::getIdentity).toList();

      assertThat(rids).containsExactlyElementsOf(expectedDesc);
      assertThat(rids.size()).as("enough records to make DESC observable")
          .isGreaterThanOrEqualTo(3);
    } finally {
      session.rollback();
    }
  }

  /**
   * The 4-arg constructor (with non-null {@code QueryPlanningInfo}) produces a functional step
   * that iterates the target collection. (CQ11 rename from prior mismatched name — the test does
   * not actually compare against a 2-arg step; it pins that the 4-arg path produces a working
   * step distinct from the 2-arg shortcut.)
   */
  @Test
  public void fourArgConstructorWithPlanningInfoIteratesRecords() {
    var clazz = createClassInstance();

    session.begin();
    var rid = session.newEntity(clazz.getName()).getIdentity();
    session.commit();

    // Scan the exact collection that received the seeded record.
    var collectionId = rid.getCollectionId();
    var ctx = newContext();
    var planning = new QueryPlanningInfo();
    var step = new FetchFromCollectionExecutionStep(collectionId, planning, ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList()).containsExactly(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * Scanning an empty collection yields a stream that produces no results and can be closed
   * cleanly. We use a freshly-created class with no records on its first collection.
   */
  @Test
  public void emptyCollectionYieldsEmptyStream() {
    var clazz = createClassInstance();
    // Class is empty so every polymorphic collection ID points at an empty collection.
    var collectionId = clazz.getPolymorphicCollectionIds()[0];
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(collectionId, ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).isEmpty();
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Predecessor draining
  // =========================================================================

  /**
   * When a predecessor is attached, it must be started AND closed before the collection scan
   * begins (the source-step side-effect contract).
   */
  @Test
  public void predecessorIsStartedAndClosedBeforeScanning() {
    var clazz = createClassInstance();
    // Any polymorphic collection ID for the (empty) class is fine — we only verify the
    // side-effect propagation on the predecessor, not the record set.
    var collectionId = clazz.getPolymorphicCollectionIds()[0];
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(collectionId, ctx, false);

    var prevStarted = new AtomicBoolean(false);
    var prevClosed = new AtomicBoolean(false);
    step.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        prevStarted.set(true);
        return new ExecutionStream() {
          @Override
          public boolean hasNext(CommandContext ctx) {
            return false;
          }

          @Override
          public Result next(CommandContext ctx) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void close(CommandContext ctx) {
            prevClosed.set(true);
          }
        };
      }
    });

    session.begin();
    try {
      drain(step.start(ctx), ctx);
    } finally {
      session.rollback();
    }

    assertThat(prevStarted).as("predecessor must be started for side effects").isTrue();
    assertThat(prevClosed).as("predecessor stream must be closed after draining").isTrue();
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * {@code prettyPrint} with default order (null) renders the "ASC" label, because the output
   * formula is {@code ORDER_DESC.equals(order) ? "DESC" : "ASC"}. No profiling suffix.
   */
  @Test
  public void prettyPrintDefaultOrderRendersAscWithoutCost() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(42, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM COLLECTION 42 ASC");
    assertThat(output).doesNotContain("DESC");
    assertThat(output).doesNotContain("μs");
  }

  /**
   * With {@code setOrder(ORDER_ASC)} (explicit ASC), {@code prettyPrint} renders "ASC". This
   * distinguishes the explicit-ASC path from the default-null path (both produce "ASC" because
   * the formula only treats {@code ORDER_DESC} specially).
   */
  @Test
  public void prettyPrintExplicitAscRendersAsc() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(7, ctx, false);
    step.setOrder(FetchFromCollectionExecutionStep.ORDER_ASC);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM COLLECTION 7 ASC");
  }

  /**
   * With {@code setOrder(ORDER_DESC)}, {@code prettyPrint} renders "DESC".
   */
  @Test
  public void prettyPrintDescRendersDesc() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(9, ctx, false);
    step.setOrder(FetchFromCollectionExecutionStep.ORDER_DESC);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM COLLECTION 9 DESC");
  }

  /**
   * With profiling enabled, the header appends a {@code (cost μs)} suffix.
   */
  @Test
  public void prettyPrintWithProfilingAppendsCost() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(1, ctx, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM COLLECTION 1");
    assertThat(output).contains("μs");
  }

  /**
   * Non-zero depth adds exactly {@code depth * indent} leading spaces to the first line
   * (CQ6 tightening: exact-width pin rejects both under- and over-indent mutations).
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(5, ctx, false);

    // depth=1, indent=4 → exactly 4 leading spaces, then '+'.
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    +").doesNotStartWith("     +");
    assertThat(output).contains("+ FETCH FROM COLLECTION 5 ASC");
  }

  // =========================================================================
  // serialize / deserialize
  // =========================================================================

  /**
   * {@code serialize} captures both the collection ID and the order sentinel. When order is null
   * (default), the serialized form has a null "order" property.
   */
  @Test
  public void serializeNullOrderStoresNullProperty() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(10, ctx, false);

    var serialized = step.serialize(session);

    assertThat((Integer) serialized.getProperty("collectionId")).isEqualTo(10);
    assertThat((Object) serialized.getProperty("order")).isNull();
  }

  /**
   * With ASC order set, serialize/deserialize round-trips the ORDER_ASC sentinel.
   *
   * <p>TB2/BC2 tightening: the deserialize branch that distinguishes explicit ORDER_ASC from
   * null ordering is pinned by re-serializing the restored step and asserting the "order"
   * property equals ORDER_ASC — prettyPrint alone renders "ASC" for both null and ORDER_ASC,
   * so a mutation that dropped the sentinel assignment during deserialize would not be
   * caught by the pretty-print check.
   */
  @Test
  public void serializeDeserializeAscRoundTrip() {
    var ctx = newContext();
    var original = new FetchFromCollectionExecutionStep(77, ctx, false);
    original.setOrder(FetchFromCollectionExecutionStep.ORDER_ASC);

    var serialized = original.serialize(session);
    assertThat((String) serialized.getProperty("order"))
        .isEqualTo(FetchFromCollectionExecutionStep.ORDER_ASC);

    var restored = new FetchFromCollectionExecutionStep(0, ctx, false);
    restored.deserialize(serialized, session);

    // Re-serialize the restored step to observe the actual sentinel (prettyPrint would coalesce
    // null-order with ORDER_ASC both to "ASC").
    var restoredSerialized = restored.serialize(session);
    assertThat((String) restoredSerialized.getProperty("order"))
        .isEqualTo(FetchFromCollectionExecutionStep.ORDER_ASC);
    assertThat((Integer) restoredSerialized.getProperty("collectionId")).isEqualTo(77);
    assertThat(restored.prettyPrint(0, 2)).contains("+ FETCH FROM COLLECTION 77 ASC");
  }

  /**
   * With DESC order set, serialize/deserialize round-trips the ORDER_DESC sentinel and the
   * restored step renders "DESC".
   */
  @Test
  public void serializeDeserializeDescRoundTrip() {
    var ctx = newContext();
    var original = new FetchFromCollectionExecutionStep(88, ctx, false);
    original.setOrder(FetchFromCollectionExecutionStep.ORDER_DESC);

    var serialized = original.serialize(session);

    var restored = new FetchFromCollectionExecutionStep(0, ctx, false);
    restored.deserialize(serialized, session);

    assertThat(restored.prettyPrint(0, 2)).contains("+ FETCH FROM COLLECTION 88 DESC");
  }

  /**
   * Deserialize with the "order" property left null keeps the restored step on the default ASC
   * branch (no order sentinel set).
   */
  @Test
  public void deserializeWithNullOrderLeavesStepAscending() {
    var ctx = newContext();
    var original = new FetchFromCollectionExecutionStep(13, ctx, false);
    var serialized = original.serialize(session);

    var restored = new FetchFromCollectionExecutionStep(0, ctx, false);
    restored.deserialize(serialized, session);

    assertThat(restored.prettyPrint(0, 2)).contains("+ FETCH FROM COLLECTION 13 ASC");
  }

  /**
   * {@code deserialize} wraps underlying exceptions into a {@link CommandExecutionException}.
   */
  @Test
  public void deserializeFailureWrapsInCommandExecutionException() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(1, ctx, false);

    var badResult = new ResultInternal(session);
    var badSubStep = new ResultInternal(session);
    badSubStep.setProperty("javaType", "com.nonexistent.Step");
    badResult.setProperty("subSteps", List.of(badSubStep));

    assertThatThrownBy(() -> step.deserialize(badResult, session))
        .isInstanceOf(CommandExecutionException.class)
        // TB10 cause-chain pin.
        .hasRootCauseInstanceOf(ClassNotFoundException.class);
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /**
   * The step is plan-cache-safe: the collection ID is a stable numeric reference.
   */
  @Test
  public void stepIsAlwaysCacheable() {
    var ctx = newContext();
    var step = new FetchFromCollectionExecutionStep(0, ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} without a planning info yields a step with the same collection ID and profiling
   * flag. The planning-info parameter path (null branch) is exercised here.
   */
  @Test
  public void copyWithNullPlanningInfoPreservesFields() {
    var clazz = createClassInstance();

    session.begin();
    var rid = session.newEntity(clazz.getName()).getIdentity();
    session.commit();

    // Target the collection that actually received the record.
    var collectionId = rid.getCollectionId();
    var ctx = newContext();
    var original = new FetchFromCollectionExecutionStep(collectionId, ctx, true);

    var copy = original.copy(ctx);

    assertThat(copy).isNotSameAs(original).isInstanceOf(FetchFromCollectionExecutionStep.class);
    var copied = (FetchFromCollectionExecutionStep) copy;
    assertThat(copied.isProfilingEnabled()).isTrue();
    assertThat(copied.canBeCached()).isTrue();

    // Functional equivalence: the copy can iterate the same record.
    session.begin();
    try {
      var results = drain(copied.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList()).containsExactly(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code copy} with a non-null planning info reaches the non-null branch of the ternary
   * {@code queryPlanning == null ? null : queryPlanning.copy()}. The resulting step still
   * iterates the target record.
   *
   * <p><b>Caveat (CQ7/TB1):</b> the {@code queryPlanning} field is private, has no getter, and
   * per production comment is "Not currently consulted during execution." Therefore neither
   * branch of the ternary produces an observable behavioral difference at the step's public
   * surface — this test is effectively branch-coverage-only for the non-null path. If the
   * planning info ever becomes execution-relevant (or a getter is added), this test should
   * be strengthened to assert the copy carries a deep-copied {@code QueryPlanningInfo}.
   */
  @Test
  public void copyWithNonNullPlanningInfoReachesNonNullBranch() {
    var clazz = createClassInstance();

    session.begin();
    var rid = session.newEntity(clazz.getName()).getIdentity();
    session.commit();

    var collectionId = rid.getCollectionId();
    var ctx = newContext();
    var planning = new QueryPlanningInfo();
    planning.distinct = true; // Set a field so any future getter-based assertion has a hook.
    var original = new FetchFromCollectionExecutionStep(collectionId, planning, ctx, false);

    var copied = (FetchFromCollectionExecutionStep) original.copy(ctx);

    session.begin();
    try {
      var results = drain(copied.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList()).containsExactly(rid);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }

  /**
   * Returns a collection ID from {@code rids} that holds at least {@code minCount} elements.
   * Records are distributed round-robin across polymorphic collections, so this is how a test
   * picks a deterministic target collection with a known population.
   */
  /**
   * Returns a collection ID that holds at least {@code minCount} elements of {@code rids}.
   *
   * <p>YouTrackDB distributes a class's records round-robin across N polymorphic collections
   * (with some random-jitter within round-robin). With ~24 seeded records and the default
   * collection count (typically 4–8), at least one collection will reliably receive
   * {@code >= 3} records by the pigeonhole principle. {@code findFirst} is safe because the
   * test only needs <em>some</em> collection meeting the threshold — the assertion is then
   * made strictly within that chosen collection.
   */
  private static int pickCollectionWithAtLeast(List<RID> rids, int minCount) {
    var counts = new HashMap<Integer, Integer>();
    for (var rid : rids) {
      counts.merge(rid.getCollectionId(), 1, Integer::sum);
    }
    return counts.entrySet().stream()
        .filter(e -> e.getValue() >= minCount)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "No collection received at least " + minCount + " of the seeded records"));
  }
}
