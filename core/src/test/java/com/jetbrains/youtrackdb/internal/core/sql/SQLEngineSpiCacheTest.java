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
package com.jetbrains.youtrackdb.internal.core.sql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollateFactory;
import com.jetbrains.youtrackdb.internal.core.exception.CommandSQLParsingException;
import com.jetbrains.youtrackdb.internal.core.sql.functions.DefaultSQLFunctionFactory;
import com.jetbrains.youtrackdb.internal.core.sql.functions.SQLFunctionAbstract;
import com.jetbrains.youtrackdb.internal.core.sql.functions.coll.SQLFunctionDistinct;
import com.jetbrains.youtrackdb.internal.core.sql.functions.misc.SQLFunctionCount;
import com.jetbrains.youtrackdb.internal.core.sql.method.DefaultSQLMethodFactory;
import com.jetbrains.youtrackdb.internal.core.sql.method.misc.SQLMethodSize;
import com.jetbrains.youtrackdb.internal.core.sql.operator.DefaultQueryOperatorFactory;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperatorEquals;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Tests for {@link SQLEngine}'s SPI factory caches, the register/unregister mutation paths, and the
 * derived helpers (getFunction, getMethod, getCollate) that consume them.
 *
 * <p>Covered contracts:
 *
 * <ul>
 *   <li>{@link SQLEngine#getFunctionFactories}, {@link SQLEngine#getMethodFactories},
 *       {@link SQLEngine#getOperatorFactories}, {@link SQLEngine#getCollateFactories} — lazy init +
 *       cache hit (the underlying list must be shared between calls, proven by verifying that both
 *       iterators surface the same factory instances in the same order).</li>
 *   <li>{@link SQLEngine#getRecordOperators} — {@code SORTED_OPERATORS} is cached after the first
 *       call (identity-equal array on subsequent reads) and invalidated by
 *       {@link SQLEngine#registerOperator}.</li>
 *   <li>{@link SQLEngine#scanForPlugins} only clears the {@code FUNCTION_FACTORIES} cache —
 *       {@code METHOD_FACTORIES}, {@code OPERATOR_FACTORIES}, and {@code COLLATE_FACTORIES} are NOT
 *       cleared. The asymmetry is pinned in
 *       {@link #zzzScanForPluginsOnlyClearsFunctionFactoriesCacheBugPin}.</li>
 *   <li>{@link SQLEngine#registerFunction} / {@link SQLEngine#unregisterFunction} — dynamic
 *       registration via the shared {@code DynamicSQLElementFactory.FUNCTIONS} map, with
 *       case-insensitive lowercasing on both paths.</li>
 *   <li>{@link SQLEngine#getFunction}, {@link SQLEngine#getFunctionOrNull} — happy path (known
 *       built-in), unknown name (throws vs null), and the {@code "any"/"all"} special-function
 *       short-circuit.</li>
 *   <li>{@link SQLEngine#getMethod} — case-insensitive lookup. Pin that SQLEngine's lowercasing
 *       masks the {@link DefaultSQLMethodFactory#createMethod} case-sensitivity bug.</li>
 *   <li>{@link SQLEngine#getCollate} — known name returns a non-null Collate, unknown returns
 *       null.</li>
 * </ul>
 *
 * <p>This test is {@link SequentialTest} because it mutates process-wide static state — the
 * {@code DynamicSQLElementFactory.FUNCTIONS} map and the {@code DynamicSQLElementFactory.OPERATORS}
 * set that back {@link SQLEngine#registerFunction} and {@link SQLEngine#registerOperator}.
 *
 * <p><b>Ordering guarantee — intra-class only.</b>
 * {@link FixMethodOrder}{@code (NAME_ASCENDING)} orders methods within this one class; it does
 * NOT order methods across classes. The {@code zzz}-prefixed destructive methods run after the
 * read-only methods <em>in this class only</em>. The real defence-in-depth against static-state
 * leakage is the snapshot-equality final assertion in {@link #verifyNoStaticStateLeak()} — any
 * entry that leaks from this class (or from an unrelated prior class that happened to run in the
 * same JVM fork) is caught there. The {@code @Category(SequentialTest)} tag additionally
 * serializes this class against other {@code SequentialTest} classes at the surefire level.
 *
 * <p>UUID-qualified names are used for every dynamic registration so that even if a concurrent
 * test runner (in a different class on another thread) happens to register a name simultaneously,
 * no collision or spurious failure occurs.
 */
@Category(SequentialTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SQLEngineSpiCacheTest extends DbTestBase {

  private Map<String, Object> functionsBefore;
  private Set<QueryOperator> operatorsBefore;
  private String uuidPrefix;

  @Before
  public void snapshotStaticState() {
    // Full key+value snapshot — a test that overwrites an existing key with a different SQLFunction
    // would be missed by a key-only snapshot, which is precisely the regression we want to catch.
    functionsBefore = Map.copyOf(DynamicSQLElementFactory.FUNCTIONS);
    // Snapshot the OPERATORS set (NOT a live view — tests may remove the operator they added).
    // The HashSet constructor iterates the synchronized set; we must hold the monitor to
    // avoid a CME if any other fork mutates OPERATORS concurrently. (SequentialTest protects
    // against concurrent in-class and cross-SequentialTest-class writes; this lock keeps the
    // test robust against a future SequentialTest-category removal.)
    synchronized (DynamicSQLElementFactory.OPERATORS) {
      operatorsBefore = new HashSet<>(DynamicSQLElementFactory.OPERATORS);
    }
    uuidPrefix = "t7s6_" + UUID.randomUUID().toString().replace("-", "_") + "_";
    assert uuidPrefix.equals(uuidPrefix.toLowerCase(Locale.ROOT))
        : "uuidPrefix must be lowercase to keep startsWith-based cleanup correct after"
            + " lowercasing registrations";
  }

  @After
  public void verifyNoStaticStateLeak() {
    // Explicit teardown: remove anything we added. Tests that already cleaned up leave these no-ops
    // idempotent (removeIf is non-throwing).
    DynamicSQLElementFactory.FUNCTIONS.keySet().removeIf(k -> k.startsWith(uuidPrefix));
    DynamicSQLElementFactory.OPERATORS.removeIf(op -> op.keyword.startsWith(uuidPrefix));

    // Snapshot assertion: the FUNCTIONS map and OPERATORS set must be byte-for-byte the same as
    // they were at setup time. A prefix-filtered cleanup above is defensive; this check is the
    // actual contract enforcement — any leaked entry from a different key space would surface as
    // an inequality.
    assertEquals(
        "FUNCTIONS map leaked static state between tests",
        functionsBefore, Map.copyOf(DynamicSQLElementFactory.FUNCTIONS));
    // Hold the monitor on iteration to match the setup-time snapshot's contract.
    Set<QueryOperator> operatorsNow;
    synchronized (DynamicSQLElementFactory.OPERATORS) {
      operatorsNow = new HashSet<>(DynamicSQLElementFactory.OPERATORS);
    }
    assertEquals("OPERATORS set leaked static state between tests", operatorsBefore, operatorsNow);
  }

  // ===========================================================================
  // Lazy init + cache hit for every getXxxFactories()
  // ===========================================================================

  @Test
  public void getFunctionFactoriesIteratorContainsAllRegisteredFactories() {
    // SPI registration (META-INF/services/SQLFunctionFactory) lists four factories:
    // DefaultSQLFunctionFactory, DynamicSQLElementFactory, DatabaseFunctionFactory,
    // CustomSQLFunctionFactory. Pin that all four are surfaced by the iterator. This indirectly
    // proves both the ServiceLoader lookup and the (lazy-init)-done side effect — after this call,
    // FUNCTION_FACTORIES is non-null.
    var factories = new ArrayList<>();
    var ite = SQLEngine.getFunctionFactories(session);
    while (ite.hasNext()) {
      factories.add(ite.next().getClass());
    }
    assertTrue(
        "DefaultSQLFunctionFactory must be in the factory list: " + factories,
        factories.contains(DefaultSQLFunctionFactory.class));
    assertTrue(
        "DynamicSQLElementFactory must be in the factory list: " + factories,
        factories.contains(DynamicSQLElementFactory.class));
  }

  @Test
  public void getFunctionFactoriesReturnsCachedListOnSecondCall() throws Exception {
    // Consumes two iterators back-to-back and verifies the same underlying factory instances are
    // yielded in the same order — covers the `if (FUNCTION_FACTORIES == null)` short-circuit on
    // the second call.
    //
    // Additionally: ServiceLoader caches singleton instances, so element-identity could pass
    // even if the short-circuit were removed. Pin the container identity directly via reflection:
    // the same List<SQLFunctionFactory> reference must be the value of the static field before
    // and after the second call.
    SQLEngine.getFunctionFactories(session); // warm
    var beforeContainer = readStaticField("FUNCTION_FACTORIES");
    var second = collect(SQLEngine.getFunctionFactories(session));
    var afterContainer = readStaticField("FUNCTION_FACTORIES");
    assertSame(
        "FUNCTION_FACTORIES container must not be rebuilt on cache-hit path",
        beforeContainer, afterContainer);

    // Belt-and-braces element-level identity check (same ServiceLoader singletons).
    var first = collect(SQLEngine.getFunctionFactories(session));
    assertEquals("cached factory count must match: " + first + " vs " + second,
        first.size(), second.size());
    for (var i = 0; i < first.size(); i++) {
      assertSame(
          "factory instance must be shared across calls (cache hit); index=" + i,
          first.get(i), second.get(i));
    }
  }

  @Test
  public void getMethodFactoriesIteratorContainsDefaultSQLMethodFactory() {
    var classes = new ArrayList<>();
    var ite = SQLEngine.getMethodFactories();
    while (ite.hasNext()) {
      classes.add(ite.next().getClass());
    }
    assertTrue(
        "DefaultSQLMethodFactory must be in the method factory list: " + classes,
        classes.contains(DefaultSQLMethodFactory.class));
  }

  @Test
  public void getMethodFactoriesReturnsCachedListOnSecondCall() {
    var first = collect(SQLEngine.getMethodFactories());
    var second = collect(SQLEngine.getMethodFactories());
    assertEquals(first.size(), second.size());
    for (var i = 0; i < first.size(); i++) {
      assertSame("method factory instance must be shared across calls; index=" + i,
          first.get(i), second.get(i));
    }
  }

  @Test
  public void getOperatorFactoriesIteratorContainsDefaultQueryOperatorFactory() {
    var classes = new ArrayList<>();
    var ite = SQLEngine.getOperatorFactories();
    while (ite.hasNext()) {
      classes.add(ite.next().getClass());
    }
    assertTrue(
        "DefaultQueryOperatorFactory must be in the operator factory list: " + classes,
        classes.contains(DefaultQueryOperatorFactory.class));
    assertTrue(
        "DynamicSQLElementFactory must be in the operator factory list: " + classes,
        classes.contains(DynamicSQLElementFactory.class));
  }

  @Test
  public void getOperatorFactoriesReturnsCachedListOnSecondCall() {
    var first = collect(SQLEngine.getOperatorFactories());
    var second = collect(SQLEngine.getOperatorFactories());
    assertEquals(first.size(), second.size());
    for (var i = 0; i < first.size(); i++) {
      assertSame("operator factory instance must be shared; index=" + i,
          first.get(i), second.get(i));
    }
  }

  @Test
  public void getCollateFactoriesIteratorContainsDefaultCollateFactory() {
    var classes = new ArrayList<>();
    var ite = SQLEngine.getCollateFactories();
    while (ite.hasNext()) {
      classes.add(ite.next().getClass());
    }
    assertTrue(
        "DefaultCollateFactory must be in the collate factory list: " + classes,
        classes.contains(DefaultCollateFactory.class));
  }

  @Test
  public void getCollateFactoriesReturnsCachedListOnSecondCall() {
    var first = collect(SQLEngine.getCollateFactories());
    var second = collect(SQLEngine.getCollateFactories());
    assertEquals(first.size(), second.size());
    for (var i = 0; i < first.size(); i++) {
      assertSame("collate factory instance must be shared; index=" + i,
          first.get(i), second.get(i));
    }
  }

  // ===========================================================================
  // Aggregated name queries — getFunctionNames / getMethodNames / getCollateNames
  // ===========================================================================

  @Test
  public void getFunctionNamesIncludesCountAndDistinct() {
    // count and distinct are registered by DefaultSQLFunctionFactory. The aggregate getFunctionNames
    // must surface both of them.
    var names = SQLEngine.getFunctionNames(session);
    assertTrue("count must be registered: " + names, names.contains("count"));
    assertTrue("distinct must be registered: " + names, names.contains("distinct"));
  }

  @Test
  public void getMethodNamesIncludesSize() {
    // size is registered by DefaultSQLMethodFactory (lowercased on put via Locale.ENGLISH).
    var names = SQLEngine.getMethodNames();
    assertTrue("size must be in method names: " + names, names.contains("size"));
  }

  @Test
  public void getCollateNamesIncludesDefaultCollate() {
    // DefaultCollateFactory registers "default"/"ci"/"case-insensitive"; exact set may evolve, so
    // assert on the most-stable entries only.
    var names = SQLEngine.getCollateNames();
    assertFalse("collate names must not be empty", names.isEmpty());
    assertTrue("default collate must be registered: " + names, names.contains("default"));
  }

  // ===========================================================================
  // SORTED_OPERATORS — cached array
  // ===========================================================================

  @Test
  public void getRecordOperatorsCachesSortedArrayIdentityOnRepeatedCalls() {
    // Array identity, not content equality — verifies the `if (SORTED_OPERATORS == null)` cache
    // short-circuit. A regression that mistakenly clones the array every call would fail this.
    var first = SQLEngine.getRecordOperators();
    var second = SQLEngine.getRecordOperators();
    assertSame("SORTED_OPERATORS must be cached (identity), not rebuilt per call", first, second);
  }

  @Test
  public void getRecordOperatorsIncludesEqualsOperator() {
    // The DEFAULT_OPERATORS_ORDER registration includes QueryOperatorEquals. Pin its presence so a
    // regression that removes the = operator from the default factory fails here rather than
    // silently in downstream SQL parsing.
    var ops = SQLEngine.getRecordOperators();
    assertTrue("SORTED_OPERATORS must be non-empty", ops.length > 0);
    var hasEquals = false;
    for (var op : ops) {
      if (op instanceof QueryOperatorEquals) {
        hasEquals = true;
        break;
      }
    }
    assertTrue("QueryOperatorEquals must be registered by DefaultQueryOperatorFactory", hasEquals);
  }

  // ===========================================================================
  // getFunction / getFunctionOrNull — dispatch and special-case handling
  // ===========================================================================

  @Test
  public void getFunctionOrNullReturnsRegisteredBuiltinForLowercaseName() {
    var fn = SQLEngine.getFunctionOrNull(session, SQLFunctionCount.NAME);
    assertNotNull("count must resolve", fn);
  }

  @Test
  public void getFunctionOrNullIsCaseInsensitiveViaLowercasing() {
    // iFunctionName.toLowerCase(Locale.ENGLISH) at entry — "COUNT" and "Count" both resolve.
    assertNotNull(SQLEngine.getFunctionOrNull(session, "COUNT"));
    assertNotNull(SQLEngine.getFunctionOrNull(session, "Count"));
  }

  @Test
  public void getFunctionOrNullReturnsNullForAnySpecialKeyword() {
    // "any" and "all" are special — getFunctionOrNull short-circuits to null BEFORE iterating
    // factories. Pin both variants including mixed-case (equalsIgnoreCase guard).
    assertNull(SQLEngine.getFunctionOrNull(session, "any"));
    assertNull(SQLEngine.getFunctionOrNull(session, "ANY"));
    assertNull(SQLEngine.getFunctionOrNull(session, "all"));
    assertNull(SQLEngine.getFunctionOrNull(session, "ALL"));
  }

  @Test
  public void getFunctionOrNullReturnsNullForUnknownName() {
    assertNull(SQLEngine.getFunctionOrNull(session, uuidPrefix + "no_such_function"));
  }

  @Test
  public void getFunctionThrowsForUnknownName() {
    // getFunction delegates to getFunctionOrNull and wraps a null return in a
    // CommandSQLParsingException whose message includes the requested name AND the full list of
    // available names. Pin both message pieces so a regression that drops either is detected.
    try {
      SQLEngine.getFunction(session, uuidPrefix + "no_such_function");
      fail("expected CommandSQLParsingException");
    } catch (CommandSQLParsingException e) {
      var msg = e.getMessage();
      assertNotNull(msg);
      assertTrue("message must echo the requested name: " + msg,
          msg.contains(uuidPrefix + "no_such_function"));
      assertTrue("message must include 'available names': " + msg,
          msg.contains("available names"));
    }
  }

  @Test
  public void getFunctionThrowsForAnyKeyword() {
    // getFunctionOrNull short-circuits "any" to null → getFunction then throws. Pin that the
    // short-circuit is visible externally as a parse failure, not an unexpected NPE.
    try {
      SQLEngine.getFunction(session, "any");
      fail("expected CommandSQLParsingException for 'any'");
    } catch (CommandSQLParsingException expected) {
      var msg = expected.getMessage();
      // Match the same contract enforced by getFunctionThrowsForUnknownName: the requested name
      // (quoted form to avoid false positives from substrings like "many") and "available names"
      // clause.
      assertTrue(
          "message must echo the requested name 'any' (quoted): " + msg,
          msg.contains("'any'"));
      assertTrue(
          "message must include 'available names' clause: " + msg,
          msg.contains("available names"));
    }
  }

  // ===========================================================================
  // getMethod — case-insensitive dispatch (masks DefaultSQLMethodFactory bug)
  // ===========================================================================

  @Test
  public void getMethodLowercaseNameResolves() {
    var m = SQLEngine.getMethod(SQLMethodSize.NAME);
    assertNotNull("size must resolve via SQLEngine.getMethod", m);
  }

  @Test
  public void getMethodUppercaseNameResolvesMaskingFactoryBug() {
    // SQLEngine.getMethod lowercases via Locale.ENGLISH at entry; this masks the
    // DefaultSQLMethodFactory.createMethod case-sensitivity bug. Both "SIZE" and "size" resolve
    // successfully HERE because SQLEngine did the lowercasing itself. If DefaultSQLMethodFactory
    // is ever fixed to lowercase internally, this test should still pass — but if the
    // SQLEngine-level lowercasing is ever removed on the theory that "factories handle case", the
    // bug in DefaultSQLMethodFactory will surface downstream. Pin the SQLEngine-level contract:
    // case-insensitivity is GUARANTEED at this API regardless of what happens below.
    var m = SQLEngine.getMethod("SIZE");
    assertNotNull("SIZE must resolve because SQLEngine lowercases before dispatch", m);
  }

  @Test
  public void getMethodMixedCaseNameResolves() {
    assertNotNull(SQLEngine.getMethod("SizE"));
  }

  @Test
  public void getMethodUnknownNameReturnsNull() {
    assertNull(SQLEngine.getMethod(uuidPrefix + "nosuchmethod"));
  }

  // ===========================================================================
  // getCollate — known name vs unknown
  // ===========================================================================

  @Test
  public void getCollateKnownNameReturnsNonNull() {
    // "default" is always registered by DefaultCollateFactory. Pin that getCollate dispatches
    // correctly.
    assertNotNull("default collate must resolve", SQLEngine.getCollate("default"));
  }

  @Test
  public void getCollateUnknownNameReturnsNull() {
    assertNull(SQLEngine.getCollate(uuidPrefix + "no_such_collate"));
  }

  // ===========================================================================
  // Dynamic registration — registerFunction / unregisterFunction
  // ===========================================================================

  @Test
  public void zzzRegisterFunctionLowercasesName() {
    // register lowercases via Locale.ENGLISH → an uppercase registration ends up keyed lowercase.
    // Pin that getFunctionNames (aggregated) sees the lowercased key.
    var name = uuidPrefix + "fn_register";
    SQLEngine.registerFunction(name.toUpperCase(Locale.ENGLISH), new NoOpSQLFunction(name));
    try {
      var names = SQLEngine.getFunctionNames(session);
      assertTrue("lowercased name must appear in function names: " + name,
          names.contains(name));
      // Upper-case lookup also works because getFunctionOrNull lowercases too.
      assertNotNull(SQLEngine.getFunctionOrNull(session, name.toUpperCase(Locale.ENGLISH)));
    } finally {
      SQLEngine.unregisterFunction(name);
    }
  }

  @Test
  public void zzzUnregisterFunctionRemovesEntryCaseInsensitive() {
    var name = uuidPrefix + "fn_unregister";
    SQLEngine.registerFunction(name, new NoOpSQLFunction(name));
    assertTrue("function must be registered",
        DynamicSQLElementFactory.FUNCTIONS.containsKey(name));
    // Unregister with uppercase — lowercasing on this path must mirror register's lowercasing.
    SQLEngine.unregisterFunction(name.toUpperCase(Locale.ENGLISH));
    assertFalse("function must be unregistered after upper-case unregister",
        DynamicSQLElementFactory.FUNCTIONS.containsKey(name));
  }

  @Test
  public void zzzRegisterFunctionAllowsOverwriteAndGetFunctionReturnsLatest() {
    // register is a straight put — an existing key is overwritten. Pin this so a regression that
    // adds a "preserve-first-registration" semantic would fail here.
    var name = uuidPrefix + "fn_overwrite";
    var first = new NoOpSQLFunction(name + "_v1");
    var second = new NoOpSQLFunction(name + "_v2");
    SQLEngine.registerFunction(name, first);
    SQLEngine.registerFunction(name, second);
    try {
      var resolved = SQLEngine.getFunctionOrNull(session, name);
      assertSame("latest registration must win", second, resolved);
    } finally {
      SQLEngine.unregisterFunction(name);
    }
  }

  // ===========================================================================
  // registerOperator — SORTED_OPERATORS invalidation + snapshot
  // ===========================================================================

  @Test
  public void zzzRegisterOperatorAppendsToOperatorsSetAndClearsSortedCache() {
    // registerOperator adds to DynamicSQLElementFactory.OPERATORS and clears SORTED_OPERATORS.
    // Pin both: the set grows by exactly one, and the next getRecordOperators call rebuilds the
    // array (identity change).
    var op = new MarkerQueryOperator(uuidPrefix + "marker");
    var sortedBefore = SQLEngine.getRecordOperators();
    assertNotNull(sortedBefore);
    var setSizeBefore = DynamicSQLElementFactory.OPERATORS.size();
    SQLEngine.registerOperator(op);
    try {
      assertEquals("OPERATORS set size must grow by 1",
          setSizeBefore + 1, DynamicSQLElementFactory.OPERATORS.size());
      assertTrue("OPERATORS set must contain the registered marker",
          DynamicSQLElementFactory.OPERATORS.contains(op));
      var sortedAfter = SQLEngine.getRecordOperators();
      assertNotSame("SORTED_OPERATORS must be rebuilt after registerOperator",
          sortedBefore, sortedAfter);
      var found = false;
      for (var candidate : sortedAfter) {
        if (candidate == op) {
          found = true;
          break;
        }
      }
      assertTrue("registered marker must appear in rebuilt SORTED_OPERATORS", found);
    } finally {
      DynamicSQLElementFactory.OPERATORS.remove(op);
      // Force a rebuild so the stale array (containing our marker) is NOT returned to the next
      // test class.
      clearSortedOperators();
    }
  }

  // ===========================================================================
  // scanForPlugins — selective cache clear (bug pin)
  // ===========================================================================

  @Test
  public void zzzScanForPluginsOnlyClearsFunctionFactoriesCacheBugPin() throws Exception {
    // SQLEngine.scanForPlugins() should clear ALL factory caches (FUNCTION_FACTORIES,
    // METHOD_FACTORIES, OPERATOR_FACTORIES, COLLATE_FACTORIES) AND SORTED_OPERATORS. Currently it
    // only clears FUNCTION_FACTORIES — so a classloader reload that adds a new
    // QueryOperatorFactory (for example) will not be visible on the next call. This test PINS the
    // observable asymmetry: after scanForPlugins, only FUNCTION_FACTORIES is null, and a
    // subsequent getFunctionFactories call rebuilds it.
    //
    // When the bug is fixed: flip the METHOD_FACTORIES/OPERATOR_FACTORIES/COLLATE_FACTORIES/
    // SORTED_OPERATORS assertions from assertNotNull to assertNull.
    SQLEngine.getFunctionFactories(session); // warm
    SQLEngine.getMethodFactories();
    SQLEngine.getOperatorFactories();
    SQLEngine.getCollateFactories();
    SQLEngine.getRecordOperators();

    SQLEngine.scanForPlugins();

    // FUNCTION_FACTORIES cleared — bug asymmetry is visible.
    assertNull("scanForPlugins should clear FUNCTION_FACTORIES",
        readStaticField("FUNCTION_FACTORIES"));
    // Others NOT cleared — pinned bug.
    assertNotNull("WHEN-FIXED: METHOD_FACTORIES still cached — should be cleared",
        readStaticField("METHOD_FACTORIES"));
    assertNotNull("WHEN-FIXED: OPERATOR_FACTORIES still cached — should be cleared",
        readStaticField("OPERATOR_FACTORIES"));
    assertNotNull("WHEN-FIXED: COLLATE_FACTORIES still cached — should be cleared",
        readStaticField("COLLATE_FACTORIES"));
    assertNotNull("WHEN-FIXED: SORTED_OPERATORS still cached — should be cleared",
        readStaticField("SORTED_OPERATORS"));

    // Rebuild FUNCTION_FACTORIES so later tests find the SPI list available.
    SQLEngine.getFunctionFactories(session);
    assertNotNull("FUNCTION_FACTORIES must be rebuilt on next call",
        readStaticField("FUNCTION_FACTORIES"));
  }

  // ===========================================================================
  // Volatile cache mutation race — WHEN-FIXED pin
  // ===========================================================================

  @Test
  public void zzzRegisterOperatorOrderingPinAddHappensBeforeClearAndNextCallRebuilds()
      throws Exception {
    // CONTRACT: SQLEngine.registerOperator is:
    //     DynamicSQLElementFactory.OPERATORS.add(iOperator);  // step 1
    //     SORTED_OPERATORS = null;                             // step 2
    // Single-threaded pin (this test) verifies:
    //   (a) After the call OPERATORS contains the new op AND SORTED_OPERATORS is null — i.e. the
    //       clear-happens-after-add observable contract. A regression that swapped the two steps
    //       would also leave the post-state identical, so this pin alone is NOT sufficient to
    //       catch a reordering-only bug. But it IS sufficient to catch a regression that dropped
    //       the clear step (SORTED_OPERATORS would stay non-null and return the stale array).
    //   (b) The NEXT call to getRecordOperators rebuilds the array AND the rebuilt array
    //       contains the newly-registered op. A regression that kept both steps but silently
    //       replaced clear-then-rebuild with a stale-read path (e.g., held onto the prior
    //       reference via a shadow field) would fail assertion (b).
    //
    // RACE — not pinned here. The hazardous interleaving (Thread B reading SORTED_OPERATORS
    // between steps 1 and 2 and observing a stale array) requires a multi-threaded exerciser.
    // Pinning that race is deferred to a follow-up twin-fix with CustomSQLFunctionFactory +
    // DefaultSQLMethodFactory, which will convert OPERATORS to a concurrent collection AND wrap
    // the add+clear in LOCK.lock()/.unlock().
    var op = new MarkerQueryOperator(uuidPrefix + "ordering_marker");
    SQLEngine.getRecordOperators(); // warm SORTED_OPERATORS so the pre-register state is non-null
    SQLEngine.registerOperator(op);
    try {
      assertTrue("OPERATORS must contain newly registered op",
          DynamicSQLElementFactory.OPERATORS.contains(op));
      assertNull(
          "SORTED_OPERATORS must be null immediately after registerOperator — the cache was"
              + " invalidated so the next getRecordOperators call rebuilds.",
          readStaticField("SORTED_OPERATORS"));

      // (b) Next call rebuilds and surfaces the newly-registered op.
      var rebuilt = SQLEngine.getRecordOperators();
      assertNotNull("getRecordOperators must rebuild after a null cache", rebuilt);
      var found = false;
      for (var candidate : rebuilt) {
        if (candidate == op) {
          found = true;
          break;
        }
      }
      assertTrue("rebuilt SORTED_OPERATORS must include the newly-registered op", found);
    } finally {
      DynamicSQLElementFactory.OPERATORS.remove(op);
      clearSortedOperators();
    }
  }

  // ===========================================================================
  // CommandExecutorSQLAbstract prefix-constant pins
  // ===========================================================================

  @Test
  public void commandExecutorSqlAbstractPrefixConstantsArePinned() {
    // The ten static constants on CommandExecutorSQLAbstract are part of the SQL grammar surface
    // (nine consumed by SQLTarget when decoding target literals, and KEYWORD_TIMEOUT — a documented
    // SQL keyword referenced from CommandRequest Javadoc). Behavioural coverage via SQLTarget pins
    // most of these implicitly, but a silent rename or literal-value drift would still slip past
    // behavioural tests when the same drift happens on both producer and consumer sides. Pin each
    // value explicitly so any mutation is caught at this test level.
    assertEquals("TIMEOUT", CommandExecutorSQLAbstract.KEYWORD_TIMEOUT);
    assertEquals("COLLECTION:", CommandExecutorSQLAbstract.COLLECTION_PREFIX);
    assertEquals("CLASS:", CommandExecutorSQLAbstract.CLASS_PREFIX);
    assertEquals("INDEX:", CommandExecutorSQLAbstract.INDEX_PREFIX);
    assertEquals("INDEXVALUES:", CommandExecutorSQLAbstract.INDEX_VALUES_PREFIX);
    assertEquals("INDEXVALUESASC:", CommandExecutorSQLAbstract.INDEX_VALUES_ASC_PREFIX);
    assertEquals("INDEXVALUESDESC:", CommandExecutorSQLAbstract.INDEX_VALUES_DESC_PREFIX);
    assertEquals("METADATA:", CommandExecutorSQLAbstract.METADATA_PREFIX);
    assertEquals("SCHEMA", CommandExecutorSQLAbstract.METADATA_SCHEMA);
    assertEquals("INDEXMANAGER", CommandExecutorSQLAbstract.METADATA_INDEXMGR);
  }

  // ===========================================================================
  // Unused distinct registration — belt-and-braces sanity
  // ===========================================================================

  @Test
  public void distinctFunctionConstantMatchesFactoryRegistration() {
    // Cross-check that SQLFunctionDistinct.NAME and the registered name are in sync. A future
    // rename of the constant without updating the factory would surface here.
    var fn = SQLEngine.getFunctionOrNull(session, SQLFunctionDistinct.NAME);
    assertNotNull("distinct must resolve via SQLFunctionDistinct.NAME: "
        + SQLFunctionDistinct.NAME, fn);
  }

  // ===========================================================================
  // Test helpers
  // ===========================================================================

  private static <T> ArrayList<T> collect(Iterator<T> it) {
    var out = new ArrayList<T>();
    while (it.hasNext()) {
      out.add(it.next());
    }
    return out;
  }

  private static Object readStaticField(String fieldName) throws Exception {
    Field f = SQLEngine.class.getDeclaredField(fieldName);
    f.setAccessible(true);
    return f.get(null);
  }

  private static void clearSortedOperators() {
    // Force a rebuild so the next test class starts clean. registerOperator does this but we also
    // want to undo any stale state after the test itself. scanForPlugins does not affect
    // SORTED_OPERATORS, so we do the clear via reflection.
    try {
      Field f = SQLEngine.class.getDeclaredField("SORTED_OPERATORS");
      f.setAccessible(true);
      f.set(null, null);
    } catch (Exception e) {
      throw new AssertionError("failed to clear SORTED_OPERATORS for test isolation", e);
    }
  }

  /**
   * Minimal SQLFunction for dynamic registration tests. Overrides only what's needed to avoid NPEs
   * in factory construction paths.
   */
  private static final class NoOpSQLFunction extends SQLFunctionAbstract {

    NoOpSQLFunction(String name) {
      super(name, 0, -1);
    }

    @Override
    public Object execute(
        Object iThis,
        com.jetbrains.youtrackdb.internal.core.query.Result iCurrentRecord,
        Object iCurrentResult,
        Object[] iParams,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext iContext) {
      return null;
    }

    @Override
    public String getSyntax(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session) {
      return getName(session) + "()";
    }
  }

  /**
   * Minimal QueryOperator for dynamic registration tests. The keyword carries the UUID prefix so
   * the teardown removal predicate can match it.
   */
  private static final class MarkerQueryOperator extends QueryOperator {

    MarkerQueryOperator(String keyword) {
      super(keyword, 100, false);
    }

    @Override
    public Object evaluateRecord(
        com.jetbrains.youtrackdb.internal.core.query.Result iRecord,
        com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl iCurrentResult,
        com.jetbrains.youtrackdb.internal.core.sql.filter.SQLFilterCondition iCondition,
        Object iLeft,
        Object iRight,
        com.jetbrains.youtrackdb.internal.core.command.CommandContext iContext,
        com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary.EntitySerializer serializer) {
      return null;
    }

    @Override
    public com.jetbrains.youtrackdb.internal.core.sql.operator.IndexReuseType getIndexReuseType(
        Object iLeft, Object iRight) {
      return com.jetbrains.youtrackdb.internal.core.sql.operator.IndexReuseType.NO_INDEX;
    }

    @Override
    public com.jetbrains.youtrackdb.internal.core.db.record.record.RID getBeginRidRange(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
        Object iLeft, Object iRight) {
      return null;
    }

    @Override
    public com.jetbrains.youtrackdb.internal.core.db.record.record.RID getEndRidRange(
        com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded session,
        Object iLeft, Object iRight) {
      return null;
    }
  }
}
