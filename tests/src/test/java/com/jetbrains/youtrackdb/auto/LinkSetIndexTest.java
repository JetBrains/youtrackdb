package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;

/**
 * @since 3/28/14
 */
public class LinkSetIndexTest extends BaseDBTest {

  @BeforeClass
  public void setupSchema() {
    final var ridBagIndexTestClass =
        session.getMetadata().getSchema().createClass("LinkSetIndexTestClass");

    ridBagIndexTestClass.createProperty("linkSet", PropertyType.LINKSET);

    ridBagIndexTestClass.createIndex("linkSetIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "linkSet");
    session.close();
  }

  @Override
  @BeforeMethod
  public void beforeMethod() {
    session = createSessionInstance();
  }

  @Override
  @AfterMethod
  public void afterMethod() {

    session.begin();
    session.execute("DELETE FROM LinkSetIndexTestClass").close();
    session.commit();

    session.begin();
    var result = session.execute("select from LinkSetIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (session.getStorage().isRemote()) {
      var index =
          session.getSharedContext().getIndexManager().getIndex("linkSetIndex");
      Assert.assertEquals(index.size(session), 0);
    }
    session.commit();

    session.close();
  }

  public void testIndexLinkSet() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetInTx() {

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

    var activeTx = session.getActiveTransaction();
    var ato = activeTx.getAtomicOperation();
    var index = getIndex("linkSetIndex");
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetUpdate() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetUpdateInTx() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docThree.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetUpdateInTxRollback() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetUpdateAddItem() {

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
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
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
    session.rollback();
  }

  public void testIndexLinkSetUpdateAddItemInTx() {

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
    Assert.assertEquals(index.size(session), 3);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
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
    session.rollback();
  }

  public void testIndexLinkSetUpdateAddItemInTxRollback() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetUpdateRemoveItemInTx() {

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
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetUpdateRemoveItemInTxRollback() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetUpdateRemoveItem() {

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
    Assert.assertEquals(index.size(session), 1);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetRemove() {

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
    Assert.assertEquals(index.size(session), 2);

    Iterator<Object> keysIterator;
    try (var keyStream = index.keyStream(ato)) {
      keysIterator = keyStream.iterator();

      while (keysIterator.hasNext()) {
        var key = (Identifiable) keysIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())
            && !key.getIdentity().equals(docTwo.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexLinkSetSQL() {

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
