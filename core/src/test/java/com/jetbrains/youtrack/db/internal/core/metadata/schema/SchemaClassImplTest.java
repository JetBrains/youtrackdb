package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.Test;

public class SchemaClassImplTest extends BaseMemoryInternalDatabase {

  /**
   * If class was not abstract and we call {@code setAbstract(false)} collections should not be
   * changed.
   */
  @Test
  public void testSetAbstractCollectionNotChanged() throws Exception {
    final Schema oSchema = session.getMetadata().getSchema();

    var oClass = oSchema.createClass("Test1");
    final var oldCollectionId = oClass.getCollectionIds()[0];

    oClass.setAbstract(false);

    assertEquals(oClass.getCollectionIds()[0], oldCollectionId);
  }

  /**
   * If class was abstract and we call {@code setAbstract(false)} a new non default collection
   * should be created.
   */
  @Test
  public void testSetAbstractShouldCreateNewCollections() {
    final Schema oSchema = session.getMetadata().getSchema();

    var oClass = oSchema.createAbstractClass("Test2");

    oClass.setAbstract(false);

    assertNotEquals(-1, oClass.getCollectionIds()[0]);
    assertEquals(oClass.getCollectionIds()[0], session.getCollectionIdByName("Test2"));
  }

  @Test
  public void testCreateNoLinkedClass() {
    final Schema oSchema = session.getMetadata().getSchema();

    var oClass = (SchemaClassInternal) oSchema.createClass("Test21");
    oClass.createProperty("some", PropertyType.LINKLIST, (SchemaClass) null);
    oClass.createProperty("some2", PropertyTypeInternal.LINKLIST, (SchemaClass) null, true);

    assertNotNull(oClass.getProperty("some"));
    assertNotNull(oClass.getProperty("some2"));
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingData() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test3");

    session.executeInTx(
        transaction -> {
          var document = (EntityImpl) session.newEntity("Test3");
          document.setProperty("some", "String");
        });

    oClass.createProperty("some", PropertyType.INTEGER);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkList() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test4");

    session.executeInTx(
        transaction -> {
          var entity = session.newEntity("Test4");
          var list = session.newLinkList();
          list.add(session.newEntity("Test4"));
          entity.setLinkList("some", list);
        });

    oClass.createProperty("some", PropertyType.EMBEDDEDLIST);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkSet() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test5");

    session.executeInTx(
        transaction -> {
          var entity = session.newEntity("Test5");
          var set = session.newLinkSet();
          set.add(session.newEntity("Test5"));
          entity.setLinkSet("somelinkset", set);
        });

    oClass.createProperty("somelinkset", PropertyType.EMBEDDEDSET);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddetSet() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test6");
    oSchema.createAbstractClass("Test69");

    session.executeInTx(
        transaction -> {
          var entity = session.newEntity("Test6");
          var list = session.newEmbeddedSet();
          list.add(session.newEmbeddedEntity("Test69"));
          entity.setEmbeddedSet("someembededset", list);
        });

    oClass.createProperty("someembededset", PropertyType.LINKSET);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddedList() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test7");

    session.executeInTx(
        transaction -> {
          var entity = session.newEntity("Test7");
          var list = session.newEmbeddedList();
          list.add(session.newEntity("Test7"));
          entity.setEmbeddedList("someembeddedlist", list);
        });

    oClass.createProperty("someembeddedlist", PropertyType.LINKLIST);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddedMap() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test8");
    oSchema.createAbstractClass("Test89");

    session.executeInTx(
        transaction -> {
          var entity = session.newEntity("Test8");
          Map<String, EntityImpl> map = session.newEmbeddedMap();
          map.put("test", (EntityImpl) session.newEmbeddedEntity("Test89"));
          entity.setEmbeddedMap("someembededmap", map);
        });

    oClass.createProperty("someembededmap", PropertyType.LINKMAP);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkMap() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test9");
    oSchema.createClass("Test8");

    session.executeInTx(
        transaction -> {
          var entity = session.newEntity("Test9");
          var map = session.newLinkMap();
          map.put("test", session.newEntity("Test8"));
          entity.setLinkMap("somelinkmap", map);
        });

    oClass.createProperty("somelinkmap", PropertyType.EMBEDDEDMAP);
  }

  @Test
  public void testCreatePropertyCastable() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test10");

    var rid =
        session.computeInTx(
            transaction -> {
              var document = (EntityImpl) session.newEntity("Test10");
              // TODO add boolan and byte
              document.setProperty("test1", (short) 1);
              document.setProperty("test2", 1);
              document.setProperty("test3", 4L);
              document.setProperty("test4", 3.0f);
              document.setProperty("test5", 3.0D);
              document.setProperty("test6", 4);
              return document.getIdentity();
            });

    oClass.createProperty("test1", PropertyType.INTEGER);
    oClass.createProperty("test2", PropertyType.LONG);
    oClass.createProperty("test3", PropertyType.DOUBLE);
    oClass.createProperty("test4", PropertyType.DOUBLE);
    oClass.createProperty("test5", PropertyType.DECIMAL);
    oClass.createProperty("test6", PropertyType.FLOAT);

    session.begin();
    EntityImpl doc1 = session.load(rid);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test1"));
    assertTrue(doc1.getProperty("test1") instanceof Integer);
    assertEquals(PropertyType.LONG, doc1.getPropertyType("test2"));
    assertTrue(doc1.getProperty("test2") instanceof Long);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test3"));
    assertTrue(doc1.getProperty("test3") instanceof Double);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test4"));
    assertTrue(doc1.getProperty("test4") instanceof Double);
    assertEquals(PropertyType.DECIMAL, doc1.getPropertyType("test5"));
    assertTrue(doc1.getProperty("test5") instanceof BigDecimal);
    assertEquals(PropertyType.FLOAT, doc1.getPropertyType("test6"));
    assertTrue(doc1.getProperty("test6") instanceof Float);
    session.commit();
  }

  @Test
  public void testCreatePropertyCastableColection() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test11");

    var rid =
        session.computeInTx(
            transaction -> {
              var entity = session.newEntity("Test11");
              entity.newEmbeddedList("test1");
              entity.newLinkList("test2");
              entity.newEmbeddedSet("test3");
              entity.newLinkSet("test4");
              entity.newEmbeddedMap("test5");
              entity.newLinkMap("test6");
              return entity.getIdentity();
            });

    oClass.createProperty("test1", PropertyType.LINKLIST);
    oClass.createProperty("test2", PropertyType.EMBEDDEDLIST);
    oClass.createProperty("test3", PropertyType.LINKSET);
    oClass.createProperty("test4", PropertyType.EMBEDDEDSET);
    oClass.createProperty("test5", PropertyType.LINKMAP);
    oClass.createProperty("test6", PropertyType.EMBEDDEDMAP);

    session.begin();
    EntityImpl doc1 = session.load(rid);
    assertEquals(PropertyType.LINKLIST, doc1.getPropertyType("test1"));
    assertEquals(PropertyType.EMBEDDEDLIST, doc1.getPropertyType("test2"));
    assertEquals(PropertyType.LINKSET, doc1.getPropertyType("test3"));
    assertEquals(PropertyType.EMBEDDEDSET, doc1.getPropertyType("test4"));
    assertEquals(PropertyType.LINKMAP, doc1.getPropertyType("test5"));
    assertEquals(PropertyType.EMBEDDEDMAP, doc1.getPropertyType("test6"));
    session.commit();
  }

  @Test
  public void testCreatePropertyIdKeep() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test12");
    var prop = oClass.createProperty("test2", PropertyType.STRING);
    var id = prop.getId();
    oClass.dropProperty("test2");
    prop = oClass.createProperty("test2", PropertyType.STRING);
    assertEquals(id, prop.getId());
  }

  @Test
  public void testRenameProperty() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test13");
    var prop = oClass.createProperty("test1", PropertyType.STRING);
    var id = prop.getId();
    prop.setName("test2");
    assertNotEquals(id, prop.getId());
  }

  @Test
  public void testChangeTypeProperty() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test14");
    var prop = oClass.createProperty("test1", PropertyType.SHORT);
    var id = prop.getId();
    prop.setType(PropertyType.INTEGER);
    assertNotEquals(id, prop.getId());
  }

  @Test
  public void testRenameBackProperty() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test15");
    var prop = oClass.createProperty("test1", PropertyType.STRING);
    var id = prop.getId();
    prop.setName("test2");
    assertNotEquals(id, prop.getId());
    prop.setName("test1");
    assertEquals(id, prop.getId());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSetUncastableType() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test16");
    var prop = oClass.createProperty("test1", PropertyType.STRING);
    prop.setType(PropertyType.INTEGER);
  }

  @Test
  public void testFindById() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test17");
    var prop = oClass.createProperty("testaaa", PropertyType.STRING);
    var global = oSchema.getGlobalPropertyById(prop.getId());

    assertEquals(prop.getId(), global.getId());
    assertEquals(prop.getName(), global.getName());
    assertEquals(prop.getType(), global.getType());
  }

  @Test
  public void testFindByIdDrop() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test18");
    var prop = oClass.createProperty("testaaa", PropertyType.STRING);
    var id = prop.getId();
    oClass.dropProperty("testaaa");
    var global = oSchema.getGlobalPropertyById(id);

    assertEquals(id, global.getId());
    assertEquals("testaaa", global.getName());
    assertEquals(PropertyType.STRING, global.getType());
  }

  @Test
  public void testChangePropertyTypeCastable() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test19");

    oClass.createProperty("test1", PropertyType.SHORT);
    oClass.createProperty("test2", PropertyType.INTEGER);
    oClass.createProperty("test3", PropertyType.LONG);
    oClass.createProperty("test4", PropertyType.FLOAT);
    oClass.createProperty("test5", PropertyType.DOUBLE);
    oClass.createProperty("test6", PropertyType.INTEGER);

    var rid =
        session.computeInTx(
            transaction -> {
              var document = (EntityImpl) session.newEntity("Test19");
              // TODO add boolean and byte
              document.setProperty("test1", (short) 1);
              document.setProperty("test2", 1);
              document.setProperty("test3", 4L);
              document.setProperty("test4", 3.0f);
              document.setProperty("test5", 3.0D);
              document.setProperty("test6", 4);
              return document.getIdentity();
            });

    oClass.getProperty("test1").setType(PropertyType.INTEGER);
    oClass.getProperty("test2").setType(PropertyType.LONG);
    oClass.getProperty("test3").setType(PropertyType.DOUBLE);
    oClass.getProperty("test4").setType(PropertyType.DOUBLE);
    oClass.getProperty("test5").setType(PropertyType.DECIMAL);
    oClass.getProperty("test6").setType(PropertyType.FLOAT);

    session.begin();
    EntityImpl doc1 = session.load(rid);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test1"));
    assertTrue(doc1.getProperty("test1") instanceof Integer);
    assertEquals(PropertyType.LONG, doc1.getPropertyType("test2"));
    assertTrue(doc1.getProperty("test2") instanceof Long);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test3"));
    assertTrue(doc1.getProperty("test3") instanceof Double);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test4"));
    assertTrue(doc1.getProperty("test4") instanceof Double);
    assertEquals(PropertyType.DECIMAL, doc1.getPropertyType("test5"));
    assertTrue(doc1.getProperty("test5") instanceof BigDecimal);
    assertEquals(PropertyType.FLOAT, doc1.getPropertyType("test6"));
    assertTrue(doc1.getProperty("test6") instanceof Float);
    session.commit();
  }

  @Test
  public void testChangePropertyName() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test20");

    oClass.createProperty("test1", PropertyType.SHORT);
    oClass.createProperty("test2", PropertyType.INTEGER);
    oClass.createProperty("test3", PropertyType.LONG);
    oClass.createProperty("test4", PropertyType.FLOAT);
    oClass.createProperty("test5", PropertyType.DOUBLE);
    oClass.createProperty("test6", PropertyType.INTEGER);

    var rid =
        session.computeInTx(
            transaction -> {
              var document = (EntityImpl) session.newEntity("Test20");
              // TODO add boolan and byte
              document.setProperty("test1", (short) 1);
              document.setProperty("test2", 1);
              document.setProperty("test3", 4L);
              document.setProperty("test4", 3.0f);
              document.setProperty("test5", 3.0D);
              document.setProperty("test6", 4);
              return document.getIdentity();
            });

    oClass.getProperty("test1").setName("test1a");
    oClass.getProperty("test2").setName("test2a");
    oClass.getProperty("test3").setName("test3a");
    oClass.getProperty("test4").setName("test4a");
    oClass.getProperty("test5").setName("test5a");
    oClass.getProperty("test6").setName("test6a");

    session.begin();
    EntityImpl doc1 = session.load(rid);
    assertEquals(PropertyType.SHORT, doc1.getPropertyType("test1a"));
    assertTrue(doc1.getProperty("test1a") instanceof Short);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test2a"));
    assertTrue(doc1.getProperty("test2a") instanceof Integer);
    assertEquals(PropertyType.LONG, doc1.getPropertyType("test3a"));
    assertTrue(doc1.getProperty("test3a") instanceof Long);
    assertEquals(PropertyType.FLOAT, doc1.getPropertyType("test4a"));
    assertTrue(doc1.getProperty("test4a") instanceof Float);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test5a"));
    assertTrue(doc1.getProperty("test5") instanceof Double);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test6a"));
    assertTrue(doc1.getProperty("test6a") instanceof Integer);
    session.commit();
  }

  @Test
  public void testCreatePropertyCastableColectionNoCache() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test11bis");

    var rid =
        session.computeInTx(
            transaction -> {
              final var entity = session.newEntity("Test11bis");
              entity.newEmbeddedList("test1");
              entity.newLinkList("test2");

              entity.newEmbeddedSet("test3");
              entity.newLinkSet("test4");
              entity.newEmbeddedMap("test5");
              entity.newLinkMap("test6");
              return entity.getIdentity();
            });

    oClass.createProperty("test1", PropertyType.LINKLIST);
    oClass.createProperty("test2", PropertyType.EMBEDDEDLIST);
    oClass.createProperty("test3", PropertyType.LINKSET);
    oClass.createProperty("test4", PropertyType.EMBEDDEDSET);
    oClass.createProperty("test5", PropertyType.LINKMAP);
    oClass.createProperty("test6", PropertyType.EMBEDDEDMAP);

    try (var sessionCopy = session.copy()) {
      sessionCopy.executeInTx(transaction -> {
        EntityImpl entity1 = sessionCopy.load(rid);
        assertEquals(PropertyType.LINKLIST, entity1.getPropertyType("test1"));
        assertEquals(PropertyType.EMBEDDEDLIST, entity1.getPropertyType("test2"));
        assertEquals(PropertyType.LINKSET, entity1.getPropertyType("test3"));
        assertEquals(PropertyType.EMBEDDEDSET, entity1.getPropertyType("test4"));
        assertEquals(PropertyType.LINKMAP, entity1.getPropertyType("test5"));
        assertEquals(PropertyType.EMBEDDEDMAP, entity1.getPropertyType("test6"));
      });
    }
  }

  @Test
  public void testClassNameSyntax() {

    final Schema oSchema = session.getMetadata().getSchema();
    assertNotNull(oSchema.createClass("OClassImplTesttestClassNameSyntax"));
    assertNotNull(oSchema.createClass("_OClassImplTesttestClassNameSyntax"));
    assertNotNull(oSchema.createClass("_OClassImplTesttestClassNameSyntax_"));
    assertNotNull(oSchema.createClass("_OClassImplTestte_stClassNameSyntax_"));
    assertNotNull(oSchema.createClass("_OClassImplTesttestClassNameSyntax_1"));
    assertNotNull(oSchema.createClass("_OClassImplTesttestClassNameSyntax_12"));
    assertNotNull(oSchema.createClass("_OClassImplTesttestCla23ssNameSyntax_12"));
    assertNotNull(oSchema.createClass("$OClassImplTesttestCla23ssNameSyntax_12"));
    assertNotNull(oSchema.createClass("OClassImplTesttestC$la23ssNameSyntax_12"));
    assertNotNull(oSchema.createClass("oOClassImplTesttestC$la23ssNameSyntax_12"));
    var validClassNamesSince30 = new String[]{
        "foo bar",
        "12",
        "#12",
        "12AAA",
        ",asdfasdf",
        "adsf,asdf",
        "asdf.sadf",
        ".asdf",
        "asdfaf.",
        "asdf:asdf"
    };
    for (var s : validClassNamesSince30) {
      assertNotNull(oSchema.createClass(s));
    }
  }

  @Test
  public void testAlterCustomAttributeInClass() {
    Schema schema = session.getMetadata().getSchema();
    var oClass = schema.createClass("TestCreateCustomAttributeClass");

    oClass.setCustom("customAttribute", "value1");
    assertEquals("value1", oClass.getCustom("customAttribute"));

    oClass.setCustom("custom.attribute", "value2");
    assertEquals("value2", oClass.getCustom("custom.attribute"));
  }
}
