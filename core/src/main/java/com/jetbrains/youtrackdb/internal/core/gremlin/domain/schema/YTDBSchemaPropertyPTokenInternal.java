package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;

public enum YTDBSchemaPropertyPTokenInternal implements YTDBPTokenInternal<YTDBSchemaPropertyImpl> {
  name {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.name();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
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

  fullName {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.fullName();
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }
  },

  type {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.type();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.type((String) value);
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

  linkedType {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.linkedType();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.linkedType((String) value);
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

  notNull {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.notNull();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.notNull(Boolean.TRUE.equals(value));
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

  collateName {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.collateName();
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }

    @Override
    public boolean isMutable() {
      return true;
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.collateName((String) value);
    }
  },

  mandatory {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.mandatory();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.mandatory((Boolean) value);
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

  readonly {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.readonly();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.readonly((Boolean) value);
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

  min {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.min();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.min((String) value);
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

  max {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.max();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.max((String) value);
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

  defaultValue {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.defaultValue();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.defaultValue((String) value);
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

  regexp {
    @Override
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.regexp();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
      validateValue(value);
      domainObject.regexp((String) value);
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
    public Object fetch(YTDBSchemaPropertyImpl domainObject) {
      return domainObject.description();
    }

    @Override
    public void update(YTDBSchemaPropertyImpl domainObject, Object value) {
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
  }
}
