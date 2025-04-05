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
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseRecordThreadLocal;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyAccess;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.security.SecurityResourceProperty;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityInternalUtils;
import com.jetbrains.youtrack.db.internal.core.sql.operator.QueryOperatorEquality;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.AbstractPaginatedStorage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
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
  String VALUE_CONTAINER_ALGORITHM = "valueContainerAlgorithm";
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
   * @param iConfig EntityImpl instance containing the configuration
   */
  boolean loadFromConfiguration(DatabaseSessionInternal session, EntityImpl iConfig);

  /**
   * Saves the index configuration to disk.
   *
   * @return The configuration as EntityImpl instance
   * @see Index#getConfiguration(DatabaseSessionInternal)
   */
  EntityImpl updateConfiguration(DatabaseSessionInternal session);

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

  IndexMetadata loadMetadata(EntityImpl iConfig);

  void close();

  /**
   * Returns the index name for a key. The name is always the current index name, but in cases where
   * the index supports key-based sharding.
   *
   * @param key the index key.
   * @return The index name involved
   */
  String getIndexNameByKey(Object key);

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * index.
   *
   * <p>If this index supports a more narrow locking, for example key-based sharding, it may use
   * the provided {@code key} to infer a more narrow lock scope, but that is not a requirement.
   *
   * @param key the index key to lock.
   * @return {@code true} if this index was locked entirely, {@code false} if this index locking is
   * sensitive to the provided {@code key} and only some subset of this index was locked.
   */
  boolean acquireAtomicExclusiveLock(Object key);

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

  static Identifiable securityFilterOnRead(Index idx, Identifiable item) {
    if (idx.getDefinition() == null) {
      return item;
    }
    String indexClass = idx.getDefinition().getClassName();
    if (indexClass == null) {
      return item;
    }
    DatabaseSessionInternal db = DatabaseRecordThreadLocal.instance().getIfDefined();
    if (db == null) {
      return item;
    }
    SecurityInternal security = db.getSharedContext().getSecurity();
    if (isReadRestrictedBySecurityPolicy(indexClass, db, security)) {
      try {
        item = item.getRecord();
      } catch (RecordNotFoundException e) {
        item = null;
      }
    }
    if (item == null) {
      return null;
    }
    if (idx.getDefinition().getFields().size() == 1) {
      String indexProp = idx.getDefinition().getFields().get(0);
      if (isLabelSecurityDefined(db, security, indexClass, indexProp)) {
        try {
          item = item.getRecord();
        } catch (RecordNotFoundException e) {
          item = null;
        }
        if (item == null) {
          return null;
        }
        if (!(item instanceof EntityImpl)) {
          return item;
        }
        PropertyAccess access = EntityInternalUtils.getPropertyAccess((EntityImpl) item);
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
    SchemaClass clazz = session.getClass(indexClass);
    if (clazz == null) {
      return false;
    }
    clazz.getAllSubclasses(session).forEach(x -> classesToCheck.add(x.getName()));
    clazz.getAllSuperClasses(session).forEach(x -> classesToCheck.add(x.getName()));
    Set<SecurityResourceProperty> allFilteredProperties =
        security.getAllFilteredProperties(session);

    for (String className : classesToCheck) {
      Optional<SecurityResourceProperty> item =
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
      String indexClass, DatabaseSessionInternal db, SecurityInternal security) {
    if (security.isReadRestrictedBySecurityPolicy(db, "database.class." + indexClass)) {
      return true;
    }

    SchemaClass clazz = db.getClass(indexClass);
    if (clazz != null) {
      Collection<SchemaClass> sub = clazz.getSubclasses(db);
      for (SchemaClass subClass : sub) {
        if (isReadRestrictedBySecurityPolicy(subClass.getName(), db, security)) {
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
