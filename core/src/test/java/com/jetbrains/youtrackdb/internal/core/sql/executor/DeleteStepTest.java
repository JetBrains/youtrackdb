package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.exception.DatabaseException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Direct-step tests for {@link DeleteStep}, the execution step that deletes every record flowing
 * through its upstream stream.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>Identifiable branch: each upstream record is deleted via {@code session.delete(...)}.
 *   <li>Non-identifiable branch: a transient (no-record) result is rejected with
 *       {@link DatabaseException}.
 *   <li>Empty upstream: the mapper never fires, no error.
 *   <li>prettyPrint rendering (with and without profiling, respecting indent).
 *   <li>{@code copy()} yields a functional clone with the same profiling flag.
 *   <li>{@code canBeCached()} is {@code true}.
 * </ul>
 */
public class DeleteStepTest extends TestUtilsFixture {

  // =========================================================================
  // internalStart: delete semantics
  // =========================================================================

  /**
   * Every identifiable upstream result is deleted from the session. After draining, none of the
   * target RIDs should load (records are gone) and the delete step re-emits the input results
   * unchanged for downstream projection.
   */
  @Test
  public void deletesEachIdentifiableResult() {
    var className = createClassInstance().getName();

    session.begin();
    var e1 = session.newEntity(className);
    var e2 = session.newEntity(className);
    var rid1 = e1.getIdentity();
    var rid2 = e2.getIdentity();
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      var step = new DeleteStep(ctx, false);
      var upstream =
          sourceStep(ctx, List.of(resultOf(session.getActiveTransaction().load(rid1)),
              resultOf(session.getActiveTransaction().load(rid2))));
      step.setPrevious(upstream);

      var results = drain(step.start(ctx), ctx);
      assertThat(results).hasSize(2);
      // DeleteStep.mapResult contracts to re-emit the input result unchanged (for downstream
      // projection like RETURN BEFORE). Pin the RIDs so a mutation that substituted a new
      // transient ResultInternal would be caught.
      assertThat(results.stream().map(r -> r.getIdentity()).toList())
          .containsExactlyInAnyOrder(rid1, rid2);
      session.commit();
    } catch (Throwable t) {
      if (session.isTxActive()) {
        session.rollback();
      }
      throw t;
    }

    session.begin();
    try {
      assertThat(session.countClass(className)).isZero();
    } finally {
      session.rollback();
    }
  }

  /**
   * When the upstream is empty the delete map-function never fires; the downstream stream is also
   * empty and nothing is touched in the database.
   */
  @Test
  public void emptyUpstreamYieldsEmptyStream() {
    var className = createClassInstance().getName();

    session.begin();
    session.newEntity(className);
    session.commit();

    session.begin();
    try {
      var ctx = newContext();
      var step = new DeleteStep(ctx, false);
      step.setPrevious(sourceStep(ctx, List.of()));

      var results = drain(step.start(ctx), ctx);
      assertThat(results).isEmpty();
    } finally {
      session.rollback();
    }
    // Pre-existing record is untouched — empty upstream means no delete occurred.
    session.begin();
    try {
      assertThat(session.countClass(className)).isEqualTo(1);
    } finally {
      session.rollback();
    }
  }

  /**
   * A transient {@link ResultInternal} with no record is not identifiable; the delete step must
   * throw a {@link DatabaseException} rather than silently dropping it.
   */
  @Test
  public void nonIdentifiableResultIsRejected() {
    var ctx = newContext();
    var step = new DeleteStep(ctx, false);

    var transientResult = new ResultInternal(session);
    transientResult.setProperty("name", "no-record");
    step.setPrevious(sourceStep(ctx, List.of(transientResult)));

    assertThatThrownBy(() -> drain(step.start(ctx), ctx))
        .isInstanceOf(DatabaseException.class)
        .hasMessageContaining("Can not delete non-record result");
  }

  // =========================================================================
  // prettyPrint
  // =========================================================================

  /**
   * prettyPrint without profiling renders the "+ DELETE" label with no cost suffix.
   */
  @Test
  public void prettyPrintWithoutProfiling() {
    var ctx = newContext();
    var step = new DeleteStep(ctx, false);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ DELETE");
    assertThat(output).doesNotContain("μs");
  }

  /**
   * prettyPrint with profiling appends the cost in microseconds.
   */
  @Test
  public void prettyPrintWithProfilingIncludesCost() {
    var ctx = newContext();
    var step = new DeleteStep(ctx, true);

    var output = step.prettyPrint(0, 2);

    assertThat(output).contains("+ DELETE").contains("μs");
  }

  /**
   * depth × indent controls the leading whitespace prefix.
   */
  @Test
  public void prettyPrintAppliesIndentation() {
    var ctx = newContext();
    var step = new DeleteStep(ctx, false);

    var output = step.prettyPrint(1, 4);

    assertThat(output).startsWith("    ");
  }

  // =========================================================================
  // canBeCached, copy
  // =========================================================================

  /**
   * DeleteStep overrides {@code canBeCached()} to return {@code true} because the step itself is
   * stateless — its effect depends on the upstream rows, which are cached by the upstream plan.
   */
  @Test
  public void stepReportsCacheable() {
    var ctx = newContext();
    var step = new DeleteStep(ctx, false);

    assertThat(step.canBeCached()).isTrue();
  }

  /**
   * {@code copy()} yields an independent step of the same type carrying the same profiling flag,
   * and the clone is likewise cacheable.
   */
  @Test
  public void copyProducesIndependentStep() {
    var ctx = newContext();
    var original = new DeleteStep(ctx, true);

    var copy = original.copy(ctx);

    assertThat(copy).isNotSameAs(original).isInstanceOf(DeleteStep.class);
    var copied = (DeleteStep) copy;
    assertThat(copied.isProfilingEnabled()).isTrue();
    assertThat(copied.canBeCached()).isTrue();
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private BasicCommandContext newContext() {
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    return ctx;
  }

  private ResultInternal resultOf(
      com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable record) {
    return new ResultInternal(session, record);
  }

  private ExecutionStepInternal sourceStep(CommandContext ctx, List<? extends Result> rows) {
    return new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(CommandContext c) {
        return ExecutionStream.resultIterator(new ArrayList<Result>(rows).iterator());
      }
    };
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
