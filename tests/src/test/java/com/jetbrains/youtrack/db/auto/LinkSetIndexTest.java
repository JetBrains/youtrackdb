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
    session.command("DELETE FROM LinkSetIndexTestClass").close();
    session.commit();

    var result = session.command("select from LinkSetIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (session.getStorage().isRemote()) {
      var index =
          session.getMetadata().getIndexManagerInternal().getIndex(session, "linkSetIndex");
      Assert.assertEquals(index.size(session), 0);
    }

    session.close();
  }

  public void testIndexLinkSet() {
    checkEmbeddedDB();

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final Set<Identifiable> linkSet = new HashSet<>();
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
      final Set<Identifiable> linkSet = new HashSet<>();
      linkSet.add(session.bindToSession(docOne));
      linkSet.add(session.bindToSession(docTwo));

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
    final Set<Identifiable> linkSetOne = new HashSet<>();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    final Set<Identifiable> linkSetTwo = new HashSet<>();
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
    final Set<Identifiable> linkSetOne = new HashSet<>();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    session.commit();

    try {
      session.begin();

      document = session.bindToSession(document);
      final Set<Identifiable> linkSetTwo = new HashSet<>();
      linkSetTwo.add(session.bindToSession(docOne));
      linkSetTwo.add(session.bindToSession(docThree));

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

    final Set<Identifiable> linkSetOne = new HashSet<>();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    var document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    document.setProperty("linkSet", linkSetOne);

    session.commit();

    session.begin();

    document = session.bindToSession(document);
    final Set<Identifiable> linkSetTwo = new HashSet<>();
    linkSetTwo.add(session.bindToSession(docOne));
    linkSetTwo.add(session.bindToSession(docThree));

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
    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session
        .command(
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
    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Set<Identifiable>>getProperty("linkSet").add(session.bindToSession(docThree));

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
    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Set<Identifiable>>getProperty("linkSet").add(session.bindToSession(docThree));

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
    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      EntityImpl loadedDocument = session.load(document.getIdentity());
      loadedDocument.<Set<Identifiable>>getProperty("linkSet").remove(docTwo);

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
    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);
    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    EntityImpl loadedDocument = session.load(document.getIdentity());
    loadedDocument.<Set<Identifiable>>getProperty("linkSet").remove(docTwo);

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
    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session
        .command("UPDATE " + document.getIdentity() + " remove linkSet = " + docTwo.getIdentity())
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

    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session.bindToSession(document).delete();
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

    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    try {
      session.begin();
      session.bindToSession(document).delete();
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
    final Set<Identifiable> linkSet = new HashSet<>();

    linkSet.add(docOne);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    session.begin();
    session.bindToSession(document).delete();
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
    final Set<Identifiable> linkSetOne = new HashSet<>();
    linkSetOne.add(docOne);
    linkSetOne.add(docTwo);

    document.setProperty("linkSet", linkSetOne);

    document = ((EntityImpl) session.newEntity("LinkSetIndexTestClass"));
    final Set<Identifiable> linkSet = new HashSet<>();
    linkSet.add(docThree);
    linkSet.add(docTwo);

    document.setProperty("linkSet", linkSet);

    session.commit();

    var result =
        session.query(
            "select * from LinkSetIndexTestClass where linkSet contains ?", docOne.getIdentity());

    List<Identifiable> listResult =
        new ArrayList<>(result.next().<Set<Identifiable>>getProperty("linkSet"));
    Assert.assertEquals(listResult.size(), 2);
    Assert.assertTrue(
        listResult.containsAll(Arrays.asList(docOne.getIdentity(), docTwo.getIdentity())));
  }
}
