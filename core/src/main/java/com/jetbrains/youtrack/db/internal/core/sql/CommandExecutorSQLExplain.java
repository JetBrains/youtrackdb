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

import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Map;

/**
 * Explains the execution of a command returning profiling information.
 */
public class CommandExecutorSQLExplain extends CommandExecutorSQLDelegate {

  public static final String KEYWORD_EXPLAIN = "EXPLAIN";

  @Override
  public CommandExecutorSQLExplain parse(DatabaseSessionInternal session, CommandRequest iCommand) {
    final var textRequest = (CommandRequestText) iCommand;

    var queryText = textRequest.getText();
    var originalQuery = queryText;
    try {
      queryText = preParse(session, queryText, iCommand);
      textRequest.setText(queryText);

      return this;
    } finally {
      textRequest.setText(originalQuery);
    }
  }

  @Override
  public Object execute(DatabaseSessionInternal session, Map<Object, Object> iArgs) {

    return null;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
