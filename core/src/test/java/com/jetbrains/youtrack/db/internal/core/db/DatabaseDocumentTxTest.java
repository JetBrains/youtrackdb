package com.jetbrains.youtrack.db.internal.core.db;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.CommitSerializationException;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.query.ResultSet;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.Vertex;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.iterator.RecordIteratorClassDescendentOrder;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class DatabaseDocumentTxTest extends DbTestBase {

  @Test
  public void testCountClass() throws Exception {

    SchemaClass testSuperclass = db.getMetadata().getSchema().createClass("TestSuperclass");
    db.getMetadata().getSchema().createClass("TestSubclass", testSuperclass);

    db.begin();
    EntityImpl toDelete = new EntityImpl("TestSubclass").field("id", 1);
    toDelete.save();
    db.commit();

    // 1 SUB, 0 SUPER
    Assert.assertEquals(db.countClass("TestSubclass", false), 1);
    Assert.assertEquals(db.countClass("TestSubclass", true), 1);
    Assert.assertEquals(db.countClass("TestSuperclass", false), 0);
    Assert.assertEquals(db.countClass("TestSuperclass", true), 1);

    db.begin();
    try {
      new EntityImpl("TestSuperclass").field("id", 1).save();
      new EntityImpl("TestSubclass").field("id", 1).save();
      // 2 SUB, 1 SUPER

      Assert.assertEquals(db.countClass("TestSuperclass", false), 1);
      Assert.assertEquals(db.countClass("TestSuperclass", true), 3);
      Assert.assertEquals(db.countClass("TestSubclass", false), 2);
      Assert.assertEquals(db.countClass("TestSubclass", true), 2);

      db.bindToSession(toDelete).delete();
      // 1 SUB, 1 SUPER

      Assert.assertEquals(db.countClass("TestSuperclass", false), 1);
      Assert.assertEquals(db.countClass("TestSuperclass", true), 2);
      Assert.assertEquals(db.countClass("TestSubclass", false), 1);
      Assert.assertEquals(db.countClass("TestSubclass", true), 1);
    } finally {
      db.commit();
    }
  }

  @Test
  public void testTimezone() {

    db.set(DatabaseSession.ATTRIBUTES.TIMEZONE, "Europe/Rome");
    Object newTimezone = db.get(DatabaseSession.ATTRIBUTES.TIMEZONE);
    Assert.assertEquals(newTimezone, "Europe/Rome");

    db.set(DatabaseSession.ATTRIBUTES.TIMEZONE, "foobar");
    newTimezone = db.get(DatabaseSession.ATTRIBUTES.TIMEZONE);
    Assert.assertEquals(newTimezone, "GMT");
  }

  @Test(expected = CommitSerializationException.class)
  public void testSaveInvalidRid() {
    db.begin();
    EntityImpl doc = new EntityImpl();
    doc.field("test", new RecordId(-2, 10));
    db.save(doc);
    db.commit();
  }

  @Test
  public void testCreateClass() {
    SchemaClass clazz = db.createClass("TestCreateClass");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestCreateClass", clazz.getName());
    List<SchemaClass> superclasses = clazz.getSuperClasses(db);
    if (superclasses != null) {
      assertTrue(superclasses.isEmpty());
    }
    Assert.assertNotNull(db.getMetadata().getSchema().getClass("TestCreateClass"));
    try {
      db.createClass("TestCreateClass");
      Assert.fail();
    } catch (SchemaException ex) {
    }

    SchemaClass subclazz = db.createClass("TestCreateClass_subclass", "TestCreateClass");
    Assert.assertNotNull(subclazz);
    Assert.assertEquals("TestCreateClass_subclass", subclazz.getName());
    List<SchemaClass> sub_superclasses = subclazz.getSuperClasses(db);
    Assert.assertEquals(1, sub_superclasses.size());
    Assert.assertEquals("TestCreateClass", sub_superclasses.get(0).getName());
  }

  @Test
  public void testGetClass() {
    db.createClass("TestGetClass");

    SchemaClass clazz = db.getClass("TestGetClass");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestGetClass", clazz.getName());
    List<SchemaClass> superclasses = clazz.getSuperClasses(db);
    if (superclasses != null) {
      assertTrue(superclasses.isEmpty());
    }

    SchemaClass clazz2 = db.getClass("TestGetClass_non_existing");
    Assert.assertNull(clazz2);
  }

  @Test
  public void testDocFromJsonEmbedded() {
    Schema schema = db.getMetadata().getSchema();

    SchemaClass c0 = schema.createClass("testDocFromJsonEmbedded_Class0");

    SchemaClass c1 = schema.createClass("testDocFromJsonEmbedded_Class1");
    c1.createProperty(db, "account", PropertyType.STRING);
    c1.createProperty(db, "meta", PropertyType.EMBEDDED, c0);

    db.begin();
    EntityImpl doc = new EntityImpl("testDocFromJsonEmbedded_Class1");

    doc.fromJSON(
        "{\n"
            + "    \"account\": \"#25:0\",\n"
            + "    "
            + "\"meta\": {"
            + "   \"created\": \"2016-10-03T21:10:21.77-07:00\",\n"
            + "        \"ip\": \"0:0:0:0:0:0:0:1\",\n"
            + "   \"contentType\": \"application/x-www-form-urlencoded\","
            + "   \"userAgent\": \"PostmanRuntime/2.5.2\""
            + "},"
            + "\"data\": \"firstName=Jessica&lastName=Smith\"\n"
            + "}");

    db.save(doc);
    db.commit();

    try (ResultSet result = db.query("select from testDocFromJsonEmbedded_Class0")) {
      Assert.assertEquals(result.stream().count(), 0);
    }

    try (ResultSet result = db.query("select from testDocFromJsonEmbedded_Class1")) {
      Assert.assertTrue(result.hasNext());
      Entity item = result.next().getEntity().get();
      EntityImpl meta = item.getProperty("meta");
      Assert.assertEquals(meta.getClassName(), "testDocFromJsonEmbedded_Class0");
      Assert.assertEquals(meta.field("ip"), "0:0:0:0:0:0:0:1");
    }
  }

  @Test
  public void testCreateClassIfNotExists() {
    db.createClass("TestCreateClassIfNotExists");

    SchemaClass clazz = db.createClassIfNotExist("TestCreateClassIfNotExists");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestCreateClassIfNotExists", clazz.getName());
    List<SchemaClass> superclasses = clazz.getSuperClasses(db);
    if (superclasses != null) {
      assertTrue(superclasses.isEmpty());
    }

    SchemaClass clazz2 = db.createClassIfNotExist("TestCreateClassIfNotExists_non_existing");
    Assert.assertNotNull(clazz2);
    Assert.assertEquals("TestCreateClassIfNotExists_non_existing", clazz2.getName());
    List<SchemaClass> superclasses2 = clazz2.getSuperClasses(db);
    if (superclasses2 != null) {
      assertTrue(superclasses2.isEmpty());
    }
  }

  @Test
  public void testCreateVertexClass() {
    SchemaClass clazz = db.createVertexClass("TestCreateVertexClass");
    Assert.assertNotNull(clazz);

    clazz = db.getClass("TestCreateVertexClass");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestCreateVertexClass", clazz.getName());
    List<SchemaClass> superclasses = clazz.getSuperClasses(db);
    Assert.assertEquals(1, superclasses.size());
    Assert.assertEquals("V", superclasses.get(0).getName());
  }

  @Test
  public void testCreateEdgeClass() {
    SchemaClass clazz = db.createEdgeClass("TestCreateEdgeClass");
    Assert.assertNotNull(clazz);

    clazz = db.getClass("TestCreateEdgeClass");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestCreateEdgeClass", clazz.getName());
    List<SchemaClass> superclasses = clazz.getSuperClasses(db);
    Assert.assertEquals(1, superclasses.size());
    Assert.assertEquals("E", superclasses.get(0).getName());
  }

  @Test
  public void testVertexProperty() {
    String className = "testVertexProperty";
    db.createClass(className, "V");

    db.begin();
    Vertex doc1 = db.newVertex(className);
    doc1.setProperty("name", "a");
    doc1.save();

    Vertex doc2 = db.newVertex(className);
    doc2.setProperty("name", "b");
    doc2.setProperty("linked", doc1);
    doc2.save();
    db.commit();

    try (ResultSet rs = db.query("SELECT FROM " + className + " WHERE name = 'b'")) {
      Assert.assertTrue(rs.hasNext());
      Result res = rs.next();

      Object linkedVal = res.getProperty("linked");
      Assert.assertTrue(linkedVal instanceof Identifiable);
      Assert.assertTrue(
          db.load(((Identifiable) linkedVal).getIdentity()) instanceof Identifiable);

      Assert.assertTrue(res.toEntity().getProperty("linked") instanceof Vertex);
    }
  }

  @Test
  public void testLinkEdges() {
    String vertexClass = "testVertex";
    String edgeClass = "testEdge";
    SchemaClass vc = db.createClass(vertexClass, "V");
    db.createClass(edgeClass, "E");
    vc.createProperty(db, "out_testEdge", PropertyType.LINK);
    vc.createProperty(db, "in_testEdge", PropertyType.LINK);

    db.begin();
    Vertex doc1 = db.newVertex(vertexClass);
    doc1.setProperty("name", "first");
    doc1.save();
    db.commit();

    db.begin();
    Vertex doc2 = db.newVertex(vertexClass);
    doc2.setProperty("name", "second");
    doc2.save();
    db.newRegularEdge(db.bindToSession(doc1), doc2, "testEdge").save();
    db.commit();

    try (ResultSet rs = db.query("SELECT out() as o FROM " + vertexClass)) {
      Assert.assertTrue(rs.hasNext());
      Result res = rs.next();

      Object linkedVal = res.getProperty("o");
      Assert.assertTrue(linkedVal instanceof Collection);
      Assert.assertEquals(((Collection) linkedVal).size(), 1);
    }
  }

  @Test
  public void testLinkOneSide() {
    String vertexClass = "testVertex";
    String edgeClass = "testEdge";
    SchemaClass vc = db.createClass(vertexClass, "V");
    db.createClass(edgeClass, "E");

    vc.createProperty(db, "out_testEdge", PropertyType.LINKBAG);
    vc.createProperty(db, "in_testEdge", PropertyType.LINK);

    db.begin();
    Vertex doc1 = db.newVertex(vertexClass);
    doc1.setProperty("name", "first");
    doc1.save();

    Vertex doc2 = db.newVertex(vertexClass);
    doc2.setProperty("name", "second");
    doc2.save();

    Vertex doc3 = db.newVertex(vertexClass);
    doc3.setProperty("name", "third");
    doc3.save();

    db.newRegularEdge(doc1, doc2, "testEdge").save();
    db.newRegularEdge(doc1, doc3, "testEdge").save();
    db.commit();

    try (ResultSet rs = db.query("SELECT out() as o FROM " + vertexClass)) {
      Assert.assertTrue(rs.hasNext());
      Result res = rs.next();

      Object linkedVal = res.getProperty("o");
      Assert.assertTrue(linkedVal instanceof Collection);
      Assert.assertEquals(((Collection) linkedVal).size(), 2);
    }
  }

  @Test(expected = DatabaseException.class)
  public void testLinkDuplicate() {
    String vertexClass = "testVertex";
    String edgeClass = "testEdge";
    SchemaClass vc = db.createClass(vertexClass, "V");
    db.createClass(edgeClass, "E");
    vc.createProperty(db, "out_testEdge", PropertyType.LINK);
    vc.createProperty(db, "in_testEdge", PropertyType.LINK);
    Vertex doc1 = db.newVertex(vertexClass);
    doc1.setProperty("name", "first");
    doc1.save();

    Vertex doc2 = db.newVertex(vertexClass);
    doc2.setProperty("name", "second");
    doc2.save();

    Vertex doc3 = db.newVertex(vertexClass);
    doc3.setProperty("name", "third");
    doc3.save();

    db.newRegularEdge(doc1, doc2, "testEdge");
    db.newRegularEdge(doc1, doc3, "testEdge");
  }

  @Test
  public void selectDescTest() {
    String className = "bar";
    Schema schema = db.getMetadata().getSchema();
    schema.createClass(className, 1, schema.getClass(SchemaClass.VERTEX_CLASS_NAME));
    db.begin();

    EntityImpl document = new EntityImpl(className);
    document.save();
    RecordIteratorClassDescendentOrder<EntityImpl> reverseIterator =
        new RecordIteratorClassDescendentOrder<EntityImpl>(db, db, className, true);
    Assert.assertTrue(reverseIterator.hasNext());
    Assert.assertEquals(document, reverseIterator.next());
  }
}
