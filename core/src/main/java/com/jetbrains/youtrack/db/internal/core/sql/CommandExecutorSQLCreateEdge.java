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
package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EdgeInternal;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL CREATE EDGE command.
 */
public class CommandExecutorSQLCreateEdge extends CommandExecutorSQLSetAware
    implements CommandDistributedReplicateRequest {

  public static final String NAME = "CREATE EDGE";
  private static final String KEYWORD_BATCH = "BATCH";

  private String from;
  private String to;
  private SchemaClassInternal clazz;
  private String edgeLabel;
  private String clusterName;
  private List<Pair<String, Object>> fields;
  private int batch = 100;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLCreateEdge parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      parserRequiredKeyword(session.getDatabaseName(), "CREATE");
      parserRequiredKeyword(session.getDatabaseName(), "EDGE");

      String className = null;

      var tempLower = parseOptionalWord(session.getDatabaseName(), false);
      var temp = tempLower == null ? null : tempLower.toUpperCase(Locale.ENGLISH);

      while (temp != null) {
        if (temp.equals("CLUSTER")) {
          clusterName = parserRequiredWord(session.getDatabaseName(), false);

        } else if (temp.equals(KEYWORD_FROM)) {
          from = parserRequiredWord(false, "Syntax error", " =><,\r\n", session.getDatabaseName());

        } else if (temp.equals("TO")) {
          to = parserRequiredWord(false, "Syntax error", " =><,\r\n", session.getDatabaseName());

        } else if (temp.equals(KEYWORD_SET)) {
          fields = new ArrayList<Pair<String, Object>>();
          parseSetFields(session, clazz, fields);

        } else if (temp.equals(KEYWORD_CONTENT)) {
          parseContent(session);

        } else if (temp.equals(KEYWORD_BATCH)) {
          temp = parserNextWord(true);
          if (temp != null) {
            batch = Integer.parseInt(temp);
          }

        } else if (className == null && !temp.isEmpty()) {
          className = tempLower;

          clazz = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
              .getClass(temp);
          if (clazz == null) {
            final int committed;
            if (session.getTransaction().isActive()) {
              LogManager.instance()
                  .warn(
                      this,
                      "Requested command '"
                          + this
                          + "' must be executed outside active transaction: the transaction will be"
                          + " committed and reopen right after it. To avoid this behavior execute"
                          + " it outside a transaction");
              committed = session.getTransaction().amountOfNestedTxs();
              session.commit();
            } else {
              committed = 0;
            }

            try {
              Schema schema = session.getMetadata().getSchema();
              var e = schema.getClass("E");
              clazz = (SchemaClassInternal) schema.createClass(className, e);
            } finally {
              // RESTART TRANSACTION
              for (var i = 0; i < committed; ++i) {
                session.begin();
              }
            }
          }
        }

        temp = parseOptionalWord(session.getDatabaseName(), true);
        if (parserIsEnded()) {
          break;
        }
      }

      if (className == null) {
        // ASSIGN DEFAULT CLASS
        className = "E";
        clazz = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
            .getClass(className);
      }

      // GET/CHECK CLASS NAME
      if (clazz == null) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Class '" + className + "' was not found");
      }

      edgeLabel = className;
    } finally {
      textRequest.setText(originalQuery);
    }
    return this;
  }

  /**
   * Execute the command and return the EntityImpl object created.
   */
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (clazz == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    final List<Object> edges = new ArrayList<Object>();
    Set<Identifiable> fromIds = null;
    Set<Identifiable> toIds = null;
    session.begin();
    try {
      fromIds = SQLEngine.getInstance().parseRIDTarget(session, from, context, iArgs);
      toIds = SQLEngine.getInstance().parseRIDTarget(session, to, context, iArgs);

      // CREATE EDGES
      for (var from : fromIds) {
        final var fromVertex = toVertex(session, from);
        if (fromVertex == null) {
          throw new CommandExecutionException(session,
              "Source vertex '" + from + "' does not exist");
        }

        for (var to : toIds) {
          final Vertex toVertex;
          if (from.equals(to)) {
            toVertex = fromVertex;
          } else {
            toVertex = toVertex(session, to);
          }
          if (toVertex == null) {
            throw new CommandExecutionException(session,
                "Source vertex '" + to + "' does not exist");
          }

          if (fields != null)
          // EVALUATE FIELDS
          {
            for (final var f : fields) {
              if (f.getValue() instanceof SQLFunctionRuntime) {
                f.setValue(((SQLFunctionRuntime) f.getValue()).getValue(to.getEntity(session), null,
                    context));
              } else if (f.getValue() instanceof SQLFilterItem) {
                f.setValue(
                    ((SQLFilterItem) f.getValue()).getValue(to.getEntity(session), null, context));
              }
            }
          }

          EdgeInternal edge;

          if (content != null) {
            if (fields != null)
            // MERGE CONTENT WITH FIELDS
            {
              fields.addAll(Pair.convertFromMap(content.toMap()));
            } else {
              fields = Pair.convertFromMap(content.toMap());
            }
          }

          edge = (EdgeInternal) fromVertex.addEdge(toVertex, edgeLabel);
          if (fields != null && !fields.isEmpty()) {
            throw new UnsupportedOperationException();
          }

          edges.add(edge);

          if (batch > 0 && edges.size() % batch == 0) {
            session.commit();
            session.begin();
          }
        }
      }

    } finally {
      session.commit();
    }

    if (edges.isEmpty()) {
      if (fromIds.isEmpty()) {
        throw new CommandExecutionException(session,
            "No edge has been created because no source vertices: " + this);
      } else if (toIds.isEmpty()) {
        throw new CommandExecutionException(session,
            "No edge has been created because no target vertices: " + this);
      }
      throw new CommandExecutionException(session,
          "No edge has been created between " + fromIds + " and " + toIds + ": " + this);
    }
    return edges;
  }

  private static Vertex toVertex(DatabaseSessionInternal db, Identifiable item) {
    if (item == null) {
      return null;
    }
    if (item instanceof Entity) {
      return ((Entity) item).asVertex();
    } else {
      try {
        item = db.load(item.getIdentity());
      } catch (RecordNotFoundException e) {
        return null;
      }
      if (item instanceof Entity) {
        return ((Entity) item).asVertex();
      }
    }
    return null;
  }

  @Override
  public Set getInvolvedClusters(DatabaseSessionInternal session) {
    if (clazz != null) {
      return Collections.singleton(
          session.getClusterNameById(
              clazz.getClusterSelection(session).getCluster(session, clazz, null)));
    } else if (clusterName != null) {
      return getInvolvedClustersOfClusters(session, Collections.singleton(clusterName));
    }

    return Collections.EMPTY_SET;
  }


  @Override
  public String getSyntax() {
    return "CREATE EDGE [<class>] [CLUSTER <cluster>] "
        + "FROM <rid>|(<query>|[<rid>]*) TO <rid>|(<query>|[<rid>]*) "
        + "[SET <field> = <expression>[,]*]|CONTENT {<JSON>} "
        + "[RETRY <retry> [WAIT <pauseBetweenRetriesInMs]] [BATCH <batch-size>]";
  }
}
