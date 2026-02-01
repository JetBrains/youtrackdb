package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Edge;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;

/**
 *
 */
public class FetchEdgesFromToVerticesStep extends AbstractExecutionStep {

  private final SQLIdentifier targetClass;
  private final SQLIdentifier targetCollection;
  private final String fromAlias;
  private final String toAlias;

  public FetchEdgesFromToVerticesStep(
      String fromAlias,
      String toAlias,
      SQLIdentifier targetClass,
      SQLIdentifier targetCollection,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.targetClass = targetClass;
    this.targetCollection = targetCollection;
    this.fromAlias = fromAlias;
    this.toAlias = toAlias;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    final Iterator fromIter = loadFrom();
    var db = ctx.getDatabaseSession();
    final var toList = loadTo(db);

    var res =
        new ExecutionStreamProducer() {
          private final Iterator iter = fromIter;
          private final Set<RID> to = toList;

          @Override
          public ExecutionStream next(CommandContext ctx) {
            return createResultSet(db, to, iter.next());
          }

          @Override
          public boolean hasNext(CommandContext ctx) {
            return iter.hasNext();
          }

          @Override
          public void close(CommandContext ctx) {
          }
        };

    return new MultipleExecutionStream(res);
  }

  private ExecutionStream createResultSet(DatabaseSessionInternal db, Set<RID> toList,
      Object val) {
    return ExecutionStream.resultIterator(
        StreamSupport.stream(FetchEdgesFromToVerticesStep.loadNextResults(db, val).spliterator(),
                false)
            .filter((e) -> filterResult(db, e, toList))
            .map(
                (edge) -> (Result) new ResultInternal(db, edge))
            .iterator());
  }

  @Nullable
  private Set<RID> loadTo(DatabaseSessionInternal session) {
    Object toValues = null;

    toValues = ctx.getVariable(toAlias);
    if (toValues instanceof Iterable && !(toValues instanceof Identifiable)) {
      toValues = ((Iterable<?>) toValues).iterator();
    } else if (!(toValues instanceof Iterator) && toValues != null) {
      toValues = Collections.singleton(toValues).iterator();
    }

    var toIter = (Iterator<?>) toValues;
    if (toIter != null) {
      final Set<RID> toList = new HashSet<>();
      while (toIter.hasNext()) {
        var elem = toIter.next();
        if (elem instanceof Result result && result.isEntity()) {
          elem = result.asEntity();
        }
        if (elem instanceof Identifiable && !(elem instanceof Entity)) {
          var transaction = session.getActiveTransaction();
          elem = transaction.load(((Identifiable) elem));
        }
        if (!(elem instanceof Entity)) {
          throw new CommandExecutionException(session, "Invalid vertex: " + elem);
        }
        var vertex = ((Entity) elem).asVertexOrNull();
        if (vertex != null) {
          toList.add(vertex.getIdentity());
        }
      }

      return toList;
    }
    return null;
  }

  private Iterator<?> loadFrom() {
    Object fromValues = null;

    fromValues = ctx.getVariable(fromAlias);
    if (fromValues instanceof Iterable && !(fromValues instanceof Identifiable)) {
      fromValues = ((Iterable<?>) fromValues).iterator();
    } else if (!(fromValues instanceof Iterator)) {
      fromValues = Collections.singleton(fromValues).iterator();
    }
    return (Iterator<?>) fromValues;
  }

  private boolean filterResult(DatabaseSessionInternal db, Edge edge, Set<RID> toList) {
    if (toList == null || toList.contains(edge.getTo().getIdentity())) {
      return matchesClass(db, edge) && matchesCollection(edge);
    }
    return true;
  }

  private static Iterable<Edge> loadNextResults(DatabaseSessionInternal session, Object from) {
    if (from instanceof Result result && result.isEntity()) {
      from = result.asEntityOrNull();
    }
    if (from instanceof Identifiable && !(from instanceof Entity)) {
      var transaction = session.getActiveTransaction();
      from = transaction.load(((Identifiable) from));
    }
    if (from instanceof Entity && ((Entity) from).isVertex()) {
      var vertex = ((Entity) from).asVertex();
      return vertex.getEdges(Direction.OUT);
    } else {
      throw new CommandExecutionException(session, "Invalid vertex: " + from);
    }
  }

  private boolean matchesCollection(Edge edge) {
    if (targetCollection == null) {
      return true;
    }
    if (edge.isStateful()) {
      var statefulEdge = edge.asStatefulEdge();
      var collectionId = statefulEdge.getIdentity().getCollectionId();
      var collectionName = ctx.getDatabaseSession().getCollectionNameById(collectionId);
      return collectionName.equals(targetCollection.getStringValue());
    }

    return false;
  }

  private boolean matchesClass(DatabaseSessionInternal db, Edge edge) {
    if (targetClass == null) {
      return true;
    }
    var schemaClass = edge.getSchemaClass();
    assert schemaClass != null;
    return schemaClass.isSubClassOf(targetClass.getStringValue());
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = spaces + "+ FOR EACH x in " + fromAlias + "\n";
    result += spaces + "    FOR EACH y in " + toAlias + "\n";
    result += spaces + "       FETCH EDGES FROM x TO y";
    if (targetClass != null) {
      result += "\n" + spaces + "       (target class " + targetClass + ")";
    }
    if (targetCollection != null) {
      result += "\n" + spaces + "       (target collection " + targetCollection + ")";
    }
    return result;
  }

  @Override
  public boolean canBeCached() {
    return true;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchEdgesFromToVerticesStep(
        fromAlias, toAlias, targetClass, targetCollection, ctx, profilingEnabled);
  }
}
