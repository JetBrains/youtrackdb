package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.YourTracks;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.Assertions;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class EntitySchemalessBinarySerializationTest extends DbTestBase {

  @Parameters
  public static Collection<Object[]> generateParams() {
    List<Object[]> params = new ArrayList<>();
    // first we want to run tests for all registreted serializers, and then for two network
    // serializers
    // testig for each serializer type has its own index
    for (byte i = 0; i < RecordSerializerBinary.INSTANCE.getNumberOfSupportedVersions() + 2; i++) {
      params.add(new Object[]{i});
    }
    return params;
  }

  protected RecordSerializer serializer;
  private final byte serializerVersion;

  // first to test for all registreted serializers , then for network serializers
  public EntitySchemalessBinarySerializationTest(byte serializerVersion) {
    var numOfRegistretedSerializers =
        RecordSerializerBinary.INSTANCE.getNumberOfSupportedVersions();
    if (serializerVersion < numOfRegistretedSerializers) {
      serializer = new RecordSerializerBinary(serializerVersion);
    }

    this.serializerVersion = serializerVersion;
  }

  @Test
  public void testSimpleSerialization() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    document.setProperty("name", "name");
    document.setProperty("age", 20);
    document.setProperty("youngAge", (short) 20);
    document.setProperty("oldAge", (long) 20);
    document.setProperty("heigth", 12.5f);
    document.setProperty("bitHeigth", 12.5d);
    document.setProperty("class", (byte) 'C');
    document.setProperty("nullField", null);
    document.setProperty("alive", true);
    document.setProperty("dateTime", new Date());
    document.setProperty("bigNumber",
        new BigDecimal("43989872423376487952454365232141525434.32146432321442534"));
    var bag = new RidBag(session);
    bag.add(new RecordId(1, 1));
    bag.add(new RecordId(2, 2));
    // document.field("ridBag", bag);
    var c = Calendar.getInstance();
    Object propertyValue1 = c.getTime();
    document.setProperty("date", propertyValue1, PropertyType.DATE);
    var c1 = Calendar.getInstance();
    c1.set(Calendar.MILLISECOND, 0);
    c1.set(Calendar.SECOND, 0);
    c1.set(Calendar.MINUTE, 0);
    c1.set(Calendar.HOUR_OF_DAY, 0);
    Object propertyValue = c1.getTime();
    document.setProperty("date1", propertyValue, PropertyType.DATE);

    var byteValue = new byte[10];
    Arrays.fill(byteValue, (byte) 10);
    document.setProperty("bytes", byteValue);

    document.setProperty("utf8String", "A" + "ê" + "ñ" + "ü" + "C");
    document.setProperty("recordId", new RecordId(10, 10));

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    c.set(Calendar.MILLISECOND, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.HOUR_OF_DAY, 0);

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty("name"), document.getProperty("name"));
    assertEquals(extr.<Object>getProperty("age"), document.getProperty("age"));
    assertEquals(extr.<Object>getProperty("youngAge"), document.getProperty("youngAge"));
    assertEquals(extr.<Object>getProperty("oldAge"), document.getProperty("oldAge"));
    assertEquals(extr.<Object>getProperty("heigth"), document.getProperty("heigth"));
    assertEquals(extr.<Object>getProperty("bitHeigth"), document.getProperty("bitHeigth"));
    assertEquals(extr.<Object>getProperty("class"), document.getProperty("class"));
    // TODO fix char management issue:#2427
    // assertEquals(document.field("character"), extr.field("character"));
    assertEquals(extr.<Object>getProperty("alive"), document.getProperty("alive"));
    assertEquals(extr.<Object>getProperty("dateTime"), document.getProperty("dateTime"));
    assertEquals(extr.getProperty("date"), c.getTime());
    assertEquals(extr.getProperty("date1"), c1.getTime());
    //    assertEquals(extr.<String>field("bytes"), document.field("bytes"));
    Assertions.assertThat(extr.<Object>getProperty("bytes")).isEqualTo(
        document.getProperty("bytes"));
    assertEquals(extr.<String>getProperty("utf8String"), document.getProperty("utf8String"));
    assertEquals(extr.<Object>getProperty("recordId"), document.getProperty("recordId"));
    assertEquals(extr.<Object>getProperty("bigNumber"), document.getProperty("bigNumber"));
    assertNull(extr.getProperty("nullField"));
    session.rollback();

  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  @Test
  public void testSimpleLiteralList() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    List<String> strings = session.newEmbeddedList();
    strings.add("a");
    strings.add("b");
    strings.add("c");
    document.setProperty("listStrings", strings);

    List<Short> shorts = session.newEmbeddedList();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.setProperty("shorts", shorts);

    List<Long> longs = session.newEmbeddedList();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.setProperty("longs", longs);

    List<Integer> ints = session.newEmbeddedList();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.setProperty("integers", ints);

    List<Float> floats = session.newEmbeddedList();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.setProperty("floats", floats);

    List<Double> doubles = session.newEmbeddedList();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.setProperty("doubles", doubles);

    List<Date> dates = session.newEmbeddedList();
    dates.add(new Date());
    dates.add(new Date());
    dates.add(new Date());
    document.setProperty("dates", dates);

    List<Byte> bytes = session.newEmbeddedList();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.setProperty("bytes", bytes);

    List<Boolean> booleans = session.newEmbeddedList();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.setProperty("booleans", booleans);

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
    document.setProperty("listMixed", listMixed);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty("listStrings"), document.getProperty("listStrings"));
    assertEquals(extr.<Object>getProperty("integers"), document.getProperty("integers"));
    assertEquals(extr.<Object>getProperty("doubles"), document.getProperty("doubles"));
    assertEquals(extr.<Object>getProperty("dates"), document.getProperty("dates"));
    assertEquals(extr.<Object>getProperty("bytes"), document.getProperty("bytes"));
    assertEquals(extr.<Object>getProperty("booleans"), document.getProperty("booleans"));
    assertEquals(extr.<Object>getProperty("listMixed"), document.getProperty("listMixed"));
    session.rollback();
  }

  @SuppressWarnings({"rawtypes", "unchecked", "OverwrittenKey"})
  @Test
  public void testSimpleLiteralSet() throws InterruptedException {
    try (var ctx = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute(
          "create database testSimpleLiteralSet memory users(admin identified by 'adminpwd' role"
              + " admin)");
      try (var session = (DatabaseSessionEmbedded) ctx.open("testSimpleLiteralSet", "admin",
          "adminpwd")) {
        session.begin();
        var document = (EntityImpl) session.newEntity();
        Set<String> strings = session.newEmbeddedSet();
        strings.add("a");
        strings.add("b");
        strings.add("c");
        document.setProperty("listStrings", strings);

        Set<Short> shorts = session.newEmbeddedSet();
        shorts.add((short) 1);
        shorts.add((short) 2);
        shorts.add((short) 3);
        document.setProperty("shorts", shorts);

        Set<Long> longs = session.newEmbeddedSet();
        longs.add((long) 1);
        longs.add((long) 2);
        longs.add((long) 3);
        document.setProperty("longs", longs);

        Set<Integer> ints = session.newEmbeddedSet();
        ints.add(1);
        ints.add(2);
        ints.add(3);
        document.setProperty("integers", ints);

        Set<Float> floats = session.newEmbeddedSet();
        floats.add(1.1f);
        floats.add(2.2f);
        floats.add(3.3f);
        document.setProperty("floats", floats);

        Set<Double> doubles = session.newEmbeddedSet();
        doubles.add(1.1);
        doubles.add(2.2);
        doubles.add(3.3);
        document.setProperty("doubles", doubles);

        Set<Date> dates = session.newEmbeddedSet();
        dates.add(new Date());
        Thread.sleep(1);
        dates.add(new Date());
        Thread.sleep(1);
        dates.add(new Date());
        document.setProperty("dates", dates);

        Set<Byte> bytes = session.newEmbeddedSet();
        bytes.add((byte) 0);
        bytes.add((byte) 1);
        bytes.add((byte) 3);
        document.setProperty("bytes", bytes);

        Set<Boolean> booleans = session.newEmbeddedSet();
        booleans.add(true);
        booleans.add(false);
        booleans.add(false);
        document.setProperty("booleans", booleans);

        Set listMixed = session.newEmbeddedSet();
        listMixed.add(true);
        listMixed.add(1);
        listMixed.add((long) 5);
        listMixed.add((short) 2);
        listMixed.add(4.0f);
        listMixed.add(7.0D);
        listMixed.add("hello");
        listMixed.add(new Date());
        listMixed.add((byte) 10);
        document.setProperty("listMixed", listMixed);

        var res = serializer.toStream(session, document);
        var extr = (EntityImpl) session.newEntity();
        serializer.fromStream(session, res, extr, new String[]{});

        assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
        assertEquals(extr.<Object>getProperty("listStrings"), document.getProperty("listStrings"));
        assertEquals(extr.<Object>getProperty("integers"), document.getProperty("integers"));
        assertEquals(extr.<Object>getProperty("doubles"), document.getProperty("doubles"));
        assertEquals(extr.<Object>getProperty("dates"), document.getProperty("dates"));
        assertEquals(extr.<Object>getProperty("bytes"), document.getProperty("bytes"));
        assertEquals(extr.<Object>getProperty("booleans"), document.getProperty("booleans"));
        assertEquals(extr.<Object>getProperty("listMixed"), document.getProperty("listMixed"));
        session.rollback();
      }
    }
  }

  @Test
  public void testLinkCollections() {
    try (var ctx = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var session = (DatabaseSessionEmbedded) ctx.open("test", "admin", "adminpwd")) {
        session.begin();
        var document = (EntityImpl) session.newEntity();
        var linkSet = session.newLinkSet();
        linkSet.add(new RecordId(10, 20));
        linkSet.add(new RecordId(10, 21));
        linkSet.add(new RecordId(10, 22));
        linkSet.add(new RecordId(11, 22));
        document.setProperty("linkSet", linkSet, PropertyType.LINKSET);

        var linkList = session.newLinkList();
        linkList.add(new RecordId(10, 20));
        linkList.add(new RecordId(10, 21));
        linkList.add(new RecordId(10, 22));
        linkList.add(new RecordId(11, 22));
        document.setProperty("linkList", linkList, PropertyType.LINKLIST);
        var res = serializer.toStream(session, document);

        var extr = (EntityImpl) session.newEntity();
        serializer.fromStream(session, res, extr, new String[]{});

        assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
        assertTrue(extr.getLinkSet("linkSet").containsAll(document.getLinkSet("linkSet")));
        assertEquals(extr.getLinkList("linkList"), document.getLinkList("linkList"));
        session.rollback();
      }
      ctx.drop("test");
    }
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
    assertEquals(emb.<Object>getProperty("name"), embedded.getProperty("name"));
    assertEquals(emb.<Object>getProperty("surname"), embedded.getProperty("surname"));
    session.rollback();
  }

  @Test
  public void testSimpleMapStringLiteral() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    Map<String, String> mapString = session.newEmbeddedMap();
    mapString.put("key", "value");
    mapString.put("key1", "value1");
    document.setProperty("mapString", mapString);

    Map<String, Integer> mapInt = session.newEmbeddedMap();
    mapInt.put("key", 2);
    mapInt.put("key1", 3);
    document.setProperty("mapInt", mapInt);

    Map<String, Long> mapLong = session.newEmbeddedMap();
    mapLong.put("key", 2L);
    mapLong.put("key1", 3L);
    document.setProperty("mapLong", mapLong);

    Map<String, Short> shortMap = session.newEmbeddedMap();
    shortMap.put("key", (short) 2);
    shortMap.put("key1", (short) 3);
    document.setProperty("shortMap", shortMap);

    Map<String, Date> dateMap = session.newEmbeddedMap();
    dateMap.put("key", new Date());
    dateMap.put("key1", new Date());
    document.setProperty("dateMap", dateMap);

    Map<String, Float> floatMap = session.newEmbeddedMap();
    floatMap.put("key", 10f);
    floatMap.put("key1", 11f);
    document.setProperty("floatMap", floatMap);

    Map<String, Double> doubleMap = session.newEmbeddedMap();
    doubleMap.put("key", 10d);
    doubleMap.put("key1", 11d);
    document.setProperty("doubleMap", doubleMap);

    Map<String, Byte> bytesMap = session.newEmbeddedMap();
    bytesMap.put("key", (byte) 10);
    bytesMap.put("key1", (byte) 11);
    document.setProperty("bytesMap", bytesMap);

    Map<String, String> mapWithNulls = session.newEmbeddedMap();
    mapWithNulls.put("key", "dddd");
    mapWithNulls.put("key1", null);
    document.setProperty("bytesMap", mapWithNulls);

    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty("mapString"), document.getProperty("mapString"));
    assertEquals(extr.<Object>getProperty("mapLong"), document.getProperty("mapLong"));
    assertEquals(extr.<Object>getProperty("shortMap"), document.getProperty("shortMap"));
    assertEquals(extr.<Object>getProperty("dateMap"), document.getProperty("dateMap"));
    assertEquals(extr.<Object>getProperty("doubleMap"), document.getProperty("doubleMap"));
    assertEquals(extr.<Object>getProperty("bytesMap"), document.getProperty("bytesMap"));
    session.rollback();
  }

  @Test
  public void testlistOfList() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    List<List<String>> list = session.newEmbeddedList();
    List<String> ls = session.newEmbeddedList();
    ls.add("test1");
    ls.add("test2");
    list.add(ls);
    document.setProperty("complexList", list);

    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty("complexList"), document.getProperty("complexList"));
    session.rollback();
  }

  @Test
  public void testEmbeddedListOfEmbeddedMap() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    List<Map<String, String>> coll = session.newEmbeddedList();
    Map<String, String> map = session.newEmbeddedMap();
    map.put("first", "something");
    map.put("second", "somethingElse");
    Map<String, String> map2 = session.newEmbeddedMap();
    map2.put("first", "something");
    map2.put("second", "somethingElse");
    coll.add(map);
    coll.add(map2);
    document.setProperty("list", coll);
    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty("list"), document.getProperty("list"));
    session.rollback();
  }

  @Test
  public void testMapOfEmbeddedEntity() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    var embeddedInMap = (EntityImpl) session.newEmbeddedEntity();
    embeddedInMap.setProperty("name", "test");
    embeddedInMap.setProperty("surname", "something");
    Map<String, EntityImpl> map = session.newEmbeddedMap();
    map.put("embedded", embeddedInMap);
    document.newEmbeddedMap("map").putAll(map);

    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    Map<String, EntityImpl> mapS = extr.getProperty("map");
    assertEquals(1, mapS.size());
    var emb = mapS.get("embedded");
    assertNotNull(emb);
    assertEquals(emb.<Object>getProperty("name"), embeddedInMap.getProperty("name"));
    assertEquals(emb.<Object>getProperty("surname"), embeddedInMap.getProperty("surname"));
    session.rollback();
  }

  @Test
  public void testMapOfLink() {
    // needs a database because of the lazy loading
    try (var ctx = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var session = (DatabaseSessionEmbedded) ctx.open("test", "admin", "adminpwd")) {
        session.begin();
        var document = (EntityImpl) session.newEntity();

        var map = session.newLinkMap();
        map.put("link", new RecordId(0, 0));
        document.setProperty("map", map, PropertyType.LINKMAP);

        var res = serializer.toStream(session, document);

        var extr = (EntityImpl) session.newEntity();
        serializer.fromStream(session, res, extr, new String[]{});
        assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
        assertEquals(extr.<Object>getProperty("map"), document.getProperty("map"));
        session.rollback();
      }
      ctx.drop("test");
    }
  }

  @Test
  public void testDocumentSimple() {
    try (var ctx = YourTracks.embedded(DbTestBase.getBaseDirectoryPath(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var session = (DatabaseSessionEmbedded) ctx.open("test", "admin", "adminpwd")) {
        session.createClass("TestClass");

        session.begin();
        var document = (EntityImpl) session.newEntity("TestClass");
        document.setProperty("test", "test");
        var res = serializer.toStream(session, document);

        var extr = (EntityImpl) session.newEntity();
        serializer.fromStream(session, res, extr, new String[]{});

        assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
        assertEquals(extr.<Object>getProperty("test"), document.getProperty("test"));
        session.rollback();
      }
      ctx.drop("test");
    }
  }


  @Test(expected = SchemaException.class)
  public void testSetOfWrongData() {
    session.executeInTx(transaction -> {
      var document = (EntityImpl) session.newEntity();

      var embeddedSet = session.newEmbeddedSet();
      embeddedSet.add(new WrongData());
      document.setProperty("embeddedSet", embeddedSet, PropertyType.EMBEDDEDSET);
    });
  }

  @Test(expected = DatabaseException.class)
  public void testListOfWrongData() {
    session.executeInTx(transaction -> {
      var document = (EntityImpl) session.newEntity();

      List<Object> embeddedList = new ArrayList<>();
      embeddedList.add(new WrongData());
      document.setProperty("embeddedList", embeddedList, PropertyType.EMBEDDEDLIST);

      serializer.toStream(session, document);
    });
  }

  @Test(expected = SchemaException.class)
  public void testMapOfWrongData() {
    session.executeInTx(transaction -> {
      var document = (EntityImpl) session.newEntity();

      Map<String, Object> embeddedMap = session.newEmbeddedMap();
      embeddedMap.put("name", new WrongData());
      document.setProperty("embeddedMap", embeddedMap, PropertyType.EMBEDDEDMAP);
    });
  }

  @Test
  public void testCollectionOfEmbeddedDocument() {
    session.begin();

    var document = (EntityImpl) session.newEntity();

    var embeddedInList = (EntityImpl) session.newEmbeddedEntity();
    embeddedInList.setProperty("name", "test");
    embeddedInList.setProperty("surname", "something");

    var embeddedInList2 = (EntityImpl) session.newEmbeddedEntity();
    embeddedInList2.setProperty("name", "test1");
    embeddedInList2.setProperty("surname", "something2");

    List<EntityImpl> embeddedList = new ArrayList<>();
    embeddedList.add(embeddedInList);
    embeddedList.add(embeddedInList2);
    embeddedList.add(null);
    embeddedList.add((EntityImpl) session.newEmbeddedEntity());
    document.newEmbeddedList("embeddedList").addAll(embeddedList);

    var embeddedInSet = (EntityImpl) session.newEmbeddedEntity();
    embeddedInSet.setProperty("name", "test2");
    embeddedInSet.setProperty("surname", "something3");

    var embeddedInSet2 = (EntityImpl) session.newEmbeddedEntity();
    embeddedInSet2.setProperty("name", "test5");
    embeddedInSet2.setProperty("surname", "something6");

    Set<EntityImpl> embeddedSet = new HashSet<>();
    embeddedSet.add(embeddedInSet);
    embeddedSet.add(embeddedInSet2);
    embeddedSet.add((EntityImpl) session.newEmbeddedEntity());
    document.newEmbeddedSet("embeddedSet").addAll(embeddedSet);

    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    List<EntityImpl> ser = extr.getProperty("embeddedList");
    assertEquals(4, ser.size());
    assertNotNull(ser.get(0));
    assertNotNull(ser.get(1));
    assertNull(ser.get(2));
    assertNotNull(ser.get(3));
    var inList = ser.get(0);
    assertNotNull(inList);
    assertEquals(inList.<Object>getProperty("name"), embeddedInList.getProperty("name"));
    assertEquals(inList.<Object>getProperty("surname"), embeddedInList.getProperty("surname"));

    Set<EntityImpl> setEmb = extr.getProperty("embeddedSet");
    assertEquals(3, setEmb.size());
    var ok = false;
    for (var inSet : setEmb) {
      assertNotNull(inSet);
      if (embeddedInSet.getProperty("name").equals(inSet.getProperty("name"))) {
        if (embeddedInSet.getProperty("surname").equals(inSet.getProperty("surname"))) {
          ok = true;
        }
      }
    }
    assertTrue("not found record in the set after serilize", ok);
    session.rollback();
  }

  @Test
  public void testFieldNames() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.properties("a", 1, "b", 2, "c", 3);
    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    final var fields = extr.propertyNames();

    assertNotNull(fields);
    assertEquals(3, fields.length);
    assertEquals("a", fields[0]);
    assertEquals("b", fields[1]);
    assertEquals("c", fields[2]);
    session.rollback();
  }

  @Test
  public void testFieldNamesRaw() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.properties("a", 1, "b", 2, "c", 3);
    var res = serializer.toStream(session, document);
    final var fields = serializer.getFieldNames(session, document, res);

    assertNotNull(fields);
    assertEquals(3, fields.length);
    assertEquals("a", fields[0]);
    assertEquals("b", fields[1]);
    assertEquals("c", fields[2]);
    session.rollback();
  }

  @Test
  public void testPartial() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.setProperty("name", "name");
    document.setProperty("age", 20);
    document.setProperty("youngAge", (short) 20);
    document.setProperty("oldAge", (long) 20);

    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{"name", "age"});

    assertEquals(document.getProperty("name"), extr.<Object>getProperty("name"));
    assertEquals(document.<Object>getProperty("age"), extr.getProperty("age"));

    assertNull(extr.getProperty("youngAge"));
    assertNull(extr.getProperty("oldAge"));
    session.rollback();
  }

  @Test
  public void testWithRemove() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.setProperty("name", "name");
    document.setProperty("age", 20);
    document.setProperty("youngAge", (short) 20);
    document.setProperty("oldAge", (long) 20);
    document.removeProperty("oldAge");

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(document.getProperty("name"), extr.<Object>getProperty("name"));
    assertEquals(document.<Object>getProperty("age"), extr.getProperty("age"));
    assertEquals(document.<Object>getProperty("youngAge"), extr.getProperty("youngAge"));
    assertNull(extr.getProperty("oldAge"));
    session.rollback();
  }

  @Test
  public void testPartialCustom() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.setProperty("name", "name");
    document.setProperty("age", 20);
    document.setProperty("youngAge", (short) 20);
    document.setProperty("oldAge", (long) 20);

    var res = serializer.toStream(session, document);

    var extr = (EntityImpl) session.newEntity();
    extr.unsetDirty();
    extr.fromStream(res);

    extr.recordSerializer = serializer;

    assertEquals(document.getProperty("name"), extr.<Object>getProperty("name"));
    assertEquals(document.<Object>getProperty("age"), extr.getProperty("age"));
    assertEquals(document.<Object>getProperty("youngAge"), extr.getProperty("youngAge"));
    assertEquals(document.<Object>getProperty("oldAge"), extr.getProperty("oldAge"));

    assertEquals(document.propertyNames().length, extr.propertyNames().length);
    session.rollback();
  }

  @Test
  public void testListOfMapsWithNull() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    var lista = document.newEmbeddedList("list");
    var mappa = session.newEmbeddedMap();

    mappa.put("prop1", "val1");
    mappa.put("prop2", null);
    lista.add(mappa);

    mappa = session.newEmbeddedMap();
    mappa.put("prop", "val");
    lista.add(mappa);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) session.newEntity();
    serializer.fromStream(session, res, extr, new String[]{});

    assertEquals(extr.getPropertiesCount(), document.getPropertiesCount());
    assertEquals(extr.<Object>getProperty("list"), document.getProperty("list"));
    session.rollback();
  }

  private static class WrongData {

  }
}
