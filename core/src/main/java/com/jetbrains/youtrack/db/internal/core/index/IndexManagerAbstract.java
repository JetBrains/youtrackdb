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

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.concur.resource.CloseableInStorage;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Abstract class to manage indexes.
 */
public interface IndexManagerAbstract extends CloseableInStorage {

  String CONFIG_INDEXES = "indexes";

  void recreateIndexes(DatabaseSessionInternal database);

  default void create() {
    throw new UnsupportedOperationException();
  }

  boolean autoRecreateIndexesAfterCrash(DatabaseSessionInternal session);

  Index createIndex(
      DatabaseSessionInternal session,
      final String iName,
      final String iType,
      IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      final ProgressListener progressListener,
      Map<String, Object> metadata);

  Index createIndex(
      DatabaseSessionInternal session,
      final String iName,
      final String iType,
      IndexDefinition indexDefinition,
      final int[] clusterIdsToIndex,
      final ProgressListener progressListener,
      Map<String, Object> metadata,
      String algorithm);

  void waitTillIndexRestore();

  void removeClassPropertyIndex(DatabaseSessionInternal session, Index idx);

  void dropIndex(DatabaseSessionInternal session, String iIndexName);

  void reload(DatabaseSessionInternal session);

  void addClusterToIndex(DatabaseSessionInternal session, String clusterName, String indexName);

  void load(DatabaseSessionInternal session);

  void removeClusterFromIndex(DatabaseSessionInternal session, String clusterName,
      String indexName);

  void save(DatabaseSessionInternal session);

  void getClassRawIndexes(DatabaseSessionInternal session, String name, Collection<Index> indexes2);

  EntityImpl getConfiguration(DatabaseSessionInternal session);

  Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, String className, Collection<String> fields);

  Set<Index> getClassInvolvedIndexes(
      DatabaseSessionInternal session, String className, String... fields);

  boolean areIndexed(DatabaseSessionInternal session, String className, String... fields);

  boolean areIndexed(DatabaseSessionInternal session, final String className,
      final Collection<String> fields);

  void getClassIndexes(
      DatabaseSessionInternal session, String className, Collection<Index> indexes2);

  Set<Index> getClassIndexes(DatabaseSessionInternal session, String className);

  Index getClassIndex(DatabaseSessionInternal session, String className, String indexName);

  @Nullable
  IndexUnique getClassUniqueIndex(DatabaseSessionInternal session, String className);

  void create(DatabaseSessionInternal session);

  Collection<? extends Index> getIndexes(DatabaseSessionInternal session);

  Index getIndex(DatabaseSessionInternal session, String iName);

  boolean existsIndex(DatabaseSessionInternal session, String iName);

  EntityImpl getEntity(DatabaseSessionInternal session);

  EntityImpl toStream(DatabaseSessionInternal session);

  RID getIdentity();
}
