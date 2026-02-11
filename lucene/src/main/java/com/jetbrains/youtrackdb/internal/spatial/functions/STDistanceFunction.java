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
package com.jetbrains.youtrackdb.internal.spatial.functions;

import com.jetbrains.youtrackdb.api.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeFactory;
import javax.annotation.Nullable;

/**
 *
 */
public class STDistanceFunction extends SpatialFunctionAbstract {

  public static final String NAME = "st_distance";
  private final ShapeFactory factory = ShapeFactory.INSTANCE;

  public STDistanceFunction() {
    super(NAME, 2, 2);
  }

  @Nullable
  @Override
  public Object execute(
      Object iThis,
      Result iCurrentRecord,
      Object iCurrentResult,
      Object[] iParams,
      CommandContext iContext) {

    if (containsNull(iParams)) {
      return null;
    }

    var shape = toShape(iParams[0]);
    var shape1 = toShape(iParams[1]);

    if (shape == null || shape1 == null) {
      return null;
    }

    return factory.operation().distance(shape, shape1);
  }

  @Nullable
  @Override
  public String getSyntax(DatabaseSessionEmbedded session) {
    return null;
  }
}
