package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for map property index operations.
 *
 * @since 21.12.11
 */
public class MapIndexTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

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

  @AfterAll
  @Override
  void afterAll() throws Exception {
    session = createSessionInstance();
    session.getMetadata().getSchema().dropClass("Mapper");
    session.getMetadata().getSchema().dropClass("MapIndexTestMovie");
    session.close();

    super.afterAll();
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    if (session.getTransactionInternal().isActive()) {
      session.rollback();
    }
    session.begin();
    session.execute("delete from Mapper").close();
    session.execute("delete from MapIndexTestMovie").close();
    session.commit();

    super.afterEach();
  }

  @Test
  void testIndexMap() {

    session.begin();
    final var mapper = session.newEntity("Mapper");
    final var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    var ato = activeTx.getAtomicOperation();
    final var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));
    try (final var keyStream = keyIndex.keyStream(ato)) {
      final var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        final var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));
    try (final var valueStream = valueIndex.keyStream(ato)) {
      final var valuesIterator = valueStream.iterator();
      while (valuesIterator.hasNext()) {
        final var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapInTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");

    assertEquals(2, keyIndex.size(session));
    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapUpdateOne() {

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

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");

    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20)) {
          fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapUpdateOneTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");

    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20)) {
          fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapUpdateOneTxRollback() {

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

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key1")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapAddItem() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(3, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(3, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20) && !value.equals(10)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapAddItemTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(3, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(3, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20) && !value.equals(10)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapAddItemTxRollback() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");

    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(20) && !value.equals(10)) {
          fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapUpdateItem() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");

    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(40)) {
          fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapUpdateItemInTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(40)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapUpdateItemInTxRollback() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapRemoveItem() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(30)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapRemoveItemInTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");

    assertEquals(2, valueIndex.size(session));
    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(30)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapRemoveItemInTxRollback() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");

    assertEquals(3, keyIndex.size(session));
    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");

    assertEquals(3, valueIndex.size(session));
    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20) && !value.equals(30)) {
          fail("Unknown key found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapRemove() {

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

    session.begin();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(0, keyIndex.size(session));

    var valueIndex = getIndex("mapIndexTestValue");

    assertEquals(0, valueIndex.size(session));
    session.rollback();
  }

  @Test
  void testIndexMapRemoveInTx() {

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

    session.begin();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(0, keyIndex.size(session));

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(0, valueIndex.size(session));
    session.rollback();
  }

  @Test
  void testIndexMapRemoveInTxRollback() {

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

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var keyIndex = getIndex("mapIndexTestKey");
    assertEquals(2, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream(ato)) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20)) {
          fail("Unknown value found: " + value);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexMapSQL() {
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
    assertNotNull(resultByKey);
    assertEquals(1, resultByKey.size());
    var result = session.loadEntity(resultByKey.getFirst().getIdentity());

    assertEquals(result.<Map<String, Integer>>getProperty("intMap"), map);

    var resultByValue =
        executeQuery("select * from Mapper where intMap containsvalue ?", 10);
    assertNotNull(resultByValue);
    assertEquals(1, resultByValue.size());
    result = session.loadEntity(resultByValue.getFirst().getIdentity());

    assertEquals(result.<Map<String, Integer>>getProperty("intMap"), map);
    session.commit();
  }
}
