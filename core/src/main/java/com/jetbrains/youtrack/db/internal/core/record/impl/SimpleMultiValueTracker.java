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

package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent.ChangeType;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import java.lang.ref.WeakReference;

/**
 * Perform gathering of all operations performed on tracked collection and create mapping between
 * list of collection operations and field name that contains collection that was changed.
 *
 * @param <K> Value that uniquely identifies position of item in collection
 * @param <V> Item value.
 */
public final class SimpleMultiValueTracker<K, V> {

  private final WeakReference<RecordElement> element;
  private MultiValueChangeTimeLine<Object, Object> timeLine;
  private boolean enabled;
  private MultiValueChangeTimeLine<K, V> transactionTimeLine;

  public SimpleMultiValueTracker(RecordElement element) {
    this.element = new WeakReference<>(element);
  }

  public void addNoDirty(K key, V value) {
    onAfterRecordChanged(
        new MultiValueChangeEvent<>(ChangeType.ADD, key, value, null),
        false);
  }

  public void removeNoDirty(K key, V value) {
    onAfterRecordChanged(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, key, null, value),
        false);
  }

  public void add(K key, V value) {
    onAfterRecordChanged(
        new MultiValueChangeEvent<>(ChangeType.ADD, key, value), true);
  }

  public void updated(K key, V newValue, V oldValue) {
    onAfterRecordChanged(
        new MultiValueChangeEvent<>(
            ChangeType.UPDATE, key, newValue, oldValue),
        true);
  }

  public void remove(K key, V value) {
    onAfterRecordChanged(
        new MultiValueChangeEvent<>(
            ChangeType.REMOVE, key, null, value),
        true);
  }

  public void onAfterRecordChanged(final MultiValueChangeEvent<K, V> event, boolean changeOwner) {
    if (!enabled) {
      return;
    }

    final var entity = this.element.get();
    if (entity == null) {
      // entity not alive anymore, do nothing.
      return;
    }

    if (timeLine == null) {
      timeLine = new MultiValueChangeTimeLine<>();
    }
    timeLine.addCollectionChangeEvent((MultiValueChangeEvent<Object, Object>) event);

    if (transactionTimeLine == null) {
      transactionTimeLine = new MultiValueChangeTimeLine<>();
    }
    transactionTimeLine.addCollectionChangeEvent(event);

    if (changeOwner) {
      entity.setDirty();
    } else {
      entity.setDirtyNoChanged();
    }
  }

  public void enable() {
    if (!this.enabled) {
      this.enabled = true;
    }
  }

  public void disable() {
    if (this.enabled) {
      this.timeLine = null;
      this.enabled = false;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void sourceFrom(SimpleMultiValueTracker<K, V> tracker) {
    this.timeLine = tracker.timeLine;
    this.transactionTimeLine = tracker.transactionTimeLine;
    this.enabled = tracker.enabled;
  }

  public MultiValueChangeTimeLine<Object, Object> getTimeLine() {
    return timeLine;
  }

  public MultiValueChangeTimeLine<K, V> getTransactionTimeLine() {
    return transactionTimeLine;
  }

  public boolean isChanged() {
    return timeLine != null && !timeLine.getMultiValueChangeEvents().isEmpty();
  }

  public boolean isTxChanged() {
    return transactionTimeLine != null && !transactionTimeLine.getMultiValueChangeEvents()
        .isEmpty();
  }

  public void transactionClear() {
    transactionTimeLine = null;
  }
}
