/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrackdb.internal.core.metadata.function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import java.util.List;
import org.junit.Test;

/**
 * Drives {@link DatabaseFunction} (the {@link com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction
 * SQLFunction} adapter that bridges stored {@link Function} entities to the SQL function
 * dispatch machinery) and {@link DatabaseFunctionFactory} (the SPI-loaded factory that
 * instantiates {@link DatabaseFunction} on demand).
 *
 * <p>Pre-existing baseline left {@link DatabaseFunction} at 36.4% line / 16.7% branch — the
 * existing {@link FunctionLibraryTest} only exercised create / lookup / drop on
 * {@link FunctionLibrary} and never reached the {@link DatabaseFunction#execute} /
 * {@link DatabaseFunction#getName} / {@link DatabaseFunction#getMaxParams} /
 * {@link DatabaseFunction#getSyntax} / boilerplate (config / setResult / getResult /
 * filterResult / aggregateResults) surface. Pinning these arms here closes the package's
 * second-largest residual class.
 *
 * <p>The factory's {@code createFunction} delegates to
 * {@link FunctionLibrary#getFunction(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded,
 * String)} and wraps the result in a fresh {@link DatabaseFunction}; both halves are pinned
 * directly without going through the SQL command layer (which would require a JS / polyglot
 * runtime to dispatch the function body — unnecessary noise for a per-arm pin).
 *
 * <p>Per the project's interface-dispatch trap: the {@link FunctionLibrary} methods are
 * accessed through the public-API interface returned by
 * {@code session.getMetadata().getFunctionLibrary()}, not through direct
 * {@link FunctionLibraryProxy} references.
 */
public class DatabaseFunctionRoundTripTest extends DbTestBase {

  /**
   * Default factory constructs cleanly (the SPI-loaded singleton path is registered through
   * {@code META-INF/services/.../SQLFunctionFactory}).
   */
  @Test
  public void factoryConstructsCleanly() {
    var factory = new DatabaseFunctionFactory();
    assertNotNull(factory);
  }

  /**
   * {@code registerDefaultFunctions} is documented as a no-op; the body has zero observable
   * side-effects. Pin the no-op contract by snapshotting the function-library name set before
   * and after the call so a future "register everything as defaults" change becomes a visible
   * test failure rather than a silent regression.
   */
  @Test
  public void factoryRegisterDefaultFunctionsIsNoOp() {
    var factory = new DatabaseFunctionFactory();
    var library = session.getMetadata().getFunctionLibrary();
    var namesBefore = List.copyOf(library.getFunctionNames());
    factory.registerDefaultFunctions(session);
    var namesAfter = List.copyOf(library.getFunctionNames());
    assertEquals("registerDefaultFunctions must not alter the function-library name set",
        namesBefore, namesAfter);
  }

  /**
   * {@code hasFunction} returns true iff the function library contains a registered function
   * with the given name. Pin both arms (present / absent) to lock the lookup contract.
   */
  @Test
  public void factoryHasFunctionReflectsLibraryContents() {
    var factory = new DatabaseFunctionFactory();
    assertFalse(factory.hasFunction("missing", session));

    session.getMetadata().getFunctionLibrary().createFunction("PresentFn");
    assertTrue(factory.hasFunction("PresentFn", session));
  }

  /**
   * {@code getFunctionNames} returns the library's name set — the factory is a thin pass-through.
   */
  @Test
  public void factoryGetFunctionNamesPassesThroughToLibrary() {
    var factory = new DatabaseFunctionFactory();
    session.getMetadata().getFunctionLibrary().createFunction("NameSetFn");
    var names = factory.getFunctionNames(session);
    assertNotNull(names);
    // Library normalises names to upper-case via Locale.ENGLISH.
    assertTrue(names.contains("NAMESETFN"));
  }

  /**
   * {@code createFunction} returns a fresh {@link DatabaseFunction} wrapping the named function;
   * the wrapper's {@code getName(session)} echoes the original name. Pin so the SPI dispatch
   * path stays exercised.
   */
  @Test
  public void factoryCreateFunctionReturnsDatabaseFunctionForName() {
    var factory = new DatabaseFunctionFactory();
    session.getMetadata().getFunctionLibrary().createFunction("FactoryFn");
    var sqlFn = factory.createFunction("FactoryFn", session);
    assertNotNull(sqlFn);
    assertTrue(sqlFn instanceof DatabaseFunction);
    assertEquals("FactoryFn", sqlFn.getName(session));
  }

  /**
   * {@link DatabaseFunction#execute} with no parameters and a {@link Function#setCallback
   * callback} short-circuits past the script engine and returns the callback's result. Pin the
   * happy-path execute arm.
   */
  @Test
  public void executeRoutesToUnderlyingFunctionCallback() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("ExecCallbackFn");
    stored.setCallback(args -> "executed");

    var dbFn = new DatabaseFunction(stored);
    var ctx = new BasicCommandContext(session);
    var result = dbFn.execute(null, null, null, new Object[0], ctx);
    assertEquals("executed", result);
  }

  /**
   * Positional parameters are forwarded to the callback through the synthetic-name map (no
   * parameters list set on the underlying {@link Function}, so the names are
   * {@code param0}, {@code param1}, …). Distinct from the no-args arm above.
   */
  @Test
  public void executeForwardsPositionalParametersAsSyntheticNames() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("ExecArgsFn");
    final Object[][] received = new Object[1][];
    stored.setCallback(args -> {
      received[0] = args.values().toArray();
      return args.size();
    });

    var dbFn = new DatabaseFunction(stored);
    var ctx = new BasicCommandContext(session);
    var result = dbFn.execute(null, null, null, new Object[] {"alpha", 7}, ctx);
    assertEquals(2, result);
    assertNotNull(received[0]);
    assertEquals("alpha", received[0][0]);
    assertEquals(7, received[0][1]);
  }

  /**
   * Constant boilerplate getters: {@code aggregateResults} and {@code filterResult} both return
   * {@code false} unconditionally. Pin both arms because they are part of the
   * {@link com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunction SQLFunction}
   * contract and a future override on the database-function variant must be a deliberate
   * decision.
   */
  @Test
  public void aggregateResultsAndFilterResultAreAlwaysFalse() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("BoilerplateFn");
    var dbFn = new DatabaseFunction(stored);
    assertFalse(dbFn.aggregateResults());
    assertFalse(dbFn.filterResult());
  }

  /**
   * {@code getMinParams()} is hard-coded to {@code 0}. Pin so a future "infer min from
   * parameters list" change is a deliberate event.
   */
  @Test
  public void getMinParamsReturnsZero() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("MinParamsFn");
    var dbFn = new DatabaseFunction(stored);
    assertEquals(0, dbFn.getMinParams());
  }

  /**
   * {@code getMaxParams(session)} reads the underlying function's parameters list size, falling
   * back to {@code 0} when the list is null. Pin both arms.
   */
  @Test
  public void getMaxParamsReflectsParametersListSize() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("MaxParamsNullFn");
    var dbFn = new DatabaseFunction(stored);
    // No parameters set → null → fallback to 0.
    assertEquals(0, dbFn.getMaxParams(session));

    stored.setParameters(List.of("a", "b", "c"));
    assertEquals(3, dbFn.getMaxParams(session));
  }

  /**
   * {@code getSyntax(session)} renders {@code name(p1,p2,...)} from the underlying function's
   * parameters list. Pin the multi-parameter case to exercise the {@code if (p > 0)} comma-
   * separator branch.
   */
  @Test
  public void getSyntaxRendersNameAndCommaSeparatedParameters() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("SyntaxFn");
    stored.setParameters(List.of("first", "second", "third"));
    var dbFn = new DatabaseFunction(stored);
    assertEquals("SyntaxFn(first,second,third)", dbFn.getSyntax(session));
  }

  /**
   * {@code getSyntax} on a function with a single parameter renders without a comma — pins the
   * {@code p == 0} no-comma branch directly.
   */
  @Test
  public void getSyntaxOmitsCommaForSingleParameter() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("SingleParamFn");
    stored.setParameters(List.of("only"));
    var dbFn = new DatabaseFunction(stored);
    assertEquals("SingleParamFn(only)", dbFn.getSyntax(session));
  }

  /**
   * {@code getResult()} unconditionally returns null and {@code setResult(any)} is a no-op.
   * Pin so a future change that introduces a result-buffering layer is a deliberate event.
   */
  @Test
  public void getResultIsAlwaysNullAndSetResultIsNoOp() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("ResultBoilerplateFn");
    var dbFn = new DatabaseFunction(stored);
    assertNull(dbFn.getResult());
    dbFn.setResult(new Object());
    // setResult does not stash the value anywhere observable.
    assertNull(dbFn.getResult());
  }

  /**
   * {@code config(args)} is documented as a no-op; pin the no-op contract by checking the
   * adapter's observable state ({@code getResult} / {@code getName}) is unchanged across the
   * call so a future change that stashes config-time state is a deliberate, visible event.
   */
  @Test
  public void configIsNoOp() {
    var lib = session.getMetadata().getFunctionLibrary();
    var stored = lib.createFunction("ConfigBoilerplateFn");
    var dbFn = new DatabaseFunction(stored);
    var resultBefore = dbFn.getResult();
    var nameBefore = dbFn.getName(session);
    dbFn.config(new Object[] {"any", "args"});
    assertEquals("config must not alter the adapter's stored result",
        resultBefore, dbFn.getResult());
    assertEquals("config must not alter the adapter's reported name",
        nameBefore, dbFn.getName(session));
  }
}
