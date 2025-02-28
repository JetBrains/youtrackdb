/*
 *
 *  *  Copyright YouTrackDB
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

import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.command.CommandDistributedReplicateRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequest;
import com.jetbrains.youtrack.db.internal.core.command.CommandRequestText;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.QueryParsingException;
import com.jetbrains.youtrack.db.internal.core.query.live.LiveQueryHook;
import com.jetbrains.youtrack.db.internal.core.sql.executor.ResultInternal;
import java.util.Locale;
import java.util.Map;

/**
 *
 */
public class CommandExecutorSQLLiveUnsubscribe extends CommandExecutorSQLAbstract
    implements CommandDistributedReplicateRequest {

  public static final String KEYWORD_LIVE_UNSUBSCRIBE = "LIVE UNSUBSCRIBE";

  protected String unsubscribeToken;

  public CommandExecutorSQLLiveUnsubscribe() {
  }

  private Object executeUnsubscribe(DatabaseSessionInternal session) {
    try {

      LiveQueryHook.unsubscribe(Integer.parseInt(unsubscribeToken), session);
      var result = new ResultInternal(session);
      result.setProperty("unsubscribed", unsubscribeToken);
      result.setProperty("unsubscribe", true);
      result.setProperty("token", unsubscribeToken);

      return result;
    } catch (Exception e) {
      LogManager.instance()
          .warn(
              this,
              "error unsubscribing token "
                  + unsubscribeToken
                  + ": "
                  + e.getClass().getName()
                  + " - "
                  + e.getMessage());
      var result = new ResultInternal(session);
      result.setProperty("error-unsubscribe", unsubscribeToken);
      result.setProperty("error-description", e.getMessage());
      result.setProperty("error-type", e.getClass().getName());

      return result;
    }
  }


  public Object execute(DatabaseSessionInternal session, final Map<Object, Object> iArgs) {
    if (this.unsubscribeToken != null) {
      return executeUnsubscribe(session);
    }
    var result = new ResultInternal(session);
    result.setProperty("error-unsubscribe", "no token");
    return result;
  }

  @Override
  public CommandExecutorSQLLiveUnsubscribe parse(DatabaseSessionInternal session,
      CommandRequest iRequest) {
    var requestText = (CommandRequestText) iRequest;
    var originalText = requestText.getText();
    var remainingText = requestText.getText().trim().substring(5).trim();
    requestText.setText(remainingText);
    try {
      if (remainingText.toLowerCase(Locale.ENGLISH).startsWith("unsubscribe")) {
        remainingText = remainingText.substring("unsubscribe".length()).trim();
        if (remainingText.contains(" ")) {
          throw new QueryParsingException(session.getDatabaseName(),
              "invalid unsubscribe token for live query: " + remainingText);
        }
        this.unsubscribeToken = remainingText;
      }
    } finally {
      requestText.setText(originalText);
    }
    return this;
  }
}
