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

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.EmbeddedEntity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.operation.buffer.BufferOp;
import org.locationtech.jts.operation.buffer.BufferParameters;
import org.locationtech.spatial4j.context.SpatialContext;
import org.locationtech.spatial4j.context.jts.JtsSpatialContext;
import org.locationtech.spatial4j.context.jts.JtsSpatialContextFactory;
import org.locationtech.spatial4j.context.jts.ValidationRule;
import org.locationtech.spatial4j.shape.Rectangle;
import org.locationtech.spatial4j.shape.Shape;
import org.locationtech.spatial4j.shape.jts.JtsGeometry;
import org.locationtech.spatial4j.shape.jts.JtsShapeFactory;

public abstract class ShapeBuilder<T extends Shape> {

  public static final String SHAPE_NAME = "shapeName";
  public static final String COORDINATES = "coordinates";
  public static final String BASE_CLASS = "OShape";
  protected static final JtsSpatialContext SPATIAL_CONTEXT;
  protected static final GeometryFactory GEOMETRY_FACTORY;
  protected static final JtsShapeFactory SHAPE_FACTORY;
  protected static final WKTReader wktReader = new WKTReader();
  private static final Map<String, Integer> capStyles = new HashMap<String, Integer>();
  private static final Map<String, Integer> join = new HashMap<String, Integer>();
  private static final DecimalFormat doubleFormat;

  static {
    var factory = new JtsSpatialContextFactory();
    factory.geo = true;
    factory.validationRule = ValidationRule.none;

    SPATIAL_CONTEXT = new JtsSpatialContext(factory);

    SHAPE_FACTORY = new JtsShapeFactory(SPATIAL_CONTEXT, factory);
    GEOMETRY_FACTORY = SHAPE_FACTORY.getGeometryFactory();
    capStyles.put("round", 1);
    capStyles.put("flat", 2);
    capStyles.put("square", 1);

    join.put("round", 1);
    join.put("mitre", 2);
    join.put("bevel", 3);

    var sym = new DecimalFormatSymbols();
    sym.setDecimalSeparator('.');
    doubleFormat = new DecimalFormat("0", sym);
    doubleFormat.setMaximumFractionDigits(16);
  }

  public synchronized String format(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    } else if (Double.isInfinite(value)) {
      if (value > 0d) {
        return "Inf";
      } else {
        return "-Inf";
      }
    } else {
      return doubleFormat.format(value);
    }
  }

  @Nullable
  public abstract String getName();

  @Nullable
  public abstract OShapeType getType();

  @Nullable
  public abstract T fromResult(Result result);

  @Nullable
  public T fromObject(Object obj) {
    throw new UnsupportedOperationException();
  }

  public T fromMapGeoJson(Map<String, Object> geoJsonMap) {
    var result = new ResultInternal(null);
    result.setMetadata(ShapeBuilder.SHAPE_NAME, getName());
    result.setProperty(COORDINATES, geoJsonMap.get(COORDINATES));
    return fromResult(result);
  }

  public abstract void initClazz(DatabaseSessionInternal db);

  public String asText(T shape) {
    return SHAPE_FACTORY.getGeometryFrom(shape).toText();
  }

  public byte[] asBinary(T shape) {
    var writer = new WKBWriter();

    var geom = SHAPE_FACTORY.getGeometryFrom(shape);
    return writer.write(geom);
  }

  @Nullable
  public String asText(EmbeddedEntity entity) {
    return asText(fromResult(entity));
  }

  public String asText(Map<String, Object> geoJson) {
    return asText(fromMapGeoJson(geoJson));
  }

  @Nullable
  public String asText(Object object) {
    throw new UnsupportedOperationException();
  }

  public String asGeoJson(T shape) {
    return SPATIAL_CONTEXT.getFormats().getGeoJsonWriter().toString(shape);
  }

  public String asGeoJson(Result result) {
    return asGeoJson(fromResult(result));
  }

  public EmbeddedEntity fromGeoJson(String geoJson, DatabaseSessionInternal session) throws IOException, ParseException {
    var shape = SPATIAL_CONTEXT.getFormats().getGeoJsonReader().read(geoJson);
    return toEmbeddedEntity((T) shape, session);
  }

  Geometry toGeometry(Shape shape) {
    return SHAPE_FACTORY.getGeometryFrom(shape);
  }

  public JtsGeometry toShape(Geometry geometry) {
    return SHAPE_FACTORY.makeShape(geometry);
  }

  protected SchemaClass superClass(DatabaseSessionInternal db) {
    return db.getMetadata().getSchema().getClass(BASE_CLASS);
  }

  public T fromText(String wkt) throws ParseException, org.locationtech.jts.io.ParseException {
    Object entity = SPATIAL_CONTEXT.getWktShapeParser().parse(wkt);

    if (entity instanceof Rectangle) {
      var geometryFrom = SHAPE_FACTORY.getGeometryFrom((Shape) entity);
      entity = SHAPE_FACTORY.makeShape(geometryFrom);
    }
    return (T) entity;
  }

  public abstract EmbeddedEntity toEmbeddedEntity(T shape, DatabaseSessionInternal session);

  protected EmbeddedEntity toEmbeddedEntity(T parsed, Geometry geometry,
      DatabaseSessionInternal session) {
    if (geometry == null || Double.isNaN(geometry.getCoordinates()[0].getZ())) {
      return toEmbeddedEntity(parsed, session);
    }
    throw new IllegalArgumentException("Invalid shape");
  }

  public EmbeddedEntity toEmbeddedEntity(String wkt, DatabaseSessionInternal session)
      throws ParseException, org.locationtech.jts.io.ParseException {
    var parsed = fromText(wkt);
    return toEmbeddedEntity(
        parsed,
        GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.getValueAsBoolean()
            ? wktReader.read(wkt)
            : null, session);
  }

  public int getSRID(Shape shape) {
    var geometry = toGeometry(shape);
    return geometry.getSRID();
  }

  public Shape buffer(Shape shape, Double distance, Map<String, Object> params) {
    var geometry = toGeometry(shape);
    var parameters = new BufferParameters();
    if (params != null) {
      bindParameters(parameters, params);
    }
    var ops = new BufferOp(geometry, parameters);
    return toShape(ops.getResultGeometry(distance));
  }

  private void bindParameters(BufferParameters parameters, Map<String, Object> params) {

    bindQuad(parameters, params);

    bindMitre(parameters, params);

    bindCap(parameters, params);

    bindJoin(parameters, params);
  }

  private void bindCap(BufferParameters parameters, Map<String, Object> params) {
    var endCap = (String) params.get("endCap");

    if (endCap != null) {
      var style = capStyles.get(endCap);
      if (style != null) {
        parameters.setEndCapStyle(style);
      }
    }
  }

  private void bindJoin(BufferParameters parameters, Map<String, Object> params) {
    var join = (String) params.get("join");
    if (join != null) {
      var style = ShapeBuilder.join.get(join);
      if (style != null) {
        parameters.setJoinStyle(style);
      }
    }
  }

  private void bindMitre(BufferParameters parameters, Map<String, Object> params) {
    var mitre = (Number) params.get("mitre");

    if (mitre != null) {
      parameters.setMitreLimit(mitre.doubleValue());
    }
  }

  private void bindQuad(BufferParameters parameters, Map<String, Object> params) {
    var quadSegs = (Number) params.get("quadSegs");

    if (quadSegs != null) {
      parameters.setQuadrantSegments(quadSegs.intValue());
    }
  }

  public SpatialContext context() {
    return SPATIAL_CONTEXT;
  }
}
