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

import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This operator add an item in a list. The list accepts duplicates.
 */
public class SQLFunctionList extends SQLFunctionMultiValueAbstract<List<Object>> {

  public static final String NAME = "list";

  public SQLFunctionList() {
    super(NAME, 1, -1);
  }

  @Override
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    if (iParams.length > 1)
    // IN LINE MODE
    {
      context = new ArrayList<Object>();
    }

    for (var value : iParams) {
      if (value != null) {
        if (iParams.length == 1 && context == null)
        // AGGREGATION MODE (STATEFULL)
        {
          context = new ArrayList<Object>();
        }

        if (value instanceof Map) {
          context.add(value);
        } else {
          MultiValue.add(context, value);
        }
      }
    }
    return prepareResult(context);
  }

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return "list(<value>*)";
  }

  public boolean aggregateResults(final Object[] configuredParameters) {
    return false;
  }

  @Override
  public List<Object> getResult() {
    final var res = context;
    context = null;
    return prepareResult(res);
  }


  protected List<Object> prepareResult(List<Object> res) {
    return res;
  }
}
