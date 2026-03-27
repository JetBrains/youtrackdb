package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class SQLDropSchemaPropertyIndexTest extends BaseDBJUnit5Test {

  private static final PropertyTypeInternal EXPECTED_PROP1_TYPE = PropertyTypeInternal.DOUBLE;
  private static final PropertyTypeInternal EXPECTED_PROP2_TYPE = PropertyTypeInternal.INTEGER;

  @BeforeEach
  @Override
  void beforeEach() throws Exception {
    super.beforeEach();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("DropPropertyIndexTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE.getPublicPropertyType());
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE.getPublicPropertyType());
  }

  @AfterEach
  @Override
  void afterEach() throws Exception {
    session.execute("drop class DropPropertyIndexTestClass").close();

    super.afterEach();
  }

  @Test
  @Order(1)
  void testForcePropertyEnabled() throws Exception {
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop2,"
                + " prop1) UNIQUE")
        .close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    assertNotNull(index);

    session.execute("DROP PROPERTY DropPropertyIndexTestClass.prop1 FORCE").close();

    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    assertNull(index);
  }

  @Test
  @Order(2)
  void testForcePropertyEnabledBrokenCase() throws Exception {
    // With case-sensitive class names, wrong-case class references should fail
    // with "Source class not found" even with FORCE.
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop2,"
                + " prop1) UNIQUE")
        .close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    assertNotNull(index);

    var e = assertThrows(CommandExecutionException.class,
        () -> session.execute("DROP PROPERTY DropPropertyIndextestclasS.prop1 FORCE").close(),
        "Should fail because class name case does not match");
    assertTrue(
        e.getMessage().contains("Source class 'DropPropertyIndextestclasS' not found"),
        "Expected 'Source class not found' but got: " + e.getMessage());

    // The index on the correctly-cased class should still exist and be intact
    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    assertNotNull(index);
    assertTrue(index.getDefinition() instanceof CompositeIndexDefinition);
    assertEquals(Arrays.asList("prop2", "prop1"), index.getDefinition().getProperties());
  }

  @Test
  @Order(3)
  void testForcePropertyDisabled() throws Exception {
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop1,"
                + " prop2) UNIQUE")
        .close();

    var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");
    assertNotNull(index);

    try {
      session.execute("DROP PROPERTY DropPropertyIndexTestClass.prop1").close();
      fail();
    } catch (CommandExecutionException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Property used in indexes (DropPropertyIndexCompositeIndex). Please drop these"
                      + " indexes before removing property or use FORCE parameter."));
    }

    index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    assertEquals("UNIQUE", index.getType());
  }

  @Test
  @Order(4)
  void testForcePropertyDisabledBrokenCase() throws Exception {
    // With case-sensitive class names, wrong-case class references should fail
    // with "Source class not found" rather than reaching index validation.
    session
        .execute(
            "CREATE INDEX DropPropertyIndexCompositeIndex ON DropPropertyIndexTestClass (prop1,"
                + " prop2) UNIQUE")
        .close();

    var e = assertThrows(CommandExecutionException.class,
        () -> session.execute("DROP PROPERTY DropPropertyIndextestclass.prop1").close());
    assertTrue(
        e.getMessage().contains("Source class 'DropPropertyIndextestclass' not found"),
        "Expected 'Source class not found' but got: " + e.getMessage());

    // The index on the correctly-cased class should still exist and be intact
    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("DropPropertyIndexTestClass")
            .getClassIndex(session, "DropPropertyIndexCompositeIndex");

    assertNotNull(index);
    assertTrue(index.getDefinition() instanceof CompositeIndexDefinition);
    assertEquals(Arrays.asList("prop1", "prop2"), index.getDefinition().getProperties());
    assertEquals("UNIQUE", index.getType());
  }
}
