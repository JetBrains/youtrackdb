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
 * Returns always the first collection configured.
 */
public class DefaultCollectionSelectionStrategy implements CollectionSelectionStrategy {

  public static final String NAME = "default";

  public int getCollection(DatabaseSessionEmbedded session, final SchemaClass iClass,
      final EntityImpl entity) {
    return iClass.getCollectionIds()[0];
  }

  @Override
  public int getCollection(DatabaseSessionEmbedded session, SchemaClass iClass, int[] selection,
      EntityImpl entity) {
    return iClass.getCollectionIds()[0];
  }

  @Override
  public String getName() {
    return NAME;
  }
}
