package com.jetbrains.youtrackdb.internal.core.db;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.common.BasicDatabaseSession;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.api.exception.SchemaException;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.iterator.RecordIteratorClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;

public class DatabaseDocumentTxTest extends DbTestBase {

  @Test
  public void testCountClass() throws Exception {
    var testSuperclass = session.getMetadata().getSlowMutableSchema().createClass("TestSuperclass");
    session.getMetadata().getSlowMutableSchema().createClass("TestSubclass", testSuperclass);

    session.begin();
    var toDelete = ((EntityImpl) session.newEntity("TestSubclass"));
    toDelete.setProperty("id", 1);

    session.commit();

    // 1 SUB, 0 SUPER
    Assert.assertEquals(1, session.countClass("TestSubclass", false));
    Assert.assertEquals(1, session.countClass("TestSubclass", true));
    Assert.assertEquals(0, session.countClass("TestSuperclass", false));
    Assert.assertEquals(1, session.countClass("TestSuperclass", true));

    session.begin();
    try {
      session.newEntity("TestSuperclass").setProperty("id", 1);

      session.newEntity("TestSubclass").setProperty("id", 1);

      // 2 SUB, 1 SUPER

      Assert.assertEquals(1, session.countClass("TestSuperclass", false));
      Assert.assertEquals(3, session.countClass("TestSuperclass", true));
      Assert.assertEquals(2, session.countClass("TestSubclass", false));
      Assert.assertEquals(2, session.countClass("TestSubclass", true));

      var activeTx = session.getActiveTransaction();
      activeTx.<EntityImpl>load(toDelete).delete();
      // 1 SUB, 1 SUPER

      Assert.assertEquals(1, session.countClass("TestSuperclass", false));
      Assert.assertEquals(2, session.countClass("TestSuperclass", true));
      Assert.assertEquals(1, session.countClass("TestSubclass", false));
      Assert.assertEquals(1, session.countClass("TestSubclass", true));
    } finally {
      session.commit();
    }
  }

  @Test
  public void testTimezone() {

    session.set(BasicDatabaseSession.ATTRIBUTES.TIMEZONE, "Europe/Rome");
    var newTimezone = session.get(BasicDatabaseSession.ATTRIBUTES.TIMEZONE);
    Assert.assertEquals("Europe/Rome", newTimezone);

    session.set(BasicDatabaseSession.ATTRIBUTES.TIMEZONE, "foobar");
    newTimezone = session.get(BasicDatabaseSession.ATTRIBUTES.TIMEZONE);
    Assert.assertEquals("GMT", newTimezone);
  }

  @Test(expected = RecordNotFoundException.class)
  public void testSaveInvalidRid() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("test", new RecordId(-2, 10));
    session.commit();
  }

  @Test
  public void testCreateClass() {
    var clazz = session.getMetadata().getSlowMutableSchema().createClass("TestCreateClass");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestCreateClass", clazz.getName());
    var superclasses = clazz.getParentClasses();
    if (superclasses != null) {
      assertTrue(superclasses.isEmpty());
    }
    Assert.assertNotNull(session.getMetadata().getSlowMutableSchema().getClass("TestCreateClass"));
    try {
      session.getMetadata().getSlowMutableSchema().createClass("TestCreateClass");
      Assert.fail();
    } catch (SchemaException ex) {
      //ignore
    }

    var schema = session.getMetadata().getSlowMutableSchema();
    var subclazz = session.getMetadata().getSlowMutableSchema()
        .createClass("TestCreateClass_subclass", schema.getClass("TestCreateClass"));
    Assert.assertNotNull(subclazz);
    Assert.assertEquals("TestCreateClass_subclass", subclazz.getName());
    var sub_superclasses = subclazz.getParentClasses();
    Assert.assertEquals(1, sub_superclasses.size());
    Assert.assertEquals("TestCreateClass", sub_superclasses.getFirst().getName());
  }

  @Test
  public void testGetClass() {
    var clazz = session.getMetadata().getFastImmutableSchemaSnapshot().getClass("TestGetClass");

    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestGetClass", clazz.getName());
    var superclasses = clazz.getParentClasses();
    if (superclasses != null) {
      assertTrue(superclasses.isEmpty());
    }

    var clazz2 = session.getMetadata().getFastImmutableSchemaSnapshot()
        .getClass("TestGetClass_non_existing");
    Assert.assertNull(clazz2);
  }

  @Test
  public void testDocFromJsonEmbedded() {
    Schema schema = session.getMetadata().getSlowMutableSchema();

    var c0 = schema.createAbstractClass("testDocFromJsonEmbedded_Class0");

    var c1 = schema.createClass("testDocFromJsonEmbedded_Class1");
    c1.createProperty("account", PropertyTypeInternal.STRING);
    c1.createProperty("meta", PropertyTypeInternal.EMBEDDED, c0);

    session.begin();
    var doc = (EntityImpl) session.newEntity("testDocFromJsonEmbedded_Class1");

    doc.updateFromJSON(
        """
            {
                "account": "#25:0",
                \
            "meta": {\
               "created": "2016-10-03T21:10:21.77-07:00",
                    "ip": "0:0:0:0:0:0:0:1",
               "contentType": "application/x-www-form-urlencoded",\
               "userAgent": "PostmanRuntime/2.5.2"\
            },\
            "data": "firstName=Jessica&lastName=Smith"
            }""");

    session.commit();

    session.begin();
    try (var result = session.query("select from testDocFromJsonEmbedded_Class0")) {
      Assert.assertEquals(0, result.stream().count());
    }

    try (var result = session.query("select from testDocFromJsonEmbedded_Class1")) {
      Assert.assertTrue(result.hasNext());
      var item = result.next().asEntity();
      EntityImpl meta = item.getProperty("meta");
      Assert.assertEquals("testDocFromJsonEmbedded_Class0", meta.getSchemaClassName());
      Assert.assertEquals("0:0:0:0:0:0:0:1", meta.getProperty("ip"));
    }
    session.commit();
  }

  @Test
  public void testCreateVertexClass() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.createVertexClass("TestCreateVertexClass");
    Assert.assertNotNull(clazz);

    clazz = schema.getClass("TestCreateVertexClass");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestCreateVertexClass", clazz.getName());
    var superclasses = clazz.getParentClasses();
    Assert.assertEquals(1, superclasses.size());
    Assert.assertEquals("V", superclasses.getFirst().getName());
  }

  @Test
  public void testCreateEdgeClass() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var clazz = schema.createEdgeClass("TestCreateEdgeClass");
    Assert.assertNotNull(clazz);

    clazz = schema.getClass("TestCreateEdgeClass");
    Assert.assertNotNull(clazz);
    Assert.assertEquals("TestCreateEdgeClass", clazz.getName());
    var superclasses = clazz.getParentClasses();
    Assert.assertEquals(1, superclasses.size());
    Assert.assertEquals("E", superclasses.getFirst().getName());
  }

  @Test
  public void testVertexProperty() {
    var schema = session.getMetadata().getSlowMutableSchema();
    var className = "testVertexProperty";
    schema.createClass(className, schema.getClass("V"));

    session.begin();
    var doc1 = session.newVertex(className);
    doc1.setProperty("name", "a");

    var doc2 = session.newVertex(className);
    doc2.setProperty("name", "b");
    doc2.setProperty("linked", doc1);
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT FROM " + className + " WHERE name = 'b'")) {
      Assert.assertTrue(rs.hasNext());
      var res = rs.next();

      var linkedVal = res.getProperty("linked");
      Assert.assertTrue(linkedVal instanceof Identifiable);
      session.load(((Identifiable) linkedVal).getIdentity());

      Assert.assertTrue(res.asEntityOrNull().getProperty("linked") instanceof Vertex);
    }
    session.commit();
  }

  @Test
  public void testLinkEdges() {
    var vertexClass = "testVertex";
    var edgeClass = "testEdge";

    var schema = session.getMetadata().getSlowMutableSchema();
    var vc = schema.createClass(vertexClass, schema.getClass("V"));
    schema.createClass(edgeClass, schema.getClass("E"));

    vc.createProperty("out_testEdge", PropertyTypeInternal.LINK);
    vc.createProperty("in_testEdge", PropertyTypeInternal.LINK);

    session.begin();
    var doc1 = session.newVertex(vertexClass);
    doc1.setProperty("name", "first");
    session.commit();

    session.begin();
    var doc2 = session.newVertex(vertexClass);
    doc2.setProperty("name", "second");
    var activeTx = session.getActiveTransaction();
    session.newStatefulEdge(activeTx.load(doc1), doc2, "testEdge");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT out() as o FROM " + doc1.getIdentity())) {
      Assert.assertTrue(rs.hasNext());
      var res = rs.next();

      var linkedVal = res.getProperty("o");
      Assert.assertTrue(linkedVal instanceof Collection);
      Assert.assertEquals(1, ((Collection) linkedVal).size());
    }
    session.commit();
  }

  @Test
  public void testLinkOneSide() {
    var vertexClass = "testVertexOneSide";
    var edgeClass = "testEdge";

    var schema = session.getMetadata().getSlowMutableSchema();
    var vc = schema.createClass(vertexClass, schema.getClass("V"));
    schema.createClass(edgeClass, schema.getClass("E"));

    vc.createProperty("out_testEdge", PropertyTypeInternal.LINKBAG);
    vc.createProperty("in_testEdge", PropertyTypeInternal.LINK);

    session.begin();
    var doc1 = session.newVertex(vertexClass);
    doc1.setProperty("name", "first");

    var doc2 = session.newVertex(vertexClass);
    doc2.setProperty("name", "second");

    var doc3 = session.newVertex(vertexClass);
    doc3.setProperty("name", "third");

    session.newStatefulEdge(doc1, doc2, "testEdge");
    session.newStatefulEdge(doc1, doc3, "testEdge");
    session.commit();

    session.begin();
    try (var rs = session.query("SELECT out() as o FROM " + doc1.getIdentity())) {
      Assert.assertTrue(rs.hasNext());
      var res = rs.next();

      var linkedVal = res.getProperty("o");
      Assert.assertTrue(linkedVal instanceof Collection);
      Assert.assertEquals(2, ((Collection) linkedVal).size());
    }
    session.commit();
  }

  @Test(expected = DatabaseException.class)
  public void testLinkDuplicate() {
    var vertexClass = "testVertex";
    var edgeClass = "testEdge";

    var schema = session.getMetadata().getSlowMutableSchema();
    var vc = schema.createClass(vertexClass, schema.getClass("V"));
    schema.createClass(edgeClass, schema.getClass("E"));

    vc.createProperty("out_testEdge", PropertyTypeInternal.LINK);
    vc.createProperty("in_testEdge", PropertyTypeInternal.LINK);

    session.executeInTx(transaction -> {
      var doc1 = session.newVertex(vertexClass);
      doc1.setProperty("name", "first");

      var doc2 = session.newVertex(vertexClass);
      doc2.setProperty("name", "second");

      var doc3 = session.newVertex(vertexClass);
      doc3.setProperty("name", "third");

      session.newStatefulEdge(doc1, doc2, "testEdge");
      session.newStatefulEdge(doc1, doc3, "testEdge");
    });
  }

  @Test
  public void selectDescTest() {
    var className = "bar";
    var schema = session.getMetadata().getSlowMutableSchema();
    schema.createClass(className, schema.getClass(SchemaClass.VERTEX_CLASS_NAME));
    session.begin();

    var document = (EntityImpl) session.newVertex(className);

    var reverseIterator =
        new RecordIteratorClass(session, className, true, false);
    Assert.assertTrue(reverseIterator.hasNext());
    Assert.assertEquals(document, reverseIterator.next());
  }
}
