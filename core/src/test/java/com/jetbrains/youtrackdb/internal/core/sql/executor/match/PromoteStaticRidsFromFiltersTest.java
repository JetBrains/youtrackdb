package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLInCondition;
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
 * copied into {@code aliasRids} or {@code aliasRidLists} so that
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

  /** Asserts promoted list size and each resolved legacy RID literal. */
  private static void assertPromotedRidList(
      Map<String, List<SQLRid>> aliasRidLists, String alias, String... expectedRids) {
    assertThat(aliasRidLists).containsKey(alias);
    assertThat(aliasRidLists.get(alias)).hasSize(expectedRids.length);
    for (var i = 0; i < expectedRids.length; i++) {
      assertThat(aliasRidLists.get(alias).get(i).toRecordId((Result) null, CTX).toString())
          .isEqualTo(expectedRids[i]);
    }
  }

  /** Asserts a singleton promoted equality resolves to the expected legacy RID. */
  private static void assertPromotedRid(
      Map<String, SQLRid> aliasRids, String alias, String expectedRid) {
    assertPromotedRid(aliasRids, alias, expectedRid, CTX);
  }

  private static void assertPromotedRid(
      Map<String, SQLRid> aliasRids, String alias, String expectedRid, CommandContext ctx) {
    assertThat(aliasRids).containsKey(alias);
    assertThat(aliasRids.get(alias).toRecordId((Result) null, ctx).toString())
        .isEqualTo(expectedRid);
  }

  /**
   * Literal RID in a WHERE clause is promoted to aliasRids. After promotion,
   * estimateRootEntries() will see the alias as a singleton (estimate = 1).
   */
  @Test
  public void literalRid_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid = #25:7"));
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertPromotedRid(aliasRids, "c", "#25:7");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertPromotedRid(aliasRids, "c", "#25:7");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(Map.of("rid", "#25:7"));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertPromotedRid(aliasRids, "c", "#25:7", ctx);
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * Filter without any @rid equality leaves aliasRids untouched.
   */
  @Test
  public void filterWithoutRid_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE name = 'foo'"));
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertThat(aliasRids).isEmpty();
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
    Map<String, SQLRid> aliasRids = new HashMap<>();
    var existing = mock(SQLRid.class);
    aliasRids.put("c", existing);

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertThat(aliasRids).containsEntry("c", existing);
  }

  /**
   * An empty filter map produces an empty aliasRids result. Smoke test for the
   * empty-input edge case.
   */
  @Test
  public void emptyFilters_producesEmptyRids() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertThat(aliasRids).isEmpty();
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(Map.of("rid", "#25:7"));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertPromotedRid(aliasRids, "c", "#25:7", ctx);
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
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, new HashMap<>(), ctx);

    assertPromotedRid(aliasRids, "c", "#25:7");
  }

  /**
   * Literal RID list {@code @rid IN [#N:M, #X:Y]} promotes into aliasRidLists.
   * Production queries pin vertices with this form instead of a single equality.
   */
  @Test
  public void literalRidList_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put(
        "c", parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertPromotedRidList(aliasRidLists, "c", "#25:7", "#26:8");
    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertPromotedRidList(aliasRidLists, "c", "#25:7", "#26:8");
    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(
        Map.of("rids", List.of("#25:7", "#26:8")));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertPromotedRidList(aliasRidLists, "c", "#25:7", "#26:8");
    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * Compound {@code @rid IN :rids AND <other>} promotes the list and keeps the filter.
   */
  @Test
  public void compoundFilterWithParameterRidList_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in :rids AND name = 'foo'"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(
        Map.of("rids", List.of("#25:7", "#26:8")));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertPromotedRidList(aliasRidLists, "c", "#25:7", "#26:8");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * {@code @rid IN (SELECT ...)} is not early-calculable and must not promote.
   */
  @Test
  public void subqueryRidList_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid IN (SELECT @rid FROM Comment)"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * An alias that already has a promoted RID list slot is not overwritten.
   */
  @Test
  public void existingAliasRidList_isNotOverwritten() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put(
        "c", parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();
    var existing = List.of(mock(SQLRid.class));

    aliasRidLists.put("c", existing);

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).containsEntry("c", existing);
  }

  /**
   * Empty {@code @rid IN []} yields no promotable list.
   */
  @Test
  public void emptyRidList_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid in []"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * A list mixing RID literals with a non-numeric, non-RID string aborts promotion.
   */
  @Test
  public void invalidStringRidInList_abortsPromotion() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, 'not-a-rid']"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * A list mixing RID literals with a non-RID numeric value aborts promotion.
   */
  @Test
  public void invalidNumericListElement_abortsPromotion() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, 42]"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * Parameter bound to a non-iterable value cannot form a RID list.
   */
  @Test
  public void nonIterableParameter_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid in :rids"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    when(ctx.getInputParameters()).thenReturn(Map.of("rids", 42));

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
  }

  /**
   * Two RID lists under OR cannot be represented as a single pinned root.
   */
  @Test
  public void orOfTwoRidLists_isNotPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere(
        "SELECT FROM Comment WHERE @rid in [#25:7, #26:8] OR @rid in [#27:9, #28:0]"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRidLists).doesNotContainKey("c");
    assertThat(aliasRids).doesNotContainKey("c");
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
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRids).containsKey("c");
    assertThat(aliasRidLists).doesNotContainKey("c");
    assertPromotedRid(aliasRids, "c", "#25:7");
  }

  /**
   * Parser-provided {@code aliasRids} slot blocks {@code @rid IN} promotion.
   */
  @Test
  public void existingAliasRid_blocksRidListPromotion() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put(
        "c", parseWhere("SELECT FROM Comment WHERE @rid in [#25:7, #26:8]"));
    Map<String, SQLRid> aliasRids = new HashMap<>();
    Map<String, List<SQLRid>> aliasRidLists = new HashMap<>();
    var existing = mock(SQLRid.class);
    aliasRids.put("c", existing);

    MatchExecutionPlanner.promoteStaticRidsFromFilters(
        aliasFilters, aliasRids, aliasRidLists, ctx);

    assertThat(aliasRids).containsEntry("c", existing);
    assertThat(aliasRidLists).doesNotContainKey("c");
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
}
