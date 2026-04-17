package com.jetbrains.youtrackdb.internal.core.sql.functions.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Direction;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers {@link SQLFunctionPathFinder} through a test-only subclass that actually invokes the
 * abstract base class's {@code execute(CommandContext)} method.
 *
 * <p>In production, the only concrete descendant ({@link SQLFunctionDijkstra}) overrides {@code
 * execute} to delegate to {@link SQLFunctionAstar}, so the PathFinder traversal loop, {@code
 * getPath}, {@code getNeighbors}, {@code getMinimum}, {@code findMinimalDistances}, and
 * friends are effectively dead in the default execution path. This test re-activates them via a
 * small fixed-weight subclass so the class's own semantics are verified and covered.
 */
public class SQLFunctionPathFinderTest {

  private YouTrackDBImpl youTrackDB;
  private DatabaseSessionEmbedded session;

  private Vertex v1;
  private Vertex v2;
  private Vertex v3;
  private Vertex v4;
  private Vertex vIsolated;

  @Before
  public void setUp() {
    youTrackDB = (YouTrackDBImpl) YourTracks.instance(DbTestBase.getBaseDirectoryPath(getClass()));
    youTrackDB.create(
        "SQLFunctionPathFinderTest",
        DatabaseType.MEMORY,
        new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
    session = youTrackDB.open("SQLFunctionPathFinderTest", "admin", DbTestBase.ADMIN_PASSWORD);

    session.createEdgeClass("EdgeFixed");

    session.begin();
    v1 = session.newVertex();
    v2 = session.newVertex();
    v3 = session.newVertex();
    v4 = session.newVertex();
    vIsolated = session.newVertex();
    v1.setProperty("name", "A");
    v2.setProperty("name", "B");
    v3.setProperty("name", "C");
    v4.setProperty("name", "D");
    vIsolated.setProperty("name", "X");

    // v1 -> v2 -> v3 -> v4 (linear out-chain; BOTH direction also allows reverse).
    session.newEdge(v1, v2, "EdgeFixed");
    session.newEdge(v2, v3, "EdgeFixed");
    session.newEdge(v3, v4, "EdgeFixed");
    session.commit();
  }

  @After
  public void tearDown() {
    session.close();
    youTrackDB.close();
  }

  private void reload() {
    var tx = session.getActiveTransaction();
    v1 = tx.load(v1);
    v2 = tx.load(v2);
    v3 = tx.load(v3);
    v4 = tx.load(v4);
    vIsolated = tx.load(vIsolated);
  }

  /** Test subclass: distance between adjacent vertices is always 1. */
  private static final class FixedPathFinder extends SQLFunctionPathFinder {

    FixedPathFinder() {
      super("fixed", 0, 0);
    }

    @Override
    public Object execute(
        Object iThis, Result iCurrentRecord, Object iCurrentResult, Object[] iParams,
        CommandContext iContext) {
      // Not used — we invoke the package-private super.execute(context) directly in the test.
      return null;
    }

    @Override
    protected float getDistance(Vertex node, Vertex target) {
      return 1.0f;
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return "fixed()";
    }

    // Expose for the test.
    LinkedList<Vertex> runExecute(CommandContext ctx) {
      return super.execute(ctx);
    }

    void initEndpoints(Vertex src, Vertex dst) {
      paramSourceVertex = src;
      paramDestinationVertex = dst;
    }

    void initEndpoints(Vertex src, Vertex dst, Direction dir) {
      paramSourceVertex = src;
      paramDestinationVertex = dst;
      paramDirection = dir;
    }

    Set<Vertex> exposedNeighbors(Vertex node) {
      return getNeighbors(node);
    }

    Vertex exposedMin(Set<Vertex> set) {
      return getMinimum(set);
    }

    boolean exposedNotSettled(Vertex v) {
      return isNotSettled(v);
    }

    boolean exposedContinue() {
      return continueTraversing();
    }

    float exposedShortest(Vertex v) {
      return getShortestDistance(v);
    }

    float exposedSum(float a, float b) {
      return sumDistances(a, b);
    }

    boolean exposedVariableEdgeWeight() {
      return isVariableEdgeWeight();
    }

    LinkedList<Vertex> exposedGetPath() {
      return getPath();
    }

    Object exposedGetResult() {
      return getResult();
    }

    boolean exposedAggregateResults() {
      return aggregateResults();
    }
  }

  /**
   * Full traversal path v1 → v2 → v3 → v4 via the abstract {@code execute(context)} loop. This
   * covers {@code findMinimalDistances}, {@code getNeighbors}, {@code getMinimum}, {@code
   * isNotSettled}, {@code continueTraversing}, and {@code getPath} in one go.
   */
  @Test
  public void executeFindsPathOnLinearGraph() {
    var tx = session.begin();
    reload();
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    var finder = new FixedPathFinder();
    finder.initEndpoints(v1, v4);

    var path = finder.runExecute(ctx);

    assertNotNull(path);
    assertEquals(4, path.size());
    assertEquals(v1, path.get(0));
    assertEquals(v2, path.get(1));
    assertEquals(v3, path.get(2));
    assertEquals(v4, path.get(3));

    // The execute() loop records max* diagnostics onto the context.
    assertNotNull(ctx.getVariable("maxDistances"));
    assertNotNull(ctx.getVariable("maxPredecessors"));
    assertNotNull(ctx.getVariable("maxUnSettled"));
    tx.commit();
  }

  /** Destination unreachable → {@code getPath()} returns {@code null} (no predecessors entry). */
  @Test
  public void executeReturnsNullWhenDestinationUnreachable() {
    var tx = session.begin();
    reload();
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    var finder = new FixedPathFinder();
    finder.initEndpoints(v1, vIsolated);

    var path = finder.runExecute(ctx);

    assertNull("no predecessor for isolated vertex → null path", path);
    tx.commit();
  }

  @Test
  public void getMinimumOverEmptySetReturnsNull() {
    var finder = new FixedPathFinder();
    finder.initEndpoints(v1, v4); // distance map is null; but empty set bypasses it.
    assertNull(finder.exposedMin(new HashSet<>()));
  }

  @Test
  public void sumDistancesIsSimpleAddition() {
    var finder = new FixedPathFinder();
    assertEquals(5.0f, finder.exposedSum(2.0f, 3.0f), 0.0f);
    assertEquals(0.0f, finder.exposedSum(0.0f, 0.0f), 0.0f);
  }

  @Test
  public void defaultIsVariableEdgeWeightIsFalse() {
    var finder = new FixedPathFinder();
    assertEquals(Boolean.FALSE, Boolean.valueOf(finder.exposedVariableEdgeWeight()));
  }

  @Test
  public void defaultAggregateResultsIsFalse() {
    var finder = new FixedPathFinder();
    assertEquals(Boolean.FALSE, Boolean.valueOf(finder.exposedAggregateResults()));
  }

  /**
   * {@code getResult()} delegates to {@code getPath()} — pin the contract so future changes to
   * getPath auto-propagate to getResult. Run immediately after execute() to reuse the
   * predecessors map (execute() nulls only the distance map, not predecessors).
   */
  @Test
  public void getResultDelegatesToGetPath() {
    var tx = session.begin();
    reload();
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    var finder = new FixedPathFinder();
    finder.initEndpoints(v1, v4);
    finder.runExecute(ctx);

    var p1 = finder.exposedGetPath();
    var p2 = finder.exposedGetResult();
    assertEquals(p1, p2);
    tx.commit();
  }

  /**
   * Single-call path: Direction.IN means v1 has no incoming neighbors in this graph, so execute
   * returns immediately with no predecessors set → getPath returns null.
   */
  @Test
  public void executeWithInDirectionFromLeafSourceYieldsNullPath() {
    var tx = session.begin();
    reload();
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);

    var finder = new FixedPathFinder();
    finder.initEndpoints(v1, v4, Direction.IN);
    var path = finder.runExecute(ctx);

    assertNull("v1 has no in-edges, so v4 is unreachable via IN → null", path);
    tx.commit();
  }
}
