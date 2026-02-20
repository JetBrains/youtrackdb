/*
 * JUnit 4 version of LinkMapIndexTest.
 * Original: tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
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
 * @since 22.03.12
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LinkMapIndexTest extends BaseDBTest {

  private static LinkMapIndexTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new LinkMapIndexTest();
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
   * Original: setupSchema (line 22) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  private void setupSchema() {
    final var linkMapIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkMapIndexTestClass");
    linkMapIndexTestClass.createProperty("linkMap", PropertyType.LINKMAP);

    linkMapIndexTestClass.createIndex("mapIndexTestKey", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "linkMap");
    linkMapIndexTestClass.createIndex(
        "mapIndexTestValue", SchemaClass.INDEX_TYPE.NOTUNIQUE, "linkMap by value");
  }

  /**
   * Original: destroySchema (line 34) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  private void destroySchema() {
    session = createSessionInstance();
    session.getMetadata().getSchema().dropClass("LinkMapIndexTestClass");
    session.close();
  }

  /**
   * Original: afterMethod (line 42) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Override
  @After
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from LinkMapIndexTestClass").close();
    session.commit();

    super.afterMethod();
  }

  /**
   * Original: testIndexMap (line 50) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test01_IndexMap() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();

        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");

    Assert.assertEquals(valueIndexMap.size(session), 2);
    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapInTx (line 100) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test02_IndexMapInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    session.commit();

    try {
      session.begin();
      var map = session.newLinkMap();

      map.put("key1", docOne.getIdentity());
      map.put("key2", docTwo.getIdentity());

      final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
      document.setProperty("linkMap", map);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapUpdateOne (line 157) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test03_IndexMapUpdateOne() {

    session.begin();

    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var mapOne = session.newLinkMap();

    mapOne.put("key1", docOne.getIdentity());
    mapOne.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", mapOne);

    final var mapTwo = session.newLinkMap();
    mapTwo.put("key2", docOne.getIdentity());
    mapTwo.put("key3", docThree.getIdentity());

    document.setProperty("linkMap", mapTwo);

    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapUpdateOneTx (line 215) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test04_IndexMapUpdateOneTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    try {
      var mapTwo = session.newLinkMap();

      mapTwo.put("key3", docOne.getIdentity());
      mapTwo.put("key2", docTwo.getIdentity());

      final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
      document.setProperty("linkMap", mapTwo);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapUpdateOneTxRollback (line 269) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test05_IndexMapUpdateOneTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var mapOne = session.newLinkMap();

    mapOne.put("key1", docOne.getIdentity());
    mapOne.put("key2", docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", mapOne);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    final var mapTwo = session.newLinkMap();

    mapTwo.put("key3", docTwo.getIdentity());
    mapTwo.put("key2", docThree.getIdentity());

    document.setProperty("linkMap", mapTwo);

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key2") && !key.equals("key1")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.equals(docOne.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapAddItem (line 332) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test06_IndexMapAddItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());
    final var docTwo = ((EntityImpl) session.newEntity());
    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE " + document.getIdentity() + " set linkMap['key3'] = " + docThree.getIdentity())
        .close();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 3);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapAddItemTx (line 389) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test07_IndexMapAddItemTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key3", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();

        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 3);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapAddItemTxRollback (line 453) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test08_IndexMapAddItemTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key3", docThree.getIdentity());

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapUpdateItem (line 510) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test09_IndexMapUpdateItem() {

    session.begin();
    final var docOne = session.newEntity();

    final var docTwo = session.newEntity();

    final var docThree = session.newEntity();

    var map = session.newLinkMap();

    map.put("key1", docOne);
    map.put("key2", docTwo);

    final var document = session.newEntity("LinkMapIndexTestClass");
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE " + document.getIdentity() + " set linkMap['key2'] = " + docThree.getIdentity())
        .close();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");

    Assert.assertEquals(keyIndexMap.size(session), 2);
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapUpdateItemInTx (line 568) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test10_IndexMapUpdateItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key2", docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Assert.assertEquals(keyIndexMap.size(session), 2);
    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapUpdateItemInTxRollback (line 631) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test11_IndexMapUpdateItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>getProperty("linkMap").put("key2", docThree.getIdentity());

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapRemoveItem (line 688) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test12_IndexMapRemoveItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    session.execute("UPDATE " + document.getIdentity() + " remove linkMap = 'key2'").close();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapRemoveItemInTx (line 744) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test13_IndexMapRemoveItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      final EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Map<String, RID>>getProperty("linkMap").remove("key2");

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapRemoveItemInTxRollback (line 807) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test14_IndexMapRemoveItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());
    map.put("key3", docThree.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    final EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Map<String, RID>>getProperty("linkMap").remove("key2");

    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 3);

    final Iterator<Object> keyIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (String) keyIterator.next();
        if (!key.equals("key1") && !key.equals("key2") && !key.equals("key3")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 3);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())
            && !value.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapRemove (line 866) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test15_IndexMapRemove() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.commit();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 0);

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 0);
  }

  /**
   * Original: testIndexMapRemoveInTx (line 897) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test16_IndexMapRemoveInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      activeTx.<EntityImpl>load(document).delete();
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 0);

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 0);
  }

  /**
   * Original: testIndexMapRemoveInTxRollback (line 931) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test17_IndexMapRemoveInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    final var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.rollback();

    final var keyIndexMap = getIndex("mapIndexTestKey");
    Assert.assertEquals(keyIndexMap.size(session), 2);

    final Iterator<Object> keysIterator;
    try (var keyStream = keyIndexMap.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("key1") && !key.equals("key2")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }

    final var valueIndexMap = getIndex("mapIndexTestValue");
    Assert.assertEquals(valueIndexMap.size(session), 2);

    final Iterator<Object> valuesIterator;
    try (var valueStream = valueIndexMap.keyStream()) {
      valuesIterator = valueStream.iterator();

      while (valuesIterator.hasNext()) {
        var value = (Identifiable) valuesIterator.next();
        if (!value.getIdentity().equals(docOne.getIdentity())
            && !value.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown value found: " + value);
        }
      }
    }
  }

  /**
   * Original: testIndexMapSQL (line 985) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/LinkMapIndexTest.java
   */
  @Test
  public void test18_IndexMapSQL() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var map = session.newLinkMap();

    map.put("key1", docOne.getIdentity());
    map.put("key2", docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("LinkMapIndexTestClass"));
    document.setProperty("linkMap", map);

    session.commit();

    session.begin();
    var resultByKey =
        session.query(
            "select * from LinkMapIndexTestClass where linkMap containskey ?",
            "key1").toList();
    Assert.assertNotNull(resultByKey);
    Assert.assertEquals(resultByKey.size(), 1);

    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    Assert.assertEquals(map, document.getProperty("linkMap"));

    var resultByValue =
        executeQuery(
            "select * from LinkMapIndexTestClass where linkMap  containsvalue ?",
            docOne.getIdentity());
    Assert.assertNotNull(resultByValue);
    Assert.assertEquals(resultByValue.size(), 1);

    Assert.assertEquals(map, document.getProperty("linkMap"));
    session.commit();
  }

}
