package com.jetbrains.youtrackdb.internal.core.gql.executor;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.gql.executor.resultset.GqlExecutionStream;
import com.jetbrains.youtrackdb.internal.core.gql.parser.GqlMatchVisitor;
import com.jetbrains.youtrackdb.internal.core.sql.executor.InternalExecutionPlan;
import com.jetbrains.youtrackdb.internal.core.sql.executor.MatchExecutionPlanner;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * GQL MATCH execution step that delegates to the unified YQL MATCH planner and engine.
 * Builds only the shared IR (Pattern + aliasClasses) and calls the planner with that IR;
 * no SQL statement is constructed.
 */
public final class GqlUnifiedMatchStep extends GqlAbstractExecutionStep {

  private static final String DEFAULT_TYPE = "V";

  private final List<GqlMatchVisitor.NodePattern> patterns;

  public GqlUnifiedMatchStep(List<GqlMatchVisitor.NodePattern> patterns) {
    this.patterns = Objects.requireNonNull(patterns);
  }

  @Override
  protected GqlExecutionStream internalStart(GqlExecutionContext ctx) {
    if (patterns.isEmpty()) {
      return GqlExecutionStream.empty();
    }
    if (prev != null) {
      throw new IllegalStateException("Unified MATCH step must be the first step");
    }

    var session = ctx.session();
    var commandContext = new BasicCommandContext(session);

    var ir = buildMatchIr();
    var planner = new MatchExecutionPlanner(
        ir.pattern(), ir.aliasClasses(), ir.aliasFilters(), ir.aliasRids());
    var plan = planner.createExecutionPlan(commandContext, false);

    var sqlStream = plan.start();
    return new SqlResultToGqlStreamAdapter(sqlStream, commandContext, plan);
  }

  /** Builds the shared MATCH IR (pattern + alias maps) from GQL node patterns. No SQL AST. */
  private MatchIr buildMatchIr() {
    var pattern = new Pattern();
    var aliasClasses = new LinkedHashMap<String, String>();
    var anonymousCounter = 0;

    for (var p : patterns) {
      var alias = effectiveAlias(p.alias(), anonymousCounter);
      if (p.alias() == null || p.alias().isBlank()) {
        anonymousCounter++;
      }
      pattern.addNode(alias);
      aliasClasses.put(alias, effectiveType(p.label()));
    }

    return new MatchIr(pattern, aliasClasses, null, null);
  }

  /** Shared MATCH IR: pattern plus alias→class/filter/rid maps. No SQL-specific AST. */
  private record MatchIr(
      Pattern pattern,
      Map<String, String> aliasClasses,
      @Nullable Map<String, SQLWhereClause> aliasFilters,
      @Nullable Map<String, SQLRid> aliasRids) {}

  private static String effectiveAlias(@Nullable String alias, int anonymousCounter) {
    return (alias != null && !alias.isBlank()) ? alias : ("$c" + anonymousCounter);
  }

  private static String effectiveType(@Nullable String label) {
    return (label == null || label.isBlank()) ? DEFAULT_TYPE : label;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return "  ".repeat(Math.max(0, depth * indent)) + "GqlUnifiedMatchStep(patterns=" + patterns.size() + ")";
  }

  @Override
  public GqlExecutionStep copy() {
    var copy = new GqlUnifiedMatchStep(patterns);
    if (prev != null) {
      copy.setPrevious(prev.copy());
    }
    return copy;
  }

  /**
   * Adapts SQL ExecutionStream (Result per row) to GqlExecutionStream (Map alias -> vertex per row).
   */
  private static final class SqlResultToGqlStreamAdapter implements GqlExecutionStream {

    private final ExecutionStream sqlStream;
    private final com.jetbrains.youtrackdb.internal.core.command.CommandContext commandContext;
    private final InternalExecutionPlan plan;
    private boolean closed;

    SqlResultToGqlStreamAdapter(
        ExecutionStream sqlStream,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext commandContext,
        InternalExecutionPlan plan) {
      this.sqlStream = sqlStream;
      this.commandContext = commandContext;
      this.plan = plan;
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
        throw new java.util.NoSuchElementException();
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
      plan.close();
    }

  }
}
