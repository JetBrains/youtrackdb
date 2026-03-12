package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class CollectionIndexTest extends BaseDBJUnit5Test {

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();

    if (session.getMetadata().getSchema().existsClass("Collector")) {
      session.getMetadata().getSchema().dropClass("Collector");
    }
    final var collector = session.createClass("Collector");
    collector.createProperty("id", PropertyType.STRING);
    collector.createProperty("stringCollection", PropertyType.EMBEDDEDLIST,
        PropertyType.STRING)
        .createIndex(SchemaClass.INDEX_TYPE.NOTUNIQUE);
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    session.begin();
    session.execute("delete from Collector").close();
    session.commit();
    super.afterEach();
  }

  @Test
  void testIndexCollection() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionInTx() {
    try {
      session.begin();
      var collector = session.newEntity("Collector");
      collector.setProperty("stringCollection",
          session.newEmbeddedList(List.of("spam", "eggs")));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdate() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "bacon")));
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("bacon")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();
    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      collector = activeTx.load(collector);
      collector.setProperty("stringCollection",
          session.newEmbeddedList(List.of("spam", "bacon")));
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("bacon")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    collector = activeTx.load(collector);
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "bacon")));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateAddItem() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    session.execute("UPDATE " + collector.getIdentity()
        + " set stringCollection = stringCollection || 'cookies'").close();
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(3, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")
            && !key.equals("cookies")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateAddItemInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();
    try {
      session.begin();
      Entity loadedCollector = session.load(collector.getIdentity());
      loadedCollector.<List<String>>getProperty("stringCollection")
          .add("cookies");
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(3, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")
            && !key.equals("cookies")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateAddItemInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection")
        .add("cookies");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateRemoveItemInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();
    try {
      session.begin();
      Entity loadedCollector = session.load(collector.getIdentity());
      loadedCollector.<List<String>>getProperty("stringCollection")
          .remove("spam");
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(1, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateRemoveItemInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    Entity loadedCollector = session.load(collector.getIdentity());
    loadedCollector.<List<String>>getProperty("stringCollection")
        .remove("spam");
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionUpdateRemoveItem() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    session.execute("UPDATE " + collector.getIdentity()
        + " remove stringCollection = 'spam'").close();
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionRemove() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.delete(collector);
    session.commit();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  void testIndexCollectionRemoveInTx() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
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
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  void testIndexCollectionRemoveInTxRollback() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    session.delete(activeTx.<Entity>load(collector));
    session.rollback();

    final var index = getIndex("Collector.stringCollection");
    session.begin();
    assertEquals(2, index.size(session));
    session.rollback();

    var tx = session.begin();
    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(tx.getAtomicOperation())) {
      keysIterator = keyStream.iterator();
      while (keysIterator.hasNext()) {
        var key = (String) keysIterator.next();
        if (!key.equals("spam") && !key.equals("eggs")) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexCollectionSQL() {
    session.begin();
    var collector = session.newEntity("Collector");
    collector.setProperty("stringCollection",
        session.newEmbeddedList(List.of("spam", "eggs")));
    session.commit();

    session.begin();
    var result =
        executeQuery("select * from Collector where stringCollection contains ?",
            "eggs");
    assertNotNull(result);
    assertEquals(1, result.size());
    assertEquals(
        List.of("spam", "eggs"),
        result.get(0).getProperty("stringCollection"));
    session.commit();
  }
}
