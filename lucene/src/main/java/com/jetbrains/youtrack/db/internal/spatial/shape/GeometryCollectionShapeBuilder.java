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

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.ShapeCollection;

/**
 *
 */
public class GeometryCollectionShapeBuilder extends ComplexShapeBuilder<ShapeCollection<Shape>> {

  protected ShapeFactory shapeFactory;

  public GeometryCollectionShapeBuilder(ShapeFactory shapeFactory) {
    this.shapeFactory = shapeFactory;
  }

  @Override
  public String getName() {
    return "OGeometryCollection";
  }

  @Override
  public OShapeType getType() {
    return OShapeType.GEOMETRYCOLLECTION;
  }

  @Override
  public ShapeCollection<Shape> fromMapGeoJson(Map<String, Object> geoJsonMap) {
    var doc = new EntityImpl(null, getName());
    doc.field("geometries", geoJsonMap.get("geometries"));
    return fromDoc(doc);
  }

  @Override
  public ShapeCollection<Shape> fromDoc(EntityImpl doc) {

    List<Object> geometries = doc.field("geometries");

    List<Shape> shapes = new ArrayList<Shape>();

    for (var geometry : geometries) {
      var shape = shapeFactory.fromObject(geometry);
      shapes.add(shape);
    }

    return new ShapeCollection(shapes, SPATIAL_CONTEXT);
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {

    Schema schema = db.getMetadata().getSchema();
    var shape = superClass(db);
    var polygon = schema.createAbstractClass(getName(), shape);
    polygon.createProperty("geometries", PropertyType.EMBEDDEDLIST, shape);
  }

  @Override
  public String asText(ShapeCollection<Shape> shapes) {

    var geometries = new Geometry[shapes.size()];
    var i = 0;
    for (var shape : shapes) {
      geometries[i] = SPATIAL_CONTEXT.getGeometryFrom(shape);
      i++;
    }
    return GEOMETRY_FACTORY.createGeometryCollection(geometries).toText();
  }

  @Override
  public EntityImpl toEntitty(ShapeCollection<Shape> shapes) {

    var doc = new EntityImpl(null, getName());
    List<EntityImpl> geometries = new ArrayList<EntityImpl>(shapes.size());
    for (var s : shapes) {
      geometries.add(shapeFactory.toEntitty(s));
    }
    doc.field("geometries", geometries);
    return doc;
  }
}
