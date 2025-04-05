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
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrack.db.internal.core.util.DateHelper;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

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

  public CommandExecutorSQLAlterProperty parse(final CommandRequest iRequest) {
    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);

      init((CommandRequestText) iRequest);

      StringBuilder word = new StringBuilder();

      int oldPos = 0;
      int pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_ALTER)) {
        throw new CommandSQLParsingException(
            "Keyword " + KEYWORD_ALTER + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_PROPERTY)) {
        throw new CommandSQLParsingException(
            "Keyword " + KEYWORD_PROPERTY + " not found. Use " + getSyntax(), parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException(
            "Expected <class>.<property>. Use " + getSyntax(), parserText, oldPos);
      }

      String[] parts = word.toString().split("\\.");
      if (parts.length != 2) {
        if (parts[1].startsWith("`") && parts[parts.length - 1].endsWith("`")) {
          StringBuilder fullName = new StringBuilder();
          for (int i = 1; i < parts.length; i++) {
            if (i > 1) {
              fullName.append(".");
            }
            fullName.append(parts[i]);
          }
          parts = new String[]{parts[0], fullName.toString()};
        } else {
          throw new CommandSQLParsingException(
              "Expected <class>.<property>. Use " + getSyntax(), parserText, oldPos);
        }
      }

      className = decodeClassName(parts[0]);
      if (className == null) {
        throw new CommandSQLParsingException("Class not found", parserText, oldPos);
      }
      fieldName = decodeClassName(parts[1]);

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1) {
        throw new CommandSQLParsingException(
            "Missing property attribute to change. Use " + getSyntax(), parserText, oldPos);
      }

      final String attributeAsString = word.toString();

      try {
        attribute = SchemaProperty.ATTRIBUTES.valueOf(
            attributeAsString.toUpperCase(Locale.ENGLISH));
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(
            new CommandSQLParsingException(
                "Unknown property attribute '"
                    + attributeAsString
                    + "'. Supported attributes are: "
                    + Arrays.toString(SchemaProperty.ATTRIBUTES.values()),
                parserText,
                oldPos),
            e);
      }

      value = parserText.substring(pos + 1).trim();
      if (attribute.equals(ATTRIBUTES.NAME) || attribute.equals(ATTRIBUTES.LINKEDCLASS)) {
        value = decodeClassName(value);
      }

      if (value.length() == 0) {
        throw new CommandSQLParsingException(
            "Missing property value to change for attribute '"
                + attribute
                + "'. Use "
                + getSyntax(),
            parserText,
            oldPos);
      }

      if (preParsedStatement != null) {
        SQLExpression settingExp = ((SQLAlterPropertyStatement) preParsedStatement).settingValue;
        if (settingExp != null) {
          Object expValue = settingExp.execute((Identifiable) null, context);
          if (expValue == null) {
            expValue = settingExp.toString();
          }
          if (expValue instanceof Date) {
            value = DateHelper.getDateTimeFormatInstance().format((Date) expValue);
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

  @Override
  public long getDistributedTimeout() {
    return getDatabase()
        .getConfiguration()
        .getValueAsLong(GlobalConfiguration.DISTRIBUTED_COMMAND_QUICK_TASK_SYNCH_TIMEOUT);
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.ALL;
  }

  /**
   * Execute the ALTER PROPERTY.
   */
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal session) {
    if (attribute == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not yet been parsed");
    }

    var db = getDatabase();
    final SchemaClassImpl sourceClass =
        (SchemaClassImpl) db.getMetadata().getSchema().getClass(className);
    if (sourceClass == null) {
      throw new CommandExecutionException("Source class '" + className + "' not found");
    }

    final SchemaPropertyImpl prop = (SchemaPropertyImpl) sourceClass.getProperty(session, fieldName);
    if (prop == null) {
      throw new CommandExecutionException(
          "Property '" + className + "." + fieldName + "' not exists");
    }

    if ("null".equalsIgnoreCase(value)) {
      prop.set(db, attribute, null);
    } else {
      prop.set(db, attribute, value);
    }
    return null;
  }

  public String getSyntax() {
    return "ALTER PROPERTY <class>.<property> <attribute-name> <attribute-value>";
  }
}
