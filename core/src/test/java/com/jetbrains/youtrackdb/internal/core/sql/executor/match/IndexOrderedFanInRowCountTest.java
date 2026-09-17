package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass.INDEX_TYPE;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import org.junit.Test;

/**
 * Regression tests for the fan-in row loss of the index-ordered MATCH traversal, recorded as
 * BG1600.
 *
 * <p>A MATCH pattern binds once per (source, target) pair, so a target reachable from two
 * sources contributes two rows. The two UNBOUND execution modes of
 * {@link IndexOrderedEdgeStep} omit the source <em>values</em> because no downstream step reads
 * them, and both used to emit one row per target as well, which silently collapsed the
 * multiplicity. Every test below fixes a fan-in graph where two sources share every target, so
 * the correct row count is exactly twice the number of matching targets.
 */
public class IndexOrderedFanInRowCountTest extends DbTestBase {

  /**
   * Fan-in fixture: {@code srcCount} sources each know all {@code targetCount} targets, plus
   * {@code noiseCount} unconnected records that only inflate the index. The {@code id} property
   * carries the ordered index. Source ids sort after the target ids so a small LIMIT lands
   * entirely inside the target block.
   */
  private void seedFanIn(int srcCount, int targetCount, int noiseCount, INDEX_TYPE indexType) {
    var person = session.createVertexClass("Person");
    person.createProperty("id", PropertyType.STRING);
    person.getProperty("id").createIndex(indexType);
    session.createEdgeClass("knows");

    session.begin();
    for (var i = 0; i < srcCount; i++) {
      session.execute("CREATE VERTEX Person SET id = 'src" + i + "', kind = 'src'").close();
    }
    for (var i = 0; i < targetCount; i++) {
      session.execute(
          "CREATE VERTEX Person SET id = '" + targetId(i) + "', kind = 'tgt'").close();
    }
    for (var i = 0; i < noiseCount; i++) {
      session.execute("CREATE VERTEX Person SET id = 'zz" + i + "', kind = 'noise'").close();
    }
    session.execute(
        "CREATE EDGE knows FROM (SELECT FROM Person WHERE kind = 'src')"
            + " TO (SELECT FROM Person WHERE kind = 'tgt')")
        .close();
    session.commit();
  }

  /** Zero-padded so lexicographic id order equals numeric order. */
  private static String targetId(int i) {
    return "t" + (i < 10 ? "0" : "") + i;
  }

  private List<String> ids(String query) {
    var result = new ArrayList<String>();
    try (var rs = session.query(query)) {
      rs.forEachRemaining(row -> result.add(String.valueOf((Object) row.getProperty("dst.id"))));
    }
    return result;
  }

  private String plan(String query) {
    try (var rs = session.query("EXPLAIN " + query)) {
      return String.valueOf((Object) rs.next().getProperty("executionPlanAsString"));
    }
  }

  /**
   * UNFILTERED_UNBOUND: no source WHERE and no downstream read of the source alias. Two sources
   * share both targets, so the pattern binds four times. Before the fix the index-ordered scan
   * emitted one row per target and returned two rows.
   */
  @Test
  public void unfilteredUnboundKeepsOneRowPerSourceTargetPair() {
    seedFanIn(2, 2, 0, INDEX_TYPE.NOTUNIQUE);
    var query =
        "MATCH {as: src, class: Person}.out('knows'){as: dst, class: Person}"
            + " RETURN dst.id ORDER BY dst.id";
    assertThat(plan(query))
        .as("this shape must reach the UNFILTERED_UNBOUND mode under test")
        .contains("INDEX ORDERED MATCH ASC (UNFILTERED_UNBOUND)");
    assertThat(ids(query))
        .as("two sources reach each of the two targets, so four pattern bindings")
        .containsExactly("t00", "t00", "t01", "t01");
  }

  /**
   * UNFILTERED_UNBOUND under a LIMIT that cuts inside the duplicate block. The cut must keep the
   * first two of the four sorted rows, both of which are the same target. Before the fix the cut
   * ran over already-collapsed rows and returned two different targets.
   */
  @Test
  public void unfilteredUnboundLimitCutsInsideTheDuplicateBlock() {
    seedFanIn(2, 3, 0, INDEX_TYPE.NOTUNIQUE);
    var query =
        "MATCH {as: src, class: Person}.out('knows'){as: dst, class: Person}"
            + " RETURN dst.id ORDER BY dst.id LIMIT 2";
    assertThat(plan(query))
        .as("this shape must reach the UNFILTERED_UNBOUND mode under test")
        .contains("(UNFILTERED_UNBOUND)");
    assertThat(ids(query))
        .as("the first two sorted rows are both the lowest target")
        .containsExactly("t00", "t00");
  }

  /**
   * UNFILTERED_UNBOUND with an unindexed order key as the reference arm. The same graph ordered
   * by a property with no index takes the ordinary MATCH plan, so its row count is the ground
   * truth the index-ordered plan has to reproduce.
   */
  @Test
  public void unfilteredUnboundMatchesThePlanWithoutAnOrderedIndex() {
    seedFanIn(2, 4, 0, INDEX_TYPE.NOTUNIQUE);
    var indexed =
        "MATCH {as: src, class: Person}.out('knows'){as: dst, class: Person}"
            + " RETURN dst.id ORDER BY dst.id";
    var reference =
        "MATCH {as: src, class: Person}.out('knows'){as: dst, class: Person}"
            + " RETURN dst.id ORDER BY dst.kind, dst.id";
    assertThat(plan(indexed)).contains("(UNFILTERED_UNBOUND)");
    assertThat(plan(reference))
        .as("the reference arm must not use the index-ordered traversal")
        .doesNotContain("INDEX ORDERED MATCH");
    assertThat(ids(indexed))
        .as("the index-ordered plan must return the same rows as the ordinary plan")
        .isEqualTo(ids(reference));
    assertThat(ids(indexed)).hasSize(8);
  }

  /**
   * FILTERED_UNBOUND union scan: a source WHERE plus a source alias no downstream step reads,
   * with a low index density and a small LIMIT so the cost model picks the union RidSet scan
   * rather than the load-and-sort fallback. Before the fix that scan emitted one row per target,
   * so the LIMIT returned two different targets instead of the lowest target twice.
   *
   * <p>THE RUNTIME PATH IS ASSERTED, because the plan text names the mode and not the strategy.
   * The mode chooses which aliases bind; the strategy chooses whether an index is scanned at
   * all, and the multiplicity fix under test lives only in the scan. Twelve union targets
   * against a 614-entry index with {@code LIMIT 3} give an estimated scan of 154 entries, well
   * inside the 874 the twelve loadable records are worth, and the model prices that scan below
   * load-and-sort. Without the path assertion this test passed on the fallback, which has
   * always emitted one row per pair, so reverting the fix broke nothing.
   */
  @Test
  public void filteredUnboundUnionScanKeepsOneRowPerSourceTargetPair() {
    seedFanIn(2, 12, 600, INDEX_TYPE.NOTUNIQUE);
    var query =
        "MATCH {as: src, class: Person, where: (kind = 'src')}"
            + ".out('knows'){as: dst, class: Person} RETURN dst.id ORDER BY dst.id LIMIT 3";
    assertThat(plan(query))
        .as("this shape must reach the FILTERED_UNBOUND mode under test")
        .contains("(FILTERED_UNBOUND)");
    assertRuntimePath(query, IndexOrderedEdgeStep.RuntimePath.UNION_SCAN);
    assertThat(ids(query))
        .as("both sources reach t00, so the first three sorted rows are t00, t00, t01")
        .containsExactly("t00", "t00", "t01");
  }

  /**
   * FILTERED_UNBOUND without a cut: the full result must hold every (source, target) pair. The
   * same fixture as the union-scan test above, so the row count is twelve targets times two
   * sources.
   *
   * <p>This shape runs the LOAD FALLBACK, and the assertion says so. Without a cut the
   * estimated scan is the whole index, which the admission rule refuses against twelve loadable
   * records, so no scan is attempted. That makes this a row-count invariant over the fallback
   * rather than a second test of the scan.
   */
  @Test
  public void filteredUnboundKeepsEveryPairWithoutACut() {
    seedFanIn(2, 12, 600, INDEX_TYPE.NOTUNIQUE);
    var query =
        "MATCH {as: src, class: Person, where: (kind = 'src')}"
            + ".out('knows'){as: dst, class: Person} RETURN dst.id ORDER BY dst.id LIMIT 100";
    assertRuntimePath(query, IndexOrderedEdgeStep.RuntimePath.LOAD_UNSORTED_MULTI);
    assertThat(ids(query))
        .as("twelve shared targets times two sources")
        .hasSize(24);
  }

  /** Runs {@code query} and asserts which physical strategy the index-ordered step chose. */
  private void assertRuntimePath(String query, IndexOrderedEdgeStep.RuntimePath expected) {
    try (var rs = session.query(query)) {
      rs.stream().forEach(row -> {
      });
      var plan = rs.getExecutionPlan();
      assertThat(plan).as("the query must have produced an execution plan").isNotNull();
      var step = findIndexOrderedStep(plan.getSteps());
      assertThat(step)
          .as("the plan must hold an index-ordered step:\n" + plan.prettyPrint(0, 2))
          .isNotNull();
      assertThat(step.getChosenRuntimePath())
          .as("unexpected runtime strategy. Plan:\n" + plan.prettyPrint(0, 2))
          .isEqualTo(expected);
    }
  }

  @Nullable private static IndexOrderedEdgeStep findIndexOrderedStep(
      List<ExecutionStep> steps) {
    for (var step : steps) {
      if (step instanceof IndexOrderedEdgeStep ordered) {
        return ordered;
      }
      var nested = findIndexOrderedStep(step.getSubSteps());
      if (nested != null) {
        return nested;
      }
    }
    return null;
  }
}
