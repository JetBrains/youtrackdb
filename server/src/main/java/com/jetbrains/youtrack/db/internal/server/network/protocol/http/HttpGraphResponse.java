/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.server.network.protocol.http;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.util.CallableFunction;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.JSONWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Graph wrapper to format the response as graph.
 */
public class HttpGraphResponse extends HttpResponseAbstract {

  private final HttpResponse iWrapped;

  public HttpGraphResponse(final HttpResponse iWrapped) {
    super(
        iWrapped.getOutputStream(),
        iWrapped.getHttpVersion(),
        iWrapped.getAdditionalHeaders(),
        iWrapped.getCharacterSet(),
        iWrapped.getServerInfo(),
        iWrapped.getSessionId(),
        iWrapped.getCallbackFunction(),
        iWrapped.isKeepAlive(),
        iWrapped.getConnection(),
        iWrapped.getContextConfiguration());
    this.iWrapped = iWrapped;
  }

  public void writeRecords(
      final Object iRecords,
      final String iFetchPlan,
      String iFormat,
      final String accept,
      final Map<String, Object> iAdditionalProperties,
      final String mode,
      DatabaseSessionInternal session)
      throws IOException {
    if (iRecords == null) {
      return;
    }

    if (!mode.equalsIgnoreCase("graph")) {
      super.writeRecords(
          iRecords,
          iFetchPlan,
          iFormat,
          accept,
          iAdditionalProperties,
          mode,
          session);
      return;
    }

    if (accept != null && accept.contains("text/csv")) {
      throw new IllegalArgumentException("Graph mode cannot accept '" + accept + "'");
    }

    try {
      // DIVIDE VERTICES FROM EDGES
      final Set<Vertex> vertices = new HashSet<>();

      Set<RID> edgeRids = new HashSet<RID>();
      var lightweightFound = false;

      final var iIterator = MultiValue.getMultiValueIterator(iRecords);
      while (iIterator.hasNext()) {
        var entry = iIterator.next();

        if (entry != null && entry instanceof Result && ((Result) entry).isEntity()) {

          entry = ((Result) entry).asEntity();

        } else if (entry == null || !(entry instanceof Identifiable)) {
          // IGNORE IT
          continue;
        }

        try {
          entry = ((Identifiable) entry).getRecord(session);
        } catch (Exception e) {
          // IGNORE IT
          continue;
        }
        entry = ((Identifiable) entry).getRecord(session);

        if (entry instanceof Entity element) {
          if (element.isVertex()) {
            vertices.add(element.asVertex());
          } else if (element.isStatefulEdge()) {
            var edge = element.asStatefulEdge();
            vertices.add(edge.getTo());
            vertices.add(edge.getFrom());
            if (edge.getIdentity() != null) {
              edgeRids.add(edge.getIdentity());
            } else {
              lightweightFound = true;
            }
          } else
          // IGNORE IT
          {
            continue;
          }
        }
      }

      final var buffer = new StringWriter();
      final var json = new JSONWriter(buffer, "");
      json.beginObject();
      json.beginObject("graph");

      // WRITE VERTICES
      json.beginCollection(session, "vertices");
      for (var vertex : vertices) {
        json.beginObject();

        json.writeAttribute(session, "@rid", vertex.getIdentity());
        json.writeAttribute(session, "@class", vertex.getSchemaClassName());

        // ADD ALL THE PROPERTIES
        for (var field : ((VertexInternal) vertex).getPropertyNamesInternal(false, true)) {
          final var v = ((VertexInternal) vertex).getPropertyInternal(field);
          if (v != null) {
            json.writeAttribute(session, field, v);
          }
        }
        json.endObject();
      }
      json.endCollection();

      if (lightweightFound) {
        // clean up cached edges and re-calculate, there could be more
        edgeRids.clear();
      }

      // WRITE EDGES
      json.beginCollection(session, "edges");

      if (edgeRids.isEmpty()) {
        for (var vertex : vertices) {
          for (var e : vertex.getEdges(Direction.OUT)) {
            var edge = e;
            if (e.isLightweight()) {
              continue;
            }

            var statefulEdge = e.asStatefulEdge();
            if (edgeRids.contains(statefulEdge.getIdentity())) {
              continue;
            }
            if (!vertices.contains(edge.getVertex(Direction.OUT))
                || !vertices.contains(edge.getVertex(Direction.IN)))
            // ONE OF THE 2 VERTICES ARE NOT PART OF THE RESULT SET: DISCARD IT
            {
              continue;
            }

            edgeRids.add(statefulEdge.getIdentity());

            printEdge(session, json, edge);
          }
        }
      } else {
        for (var edgeRid : edgeRids) {
          try {
            Entity elem = edgeRid.getRecord(session);
            var edge = elem.asStatefulEdgeOrNull();

            if (edge != null) {
              printEdge(session, json, edge);
            }
          } catch (RecordNotFoundException rnf) {
            // ignore
          }
        }
      }

      json.endCollection();

      if (iAdditionalProperties != null) {
        for (var entry : iAdditionalProperties.entrySet()) {

          final var v = entry.getValue();
          if (MultiValue.isMultiValue(v)) {
            json.beginCollection(session, -1, true, entry.getKey());
            formatMultiValue(MultiValue.getMultiValueIterator(v), buffer, null, session);
            json.endCollection(-1, true);
          } else {
            json.writeAttribute(session, entry.getKey(), v);
          }

          if (Thread.currentThread().isInterrupted()) {
            break;
          }
        }
      }

      json.endObject();
      json.endObject();

      send(
          HttpUtils.STATUS_OK_CODE,
          HttpUtils.STATUS_OK_DESCRIPTION,
          HttpUtils.CONTENT_JSON,
          buffer.toString(),
          null);
    } finally {
      session.close();
    }
  }

  private static void printEdge(DatabaseSessionInternal session, JSONWriter json, Edge edge)
      throws IOException {
    json.beginObject();

    if (!edge.isLightweight()) {
      var statefulEdge = edge.asStatefulEdge();
      json.writeAttribute(session, "@rid", statefulEdge.getIdentity());
      json.writeAttribute(session, "@class", statefulEdge.getSchemaClassName());

      for (var field : statefulEdge.getPropertyNames()) {
        final var v = statefulEdge.getProperty(field);
        if (v != null) {
          json.writeAttribute(session, field, v);
        }
      }
    }

    json.writeAttribute(session, "out", edge.getVertex(Direction.OUT).getIdentity());
    json.writeAttribute(session, "in", edge.getVertex(Direction.IN).getIdentity());

    json.endObject();
  }

  @Override
  public void send(
      final int iCode,
      final String iReason,
      final String iContentType,
      final Object iContent,
      final String iHeaders)
      throws IOException {
    iWrapped.send(iCode, iReason, iContentType, iContent, iHeaders);
  }

  @Override
  public void writeStatus(final int iStatus, final String iReason) throws IOException {
    writeLine(getHttpVersion() + " " + iStatus + " " + iReason);
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      InputStream iContent,
      long iSize)
      throws IOException {
    sendStream(iCode, iReason, iContentType, iContent, iSize, null, null);
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      InputStream iContent,
      long iSize,
      final String iFileName)
      throws IOException {
    sendStream(iCode, iReason, iContentType, iContent, iSize, iFileName, null);
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      InputStream iContent,
      long iSize,
      final String iFileName,
      Map<String, String> additionalHeaders)
      throws IOException {
    writeStatus(iCode, iReason);
    writeHeaders(iContentType);
    writeLine("Content-Transfer-Encoding: binary");

    if (iFileName != null) {
      writeLine("Content-Disposition: attachment; filename=\"" + iFileName + "\"");
    }

    if (additionalHeaders != null) {
      for (var entry : additionalHeaders.entrySet()) {
        writeLine(String.format("%s: %s", entry.getKey(), entry.getValue()));
      }
    }
    if (iSize < 0) {
      // SIZE UNKNOWN: USE A MEMORY BUFFER
      final var o = new ByteArrayOutputStream();
      if (iContent != null) {
        int b;
        while ((b = iContent.read()) > -1) {
          o.write(b);
        }
      }

      var content = o.toByteArray();

      iContent = new ByteArrayInputStream(content);
      iSize = content.length;
    }

    writeLine(HttpUtils.HEADER_CONTENT_LENGTH + (iSize));
    writeLine(null);

    if (iContent != null) {
      int b;
      while ((b = iContent.read()) > -1) {
        getOut().write(b);
      }
    }

    flush();
  }

  @Override
  public void sendStream(
      final int iCode,
      final String iReason,
      final String iContentType,
      final String iFileName,
      final CallableFunction<Void, ChunkedResponse> iWriter)
      throws IOException {
    writeStatus(iCode, iReason);
    writeHeaders(iContentType);
    writeLine("Content-Transfer-Encoding: binary");
    writeLine("Transfer-Encoding: chunked");

    if (iFileName != null) {
      writeLine("Content-Disposition: attachment; filename=\"" + iFileName + "\"");
    }

    writeLine(null);

    final var chunkedOutput = new ChunkedResponse(this);
    iWriter.call(chunkedOutput);
    chunkedOutput.close();

    flush();
  }

  @Override
  public void checkConnection() throws IOException {
    iWrapped.checkConnection();
  }
}
