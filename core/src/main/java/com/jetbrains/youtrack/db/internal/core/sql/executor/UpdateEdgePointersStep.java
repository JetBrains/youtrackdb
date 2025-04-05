package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexInternal;
import com.jetbrains.youtrack.db.internal.core.sql.executor.resultset.ExecutionStream;
import java.util.Collection;

/**
 * after an update of an edge, this step updates edge pointers on vertices to make the graph
 * consistent again
 */
public class UpdateEdgePointersStep extends AbstractExecutionStep {

  public UpdateEdgePointersStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    var prev = this.prev;
    assert prev != null;
    ExecutionStream upstream = prev.start(ctx);
    return upstream.map(this::mapResult);
  }

  private Result mapResult(Result result, CommandContext ctx) {
    if (result instanceof ResultInternal) {
      handleUpdateEdge(ctx.getDatabase(), result.toEntity().getRecord());
    }
    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    String spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ UPDATE EDGE POINTERS";
  }

  /**
   * handles vertex consistency after an UPDATE EDGE
   *
   * @param record the edge record
   */
  private void handleUpdateEdge(DatabaseSession session, EntityImpl record) {
    Object currentOut = record.field("out");
    Object currentIn = record.field("in");

    Object prevOut = record.getOriginalValue("out");
    Object prevIn = record.getOriginalValue("in");

    if (currentOut instanceof Collection<?> col && col.size() == 1) {
      currentOut = col.iterator().next();
      record.setPropertyInternal("out", currentOut);
    }
    if (currentIn instanceof Collection<?> col && col.size() == 1) {
      currentIn = col.iterator().next();
      record.setPropertyInternal("in", currentIn);
    }

    validateOutInForEdge(session, currentOut, currentIn);

    var prevInIdentifiable = (Identifiable) prevIn;
    var currentInIdentifiable = (Identifiable) currentIn;
    var currentOutIdentifiable = (Identifiable) currentOut;
    var prevOutIdentifiable = (Identifiable) prevOut;

    VertexInternal.changeVertexEdgePointers(
        ctx.getDatabase(),
        record,
        prevInIdentifiable,
        currentInIdentifiable,
        prevOutIdentifiable,
        currentOutIdentifiable);
  }

  private static void validateOutInForEdge(DatabaseSession session, Object currentOut,
      Object currentIn) {
    if (recordIsNotInstanceOfVertex(session, currentOut)) {
      throw new CommandExecutionException(
          "Error updating edge: 'out' is not a vertex - " + currentOut);
    }
    if (recordIsNotInstanceOfVertex(session, currentIn)) {
      throw new CommandExecutionException(
          "Error updating edge: 'in' is not a vertex - " + currentIn);
    }
  }

  /**
   * checks if an object is a vertex
   *
   * @param iRecord The record object
   */
  private static boolean recordIsNotInstanceOfVertex(DatabaseSession session, Object iRecord) {
    if (iRecord == null) {
      return true;
    }
    if (!(iRecord instanceof Identifiable)) {
      return true;
    }
    try {
      EntityImpl record = ((Identifiable) iRecord).getRecord();
      return (!EntityInternalUtils.getImmutableSchemaClass(record)
          .isSubClassOf(session, SchemaClass.VERTEX_CLASS_NAME));
    } catch (RecordNotFoundException rnf) {
      return true;
    }
  }
}
