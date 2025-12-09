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

package com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.binary;

import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.RecordElement;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.metadata.security.PropertyEncryption;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;

public interface EntitySerializer {

  void serialize(DatabaseSessionInternal session, EntityImpl entity, BytesContainer bytes);

  int serializeValue(
      DatabaseSessionInternal db, BytesContainer bytes,
      Object value,
      PropertyTypeInternal type,
      PropertyTypeInternal linkedType,
      ImmutableSchema schema,
      PropertyEncryption encryption);

  void deserialize(DatabaseSessionEmbedded db, EntityImpl entity, BytesContainer bytes);

  void deserializePartial(DatabaseSessionEmbedded db, EntityImpl entity, BytesContainer bytes,
      String[] iFields);

  Object deserializeValue(DatabaseSessionEmbedded db, BytesContainer bytes,
      PropertyTypeInternal type,
      RecordElement owner);

  BinaryField deserializeField(
      DatabaseSessionInternal db, BytesContainer bytes,
      SchemaClass iClass,
      String iFieldName,
      boolean embedded,
      ImmutableSchema schema,
      PropertyEncryption encryption);

  BinaryComparator getComparator();

  /**
   * Returns the array of field names with no values.
   *
   * @param session
   * @param reference TODO
   * @param embedded
   */
  String[] getFieldNames(DatabaseSessionInternal session, EntityImpl reference,
      BytesContainer iBytes, boolean embedded);
}
