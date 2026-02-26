package com.jetbrains.youtrackdb.auto;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * Tests for link bag index operations.
 *
 * @since 1/30/14
 */
@Test
public class LinkBagIndexTest extends BaseDBTest {
  @BeforeClass
  public void setupSchema() {
    final var ridBagIndexTestClass =
        session.getMetadata().getSchema().createClass("RidBagIndexTestClass");

    ridBagIndexTestClass.createProperty("ridBag", PropertyType.LINKBAG);

    ridBagIndexTestClass.createIndex("ridBagIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "ridBag");

    final var vertexClass =
        session.getMetadata().getSchema().createVertexClass("RidBagIndexVertexClass");

    vertexClass.createProperty("ridBag", PropertyType.LINKBAG);

    vertexClass.createIndex("ridBagVertexIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE,
        "ridBag");

    session.createEdgeClass("RidBagIndexEdgeClass");

    session.close();
  }

  @AfterClass
  public void destroySchema() {
    if (session.isClosed()) {
      session = acquireSession();
    }

    session.getMetadata().getSchema().dropClass("RidBagIndexEdgeClass");
    session.getMetadata().getSchema().dropClass("RidBagIndexVertexClass");
    session.getMetadata().getSchema().dropClass("RidBagIndexTestClass");
    session.close();
  }

  @Override
  @AfterMethod
  public void afterMethod() {
    session.begin();
    session.execute("DELETE FROM RidBagIndexTestClass").close();
    session.execute("DELETE VERTEX RidBagIndexVertexClass").close();
    session.commit();

    session.begin();
    var result = session.query("select from RidBagIndexTestClass");
    Assert.assertEquals(result.stream().count(), 0);

    if (!session.getStorage().isRemote()) {
      final var index = getIndex("ridBagIndex");
      Assert.assertEquals(index.size(session), 0);

      final var vertexIndex = getIndex("ridBagVertexIndex");
      Assert.assertEquals(vertexIndex.size(session), 0);
    }
    result.close();
    session.commit();
  }

  public void testIndexRidBag() {

    session.begin();
    final var docOne = session.newEntity();
    final var docTwo = session.newEntity();

    final var document = session.newEntity("RidBagIndexTestClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  public void testIndexRidBagInTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  public void testIndexRidBagUpdate() {

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

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  public void testIndexRidBagUpdateInTx() {

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

    var activeTx2 = session.begin();
    var ato = activeTx2.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  public void testIndexRidBagUpdateInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var ridBagOne = new LinkBag(session);
    ridBagOne.add(docOne.getIdentity());
    ridBagOne.add(docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    document.setProperty("ridBag", ridBagOne);

    Assert.assertNotNull(session.commit());

    session.begin();
    var activeTx = session.getActiveTransaction();
    document = activeTx.load(document);
    final var ridBagTwo = new LinkBag(session);
    ridBagTwo.add(docOne.getIdentity());
    ridBagTwo.add(docThree.getIdentity());

    document.setProperty("ridBag", ridBagTwo);

    session.rollback();

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  /**
   * Verify that adding a lightweight entry to a persisted LinkBag via direct API
   * correctly adds the new key to the index while preserving existing entries.
   */
  public void testIndexRidBagUpdateAddItem() {

    // 1. Create a document with a LinkBag containing two lightweight entries and commit.
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

    // 2. Load the document from storage and add a third entry via direct API.
    session.begin();
    EntityImpl loaded = session.load(document.getIdentity());
    loaded.<LinkBag>getProperty("ridBag").add(docThree.getIdentity());
    session.commit();

    // 3. Verify the index contains all three keys.
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 3);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
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
    session.rollback();
  }

  public void testIndexRidBagUpdateAddItemInTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 3);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
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
    session.rollback();
  }

  public void testIndexRidBagUpdateAddItemInTxRollback() {

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

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");

    Assert.assertEquals(index.size(session), 2);
    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  public void testIndexRidBagUpdateRemoveItemInTx() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");

    Assert.assertEquals(index.size(session), 1);
    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexRidBagUpdateRemoveItemInTxRollback() {

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

    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  /**
   * Verify that removing a lightweight entry from a persisted LinkBag via direct API
   * correctly removes the key from the index while preserving remaining entries.
   */
  public void testIndexRidBagUpdateRemoveItem() {

    // 1. Create a document with a LinkBag containing two lightweight entries and commit.
    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    final var ridBag = new LinkBag(session);
    ridBag.add(docOne.getIdentity());
    ridBag.add(docTwo.getIdentity());

    document.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Load from storage and remove one entry via direct API.
    session.begin();
    EntityImpl loaded = session.load(document.getIdentity());
    loaded.<LinkBag>getProperty("ridBag").remove(docTwo.getIdentity());
    session.commit();

    // 3. Verify the index contains only the remaining key.
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 1);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          Assert.fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  public void testIndexRidBagRemove() {

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
    session.begin();
    Assert.assertEquals(index.size(session), 0);
    session.rollback();
  }

  public void testIndexRidBagRemoveInTx() {

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
    session.begin();
    Assert.assertEquals(index.size(session), 0);
    session.rollback();
  }

  public void testIndexRidBagRemoveInTxRollback() {

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

    activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();

    final var index = getIndex("ridBagIndex");
    Assert.assertEquals(index.size(session), 2);

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

    session.begin();
    var result =
        session.query(
            "select * from RidBagIndexTestClass where ridBag contains ?", docOne.getIdentity());
    var res = result.next();

    var resultSet = new HashSet<>();
    for (var ridPair : res.<LinkBag>getProperty("ridBag")) {
      resultSet.add(ridPair.primaryRid());
    }
    result.close();

    Assert.assertEquals(Set.of(docOne.getIdentity(), docTwo.getIdentity()), resultSet);
    session.commit();

  }

  /**
   * Verify that committing a vertex LinkBag with double-sided RidPair entries
   * (edge RID + target vertex RID) indexes both the primary and secondary RIDs.
   */
  public void testIndexRidBagWithPairsOnVertex() {
    // 1. Create a vertex with two heavyweight edges and populate ridBag
    //    with double-sided pairs (edgeRid, targetVertexRid).
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var target1 = session.newVertex("V");
    final var target2 = session.newVertex("V");
    final var edge1 = session.newStatefulEdge(vertex, target1, "RidBagIndexEdgeClass");
    final var edge2 = session.newStatefulEdge(vertex, target2, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    ridBag.add(edge2.getIdentity(), target2.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify all 4 RIDs (2 edges + 2 targets) are in the index.
    final var index = getIndex("ridBagVertexIndex");
    Assert.assertEquals(index.size(session), 4);

    var expectedKeys = Set.of(
        edge1.getIdentity(), target1.getIdentity(),
        edge2.getIdentity(), target2.getIdentity());
    try (var keyStream = index.keyStream()) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        Assert.assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
  }

  /**
   * Verify that a vertex LinkBag containing both a lightweight entry (single RID)
   * and a double-sided RidPair entry indexes all three RIDs correctly.
   */
  public void testIndexRidBagWithMixedSingleAndPairOnVertex() {
    // 1. Create a vertex ridBag with one lightweight entry and one double-sided pair.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newStatefulEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(single1.getIdentity());
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify the index contains 3 keys: single1 + edge1 + target1.
    final var index = getIndex("ridBagVertexIndex");
    Assert.assertEquals(index.size(session), 3);

    var expectedKeys = Set.of(
        single1.getIdentity(),
        edge1.getIdentity(), target1.getIdentity());
    try (var keyStream = index.keyStream()) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        Assert.assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
  }

  /**
   * Verify that incrementally adding a double-sided RidPair entry to a persisted
   * vertex LinkBag indexes both the primary and secondary RIDs via change tracking.
   */
  public void testIndexRidBagUpdateAddPairItemOnVertex() {
    // 1. Create a vertex with a lightweight-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newStatefulEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(single1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Load from storage, add a double-sided pair entry, and commit.
    session.begin();
    EntityImpl loaded = session.load(vertex.getIdentity());
    loaded.<LinkBag>getProperty("ridBag")
        .add(edge1.getIdentity(), target1.getIdentity());
    session.commit();

    // 3. Verify the index has 3 keys: single1 + edge1 + target1.
    final var index = getIndex("ridBagVertexIndex");
    Assert.assertEquals(index.size(session), 3);

    var expectedKeys = Set.of(
        single1.getIdentity(),
        edge1.getIdentity(), target1.getIdentity());
    try (var keyStream = index.keyStream()) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        Assert.assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
  }

  /**
   * Same as {@link #testIndexRidBagUpdateAddPairItemOnVertex()} but the add
   * operation is wrapped in a try/catch transaction block to verify commit safety.
   */
  public void testIndexRidBagUpdateAddPairItemOnVertexInTx() {
    // 1. Create a vertex with a lightweight-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newStatefulEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(single1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Add a double-sided pair in a guarded transaction block.
    try {
      session.begin();
      EntityImpl loaded = session.load(vertex.getIdentity());
      loaded.<LinkBag>getProperty("ridBag")
          .add(edge1.getIdentity(), target1.getIdentity());
      session.commit();
    } catch (Exception e) {
      session.rollback();
      throw e;
    }

    // 3. Verify the index has 3 keys: single1 + edge1 + target1.
    final var index = getIndex("ridBagVertexIndex");
    Assert.assertEquals(index.size(session), 3);

    var expectedKeys = Set.of(
        single1.getIdentity(),
        edge1.getIdentity(), target1.getIdentity());
    try (var keyStream = index.keyStream()) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        Assert.assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
  }

  /**
   * Verify that removing a double-sided RidPair entry from a persisted vertex
   * LinkBag removes both the primary and secondary RIDs from the index.
   */
  public void testIndexRidBagUpdateRemovePairItemOnVertex() {
    // 1. Create a vertex ridBag with one lightweight and one pair entry, commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newStatefulEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(single1.getIdentity());
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Load from storage and remove the pair entry by its primary RID.
    session.begin();
    EntityImpl loaded = session.load(vertex.getIdentity());
    loaded.<LinkBag>getProperty("ridBag").remove(edge1.getIdentity());
    session.commit();

    // 3. Verify the index contains only the lightweight entry.
    final var index = getIndex("ridBagVertexIndex");
    Assert.assertEquals(index.size(session), 1);

    try (var keyStream = index.keyStream()) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        Assert.assertEquals(key.getIdentity(), single1.getIdentity());
      }
    }
  }

  /**
   * Verify that replacing a vertex LinkBag containing a double-sided RidPair entry
   * with an empty LinkBag removes all index entries (both primary and secondary RIDs).
   */
  public void testIndexRidBagReplaceWithEmptyOnVertex() {
    // 1. Create a vertex with a pair-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newStatefulEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify both RIDs are indexed.
    final var index = getIndex("ridBagVertexIndex");
    Assert.assertEquals(index.size(session), 2);

    // 3. Replace the ridBag with an empty one and commit.
    session.begin();
    var activeTx = session.getActiveTransaction();
    var loaded = (EntityImpl) activeTx.load(vertex);
    loaded.setProperty("ridBag", new LinkBag(session));
    session.commit();

    // 4. Verify the index is empty.
    Assert.assertEquals(index.size(session), 0);
  }

  /**
   * Verify that deleting a vertex whose LinkBag contains a double-sided RidPair
   * entry removes all index entries (both primary and secondary RIDs).
   */
  public void testIndexRidBagRemoveVertexWithPairs() {
    // 1. Create a vertex with a pair-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newStatefulEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify both RIDs are indexed.
    final var index = getIndex("ridBagVertexIndex");
    Assert.assertEquals(index.size(session), 2);

    // 3. Delete the vertex (which also deletes its edges) and commit.
    session.begin();
    session.execute("DELETE VERTEX " + vertex.getIdentity()).close();
    session.commit();

    // 4. Verify the index is empty.
    Assert.assertEquals(index.size(session), 0);
  }

}
