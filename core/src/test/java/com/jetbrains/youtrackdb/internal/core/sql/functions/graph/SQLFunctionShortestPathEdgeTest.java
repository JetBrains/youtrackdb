package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static java.util.Arrays.asList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Entity;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Focused coverage for {@link SQLFunctionShortestPath} paths that the existing tests miss:
 *
 * <ul>
 *   <li>{@code edge:true} walk branches in {@code walkLeft}/{@code walkRight}, which interleave
 *       vertex + edge identities in the returned path instead of vertices only.
 *   <li>Source-equals-destination early return (one-element path).
 *   <li>All four argument-shape error branches (null/multi source, null/multi destination).
 *   <li>Collection-of-strings edge-type parameter path.
 *   <li>Identifiable additional-params path (load options from a record).
 * </ul>
 *
 * <p>The existing {@link SQLFunctionShortestPathTest} covers the non-edge walk and {@code
 * maxDepth}; this test complements it.
 */
public class SQLFunctionShortestPathEdgeTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private SQLFunctionShortestPath function;

  private Map<Integer, Vertex> vertices = new HashMap<>();

  @Before
  public void setUp() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create(
        "SQLFunctionShortestPathEdgeTest",
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    session =
        youTrackDB.open("SQLFunctionShortestPathEdgeTest", "admin", DbTestBase.ADMIN_PASSWORD);

    session.createEdgeClass("Edge1");
    session.createEdgeClass("Edge2");

    session.begin();
    for (var i = 1; i <= 4; i++) {
      var v = session.newVertex();
      v.setProperty("node_id", "V" + i);
      vertices.put(i, v);
    }
    // Graph topology:
    // v1 -Edge1-> v2 -Edge1-> v3 -Edge1-> v4
    // v1 -Edge2-> v3        (shortcut)
    session.newEdge(vertices.get(1), vertices.get(2), "Edge1");
    session.newEdge(vertices.get(2), vertices.get(3), "Edge1");
    session.newEdge(vertices.get(3), vertices.get(4), "Edge1");
    session.newEdge(vertices.get(1), vertices.get(3), "Edge2");
    session.commit();

    function = new SQLFunctionShortestPath();
  }

  @After
  public void tearDown() {
    // Roll back any lingering transaction so session.close() does not mask a real failure
    // with a secondary "active transaction" exception. Matches the Step 1 convention.
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
    if (session != null) {
      session.close();
    }
    if (youTrackDB != null) {
      // Drop the database explicitly so a failed @Before in the next method cannot trip a
      // "database already exists" cascade — the in-memory engine retains the entry until
      // either drop() or full-process exit (BC5/TS8).
      try {
        if (youTrackDB.exists("SQLFunctionShortestPathEdgeTest")) {
          youTrackDB.drop("SQLFunctionShortestPathEdgeTest");
        }
      } finally {
        youTrackDB.close();
      }
    }
  }

  private void reloadVertices() {
    var tx = session.getActiveTransaction();
    var newVertices = new HashMap<Integer, Vertex>();
    for (var entry : vertices.entrySet()) {
      newVertices.put(entry.getKey(), tx.load(entry.getValue()));
    }
    vertices = newVertices;
  }

  /**
   * edge=true on a BOTH-direction walk must return an alternating vertex/edge/vertex/… path —
   * exercises the {@code Boolean.TRUE.equals(ctx.edge)} branch of {@code walkLeft}/{@code
   * walkRight} (the only place the edge identities are threaded into {@code previouses}/{@code
   * nexts}).
   */
  @Test
  public void shortestPathWithEdgeTrueReturnsVertexEdgeVertexTripletPerHop() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> opts = new HashMap<>();
    opts.put("edge", Boolean.TRUE);

    var result =
        function.execute(
            null,
            null,
            null,
            new Object[] {vertices.get(1), vertices.get(4), "BOTH", null, opts},
            context);

    // v1 -Edge2-> v3 -Edge1-> v4 with edges interleaved:
    // [v1, edge(v1,v3), v3, edge(v3,v4), v4] → 5 elements.
    assertEquals("edge=true should interleave vertices and edges: " + result, 5, result.size());
    assertEquals(vertices.get(1).getIdentity(), result.get(0));
    assertEquals(vertices.get(3).getIdentity(), result.get(2));
    assertEquals(vertices.get(4).getIdentity(), result.get(4));
    // Slots 1 and 3 are edge RIDs — must be edges connecting the adjacent vertex slots (not
    // just "any edge"). This pins the interleaving contract the test documents.
    var tx = session.getActiveTransaction();
    Entity edge1 = tx.load((RID) result.get(1));
    Entity edge2 = tx.load((RID) result.get(3));
    assertTrue("slot 1 should be an edge record", edge1.isEdge());
    assertTrue("slot 3 should be an edge record", edge2.isEdge());
    var e1 = edge1.asEdge();
    var e2 = edge2.asEdge();
    // edge1 connects (v1, v3) — either direction is acceptable for BOTH traversal.
    assertSame(
        "edge1 must connect v1 and v3",
        Boolean.TRUE,
        (e1.getFrom().getIdentity().equals(vertices.get(1).getIdentity())
            && e1.getTo().getIdentity().equals(vertices.get(3).getIdentity()))
            || (e1.getFrom().getIdentity().equals(vertices.get(3).getIdentity())
                && e1.getTo().getIdentity().equals(vertices.get(1).getIdentity())));
    assertSame(
        "edge2 must connect v3 and v4",
        Boolean.TRUE,
        (e2.getFrom().getIdentity().equals(vertices.get(3).getIdentity())
            && e2.getTo().getIdentity().equals(vertices.get(4).getIdentity()))
            || (e2.getFrom().getIdentity().equals(vertices.get(4).getIdentity())
                && e2.getTo().getIdentity().equals(vertices.get(3).getIdentity())));
    session.commit();
  }

  /** edge=true with an edge-type filter also exercises the non-null {@code edgeType} branch. */
  @Test
  public void shortestPathWithEdgeTrueAndEdgeTypeFilterUsesFilteredWalk() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> opts = new HashMap<>();
    opts.put("edge", Boolean.TRUE);

    // Edge2 is only v1→v3; with only Edge2 allowed, v4 is unreachable from v1. Use Edge1.
    var result =
        function.execute(
            null,
            null,
            null,
            new Object[] {vertices.get(1), vertices.get(4), "OUT", "Edge1", opts},
            context);

    // Edge1 path: v1 -e- v2 -e- v3 -e- v4 → 7 elements.
    assertEquals(7, result.size());
    assertEquals(vertices.get(1).getIdentity(), result.get(0));
    assertEquals(vertices.get(2).getIdentity(), result.get(2));
    assertEquals(vertices.get(3).getIdentity(), result.get(4));
    assertEquals(vertices.get(4).getIdentity(), result.get(6));
    session.commit();
  }

  /** Source equals destination: short-circuit returns a 1-element list before any walk. */
  @Test
  public void shortestPathSourceEqualsDestinationReturnsSingleElementPath() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    var result =
        function.execute(
            null, null, null, new Object[] {vertices.get(2), vertices.get(2)}, context);

    assertEquals(1, result.size());
    assertEquals(vertices.get(2).getIdentity(), result.get(0));
    session.commit();
  }

  /** Collection-of-strings edge-type parameter path (joins into a csv edgeType). */
  @Test
  public void shortestPathAcceptsCollectionOfEdgeTypeStrings() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    var result =
        function.execute(
            null,
            null,
            null,
            new Object[] {vertices.get(1), vertices.get(4), "BOTH", asList("Edge1", "Edge2")},
            context);

    // v1 -Edge2-> v3 -Edge1-> v4 — 3-element path.
    assertEquals(3, result.size());
    assertEquals(vertices.get(1).getIdentity(), result.get(0));
    assertEquals(vertices.get(3).getIdentity(), result.get(1));
    assertEquals(vertices.get(4).getIdentity(), result.get(2));
    session.commit();
  }

  /**
   * {@code Identifiable} additional-params path: options are loaded from a stored record and
   * parsed via {@code EntityImpl.toMap()}.
   */
  @Test
  public void shortestPathAcceptsIdentifiableAdditionalParamsRecord() {
    session.begin();
    var optsRecord = session.newEntity();
    optsRecord.setProperty("maxDepth", 2);
    // session.newEntity() auto-registers with the transaction; commit persists it.
    session.commit();

    session.begin();
    reloadVertices();
    // Reload the saved record to get a managed identity.
    var managedOpts = session.load(optsRecord.getIdentity());

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    var result =
        function.execute(
            null,
            null,
            null,
            new Object[] {vertices.get(1), vertices.get(4), "OUT", null, managedOpts},
            context);

    // maxDepth=2 prevents reaching v4 even via the shortcut (needs ≥2 hops).
    assertEquals(0, result.size());
    session.commit();
  }

  @Test
  public void shortestPathNullSourceRaisesIllegalArgument() {
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    try {
      function.execute(null, null, null, new Object[] {null, vertices.get(4)}, context);
      fail("null source should raise IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertEquals("Only one sourceVertex is allowed", expected.getMessage());
    }
  }

  @Test
  public void shortestPathNullDestinationRaisesIllegalArgument() {
    // Source-side gets past the guard and tries to load the vertex, so we need an active tx
    // to reach the destination-null check. tearDown rolls back if the commit is skipped.
    session.begin();
    reloadVertices();
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    try {
      function.execute(null, null, null, new Object[] {vertices.get(1), null}, context);
      fail("null destination should raise IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertEquals("Only one destinationVertex is allowed", expected.getMessage());
    }
    session.commit();
  }

  @Test
  public void shortestPathNonVertexSourceRaisesIllegalArgument() {
    // Create the schema class OUTSIDE the transaction — schema changes are not transactional.
    session.getSchema().createClass("Plain");

    session.begin();
    var plain = session.newEntity("Plain");
    plain.setProperty("k", "v");
    session.commit();

    session.begin();
    reloadVertices();
    var managed = session.load(plain.getIdentity());

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    try {
      function.execute(null, null, null, new Object[] {managed, vertices.get(4)}, context);
      fail("plain entity source should raise IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertEquals("The sourceVertex must be a vertex record", expected.getMessage());
    }
    session.commit();
  }

  /**
   * Symmetric coverage: non-vertex destination hits the parallel throw at line 117 of
   * SQLFunctionShortestPath. Uses a separate class from shortestPathNonVertexSourceRaises
   * IllegalArgument to avoid class-creation conflicts.
   */
  @Test
  public void shortestPathNonVertexDestinationRaisesIllegalArgument() {
    session.getSchema().createClass("PlainDst");
    session.begin();
    var plain = session.newEntity("PlainDst");
    plain.setProperty("k", "v");
    session.commit();

    session.begin();
    reloadVertices();
    var managed = session.load(plain.getIdentity());

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    try {
      function.execute(null, null, null, new Object[] {vertices.get(1), managed}, context);
      fail("plain entity destination should raise IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertEquals("The destinationVertex must be a vertex record", expected.getMessage());
    }
    session.commit();
  }

  /** Literal-string destination exercises the final `else throw` branch for the destination. */
  @Test
  public void shortestPathLiteralStringDestinationRaisesIllegalArgument() {
    session.begin();
    reloadVertices();
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    try {
      function.execute(null, null, null, new Object[] {vertices.get(1), "not-a-vertex"}, context);
      fail("literal string destination should raise IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertEquals("The destinationVertex must be a vertex record", expected.getMessage());
    }
    session.commit();
  }

  /**
   * Passing a literal string for the source hits the final {@code else throw} branch that rejects
   * anything that did not resolve to an {@link
   * com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable}.
   */
  @Test
  public void shortestPathLiteralStringSourceRaisesIllegalArgument() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    try {
      function.execute(null, null, null, new Object[] {"not-a-vertex", vertices.get(4)}, context);
      fail("literal string source should raise IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      assertEquals("The sourceVertex must be a vertex record", expected.getMessage());
    }
  }

  /**
   * maxDepth=0 forces an immediate break on the first loop-entry {@code maxDepth ≤ depth} check
   * (depth starts at 1). Complements {@link SQLFunctionShortestPathTest}'s maxDepth tests which
   * all start at 3+.
   */
  @Test
  public void shortestPathWithMaxDepthZeroReturnsEmpty() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> opts = new HashMap<>();
    opts.put("maxDepth", 0);

    var result =
        function.execute(
            null,
            null,
            null,
            new Object[] {vertices.get(1), vertices.get(4), "OUT", null, opts},
            context);

    assertNotNull(result);
    assertEquals(0, result.size());
    session.commit();
  }

  /**
   * {@code edge="true"} hits the String-path of {@code toBoolean} (the parseBoolean branch);
   * the returned path must still be the interleaved vertex/edge form, matching the Boolean
   * true case.
   */
  @Test
  public void shortestPathWithEdgeOptionAsStringTrueUsesInterleavedForm() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> opts = new HashMap<>();
    opts.put("edge", "true");

    var result =
        function.execute(
            null,
            null,
            null,
            new Object[] {vertices.get(1), vertices.get(4), "BOTH", null, opts},
            context);

    assertEquals(5, result.size()); // same size as the Boolean.TRUE test
    session.commit();
  }

  /**
   * {@code edge=Boolean.FALSE} explicitly covers the negative path through {@code toBoolean} →
   * {@code ctx.edge = FALSE} → vertex-only walk (3-element path).
   */
  @Test
  public void shortestPathWithEdgeOptionExplicitFalseUsesVertexOnlyWalk() {
    session.begin();
    reloadVertices();

    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    Map<String, Object> opts = new HashMap<>();
    opts.put("edge", Boolean.FALSE);

    var result =
        function.execute(
            null,
            null,
            null,
            new Object[] {vertices.get(1), vertices.get(4), "BOTH", null, opts},
            context);

    // vertex-only: v1 → v3 → v4, size 3.
    assertEquals(3, result.size());
    assertEquals(vertices.get(1).getIdentity(), result.get(0));
    assertEquals(vertices.get(3).getIdentity(), result.get(1));
    assertEquals(vertices.get(4).getIdentity(), result.get(2));
    session.commit();
  }

  /** Pin the full syntax string so drift in parameter names is caught. */
  @Test
  public void shortestPathSyntaxExposesExpectedForm() {
    assertEquals(
        "shortestPath(<sourceVertex>, <destinationVertex>,"
            + " [<direction>, [ <edgeTypeAsString> ]])",
        function.getSyntax(session));
  }
}
