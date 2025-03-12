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
package com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ClusterSelectionStrategy;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

/**
 * Returns always the first cluster configured.
 */
public class DefaultClusterSelectionStrategy implements ClusterSelectionStrategy {

  public static final String NAME = "default";

  public int getCluster(DatabaseSession session, final SchemaClass iClass,
      final EntityImpl entity) {
    return iClass.getClusterIds()[0];
  }

  @Override
  public int getCluster(DatabaseSession session, SchemaClass iClass, int[] selection,
      EntityImpl entity) {
    return iClass.getClusterIds()[0];
  }

  @Override
  public String getName() {
    return NAME;
  }
}
