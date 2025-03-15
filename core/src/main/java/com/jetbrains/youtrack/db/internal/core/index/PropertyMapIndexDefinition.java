/*
 *
 *
 *  *
 *  *  Licensed under the Apache License, Version 2.0 (the "License");
 *  *  you may not use this file except in compliance with the License.
 *  *  You may obtain a copy of the License at
 *  *
 *  *       http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  *  Unless required by applicable law or agreed to in writing, software
 *  *  distributed under the License is distributed on an "AS IS" BASIS,
 *  *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  *  See the License for the specific language governing permissions and
 *  *  limitations under the License.
 *  *
 *
 *
 */
package com.jetbrains.youtrack.db.internal.core.index;

import com.fasterxml.jackson.core.JsonGenerator;
import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.exception.SerializationException;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;

/**
 * Index implementation bound to one schema class property that presents
 * {@link PropertyType#EMBEDDEDMAP or
 *
 * @link PropertyType#LINKMAP} property.
 */
public class PropertyMapIndexDefinition extends PropertyIndexDefinition
    implements IndexDefinitionMultiValue {

  /**
   * Indicates whether Map will be indexed using its keys or values.
   */
  public enum INDEX_BY {
    KEY,
    VALUE
  }

  private INDEX_BY indexBy = INDEX_BY.KEY;

  public PropertyMapIndexDefinition() {
  }

  public PropertyMapIndexDefinition(
      final String iClassName, final String iField, final PropertyType iType,
      final INDEX_BY indexBy) {
    super(iClassName, iField, iType);

    if (indexBy == null) {
      throw new NullPointerException(
          "You have to provide way by which map entries should be mapped");
    }

    this.indexBy = indexBy;
  }

  @Override
  public Object getDocumentValueToIndex(DatabaseSessionInternal session, EntityImpl entity) {
    return createValue(session, entity.<Object>getProperty(field));
  }

  @Override
  public Object createValue(DatabaseSessionInternal session, List<?> params) {
    if (!(params.get(0) instanceof Map)) {
      return null;
    }

    final var mapParams = extractMapParams((Map<?, ?>) params.get(0));
    final List<Object> result = new ArrayList<>(mapParams.size());
    for (final var mapParam : mapParams) {
      result.add(createSingleValue(session, mapParam));
    }

    return result;
  }

  @Override
  public Object createValue(DatabaseSessionInternal session, Object... params) {
    if (!(params[0] instanceof Map)) {
      return null;
    }

    final var mapParams = extractMapParams((Map<?, ?>) params[0]);

    final List<Object> result = new ArrayList<>(mapParams.size());
    for (final var mapParam : mapParams) {
      var val = createSingleValue(session, mapParam);
      result.add(val);
    }
    if (getFieldsToIndex().size() == 1 && result.size() == 1) {
      return result.get(0);
    }
    return result;
  }

  public INDEX_BY getIndexBy() {
    return indexBy;
  }

  @Override
  protected void serializeToJson(JsonGenerator jsonGenerator) {
    try {
      super.serializeToJson(jsonGenerator);
      jsonGenerator.writeStringField("mapIndexBy", indexBy.toString());
    } catch (IOException e) {
      throw BaseException.wrapException(
          new SerializationException("Failed to serialize index definition to JSON"),
          e, (String) null);
    }
  }

  @Override
  protected void serializeToMap(@Nonnull Map<String, Object> map, DatabaseSessionInternal session) {
    super.serializeToMap(map, session);
    map.put("mapIndexBy", indexBy.toString());
  }

  @Override
  protected void serializeFromMap(@Nonnull Map<String, ?> map) {
    super.serializeFromMap(map);
    indexBy = INDEX_BY.valueOf((String) map.get("mapIndexBy"));
  }

  private Collection<?> extractMapParams(Map<?, ?> map) {
    if (indexBy == INDEX_BY.KEY) {
      return map.keySet();
    }
    return map.values();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    var that = (PropertyMapIndexDefinition) o;

    return indexBy == that.indexBy;
  }

  public Object createSingleValue(DatabaseSessionInternal session, final Object... param) {
    return keyType.convert(refreshRid(session, param[0]), null, null, session);
  }

  public void processChangeEvent(
      DatabaseSessionInternal session,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    final boolean result;
    if (indexBy.equals(INDEX_BY.KEY)) {
      result = processKeyChangeEvent(session, changeEvent, keysToAdd, keysToRemove);
    } else {
      result = processValueChangeEvent(session, changeEvent, keysToAdd, keysToRemove);
    }

    if (!result) {
      throw new IllegalArgumentException("Invalid change type :" + changeEvent.getChangeType());
    }
  }

  private boolean processKeyChangeEvent(
      DatabaseSessionInternal session,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    return switch (changeEvent.getChangeType()) {
      case ADD -> {
        processAdd(createSingleValue(session, changeEvent.getKey()), keysToAdd, keysToRemove);
        yield true;
      }
      case REMOVE -> {
        processRemoval(createSingleValue(session, changeEvent.getKey()), keysToAdd, keysToRemove);
        yield true;
      }
      case UPDATE -> true;
      default -> false;
    };
  }

  private boolean processValueChangeEvent(
      DatabaseSessionInternal session,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    switch (changeEvent.getChangeType()) {
      case ADD:
        processAdd(createSingleValue(session, changeEvent.getValue()), keysToAdd, keysToRemove);
        return true;
      case REMOVE:
        processRemoval(
            createSingleValue(session, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        return true;
      case UPDATE:
        processRemoval(
            createSingleValue(session, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        processAdd(createSingleValue(session, changeEvent.getValue()), keysToAdd, keysToRemove);
        return true;
    }
    return false;
  }

  @Override
  public List<String> getFieldsToIndex() {
    if (indexBy == INDEX_BY.KEY) {
      return Collections.singletonList(field + " by key");
    }
    return Collections.singletonList(field + " by value");
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + indexBy.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "PropertyMapIndexDefinition{" + "indexBy=" + indexBy + "} " + super.toString();
  }

  @Override
  public String toCreateIndexDDL(String indexName, String indexType, String engine) {
    final var ddl = new StringBuilder("create index `");

    ddl.append(indexName).append("` on `");
    ddl.append(className).append("` ( `").append(field).append("`");

    if (indexBy == INDEX_BY.KEY) {
      ddl.append(" by key");
    } else {
      ddl.append(" by value");
    }

    ddl.append(" ) ");
    ddl.append(indexType);

    return ddl.toString();
  }
}
