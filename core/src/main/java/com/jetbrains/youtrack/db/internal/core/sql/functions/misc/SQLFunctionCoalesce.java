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

package com.jetbrains.youtrack.db.internal.core.sql.functions.misc;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionAbstract;
import javax.annotation.Nullable;

/**
 * Returns the first <code>field/value</code> not null parameter. if no <code>field/value</code> is
 * <b>not</b> null, returns null.
 *
 * <p>Syntax:
 *
 * <blockquote>
 *
 * <pre>
 * coalesce(&lt;field|value&gt;[,&lt;field|value&gt;]*)
 * </pre>
 *
 * </blockquote>
 *
 * <p>Examples:
 *
 * <blockquote>
 *
 * <pre>
 * SELECT <b>coalesce('a', 'b')</b> FROM ...
 *  -> 'a'
 *
 * SELECT <b>coalesce(null, 'b')</b> FROM ...
 *  -> 'b'
 *
 * SELECT <b>coalesce(null, null, 'c')</b> FROM ...
 *  -> 'c'
 *
 * SELECT <b>coalesce(null, null)</b> FROM ...
 *  -> null
 *
 * </pre>
 *
 * </blockquote>
 */
public class SQLFunctionCoalesce extends SQLFunctionAbstract {

  public static final String NAME = "coalesce";

  public SQLFunctionCoalesce() {
    super(NAME, 1, 1000);
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    var length = iParams.length;
    for (var i = 0; i < length; i++) {
      if (iParams[i] != null) {
        return iParams[i];
      }
    }
    return null;
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "Returns the first not-null parameter or null if all parameters are null. Syntax:"
        + " coalesce(<field|value> [,<field|value>]*)";
  }
}
