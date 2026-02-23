package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Tests for link list index operations.
 *
 * @since 21.03.12
 */
@Test
public class LinkListIndexTest extends BaseDBTest {

  @BeforeClass
  public void setupSchema() {
    final var linkListIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkListIndexTestClass");

    linkListIndexTestClass.createProperty("linkCollection", PropertyType.LINKLIST);

    linkListIndexTestClass.createIndex(
        "linkCollectionIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, "linkCollection");
  }

  @AfterClass
  public void destroySchema() {
    session = acquireSession();
    session.getMetadata().getSchema().dropClass("LinkListIndexTestClass");
  }

  @Override
  @AfterMethod
  public void afterMethod() throws Exception {
    session.begin();
    session.execute("DELETE FROM LinkListIndexTestClass").close();
    session.commit();

    session.begin();
    var result = session.query("select from LinkListIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (!session.getStorage().isRemote()) {
      final var index = getIndex("linkCollectionIndex");
      Assert.assertEquals(index.size(session), 0);
    }
    session.rollback();

    super.afterMethod();
  }

  public void testIndexCollection() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionInTx() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity()) && !key.equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdate() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateInTx() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateInTxRollback() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateAddItem() {

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
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateAddItemInTx() {

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
      loadedDocument.<List<Identifiable>>getProperty("linkCollection").add(docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateAddItemInTxRollback() {

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
    loadedDocument.<List<Identifiable>>getProperty("linkCollection").add(docThree.getIdentity());

    session.rollback();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateRemoveItemInTx() {

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
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateRemoveItemInTxRollback() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionUpdateRemoveItem() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    session.execute(
        "UPDATE " + document.getIdentity() + " remove linkCollection = " + docTwo.getIdentity());
    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionRemove() {

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
    Assert.assertEquals(index.size(session), 0);
    session.rollback();
  }

  public void testIndexCollectionRemoveInTx() {

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
    Assert.assertEquals(index.size(session), 0);
    session.rollback();
  }

  public void testIndexCollectionRemoveInTxRollback() {

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

    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream(ato)) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexCollectionSQL() {
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
    Assert.assertEquals(
        Arrays.asList(docOne.getIdentity(), docTwo.getIdentity()),
        result.next().<List<?>>getProperty("linkCollection"));
    session.commit();
  }
}
