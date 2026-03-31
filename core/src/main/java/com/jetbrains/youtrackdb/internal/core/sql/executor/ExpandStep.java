package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.PreFilterableLinkBagIterable;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import it.unimi.dsi.fastutil.ints.IntSet;
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
 * <h2>Predicate push-down</h2>
 *
 * <p>Four levels of push-down are supported (cheapest to most expensive):
 * <ol>
 *   <li><b>Class filter ({@code acceptedCollectionIds})</b> — When the outer WHERE
 *       contains {@code @class = 'ClassName'}, the planner resolves the class to its
 *       collection (cluster) IDs. During expansion, the collection ID of each target
 *       RID is checked <em>before</em> loading the record from storage. Vertices
 *       whose collection ID is not in the accepted set are skipped with zero disk I/O.
 *   <li><b>RID filter ({@code ridFilterDescriptor})</b> — When the outer WHERE
 *       contains {@code @rid = <expr>} or {@code out/in('EdgeClass').@rid = <expr>},
 *       the descriptor is resolved at execution time to a {@link RidSet}. Non-matching
 *       RIDs are skipped without disk I/O. This supports {@code $parent} references
 *       because resolution happens at execution time.
 *   <li><b>Index pre-filter ({@code indexDescriptor})</b> — When the target class is
 *       known and the remaining WHERE references an indexed property, the index is
 *       queried at execution time to build a {@link RidSet}. Non-matching RIDs are
 *       skipped without disk I/O.
 *   <li><b>Generic filter ({@code pushDownFilter})</b> — Any remaining WHERE predicates
 *       are applied <em>after</em> each expanded record is loaded.
 * </ol>
 *
 * @see SelectExecutionPlanner#handleExpand
 * @see UnwindStep
 */
public class ExpandStep extends AbstractExecutionStep {

  /** The alias of the field to expand (e.g. "friends" from expand(friends)). */
  final String expandAlias;

  /**
   * Optional WHERE clause pushed down from an outer SELECT. Applied after each
   * expanded record is loaded — filters out non-matching records before they
   * flow through the rest of the pipeline.
   */
  @Nullable private final SQLWhereClause pushDownFilter;

  /**
   * When non-null, only vertices whose collection (cluster) ID is in this set
   * are loaded from storage. This is resolved from {@code @class = 'X'} predicates
   * at plan time and avoids disk I/O entirely for non-matching vertices.
   */
  @Nullable private final IntSet acceptedCollectionIds;

  /**
   * When non-null, resolved at execution time to a {@link RidSet} that
   * pre-filters expanded records. Supports {@code @rid = <expr>} and
   * {@code out/in('EdgeClass').@rid = <expr>} patterns, including
   * {@code $parent} references.
   */
  @Nullable private final RidFilterDescriptor ridFilterDescriptor;

  /**
   * When non-null, describes an index lookup to pre-filter expanded records.
   * At execution time, the index is queried to build a {@link RidSet}; during
   * iteration, RIDs not in the set are skipped without loading from storage.
   */
  @Nullable private final IndexSearchDescriptor indexDescriptor;

  public ExpandStep(CommandContext ctx, boolean profilingEnabled, String expandAlias) {
    this(ctx, profilingEnabled, expandAlias, null, null, null, null);
  }

  public ExpandStep(CommandContext ctx, boolean profilingEnabled, String expandAlias,
      @Nullable SQLWhereClause pushDownFilter,
      @Nullable IntSet acceptedCollectionIds) {
    this(ctx, profilingEnabled, expandAlias, pushDownFilter, acceptedCollectionIds, null, null);
  }

  public ExpandStep(CommandContext ctx, boolean profilingEnabled, String expandAlias,
      @Nullable SQLWhereClause pushDownFilter,
      @Nullable IntSet acceptedCollectionIds,
      @Nullable RidFilterDescriptor ridFilterDescriptor,
      @Nullable IndexSearchDescriptor indexDescriptor) {
    super(ctx, profilingEnabled);
    this.expandAlias = expandAlias;
    this.pushDownFilter = pushDownFilter;
    this.acceptedCollectionIds = acceptedCollectionIds;
    this.ridFilterDescriptor = ridFilterDescriptor;
    this.indexDescriptor = indexDescriptor;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Cannot expand without a target");
    }

    @Nullable RidSet ridFilterSet = null;
    if (ridFilterDescriptor != null) {
      ridFilterSet = ridFilterDescriptor.resolve(ctx, null);
    }
    @Nullable RidSet indexRidSet = null;
    if (indexDescriptor != null) {
      indexRidSet = resolveIndexToRidSet(indexDescriptor, ctx);
    }
    final var combinedRidSet = intersect(ridFilterSet, indexRidSet);

    var resultSet = prev.start(ctx);
    var expanded = resultSet.flatMap(
        (result, fmCtx) -> nextResults(result, fmCtx, combinedRidSet));
    if (pushDownFilter != null) {
      expanded = expanded.filter(
          (result, filterCtx) -> pushDownFilter.matchesFilters(result, filterCtx) ? result : null);
    }
    return expanded;
  }

  private ExecutionStream nextResults(
      Result nextAggregateItem, CommandContext ctx, @Nullable RidSet indexRidSet) {
    if (nextAggregateItem.getPropertyNames().isEmpty()) {
      return ExecutionStream.empty();
    }

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
        var rid = identifiable.getIdentity();
        if (acceptedCollectionIds != null
            && !acceptedCollectionIds.contains(rid.getCollectionId())) {
          return ExecutionStream.empty();
        }
        if (indexRidSet != null && !indexRidSet.contains(rid)) {
          return ExecutionStream.empty();
        }
        DBRecord rec;
        try {
          var transaction = db.getActiveTransaction();
          rec = transaction.load(identifiable);
        } catch (RecordNotFoundException rnf) {
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
        // PreFilterableLinkBagIterable covers both vertex and edge LinkBag
        // iterables. ChainedIterable (from bothE/both) does not implement the
        // interface, so it silently degrades to unfiltered iteration.
        if (iterable instanceof PreFilterableLinkBagIterable linkBagIterable
            && (acceptedCollectionIds != null || indexRidSet != null)) {
          var filtered = linkBagIterable;
          if (acceptedCollectionIds != null) {
            filtered = filtered.withClassFilter(acceptedCollectionIds);
          }
          if (indexRidSet != null
              && filtered.size() >= TraversalPreFilterHelper.minLinkBagSize()
              && TraversalPreFilterHelper.passesRatioCheck(
                  indexRidSet.size(), filtered.size())) {
            filtered = filtered.withRidFilter(indexRidSet);
          }
          return ExecutionStream.iterator(filtered.iterator(), expandAlias);
        }
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
    if (acceptedCollectionIds != null) {
      result.append(" (class filter: ").append(acceptedCollectionIds.size())
          .append(" collection(s))");
    }
    if (ridFilterDescriptor instanceof RidFilterDescriptor.DirectRid) {
      result.append(" (rid filter: direct)");
    } else if (ridFilterDescriptor instanceof RidFilterDescriptor.EdgeRidLookup lookup) {
      result.append(" (rid filter: ")
          .append(lookup.traversalDirection()).append("('")
          .append(lookup.edgeClassName()).append("'))");
    }
    if (indexDescriptor != null) {
      result.append(" (index pre-filter: ")
          .append(indexDescriptor.getIndex().getName()).append(")");
    }
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
        pushDownFilter != null ? pushDownFilter.copy() : null,
        acceptedCollectionIds,
        ridFilterDescriptor,
        indexDescriptor);
  }

  @Nullable private static RidSet resolveIndexToRidSet(
      IndexSearchDescriptor desc, CommandContext ctx) {
    return TraversalPreFilterHelper.resolveIndexToRidSet(desc, ctx);
  }

  @Nullable private static RidSet intersect(@Nullable RidSet a, @Nullable RidSet b) {
    return TraversalPreFilterHelper.intersect(a, b);
  }
}
