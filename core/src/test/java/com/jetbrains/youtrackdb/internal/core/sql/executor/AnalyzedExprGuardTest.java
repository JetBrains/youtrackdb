package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import com.jetbrains.youtrackdb.internal.core.query.ResultSet;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExpr;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprEvaluator;
import com.jetbrains.youtrackdb.internal.core.query.analyzed.AnalyzedExprLowerer;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLSelectStatement;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLWhereClause;
import com.jetbrains.youtrackdb.internal.core.sql.parser.YouTrackDBSql;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Correctness guard for the YTDB-916 Bench-1 predicate corpus (Track 06).
 *
 * <p>This is a JUnit test, NOT a JMH benchmark. It pins the "dead IR path" risk (N1) that lives in
 * the benchmark: it proves, for every predicate in the test corpus, that
 * <ol>
 *   <li>the predicate LOWERS to the analyzed-expression IR (so the benchmark's IR arm really
 *       evaluates the IR tree and does not silently time an AST fallback), and</li>
 *   <li>the IR evaluator ({@code AnalyzedExprEvaluator.evaluate}) and the AST arm
 *       ({@code SQLWhereClause.matchesFilters}) produce IDENTICAL boolean outcomes over a sample of
 *       diverse rows — cross-checked over BOTH EntityImpl-backed rows (the YTDB-628 fast path) AND
 *       projection ({@link ResultInternal}) rows (the generic slow path).</li>
 * </ol>
 *
 * <p>The sample rows are designed so every predicate has BOTH matching and non-matching rows, so a
 * trivially always-true / always-false evaluation cannot produce a false pass (the test asserts
 * that each predicate yields at least one TRUE and one FALSE over each row set).
 *
 * <p>The corpus is the nine Bench-1 cases (mirroring {@link AnalyzedExprBenchmarkState}) PLUS three
 * extra comparison operators — NE ({@code <>}), LE ({@code <=}), and GE ({@code >=}) — added to
 * exercise the fast-path NE-inversion arm (YTDB-628) and the LE/GE arms in
 * {@code AnalyzedExprEvaluator} / {@code SQLBinaryCondition.tryInPlaceComparison}. All twelve cases
 * are inside the lowering subset, so — per the Track-06 plan — there is no "AST variant throws"
 * case here; the generic "AST variant throws" clause applies only to Bench-2/3 unlowerable
 * predicates. This guard therefore asserts lowering SUCCEEDS for all twelve.
 */
public class AnalyzedExprGuardTest {

  private static final String CLASS_NAME = "Bench";

  /** Bind value for the PARAM case ({@code age > :p}). */
  private static final int PARAM_THRESHOLD = 25;

  /** A Bench-1 predicate case: display name + WHERE-side SQL. */
  private record Case(String name, String sql) {

  }

  /**
   * The nine Bench-1 cases (matching {@link AnalyzedExprBenchmarkState}'s {@code @Param} axis)
   * PLUS three extra cases — NE ({@code age <> 30}), LE ({@code age <= 30}), GE
   * ({@code age >= 30}) — added to exercise the fast-path NE-inversion arm (YTDB-628) and the
   * LE/GE arms in {@code AnalyzedExprEvaluator} / {@code SQLBinaryCondition.tryInPlaceComparison}.
   * These three extra cases are NOT in {@link AnalyzedExprBenchmarkState}'s {@code @Param} axis
   * (they are regression guards, not benchmark cases). EQ_FAST and EQ_SLOW share the SQL
   * {@code age = 30}; the row-type difference (entity vs projection) is exercised here by running
   * EVERY predicate over both row sets.
   */
  private static final List<Case> CASES = List.of(
      new Case("EQ_FAST", "age = 30"),
      new Case("CMP_FAST", "age < 30"),
      new Case("EQ_SLOW", "age = 30"),
      new Case("AND_OR", "age > 20 AND age < 40"),
      new Case("IS_NULL", "mid IS NULL"),
      new Case("IS_NOT_NULL", "mid IS NOT NULL"),
      new Case("PARAM", "age > :p"),
      new Case("CI_COLLATION", "nameCi = 'xyz'"),
      new Case("ARITH", "age + 1 > 30"),
      // Extra cases to exercise the NE-inversion arm (YTDB-628) and LE/GE arms in
      // AnalyzedExprEvaluator / SQLBinaryCondition.tryInPlaceComparison. Not in the benchmark
      // @Param axis — these are regression guards only.
      // NE truth-table (ages 10/25/30/35/50): T/T/F/T/T → 4 true, 1 false (non-trivial).
      new Case("NE", "age <> 30"),
      // LE truth-table (ages 10/25/30/35/50): T/T/T/F/F → 3 true, 2 false (non-trivial).
      new Case("LE", "age <= 30"),
      // GE truth-table (ages 10/25/30/35/50): F/F/T/T/T → 3 true, 2 false (non-trivial).
      new Case("GE", "age >= 30"));

  /**
   * Designed sample: each triple is (age, mid, nameCi). Chosen so every predicate above has both
   * matching and non-matching rows. CI-collation is genuinely exercised ONLY on entity-backed rows:
   * the IR evaluator ({@code AnalyzedExprEvaluator.collateFor}) guards on {@code row.isEntity()}
   * before resolving the schema-class collator, and the AST path
   * ({@code SQLSuffixIdentifier.getCollate}) likewise guards on {@code currentResult.isEntity()};
   * both return {@code null} for projection rows, so the CI comparison is case-SENSITIVE on
   * projection rows (no backing entity → no collator). The values "XYZ"/"abc"/"XYZ"/"xyz"/"Other"
   * still yield both TRUE ({@code "xyz"} matches {@code nameCi = 'xyz'} case-sensitively) and
   * FALSE rows for the CI_COLLATION case on projection rows, so IR/AST parity is still non-trivial
   * on both row types — the test validates parity, not the presence of collation, on projection rows.
   */
  private record Sample(int age, String mid, String nameCi) {

  }

  private static final List<Sample> SAMPLES = List.of(
      new Sample(10, null, "XYZ"),
      new Sample(25, "m25", "abc"),
      new Sample(30, null, "XYZ"),
      new Sample(35, "m35", "xyz"),
      new Sample(50, null, "Other"));

  private static YouTrackDB db;
  private static String dbName;
  private static DatabaseSessionEmbedded session;

  /** EntityImpl-backed rows captured from a live scan (fast path). */
  private static List<Result> entityRows;

  /** Projection (non-entity) rows with the identical values (slow path). */
  private static List<Result> projectionRows;

  @BeforeClass
  public static void setup() {
    // In-memory embedded DB via the public API (AD5 spike pattern).
    dbName = "analyzed_guard_" + System.nanoTime();
    db = YourTracks.instance(".");
    db.createIfNotExists(dbName, DatabaseType.MEMORY, "admin", "admin", "admin");
    session = ((YouTrackDBImpl) db).open(dbName, "admin", "admin");

    // Schema: age INT, name STRING, mid STRING (nullable), nameCi STRING (ci collation).
    SchemaClass cls = session.getMetadata().getSchema().createClass(CLASS_NAME);
    cls.createProperty("age", PropertyType.INTEGER);
    cls.createProperty("name", PropertyType.STRING);
    cls.createProperty("mid", PropertyType.STRING);
    cls.createProperty("nameCi", PropertyType.STRING).setCollate("ci");

    // Insert the designed sample rows.
    session.begin();
    try {
      for (int i = 0; i < SAMPLES.size(); i++) {
        Sample s = SAMPLES.get(i);
        var e = session.newEntity(CLASS_NAME);
        e.setProperty("age", s.age());
        e.setProperty("name", "name" + i);
        e.setProperty("mid", s.mid()); // may be null
        e.setProperty("nameCi", s.nameCi());
      }
      session.commit();
    } catch (RuntimeException e) {
      session.rollback();
      throw e;
    }

    // Capture EntityImpl-backed rows; keep the session/tx OPEN so they stay bound.
    entityRows = new ArrayList<>();
    try (ResultSet rs = session.query("SELECT FROM " + CLASS_NAME)) {
      while (rs.hasNext()) {
        Result r = rs.next();
        // Confirm the fast-path precondition the evaluator keys on.
        assertTrue("captured row must be EntityImpl-backed",
            r instanceof ResultInternal ri && ri.asEntityOrNull() instanceof EntityImpl);
        entityRows.add(r);
      }
    }
    assertEquals("captured entity rows must match inserted sample size",
        SAMPLES.size(), entityRows.size());

    // Build projection (non-entity) rows with the identical values.
    projectionRows = new ArrayList<>();
    for (int i = 0; i < SAMPLES.size(); i++) {
      Sample s = SAMPLES.get(i);
      var pr = new ResultInternal(session);
      pr.setProperty("age", s.age());
      pr.setProperty("name", "name" + i);
      pr.setProperty("mid", s.mid()); // null → resolves to null on read (IS NULL semantics)
      pr.setProperty("nameCi", s.nameCi());
      projectionRows.add(pr);
    }
  }

  @AfterClass
  public static void tearDown() {
    if (session != null) {
      session.close();
    }
    if (db != null) {
      if (dbName != null && db.exists(dbName)) {
        db.drop(dbName);
      }
      // Do NOT call db.close() here. db is the process-wide cached YouTrackDBImpl returned by
      // YourTracks.instance(".") (cached in YTDBGraphFactory.storagePathYTDBMap keyed on ".").
      // Closing it would invalidate the shared instance for any other test or benchmark state still
      // using the same path — safe only because surefire runs classes sequentially today.
      // Dropping the owned uniquely-named DB above is the correct resource scope.
    }
    // Null the static fixtures so the forked JVM does not retain the closed session and the
    // captured rows for the rest of its lifetime (tidy lifecycle; no functional impact).
    session = null;
    db = null;
    entityRows = null;
    projectionRows = null;
  }

  /**
   * For every case in {@link #CASES}: (a) the predicate lowers to IR, and (b) the IR evaluator and
   * the AST {@code matchesFilters} agree on every sampled row, over both entity-backed and
   * projection rows. Also asserts each predicate is non-trivial (produces both TRUE and FALSE) over
   * each row set.
   */
  @Test
  public void irAndAstAgreeOnEveryBench1Case() {
    for (Case c : CASES) {
      // ---- (a) lowering succeeds (no UnsupportedAnalyzedNodeException, non-null IR). ----
      SQLWhereClause where = parseWhere("SELECT FROM " + CLASS_NAME + " WHERE " + c.sql());
      AnalyzedExpr analyzed = AnalyzedExprLowerer.lowerBoolean(where.getBaseExpression());
      assertNotNull("case " + c.name() + " (" + c.sql() + ") must lower to IR", analyzed);

      // ---- (b) IR/AST parity over both row types. ----
      int[] entityCounts = assertParity(c, analyzed, where, entityRows, "entity");
      int[] projectionCounts = assertParity(c, analyzed, where, projectionRows, "projection");

      System.out.printf(
          "[GUARD] %-12s entity{true=%d,false=%d} projection{true=%d,false=%d} -> IR==AST%n",
          c.name(), entityCounts[0], entityCounts[1], projectionCounts[0], projectionCounts[1]);
    }
  }

  /**
   * Asserts IR/AST agreement over the given rows for one case, and that the predicate is
   * non-trivial (yields at least one TRUE and one FALSE). Returns {@code {trueCount, falseCount}}
   * as observed on the (equal) IR/AST outcome.
   */
  private static int[] assertParity(
      Case c, AnalyzedExpr analyzed, SQLWhereClause where, List<Result> rows, String rowKind) {
    // Fresh context per case; PARAM binds :p so both arms see the same value.
    CommandContext ctx = newContext();
    int trueCount = 0;
    int falseCount = 0;
    for (int i = 0; i < rows.size(); i++) {
      Result row = rows.get(i);
      boolean ir = Boolean.TRUE.equals(AnalyzedExprEvaluator.evaluate(analyzed, row, ctx));
      boolean ast = where.matchesFilters(row, ctx);
      assertEquals(
          "IR/AST disagree — case " + c.name() + " (" + c.sql() + "), " + rowKind
              + " row #" + i,
          ast, ir);
      if (ir) {
        trueCount++;
      } else {
        falseCount++;
      }
    }
    // Non-triviality guard: the designed sample must exercise both outcomes for this predicate.
    assertTrue(
        "case " + c.name() + " produced no TRUE over " + rowKind + " rows (trivial predicate?)",
        trueCount > 0);
    assertTrue(
        "case " + c.name() + " produced no FALSE over " + rowKind + " rows (trivial predicate?)",
        falseCount > 0);
    return new int[] {trueCount, falseCount};
  }

  private static CommandContext newContext() {
    var ctx = new BasicCommandContext(session);
    Map<Object, Object> params = new HashMap<>();
    params.put("p", PARAM_THRESHOLD);
    ctx.setInputParameters(params);
    return ctx;
  }

  private static SQLWhereClause parseWhere(String selectSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(selectSql.getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getWhereClause();
    } catch (Exception e) {
      throw new IllegalStateException("failed to parse WHERE from: " + selectSql, e);
    }
  }
}
