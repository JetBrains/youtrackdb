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
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import javax.annotation.Nullable;

/**
 * Returns different values based on the condition. If it's true the first value is returned,
 * otherwise the second one.
 *
 * <p>
 *
 * <p>Syntax:
 *
 * <blockquote>
 *
 * <p>
 *
 * <pre>
 * if(&lt;field|value|expression&gt;, &lt;return_value_if_true&gt; [,&lt;return_value_if_false&gt;])
 * </pre>
 *
 * <p>
 *
 * </blockquote>
 *
 * <p>
 *
 * <p>Examples:
 *
 * <blockquote>
 *
 * <p>
 *
 * <pre>
 * SELECT <b>if(rich, 'rich', 'poor')</b> FROM ...
 * <br>
 * SELECT <b>if( eval( 'salary > 1000000' ), 'rich', 'poor')</b> FROM ...
 * </pre>
 *
 * <p>
 *
 * </blockquote>
 */
public class SQLFunctionIf extends SQLFunctionAbstract {

  public static final String NAME = "if";

  public SQLFunctionIf() {
    super(NAME, 2, 3);
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      final CommandContext iContext) {

    boolean result;

    try {
      var condition = iParams[0];
      if (condition instanceof Boolean) {
        result = (Boolean) condition;
      } else if (condition instanceof String) {
        result = Boolean.parseBoolean(condition.toString());
      } else if (condition instanceof Number) {
        result = ((Number) condition).intValue() > 0;
      } else {
        return null;
      }

      return result ? iParams[1] : iParams[2];

    } catch (Exception e) {
      LogManager.instance().error(this, "Error during if execution", e);

      return null;
    }
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "if(<field|value|expression>, <return_value_if_true> [,<return_value_if_false>])";
  }
}
