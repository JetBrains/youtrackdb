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

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import javax.annotation.Nullable;

/**
 * Base interface that represents a record element.
 */
public interface RecordElement {

  /**
   * Available record statuses.
   */
  enum STATUS {
    NOT_LOADED,
    LOADED,
    MARSHALLING,
    UNMARSHALLING
  }

  /**
   * Marks the instance as dirty. The dirty status could be propagated up if the implementation
   * supports ownership concept.
   */
  void setDirty();

  void setDirtyNoChanged();

  /**
   * @return Returns record element which contains given one.
   */
  RecordElement getOwner();

  default EntityImpl getOwnerEntity() {
    if (this instanceof EntityImpl entity) {
      return entity;
    }

    var owner = getOwner();

    while (true) {
      if (owner instanceof EntityImpl entity) {
        return entity;
      }

      if (owner == null) {
        return null;
      }

      owner = owner.getOwner();
    }
  }

  @Nullable
  default DatabaseSessionInternal getSession() {
    if (this instanceof EntityImpl entity) {
      return (DatabaseSessionInternal) entity.getBoundedToSession();
    }

    return getOwnerEntity().getSession();
  }

  void setOwner(RecordElement owner);
}
