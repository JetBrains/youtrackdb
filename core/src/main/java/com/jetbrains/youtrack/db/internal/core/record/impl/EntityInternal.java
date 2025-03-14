package com.jetbrains.youtrack.db.internal.core.record.impl;

import com.jetbrains.youtrack.db.api.record.Entity;
import com.jetbrains.youtrack.db.api.record.RID;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.SchemaImmutableClass;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface EntityInternal extends Entity {

  Collection<String> getPropertyNamesInternal(boolean includeSystemProperties, boolean checkAccess);

  void setPropertyInternal(String name, Object value);

  void setPropertyInternal(String name, Object value, PropertyType type);

  <RET> RET removePropertyInternal(String name);

  <RET> RET getPropertyInternal(String name);

  <RET> RET getPropertyInternal(String name, boolean lazyLoading);

  @Nullable
  RID getLinkPropertyInternal(String name);

  <RET> RET getPropertyOnLoadValueInternal(@Nonnull String name);

  @Nullable
  SchemaImmutableClass getImmutableSchemaClass(@Nonnull DatabaseSessionInternal session);

  Collection<String> getDirtyPropertiesInternal(boolean includeSystemProperties,
      boolean checkAccess);

  Collection<String> getDirtyPropertiesBetweenCallbacksInternal(boolean includeSystemFields,
      boolean checkAccess);
}
