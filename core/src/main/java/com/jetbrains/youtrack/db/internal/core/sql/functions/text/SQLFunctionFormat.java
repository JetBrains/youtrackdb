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
package com.jetbrains.youtrack.db.internal.core.sql.functions.text;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionAbstract;

/**
 * Formats content.
 */
public class SQLFunctionFormat extends SQLFunctionAbstract {

  public static final String NAME = "format";

  public SQLFunctionFormat() {
    super(NAME, 1, -1);
  }

  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] params,
      CommandContext iContext) {
    final var args = new Object[params.length - 1];

    System.arraycopy(params, 1, args, 0, args.length);

    return String.format((String) params[0], args);
  }

  public String getSyntax(DatabaseSession session) {
    return "format(<format>, <arg1> [,<argN>]*)";
  }
}
