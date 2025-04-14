package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.ExecutionStep;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLBatch;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
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

    var fromIter = fetchFroms();
    var toList = fetchTo();
    var uniqueIndex = findIndex(this.uniqueIndexName);
    var db = ctx.getDatabaseSession();
    var stream =
        StreamSupport.stream(Spliterators.spliteratorUnknownSize(fromIter, 0), false)
            .map(currentFrom1 -> asVertex(db, currentFrom1))
            .flatMap(
                (currentFrom) -> mapTo(ctx.getDatabaseSession(), toList, currentFrom, uniqueIndex));
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

  private List<Object> fetchTo() {
    var toValues = ctx.getVariable(toAlias.getStringValue());
    if (toValues instanceof Iterable && !(toValues instanceof Identifiable)) {
      toValues = ((Iterable<?>) toValues).iterator();
    } else if (!(toValues instanceof Iterator)) {
      toValues = Collections.singleton(toValues).iterator();
    }
    if (toValues instanceof InternalResultSet) {
      toValues = ((InternalResultSet) toValues).copy();
    }

    var toIter = (Iterator<?>) toValues;

    if (toIter instanceof InternalResultSet resultSet) {
      try {
        resultSet.reset();
      } catch (Exception ignore) {
      }
    }
    List<Object> toList = new ArrayList<>();
    while (toIter != null && toIter.hasNext()) {
      toList.add(toIter.next());
    }
    return toList;
  }

  private Iterator<?> fetchFroms() {
    var fromValues = ctx.getVariable(fromAlias.getStringValue());
    if (fromValues instanceof Iterable && !(fromValues instanceof Identifiable)) {
      fromValues = ((Iterable<?>) fromValues).iterator();
    } else if (!(fromValues instanceof Iterator)) {
      fromValues = Collections.singleton(fromValues).iterator();
    }
    if (fromValues instanceof InternalResultSet) {
      fromValues = ((InternalResultSet) fromValues).copy();
    }
    var fromIter = (Iterator<?>) fromValues;
    if (fromIter instanceof InternalResultSet resultSet) {
      try {
        resultSet.reset();
      } catch (Exception ignore) {
      }
    }
    return fromIter;
  }

  public Stream<Result> mapTo(DatabaseSessionInternal session, List<Object> to, Vertex currentFrom,
      Index uniqueIndex) {
    return to.stream()
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
      DatabaseSessionInternal db,
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
}
