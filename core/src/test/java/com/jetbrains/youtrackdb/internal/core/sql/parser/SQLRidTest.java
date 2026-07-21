package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.io.ByteArrayInputStream;
import java.util.Map;
import org.junit.Test;

/**
 * Unit tests for {@link SQLRid} value resolution, focused on the interaction
 * between {@link SQLRid#setExpression} and the {@code legacy} flag.
 *
 * <p>{@link SQLRid#toRecordId} already honours the expression guard; these tests
 * cover the observers named in the {@code setExpression} comment — {@code
 * toString}, {@code equals}, {@code copy}, and expression-branch serialization.
 */
public class SQLRidTest {

  private static final BasicCommandContext CTX = new BasicCommandContext();

  /** Parses a WHERE clause and returns the value-side of its {@code @rid = ...}. */
  private static SQLExpression parseRidValueExpression(String whereSql) {
    try {
      var parser = new YouTrackDBSql(new ByteArrayInputStream(
          ("SELECT FROM X WHERE " + whereSql).getBytes()));
      var stm = (SQLSelectStatement) parser.parse();
      return stm.getWhereClause().findRidEquality();
    } catch (Exception e) {
      throw new AssertionError("Failed to parse: " + whereSql, e);
    }
  }

  /** Builds a legacy {@code #c:p} RID node as the parser would for a literal slot. */
  private static SQLRid legacyLiteralRid(int collection, int position) {
    var rid = new SQLRid(-1);
    var c = new SQLInteger(-1);
    c.setValue(collection);
    var p = new SQLInteger(-1);
    p.setValue(position);
    rid.setCollection(c);
    rid.setPosition(p);
    rid.setLegacy(true);
    return rid;
  }

  /** Promotes {@code exprSql} onto a legacy literal, matching MATCH planner usage. */
  private static SQLRid promoteLegacyLiteral(SQLRid rid, String exprSql) {
    rid.setExpression(parseRidValueExpression(exprSql));
    return rid;
  }

  /**
   * Regression guard for the setExpression / legacy interaction.
   * {@link SQLRid#toRecordId} short-circuits to the literal
   * {@code collection:position} pair whenever {@code legacy} is true (the
   * {@code if (legacy || ...)} guard). Setting an expression on a legacy
   * instance must therefore flip {@code legacy} off, or the expression is
   * ignored. Here the legacy pair is never set (null collection/position), so
   * without the reset the guard would dereference a null collection and throw;
   * with the reset the expression ({@code #12:0}) is evaluated and returned.
   */
  @Test
  public void setExpression_flipsLegacyOff_soToRecordIdUsesExpression() {
    var expr = parseRidValueExpression("@rid = #12:0");
    var rid = new SQLRid(-1);
    rid.setLegacy(true);
    rid.setExpression(expr);

    var recordId = rid.toRecordId((Result) null, CTX);

    assertNotNull("expression branch should resolve the RID", recordId);
    assertEquals("#12:0", recordId.toString());
  }

  /**
   * setExpression on a node that already holds a legacy {@code #c:p} literal
   * must fully transition it to the expression form: the new expression
   * resolves the RID and the stale collection/position pair is cleared, so no
   * contradictory hybrid state survives.
   */
  @Test
  public void setExpression_onLegacyLiteral_clearsPairAndUsesExpression() {
    var rid = legacyLiteralRid(99, 9);
    promoteLegacyLiteral(rid, "@rid = #12:0");

    assertEquals("#12:0", rid.toRecordId((Result) null, CTX).toString());
    assertNull("legacy collection must be cleared", rid.collection);
    assertNull("legacy position must be cleared", rid.position);
  }

  /**
   * {@link SQLRid#toString(String)} on a promoted node must render via the
   * expression branch. Without clearing the stale {@code #99:9} pair, the
   * legacy path would still print the old literal or NPE on a null collection.
   */
  @Test
  public void setExpression_onLegacyLiteral_toStringUsesExpressionNotStalePair() {
    var rid = promoteLegacyLiteral(legacyLiteralRid(99, 9), "@rid = #12:0");

    var rendered = rid.toString("");

    assertFalse("stale legacy pair must not appear in toString", rendered.contains("99:9"));
    assertTrue("expression text must appear in toString", rendered.contains("12:0"));
  }

  /**
   * Parametric {@link SQLRid#toString(Map, StringBuilder)} must take the
   * {@code {"@rid": <expr>}} branch after promotion, not the legacy
   * {@code #c:p} literal form.
   */
  @Test
  public void setExpression_onLegacyLiteral_parametricToStringUsesExpressionBranch() {
    var rid = promoteLegacyLiteral(legacyLiteralRid(99, 9), "@rid = #12:0");
    var builder = new StringBuilder();

    rid.toString(Map.of(), builder);

    var rendered = builder.toString();
    assertFalse("stale legacy pair must not appear", rendered.contains("99:9"));
    assertTrue("must use expression JSON shape", rendered.contains("{\"@rid\":"));
    assertTrue("expression RID must appear", rendered.contains("12:0"));
  }

  /**
   * After promotion, {@link SQLRid#copy()} must reproduce pure expression form:
   * {@code legacy=false}, no literal pair, and a deep-copied {@code expression}.
   * {@code copy()} mirrors all fields as-is; if {@code setExpression} left a stale
   * pair, the copy would inherit it and compare unequal to expression-only nodes
   * with the same RID.
   */
  @Test
  public void setExpression_onLegacyLiteral_copyReflectsExpressionForm() {
    var rid = promoteLegacyLiteral(legacyLiteralRid(99, 9), "@rid = #12:0");
    var copy = rid.copy();

    assertNull(copy.collection);
    assertNull(copy.position);
    assertFalse(copy.legacy);
    assertNotNull(copy.expression);
    assertEquals(rid, copy);
    assertEquals("#12:0", copy.toRecordId((Result) null, CTX).toString());
    assertFalse(copy.toString("").contains("99:9"));
  }

  /**
   * Two promoted nodes carrying the same expression must be equal even when one
   * was transitioned from a different legacy literal. Stale pairs must not
   * participate in {@link SQLRid#equals}.
   */
  @Test
  public void setExpression_onLegacyLiteral_equalsIgnoresFormerLegacyPair() {
    var fromLegacy = promoteLegacyLiteral(legacyLiteralRid(99, 9), "@rid = #12:0");
    var expressionOnly = new SQLRid(-1);
    expressionOnly.setExpression(parseRidValueExpression("@rid = #12:0"));

    assertEquals("same expression must compare equal after promotion", fromLegacy, expressionOnly);

    var differentLegacy = promoteLegacyLiteral(legacyLiteralRid(1, 1), "@rid = #12:0");
    assertEquals(fromLegacy, differentLegacy);

    var otherRid = promoteLegacyLiteral(legacyLiteralRid(99, 9), "@rid = #13:1");
    assertNotEquals("different expressions must not compare equal", fromLegacy, otherRid);
  }
}
