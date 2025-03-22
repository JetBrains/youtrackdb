package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 21.12.11
 */
@Test
public class MapIndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public MapIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void setupSchema() {
    if (session.getMetadata().getSchema().existsClass("Mapper")) {
      session.getMetadata().getSchema().dropClass("Mapper");
    }

    final var mapper = session.getMetadata().getSchema().createClass("Mapper");
    mapper.createProperty("id", PropertyType.STRING);
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);

    mapper.createIndex("mapIndexTestKey", SchemaClass.INDEX_TYPE.NOTUNIQUE, "intMap");
    mapper.createIndex("mapIndexTestValue", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "intMap by value");

    final var movie = session.getMetadata().getSchema().createClass("MapIndexTestMovie");
    movie.createProperty("title", PropertyType.STRING);
    movie.createProperty("thumbs", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);

    movie.createIndex("indexForMap", SchemaClass.INDEX_TYPE.NOTUNIQUE, "thumbs by key");
  }

  @AfterClass
  public void destroySchema() {
    session = createSessionInstance();
    session.getMetadata().getSchema().dropClass("Mapper");
    session.getMetadata().getSchema().dropClass("MapIndexTestMovie");
    session.close();
  }

  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from Mapper").close();
    session.execute("delete from MapIndexTestMovie").close();
    session.commit();

    super.afterMethod();
  }

  public void testIndexMap() {
    checkEmbeddedDB();

    final var mapper = session.newEntity("Mapper");
    final Map<String, Integer> map = new HashMap<>();
    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.begin();
    session.commit();

    final var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);
    try (final var keyStream = keyIndex.keyStream()) {
      final var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        final var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);
    try (final var valueStream = valueIndex.keyStream()) {
      final var valuesIterator = valueStream.iterator();
      while (valuesIterator.hasNext()) {
        final var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapInTx() {
    checkEmbeddedDB();

    try {
      session.begin();
      final var mapper = session.newEntity("Mapper");
      Map<String, Integer> map = new HashMap<>();

      map.put("key1", 10);
      map.put("key2", 20);

      mapper.setProperty("intMap", map);
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 2);
    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateOne() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> mapOne = new HashMap<>();

    mapOne.put("key1", 10);
    mapOne.put("key2", 20);

    mapper.setProperty("intMap", mapOne);
    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    mapper = activeTx.load(mapper);
    final Map<String, Integer> mapTwo = new HashMap<>();

    mapTwo.put("key3", 30);
    mapTwo.put("key2", 20);

    mapper.setProperty("intMap", mapTwo);

    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20)) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateOneTx() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> mapOne = new HashMap<>();

    mapOne.put("key1", 10);
    mapOne.put("key2", 20);

    mapper.setProperty("intMap", mapOne);
    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    try {
      final Map<String, Integer> mapTwo = new HashMap<>();

      mapTwo.put("key3", 30);
      mapTwo.put("key2", 20);

      var activeTx = session.getActiveTransaction();
      mapper = activeTx.load(mapper);
      mapper.setProperty("intMap", mapTwo);
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20)) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateOneTxRollback() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> mapOne = new HashMap<>();

    mapOne.put("key1", 10);
    mapOne.put("key2", 20);

    mapper.setProperty("intMap", mapOne);
    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    final Map<String, Integer> mapTwo = new HashMap<>();

    mapTwo.put("key3", 30);
    mapTwo.put("key2", 20);

    var activeTx = session.getActiveTransaction();
    mapper = activeTx.load(mapper);
    mapper.setProperty("intMap", mapTwo);
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key1")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  public void testIndexMapAddItem() {
    checkEmbeddedDB();

    session.begin();
    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    mapper = mapper;
    session.commit();

    session.begin();
    session.execute("UPDATE " + mapper.getIdentity() + " set intMap['key3'] = 30").close();
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 3);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20) && !value.equals(10)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapAddItemTx() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.begin();
    mapper = mapper;
    session.commit();

    try {
      session.begin();
      Entity loadedMapper = session.load(mapper.getIdentity());
      loadedMapper.<Map<String, Integer>>getProperty("intMap").put("key3", 30);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 3);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20) && !value.equals(10)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapAddItemTxRollback() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    Entity loadedMapper = session.load(mapper.getIdentity());
    loadedMapper.<Map<String, Integer>>getProperty("intMap").put("key3", 30);
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(20) && !value.equals(10)) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateItem() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    session.execute("UPDATE " + mapper.getIdentity() + " set intMap['key2'] = 40").close();
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");

    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(40)) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateItemInTx() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.begin();
    mapper = mapper;
    session.commit();

    try {
      session.begin();
      Entity loadedMapper = session.load(mapper.getIdentity());
      loadedMapper.<Map<String, Integer>>getProperty("intMap").put("key2", 40);
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(40)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapUpdateItemInTxRollback() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    Entity loadedMapper = session.load(new RecordId(mapper.getIdentity()));
    loadedMapper.<Map<String, Integer>>getProperty("intMap").put("key2", 40);
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapRemoveItem() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);
    map.put("key3", 30);

    mapper.setProperty("intMap", map);

    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    session.execute("UPDATE " + mapper.getIdentity() + " remove intMap = 'key2'").close();
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(30)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapRemoveItemInTx() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);
    map.put("key3", 30);

    mapper.setProperty("intMap", map);
    session.begin();
    mapper = mapper;
    session.commit();

    try {
      session.begin();
      Entity loadedMapper = session.load(mapper.getIdentity());
      loadedMapper.<Map<String, Integer>>getProperty("intMap").remove("key2");
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");

    Assert.assertEquals(valueIndex.size(session), 2);
    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(30)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapRemoveItemInTxRollback() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);
    map.put("key3", 30);

    mapper.setProperty("intMap", map);

    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    Entity loadedMapper = session.load(mapper.getIdentity());
    loadedMapper.<Map<String, Integer>>getProperty("intMap").remove("key2");
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 3);
    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");

    Assert.assertEquals(valueIndex.size(session), 3);
    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20) && !value.equals(30)) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  public void testIndexMapRemove() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(mapper));
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 0);

    var valueIndex = getIndex("mapIndexTestValue");

    Assert.assertEquals(valueIndex.size(session), 0);
  }

  public void testIndexMapRemoveInTx() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.begin();
    mapper = mapper;
    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      session.delete(activeTx.<Entity>load(mapper));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 0);

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 0);
  }

  public void testIndexMapRemoveInTxRollback() {
    checkEmbeddedDB();

    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.begin();
    mapper = mapper;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(mapper));
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndex.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  public void testIndexMapSQL() {
    var mapper = session.newEntity("Mapper");
    Map<String, Integer> map = new HashMap<>();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.begin();
    session.commit();

    var resultByKey =
        executeQuery("select * from Mapper where intMap containskey ?", "key1");
    Assert.assertNotNull(resultByKey);
    Assert.assertEquals(resultByKey.size(), 1);

    Assert.assertEquals(map, resultByKey.get(0).<Map<String, Integer>>getProperty("intMap"));

    var resultByValue =
        executeQuery("select * from Mapper where intMap containsvalue ?", 10);
    Assert.assertNotNull(resultByValue);
    Assert.assertEquals(resultByValue.size(), 1);

    Assert.assertEquals(map, resultByValue.get(0).<Map<String, Integer>>getProperty("intMap"));
  }
}
