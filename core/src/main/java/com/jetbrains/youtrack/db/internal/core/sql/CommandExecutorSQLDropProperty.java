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

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.internal.common.comparator.CaseInsentiveComparator;
import com.jetbrains.youtrack.db.internal.common.util.Collections;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL CREATE PROPERTY command: Creates a new property in the target class.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLDropProperty extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_DROP = "DROP";
  public static final String KEYWORD_PROPERTY = "PROPERTY";

  private String className;
  private String fieldName;
  private boolean ifExists;
  private boolean force = false;

  public CommandExecutorSQLDropProperty parse(final CommandRequest iRequest) {
    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);

      init((CommandRequestText) iRequest);

      final StringBuilder word = new StringBuilder();

      int oldPos = 0;
      int pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_DROP)) {
        throw new CommandSQLParsingException(
            "Keyword " + KEYWORD_DROP + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_PROPERTY)) {
        throw new CommandSQLParsingException(
            "Keyword " + KEYWORD_PROPERTY + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      String[] parts = word.toString().split("\\.");
      if (parts.length != 2) {
        throw new CommandSQLParsingException(
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      className = decodeClassName(parts[0]);
      if (className == null) {
        throw new CommandSQLParsingException("Class not found", parserText, pos);
      }
      fieldName = decodeClassName(parts[1]);

      pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
      if (pos != -1) {
        final String forceParameter = word.toString();
        if ("FORCE".equals(forceParameter)) {
          force = true;
        } else if ("IF".contentEquals(word)) {
          pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
          if ("EXISTS".contentEquals(word)) {
            this.ifExists = true;
          } else {
            throw new CommandSQLParsingException(
                "Wrong query parameter, expecting EXISTS after IF", parserText, pos);
          }
        } else {
          throw new CommandSQLParsingException("Wrong query parameter", parserText, pos);
        }
      }
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  /**
   * Execute the CREATE PROPERTY.
   */
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal querySession) {
    if (fieldName == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not yet been parsed");
    }

    final var database = getDatabase();
    var sourceClass =
        (SchemaClassImpl) database.getMetadata().getSchemaInternal().getClassInternal(className);
    if (sourceClass == null) {
      throw new CommandExecutionException("Source class '" + className + "' not found");
    }

    if (ifExists && !sourceClass.existsProperty(querySession, fieldName)) {
      return null;
    }

    final List<Index> indexes = relatedIndexes(fieldName);
    if (!indexes.isEmpty()) {
      if (force) {
        dropRelatedIndexes(indexes);
      } else {
        final StringBuilder indexNames = new StringBuilder();

        boolean first = true;
        for (final Index index : sourceClass.getClassInvolvedIndexesInternal(database, fieldName)) {
          if (!first) {
            indexNames.append(", ");
          } else {
            first = false;
          }
          indexNames.append(index.getName());
        }

        throw new CommandExecutionException(
            "Property used in indexes ("
                + indexNames
                + "). Please drop these indexes before removing property or use FORCE parameter.");
      }
    }

    // REMOVE THE PROPERTY
    sourceClass.dropProperty(database, fieldName);

    return null;
  }

  @Override
  public long getDistributedTimeout() {
    return getDatabase()
        .getConfiguration()
        .getValueAsLong(GlobalConfiguration.DISTRIBUTED_COMMAND_TASK_SYNCH_TIMEOUT);
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.ALL;
  }

  private void dropRelatedIndexes(final List<Index> indexes) {
    var database = getDatabase();
    for (final Index index : indexes) {
      database.command("DROP INDEX " + index.getName()).close();
    }
  }

  private List<Index> relatedIndexes(final String fieldName) {
    final List<Index> result = new ArrayList<Index>();

    final DatabaseSessionInternal database = getDatabase();
    for (final Index index :
        database.getMetadata().getIndexManagerInternal().getClassIndexes(database, className)) {
      if (Collections.indexOf(
          index.getDefinition().getFields(), fieldName, new CaseInsentiveComparator())
          > -1) {
        result.add(index);
      }
    }

    return result;
  }

  @Override
  public String getSyntax() {
    return "DROP PROPERTY <class>.<property> [ IF EXISTS ] [FORCE]";
  }
}
