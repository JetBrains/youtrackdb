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

package com.jetbrains.youtrack.db.internal.core.sql;

import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import java.util.List;

/**
 *
 */
public class OrderByOptimizer {

  boolean canBeUsedByOrderBy(Index index, List<Pair<String, String>> orderedFields) {
    if (orderedFields.isEmpty()) {
      return false;
    }

    if (!index.supportsOrderedIterations()) {
      return false;
    }

    final var definition = index.getDefinition();
    final var fields = definition.getFields();
    final var endIndex = Math.min(fields.size(), orderedFields.size());

    final var firstOrder = orderedFields.get(0).getValue();
    for (var i = 0; i < endIndex; i++) {
      final var pair = orderedFields.get(i);

      if (!firstOrder.equals(pair.getValue())) {
        return false;
      }

      final var orderFieldName = orderedFields.get(i).getKey();
      final var indexFieldName = fields.get(i);

      if (!orderFieldName.equals(indexFieldName)) {
        return false;
      }
    }

    return true;
  }

  /**
   * checks if, given a list of "=" conditions and a set of ORDER BY fields
   *
   * @param index
   * @param equalsFilterFields
   * @param orderedFields
   * @return
   */
  boolean canBeUsedByOrderByAfterFilter(
      Index index, List<String> equalsFilterFields, List<Pair<String, String>> orderedFields) {
    if (orderedFields.isEmpty()) {
      return false;
    }

    if (!index.supportsOrderedIterations()) {
      return false;
    }

    final var definition = index.getDefinition();
    final var indexFields = definition.getFields();
    var endIndex = Math.min(indexFields.size(), equalsFilterFields.size());

    final var firstOrder = orderedFields.get(0).getValue();

    // check that all the "equals" clauses are a prefix for the index
    for (var i = 0; i < endIndex; i++) {
      final var equalsFieldName = equalsFilterFields.get(i);
      final var indexFieldName = indexFields.get(i);
      if (!equalsFieldName.equals(indexFieldName)) {
        return false;
      }
    }

    endIndex = Math.min(indexFields.size(), orderedFields.size() + equalsFilterFields.size());
    if (endIndex == equalsFilterFields.size()) {
      // the index is used only for filtering
      return false;
    }
    // check that after that prefix there all the Order By fields in the right order
    for (var i = equalsFilterFields.size(); i < endIndex; i++) {
      var fieldOrderInOrderByClause = i - equalsFilterFields.size();
      final var pair = orderedFields.get(fieldOrderInOrderByClause);

      if (!firstOrder.equals(pair.getValue())) {
        return false;
      }

      final var orderFieldName = pair.getKey();
      final var indexFieldName = indexFields.get(i);

      if (!orderFieldName.equals(indexFieldName)) {
        return false;
      }
    }

    return true;
  }
}
