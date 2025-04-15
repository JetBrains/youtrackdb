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
package com.jetbrains.youtrack.db.internal.spatial.shape;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.ShapeCollection;
import org.locationtech.spatial4j.shape.SpatialRelation;

/**
 *
 */
public class ShapeOperationImpl implements ShapeOperation {

  private final ShapeFactory factory;

  public ShapeOperationImpl(ShapeFactory oShapeFactory) {
    this.factory = oShapeFactory;
  }

  @Override
  public double distance(Shape s1, Shape s2) {
    var geometry = factory.toGeometry(s1);
    var geometry1 = factory.toGeometry(s2);
    return geometry.distance(geometry1);
  }

  @Override
  public boolean isWithInDistance(Shape s1, Shape s2, Double dist) {
    var geometry = factory.toGeometry(s1);
    var geometry1 = factory.toGeometry(s2);
    return geometry.isWithinDistance(geometry1, dist);
  }

  @Override
  public boolean intersect(Shape s1, Shape s2) {
    var geometry = factory.toGeometry(s1);
    var geometry1 = factory.toGeometry(s2);
    return geometry.intersects(geometry1);
  }

  @Override
  public boolean contains(Shape shape, Shape shape1) {

    if (shape instanceof ShapeCollection || shape1 instanceof ShapeCollection) {
      return shape.relate(shape1).equals(SpatialRelation.CONTAINS);
    }
    var geometry = factory.toGeometry(shape);
    var geometry1 = factory.toGeometry(shape1);

    return geometry.contains(geometry1);
  }

  @Override
  public boolean within(Shape shape, Shape shape1) {

    if (shape instanceof ShapeCollection || shape1 instanceof ShapeCollection) {
      return shape.relate(shape1).equals(SpatialRelation.WITHIN);
    }
    var geometry = factory.toGeometry(shape);
    var geometry1 = factory.toGeometry(shape1);
    return geometry.within(geometry1);
  }

  @Override
  public boolean isEquals(Shape shape, Shape shape1) {
    return within(shape, shape1) && within(shape1, shape);
  }
}
