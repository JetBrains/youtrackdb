package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStreamProducer;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.MultipleExecutionStream;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLIdentifier;
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
  private final SQLIdentifier targetCluster;
  private final SQLIdentifier targetClass;

  public FetchEdgesToVerticesStep(
      String toAlias,
      SQLIdentifier targetClass,
      SQLIdentifier targetCluster,
      CommandContext ctx,
      boolean profilingEnabled) {
    super(ctx, profilingEnabled);
    this.toAlias = toAlias;
    this.targetClass = targetClass;
    this.targetCluster = targetCluster;
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
      assert vertex != null;
      var edges = vertex.getEdges(Direction.IN);
      Stream<Result> stream =
          StreamSupport.stream(edges.spliterator(), false)
              .filter((edge) -> matchesClass(session, edge) && matchesCluster(edge))
              .map(e -> new ResultInternal(session, e));
      return ExecutionStream.resultIterator(stream.iterator());
    } else {
      throw new CommandExecutionException(session, "Invalid vertex: " + from);
    }
  }

  private boolean matchesCluster(Edge edge) {
    if (targetCluster == null) {
      return true;
    }
    if (edge.isStateful()) {
      var statefulEdge = edge.asStatefulEdge();

      var clusterId = statefulEdge.getIdentity().getClusterId();
      var clusterName = ctx.getDatabaseSession().getClusterNameById(clusterId);
      return clusterName.equals(targetCluster.getStringValue());
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
    if (targetCluster != null) {
      result += "\n" + spaces + "       (target cluster " + targetCluster + ")";
    }
    return result;
  }
}
