// Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest
package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for link list index operations.
 *
 * @since 21.03.12
 */
public class LinkListIndexTest extends BaseDBJUnit5Test {

  @Override
  @BeforeAll
  void beforeAll() throws Exception {
    super.beforeAll();

    final var linkListIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkListIndexTestClass");

    linkListIndexTestClass.createProperty("linkCollection", PropertyType.LINKLIST);

    linkListIndexTestClass.createIndex(
        "linkCollectionIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "linkCollection");
  }

  @Override
  @AfterAll
  void afterAll() throws Exception {
    session = acquireSession();
    session.getMetadata().getSchema().dropClass("LinkListIndexTestClass");
    super.afterAll();
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    session.begin();
    session.execute("DELETE FROM LinkListIndexTestClass").close();
    session.commit();

    session.begin();
    var result = session.query("select from LinkListIndexTestClass");
    assertEquals(0, result.stream().count());

    if (!session.getStorage().isRemote()) {
      final var index = getIndex("linkCollectionIndex");
      assertEquals(0, index.size(session));
    }
    session.rollback();

    super.afterEach();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollection
  @Test
  void testIndexCollection() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionInTx
  @Test
  void testIndexCollectionInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    session.commit();

    try {
      session.begin();
      final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
      document.setProperty("linkCollection",
          session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkCollectionIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdate
  @Test
  void testIndexCollectionUpdate() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));

    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docThree.getIdentity())));

    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkCollectionIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateInTx
  @Test
  void testIndexCollectionUpdateInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      document = activeTx.load(document);
      document.setProperty("linkCollection",
          session.newLinkList(List.of(docOne.getIdentity(), docThree.getIdentity())));

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkCollectionIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateInTxRollback
  @Test
  void testIndexCollectionUpdateInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docThree.getIdentity())));

    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkCollectionIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateAddItem
  @Test
  void testIndexCollectionUpdateAddItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE "
                + document.getIdentity()
                + " set linkCollection = linkCollection || "
                + docThree.getIdentity())
        .close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkCollectionIndex");
    assertEquals(3, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateAddItemInTx
  @Test
  void testIndexCollectionUpdateAddItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<List<Identifiable>>getProperty("linkCollection")
          .add(docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    assertEquals(3, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateAddItemInTxRollback
  @Test
  void testIndexCollectionUpdateAddItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<List<Identifiable>>getProperty("linkCollection")
        .add(docThree.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateRemoveItemInTx
  @Test
  void testIndexCollectionUpdateRemoveItemInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<List<?>>getProperty("linkCollection").remove(docTwo.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    assertEquals(1, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateRemoveItemInTxRollback
  @Test
  void testIndexCollectionUpdateRemoveItemInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<List<?>>getProperty("linkCollection").remove(docTwo.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionUpdateRemoveItem
  @Test
  void testIndexCollectionUpdateRemoveItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    session.execute(
        "UPDATE " + document.getIdentity() + " remove linkCollection = "
            + docTwo.getIdentity());
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    assertEquals(1, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionRemove
  @Test
  void testIndexCollectionRemove() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    document.delete();
    session.commit();

    var index = getIndex("linkCollectionIndex");

    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionRemoveInTx
  @Test
  void testIndexCollectionRemoveInTx() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      document = activeTx.load(document);
      document.delete();
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkCollectionIndex");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionRemoveInTxRollback
  @Test
  void testIndexCollectionRemoveInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkCollectionIndex");

    assertEquals(2, index.size(session));

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  // Migrated from: com.jetbrains.youtrackdb.auto.LinkListIndexTest#testIndexCollectionSQL
  @Test
  void testIndexCollectionSQL() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    var result =
        session.query(
            "select * from LinkListIndexTestClass where linkCollection contains ?",
            docOne.getIdentity());
    assertEquals(
        result.next().<List<?>>getProperty("linkCollection"),
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()));
    session.commit();
  }
}
