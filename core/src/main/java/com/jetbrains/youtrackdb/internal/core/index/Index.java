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
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.security.SecurityInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquality;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.AbstractStorage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * Basic interface to handle index.
 */
public interface Index extends Comparable<Index> {

  String getDatabaseName();

  /**
   * Types of the keys that index can accept, if index contains composite key, list of types of
   * elements from which this index consist will be returned, otherwise single element (key type
   * obviously) will be returned.
   */
  @Nullable
  PropertyTypeInternal[] getKeyTypes();

  /**
   * Gets the set of records associated with the passed key.
   *
   * @param session the database session
   * @param key     The key to search
   * @return The Record set if found, otherwise an empty Set
   * @deprecated Use {@link Index#getRids(DatabaseSessionEmbedded, Object)} instead, but only as
   * internal (not public) API.
   */
  @Deprecated
  Object get(DatabaseSessionEmbedded session, Object key);

  /**
   * Inserts a new entry in the index. The behaviour depends by the index implementation.
   *
   * @param transaction the current transaction
   * @param key         Entry's key
   * @param value       Entry's value as Identifiable instance
   * @return The index instance itself to allow in chain calls
   */
  Index put(FrontendTransaction transaction, Object key, Identifiable value);

  /**
   * Removes an entry by its key.
   *
   * @param transaction the current transaction
   * @param key         The entry's key to remove
   * @return True if the entry has been found and removed, otherwise false
   */
  boolean remove(FrontendTransaction transaction, Object key);

  /**
   * Removes an entry by its key and value.
   *
   * @param key The entry's key to remove
   * @return True if the entry has been found and removed, otherwise false
   */
  boolean remove(FrontendTransaction transaction, Object key, Identifiable rid);

  @Deprecated
  long getRebuildVersion();

  /**
   * Checks whether this index is currently being rebuilt.
   *
   * @return Indicates whether index is rebuilding at the moment.
   * @see #getRebuildVersion()
   */
  @Deprecated
  boolean isRebuilding();

  /**
   * Delete the index.
   *
   * @return The index instance itself to allow in chain calls
   */
  Index delete(FrontendTransaction transaction);

  /**
   * Returns the index name.
   *
   * @return The name of the index
   */
  String getName();

  /**
   * Returns the type of the index as string.
   */
  String getType();

  /**
   * Returns the engine of the index as string.
   */
  String getAlgorithm();

  /**
   * Returns binary format version for this index. Index format changes during system development
   * but old formats are supported for binary compatibility. This method may be used to detect
   * version of binary format which is used by current index and upgrade index to new one.
   *
   * @return Returns binary format version for this index if possible, otherwise -1.
   */
  int getVersion();

  /**
   * Tells if the index is automatic. Automatic means it's maintained automatically by YouTrackDB.
   * This is the case of indexes created against schema properties. Automatic indexes can always
   * been rebuilt.
   *
   * @return True if the index is automatic, otherwise false
   */
  boolean isAutomatic();

  /**
   * Rebuilds an automatic index.
   *
   * @return The number of entries rebuilt
   */
  long rebuild(DatabaseSessionEmbedded session);

  /**
   * Populate the index with all the existent records.
   */
  long rebuild(DatabaseSessionEmbedded session, ProgressListener progressListener);

  /**
   * Returns the index configuration.
   *
   * @return An EntityImpl object containing all the index properties
   */
  Map<String, Object> getConfiguration(DatabaseSessionEmbedded session);


  IndexDefinition getDefinition();

  /**
   * Returns Names of collections that will be indexed.
   *
   * @return Names of collections that will be indexed.
   */
  Set<String> getCollections();

  Map<String, Object> getMetadata();

  boolean isUnique();

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
   * Add given collection to the list of collections that should be automatically indexed.
   *
   * @param transaction    Currently active database transaction.
   * @param collectionName Collection to add.
   * @param requireEmpty   Whether the collection has to be empty.
   * @return Current index instance.
   */
  Index addCollection(FrontendTransaction transaction, final String collectionName,
      boolean requireEmpty);

  /**
   * Remove given collection from the list of collections that should be automatically indexed.
   *
   * @param transaction    Currently active database transaction.
   * @param collectionName Collection to remove.
   */
  void removeCollection(FrontendTransaction transaction, final String collectionName);

  /**
   * Indicates whether given index can be used to calculate result of {@link QueryOperatorEquality}
   * operators.
   *
   * @return {@code true} if given index can be used to calculate result of
   * {@link QueryOperatorEquality} operators.
   */
  boolean canBeUsedInEqualityOperators();

  IndexMetadata loadMetadata(FrontendTransaction transaction, Map<String, Object> config);

  void close();

  /**
   * Acquires exclusive lock in the active atomic operation running on the current thread for this
   * index.
   */
  boolean acquireAtomicExclusiveLock(AtomicOperation atomicOperation);

  /**
   * Returns the number of entries in this index.
   *
   * @return number of entries in the index.
   */
  long size(DatabaseSessionEmbedded session);

  Stream<RID> getRids(DatabaseSessionEmbedded session, final Object key);

  Stream<RawPair<Object, RID>> stream(DatabaseSessionEmbedded session);

  Stream<RawPair<Object, RID>> descStream(DatabaseSessionEmbedded session);

  Stream<Object> keyStream(AtomicOperation operation);

  /**
   * Returns stream which presents subset of index data between passed in keys.
   *
   * @param session       the database session
   * @param fromKey       Lower border of index data.
   * @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
   * @param toKey         Upper border of index data.
   * @param toInclusive   Indicates whether upper border should be inclusive or exclusive.
   * @param ascOrder      Flag which determines whether data iterated by stream should be in
   *                      ascending or descending order.
   * @return Cursor which presents subset of index data between passed in keys.
   */
  Stream<RawPair<Object, RID>> streamEntriesBetween(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder);

  /**
   * Returns stream which presents data associated with passed in keys.
   *
   * @param session      the database session
   * @param keys         Keys data of which should be returned.
   * @param ascSortOrder Flag which determines whether data iterated by stream should be in
   *                     ascending or descending order.
   * @return stream which presents data associated with passed in keys.
   */
  Stream<RawPair<Object, RID>> streamEntries(DatabaseSessionEmbedded session,
      Collection<?> keys,
      boolean ascSortOrder);

  /**
   * Returns stream which presents subset of data which associated with key which is greater than
   * passed in key.
   *
   * @param session       the database session
   * @param fromKey       Lower border of index data.
   * @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
   * @param ascOrder      Flag which determines whether data iterated by stream should be in
   *                      ascending or descending order.
   * @return stream which presents subset of data which associated with key which is greater than
   * passed in key.
   */
  Stream<RawPair<Object, RID>> streamEntriesMajor(
      DatabaseSessionEmbedded session, Object fromKey, boolean fromInclusive, boolean ascOrder);

  /**
   * Returns stream which presents subset of data which associated with key which is less than
   * passed in key.
   *
   * @param session     the database session
   * @param toKey       Upper border of index data.
   * @param toInclusive Indicates Indicates whether upper border should be inclusive or exclusive.
   * @param ascOrder    Flag which determines whether data iterated by stream should be in ascending
   *                    or descending order.
   * @return stream which presents subset of data which associated with key which is less than
   * passed in key.
   */
  Stream<RawPair<Object, RID>> streamEntriesMinor(
      DatabaseSessionEmbedded session, Object toKey, boolean toInclusive, boolean ascOrder);

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
      String indexClass, DatabaseSessionEmbedded session, SecurityInternal security) {
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

  Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      FrontendTransactionIndexChangesPerKey changes);

  void doPut(DatabaseSessionEmbedded session, AbstractStorage storage, Object key,
      RID rid)
      throws InvalidIndexEngineIdException;

  boolean doRemove(DatabaseSessionEmbedded session, AbstractStorage storage, Object key,
      RID rid)
      throws InvalidIndexEngineIdException;

  boolean doRemove(AbstractStorage storage, Object key, DatabaseSessionEmbedded session)
      throws InvalidIndexEngineIdException;

  Stream<RID> getRidsIgnoreTx(DatabaseSessionEmbedded session, Object key);

  Index create(FrontendTransaction transaction, IndexMetadata metadata);

  int getIndexId();

  @Nullable
  RID getIdentity();
}
