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
package com.jetbrains.youtrackdb.internal.core.sql;

import com.jetbrains.youtrackdb.internal.core.command.CommandExecutor;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import java.util.Set;

/**
 * Factory to register new OCommandExecutorSQL.
 */
public interface CommandExecutorSQLFactory {

  /**
   * Returns the set of supported command names of this factory.
   *
   * @return Set of supported command names of this factory
   */
  Set<String> getCommandNames();

  /**
   * Create command for the given name. returned command may be a new instance each time or a
   * constant.
   *
   * @param name
   * @return CommandExecutorSQLAbstract : created command
   * @throws CommandExecutionException : when command creation fail
   */
  CommandExecutor createCommand(String name) throws CommandExecutionException;
}
