package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class SQLDropIndexTest extends BaseDBJUnit5Test {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("SQLDropIndexTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE);
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE);
  }

  @AfterAll
  @Override
  void afterAll() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.begin();
    session.execute("delete from SQLDropIndexTestClass").close();
    session.commit();
    session.execute("drop class SQLDropIndexTestClass").close();

    super.afterAll();
  }

  @Test
  @Order(1)
  void testOldSyntax() throws Exception {
    session.execute("CREATE INDEX SQLDropIndexTestClass.prop1 UNIQUE").close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexTestClass.prop1");
    assertNotNull(index);

    session.execute("DROP INDEX SQLDropIndexTestClass.prop1").close();

    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexTestClass.prop1");
    assertNull(index);
  }

  @Test
  @Order(2)
  void testDropCompositeIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX SQLDropIndexCompositeIndex ON SQLDropIndexTestClass (prop1, prop2)"
                + " UNIQUE")
        .close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexCompositeIndex");
    assertNotNull(index);

    session.execute("DROP INDEX SQLDropIndexCompositeIndex").close();

    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexCompositeIndex");
    assertNull(index);
  }

  @Test
  @Order(3)
  void testDropIndexWorkedCorrectly() {
    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexTestClass.prop1");
    assertNull(index);
    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexWithoutClass");
    assertNull(index);
    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("SQLDropIndexTestClass")
            .getClassIndex(session, "SQLDropIndexCompositeIndex");
    assertNull(index);
  }
}
