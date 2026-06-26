package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.io.ByteArrayInputStream;
import org.junit.Test;

/**
 * Unit tests for {@link SQLRid} value resolution, focused on the interaction
 * between {@link SQLRid#setExpression} and the {@code legacy} flag.
 */
public class SQLRidTest {

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

    var recordId = rid.toRecordId((Result) null, new BasicCommandContext());

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
    var rid = new SQLRid(-1);
    var c = new SQLInteger(-1);
    c.setValue(99);
    var p = new SQLInteger(-1);
    p.setValue(9);
    rid.setCollection(c);
    rid.setPosition(p);
    rid.setLegacy(true);

    rid.setExpression(parseRidValueExpression("@rid = #12:0"));

    assertEquals("#12:0",
        rid.toRecordId((Result) null, new BasicCommandContext()).toString());
    assertNull("legacy collection must be cleared", rid.collection);
    assertNull("legacy position must be cleared", rid.position);
  }
}
