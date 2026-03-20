package com.jetbrains.youtrackdb.junit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for link bag index operations.
 *
 * @since 1/30/14
 */
public class LinkBagIndexTest extends BaseDBJUnit5Test {

  @BeforeAll
  @Override
  void beforeAll() throws Exception {
    super.beforeAll();

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

  @AfterAll
  @Override
  void afterAll() throws Exception {
    if (session.isClosed()) {
      session = acquireSession();
    }

    session.getMetadata().getSchema().dropClass("RidBagIndexEdgeClass");
    session.getMetadata().getSchema().dropClass("RidBagIndexVertexClass");
    session.getMetadata().getSchema().dropClass("RidBagIndexTestClass");
    session.close();
  }

  @AfterEach
  @Override
  void afterEach() {
    session.begin();
    session.execute("DELETE FROM RidBagIndexTestClass").close();
    session.execute("DELETE VERTEX RidBagIndexVertexClass").close();
    session.commit();

    session.begin();
    var result = session.query("select from RidBagIndexTestClass");
    assertEquals(0, result.stream().count());

    if (!session.getStorage().isRemote()) {
      final var index = getIndex("ridBagIndex");
      assertEquals(0, index.size(session));

      final var vertexIndex = getIndex("ridBagVertexIndex");
      assertEquals(0, vertexIndex.size(session));
    }
    result.close();
    session.commit();
  }

  @Test
  void testIndexRidBag() {

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
    assertEquals(2, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagInTx() {

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
    assertEquals(2, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagUpdate() {

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
    assertEquals(2, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagUpdateInTx() {

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
    assertEquals(2, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagUpdateInTxRollback() {

    session.begin();
    final var docOne = ((EntityImpl) session.newEntity());

    final var docTwo = ((EntityImpl) session.newEntity());

    final var docThree = ((EntityImpl) session.newEntity());

    final var ridBagOne = new LinkBag(session);
    ridBagOne.add(docOne.getIdentity());
    ridBagOne.add(docTwo.getIdentity());

    var document = ((EntityImpl) session.newEntity("RidBagIndexTestClass"));
    document.setProperty("ridBag", ridBagOne);

    assertNotNull(session.commit());

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
    assertEquals(2, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  /**
   * Verify that adding a single-RID entry to a persisted LinkBag via direct API
   * correctly adds the new key to the index while preserving existing entries.
   */
  @Test
  void testIndexRidBagUpdateAddItem() {

    // 1. Create a document with a LinkBag containing two single-RID entries and commit.
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
    assertEquals(3, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagUpdateAddItemInTx() {

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
    assertEquals(3, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagUpdateAddItemInTxRollback() {

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

    assertEquals(2, index.size(session));
    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagUpdateRemoveItemInTx() {

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

    assertEquals(1, index.size(session));
    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexRidBagUpdateRemoveItemInTxRollback() {

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
    assertEquals(2, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  /**
   * Verify that removing a single-RID entry from a persisted LinkBag via direct API
   * correctly removes the key from the index while preserving remaining entries.
   */
  @Test
  void testIndexRidBagUpdateRemoveItem() {

    // 1. Create a document with a LinkBag containing two single-RID entries and commit.
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
    assertEquals(1, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        if (!key.getIdentity().equals(docOne.getIdentity())) {
          fail("Unknown key found: " + key);
        }
      }
    }
    session.rollback();
  }

  @Test
  void testIndexRidBagRemove() {

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
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  void testIndexRidBagRemoveInTx() {

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
    assertEquals(0, index.size(session));
    session.rollback();
  }

  @Test
  void testIndexRidBagRemoveInTxRollback() {

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
    assertEquals(2, index.size(session));

    final Iterator<Object> keyIterator;
    try (var keyStream = index.keyStream(ato)) {
      keyIterator = keyStream.iterator();

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

  @Test
  void testIndexRidBagSQL() {
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
            "select * from RidBagIndexTestClass where ridBag contains ?",
            docOne.getIdentity());
    var res = result.next();

    var resultSet = new HashSet<>();
    for (var ridPair : res.<LinkBag>getProperty("ridBag")) {
      resultSet.add(ridPair.primaryRid());
    }
    result.close();

    assertEquals(resultSet, Set.of(docOne.getIdentity(), docTwo.getIdentity()));
    session.commit();

  }

  /**
   * Verify that committing a vertex LinkBag with double-sided RidPair entries
   * (edge RID + target vertex RID) indexes only the primary RIDs (edge RIDs).
   */
  @Test
  void testIndexRidBagWithPairsOnVertex() {
    // 1. Create a vertex with two heavyweight edges and populate ridBag
    //    with double-sided pairs (edgeRid, targetVertexRid).
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var target1 = session.newVertex("V");
    final var target2 = session.newVertex("V");
    final var edge1 = session.newEdge(vertex, target1, "RidBagIndexEdgeClass");
    final var edge2 = session.newEdge(vertex, target2, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    ridBag.add(edge2.getIdentity(), target2.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify only the 2 primary RIDs (edge RIDs) are in the index.
    final var index = getIndex("ridBagVertexIndex");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(2, index.size(session));

    var expectedKeys = Set.of(edge1.getIdentity(), edge2.getIdentity());
    try (var keyStream = index.keyStream(ato)) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
    session.commit();
  }

  /**
   * Verify that a vertex LinkBag containing both a single-RID entry
   * and a double-sided RidPair entry indexes only the primary RIDs.
   */
  @Test
  void testIndexRidBagWithMixedSingleAndPairOnVertex() {
    // 1. Create a vertex ridBag with one single-RID entry and one double-sided pair.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(single1.getIdentity());
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify the index contains 2 keys: single1 (single-RID primary)
    //    + edge1 (heavyweight primary). target1 (secondary) is NOT indexed.
    final var index = getIndex("ridBagVertexIndex");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(2, index.size(session));

    var expectedKeys = Set.of(single1.getIdentity(), edge1.getIdentity());
    try (var keyStream = index.keyStream(ato)) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
    session.commit();
  }

  /**
   * Verify that incrementally adding a double-sided RidPair entry to a persisted
   * vertex LinkBag indexes only the primary RID via change tracking.
   */
  @Test
  void testIndexRidBagUpdateAddPairItemOnVertex() {
    // 1. Create a vertex with a single-RID-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newEdge(vertex, target1, "RidBagIndexEdgeClass");

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

    // 3. Verify the index has 2 keys: single1 + edge1 (primary only, not target1).
    final var index = getIndex("ridBagVertexIndex");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(2, index.size(session));

    var expectedKeys = Set.of(single1.getIdentity(), edge1.getIdentity());
    try (var keyStream = index.keyStream(ato)) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
    session.commit();
  }

  /**
   * Same as {@link #testIndexRidBagUpdateAddPairItemOnVertex()} but the add
   * operation is wrapped in a try/catch transaction block to verify commit safety.
   */
  @Test
  void testIndexRidBagUpdateAddPairItemOnVertexInTx() {
    // 1. Create a vertex with a single-RID-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newEdge(vertex, target1, "RidBagIndexEdgeClass");

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

    // 3. Verify the index has 2 keys: single1 + edge1 (primary only, not target1).
    final var index = getIndex("ridBagVertexIndex");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(2, index.size(session));

    var expectedKeys = Set.of(single1.getIdentity(), edge1.getIdentity());
    try (var keyStream = index.keyStream(ato)) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        assertTrue(expectedKeys.contains(key.getIdentity()),
            "Unexpected key found: " + key);
      }
    }
    session.commit();
  }

  /**
   * Verify that removing a double-sided RidPair entry from a persisted vertex
   * LinkBag removes the primary RID from the index.
   */
  @Test
  void testIndexRidBagUpdateRemovePairItemOnVertex() {
    // 1. Create a vertex ridBag with one single-RID and one pair entry, commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var single1 = session.newVertex("V");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newEdge(vertex, target1, "RidBagIndexEdgeClass");

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

    // 3. Verify the index contains only the single-RID entry.
    final var index = getIndex("ridBagVertexIndex");
    var activeTx = session.begin();
    var ato = activeTx.getAtomicOperation();
    assertEquals(1, index.size(session));

    try (var keyStream = index.keyStream(ato)) {
      var keyIterator = keyStream.iterator();
      while (keyIterator.hasNext()) {
        var key = (Identifiable) keyIterator.next();
        assertEquals(single1.getIdentity(), key.getIdentity());
      }
    }
    session.commit();
  }

  /**
   * Verify that replacing a vertex LinkBag containing a double-sided RidPair entry
   * with an empty LinkBag removes the primary RID index entry.
   */
  @Test
  void testIndexRidBagReplaceWithEmptyOnVertex() {
    // 1. Create a vertex with a pair-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify only the primary RID (edge1) is indexed.
    final var index = getIndex("ridBagVertexIndex");
    session.begin();
    assertEquals(1, index.size(session));
    session.commit();

    // 3. Replace the ridBag with an empty one and commit.
    session.begin();
    var activeTx = session.getActiveTransaction();
    var loaded = (EntityImpl) activeTx.load(vertex);
    loaded.setProperty("ridBag", new LinkBag(session));
    session.commit();

    // 4. Verify the index is empty.
    session.begin();
    assertEquals(0, index.size(session));
    session.commit();
  }

  /**
   * Verify that deleting a vertex whose LinkBag contains a double-sided RidPair
   * entry removes the primary RID index entry.
   */
  @Test
  void testIndexRidBagRemoveVertexWithPairs() {
    // 1. Create a vertex with a pair-only ridBag and commit.
    session.begin();
    final var vertex = session.newVertex("RidBagIndexVertexClass");
    final var target1 = session.newVertex("V");
    final var edge1 = session.newEdge(vertex, target1, "RidBagIndexEdgeClass");

    final var ridBag = new LinkBag(session);
    ridBag.add(edge1.getIdentity(), target1.getIdentity());
    vertex.setProperty("ridBag", ridBag);

    session.commit();

    // 2. Verify only the primary RID (edge1) is indexed.
    final var index = getIndex("ridBagVertexIndex");
    session.begin();
    assertEquals(1, index.size(session));
    session.commit();

    // 3. Delete the vertex (which also deletes its edges) and commit.
    session.begin();
    session.execute("DELETE VERTEX " + vertex.getIdentity()).close();
    session.commit();

    // 4. Verify the index is empty.
    session.begin();
    assertEquals(0, index.size(session));
    session.commit();
  }

}
