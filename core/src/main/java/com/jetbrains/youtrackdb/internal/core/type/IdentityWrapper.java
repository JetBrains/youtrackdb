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
package com.jetbrains.youtrackdb.internal.core.type;

import com.jetbrains.youtrackdb.api.exception.RecordNotFoundException;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import javax.annotation.Nonnull;

/**
 * Base abstract class to wrap a entity.
 */
public abstract class IdentityWrapper implements Identifiable {

  private final @Nonnull RID rid;

  public IdentityWrapper(DatabaseSessionEmbedded sessionInternal, final String iClassName) {
    var entity = sessionInternal.newEntity(iClassName);
    rid = entity.getIdentity();
  }

  public IdentityWrapper(EntityImpl entity) {
    rid = entity.getIdentity();
  }

  protected abstract void toEntity(@Nonnull DatabaseSessionEmbedded db, @Nonnull EntityImpl entity);

  @Override
  public int compareTo(@Nonnull Identifiable o) {
    return rid.compareTo(o.getIdentity());
  }

  @Override
  @Nonnull
  public RID getIdentity() {
    return rid;
  }

  public void save(DatabaseSessionEmbedded db) {
    var entity = db.loadEntity(rid);
    toEntity(db, (EntityImpl) entity);
  }

  public void delete(DatabaseSessionEmbedded db) {
    try {
      var entity = db.loadEntity(rid);
      db.delete(entity);
    } catch (RecordNotFoundException e) {
      // Ignore
    }
  }


  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    var that = (IdentityWrapper) o;
    return rid.equals(that.rid);
  }

  @Override
  public int hashCode() {
    return rid.hashCode();
  }
}
