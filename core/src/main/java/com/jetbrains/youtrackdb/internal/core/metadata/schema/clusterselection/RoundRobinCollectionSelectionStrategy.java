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
import java.util.concurrent.ThreadLocalRandom;

/**
 * Returns the collection selecting by round robin algorithm.
 */
public class RoundRobinCollectionSelectionStrategy implements CollectionSelectionStrategy {
  public static final String NAME = "round-robin";

  public int getCollection(DatabaseSessionEmbedded session, final SchemaClass iClass,
      final EntityImpl entity) {
    return getCollection(session, iClass, iClass.getCollectionIds(), entity);
  }

  public int getCollection(DatabaseSessionEmbedded session, final SchemaClass clazz, final int[] collections,
      final EntityImpl entity) {
    if (collections.length == 1)
    // ONLY ONE: RETURN THE FIRST ONE
    {
      return collections[0];
    }

    return collections[ThreadLocalRandom.current().nextInt(0, collections.length)];
  }

  @Override
  public String getName() {
    return NAME;
  }
}
