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
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL MOVE VERTEX command.
 */
public class CommandExecutorSQLMoveVertex extends CommandExecutorSQLSetAware
    implements CommandDistributedReplicateRequest {

  public static final String NAME = "MOVE VERTEX";
  private static final String KEYWORD_MERGE = "MERGE";
  private static final String KEYWORD_BATCH = "BATCH";
  private String source = null;
  private String clusterName;
  private String className;
  private SchemaClass clazz;
  private List<Pair<String, Object>> fields;
  private EntityImpl merge;
  private int batch = 100;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLMoveVertex parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {

    init(session, (CommandRequestText) iRequest);

    parserRequiredKeyword(session.getDatabaseName(), "MOVE");
    parserRequiredKeyword(session.getDatabaseName(), "VERTEX");

    source = parserRequiredWord(false, "Syntax error", " =><,\r\n", session.getDatabaseName());
    if (source == null) {
      throw new CommandSQLParsingException(session.getDatabaseName(), "Cannot find source");
    }

    parserRequiredKeyword(session.getDatabaseName(), "TO");

    var temp = parseOptionalWord(session.getDatabaseName(), true);

    while (temp != null) {
      if (temp.startsWith("CLUSTER:")) {
        if (className != null) {
          throw new CommandSQLParsingException(session.getDatabaseName(),
              "Cannot define multiple sources. Found both cluster and class.");
        }

        clusterName = temp.substring("CLUSTER:".length());
        if (session.getClusterIdByName(clusterName) == -1) {
          throw new CommandSQLParsingException(session.getDatabaseName(),
              "Cluster '" + clusterName + "' was not found");
        }

      } else if (temp.startsWith("CLASS:")) {
        if (clusterName != null) {
          throw new CommandSQLParsingException(session.getDatabaseName(),
              "Cannot define multiple sources. Found both cluster and class.");
        }

        className = temp.substring("CLASS:".length());

        clazz = session.getMetadata().getSchema().getClass(className);

        if (clazz == null) {
          throw new CommandSQLParsingException(session.getDatabaseName(),
              "Class '" + className + "' was not found");
        }

      } else if (temp.equals(KEYWORD_SET)) {
        fields = new ArrayList<Pair<String, Object>>();
        parseSetFields(session, clazz, fields);

      } else if (temp.equals(KEYWORD_MERGE)) {
        merge = parseJSON(session);

      } else if (temp.equals(KEYWORD_BATCH)) {
        temp = parserNextWord(true);
        if (temp != null) {
          batch = Integer.parseInt(temp);
        }
      }

      temp = parserOptionalWord(true);
      if (parserIsEnded()) {
        break;
      }
    }

    return this;
  }

  /**
   * Executes the command and return the EntityImpl object created.
   */
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {

    session.begin();
    if (className == null && clusterName == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    final var sourceRIDs =
        SQLEngine.getInstance().parseRIDTarget(session, source, context, iArgs);

    // CREATE EDGES
    final List<EntityImpl> result = new ArrayList<EntityImpl>(sourceRIDs.size());

    for (var from : sourceRIDs) {
      final var fromVertex = toVertex(session, from);
      if (fromVertex == null) {
        continue;
      }

      var oldVertex = ((RecordId) fromVertex.getIdentity()).copy();
      var newVertex = fromVertex.moveTo(className);

      final EntityImpl newVertexDoc = newVertex.getRecord(session);

      if (fields != null) {
        // EVALUATE FIELDS
        for (final var f : fields) {
          if (f.getValue() instanceof SQLFunctionRuntime) {
            f.setValue(
                ((SQLFunctionRuntime) f.getValue())
                    .getValue(newVertex.getRecord(session), null, context));
          }
        }

        SQLHelper.bindParameters(newVertexDoc, fields, new CommandParameters(iArgs), context);
      }

      if (merge != null) {
        newVertexDoc.merge(merge, true, false);
      }

      // SAVE CHANGES

      // PUT THE MOVE INTO THE RESULT
      result.add(
          new EntityImpl(session)
              .setTrackingChanges(false)
              .field("old", oldVertex, PropertyType.LINK)
              .field("new", newVertex, PropertyType.LINK));

      if (batch > 0 && result.size() % batch == 0) {
        session.commit();
        session.begin();
      }
    }

    session.commit();

    return result;
  }

  @Override
  public String getSyntax() {
    return "MOVE VERTEX <source> TO <destination> [SET [<field>=<value>]* [,]] [MERGE <JSON>]"
        + " [BATCH <batch-size>]";
  }

  private static Vertex toVertex(DatabaseSessionInternal db, Identifiable item) {
    if (item instanceof Entity) {
      return ((Entity) item).asVertexOrNull();
    } else {
      try {
        item = db.load(item.getIdentity());
      } catch (RecordNotFoundException rnf) {
        return null;
      }
      if (item instanceof Entity) {
        return ((Entity) item).asVertexOrNull();
      }
    }
    return null;
  }
}
