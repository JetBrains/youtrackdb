/*
 *
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * <p>
 * *
 */
package com.jetbrains.youtrack.db.internal.spatial.engine;

import static com.jetbrains.youtrack.db.internal.lucene.builder.LuceneQueryBuilder.EMPTY_METADATA;

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.ContextualRecordId;
import com.jetbrains.youtrack.db.internal.core.index.CompositeKey;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexEngineException;
import com.jetbrains.youtrack.db.internal.core.index.IndexKeyUpdater;
import com.jetbrains.youtrack.db.internal.core.index.engine.IndexEngineValidator;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperation;
import com.jetbrains.youtrack.db.internal.lucene.collections.LuceneResultSet;
import com.jetbrains.youtrack.db.internal.lucene.query.LuceneQueryContext;
import com.jetbrains.youtrack.db.internal.lucene.tx.LuceneTxChanges;
import com.jetbrains.youtrack.db.internal.spatial.collections.SpatialCompositeKey;
import com.jetbrains.youtrack.db.internal.spatial.query.SpatialQueryContext;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeBuilder;
import com.jetbrains.youtrack.db.internal.spatial.shape.legacy.ShapeBuilderLegacy;
import com.jetbrains.youtrack.db.internal.spatial.shape.legacy.ShapeBuilderLegacyImpl;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.spatial.SpatialStrategy;
import org.apache.lucene.spatial.prefix.RecursivePrefixTreeStrategy;
import org.apache.lucene.spatial.prefix.tree.GeohashPrefixTree;
import org.apache.lucene.spatial.query.SpatialArgs;
import org.apache.lucene.spatial.query.SpatialOperation;
import org.locationtech.spatial4j.distance.DistanceUtils;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Shape;

/**
 *
 */
public class LuceneLegacySpatialIndexEngine extends LuceneSpatialIndexEngineAbstract {

  private final ShapeBuilderLegacy<Shape> legacyBuilder = ShapeBuilderLegacyImpl.INSTANCE;

  public LuceneLegacySpatialIndexEngine(
      Storage storage, String indexName, int id, ShapeBuilder factory) {
    super(storage, indexName, id, factory);
  }

  private Set<Identifiable> legacySearch(DatabaseSessionInternal session, Object key,
      LuceneTxChanges changes) throws IOException {
    if (key instanceof SpatialCompositeKey newKey) {

      final var strategy =
          newKey.getOperation() != null ? newKey.getOperation() : SpatialOperation.Intersects;

      if (SpatialOperation.Intersects.equals(strategy)) {
        return searchIntersect(session, newKey, newKey.getMaxDistance(), newKey.getContext(),
            changes);
      } else if (SpatialOperation.IsWithin.equals(strategy)) {
        return searchWithin(newKey, newKey.getContext(), changes);
      }

    } else if (key instanceof CompositeKey) {
      return searchIntersect(session, (CompositeKey) key, 0, null, changes);
    }
    throw new IndexEngineException(session.getDatabaseName(), "Unknown key" + key, null);
  }

  private Set<Identifiable> searchIntersect(
      DatabaseSessionInternal db, CompositeKey key, double distance,
      CommandContext context,
      LuceneTxChanges changes)
      throws IOException {

    double lat = PropertyTypeInternal.convert(db, key.getKeys().get(0), Double.class);
    double lng = PropertyTypeInternal.convert(db, key.getKeys().get(1), Double.class);
    var operation = SpatialOperation.Intersects;

    @SuppressWarnings("deprecation")
    var p = ctx.makePoint(lng, lat);
    @SuppressWarnings("deprecation")
    var args =
        new SpatialArgs(
            operation,
            ctx.makeCircle(
                lng,
                lat,
                DistanceUtils.dist2Degrees(distance, DistanceUtils.EARTH_MEAN_RADIUS_KM)));
    var filterQuery = strategy.makeQuery(args);

    var searcher = searcher(db.getStorage());
    var valueSource = strategy.makeDistanceValueSource(p);
    var distSort = new Sort(valueSource.getSortField(false)).rewrite(searcher);

    var q =
        new BooleanQuery.Builder()
            .add(filterQuery, BooleanClause.Occur.MUST)
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.SHOULD)
            .build();

    var queryContext =
        new SpatialQueryContext(context, searcher, q, Arrays.asList(distSort.getSort()))
            .setSpatialArgs(args)
            .withChanges(changes);
    return new LuceneResultSet(db, this, queryContext, EMPTY_METADATA);
  }

  @Nullable
  private Set<Identifiable> searchWithin(
      SpatialCompositeKey key, CommandContext context, LuceneTxChanges changes) {

    var db = context.getDatabaseSession();
    var shape = legacyBuilder.makeShape(db, key, ctx);
    if (shape == null) {
      return null;
    }
    var args = new SpatialArgs(SpatialOperation.IsWithin, shape);
    var searcher = searcher(db.getStorage());

    var filterQuery = strategy.makeQuery(args);

    var query =
        new BooleanQuery.Builder()
            .add(filterQuery, BooleanClause.Occur.MUST)
            .add(new MatchAllDocsQuery(), BooleanClause.Occur.SHOULD)
            .build();

    var queryContext =
        new SpatialQueryContext(context, searcher, query).withChanges(changes);

    return new LuceneResultSet(db, this, queryContext, EMPTY_METADATA);
  }

  @Override
  public void onRecordAddedToResultSet(
      LuceneQueryContext queryContext,
      ContextualRecordId recordId,
      Document doc,
      ScoreDoc score) {

    var spatialContext = (SpatialQueryContext) queryContext;
    if (spatialContext.spatialArgs != null) {
      @SuppressWarnings("deprecation")
      var docPoint = (Point) ctx.readShape(doc.get(strategy.getFieldName()));
      var docDistDEG =
          ctx.getDistCalc().distance(spatialContext.spatialArgs.getShape().getCenter(), docPoint);
      final var docDistInKM =
          DistanceUtils.degrees2Dist(docDistDEG, DistanceUtils.EARTH_EQUATORIAL_RADIUS_KM);
      Map<String, Object> data = new HashMap<String, Object>();
      data.put("distance", docDistInKM);
      recordId.setContext(data);
    }
  }

  @Override
  public Set<Identifiable> getInTx(DatabaseSessionInternal session, Object key,
      LuceneTxChanges changes) {
    try {
      updateLastAccess();
      openIfClosed(session.getStorage());
      return legacySearch(session, key, changes);
    } catch (IOException e) {
      throw BaseException.wrapException(new DatabaseException(session, "Error while searching"), e,
          session);
    }
  }

  @Override
  public Object get(DatabaseSessionInternal db, Object key) {
    return getInTx(db, key, null);
  }

  @Override
  public void put(DatabaseSessionInternal db, AtomicOperation atomicOperation, Object key,
      Object value) {

    if (key instanceof CompositeKey compositeKey) {
      updateLastAccess();
      openIfClosed(db.getStorage());
      addDocument(
          newGeoDocument(
              (Identifiable) value,
              legacyBuilder.makeShape(db, compositeKey, ctx),
              compositeKey.toEntity(db)));
    }
  }

  @Override
  public void update(
      DatabaseSessionInternal db, AtomicOperation atomicOperation, Object key,
      IndexKeyUpdater<Object> updater) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean validatedPut(
      AtomicOperation atomicOperation,
      Object key,
      com.jetbrains.youtrack.db.api.record.RID value,
      IndexEngineValidator<Object, com.jetbrains.youtrack.db.api.record.RID> validator) {
    throw new UnsupportedOperationException(
        "Validated put is not supported by LuceneLegacySpatialIndexEngine");
  }

  @Override
  public Document buildDocument(DatabaseSessionInternal session, Object key,
      Identifiable value) {
    return newGeoDocument(
        value,
        legacyBuilder.makeShape(session, (CompositeKey) key, ctx),
        ((CompositeKey) key).toEntity(session));
  }

  @Override
  protected SpatialStrategy createSpatialStrategy(
      DatabaseSessionInternal db, IndexDefinition indexDefinition, Map<String, ?> metadata) {
    return new RecursivePrefixTreeStrategy(new GeohashPrefixTree(ctx, 11), "location");
  }

  @Override
  public boolean isLegacy() {
    return true;
  }
}
