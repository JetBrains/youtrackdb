package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
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
 *   <li>{@code internalStart} emits one Result per RID — iterating the stored RID collection
 *       via {@link ExecutionStream#loadIterator}.
 *   <li>Empty RID collection yields an empty stream.
 *   <li>Predecessor step is drained for side effects before iterating.
 *   <li>{@code prettyPrint} renders "+ FETCH FROM RIDs" followed by the RID list on a second
 *       line, with indentation applied on both lines.
 *   <li>{@code serialize} captures the RIDs as a list of strings; {@code deserialize} restores
 *       them so a round-tripped step emits the same RIDs.
 *   <li>{@code serialize} with null RIDs omits the "rids" property; {@code deserialize} with
 *       a missing "rids" property leaves the step's RID reference unchanged.
 *   <li>{@code deserialize} wraps underlying exceptions in {@link CommandExecutionException}.
 *   <li>{@code canBeCached} always returns {@code false} because RIDs typically come from
 *       runtime parameters.
 *   <li>{@code copy} shares the RID reference so the copied step iterates the same RIDs.
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
   * list on a second line, both respecting the indentation.
   */
  @Test
  public void prettyPrintRendersHeaderAndRidList() {
    var ctx = newContext();
    var rids = List.of((RecordIdInternal) new RecordId(11, 2), new RecordId(11, 5));
    var step = new FetchFromRidsStep(rids, ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ FETCH FROM RIDs");
    // The RID list rendering uses the default Collection toString — verify both RIDs appear.
    assertThat(output).contains("#11:2").contains("#11:5");
  }

  /**
   * A non-zero depth prepends {@code depth * indent} leading spaces to the first line.
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(
        List.of((RecordIdInternal) new RecordId(1, 1)), ctx, false);

    // depth=1, indent=4 → 4 leading spaces on line 1.
    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
    assertThat(output).contains("+ FETCH FROM RIDs");
  }

  // =========================================================================
  // serialize / deserialize
  // =========================================================================

  /**
   * {@code serialize} stores the RIDs as a {@code List<String>} under the "rids" property. A
   * round-trip through {@code deserialize} restores the same RIDs.
   */
  @Test
  public void serializeDeserializeRoundTripPreservesRids() {
    var ctx = newContext();
    var rids = List.of(
        (RecordIdInternal) new RecordId(5, 1),
        new RecordId(5, 2),
        new RecordId(5, 3));
    var original = new FetchFromRidsStep(rids, ctx, false);

    var serialized = original.serialize(session);
    assertThat((List<?>) serialized.getProperty("rids")).hasSize(3);

    var restored = new FetchFromRidsStep(Collections.emptyList(), ctx, false);
    restored.deserialize(serialized, session);

    var output = restored.prettyPrint(0, 2);
    assertThat(output).contains("#5:1").contains("#5:2").contains("#5:3");
  }

  /**
   * When a step was constructed with {@code null} RIDs, {@code serialize} skips the "rids"
   * property (the {@code rids != null} guard).
   */
  @Test
  public void serializeWithNullRidsOmitsProperty() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(null, ctx, false);

    var serialized = step.serialize(session);
    assertThat((Object) serialized.getProperty("rids")).isNull();
  }

  /**
   * {@code deserialize} when the serialized result carries no "rids" property leaves the step's
   * internal RID reference untouched. The step can then still run against its original RID list
   * (exercising the {@code fromResult.getProperty("rids") != null} guard).
   */
  @Test
  public void deserializeWithMissingRidsPropertyKeepsCurrentRids() {
    var className = createClassInstance().getName();

    session.begin();
    var rid = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var step = new FetchFromRidsStep(
        List.of((RecordIdInternal) rid), ctx, false);

    var badResult = new ResultInternal(session);
    // "rids" property absent — exercises the missing-property branch in deserialize.
    step.deserialize(badResult, session);

    session.begin();
    try {
      var results = drain(step.start(ctx), ctx);
      assertThat(results.stream().map(Result::getIdentity).toList()).containsExactly(rid);
    } finally {
      session.rollback();
    }
  }

  /**
   * {@code deserialize} wraps underlying exceptions in {@link CommandExecutionException} — here
   * triggered by a subStep pointing at a non-existent class.
   */
  @Test
  public void deserializeFailureWrapsInCommandExecutionException() {
    var ctx = newContext();
    var step = new FetchFromRidsStep(Collections.emptyList(), ctx, false);

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
   * {@code copy} preserves the RID reference and profiling flag; the copied step iterates the
   * same records independently of the original.
   */
  @Test
  public void copyPreservesRidsAndProfilingFlag() {
    var className = createClassInstance().getName();

    session.begin();
    var rid = session.newEntity(className).getIdentity();
    session.commit();

    var ctx = newContext();
    var original = new FetchFromRidsStep(
        List.of((RecordIdInternal) rid), ctx, true);

    var copy = original.copy(ctx);

    assertThat(copy).isNotSameAs(original).isInstanceOf(FetchFromRidsStep.class);
    var copied = (FetchFromRidsStep) copy;
    assertThat(copied.isProfilingEnabled()).isTrue();
    assertThat(copied.canBeCached()).isFalse();

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
}
