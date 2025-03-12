package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.config.GlobalConfiguration;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializerFactory;
import java.util.Date;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
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

  public void beforeTest() throws Exception {
    DatabaseSessionAbstract.setDefaultSerializer(serializer);
    super.beforeTest();
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

  public void afterTest() {
    super.afterTest();
    DatabaseSessionAbstract.setDefaultSerializer(
        RecordSerializerFactory.instance()
            .getFormat(GlobalConfiguration.DB_ENTITY_SERIALIZER.getValueAsString()));
  }

  @Test
  public void testSimpleSerialization() {
    session.begin();
    var document = (EntityImpl) session.newEntity(simple);

    document.field(STRING_FIELD, NAME);
    document.field(INT_FIELD, 20);
    document.field(SHORT_FIELD, (short) 20);
    document.field(LONG_FIELD, (long) 20);
    document.field(FLOAT_NUMBER, 12.5f);
    document.field(DOUBLE_NUMBER, 12.5d);
    document.field(BYTE_FIELD, (byte) 'C');
    document.field(BOOLEAN_FIELD, true);
    document.field(DATE_FIELD, new Date());
    document.field(RECORDID_FIELD, new RecordId(10, 0));

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field(STRING_FIELD), document.field(STRING_FIELD));
    assertEquals(extr.<Object>field(INT_FIELD), document.field(INT_FIELD));
    assertEquals(extr.<Object>field(SHORT_FIELD), document.field(SHORT_FIELD));
    assertEquals(extr.<Object>field(LONG_FIELD), document.field(LONG_FIELD));
    assertEquals(extr.<Object>field(FLOAT_NUMBER), document.field(FLOAT_NUMBER));
    assertEquals(extr.<Object>field(DOUBLE_NUMBER), document.field(DOUBLE_NUMBER));
    assertEquals(extr.<Object>field(BYTE_FIELD), document.field(BYTE_FIELD));
    assertEquals(extr.<Object>field(BOOLEAN_FIELD), document.field(BOOLEAN_FIELD));
    assertEquals(extr.<Object>field(DATE_FIELD), document.field(DATE_FIELD));
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
    document.field(LIST_STRINGS, strings);

    List<Short> shorts = session.newEmbeddedList();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.field(LIST_SHORTS, shorts);

    List<Long> longs = session.newEmbeddedList();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.field(LIST_LONGS, longs);

    List<Integer> ints = session.newEmbeddedList();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.field(LIST_INTEGERS, ints);

    List<Float> floats = session.newEmbeddedList();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.field(LIST_FLOATS, floats);

    List<Double> doubles = session.newEmbeddedList();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.field(LIST_DOUBLES, doubles);

    List<Date> dates = session.newEmbeddedList();
    dates.add(new Date());
    dates.add(new Date());
    dates.add(new Date());
    document.field(LIST_DATES, dates);

    List<Byte> bytes = session.newEmbeddedList();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.field(LIST_BYTES, bytes);

    List<Boolean> booleans = session.newEmbeddedList();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.field(LIST_BOOLEANS, booleans);

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
    document.field(LIST_MIXED, listMixed);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field(LIST_STRINGS), document.field(LIST_STRINGS));
    assertEquals(extr.<Object>field(LIST_INTEGERS), document.field(LIST_INTEGERS));
    assertEquals(extr.<Object>field(LIST_DOUBLES), document.field(LIST_DOUBLES));
    assertEquals(extr.<Object>field(LIST_DATES), document.field(LIST_DATES));
    assertEquals(extr.<Object>field(LIST_BYTES), document.field(LIST_BYTES));
    assertEquals(extr.<Object>field(LIST_BOOLEANS), document.field(LIST_BOOLEANS));
    assertEquals(extr.<Object>field(LIST_MIXED), document.field(LIST_MIXED));
    session.rollback();
  }

  @Test
  public void testSimpleMapStringLiteral() {
    session.begin();
    var document = (EntityImpl) session.newEntity(embMapSimple);

    Map<String, String> mapString = session.newEmbeddedMap();
    mapString.put("key", "value");
    mapString.put("key1", "value1");
    document.field(MAP_STRING, mapString);

    Map<String, Integer> mapInt = session.newEmbeddedMap();
    mapInt.put("key", 2);
    mapInt.put("key1", 3);
    document.field(MAP_INT, mapInt);

    Map<String, Long> mapLong = session.newEmbeddedMap();
    mapLong.put("key", 2L);
    mapLong.put("key1", 3L);
    document.field(MAP_LONG, mapLong);

    Map<String, Short> shortMap = session.newEmbeddedMap();
    shortMap.put("key", (short) 2);
    shortMap.put("key1", (short) 3);
    document.field(MAP_SHORT, shortMap);

    Map<String, Date> dateMap = session.newEmbeddedMap();
    dateMap.put("key", new Date());
    dateMap.put("key1", new Date());
    document.field(MAP_DATE, dateMap);

    Map<String, Float> floatMap = session.newEmbeddedMap();
    floatMap.put("key", 10f);
    floatMap.put("key1", 11f);
    document.field(MAP_FLOAT, floatMap);

    Map<String, Double> doubleMap = session.newEmbeddedMap();
    doubleMap.put("key", 10d);
    doubleMap.put("key1", 11d);
    document.field(MAP_DOUBLE, doubleMap);

    Map<String, Byte> bytesMap = session.newEmbeddedMap();
    bytesMap.put("key", (byte) 10);
    bytesMap.put("key1", (byte) 11);
    document.field(MAP_BYTES, bytesMap);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field(MAP_STRING), document.field(MAP_STRING));
    assertEquals(extr.<Object>field(MAP_LONG), document.field(MAP_LONG));
    assertEquals(extr.<Object>field(MAP_SHORT), document.field(MAP_SHORT));
    assertEquals(extr.<Object>field(MAP_DATE), document.field(MAP_DATE));
    assertEquals(extr.<Object>field(MAP_DOUBLE), document.field(MAP_DOUBLE));
    assertEquals(extr.<Object>field(MAP_BYTES), document.field(MAP_BYTES));
    session.rollback();
  }

  @Test
  public void testSimpleEmbeddedDoc() {
    session.begin();
    var document = (EntityImpl) session.newEntity(simple);
    var embedded = (EntityImpl) session.newEmbeddedEntity(address);
    embedded.field(NAME, "test");
    embedded.field(NUMBER, 1);
    embedded.field(CITY, "aaa");
    document.field(EMBEDDED_FIELD, embedded);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(document.fields(), extr.fields());
    EntityImpl emb = extr.field(EMBEDDED_FIELD);
    assertNotNull(emb);
    assertEquals(emb.<Object>field(NAME), embedded.field(NAME));
    assertEquals(emb.<Object>field(NUMBER), embedded.field(NUMBER));
    assertEquals(emb.<Object>field(CITY), embedded.field(CITY));
    session.rollback();
  }

  @Test
  public void testUpdateBooleanWithPropertyTypeAny() {
    session.begin();
    var document = (EntityImpl) session.newEntity(simple);
    document.field(ANY_FIELD, false);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(document.fields(), extr.fields());
    assertEquals(false, extr.field(ANY_FIELD));

    extr.field(ANY_FIELD, false);

    res = serializer.toStream(session, extr);
    var extr2 = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(extr.fields(), extr2.fields());
    assertEquals(false, extr2.field(ANY_FIELD));
    session.rollback();
  }

  @Test
  public void simpleTypeKeepingTest() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.field("name", "test");

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    RecordInternal.unsetDirty(extr);
    extr.fromStream(res);
    assertEquals(PropertyType.STRING, extr.getPropertyType("name"));
    session.rollback();
  }
}
