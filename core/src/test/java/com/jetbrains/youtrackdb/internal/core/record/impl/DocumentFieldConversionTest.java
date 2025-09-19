package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClass;
import java.math.BigDecimal;
import java.util.Calendar;
import java.util.Date;
import org.junit.Test;

public class DocumentFieldConversionTest extends BaseMemoryInternalDatabase {

  private SchemaClass clazz;

  public void beforeTest() throws Exception {
    super.beforeTest();
    clazz = session.getMetadata().getSlowMutableSchema().createClass("testClass");
    clazz.createProperty("integer", PropertyType.INTEGER);
    clazz.createProperty("string", PropertyType.STRING);
    clazz.createProperty("boolean", PropertyType.BOOLEAN);
    clazz.createProperty("long", PropertyType.LONG);
    clazz.createProperty("float", PropertyType.FLOAT);
    clazz.createProperty("double", PropertyType.DOUBLE);
    clazz.createProperty("decimal", PropertyType.DECIMAL);
    clazz.createProperty("date", PropertyType.DATE);

    clazz.createProperty("byteList", PropertyType.EMBEDDEDLIST, PropertyType.BYTE);
    clazz.createProperty("integerList", PropertyType.EMBEDDEDLIST, PropertyType.INTEGER);
    clazz.createProperty("longList", PropertyType.EMBEDDEDLIST, PropertyType.LONG);
    clazz.createProperty("stringList", PropertyType.EMBEDDEDLIST, PropertyType.STRING);
    clazz.createProperty("floatList", PropertyType.EMBEDDEDLIST, PropertyType.FLOAT);
    clazz.createProperty("doubleList", PropertyType.EMBEDDEDLIST, PropertyType.DOUBLE);
    clazz.createProperty("decimalList", PropertyType.EMBEDDEDLIST, PropertyType.DECIMAL);
    clazz.createProperty("booleanList", PropertyType.EMBEDDEDLIST, PropertyType.BOOLEAN);
    clazz.createProperty("dateList", PropertyType.EMBEDDEDLIST, PropertyType.DATE);

    clazz.createProperty("byteSet", PropertyType.EMBEDDEDSET, PropertyType.BYTE);
    clazz.createProperty("integerSet", PropertyType.EMBEDDEDSET, PropertyType.INTEGER);
    clazz.createProperty("longSet", PropertyType.EMBEDDEDSET, PropertyType.LONG);
    clazz.createProperty("stringSet", PropertyType.EMBEDDEDSET, PropertyType.STRING);
    clazz.createProperty("floatSet", PropertyType.EMBEDDEDSET, PropertyType.FLOAT);
    clazz.createProperty("doubleSet", PropertyType.EMBEDDEDSET, PropertyType.DOUBLE);
    clazz.createProperty("decimalSet", PropertyType.EMBEDDEDSET, PropertyType.DECIMAL);
    clazz.createProperty("booleanSet", PropertyType.EMBEDDEDSET, PropertyType.BOOLEAN);
    clazz.createProperty("dateSet", PropertyType.EMBEDDEDSET, PropertyType.DATE);

    clazz.createProperty("byteMap", PropertyType.EMBEDDEDMAP, PropertyType.BYTE);
    clazz.createProperty("integerMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);
    clazz.createProperty("longMap", PropertyType.EMBEDDEDMAP, PropertyType.LONG);
    clazz.createProperty("stringMap", PropertyType.EMBEDDEDMAP, PropertyType.STRING);
    clazz.createProperty("floatMap", PropertyType.EMBEDDEDMAP, PropertyType.FLOAT);
    clazz.createProperty("doubleMap", PropertyType.EMBEDDEDMAP, PropertyType.DOUBLE);
    clazz.createProperty("decimalMap", PropertyType.EMBEDDEDMAP, PropertyType.DECIMAL);
    clazz.createProperty("booleanMap", PropertyType.EMBEDDEDMAP, PropertyType.BOOLEAN);
    clazz.createProperty("dateMap", PropertyType.EMBEDDEDMAP, PropertyType.DATE);
  }

  @Test
  public void testDateToSchemaConversion() {
    session.begin();
    var calendare = Calendar.getInstance();
    calendare.set(Calendar.MILLISECOND, 0);
    var date = calendare.getTime();

    var dateString = session.getStorage().getConfiguration().getDateTimeFormatInstance()
        .format(date);
    var doc = (EntityImpl) session.newEntity(clazz);
    doc.setProperty("date", dateString);
    assertTrue(doc.getProperty("date") instanceof Date);
    assertEquals(date, doc.getProperty("date"));

    doc.setProperty("date", 20304);
    assertTrue(doc.getProperty("date") instanceof Date);
    assertEquals(20304L, ((Date) doc.getProperty("date")).getTime());

    doc.setProperty("date", 43432440f);
    assertTrue(doc.getProperty("date") instanceof Date);
    assertEquals(43432440L, ((Date) doc.getProperty("date")).getTime());

    doc.setProperty("date", 43432444D);
    assertTrue(doc.getProperty("date") instanceof Date);
    assertEquals(43432444L, ((Date) doc.getProperty("date")).getTime());

    doc.setProperty("date", 20304L);
    assertTrue(doc.getProperty("date") instanceof Date);
    assertEquals(20304L, ((Date) doc.getProperty("date")).getTime());
    session.rollback();
  }

  @Test
  public void testLiteralToSchemaConversionInteger() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);
    doc.setProperty("integer", 2L);
    assertTrue(doc.getProperty("integer") instanceof Integer);
    //    assertEquals(2, doc.field("integer"));

    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(2);

    doc.setProperty("integer", 3f);
    assertTrue(doc.getProperty("integer") instanceof Integer);
    //    assertEquals(3, doc.field("integer"));

    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(3);

    doc.setProperty("integer", 4d);
    assertTrue(doc.getProperty("integer") instanceof Integer);
    //    assertEquals(4, doc.field("integer"));

    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(4);

    doc.setProperty("integer", "5");
    assertTrue(doc.getProperty("integer") instanceof Integer);
    //    assertEquals(5, doc.field("integer"));
    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(5);

    doc.setProperty("integer", new BigDecimal("6"));
    assertTrue(doc.getProperty("integer") instanceof Integer);
    //    assertEquals(6, doc.field("integer"));

    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(6);

    session.rollback();
  }

  @Test
  public void testLiteralToSchemaConversionString() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);

    doc.setProperty("string", 1);
    assertTrue(doc.getProperty("string") instanceof String);
    assertEquals("1", doc.getProperty("string"));

    doc.setProperty("string", 2L);
    assertTrue(doc.getProperty("string") instanceof String);
    assertEquals("2", doc.getProperty("string"));

    doc.setProperty("string", 3f);
    assertTrue(doc.getProperty("string") instanceof String);
    assertEquals("3.0", doc.getProperty("string"));

    doc.setProperty("string", 4d);
    assertTrue(doc.getProperty("string") instanceof String);
    assertEquals("4.0", doc.getProperty("string"));

    doc.setProperty("string", new BigDecimal("6"));
    assertTrue(doc.getProperty("string") instanceof String);
    assertEquals("6", doc.getProperty("string"));

    doc.setProperty("string", true);
    assertTrue(doc.getProperty("string") instanceof String);
    assertEquals("true", doc.getProperty("string"));
    session.rollback();
  }

  @Test
  public void testLiteralToSchemaConversionFloat() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);

    doc.setProperty("float", 1);
    assertTrue(doc.getProperty("float") instanceof Float);
    //    assertEquals(1f, doc.field("float"));

    assertThat(doc.<Float>getProperty("float")).isEqualTo(1f);

    doc.setProperty("float", 2L);
    assertTrue(doc.getProperty("float") instanceof Float);
    //    assertEquals(2f, doc.field("float"));

    assertThat(doc.<Float>getProperty("float")).isEqualTo(2f);

    doc.setProperty("float", "3");
    assertTrue(doc.getProperty("float") instanceof Float);

    assertThat(doc.<Float>getProperty("float")).isEqualTo(3f);

    doc.setProperty("float", 4d);
    assertTrue(doc.getProperty("float") instanceof Float);
    //    assertEquals(4f, doc.field("float"));

    assertThat(doc.<Float>getProperty("float")).isEqualTo(4f);

    doc.setProperty("float", new BigDecimal("6"));
    assertTrue(doc.getProperty("float") instanceof Float);
    //    assertEquals(6f, doc.field("float"));

    assertThat(doc.<Float>getProperty("float")).isEqualTo(6f);

    session.rollback();
  }

  @Test
  public void testLiteralToSchemaConversionDouble() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);

    doc.setProperty("double", 1);
    assertTrue(doc.getProperty("double") instanceof Double);

    assertThat(doc.<Double>getProperty("double")).isEqualTo(1d);

    doc.setProperty("double", 2L);
    assertTrue(doc.getProperty("double") instanceof Double);

    assertThat(doc.<Double>getProperty("double")).isEqualTo(2d);

    doc.setProperty("double", "3");
    assertTrue(doc.getProperty("double") instanceof Double);

    assertThat(doc.<Double>getProperty("double")).isEqualTo(3d);

    doc.setProperty("double", 4f);
    assertTrue(doc.getProperty("double") instanceof Double);

    assertThat(doc.<Double>getProperty("double")).isEqualTo(4d);

    doc.setProperty("double", new BigDecimal("6"));
    assertTrue(doc.getProperty("double") instanceof Double);

    assertThat(doc.<Double>getProperty("double")).isEqualTo(6d);

    session.rollback();
  }

  @Test
  public void testLiteralToSchemaConversionLong() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);

    doc.setProperty("long", 1);
    assertTrue(doc.getProperty("long") instanceof Long);
    //    assertEquals(1L, doc.field("long"));

    assertThat(doc.<Long>getProperty("long")).isEqualTo(1L);

    doc.setProperty("long", 2f);
    assertTrue(doc.getProperty("long") instanceof Long);
    //    assertEquals(2L, doc.field("long"));

    assertThat(doc.<Long>getProperty("long")).isEqualTo(2L);

    doc.setProperty("long", "3");
    assertTrue(doc.getProperty("long") instanceof Long);
    //    assertEquals(3L, doc.field("long"));

    assertThat(doc.<Long>getProperty("long")).isEqualTo(3L);

    doc.setProperty("long", 4d);
    assertTrue(doc.getProperty("long") instanceof Long);
    //    assertEquals(4L, doc.field("long"));

    assertThat(doc.<Long>getProperty("long")).isEqualTo(4L);

    doc.setProperty("long", new BigDecimal("6"));
    assertTrue(doc.getProperty("long") instanceof Long);
    assertThat(doc.<Long>getProperty("long")).isEqualTo(6L);

    session.rollback();
  }

  @Test
  public void testLiteralToSchemaConversionBoolean() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);

    doc.setProperty("boolean", 0);
    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(false, doc.getProperty("boolean"));

    doc.setProperty("boolean", 1L);
    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(true, doc.getProperty("boolean"));

    doc.setProperty("boolean", 2f);
    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(true, doc.getProperty("boolean"));

    doc.setProperty("boolean", "true");
    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(true, doc.getProperty("boolean"));

    doc.setProperty("boolean", 4d);
    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(true, doc.getProperty("boolean"));

    doc.setProperty("boolean", new BigDecimal("6"));
    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(true, doc.getProperty("boolean"));
    session.rollback();
  }

  @Test
  public void testLiteralToSchemaConversionDecimal() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);

    doc.setProperty("decimal", 0);
    assertTrue(doc.getProperty("decimal") instanceof BigDecimal);
    assertEquals(BigDecimal.ZERO, doc.getProperty("decimal"));

    doc.setProperty("decimal", 1L);
    assertTrue(doc.getProperty("decimal") instanceof BigDecimal);
    assertEquals(BigDecimal.ONE, doc.getProperty("decimal"));

    doc.setProperty("decimal", 2f);
    assertTrue(doc.getProperty("decimal") instanceof BigDecimal);
    assertEquals(new BigDecimal("2.0"), doc.getProperty("decimal"));

    doc.setProperty("decimal", "3");
    assertTrue(doc.getProperty("decimal") instanceof BigDecimal);
    assertEquals(new BigDecimal("3"), doc.getProperty("decimal"));

    doc.setProperty("decimal", 4d);
    assertTrue(doc.getProperty("decimal") instanceof BigDecimal);
    assertEquals(new BigDecimal("4.0"), doc.getProperty("decimal"));

    doc.setProperty("boolean", new BigDecimal("6"));
    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(true, doc.getProperty("boolean"));
    session.rollback();
  }

  @Test
  public void testConversionAlsoWithWrongType() {
    session.begin();
    var doc = (EntityImpl) session.newEntity(clazz);

    doc.setProperty("float", 2, PropertyType.INTEGER);
    assertTrue(doc.getProperty("float") instanceof Float);
    assertThat(doc.<Float>getProperty("float")).isEqualTo(2f);

    doc.setProperty("integer", 3f, PropertyType.FLOAT);
    assertTrue(doc.getProperty("integer") instanceof Integer);
    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(3);

    doc.setProperty("double", 1L, PropertyType.LONG);
    assertTrue(doc.getProperty("double") instanceof Double);
    assertThat(doc.<Double>getProperty("double")).isEqualTo(1d);

    doc.setProperty("long", 1d, PropertyType.DOUBLE);
    assertTrue(doc.getProperty("long") instanceof Long);

    assertThat(doc.<Long>getProperty("long")).isEqualTo(1L);
    session.rollback();
  }

  @Test
  public void testLiteralConversionAfterSchemaSet() {
    session.begin();
    var doc = (EntityImpl) session.newEntity();

    doc.setProperty("float", 1);
    doc.setProperty("integer", 3f);
    doc.setProperty("double", 2L);
    doc.setProperty("long", 2D);
    doc.setProperty("string", 25);
    doc.setProperty("boolean", "true");
    doc.setProperty("decimal", -1);
    doc.setProperty("date", 20304L);

    doc.setClassNameIfExists(clazz.getName());
    assertTrue(doc.getProperty("float") instanceof Float);
    //    assertEquals(1f, doc.field("float"));

    assertThat(doc.<Float>getProperty("float")).isEqualTo(1f);

    assertTrue(doc.getProperty("integer") instanceof Integer);
    //    assertEquals(3, doc.field("integer"));

    assertThat(doc.<Integer>getProperty("integer")).isEqualTo(3);

    assertTrue(doc.getProperty("long") instanceof Long);
    //    assertEquals(2L, doc.field("long"));

    assertThat(doc.<Long>getProperty("long")).isEqualTo(2L);

    assertTrue(doc.getProperty("string") instanceof String);
    assertEquals("25", doc.getProperty("string"));

    assertTrue(doc.getProperty("boolean") instanceof Boolean);
    assertEquals(true, doc.getProperty("boolean"));

    assertTrue(doc.getProperty("decimal") instanceof BigDecimal);
    assertEquals(new BigDecimal(-1), doc.getProperty("decimal"));

    assertTrue(doc.getProperty("date") instanceof Date);
    assertEquals(20304L, ((Date) doc.getProperty("date")).getTime());
    session.rollback();
  }
}
