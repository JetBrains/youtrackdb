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
package com.jetbrains.youtrack.db.api.exception;

import com.jetbrains.youtrack.db.internal.core.exception.CoreException;

public class CommandScriptException extends CoreException {

  private String text;
  private int position;

  private static String makeMessage(String message, int position, String text) {
    if (text == null) {
      return message;
    }

    return "Error on parsing script at position #"
        + position
        + ": " + message
        + "\nScript: "
        + text
        + "\n------"
        + "-".repeat(Math.max(0, position - 1))
        + "^";
  }

  public CommandScriptException(CommandScriptException exception) {
    super(exception);

    this.text = exception.text;
    this.position = exception.position;
  }

  public CommandScriptException(String dbName, String iMessage) {
    super(dbName, iMessage);
  }

  public CommandScriptException(String dbName, String iMessage, String iText,
      int iPosition) {
    super(dbName, makeMessage(iMessage, Math.max(iPosition, 0), iText));
    text = iText;
    position = Math.max(iPosition, 0);
  }
}
