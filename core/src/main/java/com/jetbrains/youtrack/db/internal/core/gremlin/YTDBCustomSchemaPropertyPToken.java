package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectPToken;
import javax.annotation.Nonnull;

public class YTDBCustomSchemaPropertyPToken implements
    YTDBDomainObjectPToken<YTDBSchemaProperty> {

  private final @Nonnull String name;

  public YTDBCustomSchemaPropertyPToken(@Nonnull String name) {
    this.name = name;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Object fetch(YTDBSchemaProperty domainObject) {
    return domainObject.customProperty(name);
  }

  @Override
  public void update(YTDBSchemaProperty domainObject, Object value) {
    validateValue(value);
    domainObject.customProperty(name, (String) value);
  }

  @Override
  public Class<?> expectedValueType() {
    return String.class;
  }

  @Override
  public boolean isMutableInGraphTraversal() {
    return true;
  }
}
