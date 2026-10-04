package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.config.GlobalConfiguration;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.index.Index;
import com.jetbrains.youtrackdb.internal.core.index.IndexDefinition;
import com.jetbrains.youtrackdb.internal.core.index.engine.IndexStatistics;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaPropertyInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.TraversalPreFilterHelper;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/**
 * Unit tests for the static helper methods in {@link MatchExecutionPlanner} that support
 * hash join strategy selection for NOT patterns: {@code notPatternDependsOnMatched()} and
 * {@code findSharedAliases()}.
 *
 * <p>These helpers inspect NOT match expressions to determine whether they can be
 * independently materialized (no {@code $matched}/{@code $parent} dependency) and which
 * aliases are shared with the positive pattern (forming the join key).
 *
 * <p>Runs sequentially because it pins JVM-wide {@link GlobalConfiguration}
 * entries in {@code @Before}/{@code @After} — {@code QUERY_STATS_DEFAULT_FAN_OUT},
 * {@code QUERY_STATS_DEFAULT_SELECTIVITY}, and {@code QUERY_MATCH_HASH_JOIN_THRESHOLD}.
 * The last entry is also mutated by other MATCH test classes
 * ({@code MatchStepUnitTest}, {@code HashJoinPlannerIntegrationTest},
 * {@code InvertedWhileHashJoinTest}), so running in the parallel-classes pool
 * would race with those classes.
 */
@Category(SequentialTest.class)
public class MatchPlannerHelpersTest {

  // Pin config values so tests don't break if defaults change
  private static final double TEST_FAN_OUT = 10.0;
  private static final double TEST_SELECTIVITY = 0.1;
  private static final long TEST_HASH_JOIN_THRESHOLD = 10_000L;

  private Object savedFanOut;
  private Object savedSelectivity;
  private Object savedHashJoinThreshold;
  private Object savedUpstreamMin;

  @Before
  public void pinConfig() {
    savedFanOut = GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.getValue();
    savedSelectivity = GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY.getValue();
    savedHashJoinThreshold = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.getValue();
    savedUpstreamMin = GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.getValue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(5L);
    GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.setValue(TEST_FAN_OUT);
    GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY.setValue(TEST_SELECTIVITY);
    // Pin the hash-join threshold too so canUseHashJoin_* assertions are independent of
    // sibling test classes that also mutate this config entry.
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(TEST_HASH_JOIN_THRESHOLD);
  }

  @After
  public void restoreConfig() {
    GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.setValue(savedFanOut);
    GlobalConfiguration.QUERY_STATS_DEFAULT_SELECTIVITY.setValue(savedSelectivity);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(savedHashJoinThreshold);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(savedUpstreamMin);
  }

  // ── notPatternDependsOnMatched ──────────────────────────────────────────

  /**
   * A NOT expression with no WHERE clauses at all should not depend on execution
   * context. This is the simplest eligible case: {@code NOT {as: friend}.out(){as: tag}}.
   */
  @Test
  public void notPatternDependsOnMatched_noFilters_returnsFalse() {
    var exp = buildNotExpression("friend", null, "tag", null);
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isFalse();
  }

  /**
   * A NOT expression with a regular WHERE clause (no $matched/$parent) should not
   * depend on execution context.
   */
  @Test
  public void notPatternDependsOnMatched_regularFilter_returnsFalse() {
    var where = buildWhereClause("name = 'Alice'", false);
    var exp = buildNotExpression("friend", null, "tag", where);
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isFalse();
  }

  /**
   * A NOT expression with $matched reference in an intermediate filter must be
   * detected as context-dependent. This prevents hash join because the build side
   * cannot be materialized independently.
   */
  @Test
  public void notPatternDependsOnMatched_matchedInIntermediateFilter_returnsTrue() {
    var where = buildWhereClause("$matched.startPerson = $currentMatch", false);
    var exp = buildNotExpression("friend", null, "tag", where);
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isTrue();
  }

  /**
   * A NOT expression with $parent reference in an intermediate filter must be
   * detected via {@code refersToParent()}.
   */
  @Test
  public void notPatternDependsOnMatched_parentInIntermediateFilter_returnsTrue() {
    var where = buildWhereClause("something", true);
    var exp = buildNotExpression("friend", null, "tag", where);
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isTrue();
  }

  /**
   * A NOT expression with $matched reference in the origin's WHERE clause (defensive
   * check — currently the parser disallows WHERE on origin, but we check anyway).
   */
  @Test
  public void notPatternDependsOnMatched_matchedInOriginFilter_returnsTrue() {
    var originWhere = buildWhereClause("$matched.person = $currentMatch", false);
    var exp = buildNotExpression("friend", originWhere, "tag", null);
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isTrue();
  }

  /**
   * Multiple path items where only the second has $matched — must still detect it.
   */
  @Test
  public void notPatternDependsOnMatched_matchedInSecondPathItem_returnsTrue() {
    var exp = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("friend");
    exp.setOrigin(origin);

    // First path item: no $matched
    var item1 = new SQLMatchPathItem(-1);
    var filter1 = new SQLMatchFilter(-1);
    filter1.setAlias("intermediate");
    item1.setFilter(filter1);

    // Second path item: has $matched
    var item2 = new SQLMatchPathItem(-1);
    var filter2 = new SQLMatchFilter(-1);
    filter2.setAlias("tag");
    filter2.setFilter(buildWhereClause("$matched.x = 1", false));
    item2.setFilter(filter2);

    exp.setItems(List.of(item1, item2));
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isTrue();
  }

  /**
   * Mixed-case $MATCHED reference must still be detected — filterDependsOnContext
   * lowercases before checking.
   */
  @Test
  public void notPatternDependsOnMatched_mixedCaseMatched_returnsTrue() {
    var where = buildWhereClause("$MATCHED.person = $currentMatch", false);
    var exp = buildNotExpression("friend", null, "tag", where);
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isTrue();
  }

  /**
   * The string "$matched" without a trailing dot should NOT trigger context dependency,
   * because it's the dot-access pattern ($matched.alias) that indicates a reference.
   */
  @Test
  public void notPatternDependsOnMatched_matchedWithoutDot_returnsFalse() {
    var where = buildWhereClause("name = '$matched'", false);
    var exp = buildNotExpression("friend", null, "tag", where);
    assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(exp)).isFalse();
  }

  /** A WHILE condition using either outer-row variable rejects the detached hash build. */
  @Test
  public void notPatternDependsOnMatched_whileUsesOuterRow_returnsTrue() throws Exception {
    for (var variable : List.of("$matched", "$parent")) {
      var sql = "MATCH {as:a}.out('Friend'){as:x, maxDepth:2,"
          + " while:(" + variable + ".a IS NOT NULL)} RETURN a";
      var parsed = (SQLMatchStatement) new YouTrackDBSql(
          new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8))).parse();
      assertThat(MatchExecutionPlanner.notPatternDependsOnMatched(
          parsed.getMatchExpressions().getFirst())).isTrue();
    }
  }

  // ── findSharedAliases ───────────────────────────────────────────────────

  /**
   * Single shared alias: only the origin alias is shared with the positive pattern.
   * The NOT expression's leaf alias is unique to the NOT pattern.
   */
  @Test
  public void findSharedAliases_originOnlyShared_returnsSingleAlias() {
    var exp = buildNotExpression("person", null, "uniqueTag", null);
    var pattern = buildPattern("person", "friend", "city");

    var shared = MatchExecutionPlanner.findSharedAliases(exp, pattern);
    assertThat(shared).containsExactly("person");
  }

  /**
   * Composite shared aliases: both origin and leaf alias appear in the positive
   * pattern. This is the IC4 case where (friend, tag) are shared.
   */
  @Test
  public void findSharedAliases_originAndLeafShared_returnsOriginFirst() {
    var exp = buildNotExpression("friend", null, "tag", null);
    var pattern = buildPattern("person", "friend", "tag");

    var shared = MatchExecutionPlanner.findSharedAliases(exp, pattern);
    assertThat(shared).containsExactly("friend", "tag");
  }

  /**
   * All NOT expression aliases are shared with the positive pattern.
   */
  @Test
  public void findSharedAliases_allShared_returnsAllInOrder() {
    var exp = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("a");
    exp.setOrigin(origin);

    var item1 = new SQLMatchPathItem(-1);
    var filter1 = new SQLMatchFilter(-1);
    filter1.setAlias("b");
    item1.setFilter(filter1);

    var item2 = new SQLMatchPathItem(-1);
    var filter2 = new SQLMatchFilter(-1);
    filter2.setAlias("c");
    item2.setFilter(filter2);

    exp.setItems(List.of(item1, item2));
    var pattern = buildPattern("a", "b", "c", "d");

    var shared = MatchExecutionPlanner.findSharedAliases(exp, pattern);
    assertThat(shared).containsExactly("a", "b", "c");
  }

  /**
   * When a NOT path item has a null filter (edge-only traversal with no target
   * alias), it should be skipped without error.
   */
  @Test
  public void findSharedAliases_pathItemWithNullFilter_handledGracefully() {
    var exp = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("person");
    exp.setOrigin(origin);

    // Path item with null filter
    var item = new SQLMatchPathItem(-1);
    // filter is null by default
    exp.setItems(List.of(item));

    var pattern = buildPattern("person", "other");

    var shared = MatchExecutionPlanner.findSharedAliases(exp, pattern);
    assertThat(shared).containsExactly("person");
  }

  // ── estimateNotPatternCardinality ────────────────────────────────────────

  /**
   * Origin alias with a known class (100 records), one edge, no intermediate
   * filter. estimateRootEntries adds +1 for unfiltered classes, so origin
   * estimate is 101. Expected: 101 * TEST_FAN_OUT.
   */
  @Test
  public void estimateNotPatternCardinality_knownOrigin_oneEdge_noFilter() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);

    var estimate = MatchExecutionPlanner.estimateNotPatternCardinality(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx);
    assertThat(estimate).isEqualTo(Math.round(101 * TEST_FAN_OUT));
  }

  /**
   * Origin alias with no class/RID/filter in the maps — cannot estimate, must
   * return Long.MAX_VALUE to force fallback to nested-loop.
   */
  @Test
  public void estimateNotPatternCardinality_unknownOrigin_returnsMaxValue() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);

    // Empty maps — origin alias not found
    var estimate = MatchExecutionPlanner.estimateNotPatternCardinality(
        exp, Map.of(), Map.of(), Map.of(), ctx);
    assertThat(estimate).isEqualTo(Long.MAX_VALUE);
  }

  /**
   * Two edges with an intermediate WHERE filter. Without an index, the pinned
   * TEST_SELECTIVITY is used: (100+1) * TEST_FAN_OUT * TEST_FAN_OUT * TEST_SELECTIVITY.
   */
  @Test
  public void estimateNotPatternCardinality_twoEdges_withFilter() {
    var exp = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("person");
    exp.setOrigin(origin);

    // First edge: no filter
    var item1 = new SQLMatchPathItem(-1);
    var filter1 = new SQLMatchFilter(-1);
    filter1.setAlias("friend");
    item1.setFilter(filter1);

    // Second edge: has WHERE filter
    var item2 = new SQLMatchPathItem(-1);
    var filter2 = new SQLMatchFilter(-1);
    filter2.setAlias("tag");
    filter2.setFilter(buildWhereClause("name = 'X'", false));
    item2.setFilter(filter2);

    exp.setItems(List.of(item1, item2));
    var ctx = buildMockContext("Person", 100);

    var estimate = MatchExecutionPlanner.estimateNotPatternCardinality(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx);
    // (100+1) * TEST_FAN_OUT * TEST_FAN_OUT * TEST_SELECTIVITY
    long expected = Math.round(101 * TEST_FAN_OUT * TEST_FAN_OUT * TEST_SELECTIVITY);
    assertThat(estimate).isEqualTo(expected);
  }

  /**
   * Single edge with no intermediate filter (full fan-out only).
   * Origin has 50 records → (50+1) * 10 = 510.
   */
  @Test
  public void estimateNotPatternCardinality_oneEdge_fullFanout() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 50);

    var estimate = MatchExecutionPlanner.estimateNotPatternCardinality(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx);
    assertThat(estimate).isEqualTo(Math.round(51 * TEST_FAN_OUT));
  }

  /**
   * Boundary: estimated cardinality exactly at threshold should still be eligible.
   * Origin with 999 records: estimateRootEntries returns 1000,
   * times TEST_FAN_OUT = 10,000 == threshold (pinned in @Before).
   */
  @Test
  public void canUseHashJoin_exactlyAtThreshold_returnsTrue() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 999);

    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx,
        buildPattern("person", "tag")))
        .isTrue();
  }

  /**
   * Boundary: estimated cardinality one above threshold must fall back to nested-loop.
   * Origin with 1000 records: estimateRootEntries returns 1001,
   * times TEST_FAN_OUT = 10,010 > threshold.
   */
  @Test
  public void canUseHashJoin_oneAboveThreshold_returnsFalse() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 1000);

    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx,
        buildPattern("person", "tag")))
        .isFalse();
  }

  /**
   * When the cumulative estimate is large enough that multiplying by FANOUT_PER_HOP
   * would overflow, the method must return Long.MAX_VALUE instead of wrapping.
   */
  @Test
  public void estimateNotPatternCardinality_overflowGuard_returnsMaxValue() {
    // Build a 3-edge NOT expression with a very large origin class
    var exp = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("big");
    exp.setOrigin(origin);

    var item1 = new SQLMatchPathItem(-1);
    var f1 = new SQLMatchFilter(-1);
    f1.setAlias("a");
    item1.setFilter(f1);

    var item2 = new SQLMatchPathItem(-1);
    var f2 = new SQLMatchFilter(-1);
    f2.setAlias("b");
    item2.setFilter(f2);

    var item3 = new SQLMatchPathItem(-1);
    var f3 = new SQLMatchFilter(-1);
    f3.setAlias("c");
    item3.setFilter(f3);

    exp.setItems(List.of(item1, item2, item3));

    // Origin has Long.MAX_VALUE / 5 records — after one fan-out multiplication
    // the estimate exceeds Long.MAX_VALUE / 10, triggering the overflow guard
    var ctx = buildMockContext("Huge", Long.MAX_VALUE / 5);

    var estimate = MatchExecutionPlanner.estimateNotPatternCardinality(
        exp, Map.of("big", "Huge"), Map.of(), Map.of(), ctx);
    assertThat(estimate).isEqualTo(Long.MAX_VALUE);
  }

  /**
   * A NOT expression with zero edges (origin only, no path items). The estimate
   * should equal the origin's cardinality with no fan-out multiplication.
   */
  @Test
  public void estimateNotPatternCardinality_zeroEdges_returnsOriginCount() {
    var exp = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("person");
    exp.setOrigin(origin);
    exp.setItems(List.of()); // no edges

    var ctx = buildMockContext("Person", 200);

    var estimate = MatchExecutionPlanner.estimateNotPatternCardinality(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx);
    // 200 + 1 (unfiltered bias) = 201, no fan-out
    assertThat(estimate).isEqualTo(201);
  }

  // ── canUseHashJoin ──────────────────────────────────────────────────────

  /**
   * Eligible NOT expression: no $matched dependency, origin has a class, and
   * estimated cardinality (101 * 10 = 1010) is below HASH_JOIN_THRESHOLD.
   */
  @Test
  public void canUseHashJoin_eligible_returnsTrue() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);

    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx,
        buildPattern("person", "tag")))
        .isTrue();
  }

  /**
   * NOT expression with $matched dependency — cannot use hash join.
   */
  @Test
  public void canUseHashJoin_matchedDependency_returnsFalse() {
    var where = buildWhereClause("$matched.startPerson = $currentMatch", false);
    var exp = buildNotExpression("person", null, "tag", where);
    var ctx = buildMockContext("Person", 100);

    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx,
        buildPattern("person", "tag")))
        .isFalse();
  }

  /**
   * Origin alias has no class in aliasClasses — cannot construct build-side scan.
   */
  @Test
  public void canUseHashJoin_noOriginClass_returnsFalse() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);

    // Empty aliasClasses — origin has no class
    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of(), Map.of(), Map.of(), ctx, buildPattern("person", "tag")))
        .isFalse();
  }

  /**
   * Estimated cardinality exceeds HASH_JOIN_THRESHOLD — fallback to nested-loop.
   */
  @Test
  public void canUseHashJoin_highCardinality_returnsFalse() {
    var exp = buildNotExpression("person", null, "tag", null);
    // 1,000,000 records → (1,000,001) * 10 = 10,000,010 > 10,000 threshold
    var ctx = buildMockContext("Person", 1_000_000);

    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx,
        buildPattern("person", "tag")))
        .isFalse();
  }

  /** A zero-hop check on an optional origin needs the per-row probe, unlike a check with a hop. */
  @Test
  public void canUseHashJoin_optionalOriginOnlyRejectsZeroHop() {
    var zeroHop = new SQLMatchExpression(-1);
    var origin = new SQLMatchFilter(-1);
    origin.setAlias("person");
    zeroHop.setOrigin(origin);
    zeroHop.setItems(List.of());
    var withHop = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);
    var pattern = buildPattern("person", "tag");
    pattern.aliasToNode.get("person").optional = true;

    assertThat(MatchExecutionPlanner.canUseHashJoin(zeroHop,
        Map.of("person", "Person"), Map.of(), Map.of(), ctx, pattern)).isFalse();
    assertThat(MatchExecutionPlanner.canUseHashJoin(withHop,
        Map.of("person", "Person"), Map.of(), Map.of(), ctx, pattern)).isTrue();
    pattern.aliasToNode.get("person").optional = false;
    assertThat(MatchExecutionPlanner.canUseHashJoin(zeroHop,
        Map.of("person", "Person"), Map.of(), Map.of(), ctx, pattern)).isTrue();
  }

  // ── collectDownstreamAliases ────────────────────────────────────────────

  /** Creates a minimal planner with all wildcard return flags off. */
  private static MatchExecutionPlanner plannerWithDefaults() {
    return new MatchExecutionPlanner(new Pattern(), Map.of());
  }

  /**
   * RETURN with a single dotted expression (friend.name) should detect the
   * "friend" alias as referenced downstream.
   */
  @Test
  public void collectDownstreamAliases_singleDottedReturn_detectsAlias() {
    var allAliases = Set.of("person", "friend", "tag");
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("friend.name")),
        null, null, null,
        allAliases);
    assertThat(result).containsExactly("friend");
  }

  /**
   * RETURN referencing multiple aliases in different expressions should detect
   * all of them.
   */
  @Test
  public void collectDownstreamAliases_multipleReturnExpressions_detectsAll() {
    var allAliases = Set.of("person", "friend", "tag");
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("person.name"), buildExpression("tag.value")),
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "tag");
  }

  /**
   * GROUP BY adds alias references: an alias in GROUP BY but not in RETURN
   * should still be detected.
   */
  @Test
  public void collectDownstreamAliases_groupByAddsAlias() {
    var allAliases = Set.of("person", "friend", "city");
    var groupBy = new SQLGroupBy(-1);
    groupBy.addItem(buildExpression("city.name"));
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        groupBy, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "city");
  }

  /**
   * ORDER BY adds alias references via its item alias.
   */
  @Test
  public void collectDownstreamAliases_orderByAddsAlias() {
    var allAliases = Set.of("person", "friend", "tag");
    var orderBy = new SQLOrderBy(-1);
    var orderItem = new SQLOrderByItem();
    orderItem.setAlias("friend");
    orderBy.setItems(new java.util.ArrayList<>(List.of(orderItem)));
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        null, orderBy, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "friend");
  }

  /**
   * UNWIND adds alias references for the identifier being unwound.
   */
  @Test
  public void collectDownstreamAliases_unwindAddsAlias() {
    var allAliases = Set.of("person", "friend", "tags");
    var unwind = new SQLUnwind(-1);
    var ident = new SQLIdentifier("tags");
    unwind.addItem(ident);
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        null, null, unwind,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "tags");
  }

  /**
   * Wildcard return mode ($elements) should return all pattern aliases
   * regardless of RETURN expressions.
   */
  @Test
  public void collectDownstreamAliases_returnElements_returnsAllAliases() {
    var allAliases = Set.of("person", "friend", "tag");
    var planner = plannerWithDefaults();
    planner.returnElements = true;
    var result = planner.collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "friend", "tag");
  }

  /**
   * Wildcard return mode ($paths) should return all pattern aliases.
   */
  @Test
  public void collectDownstreamAliases_returnPaths_returnsAllAliases() {
    var allAliases = Set.of("person", "friend");
    var planner = plannerWithDefaults();
    planner.returnPaths = true;
    var result = planner.collectDownstreamAliases(
        List.of(),
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "friend");
  }

  /**
   * Wildcard return mode ($patterns) should return all pattern aliases.
   */
  @Test
  public void collectDownstreamAliases_returnPatterns_returnsAllAliases() {
    var allAliases = Set.of("a", "b", "c");
    var planner = plannerWithDefaults();
    planner.returnPatterns = true;
    var result = planner.collectDownstreamAliases(
        List.of(),
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("a", "b", "c");
  }

  /**
   * Wildcard return mode ($pathElements) should return all pattern aliases.
   */
  @Test
  public void collectDownstreamAliases_returnPathElements_returnsAllAliases() {
    var allAliases = Set.of("x", "y");
    var planner = plannerWithDefaults();
    planner.returnPathElements = true;
    var result = planner.collectDownstreamAliases(
        List.of(),
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("x", "y");
  }

  /**
   * An expression that references no alias (e.g., count(*)) should produce
   * an empty result set (aside from aliases in other expressions).
   */
  @Test
  public void collectDownstreamAliases_noAliasInExpression_emptySet() {
    var allAliases = Set.of("person", "friend");
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("count(*)")),
        null, null, null,
        allAliases);
    assertThat(result).isEmpty();
  }

  /**
   * Short alias name must not be falsely matched inside a longer identifier.
   * E.g., alias "a" should not match in "abandonment" or "data".
   */
  @Test
  public void collectDownstreamAliases_shortAliasNotFalselyMatched() {
    var allAliases = Set.of("a", "friend");
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("abandonment.data")),
        null, null, null,
        allAliases);
    assertThat(result).isEmpty();
  }

  /**
   * Short alias "a" should match when it appears as a standalone word:
   * "a.name" starts with the alias followed by a dot.
   */
  @Test
  public void collectDownstreamAliases_shortAliasMatchesStandalone() {
    var allAliases = Set.of("a", "friend");
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(buildExpression("a.name")),
        null, null, null,
        allAliases);
    assertThat(result).containsExactly("a");
  }

  /**
   * Empty RETURN items (defensive case) should return all aliases.
   */
  @Test
  public void collectDownstreamAliases_emptyReturnItems_returnsAllAliases() {
    var allAliases = Set.of("person", "friend");
    var result = plannerWithDefaults().collectDownstreamAliases(
        List.of(),
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "friend");
  }

  /**
   * Null RETURN items (defensive case) should return all aliases.
   */
  @Test
  public void collectDownstreamAliases_nullReturnItems_returnsAllAliases() {
    var allAliases = Set.of("person", "friend");
    var result = plannerWithDefaults().collectDownstreamAliases(
        null,
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "friend");
  }

  /** Both kinds of detached check retain positive aliases read by WHERE and WHILE. */
  @Test
  public void collectDownstreamAliases_checkConditionsRetainPositiveAliases() throws Exception {
    var sql = "MATCH {as:a}.out('Friend'){as:x,"
        + " where:($matched.c.name = 'n2'),"
        + " while:($matched.t.name = 't1'), maxDepth:1} RETURN a";
    var check = (SQLMatchStatement) new YouTrackDBSql(
        new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8))).parse();
    var pattern = buildPattern("a", "c", "t");
    for (var negative : List.of(false, true)) {
      var inputs = MatchPlanInputs.builder(pattern)
          .notMatchExpressions(negative ? check.getMatchExpressions() : List.of())
          .existsMatchExpressions(negative ? List.of() : check.getMatchExpressions())
          .build();
      var planner = new MatchExecutionPlanner(inputs);
      assertThat(planner.collectDownstreamAliases(
          List.of(buildExpression("a.name")), null, null, null, pattern.aliasToNode.keySet()))
          .containsExactlyInAnyOrder("a", "c", "t");
    }
  }

  // ── identifyHashJoinBranches ────────────────────────────────────────────

  /**
   * A simple 2-branch diamond: a→b→d and a→c→d. When only "a" and "d" are
   * downstream, the branch a→c→d should be detected as semi-join eligible.
   * Schedule: [a→b, b→d, a→c, c→d(check)]. Edge c→d is a consistency-check
   * edge because d was already visited via a→b→d.
   */
  @Test
  public void identifyHashJoinBranches_diamondPattern_detectsBranch() {
    // Build nodes
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var nodeC = new PatternNode();
    nodeC.alias = "c";
    var nodeD = new PatternNode();
    nodeD.alias = "d";

    // Build edges: a→b, b→d, a→c, c→d
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();
    nodeB.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeBD = nodeB.out.iterator().next();
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeC);
    // nodeA has 2 out edges now — find the one to C
    PatternEdge edgeAC = null;
    for (var e : nodeA.out) {
      if (e.in == nodeC) {
        edgeAC = e;
        break;
      }
    }
    nodeC.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeCD = nodeC.out.iterator().next();

    // Schedule: a→b (fwd), b→d (fwd), a→c (fwd), c→d (fwd, check)
    var schedule = List.of(
        new EdgeTraversal(edgeAB, true),
        new EdgeTraversal(edgeBD, true),
        new EdgeTraversal(edgeAC, true),
        new EdgeTraversal(edgeCD, true));

    // Only a and d are downstream → c is intermediate
    var downstream = Set.of("a", "d");
    var ctx = buildMockContext("Person", 50);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        schedule, downstream,
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of(), Map.of(), ctx);

    assertThat(result).hasSize(1);
    var branch = result.get(0);
    assertThat(branch.joinMode()).isEqualTo(JoinMode.SEMI_JOIN);
    assertThat(branch.sharedAliases()).containsExactlyInAnyOrder("a", "d");
    assertThat(branch.intermediateAliases()).containsExactly("c");
    assertThat(branch.branchEdges()).hasSize(2);
    // Verify the actual edges are a→c and c→d, not a→b and b→d
    assertThat(branch.branchEdges().get(0).edge.out.alias).isEqualTo("a");
    assertThat(branch.branchEdges().get(0).edge.in.alias).isEqualTo("c");
    assertThat(branch.branchEdges().get(1).edge.out.alias).isEqualTo("c");
    assertThat(branch.branchEdges().get(1).edge.in.alias).isEqualTo("d");
  }

  /**
   * Same diamond but "c" is referenced downstream (in RETURN) → classified as
   * INNER_JOIN so that build-side intermediate alias values are merged into the
   * result rows.
   */
  @Test
  public void identifyHashJoinBranches_intermediateInReturn_innerJoin() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var nodeC = new PatternNode();
    nodeC.alias = "c";
    var nodeD = new PatternNode();
    nodeD.alias = "d";

    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();
    nodeB.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeBD = nodeB.out.iterator().next();
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeC);
    PatternEdge edgeAC = null;
    for (var e : nodeA.out) {
      if (e.in == nodeC) {
        edgeAC = e;
        break;
      }
    }
    nodeC.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeCD = nodeC.out.iterator().next();

    var schedule = List.of(
        new EdgeTraversal(edgeAB, true),
        new EdgeTraversal(edgeBD, true),
        new EdgeTraversal(edgeAC, true),
        new EdgeTraversal(edgeCD, true));

    // c is downstream → INNER_JOIN (build-side rows merged into result)
    var downstream = Set.of("a", "c", "d");
    var ctx = buildMockContext("Person", 50);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        schedule, downstream,
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of(), Map.of(), ctx);

    assertThat(result).hasSize(1);
    var branch = result.get(0);
    assertThat(branch.joinMode()).isEqualTo(JoinMode.INNER_JOIN);
    assertThat(branch.sharedAliases()).containsExactlyInAnyOrder("a", "d");
    assertThat(branch.intermediateAliases()).containsExactly("c");
    assertThat(branch.scanAlias()).isIn("a", "c", "d");
  }

  /**
   * Branch with a $matched reference in the intermediate node's filter → not eligible.
   */
  @Test
  public void identifyHashJoinBranches_matchedDependency_noBranch() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var nodeC = new PatternNode();
    nodeC.alias = "c";
    var nodeD = new PatternNode();
    nodeD.alias = "d";

    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();
    nodeB.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeBD = nodeB.out.iterator().next();
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeC);
    PatternEdge edgeAC = null;
    for (var e : nodeA.out) {
      if (e.in == nodeC) {
        edgeAC = e;
        break;
      }
    }
    nodeC.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeCD = nodeC.out.iterator().next();

    var schedule = List.of(
        new EdgeTraversal(edgeAB, true),
        new EdgeTraversal(edgeBD, true),
        new EdgeTraversal(edgeAC, true),
        new EdgeTraversal(edgeCD, true));

    var downstream = Set.of("a", "d");
    var ctx = buildMockContext("Person", 50);
    // c's filter references $matched
    var cFilter = buildWhereClause("$matched.a.name = name", false);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        schedule, downstream,
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of("c", cFilter), Map.of(), ctx);

    assertThat(result).isEmpty();
  }

  /**
   * Single edge schedule → no consistency-check edge possible → empty result.
   */
  @Test
  public void identifyHashJoinBranches_singleEdge_empty() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();

    var schedule = List.of(new EdgeTraversal(edgeAB, true));
    var ctx = buildMockContext("Person", 50);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        schedule, Set.of("a", "b"),
        Map.of("a", "Person", "b", "Person"),
        Map.of(), Map.of(), ctx);

    assertThat(result).isEmpty();
  }

  /**
   * Branch with cardinality exceeding threshold (1M records × FANOUT_PER_HOP) → rejected.
   */
  @Test
  public void identifyHashJoinBranches_highCardinality_noBranch() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var nodeC = new PatternNode();
    nodeC.alias = "c";
    var nodeD = new PatternNode();
    nodeD.alias = "d";

    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();
    nodeB.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeBD = nodeB.out.iterator().next();
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeC);
    PatternEdge edgeAC = null;
    for (var e : nodeA.out) {
      if (e.in == nodeC) {
        edgeAC = e;
        break;
      }
    }
    nodeC.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeCD = nodeC.out.iterator().next();

    var schedule = List.of(
        new EdgeTraversal(edgeAB, true),
        new EdgeTraversal(edgeBD, true),
        new EdgeTraversal(edgeAC, true),
        new EdgeTraversal(edgeCD, true));

    var downstream = Set.of("a", "d");
    // 1M records → branch cardinality = 1M * 10 * 10 = 100M > 10K threshold
    var ctx = buildMockContext("Person", 1_000_000);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        schedule, downstream,
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of(), Map.of(), ctx);

    assertThat(result).isEmpty();
  }

  /**
   * Linear chain a→b→c with no consistency-check edge → empty result.
   */
  @Test
  public void identifyHashJoinBranches_linearChain_noBranch() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var nodeC = new PatternNode();
    nodeC.alias = "c";

    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();
    nodeB.addEdge(new SQLMatchPathItem(-1), nodeC);
    var edgeBC = nodeB.out.iterator().next();

    var schedule = List.of(
        new EdgeTraversal(edgeAB, true),
        new EdgeTraversal(edgeBC, true));

    var ctx = buildMockContext("Person", 50);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        schedule, Set.of("a", "c"),
        Map.of("a", "Person", "b", "Person", "c", "Person"),
        Map.of(), Map.of(), ctx);

    assertThat(result).isEmpty();
  }

  /**
   * Empty edge schedule (0 edges) → early return, no IndexOutOfBoundsException.
   */
  @Test
  public void identifyHashJoinBranches_emptySchedule_empty() {
    var ctx = buildMockContext("Person", 50);
    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        List.of(), Set.of("a"),
        Map.of("a", "Person"), Map.of(), Map.of(), ctx);
    assertThat(result).isEmpty();
  }

  /**
   * Branch with an optional node in the intermediate position → not eligible.
   * Optional nodes have different matching semantics (can produce null bindings).
   */
  @Test
  public void identifyHashJoinBranches_optionalNode_noBranch() {
    var diamond = buildDiamondSchedule();
    // Mark node C as optional
    diamond.nodeC.optional = true;

    var ctx = buildMockContext("Person", 50);
    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        diamond.schedule, Set.of("a", "d"),
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of(), Map.of(), ctx);
    assertThat(result).isEmpty();
  }

  /**
   * Branch with an auto-generated alias ($YOUTRACKDB_DEFAULT_ALIAS_0) as intermediate
   * → not eligible. Auto-generated aliases come from unnamed pattern nodes.
   */
  @Test
  public void identifyHashJoinBranches_autoGeneratedAlias_noBranch() {
    // Build diamond with auto-generated alias for C
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var nodeC = new PatternNode();
    nodeC.alias = "$YOUTRACKDB_DEFAULT_ALIAS_0";
    var nodeD = new PatternNode();
    nodeD.alias = "d";

    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();
    nodeB.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeBD = nodeB.out.iterator().next();
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeC);
    PatternEdge edgeAC = null;
    for (var e : nodeA.out) {
      if (e.in == nodeC) {
        edgeAC = e;
        break;
      }
    }
    nodeC.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeCD = nodeC.out.iterator().next();

    var schedule = List.of(
        new EdgeTraversal(edgeAB, true),
        new EdgeTraversal(edgeBD, true),
        new EdgeTraversal(edgeAC, true),
        new EdgeTraversal(edgeCD, true));

    var ctx = buildMockContext("Person", 50);
    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        schedule, Set.of("a", "d"),
        Map.of("a", "Person", "b", "Person",
            "$YOUTRACKDB_DEFAULT_ALIAS_0", "Person", "d", "Person"),
        Map.of(), Map.of(), ctx);
    assertThat(result).isEmpty();
  }

  /**
   * Branch where the preferred scan alias (otherShared = d) has no class, but another
   * shared alias (branchRoot = a) does → eligible, with scanAlias falling back to "a".
   */
  @Test
  public void identifyHashJoinBranches_scanAliasFallback_usesBranchRoot() {
    var diamond = buildDiamondSchedule();
    var ctx = buildMockContext("Person", 50);

    // Omit "d" from aliasClasses — findScanAlias should fall back to branchRoot "a"
    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        diamond.schedule, Set.of("a", "d"),
        Map.of("a", "Person", "b", "Person", "c", "Person"),
        Map.of(), Map.of(), ctx);
    assertThat(result).hasSize(1);
    assertThat(result.get(0).scanAlias()).isEqualTo("a");
    assertThat(result.get(0).joinMode()).isEqualTo(JoinMode.SEMI_JOIN);
  }

  /**
   * Branch where NO alias (shared or intermediate) has a class or RID → not eligible
   * because the build-side plan cannot scan records from any starting point.
   */
  @Test
  public void identifyHashJoinBranches_noAliasHasClass_noBranch() {
    var diamond = buildDiamondSchedule();
    var ctx = buildMockContext("Person", 50);

    // No alias has a class → findScanAlias returns null → branch rejected
    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        diamond.schedule, Set.of("a", "d"),
        Map.of(), Map.of(), Map.of(), ctx);
    assertThat(result).isEmpty();
  }

  /**
   * Branch with $parent reference in the intermediate node's filter → not eligible.
   * Complements the existing $matched test.
   */
  @Test
  public void identifyHashJoinBranches_parentDependency_noBranch() {
    var diamond = buildDiamondSchedule();
    var ctx = buildMockContext("Person", 50);
    var cFilter = buildWhereClause("$parent.a.name = name", true);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        diamond.schedule, Set.of("a", "d"),
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of("c", cFilter), Map.of(), ctx);
    assertThat(result).isEmpty();
  }

  /**
   * Branch where the intermediate alias 'c' has no context filter, but the check-target
   * 'd' (a shared alias) has a $matched filter → not eligible, because the shared alias
   * filter depends on the matched context. Exercises the guard at the end of
   * traceBackwardBranch that checks checkTarget's filter separately from intermediates.
   */
  @Test
  public void identifyHashJoinBranches_sharedAliasMatchedFilter_noBranch() {
    var diamond = buildDiamondSchedule();
    var ctx = buildMockContext("Person", 50);
    // Filter on the check-target (shared alias d), not on the intermediate c
    var dFilter = buildWhereClause("$matched.a.name = name", false);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        diamond.schedule, Set.of("a", "d"),
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of("d", dFilter), Map.of(), ctx);
    assertThat(result).isEmpty();
  }

  /**
   * Overflow cardinality: branch root with extreme record count should be gracefully
   * rejected (exceeds threshold) instead of throwing ArithmeticException.
   */
  @Test
  public void identifyHashJoinBranches_overflowCardinality_noBranch() {
    var diamond = buildDiamondSchedule();
    var ctx = buildMockContext("Person", Long.MAX_VALUE / 5);

    var result = MatchExecutionPlanner.identifyHashJoinBranches(
        diamond.schedule, Set.of("a", "d"),
        Map.of("a", "Person", "b", "Person", "c", "Person", "d", "Person"),
        Map.of(), Map.of(), ctx);
    assertThat(result).isEmpty();
  }

  /** The minimum rejects small outer inputs, while Guard 2 rejects ties and expensive scans. */
  @Test
  public void detachedCostGuards_compareCandidateWorkAndOriginScan() {
    var walk = OptionalDouble.of(10);
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.of(4), 1, walk))
        .isFalse();
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.of(100), 100, walk))
        .isFalse();
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.of(11), 9, walk))
        .isFalse(); // both costs are 110, so hash must not win
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.of(1_800), 900, walk))
        .isTrue(); // first-neighbour matches do not change these undiscounted costs
  }

  /** Unknown rows or walk bypass both guards. Setting zero bypasses costs, not eligibility. */
  @Test
  public void detachedCostGuards_unknownAndOffSwitch() {
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.empty(), 100,
        OptionalDouble.of(1))).isTrue();
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.of(1), 100,
        OptionalDouble.empty())).isTrue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.of(1), 100,
        OptionalDouble.of(1))).isTrue();
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of(), Map.of(), Map.of(), ctx,
        buildPattern("person", "tag"), OptionalLong.of(1))).isFalse();
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of("person", "Person"), Map.of(),
        Map.of(), ctx, buildPattern("person", "tag"), OptionalLong.of(1))).isTrue();
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(5L);
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of("person", "Person"), Map.of(),
        Map.of(), ctx, buildPattern("person", "tag"), OptionalLong.of(1))).isFalse();
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of("person", "Person"), Map.of(),
        Map.of(), ctx, buildPattern("person", "tag"), OptionalLong.empty())).isTrue();
  }

  /** A late selective match still visits ten candidates. Only earlier filters discount later hops. */
  @Test
  public void detachedWalkCost_countsCandidatesBeforeFilters() {
    var exp = buildNotExpression("person", null, "tag", buildWhereClause("flag=true", false));
    var ctx = buildMockContext("Person", 100);
    assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp, Map.of("person", "Person"),
        ctx)).isEqualTo(OptionalDouble.of(10));
    var second = buildNotExpression("tag", null, "leaf", null).getItems().getFirst();
    exp.setItems(List.of(exp.getItems().getFirst(), second));
    assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp, Map.of("person", "Person"),
        ctx)).isEqualTo(OptionalDouble.of(20)); // ten visits, then one survivor times ten
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of("person", "Person"), Map.of(),
        Map.of(), ctx, buildPattern("person"), OptionalLong.of(10_000))).isTrue();
  }

  /** Either recursive marker on a later item makes the entire walk unknown before Guard 1. */
  @Test
  public void detachedWalkCost_recursiveItemBypassesGuards() throws Exception {
    for (var condition : List.of("maxDepth:3", "while:(true)")) {
      var sql = "MATCH {as:person}.out(){as:tag}.out(){as:leaf, " + condition + "} RETURN person";
      var statement = (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
          sql.getBytes(StandardCharsets.UTF_8))).parse();
      var exp = statement.getMatchExpressions().getFirst();
      // Two hops must fit the eligibility threshold before recursion can bypass cost guards.
      var ctx = buildMockContext("Person", 90);
      assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp,
          Map.of("person", "Person"), ctx)).isEmpty();
      assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of("person", "Person"), Map.of(),
          Map.of(), ctx, buildPattern("person"), OptionalLong.of(1))).isTrue();
    }
  }

  /** Root-only and scheduled estimates use the actual root, and unknown survives composition. */
  @Test
  public void detachedOuterEstimate_followsScheduleAndPropagatesUnknown() {
    var ctx = buildMockContext("Person", 100);
    var pattern = buildPattern("a", "b", "c");
    var a = pattern.aliasToNode.get("a");
    var b = pattern.aliasToNode.get("b");
    var c = pattern.aliasToNode.get("c");
    a.addEdge(new SQLMatchPathItem(-1), b);
    b.addEdge(new SQLMatchPathItem(-1), c);
    var schedule = List.of(new EdgeTraversal(a.out.iterator().next(), true),
        new EdgeTraversal(b.out.iterator().next(), true));
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(buildPattern("a"), List.of(),
        Map.of("a", 100L), Map.of(), Map.of(), ctx)).isEqualTo(OptionalLong.of(100));
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, schedule,
        Map.of("a", 100L), Map.of("a", "Person", "b", "Person"),
        Map.of("b", buildWhereClause("flag=true", false)), ctx)).isEqualTo(OptionalLong.of(1_000));
    for (var roots : List.of(Map.<String, Long>of(), Map.of("a", -1L),
        Map.of("a", Long.MAX_VALUE),
        Map.of("a", Long.MAX_VALUE / 5))) {
      assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, schedule,
          roots, Map.of(), Map.of(), ctx)).isEmpty();
    }
    assertThat(MatchExecutionPlanner.multiplyOuterEstimates(
        List.of(OptionalLong.of(20), OptionalLong.of(30)))).isEqualTo(OptionalLong.of(600));
    assertThat(MatchExecutionPlanner.multiplyOuterEstimates(
        List.of(OptionalLong.of(20), OptionalLong.empty(), OptionalLong.of(0)))).isEmpty();
    assertThat(MatchExecutionPlanner.multiplyOuterEstimates(
        List.of(OptionalLong.of(Long.MAX_VALUE / 2), OptionalLong.of(3)))).isEmpty();
    assertThat(MatchExecutionPlanner.multiplyOuterEstimates(
        List.of(OptionalLong.of(Long.MAX_VALUE)))).isEmpty();
    assertThat(MatchExecutionPlanner.multiplyOuterEstimates(null)).isEmpty();
  }

  /**
   * Two unfiltered NOT hops with no LINK declarations retain Person for fan-out statistics.
   * Person, R and S each have 1000 records, so the build stays at 1001 and remains hash eligible
   * under threshold 10000 with cost guards disabled, rather than using default fan-out 10.
   */
  @Test
  public void detachedHashEligibility_unknownTargetRetainsFanOutClass() throws Exception {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
    var sql = "MATCH {class:Person, as:a}, NOT {as:a}.out('R'){as:b}.out('S'){as:x} RETURN a";
    var statement = (SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
        sql.getBytes(StandardCharsets.UTF_8))).parse();
    var exp = statement.getNotMatchExpressions().getFirst();
    var ctx = classCountsWithoutLinks(Map.of("Person", 1_000L, "R", 1_000L, "S", 1_000L));
    var classes = Map.of("a", "Person");
    assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
        classes, Map.of(), Map.of(), ctx)).isEqualTo(1_001);
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, classes, Map.of(), Map.of(), ctx,
        buildPattern("a"), OptionalLong.of(1_001))).isTrue();
    assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp, classes, ctx))
        .isEqualTo(OptionalDouble.of(2));
  }

  /**
   * The diamond's unfiltered main path a->b->d retains Person without LINK declarations.
   * Its upstream estimate is 1001, below minimum 5000, so Guard 1 rejects an eligible branch.
   * T has fan-out 2 so Guard 2 would accept the inflated default-fan-out estimate of 10010.
   */
  @Test
  public void branchGuard_unknownTargetRetainsFanOutClass() throws Exception {
    var diamond = buildDiamondSchedule();
    var main = parseExpression("{as:a}.out('R'){as:b}.out('S'){as:d}");
    var branch = parseExpression("{as:a}.out('T'){as:c}.out('S'){as:d}");
    for (int i = 0; i < 2; i++) {
      diamond.schedule.get(i).edge.item = main.getItems().get(i);
      diamond.schedule.get(i + 2).edge.item = branch.getItems().get(i);
    }
    var ctx = classCountsWithoutLinks(
        Map.of("Person", 1_000L, "R", 1_000L, "S", 1_000L, "T", 2_000L));
    var classes = Map.of("a", "Person");
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(0L);
    assertThat(MatchExecutionPlanner.identifyHashJoinBranches(diamond.schedule, Set.of("a", "d"),
        classes, Map.of(), Map.of(), ctx)).hasSize(1);
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(5_000L);
    var method = MatchExecutionPlanner.class.getDeclaredMethod("estimateUpstreamCardinality",
        List.class, int.class, List.class, Map.class, Map.class, Map.class, CommandContext.class);
    method.setAccessible(true);
    assertThat((long) method.invoke(null, diamond.schedule, 3, diamond.schedule.subList(2, 4),
        classes, Map.of(), Map.of(), ctx)).isEqualTo(1_001);
    assertThat(MatchExecutionPlanner.identifyHashJoinBranches(diamond.schedule, Set.of("a", "d"),
        classes, Map.of(), Map.of(), ctx)).isEmpty();
  }

  /** A source name index must not narrow the target's name filter or the next hop's work. */
  @Test
  public void detachedWalkCost_targetClassSelectivity() throws Exception {
    var ctx = sourceNameIndexContext();
    var exp = parseExpression("{as:a}.out(){as:b, where:(name='X')}.out(){as:c}");
    exp.getItems().getFirst().getFilter().setFilter(indexedNameFilter(ctx));
    assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp,
        Map.of("a", "Person", "b", "City"), ctx)).isEqualTo(OptionalDouble.of(20));
  }

  /** A City filter uses default selectivity, not Person's selective name index: 1000 rows. */
  @Test
  public void detachedOuterEstimate_targetClassSelectivity() throws Exception {
    var ctx = sourceNameIndexContext();
    var exp = parseExpression("{as:a}.out(){as:b, where:(name='X')}");
    exp.getItems().getFirst().getFilter().setFilter(indexedNameFilter(ctx));
    var pattern = new Pattern();
    pattern.addExpression(exp);
    var schedule = List.of(new EdgeTraversal(pattern.aliasToNode.get("a").out.iterator().next(),
        true));
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, schedule,
        Map.of("a", 1_000L), Map.of("a", "Person", "b", "City"),
        Map.of("b", exp.getItems().getFirst().getFilter().getFilter()), ctx))
        .isEqualTo(OptionalLong.of(1_000));
  }

  /** NOT eligibility must not undercount City matches using Person's name index. */
  @Test
  public void estimateNotPatternCardinality_targetClassSelectivity() throws Exception {
    var ctx = sourceNameIndexContext();
    var exp = parseExpression("{as:a}.out(){as:b, where:(name='X')}");
    exp.getItems().getFirst().getFilter().setFilter(indexedNameFilter(ctx));
    assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
        Map.of("a", "Person", "b", "City"), Map.of(), Map.of(), ctx)).isEqualTo(10_001);
  }

  /** The branch's City filter leaves fan-out 2, rather than Person's index clamping it to 1. */
  @Test
  public void estimateBranchFanOut_targetClassSelectivity() throws Exception {
    GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.setValue(20.0);
    var ctx = sourceNameIndexContext();
    var diamond = buildDiamondSchedule();
    var where = indexedNameFilter(ctx);
    var method = MatchExecutionPlanner.class.getDeclaredMethod("estimateBranchFanOut",
        List.class, String.class, Map.class, Map.class, CommandContext.class);
    method.setAccessible(true);
    assertThat((double) method.invoke(null, diamond.schedule.subList(2, 4), "a",
        Map.of("a", "Person", "c", "City"), Map.of("c", where), ctx)).isEqualTo(2.0);
  }

  /** Alias, explicit and schema-inferred targets all use City, including reversed schedules. */
  @Test
  public void targetClassSelectivity_resolvesReachedClass() throws Exception {
    var ctx = sourceNameIndexContext();
    var schema = ctx.getDatabaseSession().getMetadata().getImmutableSchemaSnapshot();
    var lives = mock(SchemaClassInternal.class);
    var in = mock(SchemaPropertyInternal.class);
    var out = mock(SchemaPropertyInternal.class);
    when(schema.getClassInternal("Lives")).thenReturn(lives);
    when(lives.approximateCount(ctx.getDatabaseSession())).thenReturn(100_000L);
    when(lives.getPropertyInternal("in")).thenReturn(in);
    when(lives.getPropertyInternal("out")).thenReturn(out);
    var city = schema.getClassInternal("City");
    var person = schema.getClassInternal("Person");
    when(in.getLinkedClass()).thenReturn(city);
    when(out.getLinkedClass()).thenReturn(person);
    when(person.getName()).thenReturn("Person");
    // Lives has ten edges per Person, matching the pinned default fan-out.
    for (var target : List.of("as:b", "class:City, as:b")) {
      var exp = parseExpression("{as:a}.out('Lives'){" + target
          + ", where:(name='X')}.out(){as:c}");
      exp.getItems().getFirst().getFilter().setFilter(indexedNameFilter(ctx));
      assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp, Map.of("a", "Person"), ctx))
          .isEqualTo(OptionalDouble.of(20));
      assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
          Map.of("a", "Person"), Map.of(), Map.of(), ctx)).isEqualTo(100_010);
    }
    for (var path : List.of("{as:a}.out('Lives'){as:b}", "{as:b}.in('Lives'){as:a}")) {
      var exp = parseExpression(path);
      var pattern = new Pattern();
      pattern.addExpression(exp);
      var forward = "a".equals(exp.getOrigin().getAlias());
      var edge = pattern.aliasToNode.get(exp.getOrigin().getAlias()).out.iterator().next();
      assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern,
          List.of(new EdgeTraversal(edge, forward)), Map.of("a", 1_000L),
          Map.of("a", "Person"), Map.of("b", indexedNameFilter(ctx)), ctx))
          .isEqualTo(OptionalLong.of(1_000));
    }
  }

  /** Branch build cardinality also uses City's filter statistics: 10001 rather than 100. */
  @Test
  public void estimateBranchCardinality_targetClassSelectivity() throws Exception {
    var ctx = sourceNameIndexContext();
    var diamond = buildDiamondSchedule();
    var method = MatchExecutionPlanner.class.getDeclaredMethod("estimateBranchCardinality",
        String.class, List.class, Map.class, Map.class, Map.class, CommandContext.class);
    method.setAccessible(true);
    assertThat((long) method.invoke(null, "a", diamond.schedule.subList(2, 4),
        Map.of("a", "Person", "c", "City"), Map.of("c", indexedNameFilter(ctx)), Map.of(), ctx))
        .isEqualTo(10_001);
  }

  /** Branch probe cardinality uses City statistics on the main path, not Person's index. */
  @Test
  public void estimateUpstreamCardinality_targetClassSelectivity() throws Exception {
    var ctx = sourceNameIndexContext();
    var diamond = buildDiamondSchedule();
    var method = MatchExecutionPlanner.class.getDeclaredMethod("estimateUpstreamCardinality",
        List.class, int.class, List.class, Map.class, Map.class, Map.class, CommandContext.class);
    method.setAccessible(true);
    assertThat((long) method.invoke(null, diamond.schedule, 3, diamond.schedule.subList(2, 4),
        Map.of("a", "Person", "b", "City"), Map.of("b", indexedNameFilter(ctx)), Map.of(), ctx))
        .isEqualTo(100_010);
  }

  /** Semi-join edges do not multiply outer rows, but inner-join edges still produce bindings. */
  @Test
  public void detachedOuterEstimate_excludesSemiJoinBranchEdges() {
    GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.setValue(2.0);
    var diamond = buildDiamondSchedule();
    var pattern = buildPattern("a", "b", "c", "d");
    var ctx = buildMockContext("Person", 100);
    var semiJoinEdges = Set.of(diamond.schedule.get(2).edge, diamond.schedule.get(3).edge);
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, diamond.schedule,
        Map.of("a", 1L), Map.of(), Map.of(), ctx, semiJoinEdges)).isEqualTo(OptionalLong.of(4));
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, diamond.schedule,
        Map.of("a", 1L), Map.of(), Map.of(), ctx)).isEqualTo(OptionalLong.of(8));
  }

  /** Negative minimum disables both guards, but never disables build eligibility. */
  @Test
  public void detachedCostGuards_negativeMinimumBypassesGuards() {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_UPSTREAM_MIN.setValue(-1L);
    assertThat(MatchExecutionPlanner.detachedHashCostWins(OptionalLong.of(1), 100,
        OptionalDouble.of(10))).isTrue();
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of("person", "Person"), Map.of(),
        Map.of(), ctx, buildPattern("person"), OptionalLong.of(1))).isTrue();
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of(), Map.of(), Map.of(), ctx,
        buildPattern("person"), OptionalLong.of(1))).isFalse();
    var diamond = buildDiamondSchedule();
    assertThat(MatchExecutionPlanner.identifyHashJoinBranches(diamond.schedule, Set.of("a", "d"),
        Map.of("a", "Person", "d", "Person"), Map.of(), Map.of(), ctx)).hasSize(1);
  }

  /** Either positive recursive marker makes the outer estimate unknown and keeps eligible hash. */
  @Test
  public void detachedOuterEstimate_recursivePositiveEdgeIsUnknown() throws Exception {
    var ctx = buildMockContext("Person", 100);
    for (var condition : List.of("while:(true)", "maxDepth:3")) {
      var exp = parseExpression("{as:a}.out(){as:b, " + condition + "}");
      var pattern = new Pattern();
      pattern.addExpression(exp);
      var schedule = List.of(new EdgeTraversal(pattern.aliasToNode.get("a").out.iterator().next(),
          true));
      var outer = MatchExecutionPlanner.estimateDetachedOuterRows(pattern, schedule,
          Map.of("a", 1L), Map.of("a", "Person", "b", "Person"), Map.of(), ctx);
      assertThat(outer).isEmpty();
      assertThat(MatchExecutionPlanner.canUseHashJoin(buildNotExpression("b", null, "x", null),
          Map.of("b", "Person"), Map.of(), Map.of(), ctx, pattern, outer)).isTrue();
    }
  }

  /** Triangle closing edges verify a bound alias: root 1 times two expanding hops gives 4. */
  @Test
  public void detachedOuterEstimate_triangleClosingEdgeDoesNotExpandOrRefilter() {
    GlobalConfiguration.QUERY_STATS_DEFAULT_FAN_OUT.setValue(2.0);
    var pattern = buildPattern("a", "b", "c");
    var a = pattern.aliasToNode.get("a");
    var b = pattern.aliasToNode.get("b");
    var c = pattern.aliasToNode.get("c");
    a.addEdge(new SQLMatchPathItem(-1), b);
    b.addEdge(new SQLMatchPathItem(-1), c);
    c.addEdge(new SQLMatchPathItem(-1), a);
    var schedule = List.of(new EdgeTraversal(a.out.iterator().next(), true),
        new EdgeTraversal(b.out.iterator().next(), true),
        new EdgeTraversal(c.out.iterator().next(), true));
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, schedule, Map.of("a", 1L),
        Map.of(), Map.of("a", buildWhereClause("flag=true", false)),
        buildMockContext("Person", 100))).isEqualTo(OptionalLong.of(4));
  }

  /** Nineteen default-fan-out hops saturate candidate work without any recursive markers. */
  @Test
  public void detachedWalkCost_saturationIsUnknown() throws Exception {
    var exp = parseExpression("{as:a}" + ".out(){}".repeat(19));
    assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp, Map.of("a", "Person"),
        buildMockContext("Person", 100))).isEmpty();
  }

  /** A saturated eligible walk bypasses Guard 1 even for outer 1 below minimum 5. */
  @Test
  public void detachedWalkCost_saturationKeepsHash() throws Exception {
    GlobalConfiguration.QUERY_MATCH_HASH_JOIN_THRESHOLD.setValue(Long.MAX_VALUE);
    var exp = parseExpression("{as:a}" + ".out(){}".repeat(19));
    assertThat(MatchExecutionPlanner.canUseHashJoin(exp, Map.of("a", "Person"), Map.of(),
        Map.of(), buildMockContext("Person", 100), buildPattern("a"), OptionalLong.of(1)))
        .isTrue();
  }

  /**
   * T1: Both edge-to-vertex directions use Person's index and keep the NOT hash eligible.
   * Guards against source-class scoring at a342dc6356 and unresolved edge classes at 3921d87ed9.
   */
  @Test
  public void notCardinality_edgeToVertexChainUsesReachedClass() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    for (var path : List.of(".inE('Lives'){as:e}.outV()", ".outE('Owns'){as:e}.inV()")) {
      var exp = parseExpression("{as:a}" + path + "{as:b, where:(name='X')}");
      exp.getItems().get(1).getFilter().setFilter(indexedNameFilter(ctx));
      var classes = Map.of("a", "City");
      assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
          classes, Map.of(), Map.of(), ctx)).as(path).isEqualTo(1_010);
      assertThat(MatchExecutionPlanner.canUseHashJoin(exp, classes, Map.of(), Map.of(), ctx,
          buildPattern("a"))).as(path).isTrue();
    }
  }

  /** T2: A shared edge alias also supplies the reached class, not the carried fan-out class. */
  @Test
  public void notCardinality_edgeToVertexChainUsesReachedClass_sharedEdgeAlias() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    for (var edgeClass : List.of("Lives", "Owns")) {
      var path = "Lives".equals(edgeClass) ? ".inE('Lives'){as:e}.outV()"
          : ".outE('Owns'){as:e}.inV()";
      var exp = parseExpression("{as:a}" + path + "{as:b, where:(name='X')}");
      exp.getItems().get(1).getFilter().setFilter(indexedNameFilter(ctx));
      var classes = Map.of("a", "City", "e", edgeClass);
      assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
          classes, Map.of(), Map.of(), ctx)).as(edgeClass).isEqualTo(1_010);
      assertThat(MatchExecutionPlanner.canUseHashJoin(exp, classes, Map.of(), Map.of(), ctx,
          buildPattern("a", "e"))).as(edgeClass).isTrue();
    }
  }

  /**
   * T3: Person's index discounts only the third hop, giving 1000 + 10000 + 100 visits.
   * Guards against source-class scoring at 85fbc6903b and unresolved edge classes at 3921d87ed9.
   */
  @Test
  public void walkCost_edgeToVertexChainUsesReachedClass() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    for (var path : List.of(".inE('Lives'){as:e}.outV()", ".outE('Owns'){as:e}.inV()")) {
      var exp = parseExpression("{as:a}" + path + "{as:b, where:(name='X')}.out(){as:c}");
      exp.getItems().get(1).getFilter().setFilter(indexedNameFilter(ctx));
      assertThat(MatchExecutionPlanner.estimateDetachedWalkCost(exp, Map.of("a", "City"), ctx)
          .orElseThrow()).as(path).isCloseTo(11_100, within(1e-6));
    }
  }

  /** T6: Outer rows resolve an unlisted edge alias even when its hop has no WHERE filter. */
  @Test
  public void outerRows_chainResolvesUnlistedEdgeAlias() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    var exp = parseExpression("{as:a}.inE('Lives'){as:e}.outV(){as:b, where:(name='X')}");
    var pattern = new Pattern();
    pattern.addExpression(exp);
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern, forwardSchedule(exp),
        Map.of("a", 101L), Map.of("a", "City"), Map.of("b", indexedNameFilter(ctx)), ctx))
        .isEqualTo(OptionalLong.of(1_010));
  }

  /** T7: Branch build rows use Person's index and exclude the final consistency-check hop. */
  @Test
  public void branchCardinality_chainResolvesUnlistedEdgeAlias() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    var exp = parseExpression("{as:a}.inE('Lives'){as:e}.outV(){as:c}.out(){as:d}");
    var method = MatchExecutionPlanner.class.getDeclaredMethod("estimateBranchCardinality",
        String.class, List.class, Map.class, Map.class, Map.class, CommandContext.class);
    method.setAccessible(true);
    assertThat((long) method.invoke(null, "a", forwardSchedule(exp), Map.of("a", "City"),
        Map.of("c", indexedNameFilter(ctx)), Map.of(), ctx)).isEqualTo(1_010);
  }

  /** T8: Branch fan-out uses the inferred Person filter, giving 1000 * 10 * 0.001 = 10. */
  @Test
  public void branchFanOut_chainResolvesUnlistedEdgeAlias() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    var exp = parseExpression("{as:a}.inE('Lives'){as:e}.outV(){as:c}.out(){as:d}");
    var method = MatchExecutionPlanner.class.getDeclaredMethod("estimateBranchFanOut",
        List.class, String.class, Map.class, Map.class, CommandContext.class);
    method.setAccessible(true);
    assertThat((double) method.invoke(null, forwardSchedule(exp), "a", Map.of("a", "City"),
        Map.of("c", indexedNameFilter(ctx)), ctx)).isEqualTo(10.0);
  }

  /** T9: Upstream rows infer Person on the main chain and skip the separate branch edges. */
  @Test
  public void upstream_chainResolvesUnlistedEdgeAlias() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    var main = parseExpression("{as:a}.inE('Lives'){as:e}.outV(){as:b}.out(){as:d}");
    var branch = parseExpression("{as:a}.out(){as:c}.out(){as:d}");
    var schedule = new ArrayList<>(forwardSchedule(main));
    schedule.addAll(forwardSchedule(branch));
    var method = MatchExecutionPlanner.class.getDeclaredMethod("estimateUpstreamCardinality",
        List.class, int.class, List.class, Map.class, Map.class, Map.class, CommandContext.class);
    method.setAccessible(true);
    assertThat((long) method.invoke(null, schedule, 4, schedule.subList(3, 5),
        Map.of("a", "City"), Map.of("b", indexedNameFilter(ctx)), Map.of(), ctx))
        .isEqualTo(10_100);
  }

  /**
   * T4: An unlabeled edge has no endpoint class, so its vertex filter uses default selectivity.
   * Guards against a342dc6356, which incorrectly narrows rows using the source Person index.
   */
  @Test
  public void notCardinality_unknownEdgeClassUsesDefault() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    var exp = parseExpression("{as:a}.outE(){as:e}.inV(){as:b, where:(name='X')}");
    exp.getItems().get(1).getFilter().setFilter(indexedNameFilter(ctx));
    assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
        Map.of("a", "Person"), Map.of(), Map.of(), ctx)).isEqualTo(100_010);
  }

  /** T5: Either recursive marker prevents propagating the edge class into the next vertex hop. */
  @Test
  public void notCardinality_recursiveEdgeDoesNotResolveVertex() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    for (var marker : List.of("while:($depth<2)", "maxDepth:2")) {
      for (var path : List.of(".inE('Lives'){as:e, " + marker + "}.outV()",
          ".outE('Owns'){as:e, " + marker + "}.inV()")) {
        var exp = parseExpression("{as:a}" + path + "{as:b, where:(name='X')}");
        exp.getItems().get(1).getFilter().setFilter(indexedNameFilter(ctx));
        assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
            Map.of("a", "City"), Map.of(), Map.of(), ctx)).as(path).isEqualTo(101_000);
      }
    }
  }

  /**
   * T10: Reversed outE reaches the out endpoint Person and uses its index, leaving 100 rows.
   * Guards against 85fbc6903b, which uses the edge's default selectivity and gives 10000 rows.
   */
  @Test
  public void outerRows_reversedOutEUsesOutEndpoint() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    var exp = parseExpression("{as:a}.outE('Lives'){as:e}");
    var pattern = new Pattern();
    pattern.addExpression(exp);
    var edge = pattern.aliasToNode.get("a").out.iterator().next();
    assertThat(MatchExecutionPlanner.estimateDetachedOuterRows(pattern,
        List.of(new EdgeTraversal(edge, false)), Map.of("e", 100_001L), Map.of("e", "Lives"),
        Map.of("a", indexedNameFilter(ctx)), ctx)).isEqualTo(OptionalLong.of(100));
  }

  /** R1: A recursive hop's own filter uses its inferred Person class, not default selectivity. */
  @Test
  public void notCardinality_recursiveHopOwnFilterUsesInferredClass() throws Exception {
    var ctx = sourceNameIndexContext();
    livesOwnsSchema(ctx);
    for (var marker : List.of("while:($depth<2)", "maxDepth:2")) {
      var exp = parseExpression("{as:a}.out('Owns'){as:b, " + marker + ", where:(name='X')}");
      exp.getItems().getFirst().getFilter().setFilter(indexedNameFilter(ctx));
      assertThat(MatchExecutionPlanner.estimateNotPatternCardinality(exp,
          Map.of("a", "City"), Map.of(), Map.of(), ctx)).as(marker).isEqualTo(101);
    }
  }

  // ── Test helpers ────────────────────────────────────────────────────────

  /** Forward schedule in path order, including each path item's real parsed method. */
  private static List<EdgeTraversal> forwardSchedule(SQLMatchExpression exp) {
    var pattern = new Pattern();
    pattern.addExpression(exp);
    var schedule = new ArrayList<EdgeTraversal>();
    var source = pattern.aliasToNode.get(exp.getOrigin().getAlias());
    for (var item : exp.getItems()) {
      var edge = source.out.stream().filter(candidate -> candidate.item == item)
          .findFirst().orElseThrow();
      schedule.add(new EdgeTraversal(edge, true));
      source = edge.in;
    }
    return schedule;
  }

  /** Lives links Person to City. Owns links City to Person. Only Person has a name index. */
  private static void livesOwnsSchema(CommandContext ctx) {
    var db = ctx.getDatabaseSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();
    var person = schema.getClassInternal("Person");
    var city = schema.getClassInternal("City");
    when(person.getName()).thenReturn("Person");
    for (var edgeName : List.of("Lives", "Owns")) {
      var edge = mock(SchemaClassInternal.class);
      var out = mock(SchemaPropertyInternal.class);
      var in = mock(SchemaPropertyInternal.class);
      when(schema.getClassInternal(edgeName)).thenReturn(edge);
      when(schema.existsClass(edgeName)).thenReturn(true);
      when(edge.getName()).thenReturn(edgeName);
      when(edge.approximateCount(db)).thenReturn(100_000L);
      when(edge.getPropertyInternal("out")).thenReturn(out);
      when(edge.getPropertyInternal("in")).thenReturn(in);
      when(out.getLinkedClass()).thenReturn("Lives".equals(edgeName) ? person : city);
      when(in.getLinkedClass()).thenReturn("Lives".equals(edgeName) ? city : person);
    }
  }

  private static SQLMatchExpression parseExpression(String pattern) throws Exception {
    return ((SQLMatchStatement) new YouTrackDBSql(new ByteArrayInputStream(
        ("MATCH " + pattern + " RETURN a").getBytes(StandardCharsets.UTF_8))).parse())
        .getMatchExpressions().getFirst();
  }

  /** Use the single binary condition shape accepted by the histogram estimator. */
  private static SQLWhereClause indexedNameFilter(CommandContext ctx) throws Exception {
    var parsed = parseExpression("{as:a}.out(){as:b, where:(name='X')}")
        .getItems().getFirst().getFilter().getFilter();
    var where = new SQLWhereClause(-1);
    where.setBaseExpression(parsed.flatten(ctx, null).getFirst().getSubBlocks().getFirst());
    return where;
  }

  /** Named schema classes with counts, but no endpoint LINK properties or indexes. */
  private static CommandContext classCountsWithoutLinks(Map<String, Long> counts) {
    var ctx = buildMockContext("Person", counts.get("Person"));
    var db = ctx.getDatabaseSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();
    for (var entry : counts.entrySet()) {
      var clazz = mock(SchemaClassInternal.class);
      when(schema.getClassInternal(entry.getKey())).thenReturn(clazz);
      when(schema.existsClass(entry.getKey())).thenReturn(true);
      when(clazz.getName()).thenReturn(entry.getKey());
      when(clazz.approximateCount(db)).thenReturn(entry.getValue());
    }
    return ctx;
  }

  /** Distinct classes with an indexed source name and an unindexed target name. */
  private static CommandContext sourceNameIndexContext() throws Exception {
    var ctx = buildMockContext("Person", 10_000);
    var db = ctx.getDatabaseSession();
    var schema = db.getMetadata().getImmutableSchemaSnapshot();
    var person = schema.getClassInternal("Person");
    var city = mock(SchemaClassInternal.class);
    when(schema.getClassInternal("City")).thenReturn(city);
    when(city.getName()).thenReturn("City");
    when(city.approximateCount(db)).thenReturn(100L);
    when(schema.existsClass("City")).thenReturn(true);
    var name = mock(SchemaPropertyInternal.class);
    when(person.getProperty("name")).thenReturn(name);
    var index = mock(Index.class);
    var definition = mock(IndexDefinition.class);
    when(index.getDefinition()).thenReturn(definition);
    when(index.getName()).thenReturn("Person.name");
    when(index.getType()).thenReturn("NOTUNIQUE");
    when(index.canBeUsedInEqualityOperators()).thenReturn(true);
    when(definition.getClassName()).thenReturn("Person");
    when(definition.getProperties()).thenReturn(List.of("name"));
    when(definition.getFieldsToIndex()).thenReturn(List.of("name"));
    when(index.getStatistics(db)).thenReturn(new IndexStatistics(10_000, 10_000, 0));
    when(person.getIndexesInternal()).thenReturn(Set.of(index));
    // Pin the fixture's actual index lookup, not a mocked selectivity return.
    var where = indexedNameFilter(ctx);
    assertThat(TraversalPreFilterHelper.findIndexForFilter(where, "Person", ctx)).isNotNull();
    assertThat(TraversalPreFilterHelper.findIndexForFilter(where, "City", ctx)).isNull();
    return ctx;
  }

  /**
   * Builds a standard diamond pattern a→b→d, a→c→d with schedule
   * [a→b, b→d, a→c, c→d(check)]. Used by multiple identifyHashJoinBranches tests.
   */
  private static DiamondSchedule buildDiamondSchedule() {
    var nodeA = new PatternNode();
    nodeA.alias = "a";
    var nodeB = new PatternNode();
    nodeB.alias = "b";
    var nodeC = new PatternNode();
    nodeC.alias = "c";
    var nodeD = new PatternNode();
    nodeD.alias = "d";

    nodeA.addEdge(new SQLMatchPathItem(-1), nodeB);
    var edgeAB = nodeA.out.iterator().next();
    nodeB.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeBD = nodeB.out.iterator().next();
    nodeA.addEdge(new SQLMatchPathItem(-1), nodeC);
    PatternEdge edgeAC = null;
    for (var e : nodeA.out) {
      if (e.in == nodeC) {
        edgeAC = e;
        break;
      }
    }
    nodeC.addEdge(new SQLMatchPathItem(-1), nodeD);
    var edgeCD = nodeC.out.iterator().next();

    var schedule = List.of(
        new EdgeTraversal(edgeAB, true),
        new EdgeTraversal(edgeBD, true),
        new EdgeTraversal(edgeAC, true),
        new EdgeTraversal(edgeCD, true));

    return new DiamondSchedule(nodeA, nodeB, nodeC, nodeD, schedule);
  }

  /**
   * Holds the nodes and schedule for a diamond pattern a→b→d, a→c→d.
   */
  private record DiamondSchedule(
      PatternNode nodeA, PatternNode nodeB, PatternNode nodeC, PatternNode nodeD,
      List<EdgeTraversal> schedule) {
  }

  /**
   * Builds a simple NOT expression: {@code {as: originAlias, where: originWhere}
   * .out(){as: leafAlias, where: leafWhere}}.
   */
  private static SQLMatchExpression buildNotExpression(
      String originAlias,
      SQLWhereClause originWhere,
      String leafAlias,
      SQLWhereClause leafWhere) {
    var exp = new SQLMatchExpression(-1);

    var origin = new SQLMatchFilter(-1);
    origin.setAlias(originAlias);
    if (originWhere != null) {
      origin.setFilter(originWhere);
    }
    exp.setOrigin(origin);

    var item = new SQLMatchPathItem(-1);
    var leafFilter = new SQLMatchFilter(-1);
    leafFilter.setAlias(leafAlias);
    if (leafWhere != null) {
      leafFilter.setFilter(leafWhere);
    }
    item.setFilter(leafFilter);
    exp.setItems(List.of(item));

    return exp;
  }

  /**
   * Builds a minimal {@link Pattern} with the given alias names as nodes.
   */
  private static Pattern buildPattern(String... aliases) {
    var pattern = new Pattern();
    for (var alias : aliases) {
      var node = new PatternNode();
      node.alias = alias;
      pattern.aliasToNode.put(alias, node);
    }
    return pattern;
  }

  /**
   * Builds a stub {@link SQLWhereClause} that produces the given string representation
   * and optionally reports a $parent reference.
   *
   * <p>Uses a real {@link SQLWhereClause} with a mock-free approach: constructs
   * a WHERE clause whose {@code toString()} contains the given text and whose
   * {@code refersToParent()} returns the specified value.
   */
  private static SQLWhereClause buildWhereClause(String text, boolean refersToParent) {
    return new SQLWhereClause(-1) {
      @Override
      public String toString() {
        return text;
      }

      @Override
      public boolean refersToParent() {
        return refersToParent;
      }
    };
  }

  /**
   * Builds a stub {@link SQLExpression} whose {@code toString()} returns the given text.
   */
  private static SQLExpression buildExpression(String text) {
    return new SQLExpression(-1) {
      @Override
      public String toString() {
        return text;
      }
    };
  }

  /**
   * Builds a mock {@link CommandContext} with a schema containing the given class
   * with the specified approximate record count.
   */
  private static CommandContext buildMockContext(String className, long recordCount) {
    var db = mock(DatabaseSessionEmbedded.class);
    var metadata = mock(MetadataDefault.class);
    var schema = mock(ImmutableSchema.class);
    var schemaClass = mock(SchemaClassInternal.class);

    when(db.getMetadata()).thenReturn(metadata);
    when(metadata.getImmutableSchemaSnapshot()).thenReturn(schema);
    when(schema.existsClass(className)).thenReturn(true);
    when(schema.getClassInternal(className)).thenReturn(schemaClass);
    when(schemaClass.approximateCount(db)).thenReturn(recordCount);

    var ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(db);
    return ctx;
  }
}
