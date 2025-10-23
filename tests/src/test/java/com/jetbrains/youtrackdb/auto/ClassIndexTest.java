package com.jetbrains.youtrackdb.auto;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexBy;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyListIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class ClassIndexTest extends BaseDBTest {

  private static final String testClass = "ClassIndexTestClass";
  private static final String testSuperClass = "ClassIndexTestSuperClass";

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass(testClass,
            __.createSchemaProperty("fOne", PropertyType.INTEGER),
            __.createSchemaProperty("fTwo", PropertyType.STRING),
            __.createSchemaProperty("fThree", PropertyType.BOOLEAN),
            __.createSchemaProperty("fFour", PropertyType.INTEGER),

            __.createSchemaProperty("fSix", PropertyType.STRING),
            __.createSchemaProperty("fSeven", PropertyType.STRING),

            __.createSchemaProperty("fEight", PropertyType.INTEGER),
            __.createSchemaProperty("fTen", PropertyType.INTEGER),
            __.createSchemaProperty("fEleven", PropertyType.INTEGER),
            __.createSchemaProperty("fTwelve", PropertyType.INTEGER),
            __.createSchemaProperty("fThirteen", PropertyType.INTEGER),
            __.createSchemaProperty("fFourteen", PropertyType.INTEGER),
            __.createSchemaProperty("fFifteen", PropertyType.INTEGER),

            __.createSchemaProperty("fEmbeddedMap", PropertyType.EMBEDDEDMAP,
                PropertyType.INTEGER),
            __.createSchemaProperty("fEmbeddedMapWithoutLinkedType", PropertyType.EMBEDDEDMAP),
            __.createSchemaProperty("fLinkMap", PropertyType.LINKMAP),

            __.createSchemaProperty("fLinkList", PropertyType.LINKLIST),
            __.createSchemaProperty("fEmbeddedList", PropertyType.EMBEDDEDLIST,
                PropertyType.INTEGER),

            __.createSchemaProperty("fEmbeddedSet", PropertyType.EMBEDDEDSET,
                PropertyType.INTEGER),
            __.createSchemaProperty("fLinkSet", PropertyType.LINKSET),
            __.createSchemaProperty("fRidBag", PropertyType.LINKBAG),

            __.createSchemaProperty("fNine", PropertyType.INTEGER)
        ).addParentClass(testSuperClass)
    );
  }

  @Test
  public void testCreateOnePropertyIndexTest() {
    graph.autoExecuteInTx(g -> g.schemaClass(testClass).createClassIndex(
        "ClassIndexTestPropertyOne", YTDBSchemaIndex.IndexType.UNIQUE,
        true, "fOne")
    );

    assertEquals(
        session
            .getMetadata().getFastImmutableSchemaSnapshot()
            .getClass("ClassIndexTestClass").getClassIndex("ClassIndexTestPropertyOne")
            .getName(),
        "ClassIndexTestPropertyOne");
  }

  @Test
  public void testCreateOnePropertyIndexInvalidName() {
    try {
      graph.autoExecuteInTx(g -> g.schemaClass(testClass).
          createClassIndex("ClassIndex:TestPropertyOne", YTDBSchemaIndex.IndexType.UNIQUE,
              true, "fOne"));
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
  public void testCreateCompositeIndex() {
    graph.autoExecuteInTx(g -> g.schemaIndex(testClass).createClassIndex(
        "ClassIndexTestCompositeOne",
        YTDBSchemaIndex.IndexType.UNIQUE, true,
        "fOne", "fTwo"));

    assertEquals(
        session
            .getMetadata().getFastImmutableSchemaSnapshot()
            .getClass(testClass).getClassIndex("ClassIndexTestCompositeOne")
            .getName(),
        "ClassIndexTestCompositeOne");

    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass)
            .createClassIndex("ClassIndexTestCompositeTwo", YTDBSchemaIndex.IndexType.UNIQUE, true,
                "fOne", "fTwo", "fThree")
    );

    assertEquals(
        session
            .getMetadata().getFastImmutableSchemaSnapshot()
            .getClass(testClass)
            .getClassIndex("ClassIndexTestCompositeTwo")
            .getName(),
        "ClassIndexTestCompositeTwo");
  }

  @Test
  public void testCreateOnePropertyEmbeddedMapIndex() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createClassIndex(
            "ClassIndexTestPropertyEmbeddedMap",
            YTDBSchemaIndex.IndexType.UNIQUE,
            true, "fEmbeddedMap"
        )
    );

    assertEquals(
        session.getMetadata().getFastImmutableSchemaSnapshot()
            .getClass(testClass).getClassIndex("ClassIndexTestPropertyEmbeddedMap").getName(),
        "ClassIndexTestPropertyEmbeddedMap");

    final var indexDefinition = session.getMetadata().getFastImmutableSchemaSnapshot()
        .getIndex("ClassIndexTestPropertyEmbeddedMap")
        .getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fEmbeddedMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(indexDefinition.getIndexBy(),
        List.of(SchemaIndexEntity.IndexBy.BY_VALUE));
  }

  @Test
  public void testCreateCompositeEmbeddedMapIndex() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createClassIndex(
            "ClassIndexTestCompositeEmbeddedMap",
            YTDBSchemaIndex.IndexType.UNIQUE, true,
            "fFifteen", "fEmbeddedMap")
    );

    assertEquals(
        session
            .getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
            .getClassIndex("ClassIndexTestCompositeEmbeddedMap")
            .getName(),
        "ClassIndexTestCompositeEmbeddedMap");

    final var indexDefinition = session.getMetadata().getFastImmutableSchemaSnapshot()
        .getIndex("ClassIndexTestCompositeEmbeddedMap")
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
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createClassIndex(
            "ClassIndexTestCompositeEmbeddedMapByKey",
            YTDBSchemaIndex.IndexType.UNIQUE,
            new String[]{"fEight", "fEmbeddedMap"},
            new IndexBy[]{IndexBy.BY_VALUE,
                IndexBy.BY_KEY},
            true
        )
    );

    assertEquals(
        session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
            .getClassIndex("ClassIndexTestCompositeEmbeddedMapByKey").getName(),
        "ClassIndexTestCompositeEmbeddedMapByKey");
    assertEquals(
        session
            .getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
            .getClassIndex("ClassIndexTestCompositeEmbeddedMapByKey")
            .getName(),
        "ClassIndexTestCompositeEmbeddedMapByKey");

    final var indexDefinition = session.getMetadata().getFastImmutableSchemaSnapshot().getIndex(
        "ClassIndexTestCompositeEmbeddedMapByKey").getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fEight", "fEmbeddedMap"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.STRING});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeEmbeddedMapByValueIndex() {
    graph.autoExecuteInTx(g -> g.schemaClass(testClass).createClassIndex(
        "ClassIndexTestCompositeEmbeddedMapByValue",
        YTDBSchemaIndex.IndexType.UNIQUE,
        true,
        "fTen", "fEmbeddedMap")
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex("ClassIndexTestCompositeEmbeddedMapByValue");
    assertEquals(
        index.getName(),
        "ClassIndexTestCompositeEmbeddedMapByValue");

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fTen", "fEmbeddedMap"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeLinkMapByValueIndex() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createClassIndex(
            "ClassIndexTestCompositeLinkMapByValue",
            YTDBSchemaIndex.IndexType.UNIQUE,
            true,
            "fEleven", "fLinkMap")
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex("ClassIndexTestCompositeLinkMapByValue");
    assertEquals(
        index.getName(), "ClassIndexTestCompositeLinkMapByValue");

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fEleven", "fLinkMap"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeEmbeddedSetIndex() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createClassIndex(
            "ClassIndexTestCompositeEmbeddedSet",
            YTDBSchemaIndex.IndexType.UNIQUE,
            true,
            "fTwelve", "fEmbeddedSet")
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex("ClassIndexTestCompositeEmbeddedSet");
    assertEquals(index.getName(),
        "ClassIndexTestCompositeEmbeddedSet");

    final var indexDefinition = index.getDefinition();

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

    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createClassIndex(
            "ClassIndexTestCompositeLinkSet",
            YTDBSchemaIndex.IndexType.NOT_UNIQUE,
            true,
            "fTwelve", "fLinkSet"
        )
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fTwelve", "fLinkSet"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateCompositeEmbeddedListIndex() {
    var indexName = "ClassIndexTestCompositeEmbeddedList";

    graph.autoExecuteInTx(g -> g.schemaClass(testClass).createClassIndex(
            indexName,
            YTDBSchemaIndex.IndexType.NOT_UNIQUE,
            true,
            "fThirteen", "fEmbeddedList"
        )
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();
    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(
        indexDefinition.getProperties().toArray(), new String[]{"fThirteen", "fEmbeddedList"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.INTEGER});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  public void testCreateCompositeLinkListIndex() {
    var indexName = "ClassIndexTestCompositeLinkList";
    graph.autoExecuteInTx(g -> g.schemaClass(testClass).
        createClassIndex(indexName, YTDBSchemaIndex.IndexType.NOT_UNIQUE, true, "fFourteen",
            "fLinkList")
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fFourteen", "fLinkList"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  public void testCreateCompositeRidBagIndex() {
    var indexName = "ClassIndexTestCompositeRidBag";
    graph.autoExecuteInTx(g -> g.schemaClass(testClass)
        .createClassIndex(indexName, YTDBSchemaIndex.IndexType.NOT_UNIQUE, true,
            "fFourteen", "fRidBag"));

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    assertEquals(indexDefinition.getProperties().toArray(), new String[]{"fFourteen", "fRidBag"});

    assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER, PropertyTypeInternal.LINK});
    assertEquals(indexDefinition.getParamCount(), 2);
  }

  @Test
  public void testCreateOnePropertyLinkedMapIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMap";
    graph.autoExecuteInTx(g -> g.schemaClass(testClass)
        .createClassIndex(indexName, YTDBSchemaIndex.IndexType.NOT_UNIQUE, true, "fLinkMap")
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fLinkMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(
        indexDefinition.getIndexBy(),
        SchemaIndexEntity.IndexBy.BY_KEY);
  }

  @Test
  public void testCreateOnePropertyLinkMapByKeyIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMapByKey";
    graph.autoExecuteInTx(g -> g.schemaClass(testClass).createClassIndex(
        indexName, YTDBSchemaIndex.IndexType.NOT_UNIQUE, new String[]{"fLinkMap"},
        new IndexBy[]{IndexBy.BY_KEY}, true)
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fLinkMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(indexDefinition.getIndexBy().getFirst(), SchemaIndexEntity.IndexBy.BY_KEY);
  }

  @Test
  public void testCreateOnePropertyLinkMapByValueIndex() {
    var indexName = "ClassIndexTestPropertyLinkedMapByValue";
    graph.autoExecuteInTx(g -> g.schemaClass(testClass).createClassIndex(
        indexName, YTDBSchemaIndex.IndexType.NOT_UNIQUE, new String[]{"fLinkMap"},
        new IndexBy[]{IndexBy.BY_VALUE}, true
    ));

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fLinkMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.LINK);
    assertEquals(indexDefinition.getIndexBy().getFirst(), SchemaIndexEntity.IndexBy.BY_VALUE);
  }

  @Test
  public void testCreateOnePropertyByKeyEmbeddedMapIndex() {
    var indexName = "ClassIndexTestPropertyByKeyEmbeddedMap";
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createClassIndex(
            indexName, YTDBSchemaIndex.IndexType.UNIQUE, new String[]{"fEmbeddedMap"},
            new IndexBy[]{IndexBy.BY_KEY}, true
        )
    );

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fEmbeddedMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.STRING);
    assertEquals(
        indexDefinition.getIndexBy().getFirst(),
        SchemaIndexEntity.IndexBy.BY_KEY);
  }

  @Test
  public void testCreateOnePropertyByValueEmbeddedMapIndex() {
    var indexName = "ClassIndexTestPropertyByValueEmbeddedMap";
    graph.autoExecuteInTx(g -> g.schemaClass(testClass).createClassIndex(
        indexName,
        YTDBSchemaIndex.IndexType.UNIQUE,
        new String[]{"fEmbeddedMap"},
        new IndexBy[]{IndexBy.BY_VALUE},
        true
    ));

    var index = session.getMetadata().getFastImmutableSchemaSnapshot().getClass(testClass)
        .getClassIndex(indexName);
    assertEquals(index.getName(), indexName);

    final var indexDefinition = index.getDefinition();

    assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    assertEquals(indexDefinition.getProperties().getFirst(), "fEmbeddedMap");
    assertEquals(indexDefinition.getTypes()[0], PropertyTypeInternal.INTEGER);
    assertEquals(indexDefinition.getIndexBy().getFirst(), SchemaIndexEntity.IndexBy.BY_VALUE);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed(List.of("fOne"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
  public void testAreIndexedEightProperty() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed(List.of("fEight"));
    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
  public void testAreIndexedEightPropertyEmbeddedMap() {
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass)
        .areIndexed(Arrays.asList("fEmbeddedMap", "fEight"));
    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed(List.of("fSix"));

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed(Arrays.asList("fTwo", "fOne"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass)
        .areIndexed(Arrays.asList("fTwo", "fOne", "fThree"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed(Arrays.asList("fTwo", "fTree"));

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();

    final var result = schema.getClass(testClass).areIndexed(
        Arrays.asList("fTwo", "fOne", "fThee", "fFour"));
    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed(List.of("fNine"));

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed(List.of("fOne, fNine"));

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fSix");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fTwo", "fOne");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fTwo", "fOne", "fThree");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fTwo", "fTree");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fTwo",
        "fOne", "fThee", "fFour");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fNine");

    assertTrue(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).areIndexed("fOne, fNine");

    assertFalse(result);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes("fOne");

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes("fTwo", "fOne");
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes("fTwo", "fOne",
        "fThree");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes("fTwo", "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes("fTwo", "fOne",
        "fThee",
        "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(testClass).getClassInvolvedIndexes(
            Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes(List.of("fOne"));

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes(
        Arrays.asList("fTwo", "fOne"));
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(testClass).getClassInvolvedIndexes(Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getClassInvolvedIndexes(
        Arrays.asList("fTwo", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result =
        schema.getClass(testClass).getClassInvolvedIndexes(
            Arrays.asList("fTwo", "fOne", "fThee", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes("fOne");

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes("fTwo", "fOne");
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes("fTwo", "fOne", "fThree");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes("fTwo", "fFour");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes("fNine");

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestParentPropertyNine");
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes("fOne", "fNine");

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes(List.of("fOne"));

    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestPropertyOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes(
        Arrays.asList("fTwo", "fOne"));
    assertEquals(result.size(), 1);

    assertTrue(containsIndex(result, "ClassIndexTestCompositeOne"));
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes(
        Arrays.asList("fTwo", "fOne", "fThree"));

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestCompositeTwo");
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes(
        Arrays.asList("fTwo", "fFour"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes(List.of("fNine"));

    assertEquals(result.size(), 1);
    assertEquals(result.iterator().next().getName(), "ClassIndexTestParentPropertyNine");
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var result = schema.getClass(testClass).getInvolvedIndexes(
        Arrays.asList("fOne", "fNine"));

    assertEquals(result.size(), 0);
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var indexes = schema.getClass(testClass).getClassIndexes();

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
            SchemaIndexEntity.IndexBy.BY_KEY));
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
            SchemaIndexEntity.IndexBy.BY_VALUE));
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
            SchemaIndexEntity.IndexBy.BY_VALUE));
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
            SchemaIndexEntity.IndexBy.BY_VALUE));
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
            SchemaIndexEntity.IndexBy.BY_KEY);
    expectedIndexDefinitions.add(propertyMapIndexDefinition);

    final var propertyMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.INTEGER,
            SchemaIndexEntity.IndexBy.BY_VALUE);
    expectedIndexDefinitions.add(propertyMapByValueIndexDefinition);

    final var propertyLinkMapByKeyIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.STRING,
            SchemaIndexEntity.IndexBy.BY_KEY);
    expectedIndexDefinitions.add(propertyLinkMapByKeyIndexDefinition);

    final var propertyLinkMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.LINK,
            SchemaIndexEntity.IndexBy.BY_VALUE);
    expectedIndexDefinitions.add(propertyLinkMapByValueIndexDefinition);

    assertEquals(indexes.size(), 17);

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  @Test(
      dependsOnMethods = {
          "testCreateCompositeIndex",
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
    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    final var indexes = schema.getClass(testClass).getIndexes();
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
            SchemaIndexEntity.IndexBy.BY_KEY));
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
            SchemaIndexEntity.IndexBy.BY_VALUE));
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
            SchemaIndexEntity.IndexBy.BY_VALUE));
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
            SchemaIndexEntity.IndexBy.BY_KEY));
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
            SchemaIndexEntity.IndexBy.BY_KEY);
    expectedIndexDefinitions.add(propertyMapIndexDefinition);

    final var propertyMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fEmbeddedMap",
            PropertyTypeInternal.INTEGER,
            SchemaIndexEntity.IndexBy.BY_VALUE);
    expectedIndexDefinitions.add(propertyMapByValueIndexDefinition);

    final var propertyLinkMapByKeyIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.STRING,
            SchemaIndexEntity.IndexBy.BY_KEY);
    expectedIndexDefinitions.add(propertyLinkMapByKeyIndexDefinition);

    final var propertyLinkMapByValueIndexDefinition =
        new PropertyMapIndexDefinition(
            "ClassIndexTestClass",
            "fLinkMap",
            PropertyTypeInternal.LINK,
            SchemaIndexEntity.IndexBy.BY_VALUE);
    expectedIndexDefinitions.add(propertyLinkMapByValueIndexDefinition);

    assertEquals(indexes.size(), 18);

    for (final var index : indexes) {
      assertTrue(expectedIndexDefinitions.contains(index.getDefinition()));
    }
  }

  @Test
  public void testGetIndexesWithoutParent() {
    var inClass = "ClassIndexInTest";
    var indexName = "fOneIndex";

    graph.autoExecuteInTx(g ->
        g.createSchemaClass(inClass).
            createSchemaProperty("fOne", PropertyType.INTEGER).createPropertyIndex(indexName,
                YTDBSchemaIndex.IndexType.UNIQUE, IndexBy.BY_VALUE, true)
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var index = schema.getClass(inClass).getClassIndex(indexName);

    assertEquals(index.getName(), indexName);

    final var indexes = schema.getClass(inClass).getIndexes();
    final var propertyIndexDefinition =
        new PropertyIndexDefinition("ClassIndexInTest", "fOne", PropertyTypeInternal.INTEGER);

    assertEquals(indexes.size(), 1);

    assertEquals(propertyIndexDefinition, indexes.iterator().next().getDefinition());
  }

  @Test(expectedExceptions = IndexException.class)
  public void testCreateIndexEmptyFields() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass)
            .createClassIndex("ClassIndexTestCompositeEmpty", YTDBSchemaIndex.IndexType.UNIQUE)
    );
  }

  @Test(expectedExceptions = IndexException.class)
  public void testCreateIndexAbsentFields() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass)
            .createClassIndex("ClassIndexTestCompositeAbsent", YTDBSchemaIndex.IndexType.UNIQUE,
                "fFive")
    );
  }

  @Test(dependsOnMethods = "testGetInvolvedIndexesOnePropertyArrayParams")
  public void testCreateNotUniqueIndex() {
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass)
            .createClassIndex("ClassIndexTestNotUniqueIndex", YTDBSchemaIndex.IndexType.UNIQUE,
                "fOne")
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var index = schema.getClass(testClass).getClassIndex("ClassIndexTestNotUniqueIndex");
    assertEquals(index.getName(), "ClassIndexTestNotUniqueIndex");
    assertEquals(index.getType(), IndexType.NOT_UNIQUE);
  }

  @Test
  public void testCreateMapWithoutLinkedType() {
    try {
      graph.autoExecuteInTx(g ->
          g.schemaClass(testClass).createClassIndex("ClassIndexMapWithoutLinkedTypeIndex",
              YTDBSchemaIndex.IndexType.UNIQUE, "fEmbeddedMapWithoutLinkedType")
      );
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
    graph.autoExecuteInTx(g ->
        g.schemaClass(testSuperClass)
            .createClassIndex("ClassIndexTestParentPropertyNine", YTDBSchemaIndex.IndexType.UNIQUE,
                true, "fNine")
    );

    var schema = session.getMetadata().getFastImmutableSchemaSnapshot();
    var index = schema.getClass(testSuperClass).getClassIndex("ClassIndexTestParentPropertyNine");
    assertEquals(index.getName(), "ClassIndexTestParentPropertyNine");
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
    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).createSchemaProperty("fFive", PropertyType.INTEGER)
    );

    graph.autoExecuteInTx(g ->
        g.schemaClass(testClass).schemaClassProperty("fFive").drop()
    );

    assertNull(
        graph.computeInTx(g -> g.schemaClass(testClass).schemaClassProperty("fFive").next()));
  }
}
