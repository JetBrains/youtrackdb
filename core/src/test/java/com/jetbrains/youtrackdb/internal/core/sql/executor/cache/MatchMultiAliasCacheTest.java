package com.jetbrains.youtrackdb.internal.core.sql.executor.cache;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransactionImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * End-to-end cache-vs-fresh equivalence for the multi-alias MATCH ({@code MATCH_TUPLE_MULTI}) class-
 * scoped version gate (I4 / I10). A multi-alias MATCH freezes its projected RETURN tuples and replays
 * them verbatim while no pattern read-class has been mutated since populate; a post-populate mutation to
 * any alias-vertex class or traversal-edge class invalidates the entry and forces a fresh re-execution.
 * A mutation to a class outside the pattern leaves the entry serviceable, which is the advantage of the
 * class-scoped gate over a global one.
 *
 * <p><b>The {@code RETURN a.name as a, b.name as b} shape.</b> The RETURN projects only fields, never
 * the bound alias records, so a projected row carries scalars under the {@code a}/{@code b} keys and no
 * record under {@code getProperty("a")}. A per-tuple delta that tried to recover each alias's bound RID
 * from such a row could not reconcile this shape at all; the class-scoped gate sidesteps that by reading
 * each mutation operation's own class (never the cached row) and re-executing when a pattern class is
 * touched. These tests pin that the common field-projection shape stays correct across every mutation
 * kind.
 *
 * <p>Each equivalence scenario runs twice through the live {@code query()} path — once cache-off (the
 * source of truth) and once cache-on (populate, mutate, then a second query served from the gate) — and
 * the two tuple sets are compared. Rows are sorted before comparison: the gate preserves the frozen set
 * and re-executes on invalidation, so set equality (not row order) is the property under test, and the
 * two halves re-seed independently so storage scan order is not stable across them.
 */
@Category(SequentialTest.class)
public class MatchMultiAliasCacheTest extends DbTestBase {

  private static final String VERTEX = "MmPerson";
  private static final String EDGE = "MmKnows";
  private static final String OTHER = "MmOther";
  private static final String NAME = "name";

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

  /** Clears every committed record between scenario halves, with the cache forced off. */
  private void clearAll() {
    var previous = GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.getValueAsBoolean();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    try {
      session.begin();
      session.command("DELETE VERTEX " + VERTEX);
      session.command("DELETE FROM " + OTHER);
      session.commit();
    } finally {
      GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(previous);
    }
  }

  /** Commits the fixed seed graph: alice -> bob -> carol over {@code MmKnows} edges. */
  private void seedGraph() {
    session.begin();
    for (var name : new String[] {"alice", "bob", "carol"}) {
      session.command("CREATE VERTEX " + VERTEX + " SET " + NAME + " = '" + name + "'");
    }
    linkEdge("alice", "bob");
    linkEdge("bob", "carol");
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

  /**
   * Re-seeds the graph, opens a tx, populates the cache with one MATCH query (or runs it plain with the
   * cache off), applies {@code mutation} after populate, then captures the second query's tuple set.
   */
  private List<List<String>> runScenario(
      boolean cacheEnabled, Consumer<FrontendTransactionImpl> mutation) {
    clearAll();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seedGraph();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(cacheEnabled);

    session.begin();
    snapshot(session.query(matchSql())); // populate (cache on) or plain execution (cache off)
    mutation.accept(tx());
    var result = snapshot(session.query(matchSql())); // gate-served view (on) or fresh (off)
    session.rollback();
    return result;
  }

  private void assertEquivalent(Consumer<FrontendTransactionImpl> mutation) {
    var fresh = runScenario(false, mutation);
    var cached = runScenario(true, mutation);
    assertEquals(
        "Cached multi-alias MATCH must equal a fresh uncached execution at the same moment",
        fresh, cached);
  }

  // Mutations run as DML inside the already-open scenario tx, so they flow through the mutation log and
  // are version-stamped after the entry's populate version.

  private Consumer<FrontendTransactionImpl> renameVertex(String from, String to) {
    return t -> session.command(
        "UPDATE " + VERTEX + " SET " + NAME + " = '" + to + "' WHERE " + NAME + " = '" + from
            + "'");
  }

  private Consumer<FrontendTransactionImpl> deleteVertex(String name) {
    return t -> session.command(
        "DELETE VERTEX " + VERTEX + " WHERE " + NAME + " = '" + name + "'");
  }

  /** Adds a new matching vertex linked from an existing one, so it forms a new tuple. */
  private Consumer<FrontendTransactionImpl> createVertexLinkedFrom(String from, String newName) {
    return t -> {
      session.command("CREATE VERTEX " + VERTEX + " SET " + NAME + " = '" + newName + "'");
      linkEdge(from, newName);
    };
  }

  /** Adds an edge between two existing vertices, forming a new tuple via the edge class. */
  private Consumer<FrontendTransactionImpl> createEdge(String from, String to) {
    return t -> session.command(
        "CREATE EDGE " + EDGE
            + " FROM (SELECT FROM " + VERTEX + " WHERE " + NAME + " = '" + from + "')"
            + " TO (SELECT FROM " + VERTEX + " WHERE " + NAME + " = '" + to + "')");
  }

  private Consumer<FrontendTransactionImpl> createUnrelated(String name) {
    return t -> session.command("INSERT INTO " + OTHER + " SET " + NAME + " = '" + name + "'");
  }

  // ===========================================================================
  // The field-projection shape — replay and re-execution equivalence
  // ===========================================================================

  /**
   * No post-populate mutation: the cached view replays the frozen projected tuples verbatim and equals a
   * fresh execution. This is the read-mostly hit and the field-projection shape ({@code a.name as a,
   * b.name as b}) the per-tuple model could not reconcile.
   */
  @Test
  public void noMutation_replaysFrozenTuples() {
    assertEquivalent(t -> {
    });
  }

  /**
   * A value UPDATE on a bound vertex (rename carol -> zeta) changes a projected {@code b} column. The
   * gate invalidates the entry (the vertex class is a pattern read-class) and re-executes, so the cached
   * tuple set reflects the new name rather than a stale frozen value.
   */
  @Test
  public void patternVertexUpdate_invalidatesAndMatchesFresh() {
    assertEquivalent(renameVertex("carol", "zeta"));
  }

  /** A DELETE of a bound vertex drops its tuple; the gate invalidates and re-executes to match fresh. */
  @Test
  public void patternVertexDelete_invalidatesAndMatchesFresh() {
    assertEquivalent(deleteVertex("carol"));
  }

  /** A new linked vertex adds a tuple; the gate invalidates on the create and re-executes. */
  @Test
  public void patternVertexCreate_invalidatesAndMatchesFresh() {
    assertEquivalent(createVertexLinkedFrom("carol", "dave"));
  }

  /**
   * A new edge between two already-cached vertices adds a tuple. This is the edge-class folding case: the
   * edge class must be in the entry's read-class closure, or the gate would miss the edge create and
   * replay a tuple set short one row.
   */
  @Test
  public void edgeCreate_invalidatesAndMatchesFresh() {
    assertEquivalent(createEdge("alice", "carol"));
  }

  // ===========================================================================
  // Class-scoped survival — the advantage over a global version gate
  // ===========================================================================

  /**
   * A mutation to a class outside the pattern leaves the entry serviceable: the cached replay still
   * equals a fresh execution (both unchanged), and a global gate would have wiped the entry here.
   */
  @Test
  public void unrelatedClassMutation_matchesFresh() {
    assertEquivalent(createUnrelated("ignored"));
  }

  /**
   * Proves the survival is a true cache hit, not a re-execution: after an unrelated-class mutation the
   * second query increments the hit counter and triggers no invalidation, while after a pattern-class
   * mutation it triggers an invalidation. Reads the cache metrics directly within one transaction.
   */
  @Test
  public void unrelatedMutationServesFromCache_patternMutationInvalidates() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seedGraph();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);

    // Unrelated-class mutation: served from cache, no invalidation.
    session.begin();
    try {
      snapshot(session.query(matchSql())); // populate
      var metrics = tx().getQueryResultCache().getMetrics();
      var hitsBefore = metrics.getHits();
      var invalidationsBefore = metrics.getK0Invalidations();
      createUnrelated("ignored").accept(tx());
      snapshot(session.query(matchSql())); // second query
      assertEquals("an unrelated-class mutation must not invalidate the entry",
          invalidationsBefore, tx().getQueryResultCache().getMetrics().getK0Invalidations());
      assertTrue("the second query must be served as a cache hit",
          tx().getQueryResultCache().getMetrics().getHits() > hitsBefore);
    } finally {
      session.rollback();
    }

    // Pattern-class mutation: invalidated.
    session.begin();
    try {
      snapshot(session.query(matchSql())); // re-populate
      var invalidationsBefore = tx().getQueryResultCache().getMetrics().getK0Invalidations();
      renameVertex("carol", "zeta").accept(tx());
      snapshot(session.query(matchSql())); // second query triggers the gate
      assertTrue("a pattern-class mutation must invalidate the entry",
          tx().getQueryResultCache().getMetrics().getK0Invalidations() > invalidationsBefore);
    } finally {
      session.rollback();
    }
  }

  // ===========================================================================
  // Entry metadata — vertex and edge classes fold into the closure
  // ===========================================================================

  /**
   * The multi-alias entry must be {@code MATCH_TUPLE_MULTI}, carry no Etap-A returnProjector, and fold
   * both the alias-vertex class and the traversal-edge class into its read-class closure: an edge create
   * or delete only trips the gate because the edge class is in the closure.
   */
  @Test
  public void multiAliasEntryFoldsVertexAndEdgeClasses() {
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(false);
    seedGraph();
    GlobalConfiguration.QUERY_TX_RESULT_CACHE_ENABLED.setValue(true);

    session.begin();
    try {
      snapshot(session.query(matchSql())); // populate one entry
      var cache = tx().getQueryResultCache();
      var entry = onlyEntry(cache);
      assertEquals("a multi-alias MATCH classifies as MATCH_TUPLE_MULTI",
          CacheableShape.MATCH_TUPLE_MULTI, entry.getShape());
      assertTrue("a multi-alias MATCH carries no Etap-A returnProjector",
          entry.getReturnProjector() == null);
      assertFalse("the entry's read-class closure must be non-empty",
          entry.getEffectiveFromClasses().isEmpty());
      assertTrue("the alias-vertex class must be in the closure",
          entry.getEffectiveFromClasses().contains(VERTEX));
      assertTrue("the traversal-edge class must be folded into the closure",
          entry.getEffectiveFromClasses().contains(EDGE));
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
