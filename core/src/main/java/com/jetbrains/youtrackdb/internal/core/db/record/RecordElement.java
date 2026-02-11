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
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashSet;
import java.util.Set;
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
  @Nullable
  RecordElement getOwner();

  @Nullable
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

  default Set<RecordElement> getOwnersSet() {
    var owner = getOwner();
    if (owner == null) {
      return Set.of();
    }

    var result = new HashSet<RecordElement>();
    result.add(owner);
    while (true) {
      owner = owner.getOwner();
      if (owner == null) {
        break;
      }
      result.add(owner);
    }

    return result;
  }

  default boolean isOneOfOwners(RecordElement element) {
    var owner = getOwner();
    if (owner == null) {
      return false;
    }

    while (true) {
      if (owner == element) {
        return true;
      }
      owner = owner.getOwner();
      if (owner == null) {
        return false;
      }
    }
  }

  @Nullable
  default DatabaseSessionEmbedded getSession() {
    if (this instanceof EntityImpl entity) {
      return entity.getBoundedToSession();
    }

    var owner = getOwnerEntity();
    if (owner == null) {
      return null;
    }

    return owner.getSession();
  }

  void setOwner(RecordElement owner);
}
