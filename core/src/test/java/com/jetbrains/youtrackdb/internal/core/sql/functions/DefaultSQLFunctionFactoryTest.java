package com.jetbrains.youtrackdb.internal.core.sql.functions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionDifference;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionDistinct;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionFirst;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionIntersect;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionLast;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionList;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionMap;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionSet;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionSymmetricDifference;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionTraversedEdge;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionTraversedElement;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionTraversedVertex;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionUnionAll;
import com.jetbrains.youtrackdb.internal.core.sql.functions.geo.SQLFunctionDistance;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionAstar;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionBoth;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionBothE;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionBothV;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionDijkstra;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionIn;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionInE;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionInV;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionOut;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionOutE;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionOutV;
import com.jetbrains.youtrackdb.internal.core.sql.functions.graph.SQLFunctionShortestPath;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionAbsoluteValue;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionAverage;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionDecimal;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionEval;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionInterval;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionMax;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionMin;
import com.jetbrains.youtrackdb.internal.core.sql.functions.math.SQLFunctionSum;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionAssert;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionCoalesce;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionCount;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionDate;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionDecode;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionEncode;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionIf;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionIfNull;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionIndexKeySize;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionStrcmpci;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionSysdate;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionThrowCME;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionUUID;
import com.jetbrains.youtrackdb.internal.core.sql.functions.result.SQLFunctionDetachResult;
import com.jetbrains.youtrackdb.internal.core.sql.functions.sequence.SQLFunctionSequence;
import com.jetbrains.youtrackdb.internal.core.sql.functions.stat.SQLFunctionMedian;
import com.jetbrains.youtrackdb.internal.core.sql.functions.stat.SQLFunctionMode;
import com.jetbrains.youtrackdb.internal.core.sql.functions.stat.SQLFunctionPercentile;
import com.jetbrains.youtrackdb.internal.core.sql.functions.stat.SQLFunctionStandardDeviation;
import com.jetbrains.youtrackdb.internal.core.sql.functions.stat.SQLFunctionVariance;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLFunctionConcat;
import com.jetbrains.youtrackdb.internal.core.sql.functions.text.SQLFunctionFormat;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the built-in {@link DefaultSQLFunctionFactory}: verifies every function the factory advertises
 * is actually registered and creatable, that registration-mode (instance vs Class object) matches the
 * factory source, and that unknown names raise the documented error.
 */
public class DefaultSQLFunctionFactoryTest extends DbTestBase {

  private DefaultSQLFunctionFactory factory;

  @Before
  public void initFactory() {
    factory = new DefaultSQLFunctionFactory();
    factory.registerDefaultFunctions(session);
  }

  /**
   * All function NAME constants the factory advertises as registered. This list mirrors
   * {@link DefaultSQLFunctionFactory#registerDefaultFunctions} verbatim — keeping it in a single
   * array makes it trivial to spot missing or duplicated entries in the source file.
   */
  private static final String[] ALL_NAMES = {
      SQLFunctionAverage.NAME,
      SQLFunctionCoalesce.NAME,
      SQLFunctionCount.NAME,
      SQLFunctionDate.NAME,
      SQLFunctionDecode.NAME,
      SQLFunctionDifference.NAME,
      SQLFunctionSymmetricDifference.NAME,
      SQLFunctionDistance.NAME,
      SQLFunctionDistinct.NAME,
      SQLFunctionEncode.NAME,
      SQLFunctionEval.NAME,
      SQLFunctionFirst.NAME,
      SQLFunctionFormat.NAME,
      SQLFunctionTraversedEdge.NAME,
      SQLFunctionTraversedElement.NAME,
      SQLFunctionTraversedVertex.NAME,
      SQLFunctionIf.NAME,
      SQLFunctionAssert.NAME,
      SQLFunctionIfNull.NAME,
      SQLFunctionIntersect.NAME,
      SQLFunctionLast.NAME,
      SQLFunctionList.NAME,
      SQLFunctionMap.NAME,
      SQLFunctionMax.NAME,
      SQLFunctionMin.NAME,
      SQLFunctionInterval.NAME,
      SQLFunctionSet.NAME,
      SQLFunctionSysdate.NAME,
      SQLFunctionSum.NAME,
      SQLFunctionUnionAll.NAME,
      SQLFunctionMode.NAME,
      SQLFunctionPercentile.NAME,
      SQLFunctionMedian.NAME,
      SQLFunctionVariance.NAME,
      SQLFunctionStandardDeviation.NAME,
      SQLFunctionUUID.NAME,
      SQLFunctionConcat.NAME,
      SQLFunctionDecimal.NAME,
      SQLFunctionSequence.NAME,
      SQLFunctionAbsoluteValue.NAME,
      SQLFunctionIndexKeySize.NAME,
      SQLFunctionStrcmpci.NAME,
      SQLFunctionThrowCME.NAME,
      SQLFunctionOut.NAME,
      SQLFunctionIn.NAME,
      SQLFunctionBoth.NAME,
      SQLFunctionOutE.NAME,
      SQLFunctionOutV.NAME,
      SQLFunctionInE.NAME,
      SQLFunctionInV.NAME,
      SQLFunctionBothE.NAME,
      SQLFunctionBothV.NAME,
      SQLFunctionShortestPath.NAME,
      SQLFunctionDijkstra.NAME,
      SQLFunctionAstar.NAME,
      SQLFunctionDetachResult.NAME
  };

  @Test
  public void allRegisteredFunctionsAreCreatable() {
    // The factory must produce a non-null SQLFunction for every advertised name.
    for (var name : ALL_NAMES) {
      var fn = factory.createFunction(name.toLowerCase(java.util.Locale.ENGLISH), session);
      assertNotNull("Expected registered function for name: " + name, fn);
    }
  }

  @Test
  public void hasFunctionTrueForAllRegistered() {
    // hasFunction must agree with createFunction — any mismatch would surface here.
    for (var name : ALL_NAMES) {
      assertTrue(
          "Expected hasFunction=true for: " + name,
          factory.hasFunction(name.toLowerCase(java.util.Locale.ENGLISH), session));
    }
  }

  @Test
  public void getFunctionNamesContainsAllRegistered() {
    var names = factory.getFunctionNames(session);
    for (var name : ALL_NAMES) {
      assertTrue(
          "getFunctionNames should include: " + name,
          names.contains(name.toLowerCase(java.util.Locale.ENGLISH)));
    }
    // Sanity check — the exact count pins unintended additions/removals.
    assertEquals(ALL_NAMES.length, names.size());
  }

  @Test
  public void functionNamesHaveNoDuplicates() {
    // A duplicate entry in registerDefaultFunctions would register the same key
    // twice and silently replace the first; assert the source list is unique.
    var seen = new java.util.HashSet<String>();
    for (var name : ALL_NAMES) {
      var lower = name.toLowerCase(java.util.Locale.ENGLISH);
      assertTrue(
          "Duplicate function name in registerDefaultFunctions: " + name, seen.add(lower));
    }
  }

  @Test
  public void coalesceRegisteredAsInstanceReturnsSameObject() {
    // SQLFunctionCoalesce is registered as `new SQLFunctionCoalesce()` — two
    // createFunction calls must return the same singleton instance.
    var a = factory.createFunction(SQLFunctionCoalesce.NAME, session);
    var b = factory.createFunction(SQLFunctionCoalesce.NAME, session);
    assertSame("Instance-registered functions must be reused", a, b);
  }

  @Test
  public void countRegisteredAsClassReturnsFreshInstance() {
    // SQLFunctionCount is registered as `SQLFunctionCount.class` — every
    // createFunction call must produce a new instance (stateful function model).
    var a = factory.createFunction(SQLFunctionCount.NAME, session);
    var b = factory.createFunction(SQLFunctionCount.NAME, session);
    assertNotNull(a);
    assertNotNull(b);
    assertTrue(a instanceof SQLFunctionCount);
    assertTrue(b instanceof SQLFunctionCount);
    // They must be distinct objects (a != b) because newInstance is called each time.
    assertTrue("Class-registered functions must produce new instances each call", a != b);
  }

  @Test
  public void createFunctionUnknownNameThrowsCommandExecution() {
    try {
      factory.createFunction("definitelyNotARegisteredFunction", session);
      fail("Expected CommandExecutionException for unknown function");
    } catch (CommandExecutionException expected) {
      assertTrue(expected.getMessage().contains("definitelyNotARegisteredFunction"));
    }
  }
}
