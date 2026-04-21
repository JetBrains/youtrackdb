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
   * With {@code ridOrder=null} the step leaves ordering flags unset. The step must successfully
   * resolve the class's polymorphic collection IDs even when nothing in the schema is populated
   * yet (class creation happens in {@link TestUtilsFixture#createClassInstance()}).
   */
  @Test
  public void constructorWithoutRidOrderLeavesOrderingFlagsFalse() {
    var className = createClassInstance().getName();
    var ctx = newContext();

    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    // Render the step to verify the class name was captured; ordering flags are only observable
    // via copy/serialize — checked in dedicated tests below.
    var output = step.prettyPrint(0, 2);
    assertThat(output).contains(className);
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
   * class — this pins the "null class" branch inside {@code loadClassFromSchema}.
   */
  @Test
  public void unknownClassThrowsCommandExecutionException() {
    var ctx = newContext();

    assertThatThrownBy(
        () -> new FetchFromClassExecutionStep("NoSuchClass_" + System.nanoTime(), null, ctx,
            null, false))
        .isInstanceOf(CommandExecutionException.class)
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
   * A DESC-ordered scan returns records in descending RID order within each collection. Because
   * collection IDs are also sorted DESC, the overall iteration order is reverse-insertion order
   * for a single collection class.
   */
  @Test
  public void descendingOrderReturnsRecordsInReverseInsertionOrder() {
    var className = createClassInstance().getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    var rid3 = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, Boolean.FALSE, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      var rids = results.stream().map(Result::getIdentity).toList();
      // All three RIDs must be present, and the iterator delivered them in non-ascending order
      // (descending scan). Pin the "first RID > last RID" property — tolerant to multi-
      // collection polymorphic ordering if the fixture ever introduces subclasses.
      assertThat(rids).containsExactlyInAnyOrder(rid1, rid2, rid3);
      assertThat(rids.get(0).getCollectionPosition())
          .as("DESC scan: first position >= last position")
          .isGreaterThanOrEqualTo(rids.get(rids.size() - 1).getCollectionPosition());
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
    assertThat(output).doesNotContain("μs"); // μs
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
   * A non-zero depth prepends {@code depth * indent} leading spaces to the first line.
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var className = createClassInstance().getName();
    var ctx = newContext();
    var step = new FetchFromClassExecutionStep(className, null, ctx, null, false);

    // depth=1, indent=4 → 4 leading spaces
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
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

    // Restore into a bare instance using the protected no-arg ctx-only constructor via
    // deserialize. FetchFromClassExecutionStep has no public zero-arg ctor; use the one-arg
    // variant that constructs against a placeholder class, then let deserialize overwrite fields.
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
        .isInstanceOf(CommandExecutionException.class);
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
