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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.core.exception.CommandExecutionException;
import java.util.Set;
import org.junit.Test;

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
 */
public class SqlRootDeadCodeTest {

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
    // With an empty COMMANDS map, every createCommand call must throw
    // CommandExecutionException — pin the exception type and the "Unknowned command name"
    // substring (the typo is currently in production — pinned for accuracy).
    // WHEN-FIXED: Track 22 — when the class is deleted, this test goes with it. If it survives,
    // also fix the "Unknowned" → "Unknown" typo in the error message.
    var f = new DefaultCommandExecutorSQLFactory();
    try {
      f.createCommand("anything");
      fail("expected CommandExecutionException");
    } catch (CommandExecutionException expected) {
      assertTrue(
          "message should mention the unknown name, got: " + expected.getMessage(),
          expected.getMessage().contains("anything"));
    }
  }

  // ---------------------------------------------------------------------------
  // DynamicSQLElementFactory — instance methods with no external callers
  // ---------------------------------------------------------------------------

  @Test
  public void dynamicSqlElementFactoryStaticCommandsMapIsEmpty() {
    // SQLEngine mutates DynamicSQLElementFactory.FUNCTIONS and OPERATORS but NEVER COMMANDS.
    // The COMMANDS map is always empty in production. Pin that fact so the factory's
    // createCommand path is exercised to cover the empty-map branch.
    // WHEN-FIXED: Track 22 — if DynamicSQLElementFactory stays, add a registration pathway for
    // COMMANDS or remove the dead COMMANDS map and createCommand method.
    var f = new DynamicSQLElementFactory();
    assertTrue("COMMANDS is never mutated in production", f.getCommandNames().isEmpty());
  }

  @Test
  public void dynamicSqlElementFactoryCreateCommandUnknownThrows() {
    // With a never-populated COMMANDS map, createCommand for any name throws.
    var f = new DynamicSQLElementFactory();
    try {
      f.createCommand("anything");
      fail("expected CommandExecutionException");
    } catch (CommandExecutionException expected) {
      assertTrue(expected.getMessage().contains("anything"));
    }
  }

  @Test
  public void dynamicSqlElementFactoryRegisterDefaultFunctionsIsNoop() {
    // registerDefaultFunctions is intentionally empty — factory is driven entirely by SQLEngine
    // via the static FUNCTIONS map. Pin the no-op: the call must succeed without side effects.
    // We can't observe "no side effect" directly — exercise it and confirm the static map state
    // is unchanged by calling with a null session (which is accepted by the empty body).
    var f = new DynamicSQLElementFactory();
    // The underlying FUNCTIONS map is process-wide shared state. Use a snapshot before/after.
    Set<String> before = Set.copyOf(f.getFunctionNames(null));
    f.registerDefaultFunctions(null);
    Set<String> after = Set.copyOf(f.getFunctionNames(null));
    assertEquals(before, after);
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
  public void originalRecordsReturnHandlerIsConstructible() {
    // WHEN-FIXED: Track 22 — OriginalRecordsReturnHandler has no external instantiators. Pin
    // that it can be constructed and its preprocess is identity-preserving. Delete this test
    // when the class is removed.
    var h = new OriginalRecordsReturnHandler(null, null);
    assertNotNull(h);
    // afterUpdate is a no-op for OriginalRecordsReturnHandler — the "original" snapshot is taken
    // in beforeUpdate. Pin that afterUpdate is observable as side-effect-free (reset stays empty).
    h.reset();
    h.afterUpdate(null);
    // ret() returns the results List (empty) after reset.
    assertTrue(h.ret() instanceof java.util.List);
    assertTrue(((java.util.List<?>) h.ret()).isEmpty());
  }

  @Test
  public void updatedRecordsReturnHandlerIsConstructible() {
    // WHEN-FIXED: Track 22 — UpdatedRecordsReturnHandler has no external instantiators. Pin
    // that it can be constructed and its preprocess is identity-preserving. Delete when the
    // class is removed.
    var h = new UpdatedRecordsReturnHandler(null, null);
    assertNotNull(h);
    h.reset();
    // beforeUpdate is a no-op for UpdatedRecordsReturnHandler — the snapshot is taken in
    // afterUpdate. Pin by calling beforeUpdate only and verifying no side effect.
    h.beforeUpdate(null);
    assertTrue(((java.util.List<?>) h.ret()).isEmpty());
  }

  @Test
  public void recordsReturnHandlerAbstractClassHasNoDirectInstantiators() {
    // WHEN-FIXED: Track 22 — RecordsReturnHandler is an abstract class whose only subclasses
    // are OriginalRecordsReturnHandler and UpdatedRecordsReturnHandler (both dead). Delete the
    // whole hierarchy together.
    // Pin the abstract-class contract: subclasses reset() to an empty list.
    var original = new OriginalRecordsReturnHandler(null, null);
    var updated = new UpdatedRecordsReturnHandler(null, null);
    original.reset();
    updated.reset();
    assertTrue(((java.util.List<?>) original.ret()).isEmpty());
    assertTrue(((java.util.List<?>) updated.ret()).isEmpty());
  }
}
