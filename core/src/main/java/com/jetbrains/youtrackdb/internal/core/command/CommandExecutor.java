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
package com.jetbrains.youtrackdb.internal.core.command;

import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.security.Role;
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
   * @param session  the database session to use
   * @param iRequest Command request implementation.
   * @return this executor instance for method chaining
   * @see #execute(DatabaseSessionEmbedded, Map) <Object, Object>...)
   */
  <RET extends CommandExecutor> RET parse(DatabaseSessionEmbedded session, CommandRequest iRequest);

  /**
   * Execute the requested command parsed previously.
   *
   * @param session the database session to use
   * @param iArgs   Optional variable arguments to pass to the command.
   * @return the command execution result
   * @see #parse(DatabaseSessionEmbedded, CommandRequest)
   */
  Object execute(DatabaseSessionEmbedded session, final Map<Object, Object> iArgs);

  /**
   * Set the listener invoked while the command is executing.
   *
   * @param progressListener ProgressListener implementation
   * @return this executor instance for method chaining
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
  Set<String> getInvolvedCollections(DatabaseSessionEmbedded session);

  /**
   * Returns the security operation type use to check about security.
   *
   * @return the security operation type constant
   * @see Role PERMISSION_*
   */
  int getSecurityOperationType();

  String getSyntax();

  /**
   * Returns true if the command results can be cached.
   */
  boolean isCacheable();
}
