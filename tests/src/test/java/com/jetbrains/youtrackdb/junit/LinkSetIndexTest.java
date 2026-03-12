package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for link set index operations.
 *
 * @since 3/28/14
 */
public class LinkSetIndexTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

    final var ridBagIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkSetIndexTestClass");

    ridBagIndexTestClass.createProperty("linkSet", PropertyType.LINKSET);

    ridBagIndexTestClass.createIndex("linkSetIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "linkSet");
    session.close();
  }

  @Override
  @BeforeEach
  void beforeEach() throws Exception {
    session = createSessionInstance();
  }

  @Override
  @AfterEach
  void afterEach() throws Exception {
    session.begin();
    session.execute("DELETE FROM LinkSetIndexTestClass").close();
    session.commit();

    session.begin();
    var result = session.execute("select from LinkSetIndexTestClass");
    assertEquals(0, result.stream().count());

    if (session.getStorage().isRemote()) {
      var index =
          session.getSharedContext().getIndexManager().getIndex("linkSetIndex");
      assertEquals(0, index.size(session));
    }
    session.commit();

    session.close();
  }

  @Test
  void testIndexLinkSet() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetInTx() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    session.commit();

    try {
      session.begin();
      final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
      final var linkSet = session.newLinkSet();
      var activeTx1 = session.getActiveTransaction();
      linkSet.add(activeTx1.<EntityImpl>load(docOne));
      var activeTx = session.getActiveTransaction();
      linkSet.add(activeTx.<EntityImpl>load(docTwo));

      document.setProperty("linkSet", linkSet);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdate() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    final var linkSetTwo = session.newLinkSet();
    linkSetTwo.add(docOne);
    linkSetTwo.add(docThree);

    document.setProperty("linkSet", linkSetTwo);

    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateInTx() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    session.commit();

    try {
      session.begin();

      var activeTx2 = session.getActiveTransaction();
      document = activeTx2.load(document);
      final var linkSetTwo = session.newLinkSet();
      var activeTx1 = session.getActiveTransaction();
      linkSetTwo.add(activeTx1.<EntityImpl>load(docOne));
      var activeTx = session.getActiveTransaction();
      linkSetTwo.add(activeTx.<EntityImpl>load(docThree));

      document.setProperty("linkSet", linkSetTwo);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateInTxRollback() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    document.setProperty("linkSet", linkSetOne);

    session.commit();

    session.begin();

    var activeTx2 = session.getActiveTransaction();
    document = activeTx2.load(document);
    final var linkSetTwo = session.newLinkSet();
    var activeTx1 = session.getActiveTransaction();
    linkSetTwo.add(activeTx1.<EntityImpl>load(docOne));
    var activeTx = session.getActiveTransaction();
    linkSetTwo.add(activeTx.<EntityImpl>load(docThree));

    document.setProperty("linkSet", linkSetTwo);

    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateAddItem() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE "
                + document.getIdentity()
                + " set linkSet = linkSet || "
                + docThree.getIdentity())
        .close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkSetIndex");
    assertEquals(3, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateAddItemInTx() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      var activeTx = session.getActiveTransaction();
      loadedDocument.<Set<Identifiable>>getProperty("linkSet").add(
          activeTx.<EntityImpl>load(docThree));

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkSetIndex");
    assertEquals(3, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateAddItemInTxRollback() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    var activeTx = session.getActiveTransaction();
    loadedDocument.<Set<Identifiable>>getProperty("linkSet").add(
        activeTx.<EntityImpl>load(docThree));

    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateRemoveItemInTx() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Set<Identifiable>>getProperty("linkSet").remove(docTwo.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkSetIndex");
    assertEquals(1, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateRemoveItemInTxRollback() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Set<Identifiable>>getProperty("linkSet").remove(docTwo.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetUpdateRemoveItem() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session
        .execute("UPDATE " + document.getIdentity() + " remove linkSet = " + docTwo.getIdentity())
        .close();
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkSetIndex");
    assertEquals(1, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetRemove() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));

    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.commit();

    var index = getIndex("linkSetIndex");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  void testIndexLinkSetRemoveInTx() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));

    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

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

    var index = getIndex("linkSetIndex");
    session.begin();
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  void testIndexLinkSetRemoveInTxRollback() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();

    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkSetIndex");
    assertEquals(2, index.size(session));

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexLinkSetSQL() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSetOne = session.newLinkSet();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docThree);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    var result =
        session.query(
            "select * from LinkSetIndexTestClass where linkSet contains ?", docOne.getIdentity());

    List<Identifiable> listResult =
        new ArrayList<>(result.next().<Set<Identifiable>>getProperty("linkSet"));
    assertEquals(2, listResult.size());
    assertTrue(
        listResult.containsAll(Arrays.asList(docOne.getIdentity(), docTwo.getIdentity())));
    session.commit();
  }
}
