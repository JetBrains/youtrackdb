package com.jetbrains.youtrackdb.internal.core.db.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.common.util.Sizeable;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkListImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.EntityLinkSetImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EdgeFromLinkBagIterator;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that double-sided edge storage in LinkBag correctly stores primary and secondary RIDs.
 *
 * <p>After edge unification, all edges are record-based. The LinkBag stores:
 * <ul>
 *   <li>primaryRid = edge record RID</li>
 *   <li>secondaryRid = opposite vertex RID</li>
 * </ul>
 */
public class DoubleSidedEdgeLinkBagTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  @Before
  public void before() {
    youTrackDB =
        (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create("test", DatabaseType.MEMORY,
        new LocalUserCredential("admin", "adminpwd", PredefinedLocalRole.ADMIN));
    session = youTrackDB.open("test", "admin", "adminpwd");

    session.getSchema().createVertexClass("TestVertex");
    session.getSchema().createEdgeClass("HeavyEdge");
    session.getSchema().createEdgeClass("LightEdge");
  }

  @After
  public void after() {
    session.close();
    youTrackDB.drop("test");
    youTrackDB.close();
  }

  /**
   * Verifies that a heavyweight (stateful) edge stores the edge record RID as primary
   * and the opposite vertex RID as secondary in both the outgoing and incoming vertex
   * LinkBags.
   */
  @Test
  public void testHeavyweightEdgeStoresEdgeRidAsPrimaryAndVertexAsSecondary() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "HeavyEdge");
    var edgeRid = edge.getIdentity();

    tx.commit();

    // Reload vertices after commit to get persisted state
    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outVertex.getIdentity());
    var loadedIn = (EntityImpl) tx.load(inVertex.getIdentity());

    // Check outgoing vertex: out_HeavyEdge should contain RidPair(edgeRid, inVertexRid)
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var outBag = (LinkBag) loadedOut.getPropertyInternal(outFieldName);
    assertNotNull("Outgoing vertex should have out_HeavyEdge LinkBag", outBag);
    assertEquals("Outgoing LinkBag should have exactly 1 entry", 1, outBag.size());

    var outPair = outBag.iterator().next();
    assertEquals(
        "Outgoing LinkBag primary RID should be the edge record",
        edgeRid, outPair.primaryRid());
    assertEquals(
        "Outgoing LinkBag secondary RID should be the target (in) vertex",
        inVertex.getIdentity(), outPair.secondaryRid());
    assertNotEquals(
        "Edge RID and vertex RID must differ",
        outPair.primaryRid(), outPair.secondaryRid());

    // Check incoming vertex: in_HeavyEdge should contain RidPair(edgeRid, outVertexRid)
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");
    var inBag = (LinkBag) loadedIn.getPropertyInternal(inFieldName);
    assertNotNull("Incoming vertex should have in_HeavyEdge LinkBag", inBag);
    assertEquals("Incoming LinkBag should have exactly 1 entry", 1, inBag.size());

    var inPair = inBag.iterator().next();
    assertEquals(
        "Incoming LinkBag primary RID should be the edge record",
        edgeRid, inPair.primaryRid());
    assertEquals(
        "Incoming LinkBag secondary RID should be the source (out) vertex",
        outVertex.getIdentity(), inPair.secondaryRid());
    assertNotEquals(
        "Edge RID and vertex RID must differ",
        inPair.primaryRid(), inPair.secondaryRid());

    tx.commit();
  }

  /**
   * Verifies that a "LightEdge" (which after edge unification is record-based like all
   * edges) stores the edge record RID as primary and the opposite vertex RID as secondary
   * in both the outgoing and incoming vertex LinkBags.
   */
  @Test
  public void testEdgeStoresEdgeRidAsPrimaryAndVertexRidAsSecondary() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "LightEdge");
    var edgeRid = edge.getIdentity();

    tx.commit();

    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outVertex.getIdentity());
    var loadedIn = (EntityImpl) tx.load(inVertex.getIdentity());

    // Check outgoing vertex: out_LightEdge should contain RidPair(edgeRid, inVertexRid)
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "LightEdge");
    var outBag = (LinkBag) loadedOut.getPropertyInternal(outFieldName);
    assertNotNull("Outgoing vertex should have out_LightEdge LinkBag", outBag);
    assertEquals("Outgoing LinkBag should have exactly 1 entry", 1, outBag.size());

    var outPair = outBag.iterator().next();
    assertEquals(
        "Outgoing primary RID should be the edge record",
        edgeRid, outPair.primaryRid());
    assertEquals(
        "Outgoing secondary RID should be the target (in) vertex",
        inVertex.getIdentity(), outPair.secondaryRid());
    assertNotEquals(
        "Edge RID and vertex RID must differ",
        outPair.primaryRid(), outPair.secondaryRid());

    // Check incoming vertex: in_LightEdge should contain RidPair(edgeRid, outVertexRid)
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "LightEdge");
    var inBag = (LinkBag) loadedIn.getPropertyInternal(inFieldName);
    assertNotNull("Incoming vertex should have in_LightEdge LinkBag", inBag);
    assertEquals("Incoming LinkBag should have exactly 1 entry", 1, inBag.size());

    var inPair = inBag.iterator().next();
    assertEquals(
        "Incoming primary RID should be the edge record",
        edgeRid, inPair.primaryRid());
    assertEquals(
        "Incoming secondary RID should be the source (out) vertex",
        outVertex.getIdentity(), inPair.secondaryRid());
    assertNotEquals(
        "Edge RID and vertex RID must differ",
        inPair.primaryRid(), inPair.secondaryRid());

    tx.commit();
  }

  /**
   * Verifies correct LinkBag contents when multiple heavyweight edges are added
   * from the same outgoing vertex to different incoming vertices.
   */
  @Test
  public void testMultipleHeavyweightEdgesFromSameVertex() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex1 = tx.newVertex("TestVertex");
    var inVertex2 = tx.newVertex("TestVertex");

    var edge1 = outVertex.addEdge(inVertex1, "HeavyEdge");
    var edge2 = outVertex.addEdge(inVertex2, "HeavyEdge");

    var edge1Rid = edge1.getIdentity();
    var edge2Rid = edge2.getIdentity();

    tx.commit();

    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outVertex.getIdentity());

    // The outgoing vertex should have 2 entries in out_HeavyEdge
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var outBag = (LinkBag) loadedOut.getPropertyInternal(outFieldName);
    assertNotNull("Outgoing vertex should have out_HeavyEdge LinkBag", outBag);
    assertEquals("Outgoing LinkBag should have 2 entries", 2, outBag.size());

    // Verify each pair has the correct edge RID as primary and vertex RID as secondary
    var foundEdge1 = false;
    var foundEdge2 = false;
    for (var pair : outBag) {
      assertNotEquals("Edge RID and vertex RID must differ",
          pair.primaryRid(), pair.secondaryRid());
      if (pair.primaryRid().equals(edge1Rid)) {
        assertEquals(
            "Edge1 secondary should be inVertex1",
            inVertex1.getIdentity(), pair.secondaryRid());
        foundEdge1 = true;
      } else if (pair.primaryRid().equals(edge2Rid)) {
        assertEquals(
            "Edge2 secondary should be inVertex2",
            inVertex2.getIdentity(), pair.secondaryRid());
        foundEdge2 = true;
      }
    }
    assertTrue("Should find edge1 in outgoing LinkBag", foundEdge1);
    assertTrue("Should find edge2 in outgoing LinkBag", foundEdge2);

    // Also verify each incoming vertex has 1 entry pointing back to outVertex
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");

    var loadedIn1 = (EntityImpl) tx.load(inVertex1.getIdentity());
    var inBag1 = (LinkBag) loadedIn1.getPropertyInternal(inFieldName);
    assertNotNull(inBag1);
    assertEquals(1, inBag1.size());
    var inPair1 = inBag1.iterator().next();
    assertEquals(edge1Rid, inPair1.primaryRid());
    assertEquals(outVertex.getIdentity(), inPair1.secondaryRid());

    var loadedIn2 = (EntityImpl) tx.load(inVertex2.getIdentity());
    var inBag2 = (LinkBag) loadedIn2.getPropertyInternal(inFieldName);
    assertNotNull(inBag2);
    assertEquals(1, inBag2.size());
    var inPair2 = inBag2.iterator().next();
    assertEquals(edge2Rid, inPair2.primaryRid());
    assertEquals(outVertex.getIdentity(), inPair2.secondaryRid());

    tx.commit();
  }

  /**
   * Verifies that both LightEdge and HeavyEdge classes on the same vertex store
   * the correct RidPair format (edgeRid, oppositeVertexRid). After edge unification,
   * both edge classes are record-based and produce identical RidPair patterns.
   */
  @Test
  public void testMultipleEdgeClassesOnSameVertexStoreCorrectRidPairs() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertexLight = tx.newVertex("TestVertex");
    var inVertexHeavy = tx.newVertex("TestVertex");

    var lightEdge = outVertex.addEdge(inVertexLight, "LightEdge");
    var lightEdgeRid = lightEdge.getIdentity();
    var heavyEdge = outVertex.addEdge(inVertexHeavy, "HeavyEdge");
    var heavyEdgeRid = heavyEdge.getIdentity();

    tx.commit();

    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outVertex.getIdentity());

    // Check LightEdge field: should store RidPair(edgeRid, oppositeVertexRid)
    var lightOutField = Vertex.getEdgeLinkFieldName(Direction.OUT, "LightEdge");
    var lightBag = (LinkBag) loadedOut.getPropertyInternal(lightOutField);
    assertNotNull(lightBag);
    assertEquals(1, lightBag.size());
    var lightPair = lightBag.iterator().next();
    assertNotEquals("Edge RID and vertex RID must differ",
        lightPair.primaryRid(), lightPair.secondaryRid());
    assertEquals(lightEdgeRid, lightPair.primaryRid());
    assertEquals(inVertexLight.getIdentity(), lightPair.secondaryRid());

    // Check HeavyEdge field: should store RidPair(edgeRid, oppositeVertexRid)
    var heavyOutField = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var heavyBag = (LinkBag) loadedOut.getPropertyInternal(heavyOutField);
    assertNotNull(heavyBag);
    assertEquals(1, heavyBag.size());
    var heavyPair = heavyBag.iterator().next();
    assertNotEquals("Edge RID and vertex RID must differ",
        heavyPair.primaryRid(), heavyPair.secondaryRid());
    assertEquals(heavyEdgeRid, heavyPair.primaryRid());
    assertEquals(inVertexHeavy.getIdentity(), heavyPair.secondaryRid());

    tx.commit();
  }

  /**
   * Verifies that heavyweight edge RidPairs survive a database close and reopen,
   * ensuring proper serialization and deserialization of double-sided entries.
   */
  @Test
  public void testHeavyweightEdgePairsPersistAcrossSessions() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "HeavyEdge");

    var outRid = outVertex.getIdentity();
    var inRid = inVertex.getIdentity();
    var edgeRid = edge.getIdentity();

    tx.commit();
    session.close();

    // Reopen the database in a fresh session
    session = youTrackDB.open("test", "admin", "adminpwd");

    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outRid);
    var loadedIn = (EntityImpl) tx.load(inRid);

    // Verify outgoing LinkBag still has correct double-sided pair
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var outBag = (LinkBag) loadedOut.getPropertyInternal(outFieldName);
    assertNotNull(outBag);
    assertEquals(1, outBag.size());
    var outPair = outBag.iterator().next();
    assertEquals(edgeRid, outPair.primaryRid());
    assertEquals(inRid, outPair.secondaryRid());
    assertNotEquals(outPair.primaryRid(), outPair.secondaryRid());

    // Verify incoming LinkBag still has correct double-sided pair
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");
    var inBag = (LinkBag) loadedIn.getPropertyInternal(inFieldName);
    assertNotNull(inBag);
    assertEquals(1, inBag.size());
    var inPair = inBag.iterator().next();
    assertEquals(edgeRid, inPair.primaryRid());
    assertEquals(outRid, inPair.secondaryRid());
    assertNotEquals(inPair.primaryRid(), inPair.secondaryRid());

    tx.commit();
  }

  // --- getVertices() optimization tests ---

  /**
   * Verifies that getVertices(OUT) on a heavyweight edge returns the correct opposite
   * vertex directly from the LinkBag secondary RID, without loading the edge record.
   */
  @Test
  public void testGetVerticesOutReturnsOppositeVertexForHeavyweightEdge() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addEdge(inVertex, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();

    var vertices = loadedOut.getVertices(Direction.OUT, "HeavyEdge");
    var iter = vertices.iterator();
    assertTrue("Should have at least one adjacent vertex", iter.hasNext());
    var adjacent = iter.next();
    assertEquals(
        "Adjacent vertex should be the target (in) vertex",
        inVertex.getIdentity(), adjacent.getIdentity());
    assertFalse("Should have exactly one adjacent vertex", iter.hasNext());

    tx.commit();
  }

  /**
   * Verifies that getVertices(OUT) on a LightEdge returns the correct opposite
   * vertex. After edge unification, LightEdge is record-based like all edges.
   */
  @Test
  public void testGetVerticesOutReturnsOppositeVertexForLightEdge() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addEdge(inVertex, "LightEdge");

    tx.commit();

    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();

    var vertices = loadedOut.getVertices(Direction.OUT, "LightEdge");
    var iter = vertices.iterator();
    assertTrue("Should have at least one adjacent vertex", iter.hasNext());
    var adjacent = iter.next();
    assertEquals(
        "Adjacent vertex should be the target (in) vertex",
        inVertex.getIdentity(), adjacent.getIdentity());
    assertFalse("Should have exactly one adjacent vertex", iter.hasNext());

    tx.commit();
  }

  /**
   * Verifies that getVertices(IN) returns the source vertex for a heavyweight edge.
   */
  @Test
  public void testGetVerticesInReturnsSourceVertexForHeavyweightEdge() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addEdge(inVertex, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loadedIn = tx.load(inVertex.getIdentity()).asVertex();

    var vertices = loadedIn.getVertices(Direction.IN, "HeavyEdge");
    var iter = vertices.iterator();
    assertTrue("Should have at least one adjacent vertex", iter.hasNext());
    var adjacent = iter.next();
    assertEquals(
        "Adjacent vertex should be the source (out) vertex",
        outVertex.getIdentity(), adjacent.getIdentity());
    assertFalse("Should have exactly one adjacent vertex", iter.hasNext());

    tx.commit();
  }

  /**
   * Verifies that label filtering works correctly: getVertices(OUT, "HeavyEdge") should
   * only return vertices connected via HeavyEdge, not via LightEdge.
   */
  @Test
  public void testGetVerticesWithLabelFilteringReturnsOnlyMatchingEdgeClass() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var heavyTarget = tx.newVertex("TestVertex");
    var lightTarget = tx.newVertex("TestVertex");

    outVertex.addEdge(heavyTarget, "HeavyEdge");
    outVertex.addEdge(lightTarget, "LightEdge");

    tx.commit();

    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();

    // Only request HeavyEdge vertices
    var heavyVertices = loadedOut.getVertices(Direction.OUT, "HeavyEdge");
    var iter = heavyVertices.iterator();
    assertTrue(iter.hasNext());
    assertEquals(heavyTarget.getIdentity(), iter.next().getIdentity());
    assertFalse("Should have only 1 vertex for HeavyEdge", iter.hasNext());

    // Only request LightEdge vertices
    var lightVertices = loadedOut.getVertices(Direction.OUT, "LightEdge");
    iter = lightVertices.iterator();
    assertTrue(iter.hasNext());
    assertEquals(lightTarget.getIdentity(), iter.next().getIdentity());
    assertFalse("Should have only 1 vertex for LightEdge", iter.hasNext());

    tx.commit();
  }

  /**
   * Verifies that getVertices(BOTH) returns vertices from both outgoing and incoming
   * directions for heavyweight edges.
   */
  @Test
  public void testGetVerticesBothReturnsVerticesFromBothDirections() {
    var tx = session.begin();

    var vertexA = tx.newVertex("TestVertex");
    var vertexB = tx.newVertex("TestVertex");
    var vertexC = tx.newVertex("TestVertex");

    // A --HeavyEdge--> B, C --HeavyEdge--> A
    vertexA.addEdge(vertexB, "HeavyEdge");
    vertexC.addEdge(vertexA, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loadedA = tx.load(vertexA.getIdentity()).asVertex();

    // Direction.BOTH should return both B (outgoing) and C (incoming)
    Set<RID> adjacentRids = new HashSet<>();
    for (var v : loadedA.getVertices(Direction.BOTH, "HeavyEdge")) {
      adjacentRids.add(v.getIdentity());
    }

    assertEquals("Should have 2 adjacent vertices", 2, adjacentRids.size());
    assertTrue(
        "Should contain outgoing target B",
        adjacentRids.contains(vertexB.getIdentity()));
    assertTrue(
        "Should contain incoming source C",
        adjacentRids.contains(vertexC.getIdentity()));

    tx.commit();
  }

  /**
   * Verifies that getVertices works correctly when a vertex has edges from multiple
   * edge classes, and returns all adjacent vertices when no label filter is applied.
   */
  @Test
  public void testGetVerticesWithMixedEdgeTypesReturnsAllAdjacentVertices() {
    var tx = session.begin();

    var center = tx.newVertex("TestVertex");
    var heavyTarget = tx.newVertex("TestVertex");
    var lightTarget = tx.newVertex("TestVertex");

    center.addEdge(heavyTarget, "HeavyEdge");
    center.addEdge(lightTarget, "LightEdge");

    tx.commit();

    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();

    // No label filter: should return vertices from both edge types
    Set<RID> adjacentRids = new HashSet<>();
    for (var v : loadedCenter.getVertices(Direction.OUT)) {
      adjacentRids.add(v.getIdentity());
    }

    assertEquals("Should have 2 adjacent vertices", 2, adjacentRids.size());
    assertTrue(
        "Should contain HeavyEdge target",
        adjacentRids.contains(heavyTarget.getIdentity()));
    assertTrue(
        "Should contain LightEdge target",
        adjacentRids.contains(lightTarget.getIdentity()));

    tx.commit();
  }

  /**
   * Verifies that getVertices(OUT) returns all correct vertices when multiple
   * heavyweight edges connect a single source to multiple targets.
   */
  @Test
  public void testGetVerticesOutWithMultipleHeavyweightEdges() {
    var tx = session.begin();

    var center = tx.newVertex("TestVertex");
    var target1 = tx.newVertex("TestVertex");
    var target2 = tx.newVertex("TestVertex");
    var target3 = tx.newVertex("TestVertex");

    center.addEdge(target1, "HeavyEdge");
    center.addEdge(target2, "HeavyEdge");
    center.addEdge(target3, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();

    Set<RID> adjacentRids = new HashSet<>();
    for (var v : loadedCenter.getVertices(Direction.OUT, "HeavyEdge")) {
      adjacentRids.add(v.getIdentity());
    }

    assertEquals("Should have 3 adjacent vertices", 3, adjacentRids.size());
    assertTrue(adjacentRids.contains(target1.getIdentity()));
    assertTrue(adjacentRids.contains(target2.getIdentity()));
    assertTrue(adjacentRids.contains(target3.getIdentity()));

    tx.commit();
  }

  /**
   * Verifies that getVertices returns an empty iterable when a vertex has no
   * edges at all. Exercises the empty-iterables path in getVerticesOptimized.
   */
  @Test
  public void testGetVerticesReturnsEmptyWhenNoEdgesExist() {
    var tx = session.begin();

    var loneVertex = tx.newVertex("TestVertex");
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(loneVertex.getIdentity()).asVertex();

    assertFalse(
        "Vertex with no edges should yield empty OUT vertices",
        loaded.getVertices(Direction.OUT).iterator().hasNext());
    assertFalse(
        "Vertex with no edges should yield empty IN vertices",
        loaded.getVertices(Direction.IN).iterator().hasNext());
    assertFalse(
        "Vertex with no edges should yield empty BOTH vertices",
        loaded.getVertices(Direction.BOTH).iterator().hasNext());

    tx.commit();
  }

  /**
   * Verifies that after deleting a heavyweight edge, getVertices no longer
   * returns the previously connected vertex.
   */
  @Test
  public void testGetVerticesAfterEdgeDeletionReturnsEmpty() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "HeavyEdge");

    tx.commit();

    // Delete the edge in a new transaction
    tx = session.begin();
    var loadedEdge = tx.load(edge.getIdentity()).asEdge();
    loadedEdge.delete();
    tx.commit();

    // Verify getVertices returns nothing
    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();
    var loadedIn = tx.load(inVertex.getIdentity()).asVertex();

    assertFalse(
        "After edge deletion, outgoing vertex should have no adjacent vertices",
        loadedOut.getVertices(Direction.OUT, "HeavyEdge").iterator().hasNext());
    assertFalse(
        "After edge deletion, incoming vertex should have no adjacent vertices",
        loadedIn.getVertices(Direction.IN, "HeavyEdge").iterator().hasNext());

    tx.commit();
  }

  // --- Transaction rollback and edge manipulation tests ---

  /**
   * Verifies that rolling back a transaction that added edges correctly restores the
   * LinkBag to its previous state. After rollback, the vertex should have no edges.
   */
  @Test
  public void testRollbackRestoresLinkBagToPreviousState() {
    // Begin transaction, add edge, then rollback before committing
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    tx.commit();

    // In a new transaction, add an edge then rollback
    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();
    var loadedIn = tx.load(inVertex.getIdentity()).asVertex();
    loadedOut.addEdge(loadedIn, "HeavyEdge");
    tx.rollback();

    // After rollback, the vertex should have no edges
    tx = session.begin();
    var reloadedOut = tx.load(outVertex.getIdentity()).asVertex();
    assertFalse(
        "After rollback, outgoing vertex should have no edges",
        reloadedOut.getVertices(Direction.OUT, "HeavyEdge").iterator().hasNext());
    tx.commit();
  }

  /**
   * Verifies that rolling back a transaction that added edges to a vertex that
   * already had edges correctly restores only the pre-existing edges.
   */
  @Test
  public void testRollbackPreservesPreExistingEdges() {
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex1 = tx.newVertex("TestVertex");
    var inVertex2 = tx.newVertex("TestVertex");
    outVertex.addEdge(inVertex1, "HeavyEdge");
    tx.commit();

    // In a new transaction, add another edge then rollback
    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();
    var loadedIn2 = tx.load(inVertex2.getIdentity()).asVertex();
    loadedOut.addEdge(loadedIn2, "HeavyEdge");
    tx.rollback();

    // After rollback, only the original edge should remain
    tx = session.begin();
    var reloadedOut = tx.load(outVertex.getIdentity()).asVertex();
    Set<RID> adjacentRids = new HashSet<>();
    for (var v : reloadedOut.getVertices(Direction.OUT, "HeavyEdge")) {
      adjacentRids.add(v.getIdentity());
    }
    assertEquals("Should have only the original edge target", 1, adjacentRids.size());
    assertTrue(
        "Original edge target should be preserved",
        adjacentRids.contains(inVertex1.getIdentity()));
    assertFalse(
        "Rolled-back edge target should not be present",
        adjacentRids.contains(inVertex2.getIdentity()));
    tx.commit();
  }

  /**
   * Verifies that adding and then removing an edge within the same transaction
   * correctly leaves the vertex with no edges after commit.
   */
  @Test
  public void testAddAndRemoveEdgeWithinSameTransaction() {
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "HeavyEdge");

    // Remove the edge within the same transaction
    edge.delete();
    tx.commit();

    tx = session.begin();
    var reloadedOut = tx.load(outVertex.getIdentity()).asVertex();
    assertFalse(
        "After add+delete in same transaction, no edges should remain",
        reloadedOut.getVertices(Direction.OUT, "HeavyEdge").iterator().hasNext());
    tx.commit();
  }

  /**
   * Verifies that getVertices(BOTH) with multiple edge classes returns vertices
   * from all classes. This exercises the multi-iterable chaining path.
   */
  @Test
  public void testGetVerticesBothWithMultipleEdgeClasses() {
    var tx = session.begin();
    var center = tx.newVertex("TestVertex");
    var heavyTarget = tx.newVertex("TestVertex");
    var lightTarget = tx.newVertex("TestVertex");
    var heavySource = tx.newVertex("TestVertex");

    center.addEdge(heavyTarget, "HeavyEdge");
    center.addEdge(lightTarget, "LightEdge");
    heavySource.addEdge(center, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();

    // BOTH with no label filter should return vertices from all directions and types
    Set<RID> allAdjacentRids = new HashSet<>();
    for (var v : loadedCenter.getVertices(Direction.BOTH)) {
      allAdjacentRids.add(v.getIdentity());
    }

    assertEquals("Should have 3 adjacent vertices", 3, allAdjacentRids.size());
    assertTrue("Should contain HeavyEdge outgoing target",
        allAdjacentRids.contains(heavyTarget.getIdentity()));
    assertTrue("Should contain LightEdge outgoing target",
        allAdjacentRids.contains(lightTarget.getIdentity()));
    assertTrue("Should contain HeavyEdge incoming source",
        allAdjacentRids.contains(heavySource.getIdentity()));

    tx.commit();
  }

  /**
   * Verifies that heavyweight edges work correctly when the LinkBag grows past the
   * embedded-to-BTree threshold (default 40). This exercises the BTree-based storage
   * path for double-sided edge entries, including BTree serialization, iteration,
   * and the getVertices() optimization through BTree-backed RidPairs.
   */
  @Test
  public void testDoubleSidedEdgesWithBTreeBackedLinkBag() {
    int edgeCount = 50; // > default threshold of 40

    var tx = session.begin();
    var center = tx.newVertex("TestVertex");

    for (int i = 0; i < edgeCount; i++) {
      var target = tx.newVertex("TestVertex");
      center.addEdge(target, "HeavyEdge");
    }

    tx.commit();

    // Reload and verify all edges are accessible through getVertices
    tx = session.begin();
    var loaded = tx.load(center.getIdentity()).asVertex();

    // Verify the LinkBag is BTree-backed
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var outBag = (LinkBag) ((EntityImpl) loaded).getPropertyInternal(outFieldName);
    assertNotNull(outBag);
    assertFalse(
        "LinkBag should be BTree-backed for " + edgeCount + " entries",
        outBag.isEmbedded());
    assertEquals(edgeCount, outBag.size());

    // Collect target vertices via getVertices optimized path
    Set<RID> foundRids = new HashSet<>();
    for (var v : loaded.getVertices(Direction.OUT, "HeavyEdge")) {
      foundRids.add(v.getIdentity());
    }
    assertEquals(
        "All " + edgeCount + " target vertices should be reachable",
        edgeCount, foundRids.size());

    // Verify RidPairs are correctly formed: each should be heavyweight
    for (var pair : outBag) {
      assertNotEquals(
          "Edge RID and vertex RID must differ",
          pair.primaryRid(), pair.secondaryRid());
      assertTrue(
          "Each pair's secondary RID should be a reachable target vertex",
          foundRids.contains(pair.secondaryRid()));
    }

    tx.commit();
  }

  /**
   * Verifies that BTree-backed heavyweight edges survive serialization across
   * database close/reopen.
   */
  @Test
  public void testBTreeBackedEdgesPersistAcrossSessions() {
    int edgeCount = 50;

    var tx = session.begin();
    var center = tx.newVertex("TestVertex");

    for (int i = 0; i < edgeCount; i++) {
      var target = tx.newVertex("TestVertex");
      center.addEdge(target, "HeavyEdge");
    }

    var centerRid = center.getIdentity();
    tx.commit();

    // Collect the vertex RIDs after commit (RIDs are stable now)
    tx = session.begin();
    var loadedBefore = tx.load(centerRid).asVertex();
    Set<RID> targetRidsBefore = new HashSet<>();
    for (var v : loadedBefore.getVertices(Direction.OUT, "HeavyEdge")) {
      targetRidsBefore.add(v.getIdentity());
    }
    assertEquals(edgeCount, targetRidsBefore.size());
    tx.commit();

    session.close();

    // Reopen and verify
    session = youTrackDB.open("test", "admin", "adminpwd");
    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();

    Set<RID> targetRidsAfter = new HashSet<>();
    for (var v : loaded.getVertices(Direction.OUT, "HeavyEdge")) {
      targetRidsAfter.add(v.getIdentity());
    }

    assertEquals(edgeCount, targetRidsAfter.size());
    assertEquals(
        "Target vertices should be the same after session reopen",
        targetRidsBefore, targetRidsAfter);
    tx.commit();
  }

  /**
   * Verifies that removing edges from a BTree-backed LinkBag correctly updates
   * the getVertices results.
   */
  @Test
  public void testRemoveEdgesFromBTreeBackedLinkBag() {
    int edgeCount = 50;

    var tx = session.begin();
    var center = tx.newVertex("TestVertex");

    for (int i = 0; i < edgeCount; i++) {
      var target = tx.newVertex("TestVertex");
      center.addEdge(target, "HeavyEdge");
    }

    tx.commit();

    // Pick the first edge and its target to delete
    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();
    var edgeIter = loadedCenter.getEdges(Direction.OUT, "HeavyEdge").iterator();
    assertTrue("Should have edges to delete", edgeIter.hasNext());
    var edgeToDelete = edgeIter.next().asEdge();
    var deletedTargetRid = edgeToDelete.getTo().getIdentity();
    edgeToDelete.delete();
    tx.commit();

    // Verify the deleted edge's target is no longer in getVertices results
    tx = session.begin();
    var loaded = tx.load(center.getIdentity()).asVertex();

    Set<RID> foundRids = new HashSet<>();
    for (var v : loaded.getVertices(Direction.OUT, "HeavyEdge")) {
      foundRids.add(v.getIdentity());
    }

    assertEquals(edgeCount - 1, foundRids.size());
    assertFalse(
        "Deleted edge target should not be in results",
        foundRids.contains(deletedTargetRid));
    tx.commit();
  }

  /**
   * Verifies that a self-loop heavyweight edge (where outVertex == inVertex)
   * is correctly handled by getVertices in all directions.
   */
  @Test
  public void testGetVerticesWithSelfLoopHeavyweightEdge() {
    var tx = session.begin();

    var vertex = tx.newVertex("TestVertex");
    vertex.addEdge(vertex, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loaded = tx.load(vertex.getIdentity()).asVertex();

    // OUT should return the vertex itself
    var outIter = loaded.getVertices(Direction.OUT, "HeavyEdge").iterator();
    assertTrue("Self-loop OUT should have one vertex", outIter.hasNext());
    assertEquals(vertex.getIdentity(), outIter.next().getIdentity());
    assertFalse(outIter.hasNext());

    // IN should return the vertex itself
    var inIter = loaded.getVertices(Direction.IN, "HeavyEdge").iterator();
    assertTrue("Self-loop IN should have one vertex", inIter.hasNext());
    assertEquals(vertex.getIdentity(), inIter.next().getIdentity());
    assertFalse(inIter.hasNext());

    // BOTH should return the vertex twice (once for OUT, once for IN)
    int count = 0;
    for (var v : loaded.getVertices(Direction.BOTH, "HeavyEdge")) {
      assertEquals(vertex.getIdentity(), v.getIdentity());
      count++;
    }
    assertEquals("Self-loop BOTH should yield 2 entries", 2, count);

    tx.commit();
  }

  // --- Entity comparison and LinkBag copy tests ---

  /**
   * Verifies that loading the same vertex twice produces entities with the same
   * content, including LinkBag properties. This exercises EntityHelper.compareBags
   * which iterates RidPairs during entity comparison (used in conflict resolution
   * and database export).
   */
  @Test
  public void testEntityComparisonWithLinkBagProperties() {
    var tx = session.begin();
    var vertex = tx.newVertex("TestVertex");
    var target1 = tx.newVertex("TestVertex");
    var target2 = tx.newVertex("TestVertex");

    vertex.addEdge(target1, "HeavyEdge");
    vertex.addEdge(target2, "HeavyEdge");

    tx.commit();

    // Load the same entity via two separate load calls
    tx = session.begin();
    var loaded1 = (EntityImpl) tx.load(vertex.getIdentity());
    var loaded2 = (EntityImpl) tx.load(vertex.getIdentity());

    // Compare entities (exercises EntityHelper.compareBags with RidPair iteration)
    assertTrue(
        "Same entity loaded twice should have same content",
        EntityHelper.hasSameContentOf(loaded1, session, loaded2, session, null));

    tx.commit();
  }

  /**
   * Verifies that the LinkBag iterator supports remove() during iteration,
   * which is used by some internal operations that clean up stale references.
   */
  @Test
  public void testLinkBagIteratorRemoveDuringIteration() {
    var tx = session.begin();
    var vertex = tx.newVertex("TestVertex");
    var target1 = tx.newVertex("TestVertex");
    var target2 = tx.newVertex("TestVertex");
    var target3 = tx.newVertex("TestVertex");

    vertex.addEdge(target1, "HeavyEdge");
    vertex.addEdge(target2, "HeavyEdge");
    vertex.addEdge(target3, "HeavyEdge");

    tx.commit();

    // Remove the first entry via iterator.remove()
    tx = session.begin();
    var loaded = (EntityImpl) tx.load(vertex.getIdentity());
    var outField = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var bag = (LinkBag) loaded.getPropertyInternal(outField);
    assertNotNull(bag);
    assertEquals(3, bag.size());

    var iter = bag.iterator();
    assertTrue(iter.hasNext());
    iter.next();
    iter.remove();

    // After remove, size should decrease
    assertEquals(
        "LinkBag size should decrease after iterator.remove()",
        2, bag.size());
    tx.rollback();
  }

  // --- getVerticesOptimized with non-LinkBag edge property ---

  /**
   * Verifies that getVerticesOptimized correctly handles edge properties stored as a
   * single LINK (Identifiable) rather than a LinkBag. This exercises the Identifiable
   * case in the switch statement at VertexEntityImpl.getVerticesOptimized.
   *
   * <p>When an edge property is schema-typed as LINK, addLinkToEdge stores a single
   * Identifiable (the edge record RID). getVerticesOptimized must detect this and
   * fall back to the edge-based traversal path.
   */
  @Test
  public void testGetVerticesOptimizedWithLinkTypedEdgeProperty() {
    // Create a separate edge class with LINK-typed property on the vertex
    session.getSchema().createEdgeClass("LinkEdge");
    var vertexClass = session.getSchema().getClass("TestVertex");
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "LinkEdge");
    vertexClass.createProperty(outFieldName, PropertyType.LINK);

    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    // addEdge stores the edge RID as a single LINK (not a LinkBag)
    // because the property is typed as LINK
    outVertex.addEdge(inVertex, "LinkEdge");
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(outVertex.getIdentity()).asVertex();

    // getVertices should still return the correct adjacent vertex
    // even though the property is stored as a single Identifiable
    var vertices = loaded.getVertices(Direction.OUT, "LinkEdge");
    var iter = vertices.iterator();
    assertTrue("Should have at least one adjacent vertex", iter.hasNext());
    var adjacent = iter.next();
    assertEquals(
        "Adjacent vertex should be the target (in) vertex",
        inVertex.getIdentity(), adjacent.getIdentity());
    assertFalse("Should have exactly one adjacent vertex", iter.hasNext());

    tx.commit();
  }

  // --- Index tests: verify LinkBag is indexed only by primaryRid ---

  /**
   * Verifies that a LINKBAG index on an edge field only contains the primaryRid
   * (edge record RID) for heavyweight edges, not the secondaryRid (opposite vertex).
   *
   * <p>Setup: create a vertex class with a LINKBAG property, add a NOTUNIQUE index,
   * then add heavyweight edges and check that only edge RIDs appear as index keys.
   */
  @Test
  public void testLinkBagIndexContainsOnlyPrimaryRidForHeavyweightEdges() {
    // Create an index on the out_HeavyEdge LinkBag field of TestVertex
    var vertexClass = session.getSchema().getClass("TestVertex");
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    vertexClass.createProperty(outFieldName, PropertyType.LINKBAG);
    vertexClass.createIndex(
        "testHeavyEdgeIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, outFieldName);

    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex1 = tx.newVertex("TestVertex");
    var inVertex2 = tx.newVertex("TestVertex");

    var edge1 = outVertex.addEdge(inVertex1, "HeavyEdge");
    var edge2 = outVertex.addEdge(inVertex2, "HeavyEdge");

    var edge1Rid = edge1.getIdentity();
    var edge2Rid = edge2.getIdentity();
    var inVertex1Rid = inVertex1.getIdentity();
    var inVertex2Rid = inVertex2.getIdentity();

    tx.commit();

    // Read all index keys and verify only edge RIDs (primaryRids) are present
    tx = session.begin();
    var ato = tx.getAtomicOperation();
    var index = session.getSharedContext().getIndexManager().getIndex("testHeavyEdgeIndex");
    assertNotNull("Index should exist", index);

    Set<RID> indexKeys = new HashSet<>();
    try (var keyStream = index.keyStream(ato)) {
      var keyIter = keyStream.iterator();
      while (keyIter.hasNext()) {
        var key = keyIter.next();
        indexKeys.add(((Identifiable) key).getIdentity());
      }
    }

    // Index should contain the 2 edge record RIDs (primary RIDs)
    assertTrue("Index should contain edge1 RID (primaryRid)",
        indexKeys.contains(edge1Rid));
    assertTrue("Index should contain edge2 RID (primaryRid)",
        indexKeys.contains(edge2Rid));

    // Index should NOT contain the opposite vertex RIDs (secondary RIDs)
    assertFalse("Index should NOT contain inVertex1 RID (secondaryRid)",
        indexKeys.contains(inVertex1Rid));
    assertFalse("Index should NOT contain inVertex2 RID (secondaryRid)",
        indexKeys.contains(inVertex2Rid));

    assertEquals("Index should have exactly 2 keys (one per edge)", 2, indexKeys.size());

    tx.commit();
  }

  // --- ResultInternal and LinkBag conversion tests ---

  /**
   * Verifies that ResultInternal.toMapValue correctly converts a LinkBag to a List
   * of primary RIDs. This exercises the LinkBag case in the static toMapValue switch
   * expression, which iterates RidPairs and extracts primaryRid values.
   */
  @Test
  public void testResultInternalToMapValueConvertsLinkBagToRidList() {
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex1 = tx.newVertex("TestVertex");
    var inVertex2 = tx.newVertex("TestVertex");
    var edge1 = outVertex.addEdge(inVertex1, "HeavyEdge");
    var edge2 = outVertex.addEdge(inVertex2, "HeavyEdge");
    tx.commit();

    tx = session.begin();
    var loaded = (EntityImpl) tx.load(outVertex.getIdentity());
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var bag = (LinkBag) loaded.getPropertyInternal(outFieldName);
    assertNotNull("Vertex should have a LinkBag for edge field", bag);

    // Call toMapValue to convert the LinkBag to a List<RID>
    var converted = ResultInternal.toMapValue(bag, false);
    assertTrue(
        "toMapValue should convert LinkBag to a List",
        converted instanceof List<?>);

    @SuppressWarnings("unchecked")
    var ridList = (List<RID>) converted;
    assertEquals(
        "Converted list should have same size as LinkBag",
        bag.size(), ridList.size());

    // All primary RIDs (edge record RIDs) should be in the list
    Set<RID> expectedRids = new HashSet<>();
    for (var pair : bag) {
      expectedRids.add(pair.primaryRid());
    }
    assertEquals(
        "Converted list should contain all primary RIDs from the LinkBag",
        expectedRids, new HashSet<>(ridList));

    tx.commit();
  }

  /**
   * Verifies that setting a LinkBag as a property value on a ResultInternal
   * correctly converts it to a List of primary RIDs via convertPropertyValue.
   * This exercises the LinkBag case in the private convertPropertyValue method.
   */
  @Test
  public void testResultInternalSetPropertyConvertsLinkBag() {
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "HeavyEdge");
    var edgeRid = edge.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = (EntityImpl) tx.load(outVertex.getIdentity());
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var bag = (LinkBag) loaded.getPropertyInternal(outFieldName);
    assertNotNull(bag);

    // Setting a LinkBag property on a ResultInternal should convert it to a List
    var result = new ResultInternal(session);
    result.setProperty("edges", bag);
    var value = result.getProperty("edges");

    assertTrue(
        "Property value should be converted from LinkBag to a List",
        value instanceof List<?>);

    @SuppressWarnings("unchecked")
    var ridList = (List<RID>) value;
    assertEquals("Converted list should have 1 entry", 1, ridList.size());
    assertEquals(
        "Converted list should contain the edge RID (primary RID)",
        edgeRid, ridList.getFirst());

    tx.commit();
  }

  /**
   * Verifies that a LINKBAG index on a LightEdge field stores the edge record RID
   * (primaryRid) as the index key, not the opposite vertex RID. After edge unification,
   * LightEdge is record-based and behaves identically to HeavyEdge.
   */
  @Test
  public void testLinkBagIndexContainsEdgeRidForLightEdges() {
    var vertexClass = session.getSchema().getClass("TestVertex");
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "LightEdge");
    vertexClass.createProperty(outFieldName, PropertyType.LINKBAG);
    vertexClass.createIndex(
        "testLightEdgeIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, outFieldName);

    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");

    var edge = outVertex.addEdge(inVertex, "LightEdge");

    var edgeRid = edge.getIdentity();
    var inVertexRid = inVertex.getIdentity();

    tx.commit();

    // Verify the index contains the edge record RID (primaryRid)
    tx = session.begin();
    var ato = tx.getAtomicOperation();
    var index = session.getSharedContext().getIndexManager().getIndex("testLightEdgeIndex");
    assertNotNull("Index should exist", index);

    Set<RID> indexKeys = new HashSet<>();
    try (var keyStream = index.keyStream(ato)) {
      var keyIter = keyStream.iterator();
      while (keyIter.hasNext()) {
        var key = keyIter.next();
        indexKeys.add(((Identifiable) key).getIdentity());
      }
    }

    assertEquals("Index should have exactly 1 key", 1, indexKeys.size());
    assertTrue("Index key should be the edge record RID (primaryRid)",
        indexKeys.contains(edgeRid));
    assertFalse("Index key should NOT be the opposite vertex RID (secondaryRid)",
        indexKeys.contains(inVertexRid));

    tx.commit();
  }

  /**
   * Verifies that getVertices() correctly traverses edges stored as EntityLinkListImpl.
   * When a vertex edge property is typed as LINKLIST in the schema, addEdge stores edge
   * references in an EntityLinkListImpl. The getVertices() method must handle this type
   * via the EdgeIterable path, loading edge records and resolving adjacent vertices.
   */
  @Test
  public void testGetVerticesTraversesLinkListEdgeProperty() {
    // Define edge properties as LINKLIST type on the vertex class
    var vertexClass = session.getSchema().getClass("TestVertex");
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");
    vertexClass.createProperty(outFieldName, PropertyType.LINKLIST);
    vertexClass.createProperty(inFieldName, PropertyType.LINKLIST);

    // Create vertices and a heavyweight edge; addEdge stores as EntityLinkListImpl
    // because the schema property type is LINKLIST
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addEdge(inVertex, "HeavyEdge");
    var outRid = outVertex.getIdentity();
    var inRid = inVertex.getIdentity();
    tx.commit();

    // Reload and verify the property is stored as EntityLinkListImpl
    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outRid);
    var fieldValue = loadedOut.getPropertyInternal(outFieldName);
    assertTrue("Edge property should be EntityLinkListImpl, but was: "
        + fieldValue.getClass().getSimpleName(),
        fieldValue instanceof EntityLinkListImpl);

    // Verify getVertices() correctly resolves the adjacent vertex through the LINKLIST
    var vertices = loadedOut.asVertex().getVertices(Direction.OUT, "HeavyEdge");
    var iter = vertices.iterator();
    assertTrue("Should have an adjacent vertex via LINKLIST edge property", iter.hasNext());
    assertEquals("Adjacent vertex should be the target vertex",
        inRid, iter.next().getIdentity());
    assertFalse("Should have exactly one adjacent vertex", iter.hasNext());

    // Verify the reverse direction also works
    var loadedIn = (EntityImpl) tx.load(inRid);
    var inFieldValue = loadedIn.getPropertyInternal(inFieldName);
    assertTrue("Incoming edge property should be EntityLinkListImpl, but was: "
        + inFieldValue.getClass().getSimpleName(),
        inFieldValue instanceof EntityLinkListImpl);

    var inVertices = loadedIn.asVertex().getVertices(Direction.IN, "HeavyEdge");
    var inIter = inVertices.iterator();
    assertTrue("Should have an adjacent vertex via incoming LINKLIST", inIter.hasNext());
    assertEquals("Adjacent incoming vertex should be the source vertex",
        outRid, inIter.next().getIdentity());
    assertFalse("Should have exactly one incoming adjacent vertex", inIter.hasNext());
    tx.rollback();
  }

  /**
   * Verifies that getVertices() correctly traverses edges stored as EntityLinkSetImpl.
   * Unlike LINKLIST, the addEdge method does not natively create LINKSET edge properties,
   * so this test manually replaces the LinkBag with an EntityLinkSetImpl containing the
   * same edge RIDs. This simulates a vertex whose edge property was stored as LINKSET
   * (e.g., from schema migration or direct property assignment). The getVertices() method
   * must handle the EntityLinkSetImpl type via the EdgeIterable path, loading edge records
   * and resolving adjacent vertices in both directions.
   */
  @Test
  public void testGetVerticesTraversesLinkSetEdgeProperty() {
    // Create vertices and a heavyweight edge (default storage is LinkBag)
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addEdge(inVertex, "HeavyEdge");
    var outRid = outVertex.getIdentity();
    var inRid = inVertex.getIdentity();
    tx.commit();

    // In a new transaction, replace edge properties with EntityLinkSetImpl on both
    // vertices. This is necessary because addEdge does not support PropertyType.LINKSET
    // natively — it only creates LinkBag or EntityLinkListImpl storage.
    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outRid);
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");

    // Collect existing edge RIDs from the outgoing LinkBag and replace with LinkSet
    var outBag = (LinkBag) loadedOut.getPropertyInternal(outFieldName);
    var outEdgeRids = new ArrayList<Identifiable>();
    for (var pair : outBag) {
      outEdgeRids.add(pair.primaryRid());
    }
    assertEquals("Should have one outgoing edge RID", 1, outEdgeRids.size());

    var outLinkSet = new EntityLinkSetImpl(loadedOut, outEdgeRids);
    loadedOut.setPropertyInternal(outFieldName, outLinkSet);

    // Verify the property is now EntityLinkSetImpl
    var outFieldValue = loadedOut.getPropertyInternal(outFieldName);
    assertTrue("Outgoing edge property should be EntityLinkSetImpl, but was: "
        + outFieldValue.getClass().getSimpleName(),
        outFieldValue instanceof EntityLinkSetImpl);

    // Verify getVertices() correctly resolves the adjacent vertex through the LINKSET
    var vertices = loadedOut.asVertex().getVertices(Direction.OUT, "HeavyEdge");
    var iter = vertices.iterator();
    assertTrue("Should have an adjacent vertex via LINKSET edge property", iter.hasNext());
    assertEquals("Adjacent vertex should be the target vertex",
        inRid, iter.next().getIdentity());
    assertFalse("Should have exactly one adjacent vertex", iter.hasNext());

    // Now verify the reverse direction: replace the incoming edge property with LinkSet
    var loadedIn = (EntityImpl) tx.load(inRid);
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");

    var inBag = (LinkBag) loadedIn.getPropertyInternal(inFieldName);
    var inEdgeRids = new ArrayList<Identifiable>();
    for (var pair : inBag) {
      inEdgeRids.add(pair.primaryRid());
    }
    assertEquals("Should have one incoming edge RID", 1, inEdgeRids.size());

    var inLinkSet = new EntityLinkSetImpl(loadedIn, inEdgeRids);
    loadedIn.setPropertyInternal(inFieldName, inLinkSet);

    var inFieldValue = loadedIn.getPropertyInternal(inFieldName);
    assertTrue("Incoming edge property should be EntityLinkSetImpl, but was: "
        + inFieldValue.getClass().getSimpleName(),
        inFieldValue instanceof EntityLinkSetImpl);

    var inVertices = loadedIn.asVertex().getVertices(Direction.IN, "HeavyEdge");
    var inIter = inVertices.iterator();
    assertTrue("Should have an adjacent vertex via incoming LINKSET", inIter.hasNext());
    assertEquals("Adjacent incoming vertex should be the source vertex",
        outRid, inIter.next().getIdentity());
    assertFalse("Should have exactly one incoming adjacent vertex", inIter.hasNext());
    tx.rollback();
  }

  /**
   * Verifies that getEdges(Direction.BOTH) returns edges from both outgoing and incoming
   * directions. This exercises the VertexEntityImpl.getEdges(Direction) no-label overload,
   * which scans property names with both "out_" and "in_" prefixes.
   */
  @Test
  public void testGetEdgesBothDirection() {
    var tx = session.begin();
    var center = tx.newVertex("TestVertex");
    var left = tx.newVertex("TestVertex");
    var right = tx.newVertex("TestVertex");

    // center --HeavyEdge--> right
    var outEdge = center.addEdge(right, "HeavyEdge");
    // left --HeavyEdge--> center
    var inEdge = left.addEdge(center, "HeavyEdge");

    var outEdgeRid = outEdge.getIdentity();
    var inEdgeRid = inEdge.getIdentity();
    var centerRid = center.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();

    // getEdges(BOTH) should return both edges
    Set<RID> edgeRids = new HashSet<>();
    for (var edge : loaded.getEdges(Direction.BOTH)) {
      edgeRids.add(edge.getIdentity());
    }

    assertEquals("Should have 2 edges (one out, one in)", 2, edgeRids.size());
    assertTrue("Should contain the outgoing edge", edgeRids.contains(outEdgeRid));
    assertTrue("Should contain the incoming edge", edgeRids.contains(inEdgeRid));
    tx.commit();
  }

  /**
   * Verifies that getEdges(Direction.IN) without labels discovers all incoming
   * edge classes from the vertex's "in_" prefix fields. This covers the switch
   * expression IN case in the no-label getEdges overload.
   */
  @Test
  public void testGetEdgesInDirectionNoLabel() {
    var tx = session.begin();
    var target = tx.newVertex("TestVertex");
    var source1 = tx.newVertex("TestVertex");
    var source2 = tx.newVertex("TestVertex");

    source1.addEdge(target, "HeavyEdge");
    source2.addEdge(target, "LightEdge");

    var targetRid = target.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(targetRid).asVertex();

    // getEdges(IN) with no labels should discover both incoming edge classes
    Set<String> edgeClasses = new HashSet<>();
    for (var edge : loaded.getEdges(Direction.IN)) {
      edgeClasses.add(edge.getSchemaClassName());
    }

    assertEquals("Should discover 2 incoming edge classes", 2, edgeClasses.size());
    assertTrue(edgeClasses.contains("HeavyEdge"));
    assertTrue(edgeClasses.contains("LightEdge"));
    tx.commit();
  }

  /**
   * Verifies that getEdges(Direction.BOTH) correctly discovers edges of multiple types
   * by scanning all "out_" and "in_" property names.
   */
  @Test
  public void testGetEdgesBothWithMultipleEdgeClasses() {
    var tx = session.begin();
    var center = tx.newVertex("TestVertex");
    var v1 = tx.newVertex("TestVertex");
    var v2 = tx.newVertex("TestVertex");

    // center --HeavyEdge--> v1
    center.addEdge(v1, "HeavyEdge");
    // v2 --LightEdge--> center
    v2.addEdge(center, "LightEdge");

    var centerRid = center.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();

    Set<String> edgeClasses = new HashSet<>();
    for (var edge : loaded.getEdges(Direction.BOTH)) {
      edgeClasses.add(edge.getSchemaClassName());
    }

    assertEquals("Should have edges of 2 different classes", 2, edgeClasses.size());
    assertTrue(edgeClasses.contains("HeavyEdge"));
    assertTrue(edgeClasses.contains("LightEdge"));
    tx.commit();
  }

  /**
   * Verifies that the edge iterator's next() throws NoSuchElementException when exhausted.
   */
  @Test
  public void testEdgeIteratorNextOnExhausted() {
    var tx = session.begin();
    var v1 = tx.newVertex("TestVertex");
    var v2 = tx.newVertex("TestVertex");
    v1.addEdge(v2, "HeavyEdge");
    var v1Rid = v1.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(v1Rid).asVertex();
    var iter = loaded.getEdges(Direction.OUT, "HeavyEdge").iterator();

    // Consume the only edge
    assertTrue(iter.hasNext());
    iter.next();
    assertFalse(iter.hasNext());

    // next() on exhausted iterator should throw
    try {
      iter.next();
      fail("Expected NoSuchElementException");
    } catch (NoSuchElementException expected) {
      // expected
    }
    tx.commit();
  }

  /**
   * Verifies the edge iterator's size() returns the correct count for LinkBag-backed edges,
   * and isSizeable() returns true.
   */
  @Test
  public void testEdgeIteratorSizeAndIsSizeable() {
    var tx = session.begin();
    var center = tx.newVertex("TestVertex");

    for (int i = 0; i < 5; i++) {
      center.addEdge(tx.newVertex("TestVertex"), "HeavyEdge");
    }
    var centerRid = center.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();
    var edgeIter = loaded.getEdges(Direction.OUT, "HeavyEdge").iterator();

    // EdgeFromLinkBagIterator implements Sizeable
    assertTrue("Edge iterator should be Sizeable", edgeIter instanceof Sizeable);
    var sizeable = (Sizeable) edgeIter;
    assertTrue("isSizeable should be true for LinkBag-backed edges", sizeable.isSizeable());
    assertEquals("size should be 5", 5, sizeable.size());
    tx.commit();
  }

  /**
   * Verifies EdgeIterable.size() and isSizeable() work correctly for LinkBag-backed edges.
   */
  @Test
  public void testEdgeIterableSizeAndIsSizeable() {
    var tx = session.begin();
    var center = tx.newVertex("TestVertex");

    for (int i = 0; i < 3; i++) {
      center.addEdge(tx.newVertex("TestVertex"), "HeavyEdge");
    }
    var centerRid = center.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();
    var edgeIterable = loaded.getEdges(Direction.OUT, "HeavyEdge");

    // EdgeFromLinkBagIterable implements Sizeable via PreFilterableLinkBagIterable
    assertTrue("Edge iterable should be Sizeable", edgeIterable instanceof Sizeable);
    var sizeable = (Sizeable) edgeIterable;
    assertTrue("isSizeable should be true", sizeable.isSizeable());
    assertEquals("size should be 3", 3, sizeable.size());
    tx.commit();
  }

  /**
   * Verifies that the LinkBag edge iterator is an EdgeFromLinkBagIterator (not the
   * general EdgeIterator). This confirms that getEdgesInternal uses the optimized
   * EdgeFromLinkBagIterable path for LinkBag storage.
   */
  @Test
  public void testLinkBagEdgeIteratorIsEdgeFromLinkBagIterator() {
    var tx = session.begin();
    var center = tx.newVertex("TestVertex");
    center.addEdge(tx.newVertex("TestVertex"), "HeavyEdge");
    var centerRid = center.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();
    var iter = loaded.getEdges(Direction.OUT, "HeavyEdge").iterator();

    assertTrue(
        "LinkBag case should use EdgeFromLinkBagIterator",
        (Object) iter instanceof EdgeFromLinkBagIterator);
    tx.commit();
  }

  /**
   * Verifies that getEdges(Direction.OUT) with no label argument discovers all outgoing
   * edge classes from the vertex's "out_" fields. This covers the no-label getEdges()
   * overload that scans property names ending with edge class suffixes.
   */
  @Test
  public void testGetEdgesNoLabelDiscoversBothEdgeClasses() {
    var tx = session.begin();
    var center = tx.newVertex("TestVertex");
    var v1 = tx.newVertex("TestVertex");
    var v2 = tx.newVertex("TestVertex");
    center.addEdge(v1, "HeavyEdge");
    center.addEdge(v2, "LightEdge");
    var centerRid = center.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();

    // getEdges(OUT) with no labels should discover both edge classes
    Set<String> edgeClasses = new HashSet<>();
    for (var edge : loaded.getEdges(Direction.OUT)) {
      edgeClasses.add(edge.getSchemaClassName());
    }

    assertEquals(2, edgeClasses.size());
    assertTrue(edgeClasses.contains("HeavyEdge"));
    assertTrue(edgeClasses.contains("LightEdge"));
    tx.commit();
  }

  /**
   * Verifies that adding a second edge of the same type converts the vertex's edge property
   * from a single Identifiable to a LinkBag, and that the resulting edges are correctly
   * iterable. This exercises the resolveSecondaryRid path when converting single→LinkBag.
   */
  @Test
  public void testSecondEdgeOfSameTypeCreatesLinkBag() {
    // Set up a vertex with one edge stored as LINK type
    var vertexClass = session.getSchema().getClass("TestVertex");
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    vertexClass.createProperty(outFieldName, PropertyType.LINK);

    var tx = session.begin();
    var center = tx.newVertex("TestVertex");
    var target = tx.newVertex("TestVertex");
    center.addEdge(target, "HeavyEdge");
    var centerRid = center.getIdentity();
    tx.commit();

    // Verify the property is stored as LINK (single Identifiable)
    tx = session.begin();
    var loadedCenter = (EntityImpl) tx.load(centerRid);
    var fieldValue = loadedCenter.getPropertyInternal(outFieldName);
    assertTrue("First edge should be stored as single Identifiable, but was: "
        + (fieldValue == null ? "null" : fieldValue.getClass().getSimpleName()),
        fieldValue instanceof Identifiable);
    tx.commit();

    // Now remove the LINK property type constraint to allow conversion to LinkBag
    vertexClass.dropProperty(outFieldName);

    // Add a second edge — this triggers Identifiable→LinkBag conversion
    // which calls resolveSecondaryRid to determine the opposite vertex of the existing edge
    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();
    var target2 = tx.newVertex("TestVertex");
    loaded.addEdge(target2, "HeavyEdge");
    tx.commit();

    // Verify both edges are now correctly iterable
    tx = session.begin();
    var reloaded = tx.load(centerRid).asVertex();
    int edgeCount = 0;
    for (var edge : reloaded.getEdges(Direction.OUT, "HeavyEdge")) {
      assertNotNull(edge);
      edgeCount++;
    }
    assertEquals("Should have 2 outgoing edges after conversion", 2, edgeCount);
    tx.commit();
  }

  /**
   * Verifies EdgeIterable.size() and isSizeable() work correctly for EntityLinkListImpl-backed
   * edges (where the internal size is -1, requiring delegation to the backing collection).
   * This covers EdgeIterable.size() Collection branch and EdgeIterator.size() Collection branch.
   */
  @Test
  public void testEdgeIterableAndIteratorSizeWithLinkList() {
    // Set up edge property as LINKLIST type to force EntityLinkListImpl storage
    var vertexClass = session.getSchema().getClass("TestVertex");
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");
    vertexClass.createProperty(outFieldName, PropertyType.LINKLIST);
    vertexClass.createProperty(inFieldName, PropertyType.LINKLIST);

    var tx = session.begin();
    var center = tx.newVertex("TestVertex");
    for (int i = 0; i < 3; i++) {
      center.addEdge(tx.newVertex("TestVertex"), "HeavyEdge");
    }
    var centerRid = center.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(centerRid).asVertex();
    var edgeIterable = loaded.getEdges(Direction.OUT, "HeavyEdge");

    // EdgeIterable backed by EntityLinkListImpl should report size via Collection.size()
    assertTrue("LINKLIST-backed EdgeIterable should be Sizeable",
        edgeIterable instanceof Sizeable);
    var sizeableIterable = (Sizeable) edgeIterable;
    assertTrue("isSizeable should be true", sizeableIterable.isSizeable());
    assertEquals("size should be 3", 3, sizeableIterable.size());

    // Verify the EdgeIterator also reports size correctly
    var iter = edgeIterable.iterator();
    assertTrue("LINKLIST-backed EdgeIterator should be Sizeable",
        iter instanceof Sizeable);
    var sizeableIter = (Sizeable) iter;
    assertTrue("Iterator isSizeable should be true", sizeableIter.isSizeable());
    assertEquals("Iterator size should be 3", 3, sizeableIter.size());

    // Verify iteration works correctly
    int count = 0;
    while (iter.hasNext()) {
      assertNotNull(iter.next());
      count++;
    }
    assertEquals(3, count);
    tx.rollback();
  }

  /**
   * Verifies EdgeIterable.size() and isSizeable() work correctly for EntityLinkSetImpl-backed
   * edges (where the internal size is -1, requiring delegation to the backing collection).
   */
  @Test
  public void testEdgeIterableSizeWithLinkSet() {
    // Create a single edge first via normal LinkBag
    var tx = session.begin();
    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addEdge(inVertex, "HeavyEdge");
    var outRid = outVertex.getIdentity();
    tx.commit();

    // Replace the edge property with EntityLinkSetImpl
    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outRid);
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var bag = (LinkBag) loadedOut.getPropertyInternal(outFieldName);

    var edgeRids = new ArrayList<Identifiable>();
    for (var pair : bag) {
      edgeRids.add(pair.primaryRid());
    }

    var linkSet = new EntityLinkSetImpl(loadedOut, edgeRids);
    loadedOut.setPropertyInternal(outFieldName, linkSet);

    // Now getEdges should return EdgeIterable backed by EntityLinkSetImpl
    var edgeIterable = loadedOut.asVertex().getEdges(Direction.OUT, "HeavyEdge");
    assertTrue("LINKSET-backed EdgeIterable should be Sizeable",
        edgeIterable instanceof Sizeable);
    var sizeable = (Sizeable) edgeIterable;
    assertTrue(sizeable.isSizeable());
    assertEquals(1, sizeable.size());
    tx.rollback();
  }

  /**
   * Verifies FrontendTransactionImpl.loadEdge(Identifiable) with an already-loaded Edge
   * returns the same edge when it's bound to the current session. This covers the
   * "id instanceof Edge" branch in FrontendTransactionImpl.loadEdge.
   */
  @Test
  public void testLoadEdgeWithAlreadyLoadedEdge() {
    session.createEdgeClass("LoadEdgeTestE");

    var edgeRid = session.computeInTx(tx -> {
      var v1 = tx.newVertex("TestVertex");
      var v2 = tx.newVertex("TestVertex");
      var edge = v1.addEdge(v2, "LoadEdgeTestE");
      return edge.getIdentity();
    });

    session.executeInTx(tx -> {
      // Load the edge once
      var edge = tx.loadEdge(edgeRid);
      assertNotNull(edge);

      // loadEdge with the already-loaded Edge object should return the same instance
      var edgeAgain = tx.loadEdge(edge);
      assertNotNull(edgeAgain);
      assertEquals(edge.getIdentity(), edgeAgain.getIdentity());

      // loadEdgeOrNull with the already-loaded Edge should also work
      var edgeOrNull = tx.loadEdgeOrNull(edge);
      assertNotNull(edgeOrNull);
      assertEquals(edge.getIdentity(), edgeOrNull.getIdentity());
    });
  }

  /**
   * Verifies ResultInternal.toMapValue converts an edge to its identity RID, and
   * ResultInternal.asEdge() works when the result directly wraps an Edge instance.
   */
  @Test
  public void testResultInternalToMapValueForEdge() {
    session.createEdgeClass("MapValueTestE");

    session.executeInTx(tx -> {
      var v1 = tx.newVertex("TestVertex");
      var v2 = tx.newVertex("TestVertex");
      var edge = v1.addEdge(v2, "MapValueTestE");
      edge.setString("label", "test");

      // Wrap the edge directly in a ResultInternal
      var result = new ResultInternal(session, edge);

      // toMapValue for an Edge should return its identity
      var mapValue = ResultInternal.toMapValue(edge, false);
      assertNotNull(mapValue);
      assertEquals(edge.getIdentity(), mapValue);

      // asEdge() on a result wrapping an Edge directly should return the edge
      var resultEdge = result.asEdge();
      assertNotNull(resultEdge);
      assertEquals(edge.getIdentity(), resultEdge.getIdentity());
    });
  }

  /**
   * Verifies that adding a second incoming edge of the same type to a vertex whose in_field
   * is a single Identifiable triggers conversion to LinkBag via resolveSecondaryRid for the
   * IN direction. This exercises the "in_" prefix branch in resolveSecondaryRid.
   */
  @Test
  public void testSecondIncomingEdgeTriggersResolveSecondaryRidForInDirection() {
    // Set up incoming edge property as LINK type on the vertex class
    var vertexClass = session.getSchema().getClass("TestVertex");
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");
    vertexClass.createProperty(inFieldName, PropertyType.LINK);

    // Create a single incoming edge (stored as LINK/single Identifiable)
    var tx = session.begin();
    var target = tx.newVertex("TestVertex");
    var source1 = tx.newVertex("TestVertex");
    source1.addEdge(target, "HeavyEdge");
    var targetRid = target.getIdentity();
    tx.commit();

    // Verify the property is stored as LINK (single Identifiable)
    tx = session.begin();
    var loaded = (EntityImpl) tx.load(targetRid);
    var fieldValue = loaded.getPropertyInternal(inFieldName);
    assertTrue("First incoming edge should be stored as single Identifiable",
        fieldValue instanceof Identifiable);
    tx.commit();

    // Drop the LINK constraint to allow conversion to LinkBag
    vertexClass.dropProperty(inFieldName);

    // Add a second incoming edge — triggers Identifiable→LinkBag conversion
    // with resolveSecondaryRid reading the "out" property (IN prefix → DIRECTION_OUT)
    tx = session.begin();
    var loadedTarget = tx.load(targetRid).asVertex();
    var source2 = tx.newVertex("TestVertex");
    source2.addEdge(loadedTarget, "HeavyEdge");
    tx.commit();

    // Verify both incoming edges are iterable
    tx = session.begin();
    var reloaded = tx.load(targetRid).asVertex();
    int edgeCount = 0;
    for (var edge : reloaded.getEdges(Direction.IN, "HeavyEdge")) {
      assertNotNull(edge);
      edgeCount++;
    }
    assertEquals("Should have 2 incoming edges after conversion", 2, edgeCount);
    tx.commit();
  }

  /**
   * Verifies that getEdges via SchemaClass parameter correctly delegates to string-based
   * getEdges. This covers the VertexEntityImpl.getEdges(Direction, SchemaClass...) overload.
   */
  @Test
  public void testGetEdgesWithSchemaClass() {
    var tx = session.begin();
    var v1 = tx.newVertex("TestVertex");
    var v2 = tx.newVertex("TestVertex");
    v1.addEdge(v2, "HeavyEdge");
    var v1Rid = v1.getIdentity();
    tx.commit();

    tx = session.begin();
    var loaded = tx.load(v1Rid).asVertex();
    var edgeClass = session.getSchema().getClass("HeavyEdge");

    int count = 0;
    for (var edge : loaded.getEdges(Direction.OUT, edgeClass)) {
      assertNotNull(edge);
      count++;
    }
    assertEquals(1, count);
    tx.commit();
  }

  // --- EdgeFromLinkBagIterable integration tests ---

  /**
   * Verifies that getEdges(OUT) returns the correct edge records when using
   * EdgeFromLinkBagIterable (the new optimized path for LinkBag edge traversal).
   * This validates that getEdgesInternal's LinkBag case produces identical results
   * via EdgeFromLinkBagIterable as the old EdgeIterable path.
   */
  @Test
  public void testGetEdgesOutReturnsCorrectEdgeRecords() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "HeavyEdge");
    edge.setProperty("weight", 42);
    var edgeRid = edge.getIdentity();

    tx.commit();

    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();

    var edges = loadedOut.getEdges(Direction.OUT, "HeavyEdge");
    var iter = edges.iterator();
    assertTrue("Should have at least one edge", iter.hasNext());
    var loadedEdge = iter.next();
    assertEquals("Edge RID should match", edgeRid, loadedEdge.getIdentity());
    assertEquals(
        "Edge property should be preserved",
        42, loadedEdge.<Integer>getProperty("weight").intValue());
    assertFalse("Should have exactly one edge", iter.hasNext());

    tx.commit();
  }

  /**
   * Verifies that getEdges(IN) returns the correct edge records from the
   * perspective of the target vertex.
   */
  @Test
  public void testGetEdgesInReturnsCorrectEdgeRecords() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    var edge = outVertex.addEdge(inVertex, "HeavyEdge");
    var edgeRid = edge.getIdentity();

    tx.commit();

    tx = session.begin();
    var loadedIn = tx.load(inVertex.getIdentity()).asVertex();

    var edges = loadedIn.getEdges(Direction.IN, "HeavyEdge");
    var iter = edges.iterator();
    assertTrue("Should have at least one edge", iter.hasNext());
    assertEquals("Edge RID should match", edgeRid, iter.next().getIdentity());
    assertFalse("Should have exactly one edge", iter.hasNext());

    tx.commit();
  }

  /**
   * Verifies that getEdges returns all edges from multiple edge classes when
   * no label filter is applied.
   */
  @Test
  public void testGetEdgesWithMixedEdgeTypesReturnsAll() {
    var tx = session.begin();

    var center = tx.newVertex("TestVertex");
    var heavyTarget = tx.newVertex("TestVertex");
    var lightTarget = tx.newVertex("TestVertex");

    var heavyEdge = center.addEdge(heavyTarget, "HeavyEdge");
    var lightEdge = center.addEdge(lightTarget, "LightEdge");

    tx.commit();

    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();

    Set<RID> edgeRids = new HashSet<>();
    for (var e : loadedCenter.getEdges(Direction.OUT)) {
      edgeRids.add(e.getIdentity());
    }

    assertEquals("Should have 2 edges", 2, edgeRids.size());
    assertTrue(
        "Should contain HeavyEdge",
        edgeRids.contains(heavyEdge.getIdentity()));
    assertTrue(
        "Should contain LightEdge",
        edgeRids.contains(lightEdge.getIdentity()));

    tx.commit();
  }

  /**
   * Verifies that getEdges with label filtering returns only edges of the
   * specified class.
   */
  @Test
  public void testGetEdgesWithLabelFilterReturnsOnlyMatchingEdgeClass() {
    var tx = session.begin();

    var center = tx.newVertex("TestVertex");
    var heavyTarget = tx.newVertex("TestVertex");
    var lightTarget = tx.newVertex("TestVertex");

    var heavyEdge = center.addEdge(heavyTarget, "HeavyEdge");
    center.addEdge(lightTarget, "LightEdge");

    tx.commit();

    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();

    // Only request HeavyEdge edges
    var edges = loadedCenter.getEdges(Direction.OUT, "HeavyEdge");
    var iter = edges.iterator();
    assertTrue(iter.hasNext());
    assertEquals(heavyEdge.getIdentity(), iter.next().getIdentity());
    assertFalse("Should have only 1 edge for HeavyEdge", iter.hasNext());

    tx.commit();
  }

  /**
   * Verifies that getEdges returns multiple edges when a vertex has multiple
   * heavyweight edges of the same class.
   */
  @Test
  public void testGetEdgesOutWithMultipleEdgesOfSameClass() {
    var tx = session.begin();

    var center = tx.newVertex("TestVertex");
    var target1 = tx.newVertex("TestVertex");
    var target2 = tx.newVertex("TestVertex");
    var target3 = tx.newVertex("TestVertex");

    var edge1 = center.addEdge(target1, "HeavyEdge");
    var edge2 = center.addEdge(target2, "HeavyEdge");
    var edge3 = center.addEdge(target3, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();

    Set<RID> edgeRids = new HashSet<>();
    for (var e : loadedCenter.getEdges(Direction.OUT, "HeavyEdge")) {
      edgeRids.add(e.getIdentity());
    }

    assertEquals("Should have 3 edges", 3, edgeRids.size());
    assertTrue(edgeRids.contains(edge1.getIdentity()));
    assertTrue(edgeRids.contains(edge2.getIdentity()));
    assertTrue(edgeRids.contains(edge3.getIdentity()));

    tx.commit();
  }
}
