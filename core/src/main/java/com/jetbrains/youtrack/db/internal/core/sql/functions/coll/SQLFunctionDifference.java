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
package com.jetbrains.youtrack.db.internal.core.sql.functions.coll;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommandExecutionException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * This operator can work inline. Returns the DIFFERENCE between the collections received as
 * parameters. Works also with no collection values.
 */
public class SQLFunctionDifference extends SQLFunctionMultiValueAbstract<Set<Object>> {

  public static final String NAME = "difference";

  public SQLFunctionDifference() {
    super(NAME, 1, -1);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {

    if (Boolean.TRUE.equals(iContext.getVariable("aggregation"))) {
      throw new CommandExecutionException(iContext.getDatabaseSession(),
          "difference function cannot be used in aggregation mode");
    }

    // if the first parameter is null, then the overall result is empty
    if (iParams[0] == null) {
      return List.of();
    }

    // IN-LINE MODE (STATELESS)
    final Set<Object> result = new LinkedHashSet<>();

    final var firstIt = MultiValue.getMultiValueIterator(iParams[0]);
    while (firstIt.hasNext()) {
      result.add(firstIt.next());
    }

    if (result.isEmpty()) { // no need to iterate further
      return List.of();
    }

    for (var i = 1; i < iParams.length; i++) {
      // if the parameter is null, ignoring it, it will not affect the difference result
      if (iParams[i] == null) {
        continue;
      }

      final var it = MultiValue.getMultiValueIterator(iParams[i]);
      while (it.hasNext()) {
        result.remove(it.next());
      }
    }

    // still need to return a list here, because returning a Set can
    // break the order, as some of our code performs collection copying based on
    // "instanceof Set" check.
    return new ArrayList<>(result);
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return "difference(<field> [, <field]*)";
  }
}
