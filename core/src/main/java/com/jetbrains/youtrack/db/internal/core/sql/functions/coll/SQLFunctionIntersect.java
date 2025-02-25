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
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.util.SupportsContains;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemVariable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * This operator can work as aggregate or inline. If only one argument is passed than aggregates,
 * otherwise executes, and returns, the INTERSECTION of the collections received as parameters.
 */
public class SQLFunctionIntersect extends SQLFunctionMultiValueAbstract<Object> {

  public static final String NAME = "intersect";

  public SQLFunctionIntersect() {
    super(NAME, 1, -1);
  }

  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] iParams,
      CommandContext iContext) {
    var value = iParams[0];

    if (value instanceof SQLFilterItemVariable) {
      value = ((SQLFilterItemVariable) value).getValue(iCurrentRecord, iCurrentResult, iContext);
    }

    if (value == null) {
      return Collections.emptySet();
    }

    if (Boolean.TRUE.equals(iContext.getVariable("aggregation"))) {
      // AGGREGATION MODE (STATEFUL)
      if (context == null) {
        // ADD ALL THE ITEMS OF THE FIRST COLLECTION
        if (value instanceof Collection) {
          context = ((Collection) value).iterator();
        } else if (value instanceof Iterator) {
          context = value;
        } else if (value instanceof Iterable) {
          context = ((Iterable) value).iterator();
        } else {
          context = List.of(value).iterator();
        }
      } else {
        Iterator contextIterator = null;
        if (context instanceof Iterator) {
          contextIterator = (Iterator) context;
        } else if (MultiValue.isMultiValue(context)) {
          contextIterator = MultiValue.getMultiValueIterator(context);
        }
        context = intersectWith(contextIterator, value);
      }
      return null;
    }

    // IN-LINE MODE (STATELESS)
    Iterator iterator = MultiValue.getMultiValueIterator(value);

    for (var i = 1; i < iParams.length; ++i) {
      value = iParams[i];

      if (value instanceof SQLFilterItemVariable) {
        value = ((SQLFilterItemVariable) value).getValue(iCurrentRecord, iCurrentResult, iContext);
      }

      if (value != null) {
        value = intersectWith(iterator, value);
        iterator = MultiValue.getMultiValueIterator(value);
      } else {
        return Collections.emptyIterator();
      }
    }

    List result = new ArrayList();
    while (iterator.hasNext()) {
      result.add(iterator.next());
    }
    return result;
  }

  @Override
  public Object getResult() {
    return MultiValue.toSet(context);
  }

  static Collection intersectWith(final Iterator current, Object value) {
    final var tempSet = new HashSet();

    if (!(value instanceof Set)
        && (!(value instanceof SupportsContains)
        || !((SupportsContains) value).supportsFastContains())) {
      value = MultiValue.toSet(value);
    }

    for (var it = current; it.hasNext(); ) {
      final var curr = it.next();
      if (value instanceof RidBag) {
        if (((RidBag) value).contains(((Identifiable) curr).getIdentity())) {
          tempSet.add(curr);
        }
      } else if (value instanceof Collection) {
        if (((Collection) value).contains(curr)) {
          tempSet.add(curr);
        }
      } else if (value instanceof SupportsContains) {
        if (((SupportsContains) value).contains(curr)) {
          tempSet.add(curr);
        }
      }
    }

    return tempSet;
  }

  public String getSyntax(DatabaseSession session) {
    return "intersect(<field>*)";
  }
}
