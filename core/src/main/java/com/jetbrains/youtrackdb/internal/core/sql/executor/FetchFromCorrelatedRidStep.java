package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nonnull;

/**
 * Fetches a single record by evaluating a correlated RID expression at execution time. Used when a
 * LET subquery contains {@code SELECT FROM <Class> WHERE @rid = $parent.$current.<field>}: the RID
 * is not known at plan time but resolves to exactly one record per parent row.
 *
 * <p>Replaces the {@code FetchFromClassExecutionStep + FilterStep} combination that would otherwise
 * scan every record in the class and post-filter on the RID predicate. Class membership uses the
 * same polymorphic collection-id set as the plan-time RID path, {@link ExpandStep}, and MATCH
 * pre-filter ({@link TraversalPreFilterHelper#collectionIdsForClass}): a RID whose collection is
 * not in that set yields an empty stream, matching scan+filter.
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
    this.classCollectionIds = Objects.requireNonNull(classCollectionIds);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }
    var value = ridExpression.execute((Result) null, ctx);
    var rid = coerceEqualityRid(value);
    if (rid == null || !classCollectionIds.contains(rid.getCollectionId())) {
      return ExecutionStream.empty();
    }
    // skipMissing=true: a dangling in-class RID is skipped (empty stream), matching a class
    // scan that never visits a deleted position. The default terminate-on-missing would also
    // be empty for a singleton, but skip-missing is the scan-parity contract of the class-target
    // RID fetch path.
    return ExecutionStream.loadIterator(Collections.singleton(rid).iterator(), true);
  }

  /**
   * Maps the evaluated RHS of {@code @rid = …} to at most one RID, with the same unwrap rules as
   * the plan-time equality path: a size-1 {@link Collection} unwraps; any other multi-value (empty,
   * size 2+, non-Collection) matches nothing.
   */
  private static RecordIdInternal coerceEqualityRid(Object value) {
    if (MultiValue.isMultiValue(value)) {
      if (!(value instanceof Collection<?>)) {
        return null;
      }
      value = SelectExecutionPlanner.singleElementOrNull(MultiValue.getMultiValueIterable(value));
    }
    return SelectExecutionPlanner.toRecordIdCandidate(value);
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
