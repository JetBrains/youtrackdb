package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

/**
 * Tests for schema property indexes: creation, querying, composite RID indexing, and drop.
 */
public class SchemaPropertyIndexTest extends BaseDBJUnit5Test {

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();
    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.createClass("PropertyIndexTestClass");
    oClass.createProperty("prop0", PropertyType.LINK);
    oClass.createProperty("prop1", PropertyType.STRING);
    oClass.createProperty("prop2", PropertyType.INTEGER);
    oClass.createProperty("prop3", PropertyType.BOOLEAN);
    oClass.createProperty("prop4", PropertyType.INTEGER);
    oClass.createProperty("prop5", PropertyType.STRING);
  }

  @Override
  @AfterAll
  void afterAll() throws Exception {
    if (session.isClosed()) {
      session = createSessionInstance();
    }
    session.begin();
    session.execute("delete from PropertyIndexTestClass");
    session.commit();
    session.execute("drop class PropertyIndexTestClass");
    super.afterAll();
  }

  @Test
  @Order(1)
  public void testCreateUniqueIndex() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    final var propOne = oClass.getProperty("prop1");
    propOne.createIndex(SchemaClass.INDEX_TYPE.UNIQUE,
        Map.of("ignoreNullValues", true));
    final Collection<Index> indexes =
        oClass.getInvolvedIndexesInternal(session, "prop1");
    IndexDefinition indexDefinition = null;
    for (final var index : indexes) {
      if (index.getName().equals("PropertyIndexTestClass.prop1")) {
        indexDefinition = index.getDefinition();
        break;
      }
    }
    assertNotNull(indexDefinition);

    assertEquals(1, indexDefinition.getParamCount());
    assertEquals(1, indexDefinition.getProperties().size());
    assertTrue(indexDefinition.getProperties().contains("prop1"));
    assertEquals(1, indexDefinition.getTypes().length);
    assertEquals(PropertyTypeInternal.STRING, indexDefinition.getTypes()[0]);
  }

  // Must run before createAdditionalSchemas which adds indexes involving prop3
  @Test
  @Order(2)
  public void testIsIndexedNonIndexedField() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propThree = oClass.getPropertyInternal("prop3");
    assertTrue(propThree.getAllIndexes().isEmpty());
  }

  @Test
  @Order(3)
  public void createAdditionalSchemas() {
    final Schema schema = session.getMetadata().getSchema();
    final var oClass = schema.getClass("PropertyIndexTestClass");
    oClass.createIndex("propOne0",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop0", "prop1"});
    oClass.createIndex("propOne1",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop1", "prop2"});
    oClass.createIndex("propOne2",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop1", "prop3"});
    oClass.createIndex("propOne3",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop2", "prop3"});
    oClass.createIndex("propOne4",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop2", "prop1"});
  }

  @Test
  @Order(4)
  public void testGetIndexes() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    oClass.getProperty("prop1");
    var indexes = oClass.getInvolvedIndexesInternal(session, "prop1");

    assertEquals(1, indexes.size());
    assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
  }

  @Test
  @Order(5)
  public void testGetAllIndexes() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propOne = oClass.getPropertyInternal("prop1");
    final var indexes = propOne.getAllIndexesInternal();

    assertEquals(5, indexes.size());
    assertNotNull(containsIndex(indexes, "PropertyIndexTestClass.prop1"));
    assertNotNull(containsIndex(indexes, "propOne0"));
    assertNotNull(containsIndex(indexes, "propOne1"));
    assertNotNull(containsIndex(indexes, "propOne2"));
    assertNotNull(containsIndex(indexes, "propOne4"));
  }

  @Test
  @Order(6)
  public void testIsIndexedIndexedField() {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    var propOne = oClass.getPropertyInternal("prop1");
    assertFalse(propOne.getAllIndexes().isEmpty());
  }

  @Test
  @Order(7)
  public void testIndexingCompositeRIDAndOthers() throws Exception {
    session.begin();
    var prev0 = session.getSharedContext().getIndexManager()
        .getIndex("propOne0").size(session);
    var prev1 = session.getSharedContext().getIndexManager()
        .getIndex("propOne1").size(session);
    session.rollback();

    session.begin();
    var doc = ((EntityImpl) session.newEntity("PropertyIndexTestClass"))
        .properties("prop1", "testComposite3");
    ((EntityImpl) session.newEntity("PropertyIndexTestClass"))
        .properties("prop0", doc, "prop1", "testComposite1");
    ((EntityImpl) session.newEntity("PropertyIndexTestClass"))
        .properties("prop0", doc);
    session.commit();

    session.begin();

    assertEquals(prev0 + 1,
        session.getSharedContext().getIndexManager()
            .getIndex("propOne0").size(session));
    assertEquals(prev1,
        session.getSharedContext().getIndexManager()
            .getIndex("propOne1").size(session));
    session.rollback();
  }

  //   → @Order(8)
  @Test
  @Order(8)
  public void testIndexingCompositeRIDAndOthersInTx() throws Exception {
    session.begin();
    var prev0 = session.getSharedContext().getIndexManager()
        .getIndex("propOne0").size(session);
    var prev1 = session.getSharedContext().getIndexManager()
        .getIndex("propOne1").size(session);
    var doc = ((EntityImpl) session.newEntity("PropertyIndexTestClass"))
        .properties("prop1", "testComposite34");
    ((EntityImpl) session.newEntity("PropertyIndexTestClass"))
        .properties("prop0", doc, "prop1", "testComposite33");
    ((EntityImpl) session.newEntity("PropertyIndexTestClass"))
        .properties("prop0", doc);
    session.commit();

    session.begin();

    assertEquals(prev0 + 1,
        session.getSharedContext().getIndexManager()
            .getIndex("propOne0").size(session));
    assertEquals(prev1,
        session.getSharedContext().getIndexManager()
            .getIndex("propOne1").size(session));
    session.rollback();
  }

  @Test
  @Order(9)
  public void testDropIndexes() throws Exception {
    var schema = session.getMetadata().getSchema();
    var oClass = schema.getClassInternal("PropertyIndexTestClass");
    oClass.createIndex("PropertyIndexFirstIndex",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop4"});
    oClass.createIndex("PropertyIndexSecondIndex",
        SchemaClass.INDEX_TYPE.UNIQUE.toString(), null,
        Map.of("ignoreNullValues", true),
        new String[] {"prop4"});
    var indexes = oClass.getInvolvedIndexes(session, "prop4");
    for (var index : indexes) {
      session.getSharedContext().getIndexManager().dropIndex(session, index);
    }
    assertNull(session.getSharedContext().getIndexManager()
        .getIndex("PropertyIndexFirstIndex"));
    assertNull(session.getSharedContext().getIndexManager()
        .getIndex("PropertyIndexSecondIndex"));
  }

  private static Index containsIndex(final Collection<Index> indexes,
      final String indexName) {
    for (final var index : indexes) {
      if (index.getName().equals(indexName)) {
        return index;
      }
    }
    return null;
  }
}
