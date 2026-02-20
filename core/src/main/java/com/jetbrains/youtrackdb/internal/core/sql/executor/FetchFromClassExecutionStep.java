package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
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
 * Source step that performs a full scan over all records belonging to a given class
 * (including its subclasses, since YouTrackDB uses polymorphic collections).
 *
 * <pre>
 *  SQL:   SELECT FROM Person
 *
 *  Pipeline position: always first (source step -- no upstream predecessor)
 *
 *  +---------------------+      +-----------+      +--------+
 *  | FetchFromClass      | --&gt;  | FilterBy  | --&gt;  |  ...   |
 *  | (scan Person colls) |      | Class     |      |        |
 *  +---------------------+      +-----------+      +--------+
 * </pre>
 *
 * <p>The step resolves the class's polymorphic collection IDs at construction time,
 * optionally filters by a specific set of collection names, and sorts the collection
 * IDs when RID ordering is requested (ASC or DESC).
 *
 * <p>At execution time, a {@link RecordIteratorCollections} iterates over all records
 * in the selected collections. The iterator is interruptible (checks for query
 * cancellation between pages).
 *
 * <p>When the query contains {@code ORDER BY @rid ASC} or {@code ORDER BY @rid DESC},
 * the planner pushes the sort direction down to this step, avoiding an in-memory sort.
 *
 * @see SelectExecutionPlanner#handleClassAsTarget
 */
public class FetchFromClassExecutionStep extends AbstractExecutionStep {

  /** The target class name (e.g. "Person"). */
  protected String className;

  /** If true, collections are iterated in ascending RID order. */
  protected boolean orderByRidAsc = false;

  /** If true, collections are iterated in descending RID order. */
  protected boolean orderByRidDesc = false;

  /** Pre-computed collection IDs to scan (includes subclass collections). */
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
   * Creates a step that iterates over all records of a class and its subclasses.
   *
   * <p>The class's polymorphic collection IDs are resolved at construction time.
   * When {@code collections} is non-null, only collections whose names appear in
   * the set are included. When {@code ridOrder} is specified, collections are sorted
   * by ID to produce records in RID order.
   *
   * @param className    the class name (e.g. "Person")
   * @param collections  if non-null, only scan collections with these names
   * @param planningInfo planning metadata (may carry RID range conditions); can be null
   * @param ctx          the query context
   * @param ridOrder     {@code true} for RID ascending, {@code false} for descending,
   *                     {@code null} for no ordering
   * @param profilingEnabled true to enable profiling instrumentation
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

  /**
   * Loads a class from the session's immutable schema snapshot.
   *
   * @param className the class name to look up
   * @param ctx       the command context providing the database session
   * @return the schema class; never null
   * @throws CommandExecutionException if the class does not exist in the schema
   */
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
    // Drain predecessor (e.g. global LET bindings) for side effects before scanning.
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
  public void deserialize(Result fromResult, DatabaseSessionEmbedded session) {
    try {
      ExecutionStepInternal.basicDeserialize(fromResult, this, session);
      this.className = fromResult.getProperty("className");
      this.orderByRidAsc = fromResult.getProperty("orderByRidAsc");
      this.orderByRidDesc = fromResult.getProperty("orderByRidDesc");
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(session, ""), e, session);
    }
  }

  /** Cacheable: collection IDs are resolved from the immutable schema snapshot at construction. */
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
