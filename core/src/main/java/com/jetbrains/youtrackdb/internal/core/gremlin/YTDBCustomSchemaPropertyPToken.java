package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;
import javax.annotation.Nonnull;

public record YTDBCustomSchemaPropertyPToken(@Nonnull String name) implements
    YTDBDomainObjectPToken<YTDBSchemaProperty> {

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
