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
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
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
  public CommandExecutorSQLCreateEdge parse(final CommandRequest iRequest) {
    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);

      final var database = getDatabase();

      init((CommandRequestText) iRequest);

      parserRequiredKeyword("CREATE");
      parserRequiredKeyword("EDGE");

      String className = null;

      String tempLower = parseOptionalWord(false);
      String temp = tempLower == null ? null : tempLower.toUpperCase(Locale.ENGLISH);

      while (temp != null) {
        if (temp.equals("CLUSTER")) {
          clusterName = parserRequiredWord(false);

        } else if (temp.equals(KEYWORD_FROM)) {
          from = parserRequiredWord(false, "Syntax error", " =><,\r\n");

        } else if (temp.equals("TO")) {
          to = parserRequiredWord(false, "Syntax error", " =><,\r\n");

        } else if (temp.equals(KEYWORD_SET)) {
          fields = new ArrayList<Pair<String, Object>>();
          parseSetFields(clazz, fields, iRequest.getContext().getDatabase());

        } else if (temp.equals(KEYWORD_CONTENT)) {
          parseContent();

        } else if (temp.equals(KEYWORD_BATCH)) {
          temp = parserNextWord(true);
          if (temp != null) {
            batch = Integer.parseInt(temp);
          }

        } else if (className == null && !temp.isEmpty()) {
          className = tempLower;

          clazz = (SchemaClassInternal) database.getMetadata().getImmutableSchemaSnapshot()
              .getClass(temp);
          if (clazz == null) {
            final int committed;
            if (database.getTransaction().isActive()) {
              LogManager.instance()
                  .warn(
                      this,
                      "Requested command '"
                          + this
                          + "' must be executed outside active transaction: the transaction will be"
                          + " committed and reopen right after it. To avoid this behavior execute"
                          + " it outside a transaction");
              committed = database.getTransaction().amountOfNestedTxs();
              database.commit();
            } else {
              committed = 0;
            }

            try {
              Schema schema = database.getMetadata().getSchema();
              SchemaClass e = schema.getClass("E");
              clazz = (SchemaClassInternal) schema.createClass(className, e);
            } finally {
              // RESTART TRANSACTION
              for (int i = 0; i < committed; ++i) {
                database.begin();
              }
            }
          }
        }

        temp = parseOptionalWord(true);
        if (parserIsEnded()) {
          break;
        }
      }

      if (className == null) {
        // ASSIGN DEFAULT CLASS
        className = "E";
        clazz = (SchemaClassInternal) database.getMetadata().getImmutableSchemaSnapshot()
            .getClass(className);
      }

      // GET/CHECK CLASS NAME
      if (clazz == null) {
        throw new CommandSQLParsingException("Class '" + className + "' was not found");
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
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal querySession) {
    if (clazz == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not been parsed yet");
    }

    DatabaseSessionInternal db = getDatabase();
    final List<Object> edges = new ArrayList<Object>();
    Set<Identifiable> fromIds = null;
    Set<Identifiable> toIds = null;
    db.begin();
    try {
      fromIds = SQLEngine.getInstance().parseRIDTarget(db, from, context, iArgs);
      toIds = SQLEngine.getInstance().parseRIDTarget(db, to, context, iArgs);

      // CREATE EDGES
      for (Identifiable from : fromIds) {
        final Vertex fromVertex = toVertex(from);
        if (fromVertex == null) {
          throw new CommandExecutionException("Source vertex '" + from + "' does not exist");
        }

        for (Identifiable to : toIds) {
          final Vertex toVertex;
          if (from.equals(to)) {
            toVertex = fromVertex;
          } else {
            toVertex = toVertex(to);
          }
          if (toVertex == null) {
            throw new CommandExecutionException("Source vertex '" + to + "' does not exist");
          }

          if (fields != null)
          // EVALUATE FIELDS
          {
            for (final Pair<String, Object> f : fields) {
              if (f.getValue() instanceof SQLFunctionRuntime) {
                f.setValue(((SQLFunctionRuntime) f.getValue()).getValue(to, null, context));
              } else if (f.getValue() instanceof SQLFilterItem) {
                f.setValue(((SQLFilterItem) f.getValue()).getValue(to, null, context));
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
            SQLHelper.bindParameters(
                edge.getRecord(), fields, new CommandParameters(iArgs), context);
          }

          edge.getBaseDocument().save(clusterName);
          fromVertex.save();
          toVertex.save();

          edges.add(edge);

          if (batch > 0 && edges.size() % batch == 0) {
            db.commit();
            db.begin();
          }
        }
      }

    } finally {
      db.commit();
    }

    if (edges.isEmpty()) {
      if (fromIds.isEmpty()) {
        throw new CommandExecutionException(
            "No edge has been created because no source vertices: " + this);
      } else if (toIds.isEmpty()) {
        throw new CommandExecutionException(
            "No edge has been created because no target vertices: " + this);
      }
      throw new CommandExecutionException(
          "No edge has been created between " + fromIds + " and " + toIds + ": " + this);
    }
    return edges;
  }

  private static Vertex toVertex(Identifiable item) {
    if (item == null) {
      return null;
    }
    if (item instanceof Entity) {
      return ((Entity) item).asVertex().orElse(null);
    } else {
      try {
        item = getDatabase().load(item.getIdentity());
      } catch (RecordNotFoundException e) {
        return null;
      }
      if (item instanceof Entity) {
        return ((Entity) item).asVertex().orElse(null);
      }
    }
    return null;
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.WRITE;
  }

  @Override
  public Set getInvolvedClusters() {
    if (clazz != null) {
      return Collections.singleton(
          getDatabase().getClusterNameById(clazz.getClusterSelection().getCluster(clazz, null)));
    } else if (clusterName != null) {
      return getInvolvedClustersOfClusters(Collections.singleton(clusterName));
    }

    return Collections.EMPTY_SET;
  }

  @Override
  public DISTRIBUTED_EXECUTION_MODE getDistributedExecutionMode() {
    return DISTRIBUTED_EXECUTION_MODE.LOCAL;
  }

  @Override
  public String getSyntax() {
    return "CREATE EDGE [<class>] [CLUSTER <cluster>] "
        + "FROM <rid>|(<query>|[<rid>]*) TO <rid>|(<query>|[<rid>]*) "
        + "[SET <field> = <expression>[,]*]|CONTENT {<JSON>} "
        + "[RETRY <retry> [WAIT <pauseBetweenRetriesInMs]] [BATCH <batch-size>]";
  }
}
