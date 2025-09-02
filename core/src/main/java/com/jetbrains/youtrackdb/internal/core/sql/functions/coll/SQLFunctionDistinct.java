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
package com.jetbrains.youtrackdb.internal.core.sql.functions.coll;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Keeps items only once removing duplicates
 */
public class SQLFunctionDistinct extends SQLFunctionAbstract {

  public static final String NAME = "distinct";

  private final Set<Object> context = new LinkedHashSet<Object>();

  public SQLFunctionDistinct() {
    super(NAME, 1, 1);
  }

  @Nullable
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    final var value = iParams[0];

    if (value != null && !context.contains(value)) {
      context.add(value);
      return value;
    }

    return null;
  }

  @Override
  public boolean filterResult() {
    return true;
  }

  public String getSyntax(DatabaseSession session) {
    return "distinct(<field>)";
  }
}
