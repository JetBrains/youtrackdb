package com.jetbrains.youtrackdb.internal.core.gremlin.translator.step;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.SelectExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.List;

/**
 * Builds a REAL {@link SelectExecutionPlan} — a live step chain, not a Mockito stub — over a fixed
 * row list, for the boundary-step tests that must exercise the engine's own copy / close machinery
 * rather than a mock's answers.
 *
 * <p>A stubbed {@link InternalExecutionPlan} answers {@code start()} the same way forever, so it
 * cannot witness the defect these tests exist for: re-arming a boundary step whose plan was already
 * closed. The two properties that make that defect real both live in the engine, and both are
 * present here.
 *
 * <ul>
 *   <li><b>A closed chain stays closed.</b> {@code AbstractExecutionStep.close()} sets a private
 *       sticky flag and {@code ExecutionStepInternal.reset()} does not clear it, so restarting a
 *       closed plan runs a dead chain. {@link ReplayableSourceStep} models the resulting behaviour
 *       of a real cursor-backed source: after {@code close()} it yields no rows.
 *   <li><b>Copy rebuilds the chain.</b> {@code SelectExecutionPlan.copy(ctx)} deep-copies every
 *       step, so the copy's source is a fresh instance that has never been closed and replays the
 *       rows.
 * </ul>
 *
 * <p>The source step also seeds a per-run variable onto the context each time it runs, which is
 * what a real MATCH pass does. That matters for the re-arm copy path specifically: it is the state
 * that makes {@code clone()}'s isolated-child-context recipe unusable at re-arm, so a test running
 * against this fixture is running against a context in the same condition production is in.
 *
 * <p>Rows may stay mocks — it is the plan that has to be real.
 */
final class ReplayablePlanFixture {

  /** Context variable the source step seeds per run, standing in for a MATCH pass's own state. */
  static final String PER_RUN_VARIABLE = "replayableSourceRunCount";

  private ReplayablePlanFixture() {
  }

  /** A real single-step plan that emits {@code rows} on every run until it is closed. */
  static SelectExecutionPlan planOver(CommandContext ctx, List<Result> rows) {
    var plan = new SelectExecutionPlan(ctx);
    plan.chain(new ReplayableSourceStep(ctx, rows));
    return plan;
  }

  /** How many times this plan's source step has been started. */
  static int startCount(InternalExecutionPlan plan) {
    return sourceOf(plan).startCount;
  }

  /** Whether this plan's source step has been closed. */
  static boolean isClosed(InternalExecutionPlan plan) {
    return sourceOf(plan).closed;
  }

  private static ReplayableSourceStep sourceOf(InternalExecutionPlan plan) {
    return (ReplayableSourceStep) plan.getSteps().getFirst();
  }

  /**
   * A source step over a fixed row list that stops producing once closed, the way a real
   * cursor-backed source does. Counts its starts so a test can assert that a closed plan was never
   * restarted, and seeds a per-run context variable so the context a re-arm copies against carries
   * the same kind of residue a completed MATCH pass leaves.
   */
  private static final class ReplayableSourceStep extends AbstractExecutionStep {

    private final List<Result> rows;
    private int startCount;
    private boolean closed;

    ReplayableSourceStep(CommandContext ctx, List<Result> rows) {
      super(ctx, false);
      this.rows = rows;
    }

    @Override
    protected ExecutionStream internalStart(CommandContext ctx) {
      startCount++;
      if (closed) {
        return ExecutionStream.empty();
      }
      ctx.setVariable(PER_RUN_VARIABLE, startCount);
      return ExecutionStream.resultIterator(rows.iterator());
    }

    @Override
    public void close() {
      closed = true;
      super.close();
    }

    @Override
    public ExecutionStep copy(CommandContext ctx) {
      return new ReplayableSourceStep(ctx, rows);
    }
  }
}
