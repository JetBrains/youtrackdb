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

import com.jetbrains.youtrack.db.internal.common.collection.MultiValue;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinitionMultiValue;
import com.jetbrains.youtrack.db.internal.core.index.IndexInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.DocumentHelper;
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

  @SuppressWarnings("unchecked")
  @Override
  public Stream<RawPair<Object, RID>> executeIndexQuery(
      CommandContext iContext, Index index, List<Object> keyParams, boolean ascSortOrder) {
    final IndexDefinition indexDefinition = index.getDefinition();

    final IndexInternal internalIndex = index.getInternal();
    Stream<RawPair<Object, RID>> stream;
    if (!internalIndex.canBeUsedInEqualityOperators()) {
      return null;
    }

    if (indexDefinition.getParamCount() == 1) {
      final Object inKeyValue = keyParams.get(0);
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
        for (Object o : inParams) {
          if (o instanceof EntityImpl entity && entity.getIdentity().getClusterId() < -1) {
            String[] fieldNames = entity.fieldNames();
            if (fieldNames.length == 1) {
              newInParams.add(entity.field(fieldNames[0]));
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

      boolean containsNotCompatibleKey = false;
      for (final Object keyValue : inParams) {
        final Object key;
        if (indexDefinition instanceof IndexDefinitionMultiValue) {
          key =
              ((IndexDefinitionMultiValue) indexDefinition)
                  .createSingleValue(iContext.getDatabase(), SQLHelper.getValue(keyValue));
        } else {
          key = indexDefinition.createValue(iContext.getDatabase(), SQLHelper.getValue(keyValue));
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

      stream = index.getInternal().streamEntries(iContext.getDatabase(), inKeys, ascSortOrder);
    } else {
      final List<Object> partialKey = new ArrayList<Object>();
      partialKey.addAll(keyParams);
      partialKey.remove(keyParams.size() - 1);

      final Object inKeyValue = keyParams.get(keyParams.size() - 1);

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

      final CompositeIndexDefinition compositeIndexDefinition =
          (CompositeIndexDefinition) indexDefinition;

      boolean containsNotCompatibleKey = false;
      for (final Object keyValue : inParams) {
        List<Object> fullKey = new ArrayList<Object>();
        fullKey.addAll(partialKey);
        fullKey.add(keyValue);
        final Object key =
            compositeIndexDefinition.createSingleValue(iContext.getDatabase(), fullKey);
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
        stream = index.getInternal().streamEntries(iContext.getDatabase(), inKeys, ascSortOrder);
      } else {
        return null;
      }
    }

    updateProfiler(iContext, internalIndex, keyParams);
    return stream;
  }

  @Override
  public RID getBeginRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    final Iterable<?> ridCollection;
    final int ridSize;
    if (iRight instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iRight).getRoot(session))) {
      if (iLeft instanceof SQLFilterItem) {
        iLeft = ((SQLFilterItem) iLeft).getValue(null, null, null);
      }

      ridCollection = MultiValue.getMultiValueIterable(iLeft);
      ridSize = MultiValue.getSize(iLeft);
    } else if (iLeft instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof SQLFilterItem) {
        iRight = ((SQLFilterItem) iRight).getValue(null, null, null);
      }
      ridCollection = MultiValue.getMultiValueIterable(iRight);
      ridSize = MultiValue.getSize(iRight);
    } else {
      return null;
    }

    final List<RID> rids = addRangeResults(ridCollection, ridSize);

    return rids == null ? null : Collections.min(rids);
  }

  @Override
  public RID getEndRidRange(DatabaseSession session, Object iLeft, Object iRight) {
    final Iterable<?> ridCollection;
    final int ridSize;
    if (iRight instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iRight).getRoot(session))) {
      if (iLeft instanceof SQLFilterItem) {
        iLeft = ((SQLFilterItem) iLeft).getValue(null, null, null);
      }

      ridCollection = MultiValue.getMultiValueIterable(iLeft);
      ridSize = MultiValue.getSize(iLeft);
    } else if (iLeft instanceof SQLFilterItemField
        && DocumentHelper.ATTRIBUTE_RID.equals(((SQLFilterItemField) iLeft).getRoot(session))) {
      if (iRight instanceof SQLFilterItem) {
        iRight = ((SQLFilterItem) iRight).getValue(null, null, null);
      }

      ridCollection = MultiValue.getMultiValueIterable(iRight);
      ridSize = MultiValue.getSize(iRight);
    } else {
      return null;
    }

    final List<RID> rids = addRangeResults(ridCollection, ridSize);

    return rids == null ? null : Collections.max(rids);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected boolean evaluateExpression(
      final Identifiable iRecord,
      final SQLFilterCondition iCondition,
      final Object iLeft,
      final Object iRight,
      CommandContext iContext) {
    var database = iContext.getDatabase();
    if (MultiValue.isMultiValue(iLeft)) {
      if (iRight instanceof Collection<?>) {
        // AGAINST COLLECTION OF ITEMS
        final Collection<Object> collectionToMatch = (Collection<Object>) iRight;

        boolean found = false;
        for (final Object o1 : MultiValue.getMultiValueIterable(iLeft)) {
          for (final Object o2 : collectionToMatch) {
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

        for (final Object o : MultiValue.getMultiValueIterable(iLeft)) {
          if (QueryOperatorEquals.equals(database, iRight, o)) {
            return true;
          }
        }
      }
    } else if (MultiValue.isMultiValue(iRight)) {

      if (iRight instanceof Set<?>) {
        return ((Set) iRight).contains(iLeft);
      }

      for (final Object o : MultiValue.getMultiValueIterable(iRight)) {
        if (QueryOperatorEquals.equals(database, iLeft, o)) {
          return true;
        }
      }
    } else if (iLeft.getClass().isArray()) {

      for (final Object o : (Object[]) iLeft) {
        if (QueryOperatorEquals.equals(database, iRight, o)) {
          return true;
        }
      }
    } else if (iRight.getClass().isArray()) {

      for (final Object o : (Object[]) iRight) {
        if (QueryOperatorEquals.equals(database, iLeft, o)) {
          return true;
        }
      }
    }

    return iLeft.equals(iRight);
  }

  protected List<RID> addRangeResults(final Iterable<?> ridCollection, final int ridSize) {
    if (ridCollection == null) {
      return null;
    }

    List<RID> rids = null;
    for (Object rid : ridCollection) {
      if (rid instanceof SQLFilterItemParameter) {
        rid = ((SQLFilterItemParameter) rid).getValue(null, null, null);
      }

      if (rid instanceof Identifiable) {
        final RID r = ((Identifiable) rid).getIdentity();
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
