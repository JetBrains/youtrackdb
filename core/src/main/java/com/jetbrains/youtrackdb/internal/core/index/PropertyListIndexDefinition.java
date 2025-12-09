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
import com.jetbrains.youtrackdb.internal.core.exception.BaseException;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Index implementation bound to one schema class property that presents
 * {@link PropertyTypeInternal#EMBEDDEDLIST}, {@link PropertyTypeInternal#LINKLIST},
 * {@link PropertyTypeInternal#LINKSET} or {@link PropertyTypeInternal#EMBEDDEDSET} properties.
 */
public class PropertyListIndexDefinition extends PropertyIndexDefinition
    implements IndexDefinitionMultiValue {

  @SuppressWarnings("unused")
  public PropertyListIndexDefinition() {
  }

  public PropertyListIndexDefinition(
      final String iClassName, final String iField, final PropertyTypeInternal iType) {
    super(iClassName, iField, iType);
  }

  @Override
  public Object getDocumentValueToIndex(FrontendTransaction transaction, EntityImpl entity) {
    return createValue(transaction, entity.<Object>getProperty(field));
  }

  @Override
  public Object createValue(FrontendTransaction transaction, List<?> params) {
    var param = params.getFirst();
    if (param == null) {
      return null;
    }

    if (!(param instanceof Collection<?> multiValueCollection)) {
      return Collections.singletonList(createSingleValue(transaction, param));
    }

    final List<Object> values = new ArrayList<>(multiValueCollection.size());
    for (final var item : multiValueCollection) {
      values.add(createSingleValue(transaction, item));
    }
    return values;
  }

  @Nullable
  @Override
  public Object createValue(FrontendTransaction transaction, final Object... params) {
    var param = params[0];
    if (param == null) {
      return null;
    }

    if (!(param instanceof Collection<?> multiValueCollection)) {
      return Collections.singletonList(createSingleValue(transaction, param));
    }

    final List<Object> values = new ArrayList<>(multiValueCollection.size());
    for (final var item : multiValueCollection) {
      values.add(createSingleValue(transaction, item));
    }
    return values;
  }

  @Override
  public Object createSingleValue(FrontendTransaction transaction, final Object... param) {
    try {
      var value = refreshRid(transaction.getDatabaseSession(), param[0]);
      return keyType.convert(value, null, null, transaction.getDatabaseSession());
    } catch (Exception e) {
      throw BaseException.wrapException(
          new IndexException(transaction.getDatabaseSession(),
              "Invalid key for index: " + param[0] + " cannot be converted to " + keyType),
          e, transaction.getDatabaseSession());
    }
  }

  @Override
  public void processChangeEvent(
      FrontendTransaction transaction,
      final MultiValueChangeEvent<?, ?> changeEvent,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    switch (changeEvent.getChangeType()) {
      case ADD: {
        processAdd(createSingleValue(transaction, changeEvent.getValue()), keysToAdd, keysToRemove);
        break;
      }
      case REMOVE: {
        processRemoval(
            createSingleValue(transaction, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        break;
      }
      case UPDATE: {
        processRemoval(
            createSingleValue(transaction, changeEvent.getOldValue()), keysToAdd, keysToRemove);
        processAdd(createSingleValue(transaction, changeEvent.getValue()), keysToAdd, keysToRemove);
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
