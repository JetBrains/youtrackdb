package com.jetbrains.youtrack.db.internal.core.db.tool;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandOutputListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.metadata.Metadata;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.StorageRecoverEventListener;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Repairs a graph. Current implementation scan the entire graph. In the future the WAL will be used
 * to make this repair task much faster.
 */
public class GraphRepair {

  public static class RepairStats {

    private long scannedEdges = 0;
    private long removedEdges = 0;
    private long scannedVertices = 0;
    private long scannedLinks = 0;
    private long removedLinks = 0;
    private long repairedVertices = 0;
  }

  private StorageRecoverEventListener eventListener;

  public void repair(
      final DatabaseSession graph,
      final CommandOutputListener outputListener,
      final Map<String, List<String>> options) {
    message(outputListener, "Repair of graph '" + graph.getURL() + "' is started ...\n");

    final var beginTime = System.currentTimeMillis();

    final var stats = new RepairStats();

    // SCAN AND CLEAN ALL THE EDGES FIRST (IF ANY)
    repairEdges(graph, stats, outputListener, options, false);

    // SCAN ALL THE VERTICES
    repairVertices(graph, stats, outputListener, options, false);

    message(
        outputListener,
        "Repair of graph '"
            + graph.getURL()
            + "' completed in "
            + ((System.currentTimeMillis() - beginTime) / 1000)
            + " secs\n");

    message(outputListener, " scannedEdges.....: " + stats.scannedEdges + "\n");
    message(outputListener, " removedEdges.....: " + stats.removedEdges + "\n");
    message(outputListener, " scannedVertices..: " + stats.scannedVertices + "\n");
    message(outputListener, " scannedLinks.....: " + stats.scannedLinks + "\n");
    message(outputListener, " removedLinks.....: " + stats.removedLinks + "\n");
    message(outputListener, " repairedVertices.: " + stats.repairedVertices + "\n");
  }

  public void check(
      final DatabaseSession graph,
      final CommandOutputListener outputListener,
      final Map<String, List<String>> options) {
    message(outputListener, "Check of graph '" + graph.getURL() + "' is started...\n");

    final var beginTime = System.currentTimeMillis();

    final var stats = new RepairStats();

    // SCAN AND CLEAN ALL THE EDGES FIRST (IF ANY)
    repairEdges(graph, stats, outputListener, options, true);

    // SCAN ALL THE VERTICES
    repairVertices(graph, stats, outputListener, options, true);

    message(
        outputListener,
        "Check of graph '"
            + graph.getURL()
            + "' completed in "
            + ((System.currentTimeMillis() - beginTime) / 1000)
            + " secs\n");

    message(outputListener, " scannedEdges.....: " + stats.scannedEdges + "\n");
    message(outputListener, " edgesToRemove....: " + stats.removedEdges + "\n");
    message(outputListener, " scannedVertices..: " + stats.scannedVertices + "\n");
    message(outputListener, " scannedLinks.....: " + stats.scannedLinks + "\n");
    message(outputListener, " linksToRemove....: " + stats.removedLinks + "\n");
    message(outputListener, " verticesToRepair.: " + stats.repairedVertices + "\n");
  }

  protected void repairEdges(
      final DatabaseSession graph,
      final RepairStats stats,
      final CommandOutputListener outputListener,
      final Map<String, List<String>> options,
      final boolean checkOnly) {
    final var session = (DatabaseSessionInternal) graph;
    session.executeInTx(
        transaction -> {
          final Metadata metadata = session.getMetadata();
          final Schema schema = metadata.getSchema();

          final var useVertexFieldsForEdgeLabels = true; // db.isUseVertexFieldsForEdgeLabels();

          final var edgeClass = schema.getClass(SchemaClass.EDGE_CLASS_NAME);
          if (edgeClass != null) {
            final var countEdges = session.countClass(edgeClass.getName());

            var skipEdges = 0L;
            if (options != null && options.get("-skipEdges") != null) {
              skipEdges = Long.parseLong(options.get("-skipEdges").getFirst());
            }

            message(
                outputListener,
                "Scanning " + countEdges + " edges (skipEdges=" + skipEdges + ")...\n");

            var parsedEdges = 0L;
            final var beginTime = System.currentTimeMillis();

            var edgeIterator = session.browseClass(edgeClass.getName());
            while (edgeIterator.hasNext() && !Thread.currentThread().isInterrupted()) {
              final var edge = edgeIterator.next();
              if (!edge.isStatefulEdge()) {
                continue;
              }
              final RID edgeId = edge.getIdentity();

              parsedEdges++;
              if (skipEdges > 0 && parsedEdges <= skipEdges) {
                continue;
              }

              stats.scannedEdges++;

              if (eventListener != null) {
                eventListener.onScannedEdge(edge);
              }

              if (outputListener != null && stats.scannedEdges % 100000 == 0) {
                var speedPerSecond =
                    (long) (parsedEdges / ((System.currentTimeMillis() - beginTime) / 1000.0));
                if (speedPerSecond < 1) {
                  speedPerSecond = 1;
                }
                final var remaining = (countEdges - parsedEdges) / speedPerSecond;

                message(
                    outputListener,
                    "+ edges: scanned "
                        + stats.scannedEdges
                        + ", removed "
                        + stats.removedEdges
                        + " (estimated remaining time "
                        + remaining
                        + " secs)\n");
              }

              var outVertexMissing = false;

              var removalReason = "";

              final Identifiable out = edge.asStatefulEdgeOrNull().getFrom();
              if (out == null) {
                outVertexMissing = true;
              } else {
                EntityImpl outVertex;
                try {
                  var transaction1 = session.getActiveTransaction();
                  outVertex = transaction1.load(out);
                } catch (RecordNotFoundException e) {
                  outVertex = null;
                }

                if (outVertex == null) {
                  outVertexMissing = true;
                } else {
                  final var outFieldName =
                      VertexEntityImpl.getEdgeLinkFieldName(
                          Direction.OUT, edge.getSchemaClassName(), useVertexFieldsForEdgeLabels);

                  final var outEdges = outVertex.getPropertyInternal(outFieldName);
                  switch (outEdges) {
                    case null -> outVertexMissing = true;
                    case LinkBag rids -> {
                      if (!rids.contains(edgeId)) {
                        outVertexMissing = true;
                      }
                    }
                    case Collection collection -> {
                      if (!collection.contains(edgeId)) {
                        outVertexMissing = true;
                      }
                    }
                    case Identifiable identifiable -> {
                      if (identifiable.getIdentity().equals(edgeId)) {
                        outVertexMissing = true;
                      }
                    }
                    default -> {
                    }
                  }
                }
              }

              if (outVertexMissing) {
                removalReason = "outgoing vertex (" + out + ") does not contain the edge";
              }

              var inVertexMissing = false;

              final Identifiable in = edge.asStatefulEdgeOrNull().getTo();
              if (in == null) {
                inVertexMissing = true;
              } else {

                EntityImpl inVertex;
                try {
                  var transaction1 = session.getActiveTransaction();
                  inVertex = transaction1.load(in);
                } catch (RecordNotFoundException e) {
                  inVertex = null;
                }

                if (inVertex == null) {
                  inVertexMissing = true;
                } else {
                  final var inFieldName =
                      VertexEntityImpl.getEdgeLinkFieldName(
                          Direction.IN, edge.getSchemaClassName(), useVertexFieldsForEdgeLabels);

                  final var inEdges = inVertex.getPropertyInternal(inFieldName);
                  switch (inEdges) {
                    case null -> inVertexMissing = true;
                    case LinkBag rids -> {
                      if (!rids.contains(edgeId)) {
                        inVertexMissing = true;
                      }
                    }
                    case Collection collection -> {
                      if (!collection.contains(edgeId)) {
                        inVertexMissing = true;
                      }
                    }
                    case Identifiable identifiable -> {
                      if (identifiable.getIdentity().equals(edgeId)) {
                        inVertexMissing = true;
                      }
                    }
                    default -> {
                    }
                  }
                }
              }

              if (inVertexMissing) {
                if (!removalReason.isEmpty()) {
                  removalReason += ", ";
                }
                removalReason += "incoming vertex (" + in + ") does not contain the edge";
              }

              if (outVertexMissing || inVertexMissing) {
                try {
                  if (!checkOnly) {
                    message(
                        outputListener,
                        "+ deleting corrupted edge " + edge + " because " + removalReason + "\n");
                    edge.delete();
                  } else {
                    message(
                        outputListener,
                        "+ found corrupted edge " + edge + " because " + removalReason + "\n");
                  }

                  stats.removedEdges++;
                  if (eventListener != null) {
                    eventListener.onRemovedEdge(edge);
                  }

                } catch (Exception e) {
                  message(
                      outputListener,
                      "Error on deleting edge " + edge.getIdentity() + " (" + e.getMessage() + ")");
                }
              }
            }

            message(outputListener, "Scanning edges completed\n");
          }
        });
  }

  protected void repairVertices(
      final DatabaseSession session,
      final RepairStats stats,
      final CommandOutputListener outputListener,
      final Map<String, List<String>> options,
      final boolean checkOnly) {
    final var db = (DatabaseSessionInternal) session;
    final Metadata metadata = db.getMetadata();
    final Schema schema = metadata.getSchema();

    final var vertexClass = schema.getClass(SchemaClass.VERTEX_CLASS_NAME);
    if (vertexClass != null) {
      final var countVertices = db.countClass(vertexClass.getName());
      session.executeInTx(
          transaction -> {
            var skipVertices = 0L;
            if (options != null && options.get("-skipVertices") != null) {
              skipVertices = Long.parseLong(options.get("-skipVertices").getFirst());
            }

            message(outputListener, "Scanning " + countVertices + " vertices...\n");

            var parsedVertices = new long[]{0L};
            final var beginTime = System.currentTimeMillis();

            var vertexIterator = db.browseClass(vertexClass.getName());
            while (vertexIterator.hasNext() && !Thread.currentThread().isInterrupted()) {
              var vertex = vertexIterator.next();
              parsedVertices[0]++;
              if (skipVertices > 0 && parsedVertices[0] <= skipVertices) {
                continue;
              }

              var vertexCorrupted = false;
              stats.scannedVertices++;
              if (eventListener != null) {
                eventListener.onScannedVertex(vertex);
              }

              if (outputListener != null && stats.scannedVertices % 100000 == 0) {
                var speedPerSecond =
                    (long)
                        (parsedVertices[0] / ((System.currentTimeMillis() - beginTime) / 1000.0));
                if (speedPerSecond < 1) {
                  speedPerSecond = 1;
                }
                final var remaining = (countVertices - parsedVertices[0]) / speedPerSecond;

                message(
                    outputListener,
                    "+ vertices: scanned "
                        + stats.scannedVertices
                        + ", repaired "
                        + stats.repairedVertices
                        + " (estimated remaining time "
                        + remaining
                        + " secs)\n");
              }

              if (!vertex.isVertex()) {
                return;
              }

              for (var fieldName : vertex.getPropertyNamesInternal(false, false)) {
                final var connection =
                    VertexEntityImpl.getConnection(
                        db.getMetadata().getSchema(), Direction.BOTH, fieldName);
                if (connection == null) {
                  continue;
                }

                final var fieldValue = vertex.getPropertyInternal(fieldName);
                if (fieldValue != null) {
                  switch (fieldValue) {
                    case Identifiable identifiable -> {
                      if (isEdgeBroken(db,
                          vertex,
                          fieldName,
                          connection.getKey(),
                          identifiable,
                          stats)) {
                        vertexCorrupted = true;
                        if (!checkOnly) {
                          vertex.setProperty(fieldName, null);
                        } else {
                          message(
                              outputListener,
                              "+ found corrupted vertex "
                                  + vertex
                                  + " the property "
                                  + fieldName
                                  + " could be removed\n");
                        }
                      }
                    }
                    case Collection<?> coll -> {

                      for (var it = coll.iterator(); it.hasNext(); ) {
                        final var o = it.next();

                        if (isEdgeBroken(db,
                            vertex, fieldName, connection.getKey(), (Identifiable) o,
                            stats)) {
                          vertexCorrupted = true;
                          if (!checkOnly) {
                            it.remove();
                          } else {
                            message(
                                outputListener,
                                "+ found corrupted vertex "
                                    + vertex
                                    + " the edge should be removed from property "
                                    + fieldName
                                    + " (collection)\n");
                          }
                        }
                      }
                    }
                    case LinkBag ridbag -> {
                      // In case of ridbags force save for trigger eventual conversions
                      if (ridbag.isEmpty()) {
                        vertex.removePropertyInternal(fieldName);
                      } else if (!ridbag.isEmbedded()
                          && ridbag.size()
                          < GlobalConfiguration.LINK_COLLECTION_BTREE_TO_EMBEDDED_THRESHOLD
                          .getValueAsInteger()) {
                        vertex.setDirty();
                      }
                      for (Iterator<?> it = ridbag.iterator(); it.hasNext(); ) {
                        final var o = it.next();
                        if (isEdgeBroken(db,
                            vertex, fieldName, connection.getKey(), (Identifiable) o,
                            stats)) {
                          vertexCorrupted = true;
                          if (!checkOnly) {
                            it.remove();
                          } else {
                            message(
                                outputListener,
                                "+ found corrupted vertex "
                                    + vertex
                                    + " the edge should be removed from property "
                                    + fieldName
                                    + " (ridbag)\n");
                          }
                        }
                      }
                    }
                    default -> {
                    }
                  }
                }
              }

              if (vertexCorrupted) {
                stats.repairedVertices++;
                if (eventListener != null) {
                  eventListener.onRepairedVertex(vertex);
                }

                message(outputListener, "+ repaired corrupted vertex " + vertex + "\n");
              } else if (vertex.isDirty() && !checkOnly) {
                message(outputListener, "+ optimized vertex " + vertex + "\n");

              }
            }

            message(outputListener, "Scanning vertices completed\n");
          });
    }
  }

  private void onScannedLink(final RepairStats stats, final Identifiable fieldValue) {
    stats.scannedLinks++;
    if (eventListener != null) {
      eventListener.onScannedLink(fieldValue);
    }
  }

  private void onRemovedLink(final RepairStats stats, final Identifiable fieldValue) {
    stats.removedLinks++;
    if (eventListener != null) {
      eventListener.onRemovedLink(fieldValue);
    }
  }

  public StorageRecoverEventListener getEventListener() {
    return eventListener;
  }

  public GraphRepair setEventListener(final StorageRecoverEventListener eventListener) {
    this.eventListener = eventListener;
    return this;
  }

  private static void message(final CommandOutputListener outputListener, final String message) {
    if (outputListener != null) {
      outputListener.onMessage(message);
    }
  }

  private boolean isEdgeBroken(
      DatabaseSessionInternal session, final Identifiable vertex,
      final String fieldName,
      final Direction direction,
      final Identifiable edgeRID,
      final RepairStats stats) {
    onScannedLink(stats, edgeRID);

    var broken = false;

    if (edgeRID == null)
    // RID NULL
    {
      broken = true;
    } else {
      EntityImpl record = null;
      try {
        Identifiable identifiable = edgeRID.getIdentity();
        var transaction = session.getActiveTransaction();
        record = transaction.load(identifiable);
      } catch (RecordNotFoundException e) {
        broken = true;
      }

      if (record == null)
      // RECORD DELETED
      {
        broken = true;
      } else {
        SchemaImmutableClass immutableClass = null;
        immutableClass = record.getImmutableSchemaClass(session);
        if (immutableClass == null
            || (!immutableClass.isVertexType() && !immutableClass.isEdgeType()))
        // INVALID RECORD TYPE: NULL OR NOT GRAPH TYPE
        {
          broken = true;
        } else {
          if (immutableClass.isVertexType()) {
            // VERTEX -> LIGHTWEIGHT EDGE
            final var inverseFieldName =
                getInverseConnectionFieldName(fieldName, true);

            // CHECK THE VERTEX IS IN INVERSE EDGE CONTAINS
            final var inverseEdgeContainer = record.getProperty(inverseFieldName);
            if (inverseEdgeContainer == null)
            // NULL CONTAINER
            {
              broken = true;
            } else {

              switch (inverseEdgeContainer) {
                case Identifiable identifiable -> {
                  if (!inverseEdgeContainer.equals(vertex))
                  // NOT THE SAME
                  {
                    broken = true;
                  }
                }
                case Collection<?> objects -> {
                  if (!((Collection<?>) inverseEdgeContainer).contains(vertex))
                  // NOT IN COLLECTION
                  {
                    broken = true;
                  }
                }
                case LinkBag rids -> {
                  if (!rids.contains(vertex.getIdentity()))
                  // NOT IN RIDBAG
                  {
                    broken = true;
                  }
                }
                default -> {
                }
              }
            }
          } else {
            // EDGE -> REGULAR EDGE, OK
            if (record.isStatefulEdge()) {
              var edge = record.asStatefulEdgeOrNull();
              final Identifiable backRID = edge.getVertex(direction);
              if (backRID == null || !backRID.equals(vertex))
              // BACK RID POINTS TO ANOTHER VERTEX
              {
                broken = true;
              }
            }
          }
        }
      }
    }

    if (broken) {
      onRemovedLink(stats, edgeRID);
      return true;
    }

    return false;
  }

  public static String getInverseConnectionFieldName(
      final String iFieldName, final boolean useVertexFieldsForEdgeLabels) {
    if (useVertexFieldsForEdgeLabels) {
      if (iFieldName.startsWith("out_")) {
        if (iFieldName.length() == "out_".length())
        // "OUT" CASE
        {
          return "in_";
        }

        return "in_" + iFieldName.substring("out_".length());

      } else if (iFieldName.startsWith("in_")) {
        if (iFieldName.length() == "in_".length())
        // "IN" CASE
        {
          return "out_";
        }

        return "out_" + iFieldName.substring("in_".length());

      } else {
        throw new IllegalArgumentException(
            "Cannot find reverse connection name for field " + iFieldName);
      }
    }

    if (iFieldName.equals("out")) {
      return "in";
    } else if (iFieldName.equals("in")) {
      return "out";
    }

    throw new IllegalArgumentException(
        "Cannot find reverse connection name for field " + iFieldName);
  }
}
