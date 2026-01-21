/*
 * JUnit 4 version of SQLCreateIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateIndexTest.java
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyListIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import java.util.Arrays;
import java.util.List;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of SQLCreateIndexTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/SQLCreateIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLCreateIndexTest extends BaseDBTest {

  private static final PropertyTypeInternal EXPECTED_PROP1_TYPE = PropertyTypeInternal.DOUBLE;
  private static final PropertyTypeInternal EXPECTED_PROP2_TYPE = PropertyTypeInternal.INTEGER;

  @BeforeClass
  public static void setUpClass() throws Exception {
    SQLCreateIndexTest instance = new SQLCreateIndexTest();
    instance.beforeClass();
    instance.createTestSchema();
  }

  private void createTestSchema() {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.existsClass("sqlCreateIndexTestClass")) {
      return;
    }

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

  @AfterClass
  public static void tearDownClass() throws Exception {
    SQLCreateIndexTest instance = new SQLCreateIndexTest();
    instance.beforeClass();
    if (instance.session.isClosed()) {
      instance.session = instance.createSessionInstance();
    }
    instance.session.begin();
    instance.session.execute("delete from sqlCreateIndexTestClass").close();
    instance.session.commit();
    instance.session.execute("drop class sqlCreateIndexTestClass").close();
    instance.afterClass();
  }

  /**
   * Original: testOldSyntax (line 57)
   */
  @Test
  public void test01_OldSyntax() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop1 UNIQUE").close();

    final var index =
        session.getSharedContext().getIndexManager().getIndex("sqlCreateIndexTestClass.prop1");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyIndexDefinition);
    Assert.assertEquals("prop1", indexDefinition.getProperties().get(0));
    Assert.assertEquals(EXPECTED_PROP1_TYPE, indexDefinition.getTypes()[0]);
    Assert.assertEquals("UNIQUE", index.getType());
  }

  /**
   * Original: testCreateCompositeIndex (line 77)
   */
  @Test
  public void test02_CreateCompositeIndex() throws Exception {
    session.execute(
            "CREATE INDEX sqlCreateIndexCompositeIndex ON sqlCreateIndexTestClass (prop1, prop2) UNIQUE")
        .close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    Assert.assertArrayEquals(
        new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }

  /**
   * Original: testCreateEmbeddedMapIndex (line 103)
   */
  @Test
  public void test03_CreateEmbeddedMapIndex() throws Exception {
    session.execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapIndex ON sqlCreateIndexTestClass (prop3) UNIQUE")
        .close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(List.of("prop3"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.STRING},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
    Assert.assertEquals(PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  /**
   * Original: testOldStileCreateEmbeddedMapIndex (line 130)
   */
  @Test
  public void test04_OldStileCreateEmbeddedMapIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop3 UNIQUE").close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop3");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(List.of("prop3"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.STRING},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
    Assert.assertEquals(PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  /**
   * Original: testCreateEmbeddedMapWrongSpecifierIndexOne (line 154)
   */
  @Test
  public void test05_CreateEmbeddedMapWrongSpecifierIndexOne() throws Exception {
    try {
      session.execute(
          "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
              + " (prop3 by ttt) UNIQUE").close();
      Assert.fail();
    } catch (CommandSQLParsingException e) {
    }
    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    Assert.assertNull(index);
  }

  /**
   * Original: testCreateEmbeddedMapWrongSpecifierIndexTwo (line 175)
   */
  @Test
  public void test06_CreateEmbeddedMapWrongSpecifierIndexTwo() throws Exception {
    try {
      session.execute(
          "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
              + " (prop3 b value) UNIQUE").close();
      Assert.fail();
    } catch (CommandSQLParsingException e) {
    }
    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    Assert.assertNull(index);
  }

  /**
   * Original: testCreateEmbeddedMapWrongSpecifierIndexThree (line 197)
   */
  @Test
  public void test07_CreateEmbeddedMapWrongSpecifierIndexThree() throws Exception {
    try {
      session.execute(
          "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
              + " (prop3 by value t) UNIQUE").close();
      Assert.fail();
    } catch (CommandSQLParsingException e) {
    }
    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    Assert.assertNull(index);
  }

  /**
   * Original: testCreateEmbeddedMapByKeyIndex (line 219)
   */
  @Test
  public void test08_CreateEmbeddedMapByKeyIndex() throws Exception {
    session.execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapByKeyIndex ON sqlCreateIndexTestClass (prop3 by key) UNIQUE")
        .close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapByKeyIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(List.of("prop3"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.STRING},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
    Assert.assertEquals(PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  /**
   * Original: testCreateEmbeddedMapByValueIndex (line 247)
   */
  @Test
  public void test09_CreateEmbeddedMapByValueIndex() throws Exception {
    session.execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapByValueIndex ON sqlCreateIndexTestClass (prop3 by value) UNIQUE")
        .close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapByValueIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(List.of("prop3"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
    Assert.assertEquals(PropertyMapIndexDefinition.INDEX_BY.VALUE,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  /**
   * Original: testCreateEmbeddedListIndex (line 275)
   */
  @Test
  public void test10_CreateEmbeddedListIndex() throws Exception {
    session.execute(
            "CREATE INDEX sqlCreateIndexEmbeddedListIndex ON sqlCreateIndexTestClass (prop5) NOTUNIQUE")
        .close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedListIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    Assert.assertEquals(List.of("prop5"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER},
        indexDefinition.getTypes());
    Assert.assertEquals("NOTUNIQUE", index.getType());
  }

  /**
   * Original: testCreateRidBagIndex (line 300)
   */
  @Test
  public void test11_CreateRidBagIndex() throws Exception {
    session.execute(
            "CREATE INDEX sqlCreateIndexRidBagIndex ON sqlCreateIndexTestClass (prop9) NOTUNIQUE")
        .close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexRidBagIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    Assert.assertEquals(List.of("prop9"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.LINK},
        indexDefinition.getTypes());
    Assert.assertEquals("NOTUNIQUE", index.getType());
  }

  /**
   * Original: testCreateOldStileEmbeddedListIndex (line 323)
   */
  @Test
  public void test12_CreateOldStileEmbeddedListIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop5 NOTUNIQUE").close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop5");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    Assert.assertEquals(List.of("prop5"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER},
        indexDefinition.getTypes());
    Assert.assertEquals("NOTUNIQUE", index.getType());
  }

  /**
   * Original: testCreateOldStileRidBagIndex (line 343)
   */
  @Test
  public void test13_CreateOldStileRidBagIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop9 NOTUNIQUE").close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop9");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    Assert.assertEquals(List.of("prop9"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.LINK},
        indexDefinition.getTypes());
    Assert.assertEquals("NOTUNIQUE", index.getType());
  }

  /**
   * Original: testCreateEmbeddedListWithoutLinkedTypeIndex (line 363)
   */
  @Test
  public void test14_CreateEmbeddedListWithoutLinkedTypeIndex() throws Exception {
    try {
      session.execute(
          "CREATE INDEX sqlCreateIndexEmbeddedListWithoutLinkedTypeIndex ON"
              + " sqlCreateIndexTestClass (prop6) NOTUNIQUE").close();
      Assert.fail();
    } catch (IndexException e) {
      Assert.assertTrue(e.getMessage().contains(
          "Linked type was not provided. You should provide linked type for embedded collections"
              + " that are going to be indexed."));
    }

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedListWithoutLinkedTypeIndex");

    Assert.assertNull(index);
  }

  /**
   * Original: testCreateEmbeddedMapWithoutLinkedTypeIndex (line 389)
   */
  @Test
  public void test15_CreateEmbeddedMapWithoutLinkedTypeIndex() throws Exception {
    try {
      session.execute(
          "CREATE INDEX sqlCreateIndexEmbeddedMapWithoutLinkedTypeIndex ON"
              + " sqlCreateIndexTestClass (prop7 by value) NOTUNIQUE").close();
      Assert.fail();
    } catch (IndexException e) {
      Assert.assertTrue(e.getMessage().contains(
          "Linked type was not provided. You should provide linked type for embedded collections"
              + " that are going to be indexed."));
    }

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWithoutLinkedTypeIndex");

    Assert.assertNull(index);
  }

  /**
   * Original: testCreateCompositeIndexWithTypes (line 415)
   */
  @Test
  public void test16_CreateCompositeIndexWithTypes() throws Exception {
    final String query =
        "CREATE INDEX sqlCreateIndexCompositeIndex2 ON sqlCreateIndexTestClass (prop1, prop2)"
            + " UNIQUE";

    session.execute(query).close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex2");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    Assert.assertArrayEquals(
        new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }

  /**
   * Original: testCreateCompositeIndexWithWrongTypes (line 444)
   */
  @Test
  public void test17_CreateCompositeIndexWithWrongTypes() throws Exception {
    final String query =
        "CREATE INDEX sqlCreateIndexCompositeIndexWrongTypes ON sqlCreateIndexTestClass (prop1,"
            + " prop2) UNIQUE";

    session.execute(query).close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndexWrongTypes");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    Assert.assertArrayEquals(
        new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }

  /**
   * Original: testCompositeIndexWithMetadata (line 480)
   */
  @Test
  public void test18_CompositeIndexWithMetadata() throws Exception {
    final String query =
        "CREATE INDEX sqlCreateIndexCompositeIndexWithMetadata ON sqlCreateIndexTestClass (prop1,"
            + " prop2) UNIQUE METADATA {ignoreNullValues: true}";

    session.execute(query).close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndexWithMetadata");

    Assert.assertNotNull(index);
    Assert.assertEquals(true, index.getMetadata().get("ignoreNullValues"));

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    Assert.assertArrayEquals(
        new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }

  /**
   * Original: testOldIndexWithMetadata (line 510)
   */
  @Test
  public void test19_OldIndexWithMetadata() throws Exception {
    final String query =
        "CREATE INDEX sqlCreateIndexTestClass.prop8 UNIQUE METADATA {ignoreNullValues: true}";

    session.execute(query).close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop8");

    Assert.assertNotNull(index);
    Assert.assertEquals(true, index.getMetadata().get("ignoreNullValues"));

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyIndexDefinition);
    Assert.assertEquals(List.of("prop8"), indexDefinition.getProperties());
    Assert.assertArrayEquals(new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }

  /**
   * Original: testCreateCompositeIndexWithTypesAndMetadata (line 538)
   */
  @Test
  public void test20_CreateCompositeIndexWithTypesAndMetadata() throws Exception {
    final String query =
        "CREATE INDEX sqlCreateIndexCompositeIndexWithTypesAndMetadata ON sqlCreateIndexTestClass"
            + " (prop1, prop2) UNIQUE METADATA {ignoreNullValues: true}";

    session.execute(query).close();

    final var index =
        session.getMetadata().getSchema().getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndexWithTypesAndMetadata");

    Assert.assertNotNull(index);
    Assert.assertEquals(true, index.getMetadata().get("ignoreNullValues"));

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(Arrays.asList("prop1", "prop2"), indexDefinition.getProperties());
    Assert.assertArrayEquals(
        new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE},
        indexDefinition.getTypes());
    Assert.assertEquals("UNIQUE", index.getType());
  }
}
