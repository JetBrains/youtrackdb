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
package com.jetbrains.youtrack.db.internal.core.sql.functions.stat;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.functions.SQLFunctionAbstract;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * Compute the mode (or multimodal) value for a field. The scores in the field's distribution that
 * occurs more frequently. Nulls are ignored in the calculation.
 */
public class SQLFunctionMode extends SQLFunctionAbstract {

  public static final String NAME = "mode";

  private final Object2IntOpenHashMap<Object> seen = new Object2IntOpenHashMap<>();
  private int max = 0;
  private final List<Object> maxElems = new ArrayList<Object>();

  public SQLFunctionMode() {
    super(NAME, 1, 1);
    seen.defaultReturnValue(-1);
  }

  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext iContext) {

    if (MultiValue.isMultiValue(iParams[0])) {
      for (var o : MultiValue.getMultiValueIterable(iParams[0])) {
        max = evaluate(o, 1, seen, maxElems, max);
      }
    } else {
      max = evaluate(iParams[0], 1, seen, maxElems, max);
    }
    return getResult();
  }

  @Override
  public Object getResult() {
    return maxElems.isEmpty() ? null : maxElems;
  }

  @Override
  public String getSyntax(DatabaseSession session) {
    return NAME + "(<field>)";
  }

  @Override
  public boolean aggregateResults() {
    return true;
  }

  private int evaluate(
      Object value,
      int times,
      Object2IntOpenHashMap<Object> iSeen,
      List<Object> iMaxElems,
      int iMax) {
    if (value != null) {
      if (iSeen.containsKey(value)) {
        iSeen.put(value, iSeen.getInt(value) + times);
      } else {
        iSeen.put(value, times);
      }
      if (iSeen.getInt(value) > iMax) {
        iMax = iSeen.getInt(value);
        iMaxElems.clear();
        iMaxElems.add(value);
      } else if (iSeen.getInt(value) == iMax) {
        iMaxElems.add(value);
      }
    }
    return iMax;
  }
}
