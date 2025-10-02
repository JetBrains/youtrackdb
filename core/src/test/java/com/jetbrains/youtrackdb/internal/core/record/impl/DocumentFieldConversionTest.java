package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
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
    clazz.createProperty("integer", PropertyTypeInternal.INTEGER);
    clazz.createProperty("string", PropertyTypeInternal.STRING);
    clazz.createProperty("boolean", PropertyTypeInternal.BOOLEAN);
    clazz.createProperty("long", PropertyTypeInternal.LONG);
    clazz.createProperty("float", PropertyTypeInternal.FLOAT);
    clazz.createProperty("double", PropertyTypeInternal.DOUBLE);
    clazz.createProperty("decimal", PropertyTypeInternal.DECIMAL);
    clazz.createProperty("date", PropertyTypeInternal.DATE);

    clazz.createProperty("byteList", PropertyTypeInternal.EMBEDDEDLIST, PropertyTypeInternal.BYTE);
    clazz.createProperty("integerList", PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.INTEGER);
    clazz.createProperty("longList", PropertyTypeInternal.EMBEDDEDLIST, PropertyTypeInternal.LONG);
    clazz.createProperty("stringList", PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.STRING);
    clazz.createProperty("floatList", PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.FLOAT);
    clazz.createProperty("doubleList", PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.DOUBLE);
    clazz.createProperty("decimalList", PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.DECIMAL);
    clazz.createProperty("booleanList", PropertyTypeInternal.EMBEDDEDLIST,
        PropertyTypeInternal.BOOLEAN);
    clazz.createProperty("dateList", PropertyTypeInternal.EMBEDDEDLIST, PropertyTypeInternal.DATE);

    clazz.createProperty("byteSet", PropertyTypeInternal.EMBEDDEDSET, PropertyTypeInternal.BYTE);
    clazz.createProperty("integerSet", PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.INTEGER);
    clazz.createProperty("longSet", PropertyTypeInternal.EMBEDDEDSET, PropertyTypeInternal.LONG);
    clazz.createProperty("stringSet", PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.STRING);
    clazz.createProperty("floatSet", PropertyTypeInternal.EMBEDDEDSET, PropertyTypeInternal.FLOAT);
    clazz.createProperty("doubleSet", PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.DOUBLE);
    clazz.createProperty("decimalSet", PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.DECIMAL);
    clazz.createProperty("booleanSet", PropertyTypeInternal.EMBEDDEDSET,
        PropertyTypeInternal.BOOLEAN);
    clazz.createProperty("dateSet", PropertyTypeInternal.EMBEDDEDSET, PropertyTypeInternal.DATE);

    clazz.createProperty("byteMap", PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.BYTE);
    clazz.createProperty("integerMap", PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.INTEGER);
    clazz.createProperty("longMap", PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.LONG);
    clazz.createProperty("stringMap", PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.STRING);
    clazz.createProperty("floatMap", PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.FLOAT);
    clazz.createProperty("doubleMap", PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.DOUBLE);
    clazz.createProperty("decimalMap", PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.DECIMAL);
    clazz.createProperty("booleanMap", PropertyTypeInternal.EMBEDDEDMAP,
        PropertyTypeInternal.BOOLEAN);
    clazz.createProperty("dateMap", PropertyTypeInternal.EMBEDDEDMAP, PropertyTypeInternal.DATE);
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
