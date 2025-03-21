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

import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandExecutor;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import com.jetbrains.youtrack.db.internal.core.sql.parser.SQLStatement;
import com.jetbrains.youtrack.db.internal.core.sql.parser.StatementCache;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Wrapper for OPrifileStorageStatement command (for compatibility with the old executor
 * architecture, this component should be removed)
 */
public class CommandExecutorToStatementWrapper implements CommandExecutor {

  private CommandContext context;
  private ProgressListener progressListener;

  protected SQLStatement statement;

  @SuppressWarnings("unchecked")
  @Override
  public CommandExecutorToStatementWrapper parse(DatabaseSessionInternal session,
      CommandRequest iCommand) {
    final var textRequest = (CommandRequestText) iCommand;

    var queryText = textRequest.getText();
    statement = StatementCache.get(queryText, session);
    return this;
  }


  @Override
  public Object execute(DatabaseSessionInternal session, Map<Object, Object> iArgs) {
    return null;
  }

  @Override
  public <RET extends CommandExecutor> RET setProgressListener(
      ProgressListener progressListener) {
    this.progressListener = progressListener;
    return (RET) this;
  }

  @Override
  public <RET extends CommandExecutor> RET setLimit(int iLimit) {
    return (RET) this;
  }

  @Override
  public String getFetchPlan() {
    return null;
  }

  @Override
  public Map<Object, Object> getParameters() {
    return null;
  }

  @Override
  public CommandContext getContext() {
    return this.context;
  }

  @Override
  public void setContext(CommandContext context) {
    this.context = context;
  }

  @Override
  public boolean isIdempotent() {
    return false;
  }

  @Override
  public Set<String> getInvolvedClusters(DatabaseSessionInternal session) {
    return Collections.EMPTY_SET;
  }

  @Override
  public int getSecurityOperationType() {
    return Role.PERMISSION_READ;
  }

  @Override
  public String getSyntax() {
    return "PROFILE STORAGE [ON | OFF]";
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
