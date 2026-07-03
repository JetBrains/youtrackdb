package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
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
    // The three return lists are parallel and must be equal-length, so build a returnItems list
    // that matches the aliases / nested-projections lists (three return items, one with a null
    // alias, all with null nested projections).
    var items = new ArrayList<SQLExpression>();
    items.add(new SQLExpression(-1));
    items.add(new SQLExpression(-1));
    items.add(new SQLExpression(-1));

    var aliases = new ArrayList<SQLIdentifier>();
    aliases.add(new SQLIdentifier("a"));
    aliases.add(null);
    aliases.add(new SQLIdentifier("c"));

    var nestedProjections = new ArrayList<SQLNestedProjection>();
    nestedProjections.add(null);
    nestedProjections.add(null);
    nestedProjections.add(null);

    // Positional ctor (the builder normalises nulls, so null-entry lists must use it): the three
    // varying arguments are returnItems (7th), returnAliases (8th), returnNestedProjections (9th).
    var inputs =
        new MatchPlanInputs(
            new Pattern(),
            /* aliasClasses */ null,
            /* aliasFilters */ null,
            /* aliasRids */ null,
            /* matchExpressions */ null,
            /* notMatchExpressions */ null,
            items,
            aliases,
            nestedProjections,
            /* groupBy */ null,
            /* orderBy */ null,
            /* unwind */ null,
            /* limit */ null,
            /* skip */ null,
            /* returnDistinct */ false,
            /* returnElements */ false,
            /* returnPaths */ false,
            /* returnPatterns */ false,
            /* returnPathElements */ false);

    assertThat(inputs.returnItems()).hasSize(3);
    assertThat(inputs.returnAliases()).hasSize(3);
    assertThat(inputs.returnAliases().get(1)).isNull();
    assertThat(inputs.returnNestedProjections()).hasSize(3);
    assertThat(inputs.returnNestedProjections()).containsOnlyNulls();
  }

  /**
   * The compact constructor rejects return lists of unequal length: {@code returnItems},
   * {@code returnAliases}, and {@code returnNestedProjections} are parallel (entry {@code i} of
   * each describes return item {@code i}) and the planner reads them positionally, so a mismatch
   * would throw {@link IndexOutOfBoundsException} deep in planning. The record fails loudly at
   * construction instead. Here {@code returnItems} has one entry while the other two default to
   * empty.
   */
  @Test
  public void record_unequalReturnListLengths_throwsIllegalArgument() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> MatchPlanInputs.builder(new Pattern())
                .returnItems(List.of(new SQLExpression(-1)))
                .build())
        .withMessageContaining("parallel lists of equal length");
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
    var inputs = MatchPlanInputs.builder(pattern).build();

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(getPattern(planner)).isSameAs(pattern);
    assertThat(planner.matchExpressions).isEmpty();
    assertThat(planner.notMatchExpressions).isEmpty();
    assertThat(planner.returnItems).isEmpty();
    assertThat(planner.returnAliases).isEmpty();
    assertThat(planner.returnNestedProjections).isEmpty();
    // The three working maps default to empty and the single-value AST fields to null — the "and
    // default values for everything else" half of this test's contract, which the flag / limit /
    // skip assertions alone did not pin.
    assertThat(getAliasClasses(planner)).isEmpty();
    assertThat(getAliasFilters(planner)).isEmpty();
    assertThat(getAliasRids(planner)).isEmpty();
    assertThat(getGroupBy(planner)).isNull();
    assertThat(getOrderBy(planner)).isNull();
    assertThat(getUnwind(planner)).isNull();
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

    var inputs = MatchPlanInputs.builder(pattern).aliasClasses(aliasClasses).build();

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
    var proj1 = new SQLNestedProjection(-1);
    var proj2 = new SQLNestedProjection(-1);

    var inputs =
        MatchPlanInputs.builder(pattern)
            .aliasClasses(Map.of("a", "Person"))
            .aliasFilters(Map.of("a", new SQLWhereClause(-1)))
            .aliasRids(Map.of("a", new SQLRid(-1)))
            .matchExpressions(List.of(new SQLMatchExpression(-1)))
            .notMatchExpressions(List.of(notExpr))
            // Two parallel return items so the three return lists are equal-length (the record
            // enforces this); proj1/proj2 exercise the ordered non-null nested-projection copy.
            .returnItems(List.of(new SQLExpression(-1), new SQLExpression(-1)))
            .returnAliases(List.of(new SQLIdentifier("ret1"), new SQLIdentifier("ret2")))
            .returnNestedProjections(List.of(proj1, proj2))
            .build();

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.notMatchExpressions).containsExactly(notExpr);
    assertThat(planner.returnAliases).hasSize(2);
    // Two distinct non-null projections in declared order — a copy that dropped, duplicated, or
    // reordered a non-null nested projection fails here, which the earlier null-only case (a size
    // check only) could not catch.
    assertThat(planner.returnNestedProjections).containsExactly(proj1, proj2);
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

  /**
   * Same defensive-copy contract but for a caller-supplied LIST field ({@code matchExpressions}):
   * the planner ctor does {@code new ArrayList<>(inputs.matchExpressions())}, so mutating the
   * caller's list after construction must not affect the planner's copy. The map tests above
   * cover the maps; this covers the list copies the same additive ctor makes.
   */
  @Test
  public void plannerCtor_matchExpressions_callerMutationDoesNotAffectPlanner() {
    var caller = new ArrayList<SQLMatchExpression>();
    var expr = new SQLMatchExpression(-1);
    caller.add(expr);
    var planner =
        new MatchExecutionPlanner(
            MatchPlanInputs.builder(new Pattern()).matchExpressions(caller).build());

    caller.clear(); // caller mutates its own reference after construction

    assertThat(planner.matchExpressions).containsExactly(expr);
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

    var inputs = MatchPlanInputs.builder(pattern).matchExpressions(matchExprs).build();

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.matchExpressions).containsExactly(expr1, expr2);
  }

  /** Verifies {@code returnItems} are propagated into the planner's protected list field. */
  @Test
  public void plannerCtor_propagatesReturnItems() {
    var pattern = new Pattern();
    var item = new SQLExpression(-1);

    // Parallel null-entry alias / nested-projection lists keep the three return lists equal-length
    // (the record enforces this); a single return item with no alias and no nested projection is
    // the minimal valid shape.
    var inputs =
        MatchPlanInputs.builder(pattern)
            .returnItems(List.of(item))
            .returnAliases(Arrays.asList((SQLIdentifier) null))
            .returnNestedProjections(Arrays.asList((SQLNestedProjection) null))
            .build();

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.returnItems).containsExactly(item);
  }

  /**
   * Verifies the four return-mode boolean flags ({@code returnElements}, {@code returnPaths},
   * {@code returnPatterns}, {@code returnPathElements}) and {@code returnDistinct} each propagate
   * INDEPENDENTLY to their own destination field. The five flags occupy five adjacent record
   * components and the planner maps them positionally, so an all-true / all-false test cannot
   * detect a transposed mapping (e.g. {@code returnPaths} landing in {@code returnPatterns}).
   * This drives one flag true at a time and asserts exactly that field is true and the other
   * four are false, so a permutation surfaces as a mismatched assertion.
   */
  @Test
  public void plannerCtor_propagatesReturnFlags() {
    // returnDistinct only
    assertReturnFlags(
        MatchPlanInputs.builder(new Pattern()).returnDistinct(true).build(),
        true, false, false, false, false);
    // returnElements only
    assertReturnFlags(
        MatchPlanInputs.builder(new Pattern()).returnElements(true).build(),
        false, true, false, false, false);
    // returnPaths only
    assertReturnFlags(
        MatchPlanInputs.builder(new Pattern()).returnPaths(true).build(),
        false, false, true, false, false);
    // returnPatterns only
    assertReturnFlags(
        MatchPlanInputs.builder(new Pattern()).returnPatterns(true).build(),
        false, false, false, true, false);
    // returnPathElements only
    assertReturnFlags(
        MatchPlanInputs.builder(new Pattern()).returnPathElements(true).build(),
        false, false, false, false, true);
  }

  /**
   * Builds the planner from {@code inputs} and asserts each of the five return-mode flags reads
   * back its expected value. Centralises the check so each one-hot case above is a single call
   * and a field-to-field transposition in the planner ctor fails right here.
   */
  private static void assertReturnFlags(
      MatchPlanInputs inputs,
      boolean expectDistinct,
      boolean expectElements,
      boolean expectPaths,
      boolean expectPatterns,
      boolean expectPathElements) {
    var planner = new MatchExecutionPlanner(inputs);
    assertThat(getReturnDistinct(planner)).as("returnDistinct").isEqualTo(expectDistinct);
    assertThat(planner.returnElements).as("returnElements").isEqualTo(expectElements);
    assertThat(planner.returnPaths).as("returnPaths").isEqualTo(expectPaths);
    assertThat(planner.returnPatterns).as("returnPatterns").isEqualTo(expectPatterns);
    assertThat(planner.returnPathElements).as("returnPathElements").isEqualTo(expectPathElements);
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
        MatchPlanInputs.builder(pattern)
            .groupBy(groupBy)
            .orderBy(orderBy)
            .unwind(unwind)
            .limit(limit)
            .skip(skip)
            .build();

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.limit).isSameAs(limit);
    assertThat(planner.skip).isSameAs(skip);
    assertThat(getGroupBy(planner)).isSameAs(groupBy);
    assertThat(getOrderBy(planner)).isSameAs(orderBy);
    assertThat(getUnwind(planner)).isSameAs(unwind);
  }

  // ──────────────────────────────────── Test helpers ────────────────────────────────────

  private static MatchPlanInputs inputsWithAliasClasses(Map<String, String> aliasClasses) {
    return MatchPlanInputs.builder(new Pattern()).aliasClasses(aliasClasses).build();
  }

  private static MatchPlanInputs inputsWithAliasFilters(Map<String, SQLWhereClause> aliasFilters) {
    return MatchPlanInputs.builder(new Pattern()).aliasFilters(aliasFilters).build();
  }

  private static MatchPlanInputs inputsWithAliasRids(Map<String, SQLRid> aliasRids) {
    return MatchPlanInputs.builder(new Pattern()).aliasRids(aliasRids).build();
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
    return readField(planner, "returnDistinct");
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
