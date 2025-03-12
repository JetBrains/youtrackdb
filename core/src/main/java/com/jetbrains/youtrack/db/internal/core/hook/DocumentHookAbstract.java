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
package com.jetbrains.youtrack.db.internal.core.hook;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.DatabaseSession.STATUS;
import com.jetbrains.youtrack.db.api.record.DBRecord;
import com.jetbrains.youtrack.db.api.record.RecordHook;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import javax.annotation.Nonnull;

/**
 * Hook abstract class that calls separate methods for EntityImpl records.
 *
 * @see RecordHook
 */
public abstract class DocumentHookAbstract implements RecordHook {

  private String[] includeClasses;
  private String[] excludeClasses;

  protected DatabaseSession session;

  public DocumentHookAbstract(DatabaseSession session) {
    this.session = session;
  }

  @Override
  public void onUnregister() {
  }

  /**
   * It's called just before to create the new entity.
   *
   * @param entity The entity to create
   * @return True if the entity has been modified and a new marshalling is required, otherwise false
   */
  public RESULT onRecordBeforeCreate(final EntityImpl entity) {
    return RESULT.RECORD_NOT_CHANGED;
  }

  /**
   * It's called just after the entity is created.
   *
   * @param entity The entity is going to be created
   */
  public void onRecordAfterCreate(final EntityImpl entity) {
  }

  /**
   * It's called just after the entity creation was failed.
   *
   * @param entity The entity just created
   */
  public void onRecordCreateFailed(final EntityImpl entity) {
  }

  /**
   * It's called just before to read the entity.
   *
   * @param entity The entity to read
   */
  public void onRecordBeforeRead(final EntityImpl entity) {
  }

  /**
   * It's called just after the entity is read.
   *
   * @param entity The entity just read
   */
  public void onRecordAfterRead(final EntityImpl entity) {
  }

  /**
   * It's called just after the entity read was failed.
   *
   * @param entity The entity just created
   */
  public void onRecordReadFailed(final EntityImpl entity) {
  }

  /**
   * It's called just before to update the entity.
   *
   * @param entity The entity to update
   * @return True if the entity has been modified and a new marshalling is required, otherwise false
   */
  public RESULT onRecordBeforeUpdate(final EntityImpl entity) {
    return RESULT.RECORD_NOT_CHANGED;
  }

  /**
   * It's called just after the entity is updated.
   *
   * @param entity The entity just updated
   */
  public void onRecordAfterUpdate(final EntityImpl entity) {
  }

  /**
   * It's called just after the entity updated was failed.
   *
   * @param entity The entity is going to be updated
   */
  public void onRecordUpdateFailed(final EntityImpl entity) {
  }

  /**
   * It's called just before to delete the entity.
   *
   * @param entity The entity to delete
   * @return True if the entity has been modified and a new marshalling is required, otherwise false
   */
  public RESULT onRecordBeforeDelete(final EntityImpl entity) {
    return RESULT.RECORD_NOT_CHANGED;
  }

  /**
   * It's called just after the entity is deleted.
   *
   * @param entity The entity just deleted
   */
  public void onRecordAfterDelete(final EntityImpl entity) {
  }

  /**
   * It's called just after the entity deletion was failed.
   *
   * @param entity The entity is going to be deleted
   */
  public void onRecordDeleteFailed(final EntityImpl entity) {
  }


  public void onRecordFinalizeUpdate(final EntityImpl entity) {
  }

  public void onRecordFinalizeCreation(final EntityImpl entity) {
  }

  public void onRecordFinalizeDeletion(final EntityImpl entity) {
  }

  @Override
  public RESULT onTrigger(@Nonnull DatabaseSession session, @Nonnull final TYPE iType,
      @Nonnull final DBRecord iRecord) {
    if (this.session.getStatus() != STATUS.OPEN) {
      return RESULT.RECORD_NOT_CHANGED;
    }

    if (!(iRecord instanceof EntityImpl entity)) {
      return RESULT.RECORD_NOT_CHANGED;
    }

    if (!filterBySchemaClass(entity)) {
      return RESULT.RECORD_NOT_CHANGED;
    }

    switch (iType) {
      case BEFORE_CREATE:
        return onRecordBeforeCreate(entity);

      case AFTER_CREATE:
        onRecordAfterCreate(entity);
        break;

      case CREATE_FAILED:
        onRecordCreateFailed(entity);
        break;

      case BEFORE_READ:
        onRecordBeforeRead(entity);
        break;

      case AFTER_READ:
        onRecordAfterRead(entity);
        break;

      case READ_FAILED:
        onRecordReadFailed(entity);
        break;

      case BEFORE_UPDATE:
        return onRecordBeforeUpdate(entity);

      case AFTER_UPDATE:
        onRecordAfterUpdate(entity);
        break;

      case UPDATE_FAILED:
        onRecordUpdateFailed(entity);
        break;

      case BEFORE_DELETE:
        return onRecordBeforeDelete(entity);

      case AFTER_DELETE:
        onRecordAfterDelete(entity);
        break;

      case DELETE_FAILED:
        onRecordDeleteFailed(entity);
        break;

      case FINALIZE_CREATION:
        onRecordFinalizeCreation(entity);
        break;

      case FINALIZE_UPDATE:
        onRecordFinalizeUpdate(entity);
        break;

      case FINALIZE_DELETION:
        onRecordFinalizeDeletion(entity);
        break;

      default:
        throw new IllegalStateException("Hook method " + iType + " is not managed");
    }

    return RESULT.RECORD_NOT_CHANGED;
  }

  public String[] getIncludeClasses() {
    return includeClasses;
  }

  public void setIncludeClasses(final String... includeClasses) {
    if (excludeClasses != null) {
      throw new IllegalStateException("Cannot include classes if exclude classes has been set");
    }
    this.includeClasses = includeClasses;
  }

  public String[] getExcludeClasses() {
    return excludeClasses;
  }

  public DocumentHookAbstract setExcludeClasses(final String... excludeClasses) {
    if (includeClasses != null) {
      throw new IllegalStateException("Cannot exclude classes if include classes has been set");
    }
    this.excludeClasses = excludeClasses;
    return this;
  }

  protected boolean filterBySchemaClass(final EntityImpl entity) {
    if (includeClasses == null && excludeClasses == null) {
      return true;
    }

    SchemaImmutableClass result = null;
    if (entity != null) {
      result = entity.getImmutableSchemaClass((DatabaseSessionInternal) session);
    }
    final SchemaClass clazz =
        result;
    if (clazz == null) {
      return false;
    }

    if (includeClasses != null) {
      // FILTER BY CLASSES
      for (var cls : includeClasses) {
        if (clazz.isSubClassOf(cls)) {
          return true;
        }
      }
      return false;
    }

    if (excludeClasses != null) {
      // FILTER BY CLASSES
      for (var cls : excludeClasses) {
        if (clazz.isSubClassOf(cls)) {
          return false;
        }
      }
    }

    return true;
  }
}
