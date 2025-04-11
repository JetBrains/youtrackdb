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
package com.jetbrains.youtrack.db.internal.core.exception;

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;

public class QueryParsingException extends CommandSQLParsingException {

  private String text;
  private int position = -1;
  private static final long serialVersionUID = -7430575036316163711L;

  private static String makeMessage(int position, String text, String message) {
    var buffer = new StringBuilder();
    if (position > -1) {
      buffer.append("Error on parsing query at position #");
      buffer.append(position);
      buffer.append(": ");
    }

    buffer.append(message);

    if (text != null) {
      buffer.append("\nQuery: ");
      buffer.append(text);
      buffer.append("\n------");
      for (var i = 0; i < position - 1; ++i) {
        buffer.append("-");
      }

      buffer.append("^");
    }
    return buffer.toString();
  }

  public QueryParsingException(QueryParsingException exception) {
    super(exception);

    this.text = exception.text;
    this.position = exception.position;
  }

  public QueryParsingException(final String iMessage) {
    super(iMessage);
  }

  public QueryParsingException(String dbName, final String iMessage) {
    super(dbName, iMessage);
  }

  public QueryParsingException(String dbName, final String iMessage, final String iText,
      final int iPosition) {
    super(dbName, makeMessage(iPosition, iText, iMessage));

    text = iText;
    position = iPosition;
  }

  public String getText() {
    return text;
  }
}
