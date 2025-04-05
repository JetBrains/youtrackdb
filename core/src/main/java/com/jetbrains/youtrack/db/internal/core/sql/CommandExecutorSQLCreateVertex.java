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
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.VertexInternal;
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
  public CommandExecutorSQLCreateVertex parse(final CommandRequest iRequest) {

    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);

      final var database = getDatabase();

      init((CommandRequestText) iRequest);

      String className = null;

      parserRequiredKeyword("CREATE");
      parserRequiredKeyword("VERTEX");

      String temp = parseOptionalWord(true);

      while (temp != null) {
        if (temp.equals("CLUSTER")) {
          clusterName = parserRequiredWord(false);

        } else if (temp.equals(KEYWORD_SET)) {
          fields = new ArrayList<Pair<String, Object>>();
          parseSetFields(clazz, fields, iRequest.getContext().getDatabase());

        } else if (temp.equals(KEYWORD_CONTENT)) {
          parseContent();

        } else if (className == null && temp.length() > 0) {
          className = temp;
          if (className == null)
          // ASSIGN DEFAULT CLASS
          {
            className = "V";
          }

          // GET/CHECK CLASS NAME
          clazz = (SchemaClassInternal) database.getMetadata().getImmutableSchemaSnapshot()
              .getClass(className);
          if (clazz == null) {
            throw new CommandSQLParsingException("Class '" + className + "' was not found");
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
        clazz = (SchemaClassInternal) database.getMetadata().getImmutableSchemaSnapshot()
            .getClass(className);
        if (clazz == null) {
          throw new CommandSQLParsingException("Class '" + className + "' was not found");
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
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal querySession) {
    if (clazz == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not been parsed yet");
    }

    // CREATE VERTEX DOES NOT HAVE TO BE IN TX
    final VertexInternal vertex = (VertexInternal) getDatabase().newVertex(clazz);

    if (fields != null)
    // EVALUATE FIELDS
    {
      for (final Pair<String, Object> f : fields) {
        if (f.getValue() instanceof SQLFunctionRuntime) {
          f.setValue(
              ((SQLFunctionRuntime) f.getValue()).getValue(vertex.getRecord(), null, context));
        }
      }
    }

    SQLHelper.bindParameters(vertex.getRecord(), fields, new CommandParameters(iArgs), context);

    if (content != null) {
      ((EntityImpl) vertex.getRecord()).merge(content, true, false);
    }

    if (clusterName != null) {
      vertex.getBaseDocument().save(clusterName);
    } else {
      vertex.save();
    }

    return vertex.getRecord();
  }

  @Override
  public DISTRIBUTED_EXECUTION_MODE getDistributedExecutionMode() {
    return DISTRIBUTED_EXECUTION_MODE.LOCAL;
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.WRITE;
  }

  @Override
  public Set<String> getInvolvedClusters() {
    if (clazz != null) {
      return Collections.singleton(
          getDatabase().getClusterNameById(clazz.getClusterSelection().getCluster(clazz, null)));
    } else if (clusterName != null) {
      return getInvolvedClustersOfClusters(Collections.singleton(clusterName));
    }

    return Collections.EMPTY_SET;
  }

  @Override
  public String getSyntax() {
    return "CREATE VERTEX [<class>] [CLUSTER <cluster>] [SET <field> = <expression>[,]*]";
  }
}
