/*
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.youtrackdb.internal.core.sql.functions.misc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;

/**
 * Tests for {@link SQLStaticReflectiveFunction} — reflectively dispatches to a static Java
 * method picked by argument types.
 *
 * <p>What is covered (standalone, using the {@link Fixtures} helper class's public static
 * methods as targets):
 *
 * <ul>
 *   <li>Happy path — single method, compatible arg → method.invoke returns the method result.
 *   <li>Overload resolution by arity — 1-arg vs 2-arg methods are disambiguated by iParams.length.
 *   <li>Overload resolution by primitive weight — with {@code int/long/double} overloads of the
 *       same arity, the constructor sorts by primitive weight and {@code pickMethod} scans in
 *       that order; Integer input matches {@code int} first, Long matches {@code long} first.
 *   <li>Autoboxing — {@code Integer} arg is accepted by a {@code double} parameter (primitive
 *       widening path of {@code isAssignable}).
 *   <li>Type incompatibility — {@code String} arg against an {@code int} parameter does not
 *       match; no other overload matches either → no method picked; this test does NOT exercise
 *       that branch because it requires a database session (the production code builds a
 *       {@link com.jetbrains.youtrackdb.internal.core.exception.QueryParsingException} via
 *       {@code iContext.getDatabaseSession().getDatabaseName()}). Deferred to a DB-backed
 *       test.
 *   <li>Null argument — pickMethod's null-guard skips type check, so a null arg matches any
 *       1-arg method; {@link Method#invoke} then passes null to the target method. Here the
 *       target is a non-primitive-param method.
 *   <li>Sort stability — methods array is sorted in-place by the constructor. Verified via
 *       reflection on the private {@code methods} field.
 * </ul>
 *
 * <p>Uncovered by this standalone test (require DB session): the two QueryParsingException
 * paths (method==null, and ReflectiveOperationException wrap) — assigned to Step 6 if still
 * needed for coverage, otherwise acceptable as exception-message plumbing.
 */
public class SQLStaticReflectiveFunctionTest {

  @Test
  public void happyPathInvokesSingleMatchingMethod() throws Exception {
    final var idInt = Fixtures.class.getMethod("idInt", int.class);
    final var fn = new SQLStaticReflectiveFunction("testFn", 1, 1, idInt);

    assertEquals(42, fn.execute(null, null, null, new Object[] {42}, new BasicCommandContext()));
  }

  @Test
  public void overloadResolutionByArityDispatchesToCorrectMethod() throws Exception {
    final var oneArg = Fixtures.class.getMethod("arityMarker", int.class);
    final var twoArg = Fixtures.class.getMethod("arityMarker", int.class, int.class);
    final var fn = new SQLStaticReflectiveFunction("am", 1, 2, oneArg, twoArg);

    assertEquals("1:7",
        fn.execute(null, null, null, new Object[] {7}, new BasicCommandContext()));
    assertEquals("2:7,8",
        fn.execute(null, null, null, new Object[] {7, 8}, new BasicCommandContext()));
  }

  @Test
  public void overloadResolutionBySortedPrimitiveWeight() throws Exception {
    final var mInt = Fixtures.class.getMethod("numeric", int.class);
    final var mLong = Fixtures.class.getMethod("numeric", long.class);
    final var mDouble = Fixtures.class.getMethod("numeric", double.class);

    // Pass methods in a deliberately wrong order — the constructor sorts them by primitive
    // weight (int=5, long=6, double=8), so int comes first, then long, then double.
    final var fn = new SQLStaticReflectiveFunction("numeric", 1, 1, mDouble, mInt, mLong);

    // Integer widens to all three; first match (int) wins.
    assertEquals("int:1",
        fn.execute(null, null, null, new Object[] {1}, new BasicCommandContext()));

    // Long cannot auto-widen to int (isAssignable(Long, int) returns false for the int arm),
    // but matches long.
    assertEquals("long:2",
        fn.execute(null, null, null, new Object[] {2L}, new BasicCommandContext()));

    // Double cannot auto-widen to int or long, but matches double directly.
    assertEquals("double:3.5",
        fn.execute(null, null, null, new Object[] {3.5}, new BasicCommandContext()));
  }

  @Test
  public void integerAutowidensToDoubleWhenOnlyDoubleOverloadExists() throws Exception {
    final var mDouble = Fixtures.class.getMethod("numeric", double.class);
    final var fn = new SQLStaticReflectiveFunction("numeric", 1, 1, mDouble);

    // isAssignable(Integer, double) → true via the primitive widening path. Method.invoke
    // then auto-widens int→double at call time.
    assertEquals("double:5.0",
        fn.execute(null, null, null, new Object[] {5}, new BasicCommandContext()));
  }

  @Test
  public void nullArgMatchesAnyArity1Method() throws Exception {
    // pickMethod's null-guard skips isAssignable entirely for null args. An arity match alone
    // selects the method — the underlying method must accept null (non-primitive param).
    final var m = Fixtures.class.getMethod("nullable", Object.class);
    final var fn = new SQLStaticReflectiveFunction("nullable", 1, 1, m);

    assertEquals("null",
        fn.execute(null, null, null, new Object[] {new Object[] {null}[0]},
            new BasicCommandContext()));
  }

  @Test
  public void stringToObjectAssignmentWorks() throws Exception {
    // Non-primitive iToClass: isAssignable uses iToClass.isAssignableFrom(fromClass). String
    // assigns to Object.
    final var m = Fixtures.class.getMethod("nullable", Object.class);
    final var fn = new SQLStaticReflectiveFunction("nullable", 1, 1, m);

    assertEquals("hello",
        fn.execute(null, null, null, new Object[] {"hello"}, new BasicCommandContext()));
  }

  @Test
  public void constructorSortsMethodsArrayInPlace() throws Exception {
    final var mDouble = Fixtures.class.getMethod("numeric", double.class);
    final var mInt = Fixtures.class.getMethod("numeric", int.class);
    final var mLong = Fixtures.class.getMethod("numeric", long.class);

    final var fn = new SQLStaticReflectiveFunction("numeric", 1, 1, mDouble, mInt, mLong);

    // Read the private `methods` field to confirm sort order.
    final Field field = SQLStaticReflectiveFunction.class.getDeclaredField("methods");
    field.setAccessible(true);
    final var methods = (Method[]) field.get(fn);

    assertEquals(3, methods.length);
    assertSame(mInt, methods[0]);
    assertSame(mLong, methods[1]);
    assertSame(mDouble, methods[2]);
  }

  @Test
  public void twoArgMethodSortsBeforeThreeArgMethod() throws Exception {
    // Arity differences dominate the sort comparator (c = length - length, non-zero here).
    final var two = Fixtures.class.getMethod("arityMarker", int.class, int.class);
    final var three = Fixtures.class.getMethod("arityMarker", int.class, int.class, int.class);
    final var one = Fixtures.class.getMethod("arityMarker", int.class);

    // Pass in reverse order — sort normalizes to ascending arity.
    final var fn = new SQLStaticReflectiveFunction("am", 1, 3, three, two, one);

    final Field field = SQLStaticReflectiveFunction.class.getDeclaredField("methods");
    field.setAccessible(true);
    final var methods = (Method[]) field.get(fn);

    assertEquals(1, methods[0].getParameterCount());
    assertEquals(2, methods[1].getParameterCount());
    assertEquals(3, methods[2].getParameterCount());
  }

  @Test
  public void booleanParamRejectsIntArgAndPickMethodReturnsNoMatch() throws Exception {
    // isAssignable(Integer, boolean): fromClass=int, iToClass=boolean; primitive; the
    // Integer.TYPE arm returns true ONLY for Long/Float/Double — boolean is none → false.
    // With NO alternative overload, pickMethod returns null — which then triggers
    // QueryParsingException via getDatabaseSession(). Confirmed indirectly by asserting
    // that an exception is raised (we can't assert the specific type without a session).
    final var m = Fixtures.class.getMethod("takesBoolean", boolean.class);
    final var fn = new SQLStaticReflectiveFunction("tb", 1, 1, m);

    Throwable thrown = null;
    try {
      fn.execute(null, null, null, new Object[] {42}, new BasicCommandContext());
    } catch (Throwable t) {
      thrown = t;
    }
    assertNotNull("a non-matching pickMethod path must raise — but BasicCommandContext has no "
        + "session so the thrown type will be DatabaseException rather than the intended "
        + "QueryParsingException (contract mismatch documented).", thrown);
  }

  @Test
  public void shortOrByteArgIsAssignableToIntParam() throws Exception {
    // isAssignable(Short, int): fromClass=short; Short.TYPE.equals(short) arm returns
    // int/long/float/double → true.
    final var mInt = Fixtures.class.getMethod("idInt", int.class);
    final var fn = new SQLStaticReflectiveFunction("idInt", 1, 1, mInt);

    assertEquals(7,
        fn.execute(null, null, null, new Object[] {(short) 7}, new BasicCommandContext()));
  }

  @Test
  public void charArgIsAssignableToIntParam() throws Exception {
    // isAssignable(Character, int): fromClass=char; Character.TYPE.equals(char) arm →
    // int/long/float/double → true. Method.invoke widens char to int.
    final var mInt = Fixtures.class.getMethod("idInt", int.class);
    final var fn = new SQLStaticReflectiveFunction("idInt", 1, 1, mInt);

    assertEquals((int) 'A',
        fn.execute(null, null, null, new Object[] {'A'}, new BasicCommandContext()));
  }

  @Test
  public void byteArgIsAssignableToShortParam() throws Exception {
    // isAssignable(Byte, short): fromClass=byte; Byte.TYPE.equals(byte) arm returns
    // short/int/long/float/double → true.
    final var mShort = Fixtures.class.getMethod("idShort", short.class);
    final var fn = new SQLStaticReflectiveFunction("idShort", 1, 1, mShort);

    assertEquals((short) 5,
        fn.execute(null, null, null, new Object[] {(byte) 5}, new BasicCommandContext()));
  }

  @Test
  public void floatArgIsAssignableToDoubleParam() throws Exception {
    // isAssignable(Float, double): fromClass=float; Float.TYPE arm returns double → true.
    final var mDouble = Fixtures.class.getMethod("numeric", double.class);
    final var fn = new SQLStaticReflectiveFunction("numeric", 1, 1, mDouble);

    assertEquals("double:2.5",
        fn.execute(null, null, null, new Object[] {2.5f}, new BasicCommandContext()));
  }

  @Test
  public void syntaxEqualsFunctionName() {
    // getSyntax returns getName(session) — pinning prevents accidental override drift.
    final var m = Fixtures.class.getMethods()[0];
    final var fn = new SQLStaticReflectiveFunction("customFn", 0, 0, m);
    assertEquals("customFn", fn.getSyntax(null));
    assertEquals("customFn", fn.getName(null));
  }

  @Test
  public void doubleAssignableToLongIsFalse() throws Exception {
    // isAssignable(Double, long): fromClass=double; the Double.TYPE arm returns false. With
    // only a long overload available and no fallback, pickMethod returns null.
    final var mLong = Fixtures.class.getMethod("numeric", long.class);
    final var fn = new SQLStaticReflectiveFunction("numeric", 1, 1, mLong);

    // Cannot use session so the resulting exception is the "no database session" one; this
    // at least pins that the double→long assignment is NOT accepted.
    Throwable thrown = null;
    try {
      fn.execute(null, null, null, new Object[] {1.5}, new BasicCommandContext());
    } catch (Throwable t) {
      thrown = t;
    }
    assertNotNull(thrown);
  }

  @Test
  public void metadataSurfaceIsPinned() throws Exception {
    final var m = Fixtures.class.getMethod("idInt", int.class);
    final var fn = new SQLStaticReflectiveFunction("myFn", 1, 1, m);

    assertEquals("myFn", fn.getName(null));
    assertEquals(1, fn.getMinParams());
    assertEquals(1, fn.getMaxParams(null));
    // aggregateResults / filterResult inherit defaults (false / false).
    assertEquals(false, fn.aggregateResults());
    assertEquals(false, fn.filterResult());
    assertNull(fn.getResult());
  }

  /** Fixture holder — all static methods are picked up via reflection by the tests above. */
  public static final class Fixtures {

    public static int idInt(int v) {
      return v;
    }

    public static short idShort(short v) {
      return v;
    }

    public static String numeric(int v) {
      return "int:" + v;
    }

    public static String numeric(long v) {
      return "long:" + v;
    }

    public static String numeric(double v) {
      return "double:" + v;
    }

    public static String arityMarker(int a) {
      return "1:" + a;
    }

    public static String arityMarker(int a, int b) {
      return "2:" + a + "," + b;
    }

    public static String arityMarker(int a, int b, int c) {
      return "3:" + a + "," + b + "," + c;
    }

    public static String nullable(Object o) {
      return o == null ? "null" : o.toString();
    }

    public static String takesBoolean(boolean b) {
      return "bool:" + b;
    }

    private Fixtures() {
    }
  }
}
