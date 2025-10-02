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

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeTimeLine;
import com.jetbrains.youtrackdb.internal.core.db.record.TrackedMultiValue;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import javax.annotation.Nullable;

/**
 * Entity entry. Used by EntityImpl.
 */
public class EntityEntry {

  public Object value;
  public Object original;
  public PropertyTypeInternal type;
  public ImmutableSchemaProperty property;
  private boolean changed = false;
  private boolean exists = true;
  private boolean created = false;
  private boolean txChanged = false;
  private boolean txExists = true;
  private boolean txCreated = false;
  private Object onLoadValue;
  private boolean hasOnLoadValue = false;

  public EntityEntry() {
  }

  public boolean isChanged() {
    return changed;
  }

  public void setChanged(final boolean changed) {
    this.changed = changed;
    checkAndStoreOnLoadValue();
  }

  public boolean exists() {
    return exists;
  }

  public void setExists(final boolean exists) {
    this.exists = exists;
    this.txExists = exists;
  }

  public boolean isCreated() {
    return created;
  }

  @Override
  @SuppressWarnings({"CloneDoesntDeclareCloneNotSupportedException", "MethodDoesntCallSuperMethod"})
  protected EntityEntry clone() {
    final var entry = new EntityEntry();
    entry.type = type;
    entry.property = property;
    entry.value = value;
    entry.changed = changed;
    entry.created = created;
    entry.exists = exists;
    entry.txChanged = changed;
    entry.txCreated = created;
    entry.txExists = exists;
    return entry;
  }

  @Nullable
  public MultiValueChangeTimeLine<Object, Object> getTimeLine() {
    //noinspection rawtypes
    if (!changed && value instanceof TrackedMultiValue trackedMultiValue) {
      //noinspection unchecked
      return trackedMultiValue.getTimeLine();
    } else {
      return null;
    }
  }

  public void clear() {
    this.created = false;
    this.changed = false;
    original = null;
    removeTimeline();
  }

  public void clearNotExists() {
    original = null;
    removeTimeline();
  }

  public void removeTimeline() {
    //noinspection rawtypes
    if (value instanceof TrackedMultiValue trackedMultiValue) {
      trackedMultiValue.disableTracking(null);
    }
  }

  public void replaceListener(EntityImpl entity) {
    enableTracking(entity);
  }

  public boolean enableTracking(EntityImpl entity) {
    //noinspection rawtypes
    if (value instanceof TrackedMultiValue trackedMultiValue) {
      trackedMultiValue.enableTracking(entity);
      return true;
    } else {
      return false;
    }
  }

  public void disableTracking(EntityImpl entity, Object fieldValue) {
    //noinspection rawtypes
    if (fieldValue instanceof TrackedMultiValue trackedMultiValue) {
      trackedMultiValue.disableTracking(entity);
    }
  }

  public boolean isTrackedModified() {
    //noinspection rawtypes
    if (value instanceof TrackedMultiValue trackedMultiValue) {
      return trackedMultiValue.isModified();
    }
    if (value instanceof EntityImpl entity && entity.isEmbedded()) {
      return entity.isDirty();
    }
    return false;
  }

  public boolean isTxTrackedModified() {
    //noinspection rawtypes
    if (value instanceof TrackedMultiValue trackedMultiValue) {
      return trackedMultiValue.isTransactionModified();
    }
    if (value instanceof EntityImpl entity && entity.isEmbedded()) {
      return entity.isDirty();
    }
    return false;
  }

  public void markChanged() {
    this.changed = true;
    this.txChanged = true;
    checkAndStoreOnLoadValue();
  }

  public void unmarkChanged() {
    this.changed = false;
  }

  public void markCreated() {
    this.created = true;
    this.txCreated = true;
  }

  public void unmarkCreated() {
    this.created = false;
  }

  public void undo() {
    if (changed) {
      this.value = original;
      unmarkChanged();
      original = null;
      exists = true;
    }
  }

  public void transactionClear() {
    //noinspection rawtypes
    if (value instanceof TrackedMultiValue trackedMultiValue) {
      trackedMultiValue.transactionClear();
    }
    this.txCreated = false;
    this.txChanged = false;
    this.hasOnLoadValue = false;
    this.onLoadValue = null;
  }

  public Object getOnLoadValue(DatabaseSessionInternal session) {
    if (!hasOnLoadValue && !(value instanceof TrackedMultiValue<?, ?>)) {
      return value;
    }

    if (hasOnLoadValue) {
      //noinspection rawtypes
      if (onLoadValue instanceof TrackedMultiValue trackedOnLoadValue) {
        //noinspection rawtypes
        var transactionTimeLine = trackedOnLoadValue.getTransactionTimeLine();
        //noinspection unchecked
        return transactionTimeLine != null
            ? trackedOnLoadValue.returnOriginalState(session.getActiveTransaction(),
            transactionTimeLine.getMultiValueChangeEvents())
            : onLoadValue;
      } else {
        return onLoadValue;
      }
    } else {
      //noinspection rawtypes
      var trackedOnLoadValue = (TrackedMultiValue) value;
      //noinspection rawtypes
      var transactionTimeLine = trackedOnLoadValue.getTransactionTimeLine();
      //noinspection unchecked
      return transactionTimeLine != null
          ? trackedOnLoadValue.returnOriginalState(session.getActiveTransaction(),
          transactionTimeLine.getMultiValueChangeEvents())
          : trackedOnLoadValue;
    }
  }

  public boolean isTxChanged() {
    return txChanged;
  }

  public boolean isTxCreated() {
    return txCreated;
  }

  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  public boolean isTxExists() {
    return txExists;
  }

  private void checkAndStoreOnLoadValue() {
    if (changed && !hasOnLoadValue) {
      onLoadValue = original;
      hasOnLoadValue = true;
    }
  }
}
