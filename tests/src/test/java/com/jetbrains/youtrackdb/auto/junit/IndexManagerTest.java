/*
 * JUnit 4 version of IndexManagerTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.common.listener.ProgressListener;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of IndexManagerTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class IndexManagerTest extends BaseDBTest {

  private static final String CLASS_NAME = "classForIndexManagerTest";
  private static IndexManagerTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new IndexManagerTest();
    instance.beforeClass();
  }

  /**
   * Original: beforeClass (line 34) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Override
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSchema();

    final var oClass = schema.createClass(CLASS_NAME);

    oClass.createProperty("fOne", PropertyType.INTEGER);
    oClass.createProperty("fTwo", PropertyType.STRING);
    oClass.createProperty("fThree", PropertyType.BOOLEAN);
    oClass.createProperty("fFour", PropertyType.INTEGER);

    oClass.createProperty("fSix", PropertyType.STRING);
    oClass.createProperty("fSeven", PropertyType.STRING);
  }

  /**
   * Original: testCreateOnePropertyIndexTest (line 51) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test01_CreateOnePropertyIndexTest() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.createIndex(
            session,
            "propertyone",
            SchemaClass.INDEX_TYPE.UNIQUE.toString(),
            new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER),
            new int[]{session.getCollectionIdByName(CLASS_NAME)},
            null,
            null);

    assertEquals("propertyone", result.getName());

    indexManager.reload(session);
    assertEquals(
        session
            .getSharedContext()
            .getIndexManager()
            .getClassIndex(session, CLASS_NAME, "propertyone")
            .getName(),
        result.getName());
  }

  /**
   * Original: createCompositeIndexTestWithoutListener (line 77) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test01a_CreateCompositeIndexTestWithoutListener() {
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
            new int[]{session.getCollectionIdByName(CLASS_NAME)},
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

  /**
   * Original: createCompositeIndexTestWithListener (line 106) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test01b_CreateCompositeIndexTestWithListener() {
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

  /**
   * Original: testAreIndexedOneProperty (line 167) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test02_AreIndexedOneProperty() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, List.of("fOne"));

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedDoesNotContainProperty (line 181) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test03_AreIndexedDoesNotContainProperty() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, List.of("fSix"));

    assertFalse(result);
  }

  /**
   * Original: testAreIndexedTwoProperties (line 195) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test04_AreIndexedTwoProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, Arrays.asList("fTwo", "fOne"));

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedThreeProperties (line 209) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test05_AreIndexedThreeProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.areIndexed(session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedThreePropertiesBrokenFiledNameCase (line 224) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test06_AreIndexedThreePropertiesBrokenFiledNameCase() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.areIndexed(session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedThreePropertiesBrokenClassNameCase (line 239) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test07_AreIndexedThreePropertiesBrokenClassNameCase() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.areIndexed(
            session, "ClaSSForIndeXManagerTeST", Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedPropertiesNotFirst (line 255) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test08_AreIndexedPropertiesNotFirst() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, Arrays.asList("fTwo", "fTree"));

    assertFalse(result);
  }

  /**
   * Original: testAreIndexedPropertiesMoreThanNeeded (line 269) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test09_AreIndexedPropertiesMoreThanNeeded() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.areIndexed(session, CLASS_NAME,
            Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertFalse(result);
  }

  /**
   * Original: testAreIndexedOnePropertyArrayParams (line 285) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test10_AreIndexedOnePropertyArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fOne");

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedDoesNotContainPropertyArrayParams (line 299) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test11_AreIndexedDoesNotContainPropertyArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fSix");

    assertFalse(result);
  }

  /**
   * Original: testAreIndexedTwoPropertiesArrayParams (line 313) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test12_AreIndexedTwoPropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fOne");

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedThreePropertiesArrayParams (line 327) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test13_AreIndexedThreePropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fOne", "fThree");

    assertTrue(result);
  }

  /**
   * Original: testAreIndexedPropertiesNotFirstArrayParams (line 341) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test14_AreIndexedPropertiesNotFirstArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fTree");

    assertFalse(result);
  }

  /**
   * Original: testAreIndexedPropertiesMoreThanNeededArrayParams (line 355) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test15_AreIndexedPropertiesMoreThanNeededArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.areIndexed(session, CLASS_NAME, "fTwo", "fOne", "fThee",
        "fFour");

    assertFalse(result);
  }

  /**
   * Original: testGetClassInvolvedIndexesOnePropertyArrayParams (line 370) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test16_GetClassInvolvedIndexesOnePropertyArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fOne");

    assertEquals(3, result.size());

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  /**
   * Original: testGetClassInvolvedIndexesTwoPropertiesArrayParams (line 388) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test17_GetClassInvolvedIndexesTwoPropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fTwo", "fOne");
    assertEquals(2, result.size());

    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  /**
   * Original: testGetClassInvolvedIndexesThreePropertiesArrayParams (line 405) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test18_GetClassInvolvedIndexesThreePropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fTwo", "fOne", "fThree");

    assertEquals(1, result.size());
    assertEquals("compositetwo", result.iterator().next().getName());
  }

  /**
   * Original: testGetClassInvolvedIndexesNotInvolvedPropertiesArrayParams (line 421) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test19_GetClassInvolvedIndexesNotInvolvedPropertiesArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, "fTwo", "fFour");

    assertEquals(0, result.size());
  }

  /**
   * Original: testGetClassInvolvedIndexesPropertiesMorThanNeededArrayParams (line 436) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test20_GetClassInvolvedIndexesPropertiesMorThanNeededArrayParams() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, "fTwo", "fOne", "fThee", "fFour");

    assertEquals(0, result.size());
  }

  /**
   * Original: testGetInvolvedIndexesPropertiesMorThanNeeded (line 452) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test21_GetInvolvedIndexesPropertiesMorThanNeeded() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(0, result.size());
  }

  /**
   * Original: testGetClassInvolvedIndexesNotExistingClass (line 468) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test22_GetClassInvolvedIndexesNotExistingClass() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, "testlass", List.of("fOne"));

    assertTrue(result.isEmpty());
  }

  /**
   * Original: testGetClassInvolvedIndexesOneProperty (line 483) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test23_GetClassInvolvedIndexesOneProperty() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, List.of("fOne"));

    assertEquals(3, result.size());

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  /**
   * Original: testGetClassInvolvedIndexesOnePropertyBrokenClassNameCase (line 502) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test24_GetClassInvolvedIndexesOnePropertyBrokenClassNameCase() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, "ClaSSforindeXmanagerTEST", List.of("fOne"));

    assertEquals(3, result.size());

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  /**
   * Original: testGetClassInvolvedIndexesTwoProperties (line 521) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test25_GetClassInvolvedIndexesTwoProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, Arrays.asList("fTwo", "fOne"));
    assertEquals(2, result.size());

    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  /**
   * Original: testGetClassInvolvedIndexesThreeProperties (line 538) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test26_GetClassInvolvedIndexesThreeProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(1, result.size());
    assertEquals("compositetwo", result.iterator().next().getName());
  }

  /**
   * Original: testGetClassInvolvedIndexesThreePropertiesBrokenFiledNameTest (line 555) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test27_GetClassInvolvedIndexesThreePropertiesBrokenFiledNameTest() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(1, result.size());
    assertEquals("compositetwo", result.iterator().next().getName());
  }

  /**
   * Original: testGetClassInvolvedIndexesNotInvolvedProperties (line 572) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test28_GetClassInvolvedIndexesNotInvolvedProperties() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(session, CLASS_NAME, Arrays.asList("fTwo", "fFour"));

    assertEquals(0, result.size());
  }

  /**
   * Original: testGetClassInvolvedIndexesPropertiesMorThanNeeded (line 587) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test29_GetClassInvolvedIndexesPropertiesMorThanNeeded() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassInvolvedIndexes(
            session, CLASS_NAME, Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(0, result.size());
  }

  /**
   * Original: testGetClassInvolvedIndexesWithNullValues (line 598) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test30_GetClassInvolvedIndexesWithNullValues() {
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

    result = indexManager.getClassInvolvedIndexes(session, className, Arrays.asList("one", "two"));
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

  /**
   * Original: testGetClassIndexes (line 670) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test31_GetClassIndexes() {
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

  /**
   * Original: testGetClassIndexesBrokenClassNameCase (line 714) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test32_GetClassIndexesBrokenClassNameCase() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var indexes = indexManager.getClassIndexes(session, "ClassforindeXMaNAgerTeST");
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

  /**
   * Original: testDropIndex (line 753) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test33_DropIndex() {
    final var indexManager = session.getSharedContext().getIndexManager();

    indexManager.createIndex(
        session,
        "anotherproperty",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        new PropertyIndexDefinition(CLASS_NAME, "fOne", PropertyTypeInternal.INTEGER),
        new int[]{session.getCollectionIdByName(CLASS_NAME)},
        null,
        null);

    assertNotNull(indexManager.getIndex("anotherproperty"));
    assertNotNull(indexManager.getClassIndex(session, CLASS_NAME, "anotherproperty"));

    indexManager.dropIndex(session, "anotherproperty");

    assertNull(indexManager.getIndex("anotherproperty"));
    assertNull(indexManager.getClassIndex(session, CLASS_NAME, "anotherproperty"));
  }

  /**
   * Original: testDropAllClassIndexes (line 775) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test34_DropAllClassIndexes() {
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
        new int[]{session.getCollectionIdByName("indexManagerTestClassTwo")},
        null,
        null);

    assertFalse(indexManager.getClassIndexes(session, "indexManagerTestClassTwo").isEmpty());

    indexManager.dropIndex(session, "twoclassproperty");

    assertTrue(indexManager.getClassIndexes(session, "indexManagerTestClassTwo").isEmpty());
  }

  /**
   * Original: testDropNonExistingClassIndex (line 800) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test35_DropNonExistingClassIndex() {
    final var indexManager = session.getSharedContext().getIndexManager();

    indexManager.dropIndex(session, "twoclassproperty");
  }

  /**
   * Original: testGetClassIndex (line 812) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test36_GetClassIndex() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassIndex(session, CLASS_NAME, "propertyone");
    assertNotNull(result);
    assertEquals("propertyone", result.getName());
  }

  /**
   * Original: testGetClassIndexBrokenClassNameCase (line 826) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test37_GetClassIndexBrokenClassNameCase() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result =
        indexManager.getClassIndex(session, "ClaSSforindeXManagerTeST", "propertyone");
    assertNotNull(result);
    assertEquals("propertyone", result.getName());
  }

  /**
   * Original: testGetClassIndexWrongIndexName (line 841) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test38_GetClassIndexWrongIndexName() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassIndex(session, CLASS_NAME, "propertyonetwo");
    assertNull(result);
  }

  /**
   * Original: testGetClassIndexWrongClassName (line 854) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
  @Test
  public void test39_GetClassIndexWrongClassName() {
    final var indexManager = session.getSharedContext().getIndexManager();

    final var result = indexManager.getClassIndex(session, "testClassTT", "propertyone");
    assertNull(result);
  }

  /**
   * Original: containsIndex (line 861) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/IndexManagerTest.java
   */
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
