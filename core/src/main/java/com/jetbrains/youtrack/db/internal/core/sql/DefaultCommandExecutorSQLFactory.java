/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutor;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLProfileStorageStatement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Default command operator executor factory.
 */
public class DefaultCommandExecutorSQLFactory implements CommandExecutorSQLFactory {

  private static final Map<String, Class<? extends CommandExecutor>> COMMANDS;

  static {

    // COMMANDS
    final Map<String, Class<? extends CommandExecutor>> commands =
        new HashMap<String, Class<? extends CommandExecutor>>();
    commands.put(
        CommandExecutorSQLAlterDatabase.KEYWORD_ALTER
            + " "
            + CommandExecutorSQLAlterDatabase.KEYWORD_DATABASE,
        CommandExecutorSQLAlterDatabase.class);
    commands.put(CommandExecutorSQLSelect.KEYWORD_SELECT, CommandExecutorSQLSelect.class);
    commands.put(CommandExecutorSQLSelect.KEYWORD_FOREACH, CommandExecutorSQLSelect.class);
    commands.put(CommandExecutorSQLTraverse.KEYWORD_TRAVERSE, CommandExecutorSQLTraverse.class);
    commands.put(CommandExecutorSQLInsert.KEYWORD_INSERT, CommandExecutorSQLInsert.class);
    commands.put(CommandExecutorSQLUpdate.KEYWORD_UPDATE, CommandExecutorSQLUpdate.class);
    commands.put(CommandExecutorSQLDelete.NAME, CommandExecutorSQLDelete.class);
    commands.put(CommandExecutorSQLCreateFunction.NAME, CommandExecutorSQLCreateFunction.class);
    commands.put(CommandExecutorSQLGrant.KEYWORD_GRANT, CommandExecutorSQLGrant.class);
    commands.put(CommandExecutorSQLRevoke.KEYWORD_REVOKE, CommandExecutorSQLRevoke.class);
    commands.put(
        CommandExecutorSQLCreateLink.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateLink.KEYWORD_LINK,
        CommandExecutorSQLCreateLink.class);
    commands.put(
        CommandExecutorSQLCreateIndex.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateIndex.KEYWORD_INDEX,
        CommandExecutorSQLCreateIndex.class);
    commands.put(
        CommandExecutorSQLDropIndex.KEYWORD_DROP
            + " "
            + CommandExecutorSQLDropIndex.KEYWORD_INDEX,
        CommandExecutorSQLDropIndex.class);
    commands.put(
        CommandExecutorSQLRebuildIndex.KEYWORD_REBUILD
            + " "
            + CommandExecutorSQLRebuildIndex.KEYWORD_INDEX,
        CommandExecutorSQLRebuildIndex.class);
    commands.put(
        CommandExecutorSQLCreateClass.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateClass.KEYWORD_CLASS,
        CommandExecutorSQLCreateClass.class);
    commands.put(
        CommandExecutorSQLCreateCluster.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateCluster.KEYWORD_CLUSTER,
        CommandExecutorSQLCreateCluster.class);
    commands.put(
        CommandExecutorSQLCreateCluster.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateCluster.KEYWORD_BLOB
            + " "
            + CommandExecutorSQLCreateCluster.KEYWORD_CLUSTER,
        CommandExecutorSQLCreateCluster.class);
    commands.put(
        CommandExecutorSQLAlterClass.KEYWORD_ALTER
            + " "
            + CommandExecutorSQLAlterClass.KEYWORD_CLASS,
        CommandExecutorSQLAlterClass.class);
    commands.put(
        CommandExecutorSQLCreateProperty.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateProperty.KEYWORD_PROPERTY,
        CommandExecutorSQLCreateProperty.class);
    commands.put(
        CommandExecutorSQLAlterProperty.KEYWORD_ALTER
            + " "
            + CommandExecutorSQLAlterProperty.KEYWORD_PROPERTY,
        CommandExecutorSQLAlterProperty.class);
    commands.put(
        CommandExecutorSQLDropClass.KEYWORD_DROP
            + " "
            + CommandExecutorSQLDropClass.KEYWORD_CLASS,
        CommandExecutorSQLDropClass.class);
    commands.put(
        CommandExecutorSQLDropProperty.KEYWORD_DROP
            + " "
            + CommandExecutorSQLDropProperty.KEYWORD_PROPERTY,
        CommandExecutorSQLDropProperty.class);
    commands.put(
        CommandExecutorSQLFindReferences.KEYWORD_FIND
            + " "
            + CommandExecutorSQLFindReferences.KEYWORD_REFERENCES,
        CommandExecutorSQLFindReferences.class);
    commands.put(
        CommandExecutorSQLTruncateClass.KEYWORD_TRUNCATE
            + " "
            + CommandExecutorSQLTruncateClass.KEYWORD_CLASS,
        CommandExecutorSQLTruncateClass.class);
    commands.put(
        CommandExecutorSQLAlterCluster.KEYWORD_ALTER
            + " "
            + CommandExecutorSQLAlterCluster.KEYWORD_CLUSTER,
        CommandExecutorSQLAlterCluster.class);
    commands.put(
        CommandExecutorSQLCreateSequence.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateSequence.KEYWORD_SEQUENCE,
        CommandExecutorSQLCreateSequence.class);
    commands.put(
        CommandExecutorSQLAlterSequence.KEYWORD_ALTER
            + " "
            + CommandExecutorSQLAlterSequence.KEYWORD_SEQUENCE,
        CommandExecutorSQLAlterSequence.class);
    commands.put(
        CommandExecutorSQLDropSequence.KEYWORD_DROP
            + " "
            + CommandExecutorSQLDropSequence.KEYWORD_SEQUENCE,
        CommandExecutorSQLDropSequence.class);
    commands.put(
        CommandExecutorSQLCreateUser.KEYWORD_CREATE
            + " "
            + CommandExecutorSQLCreateUser.KEYWORD_USER,
        CommandExecutorSQLCreateUser.class);
    commands.put(
        CommandExecutorSQLDropUser.KEYWORD_DROP + " " + CommandExecutorSQLDropUser.KEYWORD_USER,
        CommandExecutorSQLDropUser.class);
    commands.put(CommandExecutorSQLExplain.KEYWORD_EXPLAIN, CommandExecutorSQLExplain.class);
    commands.put(
        CommandExecutorSQLTransactional.KEYWORD_TRANSACTIONAL,
        CommandExecutorSQLTransactional.class);

    commands.put(
        CommandExecutorSQLOptimizeDatabase.KEYWORD_OPTIMIZE,
        CommandExecutorSQLOptimizeDatabase.class);

    commands.put(
        SQLProfileStorageStatement.KEYWORD_PROFILE, CommandExecutorToStatementWrapper.class);

    // GRAPH

    commands.put(CommandExecutorSQLCreateEdge.NAME, CommandExecutorSQLCreateEdge.class);
    commands.put(CommandExecutorSQLDeleteEdge.NAME, CommandExecutorSQLDeleteEdge.class);
    commands.put(CommandExecutorSQLCreateVertex.NAME, CommandExecutorSQLCreateVertex.class);
    commands.put(CommandExecutorSQLDeleteVertex.NAME, CommandExecutorSQLDeleteVertex.class);
    commands.put(CommandExecutorSQLMoveVertex.NAME, CommandExecutorSQLMoveVertex.class);

    COMMANDS = Collections.unmodifiableMap(commands);
  }

  /**
   * {@inheritDoc}
   */
  public Set<String> getCommandNames() {
    return COMMANDS.keySet();
  }

  /**
   * {@inheritDoc}
   */
  public CommandExecutor createCommand(final String name) throws CommandExecutionException {
    final var clazz = COMMANDS.get(name);

    if (clazz == null) {
      throw new CommandExecutionException("Unknowned command name :" + name);
    }

    try {
      return clazz.newInstance();
    } catch (Exception e) {
      throw BaseException.wrapException(new CommandExecutionException(
              "Error in creation of command "
                  + name
                  + "(). Probably there is not an empty constructor or the constructor generates"
                  + " errors"),
          e, (String) null);
    }
  }
}
