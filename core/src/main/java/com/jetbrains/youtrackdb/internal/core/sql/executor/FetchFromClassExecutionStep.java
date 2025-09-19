package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 *
 */
public class FetchFromClassExecutionStep extends AbstractExecutionStep {

  protected String className;
  protected boolean orderByRidAsc = false;
  protected boolean orderByRidDesc = false;
  protected List<ExecutionStep> subSteps = new ArrayList<>();

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
    var collectionIds = new int[filteredClassCollections.size() + 1];
    for (var i = 0; i < filteredClassCollections.size(); i++) {
      collectionIds[i] = filteredClassCollections.getInt(i);
    }
    collectionIds[collectionIds.length - 1] = -1; // temporary collection, data in tx

    sortClusers(collectionIds);
    for (var collectionId : collectionIds) {
      if (collectionId > 0) {
        var step =
            new FetchFromCollectionExecutionStep(collectionId, planningInfo, ctx, profilingEnabled);
        if (orderByRidAsc) {
          step.setOrder(FetchFromCollectionExecutionStep.ORDER_ASC);
        } else if (orderByRidDesc) {
          step.setOrder(FetchFromCollectionExecutionStep.ORDER_DESC);
        }
        subSteps.add(step);
      } else {
        // current tx
        var step =
            new FetchTemporaryFromTxStep(ctx, className, profilingEnabled);
        if (orderByRidAsc) {
          step.setOrder(FetchFromCollectionExecutionStep.ORDER_ASC);
        } else if (orderByRidDesc) {
          step.setOrder(FetchFromCollectionExecutionStep.ORDER_DESC);
        }
        subSteps.add(step);
      }
    }
  }

  protected static SchemaClass loadClassFromSchema(String className, CommandContext ctx) {
    var clazz = ctx.getDatabaseSession().getMetadata().getImmutableSchema(session)
        .getClass(className);
    if (clazz == null) {
      throw new CommandExecutionException(ctx.getDatabaseSession(),
          "Class " + className + " not found");
    }
    return clazz;
  }

  private void sortClusers(int[] collectionIds) {
    if (orderByRidAsc) {
      Arrays.sort(collectionIds);
    } else if (orderByRidDesc) {
      Arrays.sort(collectionIds);
      // revert order
      for (var i = 0; i < collectionIds.length / 2; i++) {
        var old = collectionIds[i];
        collectionIds[i] = collectionIds[collectionIds.length - 1 - i];
        collectionIds[collectionIds.length - 1 - i] = old;
      }
    }
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var stepsIter = subSteps;

    var res =
        new ExecutionStreamProducer() {
          private final Iterator<ExecutionStep> iter = stepsIter.iterator();

          @Override
          public ExecutionStream next(CommandContext ctx) {
            var step = iter.next();
            return ((AbstractExecutionStep) step).start(ctx);
          }

          @Override
          public boolean hasNext(CommandContext ctx) {
            return iter.hasNext();
          }

          @Override
          public void close(CommandContext ctx) {
          }
        };

    return new MultipleExecutionStream(res)
        .map(
            (result, context) -> {
              context.setVariable("$current", result);
              return result;
            });
  }

  @Override
  public void sendTimeout() {
    for (var step : subSteps) {
      ((AbstractExecutionStep) step).sendTimeout();
    }
    if (prev != null) {
      prev.sendTimeout();
    }
  }

  @Override
  public void close() {
    for (var step : subSteps) {
      ((AbstractExecutionStep) step).close();
    }
    if (prev != null) {
      prev.close();
    }
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
    for (var i = 0; i < subSteps.size(); i++) {
      var step = (ExecutionStepInternal) subSteps.get(i);
      builder.append(step.prettyPrint(depth + 1, indent));
      if (i < subSteps.size() - 1) {
        builder.append("\n");
      }
    }
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

  @Nonnull
  @Override
  public List<ExecutionStep> getSubSteps() {
    return subSteps;
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
    result.subSteps =
        this.subSteps.stream()
            .map(x -> ((ExecutionStepInternal) x).copy(ctx))
            .collect(Collectors.toList());
    return result;
  }
}
