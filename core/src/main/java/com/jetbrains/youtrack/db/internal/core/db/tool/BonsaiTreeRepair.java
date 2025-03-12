package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.metadata.Metadata;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexInternal;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * Find and repair broken bonsai tree removing the double linked buckets and regenerating the whole
 * tree with data from referring records.
 */
public class BonsaiTreeRepair {

  public void repairDatabaseRidbags(DatabaseSessionInternal db,
      CommandOutputListener outputListener) {
    message(outputListener, "Repair of ridbags is started ...\n");

    final Metadata metadata = db.getMetadata();
    final Schema schema = metadata.getSchema();
    final var edgeClass = schema.getClass("E");
    if (edgeClass != null) {
      final var processedVertexes = new HashMap<String, Set<RID>>();
      final var countEdges = db.countClass(edgeClass.getName());

      message(outputListener, countEdges + " will be processed.");
      long counter = 0;

      var iterator = db.browseClass(edgeClass.getName());
      while (iterator.hasNext() && !Thread.currentThread().isInterrupted()) {
        final var edge = iterator.next();
        try {
          final String label;
          if (edge.field("label") != null) {
            label = edge.field("label");
          } else if (!edge.getSchemaClassName().equals(edgeClass.getName())) {
            label = edge.getSchemaClassName();
          } else {
            counter++;
            continue;
          }

          Identifiable inId = edge.field("in");
          Identifiable outId = edge.field("out");
          if (inId == null || outId == null) {
            db.delete(edge);
            continue;
          }
          final var inVertexName =
              VertexInternal.getEdgeLinkFieldName(Direction.IN, label, true);
          final var outVertexName =
              VertexInternal.getEdgeLinkFieldName(Direction.OUT, label, true);

          final EntityImpl inVertex = inId.getRecord(db);
          final EntityImpl outVertex = outId.getRecord(db);

          var inVertexes = processedVertexes.get(inVertexName);
          if (inVertexes == null) {
            inVertexes = new HashSet<>();
            processedVertexes.put(inVertexName, inVertexes);
          }
          var outVertexes = processedVertexes.get(outVertexName);
          if (outVertexes == null) {
            outVertexes = new HashSet<>();
            processedVertexes.put(outVertexName, outVertexes);
          }

          if (inVertex.field(inVertexName) instanceof RidBag) {
            if (inVertexes.add(inVertex.getIdentity())) {
              inVertex.field(inVertexName, new RidBag(db));
            }

            final RidBag inRidBag = inVertex.field(inVertexName);
            inRidBag.add(edge.getIdentity());

          }

          if (outVertex.field(outVertexName) instanceof RidBag) {
            if (outVertexes.add(outVertex.getIdentity())) {
              outVertex.field(outVertexName, new RidBag(db));
            }

            final RidBag outRidBag = outVertex.field(outVertexName);
            outRidBag.add(edge.getIdentity());

          }

          counter++;

          if (counter > 0 && counter % 1000 == 0) {
            message(
                outputListener, counter + " edges were processed out of " + countEdges + " \n.");
          }

        } catch (Exception e) {
          final var sw = new StringWriter();

          sw.append("Error during processing of edge with id ")
              .append(edge.getIdentity().toString())
              .append("\n");
          e.printStackTrace(new PrintWriter(sw));

          message(outputListener, sw.toString());
        }
      }

      message(outputListener, "Processed " + counter + " from " + countEdges + ".");
    }

    message(outputListener, "repair of ridbags is completed\n");
  }

  private void message(CommandOutputListener outputListener, String message) {
    if (outputListener != null) {
      outputListener.onMessage(message);
    }
  }
}
