package com.jetbrains.youtrackdb.internal.core.sql.executor.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLRid;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for {@link MatchExecutionPlanner#promoteStaticRidsFromFilters}.
 *
 * <p>The promoter scans per-alias WHERE clauses for {@code @rid = <expr>}
 * equalities and, when the value-side is a literal or parameter (never a
 * {@code $matched.X.@rid} back-ref), copies it into {@code aliasRids} so that
 * {@link MatchExecutionPlanner#estimateRootEntries} collapses the alias's
 * estimate to 1 and root selection picks the fast path.
 */
public class PromoteStaticRidsFromFiltersTest {

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
   * Literal RID in a WHERE clause is promoted to aliasRids. After promotion,
   * estimateRootEntries() will see the alias as a singleton (estimate = 1).
   */
  @Test
  public void literalRid_isPromoted() {
    Map<String, SQLWhereClause> aliasFilters = new LinkedHashMap<>();
    aliasFilters.put("c", parseWhere("SELECT FROM Comment WHERE @rid = #25:7"));
    Map<String, SQLRid> aliasRids = new HashMap<>();

    MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasRids, ctx);

    assertThat(aliasRids).containsKey("c");
    // The filter is intentionally left intact for the DirectRid pre-filter
    // pass on non-root use; verify it was not stripped.
    assertThat(aliasFilters).containsKey("c");
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

    MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasRids, ctx);

    assertThat(aliasRids).containsKey("c");
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

    MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasRids, ctx);

    assertThat(aliasRids).containsKey("c");
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

    MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasRids, ctx);

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

    MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasRids, ctx);

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

    MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasRids, ctx);

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

    MatchExecutionPlanner.promoteStaticRidsFromFilters(aliasFilters, aliasRids, ctx);

    assertThat(aliasRids).isEmpty();
  }
}
