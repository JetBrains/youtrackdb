package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.MultipleExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import java.util.Collections;
import java.util.Iterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 *
 */
public class FetchEdgesToVerticesStep extends AbstractExecutionStep {

  private final String toAlias;
  private final SQLIdentifier targetCollection;
  private final SQLIdentifier targetClass;

  public FetchEdgesToVerticesStep(
      String toAlias,
      SQLIdentifier targetClass,
      SQLIdentifier targetCollection,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.toAlias = toAlias;
    this.targetClass = targetClass;
    this.targetCollection = targetCollection;
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    if (prev != null) {
      prev.start(ctx).close(ctx);
    }

    var source = init();

    var res =
        new ExecutionStreamProducer() {
          private final Iterator iter = source.iterator();

          @Override
          public ExecutionStream next(CommandContext ctx) {
            return edges(ctx.getDatabaseSession(), iter.next());
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

  private Stream<Object> init() {
    Object toValues;

    toValues = ctx.getVariable(toAlias);
    if (toValues instanceof Iterable && !(toValues instanceof Identifiable)) {
      toValues = ((Iterable<?>) toValues).iterator();
    } else if (!(toValues instanceof Iterator)) {
      toValues = Collections.singleton(toValues).iterator();
    }

    return StreamSupport.stream(
        Spliterators.spliteratorUnknownSize((Iterator<?>) toValues, 0), false);
  }

  private ExecutionStream edges(DatabaseSessionInternal session, Object from) {
    if (from instanceof Result) {
      from = ((Result) from).asEntityOrNull();
    }
    if (from instanceof Identifiable && !(from instanceof Entity)) {
      var transaction = session.getActiveTransaction();
      from = transaction.load(((Identifiable) from));
    }
    if (from instanceof Entity && ((Entity) from).isVertex()) {
      var vertex = ((Entity) from).asVertex();
      var edges = vertex.getEdges(Direction.IN);
      Stream<Result> stream =
          StreamSupport.stream(edges.spliterator(), false)
              .filter((edge) -> matchesClass(session, edge) && matchesCollection(edge))
              .map(e -> new ResultInternal(session, e));
      return ExecutionStream.resultIterator(stream.iterator());
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
    var result = spaces + "+ FOR EACH x in " + toAlias + "\n";
    result += spaces + "       FETCH EDGES TO x";
    if (targetClass != null) {
      result += "\n" + spaces + "       (target class " + targetClass + ")";
    }
    if (targetCollection != null) {
      result += "\n" + spaces + "       (target collection " + targetCollection + ")";
    }
    return result;
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new FetchEdgesToVerticesStep(toAlias, targetClass, targetCollection, ctx,
        profilingEnabled);
  }
}
