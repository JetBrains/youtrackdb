package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLAndBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLNotBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLOrBlock;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link MatchExecutionPlanner#promoteStaticRidsFromFilters}.
 *
 * <p>The promoter scans per-alias WHERE clauses for static {@code @rid = <expr>}
 * equalities and {@code @rid IN <static-list>} conditions. Early-calculable
 * literals and parameters (never {@code $matched} back-refs or subqueries) are
 * copied into {@code aliasPinnedRids} so that
 * {@link MatchExecutionPlanner#estimateRootEntries} collapses cardinality and
 * root selection picks the RID-fetch fast path.
 *
 * <p><strong>Note on Test Structure:</strong> The concrete RID literals used across these tests
 * (e.g., {@code #25:7}, {@code #26:8}, {@code #12:0}) are purely structural and illustrative. The
 * promoter and underlying AST nodes operate entirely in-memory against mocked contexts and are
 * never resolved against active storage clusters. Any syntactically valid RID payload can be used
 * interchangeably without shifting test outcomes.
 */
public class PromoteStaticRidsFromFiltersTest {

  private static final BasicCommandContext CTX = new BasicCommandContext();

  /**
   * The alias-class map for the cases that carry no class constraint. With no class to lose, the
   * promoter's class guard short-circuits and never touches the schema, which is what lets the
   * mocked session below stay a bare stub.
   */
  private static final Map<String, String> NO_CLASSES = Map.of();

  private CommandContext ctx;

  @Before
  public void setUp() {
    var db = mock(DatabaseSessionEmbedded.class);
    ctx = mock(CommandContext.class);
    when(ctx.getDatabaseSession()).thenReturn(db);
  }

  /**
   * Parses a SELECT to lift its WHERE clause out of the AST. Convenient way to
   * exercise the real parser without hand-building expression nodes.
   */
  private static SQLWhereClause parseWhere(String sql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getWhereClause();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse: " + sql, e);
    }
  }

  /**
   * Asserts promoted RID list size and each resolved legacy RID literal, resolving
   * every RID against the static {@link #CTX}. Use this no-ctx overload for
   * literal-RID tests (e.g. {@code @rid = #25:7}, {@code @rid IN [#25:7, #26:8]}),
   * where resolution needs no per-test parameter bindings. For parameter-bound
   * RIDs, use the {@link #assertPromotedRids(Map, String, CommandContext, String...)
   * ctx-taking overload} instead.
   */
  private static void assertPromotedRids(
      Map<String, List<SQLRid>> aliasPinnedRids, String alias, String... expectedRids) {
    assertThat(aliasPinnedRids).containsKey(alias);
    assertThat(aliasPinnedRids.get(alias)).hasSize(expectedRids.length);
    for (var i = 0; i < expectedRids.length; i++) {
      assertThat(aliasPinnedRids.get(alias).get(i).toRecordId((Result) null, CTX).toString())
          .isEqualTo(expectedRids[i]);
    }
  }

  /**
   * Same as {@link #assertPromotedRids(Map, String, String...)} but resolves every
   * RID against the caller-supplied {@code ctx} (the per-test mock) rather than the
   * static {@link #CTX}. Use this overload for parameter-bound RID tests
   * ({@code @rid = :param} / {@code @rid IN :params}) that stub
   * {@code ctx.getInputParameters()}, because the promoted {@link SQLRid} carries a
   * parameter expression that only resolves against that mock's bindings.
   */
  private static void assertPromotedRids(
      Map<String, List<SQLRid>> aliasPinnedRids, String alias, CommandContext ctx,
      String... expectedRids) {
    assertThat(aliasPinnedRids).containsKey(alias);
    assertThat(aliasPinnedRids.get(alias)).hasSize(expectedRids.length);
    for (var i = 0; i < expectedRids.length; i++) {
      assertThat(aliasPinnedRids.get(alias).get(i).toRecordId((Result) null, ctx).toString())
          .isEqualTo(expectedRids[i]);
    }
  }

  /**
   * Literal RID in a WHERE clause is promoted to aliasPinnedRids as List.of(rid). After promotion,
   * estimateRootEntries() will see the alias as a singleton (estimate = 1).
   */
  @Test
  public void literalRid_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid = #25:7"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7");
    // The filter is intentionally left intact for the DirectRid pre-filter
    // pass on non-root use; verify it was not stripped.
    assertThat(aliasFilters).containsKey("c");
    assertThat(aliasFilters.get("c").findRidEquality()).isNotNull();
  }

  /**
   * Compound filter {@code @rid = #N:M AND <other>} still promotes the alias.
   * The other terms remain in the filter and are evaluated post-fetch.
   */
  @Test
  public void compoundFilterWithLiteralRid_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid = #25:7 AND name = 'foo'"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7");
    assertThat(aliasFilters.get("c").findRidEquality()).isNotNull();
  }

  /**
   * Parameter-bound RID ({@code @rid = :param}) is early-calculable, so it
   * promotes just like a literal. The parameter is resolved at execution time
   * by SQLRid.toRecordId(expression).
   */
  @Test
  public void parameterRid_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid = :rid"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(Map.of("rid", "#25:7"));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", ctx, "#25:7");
  }

  /**
   * Back-reference {@code @rid = $matched.X.@rid} is left alone. It depends on
   * runtime bindings and is handled by EdgeRidLookup / Pattern A back-ref
   * hash join in the downstream pre-filter pass.
   */
  @Test
  public void matchedBackRef_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid = $matched.x.@rid"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * Filter without any @rid equality leaves aliasPinnedRids untouched.
   */
  @Test
  public void filterWithoutRid_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE name = 'foo'"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).isEmpty();
  }

  /**
   * An alias that already has a RID slot from the parser (e.g.
   * {@code {as: c, rid: #1:2}}) is not overwritten, even when the filter also
   * contains an @rid equality. The pre-existing entry wins.
   */
  @Test
  public void existingAliasRid_isNotOverwritten() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid = #25:7"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();
    var existing = mock(SQLRid.class);
    // Immutable list: an in-place append by a buggy promoter would throw, so
    // reference-identity below is not the only guard against mutation.
    var existingList = List.of(existing);
    aliasPinnedRids.put("c", existingList);

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).containsEntry("c", existingList);
    // Post single-map consolidation there is no second map to prove placement
    // against, so distinctness is recaptured by reference-identity plus map size:
    // the pre-existing slot is the exact same object (no append or replacement)
    // and the promotion added no new entry.
    assertThat(aliasPinnedRids.get("c")).isSameAs(existingList);
    assertThat(aliasPinnedRids).hasSize(1);
  }

  /**
   * An empty filter map produces an empty aliasPinnedRids result. Smoke test for the
   * empty-input edge case.
   */
  @Test
  public void emptyFilters_producesEmptyRids() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).isEmpty();
  }

  /**
   * Disjunction {@code @rid = #N:M OR <other>} must NOT promote. Pinning the
   * root to the single RID would drop the OR branch and silently lose rows, so
   * {@code findRidEquality()} returns null for a multi-element OR.
   */
  @Test
  public void orWithLiteralRid_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid = #25:7 OR name = 'foo'"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * Two RID equalities under an OR ({@code @rid = #N:M OR @rid = #X:Y}) are also
   * not promoted: the promoter builds a single-RID root, which cannot represent
   * a two-RID union. Left unpromoted, both RIDs are matched by the normal path.
   */
  @Test
  public void orOfTwoRids_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid = #25:7 OR @rid = #26:8"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * A RID equality nested inside an OR ({@code name = 'foo' AND (@rid = #N:M OR
   * name = 'bar')}) is not promoted: the RID term is not a top-level conjunct,
   * so it is not a necessary condition for the row.
   */
  @Test
  public void nestedOrWithRid_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE name = 'foo' AND (@rid = #25:7 OR name = 'bar')"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * Parameter RID in an AND ({@code @rid = :rid AND <other>}) promotes, matching
   * the literal-AND case. Complements {@link #compoundFilterWithLiteralRid_isPromoted}
   * on the parameter side.
   */
  @Test
  public void compoundFilterWithParameterRid_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid = :rid AND name = 'foo'"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(Map.of("rid", "#25:7"));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", ctx, "#25:7");
    assertThat(aliasFilters.get("c").findRidEquality()).isNotNull();
  }

  /**
   * Operand-reversed RID equality (literal on the left-hand side) must also be promoted
   * to ensure order-independence across incoming user queries.
   */
  @Test
  public void reversedOperandLiteralRid_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new HashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE #25:7 = @rid"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7");
  }

  /**
   * Literal RID list {@code @rid IN [#N:M, #X:Y]} promotes into aliasPinnedRids as a list.
   * Production queries pin vertices with this form instead of a single equality.
   */
  @Test
  public void literalRidList_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put(
        "c", parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7", "#26:8");
    assertThat(aliasFilters).containsKey("c");
  }

  /**
   * Compound {@code @rid IN [...] AND <other>} still promotes the RID list while
   * leaving the extra predicate in the filter.
   */
  @Test
  public void compoundFilterWithLiteralRidList_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, #26:8] AND name = 'foo'"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7", "#26:8");
    assertThat(aliasFilters).containsKey("c");
    assertThat(aliasFilters.get("c").findRidInList()).isNotNull();
  }

  /**
   * {@code @rid IN :rids} with an early-calculable parameter promotes like literals.
   */
  @Test
  public void parameterRidList_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid in :rids"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(
        Map.of("rids", List.of("#25:7", "#26:8")));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7", "#26:8");
  }

  /**
   * Compound {@code @rid IN :rids AND <other>} promotes the list and keeps the filter.
   */
  @Test
  public void compoundFilterWithParameterRidList_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in :rids AND name = 'foo'"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(
        Map.of("rids", List.of("#25:7", "#26:8")));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7", "#26:8");
    assertThat(aliasFilters.get("c").findRidInList()).isNotNull();
  }

  /**
   * Disjunction {@code @rid IN [...] OR <other>} must NOT promote — same rule as
   * equality: pinning would drop the OR branch.
   */
  @Test
  public void orWithLiteralRidList_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, #26:8] OR name = 'foo'"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * {@code @rid IN [...]} nested inside an OR is not a top-level conjunct and
   * must not be promoted.
   */
  @Test
  public void nestedOrWithRidList_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE name = 'foo'"
            + " AND (@rid in [#25:7, #26:8] OR name = 'bar')"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * Back-reference {@code @rid IN $matched.X.@rid} is left alone — runtime
   * correlation, not a static list.
   */
  @Test
  public void matchedBackRefInList_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in $matched.x.@rid"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * {@code @rid IN (SELECT ...)} is not early-calculable and must not promote.
   */
  @Test
  public void subqueryRidList_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid IN (SELECT @rid FROM Comment)"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * An alias that already has a promoted RID list slot is not overwritten.
   */
  @Test
  public void existingAliasRidList_isNotOverwritten() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put(
        "c", parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();
    var existing = List.of(mock(SQLRid.class));

    aliasPinnedRids.put("c", existing);

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).containsEntry("c", existing);
  }

  /**
   * Empty {@code @rid IN []} yields no promotable list.
   */
  @Test
  public void emptyRidList_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid in []"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * A list mixing RID literals with a non-numeric, non-RID string aborts promotion.
   */
  @Test
  public void invalidStringRidInList_abortsPromotion() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, 'not-a-rid']"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * A list mixing RID literals with a non-RID numeric value aborts promotion.
   */
  @Test
  public void invalidNumericListElement_abortsPromotion() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, 42]"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * Parameter bound to a non-iterable value cannot form a RID list.
   */
  @Test
  public void nonIterableParameter_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid in :rids"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(Map.of("rids", 42));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * Two RID lists under OR cannot be represented as a single pinned root.
   */
  @Test
  public void orOfTwoRidLists_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, #26:8] OR @rid in [#27:9, #28:0]"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

  /**
   * When both {@code @rid =} and {@code @rid IN} appear in the same AND filter,
   * equality promotion wins because it is checked first.
   */
  @Test
  public void equalityWinsOverInList_whenBothPresent() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid = #25:7 AND @rid in [#26:8, #27:9]"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).containsKey("c");
    assertThat(aliasPinnedRids.get("c")).hasSize(1);
    assertPromotedRids(aliasPinnedRids, "c", "#25:7");
  }

  /**
   * Parser-provided {@code aliasPinnedRids} slot blocks {@code @rid IN} promotion.
   */
  @Test
  public void existingAliasRid_blocksRidListPromotion() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put(
        "c", parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();
    var existing = mock(SQLRid.class);
    // Immutable list: an in-place append by a buggy promoter would throw, so
    // reference-identity below is not the only guard against mutation.
    var existingList = List.of(existing);
    aliasPinnedRids.put("c", existingList);

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).containsEntry("c", existingList);
    // Post single-map consolidation there is no second map to prove placement
    // against, so distinctness is recaptured by reference-identity plus map size:
    // the pre-existing slot is the exact same object (the IN-list promotion did
    // not append or replace) and it added no new entry.
    assertThat(aliasPinnedRids.get("c")).isSameAs(existingList);
    assertThat(aliasPinnedRids).hasSize(1);
  }

  /**
   * {@link MatchExecutionPlanner#toPromotedSqlRidList} materializes legacy
   * {@link SQLRid} nodes from a parsed IN condition.
   */
  @Test
  public void toPromotedSqlRidList_resolvesLiteralList() {
    var where = parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]");
    SQLInCondition inCond = where.findRidInList();
    assertThat(inCond).isNotNull();

    var promoted = MatchExecutionPlanner.toPromotedSqlRidList(inCond, ctx);

    assertThat(promoted).hasSize(2);
    assertThat(promoted.get(0).toRecordId((Result) null, CTX).toString()).isEqualTo("#25:7");
    assertThat(promoted.get(1).toRecordId((Result) null, CTX).toString()).isEqualTo("#26:8");
  }

  /**
   * A repeated RID in the IN list is promoted once, first occurrence winning.
   *
   * <p>IN is set membership: the class scan this promotion replaces tests each record once
   * against the list and emits it once, however many times the list names it. The promoted
   * form enumerates the pinned list instead, so leaving a duplicate in place fetches the same
   * record twice and changes the result multiset. {@code g.V().hasId(alice, alice)} is the
   * traversal that exposes it, and
   * {@code PredicateTraversalEquivalenceTest.hasIdDuplicate_isSetMembership_matchesNative}
   * is the end-to-end guard; this test pins the same invariant at the promotion itself.
   */
  @Test
  public void toPromotedSqlRidList_dropsDuplicateRids() {
    var where = parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8, #25:7]");
    SQLInCondition inCond = where.findRidInList();
    assertThat(inCond).isNotNull();

    var promoted = MatchExecutionPlanner.toPromotedSqlRidList(inCond, ctx);

    assertThat(promoted).hasSize(2);
    assertThat(promoted.get(0).toRecordId((Result) null, CTX).toString()).isEqualTo("#25:7");
    assertThat(promoted.get(1).toRecordId((Result) null, CTX).toString()).isEqualTo("#26:8");
  }

  /**
   * Rebuilds {@code where} with its single leaf condition as the base expression, dropping the
   * {@code OrBlock(AndBlock(NotBlock(...)))} wrapping the grammar's {@code WhereClause()}
   * production always adds. This is the shape a clause assembled in code has: {@code
   * MatchWhereBuilder.and} returns a lone operand unwrapped for parser parity, so a one-condition
   * alias filter reaches the planner as a bare condition with no wrapper at all. Building the leaf
   * by re-parsing rather than by hand keeps the condition node itself identical to the parsed tests
   * above, so the only variable is the missing wrapping.
   */
  private static SQLWhereClause unwrapToLeafClause(SQLWhereClause where) {
    var expr = where.getBaseExpression();
    assertThat(expr).isInstanceOf(SQLOrBlock.class);
    var orBlock = (SQLOrBlock) expr;
    assertThat(orBlock.getSubBlocks()).hasSize(1);
    var and = orBlock.getSubBlocks().getFirst();
    assertThat(and).isInstanceOf(SQLAndBlock.class);
    var subBlocks = ((SQLAndBlock) and).getSubBlocks();
    assertThat(subBlocks).hasSize(1);
    var leaf = subBlocks.getFirst();
    // The grammar routes every atom through NotBlock, negated or not, so a plain condition arrives
    // wrapped in a pass-through NotBlock. Strip it: the translator's hand-built clause has none.
    if (leaf instanceof SQLNotBlock notBlock) {
      leaf = notBlock.getSub();
    }
    assertThat(leaf)
        .as("the leaf must carry no block wrapping, matching a clause assembled in code")
        .isNotInstanceOfAny(SQLOrBlock.class, SQLAndBlock.class, SQLNotBlock.class);

    var leafClause = new SQLWhereClause(-1);
    leafClause.setBaseExpression(leaf);
    return leafClause;
  }

  /**
   * A code-assembled {@code WHERE @rid IN [#25:7]} — a bare {@code SQLInCondition} as the base
   * expression, with no {@code OrBlock}/{@code AndBlock} wrapper — promotes exactly like the parsed
   * form. This is the shape the Gremlin-to-MATCH translator hands the planner for {@code g.V(rid)}.
   * Before the leaf branch in {@code SQLWhereClause.findRidConditionInExpression}, the search
   * bottomed out at the wrapper check and returned null, so the promotion never fired and
   * {@code createSelectStatement} emitted a full class scan with an {@code @rid} post-filter
   * instead of a RID fetch — an O(class size) plan for a single-record lookup.
   */
  @Test
  public void unwrappedLiteralRidList_isPromoted() {
    var leafClause = unwrapToLeafClause(
        parseWhere("SELECT FROM Comment WHERE @rid in [#25:7]"));
    assertThat(leafClause.getBaseExpression()).isInstanceOf(SQLInCondition.class);
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", leafClause);
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7");
  }

  /**
   * The multi-RID form of {@link #unwrappedLiteralRidList_isPromoted}: a code-assembled
   * {@code WHERE @rid IN [#25:7, #26:8]} promotes both RIDs, which is what {@code g.V(id1, id2)}
   * and the set-membership {@code hasId(id1, id2)} branch produce.
   */
  @Test
  public void unwrappedMultiRidList_isPromoted() {
    var leafClause = unwrapToLeafClause(
        parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]"));
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", leafClause);
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7", "#26:8");
  }

  /**
   * The equality sibling: a code-assembled {@code WHERE @rid = #25:7} with no wrapper promotes too.
   * {@code findRidEquality()} and {@code findRidInList()} share the same tree search, so the leaf
   * branch has to serve both — and the pre-filter pass reads {@code findRidEquality()} on
   * non-root aliases, which is a second consumer of the same repair.
   */
  @Test
  public void unwrappedLiteralRidEquality_isPromoted() {
    var leafClause = unwrapToLeafClause(parseWhere("SELECT FROM Comment WHERE @rid = #25:7"));
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", leafClause);
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertPromotedRids(aliasPinnedRids, "c", "#25:7");
  }

  /**
   * A code-assembled clause with no {@code @rid} term still promotes nothing. Guards the leaf
   * branch against over-reach: it must widen where the search looks, not what the search accepts.
   */
  @Test
  public void unwrappedNonRidCondition_isNotPromoted() {
    var leafClause = unwrapToLeafClause(parseWhere("SELECT FROM Comment WHERE name = 'foo'"));
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", leafClause);
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, NO_CLASSES, aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).isEmpty();
  }

  /**
   * A parameter-bound RID under a class-constrained alias is not promoted. The parameter only
   * resolves against per-row bindings, so at planning time nothing proves the record it names is
   * an instance of the alias's class — and promoting would move the fetch to the RID and drop the
   * class from the plan. The unconstrained sibling
   * {@link #parameterRid_isPromoted} shows the same clause promoting when there is no class to
   * lose, so this asserts the class constraint is what blocks it, not the parameter.
   *
   * <p>Delete the class guard in {@code promoteStaticRidsFromFilters} and this test fails: the
   * alias gains a pinned RID.
   */
  @Test
  public void parameterRidUnderClassConstrainedAlias_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid = :rid"));
    Map<String, List<SQLRid>> aliasPinnedRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(Map.of("rid", "#25:7"));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, Map.of("c", "Comment"), aliasPinnedRids, ctx);

    assertThat(aliasPinnedRids).doesNotContainKey("c");
  }

}
