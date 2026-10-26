package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.query.ExecutionStep;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.AbstractExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.DistinctExecutionStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ProjectionCalculationStep;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;
import com.jetbrains.youtrackdb.internal.core.sql.parser.Pattern;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLGroupBy;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLIdentifier;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLLimit;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchExpression;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchFilter;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLMatchStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNestedProjection;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrderBy;
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
 *   <li>Defensive-copy independence of {@code aliasClasses} / {@code aliasFilters} against
 *       caller mutations after construction &mdash; the planner mutates these maps internally
 *       during planning.</li>
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
public class MatchExecutionPlannerInputsTest extends DbTestBase {

  /**
   * Verifies the compact constructor normalises every nullable collection input to an empty
   * collection. This lets non-SQL front-ends omit fields they don't populate (e.g. the
   * minimal {@code g.V()} translation has no NOT patterns, no return aliases, no group-by).
   */
  @Test
  public void record_nullCollections_normalizedToEmpty() {
    var inputs =
        new MatchPlanInputs(
            new Pattern(), null, null, null, null, null, null, null, null, null, null, null,
            null, null, false, false, false, false, false);
    assertThat(inputs.aliasClasses()).isEmpty();
    assertThat(inputs.aliasFilters()).isEmpty();
    assertThat(inputs.matchExpressions()).isEmpty();
    assertThat(inputs.notMatchExpressions()).isEmpty();
    assertThat(inputs.existsMatchExpressions()).isEmpty();
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

    // Positional ctor: the return lists are parallel and preserve null entries.
    var inputs =
        new MatchPlanInputs(
            new Pattern(),
            /* aliasClasses */ null,
            /* aliasFilters */ null,
            /* matchExpressions */ null,
            /* notMatchExpressions */ null,
            /* existsMatchExpressions */ null,
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
    assertThat(planner.existsMatchExpressions).isEmpty();
    assertThat(planner.returnItems).isEmpty();
    assertThat(planner.returnAliases).isEmpty();
    assertThat(planner.returnNestedProjections).isEmpty();
    // The three working maps default to empty and the single-value AST fields to null — the "and
    // default values for everything else" half of this test's contract, which the flag / limit /
    // skip assertions alone did not pin.
    assertThat(getAliasClasses(planner)).isEmpty();
    assertThat(getAliasFilters(planner)).isEmpty();
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
    var existsExpr = new SQLMatchExpression(-1);
    var proj1 = new SQLNestedProjection(-1);
    var proj2 = new SQLNestedProjection(-1);

    var inputs =
        MatchPlanInputs.builder(pattern)
            .aliasClasses(Map.of("a", "Person"))
            .aliasFilters(Map.of("a", new SQLWhereClause(-1)))
            .matchExpressions(List.of(new SQLMatchExpression(-1)))
            .notMatchExpressions(List.of(notExpr))
            .existsMatchExpressions(List.of(existsExpr))
            // Two parallel return items so the three return lists are equal-length (the record
            // enforces this); proj1/proj2 exercise the ordered non-null nested-projection copy.
            .returnItems(List.of(new SQLExpression(-1), new SQLExpression(-1)))
            .returnAliases(List.of(new SQLIdentifier("ret1"), new SQLIdentifier("ret2")))
            .returnNestedProjections(List.of(proj1, proj2))
            .build();

    var planner = new MatchExecutionPlanner(inputs);

    assertThat(planner.notMatchExpressions).containsExactly(notExpr);
    assertThat(planner.existsMatchExpressions).containsExactly(existsExpr);
    assertThat(planner.returnAliases).hasSize(2);
    // Two distinct non-null projections in declared order — a copy that dropped, duplicated, or
    // reordered a non-null nested projection fails here, which the earlier null-only case (a size
    // check only) could not catch.
    assertThat(planner.returnNestedProjections).containsExactly(proj1, proj2);
    assertThat(getAliasFilters(planner)).hasSize(1);
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

  /** A detached NOT or EXISTS check uses its origin after any positive branch has joined. */
  @Test
  public void collectDownstreamAliases_keepsDetachedCheckOrigins() {
    var exists = new SQLMatchExpression(-1);
    var existsOrigin = new SQLMatchFilter(-1);
    existsOrigin.setAlias("c");
    exists.setOrigin(existsOrigin);
    var not = new SQLMatchExpression(-1);
    var notOrigin = new SQLMatchFilter(-1);
    notOrigin.setAlias("b");
    not.setOrigin(notOrigin);
    var planner = new MatchExecutionPlanner(MatchPlanInputs.builder(new Pattern())
        .notMatchExpressions(List.of(not)).existsMatchExpressions(List.of(exists)).build());
    assertThat(planner.collectDownstreamAliases(List.of(new SQLExpression(-1)),
        null, null, null, java.util.Set.of("a", "b", "c"))).contains("b", "c");
  }

  /** Direct repeated aliases deduplicate after NOT checks and before any projection evaluation. */
  @Test
  public void identityDistinctPrecedesProjectionAndRetainsCheckedRows() {
    session.begin();
    var vertex = session.newVertex("V");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    var statement = (SQLMatchStatement) SQLEngine.parse(
        "MATCH {class:V, as:a}, NOT {as:a}.out(){as:b} RETURN DISTINCT a as x, a as y",
        session);
    var plan = new MatchExecutionPlanner(statement).createExecutionPlan(ctx, false, false);
    var steps = plan.getSteps();
    assertThat(steps.get(steps.size() - 3)).isInstanceOf(FilterNotMatchPatternStep.class);
    var distinct = (DistinctExecutionStep) steps.get(steps.size() - 2);
    assertThat(distinct.prettyPrint(0, 2)).contains("BY IDENTITY (a)");
    var projection = (ProjectionCalculationStep) steps.getLast();
    var reads = new int[1];
    var row = new ResultInternal(session) {
      @Override
      public <T> T getProperty(String name) {
        reads[0]++;
        return super.getProperty(name);
      }
    };
    row.setMatchProperty("a", ResultInternal.fromCheckedRecord(session,
        session.executeReadRecord(
            (com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal) vertex.getIdentity(),
            null, true)));
    distinct.setPrevious(new AbstractExecutionStep(ctx, false) {
      @Override
      public ExecutionStep copy(com.jetbrains.youtrackdb.internal.core.command.CommandContext c) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ExecutionStream internalStart(
          com.jetbrains.youtrackdb.internal.core.command.CommandContext c) {
        return ExecutionStream.resultIterator(List.<Result>of(row, row, row).iterator());
      }
    });
    // Binding retention reads the row during setup. Count only projection-time reads.
    reads[0] = 0;
    var stream = projection.start(ctx);
    try {
      var projected = (ResultInternal) stream.next(ctx);
      assertThat(projected.getVertex("x")).isSameAs(vertex);
      assertThat(projected.getVertex("y")).isSameAs(vertex);
      assertThat(projected.getMatchedRecord("x")).isSameAs(row.getMatchedRecord("a"));
      assertThat(stream.hasNext(ctx)).isFalse();
      assertThat(reads[0]).as("two alias projections for one survivor, not three rows")
          .isEqualTo(2);
    } finally {
      stream.close(ctx);
      session.rollback();
    }
  }

  /** Non-direct keys and clause boundaries keep projected DISTINCT in its existing position. */
  @Test
  public void identityDistinctDeclinesUnprovedReturnShapes() {
    session.begin();
    session.newVertex("V");
    var ctx = new BasicCommandContext();
    ctx.setDatabaseSession(session);
    try {
      for (var suffix : List.of("a", "DISTINCT a.name", "DISTINCT a.toString()",
          "DISTINCT a, b", "DISTINCT a:{@rid}", "DISTINCT count(a)",
          "DISTINCT a GROUP BY a", "DISTINCT a UNWIND a", "DISTINCT a ORDER BY a",
          "DISTINCT a SKIP 1", "DISTINCT a LIMIT 1", "DISTINCT missing")) {
        var statement = (SQLMatchStatement) SQLEngine.parse(
            "MATCH {class:V, as:a}.out(){as:b} RETURN " + suffix, session);
        var plan = new MatchExecutionPlanner(statement).createExecutionPlan(ctx, false, false);
        assertThat(plan.prettyPrint(0, 2)).as(suffix).doesNotContain("BY IDENTITY");
      }
      var optional = (SQLMatchStatement) SQLEngine.parse(
          "MATCH {class:V, as:a}.out(){as:b, optional:true} RETURN DISTINCT b", session);
      assertThat(new MatchExecutionPlanner(optional).createExecutionPlan(ctx, false, false)
          .prettyPrint(0, 2)).doesNotContain("BY IDENTITY");
    } finally {
      session.rollback();
    }
  }

  /** Primitive and equal-content embedded field values retain projection-based equality. */
  @Test
  public void identityDistinctDeclinesFieldBindingsAndPreservesProjectedResults() {
    session.createVertexClass("IdentitySource");
    session.begin();
    for (var tags : List.of(List.of("a", "b", "a"), List.of("a"))) {
      var vertex = session.newVertex("IdentitySource");
      vertex.<String>getOrCreateEmbeddedList("tags").addAll(tags);
      var embedded = session.newEmbeddedEntity();
      embedded.setProperty("city", "same");
      vertex.setProperty("emb", embedded);
    }
    session.commit();
    session.begin();
    try {
      assertProjectedDistinctMatchesControl(
          "MATCH {class:IdentitySource, as:p}.tags{as:t} RETURN DISTINCT t", 2);
      assertProjectedDistinctMatchesControl(
          "MATCH {class:IdentitySource, as:p}.emb{as:e} RETURN DISTINCT e", 1);
    } finally {
      session.rollback();
    }
  }

  /** Metadata may overwrite the returned node or a different node already present in the row. */
  @Test
  public void identityDistinctDeclinesMetadataCollisionsAnywhereInPattern() {
    session.begin();
    session.newVertex("V");
    session.commit();
    session.begin();
    try {
      for (var metadata : List.of("depthAlias", "pathAlias")) {
        for (var alias : List.of("a", "b")) {
          assertProjectedDistinctMatchesControl(
              "MATCH {class:V, as:a}.out(){as:b, while:($depth < 1), "
                  + metadata + ":" + alias + "} RETURN DISTINCT " + alias,
              1);
        }
      }
    } finally {
      session.rollback();
    }
  }

  /** Custom methods and compound paths have no link-only proof, even for a direct RETURN. */
  @Test
  public void identityDistinctDeclinesCustomAndCompoundPaths() {
    session.begin();
    session.newVertex("V");
    var ctx = new BasicCommandContext(session);
    try {
      for (var path : List.of(".toString(){as:b}", ".(out().out()){as:b}")) {
        var statement = (SQLMatchStatement) SQLEngine.parse(
            "MATCH {class:V, as:a}" + path + " RETURN DISTINCT b", session);
        assertThat(new MatchExecutionPlanner(statement).createExecutionPlan(ctx, false, false)
            .prettyPrint(0, 2)).as(path).doesNotContain("BY IDENTITY");
      }
    } finally {
      session.rollback();
    }
  }

  /** Pre-built field edges keep projected equality even without the positive expression list. */
  @Test
  public void identityDistinctDeclinesPrebuiltFieldPatternAndPreservesResults() {
    session.createVertexClass("IdentityInputSource");
    session.begin();
    for (var tags : List.of(List.of("a", "b", "a"), List.of("a"))) {
      session.newVertex("IdentityInputSource").<String>getOrCreateEmbeddedList("tags")
          .addAll(tags);
    }
    session.commit();
    session.begin();
    try {
      assertPrebuiltDistinctMatchesControl(
          "MATCH {class:IdentityInputSource, as:p}.tags{as:t} RETURN DISTINCT t", false, 2);
    } finally {
      session.rollback();
    }
  }

  /** Pre-built graph edges still deduplicate shared targets by identity before projection. */
  @Test
  public void identityDistinctEngagesForPrebuiltGraphPatternAndPreservesResults() {
    session.createVertexClass("IdentityInputSource");
    session.begin();
    var first = session.newVertex("IdentityInputSource");
    var second = session.newVertex("IdentityInputSource");
    var target = session.newVertex("V");
    var other = session.newVertex("V");
    first.addEdge(target, "E");
    second.addEdge(target, "E");
    second.addEdge(other, "E");
    session.commit();
    session.begin();
    try {
      assertPrebuiltDistinctMatchesControl(
          "MATCH {class:IdentityInputSource, as:p}.out(){as:t} RETURN DISTINCT t", true, 2);
    } finally {
      session.rollback();
    }
  }

  /** Pre-built traversal metadata cannot replace an identity key with a depth or path value. */
  @Test
  public void identityDistinctDeclinesPrebuiltMetadataCollisionsAndPreservesResults() {
    session.createVertexClass("IdentityInputSource");
    session.begin();
    session.newVertex("IdentityInputSource");
    session.commit();
    session.begin();
    try {
      for (var metadata : List.of("depthAlias", "pathAlias")) {
        for (var alias : List.of("p", "t")) {
          assertPrebuiltDistinctMatchesControl(
              "MATCH {class:IdentityInputSource, as:p}.out(){as:t, while:($depth < 1), "
                  + metadata + ":" + alias + "} RETURN DISTINCT " + alias,
              false, 1);
        }
      }
    } finally {
      session.rollback();
    }
  }

  private void assertPrebuiltDistinctMatchesControl(
      String query, boolean identity, int expectedSize) {
    var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
    var pattern = new Pattern();
    statement.getMatchExpressions().forEach(pattern::addExpression);
    var inputs = MatchPlanInputs.builder(pattern)
        .aliasClasses(Map.of("p", "IdentityInputSource"))
        .returnItems(statement.getReturnItems())
        .returnAliases(statement.getReturnAliases())
        .returnNestedProjections(statement.getReturnNestedProjections())
        .returnDistinct(true)
        .build();
    assertThat(inputs.matchExpressions()).isEmpty();
    var ctx = new BasicCommandContext(session);
    var plan = new MatchExecutionPlanner(inputs).createExecutionPlan(ctx, false, false);
    var steps = plan.getSteps();
    assertThat(steps).filteredOn(DistinctExecutionStep.class::isInstance).hasSize(1);
    if (identity) {
      assertThat(steps.get(steps.size() - 2)).isInstanceOf(DistinctExecutionStep.class);
      assertThat(steps.getLast()).isInstanceOf(ProjectionCalculationStep.class);
      assertThat(plan.prettyPrint(0, 2)).contains("BY IDENTITY (t)");
    } else {
      assertThat(steps.getLast()).isInstanceOf(DistinctExecutionStep.class);
      assertThat(plan.prettyPrint(0, 2)).doesNotContain("BY IDENTITY");
    }
    var actual = new ArrayList<Map<String, Object>>();
    var stream = plan.start();
    try {
      while (stream.hasNext(ctx)) {
        actual.add(stream.next(ctx).toMap());
      }
    } finally {
      stream.close(ctx);
    }
    try (var control = session.query(query + " SKIP 0")) {
      var expected = control.stream().map(Result::toMap).toList();
      assertThat(expected).as("ordinary projected DISTINCT control").hasSize(expectedSize);
      assertThat(actual).as(query).containsExactlyElementsOf(expected);
    }
  }

  private void assertProjectedDistinctMatchesControl(String query, int expectedSize) {
    var ctx = new BasicCommandContext(session);
    var statement = (SQLMatchStatement) SQLEngine.parse(query, session);
    var steps = new MatchExecutionPlanner(statement).createExecutionPlan(ctx, false, false)
        .getSteps();
    assertThat(steps).filteredOn(DistinctExecutionStep.class::isInstance).hasSize(1);
    assertThat(steps.getLast()).isInstanceOf(DistinctExecutionStep.class);
    assertThat(((DistinctExecutionStep) steps.getLast()).prettyPrint(0, 2))
        .doesNotContain("BY IDENTITY");
    try (var control = session.query(query + " SKIP 0"); var actual = session.query(query)) {
      var expected = control.stream().map(Result::toMap).toList();
      assertThat(expected).as("old projection-then-DISTINCT control").hasSize(expectedSize);
      assertThat(actual.stream().map(Result::toMap).toList()).as(query)
          .containsExactlyElementsOf(expected);
    }
  }

  // ──────────────────────────────────── Test helpers ────────────────────────────────────

  private static MatchPlanInputs inputsWithAliasClasses(Map<String, String> aliasClasses) {
    return MatchPlanInputs.builder(new Pattern()).aliasClasses(aliasClasses).build();
  }

  private static MatchPlanInputs inputsWithAliasFilters(Map<String, SQLWhereClause> aliasFilters) {
    return MatchPlanInputs.builder(new Pattern()).aliasFilters(aliasFilters).build();
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
