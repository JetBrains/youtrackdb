package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import javax.annotation.Nullable;

/**
 * Internal interface for execution plans that can be started, cached, serialized,
 * and copied.
 *
 * <p>The primary implementation is {@link SelectExecutionPlan}, which holds a chain
 * of {@link ExecutionStepInternal} nodes. Other statement types (INSERT, UPDATE,
 * DELETE, etc.) provide their own implementations.
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  1. Plan is created by a planner (e.g. SelectExecutionPlanner)
 *  2. Optionally cached in ExecutionPlanCache
 *  3. Copied via copy() before each execution (to avoid shared mutable state)
 *  4. start() is called to begin pull-based execution
 *  5. close() is called when the consumer is done (releases resources)
 * </pre>
 *
 * @see SelectExecutionPlan
 * @see ExecutionStepInternal
 */
public interface InternalExecutionPlan extends ExecutionPlan {

  /** Key used in serialized Result objects to store the Java class name for deserialization. */
  String JAVA_TYPE = "javaType";

  /** Releases resources held by this plan (propagates to all steps). */
  void close();

  /**
   * Begins execution and returns a lazy stream of results. The stream produces
   * records on demand; when exhausted it returns an empty stream on subsequent calls.
   *
   * @return a lazy {@link ExecutionStream} over the query results
   */
  ExecutionStream start();

  /**
   * Resets the plan's mutable state so it can be re-executed from scratch.
   * Propagates to all steps via {@link ExecutionStepInternal#reset()}.
   */
  void reset(CommandContext ctx);

  /** Returns the command context associated with this plan (database session, variables, etc.). */
  CommandContext getContext();

  /**
   * Returns the estimated cost of this plan, nominally in nanoseconds.
   *
   * <p>Note: the primary implementation ({@link SelectExecutionPlan}) currently returns
   * {@code 0L}; step-level costs are available via
   * {@link com.jetbrains.youtrackdb.internal.core.query.ExecutionStep#getCost()}.
   */
  long getCost();

  /** Serializes this plan into a {@link Result} for persistent storage. The default throws {@link UnsupportedOperationException}. */
  default Result serialize(DatabaseSessionEmbedded session) {
    throw new UnsupportedOperationException();
  }

  /** Reconstitutes a plan from a previously serialized {@link Result}. The default throws {@link UnsupportedOperationException}. */
  default void deserialize(Result serializedExecutionPlan, DatabaseSessionEmbedded session) {
    throw new UnsupportedOperationException();
  }

  /** Creates a deep copy of this plan for independent execution. The default throws {@link UnsupportedOperationException}. */
  default InternalExecutionPlan copy(CommandContext ctx) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns {@code true} if every step in this plan is cacheable. Only cacheable
   * plans are stored in the {@link com.jetbrains.youtrackdb.internal.core.sql.parser.ExecutionPlanCache}.
   */
  boolean canBeCached();

  /** Returns the original SQL statement text (for logging / plan cache keys), or null. */
  @Nullable
  default String getStatement() {
    return null;
  }

  /** Stores the original SQL statement text. */
  default void setStatement(String stm) {
  }

  /**
   * Returns a parameterized/generic form of the SQL statement (where literal values
   * are replaced with placeholders) used for cache grouping, or null.
   */
  @Nullable
  default String getGenericStatement() {
    return null;
  }

  /** Stores the generic (parameterized) SQL statement. */
  default void setGenericStatement(String stm) {
  }
}
