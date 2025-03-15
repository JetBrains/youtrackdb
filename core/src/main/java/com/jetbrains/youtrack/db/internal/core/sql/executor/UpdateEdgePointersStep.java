package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.concur.TimeoutException;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
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
    var upstream = prev.start(ctx);
    return upstream.map(UpdateEdgePointersStep::mapResult);
  }

  private static Result mapResult(Result result, CommandContext ctx) {
    if (result instanceof ResultInternal resultInternal && resultInternal.isEntity()) {
      var db = ctx.getDatabaseSession();
      handleUpdateEdge(result.asEntity().getRecord(db), db);
    }

    return result;
  }

  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    return spaces + "+ UPDATE EDGE POINTERS";
  }

  /**
   * handles vertex consistency after an UPDATE EDGE
   *
   * @param record the edge record
   * @param db
   */
  private static void handleUpdateEdge(EntityImpl record, DatabaseSessionInternal db) {
    var currentOut = record.getProperty("out");
    var currentIn = record.getProperty("in");

    var prevOut = record.getOriginalValue("out");
    var prevIn = record.getOriginalValue("in");

    if (currentOut instanceof Collection<?> col && col.size() == 1) {
      currentOut = col.iterator().next();
      record.setPropertyInternal("out", currentOut);
    }
    if (currentIn instanceof Collection<?> col && col.size() == 1) {
      currentIn = col.iterator().next();
      record.setPropertyInternal("in", currentIn);
    }

    validateOutInForEdge(db, currentOut, currentIn);

    var prevInIdentifiable = (Identifiable) prevIn;
    var currentInIdentifiable = (Identifiable) currentIn;
    var currentOutIdentifiable = (Identifiable) currentOut;
    var prevOutIdentifiable = (Identifiable) prevOut;

    VertexInternal.changeVertexEdgePointers(db,
        record,
        prevInIdentifiable,
        currentInIdentifiable,
        prevOutIdentifiable, currentOutIdentifiable);
  }

  private static void validateOutInForEdge(DatabaseSessionInternal session,
      Object currentOut, Object currentIn) {
    if (recordIsNotInstanceOfVertex(session, currentOut)) {
      throw new CommandExecutionException(session,
          "Error updating edge: 'out' is not a vertex - " + currentOut);
    }
    if (recordIsNotInstanceOfVertex(session, currentIn)) {
      throw new CommandExecutionException(session,
          "Error updating edge: 'in' is not a vertex - " + currentIn);
    }
  }

  /**
   * checks if an object is a vertex
   *
   * @param session
   * @param iRecord The record object
   */
  private static boolean recordIsNotInstanceOfVertex(DatabaseSessionInternal session,
      Object iRecord) {
    if (iRecord == null) {
      return true;
    }
    if (!(iRecord instanceof Identifiable)) {
      return true;
    }
    try {
      EntityImpl record = ((Identifiable) iRecord).getRecord(session);
      SchemaImmutableClass result;
      result = record.getImmutableSchemaClass(session);
      return (!result
          .isSubClassOf(SchemaClass.VERTEX_CLASS_NAME));
    } catch (RecordNotFoundException rnf) {
      return true;
    }
  }
}
