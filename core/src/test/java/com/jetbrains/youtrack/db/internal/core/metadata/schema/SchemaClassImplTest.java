package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.schema.GlobalProperty;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    final Schema oSchema = db.getMetadata().getSchema();

    SchemaClass oClass = oSchema.createClass("Test1");
    final int oldClusterId = oClass.getClusterIds()[0];

    oClass.setAbstract(db, false);

    assertEquals(oClass.getClusterIds()[0], oldClusterId);
  }

  /**
   * If class was abstract and we call {@code setAbstract(false)} a new non default cluster should
   * be created.
   *
   * @throws Exception
   */
  @Test
  public void testSetAbstractShouldCreateNewClusters() throws Exception {
    final Schema oSchema = db.getMetadata().getSchema();

    SchemaClass oClass = oSchema.createAbstractClass("Test2");

    oClass.setAbstract(db, false);

    assertNotEquals(-1, oClass.getClusterIds()[0]);
    assertNotEquals(oClass.getClusterIds()[0], db.getDefaultClusterId());
  }

  @Test
  public void testCreateNoLinkedClass() {
    final Schema oSchema = db.getMetadata().getSchema();

    SchemaClassInternal oClass = (SchemaClassInternal) oSchema.createClass("Test21");
    oClass.createProperty(db, "some", PropertyType.LINKLIST, (SchemaClass) null);
    oClass.createProperty(db, "some2", PropertyType.LINKLIST, (SchemaClass) null, true);

    assertNotNull(oClass.getProperty(db, "some"));
    assertNotNull(oClass.getProperty(db, "some2"));
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingData() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test3");

    db.executeInTx(
        () -> {
          EntityImpl document = new EntityImpl("Test3");
          document.field("some", "String");
          db.save(document);
        });

    oClass.createProperty(db, "some", PropertyType.INTEGER);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkList() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test4");

    db.executeInTx(
        () -> {
          EntityImpl document = new EntityImpl("Test4");
          ArrayList<EntityImpl> list = new ArrayList<EntityImpl>();
          list.add(new EntityImpl("Test4"));
          document.field("some", list);
          db.save(document);
        });

    oClass.createProperty(db, "some", PropertyType.EMBEDDEDLIST);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkSet() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test5");

    db.executeInTx(
        () -> {
          EntityImpl document = new EntityImpl("Test5");
          Set<EntityImpl> set = new HashSet<EntityImpl>();
          set.add(new EntityImpl("Test5"));
          document.field("somelinkset", set);
          db.save(document);
        });

    oClass.createProperty(db, "somelinkset", PropertyType.EMBEDDEDSET);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddetSet() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test6");

    db.executeInTx(
        () -> {
          EntityImpl document = new EntityImpl("Test6");
          Set<EntityImpl> list = new HashSet<EntityImpl>();
          list.add(new EntityImpl("Test6"));
          document.field("someembededset", list, PropertyType.EMBEDDEDSET);
          db.save(document);
        });

    oClass.createProperty(db, "someembededset", PropertyType.LINKSET);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddedList() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test7");

    db.executeInTx(
        () -> {
          EntityImpl document = new EntityImpl("Test7");
          List<EntityImpl> list = new ArrayList<EntityImpl>();
          list.add(new EntityImpl("Test7"));
          document.field("someembeddedlist", list, PropertyType.EMBEDDEDLIST);
          db.save(document);
        });

    oClass.createProperty(db, "someembeddedlist", PropertyType.LINKLIST);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataEmbeddedMap() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test8");

    db.executeInTx(
        () -> {
          EntityImpl document = new EntityImpl("Test8");
          Map<String, EntityImpl> map = new HashMap<>();
          map.put("test", new EntityImpl("Test8"));
          document.field("someembededmap", map, PropertyType.EMBEDDEDMAP);
          db.save(document);
        });

    oClass.createProperty(db, "someembededmap", PropertyType.LINKMAP);
  }

  @Test(expected = SchemaException.class)
  public void testCreatePropertyFailOnExistingDataLinkMap() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test9");
    oSchema.createClass("Test8");

    db.executeInTx(
        () -> {
          EntityImpl document = new EntityImpl("Test9");
          Map<String, EntityImpl> map = new HashMap<String, EntityImpl>();
          map.put("test", new EntityImpl("Test8"));
          document.field("somelinkmap", map, PropertyType.LINKMAP);
          db.save(document);
        });

    oClass.createProperty(db, "somelinkmap", PropertyType.EMBEDDEDMAP);
  }

  @Test
  public void testCreatePropertyCastable() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test10");

    var rid =
        db.computeInTx(
            () -> {
              EntityImpl document = new EntityImpl("Test10");
              // TODO add boolan and byte
              document.field("test1", (short) 1);
              document.field("test2", 1);
              document.field("test3", 4L);
              document.field("test4", 3.0f);
              document.field("test5", 3.0D);
              document.field("test6", 4);
              db.save(document);
              return document.getIdentity();
            });

    oClass.createProperty(db, "test1", PropertyType.INTEGER);
    oClass.createProperty(db, "test2", PropertyType.LONG);
    oClass.createProperty(db, "test3", PropertyType.DOUBLE);
    oClass.createProperty(db, "test4", PropertyType.DOUBLE);
    oClass.createProperty(db, "test5", PropertyType.DECIMAL);
    oClass.createProperty(db, "test6", PropertyType.FLOAT);

    EntityImpl doc1 = db.load(rid);
    assertEquals(doc1.fieldType("test1"), PropertyType.INTEGER);
    assertTrue(doc1.field("test1") instanceof Integer);
    assertEquals(doc1.fieldType("test2"), PropertyType.LONG);
    assertTrue(doc1.field("test2") instanceof Long);
    assertEquals(doc1.fieldType("test3"), PropertyType.DOUBLE);
    assertTrue(doc1.field("test3") instanceof Double);
    assertEquals(doc1.fieldType("test4"), PropertyType.DOUBLE);
    assertTrue(doc1.field("test4") instanceof Double);
    assertEquals(doc1.fieldType("test5"), PropertyType.DECIMAL);
    assertTrue(doc1.field("test5") instanceof BigDecimal);
    assertEquals(doc1.fieldType("test6"), PropertyType.FLOAT);
    assertTrue(doc1.field("test6") instanceof Float);
  }

  @Test
  public void testCreatePropertyCastableColection() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test11");

    var rid =
        db.computeInTx(
            () -> {
              EntityImpl document = new EntityImpl("Test11");
              document.field("test1", new ArrayList<EntityImpl>(), PropertyType.EMBEDDEDLIST);
              document.field("test2", new ArrayList<EntityImpl>(), PropertyType.LINKLIST);
              document.field("test3", new HashSet<EntityImpl>(), PropertyType.EMBEDDEDSET);
              document.field("test4", new HashSet<EntityImpl>(), PropertyType.LINKSET);
              document.field("test5", new HashMap<String, EntityImpl>(), PropertyType.EMBEDDEDMAP);
              document.field("test6", new HashMap<String, EntityImpl>(), PropertyType.LINKMAP);
              db.save(document);
              return document.getIdentity();
            });

    oClass.createProperty(db, "test1", PropertyType.LINKLIST);
    oClass.createProperty(db, "test2", PropertyType.EMBEDDEDLIST);
    oClass.createProperty(db, "test3", PropertyType.LINKSET);
    oClass.createProperty(db, "test4", PropertyType.EMBEDDEDSET);
    oClass.createProperty(db, "test5", PropertyType.LINKMAP);
    oClass.createProperty(db, "test6", PropertyType.EMBEDDEDMAP);

    EntityImpl doc1 = db.load(rid);
    assertEquals(doc1.fieldType("test1"), PropertyType.LINKLIST);
    assertEquals(doc1.fieldType("test2"), PropertyType.EMBEDDEDLIST);
    assertEquals(doc1.fieldType("test3"), PropertyType.LINKSET);
    assertEquals(doc1.fieldType("test4"), PropertyType.EMBEDDEDSET);
    assertEquals(doc1.fieldType("test5"), PropertyType.LINKMAP);
    assertEquals(doc1.fieldType("test6"), PropertyType.EMBEDDEDMAP);
  }

  @Test
  public void testCreatePropertyIdKeep() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test12");
    SchemaProperty prop = oClass.createProperty(db, "test2", PropertyType.STRING);
    Integer id = prop.getId();
    oClass.dropProperty(db, "test2");
    prop = oClass.createProperty(db, "test2", PropertyType.STRING);
    assertEquals(id, prop.getId());
  }

  @Test
  public void testRenameProperty() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test13");
    SchemaProperty prop = oClass.createProperty(db, "test1", PropertyType.STRING);
    Integer id = prop.getId();
    prop.setName(db, "test2");
    assertNotEquals(id, prop.getId());
  }

  @Test
  public void testChangeTypeProperty() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test14");
    SchemaProperty prop = oClass.createProperty(db, "test1", PropertyType.SHORT);
    Integer id = prop.getId();
    prop.setType(db, PropertyType.INTEGER);
    assertNotEquals(id, prop.getId());
  }

  @Test
  public void testRenameBackProperty() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test15");
    SchemaProperty prop = oClass.createProperty(db, "test1", PropertyType.STRING);
    Integer id = prop.getId();
    prop.setName(db, "test2");
    assertNotEquals(id, prop.getId());
    prop.setName(db, "test1");
    assertEquals(id, prop.getId());
  }

  @Test(expected = IllegalArgumentException.class)
  public void testSetUncastableType() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test16");
    SchemaProperty prop = oClass.createProperty(db, "test1", PropertyType.STRING);
    prop.setType(db, PropertyType.INTEGER);
  }

  @Test
  public void testFindById() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test17");
    SchemaProperty prop = oClass.createProperty(db, "testaaa", PropertyType.STRING);
    GlobalProperty global = oSchema.getGlobalPropertyById(prop.getId());

    assertEquals(prop.getId(), global.getId());
    assertEquals(prop.getName(), global.getName());
    assertEquals(prop.getType(), global.getType());
  }

  @Test
  public void testFindByIdDrop() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test18");
    SchemaProperty prop = oClass.createProperty(db, "testaaa", PropertyType.STRING);
    Integer id = prop.getId();
    oClass.dropProperty(db, "testaaa");
    GlobalProperty global = oSchema.getGlobalPropertyById(id);

    assertEquals(id, global.getId());
    assertEquals("testaaa", global.getName());
    assertEquals(PropertyType.STRING, global.getType());
  }

  @Test
  public void testChangePropertyTypeCastable() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test19");

    oClass.createProperty(db, "test1", PropertyType.SHORT);
    oClass.createProperty(db, "test2", PropertyType.INTEGER);
    oClass.createProperty(db, "test3", PropertyType.LONG);
    oClass.createProperty(db, "test4", PropertyType.FLOAT);
    oClass.createProperty(db, "test5", PropertyType.DOUBLE);
    oClass.createProperty(db, "test6", PropertyType.INTEGER);

    var rid =
        db.computeInTx(
            () -> {
              EntityImpl document = new EntityImpl("Test19");
              // TODO add boolean and byte
              document.field("test1", (short) 1);
              document.field("test2", 1);
              document.field("test3", 4L);
              document.field("test4", 3.0f);
              document.field("test5", 3.0D);
              document.field("test6", 4);
              db.save(document);
              return document.getIdentity();
            });

    oClass.getProperty(db, "test1").setType(db, PropertyType.INTEGER);
    oClass.getProperty(db, "test2").setType(db, PropertyType.LONG);
    oClass.getProperty(db, "test3").setType(db, PropertyType.DOUBLE);
    oClass.getProperty(db, "test4").setType(db, PropertyType.DOUBLE);
    oClass.getProperty(db, "test5").setType(db, PropertyType.DECIMAL);
    oClass.getProperty(db, "test6").setType(db, PropertyType.FLOAT);

    EntityImpl doc1 = db.load(rid);
    assertEquals(doc1.fieldType("test1"), PropertyType.INTEGER);
    assertTrue(doc1.field("test1") instanceof Integer);
    assertEquals(doc1.fieldType("test2"), PropertyType.LONG);
    assertTrue(doc1.field("test2") instanceof Long);
    assertEquals(doc1.fieldType("test3"), PropertyType.DOUBLE);
    assertTrue(doc1.field("test3") instanceof Double);
    assertEquals(doc1.fieldType("test4"), PropertyType.DOUBLE);
    assertTrue(doc1.field("test4") instanceof Double);
    assertEquals(doc1.fieldType("test5"), PropertyType.DECIMAL);
    assertTrue(doc1.field("test5") instanceof BigDecimal);
    assertEquals(doc1.fieldType("test6"), PropertyType.FLOAT);
    assertTrue(doc1.field("test6") instanceof Float);
  }

  @Test
  public void testChangePropertyName() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test20");

    oClass.createProperty(db, "test1", PropertyType.SHORT);
    oClass.createProperty(db, "test2", PropertyType.INTEGER);
    oClass.createProperty(db, "test3", PropertyType.LONG);
    oClass.createProperty(db, "test4", PropertyType.FLOAT);
    oClass.createProperty(db, "test5", PropertyType.DOUBLE);
    oClass.createProperty(db, "test6", PropertyType.INTEGER);

    var rid =
        db.computeInTx(
            () -> {
              EntityImpl document = new EntityImpl("Test20");
              // TODO add boolan and byte
              document.field("test1", (short) 1);
              document.field("test2", 1);
              document.field("test3", 4L);
              document.field("test4", 3.0f);
              document.field("test5", 3.0D);
              document.field("test6", 4);
              db.save(document);
              return document.getIdentity();
            });

    oClass.getProperty(db, "test1").setName(db, "test1a");
    oClass.getProperty(db, "test2").setName(db, "test2a");
    oClass.getProperty(db, "test3").setName(db, "test3a");
    oClass.getProperty(db, "test4").setName(db, "test4a");
    oClass.getProperty(db, "test5").setName(db, "test5a");
    oClass.getProperty(db, "test6").setName(db, "test6a");

    EntityImpl doc1 = db.load(rid);
    assertEquals(doc1.fieldType("test1a"), PropertyType.SHORT);
    assertTrue(doc1.field("test1a") instanceof Short);
    assertEquals(doc1.fieldType("test2a"), PropertyType.INTEGER);
    assertTrue(doc1.field("test2a") instanceof Integer);
    assertEquals(doc1.fieldType("test3a"), PropertyType.LONG);
    assertTrue(doc1.field("test3a") instanceof Long);
    assertEquals(doc1.fieldType("test4a"), PropertyType.FLOAT);
    assertTrue(doc1.field("test4a") instanceof Float);
    assertEquals(doc1.fieldType("test5a"), PropertyType.DOUBLE);
    assertTrue(doc1.field("test5") instanceof Double);
    assertEquals(doc1.fieldType("test6a"), PropertyType.INTEGER);
    assertTrue(doc1.field("test6a") instanceof Integer);
  }

  @Test
  public void testCreatePropertyCastableColectionNoCache() {
    final Schema oSchema = db.getMetadata().getSchema();
    SchemaClass oClass = oSchema.createClass("Test11bis");

    var rid =
        db.computeInTx(
            () -> {
              final EntityImpl document = new EntityImpl("Test11bis");
              document.field("test1", new ArrayList<EntityImpl>(), PropertyType.EMBEDDEDLIST);
              document.field("test2", new ArrayList<EntityImpl>(), PropertyType.LINKLIST);

              document.field("test3", new HashSet<EntityImpl>(), PropertyType.EMBEDDEDSET);
              document.field("test4", new HashSet<EntityImpl>(), PropertyType.LINKSET);
              document.field("test5", new HashMap<String, EntityImpl>(), PropertyType.EMBEDDEDMAP);
              document.field("test6", new HashMap<String, EntityImpl>(), PropertyType.LINKMAP);
              db.save(document);

              return document.getIdentity();
            });
    oClass.createProperty(db, "test1", PropertyType.LINKLIST);
    oClass.createProperty(db, "test2", PropertyType.EMBEDDEDLIST);
    oClass.createProperty(db, "test3", PropertyType.LINKSET);
    oClass.createProperty(db, "test4", PropertyType.EMBEDDEDSET);
    oClass.createProperty(db, "test5", PropertyType.LINKMAP);
    oClass.createProperty(db, "test6", PropertyType.EMBEDDEDMAP);

    ExecutorService executor = Executors.newSingleThreadExecutor();

    Future<EntityImpl> future =
        executor.submit(
            () -> {
              EntityImpl doc1 = db.copy().load(rid);
              assertEquals(doc1.fieldType("test1"), PropertyType.LINKLIST);
              assertEquals(doc1.fieldType("test2"), PropertyType.EMBEDDEDLIST);
              assertEquals(doc1.fieldType("test3"), PropertyType.LINKSET);
              assertEquals(doc1.fieldType("test4"), PropertyType.EMBEDDEDSET);
              assertEquals(doc1.fieldType("test5"), PropertyType.LINKMAP);
              assertEquals(doc1.fieldType("test6"), PropertyType.EMBEDDEDMAP);
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

    final Schema oSchema = db.getMetadata().getSchema();
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
    String[] validClassNamesSince30 = {
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
    for (String s : validClassNamesSince30) {
      assertNotNull(oSchema.createClass(s));
    }
  }

  @Test
  public void testTypeAny() {
    String className = "testTypeAny";
    final Schema oSchema = db.getMetadata().getSchema();

    SchemaClass oClass = oSchema.createClass(className);

    db.executeInTx(
        () -> {
          EntityImpl record = db.newInstance(className);
          record.field("name", "foo");
          record.save();
        });

    oClass.createProperty(db, "name", PropertyType.ANY);

    try (ResultSet result = db.query("select from " + className + " where name = 'foo'")) {
      assertEquals(result.stream().count(), 1);
    }
  }

  @Test
  public void testAlterCustomAttributeInClass() {
    Schema schema = db.getMetadata().getSchema();
    SchemaClass oClass = schema.createClass("TestCreateCustomAttributeClass");

    oClass.setCustom(db, "customAttribute", "value1");
    assertEquals("value1", oClass.getCustom("customAttribute"));

    oClass.setCustom(db, "custom.attribute", "value2");
    assertEquals("value2", oClass.getCustom("custom.attribute"));
  }
}
