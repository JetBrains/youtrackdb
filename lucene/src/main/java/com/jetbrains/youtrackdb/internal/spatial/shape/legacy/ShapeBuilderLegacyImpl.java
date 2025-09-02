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
package com.jetbrains.youtrackdb.internal.spatial.shape.legacy;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.index.CompositeKey;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.shape.Shape;

/**
 *
 */
public class ShapeBuilderLegacyImpl implements ShapeBuilderLegacy<Shape> {

  public static final ShapeBuilderLegacyImpl INSTANCE = new ShapeBuilderLegacyImpl();
  private final List<ShapeBuilderLegacy> builders = new ArrayList<ShapeBuilderLegacy>();

  protected ShapeBuilderLegacyImpl() {
    builders.add(new PointLegecyBuilder());
    builders.add(new RectangleLegacyBuilder());
  }

  @Nullable
  @Override
  public Shape makeShape(DatabaseSessionInternal session, CompositeKey key, SpatialContext ctx) {
    for (var f : builders) {
      if (f.canHandle(key)) {
        return f.makeShape(session, key, ctx);
      }
    }
    return null;
  }

  @Override
  public boolean canHandle(CompositeKey key) {
    return false;
  }
}
