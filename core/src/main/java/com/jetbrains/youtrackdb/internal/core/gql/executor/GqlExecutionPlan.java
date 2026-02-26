package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.annotation.Nullable;

/// Execution plan for GQL queries.
///
/// Wraps an [InternalExecutionPlan] produced by the unified YQL
/// [com.jetbrains.youtrackdb.internal.core.sql.executor.match.MatchExecutionPlanner],
/// adapting its [ExecutionStream] to [GqlExecutionStream].
/// Future GQL features (WHERE, RETURN, edge traversal) extend the shared IR and planner,
/// not this class.
public class GqlExecutionPlan {

  @Nullable
  private InternalExecutionPlan sqlPlan;

  private GqlExecutionPlan() {}

  /// Creates an execution plan that wraps an [InternalExecutionPlan] from the
  /// unified MATCH planner.
  public static GqlExecutionPlan forSqlMatchPlan(InternalExecutionPlan sqlPlan) {
    var plan = new GqlExecutionPlan();
    plan.sqlPlan = Objects.requireNonNull(sqlPlan);
    return plan;
  }

  /// Creates an empty execution plan (no results).
  public static GqlExecutionPlan empty() {
    return new GqlExecutionPlan();
  }

  /// Start execution and return a stream of results.
  ///
  /// The session parameter rebinds the underlying plan's context to the caller's
  /// current session and activates it on the current thread.  This is essential for
  /// cached plans whose context still references a session from a previous request
  /// (potentially on a different thread).
  /// Pass null only for empty plans (no underlying SQL plan).
  public GqlExecutionStream start(@Nullable DatabaseSessionEmbedded session) {
    if (sqlPlan == null) {
      return GqlExecutionStream.empty();
    }
    Objects.requireNonNull(session, "session required for non-empty plan");
    sqlPlan.getContext().setDatabaseSession(session);
    session.activateOnCurrentThread();
    var stream = sqlPlan.start();
    return new SqlStreamAdapter(stream, sqlPlan.getContext());
  }

  /// Close the execution plan and release resources.
  public void close() {
    if (sqlPlan != null) {
      sqlPlan.close();
    }
  }

  /// Reset the execution plan for re-execution.
  public void reset() {
    if (sqlPlan != null) {
      sqlPlan.reset(sqlPlan.getContext());
    }
  }

  /// Create a copy of this execution plan for caching purposes.
  public GqlExecutionPlan copy() {
    if (sqlPlan != null) {
      return forSqlMatchPlan(sqlPlan.copy(sqlPlan.getContext()));
    }
    return empty();
  }

  /// Check if this execution plan can be cached.
  @SuppressWarnings("SameReturnValue")
  public static boolean canBeCached() {
    return true;
  }

  /// Adapts YQL's [ExecutionStream] (which requires
  /// [com.jetbrains.youtrackdb.internal.core.command.CommandContext])
  /// to GQL's context-free [GqlExecutionStream].
  static final class SqlStreamAdapter implements GqlExecutionStream {

    private final ExecutionStream sqlStream;
    private final com.jetbrains.youtrackdb.internal.core.command.CommandContext commandContext;
    private boolean closed;

    SqlStreamAdapter(ExecutionStream sqlStream,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext commandContext) {
      this.sqlStream = sqlStream;
      this.commandContext = commandContext;
    }

    @Override
    public boolean hasNext() {
      if (closed) {
        return false;
      }
      var has = sqlStream.hasNext(commandContext);
      if (!has) {
        close();
      }
      return has;
    }

    @Override
    public Object next() {
      if (closed) {
        throw new NoSuchElementException();
      }
      return sqlStream.next(commandContext);
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      closed = true;
      sqlStream.close(commandContext);
    }
  }
}
