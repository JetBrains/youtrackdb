package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SchemaClassImplTest extends BaseMemoryInternalDatabase {

  /**
   * If class was not abstract and we call {@code setAbstract(false)} clusters should not be
   * changed.
   *
   * @throws Exception
   */
  @Test
  public void testSetAbstractClusterNotChanged() throws Exception {
    final Schema oSchema = session.getMetadata().getSchema();

    var oClass = oSchema.createClass("Test1");
    final var oldClusterId = oClass.getClusterIds(session)[0];

    oClass.setAbstract(session, false);

    assertEquals(oClass.getClusterIds(session)[0], oldClusterId);
  }

  /**
   * If class was abstract and we call {@code setAbstract(false)} a new non default cluster should
   * be created.
   */
  @Test
  public void testSetAbstractShouldCreateNewClusters() {
    final Schema oSchema = session.getMetadata().getSchema();

    var oClass = oSchema.createAbstractClass("Test2");

    oClass.setAbstract(session, false);

    assertNotEquals(-1, oClass.getClusterIds(session)[0]);
    assertEquals(oClass.getClusterIds(session)[0], session.getClusterIdByName("Test2"));
  }

  @Test
  public void testCreateNoLinkedClass() {
    final Schema oSchema = session.getMetadata().getSchema();

    var oClass = (SchemaClassInternal) oSchema.createClass("Test21");
    oClass.createProperty(session, "some", PropertyType.LINKLIST, (SchemaClass) null);
    oClass.createProperty(session, "some2", PropertyType.LINKLIST, (SchemaClass) null, true);

    assertNotNull(oClass.getProperty(session, "some"));
    assertNotNull(oClass.getProperty(session, "some2"));
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingData() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test3");

    session.executeInTx(
        () -> {
          var document = (EntityImpl) session.newEntity("Test3");
          document.field("some", "String");
        });

    oClass.createProperty(session, "some", PropertyType.INTEGER);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkList() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test4");

    session.executeInTx(
        () -> {
          var entity = session.newEntity("Test4");
          var list = session.newLinkList();
          list.add(session.newEntity("Test4"));
          entity.setLinkList("some", list);
        });

    oClass.createProperty(session, "some", PropertyType.EMBEDDEDLIST);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkSet() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test5");

    session.executeInTx(
        () -> {
          var entity = session.newEntity("Test5");
          var set = session.newLinkSet();
          set.add(session.newEntity("Test5"));
          entity.setLinkSet("somelinkset", set);
        });

    oClass.createProperty(session, "somelinkset", PropertyType.EMBEDDEDSET);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddetSet() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test6");
    oSchema.createAbstractClass("Test69");

    session.executeInTx(
        () -> {
          var entity = session.newEntity("Test6");
          var list = session.newEmbeddedSet();
          list.add(session.newEmbeddedEntity("Test69"));
          entity.setEmbeddedSet("someembededset", list);
        });

    oClass.createProperty(session, "someembededset", PropertyType.LINKSET);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddedList() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test7");

    session.executeInTx(
        () -> {
          var entity = session.newEntity("Test7");
          var list = session.newEmbeddedList();
          list.add(session.newEntity("Test7"));
          entity.setEmbeddedList("someembeddedlist", list);
        });

    oClass.createProperty(session, "someembeddedlist", PropertyType.LINKLIST);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddedMap() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test8");
    oSchema.createAbstractClass("Test89");

    session.executeInTx(
        () -> {
          var entity = session.newEntity("Test8");
          Map<String, EntityImpl> map = session.newEmbeddedMap();
          map.put("test", (EntityImpl) session.newEmbeddedEntity("Test89"));
          entity.setEmbeddedMap("someembededmap", map);
        });

    oClass.createProperty(session, "someembededmap", PropertyType.LINKMAP);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkMap() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test9");
    oSchema.createClass("Test8");

    session.executeInTx(
        () -> {
          var entity = session.newEntity("Test9");
          var map = session.newLinkMap();
          map.put("test", session.newEntity("Test8"));
          entity.setLinkMap("somelinkmap", map);
        });

    oClass.createProperty(session, "somelinkmap", PropertyType.EMBEDDEDMAP);
  }

  @Test
  public void testCreatePropertyCastable() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test10");

    var rid =
        session.computeInTx(
            () -> {
              var document = (EntityImpl) session.newEntity("Test10");
              // TODO add boolan and byte
              document.field("test1", (short) 1);
              document.field("test2", 1);
              document.field("test3", 4L);
              document.field("test4", 3.0f);
              document.field("test5", 3.0D);
              document.field("test6", 4);
              return document.getIdentity();
            });

    oClass.createProperty(session, "test1", PropertyType.INTEGER);
    oClass.createProperty(session, "test2", PropertyType.LONG);
    oClass.createProperty(session, "test3", PropertyType.DOUBLE);
    oClass.createProperty(session, "test4", PropertyType.DOUBLE);
    oClass.createProperty(session, "test5", PropertyType.DECIMAL);
    oClass.createProperty(session, "test6", PropertyType.FLOAT);

    session.begin();
    EntityImpl doc1 = session.load(rid);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test1"));
    assertTrue(doc1.field("test1") instanceof Integer);
    assertEquals(PropertyType.LONG, doc1.getPropertyType("test2"));
    assertTrue(doc1.field("test2") instanceof Long);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test3"));
    assertTrue(doc1.field("test3") instanceof Double);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test4"));
    assertTrue(doc1.field("test4") instanceof Double);
    assertEquals(PropertyType.DECIMAL, doc1.getPropertyType("test5"));
    assertTrue(doc1.field("test5") instanceof BigDecimal);
    assertEquals(PropertyType.FLOAT, doc1.getPropertyType("test6"));
    assertTrue(doc1.field("test6") instanceof Float);
    session.commit();
  }

  @Test
  public void testCreatePropertyCastableColection() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test11");

    var rid =
        session.computeInTx(
            () -> {
              var entity = session.newEntity("Test11");
              entity.newEmbeddedList("test1");
              entity.newLinkList("test2");
              entity.newEmbeddedSet("test3");
              entity.newLinkSet("test4");
              entity.newEmbeddedMap("test5");
              entity.newLinkMap("test6");
              return entity.getIdentity();
            });

    oClass.createProperty(session, "test1", PropertyType.LINKLIST);
    oClass.createProperty(session, "test2", PropertyType.EMBEDDEDLIST);
    oClass.createProperty(session, "test3", PropertyType.LINKSET);
    oClass.createProperty(session, "test4", PropertyType.EMBEDDEDSET);
    oClass.createProperty(session, "test5", PropertyType.LINKMAP);
    oClass.createProperty(session, "test6", PropertyType.EMBEDDEDMAP);

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
    var prop = oClass.createProperty(session, "test2", PropertyType.STRING);
    var id = prop.getId();
    oClass.dropProperty(session, "test2");
    prop = oClass.createProperty(session, "test2", PropertyType.STRING);
    assertEquals(id, prop.getId());
  }

  @Test
  public void testRenameProperty() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test13");
    var prop = oClass.createProperty(session, "test1", PropertyType.STRING);
    var id = prop.getId();
    prop.setName(session, "test2");
    assertNotEquals(id, prop.getId());
  }

  @Test
  public void testChangeTypeProperty() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test14");
    var prop = oClass.createProperty(session, "test1", PropertyType.SHORT);
    var id = prop.getId();
    prop.setType(session, PropertyType.INTEGER);
    assertNotEquals(id, prop.getId());
  }

  @Test
  public void testRenameBackProperty() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test15");
    var prop = oClass.createProperty(session, "test1", PropertyType.STRING);
    var id = prop.getId();
    prop.setName(session, "test2");
    assertNotEquals(id, prop.getId());
    prop.setName(session, "test1");
    assertEquals(id, prop.getId());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSetUncastableType() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test16");
    var prop = oClass.createProperty(session, "test1", PropertyType.STRING);
    prop.setType(session, PropertyType.INTEGER);
  }

  @Test
  public void testFindById() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test17");
    var prop = oClass.createProperty(session, "testaaa", PropertyType.STRING);
    var global = oSchema.getGlobalPropertyById(prop.getId());

    assertEquals(prop.getId(), global.getId());
    assertEquals(prop.getName(session), global.getName());
    assertEquals(prop.getType(session), global.getType());
  }

  @Test
  public void testFindByIdDrop() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test18");
    var prop = oClass.createProperty(session, "testaaa", PropertyType.STRING);
    var id = prop.getId();
    oClass.dropProperty(session, "testaaa");
    var global = oSchema.getGlobalPropertyById(id);

    assertEquals(id, global.getId());
    assertEquals("testaaa", global.getName());
    assertEquals(PropertyType.STRING, global.getType());
  }

  @Test
  public void testChangePropertyTypeCastable() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test19");

    oClass.createProperty(session, "test1", PropertyType.SHORT);
    oClass.createProperty(session, "test2", PropertyType.INTEGER);
    oClass.createProperty(session, "test3", PropertyType.LONG);
    oClass.createProperty(session, "test4", PropertyType.FLOAT);
    oClass.createProperty(session, "test5", PropertyType.DOUBLE);
    oClass.createProperty(session, "test6", PropertyType.INTEGER);

    var rid =
        session.computeInTx(
            () -> {
              var document = (EntityImpl) session.newEntity("Test19");
              // TODO add boolean and byte
              document.field("test1", (short) 1);
              document.field("test2", 1);
              document.field("test3", 4L);
              document.field("test4", 3.0f);
              document.field("test5", 3.0D);
              document.field("test6", 4);
              return document.getIdentity();
            });

    oClass.getProperty(session, "test1").setType(session, PropertyType.INTEGER);
    oClass.getProperty(session, "test2").setType(session, PropertyType.LONG);
    oClass.getProperty(session, "test3").setType(session, PropertyType.DOUBLE);
    oClass.getProperty(session, "test4").setType(session, PropertyType.DOUBLE);
    oClass.getProperty(session, "test5").setType(session, PropertyType.DECIMAL);
    oClass.getProperty(session, "test6").setType(session, PropertyType.FLOAT);

    session.begin();
    EntityImpl doc1 = session.load(rid);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test1"));
    assertTrue(doc1.field("test1") instanceof Integer);
    assertEquals(PropertyType.LONG, doc1.getPropertyType("test2"));
    assertTrue(doc1.field("test2") instanceof Long);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test3"));
    assertTrue(doc1.field("test3") instanceof Double);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test4"));
    assertTrue(doc1.field("test4") instanceof Double);
    assertEquals(PropertyType.DECIMAL, doc1.getPropertyType("test5"));
    assertTrue(doc1.field("test5") instanceof BigDecimal);
    assertEquals(PropertyType.FLOAT, doc1.getPropertyType("test6"));
    assertTrue(doc1.field("test6") instanceof Float);
    session.commit();
  }

  @Test
  public void testChangePropertyName() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test20");

    oClass.createProperty(session, "test1", PropertyType.SHORT);
    oClass.createProperty(session, "test2", PropertyType.INTEGER);
    oClass.createProperty(session, "test3", PropertyType.LONG);
    oClass.createProperty(session, "test4", PropertyType.FLOAT);
    oClass.createProperty(session, "test5", PropertyType.DOUBLE);
    oClass.createProperty(session, "test6", PropertyType.INTEGER);

    var rid =
        session.computeInTx(
            () -> {
              var document = (EntityImpl) session.newEntity("Test20");
              // TODO add boolan and byte
              document.field("test1", (short) 1);
              document.field("test2", 1);
              document.field("test3", 4L);
              document.field("test4", 3.0f);
              document.field("test5", 3.0D);
              document.field("test6", 4);
              return document.getIdentity();
            });

    oClass.getProperty(session, "test1").setName(session, "test1a");
    oClass.getProperty(session, "test2").setName(session, "test2a");
    oClass.getProperty(session, "test3").setName(session, "test3a");
    oClass.getProperty(session, "test4").setName(session, "test4a");
    oClass.getProperty(session, "test5").setName(session, "test5a");
    oClass.getProperty(session, "test6").setName(session, "test6a");

    session.begin();
    EntityImpl doc1 = session.load(rid);
    assertEquals(PropertyType.SHORT, doc1.getPropertyType("test1a"));
    assertTrue(doc1.field("test1a") instanceof Short);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test2a"));
    assertTrue(doc1.field("test2a") instanceof Integer);
    assertEquals(PropertyType.LONG, doc1.getPropertyType("test3a"));
    assertTrue(doc1.field("test3a") instanceof Long);
    assertEquals(PropertyType.FLOAT, doc1.getPropertyType("test4a"));
    assertTrue(doc1.field("test4a") instanceof Float);
    assertEquals(PropertyType.DOUBLE, doc1.getPropertyType("test5a"));
    assertTrue(doc1.field("test5") instanceof Double);
    assertEquals(PropertyType.INTEGER, doc1.getPropertyType("test6a"));
    assertTrue(doc1.field("test6a") instanceof Integer);
    session.commit();
  }

  @Test
  public void testCreatePropertyCastableColectionNoCache() {
    final Schema oSchema = session.getMetadata().getSchema();
    var oClass = oSchema.createClass("Test11bis");

    var rid =
        session.computeInTx(
            () -> {
              final var entity = session.newEntity("Test11bis");
              entity.newEmbeddedList("test1");
              entity.newLinkList("test2");

              entity.newEmbeddedSet("test3");
              entity.newLinkSet("test4");
              entity.newEmbeddedMap("test5");
              entity.newLinkMap("test6");
              return entity.getIdentity();
            });

    oClass.createProperty(session, "test1", PropertyType.LINKLIST);
    oClass.createProperty(session, "test2", PropertyType.EMBEDDEDLIST);
    oClass.createProperty(session, "test3", PropertyType.LINKSET);
    oClass.createProperty(session, "test4", PropertyType.EMBEDDEDSET);
    oClass.createProperty(session, "test5", PropertyType.LINKMAP);
    oClass.createProperty(session, "test6", PropertyType.EMBEDDEDMAP);

    var executor = Executors.newSingleThreadExecutor();

    var future =
        executor.submit(
            () -> {
              EntityImpl doc1 = session.copy().load(rid);
              assertEquals(PropertyType.LINKLIST, doc1.getPropertyType("test1"));
              assertEquals(PropertyType.EMBEDDEDLIST, doc1.getPropertyType("test2"));
              assertEquals(PropertyType.LINKSET, doc1.getPropertyType("test3"));
              assertEquals(PropertyType.EMBEDDEDSET, doc1.getPropertyType("test4"));
              assertEquals(PropertyType.LINKMAP, doc1.getPropertyType("test5"));
              assertEquals(PropertyType.EMBEDDEDMAP, doc1.getPropertyType("test6"));
              return doc1;
            });

    try {
      future.get();
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AssertionError) {
        throw (AssertionError) e.getCause();
      }
    }

    executor.shutdown();
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

    oClass.setCustom(session, "customAttribute", "value1");
    assertEquals("value1", oClass.getCustom(session, "customAttribute"));

    oClass.setCustom(session, "custom.attribute", "value2");
    assertEquals("value2", oClass.getCustom(session, "custom.attribute"));
  }
}
