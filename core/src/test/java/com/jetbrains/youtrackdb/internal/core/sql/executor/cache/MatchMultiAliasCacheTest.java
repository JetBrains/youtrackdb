package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end cache-vs-fresh equivalence for the multi-alias MATCH ({@code MATCH_TUPLE_MULTI})
 * class-scoped version gate (I4 / I10). A multi-alias MATCH freezes its projected RETURN tuples and
 * replays them verbatim while no pattern read-class has been mutated since populate; a post-populate
 * mutation to any alias-vertex class or traversal-edge class invalidates the entry and forces a fresh
 * re-execution. A mutation to a class outside the pattern leaves the entry serviceable, the advantage
 * of the class-scoped gate over a global one.
 *
 * <p><b>The {@code RETURN a.name as a, b.name as b} shape.</b> The RETURN projects only fields, never
 * the bound alias records, so a projected row carries scalars under the {@code a}/{@code b} keys and no
 * record under {@code getProperty("a")}. A per-tuple delta that tried to recover each alias's bound RID
 * from such a row could not reconcile this shape at all; the class-scoped gate sidesteps that by reading
 * each mutation operation's own class (never the cached row) and re-executing when a pattern class is
 * touched. These tests pin that the common field-projection shape stays correct across every mutation
 * kind.
 *
 * <p>Each equivalence scenario runs twice through the live {@code query()} path: once cache-off (the
 * source of truth) and once cache-on (populate, mutate, then a second query served from the gate). The
 * two tuple sets are compared, and the cache-on run additionally asserts the expected number of gate
 * invalidations (so a test cannot pass by silently re-executing every time, and a non-invalidating
 * mutation is proven to serve a true cache hit). Rows are sorted before comparison, because the gate
 * preserves the frozen set and re-executes on invalidation, so set equality (not row order) is the
 * property under test, and the two halves re-seed independently so storage scan order is not stable.
 */
@Category(SequentialTest.class)
public class MatchMultiAliasCacheTest extends DbTestBase {

  private static final String VERTEX = "MmPerson";
  private static final String EDGE = "MmKnows";
  private static final String OTHER = "MmOther";
  private static final String NAME = "name";

  // The fixed seed graph: ALICE -> BOB -> CAROL over MmKnows edges.
  private static final String ALICE = "alice";
  private static final String BOB = "bob";
  private static final String CAROL = "carol";

  private boolean previousEnabled;

  @Before
  public void setUpSchema() {
    previousEnabled = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    session.execute("CREATE CLASS " + VERTEX + " EXTENDS V").close();
    session.execute("CREATE CLASS " + EDGE + " EXTENDS E").close();
    session.execute("CREATE CLASS " + OTHER).close();
  }

  @After
  public void restoreFlag() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previousEnabled);
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private FrontendTransactionImpl tx() {
    return (FrontendTransactionImpl) session.getTransactionInternal();
  }

  /**
   * A two-alias MATCH joined by a named traversal edge, returning only projected fields (never the bound
   * alias records). This is the field-projection shape the per-tuple population model could not handle.
   */
  private static String matchSql() {
    return "match {as:a, class:" + VERTEX + "}.out('" + EDGE + "'){as:b, class:" + VERTEX + "}"
        + " return a." + NAME + " as a, b." + NAME + " as b";
  }

  /** The same join over the anonymous {@code .out()} traversal (parser folds the edge label to E). */
  private static String matchSqlBareOut() {
    return "match {as:a, class:" + VERTEX + "}.out(){as:b, class:" + VERTEX + "}"
        + " return a." + NAME + " as a, b." + NAME + " as b";
  }

  /** A two-expression cross-join MATCH with no traversal edge (closure is alias-classes only). */
  private static String matchSqlCrossJoin() {
    return "match {as:a, class:" + VERTEX + "}, {as:b, class:" + VERTEX + "}"
        + " return a." + NAME + " as a, b." + NAME + " as b";
  }

  /** Clears every committed record between scenario halves, with the cache forced off. */
  private void clearAll() {
    var previous = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    try {
      session.begin();
      session.command("DELETE VERTEX " + VERTEX); // polymorphic: also removes subclass vertices
      session.command("DELETE FROM " + OTHER);
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previous);
    }
  }

  /** Commits the fixed seed graph: alice -> bob -> carol over {@code MmKnows} edges. */
  private void seedGraph() {
    session.begin();
    for (var name : new String[] {ALICE, BOB, CAROL}) {
      session.command("CREATE VERTEX " + VERTEX + " SET " + NAME + " = '" + name + "'");
    }
    linkEdge(ALICE, BOB);
    linkEdge(BOB, CAROL);
    session.commit();
  }

  /** Creates one {@code MmKnows} edge between the two named, already-existing vertices. */
  private void linkEdge(String from, String to) {
    session.command(
        "CREATE EDGE " + EDGE
            + " FROM (SELECT FROM " + VERTEX + " WHERE " + NAME + " = '" + from + "')"
            + " TO (SELECT FROM " + VERTEX + " WHERE " + NAME + " = '" + to + "')");
  }

  /** Snapshots a MATCH result into a sorted set of {@code [a, b]} tuples for set-equivalence. */
  private static List<List<String>> snapshot(ResultSet rs) {
    var rows = new ArrayList<List<String>>();
    try (rs) {
      while (rs.hasNext()) {
        Result row = rs.next();
        // Bind to Object before String.valueOf: getProperty is generic, and a direct
        // String.valueOf(getProperty(...)) lets overload resolution infer char[] and insert a bad cast.
        Object a = row.getProperty("a");
        Object b = row.getProperty("b");
        rows.add(List.of(String.valueOf(a), String.valueOf(b)));
      }
    }
    rows.sort(MatchMultiAliasCacheTest::compareRows);
    return rows;
  }

  private static int compareRows(List<String> x, List<String> y) {
    var byA = x.get(0).compareTo(y.get(0));
    return byA != 0 ? byA : x.get(1).compareTo(y.get(1));
  }

  /** The captured outcome of one scenario half: the tuple set plus the cache-on metric deltas. */
  private record ScenarioResult(List<List<String>> rows, long invalidationDelta, long hitDelta) {

  }

  /**
   * Re-seeds via {@code seed}, opens a tx, populates the cache with one {@code sql} query (or runs it
   * plain with the cache off), applies {@code mutation} after populate, then captures the second query's
   * tuple set. When the cache is on it also records how many gate invalidations and hits the second
   * query produced, so the caller can prove the gate fired (or did not) rather than only that the rows
   * matched.
   */
  private ScenarioResult runScenario(
      boolean cacheEnabled, Runnable seed, Runnable mutation, String sql) {
    clearAll();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seed.run();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);

    session.begin();
    snapshot(session.query(sql)); // populate (cache on) or plain execution (cache off)
    var cache = cacheEnabled ? tx().getQueryResultCache() : null;
    long invalidationsBefore = cache == null ? 0 : cache.getMetrics().getK0Invalidations();
    long hitsBefore = cache == null ? 0 : cache.getMetrics().getHits();
    mutation.run();
    var rows = snapshot(session.query(sql)); // gate-served view (on) or fresh (off)
    long invalidationDelta =
        cache == null ? 0 : cache.getMetrics().getK0Invalidations() - invalidationsBefore;
    long hitDelta = cache == null ? 0 : cache.getMetrics().getHits() - hitsBefore;
    session.rollback();
    return new ScenarioResult(rows, invalidationDelta, hitDelta);
  }

  /** Equivalence over the default seed graph and the named-edge MATCH. */
  private void assertEquivalent(Runnable mutation, int expectedInvalidations) {
    assertEquivalent(this::seedGraph, mutation, expectedInvalidations, matchSql());
  }

  /** Equivalence over the default seed graph and an explicit MATCH SQL. */
  private void assertEquivalent(Runnable mutation, int expectedInvalidations, String sql) {
    assertEquivalent(this::seedGraph, mutation, expectedInvalidations, sql);
  }

  /**
   * Drives the cache-off and cache-on halves and asserts both the tuple-set equivalence and the
   * cache-on invalidation count. A zero-invalidation expectation additionally asserts the second query
   * was a true cache hit, so the test distinguishes "served from the frozen entry" from "silently
   * re-executed".
   */
  private void assertEquivalent(
      Runnable seed, Runnable mutation, int expectedInvalidations, String sql) {
    var fresh = runScenario(false, seed, mutation, sql);
    var cached = runScenario(true, seed, mutation, sql);
    assertEquals(
        "Cached multi-alias MATCH must equal a fresh uncached execution at the same moment",
        fresh.rows(), cached.rows());
    assertEquals(
        "the gate must invalidate exactly when a pattern class is mutated",
        expectedInvalidations, cached.invalidationDelta());
    if (expectedInvalidations == 0) {
      assertTrue(
          "a non-invalidating mutation must serve the second query from the cached entry",
          cached.hitDelta() > 0);
    }
  }

  // Mutations run as DML inside the already-open scenario tx, so they flow through the mutation log and
  // are version-stamped after the entry's populate version. They issue DML through the session and take
  // no transaction argument.

  private Runnable renameVertex(String from, String to) {
    return () -> session.command(
        "UPDATE " + VERTEX + " SET " + NAME + " = '" + to + "' WHERE " + NAME + " = '" + from
            + "'");
  }

  private Runnable deleteVertex(String name) {
    return () -> session
        .command("DELETE VERTEX " + VERTEX + " WHERE " + NAME + " = '" + name + "'");
  }

  /** Adds a new matching vertex linked from an existing one, so it forms a new tuple. */
  private Runnable createVertexLinkedFrom(String from, String newName) {
    return () -> {
      session.command("CREATE VERTEX " + VERTEX + " SET " + NAME + " = '" + newName + "'");
      linkEdge(from, newName);
    };
  }

  /** Adds an edge between two existing vertices, forming a new tuple via the edge class. */
  private Runnable createEdge(String from, String to) {
    return () -> linkEdge(from, to);
  }

  /** Deletes the edge between two existing vertices, dropping the tuple it formed. */
  private Runnable deleteEdge(String from, String to) {
    return () -> session.command(
        "DELETE EDGE " + EDGE
            + " FROM (SELECT FROM " + VERTEX + " WHERE " + NAME + " = '" + from + "')"
            + " TO (SELECT FROM " + VERTEX + " WHERE " + NAME + " = '" + to + "')");
  }

  private Runnable createUnrelated(String name) {
    return () -> session.command("INSERT INTO " + OTHER + " SET " + NAME + " = '" + name + "'");
  }

  // ===========================================================================
  // The field-projection shape — replay and re-execution equivalence
  // ===========================================================================

  /**
   * No post-populate mutation: the cached view replays the frozen projected tuples verbatim, serves the
   * second query as a hit (asserted via the zero-invalidation hit check), and equals a fresh execution.
   * This is the read-mostly hit and the field-projection shape ({@code a.name as a, b.name as b}) the
   * per-tuple model could not reconcile.
   */
  @Test
  public void noMutation_replaysFrozenTuples() {
    assertEquivalent(() -> {
    }, 0);
  }

  /**
   * A value UPDATE on a bound vertex (rename carol -> zeta) changes a projected {@code b} column. The
   * gate invalidates the entry exactly once (the vertex class is a pattern read-class) and re-executes,
   * so the cached tuple set reflects the new name rather than a stale frozen value.
   */
  @Test
  public void patternVertexUpdate_invalidatesAndMatchesFresh() {
    assertEquivalent(renameVertex(CAROL, "zeta"), 1);
  }

  /** A DELETE of a bound vertex drops its tuple; the gate invalidates once and re-executes. */
  @Test
  public void patternVertexDelete_invalidatesAndMatchesFresh() {
    assertEquivalent(deleteVertex(CAROL), 1);
  }

  /** A new linked vertex adds a tuple; the gate invalidates once on the create and re-executes. */
  @Test
  public void patternVertexCreate_invalidatesAndMatchesFresh() {
    assertEquivalent(createVertexLinkedFrom(CAROL, "dave"), 1);
  }

  /**
   * A new edge between two already-cached vertices adds a tuple. The edge-class folding is pinned
   * directly by {@link #multiAliasEntryFoldsVertexAndEdgeClasses}; here the end-to-end equivalence and
   * the single invalidation confirm the create is reconciled (the edge create also stamps its endpoint
   * vertices, so this alone does not isolate edge-class folding from vertex-class folding).
   */
  @Test
  public void edgeCreate_invalidatesAndMatchesFresh() {
    assertEquivalent(createEdge(ALICE, CAROL), 1);
  }

  /** An edge DELETE drops the tuple it formed; the gate invalidates once and re-executes. */
  @Test
  public void edgeDelete_invalidatesAndMatchesFresh() {
    assertEquivalent(deleteEdge(ALICE, BOB), 1);
  }

  /**
   * The anonymous {@code .out()} traversal folds its edge label to the base class {@code E}, whose
   * subclass closure must contain {@code MmKnows}. A real {@code MmKnows} edge create must therefore
   * still invalidate; if the base-class fold or its subclass expansion broke, the gate would miss it.
   */
  @Test
  public void bareOutTraversal_edgeCreateInvalidatesViaBaseClassClosure() {
    assertEquivalent(createEdge(ALICE, CAROL), 1, matchSqlBareOut());
  }

  /**
   * A two-expression cross-join MATCH (no traversal edge) exercises the multi-expression closure walk
   * with an alias-classes-only closure. A vertex-class mutation must invalidate the cross product.
   */
  @Test
  public void crossJoinMatch_vertexUpdateInvalidatesAndMatchesFresh() {
    assertEquivalent(renameVertex(CAROL, "zeta"), 1, matchSqlCrossJoin());
  }

  // ===========================================================================
  // Subclass-closure invalidation — the getAllSubclasses() expansion
  // ===========================================================================

  /**
   * A mutation on a subclass-of-a-pattern-class record must invalidate the entry. The pattern binds
   * {@code class:MmPerson}, which matches subclass instances, so the read-class closure folds in every
   * subclass via {@code getAllSubclasses()}; a mutation whose record's concrete class is the subclass
   * must be found by the gate's {@code effectiveFromClasses.contains(className)} probe. Without the
   * subclass-closure expansion the gate would silently replay a stale tuple set after the subclass
   * mutation. The closure-expansion loop is exercised by no other test (every other seed uses direct
   * {@code MmPerson} / {@code MmKnows} records, so {@code getAllSubclasses()} returns empty there).
   */
  @Test
  public void subclassMutation_invalidatesViaClosureExpansion() {
    var subclass = "MmEmployee";
    session.execute("CREATE CLASS " + subclass + " EXTENDS " + VERTEX).close();
    // Seed alice -> bob (MmPerson) and bob -> eve, where eve is an MmEmployee bound to alias b
    // (class:MmPerson matches it polymorphically).
    Runnable seedWithSubclass = () -> {
      session.begin();
      session.command("CREATE VERTEX " + VERTEX + " SET " + NAME + " = '" + ALICE + "'");
      session.command("CREATE VERTEX " + VERTEX + " SET " + NAME + " = '" + BOB + "'");
      session.command("CREATE VERTEX " + subclass + " SET " + NAME + " = 'eve'");
      linkEdge(ALICE, BOB);
      linkEdge(BOB, "eve");
      session.commit();
    };
    // Rename the MmEmployee record: a polymorphic UPDATE on MmPerson whose op carries the MmEmployee
    // class. The gate must fire because MmEmployee is in MmPerson's subclass closure.
    assertEquivalent(seedWithSubclass, renameVertex("eve", "eve2"), 1, matchSql());
  }

  // ===========================================================================
  // Class-scoped survival — the advantage over a global version gate
  // ===========================================================================

  /**
   * A mutation to a class outside the pattern leaves the entry serviceable: the cached replay equals a
   * fresh execution, no invalidation fires, and the second query is a true hit (a global gate would have
   * wiped the entry here).
   */
  @Test
  public void unrelatedClassMutation_servesFromCacheAndMatchesFresh() {
    assertEquivalent(createUnrelated("ignored"), 0);
  }

  // ===========================================================================
  // Strike threshold — repeated invalidation routes the key non-cacheable
  // ===========================================================================

  /**
   * Repeatedly invalidating one multi-alias MATCH key routes it non-cacheable after the configured
   * strike threshold, bounding repopulate churn. Each cycle mutates a pattern class (touching every
   * {@code MmPerson} via an unused property, so the RETURN result is unchanged but the gate still
   * fires) and re-queries. Once the threshold is reached the key is bypassed, so a further cycle counts
   * no additional invalidation: total invalidations equal the threshold even though more cycles run.
   * This also pins the shared {@code k0Strikes} machinery for the MATCH shape.
   */
  @Test
  public void repeatedPatternMutation_routesKeyNonCacheableAtStrikeThreshold() {
    int threshold =
        GlobalConfiguration.QUERY_TX_RESULT_CACHE_K0_NONE_INVALIDATION_THRESHOLD
            .getValueAsInteger();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seedGraph();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);

    session.begin();
    try {
      snapshot(session.query(matchSql())); // populate
      var metrics = tx().getQueryResultCache().getMetrics();
      long invalidationsBefore = metrics.getK0Invalidations();
      for (var cycle = 0; cycle < threshold + 1; cycle++) {
        // Touch every MmPerson with an unused property: a pattern-class mutation that does not change
        // the projected RETURN tuples, so the gate fires on class membership alone.
        session.command("UPDATE " + VERTEX + " SET touched = " + cycle);
        snapshot(session.query(matchSql()));
      }
      assertEquals(
          "invalidations must stop once the key is routed non-cacheable at the strike threshold",
          threshold, metrics.getK0Invalidations() - invalidationsBefore);
    } finally {
      session.rollback();
    }
  }

  // ===========================================================================
  // Entry metadata — vertex and edge classes fold into the closure
  // ===========================================================================

  /**
   * The multi-alias entry must be {@code MATCH_TUPLE_MULTI}, carry no Etap-A returnProjector, and its
   * read-class closure must be exactly the alias-vertex class and the traversal-edge class (no
   * superclasses folded in, no extra classes): an edge create or delete only trips the gate because the
   * edge class is in the closure, and an over-broad closure would erode the class-scoped advantage.
   */
  @Test
  public void multiAliasEntryFoldsVertexAndEdgeClasses() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seedGraph();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);

    session.begin();
    try {
      snapshot(session.query(matchSql())); // populate one entry
      var entry = onlyEntry(tx().getQueryResultCache());
      assertEquals(
          "a multi-alias MATCH classifies as MATCH_TUPLE_MULTI",
          CacheableShape.MATCH_TUPLE_MULTI, entry.getShape());
      assertTrue(
          "a multi-alias MATCH carries no Etap-A returnProjector",
          entry.getReturnProjector() == null);
      assertEquals(
          "the closure is exactly the alias-vertex and traversal-edge classes",
          Set.of(VERTEX, EDGE), entry.getEffectiveFromClasses());
    } finally {
      session.rollback();
    }
  }

  /** Returns the single cache entry, asserting there is exactly one. */
  private static CachedEntry onlyEntry(QueryResultCache cache) {
    var entries = cache.entriesForTest();
    assertEquals("expected exactly one cache entry", 1, entries.size());
    return entries.iterator().next();
  }
}
