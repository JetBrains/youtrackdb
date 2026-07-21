package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/**
 * Direct-step tests for {@link FetchFromRidsStep}, the source step that emits a pre-computed
 * list of Record IDs (RIDs) from a SQL FROM clause like {@code SELECT FROM [#10:3, #10:7]} or
 * from input parameters resolved by the planner.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code internalStart} emits one Result per RID, preserving insertion order; an empty
 *       RID collection yields an empty stream.
 *   <li>Non-existent RIDs silently terminate iteration when {@code skipMissing=false} (the
 *       default); null entries within the list are skipped without terminating.
 *   <li>Duplicate RIDs are not deduplicated — cardinality is preserved.
 *   <li>Predecessor step is drained for side effects before iterating.
 *   <li>{@code prettyPrint} renders "+ FETCH FROM RIDs" followed by the RID list on a second
 *       line, with indentation applied on both lines.
 *   <li>{@code canBeCached} always returns {@code false} because RIDs typically come from
 *       runtime parameters.
 *   <li>{@code copy} shares the RID reference so the copied step iterates the same RIDs;
 *       the {@code skipMissing} flag is preserved through the copy.
 * </ul>
 */
public class FetchFromRidsStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: RID iteration
  // =========================================================================

  /**
   * {@code internalStart} emits one {@link Result} per RID in the stored collection. Each Result
   * carries the matching identity.
   */
  @Test
  public void iteratesAllProvidedRids() {
    var className = createClassInstance().getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var step = new FetchFromRidsStep(
        List.of((RecordIdInternal) rid1, (RecordIdInternal) rid2), ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList()).containsExactly(rid1, rid2);
    } finally {
      session.rollback();
    }
  }

  /**
   * An empty RID collection yields an immediately-empty stream; no iteration attempts are made.
   */
  @Test
  public void emptyRidListYieldsEmptyStream() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(Collections.emptyList(), ctx, false);

    var results = drain(step.start(ctx), ctx);
    assertThat(results).isEmpty();
  }

  /**
   * The step iterates RIDs in the order stored in the collection. A LinkedHashSet-backed list
   * must therefore deliver its elements in insertion order.
   */
  @Test
  public void preservesInsertionOrder() {
    var className = createClassInstance().getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    var rid3 = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    // Provide RIDs in reversed insertion order to prove the step does not re-sort.
    var rids = List.of(
        (RecordIdInternal) rid3,
        (RecordIdInternal) rid1,
        (RecordIdInternal) rid2);
    var step = new FetchFromRidsStep(rids, ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList())
          .containsExactly(rid3, rid1, rid2);
    } finally {
      session.rollback();
    }
  }

  /**
   * Contract (TC1): {@code LoaderExecutionStream} catches {@link
   * com.jetbrains.youtrackdb.internal.core.exception.RecordNotFoundException} with a bare
   * {@code return}, so an unresolvable RID mid-list silently terminates the stream. Records
   * after the missing RID are NOT emitted. A future refactor that replaced the {@code return}
   * with a {@code continue} would surface here.
   */
  @Test
  public void nonExistentRidTerminatesIterationSilently() {
    var className = createClassInstance().getName();

    session.begin();
    var realRid = session.newEntity(className).getIdentity();
    session.commit();

    // Fabricate a RID in the same cluster at a never-allocated position.
    var missingRid =
        new RecordId(realRid.getCollectionId(), realRid.getCollectionPosition() + 9999);

    var ctx = newContext();
    var step = new FetchFromRidsStep(
        List.of((RecordIdInternal) realRid, missingRid, (RecordIdInternal) realRid),
        ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      // First realRid loads, missing RID aborts the loop, second realRid is NEVER emitted.
      assertThat(results.stream().map(Result::getIdentity).toList()).containsExactly(realRid);
    } finally {
      session.rollback();
    }
  }

  /**
   * Contract (TC3): {@code LoaderExecutionStream.fetchNext} skips null entries in the RID list.
   * Adjacent non-null RIDs on either side of a null gap must still be emitted. Pins the
   * null-guard so a regression that removed it would throw NPE instead of skipping.
   */
  @Test
  public void nullRidWithinListIsSkippedAndIterationContinues() {
    var className = createClassInstance().getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    session.commit();

    // Arrays.asList permits null entries (List.of does not).
    var rids = Arrays.asList(
        (RecordIdInternal) rid1,
        null,
        (RecordIdInternal) rid2);

    var ctx = newContext();
    var step = new FetchFromRidsStep(rids, ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList())
          .containsExactly(rid1, rid2);
    } finally {
      session.rollback();
    }
  }

  /**
   * Contract (TC4): duplicate RIDs in the input produce duplicate results — the step does NOT
   * deduplicate. A planner optimization that collapsed duplicates would be surfaced by this
   * pin, ensuring the cardinality contract is explicit.
   */
  @Test
  public void duplicateRidsAreNotDeduplicated() {
    var className = createClassInstance().getName();

    session.begin();
    var rid = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var step = new FetchFromRidsStep(
        List.of((RecordIdInternal) rid, (RecordIdInternal) rid, (RecordIdInternal) rid),
        ctx, false);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(3);
      assertThat(results.stream().map(Result::getIdentity).toList())
          .containsExactly(rid, rid, rid);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Predecessor draining
  // =========================================================================

  /**
   * When a predecessor is attached (e.g. a setup step), the step must start AND close the
   * upstream stream for side effects before iterating RIDs.
   */
  @Test
  public void predecessorIsStartedAndClosedBeforeIterating() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(Collections.emptyList(), ctx, false);

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

    drain(step.start(ctx), ctx);

    assertThat(prevStarted).as("predecessor must be started for side effects").isTrue();
    assertThat(prevClosed).as("predecessor stream must be closed after draining").isTrue();
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * {@code prettyPrint} renders the "+ FETCH FROM RIDs" header on the first line and the RID
   * list on a second line, both respecting the indentation. TS-M4 tightening: assert the
   * two-line layout explicitly so a future refactor that collapsed the output to one line
   * would be caught.
   */
  @Test
  public void prettyPrintRendersHeaderAndRidList() {
    var ctx = newContext();
    var rids = List.of((RecordIdInternal) new RecordId(11, 2), new RecordId(11, 5));
    var step = new FetchFromRidsStep(rids, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM RIDs");
    // Two-line layout: header on line 1, RID list on line 2.
    var lines = output.split("\n", -1);
    assertThat(lines.length).isGreaterThanOrEqualTo(2);
    assertThat(lines[0]).contains("+ FETCH FROM RIDs");
    assertThat(lines[1]).contains("#11:2").contains("#11:5");
  }

  /**
   * A non-zero depth prepends exactly {@code depth * indent} leading spaces to the first line
   * (CQ6 tightening: exact-width pin).
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(
        List.of((RecordIdInternal) new RecordId(1, 1)), ctx, false);

    // depth=1, indent=4 → exactly 4 leading spaces, then '+'.
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    +").doesNotStartWith("     +");
    assertThat(output).contains("+ FETCH FROM RIDs");
  }

  /**
   * prettyPrint with an empty rids collection renders "[]" after the header, preserving the
   * observable format (TC7).
   */
  @Test
  public void prettyPrintWithEmptyRidListRendersEmptyBrackets() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(Collections.emptyList(), ctx, false);

    assertThat(step.prettyPrint(0, 2)).contains("+ FETCH FROM RIDs").contains("[]");
  }

  // =========================================================================
  // canBeCached
  // =========================================================================

  /**
   * The step is never cacheable because RIDs typically come from runtime input parameters or
   * literal values that may differ between executions.
   */
  @Test
  public void stepIsNeverCacheable() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(Collections.emptyList(), ctx, false);

    assertThat(step.canBeCached()).isFalse();
  }

  // =========================================================================
  // copy
  // =========================================================================

  /**
   * {@code copy} preserves the RID list (order, content, and size) plus the profiling flag; the
   * copied step iterates the same records independently of the original.
   *
   * <p>TB3 tightening: use three distinct RIDs so a mutation that truncated or reordered the
   * list in {@code copy()} would be caught (a single-element test masks both defects).
   */
  @Test
  public void copyPreservesRidsAndProfilingFlag() {
    var className = createClassInstance().getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    var rid3 = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var rids = List.of(
        (RecordIdInternal) rid1,
        (RecordIdInternal) rid2,
        (RecordIdInternal) rid3);
    var original = new FetchFromRidsStep(rids, ctx, true);

    var copy = original.copy(ctx);

    assertThat(copy).isNotSameAs(original).isInstanceOf(FetchFromRidsStep.class);
    var copied = (FetchFromRidsStep) copy;
    assertThat(copied.isProfilingEnabled()).isTrue();
    assertThat(copied.canBeCached()).isFalse();

    session.begin();
    try {
      var results = drain(copied.start(ctx), ctx);
      // Exact match pins order, content, and size — rejects mutations that drop/reorder RIDs.
      assertThat(results.stream().map(Result::getIdentity).toList())
          .containsExactly(rid1, rid2, rid3);
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code copy()} must preserve the {@code skipMissing} flag. When {@code skipMissing=true},
   * a non-existent RID mid-list is skipped rather than terminating iteration (contrast with
   * {@link #nonExistentRidTerminatesIterationSilently} which pins the {@code skipMissing=false}
   * behavior). A mutation dropping the {@code skipMissing} argument from the copy constructor
   * call would revert to the default (false), causing iteration to terminate at the missing
   * RID — the second real RID would be lost.
   */
  @Test
  public void copyPreservesSkipMissing() {
    var className = createClassInstance().getName();

    session.begin();
    var rid1 = session.newEntity(className).getIdentity();
    var rid2 = session.newEntity(className).getIdentity();
    session.commit();

    // Fabricate a RID at a never-allocated position in the same collection.
    var missingRid =
        new RecordId(rid1.getCollectionId(), rid1.getCollectionPosition() + 9999);

    var ctx = newContext();
    var original = new FetchFromRidsStep(
        List.of((RecordIdInternal) rid1, missingRid, (RecordIdInternal) rid2),
        ctx, false, /* skipMissing= */ true);

    var copied = (FetchFromRidsStep) original.copy(ctx);

    // Execute the COPY (not the original) against the list containing a missing RID.
    // With skipMissing=true the missing RID is skipped and both real RIDs are emitted.
    // If copy() failed to preserve skipMissing, the stream would terminate at the missing
    // RID (the default skipMissing=false behavior) and rid2 would be lost.
    session.begin();
    try {
      var results = drain(copied.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList())
          .as("copy must preserve skipMissing=true so missing RIDs are skipped, not terminal")
          .containsExactly(rid1, rid2);
    } finally {
      session.rollback();
    }
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private static List<Result> drain(ExecutionStream stream, CommandContext ctx) {
    var out = new ArrayList<Result>();
    while (stream.hasNext(ctx)) {
      out.add(stream.next(ctx));
    }
    stream.close(ctx);
    return out;
  }
}
