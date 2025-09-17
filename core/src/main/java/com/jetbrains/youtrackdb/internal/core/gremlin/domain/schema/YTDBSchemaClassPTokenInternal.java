package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;

public enum YTDBSchemaClassPTokenInternal implements YTDBPTokenInternal<YTDBSchemaClassImpl> {
  abstractClass {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.abstractClass();
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },

  strictMode {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.strictMode();
    }

    @Override
    public void update(YTDBSchemaClassImpl domainObject, Object value) {
      validateValue(value);
      domainObject.strictMode(Boolean.TRUE.equals(value));
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }

    @Override
    public boolean isMutable() {
      return true;
    }
  },

  hasSuperClasses {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.hasSuperClasses();
    }


    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },

  name {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.name();
    }

    @Override
    public void update(YTDBSchemaClassImpl domainObject, Object value) {
      validateValue(value);
      domainObject.name((String) value);
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }

    @Override
    public boolean isMutable() {
      return true;
    }
  },

  description {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.description();
    }

    @Override
    public void update(YTDBSchemaClassImpl domainObject, Object value) {
      validateValue(value);
      domainObject.description((String) value);
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }

    @Override
    public boolean isMutable() {
      return true;
    }
  },

  collectionIds {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.collectionIds();
    }


    @Override
    public Class<?> expectedValueType() {
      return int[].class;
    }
  },

  polymorphicCollectionIds {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.polymorphicCollectionIds();
    }

    @Override
    public Class<?> expectedValueType() {
      return int[].class;
    }
  },

  isEdgeType {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.isEdgeType();
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },

  isVertexType {
    @Override
    public Object fetch(YTDBSchemaClassImpl domainObject) {
      return domainObject.isVertexType();
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },
}
