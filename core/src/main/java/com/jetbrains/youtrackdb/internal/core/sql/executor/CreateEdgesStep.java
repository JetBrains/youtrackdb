package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.google.common.collect.Streams;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.LocalResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLStatement;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class CreateEdgesStep extends AbstractExecutionStep {

  @Nullable
  private final SQLIdentifier targetClass;
  @Nullable
  private final String uniqueIndexName;

  @Nullable
  private final SQLIdentifier fromAlias;
  @Nullable
  private final SQLIdentifier toAlias;

  @Nullable
  private final SQLStatement fromStatemen;
  @Nullable
  private final SQLStatement toStatement;

  public CreateEdgesStep(
      @Nullable
      SQLIdentifier targetClass,

      @Nullable
      String uniqueIndex,

      @Nullable
      SQLIdentifier fromAlias,
      @Nullable
      SQLIdentifier toAlias,

      @Nullable
      SQLStatement fromStatemen,
      @Nullable
      SQLStatement toStatement,

      @Nonnull
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.targetClass = targetClass;
    this.uniqueIndexName = uniqueIndex;

    this.fromAlias = fromAlias;
    this.toAlias = toAlias;

    this.fromStatemen = fromStatemen;
    this.toStatement = toStatement;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var fromStream = fetchFroms();

    var uniqueIndex = findIndex(this.uniqueIndexName);
    var databaseSession = ctx.getDatabaseSession();

    var stream =
        fromStream
            .map(fromObject -> asVertex(databaseSession, fromObject))
            .flatMap(
                (fromVertex) -> fetchTos().map(
                    obj -> mapTo(ctx.getDatabaseSession(), fromVertex, uniqueIndex, obj)));

    return ExecutionStream.resultIterator(stream.iterator());
  }

  @Nullable
  private Index findIndex(String uniqueIndexName) {
    if (uniqueIndexName != null) {
      final var session = ctx.getDatabaseSession();
      var uniqueIndex =
          session.getSharedContext().getIndexManager().getIndex(uniqueIndexName);
      if (uniqueIndex == null) {
        throw new CommandExecutionException(session,
            "Index not found for upsert: " + uniqueIndexName);
      }
      return uniqueIndex;
    }
    return null;
  }


  private Stream<?> fetchFroms() {
    if (fromAlias != null) {
      var fromValues = ctx.getVariable(fromAlias.getStringValue());
      return convertToStream(fromValues);
    }

    assert fromStatemen != null;

    var execPlan = createExecutionPlan(fromStatemen);
    if (execPlan instanceof InsertExecutionPlan insertPlan) {
      insertPlan.executeInternal();
    }

    var session = ctx.getDatabaseSession();
    return new LocalResultSet(session, execPlan).stream();
  }

  private Stream<?> fetchTos() {
    if (toAlias != null) {
      var fromValues = ctx.getVariable(toAlias.getStringValue());
      return convertToStream(fromValues);
    }

    assert toStatement != null;
    if (!toStatement.isIdempotent()) {
      throw new CommandExecutionException(
          "Only idempotent statements can be used in to part of CREATE EDGE: " +
              toStatement.getOriginalStatement());
    }

    InternalExecutionPlan execPlan;
    execPlan = createExecutionPlan(toStatement);

    var session = ctx.getDatabaseSession();
    return new LocalResultSet(session, execPlan).stream();
  }

  private InternalExecutionPlan createExecutionPlan(SQLStatement statement) {
    InternalExecutionPlan execPlan;
    if (statement.getOriginalStatement() == null || statement.getOriginalStatement()
        .contains("?")) {
      // cannot cache statements with positional params, especially when it's in a
      // subquery/expression.
      execPlan = statement.createExecutionPlanNoCache(ctx, false);
    } else {
      execPlan = statement.createExecutionPlan(ctx, false);
    }

    return execPlan;
  }

  @Nonnull
  private Stream<?> convertToStream(Object fromValues) {
    if (fromValues == null) {
      return Stream.empty();
    }
    var session = ctx.getDatabaseSession();
    switch (fromValues) {
      case InternalResultSet internalResultSet -> {
        return internalResultSet.copy(session).stream();
      }
      case Stream<?> stream -> {
        return stream;
      }
      case Iterable<?> iterable -> {
        return Streams.stream(iterable);
      }
      default -> {
      }
    }
    if (!(fromValues instanceof Iterator)) {
      return Streams.stream(Collections.singleton(fromValues));
    }

    return Stream.empty();
  }

  public Result mapTo(DatabaseSessionEmbedded session, Vertex currentFrom,
      Index uniqueIndex, Object obj) {
    var currentTo = asVertex(session, obj);
    EdgeInternal edgeToUpdate = null;
    if (uniqueIndex != null) {
      var existingEdge =
          getExistingEdge(ctx.getDatabaseSession(), currentFrom, currentTo, uniqueIndex);
      if (existingEdge != null) {
        edgeToUpdate = existingEdge;
      }
    }

    if (edgeToUpdate == null) {
      edgeToUpdate =
          (EdgeInternal) currentFrom.addEdge(currentTo, targetClass.getStringValue());
    }

    if (edgeToUpdate.isStateful()) {
      return new UpdatableResult(session, edgeToUpdate.asStatefulEdge());
    } else {
      return new ResultInternal(session, edgeToUpdate);
    }
  }

  @Nullable
  private static EdgeInternal getExistingEdge(
      DatabaseSessionEmbedded db,
      Vertex currentFrom,
      Vertex currentTo,
      Index uniqueIndex) {
    var key =
        uniqueIndex
            .getDefinition()
            .createValue(db.getActiveTransaction(), currentFrom.getIdentity(),
                currentTo.getIdentity());

    final Iterator<RID> iterator;
    try (var stream = uniqueIndex.getRids(db, key)) {
      iterator = stream.iterator();
      if (iterator.hasNext()) {
        Identifiable identifiable = iterator.next();
        var transaction = db.getActiveTransaction();
        return transaction.load(identifiable);
      }
    }

    return null;
  }

  private static Vertex asVertex(DatabaseSessionEmbedded session, Object currentFrom) {
    if (currentFrom instanceof RID) {
      var transaction = session.getActiveTransaction();
      currentFrom = transaction.load(((RID) currentFrom));
    }
    if (currentFrom instanceof Result) {
      currentFrom =
          ((Result) currentFrom)
              .asVertex();
    }
    if (currentFrom instanceof Vertex) {
      return (Vertex) currentFrom;
    }
    throw new CommandExecutionException(session,
        "Invalid vertex for edge creation: "
            + (currentFrom == null ? "null" : currentFrom.toString()));
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ FOR EACH x in " + fromAlias + "\n";
    result += spaces + "    FOR EACH y in " + toAlias + "\n";
    result += spaces + "       CREATE EDGE " + targetClass + " FROM x TO y";
    if (profilingEnabled) {
      result += " (" + getCostFormatted() + ")";
    }
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new CreateEdgesStep(
        targetClass == null ? null : targetClass.copy(),
        uniqueIndexName,
        fromAlias == null ? null : fromAlias.copy(),
        toAlias == null ? null : toAlias.copy(),
        toStatement == null ? null : toStatement.copy(),
        fromStatemen == null ? null : fromStatemen.copy(),
        ctx,
        profilingEnabled);
  }
}
