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
package com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.post;

import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.JSONSerializerJackson;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedServerAbstract;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ServerCommandPostServerCommand extends ServerCommandAuthenticatedServerAbstract {

  private static final String[] NAMES = {"POST|servercommand"};

  public ServerCommandPostServerCommand() {
    super("server.command");
  }

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts = checkSyntax(iRequest.getUrl(), 1, "Syntax error: servercommand");

    // TRY TO GET THE COMMAND FROM THE URL, THEN FROM THE CONTENT
    final var language = urlParts.length > 2 ? urlParts[2].trim() : "sql";
    var text = urlParts.length > 3 ? urlParts[3].trim() : iRequest.getContent();
    var limit = urlParts.length > 4 ? Integer.parseInt(urlParts[4].trim()) : -1;
    var fetchPlan = urlParts.length > 5 ? urlParts[5] : null;
    final var accept = iRequest.getHeader("accept");

    Object params = null;
    var mode = "resultset";

    var returnExecutionPlan = true;

    var begin = System.currentTimeMillis();
    if (iRequest.getContent() != null && !iRequest.getContent().isEmpty()) {
      // CONTENT REPLACES TEXT
      if (iRequest.getContent().startsWith("{")) {
        // JSON PAYLOAD
        final var content = JSONSerializerJackson.INSTANCE.mapFromJson(iRequest.getContent());
        text = (String) content.get("command");
        params = content.get("parameters");

        if ("false".equalsIgnoreCase("" + content.get("returnExecutionPlan"))) {
          returnExecutionPlan = false;
        }

        if (params instanceof Collection) {
          final var paramArray = new Object[((Collection) params).size()];
          ((Collection) params).toArray(paramArray);
          params = paramArray;
        }
      } else {
        text = iRequest.getContent();
      }
    }

    if ("false".equalsIgnoreCase(iRequest.getHeader("return-execution-plan"))) {
      returnExecutionPlan = false;
    }

    if (text == null) {
      throw new IllegalArgumentException("text cannot be null");
    }

    iRequest.getData().commandInfo = "Command";
    iRequest.getData().commandDetail = text;

    var result = executeStatement(language, text, params);

    var i = 0;
    List response = new ArrayList();
    while (result.hasNext()) {
      if (limit >= 0 && i >= limit) {
        break;
      }
      response.add(result.next());
      i++;
    }

    Map<String, Object> additionalContent = new HashMap<>();
    if (returnExecutionPlan) {
      var plan = result.getExecutionPlan();
      if (plan != null) {
        additionalContent.put("executionPlan", plan.toResult(null).toMap());
      }
    }

    result.close();
    var elapsedMs = System.currentTimeMillis() - begin;

    String format = null;
    if (fetchPlan != null) {
      format = "fetchPlan:" + fetchPlan;
    }

    if (iRequest.getHeader("TE") != null) {
      iResponse.setStreaming(true);
    }

    additionalContent.put("elapsedMs", elapsedMs);
    iResponse.writeResult(response, format, accept, additionalContent, mode, null);

    return false;
  }

  protected ResultSet executeStatement(String language, String text, Object params) {
    ResultSet result;

    var ytdb = this.server.getContext();
    if (params instanceof Map) {
      result = ytdb.execute(text, (Map) params);
    } else if (params instanceof Object[]) {
      result = ytdb.execute(text, (Object[]) params);
    } else {
      result = ytdb.execute(text, params);
    }
    return result;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
