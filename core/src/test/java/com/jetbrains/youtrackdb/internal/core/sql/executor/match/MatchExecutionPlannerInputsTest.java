package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSkip;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLUnwind;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for the {@link MatchPlanInputs} record and the additive
 * {@link MatchExecutionPlanner#MatchExecutionPlanner(MatchPlanInputs)} constructor introduced
 * for non-SQL front-ends.
 *
 * <p>Covers:
 * <ul>
 *   <li>Record compact-ctor null normalisation (null collections become empty; null pattern
 *       throws NPE).</li>
 *   <li>Planner ctor null-input handling.</li>
 *   <li>Defensive-copy independence of {@code aliasClasses} / {@code aliasFilters} /
 *       {@code aliasRids} against caller mutations after construction &mdash; the planner mutates
 *       these maps internally during planning.</li>
 *   <li>Field propagation: {@code matchExpressions}, {@code returnItems}, the four return
 *       flags, {@code returnDistinct}, single-value AST fields ({@code skip}, {@code limit},
 *       {@code groupBy}, {@code orderBy}, {@code unwind}), and the pattern reference.</li>
 *   <li>Smoke construction with empty inputs and with single-alias inputs.</li>
 * </ul>
 *
 * <p>Defensive-copy independence is verified by reflecting on the private working maps
 * because the planner exposes no accessors for them; this is a test-only access pattern
 * that avoids expanding the public API surface.
 */
public class MatchExecutionPlannerInputsTest {

  // ─────────────────────── MatchPlanInputs record — compact ctor ────────────────────────

  /**
   * Verifies the compact constructor rejects a null pattern with a clear message. The pattern
   * is the only required input; without it the planner has nothing to plan over.
   */
  @Test
  public void record_pattern_null_throwsNpe() {
    assertThatNullPointerException()
        .isThrownBy(
            () -> new MatchPlanInputs(
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, false, false, false, false, false))
        .withMessageContaining("pattern");
  }

  /**
   * Verifies the compact constructor normalises every nullable collection input to an empty
   * collection. This lets non-SQL front-ends omit fields they don't populate (e.g. the
   * minimal {@code g.V()} translation has no NOT patterns, no return aliases, no group-by).
   */
  @Test
  public void record_nullCollections_normalizedToEmpty() {
    var inputs =
        new MatchPlanInputs(
            new Pattern(), null, null, null, null, null, null, null, null, null, null, null, null,
            null, false, false, false, false, false);
    assertThat(inputs.aliasClasses()).isEmpty();
    assertThat(inputs.aliasFilters()).isEmpty();
    assertThat(inputs.aliasRids()).isEmpty();
    assertThat(inputs.matchExpressions()).isEmpty();
    assertThat(inputs.notMatchExpressions()).isEmpty();
    assertThat(inputs.returnItems()).isEmpty();
    assertThat(inputs.returnAliases()).isEmpty();
    assertThat(inputs.returnNestedProjections()).isEmpty();
  }

  /**
   * Verifies returnAliases preserves null entries — the planner's existing AST ctor populates
   * a list where a {@code null} entry means "no user-specified alias for this return item",
   * and the corresponding {@link SQLNestedProjection} entry uses the same convention. Records
   * built via {@link List#of()} would reject nulls, so the compact ctor must use a list that
   * tolerates them when the caller provides one.
   */
  @Test
  public void record_returnAliases_nullEntriesPreserved() {
    var aliases = new ArrayList<SQLIdentifier>();
    aliases.add(new SQLIdentifier("a"));
    aliases.add(null);
    aliases.add(new SQLIdentifier("c"));

    var nestedProjections = new ArrayList<SQLNestedProjection>();
    nestedProjections.add(null);
    nestedProjections.add(null);
    nestedProjections.add(null);

    var inputs =
        new MatchPlanInputs(
            new Pattern(),
            null,
            null,
            null,
            null,
            null,
            null,
            aliases,
            nestedProjections,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false);

    assertThat(inputs.returnAliases()).hasSize(3);
    assertThat(inputs.returnAliases().get(1)).isNull();
    assertThat(inputs.returnNestedProjections()).hasSize(3);
    assertThat(inputs.returnNestedProjections()).containsOnlyNulls();
  }

  // ──────────────────── Planner ctor — null handling and propagation ────────────────────

  /** Verifies the planner ctor rejects a null inputs record with a clear message. */
  @Test
  public void plannerCtor_inputs_null_throwsNpe() {
    assertThatNullPointerException()
        .isThrownBy(() -> new MatchExecutionPlanner((MatchPlanInputs) null))
        .withMessageContaining("inputs");
  }

  /**
   * Smoke test: constructing the planner from a minimal record (just a Pattern, all other
   * fields null/default) succeeds and propagates the pattern reference plus default values
   * for everything else. This is the path the strategy takes for bare {@code g.V()}.
   */
  @Test
  public void plannerCtor_smoke_emptyInputs() {
    var pattern = new Pattern();
    var inputs =
        new MatchPlanInputs(
            pattern, null, null, null, null, null, null, null, null, null, null, null, null, null,
            false, false, false, false, false);

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(getPattern(planner)).isSameAs(pattern);
    assertThat(planner.matchExpressions).isEmpty();
    assertThat(planner.notMatchExpressions).isEmpty();
    assertThat(planner.returnItems).isEmpty();
    assertThat(planner.returnAliases).isEmpty();
    assertThat(planner.returnNestedProjections).isEmpty();
    assertThat(planner.limit).isNull();
    assertThat(planner.skip).isNull();
    assertThat(planner.returnElements).isFalse();
    assertThat(planner.returnPaths).isFalse();
    assertThat(planner.returnPatterns).isFalse();
    assertThat(planner.returnPathElements).isFalse();
  }

  /**
   * Smoke test: constructing the planner from a record carrying a single aliased node
   * (Pattern + aliasClasses with one entry) succeeds and the alias propagates into the
   * planner's working map. Mirrors the {@code g.V()} → {@code MATCH {as: v}} shape.
   */
  @Test
  public void plannerCtor_smoke_singleAliasInputs() {
    var pattern = new Pattern();
    var aliasClasses = new HashMap<String, String>();
    aliasClasses.put("v", "Person");

    var inputs =
        new MatchPlanInputs(
            pattern,
            aliasClasses,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false);

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(getAliasClasses(planner)).containsEntry("v", "Person");
  }

  /**
   * Verifies that a fully-populated record (every collection non-null) propagates without
   * loss. Exercises the non-null branch of every collection ternary in the compact
   * constructor and the corresponding shallow-copy in the planner ctor.
   */
  @Test
  public void plannerCtor_fullyPopulatedInputs() {
    var pattern = new Pattern();
    var notExpr = new SQLMatchExpression(-1);

    var inputs =
        new MatchPlanInputs(
            pattern,
            Map.of("a", "Person"),
            Map.of("a", new SQLWhereClause(-1)),
            Map.of("a", new SQLRid(-1)),
            List.of(new SQLMatchExpression(-1)),
            List.of(notExpr),
            List.of(new SQLExpression(-1)),
            List.of(new SQLIdentifier("ret")),
            new ArrayList<>(Arrays.asList((SQLNestedProjection) null)),
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false);

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.notMatchExpressions).containsExactly(notExpr);
    assertThat(planner.returnAliases).hasSize(1);
    assertThat(planner.returnNestedProjections).hasSize(1).containsOnlyNulls();
    assertThat(getAliasFilters(planner)).hasSize(1);
    assertThat(getAliasRids(planner)).hasSize(1);
  }

  // ─────────────── Planner ctor — defensive-copy independence (3 maps) ──────────────────

  /**
   * Verifies that mutating the caller's {@code aliasClasses} map after construction does not
   * affect the planner's internal copy. The planner mutates {@code aliasClasses} during
   * class inference into chained edges, so a shared reference would leak planner-internal
   * mutations back to the caller (or vice versa).
   */
  @Test
  public void plannerCtor_aliasClasses_callerMutationDoesNotAffectPlanner() {
    var caller = new HashMap<String, String>();
    caller.put("a", "Person");
    var planner =
        new MatchExecutionPlanner(
            inputsWithAliasClasses(caller));

    caller.put("b", "City"); // Caller mutates after construction.
    caller.remove("a");

    var plannerView = getAliasClasses(planner);
    assertThat(plannerView).containsExactly(java.util.Map.entry("a", "Person"));
  }

  /**
   * Same as above but for {@code aliasFilters}. The planner's NOT-IN anti-join detection
   * strips entries from this map during planning, so a shared reference would corrupt the
   * caller's record on the second {@code createExecutionPlan} invocation.
   */
  @Test
  public void plannerCtor_aliasFilters_callerMutationDoesNotAffectPlanner() {
    var caller = new HashMap<String, SQLWhereClause>();
    var w = new SQLWhereClause(-1);
    caller.put("a", w);
    var planner = new MatchExecutionPlanner(inputsWithAliasFilters(caller));

    caller.clear();

    assertThat(getAliasFilters(planner)).containsEntry("a", w);
  }

  /**
   * Same as above but for {@code aliasRids}. The minimal {@code (Pattern, Map, Map)} ctor
   * defaults this to {@code Map.of()}; the new ctor accepts a real map and must defensive-
   * copy it so that a translator that later reuses its own map for a second traversal does
   * not see entries the planner would attach during scheduling.
   */
  @Test
  public void plannerCtor_aliasRids_callerMutationDoesNotAffectPlanner() {
    var caller = new HashMap<String, SQLRid>();
    var rid = new SQLRid(-1);
    caller.put("a", rid);
    var planner = new MatchExecutionPlanner(inputsWithAliasRids(caller));

    caller.clear();

    assertThat(getAliasRids(planner)).containsEntry("a", rid);
  }

  // ─────────────────────── Planner ctor — list / flag propagation ───────────────────────

  /**
   * Verifies {@code matchExpressions} are propagated into the planner's protected list field.
   * The list is shallow-copied (caller-supplied AST elements are owned by the planner after
   * construction).
   */
  @Test
  public void plannerCtor_propagatesMatchExpressions() {
    var pattern = new Pattern();
    var expr1 = new SQLMatchExpression(-1);
    var expr2 = new SQLMatchExpression(-1);
    var matchExprs = Arrays.asList(expr1, expr2);

    var inputs =
        new MatchPlanInputs(
            pattern,
            null,
            null,
            null,
            matchExprs,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false);

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.matchExpressions).containsExactly(expr1, expr2);
  }

  /** Verifies {@code returnItems} are propagated into the planner's protected list field. */
  @Test
  public void plannerCtor_propagatesReturnItems() {
    var pattern = new Pattern();
    var item = new SQLExpression(-1);

    var inputs =
        new MatchPlanInputs(
            pattern,
            null,
            null,
            null,
            null,
            null,
            List.of(item),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false,
            false,
            false,
            false,
            false);

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.returnItems).containsExactly(item);
  }

  /**
   * Verifies the four return-mode boolean flags ({@code returnElements}, {@code returnPaths},
   * {@code returnPatterns}, {@code returnPathElements}) and {@code returnDistinct} all
   * propagate. These map onto the SQL {@code RETURN $elements} / {@code $paths} /
   * {@code $patterns} / {@code $pathElements} variants and the {@code RETURN DISTINCT}
   * keyword respectively.
   */
  @Test
  public void plannerCtor_propagatesReturnFlags() {
    var pattern = new Pattern();
    var inputs =
        new MatchPlanInputs(
            pattern, null, null, null, null, null, null, null, null, null, null, null, null, null,
            true, true, true, true, true);

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.returnElements).isTrue();
    assertThat(planner.returnPaths).isTrue();
    assertThat(planner.returnPatterns).isTrue();
    assertThat(planner.returnPathElements).isTrue();
    assertThat(getReturnDistinct(planner)).isTrue();
  }

  /**
   * Verifies the single-value AST fields ({@code limit}, {@code skip}, {@code groupBy},
   * {@code orderBy}, {@code unwind}) propagate as references (no copy). The translator owns
   * these AST nodes and hands them off; the planner takes ownership.
   */
  @Test
  public void plannerCtor_propagatesSingleValueAstFields() {
    var pattern = new Pattern();
    var limit = new SQLLimit(-1);
    var skip = new SQLSkip(-1);
    var groupBy = new SQLGroupBy(-1);
    var orderBy = new SQLOrderBy(-1);
    var unwind = new SQLUnwind(-1);

    var inputs =
        new MatchPlanInputs(
            pattern,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            groupBy,
            orderBy,
            unwind,
            limit,
            skip,
            false,
            false,
            false,
            false,
            false);

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.limit).isSameAs(limit);
    assertThat(planner.skip).isSameAs(skip);
    assertThat(getGroupBy(planner)).isSameAs(groupBy);
    assertThat(getOrderBy(planner)).isSameAs(orderBy);
    assertThat(getUnwind(planner)).isSameAs(unwind);
  }

  // ──────────────────────────────────── Test helpers ────────────────────────────────────

  private static MatchPlanInputs inputsWithAliasClasses(Map<String, String> aliasClasses) {
    return new MatchPlanInputs(
        new Pattern(),
        aliasClasses,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        false);
  }

  private static MatchPlanInputs inputsWithAliasFilters(Map<String, SQLWhereClause> aliasFilters) {
    return new MatchPlanInputs(
        new Pattern(),
        null,
        aliasFilters,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        false);
  }

  private static MatchPlanInputs inputsWithAliasRids(Map<String, SQLRid> aliasRids) {
    return new MatchPlanInputs(
        new Pattern(),
        null,
        null,
        aliasRids,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        false);
  }

  /// Reflective field access — used because the working maps and the three final AST fields
  /// are private and the public API surface is intentionally not expanded for testing.
  @SuppressWarnings("unchecked")
  private static <T> T readField(Object target, String name) {
    try {
      Field f = MatchExecutionPlanner.class.getDeclaredField(name);
      f.setAccessible(true);
      return (T) f.get(target);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError("Failed to read " + name + " via reflection", e);
    }
  }

  private static Pattern getPattern(MatchExecutionPlanner planner) {
    return readField(planner, "pattern");
  }

  private static Map<String, String> getAliasClasses(MatchExecutionPlanner planner) {
    return readField(planner, "aliasClasses");
  }

  private static Map<String, SQLWhereClause> getAliasFilters(MatchExecutionPlanner planner) {
    return readField(planner, "aliasFilters");
  }

  private static Map<String, SQLRid> getAliasRids(MatchExecutionPlanner planner) {
    return readField(planner, "aliasRids");
  }

  private static boolean getReturnDistinct(MatchExecutionPlanner planner) {
    return (Boolean) readField(planner, "returnDistinct");
  }

  private static SQLGroupBy getGroupBy(MatchExecutionPlanner planner) {
    return readField(planner, "groupBy");
  }

  private static SQLOrderBy getOrderBy(MatchExecutionPlanner planner) {
    return readField(planner, "orderBy");
  }

  private static SQLUnwind getUnwind(MatchExecutionPlanner planner) {
    return readField(planner, "unwind");
  }
}
