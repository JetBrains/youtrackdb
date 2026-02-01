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

import com.jetbrains.youtrackdb.internal.common.collection.MultiCollectionIterator;
import com.jetbrains.youtrackdb.internal.common.collection.MultiValue;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSession;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemVariable;
import java.util.ArrayList;
import java.util.Collection;

/**
 * This operator can work as aggregate or inline. If only one argument is passed than aggregates,
 * otherwise executes, and returns, a UNION of the collections received as parameters. Works also
 * with no collection values. Does not remove duplication from the result.
 */
public class SQLFunctionUnionAll extends SQLFunctionMultiValueAbstract<Collection<Object>> {

  public static final String NAME = "unionAll";

  public SQLFunctionUnionAll() {
    super(NAME, 1, -1);
  }

  @Override
  public Object execute(
      final Object iThis,
      final Result iCurrentRecord,
      final Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    if (Boolean.TRUE.equals(iContext.getVariable("aggregation"))) {
      // AGGREGATION MODE (STATEFUL)
      var value = iParams[0];
      if (value != null) {

        if (value instanceof SQLFilterItemVariable) {
          value =
              ((SQLFilterItemVariable) value).getValue(iCurrentRecord, iCurrentResult, iContext);
        }

        if (context == null) {
          context = new ArrayList<Object>();
        }

        MultiValue.add(context, value);
      }

      return context;
    } else {
      // IN-LINE MODE (STATELESS)
      final var result = new MultiCollectionIterator<Identifiable>();
      for (var value : iParams) {
        if (value != null) {
          if (value instanceof SQLFilterItemVariable) {
            value =
                ((SQLFilterItemVariable) value).getValue(iCurrentRecord, iCurrentResult, iContext);
          }

          result.add(value);
        }
      }

      return result;
    }
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "unionAll(<field>*)";
  }
}
