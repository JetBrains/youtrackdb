package com.jetbrains.youtrack.db.auto;

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;

/**
 * @since 3/28/14
 */
@SuppressWarnings("deprecation")
public class LinkSetIndexTest extends BaseDBTest {

  @Parameters(value = "remote")
  public LinkSetIndexTest(@Optional Boolean remote) {
    super(remote != null && remote);
  }

  @BeforeClass
  public void setupSchema() {
    final var ridBagIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkSetIndexTestClass");

    ridBagIndexTestClass.createProperty("linkSet", PropertyType.LINKSET);

    ridBagIndexTestClass.createIndex("linkSetIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "linkSet");
    session.close();
  }

  @BeforeMethod
  public void beforeMethod() {
    session = createSessionInstance();
  }

  @AfterMethod
  public void afterMethod() {
    checkEmbeddedDB();

    session.begin();
    session.execute("DELETE FROM LinkSetIndexTestClass").close();
    session.commit();

    session.begin();
    var result = session.execute("select from LinkSetIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (session.getStorage().isRemote()) {
      var index =
          session.getMetadata().getIndexManagerInternal().getIndex(session, "linkSetIndex");
      Assert.assertEquals(index.size(session), 0);
    }
    session.commit();

    session.close();
  }

  public void testIndexLinkSet() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final var linkSet = session.newLinkSet();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetInTx() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdate() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateInTx() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateInTxRollback() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateAddItem() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateAddItemInTx() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateAddItemInTxRollback() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateRemoveItemInTx() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateRemoveItemInTxRollback() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetUpdateRemoveItem() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetRemove() {
    checkEmbeddedDB();

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
    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexLinkSetRemoveInTx() {
    checkEmbeddedDB();

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
    Assert.assertEquals(index.size(session), 0);
  }

  public void testIndexLinkSetRemoveInTxRollback() {
    checkEmbeddedDB();

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

    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream()) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
  }

  public void testIndexLinkSetSQL() {
    checkEmbeddedDB();

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
    Assert.assertEquals(listResult.size(), 2);
    Assert.assertTrue(
        listResult.containsAll(Arrays.asList(docOne.getIdentity(), docTwo.getIdentity())));
    session.commit();
  }
}
