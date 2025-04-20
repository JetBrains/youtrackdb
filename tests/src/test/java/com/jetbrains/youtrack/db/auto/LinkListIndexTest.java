package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 21.03.12
 */
@Test
public class LinkListIndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public LinkListIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

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

    var result = session.query("select from LinkListIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (!session.getStorage().isRemote()) {
      final var index = getIndex("linkCollectionIndex");
      Assert.assertEquals(index.size(session), 0);
    }

    super.afterMethod();
  }

  public void testIndexCollection() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionInTx() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity()) && !key.equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionUpdate() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionUpdateInTx() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionUpdateInTxRollback() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionUpdateAddItem() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
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
  }

  public void testIndexCollectionUpdateAddItemInTx() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
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
  }

  public void testIndexCollectionUpdateAddItemInTxRollback() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionUpdateRemoveItemInTx() {
    checkEmbeddedDB();

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
      loadedDocument.<List>getProperty("linkCollection").remove(docTwo.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionUpdateRemoveItemInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkListIndexTestClass"));
    document.setProperty("linkCollection",
        session.newLinkList(List.of(docOne.getIdentity(), docTwo.getIdentity())));

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<List>getProperty("linkCollection").remove(docTwo.getIdentity());

    session.rollback();

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionUpdateRemoveItem() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexCollectionRemove() {
    checkEmbeddedDB();

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

    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexCollectionRemoveInTx() {
    checkEmbeddedDB();

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
    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexCollectionRemoveInTxRollback() {
    checkEmbeddedDB();

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

    var index = getIndex("linkCollectionIndex");

    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keyIterator;
    try (var indexKeyStream = index.keyStream()) {
      keyIterator = indexKeyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
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
        result.next().<List>getProperty("linkCollection"));
    session.commit();
  }
}
