package com.jetbrains.youtrack.db.internal.core.serialization.serializer.binary.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializationDebugProperty;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinaryDebug;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
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

    var names = extractNames(debug.properties);

    assertEquals(3, debug.properties.size());

    var testIndex = names.indexOf("test");
    assertEquals("test", debug.properties.get(testIndex).name);
    assertEquals(PropertyType.STRING, debug.properties.get(testIndex).type);
    assertEquals("test", debug.properties.get(testIndex).value);

    var intIndex = names.indexOf("anInt");
    assertEquals("anInt", debug.properties.get(intIndex).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(intIndex).type);
    assertEquals(2, debug.properties.get(intIndex).value);

    var doubleIndex = names.indexOf("anDouble");
    assertEquals("anDouble", debug.properties.get(doubleIndex).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(doubleIndex).type);
    assertEquals(2D, debug.properties.get(doubleIndex).value);

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

    var names = extractNames(debug.properties);
    var testPIndex = names.indexOf("testP");

    assertEquals("testP", debug.properties.get(testPIndex).name);
    assertEquals(PropertyType.STRING, debug.properties.get(testPIndex).type);
    assertEquals("test", debug.properties.get(testPIndex).value);

    var theIntIndex = names.indexOf("theInt");
    assertEquals("theInt", debug.properties.get(theIntIndex).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(theIntIndex).type);
    assertEquals(2, debug.properties.get(theIntIndex).value);

    var anDoubleIndex = names.indexOf("anDouble");
    assertEquals("anDouble", debug.properties.get(anDoubleIndex).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(anDoubleIndex).type);
    assertEquals(2D, debug.properties.get(anDoubleIndex).value);

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
    var names = extractNames(debug.properties);

    var testIndex = names.indexOf("test");
    assertEquals("test", debug.properties.get(testIndex).name);
    assertEquals(PropertyType.STRING, debug.properties.get(testIndex).type);
    assertTrue(debug.properties.get(testIndex).faildToRead);
    assertNotNull(debug.properties.get(testIndex).readingException);

    var intIndex = names.indexOf("anInt");
    assertEquals("anInt", debug.properties.get(intIndex).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(intIndex).type);
    assertTrue(debug.properties.get(intIndex).faildToRead);
    assertNotNull(debug.properties.get(intIndex).readingException);

    var doubleIndex = names.indexOf("anDouble");
    assertEquals("anDouble", debug.properties.get(doubleIndex).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(doubleIndex).type);
    assertTrue(debug.properties.get(doubleIndex).faildToRead);
    assertNotNull(debug.properties.get(doubleIndex).readingException);

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

    var names = extractNames(debug.properties);
    assertEquals(3, debug.properties.size());

    var failed = false;

    var testIndex = names.indexOf("testP");
    assertEquals("testP", debug.properties.get(testIndex).name);
    assertEquals(PropertyType.STRING, debug.properties.get(testIndex).type);
    failed |= debug.properties.get(testIndex).faildToRead;
    if (debug.properties.get(testIndex).faildToRead) {
      assertNotNull(debug.properties.get(testIndex).readingException);
    } else {
      assertNull(debug.properties.get(testIndex).readingException);
    }

    var intIndex = names.indexOf("theInt");
    assertEquals("theInt", debug.properties.get(intIndex).name);
    assertEquals(PropertyType.INTEGER, debug.properties.get(intIndex).type);
    failed |= debug.properties.get(intIndex).faildToRead;
    if (debug.properties.get(intIndex).faildToRead) {
      assertNotNull(debug.properties.get(intIndex).readingException);
    } else {
      assertNull(debug.properties.get(intIndex).readingException);
    }

    var doubleIndex = names.indexOf("anDouble");
    assertEquals("anDouble", debug.properties.get(doubleIndex).name);
    assertEquals(PropertyType.DOUBLE, debug.properties.get(doubleIndex).type);
    failed |= debug.properties.get(doubleIndex).faildToRead;
    if (debug.properties.get(doubleIndex).faildToRead) {
      assertNotNull(debug.properties.get(doubleIndex).readingException);
    } else {
      assertNull(debug.properties.get(doubleIndex).readingException);
    }

    Assert.assertTrue(failed);

    session.rollback();
  }

  static List<String> extractNames(ArrayList<RecordSerializationDebugProperty> properties) {
    var result = new ArrayList<String>();
    for (var property : properties) {
      result.add(property.name);
    }

    return result;
  }
}
