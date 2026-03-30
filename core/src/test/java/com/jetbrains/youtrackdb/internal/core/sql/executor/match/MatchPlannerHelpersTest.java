package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.metadata.MetadataDefault;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaClassInternal;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderByItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * Unit tests for the static helper methods in {@link MatchExecutionPlanner} that support
 * hash join strategy selection for NOT patterns: {@code notPatternDependsOnMatched()} and
 * {@code findSharedAliases()}.
 *
 * <p>These helpers inspect NOT match expressions to determine whether they can be
 * independently materialized (no {@code $matched}/{@code $parent} dependency) and which
 * aliases are shared with the positive pattern (forming the join key).
 */
public class MatchPlannerHelpersTest {

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
   * estimate is 101. Expected: 101 * 10 (fan-out) = 1010.
   */
  @Test
  public void estimateNotPatternCardinality_knownOrigin_oneEdge_noFilter() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 100);

    var estimate = MatchExecutionPlanner.estimateNotPatternCardinality(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx);
    assertThat(estimate).isEqualTo(1010);
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
   * Two edges with an intermediate WHERE filter: (100+1) * 10 * 10 / 2 = 5050.
   * The filter on the second hop applies 0.5 selectivity.
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
    // (100+1) * 10 * 10 / 2 = 5050
    assertThat(estimate).isEqualTo(5050);
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
    assertThat(estimate).isEqualTo(510);
  }

  /**
   * Boundary: estimated cardinality exactly at HASH_JOIN_THRESHOLD should still be
   * eligible. Origin with 999 records: estimateRootEntries returns 999+1=1000,
   * times fan-out 10 = 10,000 == HASH_JOIN_THRESHOLD.
   */
  @Test
  public void canUseHashJoin_exactlyAtThreshold_returnsTrue() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 999);

    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx))
        .isTrue();
  }

  /**
   * Boundary: estimated cardinality one above HASH_JOIN_THRESHOLD must fall back to
   * nested-loop. Origin with 1000 records: estimateRootEntries returns 1000+1=1001,
   * times fan-out 10 = 10,010 > HASH_JOIN_THRESHOLD.
   */
  @Test
  public void canUseHashJoin_oneAboveThreshold_returnsFalse() {
    var exp = buildNotExpression("person", null, "tag", null);
    var ctx = buildMockContext("Person", 1000);

    assertThat(MatchExecutionPlanner.canUseHashJoin(
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx))
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
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx))
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
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx))
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
        exp, Map.of(), Map.of(), Map.of(), ctx))
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
        exp, Map.of("person", "Person"), Map.of(), Map.of(), ctx))
        .isFalse();
  }

  // ── collectDownstreamAliases ────────────────────────────────────────────

  /**
   * RETURN with a single dotted expression (friend.name) should detect the
   * "friend" alias as referenced downstream.
   */
  @Test
  public void collectDownstreamAliases_singleDottedReturn_detectsAlias() {
    var allAliases = Set.of("person", "friend", "tag");
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("friend.name")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("person.name"), buildExpression("tag.value")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("person.name")),
        true, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(),
        false, true, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(),
        false, false, true, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(),
        false, false, false, true,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("count(*)")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("abandonment.data")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(buildExpression("a.name")),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        List.of(),
        false, false, false, false,
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
    var result = MatchExecutionPlanner.collectDownstreamAliases(
        null,
        false, false, false, false,
        null, null, null,
        allAliases);
    assertThat(result).containsExactlyInAnyOrder("person", "friend");
  }

  // ── Test helpers ────────────────────────────────────────────────────────

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
