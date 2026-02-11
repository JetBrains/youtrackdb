/*
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.CollectionSelectionStrategy;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

/**
 * Returns the collection selecting the most empty between all configured collections.
 */
public class BalancedCollectionSelectionStrategy implements CollectionSelectionStrategy {

  public static final String NAME = "balanced";
  protected static final long REFRESH_TIMEOUT = 5000;
  protected long lastCount = -1;
  protected int smallerCollectionId = -1;

  public int getCollection(DatabaseSessionEmbedded session, final SchemaClass iClass,
      final EntityImpl entity) {
    return getCollection(session, iClass, iClass.getCollectionIds(), entity);
  }

  public int getCollection(DatabaseSessionEmbedded session, final SchemaClass iClass, final int[] collections,
      final EntityImpl entity) {
    if (collections.length == 1)
    // ONLY ONE: RETURN THE FIRST ONE
    {
      return collections[0];
    }

    var sessionInternal = (DatabaseSessionEmbedded) session;
    if (lastCount < 0 || System.currentTimeMillis() - lastCount > REFRESH_TIMEOUT) {
      // REFRESH COUNTERS
      var min = Long.MAX_VALUE;

      for (var collection : collections) {
        final var count = sessionInternal.countCollectionElements(collection);
        if (count < min) {
          min = count;
          smallerCollectionId = collection;
        }
      }
      lastCount = System.currentTimeMillis();
    }

    return smallerCollectionId;
  }

  @Override
  public String getName() {
    return NAME;
  }
}
