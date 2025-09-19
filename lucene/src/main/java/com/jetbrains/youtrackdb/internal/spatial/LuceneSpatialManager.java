/**
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * <p>*
 */
package com.jetbrains.youtrackdb.internal.spatial;

import static com.jetbrains.youtrackdb.internal.spatial.shape.ShapeBuilder.BASE_CLASS;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeBuilder;

/**
 *
 */
public class LuceneSpatialManager {

  private final ShapeBuilder shapeBuilder;

  public LuceneSpatialManager(ShapeBuilder shapeBuilder) {
    this.shapeBuilder = shapeBuilder;
  }

  public void init(DatabaseSessionInternal db) {
    internalInit(db);
  }

  private void internalInit(DatabaseSessionInternal db) {
    if (db.getMetadata().getSlowMutableSchema().getClass(BASE_CLASS) == null) {
      db.getMetadata().getSlowMutableSchema().createAbstractClass(BASE_CLASS);
      shapeBuilder.initClazz(db);
    }
  }
}
