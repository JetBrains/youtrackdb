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
package com.jetbrains.youtrack.db.internal.core.index;

import com.jetbrains.youtrack.db.api.exception.RecordNotFoundException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorEquality;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Interface to handle index.
 */
public interface IndexInternal extends Index {

  String CONFIG_KEYTYPE = "keyType";
  String CONFIG_AUTOMATIC = "automatic";
  String CONFIG_TYPE = "type";
  String ALGORITHM = "algorithm";
  String CONFIG_NAME = "name";
  String INDEX_DEFINITION = "indexDefinition";
  String INDEX_DEFINITION_CLASS = "indexDefinitionClass";
  String INDEX_VERSION = "indexVersion";
  String METADATA = "metadata";
  String MERGE_KEYS = "mergeKeys";

  Object getCollatingValue(final Object key);

  /**
   * Loads the index giving the configuration.
   *
   * @param session
   * @param config  EntityImpl instance containing the configuration
   */
  boolean loadFromConfiguration(DatabaseSessionInternal session, Map<String, ?> config);

  /**
   * Saves the index configuration to disk.
   *
   * @return The configuration as EntityImpl instance
   * @see Index#getConfiguration(DatabaseSessionInternal)
   */
  Map<String, ?> updateConfiguration(DatabaseSessionInternal session);

  /**
   * Add given cluster to the list of clusters that should be automatically indexed.
   *
   * @param session
   * @param iClusterName Cluster to add.
   * @return Current index instance.
   */
  Index addCluster(DatabaseSessionInternal session, final String iClusterName);

  /**
   * Remove given cluster from the list of clusters that should be automatically indexed.
   *
   * @param session
   * @param iClusterName Cluster to remove.
   */
  void removeCluster(DatabaseSessionInternal session, final String iClusterName);

  /**
   * Indicates whether given index can be used to calculate result of {@link QueryOperatorEquality}
   * operators.
   *
   * @return {@code true} if given index can be used to calculate result of
   * {@link QueryOperatorEquality} operators.
   */
  boolean canBeUsedInEqualityOperators();

  boolean hasRangeQuerySupport();

  IndexMetadata loadMetadata(DatabaseSessionInternal session, Map<String, ?> config);

  void close();

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * index.
   */
  boolean acquireAtomicExclusiveLock();

  /**
   * @return number of entries in the index.
   */
  long size(DatabaseSessionInternal session);

  Stream<RID> getRids(DatabaseSessionInternal session, final Object key);

  Stream<RawPair<Object, RID>> stream(DatabaseSessionInternal session);

  Stream<RawPair<Object, RID>> descStream(DatabaseSessionInternal session);

  Stream<Object> keyStream();

  /**
   * Returns stream which presents subset of index data between passed in keys.
   *
   * @param session
   * @param fromKey       Lower border of index data.
   * @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
   * @param toKey         Upper border of index data.
   * @param toInclusive   Indicates whether upper border should be inclusive or exclusive.
   * @param ascOrder      Flag which determines whether data iterated by stream should be in
   *                      ascending or descending order.
   * @return Cursor which presents subset of index data between passed in keys.
   */
  Stream<RawPair<Object, RID>> streamEntriesBetween(
      DatabaseSessionInternal session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder);

  /**
   * Returns stream which presents data associated with passed in keys.
   *
   * @param session
   * @param keys         Keys data of which should be returned.
   * @param ascSortOrder Flag which determines whether data iterated by stream should be in
   *                     ascending or descending order.
   * @return stream which presents data associated with passed in keys.
   */
  Stream<RawPair<Object, RID>> streamEntries(DatabaseSessionInternal session,
      Collection<?> keys,
      boolean ascSortOrder);

  /**
   * Returns stream which presents subset of data which associated with key which is greater than
   * passed in key.
   *
   * @param session
   * @param fromKey       Lower border of index data.
   * @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
   * @param ascOrder      Flag which determines whether data iterated by stream should be in
   *                      ascending or descending order.
   * @return stream which presents subset of data which associated with key which is greater than
   * passed in key.
   */
  Stream<RawPair<Object, RID>> streamEntriesMajor(
      DatabaseSessionInternal session, Object fromKey, boolean fromInclusive, boolean ascOrder);

  /**
   * Returns stream which presents subset of data which associated with key which is less than
   * passed in key.
   *
   * @param session
   * @param toKey       Upper border of index data.
   * @param toInclusive Indicates Indicates whether upper border should be inclusive or exclusive.
   * @param ascOrder    Flag which determines whether data iterated by stream should be in ascending
   *                    or descending order.
   * @return stream which presents subset of data which associated with key which is less than
   * passed in key.
   */
  Stream<RawPair<Object, RID>> streamEntriesMinor(
      DatabaseSessionInternal session, Object toKey, boolean toInclusive, boolean ascOrder);

  static Identifiable securityFilterOnRead(DatabaseSessionInternal session, Index idx,
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
        item = item.getRecord(session);
      } catch (RecordNotFoundException e) {
        item = null;
      }
    }
    if (item == null) {
      return null;
    }
    if (idx.getDefinition().getFields().size() == 1) {
      var indexProp = idx.getDefinition().getFields().get(0);
      if (isLabelSecurityDefined(session, security, indexClass, indexProp)) {
        try {
          item = item.getRecord(session);
        } catch (RecordNotFoundException e) {
          item = null;
        }
        if (item == null) {
          return null;
        }
        if (!(item instanceof EntityImpl)) {
          return item;
        }
        var access = EntityInternalUtils.getPropertyAccess((EntityImpl) item);
        if (access != null && !access.isReadable(indexProp)) {
          return null;
        }
      }
    }
    return item;
  }

  static boolean isLabelSecurityDefined(
      DatabaseSessionInternal session,
      SecurityInternal security,
      String indexClass,
      String propertyName) {
    Set<String> classesToCheck = new HashSet<>();
    classesToCheck.add(indexClass);
    var clazz = session.getClass(indexClass);
    if (clazz == null) {
      return false;
    }
    clazz.getAllSubclasses().forEach(x -> classesToCheck.add(x.getName()));
    clazz.getAllSuperClasses().forEach(x -> classesToCheck.add(x.getName()));
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
      String indexClass, DatabaseSessionInternal session, SecurityInternal security) {
    if (security.isReadRestrictedBySecurityPolicy(session, "database.class." + indexClass)) {
      return true;
    }

    var clazz = session.getClass(indexClass);
    if (clazz != null) {
      var sub = clazz.getSubclasses();
      for (var subClass : sub) {
        if (isReadRestrictedBySecurityPolicy(subClass.getName(), session, security)) {
          return true;
        }
      }
    }

    return false;
  }

  boolean isNativeTxSupported();

  Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes);

  void doPut(DatabaseSessionInternal session, AbstractPaginatedStorage storage, Object key,
      RID rid)
      throws InvalidIndexEngineIdException;

  boolean doRemove(DatabaseSessionInternal session, AbstractPaginatedStorage storage, Object key,
      RID rid)
      throws InvalidIndexEngineIdException;

  boolean doRemove(AbstractPaginatedStorage storage, Object key)
      throws InvalidIndexEngineIdException;

  Stream<RID> getRidsIgnoreTx(DatabaseSessionInternal session, Object key);

  Index create(DatabaseSessionInternal session, IndexMetadata metadata, boolean rebuild,
      ProgressListener progressListener);

  int getIndexId();
}
