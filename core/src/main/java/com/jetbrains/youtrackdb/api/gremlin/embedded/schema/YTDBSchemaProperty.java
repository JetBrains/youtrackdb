package com.jetbrains.youtrackdb.api.gremlin.embedded.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.YTDBDomainObject;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectInToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectObjectOutToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaClassInToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaClassOutToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaPropertyInToken;
import com.jetbrains.youtrackdb.api.gremlin.tokens.schema.YTDBSchemaPropertyOutToken;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.PropertyType;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface YTDBSchemaProperty extends YTDBDomainObject {
  String LABEL = "$schemaProperty";

  @SuppressWarnings("rawtypes")
  @Override
  default YTDBDomainObjectInToken[] inTokens() {
    return YTDBSchemaPropertyInToken.values();
  }

  @SuppressWarnings("rawtypes")
  @Override
  default YTDBDomainObjectObjectOutToken[] outTokens() {
    return YTDBSchemaPropertyOutToken.values();
  }

  @Override
  default YTDBDomainObjectInToken<YTDBDomainObject> inToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBDomainObjectInToken) YTDBSchemaClassInToken.valueOf(label);
  }

  @Override
  default YTDBDomainObjectObjectOutToken<YTDBDomainObject> outToken(String label) {
    //noinspection unchecked,rawtypes
    return (YTDBDomainObjectObjectOutToken) YTDBSchemaClassOutToken.valueOf(label);
  }

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
  YTDBSchemaClass ownerClass();

  @Nonnull
  String description();

  void description(@Nullable String description);
}
