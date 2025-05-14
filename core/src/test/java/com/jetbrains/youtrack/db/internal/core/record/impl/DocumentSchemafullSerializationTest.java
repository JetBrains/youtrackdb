package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public abstract class DocumentSchemafullSerializationTest extends BaseMemoryInternalDatabase {

  private static final String CITY = "city";
  private static final String NUMBER = "number";
  private static final String INT_FIELD = NUMBER;
  private static final String NAME = "name";
  private static final String MAP_BYTES = "bytesMap";
  private static final String MAP_DOUBLE = "doubleMap";
  private static final String MAP_FLOAT = "floatMap";
  private static final String MAP_DATE = "dateMap";
  private static final String MAP_SHORT = "shortMap";
  private static final String MAP_LONG = "mapLong";
  private static final String MAP_INT = "mapInt";
  private static final String MAP_STRING = "mapString";
  private static final String LIST_MIXED = "listMixed";
  private static final String LIST_BOOLEANS = "booleans";
  private static final String LIST_BYTES = "bytes";
  private static final String LIST_DATES = "dates";
  private static final String LIST_DOUBLES = "doubles";
  private static final String LIST_FLOATS = "floats";
  private static final String LIST_INTEGERS = "integers";
  private static final String LIST_LONGS = "longs";
  private static final String LIST_SHORTS = "shorts";
  private static final String LIST_STRINGS = "listStrings";
  private static final String SHORT_FIELD = "shortNumber";
  private static final String LONG_FIELD = "longNumber";
  private static final String STRING_FIELD = "stringField";
  private static final String FLOAT_NUMBER = "floatNumber";
  private static final String DOUBLE_NUMBER = "doubleNumber";
  private static final String BYTE_FIELD = "byteField";
  private static final String BOOLEAN_FIELD = "booleanField";
  private static final String DATE_FIELD = "dateField";
  private static final String RECORDID_FIELD = "recordField";
  private static final String EMBEDDED_FIELD = "embeddedField";
  private static final String ANY_FIELD = "anyField";

  private SchemaClass simple;
  private final RecordSerializer serializer;
  private SchemaClass embSimp;
  private SchemaClass address;
  private SchemaClass embMapSimple;

  public DocumentSchemafullSerializationTest(RecordSerializer serializer) {
    this.serializer = serializer;
  }

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();

    session.setSerializer(serializer);
    // databaseDocument.getMetadata().
    Schema schema = session.getMetadata().getSchema();
    address = schema.createAbstractClass("Address");
    address.createProperty(NAME, PropertyType.STRING);
    address.createProperty(NUMBER, PropertyType.INTEGER);
    address.createProperty(CITY, PropertyType.STRING);

    simple = schema.createClass("Simple");
    simple.createProperty(STRING_FIELD, PropertyType.STRING);
    simple.createProperty(INT_FIELD, PropertyType.INTEGER);
    simple.createProperty(SHORT_FIELD, PropertyType.SHORT);
    simple.createProperty(LONG_FIELD, PropertyType.LONG);
    simple.createProperty(FLOAT_NUMBER, PropertyType.FLOAT);
    simple.createProperty(DOUBLE_NUMBER, PropertyType.DOUBLE);
    simple.createProperty(BYTE_FIELD, PropertyType.BYTE);
    simple.createProperty(BOOLEAN_FIELD, PropertyType.BOOLEAN);
    simple.createProperty(DATE_FIELD, PropertyType.DATETIME);
    simple.createProperty(RECORDID_FIELD, PropertyType.LINK);
    simple.createProperty(EMBEDDED_FIELD, PropertyType.EMBEDDED, address);

    embSimp = schema.createClass("EmbeddedCollectionSimple");
    embSimp.createProperty(LIST_BOOLEANS, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_BYTES, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_DATES, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_DOUBLES, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_FLOATS, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_INTEGERS, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_LONGS, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_SHORTS, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_STRINGS, PropertyType.EMBEDDEDLIST);
    embSimp.createProperty(LIST_MIXED, PropertyType.EMBEDDEDLIST);

    embMapSimple = schema.createClass("EmbeddedMapSimple");
    embMapSimple.createProperty(MAP_BYTES, PropertyType.EMBEDDEDMAP);
    embMapSimple.createProperty(MAP_DATE, PropertyType.EMBEDDEDMAP);
    embMapSimple.createProperty(MAP_DOUBLE, PropertyType.EMBEDDEDMAP);
    embMapSimple.createProperty(MAP_FLOAT, PropertyType.EMBEDDEDMAP);
    embMapSimple.createProperty(MAP_INT, PropertyType.EMBEDDEDMAP);
    embMapSimple.createProperty(MAP_LONG, PropertyType.EMBEDDEDMAP);
    embMapSimple.createProperty(MAP_SHORT, PropertyType.EMBEDDEDMAP);
    embMapSimple.createProperty(MAP_STRING, PropertyType.EMBEDDEDMAP);

    var clazzEmbComp = schema.createClass("EmbeddedComplex");
    clazzEmbComp.createProperty("addresses", PropertyType.EMBEDDEDLIST, address);
    clazzEmbComp.createProperty("uniqueAddresses", PropertyType.EMBEDDEDSET, address);
    clazzEmbComp.createProperty("addressByStreet", PropertyType.EMBEDDEDMAP, address);
  }

  @Override
  public void afterTest() {
    session.setSerializer(RecordSerializerBinary.INSTANCE);

    super.afterTest();
  }

  @Test
  public void testSimpleSerialization() {
    session.begin();
    var document = (EntityImpl) session.newEntity(simple);

    document.setProperty(STRING_FIELD, NAME);
    document.setProperty(INT_FIELD, 20);
    document.setProperty(SHORT_FIELD, (short) 20);
    document.setProperty(LONG_FIELD, (long) 20);
    document.setProperty(FLOAT_NUMBER, 12.5f);
    document.setProperty(DOUBLE_NUMBER, 12.5d);
    document.setProperty(BYTE_FIELD, (byte) 'C');
    document.setProperty(BOOLEAN_FIELD, true);
    document.setProperty(DATE_FIELD, new Date());
    document.setProperty(RECORDID_FIELD, new RecordId(10, 0));

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty(STRING_FIELD), document.getProperty(STRING_FIELD));
    assertEquals(extr.<Object>getProperty(INT_FIELD), document.getProperty(INT_FIELD));
    assertEquals(extr.<Object>getProperty(SHORT_FIELD), document.getProperty(SHORT_FIELD));
    assertEquals(extr.<Object>getProperty(LONG_FIELD), document.getProperty(LONG_FIELD));
    assertEquals(extr.<Object>getProperty(FLOAT_NUMBER), document.getProperty(FLOAT_NUMBER));
    assertEquals(extr.<Object>getProperty(DOUBLE_NUMBER), document.getProperty(DOUBLE_NUMBER));
    assertEquals(extr.<Object>getProperty(BYTE_FIELD), document.getProperty(BYTE_FIELD));
    assertEquals(extr.<Object>getProperty(BOOLEAN_FIELD), document.getProperty(BOOLEAN_FIELD));
    assertEquals(extr.<Object>getProperty(DATE_FIELD), document.getProperty(DATE_FIELD));
    assertEquals(extr.getProperty(RECORDID_FIELD), document.getProperty(RECORDID_FIELD));
    session.rollback();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testSimpleLiteralList() {
    session.begin();
    var document = (EntityImpl) session.newEntity(embSimp);
    List<String> strings = session.newEmbeddedList();
    strings.add("a");
    strings.add("b");
    strings.add("c");
    document.setProperty(LIST_STRINGS, strings);

    List<Short> shorts = session.newEmbeddedList();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.setProperty(LIST_SHORTS, shorts);

    List<Long> longs = session.newEmbeddedList();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.setProperty(LIST_LONGS, longs);

    List<Integer> ints = session.newEmbeddedList();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.setProperty(LIST_INTEGERS, ints);

    List<Float> floats = session.newEmbeddedList();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.setProperty(LIST_FLOATS, floats);

    List<Double> doubles = session.newEmbeddedList();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.setProperty(LIST_DOUBLES, doubles);

    List<Date> dates = session.newEmbeddedList();
    dates.add(new Date());
    dates.add(new Date());
    dates.add(new Date());
    document.setProperty(LIST_DATES, dates);

    List<Byte> bytes = session.newEmbeddedList();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.setProperty(LIST_BYTES, bytes);

    List<Boolean> booleans = session.newEmbeddedList();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.setProperty(LIST_BOOLEANS, booleans);

    List listMixed = session.newEmbeddedList();
    listMixed.add(true);
    listMixed.add(1);
    listMixed.add((long) 5);
    listMixed.add((short) 2);
    listMixed.add(4.0f);
    listMixed.add(7.0D);
    listMixed.add("hello");
    listMixed.add(new Date());
    listMixed.add((byte) 10);
    document.setProperty(LIST_MIXED, listMixed);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty(LIST_STRINGS), document.getProperty(LIST_STRINGS));
    assertEquals(extr.<Object>getProperty(LIST_INTEGERS), document.getProperty(LIST_INTEGERS));
    assertEquals(extr.<Object>getProperty(LIST_DOUBLES), document.getProperty(LIST_DOUBLES));
    assertEquals(extr.<Object>getProperty(LIST_DATES), document.getProperty(LIST_DATES));
    assertEquals(extr.<Object>getProperty(LIST_BYTES), document.getProperty(LIST_BYTES));
    assertEquals(extr.<Object>getProperty(LIST_BOOLEANS), document.getProperty(LIST_BOOLEANS));
    assertEquals(extr.<Object>getProperty(LIST_MIXED), document.getProperty(LIST_MIXED));
    session.rollback();
  }

  @Test
  public void testSimpleMapStringLiteral() {
    session.begin();
    var document = (EntityImpl) session.newEntity(embMapSimple);

    Map<String, String> mapString = session.newEmbeddedMap();
    mapString.put("key", "value");
    mapString.put("key1", "value1");
    document.setProperty(MAP_STRING, mapString);

    Map<String, Integer> mapInt = session.newEmbeddedMap();
    mapInt.put("key", 2);
    mapInt.put("key1", 3);
    document.setProperty(MAP_INT, mapInt);

    Map<String, Long> mapLong = session.newEmbeddedMap();
    mapLong.put("key", 2L);
    mapLong.put("key1", 3L);
    document.setProperty(MAP_LONG, mapLong);

    Map<String, Short> shortMap = session.newEmbeddedMap();
    shortMap.put("key", (short) 2);
    shortMap.put("key1", (short) 3);
    document.setProperty(MAP_SHORT, shortMap);

    Map<String, Date> dateMap = session.newEmbeddedMap();
    dateMap.put("key", new Date());
    dateMap.put("key1", new Date());
    document.setProperty(MAP_DATE, dateMap);

    Map<String, Float> floatMap = session.newEmbeddedMap();
    floatMap.put("key", 10f);
    floatMap.put("key1", 11f);
    document.setProperty(MAP_FLOAT, floatMap);

    Map<String, Double> doubleMap = session.newEmbeddedMap();
    doubleMap.put("key", 10d);
    doubleMap.put("key1", 11d);
    document.setProperty(MAP_DOUBLE, doubleMap);

    Map<String, Byte> bytesMap = session.newEmbeddedMap();
    bytesMap.put("key", (byte) 10);
    bytesMap.put("key1", (byte) 11);
    document.setProperty(MAP_BYTES, bytesMap);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty(MAP_STRING), document.getProperty(MAP_STRING));
    assertEquals(extr.<Object>getProperty(MAP_LONG), document.getProperty(MAP_LONG));
    assertEquals(extr.<Object>getProperty(MAP_SHORT), document.getProperty(MAP_SHORT));
    assertEquals(extr.<Object>getProperty(MAP_DATE), document.getProperty(MAP_DATE));
    assertEquals(extr.<Object>getProperty(MAP_DOUBLE), document.getProperty(MAP_DOUBLE));
    assertEquals(extr.<Object>getProperty(MAP_BYTES), document.getProperty(MAP_BYTES));
    session.rollback();
  }

  @Test
  public void testSimpleEmbeddedDoc() {
    session.begin();
    var document = (EntityImpl) session.newEntity(simple);
    var embedded = (EntityImpl) session.newEmbeddedEntity(address);
    embedded.setProperty(NAME, "test");
    embedded.setProperty(NUMBER, 1);
    embedded.setProperty(CITY, "aaa");
    document.setProperty(EMBEDDED_FIELD, embedded);

    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(document.getPropertiesCount(), extr.getPropertiesCount());
    EntityImpl emb = extr.getProperty(EMBEDDED_FIELD);
    assertNotNull(emb);
    assertEquals(emb.<Object>getProperty(NAME), embedded.getProperty(NAME));
    assertEquals(emb.<Object>getProperty(NUMBER), embedded.getProperty(NUMBER));
    assertEquals(emb.<Object>getProperty(CITY), embedded.getProperty(CITY));
    session.rollback();
  }

  @Test
  public void testUpdateBooleanWithPropertyTypeAny() {
    session.begin();
    var document = (EntityImpl) session.newEntity(simple);
    document.setProperty(ANY_FIELD, false);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(document.getPropertiesCount(), extr.getPropertiesCount());
    assertEquals(false, extr.getProperty(ANY_FIELD));

    extr.setProperty(ANY_FIELD, false);

    res = serializer.toStream(session, extr);
    var extr2 = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr2, new String[]{});
    assertEquals(extr.getPropertiesCount(), extr2.getPropertiesCount());
    assertEquals(false, extr2.getProperty(ANY_FIELD));
    session.rollback();
  }

  @Test
  public void simpleTypeKeepingTest() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.setProperty("name", "test");

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    final var rec = (RecordAbstract) extr;
    rec.unsetDirty();
    extr.fromStream(res);
    assertEquals(PropertyType.STRING, extr.getPropertyType("name"));
    session.rollback();
  }
}
