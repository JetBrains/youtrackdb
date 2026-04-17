package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Edge-case coverage for {@link SQLFunctionDijkstra} that complements {@link
 * SQLFunctionDijkstraTest} (which covers the happy-path shortest-weighted traversal).
 *
 * <p>Dijkstra delegates to {@link SQLFunctionAstar} via {@code toAStarParams}, so we verify:
 *
 * <ul>
 *   <li>No direction argument → options map carries no {@code direction} key → Astar uses its
 *       default OUT direction.
 *   <li>Explicit direction argument is forwarded into the options map.
 *   <li>Destination unreachable from source → empty path (Astar returns its empty route list
 *       after exhausting the open set).
 *   <li>Weight property missing on edges → falls back to {@code MIN=0} distance (so any path
 *       traversable is found with total cost 0 equivalent).
 *   <li>{@code isVariableEdgeWeight} is {@code true} (publicly visible via class hierarchy) and
 *       {@code getDistance(node, target)} is the sentinel {@code -1} (legacy method, unused).
 *   <li>Syntax string exposes expected signature.
 * </ul>
 */
public class SQLFunctionDijkstraEdgeCasesTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;
  private SQLFunctionDijkstra function;

  private Vertex v1;
  private Vertex v2;
  private Vertex v3;
  private Vertex v4;
  private Vertex v5;

  @Before
  public void setUp() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create(
        "SQLFunctionDijkstraEdgeCasesTest",
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    session =
        youTrackDB.open("SQLFunctionDijkstraEdgeCasesTest", "admin", DbTestBase.ADMIN_PASSWORD);

    session.getSchema().createEdgeClass("weight");

    session.begin();
    v1 = session.newVertex();
    v2 = session.newVertex();
    v3 = session.newVertex();
    v4 = session.newVertex();
    v5 = session.newVertex(); // isolated — no edges attached

    v1.setProperty("node_id", "A");
    v2.setProperty("node_id", "B");
    v3.setProperty("node_id", "C");
    v4.setProperty("node_id", "D");
    v5.setProperty("node_id", "E");

    // Force shortest-path to be v1→v2→v3→v4 (1+1+1 = 3) rather than the shortcut v1→v3→v4
    // (100+1 = 101) so the expected-length assertions remain deterministic.
    var e1 = session.newEdge(v1, v2, "weight");
    e1.setProperty("weight", 1.0f);
    var e2 = session.newEdge(v2, v3, "weight");
    e2.setProperty("weight", 1.0f);
    var e3 = session.newEdge(v3, v4, "weight");
    e3.setProperty("weight", 1.0f);
    // Shortcut edge with a deliberately high weight + a missing-property "weight2" slot so the
    // null-weight-property path is exercised only by the test that asks for "weight2".
    var e4 = session.newEdge(v1, v3, "weight");
    e4.setProperty("weight", 100.0f);
    session.commit();

    function = new SQLFunctionDijkstra();
  }

  @After
  public void tearDown() {
    if (session != null && !session.isClosed() && session.isTxActive()) {
      session.rollback();
    }
    if (session != null) {
      session.close();
    }
    if (youTrackDB != null) {
      // Drop before close so a failed @Before in the next method cannot trip a "database
      // already exists" cascade — the in-memory engine retains the entry until either drop()
      // or full-process exit (BC5/TS8).
      try {
        if (youTrackDB.exists("SQLFunctionDijkstraEdgeCasesTest")) {
          youTrackDB.drop("SQLFunctionDijkstraEdgeCasesTest");
        }
      } finally {
        youTrackDB.close();
      }
    }
  }

  private void reload() {
    var tx = session.getActiveTransaction();
    v1 = tx.load(v1);
    v2 = tx.load(v2);
    v3 = tx.load(v3);
    v4 = tx.load(v4);
    v5 = tx.load(v5);
  }

  /**
   * No direction provided → {@code toAStarParams} builds an options map without a {@code
   * direction} key → Astar uses its default OUT direction.
   */
  @Test
  public void dijkstraWithoutDirectionUsesAstarDefaultOut() {
    var tx = session.begin();
    reload();
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    List<Vertex> path =
        function.execute(null, null, null, new Object[] {v1, v4, "'weight'"}, context);

    assertEquals(4, path.size());
    assertEquals(v1, path.get(0));
    assertEquals(v4, path.get(3));
    tx.commit();
  }

  /** Explicit direction argument is forwarded to Astar. */
  @Test
  public void dijkstraWithExplicitDirectionForwardsToAstar() {
    var tx = session.begin();
    reload();
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    List<Vertex> path =
        function.execute(null, null, null, new Object[] {v1, v4, "'weight'", "OUT"}, context);

    assertEquals(4, path.size());
    assertEquals(v1, path.get(0));
    assertEquals(v4, path.get(3));
    tx.commit();
  }

  /** Unreachable destination → empty path. Exercises Astar's open-set-exhausted return. */
  @Test
  public void dijkstraReturnsEmptyListWhenDestinationUnreachable() {
    var tx = session.begin();
    reload();
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    List<Vertex> path =
        function.execute(null, null, null, new Object[] {v1, v5, "'weight'"}, context);

    assertNotNull(path);
    assertEquals(0, path.size());
    tx.commit();
  }

  /**
   * Non-existent weight field → Astar's {@code getDistance(Edge)} returns {@code MIN=0} via the
   * null branch. With all weights treated as 0, multiple equally-optimal paths exist
   * (v1→v3→v4 shortcut = 3 vertices; v1→v2→v3→v4 = 4 vertices). The A* open-set processes
   * vertices in f-score order with ties broken by insertion order; since insertion order can
   * vary across JVM heap layouts, accept either canonical path but reject any out-of-graph or
   * out-of-bounds result.
   */
  @Test
  public void dijkstraWithMissingWeightFieldFallsBackToZeroDistance() {
    var tx = session.begin();
    reload();
    var context = new BasicCommandContext();
    context.setDatabaseSession(session);

    List<Vertex> path =
        function.execute(null, null, null, new Object[] {v1, v4, "'nonexistentWeight'"}, context);

    assertNotNull(path);
    assertEquals(v1, path.get(0));
    assertEquals(v4, path.get(path.size() - 1));
    // The two canonical zero-weight paths in the fixture.
    assertTrue(
        "unexpected path length (fixture admits only 3-vertex or 4-vertex traversals): "
            + path.size(),
        path.size() == 3 || path.size() == 4);
    // Every intermediate vertex must come from the fixture graph (no ghost vertices).
    for (var v : path) {
      assertTrue(
          "path contains vertex not from fixture: " + v,
          v.equals(v1) || v.equals(v2) || v.equals(v3) || v.equals(v4));
    }
    tx.commit();
  }

  /**
   * Legacy {@code getDistance(node, target)} method returns {@code -1f} sentinel — documented as
   * "not used anymore" but still on the public hierarchy, so pin the sentinel. No DB state
   * required — pure-stub method.
   */
  @Test
  public void dijkstraGetDistanceNodeTargetReturnsLegacySentinel() {
    assertEquals(-1.0f, function.getDistance(v1, v2), 0.0f);
  }

  /** Pin the exact Dijkstra syntax string so parameter-name drift is caught. */
  @Test
  public void dijkstraSyntaxExposesFourParameterSignature() {
    assertEquals(
        "dijkstra(<sourceVertex>, <destinationVertex>, <weightEdgeFieldName>, [<direction>])",
        function.getSyntax(session));
  }

  @Test
  public void dijkstraIsVariableEdgeWeightReturnsTrue() {
    // Package-private protected-override visibility via same-package test — pins that Dijkstra
    // advertises variable edge weights (Astar relies on this flag internally).
    assertTrue(function.isVariableEdgeWeight());
  }

  @Test
  public void dijkstraAggregateResultsIsFalse() {
    // Inherited from SQLFunctionAstar via SQLFunctionPathFinder — traversal functions are never
    // aggregated; pinning here guards against an accidental override in Dijkstra.
    assertFalse(function.aggregateResults());
  }
}
