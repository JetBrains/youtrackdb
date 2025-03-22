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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerJackson;
import com.jetbrains.youtrack.db.internal.core.sql.SQLEngine;
import com.jetbrains.youtrack.db.internal.core.sql.parser.DDLStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLTraverseStatement;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandAuthenticatedDbAbstract;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;

public class ServerCommandPostCommand extends ServerCommandAuthenticatedDbAbstract {

  private static final String[] NAMES = {"GET|command/*", "POST|command/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    final var urlParts =
        checkSyntax(
            iRequest.getUrl(),
            3,
            "Syntax error: command/<database>/<language>/<command-text>[/limit][/<fetchPlan>]");

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
        final var map = RecordSerializerJackson.mapFromJson(iRequest.getContent());
        text = (String) map.get("command");
        params = map.get("parameters");
        if (map.containsKey("mode")) {
          mode = map.get("mode").toString();
        }

        if ("false".equalsIgnoreCase("" + map.get("returnExecutionPlan"))) {
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

    DatabaseSessionInternal session = null;

    var ok = false;
    var txBegun = false;
    try {
      session = getProfiledDatabaseSessionInstance(iRequest);
      session.resetRecordLoadStats();
      var stm = parseStatement(language, text, session);

      if (stm != null && !(stm instanceof DDLStatement)) {
        session.begin();
        txBegun = true;
      }

      var result = executeStatement(language, text, params, session);
      limit = getLimitFromStatement(stm, limit);
      var localFetchPlan = getFetchPlanFromStatement(stm);
      if (localFetchPlan != null) {
        fetchPlan = localFetchPlan;
      }
      var i = 0;
      List response = new ArrayList();
      TimerTask commandInterruptTimer = null;
      if (session.getConfiguration().getValueAsLong(GlobalConfiguration.COMMAND_TIMEOUT) > 0
          && !language.equalsIgnoreCase("sql")) {
      }
      try {
        while (result.hasNext()) {
          if (limit >= 0 && i >= limit) {
            break;
          }
          response.add(result.next());
          i++;
        }
      } finally {
        if (commandInterruptTimer != null) {
          commandInterruptTimer.cancel();
        }
      }
      Map<String, Object> additionalContent = new HashMap<>();
      if (returnExecutionPlan) {
        var plan = result.getExecutionPlan();
        if (plan != null) {
          additionalContent.put("executionPlan", plan.toResult(session).toMap());
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
      var dbStats = session.getStats();
      additionalContent.put("dbStats", dbStats.toResult(session).toMap());

      iResponse.writeResult(response, format, accept, additionalContent, mode, session);
      ok = true;
    } finally {
      if (session != null) {
        if (txBegun && session.getActiveTransactionOrNull() != null) {
          if (ok) {
            session.commit();
          } else {
            session.rollback();
          }
        }
        session.close();
      }
    }

    return false;
  }

  public static String getFetchPlanFromStatement(SQLStatement statement) {
    if (statement instanceof SQLSelectStatement) {
      var fp = ((SQLSelectStatement) statement).getFetchPlan();
      if (fp != null) {
        return fp.toString().substring("FETCHPLAN ".length());
      }
    }

    return null;
  }

  public static SQLStatement parseStatement(String language, String text, DatabaseSession db) {
    try {
      if (language != null && language.equalsIgnoreCase("sql")) {
        return SQLEngine.parse(text, (DatabaseSessionInternal) db);
      }
    } catch (Exception e) {
    }
    return null;
  }

  public static int getLimitFromStatement(SQLStatement statement, int previousLimit) {
    try {
      SQLLimit limit = null;
      if (statement instanceof SQLSelectStatement) {
        limit = ((SQLSelectStatement) statement).getLimit();
      } else if (statement instanceof SQLMatchStatement) {
        limit = ((SQLMatchStatement) statement).getLimit();
      } else if (statement instanceof SQLTraverseStatement) {
        limit = ((SQLTraverseStatement) statement).getLimit();
      }
      if (limit != null) {
        return limit.getValue(new BasicCommandContext());
      }

    } catch (Exception e) {
    }
    return previousLimit;
  }

  protected ResultSet executeStatement(
      String language, String text, Object params, DatabaseSession db) {
    ResultSet result;

    if (params instanceof Map) {
      result = db.runScript(language, text, (Map) params);
    } else if (params instanceof Object[]) {
      result = db.runScript(language, text, (Object[]) params);
    } else {
      result = db.runScript(language, text, params);
    }

    return result;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
