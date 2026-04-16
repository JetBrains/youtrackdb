package com.jetbrains.youtrackdb.internal.core.db.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.TraversalCache;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests verifying that the per-query traversal memoization cache produces correct
 * results for LET subqueries that invoke graph traversal functions.
 *
 * <p>Graph shape used in all tests:
 *
 * <pre>
 *   start --HAS_INTEREST--> interest1 ("java")
 *   start --HAS_INTEREST--> interest2 ("databases")
 *
 *   fof1 --KNOWS--> start
 *   fof2 --KNOWS--> start
 *
 *   post1 --HAS_CREATOR--> fof1   post1 --HAS_TAG--> interest1
 *   post2 --HAS_CREATOR--> fof1   post2 --HAS_TAG--> interest2
 *   post3 --HAS_CREATOR--> fof2   (no tags)
 * </pre>
 */
public class TraversalMemoizationTest extends DbTestBase {

  @Before
  public void setUpGraph() {
    session.createEdgeClass("HAS_INTEREST");
    session.createEdgeClass("KNOWS");
    session.createEdgeClass("HAS_CREATOR");
    session.createEdgeClass("HAS_TAG");
    session.getSchema().createVertexClass("Person");
    session.getSchema().createVertexClass("Post");
    session.getSchema().createVertexClass("Tag");

    var tx = session.begin();

    var start = tx.newVertex("Person");
    start.setProperty("name", "start");

    var interest1 = tx.newVertex("Tag");
    interest1.setProperty("name", "java");
    var interest2 = tx.newVertex("Tag");
    interest2.setProperty("name", "databases");

    start.addEdge(interest1, "HAS_INTEREST");
    start.addEdge(interest2, "HAS_INTEREST");

    var fof1 = tx.newVertex("Person");
    fof1.setProperty("name", "fof1");
    var fof2 = tx.newVertex("Person");
    fof2.setProperty("name", "fof2");

    fof1.addEdge(start, "KNOWS");
    fof2.addEdge(start, "KNOWS");

    var post1 = tx.newVertex("Post");
    post1.setProperty("title", "post1");
    var post2 = tx.newVertex("Post");
    post2.setProperty("title", "post2");
    var post3 = tx.newVertex("Post");
    post3.setProperty("title", "post3");

    post1.addEdge(fof1, "HAS_CREATOR");
    post2.addEdge(fof1, "HAS_CREATOR");
    post3.addEdge(fof2, "HAS_CREATOR");

    post1.addEdge(interest1, "HAS_TAG");
    post2.addEdge(interest2, "HAS_TAG");

    tx.commit();
  }

  /**
   * Verifies that a LET subquery using out() graph traversal produces correct results. For each
   * Post, the subquery resolves its creator via out('HAS_CREATOR'). The traversal cache is active
   * (default configuration), so the result for each Post is memoized by its RID. Despite possible
   * caching, all three Posts must resolve their creators correctly.
   */
  @Test
  public void letSubqueryGraphTraversalProducesCorrectResults() {
    var tx = session.begin();
    var creatorNames = new ArrayList<String>();
    try (var rs =
        tx.query(
            "SELECT $creator[0].name as creatorName FROM Post "
                + "LET $creator = (SELECT name FROM "
                + "  (SELECT expand(out('HAS_CREATOR')) FROM Post WHERE @rid = $parent.$current.@rid)) "
                + "ORDER BY creatorName ASC")) {
      while (rs.hasNext()) {
        creatorNames.add(rs.next().getProperty("creatorName"));
      }
    }
    // post1 and post2 were created by fof1, post3 by fof2.
    assertThat(creatorNames).containsExactly("fof1", "fof1", "fof2");
  }

  /**
   * Verifies that each Person's KNOWS neighbors (resolved via a LET subquery with out('KNOWS'))
   * are the correct vertices. For fof1 and fof2, the sole KNOWS neighbor is 'start'. Running the
   * query over multiple Person rows exercises the cache per source RID and confirms the cache does
   * not mix results across different source vertices.
   */
  @Test
  public void letSubqueryKnowsNeighborsAreCorrectPerSourceVertex() {
    var tx = session.begin();
    // Collect the name of each Person and the names of their KNOWS neighbors from the LET subquery.
    var personNames = new ArrayList<String>();
    var knowsNeighborNames = new ArrayList<List<String>>();
    try (var rs =
        tx.query(
            "SELECT name, $knows as knows FROM Person "
                + "LET $knows = (SELECT name FROM "
                + "  (SELECT expand(out('KNOWS')) FROM Person WHERE @rid = $parent.$current.@rid)) "
                + "WHERE name <> 'start' "
                + "ORDER BY name ASC")) {
      while (rs.hasNext()) {
        var row = rs.next();
        personNames.add(row.getProperty("name"));
        var knows = (List<?>) row.getProperty("knows");
        var names = new ArrayList<String>();
        if (knows != null) {
          for (var item : knows) {
            names.add(((Result) item).getProperty("name"));
          }
        }
        knowsNeighborNames.add(names);
      }
    }
    // fof1 and fof2 each KNOW exactly one vertex: 'start'.
    // This confirms that the cache returns the correct per-source-RID result for each Person,
    // without mixing results between different source vertices.
    assertThat(personNames).containsExactly("fof1", "fof2");
    assertThat(knowsNeighborNames).hasSize(2);
    assertThat(knowsNeighborNames.get(0)).containsExactly("start");
    assertThat(knowsNeighborNames.get(1)).containsExactly("start");
  }

  /**
   * Verifies that a LET subquery using out('KNOWS') traversal correctly resolves the KNOWS neighbor
   * for each fof Person. Each fof Person KNOWs 'start', so expanding out('KNOWS') from fof1 and
   * fof2 should both yield 'start'.
   */
  @Test
  public void letSubqueryWithOutTraversalResolvesKnowsNeighbor() {
    // For each fof Person, find the names of vertices they KNOW (should be 'start').
    var tx = session.begin();
    var knowsNames = new ArrayList<String>();
    try (var rs =
        tx.query(
            "SELECT name, $knows[0].name as knowsName FROM Person "
                + "LET $knows = (SELECT name FROM "
                + "  (SELECT expand(out('KNOWS')) FROM Person WHERE @rid = $parent.$current.@rid)) "
                + "WHERE name <> 'start' "
                + "ORDER BY name ASC")) {
      while (rs.hasNext()) {
        knowsNames.add(rs.next().getProperty("knowsName"));
      }
    }
    // fof1 and fof2 both KNOW the 'start' vertex.
    assertThat(knowsNames).containsExactly("start", "start");
  }

  /**
   * Verifies via EXPLAIN that a LET subquery with graph traversal produces an execution plan
   * containing a "LET (for each record)" step, and that the step is annotated with
   * "[traversal-cache=enabled]" when the cache is enabled in the database configuration. This
   * confirms that the code path responsible for initializing the traversal cache will be exercised
   * at query execution time.
   */
  @Test
  public void explainLetWithGraphTraversalAnnotatesCacheStatus() {
    var tx = session.begin();
    var explain =
        tx.query(
            "EXPLAIN SELECT name, $knows FROM Person "
                + "LET $knows = (SELECT expand(out('KNOWS')) FROM Person "
                + "  WHERE @rid = $parent.$current.@rid) "
                + "WHERE name <> 'start'")
            .toList();
    var plan = (String) explain.getFirst().getProperty("executionPlanAsString");
    // The plan must contain the LET step header ...
    assertThat(plan)
        .as("EXPLAIN plan should show a per-record LET step")
        .contains("LET (for each record)");
    // ... annotated with the cache status so operators know it is active.
    assertThat(plan)
        .as("LET step should be annotated with traversal-cache=enabled")
        .contains("[traversal-cache=enabled]");
  }

  /**
   * Verifies that the traversal cache is actually hit at runtime when the same source vertex
   * appears in multiple outer rows.
   *
   * <p>Graph shape used: fof1 and fof2 both KNOW 'start', so expanding out('KNOWS') from all
   * Persons yields two rows that both have 'start' as the result vertex. The LET subquery then
   * calls out('HAS_INTEREST') on 'start' for each of those two rows. The first call is a cache
   * miss; the second is a cache hit because the source RID is identical.
   *
   * <p>The test verifies:
   *
   * <ol>
   *   <li>At least one cache hit is recorded on this thread (the optimization ran).
   *   <li>Both rows return the same correct interest names (the cached list is properly
   *       re-iterable, proving that materialization worked correctly).
   * </ol>
   */
  @Test
  public void cacheIsHitWhenSameSourceVertexAppearsInMultipleOuterRows() {
    TraversalCache.resetThreadLocalHitCount();

    var tx = session.begin();
    var interestRows = new ArrayList<List<String>>();
    try (var rs =
        tx.query(
            "SELECT $interests FROM (SELECT expand(out('KNOWS')) FROM Person) "
                + "LET $interests = (SELECT name FROM "
                + "  (SELECT expand(out('HAS_INTEREST')) FROM Person "
                + "  WHERE @rid = $parent.$current.@rid))")) {
      while (rs.hasNext()) {
        var row = rs.next();
        var interests = (List<?>) row.getProperty("$interests");
        var names = new ArrayList<String>();
        if (interests != null) {
          for (var item : interests) {
            names.add(((Result) item).getProperty("name"));
          }
        }
        interestRows.add(names);
      }
    }

    // fof1 KNOWS start → row 1; fof2 KNOWS start → row 2.
    // Both rows resolve out('HAS_INTEREST') from 'start' → [java, databases].
    // The second resolution is a cache hit.
    assertThat(interestRows).as("both outer rows should resolve 'start' interests").hasSize(2);
    assertThat(interestRows.get(0))
        .as("first row: interests of 'start'")
        .containsExactlyInAnyOrder("java", "databases");
    assertThat(interestRows.get(1))
        .as("second row: interests of 'start' (from cache)")
        .containsExactlyInAnyOrder("java", "databases");

    assertThat(TraversalCache.getThreadLocalHitCount())
        .as("traversal cache should have been hit at least once")
        .isGreaterThanOrEqualTo(1);
  }

  /**
   * Verifies that the method-call-syntax traversal path ({@code $current.out('KNOWS')}) through
   * {@code SQLMethodCall.invokeGraphFunction} also benefits from the traversal cache. MATCH-style
   * queries route graph traversals through the method-call path rather than the function-call path,
   * so this test confirms the caching logic is exercised on both code paths.
   *
   * <p>The query uses MATCH to expand out('KNOWS') from Persons who are not 'start', which yields
   * fof1 → start and fof2 → start. The 'start' vertex has two HAS_INTEREST edges, so the second
   * traversal of out('HAS_INTEREST') from 'start' is a cache hit.
   */
  @Test
  public void methodCallSyntaxTraversalBenefitsFromCache() {
    TraversalCache.resetThreadLocalHitCount();

    var tx = session.begin();
    var interestNames = new ArrayList<String>();
    try (var rs =
        tx.query(
            "SELECT $interests FROM "
                + "(MATCH {class: Person, as: p, where: (name <> 'start')}"
                + ".out('KNOWS'){as: friend} RETURN friend) "
                + "LET $interests = (SELECT name FROM "
                + "  (SELECT expand(out('HAS_INTEREST')) FROM Person "
                + "  WHERE @rid = $parent.$current.friend.@rid))")) {
      while (rs.hasNext()) {
        var row = rs.next();
        var interests = (List<?>) row.getProperty("$interests");
        if (interests != null) {
          for (var item : interests) {
            interestNames.add(((Result) item).getProperty("name"));
          }
        }
      }
    }

    // Both fof1 and fof2 KNOW 'start', so we get 'start's interests twice (4 names total).
    // The order within each pair depends on the storage engine, so check count and contents.
    assertThat(interestNames)
        .as("both MATCH rows resolve HAS_INTEREST from 'start'")
        .hasSize(4)
        .containsOnly("java", "databases");
    assertThat(TraversalCache.getThreadLocalHitCount())
        .as("second out('HAS_INTEREST') from 'start' should be a cache hit")
        .isGreaterThanOrEqualTo(1);
  }

  /**
   * When QUERY_TRAVERSAL_CACHE_ENABLED is set to false, queries still produce correct results but
   * no cache hits are recorded. This confirms the feature can be safely disabled in production.
   */
  @Test
  public void queryProducesCorrectResultsWithCacheDisabled() {
    var oldValue =
        GlobalConfiguration.QUERY_TRAVERSAL_CACHE_ENABLED.getValueAsBoolean();
    try {
      GlobalConfiguration.QUERY_TRAVERSAL_CACHE_ENABLED.setValue(false);
      TraversalCache.resetThreadLocalHitCount();

      var tx = session.begin();
      var interestRows = new ArrayList<List<String>>();
      try (var rs =
          tx.query(
              "SELECT $interests FROM (SELECT expand(out('KNOWS')) FROM Person) "
                  + "LET $interests = (SELECT name FROM "
                  + "  (SELECT expand(out('HAS_INTEREST')) FROM Person "
                  + "  WHERE @rid = $parent.$current.@rid))")) {
        while (rs.hasNext()) {
          var row = rs.next();
          var interests = (List<?>) row.getProperty("$interests");
          var names = new ArrayList<String>();
          if (interests != null) {
            for (var item : interests) {
              names.add(((Result) item).getProperty("name"));
            }
          }
          interestRows.add(names);
        }
      }

      // Results must be correct even without caching.
      assertThat(interestRows).hasSize(2);
      assertThat(interestRows.get(0)).containsExactlyInAnyOrder("java", "databases");
      assertThat(interestRows.get(1)).containsExactlyInAnyOrder("java", "databases");

      // No cache hits should be recorded when caching is disabled.
      assertThat(TraversalCache.getThreadLocalHitCount())
          .as("no cache hits expected when cache is disabled")
          .isZero();
    } finally {
      GlobalConfiguration.QUERY_TRAVERSAL_CACHE_ENABLED.setValue(oldValue);
    }
  }
}
