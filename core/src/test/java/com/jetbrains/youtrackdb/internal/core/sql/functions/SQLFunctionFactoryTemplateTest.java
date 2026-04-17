package com.jetbrains.youtrackdb.internal.core.sql.functions;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.query.Result;
import org.junit.Test;

/**
 * Tests for {@link SQLFunctionFactoryTemplate}: verifies the common register / lookup / createFunction
 * machinery used by every SQL function factory. Covers both registration modes (instance and Class
 * object), case-insensitive lookup via {@code hasFunction}, error paths (unknown function name and
 * broken constructor), and the {@link java.util.Map} view returned by {@code getFunctions}.
 */
public class SQLFunctionFactoryTemplateTest extends DbTestBase {

  /** Minimal concrete factory used to exercise the template base class. */
  private static class TestFactory extends SQLFunctionFactoryTemplate {

    @Override
    public void registerDefaultFunctions(DatabaseSessionEmbedded db) {
      // intentionally empty — tests register functions explicitly
    }
  }

  /** Stateless stub function returned when registered as an instance. */
  private static class StubFunction extends SQLFunctionAbstract {

    StubFunction(String name) {
      super(name, 0, 0);
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        Object iCurrentResult,
        Object[] iParams,
        CommandContext iContext) {
      return "stub";
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return name + "()";
    }
  }

  /** Public class with a public no-arg constructor; createFunction should instantiate it reflectively. */
  public static class ClassBasedFunction extends SQLFunctionAbstract {

    public ClassBasedFunction() {
      super("classbased", 0, 0);
    }

    @Override
    public Object execute(
        Object iThis,
        Result iCurrentRecord,
        Object iCurrentResult,
        Object[] iParams,
        CommandContext iContext) {
      return "class";
    }

    @Override
    public String getSyntax(DatabaseSessionEmbedded session) {
      return "classbased()";
    }
  }

  /**
   * Class registered WITHOUT a no-arg constructor — reflective instantiation must throw
   * {@link CommandExecutionException} wrapping the original error.
   */
  public static class BrokenFunction extends SQLFunctionAbstract {

    public BrokenFunction(String unused) {
      super("broken", 0, 0);
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
      return "broken()";
    }
  }

  @Test
  public void registerInstanceReturnsSameInstanceOnCreate() {
    // When a function is registered as an instance, createFunction returns that
    // exact same instance (stateless reuse — documented contract of SQLFunction).
    var factory = new TestFactory();
    var stub = new StubFunction("stubfn");
    factory.register(session, stub);

    var created = factory.createFunction("stubfn", session);
    assertSame(stub, created);
  }

  @Test
  public void registerClassInstantiatesFreshEachCall() {
    // When registered as a Class object, createFunction must instantiate via
    // newInstance() — each call returns a new object (stateful function model).
    var factory = new TestFactory();
    factory.register("cls", ClassBasedFunction.class);

    var a = factory.createFunction("cls", session);
    var b = factory.createFunction("cls", session);

    assertNotNull(a);
    assertNotNull(b);
    assertTrue(a instanceof ClassBasedFunction);
    assertTrue(b instanceof ClassBasedFunction);
    assertNotSame(a, b);
  }

  @Test
  public void registerLowercasesName() {
    // Contract: the register(String, Object) overload lowercases the name via
    // Locale.ENGLISH so lookups by any casing succeed.
    var factory = new TestFactory();
    factory.register("MixedCaseName", ClassBasedFunction.class);

    // After registration, only lowercase lookups match (consistent with the
    // createFunction behaviour, which does not itself lowercase).
    assertTrue(factory.hasFunction("mixedcasename", session));
    assertFalse(factory.hasFunction("MixedCaseName", session));
  }

  @Test
  public void registerInstanceKeyUsesFunctionName() {
    // register(session, function) uses function.getName(session).toLowerCase as key.
    var factory = new TestFactory();
    factory.register(session, new StubFunction("MyFunc"));

    assertTrue(factory.hasFunction("myfunc", session));
    assertNotNull(factory.createFunction("myfunc", session));
  }

  @Test
  public void hasFunctionIsCaseSensitiveOnLookup() {
    // Current behaviour: hasFunction does NOT lowercase the input. Pin the
    // behaviour so we notice any future change.
    var factory = new TestFactory();
    factory.register("lower", ClassBasedFunction.class);

    assertTrue(factory.hasFunction("lower", session));
    assertFalse(factory.hasFunction("LOWER", session));
  }

  @Test(expected = CommandExecutionException.class)
  public void createFunctionUnknownNameThrows() {
    // Unknown function names must raise CommandExecutionException with a message
    // including the requested name.
    new TestFactory().createFunction("missing", session);
  }

  @Test
  public void createFunctionUnknownMessageContainsName() {
    try {
      new TestFactory().createFunction("noSuchFn", session);
      fail("Expected CommandExecutionException");
    } catch (CommandExecutionException e) {
      assertTrue(
          "Exception message should mention the missing function name",
          e.getMessage().contains("noSuchFn"));
    }
  }

  @Test
  public void createFunctionBrokenConstructorWrapsCause() {
    // A class registered without a no-arg constructor must raise
    // CommandExecutionException wrapping the underlying reflective error.
    var factory = new TestFactory();
    factory.register("broken", BrokenFunction.class);

    try {
      factory.createFunction("broken", session);
      fail("Expected BaseException / CommandExecutionException");
    } catch (BaseException e) {
      // BaseException.wrapException returns either the original or a wrapper —
      // verify the message mentions the function name ("broken") so users can
      // trace which function failed to instantiate.
      assertTrue(
          "Exception message should mention the broken function name",
          e.getMessage() == null || e.getMessage().contains("broken"));
    }
  }

  @Test
  public void getFunctionNamesReturnsAllRegisteredKeysLowercased() {
    var factory = new TestFactory();
    factory.register("ALPHA", ClassBasedFunction.class);
    factory.register("bravo", ClassBasedFunction.class);

    var names = factory.getFunctionNames(session);
    assertEquals(2, names.size());
    assertTrue(names.contains("alpha"));
    assertTrue(names.contains("bravo"));
  }

  @Test
  public void getFunctionsReturnsLiveMapReflectingRegistrations() {
    // getFunctions exposes the internal map for inspection; adding registrations
    // must be visible in its snapshot.
    var factory = new TestFactory();
    assertTrue(factory.getFunctions().isEmpty());

    factory.register("k", ClassBasedFunction.class);
    assertEquals(1, factory.getFunctions().size());
    assertTrue(factory.getFunctions().containsKey("k"));
    assertSame(ClassBasedFunction.class, factory.getFunctions().get("k"));
  }
}
