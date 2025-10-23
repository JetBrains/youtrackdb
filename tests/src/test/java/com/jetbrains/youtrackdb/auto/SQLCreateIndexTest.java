package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.gremlin.__;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyListIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import java.util.Arrays;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLCreateIndexTest extends BaseDBTest {

  private static final PropertyType EXPECTED_PROP1_TYPE = PropertyType.DOUBLE;
  private static final PropertyType EXPECTED_PROP2_TYPE = PropertyType.INTEGER;

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    graph.autoExecuteInTx(g ->
        g.createSchemaClass("sqlCreateIndexTestClass",
            __.createSchemaProperty("prop1", EXPECTED_PROP1_TYPE),
            __.createSchemaProperty("prop2", EXPECTED_PROP2_TYPE),
            __.createSchemaProperty("prop3", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER),
            __.createSchemaProperty("prop5", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER),
            __.createSchemaProperty("prop6", PropertyType.EMBEDDEDLIST),
            __.createSchemaProperty("prop7", PropertyType.EMBEDDEDMAP),
            __.createSchemaProperty("prop8", PropertyType.INTEGER),
            __.createSchemaProperty("prop9", PropertyType.LINKBAG)
        )
    );
  }

  @Override
  @AfterClass
  public void afterClass() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }

    session.begin();
    session.execute("delete from sqlCreateIndexTestClass").close();
    session.commit();
    session.execute("drop class sqlCreateIndexTestClass").close();

    super.afterClass();
  }

  @Test
  public void testOldSyntax() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop1 UNIQUE").close();

    final var index =
        session
            .getMetadata().getFastImmutableSchemaSnapshot()
            .getIndex("sqlCreateIndexTestClass.prop1");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties().getFirst(), "prop1");
    Assert.assertEquals(indexDefinition.getTypes()[0],
        PropertyTypeInternal.convertFromPublicType(EXPECTED_PROP1_TYPE));
    Assert.assertEquals(index.getType(), IndexType.UNIQUE);
  }

  @Test
  public void testCreateCompositeIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexCompositeIndex ON sqlCreateIndexTestClass (prop1, prop2)"
                + " UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexCompositeIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(),
        new PropertyTypeInternal[]{
            PropertyTypeInternal.convertFromPublicType(EXPECTED_PROP1_TYPE),
            PropertyTypeInternal.convertFromPublicType(EXPECTED_PROP2_TYPE)
        });
    Assert.assertEquals(index.getType(), IndexType.UNIQUE);
  }

  @Test
  public void testCreateEmbeddedMapIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapIndex ON sqlCreateIndexTestClass (prop3) UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedMapIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.STRING});
    Assert.assertEquals(index.getType(), IndexType.UNIQUE);
    Assert.assertEquals(
        indexDefinition.getIndexBy(),
        IndexBy.BY_VALUE);
  }

  @Test
  public void testOldStileCreateEmbeddedMapIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop3 UNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexTestClass.prop3");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.STRING});
    Assert.assertEquals(index.getType(), IndexType.UNIQUE);
    Assert.assertEquals(
        indexDefinition.getIndexBy(),
        IndexBy.BY_VALUE);
  }

  @Test
  public void testCreateEmbeddedMapWrongSpecifierIndexOne() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
                  + " (prop3 by ttt) UNIQUE")
          .close();
      Assert.fail();
    } catch (CommandSQLParsingException ignored) {
    }

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    Assert.assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  public void testCreateEmbeddedMapWrongSpecifierIndexTwo() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
                  + " (prop3 b value) UNIQUE")
          .close();
      Assert.fail();
    } catch (CommandSQLParsingException ignored) {

    }
    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    Assert.assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  public void testCreateEmbeddedMapWrongSpecifierIndexThree() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWrongSpecifierIndex ON sqlCreateIndexTestClass"
                  + " (prop3 by value t) UNIQUE")
          .close();
      Assert.fail();
    } catch (CommandSQLParsingException ignored) {
    }

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

    Assert.assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  public void testCreateEmbeddedMapByKeyIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapByKeyIndex ON sqlCreateIndexTestClass (prop3 by"
                + " key) UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedMapByKeyIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.STRING});
    Assert.assertEquals(index.getType(), IndexType.UNIQUE);
    Assert.assertEquals(
        indexDefinition.getIndexBy(),
        IndexBy.BY_KEY);
  }

  @Test
  public void testCreateEmbeddedMapByValueIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedMapByValueIndex ON sqlCreateIndexTestClass (prop3"
                + " by value) UNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedMapByValueIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER});
    Assert.assertEquals(index.getType(), IndexType.UNIQUE);
    Assert.assertEquals(
        indexDefinition.getIndexBy(),
        IndexBy.BY_VALUE);
  }

  @Test
  public void testCreateEmbeddedListIndex() {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedListIndex ON sqlCreateIndexTestClass (prop5)"
                + " NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedListIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop5"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER});
    Assert.assertEquals(index.getType(), IndexType.NOT_UNIQUE);
  }

  public void testCreateRidBagIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexRidBagIndex ON sqlCreateIndexTestClass (prop9) NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexRidBagIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop9"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.LINK});
    Assert.assertEquals(index.getType(), IndexType.NOT_UNIQUE);
  }

  public void testCreateOldStileEmbeddedListIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop5 NOTUNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexTestClass.prop5");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop5"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER});
    Assert.assertEquals(index.getType(), IndexType.NOT_UNIQUE);
  }

  public void testCreateOldStileRidBagIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop9 NOTUNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexTestClass.prop9");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop9"));
    Assert.assertEquals(indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.LINK});
    Assert.assertEquals(index.getType(), IndexType.NOT_UNIQUE);
  }

  @Test
  public void testCreateEmbeddedListWithoutLinkedTypeIndex() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedListWithoutLinkedTypeIndex ON"
                  + " sqlCreateIndexTestClass (prop6) UNIQUE")
          .close();
      Assert.fail();
    } catch (IndexException e) {
      Assert.assertTrue(
          e.getMessage()
              .contains(
                  "Linked type was not provided. You should provide linked type for embedded"
                      + " collections that are going to be indexed."));
    }
    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedListWithoutLinkedTypeIndex");

    Assert.assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  public void testCreateEmbeddedMapWithoutLinkedTypeIndex() throws Exception {
    try {
      session
          .execute(
              "CREATE INDEX sqlCreateIndexEmbeddedMapWithoutLinkedTypeIndex ON"
                  + " sqlCreateIndexTestClass (prop7 by value) UNIQUE")
          .close();
      Assert.fail();
    } catch (IndexException e) {
      Assert.assertTrue(
          e.getMessage()
              .contains(
                  "Linked type was not provided. You should provide linked type for embedded"
                      + " collections that are going to be indexed."));
    }
    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexEmbeddedMapWithoutLinkedTypeIndex");

    Assert.assertNull(index, "Index created while wrong query was executed");
  }

  @Test
  public void testCreateCompositeIndexWithTypes() throws Exception {
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
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexCompositeIndex2");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(),
        new PropertyTypeInternal[]{PropertyTypeInternal.convertFromPublicType(EXPECTED_PROP1_TYPE),
            PropertyTypeInternal.convertFromPublicType(EXPECTED_PROP2_TYPE)
        });

    Assert.assertEquals(index.getType(), IndexType.UNIQUE);
  }

  @Test
  public void testCreateCompositeIndexWithWrongTypes() throws Exception {
    final var query =
        "CREATE INDEX sqlCreateIndexCompositeIndex3 ON sqlCreateIndexTestClass (prop1,"
            + " prop2) UNIQUE "
            + EXPECTED_PROP1_TYPE
            + ", "
            + EXPECTED_PROP1_TYPE;

    try {
      session.command(query);
      Assert.fail();
    } catch (Exception e) {
      Throwable cause = e;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }

      Assert.assertEquals(cause.getClass(), IllegalArgumentException.class);
    }
    final var index =
        session
            .getMetadata()
            .getFastImmutableSchemaSnapshot()
            .getClass("sqlCreateIndexTestClass")
            .getClassIndex("sqlCreateIndexCompositeIndex3");

    Assert.assertNull(index, "Index created while wrong query was executed");
  }
}
