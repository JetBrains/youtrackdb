package com.jetbrains.youtrackdb.api.gremlin.embedded.domain;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.Iterator;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface YTDBSchemaIndex extends YTDBDomainVertex {

  String LABEL = "$schemaIndex";

  @Nonnull
  String name();

  void name(String name);

  boolean nullValuesIgnored();

  @Nullable
  Map<String, ?> metadata();

  @Nonnull
  PropertyType[] keyTypes();

  IndexBy[] indexBy();

  @Nonnull
  IndexType indexType();

  @Nullable
  YTDBSchemaClass classToIndex();

  @Nonnull
  Iterator<YTDBSchemaProperty> propertiesToIndex();

  enum IndexType {
    UNIQUE,
    NOT_UNIQUE
  }

  enum IndexBy {
    BY_VALUE,
    BY_KEY
  }
}
