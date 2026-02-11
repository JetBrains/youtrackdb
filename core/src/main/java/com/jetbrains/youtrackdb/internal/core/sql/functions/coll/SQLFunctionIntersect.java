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
import com.jetbrains.youtrackdb.internal.common.util.SupportsContains;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.sql.executor.RidSet;
import com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterItemVariable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * This operator can work as aggregate or inline. If only one argument is passed than aggregates,
 * otherwise executes, and returns, the INTERSECTION of the collections received as parameters.
 */
public class SQLFunctionIntersect extends SQLFunctionMultiValueAbstract<Object> {

  public static final String NAME = "intersect";

  public SQLFunctionIntersect() {
    super(NAME, 1, -1);
  }

  @Override
  @Nullable
  public Object execute(
      Object iThis,
      final Result iCurrentRecord,
      Object iCurrentResult,
      final Object[] params,
      CommandContext ctx) {

    for (var p : params) {
      if (p == null || (p instanceof Collection<?> col && col.isEmpty())) {
        return Set.of();
      }
    }

    var value = params[0];

    if (value instanceof SQLFilterItemVariable fi) {
      value = fi.getValue(iCurrentRecord, iCurrentResult, ctx);
    }

    if (Boolean.TRUE.equals(ctx.getVariable("aggregation"))) {
      // AGGREGATION MODE (STATEFUL)
      if (context == null) {
        // ADD ALL THE ITEMS OF THE FIRST COLLECTION
        switch (value) {
          case Collection collection -> context = collection;
          case Iterator iterator -> context = value;
          case Iterable iterable -> context = iterable.iterator();
          case null, default -> context = List.of(value).iterator();
        }
      } else {
        if (context instanceof Set<?> contextSet) {
          context = intersectWith(contextSet, value);
        } else {
          Iterator<?> contextIterator = null;
          if (context instanceof Iterator) {
            contextIterator = (Iterator<?>) context;
          } else if (MultiValue.isMultiValue(context)) {
            contextIterator = MultiValue.getMultiValueIterator(context);
          }

          context = intersectWith(contextIterator, value);
        }
      }

      return null;
    }

    if (params.length == 1) {
      // using LinkedHasSet here to 1) preserve the order, 2) remove duplicates.
      // IN-LINE MODE (STATELESS)
      var iterator = MultiValue.getMultiValueIterator(value);
      final var result = new LinkedHashSet<>();
      while (iterator.hasNext()) {
        result.add(iterator.next());
      }

      // still need to return a list here, because returning a Set can
      // break the order, as some of our code performs collection copying based on
      // "instanceof Set" check.
      return new ArrayList<>(result);
    } else {
      var currentResult = value;

      for (var i = 1; i < params.length; ++i) {
        value = params[i];

        if (value instanceof SQLFilterItemVariable fi) {
          value = fi.getValue(iCurrentRecord, iCurrentResult, ctx);
        }

        if (value instanceof Set<?> set) {
          currentResult = intersectWith(set, currentResult);
        } else {
          var iterator = MultiValue.getMultiValueIterator(currentResult);
          currentResult = intersectWith(iterator, value);
        }
      }

      final List<Object> result = new ArrayList<>();
      var iterator = MultiValue.getMultiValueIterator(currentResult);
      while (iterator.hasNext()) {
        result.add(iterator.next());
      }

      return result;
    }
  }

  @Override
  public Object getResult() {
    return MultiValue.toSet(context);
  }

  static Collection intersectWith(final Iterator current, Object value) {
    final var tempSet = new LinkedHashSet<>();

    if (!(value instanceof Set)
        && (!(value instanceof SupportsContains)
        || !((SupportsContains) value).supportsFastContains())) {
      if (value instanceof ResultSet resultSet) {
        var ids = new RidSet();
        var nonIds = new HashSet<>();

        while (resultSet.hasNext()) {
          var result = resultSet.next();
          if (result.isIdentifiable()) {
            if (nonIds.isEmpty()) {
              ids.add(result.getIdentity());
            } else {
              nonIds.add(result.getIdentity());
            }
          } else {
            nonIds.add(result);
          }
        }
        if (!nonIds.isEmpty()) {
          if (!ids.isEmpty()) {
            nonIds.addAll(ids);
          }
          value = nonIds;
        } else {
          value = ids;
        }
      } else {
        value = MultiValue.toSet(value);
      }
    }

    for (var it = current; it.hasNext(); ) {
      var curr = it.next();
      if (curr instanceof Identifiable identifiable) {
        curr = identifiable.getIdentity();
      } else if (curr instanceof Result result && result.isIdentifiable()) {
        curr = result.getIdentity();
      }

      switch (value) {
        case LinkBag rids -> {
          if (rids.contains(((Identifiable) curr).getIdentity())) {
            tempSet.add(curr);
          }
        }
        case Collection collection -> {
          if (collection.contains(curr)) {
            tempSet.add(curr);
          }
        }
        case SupportsContains supportsContains -> {
          if (supportsContains.contains(curr)) {
            tempSet.add(curr);
          }
        }
        default -> {
          throw new IllegalArgumentException(
              "Intersections are not supported with given value " + value);
        }
      }
    }

    return tempSet;
  }

  static LinkedHashSet<?> intersectWith(final Set<?> current, Object value) {
    Iterator<?> iter;
    if (value instanceof Iterable<?> iterable) {
      iter = iterable.iterator();
    } else if (value instanceof Iterator<?> iterator) {
      iter = iterator;
    } else {
      iter = MultiValue.getMultiValueIterator(value);
    }

    final var tempSet = new LinkedHashSet<>();
    while (iter.hasNext()) {
      var item = iter.next();
      if (current.contains(item)) {
        tempSet.add(item);
      }
    }

    return tempSet;
  }

  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return "intersect(<field>*)";
  }
}
