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
package com.jetbrains.youtrackdb.internal.core.record.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import org.junit.Test;

/**
 * Standalone POJO change-tracking shape coverage for
 * {@link SimpleMultiValueTracker}. The class wraps a {@link RecordElement}
 * via a {@link java.lang.ref.WeakReference} and accumulates
 * {@link com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent}s
 * into two parallel timelines (lifetime + transaction-scope) when enabled.
 *
 * <p>The tests pin: enable/disable gating, the per-method dispatch arms (add,
 * update, remove, addNoDirty, removeNoDirty), the dirty-callback contract
 * (setDirty vs setDirtyNoChanged), {@code transactionClear} resetting only
 * the tx timeline, {@code sourceFrom} cloning all three slots, the
 * isChanged/isTxChanged predicates, and the GC-survival no-op when the
 * weak-referenced element is collected.
 */
public class SimpleMultiValueTrackerTest {

  /**
   * After construction the tracker is disabled and both timelines are null.
   * {@code isChanged} and {@code isTxChanged} report false because no events
   * have been recorded.
   */
  @Test
  public void testDefaultsAfterConstruction() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);

    assertFalse("tracker must default to disabled", tracker.isEnabled());
    assertNull("timeline must default to null", tracker.getTimeLine());
    assertNull("transactionTimeLine must default to null",
        tracker.getTransactionTimeLine());
    assertFalse("isChanged must be false on a fresh tracker", tracker.isChanged());
    assertFalse("isTxChanged must be false on a fresh tracker", tracker.isTxChanged());
  }

  /**
   * {@link SimpleMultiValueTracker#enable()} flips the gate to true; the
   * second call is idempotent. The mirror via {@link SimpleMultiValueTracker#disable()}
   * also clears the lifetime timeline (per the source comment: "if disabled,
   * we lose the history").
   */
  @Test
  public void testEnableDisableGate() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);

    tracker.enable();
    assertTrue(tracker.isEnabled());
    tracker.enable(); // idempotent
    assertTrue(tracker.isEnabled());

    tracker.disable();
    assertFalse(tracker.isEnabled());
    tracker.disable(); // idempotent
    assertFalse(tracker.isEnabled());
  }

  /**
   * When the tracker is disabled, the {@code add} dispatch must be a no-op:
   * the timeline stays null, no dirty callback fires on the element.
   */
  @Test
  public void testAddIsNoOpWhenDisabled() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);

    tracker.add("k", "v");

    assertNull(tracker.getTimeLine());
    assertNull(tracker.getTransactionTimeLine());
    verify(element, never()).setDirty();
    verify(element, never()).setDirtyNoChanged();
  }

  /**
   * When enabled, {@code add} populates both timelines and fires
   * {@code setDirty} (the "changeOwner=true" arm). Two adds must accumulate
   * (each call appends an event to both timelines).
   */
  @Test
  public void testAddPopulatesTimelinesAndCallsSetDirty() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();

    tracker.add("k1", "v1");
    tracker.add("k2", "v2");

    assertNotNull(tracker.getTimeLine());
    assertNotNull(tracker.getTransactionTimeLine());
    assertEquals(2, tracker.getTimeLine().getMultiValueChangeEvents().size());
    assertEquals(2, tracker.getTransactionTimeLine().getMultiValueChangeEvents().size());
    assertEquals(ChangeType.ADD,
        tracker.getTimeLine().getMultiValueChangeEvents().get(0).getChangeType());
    verify(element, times(2)).setDirty();
    verify(element, never()).setDirtyNoChanged();
    assertTrue(tracker.isChanged());
    assertTrue(tracker.isTxChanged());
  }

  /**
   * {@link SimpleMultiValueTracker#updated} produces an UPDATE event with
   * both old and new values, fires {@code setDirty}.
   */
  @Test
  public void testUpdatedRecordsBothValuesAndFiresSetDirty() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();

    tracker.updated("k", "new", "old");

    var event = tracker.getTimeLine().getMultiValueChangeEvents().get(0);
    assertEquals(ChangeType.UPDATE, event.getChangeType());
    assertEquals("k", event.getKey());
    assertEquals("new", event.getValue());
    assertEquals("old", event.getOldValue());
    verify(element).setDirty();
  }

  /**
   * {@link SimpleMultiValueTracker#remove} produces a REMOVE event carrying
   * only the old value (the new value slot is null), fires {@code setDirty}.
   */
  @Test
  public void testRemoveRecordsOldValueAndFiresSetDirty() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();

    tracker.remove("k", "old");

    var event = tracker.getTimeLine().getMultiValueChangeEvents().get(0);
    assertEquals(ChangeType.REMOVE, event.getChangeType());
    assertEquals("k", event.getKey());
    assertNull("new value slot must be null on REMOVE", event.getValue());
    assertEquals("old", event.getOldValue());
    verify(element).setDirty();
  }

  /**
   * {@link SimpleMultiValueTracker#addNoDirty} records an ADD event but
   * fires {@code setDirtyNoChanged} (the "changeOwner=false" arm — used
   * for collection-mutation methods that should not flip the parent's
   * canonical changed flag).
   */
  @Test
  public void testAddNoDirtyFiresSetDirtyNoChanged() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();

    tracker.addNoDirty("k", "v");

    var event = tracker.getTimeLine().getMultiValueChangeEvents().get(0);
    assertEquals(ChangeType.ADD, event.getChangeType());
    assertEquals("k", event.getKey());
    assertEquals("v", event.getValue());
    verify(element, never()).setDirty();
    verify(element).setDirtyNoChanged();
  }

  /**
   * {@link SimpleMultiValueTracker#removeNoDirty} records a REMOVE event
   * (with old value populated) and fires {@code setDirtyNoChanged} —
   * mirror of {@code addNoDirty}.
   */
  @Test
  public void testRemoveNoDirtyFiresSetDirtyNoChanged() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();

    tracker.removeNoDirty("k", "old");

    var event = tracker.getTimeLine().getMultiValueChangeEvents().get(0);
    assertEquals(ChangeType.REMOVE, event.getChangeType());
    assertEquals("old", event.getOldValue());
    verify(element, never()).setDirty();
    verify(element).setDirtyNoChanged();
  }

  /**
   * {@link SimpleMultiValueTracker#transactionClear()} drops only the
   * transaction timeline. The lifetime timeline and {@code isChanged}
   * predicate must remain.
   */
  @Test
  public void testTransactionClearOnlyResetsTxTimeline() {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();
    tracker.add("k", "v");

    tracker.transactionClear();

    assertNotNull("lifetime timeline must remain after transactionClear",
        tracker.getTimeLine());
    assertNull("tx timeline must be cleared",
        tracker.getTransactionTimeLine());
    assertTrue("isChanged still reflects the lifetime timeline",
        tracker.isChanged());
    assertFalse("isTxChanged must be false after transactionClear",
        tracker.isTxChanged());
  }

  /**
   * {@link SimpleMultiValueTracker#sourceFrom(SimpleMultiValueTracker)}
   * copies all three internal slots (timeline, transactionTimeLine, enabled)
   * from the source. Both trackers wrap different elements but the post-
   * sourceFrom state is observable via the public getters.
   */
  @Test
  public void testSourceFromCopiesAllThreeSlots() {
    var elementA = mock(RecordElement.class);
    var elementB = mock(RecordElement.class);
    var source = new SimpleMultiValueTracker<String, String>(elementA);
    source.enable();
    source.add("k", "v");

    var dest = new SimpleMultiValueTracker<String, String>(elementB);
    assertFalse(dest.isEnabled());
    assertNull(dest.getTimeLine());

    dest.sourceFrom(source);

    assertTrue("enabled flag must be copied", dest.isEnabled());
    assertEquals("timeline must be the same instance reference",
        source.getTimeLine(), dest.getTimeLine());
    assertEquals("tx timeline must be the same instance reference",
        source.getTransactionTimeLine(), dest.getTransactionTimeLine());
  }

  /**
   * {@link SimpleMultiValueTracker#isChanged()} returns false when the
   * timeline is non-null but empty (no events). Pinned because the field
   * being non-null is not enough — the predicate also checks the event list.
   */
  @Test
  public void testIsChangedReturnsFalseForEmptyTimeline() throws Exception {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();
    // Force the timeline to non-null without adding events: trigger a normal
    // add then clear the events list via reflection.
    tracker.add("k", "v");
    var timeline = tracker.getTimeLine();
    // The public getter returns an unmodifiableList wrapper; reach the
    // underlying list and clear it to produce a non-null but empty timeline.
    var eventsField = timeline.getClass().getDeclaredField("multiValueChangeEvents");
    eventsField.setAccessible(true);
    ((java.util.List<?>) eventsField.get(timeline)).clear();

    assertFalse("isChanged must be false when timeline events are empty",
        tracker.isChanged());
  }

  /**
   * If the weak-referenced {@link RecordElement} has been GC'd before the
   * change event fires, {@code onAfterRecordChanged} must short-circuit
   * (no NPE, no timeline mutation). Verified by clearing the WeakReference
   * via reflection.
   */
  @Test
  @SuppressWarnings("unchecked")
  public void testGcCollectedElementShortCircuits() throws Exception {
    var element = mock(RecordElement.class);
    var tracker = new SimpleMultiValueTracker<String, String>(element);
    tracker.enable();

    var elementField = SimpleMultiValueTracker.class.getDeclaredField("element");
    elementField.setAccessible(true);
    var weakRef = (java.lang.ref.WeakReference<RecordElement>) elementField.get(tracker);
    weakRef.clear(); // simulate GC of the referenced element

    tracker.add("k", "v");

    assertNull("timeline must remain null when element is GC'd",
        tracker.getTimeLine());
    verify(element, never()).setDirty();
    verify(element, never()).setDirtyNoChanged();
  }
}
