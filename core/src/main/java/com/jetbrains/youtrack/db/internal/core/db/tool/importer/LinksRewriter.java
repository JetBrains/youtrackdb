package com.jetbrains.youtrack.db.internal.core.db.tool.importer;

import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.EntityPropertiesVisitor;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import javax.annotation.Nullable;

/**
 *
 */
public final class LinksRewriter implements EntityPropertiesVisitor {

  private final ConverterData converterData;

  public LinksRewriter(ConverterData converterData) {
    this.converterData = converterData;
  }

  @Nullable
  @Override
  public Object visitField(DatabaseSessionInternal db,
      PropertyTypeInternal type,
      PropertyTypeInternal linkedType,
      Object value) {
    final var valuesConverter =
        ImportConvertersFactory.INSTANCE.getConverter(value, converterData);
    if (valuesConverter == null) {
      return value;
    }

    final var newValue = valuesConverter.convert(db, value);

    // this code intentionally uses == instead of equals, in such case we may distinguish rids which
    // already contained in
    // document and RID which is used to indicate broken record
    if (newValue == ImportConvertersFactory.BROKEN_LINK) {
      return null;
    }

    return newValue;
  }

  @Override
  public boolean goFurther(PropertyTypeInternal type, PropertyTypeInternal linkedType, Object value,
      Object newValue) {
    return true;
  }

  @Override
  public boolean goDeeper(PropertyTypeInternal type, PropertyTypeInternal linkedType,
      Object value) {
    return true;
  }

  @Override
  public boolean updateMode() {
    return true;
  }
}
