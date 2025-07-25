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
package com.jetbrains.youtrack.db.internal.core.sql.operator;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemParameter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * IN operator.
 */
public class QueryOperatorIn extends QueryOperatorEqualityNotNulls {

  public QueryOperatorIn() {
    super("IN", 5, false);
  }

  @Override
  public IndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
    return IndexReuseType.INDEX_METHOD;
  }

  @Nullable
  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    final Iterable<?> ridCollection;
    final int ridSize;
    if (iRight instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iRight).getRoot(session))) {
      if (iLeft instanceof SQLFilterItem) {
        iLeft = ((SQLFilterItem) iLeft).getValue(null, null, null);
      }

      ridCollection = MultiValue.getMultiValueIterable(iLeft);
      ridSize = (int) MultiValue.getSize(iLeft);
    } else if (iLeft instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof SQLFilterItem) {
        iRight = ((SQLFilterItem) iRight).getValue(null, null, null);
      }
      ridCollection = MultiValue.getMultiValueIterable(iRight);
      ridSize = (int) MultiValue.getSize(iRight);
    } else {
      return null;
    }

    final var rids = addRangeResults(ridCollection, ridSize);

    return rids == null ? null : Collections.min(rids);
  }

  @Nullable
  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    final Iterable<?> ridCollection;
    final int ridSize;
    if (iRight instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iRight).getRoot(session))) {
      if (iLeft instanceof SQLFilterItem) {
        iLeft = ((SQLFilterItem) iLeft).getValue(null, null, null);
      }

      ridCollection = MultiValue.getMultiValueIterable(iLeft);
      ridSize = (int) MultiValue.getSize(iLeft);
    } else if (iLeft instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof SQLFilterItem) {
        iRight = ((SQLFilterItem) iRight).getValue(null, null, null);
      }

      ridCollection = MultiValue.getMultiValueIterable(iRight);
      ridSize = (int) MultiValue.getSize(iRight);
    } else {
      return null;
    }

    final var rids = addRangeResults(ridCollection, ridSize);

    return rids == null ? null : Collections.max(rids);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Result iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    var database = iContext.getDatabaseSession();
    if (MultiValue.isMultiValue(iLeft)) {
      if (iRight instanceof Collection<?>) {
        // AGAINST COLLECTION OF ITEMS
        final var collectionToMatch = (Collection<Object>) iRight;

        var found = false;
        for (final var o1 : MultiValue.getMultiValueIterable(iLeft)) {
          for (final var o2 : collectionToMatch) {
            if (QueryOperatorEquals.equals(database, o1, o2)) {
              found = true;
              break;
            }
          }
        }
        return found;
      } else {
        // AGAINST SINGLE ITEM
        if (iLeft instanceof Set<?>) {
          return ((Set) iLeft).contains(iRight);
        }

        for (final var o : MultiValue.getMultiValueIterable(iLeft)) {
          if (QueryOperatorEquals.equals(database, iRight, o)) {
            return true;
          }
        }
      }
    } else if (MultiValue.isMultiValue(iRight)) {

      if (iRight instanceof Set<?>) {
        return ((Set) iRight).contains(iLeft);
      }

      for (final var o : MultiValue.getMultiValueIterable(iRight)) {
        if (QueryOperatorEquals.equals(database, iLeft, o)) {
          return true;
        }
      }
    } else if (iLeft.getClass().isArray()) {

      for (final var o : (Object[]) iLeft) {
        if (QueryOperatorEquals.equals(database, iRight, o)) {
          return true;
        }
      }
    } else if (iRight.getClass().isArray()) {

      for (final var o : (Object[]) iRight) {
        if (QueryOperatorEquals.equals(database, iLeft, o)) {
          return true;
        }
      }
    }

    return iLeft.equals(iRight);
  }

  @Nullable
  protected List<RID> addRangeResults(final Iterable<?> ridCollection, final int ridSize) {
    if (ridCollection == null) {
      return null;
    }

    List<RID> rids = null;
    for (var rid : ridCollection) {
      if (rid instanceof SQLFilterItemParameter) {
        rid = ((SQLFilterItemParameter) rid).getValue(null, null, null);
      }

      if (rid instanceof Identifiable) {
        final var r = ((Identifiable) rid).getIdentity();
        if (r.isPersistent()) {
          if (rids == null)
          // LAZY CREATE IT
          {
            rids = new ArrayList<RID>(ridSize);
          }
          rids.add(r);
        }
      }
    }
    return rids;
  }
}
