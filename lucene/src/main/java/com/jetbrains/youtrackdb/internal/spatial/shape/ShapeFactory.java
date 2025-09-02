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
package com.jetbrains.youtrackdb.internal.spatial.shape;

import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.spatial4j.shape.Point;
import org.locationtech.spatial4j.shape.Rectangle;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.ShapeCollection;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;

public class ShapeFactory extends ComplexShapeBuilder {

  public static final ShapeFactory INSTANCE = new ShapeFactory();

  protected ShapeOperation operation;

  private final Map<String, ShapeBuilder> factories = new HashMap<String, ShapeBuilder>();

  protected ShapeFactory() {
    operation = new ShapeOperationImpl(this);
    registerFactory(new LineStringShapeBuilder());
    registerFactory(new MultiLineStringShapeBuilder());
    registerFactory(new PointShapeBuilder());
    registerFactory(new MultiPointShapeBuilder());
    registerFactory(new RectangleShapeBuilder());
    registerFactory(new PolygonShapeBuilder());
    registerFactory(new MultiPolygonShapeBuilder());
    registerFactory(new GeometryCollectionShapeBuilder(this));
  }

  @Override
  public String getName() {
    return null;
  }

  @Override
  public OShapeType getType() {
    return null;
  }

  @Override
  public void initClazz(DatabaseSessionInternal db) {
    for (var f : factories.values()) {
      f.initClazz(db);
    }
  }

  @Override
  public Shape fromResult(Result result) {
    String shapeName;
    if (result instanceof Result res) {
      if (res.isEntity()) {
        var entity = res.asEntity();
        shapeName = entity.getSchemaClassName();
      } else {
        shapeName = res.getString(EntityHelper.ATTRIBUTE_CLASS);
      }
    } else {
      throw new IllegalStateException("Unexpected result type: " + result);
    }

    var shapeBuilder = factories.get(shapeName);
    if (shapeBuilder != null) {
      return shapeBuilder.fromResult(result);
    }
    // TODO handle exception shape not found
    return null;
  }

  @Override
  public Shape fromObject(Object obj) {
    if (obj instanceof String) {
      try {
        return fromText((String) obj);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
    if (obj instanceof Result result) {
      return fromResult(result);
    }

    if (obj instanceof Map) {
      var map = (Map) ((Map) obj).get("shape");
      if (map == null) {
        map = (Map) obj;
      }
      return fromMapGeoJson(map);
    }
    return null;
  }

  @Override
  public String asText(EmbeddedEntity entity) {
    var className = entity.getSchemaClassName();
    var shapeBuilder = factories.get(className);
    if (shapeBuilder != null) {
      return shapeBuilder.asText(entity);
    } else if (className.endsWith("Z")) {
      shapeBuilder = factories.get(className.substring(0, className.length() - 1));
      if (shapeBuilder != null) {
        return shapeBuilder.asText(entity);
      }
    }

    // TODO handle exception shape not found
    return null;
  }

  @Override
  public String asText(Object obj) {

    if (obj instanceof EmbeddedEntity embeddedEntity) {
      return asText(embeddedEntity);
    }

    if (obj instanceof Map) {
      var map = (Map) ((Map) obj).get("shape");
      if (map == null) {
        map = (Map) obj;
      }
      return asText(map);
    }

    return null;
  }

  public byte[] asBinary(Object obj) {
    if (obj instanceof ResultInternal resultInternal) {
      var shape = fromResult(resultInternal);
      return asBinary(shape);
    }
    if (obj instanceof Map) {
      var map = (Map) ((Map) obj).get("shape");
      if (map == null) {
        map = (Map) obj;
      }
      var shape = fromMapGeoJson(map);

      return asBinary(shape);
    }
    throw new IllegalArgumentException("Error serializing to binary " + obj);
  }

  @Override
  public EmbeddedEntity toEmbeddedEntity(Shape shape, DatabaseSessionInternal session) {

    EmbeddedEntity result = null;
    if (Point.class.isAssignableFrom(shape.getClass())) {
      result = factories.get(PointShapeBuilder.NAME).toEmbeddedEntity(shape, session);
    } else if (Rectangle.class.isAssignableFrom(shape.getClass())) {
      result = factories.get(RectangleShapeBuilder.NAME).toEmbeddedEntity(shape, session);
    } else if (JtsGeometry.class.isAssignableFrom(shape.getClass())) {
      var geometry = (JtsGeometry) shape;
      var geom = geometry.getGeom();
      result = factories.get("O" + geom.getClass().getSimpleName())
          .toEmbeddedEntity(shape, session);

    } else if (ShapeCollection.class.isAssignableFrom(shape.getClass())) {
      var collection = (ShapeCollection) shape;

      if (isMultiPolygon(collection)) {
        result = factories.get("OMultiPolygon").toEmbeddedEntity(createMultiPolygon(collection),
            session);
      } else if (isMultiPoint(collection)) {
        result = factories.get("OMultiPoint").toEmbeddedEntity(createMultiPoint(collection),
            session);
      } else if (isMultiLine(collection)) {
        result = factories.get("OMultiLineString").toEmbeddedEntity(createMultiLine(collection),
            session);
      } else {
        result = factories.get("OGeometryCollection").toEmbeddedEntity(shape, session);
      }
    }
    return result;
  }

  @Override
  protected EmbeddedEntity toEmbeddedEntity(Shape shape, Geometry geometry,
      DatabaseSessionInternal session) {
    if (Point.class.isAssignableFrom(shape.getClass())) {
      return factories.get(PointShapeBuilder.NAME).toEmbeddedEntity(shape, geometry, session);
    } else if (geometry != null && "LineString".equals(geometry.getClass().getSimpleName())) {
      return factories.get("OLineString").toEmbeddedEntity(shape, geometry, session);
    } else if (geometry != null && "MultiLineString".equals(geometry.getClass().getSimpleName())) {
      return factories.get("OMultiLineString").toEmbeddedEntity(shape, geometry, session);
    } else if (geometry != null && "Polygon".equals(geometry.getClass().getSimpleName())) {
      return factories.get("OPolygon").toEmbeddedEntity(shape, geometry, session);
    } else {
      return toEmbeddedEntity(shape, session);
    }
  }

  @Override
  public Shape fromMapGeoJson(Map geoJsonMap) {
    var shapeBuilder = factories.get(geoJsonMap.get("type"));

    if (shapeBuilder == null) {
      shapeBuilder = factories.get(geoJsonMap.get("@class"));
    }
    if (shapeBuilder != null) {
      return shapeBuilder.fromMapGeoJson(geoJsonMap);
    }
    throw new IllegalArgumentException("Invalid map");
    // TODO handle exception shape not found
  }

  public Geometry toGeometry(Shape shape) {
    if (shape instanceof ShapeCollection) {
      var shapes = (ShapeCollection<Shape>) shape;
      var geometries = new Geometry[shapes.size()];
      var i = 0;
      for (var shapeItem : shapes) {
        geometries[i] = SPATIAL_CONTEXT.getGeometryFrom(shapeItem);
        i++;
      }
      return GEOMETRY_FACTORY.createGeometryCollection(geometries);
    } else {
      return SPATIAL_CONTEXT.getGeometryFrom(shape);
    }
  }

  public EmbeddedEntity toEmbeddedEntity(Geometry geometry, DatabaseSessionInternal session) {
    if (geometry instanceof org.locationtech.jts.geom.Point point) {
      var point1 = context().makePoint(point.getX(), point.getY());
      return toEmbeddedEntity(point1, session);
    }
    if (geometry instanceof org.locationtech.jts.geom.GeometryCollection gc) {
      List<Shape> shapes = new ArrayList<Shape>();
      for (var i = 0; i < gc.getNumGeometries(); i++) {
        var geo = gc.getGeometryN(i);
        Shape shape = null;
        if (geo instanceof org.locationtech.jts.geom.Point point) {
          shape = context().makePoint(point.getX(), point.getY());
        } else {
          shape = SPATIAL_CONTEXT.makeShape(geo);
        }
        shapes.add(shape);
      }
      return toEmbeddedEntity(new ShapeCollection<Shape>(shapes, SPATIAL_CONTEXT), session);
    }
    return toEmbeddedEntity(SPATIAL_CONTEXT.makeShape(geometry), session);
  }

  public ShapeOperation operation() {
    return operation;
  }

  public void registerFactory(ShapeBuilder factory) {
    factories.put(factory.getName(), factory);
  }
}
