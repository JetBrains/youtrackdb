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
package com.jetbrains.youtrack.db.internal.core.db.record;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.exception.SchemaException;
import com.jetbrains.youtrack.db.api.query.Result;
import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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
   * @param changeEvents List of operations that were performed on collection.
   * @return Original collection state.
   */
  Object returnOriginalState(DatabaseSessionInternal session,
      List<MultiValueChangeEvent<K, V>> changeEvents);

  Class<?> getGenericClass();

  void enableTracking(RecordElement parent);

  void disableTracking(RecordElement entity);

  boolean isModified();

  boolean isTransactionModified();

  MultiValueChangeTimeLine<Object, Object> getTimeLine();

  boolean isEmbeddedContainer();

  default void checkValue(V value) {
    if (isEmbeddedContainer()) {
      if ((value instanceof RID
          || value instanceof Entity entity && !entity.isEmbedded())) {
        throw new SchemaException(
            "Cannot add a RID or a non-embedded entity to a embedded data container");
      }

      if (PropertyTypeInternal.isSingleValueType(value) || ((value instanceof Entity entity)
          && entity.isEmbedded())) {
        return;
      }

      if (value instanceof Collection<?>) {
        if (value instanceof TrackedList<?> || value instanceof EmbeddedSetImpl<?>) {
          return;
        }
      }

      if (value instanceof Map<?, ?>) {
        if (value instanceof TrackedMap<?>) {
          return;
        }
      }

      if ((value instanceof Collection<?>) || (value instanceof Map<?, ?>)) {
        throw new SchemaException(
            "Cannot add a non embedded collection to a embedded data container. Please use "
                + DatabaseSession.class.getName() +
                " factory methods instead : "
                + "newEmbeddedList(), newEmbeddedSet(), newEmbeddedMap().");
      }
      //case whew we use it inside query result set, fail later on validation and serialization
      if (value instanceof Result) {
        return;
      }

      throw new SchemaException("Value " + value + " is not supported by data container.");
    } else {
      if (value == null) {
        return;
      }

      if (value instanceof Entity entity && entity.isEmbedded()) {
        throw new SchemaException(
            "Cannot add an embedded entity to a link based data container");
      }
      if (!(value instanceof Identifiable)) {
        throw new SchemaException(
            "Cannot add a non-identifiable entity to a link based data container");
      }
    }
  }

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
          ((EntityImpl) x).clearTransactionTrackData();
        }
      }
    }
  }

  void transactionClear();

  boolean addInternal(final V e);

  MultiValueChangeTimeLine<K, V> getTransactionTimeLine();

  default void addOwner(V e) {
    if (isEmbeddedContainer()) {
      if (e instanceof EntityImpl entity) {
        var rid = entity.getIdentity();

        if (!rid.isValidPosition() || rid.isNew()) {
          ((EntityImpl) e).setOwner(this);
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
}
