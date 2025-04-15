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
package com.jetbrains.youtrack.db.internal.spatial.index;

import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.exception.InvalidIndexEngineIdException;
import com.jetbrains.youtrack.db.internal.core.storage.Storage;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransaction;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChanges;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey;
import com.jetbrains.youtrack.db.internal.core.tx.FrontendTransactionIndexChangesPerKey.TransactionIndexEntry;
import com.jetbrains.youtrack.db.internal.lucene.index.LuceneIndexNotUnique;
import com.jetbrains.youtrack.db.internal.spatial.engine.LuceneSpatialIndexContainer;
import com.jetbrains.youtrack.db.internal.spatial.shape.ShapeFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Geometry;

public class LuceneSpatialIndex extends LuceneIndexNotUnique {

  private final ShapeFactory shapeFactory = ShapeFactory.INSTANCE;

  public LuceneSpatialIndex(@Nullable RID identity, @Nonnull FrontendTransaction transaction,
      @Nonnull Storage storage) {
    super(identity, transaction, storage);
  }

  public LuceneSpatialIndex(@Nonnull Storage storage) {
    super(storage);
  }

  @Override
  public LuceneIndexNotUnique put(FrontendTransaction transaction, Object key,
      Identifiable value) {
    if (key == null) {
      return this;
    }
    return super.put(transaction, key, value);
  }

  @Override
  public Iterable<TransactionIndexEntry> interpretTxKeyChanges(
      final FrontendTransactionIndexChangesPerKey changes) {

    try {
      return storage.callIndexEngine(
          false,
          indexId,
          engine -> {
            if (((LuceneSpatialIndexContainer) engine).isLegacy()) {
              return LuceneSpatialIndex.super.interpretTxKeyChanges(changes);
            } else {
              return interpretAsSpatial(changes);
            }
          });
    } catch (InvalidIndexEngineIdException e) {
      e.printStackTrace();
    }

    return super.interpretTxKeyChanges(changes);
  }

  @Override
  protected Object encodeKey(Object key) {
    if (key instanceof Result result) {
      var shape = shapeFactory.fromResult(result);
      return shapeFactory.toGeometry(shape);
    }
    return key;
  }

  @Override
  protected Object decodeKey(Object key, DatabaseSessionInternal session) {
    if (key instanceof Geometry geom) {
      return shapeFactory.toEmbeddedEntity(geom, session);
    }
    return key;
  }

  private static Iterable<TransactionIndexEntry> interpretAsSpatial(
      FrontendTransactionIndexChangesPerKey item) {
    // 1. Handle common fast paths.

    var entries = item.getEntriesAsList();
    Map<Identifiable, Integer> counters = new LinkedHashMap<>();

    for (var entry : entries) {

      var counter = counters.get(entry.getValue());
      if (counter == null) {
        counter = 0;
      }
      switch (entry.getOperation()) {
        case PUT:
          counter++;
          break;
        case REMOVE:
          counter--;
          break;
        case CLEAR:
          break;
      }
      counters.put(entry.getValue(), counter);
    }

    var changes = new FrontendTransactionIndexChangesPerKey(
        item.key);

    for (var entry : counters.entrySet()) {
      var oIdentifiable = entry.getKey();
      switch (entry.getValue()) {
        case 1:
          changes.add(oIdentifiable, FrontendTransactionIndexChanges.OPERATION.PUT);
          break;
        case -1:
          changes.add(oIdentifiable, FrontendTransactionIndexChanges.OPERATION.REMOVE);
          break;
      }
    }
    return changes.getEntriesAsList();
  }
}
