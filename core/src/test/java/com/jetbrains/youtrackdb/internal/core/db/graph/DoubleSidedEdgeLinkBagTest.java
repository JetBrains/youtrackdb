package com.jetbrains.youtrackdb.internal.core.db.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityHelper;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that double-sided edge storage in LinkBag correctly stores primary and secondary RIDs.
 *
 * <p>For heavyweight (stateful) edges, the LinkBag should store:
 * <ul>
 *   <li>primaryRid = edge record RID</li>
 *   <li>secondaryRid = opposite vertex RID</li>
 * </ul>
 *
 * <p>For lightweight edges, the LinkBag should store:
 * <ul>
 *   <li>primaryRid = opposite vertex RID</li>
 *   <li>secondaryRid = opposite vertex RID (same as primary)</li>
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
    session.getSchema().createLightweightEdgeClass("LightEdge");
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
    var edge = outVertex.addStateFulEdge(inVertex, "HeavyEdge");
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
    assertFalse(
        "Heavyweight edge pair should not be lightweight",
        outPair.isLightweight());

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
    assertFalse(
        "Heavyweight edge pair should not be lightweight",
        inPair.isLightweight());

    tx.commit();
  }

  /**
   * Verifies that a lightweight edge stores the opposite vertex RID as both primary
   * and secondary (i.e. the pair is "lightweight") in both the outgoing and incoming
   * vertex LinkBags.
   */
  @Test
  public void testLightweightEdgeStoresSameVertexRidAsBothPrimaryAndSecondary() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addLightWeightEdge(inVertex, "LightEdge");

    tx.commit();

    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outVertex.getIdentity());
    var loadedIn = (EntityImpl) tx.load(inVertex.getIdentity());

    // Check outgoing vertex: out_LightEdge should contain RidPair(inVertexRid, inVertexRid)
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "LightEdge");
    var outBag = (LinkBag) loadedOut.getPropertyInternal(outFieldName);
    assertNotNull("Outgoing vertex should have out_LightEdge LinkBag", outBag);
    assertEquals("Outgoing LinkBag should have exactly 1 entry", 1, outBag.size());

    var outPair = outBag.iterator().next();
    assertEquals(
        "Lightweight outgoing primary RID should be the target (in) vertex",
        inVertex.getIdentity(), outPair.primaryRid());
    assertEquals(
        "Lightweight outgoing secondary RID should also be the target (in) vertex",
        inVertex.getIdentity(), outPair.secondaryRid());
    assertTrue(
        "Lightweight edge pair should be lightweight (primary == secondary)",
        outPair.isLightweight());

    // Check incoming vertex: in_LightEdge should contain RidPair(outVertexRid, outVertexRid)
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "LightEdge");
    var inBag = (LinkBag) loadedIn.getPropertyInternal(inFieldName);
    assertNotNull("Incoming vertex should have in_LightEdge LinkBag", inBag);
    assertEquals("Incoming LinkBag should have exactly 1 entry", 1, inBag.size());

    var inPair = inBag.iterator().next();
    assertEquals(
        "Lightweight incoming primary RID should be the source (out) vertex",
        outVertex.getIdentity(), inPair.primaryRid());
    assertEquals(
        "Lightweight incoming secondary RID should also be the source (out) vertex",
        outVertex.getIdentity(), inPair.secondaryRid());
    assertTrue(
        "Lightweight edge pair should be lightweight (primary == secondary)",
        inPair.isLightweight());

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

    var edge1 = outVertex.addStateFulEdge(inVertex1, "HeavyEdge");
    var edge2 = outVertex.addStateFulEdge(inVertex2, "HeavyEdge");

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
      assertFalse("All pairs should be heavyweight", pair.isLightweight());
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
   * Verifies that mixing lightweight and heavyweight edges on the same vertex stores
   * the correct RidPair format for each type.
   */
  @Test
  public void testMixedLightweightAndHeavyweightEdgesOnSameVertex() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertexLight = tx.newVertex("TestVertex");
    var inVertexHeavy = tx.newVertex("TestVertex");

    outVertex.addLightWeightEdge(inVertexLight, "LightEdge");
    var heavyEdge = outVertex.addStateFulEdge(inVertexHeavy, "HeavyEdge");
    var heavyEdgeRid = heavyEdge.getIdentity();

    tx.commit();

    tx = session.begin();
    var loadedOut = (EntityImpl) tx.load(outVertex.getIdentity());

    // Check lightweight edge field
    var lightOutField = Vertex.getEdgeLinkFieldName(Direction.OUT, "LightEdge");
    var lightBag = (LinkBag) loadedOut.getPropertyInternal(lightOutField);
    assertNotNull(lightBag);
    assertEquals(1, lightBag.size());
    var lightPair = lightBag.iterator().next();
    assertTrue("Light edge should be lightweight", lightPair.isLightweight());
    assertEquals(inVertexLight.getIdentity(), lightPair.primaryRid());
    assertEquals(inVertexLight.getIdentity(), lightPair.secondaryRid());

    // Check heavyweight edge field
    var heavyOutField = Vertex.getEdgeLinkFieldName(Direction.OUT, "HeavyEdge");
    var heavyBag = (LinkBag) loadedOut.getPropertyInternal(heavyOutField);
    assertNotNull(heavyBag);
    assertEquals(1, heavyBag.size());
    var heavyPair = heavyBag.iterator().next();
    assertFalse("Heavy edge should not be lightweight", heavyPair.isLightweight());
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
    var edge = outVertex.addStateFulEdge(inVertex, "HeavyEdge");

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
    assertFalse(outPair.isLightweight());

    // Verify incoming LinkBag still has correct double-sided pair
    var inFieldName = Vertex.getEdgeLinkFieldName(Direction.IN, "HeavyEdge");
    var inBag = (LinkBag) loadedIn.getPropertyInternal(inFieldName);
    assertNotNull(inBag);
    assertEquals(1, inBag.size());
    var inPair = inBag.iterator().next();
    assertEquals(edgeRid, inPair.primaryRid());
    assertEquals(outRid, inPair.secondaryRid());
    assertFalse(inPair.isLightweight());

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
    outVertex.addStateFulEdge(inVertex, "HeavyEdge");

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
   * Verifies that getVertices(OUT) on a lightweight edge returns the correct opposite
   * vertex. Lightweight edges store the same vertex RID as both primary and secondary.
   */
  @Test
  public void testGetVerticesOutReturnsOppositeVertexForLightweightEdge() {
    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");
    outVertex.addLightWeightEdge(inVertex, "LightEdge");

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
    outVertex.addStateFulEdge(inVertex, "HeavyEdge");

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

    outVertex.addStateFulEdge(heavyTarget, "HeavyEdge");
    outVertex.addLightWeightEdge(lightTarget, "LightEdge");

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
    vertexA.addStateFulEdge(vertexB, "HeavyEdge");
    vertexC.addStateFulEdge(vertexA, "HeavyEdge");

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
   * Verifies that getVertices works correctly when a vertex has both heavyweight
   * and lightweight edges, and returns all adjacent vertices when no label filter
   * is applied.
   */
  @Test
  public void testGetVerticesWithMixedEdgeTypesReturnsAllAdjacentVertices() {
    var tx = session.begin();

    var center = tx.newVertex("TestVertex");
    var heavyTarget = tx.newVertex("TestVertex");
    var lightTarget = tx.newVertex("TestVertex");

    center.addStateFulEdge(heavyTarget, "HeavyEdge");
    center.addLightWeightEdge(lightTarget, "LightEdge");

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
        "Should contain heavyweight target",
        adjacentRids.contains(heavyTarget.getIdentity()));
    assertTrue(
        "Should contain lightweight target",
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

    center.addStateFulEdge(target1, "HeavyEdge");
    center.addStateFulEdge(target2, "HeavyEdge");
    center.addStateFulEdge(target3, "HeavyEdge");

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
    var edge = outVertex.addStateFulEdge(inVertex, "HeavyEdge");

    tx.commit();

    // Delete the edge in a new transaction
    tx = session.begin();
    var loadedEdge = tx.load(edge.getIdentity()).asStatefulEdge();
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
    loadedOut.addStateFulEdge(loadedIn, "HeavyEdge");
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
    outVertex.addStateFulEdge(inVertex1, "HeavyEdge");
    tx.commit();

    // In a new transaction, add another edge then rollback
    tx = session.begin();
    var loadedOut = tx.load(outVertex.getIdentity()).asVertex();
    var loadedIn2 = tx.load(inVertex2.getIdentity()).asVertex();
    loadedOut.addStateFulEdge(loadedIn2, "HeavyEdge");
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
    var edge = outVertex.addStateFulEdge(inVertex, "HeavyEdge");

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

    center.addStateFulEdge(heavyTarget, "HeavyEdge");
    center.addLightWeightEdge(lightTarget, "LightEdge");
    heavySource.addStateFulEdge(center, "HeavyEdge");

    tx.commit();

    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();

    // BOTH with no label filter should return vertices from all directions and types
    Set<RID> allAdjacentRids = new HashSet<>();
    for (var v : loadedCenter.getVertices(Direction.BOTH)) {
      allAdjacentRids.add(v.getIdentity());
    }

    assertEquals("Should have 3 adjacent vertices", 3, allAdjacentRids.size());
    assertTrue("Should contain heavyweight outgoing target",
        allAdjacentRids.contains(heavyTarget.getIdentity()));
    assertTrue("Should contain lightweight outgoing target",
        allAdjacentRids.contains(lightTarget.getIdentity()));
    assertTrue("Should contain heavyweight incoming source",
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
      center.addStateFulEdge(target, "HeavyEdge");
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
      assertFalse(
          "Each BTree pair should be heavyweight (edge RID != vertex RID)",
          pair.isLightweight());
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
      center.addStateFulEdge(target, "HeavyEdge");
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
      center.addStateFulEdge(target, "HeavyEdge");
    }

    tx.commit();

    // Pick the first edge and its target to delete
    tx = session.begin();
    var loadedCenter = tx.load(center.getIdentity()).asVertex();
    var edgeIter = loadedCenter.getEdges(Direction.OUT, "HeavyEdge").iterator();
    assertTrue("Should have edges to delete", edgeIter.hasNext());
    var edgeToDelete = edgeIter.next().asStatefulEdge();
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
    vertex.addStateFulEdge(vertex, "HeavyEdge");

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

    vertex.addStateFulEdge(target1, "HeavyEdge");
    vertex.addStateFulEdge(target2, "HeavyEdge");

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

    vertex.addStateFulEdge(target1, "HeavyEdge");
    vertex.addStateFulEdge(target2, "HeavyEdge");
    vertex.addStateFulEdge(target3, "HeavyEdge");

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

    var edge1 = outVertex.addStateFulEdge(inVertex1, "HeavyEdge");
    var edge2 = outVertex.addStateFulEdge(inVertex2, "HeavyEdge");

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

  /**
   * Verifies that a LINKBAG index on an edge field stores the opposite vertex RID
   * for lightweight edges (since primaryRid == secondaryRid == opposite vertex for
   * lightweight edges).
   */
  @Test
  public void testLinkBagIndexContainsVertexRidForLightweightEdges() {
    var vertexClass = session.getSchema().getClass("TestVertex");
    var outFieldName = Vertex.getEdgeLinkFieldName(Direction.OUT, "LightEdge");
    vertexClass.createProperty(outFieldName, PropertyType.LINKBAG);
    vertexClass.createIndex(
        "testLightEdgeIndex", SchemaClass.INDEX_TYPE.NOTUNIQUE, outFieldName);

    var tx = session.begin();

    var outVertex = tx.newVertex("TestVertex");
    var inVertex = tx.newVertex("TestVertex");

    outVertex.addLightWeightEdge(inVertex, "LightEdge");

    var inVertexRid = inVertex.getIdentity();

    tx.commit();

    // Verify the index contains the opposite vertex RID (primaryRid for lightweight)
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
    assertTrue("Index key should be the opposite vertex RID",
        indexKeys.contains(inVertexRid));

    tx.commit();
  }
}
