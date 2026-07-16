package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.core.sql.SQLEngine;
import com.jetbrains.youtrackdb.internal.core.sql.executor.ResultInternal;
import com.jetbrains.youtrackdb.internal.core.sql.executor.match.builder.MatchWhereBuilder;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Runtime-behavior tests for the collation-aware string predicates: the {@code
 * SQLContainsTextCondition} collate transform, the new {@link SQLEndsWithCondition}, and the
 * find-mode {@link SQLMatchesCondition}.
 *
 * <p>The schema fixture is a {@code Doc} class with a default-collated {@code cs} property
 * (case-sensitive) and a {@code ci}-collated {@code ci} property (case-insensitive). The two
 * properties let every test assert the no-change baseline (default) beside the intended behavior
 * change (ci) in one place.
 *
 * <p>CONTAINSTEXT is verified across every eval path the collate transform touches. The
 * "index-backed vs scan-backed" consistency is asserted as agreement between the
 * {@code evaluate(Identifiable)} path (MATCH edge filters) and the {@code evaluate(Result)} path
 * (SELECT filters): a native FULLTEXT index — the only truly index-backed CONTAINSTEXT lookup — is
 * Lucene-only and excluded from the core build ({@code DefaultIndexFactory} serves only UNIQUE /
 * NOTUNIQUE, and CONTAINSTEXT is not BTREE-index-aware), so the two production scan paths are the
 * meaningful consistency surface here. The legacy {@code QueryOperatorContainsText} is checked
 * through {@link SQLEngine#parseCondition}, its only reachable entry point; it stays consistent
 * because {@code SQLFilterCondition} already applies the collate to both operands before invoking
 * the operator.
 */
public class StringPredicateCollationTest extends DbTestBase {

  private final MatchWhereBuilder builder = new MatchWhereBuilder();

  @Override
  public void beforeTest() throws Exception {
    super.beforeTest();
    var cls = session.getMetadata().getSchema().createClass("Doc");
    cls.createProperty("cs", PropertyType.STRING); // default collate — case-sensitive
    cls.createProperty("ci", PropertyType.STRING).setCollate("ci"); // case-insensitive
  }

  // ── CONTAINSTEXT via SELECT: default unchanged, ci now case-insensitive ──

  /**
   * Regression guard: CONTAINSTEXT on a default-collated property stays case-sensitive, so the
   * exact-case substring matches and a differently-cased one does not. Pins the "no change on
   * default collation" half of the collate-transform behavior.
   */
  @Test
  public void containsText_defaultCollation_staysCaseSensitive() {
    insertDoc("cs", "Hello World");
    assertEquals(1, count("SELECT FROM Doc WHERE cs CONTAINSTEXT 'World'"));
    assertEquals(0, count("SELECT FROM Doc WHERE cs CONTAINSTEXT 'world'"));
    assertEquals(0, count("SELECT FROM Doc WHERE cs CONTAINSTEXT 'WORLD'"));
  }

  /**
   * The intended behavior change: CONTAINSTEXT on a {@code ci}-collated property
   * is now case-insensitive, so every case of the substring matches. Before the collate transform
   * this returned only the exact-case row.
   */
  @Test
  public void containsText_ciCollation_isNowCaseInsensitive() {
    insertDoc("ci", "Hello World");
    assertEquals(1, count("SELECT FROM Doc WHERE ci CONTAINSTEXT 'World'"));
    assertEquals(1, count("SELECT FROM Doc WHERE ci CONTAINSTEXT 'world'"));
    assertEquals(1, count("SELECT FROM Doc WHERE ci CONTAINSTEXT 'WORLD'"));
  }

  /**
   * The two production scan paths must return the same answer for the same CONTAINSTEXT predicate on
   * a {@code ci} property: {@code evaluate(Identifiable)} (the path MATCH edge filters take) and
   * {@code evaluate(Result)} (the path SELECT filters take). A divergence here is the failure
   * mode — one query type matching case-insensitively while the other stays case-sensitive.
   */
  @Test
  public void containsText_ciCollation_identifiableAndResultPathsAgree() {
    session.begin();
    var match = session.newEntity("Doc");
    match.setProperty("ci", "Hello World");
    var noMatch = session.newEntity("Doc");
    noMatch.setProperty("ci", "Goodbye");

    var ctx = ctx();
    var cond = builder.containsText("ci", "world"); // lowercase probe; ci makes it match "World"

    assertTrue("Identifiable path must match case-insensitively on a ci property",
        cond.evaluate((Identifiable) match, ctx));
    assertFalse(cond.evaluate((Identifiable) noMatch, ctx));
    assertTrue("Result path must agree with the Identifiable path",
        cond.evaluate(new ResultInternal(session, match), ctx));
    assertFalse(cond.evaluate(new ResultInternal(session, noMatch), ctx));
    session.commit();
  }

  /**
   * The {@code any()} eval path resolves each property's own collation: {@code any() CONTAINSTEXT}
   * matches the {@code ci} property case-insensitively but the default property only case-sensitively.
   * A lowercase probe therefore matches through the ci property but not through the default one.
   */
  @Test
  public void containsText_anyFunction_honorsPerPropertyCollation() {
    session.begin();
    var e = session.newEntity("Doc");
    e.setProperty("ci", "Hello"); // ci property carries "Hello"
    e.setProperty("cs", "World"); // default property carries "World"
    session.commit();

    // 'hello' matches the ci property case-insensitively.
    assertEquals(1, count("SELECT FROM Doc WHERE any() CONTAINSTEXT 'hello'"));
    // 'world' does not match the default property case-sensitively, and the ci value has no 'world'.
    assertEquals(0, count("SELECT FROM Doc WHERE any() CONTAINSTEXT 'world'"));
  }

  /**
   * The {@code all()} eval path also resolves per-property collation. With a substring present in
   * both properties (case-insensitively in the ci one, exactly in the default one), all() matches;
   * with a differently-cased probe the default property fails the case-sensitive check, so all()
   * does not match even though the ci property would.
   */
  @Test
  public void containsText_allFunction_honorsPerPropertyCollation() {
    session.begin();
    var e = session.newEntity("Doc");
    e.setProperty("ci", "ABCxyz");
    e.setProperty("cs", "xyzDEF");
    session.commit();

    // 'xyz' is in both (ci: case-insensitively; default: exactly) → all() matches.
    assertEquals(1, count("SELECT FROM Doc WHERE all() CONTAINSTEXT 'xyz'"));
    // 'XYZ' matches the ci property but not the case-sensitive default one → all() does not match.
    assertEquals(0, count("SELECT FROM Doc WHERE all() CONTAINSTEXT 'XYZ'"));
  }

  /**
   * The legacy {@link com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorContainsText}
   * path (reachable only through {@link SQLEngine#parseCondition}) stays consistent with the modern
   * AST node: case-insensitive on the {@code ci} property, case-sensitive on the default one. This
   * consistency comes from {@code SQLFilterCondition}, which applies the collate to both operands
   * before invoking the operator, so the operator itself needs no change.
   */
  @Test
  public void legacyContainsText_isCollationConsistentWithModernPath() {
    session.begin();
    var doc = session.newEntity("Doc");
    doc.setProperty("ci", "Hello World");
    doc.setProperty("cs", "Hello World");
    var ctx = ctx();

    var ciFilter = SQLEngine.parseCondition("ci containstext 'world'", ctx);
    assertEquals("legacy path must be case-insensitive on a ci property",
        Boolean.TRUE, ciFilter.getRootCondition().evaluate(doc, null, ctx));

    var csFilter = SQLEngine.parseCondition("cs containstext 'world'", ctx);
    assertEquals("legacy path must stay case-sensitive on a default property",
        Boolean.FALSE, csFilter.getRootCondition().evaluate(doc, null, ctx));
    session.commit();
  }

  // ── ENDSWITH evaluation (SQLEndsWithCondition) ──

  /**
   * {@link SQLEndsWithCondition} honors the property collation on both eval overloads: case-sensitive
   * on the default property, case-insensitive on the {@code ci} property. The Result overload is
   * asserted alongside the Identifiable one so both production paths are covered.
   */
  @Test
  public void endsWith_honorsCollationOnBothEvalPaths() {
    session.begin();
    var e = session.newEntity("Doc");
    e.setProperty("cs", "Johnson");
    e.setProperty("ci", "Johnson");
    var ctx = ctx();

    // default collation — case-sensitive
    assertTrue(builder.endsWith("cs", "son").evaluate((Identifiable) e, ctx));
    assertFalse(builder.endsWith("cs", "SON").evaluate((Identifiable) e, ctx));

    // ci collation — case-insensitive, on both eval overloads
    assertTrue(builder.endsWith("ci", "SON").evaluate((Identifiable) e, ctx));
    assertTrue(builder.endsWith("ci", "SON").evaluate(new ResultInternal(session, e), ctx));
    // a non-suffix still does not match
    assertFalse(builder.endsWith("ci", "John").evaluate((Identifiable) e, ctx));
    session.commit();
  }

  // ── regex evaluation (find-mode SQLMatchesCondition) ──

  /**
   * The find-mode {@link SQLMatchesCondition} matches a pattern anywhere in the value (Gremlin
   * {@code Text.regex} semantics), unlike the parser's full-match MATCHES which is anchored to the
   * whole value. Regex stays case-sensitive even on a {@code ci} property, because collate-transforming
   * a pattern would change its meaning. The parsed full-match MATCHES on the same data confirms the
   * find-vs-full-match distinction.
   */
  @Test
  public void matchesRegex_usesFindSemanticsAndStaysCaseSensitive() {
    session.begin();
    var e = session.newEntity("Doc");
    e.setProperty("ci", "Hello World");
    var ctx = ctx();

    // find(): a substring pattern matches anywhere, not just a full-value match
    assertTrue(builder.matchesRegex("ci", "World").evaluate((Identifiable) e, ctx));
    assertTrue(builder.matchesRegex("ci", "o W").evaluate((Identifiable) e, ctx));
    assertTrue(builder.matchesRegex("ci", "Hello.*").evaluate((Identifiable) e, ctx));
    // case-sensitive despite the ci collation: a lowercase pattern does not match
    assertFalse(builder.matchesRegex("ci", "world").evaluate((Identifiable) e, ctx));
    session.commit();

    // Contrast: parsed MATCHES is a full (anchored) match, so "World" alone does not match the
    // whole "Hello World" value — the find-mode node above did.
    assertEquals(0, count("SELECT FROM Doc WHERE ci MATCHES 'World'"));
  }

  // ── splitForAggregation reconstruction (aggregate branch) ──

  /**
   * {@link SQLEndsWithCondition#splitForAggregation} reconstructs the node field-by-field when the
   * condition is aggregate. Both operands must survive the reconstruction: the SQL engine takes this
   * path when it deep-copies a plan carrying the node, so a dropped operand would be a silent
   * wrong-result bug. The left operand is swapped for an aggregate expression so the reconstruction
   * branch (not the non-aggregate passthrough) runs.
   */
  @Test
  public void endsWith_splitForAggregationPreservesBothOperands() {
    var node = (SQLEndsWithCondition) builder.endsWith("cs", "son");
    node.setLeft(aggregateLeftExpression());
    var ctx = ctx();
    assertTrue("precondition: the node must be aggregate to exercise reconstruction",
        node.isAggregate(session));

    var split = node.splitForAggregation(new AggregateProjectionSplit(), ctx);
    assertTrue(split instanceof SQLEndsWithCondition);
    var reconstructed = (SQLEndsWithCondition) split;
    assertNotSame("reconstruction must produce a fresh node", node, reconstructed);
    assertNotNull("left operand must survive reconstruction", reconstructed.getLeft());
    assertNotNull("right operand (suffix) must survive reconstruction", reconstructed.getRight());
  }

  /**
   * {@link SQLMatchesCondition#splitForAggregation} must carry {@code findMode} through the aggregate
   * reconstruction. Dropping it would silently turn a find-mode regex into a full-match one in a
   * copied plan.
   */
  @Test
  public void matchesRegex_splitForAggregationPreservesFindMode() {
    var node = (SQLMatchesCondition) builder.matchesRegex("cs", "x.*");
    node.setExpression(aggregateLeftExpression());
    var ctx = ctx();
    assertTrue("precondition: the node must be aggregate to exercise reconstruction",
        node.isAggregate(session));

    var split = node.splitForAggregation(new AggregateProjectionSplit(), ctx);
    assertTrue(split instanceof SQLMatchesCondition);
    var reconstructed = (SQLMatchesCondition) split;
    assertNotSame(node, reconstructed);
    assertTrue("find mode must survive splitForAggregation reconstruction",
        reconstructed.isFindMode());
  }

  // ── helpers ──

  /** Inserts one {@code Doc} with a single property set, in its own transaction. */
  private void insertDoc(String property, String value) {
    session.begin();
    var e = session.newEntity("Doc");
    e.setProperty(property, value);
    session.commit();
  }

  /** Runs a query in its own transaction and returns the row count. */
  private int count(String sql) {
    session.begin();
    var rs = session.query(sql);
    var n = 0;
    while (rs.hasNext()) {
      rs.next();
      n++;
    }
    rs.close();
    session.commit();
    return n;
  }

  private CommandContext ctx() {
    var c = new BasicCommandContext();
    c.setDatabaseSession(session);
    return c;
  }

  /**
   * Parses an aggregate expression ({@code max(cs)}) out of a throwaway SELECT projection so it can
   * be injected as a condition operand — the only way to make the hand-built AST nodes report
   * {@code isAggregate == true} and so drive their {@code splitForAggregation} reconstruction branch.
   *
   * <p>The projection item's expression is the {@link SQLExpression} directly, without any of the
   * boolean-wrapper nesting (single-child OR/AND blocks, a pass-through NOT block, parenthesis
   * blocks) the grammar layers around a lone condition in a WHERE clause. That keeps the helper
   * independent of the exact WHERE-clause AST shape.
   */
  private static SQLExpression aggregateLeftExpression() {
    var sql = "SELECT max(cs) FROM Doc";
    var parser = new YouTrackDBSql(new ByteArrayInputStream(sql.getBytes(StandardCharsets.UTF_8)));
    SQLSelectStatement stmt;
    try {
      stmt = (SQLSelectStatement) parser.parse();
    } catch (ParseException e) {
      throw new IllegalStateException("failed to parse aggregate fixture", e);
    }
    return stmt.getProjection().getItems().getFirst().getExpression();
  }
}
