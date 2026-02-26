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
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.storage.ridbag.RidPair;
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
}
