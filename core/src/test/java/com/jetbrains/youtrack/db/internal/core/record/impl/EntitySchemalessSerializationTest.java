package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionAbstract;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.string.RecordSerializerStringAbstract;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class EntitySchemalessSerializationTest extends DbTestBase {

  protected RecordSerializer serializer;
  private RecordSerializer defaultSerializer;

  @Before
  public void before() {
    serializer = new RecordSerializerBinary();
    defaultSerializer = DatabaseSessionAbstract.getDefaultSerializer();
    DatabaseSessionAbstract.setDefaultSerializer(serializer);
  }

  @After
  public void after() {
    DatabaseSessionAbstract.setDefaultSerializer(defaultSerializer);
  }

  @Test
  public void testSimpleSerialization() {
    session.begin();
    var entity = session.newEntity();

    entity.setString("name", "name");
    entity.setInt("age", 20);
    entity.setShort("youngAge", (short) 20);
    entity.setLong("oldAge", (long) 20);
    entity.setFloat("heigth", 12.5f);
    entity.setDouble("bitHeigth", 12.5d);
    entity.setByte("class", (byte) 'C');
    entity.setBoolean("alive", true);
    entity.setDateTime("date", new Date());
    entity.setLink("recordId", new RecordId(10, 10));

    var res = serializer.toStream(session, (EntityImpl) entity);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertyNames(), entity.getPropertyNames());
    assertEquals(extr.getString("name"), entity.getString("name"));
    assertEquals(extr.getInt("age"), entity.getInt("age"));
    assertEquals(extr.getShort("youngAge"), entity.getShort("youngAge"));
    assertEquals(extr.getLong("oldAge"), entity.getLong("oldAge"));
    assertEquals(extr.getFloat("heigth"), entity.getFloat("heigth"));
    assertEquals(extr.getDouble("bitHeigth"), entity.getDouble("bitHeigth"));
    assertEquals(extr.getByte("class"), entity.getByte("class"));
    assertEquals(extr.getBoolean("alive"), entity.getBoolean("alive"));
    assertEquals(extr.getDateTime("date"), entity.getDateTime("date"));

    session.rollback();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testSimpleLiteralList() {
    session.begin();
    var entity = session.newEntity();
    List<String> strings = session.newEmbeddedList();
    strings.add("a");
    strings.add("b");
    strings.add("c");
    entity.setEmbeddedList("listStrings", strings);

    List<Short> shorts = session.newEmbeddedList();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    entity.setEmbeddedList("shorts", shorts);

    List<Long> longs = session.newEmbeddedList();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    entity.setEmbeddedList("longs", longs);

    List<Integer> ints = session.newEmbeddedList();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    entity.setEmbeddedList("integers", ints);

    List<Float> floats = session.newEmbeddedList();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    entity.setEmbeddedList("floats", floats);

    List<Double> doubles = session.newEmbeddedList();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    entity.setEmbeddedList("doubles", doubles);

    List<Date> dates = session.newEmbeddedList();
    dates.add(new Date());
    dates.add(new Date());
    dates.add(new Date());
    entity.setEmbeddedList("dates", dates);

    List<Byte> bytes = session.newEmbeddedList();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    entity.setEmbeddedList("bytes", bytes);

    List<Boolean> booleans = session.newEmbeddedList();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    entity.setEmbeddedList("booleans", booleans);

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
    entity.setEmbeddedList("listMixed", listMixed);

    var res = serializer.toStream(session, (EntityImpl) entity);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertyNames(), entity.getPropertyNames());
    assertEquals(extr.getEmbeddedList("listStrings"), entity.getEmbeddedList("listStrings"));
    assertEquals(extr.getEmbeddedList("integers"), entity.getEmbeddedList("integers"));
    assertEquals(extr.getEmbeddedList("doubles"), entity.getEmbeddedList("doubles"));
    assertEquals(extr.getEmbeddedList("dates"), entity.getEmbeddedList("dates"));
    assertEquals(extr.getEmbeddedList("bytes"), entity.getEmbeddedList("bytes"));
    assertEquals(extr.getEmbeddedList("booleans"), entity.getEmbeddedList("booleans"));
    assertEquals(extr.getEmbeddedList("listMixed"), entity.getEmbeddedList("listMixed"));

    session.rollback();
  }

  @Test
  public void testSimpleMapStringLiteral() {
    session.begin();
    var entity = session.newEntity();

    Map<String, String> mapString = session.newEmbeddedMap();
    mapString.put("key", "value");
    mapString.put("key1", "value1");
    entity.setEmbeddedMap("mapString", mapString);

    Map<String, Integer> mapInt = session.newEmbeddedMap();
    mapInt.put("key", 2);
    mapInt.put("key1", 3);
    entity.setEmbeddedMap("mapInt", mapInt);

    Map<String, Long> mapLong = session.newEmbeddedMap();
    mapLong.put("key", 2L);
    mapLong.put("key1", 3L);
    entity.setEmbeddedMap("mapLong", mapLong);

    Map<String, Short> shortMap = session.newEmbeddedMap();
    shortMap.put("key", (short) 2);
    shortMap.put("key1", (short) 3);
    entity.setEmbeddedMap("shortMap", shortMap);

    Map<String, Date> dateMap = session.newEmbeddedMap();
    dateMap.put("key", new Date());
    dateMap.put("key1", new Date());
    entity.setEmbeddedMap("dateMap", dateMap);

    Map<String, Float> floatMap = session.newEmbeddedMap();
    floatMap.put("key", 10f);
    floatMap.put("key1", 11f);
    entity.setEmbeddedMap("floatMap", floatMap);

    Map<String, Double> doubleMap = session.newEmbeddedMap();
    doubleMap.put("key", 10d);
    doubleMap.put("key1", 11d);
    entity.setEmbeddedMap("doubleMap", doubleMap);

    Map<String, Byte> bytesMap = session.newEmbeddedMap();
    bytesMap.put("key", (byte) 10);
    bytesMap.put("key1", (byte) 11);
    entity.setEmbeddedMap("bytesMap", bytesMap);

    var res = serializer.toStream(session, (EntityImpl) entity);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertyNames(), entity.getPropertyNames());
    assertEquals(extr.getEmbeddedMap("mapString"), entity.getEmbeddedMap("mapString"));
    assertEquals(extr.getEmbeddedMap("mapLong"), entity.getEmbeddedMap("mapLong"));
    assertEquals(extr.getEmbeddedMap("shortMap"), entity.getEmbeddedMap("shortMap"));
    assertEquals(extr.getEmbeddedMap("dateMap"), entity.getEmbeddedMap("dateMap"));
    assertEquals(extr.getEmbeddedMap("doubleMap"), entity.getEmbeddedMap("doubleMap"));
    assertEquals(extr.getEmbeddedMap("bytesMap"), entity.getEmbeddedMap("bytesMap"));

    session.rollback();
  }

  @Test
  public void testSimpleEmbeddedDoc() {
    session.begin();

    var document = (EntityImpl) session.newEntity();
    var embedded = (EntityImpl) session.newEmbeddedEntity();

    embedded.setProperty("name", "test");
    embedded.setProperty("surname", "something");
    document.setProperty("embed", embedded, PropertyType.EMBEDDED);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(document.getPropertiesCount(), extr.getPropertiesCount());
    EntityImpl emb = extr.getProperty("embed");
    assertNotNull(emb);
    assertEquals(emb.<String>getProperty("name"), embedded.getProperty("name"));
    assertEquals(emb.<String>getProperty("surname"), embedded.getProperty("surname"));
    session.rollback();
  }

  @Test
  public void testMapOfEmbeddedDocument() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();

    var embeddedInMap = (EntityImpl) session.newEmbeddedEntity();
    embeddedInMap.setProperty("name", "test");
    embeddedInMap.setProperty("surname", "something");
    Map<String, EntityImpl> map = entity.newEmbeddedMap("map");
    map.put("embedded", embeddedInMap);

    var res = serializer.toStream(session, entity);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    Map<String, Entity> mapS = extr.getEmbeddedMap("map");
    assertEquals(1, mapS.size());
    var emb = mapS.get("embedded");
    assertNotNull(emb);
    assertEquals(emb.<String>getProperty("name"), embeddedInMap.getProperty("name"));
    assertEquals(emb.<String>getProperty("surname"), embeddedInMap.getProperty("surname"));
    session.rollback();
  }

  @Test
  public void testCollectionOfEmbeddedDocument() {
    session.begin();
    var entity = (EntityImpl) session.newEntity();

    var embeddedInList = (EntityImpl) session.newEmbeddedEntity();
    embeddedInList.setProperty("name", "test");
    embeddedInList.setProperty("surname", "something");

    List<EntityImpl> embeddedList = entity.newEmbeddedList("embeddedList");
    embeddedList.add(embeddedInList);

    var embeddedInSet = (EntityImpl) session.newEmbeddedEntity();
    embeddedInSet.setProperty("name", "test1");
    embeddedInSet.setProperty("surname", "something2");

    Set<EntityImpl> embeddedSet = entity.newEmbeddedSet("embeddedSet");
    embeddedSet.add(embeddedInSet);

    var res = serializer.toStream(session, entity);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});


    List<Identifiable> ser = extr.getProperty("embeddedList");
    assertEquals(1, ser.size());
    var inList = ser.getFirst().getEntity(session);
    assertNotNull(inList);
    assertEquals(inList.<String>getProperty("name"), embeddedInList.getProperty("name"));
    assertEquals(inList.<String>getProperty("surname"), embeddedInList.getProperty("surname"));

    Set<Identifiable> setEmb = extr.getProperty("embeddedSet");
    assertEquals(1, setEmb.size());
    var inSet = setEmb.iterator().next().getEntity(session);
    assertNotNull(inSet);
    assertEquals(inSet.<String>getProperty("name"), embeddedInSet.getProperty("name"));
    assertEquals(inSet.<String>getProperty("surname"), embeddedInSet.getProperty("surname"));
    session.rollback();
  }

  @Test
  @Ignore
  public void testCsvGetTypeByValue() {
    var res = RecordSerializerStringAbstract.getTypeValue(session, "-");
    assertTrue(res instanceof String);
    res = RecordSerializerStringAbstract.getTypeValue(session, "-email-@gmail.com");
    assertTrue(res instanceof String);
  }
}
