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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.record.RecordHook.TYPE;
import java.util.EnumMap;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Shape pin for {@link RecordHookAbstract}. PSI all-scope {@code ReferencesSearch} confirms
 * the only production-source reference is a Javadoc {@code @see} tag in
 * {@link RecordHook}.java; every concrete subclass lives in {@code tests/src/test/}
 * (BrokenMapHook, HookTxTest$RecordHook). The class is therefore production-dead but
 * test-reachable. Deletion in the deferred-cleanup track must update those test files in
 * lockstep — either delete them, or retarget them at the {@link RecordHook} interface
 * directly (which already provides a {@code default} no-op for {@code onUnregister} and
 * abstract {@code onTrigger}).
 *
 * <p>The single load-bearing observable is the {@link #onTrigger(TYPE, DBRecord)} switch:
 * unlike {@link EntityHookAbstract}, this base does <strong>not</strong> guard on record
 * type — every {@link DBRecord} dispatches to the matching {@code on*Record*} callback.
 * Pinning all 7 cases catches both a renamed callback and an accidental swap (e.g.
 * BEFORE_UPDATE arm calling {@code onBeforeRecordCreate}).
 *
 * <p>WHEN-FIXED: deferred-cleanup track — delete this class together with this test file
 * once the test subclasses (BrokenMapHook, HookTxTest$RecordHook) are retargeted directly
 * at {@link RecordHook}.
 *
 * <p>Standalone — no database session needed.
 */
public class RecordHookAbstractDeadCodeTest {

  /** Recording subclass — captures every {@code on*Record*} invocation by TYPE. */
  private static final class RecordingRecordHook extends RecordHookAbstract {
    private final EnumMap<TYPE, DBRecord> calls = new EnumMap<>(TYPE.class);

    @Override
    public void onRecordRead(DBRecord record) {
      calls.put(TYPE.READ, record);
    }

    @Override
    public void onBeforeRecordCreate(DBRecord record) {
      calls.put(TYPE.BEFORE_CREATE, record);
    }

    @Override
    public void onAfterRecordCreate(DBRecord record) {
      calls.put(TYPE.AFTER_CREATE, record);
    }

    @Override
    public void onBeforeRecordUpdate(DBRecord record) {
      calls.put(TYPE.BEFORE_UPDATE, record);
    }

    @Override
    public void onAfterRecordUpdate(DBRecord record) {
      calls.put(TYPE.AFTER_UPDATE, record);
    }

    @Override
    public void onBeforeRecordDelete(DBRecord record) {
      calls.put(TYPE.BEFORE_DELETE, record);
    }

    @Override
    public void onAfterRecordDelete(DBRecord record) {
      calls.put(TYPE.AFTER_DELETE, record);
    }
  }

  @Test
  public void onTriggerDispatchesEachTypeToTheMatchingCallbackWithRecordPassedThrough() {
    // Every TYPE value drives a distinct callback. Pin per-arm to catch a swapped or
    // renamed branch — the failure mode of "BEFORE_UPDATE arm calls onBeforeRecordCreate"
    // wouldn't be caught by a single representative test.
    var hook = new RecordingRecordHook();
    var perTypeRecord = new EnumMap<TYPE, DBRecord>(TYPE.class);
    for (var type : TYPE.values()) {
      var record = Mockito.mock(DBRecord.class);
      perTypeRecord.put(type, record);
      hook.onTrigger(type, record);
    }
    assertEquals("each of the 7 TYPE values must drive exactly one callback",
        TYPE.values().length, hook.calls.size());
    for (var type : TYPE.values()) {
      assertSame(
          "TYPE." + type
              + " must dispatch to the matching on*Record* callback with the record passed through",
          perTypeRecord.get(type), hook.calls.get(type));
    }
  }

  @Test
  public void emptyDefaultCallbacksDoNotThrowWhenInvokedDirectly() {
    // No-override subclass must be able to invoke each on*Record* directly without
    // exception — the base's bodies are empty. Pin the "empty default" contract.
    var bareHook = new RecordHookAbstract() {
      // intentionally empty
    };
    bareHook.onRecordRead(null);
    bareHook.onBeforeRecordCreate(null);
    bareHook.onAfterRecordCreate(null);
    bareHook.onBeforeRecordUpdate(null);
    bareHook.onAfterRecordUpdate(null);
    bareHook.onBeforeRecordDelete(null);
    bareHook.onAfterRecordDelete(null);
    assertFalse("if we got here without exception, the empty defaults are intact", false);
  }

  @Test
  public void implementsRecordHookSoSubclassesPlugIntoTheRegistrationSurface() {
    // The reason this base exists at all — it implements RecordHook so subclasses can
    // be registered through the normal hook surface. Pin to catch a refactor that drops
    // the implements clause.
    assertTrue("RecordHookAbstract must implement RecordHook",
        RecordHook.class.isAssignableFrom(RecordHookAbstract.class));
  }
}
