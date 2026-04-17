package com.jetbrains.youtrackdb.internal.core.sql.functions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLStaticReflectiveFunction;
import java.util.HashSet;
import java.util.UUID;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Tests for {@link CustomSQLFunctionFactory}: the reflective factory that exposes Java classes'
 * static methods as SQL functions under a given prefix. The factory populates a static, shared map
 * (initialized with {@code math_*} from {@link Math}) and refuses to overwrite an already-registered
 * name. These tests cover: the default {@code math_*} registrations are present, {@code hasFunction}
 * and {@code createFunction} agree on known names, unknown names raise
 * {@link CommandExecutionException}, and re-registering a class under a prefix that collides with
 * existing entries is idempotent (no exception thrown, existing entries preserved).
 */
// CustomSQLFunctionFactory's registry is a process-wide static HashMap mutated
// without synchronization. The class is SPI-registered in
// META-INF/services/...SQLFunctionFactory, so every other test that resolves an
// SQL function reads the same map concurrently. Tagging this class as
// @SequentialTest forces it into the sequential surefire execution so its
// register() writes never race with concurrent reads from parallel test
// classes (BC1/TX1 from the Track 6 review). The intra-class @FixMethodOrder
// remains because the foreign-prefix test seeds entries that persist for the
// rest of the JVM fork — alphabetical ordering keeps prefix-sensitive tests
// running before prefix-mutating tests.
@Category(SequentialTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class CustomSQLFunctionFactoryTest extends DbTestBase {

  @Test
  public void defaultMathFunctionsAreRegistered() {
    // The static initializer registers `math_*` for every public static method
    // on java.lang.Math. Spot-check a representative sample covering:
    //   - primitive-returning (abs, max, min)
    //   - overloaded (abs, max, min have many overloads)
    //   - no-arg-like (random)
    //   - double-returning (sqrt, sin, cos)
    // All entries are lowercased; lookups match lowercase keys.
    var factory = new CustomSQLFunctionFactory();
    assertTrue("math_abs should be registered", factory.hasFunction("math_abs", session));
    assertTrue("math_max should be registered", factory.hasFunction("math_max", session));
    assertTrue("math_min should be registered", factory.hasFunction("math_min", session));
    assertTrue("math_sqrt should be registered", factory.hasFunction("math_sqrt", session));
    assertTrue("math_sin should be registered", factory.hasFunction("math_sin", session));
    assertTrue("math_random should be registered", factory.hasFunction("math_random", session));
  }

  @Test
  public void createFunctionReturnsReflectiveFunctionForKnownName() {
    // createFunction for a registered name must return a populated
    // SQLStaticReflectiveFunction that carries the captured Method[] array.
    var factory = new CustomSQLFunctionFactory();
    var fn = factory.createFunction("math_abs", session);
    assertNotNull(fn);
    assertTrue(
        "Custom reflective functions are SQLStaticReflectiveFunction instances",
        fn instanceof SQLStaticReflectiveFunction);
  }

  @Test
  public void hasFunctionFalseForUnknownName() {
    var factory = new CustomSQLFunctionFactory();
    assertFalse(factory.hasFunction("definitelyNotRegistered", session));
  }

  @Test
  public void createFunctionUnknownNameThrows() {
    try {
      new CustomSQLFunctionFactory().createFunction("definitelyNotRegistered", session);
      fail("Expected CommandExecutionException for unknown function name");
    } catch (CommandExecutionException expected) {
      assertTrue(expected.getMessage().contains("definitelyNotRegistered"));
    }
  }

  @Test
  public void getFunctionNamesIncludesMathEntries() {
    // The advertised name set must agree with hasFunction for known keys.
    // We deliberately avoid asserting that EVERY name is a math_ prefix —
    // the static FUNCTIONS map is process-wide and other tests (or other
    // test classes sharing the JVM fork) may legitimately register foreign
    // prefixes. We pin only the contract: math_ entries registered by the
    // static initializer must be visible.
    var names = new CustomSQLFunctionFactory().getFunctionNames(session);
    assertTrue("math_abs must be visible", names.contains("math_abs"));
    assertTrue("math_sqrt must be visible", names.contains("math_sqrt"));
    assertTrue("math_min must be visible", names.contains("math_min"));
    assertTrue("math_max must be visible", names.contains("math_max"));
  }

  @Test
  public void reRegisterSamePrefixIsIdempotent() {
    // Registering Math.class again under "math_" must not throw; the first-wins
    // guard logs a warning but leaves existing entries UNTOUCHED (same instance,
    // not a freshly-built replacement). Capture the reflective function before
    // re-registering and assert we still get the exact same object afterwards —
    // this is the assertion that would actually falsify if `register` started
    // overwriting collisions instead of skipping them.
    var factory = new CustomSQLFunctionFactory();
    var before = factory.createFunction("math_abs", session);

    CustomSQLFunctionFactory.register("math_", Math.class);

    var after = factory.createFunction("math_abs", session);
    assertSame("first-wins: existing entry must not be replaced", before, after);
  }

  /** Class used to verify reflective registration with a custom prefix. */
  public static class ReflectFixture {

    public static int plus(int a, int b) {
      return a + b;
    }

    public static double plus(double a, double b) {
      return a + b;
    }

    public static String greet(String who) {
      return "hello " + who;
    }
  }

  @Test
  public void registerCustomPrefixExposesStaticMethods() {
    // UUID-qualified prefix so accidental collisions across future tests are
    // ruled out, and so that re-running this test in the same JVM never trips
    // the first-wins guard against a prior run's leftovers.
    var prefix = "customfactorytest_" + UUID.randomUUID().toString().replace("-", "_") + "_";
    CustomSQLFunctionFactory.register(prefix, ReflectFixture.class);

    var factory = new CustomSQLFunctionFactory();
    assertTrue(
        "Expected 'plus' (overloaded) to register", factory.hasFunction(prefix + "plus", session));
    assertTrue("Expected 'greet' to register", factory.hasFunction(prefix + "greet", session));

    var plus = factory.createFunction(prefix + "plus", session);
    assertTrue(plus instanceof SQLStaticReflectiveFunction);
  }

  @Test
  public void registerDefaultFunctionsIsNoOp() {
    // CustomSQLFunctionFactory#registerDefaultFunctions is documented as a no-op
    // — it should not throw and should not alter the advertised name set.
    // Compare the actual name SETS (not just sizes) so a regression that
    // simultaneously added one entry and removed another would still be caught.
    var factory = new CustomSQLFunctionFactory();
    var before = new HashSet<>(factory.getFunctionNames(session));
    factory.registerDefaultFunctions(session);
    var after = new HashSet<>(factory.getFunctionNames(session));
    assertEquals(
        "registerDefaultFunctions must not change the advertised name set", before, after);
  }
}
