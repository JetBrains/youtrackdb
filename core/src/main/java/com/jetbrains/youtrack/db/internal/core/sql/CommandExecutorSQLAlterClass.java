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
import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaClass.ATTRIBUTES;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassImpl;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLAlterClassStatement;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;

/**
 * SQL ALTER PROPERTY command: Changes an attribute of an existent property in the target class.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLAlterClass extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_ALTER = "ALTER";
  public static final String KEYWORD_CLASS = "CLASS";

  private String className;
  private ATTRIBUTES attribute;
  private String value;
  private boolean unsafe = false;

  public CommandExecutorSQLAlterClass parse(final CommandRequest iRequest) {
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
            "Keyword " + KEYWORD_ALTER + " not found", parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1 || !word.toString().equals(KEYWORD_CLASS)) {
        throw new CommandSQLParsingException(
            "Keyword " + KEYWORD_CLASS + " not found", parserText, oldPos);
      }

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, false);
      if (pos == -1) {
        throw new CommandSQLParsingException("Expected <class>", parserText, oldPos);
      }

      className = decodeClassName(word.toString());

      oldPos = pos;
      pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
      if (pos == -1) {
        throw new CommandSQLParsingException(
            "Missed the class's attribute to change", parserText, oldPos);
      }

      final String attributeAsString = word.toString();

      try {
        attribute = SchemaClass.ATTRIBUTES.valueOf(attributeAsString.toUpperCase(Locale.ENGLISH));
      } catch (IllegalArgumentException e) {
        throw BaseException.wrapException(
            new CommandSQLParsingException(
                "Unknown class's attribute '"
                    + attributeAsString
                    + "'. Supported attributes are: "
                    + Arrays.toString(SchemaClass.ATTRIBUTES.values()),
                parserText,
                oldPos),
            e);
      }

      value = parserText.substring(pos + 1).trim();

      if ("addcluster".equalsIgnoreCase(attributeAsString)
          || "removecluster".equalsIgnoreCase(attributeAsString)) {
        value = decodeClassName(value);
      }
      SQLAlterClassStatement stm = (SQLAlterClassStatement) preParsedStatement;
      if (this.preParsedStatement != null && stm.property == ATTRIBUTES.CUSTOM) {
        value = stm.customKey.getStringValue() + "=" + stm.customValue.toString();
      }

      if (parserTextUpperCase.endsWith("UNSAFE")) {
        unsafe = true;
        value = value.substring(0, value.length() - "UNSAFE".length());
        for (int i = value.length() - 1; value.charAt(i) == ' ' || value.charAt(i) == '\t'; i--) {
          value = value.substring(0, value.length() - 1);
        }
      }
      if (value.length() == 0) {
        throw new CommandSQLParsingException(
            "Missed the property's value to change for attribute '" + attribute + "'",
            parserText,
            oldPos);
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
   * Execute the ALTER CLASS.
   */
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal querySession) {
    final var database = getDatabase();
    if (attribute == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not been parsed yet");
    }

    final SchemaClassImpl cls = (SchemaClassImpl) database.getMetadata().getSchema()
        .getClass(className);
    if (cls == null) {
      throw new CommandExecutionException(
          "Cannot alter class '" + className + "' because not found");
    }

    if (!unsafe && attribute == ATTRIBUTES.NAME && cls.isSubClassOf(querySession, "E")) {
      throw new CommandExecutionException(
          "Cannot alter class '"
              + className
              + "' because is an Edge class and could break vertices. Use UNSAFE if you want to"
              + " force it");
    }

    if (value != null && attribute == ATTRIBUTES.SUPERCLASS) {
      checkClassExists(database, className, decodeClassName(value));
    }
    if (value != null && attribute == ATTRIBUTES.SUPERCLASSES) {
      String[] classes = value.split(",\\s*");
      for (String cName : classes) {
        checkClassExists(database, className, decodeClassName(cName));
      }
    }
    if (!unsafe && value != null && attribute == ATTRIBUTES.NAME) {
      if (!cls.getIndexes(database).isEmpty()) {
        throw new CommandExecutionException(
            "Cannot rename class '"
                + className
                + "' because it has indexes defined on it. Drop indexes before or use UNSAFE (at"
                + " your won risk)");
      }
    }
    cls.set(database, attribute, value);

    return Boolean.TRUE;
  }

  @Override
  public long getDistributedTimeout() {
    return getDatabase()
        .getConfiguration()
        .getValueAsLong(GlobalConfiguration.DISTRIBUTED_COMMAND_QUICK_TASK_SYNCH_TIMEOUT);
  }

  protected void checkClassExists(
      DatabaseSession database, String targetClass, String superClass) {
    if (superClass.startsWith("+") || superClass.startsWith("-")) {
      superClass = superClass.substring(1);
    }
    if (((DatabaseSessionInternal) database)
        .getMetadata()
        .getImmutableSchemaSnapshot()
        .getClass(decodeClassName(superClass))
        == null) {
      throw new CommandExecutionException(
          "Cannot alter superClass of '"
              + targetClass
              + "' because "
              + superClass
              + " class not found");
    }
  }

  public String getSyntax() {
    return "ALTER CLASS <class> <attribute-name> <attribute-value> [UNSAFE]";
  }

  @Override
  public boolean involveSchema() {
    return true;
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.ALL;
  }
}
