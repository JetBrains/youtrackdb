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

import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.Collection;
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

      final var cmd = ((CommandRequestText) iCommand).getText();
      var command = new CommandSQL(cmd.substring(KEYWORD_EXPLAIN.length()));
      var context = new BasicCommandContext();
      context.setParent(iCommand.getContext());

      command.setContext(context);

      super.parse(session, command);
    } finally {
      textRequest.setText(originalQuery);
    }
    return this;
  }

  @Override
  public Object execute(DatabaseSessionInternal session, Map<Object, Object> iArgs) {
    delegate.getContext().setRecordingMetrics(true);

    final var startTime = System.nanoTime();

    final var result = super.execute(session, iArgs);
    final var report = new ResultInternal(session);
    var variables = delegate.getContext().getVariables();
    for (var entry : variables.entrySet()) {
      report.setProperty(entry.getKey(), entry.getValue());
    }

    report.setProperty("elapsed", (System.nanoTime() - startTime) / 1000000f);

    if (result instanceof Collection<?>) {
      report.setProperty("resultType", "collection");
      report.setProperty("resultSize", ((Collection<?>) result).size());
    } else if (result instanceof EntityImpl) {
      report.setProperty("resultType", "document");
      report.setProperty("resultSize", 1);
    } else if (result instanceof Number) {
      report.setProperty("resultType", "number");
    }

    return report;
  }

  @Override
  public boolean isCacheable() {
    return false;
  }
}
