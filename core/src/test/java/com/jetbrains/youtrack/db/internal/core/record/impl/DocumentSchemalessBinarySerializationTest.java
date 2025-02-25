package com.jetbrains.youtrack.db.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.YouTrackDB;
import com.jetbrains.youtrack.db.api.config.YouTrackDBConfig;
import com.jetbrains.youtrack.db.api.exception.DatabaseException;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.DbTestBase;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordInternal;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.RecordSerializer;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerBinary;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkBase;
import com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary.RecordSerializerNetworkV37;
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
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class DocumentSchemalessBinarySerializationTest extends DbTestBase {

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
  public DocumentSchemalessBinarySerializationTest(byte serializerVersion) {
    var numOfRegistretedSerializers =
        RecordSerializerBinary.INSTANCE.getNumberOfSupportedVersions();
    if (serializerVersion < numOfRegistretedSerializers) {
      serializer = new RecordSerializerBinary(serializerVersion);
    } else if (serializerVersion == numOfRegistretedSerializers) {
      serializer = new RecordSerializerNetworkBase();
    } else if (serializerVersion == numOfRegistretedSerializers + 1) {
      serializer = new RecordSerializerNetworkV37();
    }

    this.serializerVersion = serializerVersion;
  }

  @Before
  public void createSerializer() {
    // we want new instance before method only for network serializers
    if (serializerVersion == RecordSerializerBinary.INSTANCE.getNumberOfSupportedVersions()) {
      serializer = new RecordSerializerNetworkBase();
    } else if (serializerVersion
        == RecordSerializerBinary.INSTANCE.getNumberOfSupportedVersions() + 1) {
      serializer = new RecordSerializerNetworkV37();
    }
  }

  @Test
  public void testSimpleSerialization() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);
    document.field("heigth", 12.5f);
    document.field("bitHeigth", 12.5d);
    document.field("class", (byte) 'C');
    document.field("nullField", null);
    document.field("alive", true);
    document.field("dateTime", new Date());
    document.field(
        "bigNumber", new BigDecimal("43989872423376487952454365232141525434.32146432321442534"));
    var bag = new RidBag(session);
    bag.add(new RecordId(1, 1));
    bag.add(new RecordId(2, 2));
    // document.field("ridBag", bag);
    var c = Calendar.getInstance();
    document.field("date", c.getTime(), PropertyType.DATE);
    var c1 = Calendar.getInstance();
    c1.set(Calendar.MILLISECOND, 0);
    c1.set(Calendar.SECOND, 0);
    c1.set(Calendar.MINUTE, 0);
    c1.set(Calendar.HOUR_OF_DAY, 0);
    document.field("date1", c1.getTime(), PropertyType.DATE);

    var byteValue = new byte[10];
    Arrays.fill(byteValue, (byte) 10);
    document.field("bytes", byteValue);

    document.field("utf8String", "A" + "ê" + "ñ" + "ü" + "C");
    document.field("recordId", new RecordId(10, 10));

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});

    c.set(Calendar.MILLISECOND, 0);
    c.set(Calendar.SECOND, 0);
    c.set(Calendar.MINUTE, 0);
    c.set(Calendar.HOUR_OF_DAY, 0);

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("name"), document.field("name"));
    assertEquals(extr.<Object>field("age"), document.field("age"));
    assertEquals(extr.<Object>field("youngAge"), document.field("youngAge"));
    assertEquals(extr.<Object>field("oldAge"), document.field("oldAge"));
    assertEquals(extr.<Object>field("heigth"), document.field("heigth"));
    assertEquals(extr.<Object>field("bitHeigth"), document.field("bitHeigth"));
    assertEquals(extr.<Object>field("class"), document.field("class"));
    // TODO fix char management issue:#2427
    // assertEquals(document.field("character"), extr.field("character"));
    assertEquals(extr.<Object>field("alive"), document.field("alive"));
    assertEquals(extr.<Object>field("dateTime"), document.field("dateTime"));
    assertEquals(extr.field("date"), c.getTime());
    assertEquals(extr.field("date1"), c1.getTime());
    //    assertEquals(extr.<String>field("bytes"), document.field("bytes"));
    Assertions.assertThat(extr.<Object>field("bytes")).isEqualTo(document.field("bytes"));
    assertEquals(extr.<String>field("utf8String"), document.field("utf8String"));
    assertEquals(extr.<Object>field("recordId"), document.field("recordId"));
    assertEquals(extr.<Object>field("bigNumber"), document.field("bigNumber"));
    assertNull(extr.field("nullField"));
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
    document.field("listStrings", strings);

    List<Short> shorts = session.newEmbeddedList();
    shorts.add((short) 1);
    shorts.add((short) 2);
    shorts.add((short) 3);
    document.field("shorts", shorts);

    List<Long> longs = session.newEmbeddedList();
    longs.add((long) 1);
    longs.add((long) 2);
    longs.add((long) 3);
    document.field("longs", longs);

    List<Integer> ints = session.newEmbeddedList();
    ints.add(1);
    ints.add(2);
    ints.add(3);
    document.field("integers", ints);

    List<Float> floats = session.newEmbeddedList();
    floats.add(1.1f);
    floats.add(2.2f);
    floats.add(3.3f);
    document.field("floats", floats);

    List<Double> doubles = session.newEmbeddedList();
    doubles.add(1.1);
    doubles.add(2.2);
    doubles.add(3.3);
    document.field("doubles", doubles);

    List<Date> dates = session.newEmbeddedList();
    dates.add(new Date());
    dates.add(new Date());
    dates.add(new Date());
    document.field("dates", dates);

    List<Byte> bytes = session.newEmbeddedList();
    bytes.add((byte) 0);
    bytes.add((byte) 1);
    bytes.add((byte) 3);
    document.field("bytes", bytes);

    List<Boolean> booleans = session.newEmbeddedList();
    booleans.add(true);
    booleans.add(false);
    booleans.add(false);
    document.field("booleans", booleans);

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
    document.field("listMixed", listMixed);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});

    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("listStrings"), document.field("listStrings"));
    assertEquals(extr.<Object>field("integers"), document.field("integers"));
    assertEquals(extr.<Object>field("doubles"), document.field("doubles"));
    assertEquals(extr.<Object>field("dates"), document.field("dates"));
    assertEquals(extr.<Object>field("bytes"), document.field("bytes"));
    assertEquals(extr.<Object>field("booleans"), document.field("booleans"));
    assertEquals(extr.<Object>field("listMixed"), document.field("listMixed"));
    session.rollback();
  }

  @SuppressWarnings({"rawtypes", "unchecked", "OverwrittenKey"})
  @Test
  public void testSimpleLiteralSet() throws InterruptedException {
    try (YouTrackDB ctx = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute(
          "create database testSimpleLiteralSet memory users(admin identified by 'adminpwd' role"
              + " admin)");
      try (var session = (DatabaseSessionInternal) ctx.open("testSimpleLiteralSet", "admin",
          "adminpwd")) {
        session.begin();
        var document = (EntityImpl) session.newEntity();
        Set<String> strings = session.newEmbeddedSet();
        strings.add("a");
        strings.add("b");
        strings.add("c");
        document.field("listStrings", strings);

        Set<Short> shorts = session.newEmbeddedSet();
        shorts.add((short) 1);
        shorts.add((short) 2);
        shorts.add((short) 3);
        document.field("shorts", shorts);

        Set<Long> longs = session.newEmbeddedSet();
        longs.add((long) 1);
        longs.add((long) 2);
        longs.add((long) 3);
        document.field("longs", longs);

        Set<Integer> ints = session.newEmbeddedSet();
        ints.add(1);
        ints.add(2);
        ints.add(3);
        document.field("integers", ints);

        Set<Float> floats = session.newEmbeddedSet();
        floats.add(1.1f);
        floats.add(2.2f);
        floats.add(3.3f);
        document.field("floats", floats);

        Set<Double> doubles = session.newEmbeddedSet();
        doubles.add(1.1);
        doubles.add(2.2);
        doubles.add(3.3);
        document.field("doubles", doubles);

        Set<Date> dates = session.newEmbeddedSet();
        dates.add(new Date());
        Thread.sleep(1);
        dates.add(new Date());
        Thread.sleep(1);
        dates.add(new Date());
        document.field("dates", dates);

        Set<Byte> bytes = session.newEmbeddedSet();
        bytes.add((byte) 0);
        bytes.add((byte) 1);
        bytes.add((byte) 3);
        document.field("bytes", bytes);

        Set<Boolean> booleans = session.newEmbeddedSet();
        booleans.add(true);
        booleans.add(false);
        booleans.add(false);
        document.field("booleans", booleans);

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
        document.field("listMixed", listMixed);

        var res = serializer.toStream(session, document);
        var extr = (EntityImpl) serializer.fromStream(session, res,
            (EntityImpl) session.newEntity(),
            new String[]{});

        assertEquals(extr.fields(), document.fields());
        assertEquals(extr.<Object>field("listStrings"), document.field("listStrings"));
        assertEquals(extr.<Object>field("integers"), document.field("integers"));
        assertEquals(extr.<Object>field("doubles"), document.field("doubles"));
        assertEquals(extr.<Object>field("dates"), document.field("dates"));
        assertEquals(extr.<Object>field("bytes"), document.field("bytes"));
        assertEquals(extr.<Object>field("booleans"), document.field("booleans"));
        assertEquals(extr.<Object>field("listMixed"), document.field("listMixed"));
        session.rollback();
      }
    }
  }

  @Test
  public void testLinkCollections() {
    try (YouTrackDB ctx = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var session = (DatabaseSessionInternal) ctx.open("test", "admin", "adminpwd")) {
        session.begin();
        var document = (EntityImpl) session.newEntity();
        var linkSet = session.newLinkSet();
        linkSet.add(new RecordId(10, 20));
        linkSet.add(new RecordId(10, 21));
        linkSet.add(new RecordId(10, 22));
        linkSet.add(new RecordId(11, 22));
        document.field("linkSet", linkSet, PropertyType.LINKSET);

        var linkList = session.newLinkList();
        linkList.add(new RecordId(10, 20));
        linkList.add(new RecordId(10, 21));
        linkList.add(new RecordId(10, 22));
        linkList.add(new RecordId(11, 22));
        document.field("linkList", linkList, PropertyType.LINKLIST);
        var res = serializer.toStream(session, document);
        var extr = (EntityImpl) serializer.fromStream(session, res,
            (EntityImpl) session.newEntity(),
            new String[]{});

        assertEquals(extr.fields(), document.fields());
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
    var embedded = (EntityImpl) session.newEntity();
    embedded.field("name", "test");
    embedded.field("surname", "something");
    document.field("embed", embedded, PropertyType.EMBEDDED);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(document.fields(), extr.fields());
    EntityImpl emb = extr.field("embed");
    assertNotNull(emb);
    assertEquals(emb.<Object>field("name"), embedded.field("name"));
    assertEquals(emb.<Object>field("surname"), embedded.field("surname"));
    session.rollback();
  }

  @Test
  public void testSimpleMapStringLiteral() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    Map<String, String> mapString = session.newEmbeddedMap();
    mapString.put("key", "value");
    mapString.put("key1", "value1");
    document.field("mapString", mapString);

    Map<String, Integer> mapInt = session.newEmbeddedMap();
    mapInt.put("key", 2);
    mapInt.put("key1", 3);
    document.field("mapInt", mapInt);

    Map<String, Long> mapLong = session.newEmbeddedMap();
    mapLong.put("key", 2L);
    mapLong.put("key1", 3L);
    document.field("mapLong", mapLong);

    Map<String, Short> shortMap = session.newEmbeddedMap();
    shortMap.put("key", (short) 2);
    shortMap.put("key1", (short) 3);
    document.field("shortMap", shortMap);

    Map<String, Date> dateMap = session.newEmbeddedMap();
    dateMap.put("key", new Date());
    dateMap.put("key1", new Date());
    document.field("dateMap", dateMap);

    Map<String, Float> floatMap = session.newEmbeddedMap();
    floatMap.put("key", 10f);
    floatMap.put("key1", 11f);
    document.field("floatMap", floatMap);

    Map<String, Double> doubleMap = session.newEmbeddedMap();
    doubleMap.put("key", 10d);
    doubleMap.put("key1", 11d);
    document.field("doubleMap", doubleMap);

    Map<String, Byte> bytesMap = session.newEmbeddedMap();
    bytesMap.put("key", (byte) 10);
    bytesMap.put("key1", (byte) 11);
    document.field("bytesMap", bytesMap);

    Map<String, String> mapWithNulls = session.newEmbeddedMap();
    mapWithNulls.put("key", "dddd");
    mapWithNulls.put("key1", null);
    document.field("bytesMap", mapWithNulls);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("mapString"), document.field("mapString"));
    assertEquals(extr.<Object>field("mapLong"), document.field("mapLong"));
    assertEquals(extr.<Object>field("shortMap"), document.field("shortMap"));
    assertEquals(extr.<Object>field("dateMap"), document.field("dateMap"));
    assertEquals(extr.<Object>field("doubleMap"), document.field("doubleMap"));
    assertEquals(extr.<Object>field("bytesMap"), document.field("bytesMap"));
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
    document.field("complexList", list);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("complexList"), document.field("complexList"));
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
    document.field("list", coll);
    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("list"), document.field("list"));
    session.rollback();
  }

  @Test
  public void testMapOfEmbeddedEntity() {
    session.begin();
    var document = (EntityImpl) session.newEntity();

    var embeddedInMap = (EntityImpl) session.newEmbededEntity();
    embeddedInMap.field("name", "test");
    embeddedInMap.field("surname", "something");
    Map<String, EntityImpl> map = session.newEmbeddedMap();
    map.put("embedded", embeddedInMap);
    document.newEmbeddedMap("map").putAll(map);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    Map<String, EntityImpl> mapS = extr.field("map");
    assertEquals(1, mapS.size());
    var emb = mapS.get("embedded");
    assertNotNull(emb);
    assertEquals(emb.<Object>field("name"), embeddedInMap.field("name"));
    assertEquals(emb.<Object>field("surname"), embeddedInMap.field("surname"));
    session.rollback();
  }

  @Test
  public void testMapOfLink() {
    // needs a database because of the lazy loading
    try (YouTrackDB ctx = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var session = (DatabaseSessionInternal) ctx.open("test", "admin", "adminpwd")) {
        session.begin();
        var document = (EntityImpl) session.newEntity();

        var map = session.newLinkMap();
        map.put("link", new RecordId(0, 0));
        document.field("map", map, PropertyType.LINKMAP);

        var res = serializer.toStream(session, document);
        var extr = (EntityImpl) serializer.fromStream(session, res,
            (EntityImpl) session.newEntity(),
            new String[]{});
        assertEquals(extr.fields(), document.fields());
        assertEquals(extr.<Object>field("map"), document.field("map"));
        session.rollback();
      }
      ctx.drop("test");
    }
  }

  @Test
  public void testDocumentSimple() {
    try (YouTrackDB ctx = new YouTrackDBImpl(DbTestBase.embeddedDBUrl(getClass()),
        YouTrackDBConfig.defaultConfig())) {
      ctx.execute("create database test memory users(admin identified by 'adminpwd' role admin)");
      try (var session = (DatabaseSessionInternal) ctx.open("test", "admin", "adminpwd")) {
        session.createClass("TestClass");

        session.begin();
        var document = (EntityImpl) session.newEntity("TestClass");
        document.field("test", "test");
        var res = serializer.toStream(session, document);
        var extr = (EntityImpl) serializer.fromStream(session, res,
            (EntityImpl) session.newEntity(),
            new String[]{});

        assertEquals(extr.fields(), document.fields());
        assertEquals(extr.<Object>field("test"), document.field("test"));
        session.rollback();
      }
      ctx.drop("test");
    }
  }


  @Test(expected = SchemaException.class)
  public void testSetOfWrongData() {
    session.executeInTx(() -> {
      var document = (EntityImpl) session.newEntity();

      var embeddedSet = session.newEmbeddedSet();
      embeddedSet.add(new WrongData());
      document.field("embeddedSet", embeddedSet, PropertyType.EMBEDDEDSET);
    });
  }

  @Test(expected = DatabaseException.class)
  public void testListOfWrongData() {
    session.executeInTx(() -> {
      var document = (EntityImpl) session.newEntity();

      List<Object> embeddedList = new ArrayList<>();
      embeddedList.add(new WrongData());
      document.field("embeddedList", embeddedList, PropertyType.EMBEDDEDLIST);

      serializer.toStream(session, document);
    });
  }

  @Test(expected = SchemaException.class)
  public void testMapOfWrongData() {
    session.executeInTx(() -> {
      var document = (EntityImpl) session.newEntity();

      Map<String, Object> embeddedMap = session.newEmbeddedMap();
      embeddedMap.put("name", new WrongData());
      document.field("embeddedMap", embeddedMap, PropertyType.EMBEDDEDMAP);
    });
  }

  @Test
  public void testCollectionOfEmbeddedDocument() {
    session.begin();

    var document = (EntityImpl) session.newEntity();

    var embeddedInList = (EntityImpl) session.newEmbededEntity();
    embeddedInList.field("name", "test");
    embeddedInList.field("surname", "something");

    var embeddedInList2 = (EntityImpl) session.newEmbededEntity();
    embeddedInList2.field("name", "test1");
    embeddedInList2.field("surname", "something2");

    List<EntityImpl> embeddedList = new ArrayList<>();
    embeddedList.add(embeddedInList);
    embeddedList.add(embeddedInList2);
    embeddedList.add(null);
    embeddedList.add((EntityImpl) session.newEmbededEntity());
    document.newEmbeddedList("embeddedList").addAll(embeddedList);

    var embeddedInSet = (EntityImpl) session.newEmbededEntity();
    embeddedInSet.field("name", "test2");
    embeddedInSet.field("surname", "something3");

    var embeddedInSet2 = (EntityImpl) session.newEmbededEntity();
    embeddedInSet2.field("name", "test5");
    embeddedInSet2.field("surname", "something6");

    Set<EntityImpl> embeddedSet = new HashSet<>();
    embeddedSet.add(embeddedInSet);
    embeddedSet.add(embeddedInSet2);
    embeddedSet.add((EntityImpl) session.newEmbededEntity());
    document.newEmbeddedSet("embeddedSet").addAll(embeddedSet);

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});

    List<EntityImpl> ser = extr.field("embeddedList");
    assertEquals(4, ser.size());
    assertNotNull(ser.get(0));
    assertNotNull(ser.get(1));
    assertNull(ser.get(2));
    assertNotNull(ser.get(3));
    var inList = ser.get(0);
    assertNotNull(inList);
    assertEquals(inList.<Object>field("name"), embeddedInList.field("name"));
    assertEquals(inList.<Object>field("surname"), embeddedInList.field("surname"));

    Set<EntityImpl> setEmb = extr.field("embeddedSet");
    assertEquals(3, setEmb.size());
    var ok = false;
    for (var inSet : setEmb) {
      assertNotNull(inSet);
      if (embeddedInSet.field("name").equals(inSet.field("name"))
          && embeddedInSet.field("surname").equals(inSet.field("surname"))) {
        ok = true;
      }
    }
    assertTrue("not found record in the set after serilize", ok);
    session.rollback();
  }

  @Test
  public void testFieldNames() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.fields("a", 1, "b", 2, "c", 3);
    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});

    final var fields = extr.fieldNames();

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
    document.fields("a", 1, "b", 2, "c", 3);
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
    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);

    var res = serializer.toStream(session, document);
    var extr =
        (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
            new String[]{"name", "age"});

    assertEquals(document.field("name"), extr.<Object>field("name"));
    assertEquals(document.<Object>field("age"), extr.field("age"));
    assertNull(extr.field("youngAge"));
    assertNull(extr.field("oldAge"));
    session.rollback();
  }

  @Test
  public void testWithRemove() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);
    document.removeField("oldAge");

    var res = serializer.toStream(session, document);
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});

    assertEquals(document.field("name"), extr.<Object>field("name"));
    assertEquals(document.<Object>field("age"), extr.field("age"));
    assertEquals(document.<Object>field("youngAge"), extr.field("youngAge"));
    assertNull(extr.field("oldAge"));
    session.rollback();
  }

  @Test
  public void testPartialCustom() {
    session.begin();
    var document = (EntityImpl) session.newEntity();
    document.field("name", "name");
    document.field("age", 20);
    document.field("youngAge", (short) 20);
    document.field("oldAge", (long) 20);

    var res = serializer.toStream(session, document);

    var extr = new EntityImpl(session, res);
    extr.unsetDirty();

    RecordInternal.setRecordSerializer(extr, serializer);

    assertEquals(document.field("name"), extr.<Object>field("name"));
    assertEquals(document.<Object>field("age"), extr.field("age"));
    assertEquals(document.<Object>field("youngAge"), extr.field("youngAge"));
    assertEquals(document.<Object>field("oldAge"), extr.field("oldAge"));

    assertEquals(document.fieldNames().length, extr.fieldNames().length);
    session.rollback();
  }

  @Test
  public void testPartialNotFound() {
    // this test want to do only for RecordSerializerNetworkV37
    if (serializer instanceof RecordSerializerNetworkV37) {
      session.begin();
      var document = (EntityImpl) session.newEntity();
      document.field("name", "name");
      document.field("age", 20);
      document.field("youngAge", (short) 20);
      document.field("oldAge", (long) 20);

      var res = serializer.toStream(session, document);
      var extr =
          (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
              new String[]{"foo"});

      assertEquals(document.field("name"), extr.<Object>field("name"));
      assertEquals(document.<Object>field("age"), extr.field("age"));
      assertEquals(document.<Object>field("youngAge"), extr.field("youngAge"));
      assertEquals(document.<Object>field("oldAge"), extr.field("oldAge"));
      session.rollback();
    }
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
    var extr = (EntityImpl) serializer.fromStream(session, res, (EntityImpl) session.newEntity(),
        new String[]{});
    assertEquals(extr.fields(), document.fields());
    assertEquals(extr.<Object>field("list"), document.field("list"));
    session.rollback();
  }

  private static class WrongData {

  }
}
