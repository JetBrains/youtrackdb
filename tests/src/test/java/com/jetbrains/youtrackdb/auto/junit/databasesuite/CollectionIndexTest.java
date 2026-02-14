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
package com.jetbrains.youtrackdb.auto.junit.databasesuite;

import com.jetbrains.youtrackdb.auto.junit.BaseDBTest;
import com.jetbrains.youtrackdb.auto.junit.BaseTest;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Iterator;
import java.util.List;
import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * JUnit 4 migration of CollectionIndexTest. Original test class:
 * com.jetbrains.youtrackdb.auto.CollectionIndexTest Location:
 * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CollectionIndexTest extends BaseDBTest {

  private static CollectionIndexTest instance;

  @BeforeClass
  public static void setUpClass() throws Exception {
    instance = new CollectionIndexTest();
    instance.beforeClass();

    // Original: setupSchema (line 30)
    if (instance.session.getMetadata().getSchema().existsClass("Collector")) {
      instance.session.getMetadata().getSchema().dropClass("Collector");
    }
    final var collector = instance.session.createClass("Collector");
    collector.createProperty("id", PropertyType.STRING);
    collector
        .createProperty("stringCollection", PropertyType.EMBEDDEDLIST,
            PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
  }

  /**
   * Original: afterMethod (line 43) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Override
  @After
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from Collector").close();
    session.commit();

    super.afterMethod();
  }

  /**
   * Original: testIndexCollection (line 53) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test01_IndexCollection() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));

    session.commit();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionInTx (line 77) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test02_IndexCollectionInTx() {
    try {
      session.begin();
      var collector = session.newEntity("Collector");
      collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdate (line 104) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test03_IndexCollectionUpdate() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    collector = collector;
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "bacon")));
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("bacon")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateInTx (line 129) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test04_IndexCollectionUpdateInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();
    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      collector = activeTx.load(collector);
      collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "bacon")));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(2, index.size(session));
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("bacon")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateInTxRollback (line 162) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test05_IndexCollectionUpdateInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    collector = collector;
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    collector = activeTx.load(collector);
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "bacon")));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateAddItem (line 193) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test06_IndexCollectionUpdateAddItem() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE "
                + collector.getIdentity()
                + " set stringCollection = stringCollection || 'cookies'")
        .close();
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(3, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs") && !key.equals("cookies")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateAddItemInTx (line 226) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test07_IndexCollectionUpdateAddItemInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    try {
      session.begin();
      Entity loadedCollector = session.load(collector.getIdentity());
      loadedCollector.<List<String>>getProperty("stringCollection").add("cookies");
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(3, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs") && !key.equals("cookies")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateAddItemInTxRollback (line 260) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test08_IndexCollectionUpdateAddItemInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection").add("cookies");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateRemoveItemInTx (line 288) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test09_IndexCollectionUpdateRemoveItemInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    try {
      session.begin();
      Entity loadedCollector = session.load(collector.getIdentity());
      loadedCollector.<List<String>>getProperty("stringCollection").remove("spam");
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(1, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateRemoveItemInTxRollback (line 321) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test10_IndexCollectionUpdateRemoveItemInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection").remove("spam");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionUpdateRemoveItem (line 349) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test11_IndexCollectionUpdateRemoveItem() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    session
        .execute("UPDATE " + collector.getIdentity() + " remove stringCollection = 'spam'")
        .close();
    session.commit();

    final var index = getIndex("Collector.stringCollection");

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionRemove (line 377) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test12_IndexCollectionRemove() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.delete(collector);
    session.commit();

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(0, index.size(session));
  }

  /**
   * Original: testIndexCollectionRemoveInTx (line 390) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test13_IndexCollectionRemoveInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();
    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      session.delete(activeTx.<Entity>load(collector));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(0, index.size(session));
  }

  /**
   * Original: testIndexCollectionRemoveInTxRollback (line 411) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test14_IndexCollectionRemoveInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(collector));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  /**
   * Original: testIndexCollectionSQL (line 439) Location:
   * tests/src/test/java/com/jetbrains/youtrackdb/auto/CollectionIndexTest.java
   */
  @Test
  public void test15_IndexCollectionSQL() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var result =
        executeQuery("select * from Collector where stringCollection contains ?", "eggs");
    Assert.assertNotNull(result);
    Assert.assertEquals(1, result.size());
    Assert.assertEquals(
        List.of("spam", "eggs"),
        result.get(0).getProperty("stringCollection")
    );
    session.commit();
  }
}
