package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaManager;
import java.util.Iterator;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * @since 21.12.11
 */
@Test
public class MapIndexTest extends BaseDBTest {

  @BeforeClass
  public void setupSchema() {
    if (session.getMetadata().getSlowMutableSchema().existsClass("Mapper")) {
      session.getMetadata().getSlowMutableSchema().dropClass("Mapper");
    }

    final var mapper = session.getMetadata().getSlowMutableSchema().createClass("Mapper");
    mapper.createProperty("id", PropertyType.STRING);
    mapper.createProperty("intMap", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);

    mapper.createIndex("mapIndexTestKey", SchemaManager.INDEX_TYPE.NOTUNIQUE, "intMap");
    mapper.createIndex("mapIndexTestValue", SchemaManager.INDEX_TYPE.NOTUNIQUE,
        "intMap by value");

    final var movie = session.getMetadata().getSlowMutableSchema().createClass("MapIndexTestMovie");
    movie.createProperty("title", PropertyType.STRING);
    movie.createProperty("thumbs", PropertyType.EMBEDDEDMAP, PropertyType.INTEGER);

    movie.createIndex("indexForMap", SchemaManager.INDEX_TYPE.NOTUNIQUE, "thumbs by key");
  }

  @AfterClass
  public void destroySchema() {
    session = createSessionInstance();
    session.getMetadata().getSlowMutableSchema().dropClass("Mapper");
    session.getMetadata().getSlowMutableSchema().dropClass("MapIndexTestMovie");
    session.close();
  }

  @Override
  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from Mapper").close();
    session.execute("delete from MapIndexTestMovie").close();
    session.commit();

    super.afterMethod();
  }

  public void testIndexMap() {

    session.begin();
    final var mapper = session.newEntity("Mapper");
    final var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.commit();

    final var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);
    try (final var keyStream = keyIndex.keys()) {
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
    try (final var valueStream = valueIndex.keys()) {
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

    try {
      session.begin();
      final var mapper = session.newEntity("Mapper");
      var map = session.newEmbeddedMap();

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
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var mapOne = session.newEmbeddedMap();

    mapOne.put("key1", 10);
    mapOne.put("key2", 20);

    mapper.setProperty("intMap", mapOne);
    session.commit();

    session.begin();

    var activeTx = session.getActiveTransaction();
    mapper = activeTx.load(mapper);
    final var mapTwo = session.newEmbeddedMap();

    mapTwo.put("key3", 30);
    mapTwo.put("key2", 20);

    mapper.setProperty("intMap", mapTwo);

    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var mapOne = session.newEmbeddedMap();

    mapOne.put("key1", 10);
    mapOne.put("key2", 20);

    mapper.setProperty("intMap", mapOne);
    session.commit();

    session.begin();
    try {
      final var mapTwo = session.newEmbeddedMap();

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
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var mapOne = session.newEmbeddedMap();

    mapOne.put("key1", 10);
    mapOne.put("key2", 20);

    mapper.setProperty("intMap", mapOne);
    session.commit();

    session.begin();
    final var mapTwo = session.newEmbeddedMap();

    mapTwo.put("key3", 30);
    mapTwo.put("key2", 20);

    var activeTx = session.getActiveTransaction();
    mapper = activeTx.load(mapper);
    mapper.setProperty("intMap", mapTwo);
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.commit();

    session.begin();
    session.execute("UPDATE " + mapper.getIdentity() + " set intMap['key3'] = 30").close();
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
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
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.commit();

    session.begin();
    Entity loadedMapper = session.load(mapper.getIdentity());
    loadedMapper.<Map<String, Integer>>getProperty("intMap").put("key3", 30);
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.commit();

    session.begin();
    session.execute("UPDATE " + mapper.getIdentity() + " set intMap['key2'] = 40").close();
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
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
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.commit();

    session.begin();
    Entity loadedMapper = session.load(mapper.getIdentity());
    loadedMapper.<Map<String, Integer>>getProperty("intMap").put("key2", 40);
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);
    map.put("key3", 30);

    mapper.setProperty("intMap", map);

    session.commit();

    session.begin();
    session.execute("UPDATE " + mapper.getIdentity() + " remove intMap = 'key2'").close();
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);
    map.put("key3", 30);

    mapper.setProperty("intMap", map);
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
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);
    map.put("key3", 30);

    mapper.setProperty("intMap", map);

    session.commit();

    session.begin();
    Entity loadedMapper = session.load(mapper.getIdentity());
    loadedMapper.<Map<String, Integer>>getProperty("intMap").remove("key2");
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndex.size(session), 3);
    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

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

    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(mapper));
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndex.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keys()) {
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
    try (var valueStream = valueIndex.keys()) {
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
    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);

    session.commit();

    session.begin();
    var resultByKey =
        executeQuery("select * from Mapper where intMap containskey ?", "key1");
    Assert.assertNotNull(resultByKey);
    Assert.assertEquals(resultByKey.size(), 1);
    var result = session.loadEntity(resultByKey.get(0).getIdentity());

    Assert.assertEquals(map, result.<Map<String, Integer>>getProperty("intMap"));

    var resultByValue =
        executeQuery("select * from Mapper where intMap containsvalue ?", 10);
    Assert.assertNotNull(resultByValue);
    Assert.assertEquals(resultByValue.size(), 1);
    result = session.loadEntity(resultByValue.get(0).getIdentity());

    Assert.assertEquals(map, result.<Map<String, Integer>>getProperty("intMap"));
    session.commit();
  }
}
