package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Fetches records by evaluating a correlated RID expression once per parent row. Used when a
 * LET-hosted subquery contains {@code SELECT FROM <Class> WHERE @rid = $parent.$current.<field>}.
 *
 * <p>Replaces the {@code FetchFromClassExecutionStep + FilterStep} combination that would otherwise
 * scan every record in the class and post-filter on the RID predicate. Class membership uses the
 * same polymorphic collection-id set as the plan-time RID path, {@link ExpandStep}, and MATCH
 * pre-filter ({@link TraversalPreFilterHelper#collectionIdsForClass}): a RID whose collection is
 * not in that set is dropped before load, matching scan+filter.
 */
public class FetchFromCorrelatedRidStep extends AbstractExecutionStep {

  private SQLExpression ridExpression;
  /**
   * Polymorphic collection IDs of the FROM class — same {@link IntSet} shape as
   * {@link ExpandStep}'s {@code acceptedCollectionIds} and the plan-time RID membership filter.
   */
  private IntSet classCollectionIds;

  public FetchFromCorrelatedRidStep(
      SQLExpression ridExpression,
      @Nonnull IntSet classCollectionIds,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.ridExpression = ridExpression;
    this.classCollectionIds = classCollectionIds;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }
    var value = ridExpression.execute((Result) null, ctx);
    var candidates = equalityMatchSet(value, classCollectionIds);
    if (candidates.isEmpty()) {
      return ExecutionStream.empty();
    }
    return ExecutionStream.loadIterator(candidates.iterator(), true);
  }

  /**
   * Coerces the evaluated RHS of {@code @rid = …} to the identifier set the scan filter would
   * match, mirroring {@code QueryOperatorEquals} value handling. Drops invalid positions and
   * identifiers outside {@code classCollectionIds}, deduplicates, and sorts ascending.
   */
  static List<RecordIdInternal> equalityMatchSet(Object value, IntSet classCollectionIds) {
    var collected = new LinkedHashSet<RecordIdInternal>();
    collectEqualityMatchSet(value, collected);
    if (collected.isEmpty()) {
      return List.of();
    }
    var filtered = new ArrayList<RecordIdInternal>(collected.size());
    for (var rid : collected) {
      if (classCollectionIds.contains(rid.getCollectionId())) {
        filtered.add(rid);
      }
    }
    if (filtered.isEmpty()) {
      return List.of();
    }
    filtered.sort(RecordIdInternal::compareTo);
    return filtered;
  }

  /**
   * Adds every identifier {@code QueryOperatorEquals} would accept for {@code @rid = value} into
   * {@code out}. Position {@code -1} and non-identifier shapes are skipped.
   */
  private static void collectEqualityMatchSet(Object value, LinkedHashSet<RecordIdInternal> out) {
    if (value == null) {
      return;
    }
    if (value instanceof Collection<?> collection && collection.size() == 1) {
      value = SelectExecutionPlanner.singleElementOrNull(collection);
      if (value == null) {
        return;
      }
    }
    if (value instanceof Result result) {
      if (result.isIdentifiable() && result.getIdentity().isPersistent()) {
        addRecordId(out, result.getIdentity());
        return;
      }
      var propertyNames = result.getPropertyNames();
      if (propertyNames.size() != 1) {
        return;
      }
      var fieldValue = result.getProperty(propertyNames.iterator().next());
      if (fieldValue == null) {
        return;
      }
      if (MultiValue.isMultiValue(fieldValue)) {
        for (var element : MultiValue.getMultiValueIterable(fieldValue)) {
          if (element instanceof Identifiable identifiable) {
            addRecordId(out, identifiable.getIdentity());
          } else if (element instanceof RecordIdInternal rid) {
            addRecordId(out, rid);
          }
        }
        return;
      }
      if (fieldValue instanceof Identifiable identifiable) {
        addRecordId(out, identifiable.getIdentity());
      }
      return;
    }
    if (value instanceof Identifiable identifiable) {
      addRecordId(out, identifiable.getIdentity());
      return;
    }
    if (value instanceof String) {
      var rid = SelectExecutionPlanner.toRecordIdCandidate(value);
      if (rid != null) {
        addRecordId(out, rid);
      }
    }
  }

  private static void addRecordId(LinkedHashSet<RecordIdInternal> out, @Nullable Object identity) {
    if (identity instanceof RecordIdInternal rid && rid.isValidPosition()) {
      out.add(rid);
    }
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    return ExecutionStepInternal.getIndent(depth, indent)
        + "+ FETCH FROM CORRELATED RID\n"
        + ExecutionStepInternal.getIndent(depth, indent)
        + "  "
        + ridExpression;
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    if (ridExpression != null) {
      result.setProperty("ridExpression", ridExpression.serialize(session));
    }
    if (classCollectionIds != null) {
      List<Integer> ids = new ArrayList<>(classCollectionIds.size());
      classCollectionIds.forEach(ids::add);
      result.setProperty("classCollectionIds", ids);
    }
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      if (fromResult.getProperty("ridExpression") != null) {
        ridExpression = new SQLExpression(-1);
        ridExpression.deserialize(fromResult.getProperty("ridExpression"));
      }
      List<Integer> ids = fromResult.getProperty("classCollectionIds");
      if (ids != null) {
        var set = new IntOpenHashSet(ids.size());
        set.addAll(ids);
        classCollectionIds = set;
      }
      reset();
    } catch (Exception e) {
      throw BaseException.wrapException(
          new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public boolean canBeCached() {
    return false;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    SQLExpression expressionCopy = ridExpression == null ? null : ridExpression.copy();
    return new FetchFromCorrelatedRidStep(
        expressionCopy, classCollectionIds, ctx, profilingEnabled);
  }
}
