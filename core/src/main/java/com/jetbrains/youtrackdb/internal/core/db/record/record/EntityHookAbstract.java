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
package com.jetbrains.youtrackdb.internal.core.db.record.record;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.SchemaImmutableClass;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import javax.annotation.Nonnull;

/**
 * Hook abstract class that calls separate methods for Entity records.
 *
 * @see RecordHook
 */
public abstract class EntityHookAbstract implements RecordHook {

  private String[] includeClasses;
  private String[] excludeClasses;

  /**
   * It's called just after the entity is read.
   *
   * @param entity The entity just read
   */
  public void onEntityRead(final Entity entity) {
  }

  /**
   * It's called just after the entity is created.
   *
   * @param entity The entity is going to be created
   */
  public void onBeforeEntityCreate(final Entity entity) {
  }

  public void onAfterEntityCreate(final Entity entity) {
  }

  /**
   * It's called just after the entity is updated.
   *
   * @param entity The entity just updated
   */
  public void onBeforeEntityUpdate(final Entity entity) {
  }

  public void onAfterEntityUpdate(final Entity entity) {
  }

  /**
   * It's called just after the entity is deleted.
   *
   * @param entity The entity just deleted
   */
  public void onBeforeEntityDelete(final Entity entity) {
  }

  public void onAfterEntityDelete(final Entity entity) {
  }


  @Override
  public void onTrigger(@Nonnull final TYPE iType, @Nonnull final DBRecord iRecord) {
    if (!(iRecord instanceof Entity entity)) {
      return;
    }

    if (!filterBySchemaClass(entity)) {
      return;
    }

    switch (iType) {
      case READ -> onEntityRead(entity);
      case BEFORE_CREATE -> onBeforeEntityCreate(entity);
      case AFTER_CREATE -> onAfterEntityCreate(entity);
      case BEFORE_UPDATE -> onBeforeEntityUpdate(entity);
      case AFTER_UPDATE -> onAfterEntityUpdate(entity);
      case BEFORE_DELETE -> onBeforeEntityDelete(entity);
      case AFTER_DELETE -> onAfterEntityDelete(entity);
      default -> throw new IllegalStateException("Hook method " + iType + " is not managed");
    }
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

  public EntityHookAbstract setExcludeClasses(final String... excludeClasses) {
    if (includeClasses != null) {
      throw new IllegalStateException("Cannot exclude classes if include classes has been set");
    }

    this.excludeClasses = excludeClasses;
    return this;
  }

  protected boolean filterBySchemaClass(final Entity entity) {
    if (includeClasses == null && excludeClasses == null) {
      return true;
    }

    SchemaImmutableClass result = null;
    if (entity != null) {
      result = ((EntityImpl) entity).getImmutableSchemaClass(
          entity.getBoundedToSession());
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
