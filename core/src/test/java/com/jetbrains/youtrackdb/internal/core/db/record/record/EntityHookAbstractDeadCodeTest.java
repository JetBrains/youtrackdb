/*
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
package com.jetbrains.youtrackdb.internal.core.db.record.record;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHook.TYPE;
import java.util.EnumMap;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Shape pin for {@link EntityHookAbstract}. PSI all-scope {@code ReferencesSearch} confirms
 * that <strong>no production source file</strong> across the full module graph extends
 * {@code EntityHookAbstract} or imports it for instantiation; the only subclasses live in
 * {@code core/src/test/} and {@code tests/src/test/} (CheckHookCallCountTest$TestHook,
 * HookChangeValidationTest's anonymous subclasses, DbListenerTest's anonymous subclass).
 * The class is therefore production-dead but test-reachable — a slightly weaker form of the
 * pure dead-code pattern. Deletion in the deferred-cleanup track must update those test
 * files in lockstep (either delete them, retarget them at {@link RecordHookAbstract}, or
 * inline the entity-specific dispatch into each test subclass).
 *
 * <p>Pin shape — the load-bearing observables are:
 * <ul>
 *   <li>The early-return guard in {@code onTrigger}: a non-{@link Entity} {@link DBRecord}
 *       short-circuits without invoking any callback.
 *   <li>The {@code onTrigger} switch: each of the seven {@link TYPE} values dispatches to
 *       its corresponding {@code on*Entity*} callback, with the entity passed through
 *       unchanged.
 *   <li>The mutual-exclusion contract on {@code setIncludeClasses} / {@code setExcludeClasses}.
 *   <li>The fluent self-return on {@code setExcludeClasses} — pinned via {@code assertSame}
 *       per established precedent in this branch.
 * </ul>
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete {@code EntityHookAbstract} together with
 * this test file; the remaining test subclasses must either be deleted or retargeted at
 * {@link RecordHookAbstract} or {@link RecordHook}.
 *
 * <p>Standalone — no database session needed. The {@code filterBySchemaClass} default-true
 * branch (both classes-list null) is the only branch reachable without a session, and it
 * is exercised implicitly by every dispatch test below.
 */
public class EntityHookAbstractDeadCodeTest {

  /** Recording subclass — captures every {@code on*Entity*} invocation by TYPE. */
  private static final class RecordingEntityHook extends EntityHookAbstract {
    private final EnumMap<TYPE, Entity> calls = new EnumMap<>(TYPE.class);

    @Override
    public void onEntityRead(Entity entity) {
      calls.put(TYPE.READ, entity);
    }

    @Override
    public void onBeforeEntityCreate(Entity entity) {
      calls.put(TYPE.BEFORE_CREATE, entity);
    }

    @Override
    public void onAfterEntityCreate(Entity entity) {
      calls.put(TYPE.AFTER_CREATE, entity);
    }

    @Override
    public void onBeforeEntityUpdate(Entity entity) {
      calls.put(TYPE.BEFORE_UPDATE, entity);
    }

    @Override
    public void onAfterEntityUpdate(Entity entity) {
      calls.put(TYPE.AFTER_UPDATE, entity);
    }

    @Override
    public void onBeforeEntityDelete(Entity entity) {
      calls.put(TYPE.BEFORE_DELETE, entity);
    }

    @Override
    public void onAfterEntityDelete(Entity entity) {
      calls.put(TYPE.AFTER_DELETE, entity);
    }
  }

  // ---------------------------------------------------------------------------
  // The early-return guard: non-Entity DBRecord is silently ignored
  // ---------------------------------------------------------------------------

  @Test
  public void onTriggerWithNonEntityRecordIsSilentlyIgnoredForEveryHookType() {
    // The guard `if (!(iRecord instanceof Entity entity)) { return; }` short-circuits
    // every callback. Pin all 7 TYPE values — a future change that drops the guard for
    // (say) READ would slip through with only one TYPE pinned.
    var hook = new RecordingEntityHook();
    var nonEntity = Mockito.mock(DBRecord.class);

    for (var type : TYPE.values()) {
      hook.onTrigger(type, nonEntity);
    }
    assertTrue(
        "no callback must be invoked when the record is not an Entity (early-return guard)",
        hook.calls.isEmpty());
    Mockito.verifyNoInteractions(nonEntity);
  }

  @Test
  public void onTriggerDispatchesEachTypeToTheMatchingEntityCallbackWithEntityPassedThrough() {
    // Positive complement of onTriggerWithNonEntityRecordIsSilentlyIgnoredForEveryHookType:
    // every TYPE value drives a distinct on*Entity* callback when the record IS an Entity
    // and the (default-true) class filter is satisfied. Pin per-arm with a unique mock per
    // TYPE so that a swapped or renamed branch (e.g. BEFORE_UPDATE arm calling
    // onBeforeEntityCreate) surfaces as an identity mismatch rather than a silent shrug.
    // Sibling RecordHookAbstractDeadCodeTest covers the same shape for the record-side
    // base; these two together pin the full dispatch surface.
    var hook = new RecordingEntityHook();
    var perTypeEntity = new EnumMap<TYPE, Entity>(TYPE.class);
    for (var type : TYPE.values()) {
      var entity = Mockito.mock(Entity.class);
      perTypeEntity.put(type, entity);
      hook.onTrigger(type, entity);
    }
    assertEquals(
        "each of the 7 TYPE values must drive exactly one callback",
        TYPE.values().length, hook.calls.size());
    for (var type : TYPE.values()) {
      assertSame(
          "TYPE." + type
              + " must dispatch to the matching on*Entity* callback with the entity passed through",
          perTypeEntity.get(type), hook.calls.get(type));
    }
  }

  // ---------------------------------------------------------------------------
  // The setIncludeClasses / setExcludeClasses mutual-exclusion contract
  // ---------------------------------------------------------------------------

  @Test
  public void getIncludeAndExcludeClassesAreNullByDefault() {
    var hook = new RecordingEntityHook();
    assertNull("includeClasses must default to null", hook.getIncludeClasses());
    assertNull("excludeClasses must default to null", hook.getExcludeClasses());
  }

  @Test
  public void setIncludeClassesStoresArrayAndIsRetrievableViaGetter() {
    var hook = new RecordingEntityHook();
    hook.setIncludeClasses("Foo", "Bar");
    assertArrayEquals(
        new String[] {"Foo", "Bar"}, hook.getIncludeClasses());
    assertNull("excludeClasses must remain null after include is set",
        hook.getExcludeClasses());
  }

  @Test
  public void setExcludeClassesStoresArrayAndIsRetrievableViaGetter() {
    var hook = new RecordingEntityHook();
    hook.setExcludeClasses("Foo", "Bar");
    assertArrayEquals(
        new String[] {"Foo", "Bar"}, hook.getExcludeClasses());
    assertNull("includeClasses must remain null after exclude is set",
        hook.getIncludeClasses());
  }

  @Test
  public void setExcludeClassesReturnsTheReceiverForFluentChaining() {
    // Self-return is the documented signature; pin via assertSame so any future refactor
    // that returns a copy or null is caught (would compile but break chained usages).
    var hook = new RecordingEntityHook();
    assertSame("setExcludeClasses must return the receiver for chaining",
        hook, hook.setExcludeClasses("X"));
  }

  @Test
  public void setIncludeAfterSetExcludeRaisesIllegalStateException() {
    var hook = new RecordingEntityHook();
    hook.setExcludeClasses("Foo");
    var thrown = assertThrows(
        "include/exclude must be mutually exclusive — setting include after exclude must throw",
        IllegalStateException.class,
        () -> hook.setIncludeClasses("Bar"));
    assertEquals(
        "Cannot include classes if exclude classes has been set",
        thrown.getMessage());
  }

  @Test
  public void setExcludeAfterSetIncludeRaisesIllegalStateException() {
    var hook = new RecordingEntityHook();
    hook.setIncludeClasses("Foo");
    var thrown = assertThrows(
        "include/exclude must be mutually exclusive — setting exclude after include must throw",
        IllegalStateException.class,
        () -> hook.setExcludeClasses("Bar"));
    assertEquals(
        "Cannot exclude classes if include classes has been set",
        thrown.getMessage());
  }

  // ---------------------------------------------------------------------------
  // Empty-default callbacks — calling them directly must not throw
  // ---------------------------------------------------------------------------

  @Test
  public void emptyDefaultCallbacksDoNotThrowWhenInvokedDirectly() {
    // Each on*Entity* callback in the abstract base has an empty body. A no-override
    // subclass must therefore be able to invoke them with any argument (including null
    // — the bodies don't dereference) without exception. This pins the "empty default"
    // contract: a future refactor that adds defensive logic must update this test.
    var bareHook = new EntityHookAbstract() {
      // intentionally empty — uses every default
    };
    bareHook.onEntityRead(null);
    bareHook.onBeforeEntityCreate(null);
    bareHook.onAfterEntityCreate(null);
    bareHook.onBeforeEntityUpdate(null);
    bareHook.onAfterEntityUpdate(null);
    bareHook.onBeforeEntityDelete(null);
    bareHook.onAfterEntityDelete(null);
    // No assertion needed — JUnit reports the test as failed if any of the seven calls
    // above throws. Reaching this line is the contract the test pins.
  }
}
