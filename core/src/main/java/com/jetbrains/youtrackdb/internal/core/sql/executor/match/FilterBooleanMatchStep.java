package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ExecutionStepInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A streaming boolean check. Child nodes are evaluators, not row-producing joins. */
public final class FilterBooleanMatchStep extends AbstractExecutionStep {

  enum Kind {
    AND, OR, NOT, FILTER, PROBE
  }

  private final Kind kind;
  private final List<FilterBooleanMatchStep> children;
  private final String alias;
  private final SQLWhereClause condition;
  private final List<AbstractExecutionStep> probeSteps;
  private final HashJoinMatchStep hash;
  private Set<JoinKey> keys;
  private final DetachedCheckMemory memory;

  FilterBooleanMatchStep(CommandContext ctx, boolean profiling, Kind kind,
      List<FilterBooleanMatchStep> children, String alias, SQLWhereClause condition,
      List<AbstractExecutionStep> probeSteps, HashJoinMatchStep hash) {
    this(ctx, profiling, kind, children, alias, condition, probeSteps, hash, null);
  }

  FilterBooleanMatchStep(CommandContext ctx, boolean profiling, Kind kind,
      List<FilterBooleanMatchStep> children, String alias, SQLWhereClause condition,
      List<AbstractExecutionStep> probeSteps, HashJoinMatchStep hash, DetachedCheckReadSet reads) {
    super(ctx, profiling);
    this.memory = new DetachedCheckMemory(reads);
    this.kind = kind;
    this.children = List.copyOf(children);
    this.alias = alias;
    this.condition = condition;
    this.probeSteps = List.copyOf(probeSteps);
    this.hash = hash;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) {
    if (prev == null) {
      throw new IllegalStateException("boolean filter requires a previous step");
    }
    reset();
    // Build every selected set before starting upstream. A null result means sticky overflow,
    // not an uninitialized build. No row can retry the build during this start.
    prepare(ctx);
    return prev.start(ctx).filter((row, context) -> evaluate(row, context) ? row : null);
  }

  private void prepare(CommandContext ctx) {
    memory.start();
    if (hash != null) {
      // BasicCommandContext forwards writes to the parent that owns a system variable.
      // This boundary keeps build writes local while preserving parent reads and cancellation.
      var isolated = new BuildContext();
      isolated.setParentWithoutOverridingChild(ctx);
      keys = hash.buildHashSet(isolated);
    }
    for (var child : children) {
      child.prepare(ctx);
    }
  }

  private boolean evaluate(Result row, CommandContext ctx) {
    return switch (kind) {
      case AND -> {
        for (var child : children) {
          if (!child.evaluate(row, ctx)) {
            yield false;
          }
        }
        yield true;
      }
      case OR -> {
        for (var child : children) {
          if (child.evaluate(row, ctx)) {
            yield true;
          }
        }
        yield false;
      }
      case NOT -> !children.getFirst().evaluate(row, ctx);
      case FILTER -> {
        var value = row.getProperty(alias);
        var record = ResultInternal.toResultInternal(value, ctx.getDatabaseSession());
        if (record == null) {
          yield false;
        }
        var savedMatched = ctx.getSystemVariable(CommandContext.VAR_MATCHED);
        var savedCurrent = ctx.getSystemVariable(CommandContext.VAR_CURRENT_MATCH);
        // Seed missing bindings before linking a child. BasicCommandContext otherwise forwards
        // writes to a parent that owns the variable, and absent bindings cannot be restored.
        CommandContext filterCtx = ctx;
        if (savedMatched == null || savedCurrent == null) {
          var child = new BasicCommandContext();
          child.setSystemVariable(CommandContext.VAR_MATCHED, row);
          child.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, record);
          child.setParentWithoutOverridingChild(ctx);
          filterCtx = child;
        }
        try {
          filterCtx.setSystemVariable(CommandContext.VAR_MATCHED, row);
          filterCtx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, record);
          yield condition.matchesFilters(record, filterCtx);
        } finally {
          if (filterCtx == ctx) {
            ctx.setSystemVariable(CommandContext.VAR_MATCHED, savedMatched);
            ctx.setSystemVariable(CommandContext.VAR_CURRENT_MATCH, savedCurrent);
          }
        }
      }
      case PROBE -> keys == null
          ? memory.matches(row,
              () -> DetachedMatchPatternProbe.matches(row, probeSteps, ctx, profilingEnabled))
          : keys.contains(hash.extractKey(row));
    };
  }

  @Override
  public void reset() {
    keys = null;
    memory.clear();
    children.forEach(FilterBooleanMatchStep::reset);
    if (hash != null) {
      hash.reset();
    }
  }

  @Override
  public void close() {
    reset();
    try {
      children.forEach(FilterBooleanMatchStep::close);
      probeSteps.forEach(AbstractExecutionStep::close);
      if (hash != null) {
        hash.close();
      }
    } finally {
      super.close();
    }
  }

  @Override
  public List<ExecutionStep> getSubSteps() {
    var steps = new ArrayList<ExecutionStep>(children);
    steps.addAll(probeSteps);
    if (hash != null) {
      steps.add(hash);
    }
    return List.copyOf(steps);
  }

  @Override
  public boolean canBeCached() {
    return (condition == null || condition.isCacheable(ctx.getDatabaseSession()))
        && getSubSteps().stream().allMatch(step -> ((ExecutionStepInternal) step).canBeCached());
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var text = new StringBuilder(spaces).append("+ BOOLEAN ").append(kind);
    if (condition != null) {
      text.append(" on ").append(alias).append(": ").append(condition);
    }
    for (var step : getSubSteps()) {
      text.append("\n").append(((ExecutionStepInternal) step).prettyPrint(depth + 1, indent));
    }
    return text.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FilterBooleanMatchStep(ctx, profilingEnabled, kind,
        children.stream().map(child -> (FilterBooleanMatchStep) child.copy(ctx)).toList(),
        alias, condition == null ? null : condition.copy(),
        probeSteps.stream().map(step -> (AbstractExecutionStep) step.copy(ctx)).toList(),
        hash == null ? null : (HashJoinMatchStep) hash.copy(ctx), memory.reads());
  }

  private static final class BuildContext extends BasicCommandContext {
    private final Map<Integer, Object> local = new HashMap<>();

    @Override
    public <T> void setSystemVariable(int id, T value) {
      local.put(id, value);
    }

    @Override
    @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
    public <T> T getSystemVariable(int id) {
      return local.containsKey(id) ? (T) local.get(id) : super.getSystemVariable(id);
    }

    @Override
    public boolean hasSystemVariable(int id) {
      return local.containsKey(id) || super.hasSystemVariable(id);
    }
  }
}
