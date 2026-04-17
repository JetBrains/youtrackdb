package com.jetbrains.youtrackdb.internal.core.sql.functions.stat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YouTrackDB.LocalUserCredential;
import com.jetbrains.youtrackdb.api.YouTrackDB.PredefinedLocalRole;
import com.jetbrains.youtrackdb.api.YourTracks;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionDecimal;
import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests {@link SQLFunctionDecimal}. Note: this test lives under
 * {@code sql/functions/stat/} for historical reasons; the class under test is in
 * {@code sql/functions/math/}. WHEN-FIXED: relocate this file to
 * {@code sql/functions/math/SQLFunctionDecimalTest} to match the JaCoCo package.
 */
public class SQLFunctionDecimalTest {

  private SQLFunctionDecimal function;

  @Before
  public void setup() {
    function = new SQLFunctionDecimal();
  }

  @Test
  public void testEmpty() {
    var result = function.getResult();
    assertNull(result);
  }

  @Test
  public void testFromInteger() {
    function.execute(null, null, null, new Object[] {12}, null);
    var result = function.getResult();
    assertEquals(result, BigDecimal.valueOf(12));
  }

  @Test
  public void testFromLong() {
    function.execute(null, null, null, new Object[] {1287623847384L}, null);
    var result = function.getResult();
    assertEquals(new BigDecimal(1287623847384L), result);
  }

  @Test
  public void testFromString() {
    var initial = "12324124321234543256758654.76543212345676543254356765434567654";
    function.execute(null, null, null, new Object[] {initial}, null);
    var result = function.getResult();
    assertEquals(new BigDecimal(initial), result);
  }

  @Test
  public void testFromBigDecimalReturnsSameInstance() {
    // BigDecimal passes through the identity branch — the exact reference is preserved.
    var initial = new BigDecimal("123.456");
    var ret = function.execute(null, null, null, new Object[] {initial}, null);
    assertSame("Expected pass-through of the BigDecimal reference", initial, ret);
    assertSame(initial, function.getResult());
  }

  @Test
  public void testFromBigIntegerIsPromoted() {
    var bi = new BigInteger("987654321012345678");
    var ret = function.execute(null, null, null, new Object[] {bi}, null);
    assertTrue("Expected BigDecimal, got " + ret.getClass(), ret instanceof BigDecimal);
    assertEquals(0, new BigDecimal(bi).compareTo((BigDecimal) ret));
  }

  @Test
  public void testFromShortUsesNumberDoubleBranch() {
    // Short is neither BigDecimal, BigInteger, Integer, nor Long — it falls through to the
    // generic Number branch (BigDecimal.valueOf(n.doubleValue())).
    var ret = function.execute(null, null, null, new Object[] {(short) 42}, null);
    assertTrue(ret instanceof BigDecimal);
    assertEquals(0, BigDecimal.valueOf(42.0).compareTo((BigDecimal) ret));
  }

  @Test
  public void testFromFloatUsesNumberDoubleBranch() {
    // Float also falls through to the generic Number branch.
    var ret = function.execute(null, null, null, new Object[] {1.5f}, null);
    assertTrue(ret instanceof BigDecimal);
    assertEquals(0, BigDecimal.valueOf(1.5).compareTo((BigDecimal) ret));
  }

  @Test
  public void testFromDoubleUsesNumberDoubleBranch() {
    var ret = function.execute(null, null, null, new Object[] {2.75d}, null);
    assertTrue(ret instanceof BigDecimal);
    assertEquals(0, BigDecimal.valueOf(2.75).compareTo((BigDecimal) ret));
  }

  @Test
  public void testFromMalformedStringIsSwallowedAndReturnsNull() {
    // The try/catch around `new BigDecimal(s)` must swallow parse errors and set result = null.
    var ret = function.execute(null, null, null, new Object[] {"not a number"}, null);
    assertNull(ret);
    assertNull(function.getResult());
  }

  @Test
  public void testFromNullReturnsNull() {
    var ret = function.execute(null, null, null, new Object[] {null}, null);
    assertNull(ret);
    assertNull(function.getResult());
  }

  @Test
  public void testFromUnsupportedTypeFallsThroughToResultSoFar() {
    // An Object that is neither Number nor String — none of the branches assign result,
    // so result stays null on the first call. This pins the fall-through semantics.
    var ret = function.execute(null, null, null, new Object[] {new Object()}, null);
    assertNull(ret);
  }

  @Test
  public void testUnsupportedTypeAfterValidInputLeaksPreviousResult() {
    // WHEN-FIXED: SQLFunctionDecimal.execute() does NOT reset `result` at the start of
    // every call — a second call with an unsupported type silently returns (and leaves
    // accessible) the PREVIOUS call's value. The defensible fix is to assign
    // `result = null` at the top of execute(); once applied, both assertions below
    // should become `assertNull(...)`.
    function.execute(null, null, null, new Object[] {12}, null);
    var ret = function.execute(null, null, null, new Object[] {new Object()}, null);
    assertEquals(BigDecimal.valueOf(12), ret);
    assertEquals(BigDecimal.valueOf(12), function.getResult());
  }

  @Test
  public void testAggregateResultsIsAlwaysFalse() {
    assertFalse(function.aggregateResults());
  }

  @Test
  public void testGetSyntaxAdvertisesFunctionShape() {
    var syntax = function.getSyntax(null);
    assertNotNull(syntax);
    assertTrue("Expected 'decimal(' prefix, got: " + syntax, syntax.startsWith("decimal("));
  }

  @Test
  public void testFromQuery() {
    try (var ytdb = (YouTrackDBImpl) YourTracks.instance(
        DbTestBase.getBaseDirectoryPathStr(getClass()))) {
      ytdb.create("test", DatabaseType.MEMORY,
          new LocalUserCredential("admin", DbTestBase.ADMIN_PASSWORD, PredefinedLocalRole.ADMIN));
      try (var db = ytdb.open("test", "admin", DbTestBase.ADMIN_PASSWORD)) {
        var initial = "12324124321234543256758654.76543212345676543254356765434567654";
        db.executeInTx(transaction -> {
          try (var result = transaction.query("select decimal('" + initial + "') as decimal")) {
            assertEquals(new BigDecimal(initial), result.next().getProperty("decimal"));
          }
        });
      }
      ytdb.drop("test");
    }
  }
}
