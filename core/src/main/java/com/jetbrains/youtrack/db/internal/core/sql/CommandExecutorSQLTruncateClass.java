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
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import java.util.Locale;
import java.util.Map;

/**
 * SQL TRUNCATE CLASS command: Truncates an entire class deleting all configured collections where the
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
  public CommandExecutorSQLTruncateClass parse(DatabaseSessionInternal session,
      final CommandRequest iRequest) {
    final var textRequest = (CommandRequestText) iRequest;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iRequest);
      textRequest.setText(queryText);

      init(session, (CommandRequestText) iRequest);

      var word = new StringBuilder();

      var oldPos = 0;
      var pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_TRUNCATE)) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Keyword " + KEYWORD_TRUNCATE + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_CLASS)) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Keyword " + KEYWORD_CLASS + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserText, oldPos, word, true);
      if (pos == -1) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Expected class name. Use " + getSyntax(), parserText, oldPos);
      }

      final var className = word.toString();
      schemaClass = (SchemaClassInternal) session.getMetadata().getSchema().getClass(className);

      if (schemaClass == null) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Class '" + className + "' not found", parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserText, oldPos, word, true);

      while (pos > 0) {
        var nextWord = word.toString();
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
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (schemaClass == null) {
      throw new CommandExecutionException(session.getDatabaseName(),
          "Cannot execute the command because it has not been parsed yet");
    }

    final var recs = schemaClass.count(session, deep);
    if (recs > 0 && !unsafe) {
      if (schemaClass.isSubClassOf("V")) {
        throw new CommandExecutionException(session.getDatabaseName(),
            "'TRUNCATE CLASS' command cannot be used on not empty vertex classes. Apply the"
                + " 'UNSAFE' keyword to force it (at your own risk)");
      } else if (schemaClass.isSubClassOf("E")) {
        throw new CommandExecutionException(session.getDatabaseName(),
            "'TRUNCATE CLASS' command cannot be used on not empty edge classes. Apply the 'UNSAFE'"
                + " keyword to force it (at your own risk)");
      }
    }

    var subclasses = schemaClass.getAllSubclasses();
    if (deep && !unsafe) { // for multiple inheritance
      for (var subclass : subclasses) {
        var subclassRecs = schemaClass.count(session);
        if (subclassRecs > 0) {
          if (subclass.isSubClassOf("V")) {
            throw new CommandExecutionException(session.getDatabaseName(),
                "'TRUNCATE CLASS' command cannot be used on not empty vertex classes ("
                    + subclass.getName()
                    + "). Apply the 'UNSAFE' keyword to force it (at your own risk)");
          } else if (subclass.isSubClassOf("E")) {
            throw new CommandExecutionException(session.getDatabaseName(),
                "'TRUNCATE CLASS' command cannot be used on not empty edge classes ("
                    + subclass.getName()
                    + "). Apply the 'UNSAFE' keyword to force it (at your own risk)");
          }
        }
      }
    }

    schemaClass.truncate();
    if (deep) {
      for (var subclass : subclasses) {
        ((SchemaClassInternal) subclass).truncate();
      }
    }

    return recs;
  }


  @Override
  public String getSyntax() {
    return "TRUNCATE CLASS <class-name>";
  }
}
