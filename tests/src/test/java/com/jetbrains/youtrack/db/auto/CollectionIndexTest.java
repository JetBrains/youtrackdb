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
package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

@Test
public class CollectionIndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public CollectionIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

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

  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("delete from Collector").close();
    session.commit();

    super.afterMethod();
  }

  public void testIndexCollection() {
    checkEmbeddedDB();

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));

    session.commit();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(index.size(session), 2);

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

  public void testIndexCollectionInTx() {
    checkEmbeddedDB();

    try {
      session.begin();
      var collector = session.newEntity("Collector");
      collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(index.size(session), 2);

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

  public void testIndexCollectionUpdate() {
    checkEmbeddedDB();

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
    collector = collector;
    collector.setProperty("stringCollection", Arrays.asList("spam", "bacon"));
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(index.size(session), 2);

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

  public void testIndexCollectionUpdateInTx() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
    session.begin();
    collector = collector;
    session.commit();
    try {
      session.begin();
      collector = session.bindToSession(collector);
      collector.setProperty("stringCollection", Arrays.asList("spam", "bacon"));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(index.size(session), 2);
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

  public void testIndexCollectionUpdateInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
    collector = collector;
    session.commit();

    session.begin();
    collector = session.bindToSession(collector);
    collector.setProperty("stringCollection", Arrays.asList("spam", "bacon"));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(index.size(session), 2);

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

  public void testIndexCollectionUpdateAddItem() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));

    session.begin();
    collector = collector;
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
    Assert.assertEquals(index.size(session), 3);

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

  public void testIndexCollectionUpdateAddItemInTx() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", new ArrayList<>(Arrays.asList("spam", "eggs")));
    session.begin();
    collector = collector;
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

    Assert.assertEquals(index.size(session), 3);

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

  public void testIndexCollectionUpdateAddItemInTxRollback() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", new ArrayList<>(Arrays.asList("spam", "eggs")));
    session.begin();
    collector = collector;
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection").add("cookies");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(index.size(session), 2);

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

  public void testIndexCollectionUpdateRemoveItemInTx() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", new ArrayList<>(Arrays.asList("spam", "eggs")));
    session.begin();
    collector = collector;
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
    Assert.assertEquals(index.size(session), 1);

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

  public void testIndexCollectionUpdateRemoveItemInTxRollback() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", new ArrayList<>(Arrays.asList("spam", "eggs")));
    session.begin();
    collector = collector;
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection").remove("spam");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(index.size(session), 2);

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

  public void testIndexCollectionUpdateRemoveItem() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
    session.begin();
    collector = collector;
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

  public void testIndexCollectionRemove() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
    session.begin();
    collector = collector;
    session.delete(collector);
    session.commit();

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexCollectionRemoveInTx() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
    session.begin();
    collector = collector;
    session.commit();
    try {
      session.begin();
      session.delete(session.bindToSession(collector));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");

    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexCollectionRemoveInTxRollback() {
    checkEmbeddedDB();

    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));
    session.begin();
    collector = collector;
    session.commit();

    session.begin();
    session.delete(session.bindToSession(collector));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    Assert.assertEquals(index.size(session), 2);

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

  public void testIndexCollectionSQL() {
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection", Arrays.asList("spam", "eggs"));

    session.begin();
    session.commit();

    var result =
        executeQuery("select * from Collector where stringCollection contains ?", "eggs");
    Assert.assertNotNull(result);
    Assert.assertEquals(result.size(), 1);
    Assert.assertEquals(
        Arrays.asList("spam", "eggs"), result.get(0).getProperty("stringCollection"));
  }
}
