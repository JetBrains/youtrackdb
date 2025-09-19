package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.index.CompositeIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.IndexException;
import com.jetbrains.youtrackdb.internal.core.index.PropertyIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyLinkBagIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyListIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.PropertyMapIndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import java.util.Arrays;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class SQLCreateIndexTest extends BaseDBTest {
  private static final PropertyTypeInternal EXPECTED_PROP1_TYPE = PropertyTypeInternal.DOUBLE;
  private static final PropertyTypeInternal EXPECTED_PROP2_TYPE = PropertyTypeInternal.INTEGER;

  @Override
  @BeforeClass
  public void beforeClass() throws Exception {
    super.beforeClass();

    final Schema schema = session.getMetadata().getSlowMutableSchema();
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
            .getSharedContext()
            .getIndexManager()
            .getIndex("sqlCreateIndexTestClass.prop1");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties().get(0), "prop1");
    Assert.assertEquals(indexDefinition.getTypes()[0], EXPECTED_PROP1_TYPE);
    Assert.assertEquals(index.getType(), "UNIQUE");
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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(), new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE});
    Assert.assertEquals(index.getType(), "UNIQUE");
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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.STRING});
    Assert.assertEquals(index.getType(), "UNIQUE");
    Assert.assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.KEY);
  }

  @Test
  public void testOldStileCreateEmbeddedMapIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop3 UNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop3");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.STRING});
    Assert.assertEquals(index.getType(), "UNIQUE");
    Assert.assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.KEY);
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
    } catch (CommandSQLParsingException e) {
    }
    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

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
    } catch (CommandSQLParsingException e) {

    }
    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

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
    } catch (CommandSQLParsingException e) {

    }
    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWrongSpecifierIndex");

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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapByKeyIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.STRING});
    Assert.assertEquals(index.getType(), "UNIQUE");
    Assert.assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.KEY);
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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapByValueIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyMapIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop3"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER});
    Assert.assertEquals(index.getType(), "UNIQUE");
    Assert.assertEquals(
        ((PropertyMapIndexDefinition) indexDefinition).getIndexBy(),
        PropertyMapIndexDefinition.INDEX_BY.VALUE);
  }

  @Test
  public void testCreateEmbeddedListIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexEmbeddedListIndex ON sqlCreateIndexTestClass (prop5)"
                + " NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedListIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop5"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER});
    Assert.assertEquals(index.getType(), "NOTUNIQUE");
  }

  public void testCreateRidBagIndex() throws Exception {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexRidBagIndex ON sqlCreateIndexTestClass (prop9) NOTUNIQUE")
        .close();

    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexRidBagIndex");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop9"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.LINK});
    Assert.assertEquals(index.getType(), "NOTUNIQUE");
  }

  public void testCreateOldStileEmbeddedListIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop5 NOTUNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop5");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyListIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop5"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER});
    Assert.assertEquals(index.getType(), "NOTUNIQUE");
  }

  public void testCreateOldStileRidBagIndex() throws Exception {
    session.execute("CREATE INDEX sqlCreateIndexTestClass.prop9 NOTUNIQUE").close();

    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop9");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyLinkBagIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop9"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.LINK});
    Assert.assertEquals(index.getType(), "NOTUNIQUE");
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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedListWithoutLinkedTypeIndex");

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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexEmbeddedMapWithoutLinkedTypeIndex");

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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex2");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(), new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE});
    Assert.assertEquals(index.getType(), "UNIQUE");
  }

  @Test
  public void testCreateCompositeIndexWithWrongTypes() throws Exception {
    final var query =
        "CREATE INDEX sqlCreateIndexCompositeIndex3 ON sqlCreateIndexTestClass (prop1,"
            + " prop2) UNIQUE "
            + EXPECTED_PROP1_TYPE.getPublicPropertyType()
            + ", "
            + EXPECTED_PROP1_TYPE.getPublicPropertyType();

    try {
      session.command(query);
      Assert.fail();
    } catch (Exception e) {
//      Assert.assertTrue(
//          e.getMessage()
//              .contains(
//                  "Error on execution of command: sql.CREATE INDEX sqlCreateIndexCompositeIndex3"
//                      + " ON"));
//
      Throwable cause = e;
      while (cause.getCause() != null) {
        cause = cause.getCause();
      }

      Assert.assertEquals(cause.getClass(), IllegalArgumentException.class);
    }
    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex3");

    Assert.assertNull(index, "Index created while wrong query was executed");
  }

  public void testCompositeIndexWithMetadata() {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexCompositeIndexWithMetadata ON sqlCreateIndexTestClass"
                + " (prop1, prop2) UNIQUE metadata {v1:23, v2:\"val2\"}")
        .close();

    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndexWithMetadata");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(), new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE});
    Assert.assertEquals(index.getType(), "UNIQUE");

    var metadata = index.getMetadata();

    Assert.assertEquals(metadata.get("v1"), 23);
    Assert.assertEquals(metadata.get("v2"), "val2");
  }

  public void testOldIndexWithMetadata() {
    session
        .execute(
            "CREATE INDEX sqlCreateIndexTestClass.prop8 NOTUNIQUE  metadata {v1:23, v2:\"val2\"}")
        .close();

    final var index =
        session
            .getMetadata()
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexTestClass.prop8");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof PropertyIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), List.of("prop8"));
    Assert.assertEquals(indexDefinition.getTypes(), new PropertyTypeInternal[]{PropertyTypeInternal.INTEGER});
    Assert.assertEquals(index.getType(), "NOTUNIQUE");

    var metadata = index.getMetadata();

    Assert.assertEquals(metadata.get("v1"), 23);
    Assert.assertEquals(metadata.get("v2"), "val2");
  }

  public void testCreateCompositeIndexWithTypesAndMetadata() throws Exception {
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
            .getSlowMutableSchema()
            .getClassInternal("sqlCreateIndexTestClass")
            .getClassIndex(session, "sqlCreateIndexCompositeIndex2WithConfig");

    Assert.assertNotNull(index);

    final var indexDefinition = index.getDefinition();

    Assert.assertTrue(indexDefinition instanceof CompositeIndexDefinition);
    Assert.assertEquals(indexDefinition.getProperties(), Arrays.asList("prop1", "prop2"));
    Assert.assertEquals(
        indexDefinition.getTypes(), new PropertyTypeInternal[]{EXPECTED_PROP1_TYPE, EXPECTED_PROP2_TYPE});
    Assert.assertEquals(index.getType(), "UNIQUE");

    var metadata = index.getMetadata();
    Assert.assertEquals(metadata.get("v1"), 23);
    Assert.assertEquals(metadata.get("v2"), "val2");
  }
}
