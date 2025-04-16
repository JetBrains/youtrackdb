package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

/**
 * @since 1/30/14
 */
@Test
public class LinkBagIndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public LinkBagIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void setupSchema() {
    final var ridBagIndexTestClass =
        session.getMetadata().getSchema().createClass("RidBagIndexTestClass");

    ridBagIndexTestClass.createProperty("ridBag", PropertyType.LINKBAG);

    ridBagIndexTestClass.createIndex("ridBagIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "ridBag");

    session.close();
  }

  @AfterClass
  public void destroySchema() {
    if (session.isClosed()) {
      session = acquireSession();
    }

    session.getMetadata().getSchema().dropClass("RidBagIndexTestClass");
    session.close();
  }

  @AfterMethod
  public void afterMethod() {
    session.begin();
    session.execute("DELETE FROM RidBagIndexTestClass").close();
    session.commit();

    var result = session.query("select from RidBagIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (!session.getStorage().isRemote()) {
      final var index = getIndex("ridBagIndex");
      Assert.assertEquals(index.size(session), 0);
    }
  }

  public void testIndexRidBag() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = session.newEntity();
    final var docTwo = session.newEntity();

    final var document = session.newEntity("RidBagIndexTestClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    try {
      final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
      final var ridBag = new LinkBag(session);
      ridBag.add(docOne.getIdentity());
      ridBag.add(docTwo.getIdentity());

      document.setProperty("ridBag", ridBag);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagUpdate() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBagOne = new LinkBag(session);
    ridBagOne.add(docOne.getIdentity());
    ridBagOne.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBagOne);

    session.commit();

    session.begin();
    final var ridBagTwo = new LinkBag(session);
    ridBagTwo.add(docOne.getIdentity());
    ridBagTwo.add(docThree.getIdentity());

    var activeTx1 = session.getActiveTransaction();
    document = activeTx1.load(document);
    document.setProperty("ridBag", ridBagTwo);

    var activeTx = session.getActiveTransaction();
    activeTx.load(document);

    session.commit();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagUpdateInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBagOne = new LinkBag(session);
    ridBagOne.add(docOne.getIdentity());
    ridBagOne.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBagOne);

    session.commit();

    try {
      session.begin();

      var activeTx = session.getActiveTransaction();
      document = activeTx.load(document);
      final var ridBagTwo = new LinkBag(session);
      ridBagTwo.add(docOne.getIdentity());
      ridBagTwo.add(docThree.getIdentity());

      document.setProperty("ridBag", ridBagTwo);

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagUpdateInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var ridBagOne = new LinkBag(session);
    ridBagOne.add(docOne.getIdentity());
    ridBagOne.add(docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    document.setProperty("ridBag", ridBagOne);

    Assert.assertTrue(session.commit());

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    final var ridBagTwo = new LinkBag(session);
    ridBagTwo.add(docOne.getIdentity());
    ridBagTwo.add(docThree.getIdentity());

    document.setProperty("ridBag", ridBagTwo);

    session.rollback();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagUpdateAddItem() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    session
        .execute(
            "UPDATE "
                + document.getIdentity()
                + " set ridBag = ridBag || "
                + docThree.getIdentity())
        .close();
    session.commit();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 3);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

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

  public void testIndexRidBagUpdateAddItemInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    try {
      session.begin();
      var activeTx = session.getActiveTransaction();
      docThree = activeTx.load(docThree);
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<LinkBag>getProperty("ridBag").add(docThree.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 3);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

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

  public void testIndexRidBagUpdateAddItemInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    var docThree = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    docThree = activeTx.load(docThree);
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<LinkBag>getProperty("ridBag").add(docThree.getIdentity());

    session.rollback();

    final var index = getIndex("ridBagIndex");

    Assert.assertEquals(index.size(session), 2);
    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagUpdateRemoveItemInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());
    document.setProperty("ridBag", ridBag);

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<LinkBag>getProperty("ridBag").remove(docTwo.getIdentity());

      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    final var index = getIndex("ridBagIndex");

    Assert.assertEquals(index.size(session), 1);
    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagUpdateRemoveItemInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());
    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<LinkBag>getProperty("ridBag").remove(docTwo.getIdentity());

    session.rollback();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagUpdateRemoveItem() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    //noinspection deprecation
    session.begin();
    session
        .execute("UPDATE " + document.getIdentity() + " remove ridBag = " + docTwo.getIdentity())
        .close();
    session.commit();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 1);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagRemove() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));

    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    document.delete();
    session.commit();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexRidBagRemoveInTx() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));

    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

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

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexRidBagRemoveInTxRollback() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    session.begin();
    var activeTx = session.getActiveTransaction();
    activeTx.<EntityImpl>load(document).delete();
    session.rollback();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream()) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();

        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexRidBagSQL() {
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBagOne = new LinkBag(session);
    ridBagOne.add(docOne.getIdentity());
    ridBagOne.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBagOne);

    document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    var ridBag = new LinkBag(session);
    ridBag.add(docThree.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    var result =
        session.query(
            "select * from RidBagIndexTestClass where ridBag contains ?", docOne.getIdentity());
    var res = result.next();

    var resultSet = new HashSet<>();
    for (Identifiable identifiable : res.<LinkBag>getProperty("ridBag")) {
      resultSet.add(identifiable);
    }
    result.close();

    Assert.assertEquals(Set.of(docOne.getIdentity(), docTwo.getIdentity()), resultSet);
  }
}
