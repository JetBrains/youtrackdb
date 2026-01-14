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
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Default command operator executor factory.
 */
public class DefaultCommandExecutorSQLFactory implements CommandExecutorSQLFactory {

  private static final Map<String, Class<? extends CommandExecutor>> COMMANDS;

  static {
    COMMANDS = Collections.emptyMap();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Set<String> getCommandNames() {
    return COMMANDS.keySet();
  }

  /**
   * {@inheritDoc}
   */
  @Override
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
