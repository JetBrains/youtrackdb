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

package com.jetbrains.youtrack.db.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.db.record.RecordElement;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;

public interface EntitySerializer {

  void serialize(DatabaseSessionInternal session, EntityImpl entity, BytesContainer bytes);

  int serializeValue(
      DatabaseSessionInternal session, BytesContainer bytes,
      Object value,
      PropertyType type,
      PropertyType linkedType,
      ImmutableSchema schema,
      PropertyEncryption encryption);

  void deserialize(DatabaseSessionInternal db, EntityImpl entity, BytesContainer bytes);

  void deserializePartial(DatabaseSessionInternal db, EntityImpl entity, BytesContainer bytes,
      String[] iFields);

  Object deserializeValue(DatabaseSessionInternal session, BytesContainer bytes, PropertyType type,
      RecordElement owner);

  BinaryField deserializeField(
      DatabaseSession session,
      BytesContainer bytes,
      SchemaClass iClass,
      String iFieldName,
      boolean embedded,
      ImmutableSchema schema,
      PropertyEncryption encryption);

  BinaryComparator getComparator();

  /**
   * Returns the array of field names with no values.
   *
   * @param reference TODO
   * @param embedded
   */
  String[] getFieldNames(EntityImpl reference, BytesContainer iBytes, boolean embedded);

  boolean isSerializingClassNameByDefault();

  <RET> RET deserializeFieldTyped(
      DatabaseSessionInternal session, BytesContainer record,
      String iFieldName,
      boolean isEmbedded,
      ImmutableSchema schema,
      PropertyEncryption encryption);

  void deserializeDebug(
      DatabaseSessionInternal db, BytesContainer bytes,
      RecordSerializationDebug debugInfo,
      ImmutableSchema schema);
}
