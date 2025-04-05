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
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLDropClassStatement;
import java.util.Map;

/**
 * SQL DROP CLASS command: Drops a class from the database. Cluster associated are removed too if
 * are used exclusively by the deleting class.
 */
@SuppressWarnings("unchecked")
public class CommandExecutorSQLDropClass extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_DROP = "DROP";
  public static final String KEYWORD_CLASS = "CLASS";
  public static final String KEYWORD_UNSAFE = "UNSAFE";

  private String className;
  private boolean unsafe;
  private boolean ifExists = false;

  public CommandExecutorSQLDropClass parse(final CommandRequest iRequest) {
    final CommandRequestText textRequest = (CommandRequestText) iRequest;

    String queryText = textRequest.getText();
    String originalQuery = queryText;
    try {
      queryText = preParse(queryText, iRequest);
      textRequest.setText(queryText);
      final boolean strict = getDatabase().getStorageInfo().getConfiguration().isStrictSql();
      if (strict) {
        this.className = ((SQLDropClassStatement) this.preParsedStatement).name.getStringValue();
        this.unsafe = ((SQLDropClassStatement) this.preParsedStatement).unsafe;
        this.ifExists = ((SQLDropClassStatement) this.preParsedStatement).ifExists;
      } else {
        oldParsing((CommandRequestText) iRequest);
      }
    } finally {
      textRequest.setText(originalQuery);
    }

    return this;
  }

  private void oldParsing(CommandRequestText iRequest) {
    init(iRequest);

    final StringBuilder word = new StringBuilder();

    int oldPos = 0;
    int pos = nextWord(parserText, parserTextUpperCase, oldPos, word, true);
    if (pos == -1 || !word.toString().equals(KEYWORD_DROP)) {
      throw new CommandSQLParsingException(
          "Keyword " + KEYWORD_DROP + " not found. Use " + getSyntax(), parserText, oldPos);
    }

    pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
    if (pos == -1 || !word.toString().equals(KEYWORD_CLASS)) {
      throw new CommandSQLParsingException(
          "Keyword " + KEYWORD_CLASS + " not found. Use " + getSyntax(), parserText, oldPos);
    }

    pos = nextWord(parserText, parserTextUpperCase, pos, word, false);
    if (pos == -1) {
      throw new CommandSQLParsingException(
          "Expected <class>. Use " + getSyntax(), parserText, pos);
    }

    className = word.toString();

    pos = nextWord(parserText, parserTextUpperCase, pos, word, true);
    if (pos > -1 && KEYWORD_UNSAFE.equalsIgnoreCase(word.toString())) {
      unsafe = true;
    }
  }

  @Override
  public QUORUM_TYPE getQuorumType() {
    return QUORUM_TYPE.ALL;
  }

  @Override
  public long getDistributedTimeout() {
    final SchemaClassInternal cls = (SchemaClassInternal) getDatabase().getMetadata().getSchema()
        .getClass(className);
    if (className != null && cls != null) {
      return getDatabase()
          .getConfiguration()
          .getValueAsLong(GlobalConfiguration.DISTRIBUTED_COMMAND_QUICK_TASK_SYNCH_TIMEOUT)
          + (2 * cls.count(getDatabase()));
    }

    return getDatabase()
        .getConfiguration()
        .getValueAsLong(GlobalConfiguration.DISTRIBUTED_COMMAND_QUICK_TASK_SYNCH_TIMEOUT);
  }

  /**
   * Execute the DROP CLASS.
   */
  public Object execute(final Map<Object, Object> iArgs, DatabaseSessionInternal querySession) {
    if (className == null) {
      throw new CommandExecutionException(
          "Cannot execute the command because it has not been parsed yet");
    }

    final var database = getDatabase();
    if (ifExists && !database.getMetadata().getSchema().existsClass(className)) {
      return true;
    }
    final SchemaClassInternal cls = (SchemaClassInternal) database.getMetadata().getSchema()
        .getClass(className);
    if (cls == null) {
      return null;
    }

    final long records = cls.count(database, true);

    if (records > 0 && !unsafe) {
      // NOT EMPTY, CHECK IF CLASS IS OF VERTEX OR EDGES
      if (cls.isSubClassOf(database, "V")) {
        // FOUND VERTEX CLASS
        throw new CommandExecutionException(
            "'DROP CLASS' command cannot drop class '"
                + className
                + "' because it contains Vertices. Use 'DELETE VERTEX' command first to avoid"
                + " broken edges in a database, or apply the 'UNSAFE' keyword to force it");
      } else if (cls.isSubClassOf(database, "E")) {
        // FOUND EDGE CLASS
        throw new CommandExecutionException(
            "'DROP CLASS' command cannot drop class '"
                + className
                + "' because it contains Edges. Use 'DELETE EDGE' command first to avoid broken"
                + " vertices in a database, or apply the 'UNSAFE' keyword to force it");
      }
    }

    database.getMetadata().getSchema().dropClass(className);

    if (records > 0 && unsafe) {
      // NOT EMPTY, CHECK IF CLASS IS OF VERTEX OR EDGES
      if (cls.isSubClassOf(database, "V")) {
        // FOUND VERTICES
        if (unsafe) {
          LogManager.instance()
              .warn(
                  this,
                  "Dropped class '%s' containing %d vertices using UNSAFE mode. Database could"
                      + " contain broken edges",
                  className,
                  records);
        }
      } else if (cls.isSubClassOf(database, "E")) {
        // FOUND EDGES
        LogManager.instance()
            .warn(
                this,
                "Dropped class '%s' containing %d edges using UNSAFE mode. Database could contain"
                    + " broken vertices",
                className,
                records);
      }
    }

    return true;
  }

  @Override
  public String getSyntax() {
    return "DROP CLASS <class> [IF EXISTS] [UNSAFE]";
  }

  @Override
  public boolean involveSchema() {
    return true;
  }
}
