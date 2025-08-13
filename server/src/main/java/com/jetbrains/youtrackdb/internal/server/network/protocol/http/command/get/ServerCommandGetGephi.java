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
package com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.get;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.api.query.ResultSet;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.JSONWriter;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.HttpUtils;
import com.jetbrains.youtrackdb.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import com.jetbrains.youtrackdb.internal.tools.config.ServerCommandConfiguration;
import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ServerCommandGetGephi extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"GET|gephi/*"};

  public ServerCommandGetGephi() {
  }

  public ServerCommandGetGephi(final ServerCommandConfiguration iConfig) {
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    var urlParts =
        checkSyntax(
            iRequest.getUrl(),
            4,
            "Syntax error:"
                + " gephi/<database>/<language>/<query-text>[/<limit>][/<fetchPlan>].<br>Limit is"
                + " optional and is set to 20 by default. Set to 0 to have no limits.");

    final var language = urlParts[2];
    final var text = urlParts[3];
    final var limit = urlParts.length > 4 ? Integer.parseInt(urlParts[4]) : 20;
    final var fetchPlan = urlParts.length > 5 ? urlParts[5] : null;

    iRequest.getData().commandInfo = "Gephi";
    iRequest.getData().commandDetail = text;

    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {
      db.executeInTx(transaction -> {
        final ResultSet resultSet;
        if (language.equals("sql")) {
          resultSet = executeStatement(language, text, new HashMap<>(), db);
        } else {
          throw new IllegalArgumentException(
              "Language '" + language + "' is not supported. Use 'sql' or 'gremlin'");
        }

        sendRecordsContent(db, iRequest, iResponse, resultSet, fetchPlan, limit);
      });
    }

    return false;
  }

  protected static void sendRecordsContent(
      DatabaseSessionInternal db, final HttpRequest iRequest,
      final HttpResponse iResponse,
      ResultSet resultSet,
      String iFetchPlan,
      int limit) {

    final var buffer = new StringWriter();
    final var json = new JSONWriter(buffer, HttpResponse.JSON_FORMAT);
    json.setPrettyPrint(true);

    generateGraphDbOutput(db, resultSet, limit, json);

    iResponse.send(
        HttpUtils.STATUS_OK_CODE,
        HttpUtils.STATUS_OK_DESCRIPTION,
        HttpUtils.CONTENT_JSON,
        buffer.toString(),
        null);
  }

  protected static void generateGraphDbOutput(
      DatabaseSessionInternal db, final ResultSet resultSet, int limit, final JSONWriter json) {
    if (resultSet == null) {
      return;
    }

    // CREATE A SET TO SPEED UP SEARCHES ON VERTICES
    final Set<Vertex> vertexes = new HashSet<>();
    final Set<Edge> edges = new HashSet<>();

    var i = 0;
    while (resultSet.hasNext()) {
      if (limit >= 0 && i >= limit) {
        break;
      }
      var next = resultSet.next();
      if (next.isVertex()) {
        vertexes.add(next.asVertex());
      } else if (next.isStatefulEdge()) {
        edges.add(next.asStatefulEdge());
      }
      i++;
    }
    resultSet.close();

    try {
      for (var vertex : vertexes) {
        json.resetAttributes();
        json.beginObject(0, false, null);
        json.beginObject(1, false, "an");
        json.beginObject(2, false, vertex.getIdentity());

        // ADD ALL THE EDGES
        vertex.getEdges(Direction.BOTH).forEach(edges::add);

        // ADD ALL THE PROPERTIES
        for (var field : vertex.getPropertyNames()) {
          final var v = vertex.getProperty(field);
          if (v != null) {
            json.writeAttribute(db, 3, false, field, v);
          }
        }
        json.endObject(2, false);
        json.endObject(1, false);
        json.endObject(0, false);
        json.newline();
      }

      for (var edge : edges) {
        json.resetAttributes();
        json.beginObject();
        json.beginObject(1, false, "ae");
        if (edge.isStateful()) {
          json.beginObject(2, false, edge.asStatefulEdge().getIdentity());
        }

        json.writeAttribute(db, 3, false, "directed", false);

        json.writeAttribute(db, 3, false, "source", edge.getToLink());
        json.writeAttribute(db, 3, false, "target", edge.getFromLink());

        if (edge.isStateful()) {
          var statefulEdge = edge.asStatefulEdge();
          for (var field : statefulEdge.getPropertyNames()) {
            final var v = statefulEdge.getProperty(field);

            if (v != null) {
              json.writeAttribute(db, 3, false, field, v);
            }
          }
        }

        json.endObject(2, false);
        json.endObject(1, false);
        json.endObject(0, false);
        json.newline();
      }
    } catch (IOException e) {
      throw BaseException.wrapException(
          new CommandExecutionException(db, "Error while writing graph output"), e, db);
    }
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }

  protected static ResultSet executeStatement(
      String language, String text, Object params, DatabaseSessionEmbedded db) {
    ResultSet result;
    if (params instanceof Map) {
      result = db.computeScript("sql", text, (Map) params);
    } else if (params instanceof Object[]) {
      result = db.computeScript("sql", text, (Object[]) params);
    } else {
      result = db.computeScript("sql", text, params);
    }
    return result;
  }
}
