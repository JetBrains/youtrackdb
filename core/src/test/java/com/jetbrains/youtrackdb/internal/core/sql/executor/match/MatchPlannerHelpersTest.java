package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchPathItem;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.util.List;
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
}
