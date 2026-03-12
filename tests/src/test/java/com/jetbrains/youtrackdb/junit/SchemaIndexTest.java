package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.exception.SchemaException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// Migrated from: com.jetbrains.youtrackdb.auto.SchemaIndexTest
public class SchemaIndexTest extends BaseDBJUnit5Test {

  @Override
  @BeforeEach
  void beforeEach() throws Exception {
    super.beforeEach();

    final Schema schema = session.getMetadata().getSchema();
    final var superTest = schema.createClass("SchemaSharedIndexSuperTest");
    final var test = schema.createClass("SchemaIndexTest", superTest);
    test.createProperty("prop1", PropertyType.DOUBLE);
    test.createProperty("prop2", PropertyType.DOUBLE);
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    if (session.getMetadata().getSchema().existsClass("SchemaIndexTest")) {
      session.execute("drop class SchemaIndexTest").close();
    }
    session.execute("drop class SchemaSharedIndexSuperTest").close();

    super.afterEach();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SchemaIndexTest#testDropClass
  @Test
  void testDropClass() throws Exception {
    session.execute(
        "CREATE INDEX SchemaSharedIndexCompositeIndex"
            + " ON SchemaIndexTest (prop1, prop2) UNIQUE")
        .close();
    session.getSharedContext().getIndexManager().reload(session);
    assertNotNull(
        session.getSharedContext().getIndexManager()
            .getIndex("SchemaSharedIndexCompositeIndex"));
    session.getMetadata().getSchema().dropClass("SchemaIndexTest");
    session.getSharedContext().getIndexManager().reload(session);
    assertNull(session.getMetadata().getSchema().getClass("SchemaIndexTest"));
    assertNotNull(
        session.getMetadata().getSchema()
            .getClass("SchemaSharedIndexSuperTest"));
    assertNull(
        session.getSharedContext().getIndexManager()
            .getIndex("SchemaSharedIndexCompositeIndex"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SchemaIndexTest#testDropSuperClass
  @Test
  void testDropSuperClass() throws Exception {
    session.execute(
        "CREATE INDEX SchemaSharedIndexCompositeIndex"
            + " ON SchemaIndexTest (prop1, prop2) UNIQUE")
        .close();
    try {
      session.getMetadata().getSchema()
          .dropClass("SchemaSharedIndexSuperTest");
      fail();
    } catch (SchemaException e) {
      assertTrue(e.getMessage().startsWith(
          "Class 'SchemaSharedIndexSuperTest' cannot be dropped"
              + " because it has sub classes"));
    }
    assertNotNull(
        session.getMetadata().getSchema().getClass("SchemaIndexTest"));
    assertNotNull(
        session.getMetadata().getSchema()
            .getClass("SchemaSharedIndexSuperTest"));
    assertNotNull(
        session.getSharedContext().getIndexManager()
            .getIndex("SchemaSharedIndexCompositeIndex"));
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SchemaIndexTest#testIndexWithNumberProperties
  @Test
  void testIndexWithNumberProperties() {
    var oclass = session.getMetadata().getSchema()
        .createClass("SchemaIndexTest_numberclass");
    oclass.createProperty("1", PropertyType.STRING).setMandatory(false);
    oclass.createProperty("2", PropertyType.STRING).setMandatory(false);
    oclass.createIndex(
        "SchemaIndexTest_numberclass_1_2",
        SchemaClass.INDEX_TYPE.UNIQUE, "1", "2");
    session.getMetadata().getSchema().dropClass(oclass.getName());
  }
}
