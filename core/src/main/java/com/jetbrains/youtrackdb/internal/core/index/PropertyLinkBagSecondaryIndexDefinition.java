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
import com.jetbrains.youtrackdb.internal.core.db.record.ridbag.LinkBag;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Index definition for LINKBAG fields that indexes the secondary RID (opposite vertex RID) instead
 * of the primary RID (edge record RID). Used with the {@code BY VALUE} specifier in
 * {@code CREATE INDEX} DDL.
 *
 * <p>This enables "index by vertex" lookups: given a vertex RID, find all LinkBag entries
 * (edges) that reference that vertex as the opposite endpoint.
 */
public class PropertyLinkBagSecondaryIndexDefinition extends PropertyIndexDefinition
    implements IndexDefinitionMultiValue {

  public PropertyLinkBagSecondaryIndexDefinition() {
  }

  public PropertyLinkBagSecondaryIndexDefinition(String className, String field) {
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
      case ADD -> {
        // Index by secondaryRid (the opposite vertex RID) from the change event value.
        var secondaryKey = createSingleValue(transaction, changeEvent.getValue());
        processAdd(secondaryKey, keysToAdd, keysToRemove);
      }
      case REMOVE -> {
        // Remove index entry by secondaryRid. On REMOVE events, getValue() is null;
        // the old secondaryRid is in getOldValue().
        var secondaryKey = createSingleValue(transaction, changeEvent.getOldValue());
        processRemoval(secondaryKey, keysToAdd, keysToRemove);
      }
      default ->
          throw new IllegalArgumentException(
              "Invalid change type : " + changeEvent.getChangeType());
    }
  }

  @Override
  public Object getDocumentValueToIndex(FrontendTransaction transaction, EntityImpl entity) {
    return createValue(transaction, entity.<Object>getPropertyInternal(field));
  }

  @Nullable @Override
  public Object createValue(FrontendTransaction transaction, final List<?> params) {
    if (!(params.get(0) instanceof LinkBag linkBag)) {
      return null;
    }
    final List<Object> values = new ArrayList<>();
    for (final var item : linkBag) {
      values.add(createSingleValue(transaction, item.secondaryRid()));
    }

    return values;
  }

  @Nullable @Override
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
    for (final var item : linkBag) {
      values.add(createSingleValue(transaction, item.secondaryRid()));
    }

    return values;
  }

  @Override
  public List<String> getFieldsToIndex() {
    return Collections.singletonList(field + " by value");
  }

  @Override
  public String toCreateIndexDDL(String indexName, String indexType, String engine) {
    final var ddl = new StringBuilder("create index `");
    ddl.append(indexName).append("` on `");
    ddl.append(className).append("` ( `").append(field).append("` by value ) ");
    ddl.append(indexType);

    if (engine != null) {
      ddl.append(" ENGINE  ").append(engine);
    }
    return ddl.toString();
  }
}
