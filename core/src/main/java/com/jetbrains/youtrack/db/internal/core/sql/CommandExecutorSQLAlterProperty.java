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
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty.ATTRIBUTES;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaPropertyImpl;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLAlterPropertyStatement;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * SQL ALTER PROPERTY command: Changes an attribute of an existent property in the target class.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLAlterProperty extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_ALTER = "ALTER";
  public static final String KEYWORD_PROPERTY = "PROPERTY";

  private String className;
  private String fieldName;
  private ATTRIBUTES attribute;
  private String value;

  public CommandExecutorSQLAlterProperty parse(DatabaseSessionInternal session,
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
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_ALTER + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_PROPERTY)) {
        throw new CommandSQLParsingException(session,
            "Keyword " + KEYWORD_PROPERTY + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Expected <class>.<property>. Use " + getSyntax(), parserText, oldPos);
      }

      var parts = word.toString().split("\\.");
      if (parts.length != 2) {
        if (parts[1].startsWith("`") && parts[parts.length - 1].endsWith("`")) {
          var fullName = new StringBuilder();
          for (var i = 1; i < parts.length; i++) {
            if (i > 1) {
              fullName.append(".");
            }
            fullName.append(parts[i]);
          }
          parts = new String[]{parts[0], fullName.toString()};
        } else {
          throw new CommandSQLParsingException(session,
              "Expected <class>.<property>. Use " + getSyntax(), parserText, oldPos);
        }
      }

      className = decodeClassName(parts[0]);
      if (className == null) {
        throw new CommandSQLParsingException(session, "Class not found", parserText, oldPos);
      }
      fieldName = decodeClassName(parts[1]);

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1) {
        throw new CommandSQLParsingException(session,
            "Missing property attribute to change. Use " + getSyntax(), parserText, oldPos);
      }

      final var attributeAsString = word.toString();

      try {
        attribute = SchemaProperty.ATTRIBUTES.valueOf(
            attributeAsString.toUpperCase(Locale.ENGLISH));
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(
            new CommandSQLParsingException(session,
                "Unknown property attribute '"
                    + attributeAsString
                    + "'. Supported attributes are: "
                    + Arrays.toString(ATTRIBUTES.values()),
                parserText, oldPos),
            e, session);
      }

      value = parserText.substring(pos + 1).trim();
      if (attribute.equals(ATTRIBUTES.NAME) || attribute.equals(ATTRIBUTES.LINKEDCLASS)) {
        value = decodeClassName(value);
      }

      if (value.length() == 0) {
        throw new CommandSQLParsingException(session,
            "Missing property value to change for attribute '"
                + attribute
                + "'. Use "
                + getSyntax(),
            parserText, oldPos);
      }

      if (preParsedStatement != null) {
        var settingExp = ((SQLAlterPropertyStatement) preParsedStatement).settingValue;
        if (settingExp != null) {
          var expValue = settingExp.execute((Identifiable) null, context);
          if (expValue == null) {
            expValue = settingExp.toString();
          }
          if (expValue instanceof Date) {
            value = DateHelper.getDateTimeFormatInstance(session).format((Date) expValue);
          } else {
            value = expValue.toString();
          }

          if (attribute.equals(ATTRIBUTES.NAME) || attribute.equals(ATTRIBUTES.LINKEDCLASS)) {
            value = decodeClassName(value);
          }
        }
      } else {
        if (value.equalsIgnoreCase("null")) {
          value = null;
        }
        if (value != null && isQuoted(value)) {
          value = removeQuotes(value);
        }
      }
    } finally {
      textRequest.setText(originalQuery);
    }
    return this;
  }

  private String removeQuotes(String s) {
    s = s.trim();
    return s.substring(1, s.length() - 1).replaceAll("\\\\\"", "\"");
  }

  private boolean isQuoted(String s) {
    s = s.trim();
    if (s.startsWith("\"") && s.endsWith("\"")) {
      return true;
    }
    if (s.startsWith("'") && s.endsWith("'")) {
      return true;
    }
    return s.startsWith("`") && s.endsWith("`");
  }

  /**
   * Execute the ALTER PROPERTY.
   */
  @Nullable
  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (attribute == null) {
      throw new CommandExecutionException(session,
          "Cannot execute the command because it has not yet been parsed");
    }

    final var sourceClass =
        (SchemaClassImpl) session.getMetadata().getSchema().getClass(className);
    if (sourceClass == null) {
      throw new CommandExecutionException(session, "Source class '" + className + "' not found");
    }

    final var prop = (SchemaPropertyImpl) sourceClass.getProperty(session, fieldName);
    if (prop == null) {
      throw new CommandExecutionException(session,
          "Property '" + className + "." + fieldName + "' not exists");
    }

    if ("null".equalsIgnoreCase(value)) {
      prop.set(session, attribute, null);
    } else {
      prop.set(session, attribute, value);
    }
    return null;
  }

  public String getSyntax() {
    return "ALTER PROPERTY <class>.<property> <attribute-name> <attribute-value>";
  }
}
