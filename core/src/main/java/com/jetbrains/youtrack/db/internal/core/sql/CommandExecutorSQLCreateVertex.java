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
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexEntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SQL CREATE VERTEX command.
 */
public class CommandExecutorSQLCreateVertex extends CommandExecutorSQLSetAware
    implements CommandDistributedReplicateRequest {

  public static final String NAME = "CREATE VERTEX";
  private SchemaClassInternal clazz;
  private String clusterName;
  private List<Pair<String, Object>> fields;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLCreateVertex parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {

    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      String className = null;

      parserRequiredKeyword(session.getDatabaseName(), "CREATE");
      parserRequiredKeyword(session.getDatabaseName(), "VERTEX");

      var temp = parseOptionalWord(session.getDatabaseName(), true);

      while (temp != null) {
        if (temp.equals("CLUSTER")) {
          clusterName = parserRequiredWord(session.getDatabaseName(), false);

        } else if (temp.equals(KEYWORD_SET)) {
          fields = new ArrayList<>();
          parseSetFields(session, clazz, fields);

        } else if (temp.equals(KEYWORD_CONTENT)) {
          parseContent(session);

        } else if (className == null && !temp.isEmpty()) {
          className = temp;

          // GET/CHECK CLASS NAME
          clazz = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
              .getClass(className);
          if (clazz == null) {
            throw new CommandSQLParsingException(session.getDatabaseName(),
                "Class '" + className + "' was not found");
          }
        }

        temp = parserOptionalWord(true);
        if (parserIsEnded()) {
          break;
        }
      }

      if (className == null) {
        // ASSIGN DEFAULT CLASS
        className = "V";

        // GET/CHECK CLASS NAME
        clazz = (SchemaClassInternal) session.getMetadata().getImmutableSchemaSnapshot()
            .getClass(className);
        if (clazz == null) {
          throw new CommandSQLParsingException(session.getDatabaseName(),
              "Class '" + className + "' was not found");
        }
      }
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

    // CREATE VERTEX DOES NOT HAVE TO BE IN TX
    final var vertex = (VertexEntityImpl) session.newVertex(clazz);
    if (fields != null)
    // EVALUATE FIELDS
    {
      for (final var f : fields) {
        if (f.getValue() instanceof SQLFunctionRuntime) {
          f.setValue(
              ((SQLFunctionRuntime) f.getValue()).getValue(vertex, null,
                  context));
        }
      }
    }

    SQLHelper.bindParameters(vertex, fields, new CommandParameters(iArgs),
        context);

    if (content != null) {
      throw new UnsupportedOperationException();
    }

    return vertex.getRecord(session);
  }

  @Override
  public Set<String> getInvolvedClusters(DatabaseSessionInternal session) {
    if (clazz != null) {
      return Collections.singleton(
          session.getClusterNameById(
              clazz.getClusterSelection().getCluster(session, clazz, null)));
    } else if (clusterName != null) {
      return getInvolvedClustersOfClusters(session, Collections.singleton(clusterName));
    }

    return Collections.emptySet();
  }

  @Override
  public String getSyntax() {
    return "CREATE VERTEX [<class>] [CLUSTER <cluster>] [SET <field> = <expression>[,]*]";
  }
}
