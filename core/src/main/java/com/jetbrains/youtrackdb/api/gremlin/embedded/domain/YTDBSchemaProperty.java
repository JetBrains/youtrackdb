package com.jetbrains.youtrackdb.api.gremlin.embedded.domain;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.api.schema.PropertyType;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface YTDBSchemaProperty extends YTDBDomainVertex {
  String LABEL = "$schemaProperty";

  @SuppressWarnings("rawtypes")

  @Override
  default String label() {
    return LABEL;
  }

  @Nonnull
  String name();

  void name(@Nonnull String propertyName);

  @Nonnull
  String fullName();

  @Nonnull
  PropertyType propertyType();

  void propertyType(@Nonnull PropertyType propertyType);

  @Nonnull
  String type();

  void type(@Nonnull final String propertyType);

  @Nullable
  YTDBSchemaClass linkedClass();

  void linkedClass(@Nullable YTDBSchemaClass linkedClass);

  @Nullable
  PropertyType linkedPropertyType();

  void linkedPropertyType(@Nullable PropertyType linkedPropertyType);

  @Nullable
  String linkedType();

  void linkedType(@Nullable String type);

  boolean notNull();

  void notNull(boolean notNull);

  @Nullable
  String collateName();

  void collateName(@Nullable String collateName);

  boolean mandatory();

  void mandatory(boolean mandatory);

  boolean readonly();

  void readonly(boolean readonly);

  @Nullable
  String min();

  void min(@Nullable String min);

  @Nullable
  String max();

  void max(@Nullable String max);

  @Nullable
  String defaultValue();

  void defaultValue(@Nullable String defaultValue);

  @Nullable
  String regexp();

  void regexp(@Nullable String regexp);

  @Nullable
  String customProperty(@Nonnull final String propertyName);

  void customProperty(@Nonnull final String propertyName, @Nullable final String propertyValue);

  void removeCustomProperty(@Nonnull final String propertyName);

  void clearCustomProperties();

  @Nullable
  Iterator<String> customPropertyNames();

  @Nonnull
  YTDBSchemaClass declaringClass();

  @Nonnull
  String description();

  void description(@Nullable String description);

  YTDBSchemaIndex createIndex(String indexName, IndexType indexType);
}
