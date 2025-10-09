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

import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.MultiValueChangeEvent;
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Index definition for index which is bound to field with type {@link PropertyTypeInternal#LINKBAG}
 * .
 *
 * @since 1/30/14
 */
public class PropertyLinkBagIndexDefinition extends PropertyIndexDefinition
    implements IndexDefinitionMultiValue {

  public PropertyLinkBagIndexDefinition() {
  }

  public PropertyLinkBagIndexDefinition(String className, String field) {
    super(className, field, PropertyTypeInternal.LINK);
  }

  @Override
  public Object createSingleValue(FrontendTransaction transaction, Object... param) {
    return keyType.convert(refreshRid(transaction.getDatabaseSession(),
        param[0]), null, null, transaction.getDatabaseSession());
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
      default:
        throw new IllegalArgumentException("Invalid change type : " + changeEvent.getChangeType());
    }
  }

  @Override
  public Object convertEntityPropertiesToIndexKey(FrontendTransaction transaction,
      EntityImpl entity) {
    return createValue(transaction, entity.<Object>getPropertyInternal(property));
  }

  @Override
  public List<IndexBy> getIndexBy() {
    return List.of(IndexBy.BY_VALUE);
  }

  @Nullable
  @Override
  public Object createValue(FrontendTransaction transaction, final List<?> params) {
    if (!(params.getFirst() instanceof LinkBag linkBag)) {
      return null;
    }
    final List<Object> values = new ArrayList<>();
    for (final Identifiable item : linkBag) {
      values.add(createSingleValue(transaction, item.getIdentity()));
    }

    return values;
  }

  @Nullable
  @Override
  public Object createValue(FrontendTransaction transaction, final Object... params) {
    var param = params[0];
    if (!(param instanceof LinkBag linkBag)) {
      try {
        var session = transaction.getDatabaseSession();
        return keyType.convert(refreshRid(session, param), null, null, session);
      } catch (Exception e) {
        return null;
      }
    }

    final List<Object> values = new ArrayList<>();
    for (final Identifiable item : linkBag) {
      values.add(createSingleValue(transaction, item.getIdentity()));
    }

    return values;
  }
}
