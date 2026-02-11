/*
 *
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS"
 * BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 * <p>
 * *
 */
package com.jetbrains.youtrackdb.internal.spatial.engine;

import com.jetbrains.youtrackdb.api.exception.BaseException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.config.IndexEngineData;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.IndexKeyUpdater;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.lucene.engine.LuceneIndexEngine;
import com.jetbrains.youtrackdb.internal.lucene.query.LuceneQueryContext;
import com.jetbrains.youtrackdb.internal.lucene.tx.LuceneTxChanges;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeFactory;
import java.io.IOException;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.spatial.SpatialStrategy;

public class LuceneSpatialIndexEngineDelegator
    implements LuceneIndexEngine, LuceneSpatialIndexContainer {

  private final Storage storage;
  private final String indexName;
  private LuceneSpatialIndexEngineAbstract delegate;
  private final int id;

  public LuceneSpatialIndexEngineDelegator(int id, String name, Storage storage, int version) {
    this.id = id;

    this.indexName = name;
    this.storage = storage;
  }

  @Override
  public int getId() {
    return id;
  }

  @Override
  public void init(DatabaseSessionEmbedded session, IndexMetadata im) {
    if (delegate == null) {
      if (SchemaClass.INDEX_TYPE.SPATIAL.name().equalsIgnoreCase(im.getType())) {
        if (im.getIndexDefinition().getProperties().size() > 1) {
          delegate =
              new LuceneLegacySpatialIndexEngine(this.storage, indexName, id,
                  ShapeFactory.INSTANCE);
        } else {
          delegate =
              new LuceneGeoSpatialIndexEngine(this.storage, indexName, id, ShapeFactory.INSTANCE);
        }

        delegate.init(session, im);
      } else {
        throw new IllegalStateException("Invalid index type " + im.getType());
      }
    }
  }

  @Override
  public void flush() {
    delegate.flush();
  }

  @Override
  public void create(AtomicOperation atomicOperation, IndexEngineData data) throws IOException {
  }

  @Override
  public void delete(AtomicOperation atomicOperation) {
    if (delegate != null) {
      delegate.delete(atomicOperation);
    }
  }

  @Override
  public void load(IndexEngineData data) {
    if (delegate != null) {
      delegate.load(data);
    }
  }

  @Override
  public boolean remove(Storage storage, AtomicOperation atomicOperation, Object key) {
    return delegate.remove(storage, atomicOperation, key);
  }

  @Override
  public void clear(Storage storage, AtomicOperation atomicOperation) {

    delegate.clear(storage, atomicOperation);
  }

  @Override
  public void close() {

    delegate.close();
  }

  @Override
  public Object get(DatabaseSessionEmbedded db, Object key) {
    return delegate.get(db, key);
  }

  @Override
  public void put(DatabaseSessionEmbedded session, AtomicOperation atomicOperation, Object key,
      Object value) {

    try {
      delegate.put(session, atomicOperation, key, value);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(session,
              "Error during insertion of key " + key + " in index " + indexName),
          e, session);
    }
  }

  @Override
  public void update(
      DatabaseSessionEmbedded session, AtomicOperation atomicOperation, Object key,
      IndexKeyUpdater<Object> updater) {
    try {
      delegate.update(session, atomicOperation, key, updater);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException(session,
              "Error during update of key " + key + " in index " + indexName),
          e, session);
    }
  }

  @Override
  public boolean validatedPut(
      AtomicOperation atomicOperation,
      Object key,
      RID value,
      IndexEngineValidator<Object, RID> validator) {
    try {
      return delegate.validatedPut(atomicOperation, key, value, validator);
    } catch (IOException e) {
      throw BaseException.wrapException(
          new IndexException("Error during insertion of key " + key + " in index " + indexName),
          e, (String) null);
    }
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesBetween(
      DatabaseSessionEmbedded db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return delegate.iterateEntriesBetween(db
        , rangeFrom, fromInclusive, rangeTo, toInclusive, ascSortOrder, transformer);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return delegate.iterateEntriesMajor(fromKey, isInclusive, ascSortOrder, transformer);
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return delegate.iterateEntriesMinor(toKey, isInclusive, ascSortOrder, transformer);
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(IndexEngineValuesTransformer valuesTransformer) {
    return delegate.stream(valuesTransformer);
  }

  @Override
  public Stream<RawPair<Object, RID>> descStream(
      IndexEngineValuesTransformer valuesTransformer) {
    return delegate.descStream(valuesTransformer);
  }

  @Override
  public Stream<Object> keyStream() {
    return delegate.keyStream();
  }

  @Override
  public long size(Storage storage, IndexEngineValuesTransformer transformer) {
    return delegate.size(storage, transformer);
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return delegate.hasRangeQuerySupport();
  }

  @Override
  public String getName() {
    return delegate.getName();
  }

  @Override
  public String indexName() {
    return indexName;
  }

  @Override
  public void onRecordAddedToResultSet(
      LuceneQueryContext queryContext,
      ContextualRecordId recordId,
      Document ret,
      ScoreDoc score) {
    delegate.onRecordAddedToResultSet(queryContext, recordId, ret, score);
  }

  @Override
  public Document buildDocument(DatabaseSessionEmbedded session, Object key,
      Identifiable value) {
    return delegate.buildDocument(session, key, value);
  }

  @Override
  public Query buildQuery(Object query, DatabaseSessionEmbedded session) {
    return delegate.buildQuery(query, session);
  }

  @Override
  public Analyzer indexAnalyzer() {
    return delegate.indexAnalyzer();
  }

  @Override
  public Analyzer queryAnalyzer() {
    return delegate.queryAnalyzer();
  }

  @Override
  public boolean remove(Storage storage, Object key) {
    return delegate.remove(storage, key);
  }

  @Override
  public boolean remove(Storage storage, Object key, Identifiable value) {
    return delegate.remove(storage, key, value);
  }

  @Override
  public IndexSearcher searcher(Storage storage) {
    return delegate.searcher(storage);
  }

  @Override
  public void release(Storage storage, IndexSearcher searcher) {
    delegate.release(storage, searcher);
  }

  @Override
  public SpatialStrategy strategy() {
    return delegate.strategy();
  }

  @Override
  public boolean isLegacy() {
    return delegate.isLegacy();
  }

  @Override
  public Set<Identifiable> getInTx(DatabaseSessionEmbedded session, Object key,
      LuceneTxChanges changes) {
    return delegate.getInTx(session, key, changes);
  }

  @Override
  public long sizeInTx(LuceneTxChanges changes, Storage storage) {
    return delegate.sizeInTx(changes, storage);
  }

  @Override
  public LuceneTxChanges buildTxChanges() throws IOException {
    return delegate.buildTxChanges();
  }

  @Override
  public Query deleteQuery(Storage storage, Object key, Identifiable value) {
    return delegate.deleteQuery(storage, key, value);
  }

  @Override
  public boolean isCollectionIndex() {
    return delegate.isCollectionIndex();
  }

  @Override
  public void freeze(DatabaseSessionEmbedded db, boolean throwException) {
    delegate.freeze(db, throwException);
  }

  @Override
  public void release(DatabaseSessionEmbedded db) {
    delegate.release(db);
  }

  @Override
  public boolean acquireAtomicExclusiveLock() {
    return true; // do nothing
  }

  @Override
  public String getIndexNameByKey(Object key) {
    return delegate.getIndexNameByKey(key);
  }
}
