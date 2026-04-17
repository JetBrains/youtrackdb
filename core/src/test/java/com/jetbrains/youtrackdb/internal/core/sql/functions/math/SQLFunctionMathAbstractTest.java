package com.jetbrains.youtrackdb.internal.core.sql.functions.math;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import java.math.BigDecimal;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises the two helpers on {@link SQLFunctionMathAbstract} that are {@code protected} and
 * not directly reached from the current concrete subclasses ({@link SQLFunctionSum} et al.):
 *
 * <ul>
 *   <li>{@code getContextValue(Object, Class)} — numeric downcasting / widening via the
 *       deprecated {@code new Float(...)} / {@code new Double(...)} constructors,
 *   <li>{@code getClassWithMorePrecision(Class, Class)} — the precision lattice used when
 *       tracking the best representation across a stream of heterogeneous numbers.
 * </ul>
 *
 * These helpers are still public-ish API surface of the abstract class (exposed via inheritance),
 * so they need direct coverage. A trivial {@link TestMathSubclass} exposes them for the tests.
 *
 * <p>Also checks {@link SQLFunctionMathAbstract#aggregateResults()} behavior when the concrete
 * subclass does not override it: configuredParameters length == 1 → true, otherwise false.
 */
public class SQLFunctionMathAbstractTest {

  private TestMathSubclass fn;

  @Before
  public void setUp() {
    fn = new TestMathSubclass();
  }

  // ---- getContextValue ----

  @Test
  public void getContextValueReturnsSameInstanceWhenAlreadyTargetClass() {
    // Fast path: iClass == iContext.getClass() → no conversion, original instance returned.
    Integer original = Integer.valueOf(42);
    Number result = fn.callGetContextValue(original, Integer.class);
    assertSame(original, result);
  }

  @Test
  public void getContextValueConvertsIntegerToLong() {
    Number result = fn.callGetContextValue(Integer.valueOf(5), Long.class);
    assertTrue(result instanceof Long);
    assertEquals(5L, result.longValue());
  }

  @Test
  public void getContextValueConvertsIntegerToShort() {
    Number result = fn.callGetContextValue(Integer.valueOf(7), Short.class);
    assertTrue(result instanceof Short);
    assertEquals((short) 7, result.shortValue());
  }

  @Test
  public void getContextValueConvertsIntegerToFloat() {
    Number result = fn.callGetContextValue(Integer.valueOf(3), Float.class);
    assertTrue(result instanceof Float);
    assertEquals(3.0f, result.floatValue(), 0.0f);
  }

  @Test
  public void getContextValueConvertsIntegerToDouble() {
    Number result = fn.callGetContextValue(Integer.valueOf(9), Double.class);
    assertTrue(result instanceof Double);
    assertEquals(9.0, result.doubleValue(), 0.0);
  }

  @Test
  public void getContextValueFallsThroughWhenTargetClassIsUnhandled() {
    // A target class not in the switch (e.g. BigDecimal) leaves iContext unchanged:
    // the method returns the original Number cast, not a converted instance.
    Integer original = Integer.valueOf(11);
    Number result = fn.callGetContextValue(original, BigDecimal.class);
    assertSame("Unhandled target class must return the original reference", original, result);
  }

  // ---- getClassWithMorePrecision ----

  @Test
  public void sameClassReturnsThatClassAndExitsEarly() {
    assertEquals(Integer.class, fn.callGetClassWithMorePrecision(Integer.class, Integer.class));
    assertEquals(BigDecimal.class,
        fn.callGetClassWithMorePrecision(BigDecimal.class, BigDecimal.class));
  }

  @Test
  public void integerBeatenByLongFloatDoubleAndBigDecimal() {
    assertEquals(Long.class, fn.callGetClassWithMorePrecision(Integer.class, Long.class));
    assertEquals(Float.class, fn.callGetClassWithMorePrecision(Integer.class, Float.class));
    assertEquals(Double.class, fn.callGetClassWithMorePrecision(Integer.class, Double.class));
    assertEquals(BigDecimal.class,
        fn.callGetClassWithMorePrecision(Integer.class, BigDecimal.class));
  }

  @Test
  public void longBeatenByFloatDoubleAndBigDecimalButNotByInteger() {
    assertEquals(Float.class, fn.callGetClassWithMorePrecision(Long.class, Float.class));
    assertEquals(Double.class, fn.callGetClassWithMorePrecision(Long.class, Double.class));
    assertEquals(BigDecimal.class, fn.callGetClassWithMorePrecision(Long.class, BigDecimal.class));
    // Integer is strictly less precise than Long — Long must win.
    assertEquals(Long.class, fn.callGetClassWithMorePrecision(Long.class, Integer.class));
  }

  @Test
  public void floatBeatenByDoubleAndBigDecimalButNotByInteger() {
    assertEquals(Double.class, fn.callGetClassWithMorePrecision(Float.class, Double.class));
    assertEquals(BigDecimal.class, fn.callGetClassWithMorePrecision(Float.class, BigDecimal.class));
    // Integer does not upgrade Float — Float stays.
    assertEquals(Float.class, fn.callGetClassWithMorePrecision(Float.class, Integer.class));
  }

  @Test
  public void doubleKeepsItselfAgainstLessPreciseTypes() {
    assertEquals(Double.class, fn.callGetClassWithMorePrecision(Double.class, Integer.class));
    assertEquals(Double.class, fn.callGetClassWithMorePrecision(Double.class, Long.class));
    assertEquals(Double.class, fn.callGetClassWithMorePrecision(Double.class, Float.class));
  }

  @Test
  public void unhandledFirstClassFallsThroughToItself() {
    // Short is not in any of the switch branches → fall-through returns iClass1.
    assertEquals(Short.class, fn.callGetClassWithMorePrecision(Short.class, Long.class));
  }

  // ---- aggregateResults default ----

  @Test
  public void aggregateResultsRespectsConfiguredParameterCount() {
    fn.config(new Object[] {"one"});
    assertTrue(fn.aggregateResults());
    fn.config(new Object[] {"one", "two"});
    assertFalse(fn.aggregateResults());
  }

  @Test
  public void nameAndSyntaxAreExposed() {
    assertNotNull(fn.getName(null));
    assertEquals("test-math", fn.getName(null));
    assertEquals("test-math()", fn.getSyntax(null));
  }

  /**
   * Minimal concrete subclass of {@link SQLFunctionMathAbstract} used only to expose the two
   * protected helpers for direct unit testing.
   */
  private static final class TestMathSubclass extends SQLFunctionMathAbstract {

    TestMathSubclass() {
      super("test-math", 0, -1);
    }

    Number callGetContextValue(Object context, Class<? extends Number> iClass) {
      return getContextValue(context, iClass);
    }

    Class<? extends Number> callGetClassWithMorePrecision(
        Class<? extends Number> a, Class<? extends Number> b) {
      return getClassWithMorePrecision(a, b);
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        Object iCurrentResult,
        Object[] iParams,
        CommandContext iContext) {
      return null;
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return "test-math()";
    }

    @Override
    public Object getResult() {
      return null;
    }
  }
}
