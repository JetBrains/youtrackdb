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

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import com.jetbrains.youtrackdb.internal.spatial.shape.ShapeFactory;
import javax.annotation.Nullable;
import org.locationtech.spatial4j.shape.Shape;

/**
 *
 */
public abstract class SpatialFunctionAbstract extends SQLFunctionAbstract {

  protected ShapeFactory factory = ShapeFactory.INSTANCE;

  public SpatialFunctionAbstract(String iName, int iMinParams, int iMaxParams) {
    super(iName, iMinParams, iMaxParams);
  }

  boolean containsNull(Object[] params) {
    for (var param : params) {
      if (param == null) {
        return true;
      }
    }

    return false;
  }

  @Nullable
  protected Shape toShape(Object param) {
    Shape result = null;
    if (param instanceof Result res) {
      if (res.isEntity() || res.hasProperty(EntityHelper.ATTRIBUTE_CLASS)) {
        result = factory.fromObject(res);
      }
    }

    if (result != null) {
      return result;
    }

    final var singleItem = getSingleItem(param);
    if (singleItem != null) {
      final var singleProp = getSingleProperty(singleItem);
      if (singleProp != null) {
        return factory.fromObject(singleProp);
      }
    }

    return null;
  }
}
