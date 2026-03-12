package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyListIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 *
 * <p>Tests class-level index creation, querying, and involved-index lookups.
 * Uses {@code @Order} to replicate the original {@code dependsOnMethods}
 * execution ordering.
 */
public class ClassIndexTest extends BaseDBJUnit5Test {

  private SchemaClassInternal oClass;
  private SchemaClassInternal oSuperClass;

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();

    final Schema schema = session.getMetadata().getSchemaInternal();

    oClass = (SchemaClassInternal) schema.createClass("ClassIndexTestClass");
    oSuperClass = (SchemaClassInternal) schema.createClass("ClassIndexTestSuperClass");

    oClass.createProperty("fOne", PropertyType.INTEGER);
    oClass.createProperty("fTwo", PropertyType.STRING);
    oClass.createProperty("fThree", PropertyType.BOOLEAN);
    oClass.createProperty("fFour", PropertyType.INTEGER);

    oClass.createProperty("fSix", PropertyType.STRING);
    oClass.createProperty("fSeven", PropertyType.STRING);

    oClass.createProperty("fEight", PropertyType.INTEGER);
    oClass.createProperty("fTen", PropertyType.INTEGER);
    oClass.createProperty("fEleven", PropertyType.INTEGER);
    oClass.createProperty("fTwelve", PropertyType.INTEGER);
    oClass.createProperty("fThirteen", PropertyType.INTEGER);
    oClass.createProperty("fFourteen", PropertyType.INTEGER);
    oClass.createProperty("fFifteen", PropertyType.INTEGER);

    oClass.createProperty("fEmbeddedMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    oClass.createProperty("fEmbeddedMapWithoutLinkedType", PropertyType.EMBEDDEDMAP);
    oClass.createProperty("fLinkMap", PropertyType.LINKMAP);

    oClass.createProperty("fLinkList", PropertyType.LINKLIST);
    oClass.createProperty("fEmbeddedList", PropertyType.EMBEDDEDLIST,
        PropertyType.INTEGER);

    oClass.createProperty("fEmbeddedSet", PropertyType.EMBEDDEDSET, PropertyType.INTEGER);
    oClass.createProperty("fLinkSet", PropertyType.LINKSET);

    oClass.createProperty("fRidBag", PropertyType.LINKBAG);

    oSuperClass.createProperty("fNine", PropertyType.INTEGER);
    oClass.addSuperClass(oSuperClass);

    session.close();
  }

  @Override
  @BeforeEach
  void beforeEach() throws Exception {
    super.beforeEach();

    oClass = session.getClassInternal("ClassIndexTestClass");
    oSuperClass = session.getClassInternal("ClassIndexTestSuperClass");
  }

  @Test
  @Order(1)
  void testCreateOnePropertyIndexTest() {
    oClass.createIndex(
        "ClassIndexTestPropertyOne",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fOne"});
    assertEquals(
        "ClassIndexTestPropertyOne",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestPropertyOne")
            .getName());
  }

  @Test
  @Order(2)
  void testCreateOnePropertyIndexInvalidName() {
    try {
      oClass.createIndex(
          "ClassIndex:TestPropertyOne",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[] {"fOne"});
      fail();
    } catch (Exception e) {

      Throwable cause = e;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }

      assertTrue(
          (cause instanceof IllegalArgumentException)
              || (cause instanceof CommandSQLParsingException));
    }
  }

  @Test
  @Order(3)
  void createCompositeIndexTestWithoutListener() {
    oClass.createIndex(
        "ClassIndexTestCompositeOne",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fOne", "fTwo"});

    assertEquals(
        "ClassIndexTestCompositeOne",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeOne")
            .getName());
  }

  @Test
  @Order(4)
  void createCompositeIndexTestWithListener() {
    final var atomicInteger = new AtomicInteger(0);
    final var progressListener =
        new ProgressListener() {
          @Override
          public void onBegin(final Object iTask, final long iTotal,
              Object metadata) {
            atomicInteger.incrementAndGet();
          }

          @Override
          public boolean onProgress(final Object iTask, final long iCounter,
              final float iPercent) {
            return true;
          }

          @Override
          public void onCompletition(DatabaseSessionEmbedded session,
              final Object iTask, final boolean iSucceed) {
            atomicInteger.incrementAndGet();
          }
        };

    oClass.createIndex(
        "ClassIndexTestCompositeTwo",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        progressListener,
        Map.of("ignoreNullValues", true),
        new String[] {"fOne", "fTwo", "fThree"});
    assertEquals(
        "ClassIndexTestCompositeTwo",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeTwo")
            .getName());
    assertEquals(2, atomicInteger.get());
  }

  @Test
  @Order(5)
  void testCreateOnePropertyEmbeddedMapIndex() {
    oClass.createIndex(
        "ClassIndexTestPropertyEmbeddedMap",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fEmbeddedMap"});

    assertEquals(
        "ClassIndexTestPropertyEmbeddedMap",
        oClass.getClassIndex(session, "ClassIndexTestPropertyEmbeddedMap").getName());
    assertEquals(
        "ClassIndexTestPropertyEmbeddedMap",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", "ClassIndexTestPropertyEmbeddedMap")
            .getName());

    final var indexDefinition =
        session.getIndex("ClassIndexTestPropertyEmbeddedMap").getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals("fEmbeddedMap", indexDefinition.getProperties().getFirst());
    assertEquals(PropertyTypeInternal.STRING, indexDefinition.getTypes()[0]);
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(6)
  void testCreateCompositeEmbeddedMapIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedMap",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fFifteen", "fEmbeddedMap"});

    assertEquals(
        "ClassIndexTestCompositeEmbeddedMap",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", "ClassIndexTestCompositeEmbeddedMap")
            .getName());

    final var indexDefinition =
        session.getIndex("ClassIndexTestCompositeEmbeddedMap").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fFifteen", "fEmbeddedMap"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(7)
  void testCreateCompositeEmbeddedMapByKeyIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedMapByKey",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fEight", "fEmbeddedMap"});

    assertEquals(
        "ClassIndexTestCompositeEmbeddedMapByKey",
        oClass.getClassIndex(session, "ClassIndexTestCompositeEmbeddedMapByKey")
            .getName());
    assertEquals(
        "ClassIndexTestCompositeEmbeddedMapByKey",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass",
                "ClassIndexTestCompositeEmbeddedMapByKey")
            .getName());

    final var indexDefinition = session.getIndex(
        "ClassIndexTestCompositeEmbeddedMapByKey").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fEight", "fEmbeddedMap"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(8)
  void testCreateCompositeEmbeddedMapByValueIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedMapByValue",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fTen", "fEmbeddedMap by value"});

    assertEquals(
        "ClassIndexTestCompositeEmbeddedMapByValue",
        oClass.getClassIndex(session, "ClassIndexTestCompositeEmbeddedMapByValue")
            .getName());
    assertEquals(
        "ClassIndexTestCompositeEmbeddedMapByValue",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass",
                "ClassIndexTestCompositeEmbeddedMapByValue")
            .getName());

    final var indexDefinition = session.getIndex(
        "ClassIndexTestCompositeEmbeddedMapByValue").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fTen", "fEmbeddedMap"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(9)
  void testCreateCompositeLinkMapByValueIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeLinkMapByValue",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fEleven", "fLinkMap by value"});

    assertEquals(
        "ClassIndexTestCompositeLinkMapByValue",
        oClass.getClassIndex(session, "ClassIndexTestCompositeLinkMapByValue")
            .getName());
    assertEquals(
        "ClassIndexTestCompositeLinkMapByValue",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass",
                "ClassIndexTestCompositeLinkMapByValue")
            .getName());

    final var indexDefinition = session.getIndex(
        "ClassIndexTestCompositeLinkMapByValue").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fEleven", "fLinkMap"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(10)
  void testCreateCompositeEmbeddedSetIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedSet",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fTwelve", "fEmbeddedSet"});

    assertEquals(
        "ClassIndexTestCompositeEmbeddedSet",
        oClass.getClassIndex(session, "ClassIndexTestCompositeEmbeddedSet").getName());
    assertEquals(
        "ClassIndexTestCompositeEmbeddedSet",
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", "ClassIndexTestCompositeEmbeddedSet")
            .getName());

    final var indexDefinition =
        session.getIndex("ClassIndexTestCompositeEmbeddedSet").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fTwelve", "fEmbeddedSet"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(11)
  void testCreateCompositeEmbeddedListIndex() {
    var indexName = "ClassIndexTestCompositeEmbeddedList";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fThirteen", "fEmbeddedList"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fThirteen", "fEmbeddedList"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(12)
  void testCreateCompositeLinkListIndex() {
    var indexName = "ClassIndexTestCompositeLinkList";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fFourteen", "fLinkList"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fFourteen", "fLinkList"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(13)
  void testCreateCompositeRidBagIndex() {
    var indexName = "ClassIndexTestCompositeRidBag";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fFourteen", "fRidBag"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fFourteen", "fRidBag"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(14)
  void testCreateOnePropertyLinkedMapIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMap";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fLinkMap"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals("fLinkMap", indexDefinition.getProperties().getFirst());
    assertEquals(PropertyTypeInternal.STRING, indexDefinition.getTypes()[0]);
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(15)
  void testCreateOnePropertyLinkMapByKeyIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMapByKey";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fLinkMap by key"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals("fLinkMap", indexDefinition.getProperties().getFirst());
    assertEquals(PropertyTypeInternal.STRING, indexDefinition.getTypes()[0]);
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(16)
  void testCreateOnePropertyLinkMapByValueIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMapByValue";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fLinkMap by value"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals("fLinkMap", indexDefinition.getProperties().getFirst());
    assertEquals(PropertyTypeInternal.LINK, indexDefinition.getTypes()[0]);
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.VALUE,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(17)
  void testCreateOnePropertyByKeyEmbeddedMapIndex() {
    var indexName = "ClassIndexTestPropertyByKeyEmbeddedMap";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fEmbeddedMap by key"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals("fEmbeddedMap", indexDefinition.getProperties().getFirst());
    assertEquals(PropertyTypeInternal.STRING, indexDefinition.getTypes()[0]);
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.KEY,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(18)
  void testCreateOnePropertyByValueEmbeddedMapIndex() {
    var indexName = "ClassIndexTestPropertyByValueEmbeddedMap";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fEmbeddedMap by value"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, indexName).getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", indexName)
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals("fEmbeddedMap", indexDefinition.getProperties().getFirst());
    assertEquals(PropertyTypeInternal.INTEGER, indexDefinition.getTypes()[0]);
    assertEquals(
        PropertyMapIndexDefinition.INDEX_BY.VALUE,
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy());
  }

  @Test
  @Order(19)
  void testCreateOnePropertyWrongSpecifierEmbeddedMapIndexOne() {
    var exceptionIsThrown = false;
    try {
      oClass.createIndex(
          "ClassIndexTestPropertyWrongSpecifierEmbeddedMap",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true),
          new String[] {"fEmbeddedMap by ttt"});
    } catch (Exception e) {
      assertTrue(e instanceof IllegalArgumentException);
      exceptionIsThrown = true;
      assertEquals(
          "Illegal field name format, should be '<property> [by key|value]' but was"
              + " 'fEmbeddedMap by ttt'",
          e.getMessage());
    }

    assertTrue(exceptionIsThrown);
    assertNull(
        oClass.getClassIndex(
            session, "ClassIndexTestPropertyWrongSpecifierEmbeddedMap"));
  }

  @Test
  @Order(20)
  void testCreateOnePropertyWrongSpecifierEmbeddedMapIndexTwo() {
    var exceptionIsThrown = false;
    try {
      oClass.createIndex(
          "ClassIndexTestPropertyWrongSpecifierEmbeddedMap",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true),
          new String[] {"fEmbeddedMap b value"});
    } catch (IndexException e) {
      exceptionIsThrown = true;
    }

    assertTrue(exceptionIsThrown);
    assertNull(
        oClass.getClassIndex(
            session, "ClassIndexTestPropertyWrongSpecifierEmbeddedMap"));
  }

  @Test
  @Order(21)
  void testCreateOnePropertyWrongSpecifierEmbeddedMapIndexThree() {
    var exceptionIsThrown = false;
    try {
      oClass.createIndex(
          "ClassIndexTestPropertyWrongSpecifierEmbeddedMap",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true),
          new String[] {"fEmbeddedMap by value t"});
    } catch (IndexException e) {
      exceptionIsThrown = true;
    }

    assertTrue(exceptionIsThrown);
    assertNull(
        oClass.getClassIndex(
            session, "ClassIndexTestPropertyWrongSpecifierEmbeddedMap"));
  }

  @Test
  @Order(22)
  void createParentPropertyIndex() {
    oSuperClass.createIndex(
        "ClassIndexTestParentPropertyNine",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fNine"});

    assertEquals(
        "ClassIndexTestParentPropertyNine",
        oSuperClass.getClassIndex(session, "ClassIndexTestParentPropertyNine")
            .getName());
  }

  @Test
  @Order(23)
  void testGetIndexesWithoutParent() {
    final var inClass = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass("ClassIndexInTest");
    inClass.createProperty("fOne", PropertyType.INTEGER);

    var indexName = "ClassIndexInTestPropertyOne";
    inClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fOne"});

    assertEquals(
        indexName,
        inClass.getClassIndex(session, indexName).getName());

    final var indexes = inClass.getIndexesInternal();
    final var propertyIndexDefinition =
        new PropertyIndexDefinition(
            "ClassIndexInTest", "fOne", PropertyTypeInternal.INTEGER);

    assertEquals(1, indexes.size());

    assertEquals(
        propertyIndexDefinition, indexes.iterator().next().getDefinition());
  }

  @Test
  @Order(24)
  void testCreateIndexEmptyFields() {
    assertThrows(IndexException.class, () -> oClass.createIndex("ClassIndexTestCompositeEmpty",
        SchemaClass.INDEX_TYPE.UNIQUE));
  }

  @Test
  @Order(25)
  void testCreateIndexAbsentFields() {
    assertThrows(IndexException.class, () -> oClass.createIndex(
        "ClassIndexTestCompositeFieldAbsent",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[] {"fFive"}));
  }

  @Test
  @Order(26)
  void testCreateMapWithoutLinkedType() {
    try {
      oClass.createIndex(
          "ClassIndexMapWithoutLinkedTypeIndex",
          SchemaClass.INDEX_TYPE.NOTUNIQUE,
          "fEmbeddedMapWithoutLinkedType by value");
      fail();
    } catch (IndexException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Linked type was not provided. You should provide linked type"
                      + " for embedded collections that are going to be"
                      + " indexed."));
    }
  }

  @Test
  @Order(27)
  void testDropProperty() throws Exception {
    oClass.createProperty("fFive", PropertyType.INTEGER);

    oClass.dropProperty("fFive");

    assertNull(oClass.getProperty("fFive"));
  }

  // --- Phase 2: Tests depending on creation (Order 30-70) ---

  @Test
  @Order(30)
  void testAreIndexedOneProperty() {
    final var result = oClass.areIndexed(session, List.of("fOne"));

    assertTrue(result);
  }

  @Test
  @Order(31)
  void testAreIndexedEightProperty() {
    final var result = oClass.areIndexed(session, List.of("fEight"));
    assertTrue(result);
  }

  @Test
  @Order(32)
  void testAreIndexedEightPropertyEmbeddedMap() {
    final var result =
        oClass.areIndexed(session, Arrays.asList("fEmbeddedMap", "fEight"));
    assertTrue(result);
  }

  @Test
  @Order(33)
  void testAreIndexedDoesNotContainProperty() {
    final var result = oClass.areIndexed(session, List.of("fSix"));

    assertFalse(result);
  }

  @Test
  @Order(34)
  void testAreIndexedTwoProperties() {
    final var result =
        oClass.areIndexed(session, Arrays.asList("fTwo", "fOne"));

    assertTrue(result);
  }

  @Test
  @Order(35)
  void testAreIndexedThreeProperties() {
    final var result =
        oClass.areIndexed(session, Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  @Test
  @Order(36)
  void testAreIndexedPropertiesNotFirst() {
    final var result =
        oClass.areIndexed(session, Arrays.asList("fTwo", "fTree"));

    assertFalse(result);
  }

  @Test
  @Order(37)
  void testAreIndexedPropertiesMoreThanNeeded() {
    final var result = oClass.areIndexed(
        session, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertFalse(result);
  }

  @Test
  @Order(38)
  void testAreIndexedParentProperty() {
    final var result = oClass.areIndexed(session, List.of("fNine"));

    assertTrue(result);
  }

  @Test
  @Order(39)
  void testAreIndexedParentChildProperty() {
    final var result = oClass.areIndexed(session, List.of("fOne, fNine"));

    assertFalse(result);
  }

  @Test
  @Order(40)
  void testAreIndexedOnePropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fOne");

    assertTrue(result);
  }

  @Test
  @Order(41)
  void testAreIndexedDoesNotContainPropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fSix");

    assertFalse(result);
  }

  @Test
  @Order(42)
  void testAreIndexedTwoPropertiesArrayParams() {
    final var result = oClass.areIndexed(session, "fTwo", "fOne");

    assertTrue(result);
  }

  @Test
  @Order(43)
  void testAreIndexedThreePropertiesArrayParams() {
    final var result = oClass.areIndexed(session, "fTwo", "fOne", "fThree");

    assertTrue(result);
  }

  @Test
  @Order(44)
  void testAreIndexedPropertiesNotFirstArrayParams() {
    final var result = oClass.areIndexed(session, "fTwo", "fTree");

    assertFalse(result);
  }

  @Test
  @Order(45)
  void testAreIndexedPropertiesMoreThanNeededArrayParams() {
    final var result =
        oClass.areIndexed(session, "fTwo", "fOne", "fThee", "fFour");

    assertFalse(result);
  }

  @Test
  @Order(46)
  void testAreIndexedParentPropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fNine");

    assertTrue(result);
  }

  @Test
  @Order(47)
  void testAreIndexedParentChildPropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fOne, fNine");

    assertFalse(result);
  }

  @Test
  @Order(48)
  void testGetClassInvolvedIndexesOnePropertyArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(session, "fOne");

    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test
  @Order(49)
  void testGetClassInvolvedIndexesTwoPropertiesArrayParams() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(session, "fTwo", "fOne");
    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test
  @Order(50)
  void testGetClassInvolvedIndexesThreePropertiesArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(
        session, "fTwo", "fOne", "fThree");

    assertEquals(1, result.size());
    assertEquals(
        "ClassIndexTestCompositeTwo",
        result.iterator().next().getName());
  }

  @Test
  @Order(51)
  void testGetClassInvolvedIndexesNotInvolvedPropertiesArrayParams() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(session, "fTwo", "fFour");

    assertEquals(0, result.size());
  }

  @Test
  @Order(52)
  void testGetClassInvolvedIndexesPropertiesMorThanNeededArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(
        session, "fTwo", "fOne", "fThee", "fFour");

    assertEquals(0, result.size());
  }

  @Test
  @Order(53)
  void testGetInvolvedIndexesPropertiesMorThanNeeded() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(
            session, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(0, result.size());
  }

  @Test
  @Order(54)
  void testGetClassInvolvedIndexesOneProperty() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(session, List.of("fOne"));

    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test
  @Order(55)
  void testGetClassInvolvedIndexesTwoProperties() {
    final var result = oClass.getClassInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fOne"));
    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test
  @Order(56)
  void testGetClassInvolvedIndexesThreeProperties() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(
            session, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(1, result.size());
    assertEquals(
        "ClassIndexTestCompositeTwo",
        result.iterator().next().getName());
  }

  @Test
  @Order(57)
  void testGetClassInvolvedIndexesNotInvolvedProperties() {
    final var result = oClass.getClassInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fFour"));

    assertEquals(0, result.size());
  }

  @Test
  @Order(58)
  void testGetClassInvolvedIndexesPropertiesMorThanNeeded() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(
            session, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(0, result.size());
  }

  @Test
  @Order(59)
  void testGetInvolvedIndexesOnePropertyArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fOne");

    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test
  @Order(60)
  void testGetInvolvedIndexesTwoPropertiesArrayParams() {
    final var result =
        oClass.getInvolvedIndexesInternal(session, "fTwo", "fOne");
    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test
  @Order(61)
  void testGetInvolvedIndexesThreePropertiesArrayParams() {
    final var result =
        oClass.getInvolvedIndexesInternal(session, "fTwo", "fOne", "fThree");

    assertEquals(1, result.size());
    assertEquals(
        "ClassIndexTestCompositeTwo",
        result.iterator().next().getName());
  }

  @Test
  @Order(62)
  void testGetInvolvedIndexesNotInvolvedPropertiesArrayParams() {
    final var result =
        oClass.getInvolvedIndexesInternal(session, "fTwo", "fFour");

    assertEquals(0, result.size());
  }

  @Test
  @Order(63)
  void testGetParentInvolvedIndexesArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fNine");

    assertEquals(1, result.size());
    assertEquals(
        "ClassIndexTestParentPropertyNine",
        result.iterator().next().getName());
  }

  @Test
  @Order(64)
  void testGetParentChildInvolvedIndexesArrayParams() {
    final var result =
        oClass.getInvolvedIndexesInternal(session, "fOne", "fNine");

    assertEquals(0, result.size());
  }

  @Test
  @Order(65)
  void testGetInvolvedIndexesOneProperty() {
    final var result =
        oClass.getInvolvedIndexesInternal(session, List.of("fOne"));

    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test
  @Order(66)
  void testGetInvolvedIndexesTwoProperties() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fOne"));
    assertEquals(1, result.size());

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test
  @Order(67)
  void testGetInvolvedIndexesThreeProperties() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(1, result.size());
    assertEquals(
        "ClassIndexTestCompositeTwo",
        result.iterator().next().getName());
  }

  @Test
  @Order(68)
  void testGetInvolvedIndexesNotInvolvedProperties() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fFour"));

    assertEquals(0, result.size());
  }

  @Test
  @Order(69)
  void testGetParentInvolvedIndexes() {
    final var result =
        oClass.getInvolvedIndexesInternal(session, List.of("fNine"));

    assertEquals(1, result.size());
    assertEquals(
        "ClassIndexTestParentPropertyNine",
        result.iterator().next().getName());
  }

  @Test
  @Order(70)
  void testGetParentChildInvolvedIndexes() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fOne", "fNine"));

    assertEquals(0, result.size());
  }

  // --- Phase 3: Depends on creation + linkList/ridBag ---

  //   testCreateCompositeRidBagIndex
  @Test
  @Order(71)
  void testGetClassIndexes() {
    final var indexes = oClass.getClassIndexesInternal();
    final Set<IndexDefinition> expectedIndexDefinitions = new HashSet<>();

    final var compositeIndexOne =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexOne.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexOne.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    expectedIndexDefinitions.add(compositeIndexOne);

    final var compositeIndexTwo =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fThree", PropertyTypeInternal.BOOLEAN));
    expectedIndexDefinitions.add(compositeIndexTwo);

    final var compositeIndexThree =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexThree.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fEight", PropertyTypeInternal.INTEGER));
    compositeIndexThree.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY));
    expectedIndexDefinitions.add(compositeIndexThree);

    final var compositeIndexFour =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexFour.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTen", PropertyTypeInternal.INTEGER));
    compositeIndexFour.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.INTEGER,
            PropertyMapIndexDefinition.INDEX_BY.VALUE));
    expectedIndexDefinitions.add(compositeIndexFour);

    final var compositeIndexFive =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexFive.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fEleven", PropertyTypeInternal.INTEGER));
    compositeIndexFive.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.LINK,
            PropertyMapIndexDefinition.INDEX_BY.VALUE));
    expectedIndexDefinitions.add(compositeIndexFive);

    final var compositeIndexSix =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexSix.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTwelve", PropertyTypeInternal.INTEGER));
    compositeIndexSix.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fEmbeddedSet",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSix);

    final var compositeIndexSeven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexSeven.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fThirteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexSeven.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSeven);

    final var compositeIndexEight =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEight.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEight.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexEight);

    final var compositeIndexNine =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexNine.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFifteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexNine.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY));
    expectedIndexDefinitions.add(compositeIndexNine);

    final var compositeIndexTen =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexTen.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexTen.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fLinkList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexTen);

    final var compositeIndexEleven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEleven.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEleven.addIndex(
        new PropertyLinkBagIndexDefinition(
            "ClassIndexTestClass", "fRidBag"));
    expectedIndexDefinitions.add(compositeIndexEleven);

    final var propertyIndex =
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER);
    expectedIndexDefinitions.add(propertyIndex);

    final var propertyMapIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY);
    expectedIndexDefinitions.add(propertyMapIndexDefinition);

    final var propertyMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.INTEGER,
            PropertyMapIndexDefinition.INDEX_BY.VALUE);
    expectedIndexDefinitions.add(propertyMapByValueIndexDefinition);

    final var propertyLinkMapByKeyIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY);
    expectedIndexDefinitions.add(propertyLinkMapByKeyIndexDefinition);

    final var propertyLinkMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.LINK,
            PropertyMapIndexDefinition.INDEX_BY.VALUE);
    expectedIndexDefinitions.add(propertyLinkMapByValueIndexDefinition);

    assertEquals(17, indexes.size());

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  //   testCreateCompositeLinkListIndex, testCreateCompositeRidBagIndex
  @Test
  @Order(72)
  void testGetIndexes() {
    final var indexes = oClass.getIndexesInternal();
    final Set<IndexDefinition> expectedIndexDefinitions = new HashSet<>();

    final var compositeIndexOne =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexOne.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexOne.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    expectedIndexDefinitions.add(compositeIndexOne);

    final var compositeIndexTwo =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fThree", PropertyTypeInternal.BOOLEAN));
    expectedIndexDefinitions.add(compositeIndexTwo);

    final var compositeIndexThree =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexThree.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fEight", PropertyTypeInternal.INTEGER));
    compositeIndexThree.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY));
    expectedIndexDefinitions.add(compositeIndexThree);

    final var compositeIndexFour =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexFour.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTen", PropertyTypeInternal.INTEGER));
    compositeIndexFour.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.INTEGER,
            PropertyMapIndexDefinition.INDEX_BY.VALUE));
    expectedIndexDefinitions.add(compositeIndexFour);

    final var compositeIndexFive =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexFive.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fEleven",
            PropertyTypeInternal.INTEGER));
    compositeIndexFive.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.LINK,
            PropertyMapIndexDefinition.INDEX_BY.VALUE));
    expectedIndexDefinitions.add(compositeIndexFive);

    final var compositeIndexSix =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexSix.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fTwelve",
            PropertyTypeInternal.INTEGER));
    compositeIndexSix.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fEmbeddedSet",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSix);

    final var compositeIndexSeven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexSeven.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fThirteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexSeven.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSeven);

    final var compositeIndexEight =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEight.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEight.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexEight);

    final var compositeIndexNine =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexNine.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFifteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexNine.addIndex(
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY));
    expectedIndexDefinitions.add(compositeIndexNine);

    final var compositeIndexTen =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexTen.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexTen.addIndex(
        new PropertyListIndexDefinition(
            "ClassIndexTestClass", "fLinkList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexTen);

    final var compositeIndexEleven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEleven.addIndex(
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEleven.addIndex(
        new PropertyLinkBagIndexDefinition(
            "ClassIndexTestClass", "fRidBag"));
    expectedIndexDefinitions.add(compositeIndexEleven);

    final var propertyIndex =
        new PropertyIndexDefinition(
            "ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER);
    expectedIndexDefinitions.add(propertyIndex);

    final var parentPropertyIndex =
        new PropertyIndexDefinition(
            "ClassIndexTestSuperClass", "fNine",
            PropertyTypeInternal.INTEGER);
    expectedIndexDefinitions.add(parentPropertyIndex);

    final var propertyMapIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY);
    expectedIndexDefinitions.add(propertyMapIndexDefinition);

    final var propertyMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.INTEGER,
            PropertyMapIndexDefinition.INDEX_BY.VALUE);
    expectedIndexDefinitions.add(propertyMapByValueIndexDefinition);

    final var propertyLinkMapByKeyIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.STRING,
            PropertyMapIndexDefinition.INDEX_BY.KEY);
    expectedIndexDefinitions.add(propertyLinkMapByKeyIndexDefinition);

    final var propertyLinkMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.LINK,
            PropertyMapIndexDefinition.INDEX_BY.VALUE);
    expectedIndexDefinitions.add(propertyLinkMapByValueIndexDefinition);

    assertEquals(18, indexes.size());

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  // --- Phase 4: Depends on testGetIndexes / testGetInvolvedIndexesOnePropertyArrayParams ---

  @Test
  @Order(73)
  void testCreateCompositeLinkSetIndex() {
    var indexName = "ClassIndexTestCompositeLinkSet";
    oClass.createIndex(
        "ClassIndexTestCompositeLinkSet",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[] {"fTwelve", "fLinkSet"});

    assertEquals(
        indexName,
        oClass.getClassIndex(session, "ClassIndexTestCompositeLinkSet")
            .getName());
    assertEquals(
        indexName,
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass",
                "ClassIndexTestCompositeLinkSet")
            .getName());

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertArrayEquals(
        new String[] {"fTwelve", "fLinkSet"},
        indexDefinition.getProperties().toArray());

    assertArrayEquals(
        new PropertyTypeInternal[] {
            PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK},
        indexDefinition.getTypes());
    assertEquals(2, indexDefinition.getParamCount());
  }

  @Test
  @Order(74)
  void testCreateNotUniqueIndex() {
    oClass.createIndex("ClassIndexTestNotUniqueIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fOne");

    assertEquals(
        "ClassIndexTestNotUniqueIndex",
        oClass.getClassIndex(session, "ClassIndexTestNotUniqueIndex")
            .getName());
    var index = session.getIndex("ClassIndexTestNotUniqueIndex");
    assertEquals(
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(), index.getType());
  }

  private static boolean containsIndex(
      final Collection<? extends Index> classIndexes,
      final String indexName) {
    for (final var index : classIndexes) {
      if (index.getName().equals(indexName)) {
        return true;
      }
    }
    return false;
  }
}
