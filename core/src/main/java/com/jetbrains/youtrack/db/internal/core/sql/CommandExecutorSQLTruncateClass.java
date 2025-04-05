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
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;

/**
 * SQL TRUNCATE CLASS command: Truncates an entire class deleting all configured clusters where the
 * class relies on.
 */
public class CommandExecutorSQLTruncateClass extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_TRUNCATE = "TRUNCATE";
  public static final String KEYWORD_CLASS = "CLASS";
  public static final String KEYWORD_POLYMORPHIC = "POLYMORPHIC";
  private SchemaClassInternal schemaClass;
  private boolean unsafe = false;
  private boolean deep = false;

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLTruncateClass parse(final CommandRequest iRequest) {
    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);

      final var database = getDatabase();

      init((CommandRequestText) iRequest);

      StringBuilder word = new StringBuilder();

      int oldPos = 0;
      int pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_TRUNCATE)) {
        throw new CommandSQLParsingException(
            "Keyword " + KEYWORD_TRUNCATE + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_CLASS)) {
        throw new CommandSQLParsingException(
            "Keyword " + KEYWORD_CLASS + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserText, oldPos, word, true);
      if (pos == -1) {
        throw new CommandSQLParsingException(
            "Expected class name. Use " + getSyntax(), parserText, oldPos);
      }

      final String className = word.toString();
      schemaClass = (SchemaClassInternal) database.getMetadata().getSchema().getClass(className);

      if (schemaClass == null) {
        throw new CommandSQLParsingException(
            "Class '" + className + "' not found", parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserText, oldPos, word, true);

      while (pos > 0) {
        String nextWord = word.toString();
        if (nextWord.toUpperCase(Locale.ENGLISH).equals(KEYWORD_UNSAFE)) {
          unsafe = true;
        } else if (nextWord.toUpperCase(Locale.ENGLISH).equals(KEYWORD_POLYMORPHIC)) {
          deep = true;
        }
        oldPos = pos;
        pos = nextWord(parserText, parserText, oldPos, word, true);
      }
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  /**
   * Execute the command.
   */
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal querySession) {
    if (schemaClass == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not been parsed yet");
    }

    var database = getDatabase();
    final long recs = schemaClass.count(database, deep);
    if (recs > 0 && !unsafe) {
      if (schemaClass.isSubClassOf(querySession, "V")) {
        throw new CommandExecutionException(
            "'TRUNCATE CLASS' command cannot be used on not empty vertex classes. Apply the"
                + " 'UNSAFE' keyword to force it (at your own risk)");
      } else if (schemaClass.isSubClassOf(querySession, "E")) {
        throw new CommandExecutionException(
            "'TRUNCATE CLASS' command cannot be used on not empty edge classes. Apply the 'UNSAFE'"
                + " keyword to force it (at your own risk)");
      }
    }

    Collection<SchemaClass> subclasses = schemaClass.getAllSubclasses(querySession);
    if (deep && !unsafe) { // for multiple inheritance
      for (SchemaClass subclass : subclasses) {
        long subclassRecs = schemaClass.count(database);
        if (subclassRecs > 0) {
          if (subclass.isSubClassOf(querySession, "V")) {
            throw new CommandExecutionException(
                "'TRUNCATE CLASS' command cannot be used on not empty vertex classes ("
                    + subclass.getName()
                    + "). Apply the 'UNSAFE' keyword to force it (at your own risk)");
          } else if (subclass.isSubClassOf(querySession, "E")) {
            throw new CommandExecutionException(
                "'TRUNCATE CLASS' command cannot be used on not empty edge classes ("
                    + subclass.getName()
                    + "). Apply the 'UNSAFE' keyword to force it (at your own risk)");
          }
        }
      }
    }

    schemaClass.truncate(database);
    if (deep) {
      for (SchemaClass subclass : subclasses) {
        ((SchemaClassInternal) subclass).truncate(database);
      }
    }

    return recs;
  }

  @Override
  public long getDistributedTimeout() {
    return getDatabase()
        .getConfiguration()
        .getValueAsLong(GlobalConfiguration.DISTRIBUTED_COMMAND_TASK_SYNCH_TIMEOUT);
  }

  @Override
  public String getSyntax() {
    return "TRUNCATE CLASS <class-name>";
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.WRITE;
  }
}
