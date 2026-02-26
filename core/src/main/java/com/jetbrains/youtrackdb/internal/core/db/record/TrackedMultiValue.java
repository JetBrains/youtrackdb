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
package com.jetbrains.youtrackdb.internal.core.db.record;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import java.util.Iterator;
import java.util.List;

/**
 * Interface that indicates that collection will send notifications about operations that are
 * performed on it to the listeners.
 *
 * @param <K> Value that indicates position of item inside collection.
 * @param <V> Value that is hold by collection.
 */
public interface TrackedMultiValue<K, V> extends RecordElement {

  /**
   * Reverts all operations that were performed on collection and return original collection state.
   *
   * @param transaction  currently active transaction.
   * @param changeEvents List of operations that were performed on collection.
   * @return Original collection state.
   */
  Object returnOriginalState(FrontendTransaction transaction,
      List<MultiValueChangeEvent<K, V>> changeEvents);

  /// Reverts all changes are done to the tracked collection since the last time DB callbacks
  /// processing changes of the entity were called. That means that not all operations can be
  /// reverted but only diff between callbacks. This method is mostly used to keep consistency
  /// between the state of the entity and related property indexes.
  ///
  /// @see DatabaseSessionEmbedded#beforeCreateOperations(RecordAbstract, String)
  /// @see DatabaseSessionEmbedded#beforeUpdateOperations(RecordAbstract, String)
  /// @see DatabaseSessionEmbedded#beforeDeleteOperations(RecordAbstract, String)
  /// @see DatabaseSessionEmbedded#afterCreateOperations(RecordAbstract)
  /// @see DatabaseSessionEmbedded#afterUpdateOperations(RecordAbstract)
  /// @see DatabaseSessionEmbedded#afterDeleteOperations(RecordAbstract)
  void rollbackChanges(FrontendTransaction transaction);

  void enableTracking(RecordElement parent);

  void disableTracking(RecordElement entity);

  boolean isModified();

  boolean isTransactionModified();

  MultiValueChangeTimeLine<? extends K, ? extends V> getTimeLine();

  boolean isEmbeddedContainer();

  static <X> void nestedEnabled(Iterator<X> iterator, RecordElement parent) {
    while (iterator.hasNext()) {
      var x = iterator.next();
      if (x instanceof TrackedMultiValue<?, ?> trackedMultiValue) {
        trackedMultiValue.enableTracking(parent);
      }
    }
  }

  static <X> void nestedDisable(Iterator<X> iterator, RecordElement parent) {
    while (iterator.hasNext()) {
      var x = iterator.next();
      if (x instanceof TrackedMultiValue<?, ?> trackedMultiValue) {
        trackedMultiValue.disableTracking(parent);
      }
    }
  }

  static <X> void nestedTransactionClear(Iterator<X> iterator) {
    while (iterator.hasNext()) {
      var x = iterator.next();
      if (x instanceof TrackedMultiValue<?, ?> trackedMultiValue) {
        trackedMultiValue.transactionClear();
      } else if (x instanceof EntityImpl EntityImpl) {
        if (EntityImpl.isEmbedded()) {
          EntityImpl.clearTransactionTrackData();
        }
      }
    }
  }

  void transactionClear();


  MultiValueChangeTimeLine<? extends K, ? extends V> getTransactionTimeLine();

  default void addOwner(V e) {
    if (isEmbeddedContainer()) {
      if (e instanceof EntityImpl entity) {
        var rid = entity.getIdentity();

        if (!rid.isValidPosition() || rid.isNew()) {
          entity.setOwner(this);
        }
      } else if (e instanceof RecordElement recordElement) {
        if (!(recordElement instanceof Blob)) {
          recordElement.setOwner(this);
        }

      }
    }
  }

  default void removeOwner(V oldValue) {
    if (oldValue instanceof RecordElement recordElement) {
      recordElement.setOwner(null);
    }
  }

  default boolean assertIfNotActive() {
    var owner = getOwnerEntity();
    assert owner == null
        || !owner.isUnloaded() : "Data container is unloaded please acquire new one from entity";
    DatabaseSessionEmbedded session = null;

    if (owner != null) {
      session = owner.getSession();
    }
    assert session == null
        || session.assertIfNotActive() : "Data container is unloaded please acquire new one from entity";

    return true;
  }
}
