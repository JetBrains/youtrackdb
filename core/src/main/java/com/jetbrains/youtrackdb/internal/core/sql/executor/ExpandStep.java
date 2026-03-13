package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * Intermediate step that implements the {@code expand(field)} projection operator.
 *
 * <p>EXPAND takes a single field from each upstream record and "expands" its value:
 * <ul>
 *   <li>{@link Identifiable} -- loaded as a full record (single output row)</li>
 *   <li>{@link Iterable} / {@link Iterator} -- each element becomes a separate output row</li>
 *   <li>{@link Map} -- each entry becomes a separate output row (key-value result)</li>
 *   <li>{@link Result} -- passed through as-is</li>
 * </ul>
 *
 * <pre>
 *  SQL:   SELECT expand(friends) FROM Person WHERE name = 'Alice'
 *
 *  Input:  { friends: [#10:1, #10:2, #10:3] }  (single row with link list)
 *  Output: { @rid: #10:1, name: "Bob",   ... }  (three separate records)
 *          { @rid: #10:2, name: "Carol", ... }
 *          { @rid: #10:3, name: "Dave",  ... }
 * </pre>
 *
 * <p>This is commonly used for graph traversals where outgoing edges return link
 * collections that need to be expanded into individual vertex records.
 *
 * @see SelectExecutionPlanner#handleExpand
 * @see UnwindStep
 */
public class ExpandStep extends AbstractExecutionStep {

  /** The alias of the field to expand (e.g. "friends" from expand(friends)). */
  final String expandAlias;

  /**
   * Optional WHERE clause pushed down from an outer SELECT. When present, each
   * expanded element is tested against this filter during iteration — elements
   * that don't match are discarded immediately without flowing through the rest
   * of the pipeline. This avoids materializing and processing records that would
   * be filtered out by the outer WHERE anyway.
   */
  @Nullable private final SQLWhereClause pushDownFilter;

  public ExpandStep(CommandContext ctx, boolean profilingEnabled, String expandAlias) {
    this(ctx, profilingEnabled, expandAlias, null);
  }

  public ExpandStep(CommandContext ctx, boolean profilingEnabled, String expandAlias,
      @Nullable SQLWhereClause pushDownFilter) {
    super(ctx, profilingEnabled);
    this.expandAlias = expandAlias;
    this.pushDownFilter = pushDownFilter;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot expand without a target");
    }
    var resultSet = prev.start(ctx);
    var expanded = resultSet.flatMap(this::nextResults);
    if (pushDownFilter != null) {
      expanded = expanded.filter(
          (result, filterCtx) -> pushDownFilter.matchesFilters(result, filterCtx) ? result : null);
    }
    return expanded;
  }

  private ExecutionStream nextResults(Result nextAggregateItem, CommandContext ctx) {
    if (nextAggregateItem.getPropertyNames().isEmpty()) {
      return ExecutionStream.empty();
    }

    // For entity results, expand the entity itself. For projected results, the single
    // property value is the collection/link to expand.
    Object projValue;
    if (nextAggregateItem.isEntity()) {
      projValue = nextAggregateItem.asEntity();
    } else {
      if (nextAggregateItem.getPropertyNames().size() > 1) {
        throw new IllegalStateException("Invalid EXPAND on record " + nextAggregateItem);
      }
      var propName = nextAggregateItem.getPropertyNames().getFirst();
      projValue = nextAggregateItem.getProperty(propName);
    }

    var db = ctx.getDatabaseSession();
    switch (projValue) {
      case null -> {
        return ExecutionStream.empty();
      }
      case Identifiable identifiable -> {
        if (expandAlias != null) {
          throw new CommandExecutionException(db,
              "Cannot expand a record with a non-null alias: " + expandAlias);
        }
        DBRecord rec;
        try {
          var transaction = db.getActiveTransaction();
          rec = transaction.load(identifiable);
        } catch (RecordNotFoundException rnf) {
          // Deleted or inaccessible records are silently skipped (dangling links are tolerated).
          return ExecutionStream.empty();
        }

        var res = new ResultInternal(ctx.getDatabaseSession(), rec);
        return ExecutionStream.singleton(res);
      }
      case Result result -> {
        return ExecutionStream.singleton(result);
      }
      case Iterator<?> iterator -> {
        return ExecutionStream.iterator(iterator, expandAlias);
      }
      case Iterable<?> iterable -> {
        return ExecutionStream.iterator(iterable.iterator(), expandAlias);
      }
      case Map<?, ?> map -> {
        if (expandAlias != null) {
          throw new CommandExecutionException(db,
              "Cannot expand a map with a non-null alias: " + expandAlias);
        }
        return ExecutionStream.iterator(map.entrySet().iterator(), null);
      }
      default -> {
        return ExecutionStream.empty();
      }
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder(spaces + "+ EXPAND");
    if (pushDownFilter != null) {
      result.append(" (push-down filter: ").append(pushDownFilter).append(")");
    }
    if (profilingEnabled) {
      result.append(" (").append(getCostFormatted()).append(")");
    }
    return result.toString();
  }

  /** Cacheable: the expand alias is a fixed string determined at plan time. */
  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new ExpandStep(ctx, profilingEnabled, expandAlias,
        pushDownFilter != null ? pushDownFilter.copy() : null);
  }
}
