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

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * Manager of indexes.
 */
public interface IndexManager {

  /**
   * Load index manager data from database.
   *
   * <p>IMPORTANT! Only for internal usage.
   */
  @Deprecated
  void load();

  /**
   * Creates a document where index manager configuration is saved and creates a "dictionary"
   * index.
   *
   * <p>IMPORTANT! Only for internal usage.
   */
  @Deprecated
  void create();

  @Deprecated
  IndexManager reload();

  /**
   * Drops all indexes and creates them from scratch.
   *
   * @deprecated Manual indexes are deprecated and will be removed
   */
  @Deprecated
  void recreateIndexes();

  /**
   * Returns all indexes registered in database.
   *
   * @return list of registered indexes.
   */
  Collection<? extends Index> getIndexes();

  /**
   * Index by specified name.
   *
   * @param iName name of index
   * @return index if one registered in database or null otherwise.
   */
  Index getIndex(final String iName);


  /**
   * Checks if index with specified name exists in database.
   *
   * @param iName name of index.
   * @return true if index with specified name exists, false otherwise.
   */
  boolean existsIndex(final String iName);

  /**
   * Creates a new index with default algorithm.
   *
   * @param iName             - name of index
   * @param iType             - index type. Specified by plugged index factories.
   * @param indexDefinition   metadata that describes index structure
   * @param clusterIdsToIndex ids of clusters that index should track for changes.
   * @param progressListener  listener to track task progress.
   * @param metadata          document with additional properties that can be used by index engine.
   * @return a newly created index instance
   */
  Index createIndex(
      final String iName,
      final String iType,
      IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      final ProgressListener progressListener,
      Map<String, Object> metadata);

  /**
   * Creates a new index.
   *
   * <p>May require quite a long time if big amount of data should be indexed.
   *
   * @param iName             name of index
   * @param iType             index type. Specified by plugged index factories.
   * @param indexDefinition   metadata that describes index structure
   * @param clusterIdsToIndex ids of clusters that index should track for changes.
   * @param progressListener  listener to track task progress.
   * @param metadata          document with additional properties that can be used by index engine.
   * @param algorithm         tip to an index factory what algorithm to use
   * @return a newly created index instance
   */
  Index createIndex(
      final String iName,
      final String iType,
      IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      final ProgressListener progressListener,
      Map<String, Object> metadata,
      String algorithm);

  /**
   * Drop index with specified name. Do nothing if such index does not exists.
   *
   * @param iIndexName the name of index to drop
   * @return this
   */
  IndexManager dropIndex(final String iIndexName);


  /**
   * Returns a record where configurations are saved.
   *
   * <p>IMPORTANT! Only for internal usage.
   *
   * @return a document that used to store index configurations.
   */
  EntityImpl getConfiguration(DatabaseSession session);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param className name of class which is indexed.
   * @param fields    Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<Index> getClassInvolvedIndexes(String className, Collection<String> fields);

  /**
   * Returns list of indexes that contain passed in fields names as their first keys. Order of
   * fields does not matter.
   *
   * <p>All indexes sorted by their count of parameters in ascending order. If there are indexes
   * for the given set of fields in super class they will be taken into account.
   *
   * @param className name of class which is indexed.
   * @param fields    Field names.
   * @return list of indexes that contain passed in fields names as their first keys.
   */
  Set<Index> getClassInvolvedIndexes(String className, String... fields);

  /**
   * Indicates whether given fields are contained as first key fields in class indexes. Order of
   * fields does not matter. If there are indexes for the given set of fields in super class they
   * will be taken into account.
   *
   * @param className name of class which contain {@code fields}.
   * @param fields    Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   */
  boolean areIndexed(String className, Collection<String> fields);

  /**
   * @param className name of class which contain {@code fields}.
   * @param fields    Field names.
   * @return <code>true</code> if given fields are contained as first key fields in class indexes.
   * @see #areIndexed(String, java.util.Collection)
   */
  boolean areIndexed(String className, String... fields);

  /**
   * Gets indexes for a specified class (excluding indexes for sub-classes).
   *
   * @param className name of class which is indexed.
   * @return a set of indexes related to specified class
   */
  Set<Index> getClassIndexes(String className);

  /**
   * Gets indexes for a specified class (excluding indexes for sub-classes).
   *
   * @param className name of class which is indexed.
   * @param indexes   Collection of indexes where to add all the indexes
   */
  void getClassIndexes(String className, Collection<Index> indexes);

  /**
   * Returns the unique index for a class, if any.
   */
  IndexUnique getClassUniqueIndex(String className);

  /**
   * Searches for index for a specified class with specified name.
   *
   * @param className name of class which is indexed.
   * @param indexName name of index.
   * @return an index instance or null if such does not exist.
   */
  Index getClassIndex(String className, String indexName);

  /**
   * Blocks current thread till indexes will be restored.
   *
   */
  void waitTillIndexRestore();

  /**
   * Checks if indexes should be automatically recreated.
   *
   * <p>IMPORTANT! Only for internal usage.
   *
   * @return true if crash is happened and database configured to automatically recreate indexes
   * after crash.
   */
  @Deprecated
  boolean autoRecreateIndexesAfterCrash();

  /**
   * Adds a cluster to tracked cluster list of specified index.
   *
   * <p>IMPORTANT! Only for internal usage.
   *
   * @param session
   * @param clusterName cluster to add.
   * @param indexName   name of index.
   */
  void addClusterToIndex(DatabaseSession session, String clusterName, String indexName);

  /**
   * Removes a cluster from tracked cluster list of specified index.
   *
   * <p>IMPORTANT! Only for internal usage.
   *
   * @param session
   * @param clusterName cluster to remove.
   * @param indexName   name of index.
   */
  void removeClusterFromIndex(DatabaseSession session, String clusterName, String indexName);

  /**
   * Saves index manager data.
   *
   * <p>IMPORTANT! Only for internal usage.
   */
  @Deprecated
  IndexManager save(DatabaseSession session);

  /**
   * Removes index from class-property map.
   *
   * <p>IMPORTANT! Only for internal usage.
   *
   * @param session
   * @param idx     index to remove.
   */
  void removeClassPropertyIndex(DatabaseSession session, Index idx);
}
