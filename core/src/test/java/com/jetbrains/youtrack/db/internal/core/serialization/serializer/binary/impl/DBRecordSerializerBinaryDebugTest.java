package com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinaryDebug;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DBRecordSerializerBinaryDebugTest extends DbTestBase {

  private RecordSerializer previous;

  @Before
  public void before() {
    previous = DatabaseSessionAbstract.getDefaultSerializer();
    DatabaseSessionAbstract.setDefaultSerializer(new RecordSerializerBinary());
  }

  @After
  public void after() {
    DatabaseSessionAbstract.setDefaultSerializer(previous);
  }

  @Test
  public void testSimpleDocumentDebug() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("test", "test");
    doc.setProperty("anInt", 2);
    doc.setProperty("anDouble", 2D);

    var bytes = doc.toStream();

    var debugger = new RecordSerializerBinaryDebug();
    var debug = debugger.deserializeDebug(bytes, session);

    assertEquals(3, debug.properties.size());
    assertEquals("test", debug.properties.getFirst().name);
    assertEquals(PropertyType.STRING, debug.properties.get(0).type);
    assertEquals("test", debug.properties.get(0).value);

    assertEquals("anInt", debug.properties.get(1).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(1).type);
    assertEquals(2, debug.properties.get(1).value);

    assertEquals("anDouble", debug.properties.get(2).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(2).type);
    assertEquals(2D, debug.properties.get(2).value);
    session.rollback();
  }

  @Test
  public void testSchemaFullDocumentDebug() {
    var clazz = session.getMetadata().getSchema().createClass("some");
    clazz.createProperty("testP", PropertyType.STRING);
    clazz.createProperty("theInt", PropertyType.INTEGER);

    session.begin();
    var doc = (EntityImpl) session.newEntity("some");
    doc.setProperty("testP", "test");
    doc.setProperty("theInt", 2);
    doc.setProperty("anDouble", 2D);

    var bytes = doc.toStream();

    var debugger = new RecordSerializerBinaryDebug();
    var debug = debugger.deserializeDebug(bytes, session);

    assertEquals(3, debug.properties.size());
    assertEquals("testP", debug.properties.getFirst().name);
    assertEquals(PropertyType.STRING, debug.properties.get(0).type);
    assertEquals("test", debug.properties.get(0).value);

    assertEquals("theInt", debug.properties.get(1).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(1).type);
    assertEquals(2, debug.properties.get(1).value);

    assertEquals("anDouble", debug.properties.get(2).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(2).type);
    assertEquals(2D, debug.properties.get(2).value);
    session.rollback();
  }

  @Test
  public void testSimpleBrokenDocumentDebug() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();
    doc.setProperty("test", "test");
    doc.setProperty("anInt", 2);
    doc.setProperty("anDouble", 2D);

    var bytes = doc.toStream();
    var brokenBytes = new byte[bytes.length - 10];
    System.arraycopy(bytes, 0, brokenBytes, 0, bytes.length - 10);

    var debugger = new RecordSerializerBinaryDebug();
    var debug = debugger.deserializeDebug(brokenBytes, session);

    assertEquals(3, debug.properties.size());
    assertEquals("test", debug.properties.getFirst().name);
    assertEquals(PropertyType.STRING, debug.properties.getFirst().type);
    assertTrue(debug.properties.get(0).faildToRead);
    assertNotNull(debug.properties.get(0).readingException);

    assertEquals("anInt", debug.properties.get(1).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(1).type);
    assertTrue(debug.properties.get(1).faildToRead);
    assertNotNull(debug.properties.get(1).readingException);

    assertEquals("anDouble", debug.properties.get(2).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(2).type);
    assertTrue(debug.properties.get(2).faildToRead);
    assertNotNull(debug.properties.get(2).readingException);
    session.rollback();
  }

  @Test
  public void testBrokenSchemaFullDocumentDebug() {
    var clazz = session.getMetadata().getSchema().createClass("some");
    clazz.createProperty("testP", PropertyType.STRING);
    clazz.createProperty("theInt", PropertyType.INTEGER);

    session.begin();
    var doc = (EntityImpl) session.newEntity("some");
    doc.setProperty("testP", "test");
    doc.setProperty("theInt", 2);
    doc.setProperty("anDouble", 2D);

    var bytes = doc.toStream();
    var brokenBytes = new byte[bytes.length - 10];
    System.arraycopy(bytes, 0, brokenBytes, 0, bytes.length - 10);

    var debugger = new RecordSerializerBinaryDebug();
    var debug = debugger.deserializeDebug(brokenBytes, session);

    assertEquals(3, debug.properties.size());
    assertEquals("testP", debug.properties.getFirst().name);
    assertEquals(PropertyType.STRING, debug.properties.getFirst().type);
    assertTrue(debug.properties.get(0).faildToRead);
    assertNotNull(debug.properties.get(0).readingException);

    assertEquals("theInt", debug.properties.get(1).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(1).type);
    assertTrue(debug.properties.get(1).faildToRead);
    assertNotNull(debug.properties.get(1).readingException);

    assertEquals("anDouble", debug.properties.get(2).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(2).type);
    assertTrue(debug.properties.get(2).faildToRead);
    assertNotNull(debug.properties.get(2).readingException);
    session.rollback();
  }
}
