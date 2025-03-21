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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.DBRecord;
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
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import com.jetbrains.youtrack.db.internal.core.sql.query.SQLAsynchQuery;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;

/**
 * SQL DELETE VERTEX command.
 */
public class CommandExecutorSQLDeleteVertex extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest, CommandResultListener {

  public static final String NAME = "DELETE VERTEX";
  private static final String KEYWORD_BATCH = "BATCH";
  private RecordId rid;
  private int removed = 0;
  private DatabaseSessionInternal database;
  private CommandRequest query;
  private String returning = "COUNT";
  private List<DBRecord> allDeletedRecords;
  private final ModifiableBoolean shutdownFlag = new ModifiableBoolean();
  private boolean txAlreadyBegun;
  private int batch = 100;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLDeleteVertex parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      parserRequiredKeyword(session.getDatabaseName(), "DELETE");
      parserRequiredKeyword(session.getDatabaseName(), "VERTEX");

      SchemaClass clazz = null;
      String where = null;

      var limit = -1;
      var word = parseOptionalWord(session.getDatabaseName(), true);
      while (word != null) {

        if (word.startsWith("#")) {
          rid = new RecordId(word);

        } else if (word.equalsIgnoreCase("from")) {
          final var q = new StringBuilder();
          final var newPos =
              StringSerializerHelper.getEmbedded(parserText, parserGetCurrentPosition(), -1, q);

          query = database.command(new SQLAsynchQuery<EntityImpl>(q.toString(), this));

          parserSetCurrentPosition(newPos);

        } else if (word.equals(KEYWORD_WHERE)) {
          if (clazz == null)
          // ASSIGN DEFAULT CLASS
          {
            clazz = database.getMetadata().getImmutableSchemaSnapshot().getClass("V");
          }

          where =
              parserGetCurrentPosition() > -1
                  ? " " + parserText.substring(parserGetPreviousPosition())
                  : "";
          query =
              database.command(
                  new SQLAsynchQuery<EntityImpl>(
                      "select from `" + clazz.getName() + "`" + where, this));
          break;

        } else if (word.equals(KEYWORD_LIMIT)) {
          word = parseOptionalWord(session.getDatabaseName(), true);
          try {
            limit = Integer.parseInt(word);
          } catch (Exception e) {
            throw BaseException.wrapException(
                new CommandSQLParsingException(session.getDatabaseName(), "Invalid LIMIT: " + word),
                e,
                session);
          }
        } else if (word.equals(KEYWORD_RETURN)) {
          returning = parseReturn();

        } else if (word.equals(KEYWORD_BATCH)) {
          word = parserNextWord(true);
          if (word != null) {
            batch = Integer.parseInt(word);
          }

        } else if (word.length() > 0) {
          // GET/CHECK CLASS NAME
          clazz = database.getMetadata().getImmutableSchemaSnapshot().getClass(word);
          if (clazz == null) {
            throw new CommandSQLParsingException(session.getDatabaseName(),
                "Class '" + word + "' was not found");
          }
        }

        word = parseOptionalWord(session.getDatabaseName(), true);
        if (parserIsEnded()) {
          break;
        }
      }

      if (where == null) {
        where = "";
      } else {
        where = " WHERE " + where;
      }

      if (query == null && rid == null) {
        var queryString = new StringBuilder();
        queryString.append("select from `");
        if (clazz == null) {
          queryString.append("V");
        } else {
          queryString.append(clazz.getName());
        }
        queryString.append("`");

        queryString.append(where);
        if (limit > -1) {
          queryString.append(" LIMIT ").append(limit);
        }
        query = database.command(new SQLAsynchQuery<EntityImpl>(queryString.toString(), this));
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
    if (rid == null && query == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not been parsed yet");
    }

    if (!returning.equalsIgnoreCase("COUNT")) {
      allDeletedRecords = new ArrayList<DBRecord>();
    }

    txAlreadyBegun = session.getTransactionInternal().isActive();

    if (rid != null) {
      // REMOVE PUNCTUAL RID
      session.begin();
      final var v = toVertex(session, rid);
      if (v != null) {
        v.delete();
        removed = 1;
      }
      session.commit();

    } else if (query != null) {
      // TARGET IS A CLASS + OPTIONAL CONDITION
      session.begin();
      // TARGET IS A CLASS + OPTIONAL CONDITION

      query.setContext(getContext());
      query.execute(session, iArgs);
      session.commit();

    } else {
      throw new CommandExecutionException(session, "Invalid target");
    }

    if (returning.equalsIgnoreCase("COUNT"))
    // RETURNS ONLY THE COUNT
    {
      return removed;
    } else
    // RETURNS ALL THE DELETED RECORDS
    {
      return allDeletedRecords;
    }
  }

  /**
   * Delete the current vertex.
   */
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    final var id = (Identifiable) iRecord;
    if (((RecordId) id.getIdentity()).isValidPosition()) {
      var transaction = session.getActiveTransaction();
      final EntityImpl record = transaction.load(id);
      final var v = toVertex(session, record);
      if (v != null) {
        v.delete();

        if (!txAlreadyBegun && batch > 0 && removed % batch == 0) {
          session.commit();
          session.begin();
        }

        if (returning.equalsIgnoreCase("BEFORE")) {
          allDeletedRecords.add(record);
        }

        removed++;
      }
    }

    return true;
  }


  @Override
  public String getSyntax() {
    return "DELETE VERTEX <rid>|<class>|FROM <query> [WHERE <conditions>] [LIMIT <max-records>]"
        + " [RETURN <COUNT|BEFORE>]> [BATCH <batch-size>]";
  }

  @Override
  public void end(@Nonnull DatabaseSessionInternal session) {
    if (!txAlreadyBegun) {
      session.commit();
    }
  }

  @Override
  public int getSecurityOperationType() {
    return Role.PERMISSION_DELETE;
  }

  /**
   * Parses the returning keyword if found.
   */
  protected String parseReturn() throws CommandSQLParsingException {
    final var returning = parserNextWord(true);

    if (!returning.equalsIgnoreCase("COUNT") && !returning.equalsIgnoreCase("BEFORE")) {
      throwParsingException(null,
          "Invalid "
              + KEYWORD_RETURN
              + " value set to '"
              + returning
              + "' but it should be COUNT (default), BEFORE. Example: "
              + KEYWORD_RETURN
              + " BEFORE");
    }

    return returning;
  }

  @Override
  public Set<String> getInvolvedClusters(DatabaseSessionInternal session) {
    final var result = new HashSet<String>();
    if (rid != null) {
      result.add(database.getClusterNameById(rid.getClusterId()));
    } else if (query != null) {
      final var executor =
          database
              .getSharedContext()
              .getYouTrackDB()
              .getScriptManager()
              .getCommandManager()
              .getExecutor((CommandRequestInternal) query);
      // COPY THE CONTEXT FROM THE REQUEST
      executor.setContext(context);
      executor.parse(database, query);
      return executor.getInvolvedClusters(session);
    }
    return result;
  }

  @Override
  public Object getResult() {
    return null;
  }

  /**
   * setLimit() for DELETE VERTEX is ignored. Please use LIMIT keyword in the SQL statement
   */
  public <RET extends CommandExecutor> RET setLimit(final int iLimit) {
    // do nothing
    return (RET) this;
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
