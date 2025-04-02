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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseSession.ATTRIBUTES;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Rule;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * SQL ALTER DATABASE command: Changes an attribute of the current database.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLAlterDatabase extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_ALTER = "ALTER";
  public static final String KEYWORD_DATABASE = "DATABASE";

  private ATTRIBUTES attribute;
  private String value;

  public CommandExecutorSQLAlterDatabase parse(DatabaseSessionInternal session,
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
      if (pos == -1 || !word.toString().equals(KEYWORD_ALTER)) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Keyword " + KEYWORD_ALTER + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_DATABASE)) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Keyword " + KEYWORD_DATABASE + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Missed the database's attribute to change. Use " + getSyntax(), parserText, oldPos);
      }

      final var attributeAsString = word.toString();

      try {
        attribute =
            DatabaseSession.ATTRIBUTES.valueOf(attributeAsString.toUpperCase(Locale.ENGLISH));
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(
            new CommandSQLParsingException(session.getDatabaseName(),
                "Unknown database's attribute '"
                    + attributeAsString
                    + "'. Supported attributes are: "
                    + Arrays.toString(ATTRIBUTES.values()),
                parserText, oldPos),
            e, session.getDatabaseName());
      }

      value = parserText.substring(pos + 1).trim();

      if (value.isEmpty()) {
        throw new CommandSQLParsingException(session.getDatabaseName(),
            "Missed the database's value to change for attribute '"
                + attribute
                + "'. Use "
                + getSyntax(),
            parserText, oldPos);
      }

      if (value.equalsIgnoreCase("null")) {
        value = null;
      }
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  /**
   * Execute the ALTER DATABASE.
   */
  @Nullable
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (attribute == null) {
      throw new CommandExecutionException(session.getDatabaseName(),
          "Cannot execute the command because it has not been parsed yet");
    }
    session.checkSecurity(Rule.ResourceGeneric.DATABASE, Role.PERMISSION_UPDATE);
    session.setInternal(attribute, value);
    return null;
  }

  public String getSyntax() {
    return "ALTER DATABASE <attribute-name> <attribute-value>";
  }
}
