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
package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Iterator;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

@Test
public class CollectionIndexTest extends BaseDBTest {
  @BeforeClass
  public void setupSchema() {
    if (session.getMetadata().getSchema().existsClass("Collector")) {
      session.getMetadata().getSchema().dropClass("Collector");
    }
    final var collector = session.createClass("Collector");
    collector.createProperty("id", PropertyType.STRING);
    collector
        .createProperty("stringCollection", PropertyType.EMBEDDEDLIST,
            PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
  }

  @Override
  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from Collector").close();
    session.commit();

    super.afterMethod();
  }

  public void testIndexCollection() {

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));

    session.commit();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionInTx() {

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
    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdate() {

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "bacon")));
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("bacon")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateInTx() {

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

    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("bacon")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateInTxRollback() {

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    collector = activeTx.load(collector);
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "bacon")));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");

    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateAddItem() {

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
    session.begin();
    Assert.assertEquals(index.size(session), 3);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs") && !key.equals("cookies")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateAddItemInTx() {

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

    session.begin();
    Assert.assertEquals(index.size(session), 3);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs") && !key.equals("cookies")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateAddItemInTxRollback() {

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection").add("cookies");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateRemoveItemInTx() {

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
    session.begin();
    Assert.assertEquals(index.size(session), 1);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateRemoveItemInTxRollback() {

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection").remove("spam");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateRemoveItem() {

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

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionRemove() {

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.delete(collector);
    session.commit();

    final var index = getIndex("Collector.stringCollection");

    session.begin();
    Assert.assertEquals(index.size(session), 0);
    session.rollback();
  }

  public void testIndexCollectionRemoveInTx() {

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

    session.begin();
    Assert.assertEquals(index.size(session), 0);
    session.rollback();
  }

  public void testIndexCollectionRemoveInTxRollback() {

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(collector));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    Assert.assertEquals(index.size(session), 2);
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionSQL() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var result =
        executeQuery("select * from Collector where stringCollection contains ?", "eggs");
    Assert.assertNotNull(result);
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(
        List.of("spam", "eggs"),
        result.get(0).getProperty("stringCollection")
    );
    session.commit();
  }
}
