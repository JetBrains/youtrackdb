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
import com.jetbrains.youtrack.db.api.record.Direction;
import com.jetbrains.youtrack.db.api.record.Edge;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.types.ModifiableBoolean;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutor;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestInternal;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * SQL DELETE EDGE command.
 */
public class CommandExecutorSQLDeleteEdge extends CommandExecutorSQLSetAware
    implements CommandDistributedReplicateRequest, CommandResultListener {

  public static final String NAME = "DELETE EDGE";
  private static final String KEYWORD_BATCH = "BATCH";
  private List<RecordId> rids;
  private String fromExpr;
  private String toExpr;
  private int removed = 0;
  private CommandRequest query;
  private SQLFilter compiledFilter;
  private String label;
  private final ModifiableBoolean shutdownFlag = new ModifiableBoolean();
  private boolean txAlreadyBegun;
  private int batch = 100;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLDeleteEdge parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;

    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      parserRequiredKeyword(session.getDatabaseName(), "DELETE");
      parserRequiredKeyword(session.getDatabaseName(), "EDGE");

      SchemaClass clazz = null;
      String where = null;

      var temp = parseOptionalWord(session.getDatabaseName(), true);
      String originalTemp = null;

      var limit = -1;

      if (temp != null && !parserIsEnded()) {
        originalTemp =
            parserText.substring(parserGetPreviousPosition(), parserGetCurrentPosition()).trim();
      }

      while (temp != null) {

        if (temp.equals("FROM")) {
          fromExpr = parserRequiredWord(false, "Syntax error", " =><,\r\n",
              session.getDatabaseName());
          if (rids != null) {
            throwSyntaxErrorException(session.getDatabaseName(),
                "FROM '" + fromExpr + "' is not allowed when specify a RIDs (" + rids + ")");
          }

        } else if (temp.equals("TO")) {
          toExpr = parserRequiredWord(false, "Syntax error", " =><,\r\n",
              session.getDatabaseName());
          if (rids != null) {
            throwSyntaxErrorException(session.getDatabaseName(),
                "TO '" + toExpr + "' is not allowed when specify a RID (" + rids + ")");
          }

        } else if (temp.startsWith("#")) {
          rids = new ArrayList<RecordId>();
          rids.add(new RecordId(temp));
          if (fromExpr != null || toExpr != null) {
            throwSyntaxErrorException(session.getDatabaseName(),
                "Specifying the RID " + rids + " is not allowed with FROM/TO");
          }

        } else if (temp.startsWith("[") && temp.endsWith("]")) {
          temp = temp.substring(1, temp.length() - 1);
          rids = new ArrayList<RecordId>();
          for (var rid : temp.split(",")) {
            rid = rid.trim();
            if (!rid.startsWith("#")) {
              throwSyntaxErrorException(session.getDatabaseName(), "Not a valid RID: " + rid);
            }
            rids.add(new RecordId(rid));
          }
        } else if (temp.equals(KEYWORD_WHERE)) {
          if (clazz == null)
          // ASSIGN DEFAULT CLASS
          {
            clazz = session.getMetadata().getImmutableSchemaSnapshot().getClass("E");
          }

          where =
              parserGetCurrentPosition() > -1
                  ? " " + parserText.substring(parserGetCurrentPosition())
                  : "";

          compiledFilter =
              SQLEngine.parseCondition(where, getContext(), KEYWORD_WHERE);
          break;

        } else if (temp.equals(KEYWORD_BATCH)) {
          temp = parserNextWord(true);
          if (temp != null) {
            batch = Integer.parseInt(temp);
          }

        } else if (temp.equals(KEYWORD_LIMIT)) {
          temp = parserNextWord(true);
          if (temp != null) {
            limit = Integer.parseInt(temp);
          }

        } else if (temp.length() > 0) {
          // GET/CHECK CLASS NAME
          label = originalTemp;
          clazz = session.getMetadata().getSchema().getClass(temp);
          if (clazz == null) {
            throw new CommandSQLParsingException(session.getDatabaseName(),
                "Class '" + temp + "' was not found");
          }
        }

        temp = parseOptionalWord(session.getDatabaseName(), true);
        if (parserIsEnded()) {
          break;
        }
      }

      if (where == null) {
        if (limit > -1) {
          where = " LIMIT " + limit;
        } else {
          where = "";
        }
      } else {
        where = " WHERE " + where;
      }

      if (fromExpr == null && toExpr == null && rids == null) {
        if (clazz == null)
        // DELETE ALL THE EDGES
        {
          throw new UnsupportedOperationException();
        } else
        // DELETE EDGES OF CLASS X
        {
          throw new UnsupportedOperationException();
        }
      }

      return this;
    } finally {
      textRequest.setText(originalQuery);
    }
  }

  /**
   * Execute the command and return the EntityImpl object created.
   */
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (fromExpr == null
        && toExpr == null
        && rids == null
        && query == null
        && compiledFilter == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }
    txAlreadyBegun = session.getTransactionInternal().isActive();

    if (rids != null) {
      // REMOVE PUNCTUAL RID
      session.begin();
      for (var rid : rids) {
        final var e = toEdge(session, rid);
        if (e != null) {
          e.delete();
          removed++;
        }
      }
      session.commit();
      return removed;
    } else {
      // MULTIPLE EDGES
      final Set<Edge> edges = new HashSet<Edge>();
      if (query == null) {
        session.begin();
        Set<Identifiable> fromIds = null;
        if (fromExpr != null) {
          fromIds = SQLEngine.parseRIDTarget(session, fromExpr, context, iArgs);
        }
        Set<Identifiable> toIds = null;
        if (toExpr != null) {
          toIds = SQLEngine.parseRIDTarget(session, toExpr, context, iArgs);
        }
        if (label == null) {
          label = "E";
        }

        if (fromIds != null && toIds != null) {
          var fromCount = 0;
          var toCount = 0;
          for (var fromId : fromIds) {
            final var v = toVertex(session, fromId);
            if (v != null) {
              fromCount += count(v.getEdges(Direction.OUT, label));
            }
          }
          for (var toId : toIds) {
            final var v = toVertex(session, toId);
            if (v != null) {
              toCount += count(v.getEdges(Direction.IN, label));
            }
          }
          if (fromCount <= toCount) {
            // REMOVE ALL THE EDGES BETWEEN VERTICES
            for (var fromId : fromIds) {
              final var v = toVertex(session, fromId);
              if (v != null) {
                for (var e : v.getEdges(Direction.OUT, label)) {
                  final Identifiable inV = e.getTo();
                  if (inV != null && toIds.contains(inV.getIdentity())) {
                    edges.add(e);
                  }
                }
              }
            }
          } else {
            for (var toId : toIds) {
              final var v = toVertex(session, toId);
              if (v != null) {
                for (var e : v.getEdges(Direction.IN, label)) {
                  final var outVRid = e.getFromLink().getIdentity();
                  if (outVRid != null && fromIds.contains(outVRid)) {
                    edges.add(e);
                  }
                }
              }
            }
          }
        } else if (fromIds != null) {
          // REMOVE ALL THE EDGES THAT START FROM A VERTEXES
          for (var fromId : fromIds) {

            final var v = toVertex(session, fromId);
            if (v != null) {
              for (var e : v.getEdges(Direction.OUT, label)) {
                edges.add(e);
              }
            }
          }
        } else if (toIds != null) {
          // REMOVE ALL THE EDGES THAT ARRIVE TO A VERTEXES
          for (var toId : toIds) {
            final var v = toVertex(session, toId);
            if (v != null) {
              for (var e : v.getEdges(Direction.IN, label)) {
                edges.add(e);
              }
            }
          }
        } else {
          throw new CommandExecutionException(session, "Invalid target: " + toIds);
        }

        if (compiledFilter != null) {
          // ADDITIONAL FILTERING
          edges.removeIf(
              edge -> edge.isStateful() && !(Boolean) compiledFilter.evaluate(
                  edge.asStatefulEdge(), null, context));
        }

        // DELETE THE FOUND EDGES
        removed = edges.size();
        for (var edge : edges) {
          edge.delete();
        }

        session.commit();
        return removed;

      } else {
        session.begin();
        // TARGET IS A CLASS + OPTIONAL CONDITION
        query.setContext(getContext());
        query.execute(session, iArgs);
        session.commit();
        return removed;
      }
    }
  }

  private int count(Iterable<Edge> edges) {
    var result = 0;
    for (var x : edges) {
      result++;
    }
    return result;
  }

  /**
   * Delete the current edge.
   */
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    final var id = (Identifiable) iRecord;

    if (compiledFilter != null) {
      // ADDITIONAL FILTERING
      var transaction = session.getActiveTransaction();
      if (!(Boolean) compiledFilter.evaluate(transaction.load(id), null, context)) {
        return true;
      }
    }

    if (((RecordId) id.getIdentity()).isValidPosition()) {

      final var e = toEdge(session, id);

      if (e != null) {
        e.delete();

        if (!txAlreadyBegun && batch > 0 && (removed + 1) % batch == 0) {
          session.commit();
          session.begin();
        }

        removed++;
      }
    }

    return true;
  }

  private Edge toEdge(DatabaseSessionInternal session, Identifiable item) {
    if (item instanceof Entity) {
      return ((Entity) item).asStatefulEdge();
    } else {
      try {
        item = session.load(item.getIdentity());
      } catch (RecordNotFoundException rnf) {
        return null;
      }

      if (item instanceof Entity) {
        final var a = item;
        return ((Entity) item).asStatefulEdge();
      }
    }
    return null;
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

  @Override
  public String getSyntax() {
    return "DELETE EDGE <rid>|FROM <rid>|TO <rid>|<[<class>] [WHERE <conditions>]> [BATCH"
        + " <batch-size>]";
  }

  @Override
  public void end(@Nonnull DatabaseSessionInternal session) {
  }

  @Override
  public int getSecurityOperationType() {
    return Role.PERMISSION_DELETE;
  }

  @Override
  public Object getResult() {
    return null;
  }

  @Override
  public Set<String> getInvolvedClusters(DatabaseSessionInternal session) {
    final var result = new HashSet<String>();
    if (rids != null) {
      for (var rid : rids) {
        result.add(session.getClusterNameById(rid.getClusterId()));
      }
    } else if (query != null) {

      final var executor =
          session
              .getSharedContext()
              .getYouTrackDB()
              .getScriptManager()
              .getCommandManager()
              .getExecutor((CommandRequestInternal) query);
      // COPY THE CONTEXT FROM THE REQUEST
      executor.setContext(context);
      executor.parse(session, query);
      return executor.getInvolvedClusters(session);
    }
    return result;
  }

  /**
   * setLimit() for DELETE EDGE is ignored. Please use LIMIT keyword in the SQL statement
   */
  public <RET extends CommandExecutor> RET setLimit(final int iLimit) {
    // do nothing
    return (RET) this;
  }
}
