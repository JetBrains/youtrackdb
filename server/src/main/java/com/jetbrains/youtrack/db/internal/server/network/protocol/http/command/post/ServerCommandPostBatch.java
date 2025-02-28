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

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpRequest;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.HttpResponse;
import com.jetbrains.youtrack.db.internal.server.network.protocol.http.command.ServerCommandDocumentAbstract;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes a batch of operations in a single call. This is useful to reduce network latency issuing
 * multiple commands as multiple requests. Batch command supports transactions as well.<br>
 * <br>
 * Format: { "transaction" : &lt;true|false&gt;, "operations" : [ { "type" : "&lt;type&gt;" }* ] }
 * <br>
 * Where:
 *
 * <ul>
 *   <li><b>type</b> can be:
 *       <ul>
 *         <li>'c' for create
 *         <li>'u' for update
 *         <li>'d' for delete. The '@rid' field only is needed.
 *       </ul>
 * </ul>
 * <p>
 * Example:<br>
 *
 * <p>
 *
 * <pre>
 * { "transaction" : true,
 *   "operations" : [
 *        { "type" : "u",
 *          "record" : {
 *            "@rid" : "#14:122",
 *            "name" : "Luca",
 *            "vehicle" : "Car"
 *          }
 *        }, { "type" : "d",
 *          "record" : {
 *            "@rid" : "#14:100"
 *          }
 *        }, { "type" : "c",
 *          "record" : {
 *            "@class" : "City",
 *            "name" : "Venice"
 *          }
 *        }
 *     ]
 * }
 * </pre>
 */
public class ServerCommandPostBatch extends ServerCommandDocumentAbstract {

  private static final String[] NAMES = {"POST|batch/*"};

  @Override
  public boolean execute(final HttpRequest iRequest, HttpResponse iResponse) throws Exception {
    checkSyntax(iRequest.getUrl(), 2, "Syntax error: batch/<database>");

    iRequest.getData().commandInfo = "Execute multiple requests in one shot";

    EntityImpl batch = null;
    Object lastResult = null;
    try (var db = getProfiledDatabaseSessionInstance(iRequest)) {

      if (db.getTransaction().isActive()) {
        // TEMPORARY PATCH TO UNDERSTAND WHY UNDER HIGH LOAD TX IS NOT COMMITTED AFTER BATCH. MAYBE
        // A PENDING TRANSACTION?
        LogManager.instance()
            .warn(
                this,
                "Found database instance from the pool with a pending transaction. Forcing rollback"
                    + " before using it");
        db.rollback(true);
      }

      batch = new EntityImpl(null);
      batch.updateFromJSON(iRequest.getContent());

      Boolean tx = batch.field("transaction");
      if (tx == null) {
        tx = false;
      }

      final Collection<Map<Object, Object>> operations;
      try {
        operations = batch.field("operations");
      } catch (Exception e) {
        throw new IllegalArgumentException(
            "Expected 'operations' field as a collection of objects", e);
      }

      if (operations == null || operations.isEmpty()) {
        throw new IllegalArgumentException("Input JSON has no operations to execute");
      }

      var txBegun = false;
      if (tx && !db.getTransaction().isActive()) {
        db.begin();
        txBegun = true;
      }

      // BROWSE ALL THE OPERATIONS
      for (var operation : operations) {
        final var type = (String) operation.get("type");

        if (type.equals("c")) {
          // CREATE
          final var entity = getRecord(db, operation);

          lastResult = entity;
        } else if (type.equals("u")) {
          // UPDATE
          final var entity = getRecord(db, operation);

          lastResult = entity;
        } else if (type.equals("d")) {
          // DELETE
          final var entity = getRecord(db, operation);
          if (entity.isRecord()) {
            entity.castToRecord().delete();
          } else {
            throw new IllegalArgumentException("Cannot delete a non-record entity");
          }

          lastResult = entity.getIdentity();
        } else if (type.equals("cmd")) {
          // COMMAND
          final var language = (String) operation.get("language");
          if (language == null) {
            throw new IllegalArgumentException("language parameter is null");
          }

          final var command = operation.get("command");
          if (command == null) {
            throw new IllegalArgumentException("command parameter is null");
          }

          var params = operation.get("parameters");
          if (params instanceof Collection) {
            params = ((Collection) params).toArray();
          }

          String commandAsString = null;
          if (command != null) {
            if (MultiValue.isMultiValue(command)) {
              for (var c : MultiValue.getMultiValueIterable(command)) {
                if (commandAsString == null) {
                  commandAsString = c.toString();
                } else {
                  commandAsString += ";" + c.toString();
                }
              }
            } else {
              commandAsString = command.toString();
            }
          }

          ResultSet result;
          if (params == null) {
            result = db.execute(language, commandAsString);
          } else {
            result = db.execute(language, commandAsString, (Object[]) params);
          }
          lastResult = result.stream().map(Result::toMap).collect(Collectors.toList());
          result.close();
        } else if (type.equals("script")) {
          // COMMAND
          final var language = (String) operation.get("language");
          if (language == null) {
            throw new IllegalArgumentException("language parameter is null");
          }

          final var script = operation.get("script");
          if (script == null) {
            throw new IllegalArgumentException("script parameter is null");
          }

          var text = new StringBuilder(1024);
          if (MultiValue.isMultiValue(script)) {
            // ENSEMBLE ALL THE SCRIPT LINES IN JUST ONE SEPARATED BY LINEFEED
            var i = 0;
            for (var o : MultiValue.getMultiValueIterable(script)) {
              if (o != null) {
                if (i++ > 0) {
                  if (!text.toString().trim().endsWith(";")) {
                    text.append(";");
                  }
                  text.append("\n");
                }
                text.append(o);
              }
            }
          } else {
            text.append(script);
          }

          var params = operation.get("parameters");
          if (params instanceof Collection) {
            params = ((Collection) params).toArray();
          }

          ResultSet result;
          if (params == null) {
            result = db.execute(language, text.toString());
          } else {
            result = db.execute(language, text.toString(), (Object[]) params);
          }

          lastResult = result.stream().map(Result::toMap).collect(Collectors.toList());
          result.close();
        }
      }

      if (txBegun) {
        db.commit();
      }

      try {
        iResponse.writeResult(lastResult, db);
      } catch (RuntimeException e) {
        LogManager.instance()
            .error(
                this,
                "Error (%s) on serializing result of batch command:\n%s",
                e,
                batch.toJSON("prettyPrint"));
        throw e;
      }

    }
    return false;
  }

  public static Result getRecord(DatabaseSessionInternal db, Map<Object, Object> operation) {
    var record = operation.get("record");

    Result result;
    if (record instanceof Map<?, ?>)
    // CONVERT MAP IN DOCUMENT
    {
      result = new ResultInternal(db, (Map<String, Object>) record);
    } else {
      result = (EntityImpl) record;
    }
    return result;
  }

  @Override
  public String[] getNames() {
    return NAMES;
  }
}
