package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link FetchFromClassExecutionStep}, the source step that scans every
 * collection belonging to a named schema class (including subclass collections).
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Constructor resolves the class from the immutable schema snapshot and pre-computes the
 *       polymorphic collection IDs.
 *   <li>Constructor throws {@link CommandExecutionException} when the class does not exist.
 *   <li>{@code ridOrder=true} triggers ASC collection-ID sort; {@code ridOrder=false} triggers DESC
 *       sort; {@code null} leaves the natural polymorphic order untouched.
 *   <li>The optional {@code collections} filter restricts which collection IDs are scanned.
 *   <li>{@code internalStart} returns an empty stream when the resolved collection-ID array is
 *       empty or null.
 *   <li>{@code internalStart} drains and closes a predecessor step (for side effects) before
 *       emitting records.
 *   <li>{@code internalStart} produces one Result per stored record and the records are reachable
 *       from the returned stream.
 *   <li>{@code prettyPrint} renders the class name, respects indent, and appends the profiling
 *       cost suffix only when profiling is enabled.
 *   <li>{@code serialize}/{@code deserialize} round-trips {@code className}, {@code orderByRidAsc},
 *       and {@code orderByRidDesc}; deserialization failures wrap into
 *       {@link CommandExecutionException}.
 *   <li>{@code canBeCached} always returns {@code true} (the step is plan-cache-safe: collection
 *       IDs come from the immutable schema snapshot).
 *   <li>{@code copy} produces an independent, fully-initialized step carrying the same settings.
 * </ul>
 */
public class FetchFromClassExecutionStepTest extends TestUtilsFixture {

  // =========================================================================
  // Constructor: class resolution + ordering
  // =========================================================================

  /**
   * With {@code ridOrder=null} the step leaves both ordering flags at their default {@code false}.
   * Asserted via the serialize probe so a mutation flipping the null branch to set either
   * {@code orderByRidAsc} or {@code orderByRidDesc} would be caught. (TB8 tightening.)
   */
  @Test
  public void constructorWithoutRidOrderLeavesOrderingFlagsFalse() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    var serialized = step.serialize(session);
    assertThat((String) serialized.getProperty("className")).isEqualTo(className);
    assertThat((Boolean) serialized.getProperty("orderByRidAsc")).isFalse();
    assertThat((Boolean) serialized.getProperty("orderByRidDesc")).isFalse();
  }

  /**
   * {@code ridOrder=TRUE} sets {@code orderByRidAsc=true} and sorts the resolved collection IDs
   * ascending. After round-trip via {@code serialize}, the flag is visible.
   */
  @Test
  public void constructorAscSetsOrderByRidAsc() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    var step = new FetchFromClassExecutionStep(className, null, ctx, Boolean.TRUE, false);

    var serialized = step.serialize(session);
    assertThat((Boolean) serialized.getProperty("orderByRidAsc")).isTrue();
    assertThat((Boolean) serialized.getProperty("orderByRidDesc")).isFalse();
  }

  /**
   * {@code ridOrder=FALSE} sets {@code orderByRidDesc=true} and sorts descending.
   */
  @Test
  public void constructorDescSetsOrderByRidDesc() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    var step = new FetchFromClassExecutionStep(className, null, ctx, Boolean.FALSE, false);

    var serialized = step.serialize(session);
    assertThat((Boolean) serialized.getProperty("orderByRidAsc")).isFalse();
    assertThat((Boolean) serialized.getProperty("orderByRidDesc")).isTrue();
  }

  /**
   * An unknown class name is reported via {@link CommandExecutionException} naming the missing
   * class — this pins the "null class" branch inside {@code loadClassFromSchema}. The missing
   * class name MUST appear in the message (TB9 tightening) so a mutation that produced a
   * static "Class not found" text without interpolation would be caught.
   */
  @Test
  public void unknownClassThrowsCommandExecutionException() {
    var ctx = newContext();
    var missing = "NoSuchClass_" + System.nanoTime();

    assertThatThrownBy(
        () -> new FetchFromClassExecutionStep(missing, null, ctx, null, false))
        .isInstanceOf(CommandExecutionException.class)
        .hasMessageContaining(missing)
        .hasMessageContaining("not found");
  }

  /**
   * When {@code collections} is non-null but empty, no collection ID passes the filter. The step
   * therefore resolves to an empty collection-ID array, and {@code internalStart} returns an
   * empty stream immediately (does not attempt to iterate).
   */
  @Test
  public void emptyCollectionsFilterProducesEmptyStream() {
    var className = createClassInstance().getName();

    session.begin();
    session.newEntity(className);
    session.commit();

    var ctx = newContext();
    // Filter is non-null but rejects every collection: no matching collection IDs.
    var step = new FetchFromClassExecutionStep(className, Set.of(), ctx, null, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).isEmpty();
    } finally {
      session.rollback();
    }
  }

  /**
   * When {@code collections} includes all of the class's collection names, records from the
   * class are iterated. The filter path (non-null filter retaining at least one ID) is distinct
   * from {@code collections=null}.
   */
  @Test
  public void collectionsFilterMatchingClassCollectionRetainsScan() {
    var clazz = createClassInstance();
    var className = clazz.getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    session.commit();

    // Resolve the actual collection names for this class: default naming is
    // "<lowerClassName>_<N>" (see SchemaEmbedded.createCollections).
    var collectionNames = new java.util.HashSet<String>();
    for (var collectionId : clazz.getPolymorphicCollectionIds()) {
      collectionNames.add(session.getCollectionNameById(collectionId));
    }

    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, collectionNames, ctx, null, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var rids = results.stream().map(Result::getIdentity).toList();
      assertThat(rids).containsExactlyInAnyOrder(rid1, rid2);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // internalStart: record iteration and empty-collection branch
  // =========================================================================

  /**
   * When the class has records, {@code internalStart} produces one result per record across every
   * collection in the polymorphic set. Each result carries a persistent identity.
   */
  @Test
  public void internalStartIteratesAllRecordsOfClass() {
    var className = createClassInstance().getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    var rid3 = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var rids = results.stream().map(Result::getIdentity).toList();
      assertThat(rids).containsExactlyInAnyOrder(rid1, rid2, rid3);
    } finally {
      session.rollback();
    }
  }

  /**
   * When the class has no records, {@code internalStart} still returns a valid stream that yields
   * zero elements and can be closed cleanly. This exercises the normal iteration path on an
   * empty polymorphic collection set (as opposed to the empty-filter branch above).
   */
  @Test
  public void internalStartOnEmptyClassYieldsEmptyStream() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).isEmpty();
    } finally {
      session.rollback();
    }
  }

  /**
   * A DESC-ordered scan sorts polymorphic collection IDs in descending order AND iterates records
   * within each collection by descending position. With enough seeded records, one collection
   * will hold at least three, and those positions must arrive strictly descending.
   *
   * <p>Enhanced over an earlier weak {@code first >= last} form (BC1/TB6): that check was a
   * near-tautology for small seed counts because round-robin distributes few records into
   * different collections at position 0. Seeding 24 records guarantees at least one collection
   * has three insertions; the strict descending check then rejects mutations that emit ASC
   * positions within a collection even when sorted collections are DESC-visited.
   */
  @Test
  public void descendingOrderReturnsRecordsInReverseInsertionOrder() {
    var className = createClassInstance().getName();

    session.begin();
    var allRids = new ArrayList<com.jetbrains.youtrackdb.internal.core.db.record.record.RID>();
    for (var i = 0; i < 24; i++) {
      allRids.add(session.newEntity(className).getIdentity());
    }
    session.commit();

    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, Boolean.FALSE, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var rids = results.stream().map(Result::getIdentity).toList();
      assertThat(rids).containsExactlyInAnyOrderElementsOf(allRids);

      // Within each collection, positions must be strictly descending (the effect of DESC
      // iteration inside RecordIteratorCollections). Verify for any collection that holds
      // multiple records from the seeded batch — falsifies a mutation that drops the DESC
      // pass-through inside the iterator.
      var byCollection = rids.stream()
          .collect(java.util.stream.Collectors.groupingBy(
              com.jetbrains.youtrackdb.internal.core.db.record.record.RID::getCollectionId,
              java.util.stream.Collectors.mapping(
                  r -> r.getCollectionPosition(), java.util.stream.Collectors.toList())));
      byCollection.entrySet().stream()
          .filter(e -> e.getValue().size() >= 2)
          .forEach(e -> assertThat(e.getValue())
              .as("DESC scan of collection %d positions", e.getKey())
              .isSortedAccordingTo((a, b) -> Long.compare(b, a)));

      // Across collections: the collection IDs appear in DESC order (sorted at construction).
      var visitedCollections = rids.stream()
          .map(com.jetbrains.youtrackdb.internal.core.db.record.record.RID::getCollectionId)
          .distinct()
          .toList();
      assertThat(visitedCollections)
          .as("collection IDs visited in DESC order")
          .isSortedAccordingTo((a, b) -> Integer.compare(b, a));
    } finally {
      session.rollback();
    }
  }

  /**
   * Polymorphic scan includes subclass collections. A parent-class fetch must return records from
   * both parent and subclass instances, proving that {@code getPolymorphicCollectionIds()} feeds
   * the whole subtree into the scan. Pins the defining behavior of
   * {@code FetchFromClassExecutionStep} vs. {@code FetchFromCollectionExecutionStep} (TC2).
   */
  @Test
  public void scanIncludesSubclassCollectionsByDefault() {
    var parent = createClassInstance();
    var child = createChildClassInstance(parent);

    session.begin();
    var parentRid = session.newEntity(parent.getName()).getIdentity();
    var childRid = session.newEntity(child.getName()).getIdentity();
    session.commit();

    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(parent.getName(), null, ctx, null, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList())
          .containsExactlyInAnyOrder(parentRid, childRid);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Predecessor draining
  // =========================================================================

  /**
   * When a predecessor is attached, {@code internalStart} starts AND closes the upstream stream
   * before beginning the class scan. This is the side-effect contract for setup steps (e.g.
   * GlobalLetExpressionStep) chained before a source step.
   */
  @Test
  public void predecessorIsStartedAndClosedBeforeScanning() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

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
   * {@code prettyPrint} without profiling renders the header "+ FETCH FROM CLASS <name>"
   * without a cost suffix.
   */
  @Test
  public void prettyPrintWithoutProfilingOmitsCost() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM CLASS ").contains(className);
    assertThat(output).doesNotContain(" (").doesNotContain("μs");
  }

  /**
   * With profiling enabled the header appends a {@code (cost μs)} suffix.
   */
  @Test
  public void prettyPrintWithProfilingAppendsCost() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, null, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM CLASS ").contains(className);
    assertThat(output).contains("μs");
  }

  /**
   * A non-zero depth prepends exactly {@code depth * indent} leading spaces to the first line.
   * Exact-width pin (CQ6): rejects both under-indent and over-indent mutations.
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    // depth=1, indent=4 → exactly 4 leading spaces, then '+'.
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    +").doesNotStartWith("     +");
    assertThat(output).contains("+ FETCH FROM CLASS ").contains(className);
  }

  // =========================================================================
  // serialize / deserialize
  // =========================================================================

  /**
   * {@code serialize} captures {@code className}, {@code orderByRidAsc}, {@code orderByRidDesc}.
   * {@code deserialize} restores them into a freshly-constructed step.
   */
  @Test
  public void serializeDeserializeRoundTripPreservesFields() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var original = new FetchFromClassExecutionStep(className, null, ctx, Boolean.TRUE, false);

    var serialized = original.serialize(session);

    // FetchFromClassExecutionStep has no zero-arg ctor, so reconstruct via the regular
    // class-name ctor and let deserialize overwrite fields to prove round-trip fidelity.
    var restored = new FetchFromClassExecutionStep(className, null, ctx, null, false);
    restored.deserialize(serialized, session);

    var restoredSerialized = restored.serialize(session);
    assertThat((String) restoredSerialized.getProperty("className")).isEqualTo(className);
    assertThat((Boolean) restoredSerialized.getProperty("orderByRidAsc")).isTrue();
    assertThat((Boolean) restoredSerialized.getProperty("orderByRidDesc")).isFalse();
  }

  /**
   * When {@code deserialize} encounters malformed data (a substep pointing at a non-existent
   * class), the {@link ClassNotFoundException} is wrapped in {@link CommandExecutionException}.
   */
  @Test
  public void deserializeFailureWrapsInCommandExecutionException() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    var badResult = new ResultInternal(session);
    var badSubStep = new ResultInternal(session);
    badSubStep.setProperty("javaType", "com.nonexistent.Step");
    badResult.setProperty("subSteps", List.of(badSubStep));

    assertThatThrownBy(() -> step.deserialize(badResult, session))
        .isInstanceOf(CommandExecutionException.class)
        // TB10 cause-chain pin: the wrapped exception identifies the real failure.
        .hasRootCauseInstanceOf(ClassNotFoundException.class);
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /**
   * The step is always plan-cache-safe because collection IDs come from the immutable schema
   * snapshot at construction time.
   */
  @Test
  public void stepIsAlwaysCacheable() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    assertThat(step.canBeCached()).isTrue();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} produces a distinct instance carrying the same {@code className},
   * {@code orderByRidAsc}/{@code orderByRidDesc}, and {@code profilingEnabled}. The copy renders
   * the same header via {@code prettyPrint} and is functionally equivalent.
   */
  @Test
  public void copyProducesIndependentStepWithSameSettings() {
    var className = createClassInstance().getName();

    session.begin();
    var rid = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var original = new FetchFromClassExecutionStep(className, null, ctx, Boolean.TRUE, true);

    var copy = original.copy(ctx);

    assertThat(copy).isNotSameAs(original).isInstanceOf(FetchFromClassExecutionStep.class);
    var copied = (FetchFromClassExecutionStep) copy;
    assertThat(copied.isProfilingEnabled()).isTrue();
    assertThat(copied.canBeCached()).isTrue();

    // TB2-style pin: verify className, orderByRidAsc, orderByRidDesc survived the copy via the
    // serialize probe. Without this a mutation that omits any of these field assignments from
    // copy() would slip past the prettyPrint check alone.
    var copySerialized = copied.serialize(session);
    assertThat((String) copySerialized.getProperty("className")).isEqualTo(className);
    assertThat((Boolean) copySerialized.getProperty("orderByRidAsc")).isTrue();
    assertThat((Boolean) copySerialized.getProperty("orderByRidDesc")).isFalse();

    // Copy renders the same header (proxy check that className was preserved).
    var copyOutput = copied.prettyPrint(0, 2);
    assertThat(copyOutput).contains(className);

    // Copy can execute and produces the same record set.
    session.begin();
    try {
      var results = drain(copied.start(ctx), ctx);
      var rids = results.stream().map(Result::getIdentity).toList();
      assertThat(rids).containsExactly(rid);
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
}
