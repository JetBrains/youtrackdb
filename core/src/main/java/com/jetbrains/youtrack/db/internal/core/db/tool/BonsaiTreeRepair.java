package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.metadata.Metadata;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexEntityImpl;
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

  public static void repairDatabaseRidbags(DatabaseSessionInternal db,
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
          if (edge.getProperty("label") != null) {
            label = edge.getProperty("label");
          } else if (!edge.getSchemaClassName().equals(edgeClass.getName())) {
            label = edge.getSchemaClassName();
          } else {
            counter++;
            continue;
          }

          Identifiable inId = edge.getProperty("in");
          Identifiable outId = edge.getProperty("out");
          if (inId == null || outId == null) {
            db.delete(edge);
            continue;
          }
          final var inVertexName =
              VertexEntityImpl.getEdgeLinkFieldName(Direction.IN, label, true);
          final var outVertexName =
              VertexEntityImpl.getEdgeLinkFieldName(Direction.OUT, label, true);

          var transaction1 = db.getActiveTransaction();
          final EntityImpl inVertex = transaction1.load(inId);
          var transaction = db.getActiveTransaction();
          final EntityImpl outVertex = transaction.load(outId);

          var inVertexes = processedVertexes.computeIfAbsent(inVertexName, k -> new HashSet<>());
          var outVertexes = processedVertexes.computeIfAbsent(outVertexName, k -> new HashSet<>());

          if (inVertex.getProperty(inVertexName) instanceof LinkBag) {
            if (inVertexes.add(inVertex.getIdentity())) {
              inVertex.setProperty(inVertexName, new LinkBag(db));
            }

            final LinkBag inLinkBag = inVertex.getProperty(inVertexName);
            inLinkBag.add(edge.getIdentity());

          }

          if (outVertex.getProperty(outVertexName) instanceof LinkBag) {
            if (outVertexes.add(outVertex.getIdentity())) {
              outVertex.setProperty(outVertexName, new LinkBag(db));
            }

            final LinkBag outLinkBag = outVertex.getProperty(outVertexName);
            outLinkBag.add(edge.getIdentity());

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

  private static void message(CommandOutputListener outputListener, String message) {
    if (outputListener != null) {
      outputListener.onMessage(message);
    }
  }
}
