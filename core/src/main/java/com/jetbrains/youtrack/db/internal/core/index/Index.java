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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

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
  PropertyType[] getKeyTypes();

  /**
   * Gets the set of records associated with the passed key.
   *
   * @param session
   * @param key     The key to search
   * @return The Record set if found, otherwise an empty Set
   * @deprecated Use {@link IndexInternal#getRids(DatabaseSessionInternal, Object)} instead, but
   * only as internal (not public) API.
   */
  @Deprecated
  Object get(DatabaseSessionInternal session, Object key);

  /**
   * Inserts a new entry in the index. The behaviour depends by the index implementation.
   *
   * @param db
   * @param key   Entry's key
   * @param value Entry's value as Identifiable instance
   * @return The index instance itself to allow in chain calls
   */
  Index put(DatabaseSessionInternal db, Object key, Identifiable value);

  /**
   * Removes an entry by its key.
   *
   * @param session
   * @param key     The entry's key to remove
   * @return True if the entry has been found and removed, otherwise false
   */
  boolean remove(DatabaseSessionInternal session, Object key);

  /**
   * Removes an entry by its key and value.
   *
   * @param session
   * @param key     The entry's key to remove
   * @return True if the entry has been found and removed, otherwise false
   */
  boolean remove(DatabaseSessionInternal session, Object key, Identifiable rid);

  /**
   * Clears the index removing all the entries in one shot.
   *
   * @return The index instance itself to allow in chain calls
   * @deprecated Manual indexes are deprecated and will be removed
   */
  @Deprecated
  Index clear(DatabaseSessionInternal session);

  /**
   * @return number of entries in the index.
   * @deprecated Use {@link IndexInternal#size(DatabaseSessionInternal)} instead. This API only for
   * internal use !.
   */
  @Deprecated
  long getSize(DatabaseSessionInternal session);

  /**
   * Counts the entries for the key.
   *
   * @deprecated Use <code>index.getInternal().getRids(key).count()</code> instead. This API only
   * for internal use !.
   */
  @Deprecated
  long count(DatabaseSessionInternal session, Object iKey);

  /**
   * @return Number of keys in index
   * @deprecated Use <code>index.getInternal().getRids(key).distinct().count()</code> instead. This
   * API only for internal use !.
   */
  @Deprecated
  long getKeySize();

  /**
   * Flushes in-memory changes to disk.
   */
  @Deprecated
  void flush();

  @Deprecated
  long getRebuildVersion();

  /**
   * @return Indicates whether index is rebuilding at the moment.
   * @see #getRebuildVersion()
   */
  @Deprecated
  boolean isRebuilding();

  /**
   * @deprecated Use <code>index.getInternal().stream().findFirst().map(pair->pair.first)</code>
   * instead. This API only for internal use !
   */
  @Deprecated
  Object getFirstKey();

  /**
   * @deprecated Use <code>index.getInternal().descStream().findFirst().map(pair->pair.first)</code>
   * instead. This API only for internal use !
   */
  @Deprecated
  Object getLastKey(DatabaseSessionInternal session);

  /**
   * @deprecated Use <code>index.getInternal().stream()</code> instead. This API only for internal
   * use !
   */
  @Deprecated
  IndexCursor cursor(DatabaseSessionInternal session);

  /**
   * @deprecated Use <code>index.getInternal().descStream()</code> instead. This API only for
   * internal use !
   */
  @Deprecated
  IndexCursor descCursor(DatabaseSessionInternal session);

  /**
   * @deprecated Use <code>index.getInternal().keyStream()</code> instead. This API only for
   * internal use !
   */
  @Deprecated
  IndexKeyCursor keyCursor();

  /**
   * Delete the index.
   *
   * @return The index instance itself to allow in chain calls
   */
  Index delete(DatabaseSessionInternal session);

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
  long rebuild(DatabaseSessionInternal session);

  /**
   * Populate the index with all the existent records.
   */
  long rebuild(DatabaseSessionInternal session, ProgressListener iProgressListener);

  /**
   * Returns the index configuration.
   *
   * @return An EntityImpl object containing all the index properties
   */
  Map<String, ?> getConfiguration(DatabaseSessionInternal session);

  /**
   * Returns the internal index used.
   */
  IndexInternal getInternal();

  IndexDefinition getDefinition();

  /**
   * Returns Names of clusters that will be indexed.
   *
   * @return Names of clusters that will be indexed.
   */
  Set<String> getClusters();

  /**
   * Returns cursor which presents data associated with passed in keys.
   *
   * @param session
   * @param keys         Keys data of which should be returned.
   * @param ascSortOrder Flag which determines whether data iterated by cursor should be in
   *                     ascending or descending order.
   * @return cursor which presents data associated with passed in keys.
   * @deprecated Use
   * {@link IndexInternal#streamEntries(DatabaseSessionInternal, Collection, boolean)} instead. This
   * API only for internal use !
   */
  @Deprecated
  IndexCursor iterateEntries(DatabaseSessionInternal session, Collection<?> keys,
      boolean ascSortOrder);

  /**
   * Returns cursor which presents subset of index data between passed in keys.
   *
   * @param session
   * @param fromKey       Lower border of index data.
   * @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
   * @param toKey         Upper border of index data.
   * @param toInclusive   Indicates whether upper border should be inclusive or exclusive.
   * @param ascOrder      Flag which determines whether data iterated by cursor should be in
   *                      ascending or descending order.
   * @return Cursor which presents subset of index data between passed in keys.
   * @deprecated Use
   * {@link IndexInternal#streamEntriesBetween(DatabaseSessionInternal, Object, boolean, Object,
   * boolean, boolean)} instead. This API only * for internal use !
   */
  @Deprecated
  IndexCursor iterateEntriesBetween(
      DatabaseSessionInternal session, Object fromKey, boolean fromInclusive, Object toKey,
      boolean toInclusive, boolean ascOrder);

  /**
   * Returns cursor which presents subset of data which associated with key which is greater than
   * passed in key.
   *
   * @param session
   * @param fromKey       Lower border of index data.
   * @param fromInclusive Indicates whether lower border should be inclusive or exclusive.
   * @param ascOrder      Flag which determines whether data iterated by cursor should be in
   *                      ascending or descending order.
   * @return cursor which presents subset of data which associated with key which is greater than
   * passed in key.
   * @deprecated Use
   * {@link IndexInternal#streamEntriesMajor(DatabaseSessionInternal, Object, boolean, boolean)}
   * instead. This API only for internal use !
   */
  @Deprecated
  IndexCursor iterateEntriesMajor(DatabaseSessionInternal session, Object fromKey,
      boolean fromInclusive, boolean ascOrder);

  /**
   * Returns cursor which presents subset of data which associated with key which is less than
   * passed in key.
   *
   * @param session
   * @param toKey       Upper border of index data.
   * @param toInclusive Indicates Indicates whether upper border should be inclusive or exclusive.
   * @param ascOrder    Flag which determines whether data iterated by cursor should be in ascending
   *                    or descending order.
   * @return cursor which presents subset of data which associated with key which is less than
   * passed in key.
   * @deprecated Use
   * {@link IndexInternal#streamEntriesMinor(DatabaseSessionInternal, Object, boolean, boolean)}
   * instead. This API only for internal use !
   */
  @Deprecated
  IndexCursor iterateEntriesMinor(DatabaseSessionInternal session, Object toKey,
      boolean toInclusive, boolean ascOrder);

  Map<String, Object> getMetadata();

  boolean supportsOrderedIterations();

  boolean isUnique();
}
