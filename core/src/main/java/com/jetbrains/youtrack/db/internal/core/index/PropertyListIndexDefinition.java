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

import com.jetbrains.youtrack.db.api.exception.BaseException;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Index implementation bound to one schema class property that presents
 * {@link PropertyType#EMBEDDEDLIST}, {@link PropertyType#LINKLIST}, {@link PropertyType#LINKSET} or
 * {@link PropertyType#EMBEDDEDSET} properties.
 */
public class PropertyListIndexDefinition extends PropertyIndexDefinition
    implements IndexDefinitionMultiValue {

  @SuppressWarnings("unused")
  public PropertyListIndexDefinition() {
  }

  public PropertyListIndexDefinition(
      final String iClassName, final String iField, final PropertyType iType) {
    super(iClassName, iField, iType);
  }

  @Override
  public Object getDocumentValueToIndex(DatabaseSessionInternal session, EntityImpl entity) {
    return createValue(session, entity.<Object>getProperty(field));
  }

  @Override
  public Object createValue(DatabaseSessionInternal session, List<?> params) {
    if (!(params.get(0) instanceof Collection)) {
      params = Collections.singletonList(params);
    }

    final var multiValueCollection = (Collection<?>) params.get(0);
    final List<Object> values = new ArrayList<>(multiValueCollection.size());
    for (final var item : multiValueCollection) {
      values.add(createSingleValue(session, item));
    }
    return values;
  }

  @Override
  public Object createValue(DatabaseSessionInternal session, final Object... params) {
    var param = params[0];
    if (!(param instanceof Collection<?>)) {
      try {
        return keyType.convert(refreshRid(session, param), null, null, session);
      } catch (Exception e) {
        return null;
      }
    }

    @SuppressWarnings("PatternVariableCanBeUsed")
    var multiValueCollection = (Collection<?>) param;
    final List<Object> values = new ArrayList<>(multiValueCollection.size());
    for (final var item : multiValueCollection) {
      values.add(createSingleValue(session, item));
    }
    return values;
  }

  public Object createSingleValue(DatabaseSessionInternal session, final Object... param) {
    try {
      var value = refreshRid(session, param[0]);
      return keyType.convert(value, null, null, session);
    } catch (Exception e) {
      throw BaseException.wrapException(
          new IndexException(session.getDatabaseName(),
              "Invalid key for index: " + param[0] + " cannot be converted to " + keyType),
          e, session.getDatabaseName());
    }
  }

  public void processChangeEvent(
      DatabaseSessionInternal session,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    switch (changeEvent.getChangeType()) {
      case ADD: {
        processAdd(createSingleValue(session, changeEvent.getValue()), keysToAdd, keysToRemove);
        break;
      }
      case REMOVE: {
        processRemoval(
            createSingleValue(session, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        break;
      }
      case UPDATE: {
        processRemoval(
            createSingleValue(session, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        processAdd(createSingleValue(session, changeEvent.getValue()), keysToAdd, keysToRemove);
        break;
      }
      default:
        throw new IllegalArgumentException("Invalid change type : " + changeEvent.getChangeType());
    }
  }

  @Override
  public String toCreateIndexDDL(String indexName, String indexType, String engine) {
    return createIndexDDLWithoutFieldType(indexName, indexType, engine).toString();
  }
}
