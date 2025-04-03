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
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.sql.SQLHelper;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterCondition;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItem;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemField;
import com.jetbrains.youtrack.db.internal.core.sql.filter.SQLFilterItemParameter;
import com.jetbrains.youtrack.db.internal.core.sql.query.LegacyResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
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
  @SuppressWarnings("unchecked")
  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    final var indexDefinition = index.getDefinition();

    Stream<RawPair<Object, RID>> stream;
    if (!index.canBeUsedInEqualityOperators()) {
      return null;
    }

    if (indexDefinition.getParamCount() == 1) {
      final var inKeyValue = keyParams.get(0);
      Collection<Object> inParams;
      if (inKeyValue instanceof List<?>) {
        inParams = (Collection<Object>) inKeyValue;
      } else if (inKeyValue instanceof SQLFilterItem) {
        inParams =
            (Collection<Object>) ((SQLFilterItem) inKeyValue).getValue(null, null, iContext);
      } else {
        inParams = Collections.singleton(inKeyValue);
      }

      if (inParams instanceof LegacyResultSet) { // manage IN (subquery)
        Set newInParams = new HashSet();
        for (var o : inParams) {
          if (o instanceof EntityImpl entity && entity.getIdentity().getCollectionId() < -1) {
            var fieldNames = entity.propertyNames();
            if (fieldNames.length == 1) {
              newInParams.add(entity.getProperty(fieldNames[0]));
            } else {
              newInParams.add(o);
            }
          } else {
            newInParams.add(o);
          }
        }
        inParams = newInParams;
      }
      final List<Object> inKeys = new ArrayList<Object>();

      var containsNotCompatibleKey = false;
      for (final var keyValue : inParams) {
        final Object key;
        if (indexDefinition instanceof IndexDefinitionMultiValue) {
          key =
              ((IndexDefinitionMultiValue) indexDefinition)
                  .createSingleValue(iContext.getDatabaseSession(), SQLHelper.getValue(keyValue));
        } else {
          key = indexDefinition.createValue(iContext.getDatabaseSession(),
              SQLHelper.getValue(keyValue));
        }

        if (key == null) {
          containsNotCompatibleKey = true;
          break;
        }

        inKeys.add(key);
      }
      if (containsNotCompatibleKey) {
        return null;
      }

      stream = index
          .streamEntries(iContext.getDatabaseSession(), inKeys, ascSortOrder);
    } else {
      final List<Object> partialKey = new ArrayList<Object>();
      partialKey.addAll(keyParams);
      partialKey.remove(keyParams.size() - 1);

      final var inKeyValue = keyParams.get(keyParams.size() - 1);

      final Collection<Object> inParams;
      if (inKeyValue instanceof List<?>) {
        inParams = (Collection<Object>) inKeyValue;
      } else if (inKeyValue instanceof SQLFilterItem) {
        inParams =
            (Collection<Object>) ((SQLFilterItem) inKeyValue).getValue(null, null, iContext);
      } else {
        throw new IllegalArgumentException("Key '" + inKeyValue + "' is not valid");
      }

      final List<Object> inKeys = new ArrayList<Object>();

      final var compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      var containsNotCompatibleKey = false;
      for (final var keyValue : inParams) {
        List<Object> fullKey = new ArrayList<Object>();
        fullKey.addAll(partialKey);
        fullKey.add(keyValue);
        final Object key =
            compositeIndexDefinition.createSingleValue(iContext.getDatabaseSession(), fullKey);
        if (key == null) {
          containsNotCompatibleKey = true;
          break;
        }

        inKeys.add(key);
      }
      if (containsNotCompatibleKey) {
        return null;
      }

      if (indexDefinition.getParamCount() == keyParams.size()) {
        stream = index
            .streamEntries(iContext.getDatabaseSession(), inKeys, ascSortOrder);
      } else {
        return null;
      }
    }

    updateProfiler(iContext, index, keyParams);
    return stream;
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
      ridSize = MultiValue.getSize(iLeft);
    } else if (iLeft instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof SQLFilterItem) {
        iRight = ((SQLFilterItem) iRight).getValue(null, null, null);
      }
      ridCollection = MultiValue.getMultiValueIterable(iRight);
      ridSize = MultiValue.getSize(iRight);
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
      ridSize = MultiValue.getSize(iLeft);
    } else if (iLeft instanceof SQLFilterItemField
        && EntityHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof SQLFilterItem) {
        iRight = ((SQLFilterItem) iRight).getValue(null, null, null);
      }

      ridCollection = MultiValue.getMultiValueIterable(iRight);
      ridSize = MultiValue.getSize(iRight);
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
