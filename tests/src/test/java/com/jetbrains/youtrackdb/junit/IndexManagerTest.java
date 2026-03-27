package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for the index manager operations including creation, querying, and dropping of indexes.
 */
public class IndexManagerTest extends BaseDBJUnit5Test {
  private static final String CLASS_NAME = "classForIndexManagerTest";

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    final Schema schema = session.getMetadata().getSchema();

    final var oClass = schema.createClass(CLASS_NAME);

    oClass.createProperty("fOne", PropertyType.INTEGER);
    oClass.createProperty("fTwo", PropertyType.STRING);
    oClass.createProperty("fThree", PropertyType.BOOLEAN);
    oClass.createProperty("fFour", PropertyType.INTEGER);

    oClass.createProperty("fSix", PropertyType.STRING);
    oClass.createProperty("fSeven", PropertyType.STRING);
  }

  @Test
  @Order(1)
  void testCreateOnePropertyIndexTest() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.createIndex(
            session,
            "propertyone",
            SchemaClass.INDEX_TYPE.UNIQUE.toString(),
            new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER),
            session.getSchema().getClass(CLASS_NAME).getCollectionIds(),
            null,
            null);

    assertEquals("propertyone", result.getName());

    indexManager.reload(session);
    assertEquals(
        result.getName(),
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, CLASS_NAME, "propertyone")
            .getName());
  }

  @Test
  @Order(2)
  void createCompositeIndexTestWithoutListener() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.createIndex(
            session,
            "compositeone",
            SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
            new CompositeIndexDefinition(
                CLASS_NAME,
                Arrays.asList(
                    new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER),
                    new PropertyIndexDefinition(CLASS_NAME, "fTwo", PropertyTypeInternal.STRING))),
            session.getSchema().getClass(CLASS_NAME).getCollectionIds(),
            null,
            null);

    assertEquals("compositeone", result.getName());

    assertEquals(
        result.getName(),
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, CLASS_NAME, "compositeone")
            .getName());
  }

  @Test
  @Order(3)
  void createCompositeIndexTestWithListener() {
    final var atomicInteger = new AtomicInteger(0);
    final var progressListener =
        new ProgressListener() {
          @Override
          public void onBegin(final Object iTask, final long iTotal, Object metadata) {
            atomicInteger.incrementAndGet();
          }

          @Override
          public boolean onProgress(final Object iTask, final long iCounter,
              final float iPercent) {
            return true;
          }

          @Override
          public void onCompletition(DatabaseSessionEmbedded session, final Object iTask,
              final boolean iSucceed) {
            atomicInteger.incrementAndGet();
          }
        };

    final var indexManager = session.getSharedContext().getIndexManager();

    session.executeInTx(transaction -> {
      transaction.newEntity(CLASS_NAME);
    });

    final var result =
        indexManager.createIndex(
            session,
            "compositetwo",
            SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
            new CompositeIndexDefinition(
                CLASS_NAME,
                Arrays.asList(
                    new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER),
                    new PropertyIndexDefinition(CLASS_NAME, "fTwo", PropertyTypeInternal.STRING),
                    new PropertyIndexDefinition(CLASS_NAME, "fThree",
                        PropertyTypeInternal.BOOLEAN))),
            session.getSchema().getClass(CLASS_NAME).getCollectionIds(),
            progressListener,
            null);

    assertEquals("compositetwo", result.getName());
    assertEquals(2, atomicInteger.get());

    assertEquals(
        result.getName(),
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, CLASS_NAME, "compositetwo")
            .getName());
  }

  @Test
  @Order(4)
  void testAreIndexedOneProperty() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, List.of("fOne"));

    assertTrue(result);
  }

  @Test
  @Order(5)
  void testAreIndexedDoesNotContainProperty() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, List.of("fSix"));

    assertFalse(result);
  }

  @Test
  @Order(6)
  void testAreIndexedTwoProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME,
        Arrays.asList("fTwo", "fOne"));

    assertTrue(result);
  }

  @Test
  @Order(7)
  void testAreIndexedThreeProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.areIndexed(session, CLASS_NAME,
            Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  @Test
  @Order(8)
  void testAreIndexedThreePropertiesBrokenFiledNameCase() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.areIndexed(session, CLASS_NAME,
            Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  @Test
  @Order(9)
  void testAreIndexedThreePropertiesBrokenClassNameCase() {
    // With case-sensitive class names, wrong-case lookups should not find indexes.
    final var indexManager = session.getSharedContext().getIndexManager();

    // Guard: correct case must be indexed (ensures test setup is valid)
    assertTrue(indexManager.areIndexed(session, CLASS_NAME,
        Arrays.asList("fTwo", "fOne", "fThree")));

    final var result =
        indexManager.areIndexed(
            session, "ClaSSForIndeXManagerTeST",
            Arrays.asList("fTwo", "fOne", "fThree"));

    assertFalse(result);
  }

  @Test
  @Order(10)
  void testAreIndexedPropertiesNotFirst() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME,
        Arrays.asList("fTwo", "fTree"));

    assertFalse(result);
  }

  @Test
  @Order(11)
  void testAreIndexedPropertiesMoreThanNeeded() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.areIndexed(session, CLASS_NAME,
            Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertFalse(result);
  }

  @Test
  @Order(12)
  void testAreIndexedOnePropertyArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fOne");

    assertTrue(result);
  }

  @Test
  @Order(13)
  void testAreIndexedDoesNotContainPropertyArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fSix");

    assertFalse(result);
  }

  @Test
  @Order(14)
  void testAreIndexedTwoPropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fOne");

    assertTrue(result);
  }

  @Test
  @Order(15)
  void testAreIndexedThreePropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fOne", "fThree");

    assertTrue(result);
  }

  @Test
  @Order(16)
  void testAreIndexedPropertiesNotFirstArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fTree");

    assertFalse(result);
  }

  @Test
  @Order(17)
  void testAreIndexedPropertiesMoreThanNeededArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fOne", "fThee",
        "fFour");

    assertFalse(result);
  }

  @Test
  @Order(18)
  void testGetClassInvolvedIndexesOnePropertyArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fOne");

    assertEquals(3, result.size());

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test
  @Order(19)
  void testGetClassInvolvedIndexesTwoPropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fTwo", "fOne");
    assertEquals(2, result.size());

    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test
  @Order(20)
  void testGetClassInvolvedIndexesThreePropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fTwo", "fOne", "fThree");

    assertEquals(1, result.size());
    assertEquals("compositetwo", result.iterator().next().getName());
  }

  @Test
  @Order(21)
  void testGetClassInvolvedIndexesNotInvolvedPropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fTwo", "fFour");

    assertEquals(0, result.size());
  }

  @Test
  @Order(22)
  void testGetClassInvolvedIndexesPropertiesMorThanNeededArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, "fTwo", "fOne", "fThee", "fFour");

    assertEquals(0, result.size());
  }

  @Test
  @Order(23)
  void testGetInvolvedIndexesPropertiesMorThanNeeded() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(0, result.size());
  }

  @Test
  @Order(24)
  void testGetClassInvolvedIndexesNotExistingClass() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, "testlass", List.of("fOne"));

    assertTrue(result.isEmpty());
  }

  @Test
  @Order(25)
  void testGetClassInvolvedIndexesOneProperty() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, List.of("fOne"));

    assertEquals(3, result.size());

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test
  @Order(26)
  void testGetClassInvolvedIndexesOnePropertyBrokenClassNameCase() {
    // With case-sensitive class names, wrong-case lookups should return no indexes.
    final var indexManager = session.getSharedContext().getIndexManager();

    // Guard: correct case must return indexes (ensures test setup is valid)
    assertFalse(
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, List.of("fOne")).isEmpty());

    final var result =
        indexManager.getClassInvolvedIndexes(session, "ClaSSforindeXmanagerTEST",
            List.of("fOne"));

    assertTrue(result.isEmpty());
  }

  @Test
  @Order(27)
  void testGetClassInvolvedIndexesTwoProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME,
            Arrays.asList("fTwo", "fOne"));
    assertEquals(2, result.size());

    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test
  @Order(28)
  void testGetClassInvolvedIndexesThreeProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(1, result.size());
    assertEquals("compositetwo", result.iterator().next().getName());
  }

  @Test
  @Order(29)
  void testGetClassInvolvedIndexesThreePropertiesBrokenFiledNameTest() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(1, result.size());
    assertEquals("compositetwo", result.iterator().next().getName());
  }

  @Test
  @Order(30)
  void testGetClassInvolvedIndexesNotInvolvedProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME,
            Arrays.asList("fTwo", "fFour"));

    assertEquals(0, result.size());
  }

  @Test
  @Order(31)
  void testGetClassInvolvedIndexesPropertiesMorThanNeeded() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(0, result.size());
  }

  @Test
  @Order(32)
  void testGetClassInvolvedIndexesWithNullValues() {
    var className = "GetClassInvolvedIndexesWithNullValues";
    final var indexManager = session.getSharedContext().getIndexManager();
    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass(className);

    oClass.createProperty("one", PropertyType.STRING);
    oClass.createProperty("two", PropertyType.STRING);
    oClass.createProperty("three", PropertyType.STRING);

    indexManager.createIndex(
        session,
        className + "_indexOne_notunique",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        new PropertyIndexDefinition(className, "one", PropertyTypeInternal.STRING),
        oClass.getCollectionIds(),
        null,
        null);

    indexManager.createIndex(
        session,
        className + "_indexOneTwo_notunique",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        new CompositeIndexDefinition(
            className,
            Arrays.asList(
                new PropertyIndexDefinition(className, "one", PropertyTypeInternal.STRING),
                new PropertyIndexDefinition(className, "two", PropertyTypeInternal.STRING))),
        oClass.getCollectionIds(),
        null,
        null);

    indexManager.createIndex(
        session,
        className + "_indexOneTwoThree_notunique",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        new CompositeIndexDefinition(
            className,
            Arrays.asList(
                new PropertyIndexDefinition(className, "one", PropertyTypeInternal.STRING),
                new PropertyIndexDefinition(className, "two", PropertyTypeInternal.STRING),
                new PropertyIndexDefinition(className, "three", PropertyTypeInternal.STRING))),
        oClass.getCollectionIds(),
        null,
        null);

    var result = indexManager.getClassInvolvedIndexes(session, className, List.of("one"));
    assertEquals(3, result.size());

    result = indexManager.getClassInvolvedIndexes(session, className,
        Arrays.asList("one", "two"));
    assertEquals(2, result.size());

    result =
        indexManager.getClassInvolvedIndexes(
            session, className, Arrays.asList("one", "two", "three"));
    assertEquals(1, result.size());

    result = indexManager.getClassInvolvedIndexes(session, className, List.of("two"));
    assertEquals(0, result.size());

    result =
        indexManager.getClassInvolvedIndexes(
            session, className, Arrays.asList("two", "one", "three"));
    assertEquals(1, result.size());
  }

  @Test
  @Order(33)
  void testGetClassIndexes() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var indexes = indexManager.getClassIndexes(session, CLASS_NAME);
    final Set<IndexDefinition> expectedIndexDefinitions = new HashSet<IndexDefinition>();

    final var compositeIndexOne = new CompositeIndexDefinition(CLASS_NAME);

    compositeIndexOne.addIndex(
        new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexOne.addIndex(
        new PropertyIndexDefinition(CLASS_NAME, "fTwo", PropertyTypeInternal.STRING));
    compositeIndexOne.setNullValuesIgnored(false);
    expectedIndexDefinitions.add(compositeIndexOne);

    final var compositeIndexTwo = new CompositeIndexDefinition(CLASS_NAME);

    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(CLASS_NAME, "fTwo", PropertyTypeInternal.STRING));
    compositeIndexTwo.addIndex(
        new PropertyIndexDefinition(CLASS_NAME, "fThree", PropertyTypeInternal.BOOLEAN));
    compositeIndexTwo.setNullValuesIgnored(false);
    expectedIndexDefinitions.add(compositeIndexTwo);

    final var propertyIndex =
        new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER);
    propertyIndex.setNullValuesIgnored(false);
    expectedIndexDefinitions.add(propertyIndex);

    assertEquals(3, indexes.size());

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  @Test
  @Order(34)
  void testGetClassIndexesBrokenClassNameCase() {
    // With case-sensitive class names, wrong-case lookups should return no indexes.
    final var indexManager = session.getSharedContext().getIndexManager();

    // Guard: correct case must return indexes (ensures test setup is valid)
    assertFalse(indexManager.getClassIndexes(session, CLASS_NAME).isEmpty());

    final var indexes = indexManager.getClassIndexes(session, "ClassforindeXMaNAgerTeST");

    assertTrue(indexes.isEmpty());
  }

  @Test
  @Order(35)
  void testDropIndex() throws Exception {
    final var indexManager = session.getSharedContext().getIndexManager();

    indexManager.createIndex(
        session,
        "anotherproperty",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER),
        session.getSchema().getClass(CLASS_NAME).getCollectionIds(),
        null,
        null);

    assertNotNull(indexManager.getIndex("anotherproperty"));
    assertNotNull(indexManager.getClassIndex(session, CLASS_NAME, "anotherproperty"));

    indexManager.dropIndex(session, "anotherproperty");

    assertNull(indexManager.getIndex("anotherproperty"));
    assertNull(indexManager.getClassIndex(session, CLASS_NAME, "anotherproperty"));
  }

  @Test
  @Order(36)
  void testDropAllClassIndexes() {
    final var oClass =
        session.getMetadata().getSchema().createClass("indexManagerTestClassTwo");
    oClass.createProperty("fOne", PropertyType.INTEGER);

    final var indexManager = session.getSharedContext().getIndexManager();

    indexManager.createIndex(
        session,
        "twoclassproperty",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        new PropertyIndexDefinition("indexManagerTestClassTwo", "fOne",
            PropertyTypeInternal.INTEGER),
        session.getSchema().getClass("indexManagerTestClassTwo").getCollectionIds(),
        null,
        null);

    assertFalse(indexManager.getClassIndexes(session, "indexManagerTestClassTwo").isEmpty());

    indexManager.dropIndex(session, "twoclassproperty");

    assertTrue(indexManager.getClassIndexes(session, "indexManagerTestClassTwo").isEmpty());
  }

  @Test
  @Order(37)
  void testDropNonExistingClassIndex() {
    final var indexManager = session.getSharedContext().getIndexManager();

    indexManager.dropIndex(session, "twoclassproperty");
  }

  @Test
  @Order(38)
  void testGetClassIndex() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassIndex(session, CLASS_NAME, "propertyone");
    assertNotNull(result);
    assertEquals("propertyone", result.getName());
  }

  @Test
  @Order(39)
  void testGetClassIndexBrokenClassNameCase() {
    // With case-sensitive class names, wrong-case lookups should return null.
    final var indexManager = session.getSharedContext().getIndexManager();

    // Guard: correct case must find the index (ensures test setup is valid)
    assertNotNull(indexManager.getClassIndex(session, CLASS_NAME, "propertyone"));

    final var result =
        indexManager.getClassIndex(session, "ClaSSforindeXManagerTeST", "propertyone");
    assertNull(result);
  }

  @Test
  @Order(40)
  void testGetClassIndexWrongIndexName() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassIndex(session, CLASS_NAME, "propertyonetwo");
    assertNull(result);
  }

  @Test
  @Order(41)
  void testGetClassIndexWrongClassName() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassIndex(session, "testClassTT", "propertyone");
    assertNull(result);
  }

  private boolean containsIndex(
      final Collection<? extends Index> classIndexes, final String indexName) {
    for (final var index : classIndexes) {
      if (index.getName().equals(indexName)) {
        return true;
      }
    }
    return false;
  }
}
