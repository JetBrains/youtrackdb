/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.auto.junit;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Iterator;
import java.util.Map;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of MapIndexTest. Original test class:
 * com.jetbrains.youtrackdb.auto.MapIndexTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MapIndexTest extends BaseDBTest {

  private static MapIndexTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new MapIndexTest();
    instance.beforeClass();
    instance.setupSchema();
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (instance != null) {
      instance.destroySchema();
    }
  }

  /**
   * Original: setupSchema (line 20) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  private void setupSchema() {
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

  /**
   * Original: destroySchema (line 41)
   */
  private void destroySchema() {
    if (session == null || session.isClosed()) {
      session = createSessionInstance();
    }
    if (session.getMetadata().getSchema().existsClass("Mapper")) {
      session.getMetadata().getSchema().dropClass("Mapper");
    }
    if (session.getMetadata().getSchema().existsClass("MapIndexTestMovie")) {
      session.getMetadata().getSchema().dropClass("MapIndexTestMovie");
    }
  }

  /**
   * Original: afterMethod (line 49)
   */
  @Override
  @After
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from Mapper").close();
    session.execute("delete from MapIndexTestMovie").close();
    session.commit();

    super.afterMethod();
  }

  /**
   * Original: testIndexMap (line 60) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test01_IndexMap() {
    session.begin();
    final var mapper = session.newEntity("Mapper");
    final var map = session.newEmbeddedMap();
    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.commit();

    final var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(2, keyIndex.size(session));
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
    Assert.assertEquals(2, valueIndex.size(session));
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

  /**
   * Original: testIndexMapInTx (line 96) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test02_IndexMapInTx() {
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

    Assert.assertEquals(2, keyIndex.size(session));
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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapUpdateOne (line 144) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test03_IndexMapUpdateOne() {
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

    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapUpdateOneTx (line 201) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test04_IndexMapUpdateOneTx() {
    session.begin();
    var mapper = session.newEntity("Mapper");
    var mapOne = session.newEmbeddedMap();

    mapOne.put("key1", 10);
    mapOne.put("key2", 20);

    mapper.setProperty("intMap", mapOne);
    session.commit();

    try {
      session.begin();

      var activeTx = session.getActiveTransaction();
      mapper = activeTx.load(mapper);
      final var mapTwo = session.newEmbeddedMap();

      mapTwo.put("key3", 30);
      mapTwo.put("key2", 20);

      mapper.setProperty("intMap", mapTwo);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(30) && !value.equals(20)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapUpdateOneTxRollback (line 261) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test05_IndexMapUpdateOneTxRollback() {
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

    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapAddItem (line 315) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test06_IndexMapAddItem() {
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
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(3, keyIndex.size(session));

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
    Assert.assertEquals(3, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20) && !value.equals(30)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapAddItemTx (line 363) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test07_IndexMapAddItemTx() {
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
    Assert.assertEquals(3, keyIndex.size(session));

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
    Assert.assertEquals(3, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10) && !value.equals(20) && !value.equals(30)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapAddItemTxRollback (line 417) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test08_IndexMapAddItemTxRollback() {
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
    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapUpdateItem (line 466) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test09_IndexMapUpdateItem() {
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
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(2, keyIndex.size(session));

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

    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapUpdateItemInTx (line 514) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test10_IndexMapUpdateItemInTx() {
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
    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapUpdateItemInTxRollback (line 567) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test11_IndexMapUpdateItemInTxRollback() {
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
    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapRemoveItem (line 616) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test12_IndexMapRemoveItem() {
    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.commit();

    session.begin();
    Entity loadedMapper = session.load(mapper.getIdentity());
    loadedMapper.<Map<String, Integer>>getProperty("intMap").remove("key2");
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(1, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(1, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapRemoveItemInTx (line 665) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test13_IndexMapRemoveItemInTx() {
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
      loadedMapper.<Map<String, Integer>>getProperty("intMap").remove("key2");
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(1, keyIndex.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndex.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(1, valueIndex.size(session));

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndex.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Integer) valuesIterator.next();
        if (!value.equals(10)) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapRemoveItemInTxRollback (line 719) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test14_IndexMapRemoveItemInTxRollback() {
    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.commit();

    session.begin();
    Entity loadedMapper = session.load(mapper.getIdentity());
    loadedMapper.<Map<String, Integer>>getProperty("intMap").remove("key2");
    session.rollback();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapRemove (line 769) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test15_IndexMapRemove() {
    session.begin();
    var mapper = session.newEntity("Mapper");
    var map = session.newEmbeddedMap();

    map.put("key1", 10);
    map.put("key2", 20);

    mapper.setProperty("intMap", map);
    session.delete(mapper);
    session.commit();

    var keyIndex = getIndex("mapIndexTestKey");
    Assert.assertEquals(0, keyIndex.size(session));

    var valueIndex = getIndex("mapIndexTestValue");

    Assert.assertEquals(0, valueIndex.size(session));
  }

  /**
   * Original: testIndexMapRemoveInTx (line 794) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test16_IndexMapRemoveInTx() {
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
    Assert.assertEquals(0, keyIndex.size(session));

    var valueIndex = getIndex("mapIndexTestValue");
    Assert.assertEquals(0, valueIndex.size(session));
  }

  /**
   * Original: testIndexMapRemoveInTxRollback (line 824) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test17_IndexMapRemoveInTxRollback() {
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
    Assert.assertEquals(2, keyIndex.size(session));

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
    Assert.assertEquals(2, valueIndex.size(session));

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

  /**
   * Original: testIndexMapSQL (line 873) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/MapIndexTest.java
   */
  @Test
  public void test18_IndexMapSQL() {
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
    Assert.assertEquals(1, resultByKey.size());
    var result = session.loadEntity(resultByKey.get(0).getIdentity());

    Assert.assertEquals(map, result.<Map<String, Integer>>getProperty("intMap"));

    var resultByValue =
        executeQuery("select * from Mapper where intMap containsvalue ?", 10);
    Assert.assertNotNull(resultByValue);
    Assert.assertEquals(1, resultByValue.size());
    result = session.loadEntity(resultByValue.get(0).getIdentity());

    Assert.assertEquals(map, result.<Map<String, Integer>>getProperty("intMap"));
    session.commit();
  }
}
