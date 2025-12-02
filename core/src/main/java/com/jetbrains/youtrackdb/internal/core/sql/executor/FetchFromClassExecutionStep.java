package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorCollections;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntComparators;
import java.util.Set;

/**
 *
 */
public class FetchFromClassExecutionStep extends AbstractExecutionStep {

  protected String className;
  protected boolean orderByRidAsc = false;
  protected boolean orderByRidDesc = false;
  private int[] collectionIds;

  protected FetchFromClassExecutionStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  public FetchFromClassExecutionStep(
      String className,
      Set<String> collections,
      CommandContext ctx,
      Boolean ridOrder,
      boolean profilingEnabled) {
    this(className, collections, null, ctx, ridOrder, profilingEnabled);
  }

  /**
   * iterates over a class and its subclasses
   *
   * @param className the class name
   * @param collections  if present (it can be null), filter by only these collections
   * @param ctx       the query context
   * @param ridOrder  true to sort by RID asc, false to sort by RID desc, null for no sort.
   */
  public FetchFromClassExecutionStep(
      String className,
      Set<String> collections,
      QueryPlanningInfo planningInfo,
      CommandContext ctx,
      Boolean ridOrder,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);

    this.className = className;

    if (Boolean.TRUE.equals(ridOrder)) {
      orderByRidAsc = true;
    } else if (Boolean.FALSE.equals(ridOrder)) {
      orderByRidDesc = true;
    }

    var clazz = loadClassFromSchema(className, ctx);
    var classCollections = clazz.getPolymorphicCollectionIds();
    var filteredClassCollections = new IntArrayList();

    for (var collectionId : classCollections) {
      var collectionName = ctx.getDatabaseSession().getCollectionNameById(collectionId);
      if (collections == null || collections.contains(collectionName)) {
        filteredClassCollections.add(collectionId);
      }
    }
    if (orderByRidAsc) {
      filteredClassCollections.sort(IntComparators.NATURAL_COMPARATOR);
    } else if (orderByRidDesc) {
      filteredClassCollections.sort(IntComparators.OPPOSITE_COMPARATOR);
    }
    collectionIds = filteredClassCollections.toArray(new int[0]);
  }

  protected static SchemaClass loadClassFromSchema(String className, CommandContext ctx) {
    var clazz = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot()
        .getClass(className);
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class " + className + " not found");
    }
    return clazz;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    if (collectionIds == null || collectionIds.length == 0) {
      return ExecutionStream.empty();
    }

    final var iter = new RecordIteratorCollections<>(
        ctx.getDatabaseSession(),
        collectionIds,
        !orderByRidDesc
    );

    var set = ExecutionStream.loadIterator(iter);

    set = set.interruptable();
    return set;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var builder = new StringBuilder();
    var ind = ExecutionStepInternal.getIndent(depth, indent);
    builder.append(ind);
    builder.append("+ FETCH FROM CLASS ").append(className);
    if (profilingEnabled) {
      builder.append(" (").append(getCostFormatted()).append(")");
    }
    builder.append("\n");
    return builder.toString();
  }

  @Override
  public Result serialize(DatabaseSessionEmbedded session) {
    var result = ExecutionStepInternal.basicSerialize(session, this);
    result.setProperty("className", className);
    result.setProperty("orderByRidAsc", orderByRidAsc);
    result.setProperty("orderByRidDesc", orderByRidDesc);
    return result;
  }

  @Override
  public void deserialize(Result fromResult, DatabaseSessionInternal session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      this.className = fromResult.getProperty("className");
      this.orderByRidAsc = fromResult.getProperty("orderByRidAsc");
      this.orderByRidDesc = fromResult.getProperty("orderByRidDesc");
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    var result = new FetchFromClassExecutionStep(ctx, profilingEnabled);
    result.className = this.className;
    result.orderByRidAsc = this.orderByRidAsc;
    result.orderByRidDesc = this.orderByRidDesc;
    result.collectionIds = this.collectionIds;
    return result;
  }
}
