package com.jetbrains.youtrack.db.api.gremlin.tokens.schema;

import com.jetbrains.youtrack.db.api.gremlin.embedded.schema.YTDBSchemaProperty;
import com.jetbrains.youtrack.db.api.gremlin.tokens.YTDBDomainObjectPToken;

public enum YTDBSchemaPropertyPToken implements YTDBDomainObjectPToken<YTDBSchemaProperty> {
  name {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.name();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
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

  fullName {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.fullName();
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }
  },

  type {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.type();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.type((String) value);
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

  linkedType {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.linkedType();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.linkedType((String) value);
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

  notNull {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.notNull();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.notNull(Boolean.TRUE.equals(value));
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

  collateName {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.collateName();
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }

    @Override
    public boolean isMutableInGraphTraversal() {
      return true;
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.collateName((String) value);
    }
  },

  mandatory {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.mandatory();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.mandatory((Boolean) value);
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

  readonly {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.readonly();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.readonly((Boolean) value);
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

  min {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.min();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.min((String) value);
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

  max {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.max();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.max((String) value);
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

  defaultValue {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.defaultValue();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.defaultValue((String) value);
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }
  },

  regexp {
    @Override
    public Object fetch(YTDBSchemaProperty domainObject) {
      return domainObject.regexp();
    }

    @Override
    public void update(YTDBSchemaProperty domainObject, Object value) {
      validateValue(value);
      domainObject.regexp((String) value);
    }

    @Override
    public Class<?> expectedValueType() {
      return String.class;
    }
  }
}
