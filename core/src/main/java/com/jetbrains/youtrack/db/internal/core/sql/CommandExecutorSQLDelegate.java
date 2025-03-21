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
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutor;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLPredicate;
import java.util.Map;
import java.util.Set;

/**
 * SQL UPDATE command.
 */
public class CommandExecutorSQLDelegate extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  @SuppressWarnings("unchecked")
  public CommandExecutorSQLDelegate parse(DatabaseSessionInternal session,
      final CommandRequest iCommand) {
    if (iCommand instanceof CommandRequestText textRequest) {
      final var text = textRequest.getText();
      if (text == null) {
        throw new IllegalArgumentException("Command text is null");
      }

      final var textUpperCase = SQLPredicate.upperCase(text);

    } else {
      throw new CommandExecutionException(session,
          "Cannot find a command executor for the command request: " + iCommand);
    }
    return this;
  }


  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    return null;
  }

  @Override
  public CommandContext getContext() {
    return null;
  }

  @Override
  public String toString() {
    return null;
  }

  public String getSyntax() {
    return null;
  }

  @Override
  public String getFetchPlan() {
    return null;
  }

  @Override
  public boolean isIdempotent() {
    return false;
  }

  public CommandExecutor getDelegate() {
    return null;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  @Override
  public Set<String> getInvolvedClusters(DatabaseSessionInternal session) {
    return null;
  }
}
