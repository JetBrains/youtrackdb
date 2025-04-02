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
import javax.annotation.Nullable;

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

  public CommandExecutorSQLDropProperty parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      final var word = new StringBuilder();

      var oldPos = 0;
      var pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_DROP)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_DROP + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_PROPERTY)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_PROPERTY + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      var parts = word.toString().split("\\.");
      if (parts.length != 2) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      className = decodeClassName(parts[0]);
      if (className == null) {
        throw new CommandSQLParsingException(session, "Class not found", parserText, pos);
      }
      fieldName = decodeClassName(parts[1]);

      pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
      if (pos != -1) {
        final var forceParameter = word.toString();
        if ("FORCE".equals(forceParameter)) {
          force = true;
        } else if ("IF".contentEquals(word)) {
          pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
          if ("EXISTS".contentEquals(word)) {
            this.ifExists = true;
          } else {
            throw new CommandSQLParsingException(session,
                "Wrong query parameter, expecting EXISTS after IF", parserText, pos);
          }
        } else {
          throw new CommandSQLParsingException(session, "Wrong query parameter", parserText, pos);
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
  @Nullable
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (fieldName == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not yet been parsed");
    }

    var sourceClass =
        (SchemaClassImpl) session.getMetadata().getSchemaInternal().getClassInternal(className);
    if (sourceClass == null) {
      throw new CommandExecutionException(session, "Source class '" + className + "' not found");
    }

    if (ifExists && !sourceClass.existsProperty(session, fieldName)) {
      return null;
    }

    final var indexes = relatedIndexes(session, fieldName);
    if (!indexes.isEmpty()) {
      if (force) {
        dropRelatedIndexes(indexes, session);
      } else {
        final var indexNames = new StringBuilder();

        var first = true;
        for (final var index : sourceClass.getClassInvolvedIndexesInternal(session, fieldName)) {
          if (!first) {
            indexNames.append(", ");
          } else {
            first = false;
          }
          indexNames.append(index.getName());
        }

        throw new CommandExecutionException(session,
            "Property used in indexes ("
                + indexNames
                + "). Please drop these indexes before removing property or use FORCE parameter.");
      }
    }

    // REMOVE THE PROPERTY
    sourceClass.dropProperty(session, fieldName);

    return null;
  }

  private static void dropRelatedIndexes(final List<Index> indexes, DatabaseSessionInternal db) {
    for (final var index : indexes) {
      db.execute("DROP INDEX " + index.getName()).close();
    }
  }

  private List<Index> relatedIndexes(DatabaseSessionInternal db, final String fieldName) {
    final List<Index> result = new ArrayList<Index>();
    for (final var index :
        db.getMetadata().getIndexManagerInternal().getClassIndexes(db, className)) {
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
