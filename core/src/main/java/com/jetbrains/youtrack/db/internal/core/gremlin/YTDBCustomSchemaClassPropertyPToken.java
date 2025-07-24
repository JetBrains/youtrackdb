package com.jetbrains.youtrack.db.internal.core.gremlin;

import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectPToken;
import javax.annotation.Nonnull;

public class YTDBCustomSchemaClassPropertyPToken implements
    YTDBDomainObjectPToken<YTDBSchemaClass> {

  private final @Nonnull String name;

  public YTDBCustomSchemaClassPropertyPToken(@Nonnull String name) {
    this.name = name;
  }

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
