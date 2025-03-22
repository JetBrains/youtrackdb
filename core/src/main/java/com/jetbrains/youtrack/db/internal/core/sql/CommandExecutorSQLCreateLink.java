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

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.StringSerializerHelper;
import java.util.Locale;
import java.util.Map;

/**
 * SQL CREATE LINK command: Transform a JOIN relationship to a physical LINK
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLCreateLink extends CommandExecutorSQLAbstract {

  public static final String KEYWORD_CREATE = "CREATE";
  public static final String KEYWORD_LINK = "LINK";
  private static final String KEYWORD_FROM = "FROM";
  private static final String KEYWORD_TO = "TO";
  private static final String KEYWORD_TYPE = "TYPE";

  private String destClassName;
  private String destField;
  private String sourceClassName;
  private String sourceField;
  private String linkName;
  private PropertyTypeInternal linkType;
  private boolean inverse = false;

  public CommandExecutorSQLCreateLink parse(DatabaseSessionInternal session,
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
      if (pos == -1 || !word.toString().equals(KEYWORD_CREATE)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_CREATE + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_LINK)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_LINK + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_FROM + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      if (!word.toString().equalsIgnoreCase(KEYWORD_FROM)) {
        // GET THE LINK NAME
        linkName = word.toString();

        if (StringSerializerHelper.contains(linkName, ' ')) {
          throw new CommandSQLParsingException(session,
              "Link name '" + linkName + "' contains not valid characters", parserText, oldPos);
        }

        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      }

      if (word.toString().equalsIgnoreCase(KEYWORD_TYPE)) {
        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, pos, word, true);

        if (pos == -1) {
          throw new CommandSQLParsingException(session,
              "Link type missed. Use " + getSyntax(), parserText, oldPos);
        }

        linkType = PropertyTypeInternal.valueOf(word.toString().toUpperCase(Locale.ENGLISH));

        oldPos = pos;
        pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      }

      if (pos == -1 || !word.toString().equals(KEYWORD_FROM)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_FROM + " not found. Use " + getSyntax(), parserText, oldPos);
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

      sourceClassName = parts[0];
      if (sourceClassName == null) {
        throw new CommandSQLParsingException(session, "Class not found", parserText, pos);
      }
      sourceField = parts[1];

      pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_TO)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_TO + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      parts = word.toString().split("\\.");
      if (parts.length != 2) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, pos);
      }

      destClassName = parts[0];
      if (destClassName == null) {
        throw new CommandSQLParsingException(session, "Class not found", parserText, pos);
      }
      destField = parts[1];

      pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
      if (pos == -1) {
        return this;
      }

      if (!word.toString().equalsIgnoreCase("INVERSE")) {
        throw new CommandSQLParsingException(session,
            "Missed 'INVERSE'. Use " + getSyntax(), parserText, pos);
      }

      inverse = true;
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  @Override
  public Object execute(DatabaseSessionInternal session, Map<Object, Object> iArgs) {
    return null;
  }
}