package com.jetbrains.youtrackdb.internal.core.metadata.schema.entities;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class SchemaGlobalPropertyEntity extends EntityImpl implements SchemaEntity {
  public static final String NAME_PROPERTY = "name";
  public static final String TYPE_PROPERTY = "type";
  public static final String ID = "id";

  public SchemaGlobalPropertyEntity(
      @Nonnull RecordIdInternal recordId,
      @Nonnull DatabaseSessionEmbedded session) {
    super(recordId, session);
  }

  @Nullable
  public String getName() {
    return getString(NAME_PROPERTY);
  }

  public void setName(@Nonnull String name) {
    setString(NAME_PROPERTY, name);
  }

  @Nullable
  public PropertyTypeInternal getType() {
    var type = getString(TYPE_PROPERTY);
    if (type == null) {
      return null;
    }

    return PropertyTypeInternal.valueOf(type);
  }

  public void setType(@Nonnull PropertyTypeInternal type) {
    setString(TYPE_PROPERTY, type.name());
  }

  public int getId() {
    return getInt(ID);
  }

  public void setId(int id) {
    setInt(ID, id);
  }
}
