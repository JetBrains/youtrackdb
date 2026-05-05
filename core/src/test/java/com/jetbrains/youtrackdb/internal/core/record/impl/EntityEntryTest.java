/*
 *
 *  *  Copyright YouTrackDB
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
package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import org.junit.Test;

/**
 * Standalone POJO-shape coverage for {@link EntityEntry}. No {@code DbTestBase} — the class
 * is a value-tracking record holder with reflective access patterns and no schema/storage
 * collaborators of its own (the {@link com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded
 * DatabaseSessionEmbedded} parameter on {@link EntityEntry#getOnLoadValue
 * getOnLoadValue} is exercised via Mockito).
 *
 * <p>The test target is the small set of state-mutation flags that drive
 * {@link EntityImpl}'s dirty-tracking and transaction semantics:
 * {@code changed}, {@code exists}, {@code created}, {@code txChanged},
 * {@code txExists}, {@code txCreated}, {@code hasOnLoadValue}, plus the
 * three {@code value}-{@code original}-{@code onLoadValue} slots and the
 * {@link TrackedMultiValue} dispatch arms.
 */
public class EntityEntryTest {

  /**
   * Default ctor: every flag is at its documented default — {@code exists=true},
   * {@code txExists=true}, all other flags false, all value/original slots null.
   * This is the contract a freshly-created {@link EntityEntry} must satisfy
   * before any setter touches it.
   */
  @Test
  public void testDefaultsAfterNoArgConstruction() {
    var entry = new EntityEntry();

    assertFalse("changed must default to false", entry.isChanged());
    assertTrue("exists must default to true", entry.exists());
    assertFalse("created must default to false", entry.isCreated());
    assertFalse("txChanged must default to false", entry.isTxChanged());
    assertTrue("txExists must default to true", entry.isTxExists());
    assertFalse("txCreated must default to false", entry.isTxCreated());

    assertNull("value slot must default to null", entry.value);
    assertNull("original slot must default to null", entry.original);
    assertNull("type slot must default to null", entry.type);
    assertNull("property slot must default to null", entry.property);
  }

  /**
   * {@link EntityEntry#setExists(boolean)} mirrors the value into both
   * {@code exists} and {@code txExists}. Toggling false once must drop both
   * flags; restoring true must lift both. This dual-write semantics is what
   * makes "exists" the canonical liveness flag for the property.
   */
  @Test
  public void testSetExistsMirrorsToTxExists() {
    var entry = new EntityEntry();

    entry.setExists(false);
    assertFalse("exists must become false", entry.exists());
    assertFalse("txExists must mirror exists=false", entry.isTxExists());

    entry.setExists(true);
    assertTrue("exists must become true again", entry.exists());
    assertTrue("txExists must mirror exists=true", entry.isTxExists());
  }

  /**
   * {@link EntityEntry#markChanged()} and {@link EntityEntry#unmarkChanged()}
   * govern the {@code changed} flag (with {@code markChanged} also flipping
   * {@code txChanged} on). Confirms the asymmetry: {@code unmarkChanged} only
   * clears the local {@code changed}, leaving {@code txChanged} sticky for the
   * outer transaction to resolve.
   */
  @Test
  public void testMarkChangedAndUnmarkChangedAsymmetry() {
    var entry = new EntityEntry();

    entry.markChanged();
    assertTrue("markChanged sets changed", entry.isChanged());
    assertTrue("markChanged sets txChanged", entry.isTxChanged());

    entry.unmarkChanged();
    assertFalse("unmarkChanged clears changed", entry.isChanged());
    assertTrue("unmarkChanged leaves txChanged sticky", entry.isTxChanged());
  }

  /**
   * {@link EntityEntry#markCreated()} sets both {@code created} and
   * {@code txCreated}; {@link EntityEntry#unmarkCreated()} only clears
   * {@code created}, leaving {@code txCreated} for the outer transaction.
   */
  @Test
  public void testMarkCreatedAndUnmarkCreatedAsymmetry() {
    var entry = new EntityEntry();

    entry.markCreated();
    assertTrue(entry.isCreated());
    assertTrue(entry.isTxCreated());

    entry.unmarkCreated();
    assertFalse("unmarkCreated clears created", entry.isCreated());
    assertTrue("unmarkCreated leaves txCreated sticky", entry.isTxCreated());
  }

  /**
   * {@link EntityEntry#setChanged(boolean)} captures the on-load value the
   * first time it transitions to {@code true}. Subsequent transitions must
   * not overwrite the captured value (the original captured snapshot is
   * what gets undone on rollback).
   */
  @Test
  public void testSetChangedCapturesOnLoadValueOnce() throws Exception {
    var entry = new EntityEntry();
    entry.original = "first-original";

    entry.setChanged(true);
    assertEquals("on-load value must be the first captured original",
        "first-original", reflectOnLoadValue(entry));
    assertTrue(reflectHasOnLoadValue(entry));

    // Second transition with a different original must not overwrite.
    entry.original = "second-original";
    entry.setChanged(false);
    entry.setChanged(true);
    assertEquals("on-load value must remain the first capture",
        "first-original", reflectOnLoadValue(entry));
  }

  /**
   * {@link EntityEntry#clear()} resets {@code created}, {@code changed},
   * and {@code original} to their defaults. {@code exists}/{@code txExists}
   * are deliberately untouched (they are the property's liveness flag, not
   * tracking state).
   */
  @Test
  public void testClearResetsTrackingButLeavesExists() {
    var entry = new EntityEntry();
    entry.markChanged();
    entry.markCreated();
    entry.original = "snapshot";

    entry.clear();

    assertFalse(entry.isChanged());
    assertFalse(entry.isCreated());
    assertNull(entry.original);
    assertTrue("clear() must NOT touch exists", entry.exists());
  }

  /**
   * {@link EntityEntry#clearNotExists()} clears only the {@code original}
   * slot and the timeline (verified via mocked {@link TrackedMultiValue}).
   * It must not touch {@code changed}/{@code created} flags.
   */
  @Test
  public void testClearNotExistsOnlyClearsOriginalAndTimeline() {
    var entry = new EntityEntry();
    entry.markChanged();
    entry.markCreated();
    entry.original = "snapshot";

    var tracked = mock(TrackedMultiValue.class);
    entry.value = tracked;

    entry.clearNotExists();

    assertNull(entry.original);
    assertTrue("changed must remain set", entry.isChanged());
    assertTrue("created must remain set", entry.isCreated());
    verify(tracked).disableTracking(null);
  }

  /**
   * {@link EntityEntry#removeTimeline()} only dispatches to a
   * {@link TrackedMultiValue} value. For non-tracked values it must be a
   * no-op (no NPE, no cast).
   */
  @Test
  public void testRemoveTimelineDispatchesOnlyForTrackedMultiValue() {
    var entry = new EntityEntry();
    var tracked = mock(TrackedMultiValue.class);
    entry.value = tracked;

    entry.removeTimeline();
    verify(tracked).disableTracking(null);

    // Non-tracked value: no-op
    entry.value = "plain-string";
    entry.removeTimeline(); // must not throw
  }

  /**
   * {@link EntityEntry#enableTracking(EntityImpl)} returns true and forwards
   * to the underlying {@link TrackedMultiValue} when {@code value} is one;
   * returns false for non-tracked values. The caller uses the boolean to
   * decide whether listener registration succeeded.
   */
  @Test
  public void testEnableTrackingReturnsBooleanForDispatch() {
    var entry = new EntityEntry();
    var owner = mock(EntityImpl.class);

    entry.value = "plain-string";
    assertFalse("non-tracked value must report false", entry.enableTracking(owner));

    var tracked = mock(TrackedMultiValue.class);
    entry.value = tracked;
    assertTrue("tracked value must report true", entry.enableTracking(owner));
    verify(tracked).enableTracking(owner);
  }

  /**
   * {@link EntityEntry#replaceListener(EntityImpl)} must delegate to
   * {@code enableTracking}. Verified by injecting a tracked value and
   * confirming the underlying call is made.
   */
  @Test
  public void testReplaceListenerForwardsToEnableTracking() {
    var entry = new EntityEntry();
    var tracked = mock(TrackedMultiValue.class);
    entry.value = tracked;
    var owner = mock(EntityImpl.class);

    entry.replaceListener(owner);
    verify(tracked).enableTracking(owner);
  }

  /**
   * {@link EntityEntry#disableTracking(EntityImpl, Object)} accepts an
   * arbitrary {@code fieldValue} parameter (not the entry's own value);
   * it must dispatch only when that parameter is a {@link TrackedMultiValue}.
   */
  @Test
  public void testDisableTrackingDispatchesOnFieldValueParam() {
    var entry = new EntityEntry();
    var owner = mock(EntityImpl.class);
    var tracked = mock(TrackedMultiValue.class);

    entry.disableTracking(owner, tracked);
    verify(tracked).disableTracking(owner);

    // Non-tracked fieldValue: no-op (no NPE, no extra interaction with the entry).
    entry.disableTracking(owner, "plain-string"); // must not throw
  }

  /**
   * {@link EntityEntry#getTimeLine()} returns null when {@code changed} is
   * true (the timeline is shadowed by the explicit changed marker), and
   * delegates to {@link TrackedMultiValue#getTimeLine()} when {@code changed}
   * is false. Non-tracked values always return null.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testGetTimeLineDispatchArms() {
    var entry = new EntityEntry();

    // Non-tracked value: null regardless of changed flag.
    entry.value = "plain-string";
    assertNull(entry.getTimeLine());

    // Tracked value, not changed: delegate.
    var tracked = mock(TrackedMultiValue.class);
    var timeline = mock(MultiValueChangeTimeLine.class);
    when(tracked.getTimeLine()).thenReturn(timeline);
    entry.value = tracked;
    assertSame(timeline, entry.getTimeLine());

    // Tracked value, changed=true: shadowed → null.
    entry.markChanged();
    assertNull("changed=true shadows the underlying timeline", entry.getTimeLine());
  }

  /**
   * {@link EntityEntry#isTrackedModified()} dispatches into three arms:
   * {@link TrackedMultiValue#isModified()} for tracked values, the embedded
   * entity's {@code isDirty} when the value is an embedded {@link EntityImpl},
   * and false for everything else (plain strings, primitives, null).
   */
  @Test
  public void testIsTrackedModifiedDispatchArms() {
    var entry = new EntityEntry();

    // Plain value: false.
    entry.value = "plain";
    assertFalse(entry.isTrackedModified());

    // Tracked value: delegated.
    var tracked = mock(TrackedMultiValue.class);
    when(tracked.isModified()).thenReturn(true);
    entry.value = tracked;
    assertTrue(entry.isTrackedModified());

    // Embedded entity: delegated to isDirty.
    var embedded = mock(EntityImpl.class);
    when(embedded.isEmbedded()).thenReturn(true);
    when(embedded.isDirty()).thenReturn(true);
    entry.value = embedded;
    assertTrue(entry.isTrackedModified());

    // Non-embedded entity: returns false (the isEmbedded guard fails).
    var notEmbedded = mock(EntityImpl.class);
    when(notEmbedded.isEmbedded()).thenReturn(false);
    entry.value = notEmbedded;
    assertFalse(entry.isTrackedModified());

    // Null value: false.
    entry.value = null;
    assertFalse(entry.isTrackedModified());
  }

  /**
   * {@link EntityEntry#isTxTrackedModified()} is the transaction-scope version
   * of {@code isTrackedModified}. Same dispatch arms but delegates to
   * {@link TrackedMultiValue#isTransactionModified()} for the tracked arm.
   */
  @Test
  public void testIsTxTrackedModifiedDispatchArms() {
    var entry = new EntityEntry();

    var tracked = mock(TrackedMultiValue.class);
    when(tracked.isTransactionModified()).thenReturn(true);
    entry.value = tracked;
    assertTrue(entry.isTxTrackedModified());

    var embedded = mock(EntityImpl.class);
    when(embedded.isEmbedded()).thenReturn(true);
    when(embedded.isDirty()).thenReturn(true);
    entry.value = embedded;
    assertTrue(entry.isTxTrackedModified());

    entry.value = "plain";
    assertFalse(entry.isTxTrackedModified());
  }

  /**
   * {@link EntityEntry#undo()} restores {@code value} from {@code original}
   * when {@code changed} is true, then clears {@code changed} and
   * {@code original}, and lifts {@code exists} back to true. When
   * {@code changed} is false it is a no-op.
   */
  @Test
  public void testUndoRestoresOriginalWhenChanged() {
    var entry = new EntityEntry();

    // Path 1: changed=false → no-op.
    entry.value = "current";
    entry.original = "snapshot";
    entry.undo();
    assertEquals("undo on unchanged entry must be a no-op",
        "current", entry.value);
    assertEquals("original must remain when undo is a no-op",
        "snapshot", entry.original);

    // Path 2: changed=true → restore.
    entry.original = "snapshot";
    entry.markChanged();
    entry.value = "modified";
    entry.setExists(false);

    entry.undo();
    assertEquals("value must be restored from original", "snapshot", entry.value);
    assertFalse("changed must be cleared after undo", entry.isChanged());
    assertNull("original must be cleared after undo", entry.original);
    assertTrue("exists must be restored to true after undo", entry.exists());
  }

  /**
   * {@link EntityEntry#transactionClear()} resets all transaction-scope
   * flags ({@code txChanged}, {@code txCreated}) and clears the on-load
   * snapshot ({@code hasOnLoadValue=false}, {@code onLoadValue=null}).
   * For tracked values it also forwards to
   * {@link TrackedMultiValue#transactionClear()}.
   */
  @Test
  public void testTransactionClearResetsTxStateAndOnLoadValue() throws Exception {
    var entry = new EntityEntry();
    entry.markChanged();
    entry.markCreated();
    entry.original = "snapshot";
    entry.setChanged(true); // captures hasOnLoadValue=true

    var tracked = mock(TrackedMultiValue.class);
    entry.value = tracked;

    entry.transactionClear();

    assertFalse("txChanged must be cleared", entry.isTxChanged());
    assertFalse("txCreated must be cleared", entry.isTxCreated());
    assertFalse("hasOnLoadValue must be cleared", reflectHasOnLoadValue(entry));
    assertNull("onLoadValue must be cleared", reflectOnLoadValue(entry));
    verify(tracked).transactionClear();
  }

  /**
   * {@link EntityEntry#getOnLoadValue} arm 1: when no on-load capture has
   * happened and the value is a plain object, it returns the live value.
   */
  @Test
  public void testGetOnLoadValueReturnsValueWhenNotCapturedAndNotTracked() {
    var entry = new EntityEntry();
    entry.value = "live-value";
    var session = mock(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.class);

    assertEquals("live-value", entry.getOnLoadValue(session));
  }

  /**
   * {@link EntityEntry#getOnLoadValue} arm 2: when on-load capture HAS
   * happened and the captured value is plain (not tracked), the captured
   * value is returned verbatim.
   */
  @Test
  public void testGetOnLoadValueReturnsCapturedValueWhenPlain() {
    var entry = new EntityEntry();
    entry.original = "captured-original";
    entry.setChanged(true); // captures original into onLoadValue
    entry.value = "now-modified";

    var session = mock(com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded.class);
    assertEquals("captured-original", entry.getOnLoadValue(session));
    // No timeline interaction expected for plain captured value.
    verify(session, never()).getActiveTransaction();
  }

  /**
   * {@link EntityEntry#clone()} copies all flags but uses {@code changed} for
   * both {@code txChanged} and {@code txCreated}/{@code txExists}. The clone's
   * tx-flags are derived (not original) — confirms the documented copy
   * semantics. {@code clone()} is package-private so the test is colocated in
   * the same package.
   */
  @Test
  public void testCloneCopiesAllFlagsAndDerivesTxFlags() throws Exception {
    var entry = new EntityEntry();
    entry.value = "v";
    entry.original = "o";
    entry.markChanged();
    entry.markCreated();
    entry.setExists(false);

    var cloneMethod = EntityEntry.class.getDeclaredMethod("clone");
    cloneMethod.setAccessible(true);
    var clone = (EntityEntry) cloneMethod.invoke(entry);

    assertNotNull(clone);
    assertEquals("v", clone.value);
    assertSame("clone must reuse the original-slot reference",
        entry.value, clone.value);
    assertEquals(entry.isChanged(), clone.isChanged());
    assertEquals(entry.isCreated(), clone.isCreated());
    assertEquals(entry.exists(), clone.exists());
    // Per clone() body, txChanged is set from changed (not from txChanged),
    // so the derived value must be the source-side {@code changed}.
    assertEquals("clone.txChanged must derive from src.changed",
        entry.isChanged(), clone.isTxChanged());
    assertEquals("clone.txCreated must derive from src.created",
        entry.isCreated(), clone.isTxCreated());
    assertEquals("clone.txExists must derive from src.exists",
        entry.exists(), clone.isTxExists());
  }

  // ---------- reflection helpers (private fields under test) -----------------

  private static Object reflectOnLoadValue(EntityEntry entry) throws Exception {
    var f = EntityEntry.class.getDeclaredField("onLoadValue");
    f.setAccessible(true);
    return f.get(entry);
  }

  private static boolean reflectHasOnLoadValue(EntityEntry entry) throws Exception {
    var f = EntityEntry.class.getDeclaredField("hasOnLoadValue");
    f.setAccessible(true);
    return (boolean) f.get(entry);
  }
}
