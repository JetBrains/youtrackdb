package com.jetbrains.youtrackdb.internal.core.gremlin;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;
import javax.annotation.Nonnull;

public record YTDBCustomSchemaClassPropertyPToken(@Nonnull String name) implements
    YTDBDomainObjectPToken<YTDBSchemaClass> {

  @Override
  public String name() {
    return name;
  }

  @Override
  public Object fetch(YTDBSchemaClass domainObject) {
    return domainObject.customProperty(name);
  }

  @Override
  public void update(YTDBSchemaClass domainObject, Object value) {
    validateValue(value);
    domainObject.customProperty((String) value);
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
