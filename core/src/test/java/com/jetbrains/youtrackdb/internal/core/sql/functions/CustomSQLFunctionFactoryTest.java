package com.jetbrains.youtrackdb.internal.core.sql.functions;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLStaticReflectiveFunction;
import org.junit.Test;

/**
 * Tests for {@link CustomSQLFunctionFactory}: the reflective factory that exposes Java classes'
 * static methods as SQL functions under a given prefix. The factory populates a static, shared map
 * (initialized with {@code math_*} from {@link Math}) and refuses to overwrite an already-registered
 * name. These tests cover: the default {@code math_*} registrations are present, {@code hasFunction}
 * and {@code createFunction} agree on known names, unknown names raise
 * {@link CommandExecutionException}, and re-registering a class under a prefix that collides with
 * existing entries is idempotent (no exception thrown, existing entries preserved).
 */
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
    var names = new CustomSQLFunctionFactory().getFunctionNames(session);
    assertTrue(names.contains("math_abs"));
    assertTrue(names.contains("math_sqrt"));
    // All entries begin with the "math_" prefix since that is the only
    // registration.
    for (var name : names) {
      assertTrue(
          "Every custom function should carry the math_ prefix; got: " + name,
          name.startsWith("math_"));
    }
  }

  @Test
  public void reRegisterSamePrefixIsIdempotent() {
    // Registering Math.class again under "math_" must not throw; the first-wins
    // guard logs a warning but leaves existing entries untouched.
    CustomSQLFunctionFactory.register("math_", Math.class);
    var factory = new CustomSQLFunctionFactory();
    assertTrue(factory.hasFunction("math_abs", session));
    assertNotNull(factory.createFunction("math_abs", session));
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
    // Use a unique prefix to avoid collision with prior test runs.
    var prefix = "customfactorytest_";
    CustomSQLFunctionFactory.register(prefix, ReflectFixture.class);

    var factory = new CustomSQLFunctionFactory();
    assertTrue("Expected 'plus' (overloaded) to register",
        factory.hasFunction(prefix + "plus", session));
    assertTrue("Expected 'greet' to register", factory.hasFunction(prefix + "greet", session));

    var plus = factory.createFunction(prefix + "plus", session);
    assertTrue(plus instanceof SQLStaticReflectiveFunction);
  }

  @Test
  public void registerDefaultFunctionsIsNoOp() {
    // CustomSQLFunctionFactory#registerDefaultFunctions is documented as a no-op
    // — it should not throw and should not alter the advertised name set.
    var factory = new CustomSQLFunctionFactory();
    var before = factory.getFunctionNames(session).size();
    factory.registerDefaultFunctions(session);
    var after = factory.getFunctionNames(session).size();
    // Count must be unchanged — this both pins the no-op contract and guards
    // against accidental self-registration loops.
    assertTrue("registerDefaultFunctions must not change name count", before == after);
  }
}
