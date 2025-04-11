package com.jetbrains.youtrack.db.internal.spatial.functions;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.spatial.BaseSpatialLuceneTest;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;
import org.locationtech.jts.io.WKTReader;

public class STGeomFromTextFunctionTest extends BaseSpatialLuceneTest {

  protected static final WKTReader wktReader = new WKTReader();
  private boolean prevValue;

  @Override
  public void beforeTest() throws Exception {
    prevValue = GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.getValueAsBoolean();
    GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.setValue(true);

    super.beforeTest();
  }

  @Test
  public void test() {
    var func = new STGeomFromTextFunction();
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    var item =
        (EntityImpl) func.execute(null, null, null, new Object[]{"POINT (100.0 80.0)"}, context);
    Assert.assertEquals("OPoint", item.getSchemaClassName());
    Assert.assertEquals(2, ((List) item.getProperty("coordinates")).size());

    item =
        (EntityImpl) func.execute(null, null, null, new Object[]{"POINT Z(100.0 80.0 10)"},
            context);
    Assert.assertEquals("OPointZ", item.getSchemaClassName());
    Assert.assertEquals(3, ((List) item.getProperty("coordinates")).size());

    item =
        (EntityImpl)
            func.execute(
                null,
                null,
                null,
                new Object[]{"LINESTRING Z (1 1 0, 1 2 0, 1 3 1, 2 2 0)"},
                context);
    Assert.assertEquals("OLineStringZ", item.getSchemaClassName());
    Assert.assertEquals(3,
        ((List<List<Double>>) item.getProperty("coordinates")).getFirst().size());
    Assert.assertFalse(
        Double.isNaN(((List<List<Double>>) item.getProperty("coordinates")).getFirst().get(2)));

    item =
        (EntityImpl)
            func.execute(
                null,
                null,
                null,
                new Object[]{"POLYGON Z ((0 0 1, 0 1 0, 1 1 0, 1 0 0, 0 0 0))"},
                context);
    Assert.assertEquals("OPolygonZ", item.getSchemaClassName());
    Assert.assertEquals(
        5, ((List<List<List<Double>>>) item.getProperty("coordinates")).getFirst().size());
    Assert.assertFalse(
        Double.isNaN(
            ((List<List<List<Double>>>) item.getProperty("coordinates")).getFirst().getFirst()
                .get(2)));

    item =
        (EntityImpl)
            func.execute(
                null,
                null,
                null,
                new Object[]{"MULTILINESTRING Z ((1 1 0, 1 2 0), (1 3 1, 2 2 0))"},
                context);
    Assert.assertEquals("OMultiLineStringZ", item.getSchemaClassName());
    Assert.assertEquals(
        2, ((List<List<List<Double>>>) item.getProperty("coordinates")).getFirst().size());
    Assert.assertFalse(
        Double.isNaN(
            ((List<List<List<Double>>>) item.getProperty("coordinates")).getFirst().getFirst()
                .get(2)));
  }

  @Override
  public void afterTest() {
    super.afterTest();
    GlobalConfiguration.SPATIAL_ENABLE_DIRECT_WKT_READER.setValue(prevValue);
  }
}
