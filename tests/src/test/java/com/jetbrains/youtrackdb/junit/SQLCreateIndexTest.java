package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagSecondaryIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyListIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

public class SQLCreateIndexTest extends BaseDBJUnit5Test {

  private static final PropertyTypeInternal EXPECTED_PROP1_TYPE = PropertyTypeInternal.DOUBLE;
  private static final PropertyTypeInternal EXPECTED_PROP2_TYPE = PropertyTypeInternal.INTEGER;

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("sqlCreateIndexTestClass");
    oClass.createProperty("prop1", EXPECTED_PROP1_TYPE.getPublicPropertyType());
    oClass.createProperty("prop2", EXPECTED_PROP2_TYPE.getPublicPropertyType());
    oClass.createProperty("prop3", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    oClass.createProperty("prop5", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
    oClass.createProperty("prop6", PropertyType.EMBEDDEDLIST);
    oClass.createProperty("prop7", PropertyType.EMBEDDEDMAP);
    oClass.createProperty("prop8", PropertyType.INTEGER);
    oClass.createProperty("prop9", PropertyType.LINKBAG);
  }

  @AfterAll
  @Override
  void afterAll() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.begin();
    session.execute("delete from sqlCreateIndexTestClass").close();
    session.commit();
    session.execute("drop class sqlCreateIndexTestClass").close();

    super.afterAll();
  }

  @Test
  @Order(1)
  void testOldSyntax() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop1 UNIQUE").close();

    final var index =
        session
            .getSharedContext()
            .getIndexManager()
            .getIndex("sqlCreateIndexTestClass.prop1");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyIndexDefinition);
    assertEquals("prop1", indexDefinition.getProperties().get(0));
    assertEquals(EXPECTED_PROP1_TYPE, indexDefinition.getTypes()[0]);
    assertEquals("UNIQUE", index.getType());
  }

  @Test
  @Order(2)
  void testCreateCompositeIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexCompositeIndex ON sqlCreateIndexTestClass (prop1, prop2)"
                + " UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex");

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
  @Order(3)
  void testCreateEmbeddedMapIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapIndex ON sqlCreateIndexTestClass (prop3) UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(List.of("prop3"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING}, indexDefinition.getTypes());
    assertEquals("UNIQUE", index.getType());
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(4)
  void testOldStileCreateEmbeddedMapIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop3 UNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop3");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(List.of("prop3"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING}, indexDefinition.getTypes());
    assertEquals("UNIQUE", index.getType());
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(5)
  void testCreateEmbeddedMapWrongSpecifierIndexOne() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
                  + " (prop3 by ttt) UNIQUE")
          .close();
      fail();
    } catch (CommandSQLParsingException e) {
    }
    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  @Order(6)
  void testCreateEmbeddedMapWrongSpecifierIndexTwo() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
                  + " (prop3 b value) UNIQUE")
          .close();
      fail();
    } catch (CommandSQLParsingException e) {
    }
    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  @Order(7)
  void testCreateEmbeddedMapWrongSpecifierIndexThree() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
                  + " (prop3 by value t) UNIQUE")
          .close();
      fail();
    } catch (CommandSQLParsingException e) {
    }
    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  @Order(8)
  void testCreateEmbeddedMapByKeyIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapByKeyIndex ON sqlCreateIndexTestClass (prop3 by"
                + " key) UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapByKeyIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(List.of("prop3"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.STRING}, indexDefinition.getTypes());
    assertEquals("UNIQUE", index.getType());
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(9)
  void testCreateEmbeddedMapByValueIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapByValueIndex ON sqlCreateIndexTestClass (prop3"
                + " by value) UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapByValueIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(List.of("prop3"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER}, indexDefinition.getTypes());
    assertEquals("UNIQUE", index.getType());
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.VALUE,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(10)
  void testCreateEmbeddedListIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedListIndex ON sqlCreateIndexTestClass (prop5)"
                + " NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedListIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    assertEquals(List.of("prop5"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER}, indexDefinition.getTypes());
    assertEquals("NOTUNIQUE", index.getType());
  }

  @Test
  @Order(11)
  void testCreateRidBagIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexRidBagIndex ON sqlCreateIndexTestClass (prop9) NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexRidBagIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    assertEquals(List.of("prop9"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.LINK}, indexDefinition.getTypes());
    assertEquals("NOTUNIQUE", index.getType());
  }

  @Test
  @Order(12)
  void testCreateOldStileEmbeddedListIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop5 NOTUNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop5");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    assertEquals(List.of("prop5"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER}, indexDefinition.getTypes());
    assertEquals("NOTUNIQUE", index.getType());
  }

  @Test
  @Order(13)
  void testCreateOldStileRidBagIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop9 NOTUNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop9");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    assertEquals(List.of("prop9"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.LINK}, indexDefinition.getTypes());
    assertEquals("NOTUNIQUE", index.getType());
  }

  @Test
  @Order(14)
  void testCreateEmbeddedListWithoutLinkedTypeIndex() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedListWithoutLinkedTypeIndex ON"
                  + " sqlCreateIndexTestClass (prop6) UNIQUE")
          .close();
      fail();
    } catch (IndexException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Linked type was not provided. You should provide linked type for embedded"
                      + " collections that are going to be indexed."));
    }
    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedListWithoutLinkedTypeIndex");

    assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  @Order(15)
  void testCreateEmbeddedMapWithoutLinkedTypeIndex() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWithoutLinkedTypeIndex ON"
                  + " sqlCreateIndexTestClass (prop7 by value) UNIQUE")
          .close();
      fail();
    } catch (IndexException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Linked type was not provided. You should provide linked type for embedded"
                      + " collections that are going to be indexed."));
    }
    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWithoutLinkedTypeIndex");

    assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  @Order(16)
  void testCreateCompositeIndexWithTypes() throws Exception {
    final var query =
        "CREATE INDEX sqlCreateIndexCompositeIndex2 ON sqlCreateIndexTestClass (prop1,"
            + " prop2) UNIQUE "
            + EXPECTED_PROP1_TYPE
            + ", "
            + EXPECTED_PROP2_TYPE;

    session.execute(query).close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex2");

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
  @Order(17)
  void testCreateCompositeIndexWithWrongTypes() throws Exception {
    final var query =
        "CREATE INDEX sqlCreateIndexCompositeIndex3 ON sqlCreateIndexTestClass (prop1,"
            + " prop2) UNIQUE "
            + EXPECTED_PROP1_TYPE.getPublicPropertyType()
            + ", "
            + EXPECTED_PROP1_TYPE.getPublicPropertyType();

    try {
      session.command(query);
      fail();
    } catch (Exception e) {
      Throwable cause = e;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }

      assertEquals(IllegalArgumentException.class, cause.getClass());
    }
    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex3");

    assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  @Order(18)
  void testCompositeIndexWithMetadata() {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexCompositeIndexWithMetadata ON sqlCreateIndexTestClass"
                + " (prop1, prop2) UNIQUE metadata {v1:23, v2:\"val2\"}")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndexWithMetadata");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    assertEquals("UNIQUE", index.getType());

    var metadata = index.getMetadata();

    assertEquals(23, metadata.get("v1"));
    assertEquals("val2", metadata.get("v2"));
  }

  @Test
  @Order(19)
  void testOldIndexWithMetadata() {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexTestClass.prop8 NOTUNIQUE  metadata {v1:23, v2:\"val2\"}")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop8");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyIndexDefinition);
    assertEquals(List.of("prop8"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.INTEGER}, indexDefinition.getTypes());
    assertEquals("NOTUNIQUE", index.getType());

    var metadata = index.getMetadata();

    assertEquals(23, metadata.get("v1"));
    assertEquals("val2", metadata.get("v2"));
  }

  @Test
  @Order(20)
  void testCreateCompositeIndexWithTypesAndMetadata() throws Exception {
    final var query =
        "CREATE INDEX sqlCreateIndexCompositeIndex2WithConfig ON sqlCreateIndexTestClass"
            + " (prop1, prop2) UNIQUE "
            + EXPECTED_PROP1_TYPE
            + ", "
            + EXPECTED_PROP2_TYPE
            + " metadata {v1:23, v2:\"val2\"}";

    session.execute(query).close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex2WithConfig");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    assertEquals("UNIQUE", index.getType());

    var metadata = index.getMetadata();
    assertEquals(23, metadata.get("v1"));
    assertEquals("val2", metadata.get("v2"));
  }

  /**
   * Verifies that CREATE INDEX with BY VALUE on a LINKBAG property creates a
   * PropertyLinkBagSecondaryIndexDefinition that indexes secondaryRid (vertex RID).
   */
  @Test
  @Order(21)
  void testCreateRidBagByValueIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexRidBagByValueIndex ON sqlCreateIndexTestClass"
                + " (prop9 by value) NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexRidBagByValueIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(
        indexDefinition instanceof PropertyLinkBagSecondaryIndexDefinition,
        "BY VALUE on LINKBAG should create PropertyLinkBagSecondaryIndexDefinition, got "
            + indexDefinition.getClass().getSimpleName());
    assertEquals(List.of("prop9 by value"), indexDefinition.getFieldsToIndex());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.LINK}, indexDefinition.getTypes());
    assertEquals("NOTUNIQUE", index.getType());
  }

  /**
   * Verifies that CREATE INDEX with BY KEY on a LINKBAG property creates a
   * PropertyLinkBagIndexDefinition (the default/primary index).
   */
  @Test
  @Order(22)
  void testCreateRidBagByKeyIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexRidBagByKeyIndex ON sqlCreateIndexTestClass"
                + " (prop9 by key) NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexRidBagByKeyIndex");

    assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    assertTrue(
        indexDefinition instanceof PropertyLinkBagIndexDefinition,
        "BY KEY on LINKBAG should create PropertyLinkBagIndexDefinition, got "
            + indexDefinition.getClass().getSimpleName());
    assertEquals(List.of("prop9"), indexDefinition.getProperties());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.LINK}, indexDefinition.getTypes());
    assertEquals("NOTUNIQUE", index.getType());
  }

  /**
   * Verifies that secondary LINKBAG index (BY VALUE) survives a database close/reopen cycle.
   * The index definition class is stored in the index configuration and must be reconstructed
   * from the stored class name during schema reload.
   */
  @Test
  @Order(23)
  void testRidBagByValueIndexPersistsAcrossReopen() throws Exception {
    // Create the secondary index
    session
        .execute(
            "CREATE INDEX sqlCreateIndexRidBagByValuePersist ON sqlCreateIndexTestClass"
                + " (prop9 by value) NOTUNIQUE")
        .close();

    // Force a close/reopen to verify serialization round-trip
    session.close();
    session = createSessionInstance();

    final var index =
        session
            .getMetadata()
            .getSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexRidBagByValuePersist");

    assertNotNull(index, "Index should survive database reopen");

    final var indexDefinition = index.getDefinition();

    assertTrue(
        indexDefinition instanceof PropertyLinkBagSecondaryIndexDefinition,
        "After reopen, definition should be PropertyLinkBagSecondaryIndexDefinition, got "
            + indexDefinition.getClass().getSimpleName());
    assertEquals(List.of("prop9 by value"), indexDefinition.getFieldsToIndex());
    assertArrayEquals(
        new PropertyTypeInternal[] {PropertyTypeInternal.LINK}, indexDefinition.getTypes());
  }
}
