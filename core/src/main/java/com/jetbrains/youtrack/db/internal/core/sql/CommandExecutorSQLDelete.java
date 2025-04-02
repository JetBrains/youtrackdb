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
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.parser.StringParser;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.command.CommandResultListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilter;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLDeleteStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * SQL UPDATE command.
 */
public class CommandExecutorSQLDelete extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest, CommandResultListener {

  public static final String NAME = "DELETE FROM";
  public static final String KEYWORD_DELETE = "DELETE";
  private static final String VALUE_NOT_FOUND = "_not_found_";

  private String indexName = null;
  private int recordCount = 0;
  private String returning = "COUNT";
  private List<DBRecord> allDeletedRecords;

  private SQLFilter compiledFilter;
  private boolean unsafe = false;

  public CommandExecutorSQLDelete() {
  }

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLDelete parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      recordCount = 0;

      if (parserTextUpperCase.endsWith(KEYWORD_UNSAFE)) {
        unsafe = true;
        parserText = parserText.substring(0, parserText.length() - KEYWORD_UNSAFE.length() - 1);
        parserTextUpperCase =
            parserTextUpperCase.substring(
                0, parserTextUpperCase.length() - KEYWORD_UNSAFE.length() - 1);
      }

      parserRequiredKeyword(session.getDatabaseName(), CommandExecutorSQLDelete.KEYWORD_DELETE);
      parserRequiredKeyword(session.getDatabaseName(), CommandExecutorSQLDelete.KEYWORD_FROM);

      var subjectName = parserRequiredWord(false, "Syntax error", " =><,\r\n",
          session.getDatabaseName());
      if (subjectName == null) {
        throwSyntaxErrorException(session.getDatabaseName(),
            "Invalid subject name. Expected collection, class, index or sub-query");
      }

      if (StringParser.startsWithIgnoreCase(
          subjectName, CommandExecutorSQLAbstract.INDEX_PREFIX)) {
        // INDEX
        indexName = subjectName.substring(CommandExecutorSQLAbstract.INDEX_PREFIX.length());

        if (!parserIsEnded()) {
          while (!parserIsEnded()) {
            final var word = parserGetLastWord();

            if (word.equals(KEYWORD_RETURN)) {
              returning = parseReturn();
            } else if (word.equals(KEYWORD_UNSAFE)) {
              unsafe = true;
            } else if (word.equalsIgnoreCase(KEYWORD_WHERE)) {
              compiledFilter =
                  SQLEngine
                      .parseCondition(
                          parserText.substring(parserGetCurrentPosition()),
                          getContext(),
                          KEYWORD_WHERE);
            }

            parserNextWord(true);
          }

        } else {
          parserSetCurrentPosition(-1);
        }

      } else if (subjectName.startsWith("(")) {
        subjectName = subjectName.trim();

        throw new UnsupportedOperationException();
      } else {
        parserNextWord(true);

        while (!parserIsEnded()) {
          final var word = parserGetLastWord();

          if (word.equals(KEYWORD_RETURN)) {
            returning = parseReturn();
          } else {
            parserGoBack();
            break;
          }

          parserNextWord(true);
        }

        final var condition =
            parserGetCurrentPosition() > -1
                ? " " + parserText.substring(parserGetCurrentPosition())
                : "";
        throw new UnsupportedOperationException();
      }
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  private String getSelectTarget(String subjectName) {
    if (preParsedStatement == null) {
      return subjectName;
    }
    return ((SQLDeleteStatement) preParsedStatement).fromClause.toString();
  }

  @Nullable
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    return null;
  }


  /**
   * Deletes the current record.
   */
  public boolean result(@Nonnull DatabaseSessionInternal session, final Object iRecord) {
    var transaction = session.getActiveTransaction();
    final RecordAbstract record = transaction.load(((Identifiable) iRecord));

    if (record instanceof EntityImpl entity
        && compiledFilter != null
        && !Boolean.TRUE.equals(
        this.compiledFilter.evaluate(entity, entity, getContext()))) {
      return true;
    }
    if (record.getIdentity().isValidPosition()) {
      if (returning.equalsIgnoreCase("BEFORE")) {
        allDeletedRecords.add(record);
      }

      // RESET VERSION TO DISABLE MVCC AVOIDING THE CONCURRENT EXCEPTION IF LOCAL CACHE IS NOT
      // UPDATED
      //        RecordInternal.setVersion(record, -1);

      if (!unsafe && record instanceof EntityImpl) {
        // CHECK IF ARE VERTICES OR EDGES
        SchemaImmutableClass result = null;
        if (record != null) {
          result = ((EntityImpl) record).getImmutableSchemaClass(session);
        }
        final SchemaClass cls = result;
        if (cls != null) {
          if (cls.isSubClassOf("V"))
          // FOUND VERTEX
          {
            throw new CommandExecutionException(session,
                "'DELETE' command cannot delete vertices. Use 'DELETE VERTEX' command instead, or"
                    + " apply the 'UNSAFE' keyword to force it");
          } else if (cls.isSubClassOf("E"))
          // FOUND EDGE
          {
            throw new CommandExecutionException(session,
                "'DELETE' command cannot delete edges. Use 'DELETE EDGE' command instead, or"
                    + " apply the 'UNSAFE' keyword to force it");
          }
        }
      }

      record.delete();

      recordCount++;
      return true;
    }
    return false;
  }

  public String getSyntax() {
    return "DELETE FROM <Class>|RID|collection:<collection> [UNSAFE] [LOCK <NONE|RECORD>] [RETURN"
        + " <COUNT|BEFORE>] [WHERE <condition>*]";
  }

  @Override
  public void end(@Nonnull DatabaseSessionInternal session) {
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

  private Object getIndexKey(
      DatabaseSessionInternal session, final IndexDefinition indexDefinition, Object value) {
    if (indexDefinition instanceof CompositeIndexDefinition) {
      if (value instanceof List<?> values) {
        List<Object> keyParams = new ArrayList<Object>(values.size());

        for (var o : values) {
          keyParams.add(SQLHelper.getValue(o));
        }
        return indexDefinition.createValue(session, keyParams);
      } else {
        value = SQLHelper.getValue(value);
        if (value instanceof CompositeKey) {
          return value;
        } else {
          return indexDefinition.createValue(session, value);
        }
      }
    } else {
      return SQLHelper.getValue(value);
    }
  }

  @Override
  public Object getResult() {
    return null;
  }
}
