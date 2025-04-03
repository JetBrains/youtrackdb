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

import com.jetbrains.youtrack.db.api.record.Identifiable;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrack.db.internal.core.db.record.ridbag.RidBag;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Index definition for index which is bound to field with type {@link PropertyTypeInternal#LINKBAG} .
 *
 * @since 1/30/14
 */
public class PropertyRidBagIndexDefinition extends PropertyIndexDefinition
    implements IndexDefinitionMultiValue {

  public PropertyRidBagIndexDefinition() {
  }

  public PropertyRidBagIndexDefinition(String className, String field) {
    super(className, field, PropertyTypeInternal.LINK);
  }

  @Override
  public Object createSingleValue(DatabaseSessionInternal session, Object... param) {
    return keyType.convert(refreshRid(session, param[0]), null, null, session);
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
      default:
        throw new IllegalArgumentException("Invalid change type : " + changeEvent.getChangeType());
    }
  }

  @Override
  public Object getDocumentValueToIndex(DatabaseSessionInternal session, EntityImpl entity) {
    return createValue(session, entity.<Object>getPropertyInternal(field));
  }

  @Nullable
  @Override
  public Object createValue(DatabaseSessionInternal session, final List<?> params) {
    if (!(params.get(0) instanceof RidBag ridBag)) {
      return null;
    }
    final List<Object> values = new ArrayList<>();
    for (final Identifiable item : ridBag) {
      values.add(createSingleValue(session, item.getIdentity()));
    }

    return values;
  }

  @Nullable
  @Override
  public Object createValue(DatabaseSessionInternal session, final Object... params) {
    if (!(params[0] instanceof RidBag ridBag)) {
      return null;
    }
    final List<Object> values = new ArrayList<>();
    for (final Identifiable item : ridBag) {
      values.add(createSingleValue(session, item.getIdentity()));
    }

    return values;
  }

  @Override
  public String toCreateIndexDDL(String indexName, String indexType, String engine) {
    return createIndexDDLWithoutFieldType(indexName, indexType, engine).toString();
  }
}
