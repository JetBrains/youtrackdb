package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import org.junit.Test;

public class SchemaSharedGlobalSchemaPropertyTest extends DbTestBase {

  @Test
  public void testGlobalPropertyCreate() {

    Schema schema = session.getMetadata().getSchema();

    schema.createGlobalProperty("testaasd", PropertyType.SHORT, 100);
    var prop = schema.getGlobalPropertyById(100);
    assertEquals(prop.getName(), "testaasd");
    assertEquals(prop.getId(), (Integer) 100);
    assertEquals(prop.getType(), PropertyType.SHORT);
  }

  @Test
  public void testGlobalPropertyCreateDoubleSame() {

    Schema schema = session.getMetadata().getSchema();

    schema.createGlobalProperty("test", PropertyType.SHORT, 200);
    schema.createGlobalProperty("test", PropertyType.SHORT, 200);
  }

  @Test(expected = SchemaException.class)
  public void testGlobalPropertyCreateDouble() {

    Schema schema = session.getMetadata().getSchema();

    schema.createGlobalProperty("test", PropertyType.SHORT, 201);
    schema.createGlobalProperty("test1", PropertyType.SHORT, 201);
  }
}
