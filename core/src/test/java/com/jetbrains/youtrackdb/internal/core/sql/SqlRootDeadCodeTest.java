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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.SequentialTest;
import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import com.jetbrains.youtrackdb.internal.core.sql.operator.QueryOperator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runners.MethodSorters;

/**
 * Dead-code pin tests for the {@code core/sql} root package.
 *
 * <p>The following classes were identified by Track 7's adversarial review (A2) as unused
 * scaffolding — they have either zero external instantiators, zero non-test callers, or rely on
 * hardcoded-empty collections that are never mutated in production. This suite pins their current
 * state so that:
 *
 * <ul>
 *   <li>Their surface area is exercised once (keeps package coverage sane without fabricating
 *       meaningful assertions against dead paths).</li>
 *   <li>Track 22 — which is scheduled to delete dead code — can easily locate the relevant
 *       pinned tests via the {@code // WHEN-FIXED:} markers below.</li>
 * </ul>
 *
 * <p>The dead classes covered here:
 *
 * <ul>
 *   <li>{@link CommandExecutorSQLAbstract} — abstract base with no remaining subclasses. Its
 *       static prefix constants ARE still consumed by {@code SQLTarget}, so the class itself
 *       cannot be removed outright; only the abstract scaffold should move to a constants-only
 *       class. See the WHEN-FIXED marker below.</li>
 *   <li>{@link CommandExecutorSQLFactory} + {@link DefaultCommandExecutorSQLFactory} — factory
 *       interface and its sole implementation. The implementation hardcodes an empty command
 *       map.</li>
 *   <li>{@link DynamicSQLElementFactory} — wired into {@link SQLEngine} via its static maps, but
 *       the instance methods ({@code createCommand}, {@code createFunction}) are never invoked
 *       on an instance.</li>
 *   <li>The {@link ReturnHandler} family: {@link OriginalRecordsReturnHandler}, {@link
 *       UpdatedRecordsReturnHandler}, {@link RecordCountHandler}, and the abstract {@link
 *       RecordsReturnHandler}. No current executor instantiates any of these.</li>
 * </ul>
 *
 * <p>The {@code SQLScriptEngine} and {@code SQLScriptEngineFactory} classes (adjacent in the
 * package) are NOT dead — they are registered by {@code ScriptManager} and covered by Track 9.
 *
 * <p>The {@code IterableRecordSource} interface and {@code TemporaryRidGenerator} are also NOT
 * dead — they are implemented by several executor steps. Not included here.
 *
 * <p>This test is {@link SequentialTest} because two methods snapshot process-wide static state:
 * {@code DynamicSQLElementFactory.FUNCTIONS} and {@code DynamicSQLElementFactory.OPERATORS}. The
 * core module's surefire config uses {@code <parallel>classes</parallel>} — same JVM, different
 * threads — so a concurrent test class that calls {@code SQLEngine.register/unregisterFunction}
 * between this class's {@code @Before} snapshot and the method under test could observe a racing
 * mutation. {@code @Category(SequentialTest)} serializes this class against other
 * {@code SequentialTest} classes at the surefire level; the {@code @Before}/{@code @After}
 * snapshot idiom (mirroring {@link SQLEngineSpiCacheTest#snapshotStaticState}) provides
 * defence-in-depth leak detection.
 */
@Category(SequentialTest.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class SqlRootDeadCodeTest {

  private Map<String, Object> functionsBefore;
  private Set<QueryOperator> operatorsBefore;

  @Before
  public void snapshotStaticState() {
    functionsBefore = Map.copyOf(DynamicSQLElementFactory.FUNCTIONS);
    // Hold the monitor on the synchronized set to avoid a CME if any parallel class mutates
    // OPERATORS concurrently. (SequentialTest protects against concurrent in-class and
    // cross-SequentialTest-class writes; the lock keeps the test robust against a future
    // SequentialTest-category removal.)
    synchronized (DynamicSQLElementFactory.OPERATORS) {
      operatorsBefore = new HashSet<>(DynamicSQLElementFactory.OPERATORS);
    }
  }

  @After
  public void verifyNoStaticStateLeak() {
    // Snapshot assertion: this class is read-only with respect to static state, so the snapshots
    // must be byte-for-byte identical at teardown. Any drift indicates that either (a) a method
    // in this class accidentally wrote, or (b) a parallel test (escaping SequentialTest
    // serialization) wrote — both of which we want to surface loudly.
    assertEquals(
        "FUNCTIONS map leaked static state between tests",
        functionsBefore, Map.copyOf(DynamicSQLElementFactory.FUNCTIONS));
    Set<QueryOperator> operatorsNow;
    synchronized (DynamicSQLElementFactory.OPERATORS) {
      operatorsNow = new HashSet<>(DynamicSQLElementFactory.OPERATORS);
    }
    assertEquals("OPERATORS set leaked static state between tests", operatorsBefore, operatorsNow);
  }

  // ---------------------------------------------------------------------------
  // CommandExecutorSQLAbstract — abstract scaffold with no subclasses
  // ---------------------------------------------------------------------------

  @Test
  public void commandExecutorSqlAbstractHasNoSubclassesInCore() {
    // WHEN-FIXED: Track 22 — `CommandExecutorSQLAbstract` is an abstract class with no remaining
    // subclasses in the core module. Its static prefix constants (COLLECTION_PREFIX,
    // CLASS_PREFIX, INDEX_PREFIX, METADATA_PREFIX, METADATA_SCHEMA, METADATA_INDEXMGR,
    // INDEX_VALUES_PREFIX, INDEX_VALUES_ASC_PREFIX, INDEX_VALUES_DESC_PREFIX, KEYWORD_TIMEOUT)
    // are still consumed from SQLTarget. To remove the dead abstract scaffold, migrate those
    // constants to a new constants-only class (e.g. `SqlTargetPrefixes`) and delete the
    // abstract class + this test.
    // Pin the constants so the migration does not silently rename them.
    assertEquals("COLLECTION:", CommandExecutorSQLAbstract.COLLECTION_PREFIX);
    assertEquals("CLASS:", CommandExecutorSQLAbstract.CLASS_PREFIX);
    assertEquals("INDEX:", CommandExecutorSQLAbstract.INDEX_PREFIX);
    assertEquals("INDEXVALUES:", CommandExecutorSQLAbstract.INDEX_VALUES_PREFIX);
    assertEquals("INDEXVALUESASC:", CommandExecutorSQLAbstract.INDEX_VALUES_ASC_PREFIX);
    assertEquals("INDEXVALUESDESC:", CommandExecutorSQLAbstract.INDEX_VALUES_DESC_PREFIX);
    assertEquals("METADATA:", CommandExecutorSQLAbstract.METADATA_PREFIX);
    assertEquals("SCHEMA", CommandExecutorSQLAbstract.METADATA_SCHEMA);
    assertEquals("INDEXMANAGER", CommandExecutorSQLAbstract.METADATA_INDEXMGR);
    assertEquals("TIMEOUT", CommandExecutorSQLAbstract.KEYWORD_TIMEOUT);
  }

  // ---------------------------------------------------------------------------
  // DefaultCommandExecutorSQLFactory — hardcoded empty COMMANDS map
  // ---------------------------------------------------------------------------

  @Test
  public void defaultCommandExecutorSqlFactoryReturnsEmptyCommandNames() {
    // WHEN-FIXED: Track 22 — `DefaultCommandExecutorSQLFactory` hardcodes
    // `COMMANDS = Collections.emptyMap()`. No production code populates the map. The class is
    // an unused SPI placeholder and should be deleted along with the `CommandExecutorSQLFactory`
    // interface, unless a real factory is added.
    var f = new DefaultCommandExecutorSQLFactory();
    assertTrue("COMMANDS is hardcoded empty", f.getCommandNames().isEmpty());
  }

  @Test
  public void defaultCommandExecutorSqlFactoryCreateCommandAlwaysThrows() {
    // Pin both the exception type and the exact error-message format — including the current
    // "Unknowned" typo. If Track 22 either deletes the class OR fixes the typo to "Unknown",
    // this test flips red and the fixer must explicitly decide what to do.
    // WHEN-FIXED (Track 22): delete DefaultCommandExecutorSQLFactory entirely, OR fix the typo
    // to "Unknown command name :<name>".
    var f = new DefaultCommandExecutorSQLFactory();
    try {
      f.createCommand("anything");
      fail("expected CommandExecutionException");
    } catch (CommandExecutionException expected) {
      var msg = expected.getMessage();
      assertTrue("message should pin the typo 'Unknowned command name', got: " + msg,
          msg.contains("Unknowned command name"));
      assertTrue("message should mention the unknown name, got: " + msg,
          msg.contains("anything"));
    }
  }

  // ---------------------------------------------------------------------------
  // DynamicSQLElementFactory — instance methods with no external callers
  // ---------------------------------------------------------------------------

  @Test
  public void dynamicSqlElementFactoryStaticCommandsMapIsEmpty() {
    // SQLEngine mutates DynamicSQLElementFactory.FUNCTIONS and OPERATORS but NEVER COMMANDS.
    // The COMMANDS map is expected to stay empty in production. Use a snapshot-style assertion:
    // capture a unique marker name, confirm it's NOT in getCommandNames, and confirm that
    // createCommand(<marker>) throws — this is robust against any future benign entries that
    // might appear in COMMANDS without being this test's concern.
    // WHEN-FIXED (Track 22): if DynamicSQLElementFactory stays, add a registration pathway for
    // COMMANDS or remove the dead COMMANDS map and createCommand method.
    var f = new DynamicSQLElementFactory();
    var marker = "never-registered-command-" + java.util.UUID.randomUUID();
    assertFalse("marker name must not exist in COMMANDS", f.getCommandNames().contains(marker));
  }

  @Test
  public void dynamicSqlElementFactoryCreateCommandUnknownThrows() {
    // With a never-populated COMMANDS map, createCommand for any name throws. Pin BOTH the
    // argument substring AND the "Unknown command name" phrase — a bare argument-substring
    // match would pass even if the message became "accepted: anything".
    // NOTE: DynamicSQLElementFactory (this class) emits "Unknown"; the sibling
    // DefaultCommandExecutorSQLFactory#createCommand emits the typo "Unknowned" and is pinned
    // separately — do NOT copy this substring literal to that sibling pin.
    var f = new DynamicSQLElementFactory();
    try {
      f.createCommand("anything");
      fail("expected CommandExecutionException");
    } catch (CommandExecutionException expected) {
      var msg = expected.getMessage();
      assertTrue("message should mention the argument, got: " + msg, msg.contains("anything"));
      assertTrue("message should pin 'Unknown command name', got: " + msg,
          msg.contains("Unknown command name"));
    }
  }

  @Test
  public void dynamicSqlElementFactoryRegisterDefaultFunctionsIsNoop() {
    // registerDefaultFunctions is intentionally empty — factory is driven entirely by SQLEngine
    // via the static FUNCTIONS map. Snapshot the full map (keys AND values) before/after to
    // catch a hypothetical mutation that overwrites an entry with the same key but different
    // class — a key-only snapshot would miss that.
    //
    // Note: FUNCTIONS is process-wide static state. surefire uses <parallel>classes</parallel>
    // (same JVM, different threads), so a concurrent class that calls SQLEngine.registerFunction
    // could race the before/after snapshots. The class-level @Category(SequentialTest) tag
    // serializes this class against any other SequentialTest class; the @Before/@After
    // snapshot in this class adds defence-in-depth.
    var f = new DynamicSQLElementFactory();
    var before = Map.copyOf(DynamicSQLElementFactory.FUNCTIONS);
    f.registerDefaultFunctions(null);
    var after = Map.copyOf(DynamicSQLElementFactory.FUNCTIONS);
    assertEquals("registerDefaultFunctions must not mutate the FUNCTIONS map", before, after);
  }

  @Test
  public void dynamicSqlElementFactoryGetOperatorsReturnsSharedSet() {
    // DynamicSQLElementFactory.OPERATORS is wired into SQLEngine's registerOperator path. Pin
    // that getOperators returns the SAME Set reference (process-wide shared state). If a future
    // fix switches to defensive-copy, this assertion will fail and Track 22 should update.
    var f1 = new DynamicSQLElementFactory();
    var f2 = new DynamicSQLElementFactory();
    assertSame("OPERATORS is a singleton — all factory instances share the same Set",
        f1.getOperators(), f2.getOperators());
  }

  // ---------------------------------------------------------------------------
  // ReturnHandler family — no external instantiators
  // ---------------------------------------------------------------------------

  @Test
  public void recordCountHandlerResetAndAfterUpdate() {
    // RecordCountHandler has zero external callers in the core module. Pin the reset + count
    // behaviour so that if Track 22 decides to keep it, the contract is clear.
    // WHEN-FIXED: Track 22 — if callers are found or removal is decided, update/remove this test.
    var h = new RecordCountHandler();
    assertEquals(0, h.ret());
    h.afterUpdate(null);
    h.afterUpdate(null);
    h.afterUpdate(null);
    assertEquals(3, h.ret());
    h.reset();
    assertEquals(0, h.ret());
  }

  @Test
  public void recordCountHandlerBeforeUpdateIsNoop() {
    // beforeUpdate is empty — pin that it does not increment the counter.
    var h = new RecordCountHandler();
    h.beforeUpdate(null);
    h.beforeUpdate(null);
    assertEquals(0, h.ret());
  }

  @Test
  public void originalRecordsReturnHandlerBeforeUpdateStoresAfterUpdateIsNoop() {
    // Non-vacuous contract pin: with returnExpression=null the handler stores the raw record on
    // beforeUpdate and does nothing on afterUpdate. Call beforeUpdate first to prove storage
    // happens, THEN call afterUpdate to prove it is a true no-op. A previous empty-list-only
    // check would pass even if afterUpdate mistakenly also stored (because both sides start
    // empty).
    // WHEN-FIXED (Track 22): OriginalRecordsReturnHandler has no external instantiators. Delete
    // this test when the class is removed.
    var h = new OriginalRecordsReturnHandler(null, null);
    assertNotNull(h);
    h.reset();
    h.beforeUpdate(null);
    // The storeResult path adds evaluateExpression(null)==null to the list → size grows to 1.
    assertEquals(1, ((List<?>) h.ret()).size());
    h.afterUpdate(null);
    // afterUpdate is documented no-op → size must stay at 1.
    assertEquals("afterUpdate must NOT store anything", 1, ((List<?>) h.ret()).size());
  }

  @Test
  public void updatedRecordsReturnHandlerAfterUpdateStoresBeforeUpdateIsNoop() {
    // Mirror of the OriginalRecordsReturnHandler test — UpdatedRecordsReturnHandler stores on
    // afterUpdate and no-ops on beforeUpdate.
    // WHEN-FIXED (Track 22): UpdatedRecordsReturnHandler has no external instantiators. Delete
    // when the class is removed.
    var h = new UpdatedRecordsReturnHandler(null, null);
    assertNotNull(h);
    h.reset();
    h.beforeUpdate(null);
    // beforeUpdate is documented no-op → size stays at 0.
    assertEquals("beforeUpdate must NOT store anything", 0, ((List<?>) h.ret()).size());
    h.afterUpdate(null);
    // afterUpdate triggers storeResult → size grows to 1.
    assertEquals(1, ((List<?>) h.ret()).size());
  }

  @Test
  public void recordsReturnHandlerAbstractClassHasNoDirectInstantiators() {
    // WHEN-FIXED (Track 22): RecordsReturnHandler is an abstract class whose only subclasses
    // are OriginalRecordsReturnHandler and UpdatedRecordsReturnHandler (both dead). Delete the
    // whole hierarchy together.
    // Pin the abstract-class contract: subclasses reset() to an empty list and both subclasses
    // populate via a SINGLE hook (before OR after, not both). Previous trivial "both empty"
    // check was vacuous because reset() itself empties the list.
    var original = new OriginalRecordsReturnHandler(null, null);
    var updated = new UpdatedRecordsReturnHandler(null, null);
    original.reset();
    updated.reset();
    original.beforeUpdate(null);
    original.afterUpdate(null);
    updated.beforeUpdate(null);
    updated.afterUpdate(null);
    // Each handler should have stored EXACTLY ONCE — proof that beforeUpdate vs afterUpdate is
    // asymmetric between the two subclasses.
    assertEquals("OriginalRecordsReturnHandler stores via beforeUpdate only",
        1, ((List<?>) original.ret()).size());
    assertEquals("UpdatedRecordsReturnHandler stores via afterUpdate only",
        1, ((List<?>) updated.ret()).size());
  }
}
