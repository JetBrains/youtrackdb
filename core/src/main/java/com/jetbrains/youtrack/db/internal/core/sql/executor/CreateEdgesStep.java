package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.google.common.collect.Streams;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBatch;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import java.util.Collections;
import java.util.Iterator;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 *
 */
public class CreateEdgesStep extends AbstractExecutionStep {

  private final SQLIdentifier targetClass;
  private final String uniqueIndexName;
  private final SQLIdentifier fromAlias;
  private final SQLIdentifier toAlias;
  private final Number wait;
  private final Number retry;
  private final SQLBatch batch;

  private Stream<?> fromStream;
  private Stream<?> toStream;


  public CreateEdgesStep(
      SQLIdentifier targetClass,
      String uniqueIndex,
      SQLIdentifier fromAlias,
      SQLIdentifier toAlias,
      Number wait,
      Number retry,
      SQLBatch batch,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.targetClass = targetClass;
    this.uniqueIndexName = uniqueIndex;
    this.fromAlias = fromAlias;
    this.toAlias = toAlias;
    this.wait = wait;
    this.retry = retry;
    this.batch = batch;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    fromStream = fetchFroms();
    toStream = fetchTo();

    var uniqueIndex = findIndex(this.uniqueIndexName);
    var db = ctx.getDatabaseSession();
    var stream =
        fromStream
            .map(fromObject -> asVertex(db, fromObject))
            .flatMap(
                (fromVertex) -> mapTo(ctx.getDatabaseSession(), toStream, fromVertex, uniqueIndex));
    return ExecutionStream.resultIterator(stream.iterator());
  }

  @Nullable
  private Index findIndex(String uniqueIndexName) {
    if (uniqueIndexName != null) {
      final var session = ctx.getDatabaseSession();
      var uniqueIndex =
          session.getSharedContext().getIndexManager().getIndex(session, uniqueIndexName);
      if (uniqueIndex == null) {
        throw new CommandExecutionException(session,
            "Index not found for upsert: " + uniqueIndexName);
      }
      return uniqueIndex;
    }
    return null;
  }

  private Stream<?> fetchTo() {
    var toValues = ctx.getVariable(toAlias.getStringValue());
    return convertToStream(toValues);
  }

  private Stream<?> fetchFroms() {
    var fromValues = ctx.getVariable(fromAlias.getStringValue());
    return convertToStream(fromValues);
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

  public Stream<Result> mapTo(DatabaseSessionInternal session, Stream<?> to, Vertex currentFrom,
      Index uniqueIndex) {
    return to
        .map(
            (obj) -> {
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
            });
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

  private static Vertex asVertex(DatabaseSessionInternal session, Object currentFrom) {
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
        wait,
        retry,
        batch == null ? null : batch.copy(),
        ctx,
        profilingEnabled);
  }

  @Override
  public void close() {
    super.close();

    if (fromStream != null) {
      fromStream.close();
    }
    if (toStream != null) {
      toStream.close();
    }
  }
}
