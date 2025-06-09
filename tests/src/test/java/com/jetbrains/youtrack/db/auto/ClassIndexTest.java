package com.jetbrains.youtrack.db.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import com.jetbrains.youtrack.db.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.common.listener.ProgressListener;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.index.IndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.IndexException;
import com.jetbrains.youtrack.db.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.PropertyListIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaClassInternal;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class ClassIndexTest extends BaseDBTest {
  private SchemaClassInternal oClass;
  private SchemaClassInternal oSuperClass;

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

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

  @BeforeMethod
  @Override
  public void beforeMethod() throws Exception {
    super.beforeMethod();

    oClass = session.getClassInternal("ClassIndexTestClass");
    oSuperClass = session.getClassInternal("ClassIndexTestSuperClass");
  }

  @Test
  public void testCreateOnePropertyIndexTest() {
    oClass.createIndex(
        "ClassIndexTestPropertyOne",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fOne"});
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestPropertyOne")
            .getName(),
        "ClassIndexTestPropertyOne");
  }

  @Test
  public void testCreateOnePropertyIndexInvalidName() {
    try {
      oClass.createIndex(
          "ClassIndex:TestPropertyOne",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[]{"fOne"});
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
  public void createCompositeIndexTestWithoutListener() {
    oClass.createIndex(
        "ClassIndexTestCompositeOne",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fOne", "fTwo"});

    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeOne")
            .getName(),
        "ClassIndexTestCompositeOne");
  }

  @Test
  public void createCompositeIndexTestWithListener() {
    final var atomicInteger = new AtomicInteger(0);
    final var progressListener =
        new ProgressListener() {
          @Override
          public void onBegin(final Object iTask, final long iTotal, Object metadata) {
            atomicInteger.incrementAndGet();
          }

          @Override
          public boolean onProgress(final Object iTask, final long iCounter, final float iPercent) {
            return true;
          }

          @Override
          public void onCompletition(DatabaseSessionEmbedded session, final Object iTask,
              final boolean iSucceed) {
            atomicInteger.incrementAndGet();
          }
        };

    oClass.createIndex(
        "ClassIndexTestCompositeTwo",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        progressListener,
        Map.of("ignoreNullValues", true),
        new String[]{"fOne", "fTwo", "fThree"});
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeTwo")
            .getName(),
        "ClassIndexTestCompositeTwo");
    assertEquals(atomicInteger.get(), 2);
  }

  @Test
  public void testCreateOnePropertyEmbeddedMapIndex() {
    oClass.createIndex(
        "ClassIndexTestPropertyEmbeddedMap",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fEmbeddedMap"});

    assertEquals(
        oClass.getClassIndex(session, "ClassIndexTestPropertyEmbeddedMap").getName(),
        "ClassIndexTestPropertyEmbeddedMap");
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestPropertyEmbeddedMap")
            .getName(),
        "ClassIndexTestPropertyEmbeddedMap");

    final var indexDefinition = session.getIndex("ClassIndexTestPropertyEmbeddedMap")
        .getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fEmbeddedMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.KEY);
  }

  @Test
  public void testCreateCompositeEmbeddedMapIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedMap",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fFifteen", "fEmbeddedMap"});

    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeEmbeddedMap")
            .getName(),
        "ClassIndexTestCompositeEmbeddedMap");

    final var indexDefinition = session.getIndex("ClassIndexTestCompositeEmbeddedMap")
        .getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(),
        new String[]{"fFifteen", "fEmbeddedMap"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeEmbeddedMapByKeyIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedMapByKey",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fEight", "fEmbeddedMap"});

    assertEquals(
        oClass.getClassIndex(session, "ClassIndexTestCompositeEmbeddedMapByKey").getName(),
        "ClassIndexTestCompositeEmbeddedMapByKey");
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", "ClassIndexTestCompositeEmbeddedMapByKey")
            .getName(),
        "ClassIndexTestCompositeEmbeddedMapByKey");

    final var indexDefinition = session.getIndex(
        "ClassIndexTestCompositeEmbeddedMapByKey").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fEight", "fEmbeddedMap"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeEmbeddedMapByValueIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedMapByValue",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fTen", "fEmbeddedMap by value"});

    assertEquals(
        oClass.getClassIndex(session, "ClassIndexTestCompositeEmbeddedMapByValue").getName(),
        "ClassIndexTestCompositeEmbeddedMapByValue");
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", "ClassIndexTestCompositeEmbeddedMapByValue")
            .getName(),
        "ClassIndexTestCompositeEmbeddedMapByValue");

    final var indexDefinition = session.getIndex(
        "ClassIndexTestCompositeEmbeddedMapByValue").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fTen", "fEmbeddedMap"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeLinkMapByValueIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeLinkMapByValue",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fEleven", "fLinkMap by value"});

    assertEquals(
        oClass.getClassIndex(session, "ClassIndexTestCompositeLinkMapByValue").getName(),
        "ClassIndexTestCompositeLinkMapByValue");
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeLinkMapByValue")
            .getName(),
        "ClassIndexTestCompositeLinkMapByValue");

    final var indexDefinition = session.getIndex(
        "ClassIndexTestCompositeLinkMapByValue").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fEleven", "fLinkMap"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeEmbeddedSetIndex() {
    oClass.createIndex(
        "ClassIndexTestCompositeEmbeddedSet",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fTwelve", "fEmbeddedSet"});

    assertEquals(
        oClass.getClassIndex(session, "ClassIndexTestCompositeEmbeddedSet").getName(),
        "ClassIndexTestCompositeEmbeddedSet");
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeEmbeddedSet")
            .getName(),
        "ClassIndexTestCompositeEmbeddedSet");

    final var indexDefinition = session.getIndex("ClassIndexTestCompositeEmbeddedSet")
        .getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(),
        new String[]{"fTwelve", "fEmbeddedSet"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test(dependsOnMethods = "testGetIndexes")
  public void testCreateCompositeLinkSetIndex() {
    var indexName = "ClassIndexTestCompositeLinkSet";
    oClass.createIndex(
        "ClassIndexTestCompositeLinkSet",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fTwelve", "fLinkSet"});

    assertEquals(
        oClass.getClassIndex(session, "ClassIndexTestCompositeLinkSet").getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", "ClassIndexTestCompositeLinkSet")
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName)
        .getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fTwelve", "fLinkSet"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeEmbeddedListIndex() {
    var indexName = "ClassIndexTestCompositeEmbeddedList";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fThirteen", "fEmbeddedList"});

    assertEquals(
        oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName)
        .getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(
        indexDefinition.getProperties().toArray(), new String[]{"fThirteen", "fEmbeddedList"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  public void testCreateCompositeLinkListIndex() {
    var indexName = "ClassIndexTestCompositeLinkList";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fFourteen", "fLinkList"});

    assertEquals(
        oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fFourteen", "fLinkList"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  public void testCreateCompositeRidBagIndex() {
    var indexName = "ClassIndexTestCompositeRidBag";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fFourteen", "fRidBag"});

    assertEquals(oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fFourteen", "fRidBag"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateOnePropertyLinkedMapIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMap";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fLinkMap"});

    assertEquals(
        oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fLinkMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.KEY);
  }

  @Test
  public void testCreateOnePropertyLinkMapByKeyIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMapByKey";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fLinkMap by key"});

    assertEquals(
        oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fLinkMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.KEY);
  }

  @Test
  public void testCreateOnePropertyLinkMapByValueIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMapByValue";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fLinkMap by value"});

    assertEquals(
        oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fLinkMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.LINK);
    assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.VALUE);
  }

  @Test
  public void testCreateOnePropertyByKeyEmbeddedMapIndex() {
    var indexName = "ClassIndexTestPropertyByKeyEmbeddedMap";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fEmbeddedMap by key"});

    assertEquals(
        oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fEmbeddedMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.KEY);
  }

  @Test
  public void testCreateOnePropertyByValueEmbeddedMapIndex() {
    var indexName = "ClassIndexTestPropertyByValueEmbeddedMap";
    oClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true),
        new String[]{"fEmbeddedMap by value"});

    assertEquals(
        oClass.getClassIndex(session, indexName).getName(),
        indexName);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(
                session, "ClassIndexTestClass", indexName)
            .getName(),
        indexName);

    final var indexDefinition = session.getIndex(indexName).getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fEmbeddedMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.INTEGER);
    assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.VALUE);
  }

  @Test
  public void testCreateOnePropertyWrongSpecifierEmbeddedMapIndexOne() {
    var exceptionIsThrown = false;
    try {
      oClass.createIndex(
          "ClassIndexTestPropertyWrongSpecifierEmbeddedMap",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true), new String[]{"fEmbeddedMap by ttt"});
    } catch (Exception e) {
      Assert.assertTrue(e instanceof IllegalArgumentException);
      exceptionIsThrown = true;
      assertEquals(
          e.getMessage(),
          "Illegal field name format, should be '<property> [by key|value]' but was 'fEmbeddedMap"
              + " by ttt'");
    }

    assertTrue(exceptionIsThrown);
    assertNull(oClass.getClassIndex(session, "ClassIndexTestPropertyWrongSpecifierEmbeddedMap"));
  }

  @Test
  public void testCreateOnePropertyWrongSpecifierEmbeddedMapIndexTwo() {
    var exceptionIsThrown = false;
    try {
      oClass.createIndex(
          "ClassIndexTestPropertyWrongSpecifierEmbeddedMap",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true),
          new String[]{"fEmbeddedMap b value"});
    } catch (IndexException e) {
      exceptionIsThrown = true;
    }

    assertTrue(exceptionIsThrown);
    assertNull(oClass.getClassIndex(session, "ClassIndexTestPropertyWrongSpecifierEmbeddedMap"));
  }

  @Test
  public void testCreateOnePropertyWrongSpecifierEmbeddedMapIndexThree() {
    var exceptionIsThrown = false;
    try {
      oClass.createIndex(
          "ClassIndexTestPropertyWrongSpecifierEmbeddedMap",
          SchemaClass.INDEX_TYPE.UNIQUE.toString(),
          null,
          Map.of("ignoreNullValues", true),
          new String[]{"fEmbeddedMap by value t"});
    } catch (IndexException e) {
      exceptionIsThrown = true;
    }

    assertTrue(exceptionIsThrown);
    assertNull(oClass.getClassIndex(session, "ClassIndexTestPropertyWrongSpecifierEmbeddedMap"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedOneProperty() {
    final var result = oClass.areIndexed(session, List.of("fOne"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedEightProperty() {
    final var result = oClass.areIndexed(session, List.of("fEight"));
    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedEightPropertyEmbeddedMap() {
    final var result = oClass.areIndexed(session, Arrays.asList("fEmbeddedMap", "fEight"));
    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedDoesNotContainProperty() {
    final var result = oClass.areIndexed(session, List.of("fSix"));

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedTwoProperties() {
    final var result = oClass.areIndexed(session, Arrays.asList("fTwo", "fOne"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedThreeProperties() {
    final var result = oClass.areIndexed(session, Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedPropertiesNotFirst() {
    final var result = oClass.areIndexed(session, Arrays.asList("fTwo", "fTree"));

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedPropertiesMoreThanNeeded() {
    final var result = oClass.areIndexed(
        session, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "createParentPropertyIndex",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedParentProperty() {
    final var result = oClass.areIndexed(session, List.of("fNine"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedParentChildProperty() {
    final var result = oClass.areIndexed(session, List.of("fOne, fNine"));

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedOnePropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedDoesNotContainPropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fSix");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedTwoPropertiesArrayParams() {
    final var result = oClass.areIndexed(session, "fTwo", "fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedThreePropertiesArrayParams() {
    final var result = oClass.areIndexed(session, "fTwo", "fOne", "fThree");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedPropertiesNotFirstArrayParams() {
    final var result = oClass.areIndexed(session, "fTwo", "fTree");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedPropertiesMoreThanNeededArrayParams() {
    final var result = oClass.areIndexed(session, "fTwo", "fOne", "fThee", "fFour");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "createParentPropertyIndex",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedParentPropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fNine");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testAreIndexedParentChildPropertyArrayParams() {
    final var result = oClass.areIndexed(session, "fOne, fNine");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesOnePropertyArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(session, "fOne");

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesTwoPropertiesArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(session, "fTwo", "fOne");
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesThreePropertiesArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(session, "fTwo", "fOne",
        "fThree");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesNotInvolvedPropertiesArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(session, "fTwo", "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesPropertiesMorThanNeededArrayParams() {
    final var result = oClass.getClassInvolvedIndexesInternal(session, "fTwo", "fOne",
        "fThee",
        "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesPropertiesMorThanNeeded() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(
            session, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesOneProperty() {
    final var result = oClass.getClassInvolvedIndexesInternal(session, List.of("fOne"));

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesTwoProperties() {
    final var result = oClass.getClassInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fOne"));
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesThreeProperties() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(session, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesNotInvolvedProperties() {
    final var result = oClass.getClassInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetClassInvolvedIndexesPropertiesMorThanNeeded() {
    final var result =
        oClass.getClassInvolvedIndexesInternal(
            session, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesOnePropertyArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fOne");

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesTwoPropertiesArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fTwo", "fOne");
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesThreePropertiesArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fTwo", "fOne", "fThree");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesNotInvolvedPropertiesArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fTwo", "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetParentInvolvedIndexesArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fNine");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestParentPropertyNine");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetParentChildInvolvedIndexesArrayParams() {
    final var result = oClass.getInvolvedIndexesInternal(session, "fOne", "fNine");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesOneProperty() {
    final var result = oClass.getInvolvedIndexesInternal(session, List.of("fOne"));

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesTwoProperties() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fOne"));
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesThreeProperties() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetInvolvedIndexesNotInvolvedProperties() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fTwo", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetParentInvolvedIndexes() {
    final var result = oClass.getInvolvedIndexesInternal(session, List.of("fNine"));

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestParentPropertyNine");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex"
      })
  public void testGetParentChildInvolvedIndexes() {
    final var result = oClass.getInvolvedIndexesInternal(
        session, Arrays.asList("fOne", "fNine"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex",
          "testCreateCompositeLinkListIndex",
          "testCreateCompositeRidBagIndex"
      })
  public void testGetClassIndexes() {
    final var indexes = oClass.getClassIndexesInternal();
    final Set<IndexDefinition> expectedIndexDefinitions = new HashSet<>();

    final var compositeIndexOne =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexOne.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexOne.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    expectedIndexDefinitions.add(compositeIndexOne);

    final var compositeIndexTwo =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fThree", PropertyTypeInternal.BOOLEAN));
    expectedIndexDefinitions.add(compositeIndexTwo);

    final var compositeIndexThree =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexThree.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fEight", PropertyTypeInternal.INTEGER));
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fTen", PropertyTypeInternal.INTEGER));
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fEleven",
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fTwelve",
            PropertyTypeInternal.INTEGER));
    compositeIndexSix.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fEmbeddedSet",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSix);

    final var compositeIndexSeven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexSeven.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fThirteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexSeven.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSeven);

    final var compositeIndexEight =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEight.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEight.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexEight);

    final var compositeIndexNine =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexNine.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fFifteen",
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexTen.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fLinkList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexTen);

    final var compositeIndexEleven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEleven.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEleven.addIndex(
        new PropertyLinkBagIndexDefinition("ClassIndexTestClass", "fRidBag"));
    expectedIndexDefinitions.add(compositeIndexEleven);

    final var propertyIndex =
        new PropertyIndexDefinition("ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER);
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

    assertEquals(indexes.size(), 17);

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTestWithListener",
          "createCompositeIndexTestWithoutListener",
          "testCreateOnePropertyIndexTest",
          "createParentPropertyIndex",
          "testCreateOnePropertyEmbeddedMapIndex",
          "testCreateOnePropertyByKeyEmbeddedMapIndex",
          "testCreateOnePropertyByValueEmbeddedMapIndex",
          "testCreateOnePropertyLinkedMapIndex",
          "testCreateOnePropertyLinkMapByKeyIndex",
          "testCreateOnePropertyLinkMapByValueIndex",
          "testCreateCompositeEmbeddedMapIndex",
          "testCreateCompositeEmbeddedMapByKeyIndex",
          "testCreateCompositeEmbeddedMapByValueIndex",
          "testCreateCompositeLinkMapByValueIndex",
          "testCreateCompositeEmbeddedSetIndex",
          "testCreateCompositeEmbeddedListIndex",
          "testCreateCompositeLinkListIndex",
          "testCreateCompositeRidBagIndex"
      })
  public void testGetIndexes() {
    final var indexes = oClass.getIndexesInternal();
    final Set<IndexDefinition> expectedIndexDefinitions = new HashSet<>();

    final var compositeIndexOne =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexOne.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexOne.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    expectedIndexDefinitions.add(compositeIndexOne);

    final var compositeIndexTwo =
        new CompositeIndexDefinition("ClassIndexTestClass");

    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fTwo", PropertyTypeInternal.STRING));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fThree", PropertyTypeInternal.BOOLEAN));
    expectedIndexDefinitions.add(compositeIndexTwo);

    final var compositeIndexThree =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexThree.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fEight", PropertyTypeInternal.INTEGER));
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fTen", PropertyTypeInternal.INTEGER));
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fEleven",
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fTwelve",
            PropertyTypeInternal.INTEGER));
    compositeIndexSix.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fEmbeddedSet",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSix);

    final var compositeIndexSeven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexSeven.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fThirteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexSeven.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.INTEGER));
    expectedIndexDefinitions.add(compositeIndexSeven);

    final var compositeIndexEight =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEight.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEight.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fEmbeddedList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexEight);

    final var compositeIndexNine =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexNine.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fFifteen",
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
        new PropertyIndexDefinition("ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexTen.addIndex(
        new PropertyListIndexDefinition("ClassIndexTestClass", "fLinkList",
            PropertyTypeInternal.LINK));
    expectedIndexDefinitions.add(compositeIndexTen);

    final var compositeIndexEleven =
        new CompositeIndexDefinition("ClassIndexTestClass");
    compositeIndexEleven.addIndex(
        new PropertyIndexDefinition("ClassIndexTestClass", "fFourteen",
            PropertyTypeInternal.INTEGER));
    compositeIndexEleven.addIndex(
        new PropertyLinkBagIndexDefinition("ClassIndexTestClass", "fRidBag"));
    expectedIndexDefinitions.add(compositeIndexEleven);

    final var propertyIndex =
        new PropertyIndexDefinition("ClassIndexTestClass", "fOne", PropertyTypeInternal.INTEGER);
    expectedIndexDefinitions.add(propertyIndex);

    final var parentPropertyIndex =
        new PropertyIndexDefinition("ClassIndexTestSuperClass", "fNine",
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

    assertEquals(indexes.size(), 18);

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  @Test
  public void testGetIndexesWithoutParent() {
    final var inClass = (SchemaClassInternal) session.getMetadata().getSchema()
        .createClass("ClassIndexInTest");
    inClass.createProperty("fOne", PropertyType.INTEGER);

    var indexName = "ClassIndexInTestPropertyOne";
    inClass.createIndex(
        indexName,
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fOne"});

    assertEquals(inClass.getClassIndex(session, indexName).getName(),
        indexName);

    final var indexes = inClass.getIndexesInternal();
    final var propertyIndexDefinition =
        new PropertyIndexDefinition("ClassIndexInTest", "fOne", PropertyTypeInternal.INTEGER);

    assertEquals(indexes.size(), 1);

    assertEquals(propertyIndexDefinition, indexes.iterator().next().getDefinition());
  }

  @Test(expectedExceptions = IndexException.class)
  public void testCreateIndexEmptyFields() {
    oClass.createIndex("ClassIndexTestCompositeEmpty", SchemaClass.INDEX_TYPE.UNIQUE);
  }

  @Test(expectedExceptions = IndexException.class)
  public void testCreateIndexAbsentFields() {
    oClass.createIndex(
        "ClassIndexTestCompositeFieldAbsent",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fFive"});
  }

  @Test(dependsOnMethods = "testGetInvolvedIndexesOnePropertyArrayParams")
  public void testCreateNotUniqueIndex() {
    oClass.createIndex("ClassIndexTestNotUniqueIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "fOne");

    assertEquals(oClass.getClassIndex(session, "ClassIndexTestNotUniqueIndex").getName(),
        "ClassIndexTestNotUniqueIndex");
    var index = session.getIndex("ClassIndexTestNotUniqueIndex");
    assertEquals(index.getType(), SchemaClass.INDEX_TYPE.NOTUNIQUE.toString());
  }

  @Test
  public void testCreateMapWithoutLinkedType() {
    try {
      oClass.createIndex(
          "ClassIndexMapWithoutLinkedTypeIndex",
          SchemaClass.INDEX_TYPE.NOTUNIQUE, "fEmbeddedMapWithoutLinkedType by value");
      fail();
    } catch (IndexException e) {
      assertTrue(
          e.getMessage()
              .contains(
                  "Linked type was not provided. You should provide linked type for embedded"
                      + " collections that are going to be indexed."));
    }
  }

  public void createParentPropertyIndex() {
    oSuperClass.createIndex(
        "ClassIndexTestParentPropertyNine",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null,
        Map.of("ignoreNullValues", true), new String[]{"fNine"});

    assertEquals(
        oSuperClass.getClassIndex(session, "ClassIndexTestParentPropertyNine").getName(),
        "ClassIndexTestParentPropertyNine");
  }

  private static boolean containsIndex(
      final Collection<? extends Index> classIndexes, final String indexName) {
    for (final var index : classIndexes) {
      if (index.getName().equals(indexName)) {
        return true;
      }
    }
    return false;
  }

  @Test
  public void testDropProperty() throws Exception {
    oClass.createProperty("fFive", PropertyType.INTEGER);

    oClass.dropProperty("fFive");

    assertNull(oClass.getProperty("fFive"));
  }
}
