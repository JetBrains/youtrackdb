package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class IndexManagerTest extends BaseDBTest {

  private static final String CLASS_NAME = "classForIndexManagerTest";

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    graph.autoExecuteInTx(
        g -> g.createSchemaClass(CLASS_NAME,
            __.createSchemaProperty("fOne", PropertyType.INTEGER),
            __.createSchemaProperty("fTwo", PropertyType.STRING),
            __.createSchemaProperty("fThree", PropertyType.BOOLEAN),
            __.createSchemaProperty("fFour", PropertyType.INTEGER),

            __.createSchemaProperty("fSix", PropertyType.INTEGER),
            __.createSchemaProperty("fSeven", PropertyType.INTEGER)
        )
    );
  }

  @Test
  public void testCreateOnePropertyIndexTest() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(CLASS_NAME)
            .createClassIndex("propertyone", YTDBSchemaIndex.IndexType.UNIQUE, "fOne")
    );

    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertEquals(
        schema.getClass(CLASS_NAME)
            .getClassIndex("propertyone")
            .getName(),
        "propertyone");
  }

  @Test
  public void createCompositeIndexTest() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(CLASS_NAME)
            .createClassIndex("compositeone", YTDBSchemaIndex.IndexType.NOT_UNIQUE, "fOne", "fTwo").
            createClassIndex("compositetwo", YTDBSchemaIndex.IndexType.NOT_UNIQUE, "fTwo", "fOne",
                "fThree")
    );

    assertEquals(
        session
            .getMetadata().getFastImmutableSchemaSnapshot().getClass(CLASS_NAME)
            .getClassIndex("compositeone")
            .getName(),
        "compositeone");
    assertEquals(
        session
            .getMetadata().getFastImmutableSchemaSnapshot().getClass(CLASS_NAME)
            .getClassIndex("compositetwo")
            .getName(),
        "compositetwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedOneProperty() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedDoesNotContainProperty() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fSix");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedTwoProperties() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fTwo", "fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedThreeProperties() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).areIndexed("fTwo", "fOne", "fThree");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedThreePropertiesBrokenFiledNameCase() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).areIndexed("fTwo", "fOne", "fThree");

    assertTrue(result);
  }


  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedPropertiesNotFirst() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fTwo", "fTree");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedPropertiesMoreThanNeeded() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result = schema.getClass(CLASS_NAME).areIndexed("fTwo", "fOne", "fThee", "fFour");
    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedOnePropertyArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedDoesNotContainPropertyArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fSix");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedTwoPropertiesArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fTwo", "fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedThreePropertiesArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fTwo", "fOne", "fThree");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedPropertiesNotFirstArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).areIndexed("fTwo", "fTree");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testAreIndexedPropertiesMoreThanNeededArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result = schema.getClass(CLASS_NAME).areIndexed("fTwo", "fOne", "fThee",
        "fFour");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesOnePropertyArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result = schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fOne");

    assertEquals(result.size(), 3);

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesTwoPropertiesArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne");
    assertEquals(result.size(), 2);

    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesThreePropertiesArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne", "fThree");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "compositetwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesNotInvolvedPropertiesArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesPropertiesMorThanNeededArrayParams() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne",
        "fThee", "fFour");
    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetInvolvedIndexesPropertiesMorThanNeeded() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne", "fThee", "fFour");
    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesNotExistingClass() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fOne");

    assertTrue(result.isEmpty());
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesOneProperty() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fOne");

    assertEquals(result.size(), 3);

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesOnePropertyBrokenClassNameCase() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fOne");

    assertEquals(result.size(), 3);

    assertTrue(containsIndex(result, "propertyone"));
    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesTwoProperties() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne");
    assertEquals(result.size(), 2);

    assertTrue(containsIndex(result, "compositeone"));
    assertTrue(containsIndex(result, "compositetwo"));
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesThreeProperties() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne", "fThree");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "compositetwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesThreePropertiesBrokenFiledNameTest() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne", "fThree");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "compositetwo");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesNotInvolvedProperties() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassInvolvedIndexesPropertiesMorThanNeeded() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result =
        schema.getClass(CLASS_NAME).getClassInvolvedIndexes("fTwo", "fOne", "fThee", "fFour");
    assertEquals(result.size(), 0);
  }

  @Test
  public void testGetClassInvolvedIndexesWithNullValues() {
    var className = "GetClassInvolvedIndexesWithNullValues";

    graph.autoExecuteInTx(g -> g.createSchemaClass(className,
                __.createSchemaProperty("one", PropertyType.STRING),
                __.createSchemaProperty("two", PropertyType.STRING),
                __.createSchemaProperty("three", PropertyType.STRING)
            ).createClassIndex(className + "_indexOne_notunique", YTDBSchemaIndex.IndexType.NOT_UNIQUE,
                "one").
            createClassIndex(className + "_indexOneTwo_notunique", YTDBSchemaIndex.IndexType.NOT_UNIQUE,
                "one", "two").
            createClassIndex(className + "_indexOneTwoThree_notunique",
                YTDBSchemaIndex.IndexType.NOT_UNIQUE, "one", "two", "three")
    );

    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var result = schema.getClass(className).getClassInvolvedIndexes("one");
    assertEquals(result.size(), 3);

    result = schema.getClass(className).getClassInvolvedIndexes("one", "two");
    assertEquals(result.size(), 2);

    result =
        schema.getClass(className).getClassInvolvedIndexes("one", "two", "three");
    assertEquals(result.size(), 1);

    result = schema.getClass(className).getClassInvolvedIndexes("two");
    assertEquals(result.size(), 0);

    result =
        schema.getClass(className).getClassInvolvedIndexes("two", "one", "three");
    assertEquals(result.size(), 1);
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassIndexes() {
    final var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var indexes = schema.getClass(CLASS_NAME).getClassIndexes();
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

    assertEquals(indexes.size(), 3);

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  @Test
  public void testDropIndex() throws Exception {
    graph.autoExecuteInTx(g ->
        g.schemaClass(CLASS_NAME)
            .createClassIndex("anotherproperty", YTDBSchemaIndex.IndexType.UNIQUE, "fOne")
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertNotNull(schema.getIndex("anotherproperty"));
    assertNotNull(schema.getClass(CLASS_NAME).getClassIndex("anotherproperty"));

    graph.autoExecuteInTx(g ->
        g.schemaIndex("anotherproperty").drop()
    );

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertNull(schema.getIndex("anotherproperty"));
    assertNull(schema.getClass(CLASS_NAME).getClassIndex("anotherproperty"));
  }

  @Test
  public void testDropAllClassIndexes() {
    graph.autoExecuteInTx(g ->
        g.createSchemaClass("indexManagerTestClassTwo")
            .createSchemaProperty("fOne", PropertyType.INTEGER)
            .createPropertyIndex("twoclassproperty", YTDBSchemaIndex.IndexType.UNIQUE)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertFalse(schema.getClass("indexManagerTestClassTwo").getClassIndexes().isEmpty());

    graph.autoExecuteInTx(g -> g.schemaIndex("twoclassproperty").drop());

    schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    assertTrue(schema.getClass("indexManagerTestClassTwo").getClassIndexes().isEmpty());
  }

  @Test(dependsOnMethods = "testDropAllClassIndexes")
  public void testDropNonExistingClassIndex() {
    graph.autoExecuteInTx(g -> g.schemaIndex("twoclassproperty").drop());
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassIndex() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result = schema.getClass(CLASS_NAME).getClassIndex("propertyone");
    assertNotNull(result);
    assertEquals(result.getName(), "propertyone");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassIndexBrokenClassNameCase() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result = schema.getClass(CLASS_NAME).getClassIndex("propertyone");
    assertNotNull(result);
    assertEquals(result.getName(), "propertyone");
  }

  @Test(
      dependsOnMethods = {
          "createCompositeIndexTest",
          "testCreateOnePropertyIndexTest"
      })
  public void testGetClassIndexWrongIndexName() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result = schema.getClass(CLASS_NAME).getClassIndex("propertyonetwo");
    assertNull(result);
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
}
