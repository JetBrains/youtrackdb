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
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.entities.SchemaIndexEntity.IndexBy;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Index implementation bound to one schema class property.
 */
public class PropertyIndexDefinition extends AbstractIndexDefinition {

  protected String className;
  protected String property;
  protected PropertyTypeInternal keyType;

  public PropertyIndexDefinition(final String iClassName, final String property,
      final PropertyTypeInternal iType) {
    super();
    className = iClassName;
    this.property = property;
    keyType = iType;
  }

  /**
   * Constructor used for index unmarshalling.
   */
  public PropertyIndexDefinition() {
  }

  @Override
  public String getClassName() {
    return className;
  }

  @Override
  public List<String> getProperties() {
    return Collections.singletonList(property);
  }

  @Override
  public List<IndexBy> getIndexBy() {
    return List.of(IndexBy.BY_VALUE);
  }

  @Override
  @Nullable
  public Object convertEntityPropertiesToIndexKey(
      FrontendTransaction transaction, final EntityImpl entity) {
    if (PropertyTypeInternal.LINK.equals(keyType)) {
      final Identifiable identifiable = entity.getPropertyInternal(property);
      if (identifiable != null) {
        return createValue(transaction, identifiable.getIdentity());
      } else {
        return null;
      }
    }
    return createValue(transaction, entity.<Object>getPropertyInternal(property));
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }

    if (!super.equals(o)) {
      return false;
    }

    final var that = (PropertyIndexDefinition) o;

    if (!className.equals(that.className)) {
      return false;
    }
    if (!property.equals(that.property)) {
      return false;
    }
    return keyType == that.keyType;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + className.hashCode();
    result = 31 * result + property.hashCode();
    result = 31 * result + keyType.hashCode();
    return result;
  }

  @Override
  public String toString() {
    return "PropertyIndexDefinition{"
        + "className='"
        + className
        + '\''
        + ", field='"
        + property
        + '\''
        + ", keyType="
        + keyType
        + ", collate="
        + collate
        + ", null values ignored = "
        + isNullValuesIgnored()
        + '}';
  }

  @Override
  public Object createValue(FrontendTransaction transaction, final List<?> params) {
    return keyType.convert(params.getFirst(), null, null, transaction.getDatabaseSession());
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public Object createValue(FrontendTransaction transaction, final Object... params) {
    return keyType.convert(refreshRid(transaction.getDatabaseSession(), params[0]), null, null,
        transaction.getDatabaseSession());
  }

  @Override
  public int getParamCount() {
    return 1;
  }

  @Override
  public PropertyTypeInternal[] getTypes() {
    return new PropertyTypeInternal[]{keyType};
  }


  protected static void processAdd(
      final Object value,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    processAddRemoval(value, keysToRemove, keysToAdd);
  }

  protected static void processRemoval(
      final Object value,
      final Object2IntMap<Object> keysToAdd,
      final Object2IntMap<Object> keysToRemove) {
    processAddRemoval(value, keysToAdd, keysToRemove);
  }

  private static void processAddRemoval(Object value, Object2IntMap<Object> keysToAdd,
      Object2IntMap<Object> keysToRemove) {
    if (value == null) {
      return;
    }

    final var addCount = keysToAdd.getInt(value);
    if (addCount > 0) {
      var newAddCount = addCount - 1;
      if (newAddCount > 0) {
        keysToAdd.put(value, newAddCount);
      } else {
        keysToAdd.removeInt(value);
      }
    } else {
      final var removeCount = keysToRemove.getInt(value);
      if (removeCount > 0) {
        keysToRemove.put(value, removeCount + 1);
      } else {
        keysToRemove.put(value, 1);
      }
    }
  }

  @Override
  public boolean isAutomatic() {
    return true;
  }
}
