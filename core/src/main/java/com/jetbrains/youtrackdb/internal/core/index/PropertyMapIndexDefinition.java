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
package com.jetbrains.youtrackdb.internal.core.index;

import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/// Index implementation bound to one schema class property that presents
/// [PropertyTypeInternal#EMBEDDEDMAP] or [PropertyTypeInternal#LINKMAP] property.
public class PropertyMapIndexDefinition extends PropertyIndexDefinition
    implements IndexDefinitionMultiValue {

  private IndexBy indexBy = IndexBy.BY_KEY;

  public PropertyMapIndexDefinition() {
  }

  public PropertyMapIndexDefinition(
      final String iClassName, final String iField, final PropertyTypeInternal iType,
      final IndexBy indexBy) {
    super(iClassName, iField, iType);

    if (indexBy == null) {
      throw new NullPointerException(
          "You have to provide way by which map entries should be mapped");
    }

    this.indexBy = indexBy;
  }

  @Override
  public Object convertEntityPropertiesToIndexKey(FrontendTransaction transaction,
      EntityImpl entity) {
    return createValue(transaction, entity.<Object>getProperty(property));
  }

  @Nullable
  @Override
  public Object createValue(FrontendTransaction transaction, List<?> params) {
    if (!(params.getFirst() instanceof Map)) {
      return null;
    }

    final var mapParams = extractMapParams((Map<?, ?>) params.getFirst());
    final List<Object> result = new ArrayList<>(mapParams.size());
    for (final var mapParam : mapParams) {
      result.add(createSingleValue(transaction, mapParam));
    }

    return result;
  }

  @Nullable
  @Override
  public Object createValue(FrontendTransaction transaction, Object... params) {
    if (!(params[0] instanceof Map)) {
      return null;
    }

    final var mapParams = extractMapParams((Map<?, ?>) params[0]);

    final List<Object> result = new ArrayList<>(mapParams.size());
    for (final var mapParam : mapParams) {
      var val = createSingleValue(transaction, mapParam);
      result.add(val);
    }
    if (getProperties().size() == 1 && result.size() == 1) {
      return result.getFirst();
    }
    return result;
  }

  @Override
  public List<IndexBy> getIndexBy() {
    return Collections.singletonList(indexBy);
  }


  private Collection<?> extractMapParams(Map<?, ?> map) {
    if (indexBy == IndexBy.BY_KEY) {
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

  @Override
  public Object createSingleValue(FrontendTransaction transaction, final Object... param) {
    var session = transaction.getDatabaseSession();
    return keyType.convert(refreshRid(session, param[0]), null, null, session);
  }

  @Override
  public void processChangeEvent(
      FrontendTransaction transaction,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    final boolean result;
    if (indexBy == IndexBy.BY_KEY) {
      result = processKeyChangeEvent(transaction, changeEvent, keysToAdd, keysToRemove);
    } else {
      result = processValueChangeEvent(transaction, changeEvent, keysToAdd, keysToRemove);
    }

    if (!result) {
      throw new IllegalArgumentException("Invalid change type :" + changeEvent.getChangeType());
    }
  }

  private boolean processKeyChangeEvent(
      FrontendTransaction transaction,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    return switch (changeEvent.getChangeType()) {
      case ADD -> {
        processAdd(createSingleValue(transaction, changeEvent.getKey()), keysToAdd, keysToRemove);
        yield true;
      }
      case REMOVE -> {
        processRemoval(createSingleValue(transaction, changeEvent.getKey()), keysToAdd,
            keysToRemove);
        yield true;
      }
      case UPDATE -> true;
      default -> false;
    };
  }

  private boolean processValueChangeEvent(
      FrontendTransaction transaction,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    switch (changeEvent.getChangeType()) {
      case ADD:
        processAdd(createSingleValue(transaction, changeEvent.getValue()), keysToAdd, keysToRemove);
        return true;
      case REMOVE:
        processRemoval(
            createSingleValue(transaction, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        return true;
      case UPDATE:
        processRemoval(
            createSingleValue(transaction, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        processAdd(createSingleValue(transaction, changeEvent.getValue()), keysToAdd, keysToRemove);
        return true;
    }
    return false;
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
}
