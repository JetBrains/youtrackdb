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
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquality;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.tinkerpop.gremlin.structure.util.CloseableIterator;

/// Basic interface to handle snapshot of current index in schema. In this context snapshot of index
/// means that metadata of snapshot are frozen and used to interact with storage to reflect changes
/// in entities in storage indexes.
public interface Index extends Comparable<Index> {

  /// Inserts a new entry in the storage index. The behaviour depends by the index implementation.
  ///
  /// @param key   Entry's key
  /// @param value Entry's value as Identifiable instance
  /// @return The index instance itself to allow in chain calls
  Index put(FrontendTransaction transaction, Object key, Identifiable value);

  /// Removes an entry by its key.
  ///
  /// @param key The entry's key to remove
  void remove(FrontendTransaction transaction, Object key);

  /// Removes an entry by its key and value.
  ///
  /// @param key The entry's key to remove
  void remove(FrontendTransaction transaction, Object key, Identifiable rid);

  /// Returns the index name.
  ///
  /// @return The name of the index
  String getName();

  /// Returns the type of the index as string.
  INDEX_TYPE getType();

  /// Rebuilds an automatic index.
  void rebuild(DatabaseSessionEmbedded session);


  IndexDefinition getDefinition();

  boolean isUnique();

  Object getCollatingValue(final Object key);

  /// Indicates whether given index can be used to calculate result of [QueryOperatorEquality]
  /// operators.
  ///
  /// @return `true` if given index can be used to calculate result of [QueryOperatorEquality]
  /// operators.
  boolean canBeUsedInEqualityOperators();

  /// @return number of entries in the index.
  long size(DatabaseSessionEmbedded session);

  CloseableIterator<RID> getRids(DatabaseSessionEmbedded session, final Object key);

  CloseableIterator<RawPair<Object, RID>> ascEntries(DatabaseSessionEmbedded session);

  CloseableIterator<RawPair<Object, RID>> descEntries(DatabaseSessionEmbedded session);

  CloseableIterator<Object> keys();

  /// Returns [Iterator] which presents subset of index data between passed in keys.
  ///
  /// @param fromKey       Lower border of index data.
  /// @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
  /// @param toKey         Upper border of index data.
  /// @param toInclusive   Indicates whether upper border should be inclusive or exclusive.
  /// @param ascOrder      Flag which determines whether data iterated should be in ascending or
  ///                      descending order.
  /// @return [Iterator] which presents subset of index data between passed in keys.
  CloseableIterator<RawPair<Object, RID>> entriesBetween(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder);

  /// Returns [Iterator] which presents data associated with passed in keys.
  ///
  /// @param keys         Keys data of which should be returned.
  /// @param ascSortOrder Flag which determines whether data iterated should be in ascending or
  ///                     descending order.
  /// @return iterator which presents data associated with passed in keys.
  CloseableIterator<RawPair<Object, RID>> entries(DatabaseSessionEmbedded session,
      Collection<?> keys,
      boolean ascSortOrder);

  /// Returns [Iterator] which presents subset of data which associated with key which is greater
  /// than passed in key.
  ///
  /// @param fromKey       Lower border of index data.
  /// @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
  /// @param ascOrder      Flag which determines whether data iterated should be in ascending or
  ///                      descending order.
  /// @return [Iterator] which presents subset of data which associated with key which is greater
  /// than passed in key.
  CloseableIterator<RawPair<Object, RID>> entriesMajor(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, boolean ascOrder);

  /// Returns [Iterator] which presents subset of data which associated with key which is less than
  /// passed in key.
  ///
  /// @param toKey       Upper border of index data.
  /// @param toInclusive Indicates Indicates whether upper border should be inclusive or exclusive.
  /// @param ascOrder    Flag which determines whether data iterated should be in ascending or
  ///                    descending order.
  /// @return [Iterator] which presents subset of data which associated with key which is less than
  /// passed in key.
  CloseableIterator<RawPair<Object, RID>> entriesMinor(
      DatabaseSessionEmbedded session, Object toKey, boolean toInclusive, boolean ascOrder);

  boolean supportsOrderedIterations();

  @Nullable
  static Identifiable securityFilterOnRead(DatabaseSessionEmbedded session, Index idx,
      Identifiable item) {
    if (idx.getDefinition() == null) {
      return item;
    }
    var indexClass = idx.getDefinition().getClassName();
    if (indexClass == null) {
      return item;
    }

    if (session == null) {
      return item;
    }

    var security = session.getSharedContext().getSecurity();
    if (isReadRestrictedBySecurityPolicy(indexClass, session, security)) {
      try {
        var transaction = session.getActiveTransaction();
        item = transaction.load(item);
      } catch (RecordNotFoundException e) {
        item = null;
      }
    }
    if (item == null) {
      return null;
    }
    if (idx.getDefinition().getProperties().size() == 1) {
      var indexProp = idx.getDefinition().getProperties().getFirst();
      if (isLabelSecurityDefined(session, security, indexClass, indexProp)) {
        try {
          var transaction = session.getActiveTransaction();
          item = transaction.load(item);
        } catch (RecordNotFoundException e) {
          item = null;
        }
        if (item == null) {
          return null;
        }
        if (!(item instanceof EntityImpl entity)) {
          return item;
        }
        if (!entity.checkPropertyAccess(indexProp)) {
          return null;
        }
      }
    }
    return item;
  }

  static boolean isLabelSecurityDefined(
      DatabaseSessionEmbedded session,
      SecurityInternal security,
      String indexClass,
      String propertyName) {
    Set<String> classesToCheck = new HashSet<>();
    classesToCheck.add(indexClass);
    var clazz = session.getMetadata().getFastImmutableSchema().getClass(indexClass);
    if (clazz == null) {
      return false;
    }
    clazz.getDescendantClasses().forEach(x -> classesToCheck.add(x.getName()));
    clazz.getAscendantClasses().forEach(x -> classesToCheck.add(x.getName()));
    var allFilteredProperties =
        security.getAllFilteredProperties(session);

    for (var className : classesToCheck) {
      var item =
          allFilteredProperties.stream()
              .filter(x -> x.getClassName().equalsIgnoreCase(className))
              .filter(x -> x.getPropertyName().equals(propertyName))
              .findFirst();

      if (item.isPresent()) {
        return true;
      }
    }
    return false;
  }

  static boolean isReadRestrictedBySecurityPolicy(
      String indexClass, DatabaseSessionEmbedded session, SecurityInternal security) {
    if (security.isReadRestrictedBySecurityPolicy(session, "database.class." + indexClass)) {
      return true;
    }

    var clazz = session.getMetadata().getFastImmutableSchema().getClass(indexClass);
    if (clazz != null) {
      var sub = clazz.getChildClasses();
      for (var subClass : sub) {
        if (isReadRestrictedBySecurityPolicy(subClass.getName(), session, security)) {
          return true;
        }
      }
    }

    return false;
  }

  Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes);

  void doPut(DatabaseSessionInternal session, AbstractStorage storage, Object key,
      RID rid);

  void doRemove(DatabaseSessionInternal session, AbstractStorage storage, Object key,
      RID rid);

  void doRemove(AbstractStorage storage, Object key, DatabaseSessionInternal session);

  CloseableIterator<RID> getRidsIgnoreTx(DatabaseSessionEmbedded session, Object key);

  int getIndexId();
}
