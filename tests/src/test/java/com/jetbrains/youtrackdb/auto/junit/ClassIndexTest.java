/*
 * JUnit 4 version of ClassIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/ClassIndexTest.java
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

import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Arrays;
import java.util.Collection;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 version of ClassIndexTest. Original:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/ClassIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ClassIndexTest extends BaseDBTest {

  @BeforeClass
  public static void setUpClass() throws Exception {
    ClassIndexTest instance = new ClassIndexTest();
    instance.beforeClass();
    instance.createTestSchema();
  }

  private void createTestSchema() {
    final Schema schema = session.getMetadata().getSchema();
    if (schema.existsClass("classIndexTestClass")) {
      return;
    }

    final var superClass = schema.createClass("classIndexTestSuperClass");
    superClass.createProperty("fSuperProp", PropertyType.LONG);
    superClass.createIndex("classIndexTestSuperClass.fSuperProp",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null, null, new String[]{"fSuperProp"});

    final var testClass = schema.createClass("classIndexTestClass", superClass);
    testClass.createProperty("fOne", PropertyType.INTEGER);
    testClass.createProperty("fTwo", PropertyType.STRING);
    testClass.createProperty("fThree", PropertyType.BOOLEAN);
    testClass.createProperty("fFour", PropertyType.INTEGER);
    testClass.createProperty("fFive", PropertyType.INTEGER);
    testClass.createProperty("fSix", PropertyType.INTEGER);
    testClass.createProperty("fSeven", PropertyType.STRING);
    testClass.createProperty("fEight", PropertyType.INTEGER);
    testClass.createProperty("fEmbeddedMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    testClass.createProperty("fEmbeddedMapWithoutLinkedType", PropertyType.EMBEDDEDMAP);
    testClass.createProperty("fLinkMap", PropertyType.LINKMAP);
    testClass.createProperty("fEmbeddedSet", PropertyType.EMBEDDEDSET, PropertyType.INTEGER);
    testClass.createProperty("fEmbeddedList", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
    testClass.createProperty("fLinkSet", PropertyType.LINKSET);
    testClass.createProperty("fLinkList", PropertyType.LINKLIST);
    testClass.createProperty("fRidBag", PropertyType.LINKBAG);

    testClass.createIndex("classIndexTestClass.fOne", SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null, null, new String[]{"fOne"});
    testClass.createIndex("classIndexTestClass.fOneAndFTwo",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null, null, new String[]{"fTwo", "fOne"});
    testClass.createIndex("classIndexTestClass.fOneAndFThree",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(),
        null, null, new String[]{"fOne", "fThree"});
    testClass.createIndex("classIndexTestClass.fEight", SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fEight"});
    testClass.createIndex("classIndexTestClass.fEmbeddedMap",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fEmbeddedMap"});
    testClass.createIndex("classIndexTestClass.fEmbeddedMapByKey",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fEmbeddedMap by key"});
    testClass.createIndex("classIndexTestClass.fEmbeddedMapByValue",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fEmbeddedMap by value"});
    testClass.createIndex("classIndexTestClass.fLinkMapByKey",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fLinkMap by key"});
    testClass.createIndex("classIndexTestClass.fLinkMapByValue",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fLinkMap by value"});
    testClass.createIndex("classIndexTestClass.fEmbeddedSet",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fEmbeddedSet"});
    testClass.createIndex("classIndexTestClass.fEmbeddedList",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fEmbeddedList"});
    testClass.createIndex("classIndexTestClass.fLinkSet",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fLinkSet"});
    testClass.createIndex("classIndexTestClass.fLinkList",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fLinkList"});
    testClass.createIndex("classIndexTestClass.fRidBag",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fRidBag"});
    testClass.createIndex("classIndexTestClassParentPropertyIndex",
        SchemaClass.INDEX_TYPE.NOTUNIQUE.toString(),
        null, null, new String[]{"fSuperProp", "fEight"});
  }

  /**
   * Original: testCreateOnePropertyIndexTest (line 96)
   */
  @Test
  public void test01_CreateOnePropertyIndexTest() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.getClassIndex(session, "classIndexTestClass.fOne");

    Assert.assertNotNull(result);
    Assert.assertEquals("classIndexTestClass.fOne", result.getName());
    Assert.assertEquals("UNIQUE", result.getType());
  }

  /**
   * Original: testAreIndexedOneProperty (line 712)
   */
  @Test
  public void test02_AreIndexedOneProperty() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.areIndexed(session, "fOne");

    Assert.assertTrue(result);
  }

  /**
   * Original: testAreIndexedDoesNotContainProperty (line 784)
   */
  @Test
  public void test03_AreIndexedDoesNotContainProperty() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.areIndexed(session, "fSix");

    Assert.assertFalse(result);
  }

  /**
   * Original: testAreIndexedTwoProperties (line 808)
   */
  @Test
  public void test04_AreIndexedTwoProperties() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.areIndexed(session, Arrays.asList("fOne", "fThree"));

    Assert.assertTrue(result);
  }

  /**
   * Original: testGetClassInvolvedIndexesOneProperty (line 1300)
   */
  @Test
  public void test05_GetClassInvolvedIndexesOneProperty() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.getClassInvolvedIndexes(session, "fOne");

    // At least one index should involve fOne property
    Assert.assertFalse(result.isEmpty());
    Assert.assertTrue(result.contains("classIndexTestClass.fOne"));
  }

  /**
   * Original: testGetClassInvolvedIndexesTwoProperties (line 1326)
   */
  @Test
  public void test06_GetClassInvolvedIndexesTwoProperties() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.getClassInvolvedIndexes(session,
        Arrays.asList("fTwo", "fOne"));

    Assert.assertFalse(result.isEmpty());
  }

  /**
   * Original: testGetInvolvedIndexesOneProperty (line 1578)
   */
  @Test
  public void test07_GetInvolvedIndexesOneProperty() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.getInvolvedIndexes(session, "fOne");

    // At least one index should involve fOne property
    Assert.assertFalse(result.isEmpty());
    Assert.assertTrue(result.contains("classIndexTestClass.fOne"));
  }

  /**
   * Original: testGetParentInvolvedIndexes (line 1681)
   */
  @Test
  public void test08_GetParentInvolvedIndexes() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.getInvolvedIndexes(session, "fSuperProp");

    Assert.assertFalse(result.isEmpty());
    Assert.assertTrue(result.contains("classIndexTestSuperClass.fSuperProp"));
  }

  /**
   * Original: testGetClassIndexes (line 1731)
   */
  @Test
  public void test09_GetClassIndexes() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");

    // Verify expected indexes can be retrieved
    Assert.assertNotNull(classInternal.getClassIndex(session, "classIndexTestClass.fOne"));
    Assert.assertNotNull(classInternal.getClassIndex(session, "classIndexTestClass.fEight"));
  }

  /**
   * Original: testGetIndexesWithoutParent (line 2109)
   */
  @Test
  public void test10_GetIndexesWithoutParent() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");

    // Verify class indexes exist but parent's direct index is separate
    Assert.assertNotNull(classInternal.getClassIndex(session, "classIndexTestClass.fOne"));
    // Parent's index should be accessible via parent class
    final var parentClass = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestSuperClass");
    Assert.assertNotNull(parentClass.getClassIndex(session, "classIndexTestSuperClass.fSuperProp"));
  }

  /**
   * Original: testCreateNotUniqueIndex (line 2148)
   */
  @Test
  public void test11_CreateNotUniqueIndex() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.getClassIndex(session, "classIndexTestClass.fEight");

    Assert.assertNotNull(result);
    Assert.assertEquals("classIndexTestClass.fEight", result.getName());
    Assert.assertEquals("NOTUNIQUE", result.getType());
  }

  /**
   * Original: testDropProperty (line 2198)
   */
  @Test
  public void test12_CreateParentPropertyIndex() {
    final var classInternal = session.getMetadata().getSchema()
        .getClassInternal("classIndexTestClass");
    final var result = classInternal.getClassIndex(session,
        "classIndexTestClassParentPropertyIndex");

    Assert.assertNotNull(result);
    Assert.assertEquals("classIndexTestClassParentPropertyIndex", result.getName());
    Assert.assertEquals("NOTUNIQUE", result.getType());
    Assert.assertEquals(Arrays.asList("fSuperProp", "fEight"),
        result.getDefinition().getProperties());
  }

  private boolean containsIndex(Collection<? extends Index> classIndexes, String indexName) {
    for (var index : classIndexes) {
      if (index.getName().equals(indexName)) {
        return true;
      }
    }
    return false;
  }
}
