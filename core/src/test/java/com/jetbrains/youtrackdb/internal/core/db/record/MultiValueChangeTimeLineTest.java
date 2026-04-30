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
package com.jetbrains.youtrackdb.internal.core.db.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import org.junit.Test;

/**
 * Standalone POJO unit tests for {@link MultiValueChangeTimeLine}: pins the empty-on-construction
 * invariant, the append-order semantics of {@link
 * MultiValueChangeTimeLine#addCollectionChangeEvent}, and the unmodifiable-view contract on
 * {@link MultiValueChangeTimeLine#getMultiValueChangeEvents()}. The timeline carries the
 * append-only history of every tracked-collection mutation between {@code session.begin()} and
 * {@code session.commit()} — write-time append order is what the WAL serializer relies on.
 */
public class MultiValueChangeTimeLineTest {

  @Test
  public void emptyAfterConstruction() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    assertTrue(timeline.getMultiValueChangeEvents().isEmpty());
  }

  /** A single append produces a one-element view containing the same event reference. */
  @Test
  public void singleAppendIsVisibleViaView() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    var event = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v");

    timeline.addCollectionChangeEvent(event);

    var view = timeline.getMultiValueChangeEvents();
    assertEquals(1, view.size());
    assertSame(event, view.get(0));
  }

  /** Multiple appends preserve insertion order — pin both index 0 and index 2. */
  @Test
  public void multipleAppendsPreserveInsertionOrder() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    var first = new MultiValueChangeEvent<>(ChangeType.ADD, 0, "a");
    var second = new MultiValueChangeEvent<>(ChangeType.UPDATE, 0, "b", "a");
    var third = new MultiValueChangeEvent<>(ChangeType.REMOVE, 0, "b");

    timeline.addCollectionChangeEvent(first);
    timeline.addCollectionChangeEvent(second);
    timeline.addCollectionChangeEvent(third);

    var view = timeline.getMultiValueChangeEvents();
    assertEquals(3, view.size());
    assertSame(first, view.get(0));
    assertSame(second, view.get(1));
    assertSame(third, view.get(2));
  }

  /** {@code null} events are accepted (the implementation does not guard) — pin observed shape. */
  @Test
  public void nullAppendIsAccepted() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    timeline.addCollectionChangeEvent(null);
    assertEquals(1, timeline.getMultiValueChangeEvents().size());
    assertSame(null, timeline.getMultiValueChangeEvents().get(0));
  }

  /**
   * The view is unmodifiable: callers cannot mutate the underlying list. Falsifies a regression
   * that exposed the live {@code ArrayList} directly (which would let a caller bypass the
   * append-only discipline).
   */
  @Test
  public void viewRejectsAdd() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    timeline.addCollectionChangeEvent(new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v"));
    var view = timeline.getMultiValueChangeEvents();

    assertThrows(
        UnsupportedOperationException.class,
        () -> view.add(new MultiValueChangeEvent<>(ChangeType.ADD, 1, "w")));
  }

  @Test
  public void viewRejectsClear() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    timeline.addCollectionChangeEvent(new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v"));
    var view = timeline.getMultiValueChangeEvents();

    assertThrows(UnsupportedOperationException.class, view::clear);
  }

  @Test
  public void viewRejectsRemoveByIndex() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    timeline.addCollectionChangeEvent(new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v"));
    var view = timeline.getMultiValueChangeEvents();

    assertThrows(UnsupportedOperationException.class, () -> view.remove(0));
  }

  /**
   * The view is a live wrapper, not a snapshot — appends after view retrieval are visible
   * through the same view reference. Pin the live-view contract because a caller might cache the
   * reference and rely on observing in-flight changes.
   */
  @Test
  public void viewIsLiveNotSnapshot() {
    var timeline = new MultiValueChangeTimeLine<Integer, String>();
    var view = timeline.getMultiValueChangeEvents();
    assertTrue(view.isEmpty());

    timeline.addCollectionChangeEvent(new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v"));

    assertEquals(1, view.size());
  }

  /**
   * Two timelines are independent: an append on one does not leak into the other (no shared
   * static state).
   */
  @Test
  public void instancesAreIndependent() {
    var a = new MultiValueChangeTimeLine<Integer, String>();
    var b = new MultiValueChangeTimeLine<Integer, String>();

    a.addCollectionChangeEvent(new MultiValueChangeEvent<>(ChangeType.ADD, 0, "v"));

    assertEquals(1, a.getMultiValueChangeEvents().size());
    assertTrue(b.getMultiValueChangeEvents().isEmpty());
  }
}
