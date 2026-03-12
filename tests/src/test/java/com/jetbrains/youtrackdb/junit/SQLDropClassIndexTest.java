package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class SQLDropClassIndexTest extends BaseDBJUnit5Test {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("SQLDropClassTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE);
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE);
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.SQLDropClassIndexTest#testIndexDeletion
  @Test
  @Order(1)
  void testIndexDeletion() throws Exception {
    session
        .execute(
            "CREATE INDEX SQLDropClassCompositeIndex ON SQLDropClassTestClass (prop1, prop2)"
                + " UNIQUE")
        .close();

    assertNotNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("SQLDropClassCompositeIndex"));

    session.execute("DROP CLASS SQLDropClassTestClass").close();

    assertNull(session.getMetadata().getSchema().getClass("SQLDropClassTestClass"));
    assertNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("SQLDropClassCompositeIndex"));
    session.close();
    session = createSessionInstance();
    assertNull(session.getMetadata().getSchema().getClass("SQLDropClassTestClass"));
    assertNull(
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("SQLDropClassCompositeIndex"));
  }
}
