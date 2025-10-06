package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBDomainVertex;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaProperty;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.YTDBDomainEdgeImpl;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBOutTokenInternal;
import java.util.Iterator;

public enum YTDBSchemaClassOutTokenInternal implements YTDBOutTokenInternal<YTDBSchemaClassImpl> {
  parentClass {
    @Override
    public <I extends YTDBDomainVertex> YTDBDomainEdgeImpl<YTDBSchemaClassImpl, I> add(
        YTDBSchemaClassImpl outVertex, I inVertex) {
      if (inVertex instanceof YTDBSchemaClassImpl schemaClass) {
        outVertex.addParentClass(schemaClass);
        return new YTDBDomainEdgeImpl<>(outVertex.graph(), outVertex, inVertex, this);
      } else {
        throw new IllegalArgumentException(
            "inVertex must be instance of " + YTDBSchemaClass.class.getSimpleName());
      }
    }

    @Override
    public <I extends YTDBDomainVertex> void remove(YTDBSchemaClassImpl outVertex, I inVertex) {
      if (inVertex instanceof YTDBSchemaClassImpl schemaClass) {
        outVertex.removeParentClass(schemaClass);
      } else {
        throw new IllegalArgumentException(
            "inVertex must be instance of " + YTDBSchemaClass.class.getSimpleName());
      }
    }

    @Override
    public Iterator<YTDBSchemaClass> apply(YTDBSchemaClassImpl ytdbSchemaClass) {
      return ytdbSchemaClass.parentClasses();
    }
  },

  declaredProperty {
    @Override
    public <I extends YTDBDomainVertex> YTDBDomainEdgeImpl<YTDBSchemaClassImpl, I> add(
        YTDBSchemaClassImpl outVertex, I inVertex) {
      if (inVertex instanceof YTDBSchemaPropertyImpl schemaProperty) {
        outVertex.addSchemaProperty(schemaProperty);
        return new YTDBDomainEdgeImpl<>(outVertex.graph(), outVertex, inVertex, this);
      } else {
        throw new IllegalArgumentException(
            "inVertex must be instance of " + YTDBSchemaProperty.class.getSimpleName());
      }
    }

    @Override
    public <I extends YTDBDomainVertex> void remove(YTDBSchemaClassImpl outVertex, I inVertex) {
      if (inVertex instanceof YTDBSchemaPropertyImpl schemaProperty) {
        outVertex.removeSchemaProperty(schemaProperty);
      } else {
        throw new IllegalArgumentException(
            "inVertex must be instance of " + YTDBSchemaProperty.class.getSimpleName());
      }
    }

    @Override
    public Iterator<YTDBSchemaProperty> apply(YTDBSchemaClassImpl ytdbSchemaClass) {
      return ytdbSchemaClass.declaredProperty();
    }
  }
}
