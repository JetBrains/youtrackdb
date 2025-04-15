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
package com.jetbrains.youtrack.db.internal.core.command;

import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.Role;
import java.util.Map;
import java.util.Set;

/**
 * Generic GOF command pattern implementation.
 */
public interface CommandExecutor {

  /**
   * Parse the request. Once parsed the command can be executed multiple times by using the
   * execute() method.
   *
   * @param session
   * @param iRequest Command request implementation.
   * @return
   * @see #execute(DatabaseSessionInternal, Map) <Object, Object>...)
   */
  <RET extends CommandExecutor> RET parse(DatabaseSessionInternal session, CommandRequest iRequest);

  /**
   * Execute the requested command parsed previously.
   *
   * @param session
   * @param iArgs Optional variable arguments to pass to the command.
   * @return
   * @see #parse(DatabaseSessionInternal, CommandRequest)
   */
  Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs);

  /**
   * Set the listener invoked while the command is executing.
   *
   * @param progressListener ProgressListener implementation
   * @return
   */
  <RET extends CommandExecutor> RET setProgressListener(ProgressListener progressListener);

  <RET extends CommandExecutor> RET setLimit(int iLimit);

  String getFetchPlan();

  Map<Object, Object> getParameters();

  CommandContext getContext();

  void setContext(CommandContext context);

  /**
   * Returns true if the command doesn't change the database, otherwise false.
   */
  boolean isIdempotent();

  /**
   * Returns the involved collections.
   */
  Set<String> getInvolvedCollections(DatabaseSessionInternal session);

  /**
   * Returns the security operation type use to check about security.
   *
   * @return
   * @see Role PERMISSION_*
   */
  int getSecurityOperationType();

  String getSyntax();

  /**
   * Returns true if the command results can be cached.
   */
  boolean isCacheable();
}
