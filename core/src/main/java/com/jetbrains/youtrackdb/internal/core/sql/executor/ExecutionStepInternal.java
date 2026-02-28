package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nonnull;

/**
 * Internal interface for execution plan steps -- the building blocks of a query
 * execution pipeline.
 *
 * <h2>Pull-based execution model</h2>
 * <p>Steps form a doubly-linked chain inside a {@link SelectExecutionPlan}. Execution
 * is <b>pull-based</b>: the consumer calls {@link #start(CommandContext)} on the
 * <em>last</em> (terminal) step, which in turn calls {@code start()} on its
 * predecessor, and so on back to the source step.
 *
 * <pre>
 *  +---------+     +---------+     +---------+     +---------+
 *  | Source  |--&gt;  | Filter  |--&gt;  | Project |--&gt;  | Limit   |
 *  | Step    |     | Step    |     | Step    |     | Step    |
 *  +---------+     +---------+     +---------+     +---------+
 *       ^               ^               ^               |
 *       |               |               |               v
 *     start()         start()         start()       (consumer)
 *     produces        filters         transforms    receives
 *     records         records         records       final stream
 * </pre>
 *
 * <p>Each step returns an {@link ExecutionStream} -- a lazy, one-pass iterator that
 * produces {@link Result} objects on demand. Steps may buffer, filter, transform,
 * or aggregate the upstream stream before returning their own.
 *
 * <h2>Key contracts</h2>
 * <ul>
 *   <li>{@link #start(CommandContext)} -- primary execution entry point</li>
 *   <li>{@link #setPrevious}/{@link #setNext} -- chain wiring (called by
 *       {@link SelectExecutionPlan#chain})</li>
 *   <li>{@link #copy(CommandContext)} -- deep copy for plan caching</li>
 *   <li>{@link #canBeCached()} -- must return {@code true} for the plan to be cacheable</li>
 *   <li>{@link #close()} -- releases resources; propagates backward through the chain</li>
 * </ul>
 *
 * @see AbstractExecutionStep
 * @see SelectExecutionPlan
 */
public interface ExecutionStepInternal extends ExecutionStep {

  /**
   * Starts (or restarts) this step's execution and returns a lazy stream of results.
   * The step should pull from its predecessor ({@code prev.start(ctx)}) and transform
   * or filter the upstream stream.
   *
   * @throws TimeoutException if the query timeout is exceeded
   */
  ExecutionStream start(CommandContext ctx) throws TimeoutException;

  /** Propagates a timeout signal backward through the chain. */
  void sendTimeout();

  /** Links this step to its upstream predecessor (the step that feeds data into this one). */
  void setPrevious(ExecutionStepInternal step);

  /** Links this step to its downstream successor (the step that consumes data from this one). */
  void setNext(ExecutionStepInternal step);

  /**
   * Releases any resources held by this step and propagates the close signal
   * backward to the predecessor. After closing, the step must not be started again.
   */
  void close();

  /**
   * Builds an indentation string for pretty-printing execution plans.
   *
   * @param depth  nesting depth (number of indent blocks)
   * @param indent number of spaces per indent level
   * @return a string of {@code depth * indent} spaces
   */
  static String getIndent(int depth, int indent) {
    var result = new StringBuilder();
    for (var i = 0; i < depth; i++) {
      for (var j = 0; j < indent; j++) {
        result.append(" ");
      }
    }
    return result.toString();
  }

  /**
   * Returns a human-readable representation of this step, indented to the given depth.
   * Subclasses override this to include step-specific details (conditions, index names, etc.).
   *
   * @param depth  nesting depth for indentation
   * @param indent number of spaces per indent level
   */
  default String prettyPrint(int depth, int indent) {
    var spaces = getIndent(depth, indent);
    return spaces + getClass().getSimpleName();
  }

  /**
   * Returns a human-readable name for this step instance. Defaults to the class simple name.
   * Steps with multiple modes of operation may override to include context (e.g., index name).
   */
  @Override
  default @Nonnull String getName() {
    return getClass().getSimpleName();
  }

  /**
   * Returns the step type identifier. Defaults to the class simple name.
   * Unlike {@link #getName()}, the type should be stable across different instances
   * of the same step class (used for plan comparison and analysis).
   */
  @Override
  default @Nonnull String getType() {
    return getClass().getSimpleName();
  }

  @Override
  default String getDescription() {
    return prettyPrint(0, 3);
  }

  /**
   * Returns the target node identifier for execution plan display purposes.
   * Embedded steps always return {@code "<local>"}. Remote/distributed steps
   * would override this to return the server address or node name.
   */
  default String getTargetNode() {
    return "<local>";
  }

  /** Returns child steps nested inside this step (e.g. sub-plan steps). Empty by default. */
  @Override
  @Nonnull
  default List<ExecutionStep> getSubSteps() {
    return Collections.emptyList();
  }

  /** Returns child execution plans embedded in this step (e.g. subquery plans). Empty by default. */
  default List<ExecutionPlan> getSubExecutionPlans() {
    return Collections.emptyList();
  }

  /**
   * Resets this step's mutable state so it can be re-executed from scratch. Called by the
   * execution plan when a query is re-run (e.g., in a loop or retry scenario).
   * Default is a no-op; steps with internal cursors, counters, or accumulated
   * state (e.g., aggregation buffers) must override to clear that state.
   */
  default void reset() {
    // do nothing
  }

  /**
   * Serializes this step into a {@link Result} for plan persistence or transmission.
   * Not all steps support serialization -- the default throws {@link UnsupportedOperationException}.
   *
   * @param session the database session for type resolution
   * @return a Result containing the serialized step
   * @throws UnsupportedOperationException if this step does not support serialization
   */
  default Result serialize(DatabaseSessionEmbedded session) {
    throw new UnsupportedOperationException();
  }

  /**
   * Reconstructs this step's state from a previously serialized {@link Result}.
   * Not all steps support deserialization -- the default throws {@link UnsupportedOperationException}.
   *
   * @param fromResult the serialized step data
   * @param session    the database session for type resolution
   * @throws UnsupportedOperationException if this step does not support deserialization
   */
  default void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    throw new UnsupportedOperationException();
  }

  /**
   * Serializes the common parts of any step: its Java class name, sub-steps, and
   * sub-execution plans. Concrete steps call this first, then append step-specific
   * properties to the returned {@link ResultInternal}.
   */
  static ResultInternal basicSerialize(DatabaseSessionEmbedded session,
      ExecutionStepInternal step) {
    var result = new ResultInternal(session);
    result.setProperty(InternalExecutionPlan.JAVA_TYPE, step.getClass().getName());
    if (!step.getSubSteps().isEmpty()) {
      List<Result> serializedSubsteps = new ArrayList<>();
      for (var substep : step.getSubSteps()) {
        serializedSubsteps.add(((ExecutionStepInternal) substep).serialize(session));
      }
      result.setProperty("subSteps", serializedSubsteps);
    }

    if (step.getSubExecutionPlans() != null && !step.getSubExecutionPlans().isEmpty()) {
      List<Result> serializedSubPlans = new ArrayList<>();
      for (var substep : step.getSubExecutionPlans()) {
        serializedSubPlans.add(((InternalExecutionPlan) substep).serialize(session));
      }
      result.setProperty("subExecutionPlans", serializedSubPlans);
    }
    return result;
  }

  /**
   * Deserializes the common parts of any step: reconstructs sub-steps and sub-execution
   * plans from their serialized forms. Concrete steps call this first, then read their
   * step-specific properties from the serialized result.
   */
  static void basicDeserialize(Result serialized, ExecutionStepInternal step,
      DatabaseSessionEmbedded session)
      throws ClassNotFoundException, IllegalAccessException, InstantiationException {
    List<Result> serializedSubsteps = serialized.getProperty("subSteps");
    if (serializedSubsteps != null) {
      for (var serializedSub : serializedSubsteps) {
        String className = serializedSub.getProperty(InternalExecutionPlan.JAVA_TYPE);
        var subStep =
            (ExecutionStepInternal) Class.forName(className).newInstance();
        subStep.deserialize(serializedSub, session);
        step.getSubSteps().add(subStep);
      }
    }

    List<Result> serializedPlans = serialized.getProperty("subExecutionPlans");
    if (serializedPlans != null) {
      for (var serializedSub : serializedPlans) {
        String className = serializedSub.getProperty(InternalExecutionPlan.JAVA_TYPE);
        var subStep =
            (InternalExecutionPlan) Class.forName(className).newInstance();
        subStep.deserialize(serializedSub, session);
        step.getSubExecutionPlans().add(subStep);
      }
    }
  }

  /**
   * Deep-copies this step (and its sub-steps/sub-plans) for use in a new execution context.
   * Required for plan caching: a cached plan is copied before each execution.
   */
  ExecutionStep copy(CommandContext ctx);

  /**
   * Returns {@code true} if this step can be part of a cached execution plan.
   *
   * <p>The default is {@code false} (safe-by-default: steps must explicitly opt in
   * to caching by overriding this method). Steps that depend on mutable external
   * state (e.g. input parameters resolved at planning time) must keep the default.
   * Stateless or configuration-only steps should override to return {@code true}.
   */
  default boolean canBeCached() {
    return false;
  }
}
