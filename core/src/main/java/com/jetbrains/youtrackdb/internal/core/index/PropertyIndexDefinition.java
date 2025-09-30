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

import com.fasterxml.jackson.core.JsonGenerator;
import com.jetbrains.youtrackdb.api.common.query.collection.embedded.EmbeddedMap;
import com.jetbrains.youtrackdb.api.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.collate.DefaultCollate;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.tx.FrontendTransaction;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Index implementation bound to one schema class property.
 */
public class PropertyIndexDefinition extends AbstractIndexDefinition {

  protected String className;
  protected String field;
  protected PropertyTypeInternal keyType;

  public PropertyIndexDefinition(final String iClassName, final String iField,
      final PropertyTypeInternal iType) {
    super();
    className = iClassName;
    field = iField;
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
    return Collections.singletonList(field);
  }

  @Override
  public List<String> getFieldsToIndex() {
    if (collate == null || collate.getName().equals(DefaultCollate.NAME)) {
      return Collections.singletonList(field);
    }

    return Collections.singletonList(field + " collate " + collate.getName());
  }

  @Override
  @Nullable
  public Object convertEntityPropertiesToIndexKey(
      FrontendTransaction transaction, final EntityImpl entity) {
    if (PropertyTypeInternal.LINK.equals(keyType)) {
      final Identifiable identifiable = entity.getPropertyInternal(field);
      if (identifiable != null) {
        return createValue(transaction, identifiable.getIdentity());
      } else {
        return null;
      }
    }
    return createValue(transaction, entity.<Object>getPropertyInternal(field));
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
    if (!field.equals(that.field)) {
      return false;
    }
    return keyType == that.keyType;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + className.hashCode();
    result = 31 * result + field.hashCode();
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
        + field
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

  @Override
  public void fromMap(@Nonnull Map<String, ?> map) {
    serializeFromMap(map);
  }

  @Nonnull
  @Override
  public EmbeddedMap<Object> toMap(DatabaseSessionInternal session) {
    var result = session.newEmbeddedMap();
    serializeToMap(result, session);
    return result;
  }

  @Override
  public void toJson(@Nonnull JsonGenerator jsonGenerator) {
    try {
      jsonGenerator.writeStartObject();
      serializeToJson(jsonGenerator);
      jsonGenerator.writeEndObject();
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  protected void serializeToJson(JsonGenerator jsonGenerator) {
    try {
      jsonGenerator.writeStringField("className", className);
      jsonGenerator.writeStringField("field", field);
      jsonGenerator.writeStringField("keyType", keyType.toString());
      jsonGenerator.writeStringField("collate", collate.getName());
      jsonGenerator.writeBooleanField("nullValuesIgnored", isNullValuesIgnored());
    } catch (final Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void serializeToMap(@Nonnull Map<String, Object> map, DatabaseSessionInternal session) {
    super.serializeToMap(map, session);

    map.put("className", className);
    map.put("field", field);
    map.put("keyType", keyType.toString());
    map.put("collate", collate.getName());
    map.put("nullValuesIgnored", isNullValuesIgnored());
  }

  @Override
  protected void serializeFromMap(@Nonnull Map<String, ?> map) {
    super.serializeFromMap(map);

    className = (String) map.get("className");
    field = (String) map.get("field");

    final var keyTypeStr = (String) map.get("keyType");
    keyType = PropertyTypeInternal.valueOf(keyTypeStr);

    setCollate((String) map.get("collate"));
    setNullValuesIgnored(!Boolean.FALSE.equals(map.get("nullValuesIgnored")));
  }

  /**
   * {@inheritDoc}
   *
   * @param indexName
   * @param indexType
   */
  @Override
  public String toCreateIndexDDL(
      final String indexName, final String indexType, final String engine) {
    return createIndexDDLWithFieldType(indexName, indexType, engine).toString();
  }

  protected StringBuilder createIndexDDLWithFieldType(
      String indexName, String indexType, String engine) {
    final var ddl = createIndexDDLWithoutFieldType(indexName, indexType, engine);
    ddl.append(' ').append(keyType.name());
    return ddl;
  }

  protected StringBuilder createIndexDDLWithoutFieldType(
      final String indexName, final String indexType, final String engine) {
    final var ddl = new StringBuilder("create index `");

    ddl.append(indexName).append("` on `");
    ddl.append(className).append("` ( `").append(field).append("`");

    if (!collate.getName().equals(DefaultCollate.NAME)) {
      ddl.append(" collate ").append(collate.getName());
    }

    ddl.append(" ) ");
    ddl.append(indexType);

    if (engine != null) {
      ddl.append(" ENGINE  ").append(engine);
    }
    return ddl;
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
