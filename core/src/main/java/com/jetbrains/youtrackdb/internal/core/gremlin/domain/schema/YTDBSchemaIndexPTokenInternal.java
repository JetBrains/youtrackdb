package com.jetbrains.youtrackdb.internal.core.gremlin.domain.schema;

import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexBy;
import com.jetbrains.youtrackdb.api.gremlin.embedded.domain.YTDBSchemaIndex.IndexType;
import com.jetbrains.youtrackdb.internal.core.gremlin.domain.tokens.YTDBPTokenInternal;
import java.util.Map;

public enum YTDBSchemaIndexPTokenInternal implements YTDBPTokenInternal<YTDBSchemaIndexImpl> {
  name {
    @Override
    public Object fetch(YTDBSchemaIndexImpl domainObject) {
      return domainObject.name();
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
    public void update(YTDBSchemaIndexImpl domainObject, Object value) {
      validateValue(value);

      if (value == null) {
        throw new IllegalArgumentException("Index name cannot be null");
      }

      domainObject.name(value.toString());
    }
  },

  nullValuesIgnored {
    @Override
    public Object fetch(YTDBSchemaIndexImpl domainObject) {
      return domainObject.nullValuesIgnored();
    }

    @Override
    public Class<?> expectedValueType() {
      return Boolean.class;
    }
  },

  metadata {
    @Override
    public Object fetch(YTDBSchemaIndexImpl domainObject) {
      return domainObject.metadata();
    }

    @Override
    public Class<?> expectedValueType() {
      return Map.class;
    }
  },

  indexBy {
    @Override
    public Object fetch(YTDBSchemaIndexImpl domainObject) {
      return domainObject.indexBy();
    }

    @Override
    public Class<?> expectedValueType() {
      return IndexBy[].class;
    }
  },

  indexType {
    @Override
    public Object fetch(YTDBSchemaIndexImpl domainObject) {
      return domainObject.indexType();
    }

    @Override
    public Class<?> expectedValueType() {
      return IndexType.class;
    }
  }

}
