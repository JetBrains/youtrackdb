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
package com.jetbrains.youtrack.db.internal.core.storage.index.engine;

import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.common.util.RawPair;
import com.jetbrains.youtrack.db.internal.core.config.IndexEngineData;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.index.IndexKeyUpdater;
import com.jetbrains.youtrack.db.internal.core.index.IndexMetadata;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngine;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import java.io.IOException;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/**
 * @since 18.07.13
 */
public class RemoteIndexEngine implements IndexEngine {

  private final String name;
  private final int id;

  public RemoteIndexEngine(int id, String name) {
    this.id = id;
    this.name = name;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String getIndexNameByKey(Object key) {
    return name;
  }

  @Override
  public void init(DatabaseSessionInternal db, IndexMetadata metadata) {
  }

  @Override
  public void flush() {
  }

  public void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException {
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
  }

  @Override
  public void load(IndexEngineData data) {
  }

  @Override
  public boolean remove(Storage storage, AtomicOperation atomicOperation, Object key) {
    return false;
  }

  @Override
  public void clear(Storage storage, AtomicOperation atomicOperation) {
  }

  @Override
  public void close() {
  }

  @Nullable
  @Override
  public Object get(DatabaseSessionInternal db, Object key) {
    return null;
  }

  @Override
  public void put(DatabaseSessionInternal db, AtomicOperation atomicOperation, Object key,
      Object value) {
  }

  @Override
  public void update(
      DatabaseSessionInternal db, AtomicOperation atomicOperation, Object key,
      IndexKeyUpdater<Object> updater) {
  }

  @Override
  public boolean validatedPut(
      AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator) {
    return false;
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesBetween(
      DatabaseSessionInternal db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    throw new UnsupportedOperationException("stream");
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    throw new UnsupportedOperationException("stream");
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    throw new UnsupportedOperationException("stream");
  }

  @Override
  public long size(Storage storage, IndexEngineValuesTransformer transformer) {
    return 0;
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return false;
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer) {
    throw new UnsupportedOperationException("stream");
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Stream<Object> keyStream() {
    throw new UnsupportedOperationException("keyStream");
  }

  @Override
  public boolean acquireAtomicExclusiveLock() {
    throw new UnsupportedOperationException(
        "atomic locking is not supported by remote index engine");
  }
}
