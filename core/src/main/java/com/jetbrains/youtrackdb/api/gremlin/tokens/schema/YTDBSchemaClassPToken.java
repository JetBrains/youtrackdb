package com.jetbrains.youtrackdb.api.gremlin.tokens.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.schema.YTDBSchemaClass;
import com.jetbrains.youtrackdb.api.gremlin.tokens.YTDBDomainObjectPToken;

public enum YTDBSchemaClassPToken implements YTDBDomainObjectPToken<YTDBSchemaClass> {
  abstractClass {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.abstractClass();
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },

  strictMode {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.strictMode();
    }

    @Override
    public void update(YTDBSchemaClass domainObject, Object value) {
      validateValue(value);
      domainObject.strictMode(Boolean.TRUE.equals(value));
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }

    @Override
    public boolean isMutableInGraphTraversal() {
      return true;
    }
  },

  hasSuperClasses {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.hasSuperClasses();
    }


    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }

    @Override
    public boolean isMutableInGraphTraversal() {
      return true;
    }
  },

  name {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.name();
    }

    @Override
    public void update(YTDBSchemaClass domainObject, Object value) {
      validateValue(value);
      domainObject.name((String) value);
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }

    @Override
    public boolean isMutableInGraphTraversal() {
      return true;
    }
  },

  description {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.description();
    }

    @Override
    public void update(YTDBSchemaClass domainObject, Object value) {
      validateValue(value);
      domainObject.description((String) value);
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }

    @Override
    public boolean isMutableInGraphTraversal() {
      return true;
    }
  },

  collectionIds {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.collectionIds();
    }


    @Override
    public Class<?> expectedValueType() {
      return int[].class;
    }
  },

  polymorphicCollectionIds {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.polymorphicCollectionIds();
    }

    @Override
    public Class<?> expectedValueType() {
      return int[].class;
    }
  },

  isEdgeType {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.isEdgeType();
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },

  isVertexType {
    @Override
    public Object fetch(YTDBSchemaClass domainObject) {
      return domainObject.isVertexType();
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },
}
