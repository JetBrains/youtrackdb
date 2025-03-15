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
package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.util.Pair;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.sql.CommandExecutorSQLSelect;
import java.text.Collator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Comparator implementation class used by ODocumentSorter class to sort documents following dynamic
 * criteria.
 */
public class EntityComparator implements Comparator<Identifiable> {

  private final List<Pair<String, String>> orderCriteria;
  private final CommandContext context;
  private final Collator collator;

  public EntityComparator(
      final List<Pair<String, String>> iOrderCriteria, CommandContext iContext) {
    this.orderCriteria = iOrderCriteria;
    this.context = iContext;

    var db = context.getDatabaseSession();
    collator =
        Collator.getInstance(
            new Locale(
                db.get(DatabaseSession.ATTRIBUTES.LOCALE_COUNTRY)
                    + "_"
                    + db.get(DatabaseSession.ATTRIBUTES.LOCALE_LANGUAGE)));
  }

  @SuppressWarnings("unchecked")
  public int compare(final Identifiable ind1, final Identifiable ind2) {
    if (ind1 != null && ind1.equals(ind2)) {
      return 0;
    }

    Object fieldValue1;
    Object fieldValue2;

    var partialResult = 0;

    var db = context.getDatabaseSession();
    for (var field : orderCriteria) {
      final var fieldName = field.getKey();
      final var ordering = field.getValue();

      EntityImpl entity1 = ind1.getRecord(db);
      fieldValue1 = entity1.getProperty(fieldName);
      EntityImpl entity = ind2.getRecord(db);
      fieldValue2 = entity.getProperty(fieldName);

      if (fieldValue1 == null && fieldValue2 == null) {
        continue;
      }

      if (fieldValue1 == null) {
        return factor(-1, ordering);
      }

      if (fieldValue2 == null) {
        return factor(1, ordering);
      }

      if (!(fieldValue1 instanceof Comparable<?>)) {
        context.incrementVariable(BasicCommandContext.INVALID_COMPARE_COUNT);
        partialResult = ("" + fieldValue1).compareTo("" + fieldValue2);
      } else {
        try {
          if (collator != null && fieldValue1 instanceof String && fieldValue2 instanceof String) {
            partialResult = collator.compare(fieldValue1, fieldValue2);
          } else {
            partialResult = ((Comparable<Object>) fieldValue1).compareTo(fieldValue2);
          }
        } catch (Exception ignore) {
          context.incrementVariable(BasicCommandContext.INVALID_COMPARE_COUNT);
          partialResult = collator.compare("" + fieldValue1, "" + fieldValue2);
        }
      }
      partialResult = factor(partialResult, ordering);

      if (partialResult != 0) {
        break;
      }

      // CONTINUE WITH THE NEXT FIELD
    }

    return partialResult;
  }

  private int factor(final int partialResult, final String iOrdering) {
    if (iOrdering.equals(CommandExecutorSQLSelect.KEYWORD_DESC))
    // INVERT THE ORDERING
    {
      return partialResult * -1;
    }

    return partialResult;
  }
}
