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

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.common.util.RawPair;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexMetadata;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexEngineValuesTransformer;
import com.jetbrains.youtrackdb.internal.core.storage.Storage;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrackdb.internal.lucene.builder.LuceneIndexType;
import com.jetbrains.youtrackdb.internal.lucene.engine.LuceneIndexEngineAbstract;
import com.jetbrains.youtrackdb.internal.lucene.engine.LuceneIndexWriterFactory;
import com.jetbrains.youtrackdb.internal.spatial.factory.SpatialStrategyFactory;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeBuilder;
import com.jetbrains.youtrackdb.internal.spatial.strategy.SpatialQueryBuilder;
import java.io.IOException;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.search.Query;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.store.Directory;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Shape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 */
public abstract class LuceneSpatialIndexEngineAbstract extends LuceneIndexEngineAbstract
    implements LuceneSpatialIndexContainer {

  private static final Logger logger = LoggerFactory.getLogger(
      LuceneSpatialIndexEngineAbstract.class);

  protected final ShapeBuilder factory;
  protected SpatialContext ctx;
  protected SpatialStrategy strategy;

  protected SpatialStrategyFactory strategyFactory;
  protected SpatialQueryBuilder queryStrategy;

  public LuceneSpatialIndexEngineAbstract(
      Storage storage, String indexName, int id, ShapeBuilder factory) {
    super(id, storage, indexName);
    this.ctx = factory.context();
    this.factory = factory;
    strategyFactory = new SpatialStrategyFactory(factory);
    this.queryStrategy = new SpatialQueryBuilder(this, factory);
  }

  @Override
  public void init(DatabaseSessionEmbedded session, IndexMetadata im) {
    super.init(session, im);
    strategy = createSpatialStrategy(session, im.getIndexDefinition(), im.getMetadata());
  }

  protected abstract SpatialStrategy createSpatialStrategy(
      DatabaseSessionEmbedded db, IndexDefinition indexDefinition, Map<String, ?> metadata);

  @Override
  public IndexWriter createIndexWriter(Directory directory) throws IOException {
    var fc = new LuceneIndexWriterFactory();

    LogManager.instance().debug(this, "Creating Lucene index in '%s'...", logger, directory);

    return fc.createIndexWriter(directory, metadata, indexAnalyzer());
  }

  @Override
  public boolean remove(Storage storage, AtomicOperation atomicOperation, Object key) {
    return false;
  }


  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesBetween(
      DatabaseSessionEmbedded db, Object rangeFrom,
      boolean fromInclusive,
      Object rangeTo,
      boolean toInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return Stream.empty();
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMajor(
      Object fromKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return Stream.empty();
  }

  @Override
  public Stream<RawPair<Object, RID>> iterateEntriesMinor(
      Object toKey,
      boolean isInclusive,
      boolean ascSortOrder,
      IndexEngineValuesTransformer transformer) {
    return Stream.empty();
  }

  @Override
  public Stream<RawPair<Object, RID>> stream(
      IndexEngineValuesTransformer valuesTransformer) {
    return Stream.empty();
  }

  @Override
  public Stream<Object> keyStream() {
    return Stream.empty();
  }

  @Override
  public boolean hasRangeQuerySupport() {
    return false;
  }

  protected Document newGeoDocument(Identifiable oIdentifiable, Shape shape,
      Result shapeResult) {

    var ft = new FieldType();
    ft.setIndexOptions(IndexOptions.DOCS);
    ft.setStored(true);

    var doc = new Document();
    doc.add(LuceneIndexType.createOldIdField(oIdentifiable));
    doc.add(LuceneIndexType.createIdField(oIdentifiable, shapeResult));

    for (IndexableField f : strategy.createIndexableFields(shape)) {
      doc.add(f);
    }

    //noinspection deprecation
    doc.add(new StoredField(strategy.getFieldName(), ctx.toString(shape)));
    doc.add(
        new StringField(
            strategy.getFieldName() + "__orient_key_hash",
            LuceneIndexType.hashKey(shapeResult),
            Field.Store.YES));
    return doc;
  }

  @Override
  public Document buildDocument(DatabaseSessionEmbedded session, Object key,
      Identifiable value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Query buildQuery(Object query, DatabaseSessionEmbedded session) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SpatialStrategy strategy() {
    return strategy;
  }
}
