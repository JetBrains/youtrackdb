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

import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.ProxedResource;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IndexManagerProxy extends ProxedResource<IndexManagerAbstract>
    implements IndexManager {

  public IndexManagerProxy(
      final IndexManagerAbstract iDelegate, final DatabaseSessionInternal iDatabase) {
    super(iDelegate, iDatabase);
  }

  public void load() {
  }

  /**
   * Force reloading of indexes.
   */
  public IndexManagerProxy reload() {
    delegate.load(session);
    return this;
  }

  public void create() {
    delegate.create(session);
  }

  public Collection<? extends Index> getIndexes() {
    return delegate.getIndexes(session);
  }

  public Index getIndex(final String iName) {
    return delegate.getIndex(session, iName);
  }

  public boolean existsIndex(final String iName) {
    return delegate.existsIndex(session, iName);
  }

  public Index createIndex(
      final String iName,
      final String iType,
      final IndexDefinition indexDefinition,
      final int[] collectionIdsToIndex,
      final ProgressListener progressListener,
      final Map<String, Object> metadata) {
    return delegate.createIndex(
        session, iName, iType, indexDefinition, collectionIdsToIndex, progressListener, metadata);
  }

  @Override
  public Index createIndex(
      final String iName,
      final String iType,
      final IndexDefinition iIndexDefinition,
      final int[] iCollectionIdsToIndex,
      final ProgressListener progressListener,
      final Map<String, Object> metadata,
      final String algorithm) {
    return delegate.createIndex(
        session,
        iName,
        iType,
        iIndexDefinition,
        iCollectionIdsToIndex,
        progressListener,
        metadata,
        algorithm);
  }


  public IndexManager dropIndex(final String iIndexName) {
    delegate.dropIndex(session, iIndexName);
    return this;
  }

  @Override
  public List<Map<String, Object>> getConfiguration() {
    return delegate.getIndexesConfiguration(session);
  }


  public Set<Index> getClassInvolvedIndexes(
      final String className, final Collection<String> fields) {
    return delegate.getClassInvolvedIndexes(session, className, fields);
  }

  public Set<Index> getClassInvolvedIndexes(final String className, final String... fields) {
    return delegate.getClassInvolvedIndexes(session, className, fields);
  }

  public boolean areIndexed(final String className, final Collection<String> fields) {
    return delegate.areIndexed(session, className, fields);
  }

  public boolean areIndexed(final String className, final String... fields) {
    return delegate.areIndexed(session, className, fields);
  }

  public Set<Index> getClassIndexes(final String className) {
    return delegate.getClassIndexes(session, className);
  }

  @Override
  public void getClassIndexes(final String className, final Collection<Index> indexes) {
    delegate.getClassIndexes(session, className, indexes);
  }

  public Index getClassIndex(final String className, final String indexName) {
    return delegate.getClassIndex(session, className, indexName);
  }

  @Override
  public IndexUnique getClassUniqueIndex(final String className) {
    return delegate.getClassUniqueIndex(session, className);
  }

  @Override
  public void recreateIndexes() {
    delegate.recreateIndexes(session);
  }

  @Override
  public void waitTillIndexRestore() {
    delegate.waitTillIndexRestore();
  }

  @Override
  public boolean autoRecreateIndexesAfterCrash() {
    return delegate.autoRecreateIndexesAfterCrash(session);
  }

  @Override
  public void addCollectionToIndex(String collectionName,
      String indexName) {
    assert session.assertIfNotActive();
    delegate.addCollectionToIndex(session, collectionName, indexName);
  }

  @Override
  public void removeCollectionFromIndex(String collectionName,
      String indexName) {
    delegate.removeCollectionFromIndex(this.session, collectionName,
        indexName);
  }

  @Override
  public void removeClassPropertyIndex(Index idx) {
      delegate.removeClassPropertyIndex(this.session, idx);
  }

  public IndexManagerAbstract delegate() {
    return delegate;
  }
}
