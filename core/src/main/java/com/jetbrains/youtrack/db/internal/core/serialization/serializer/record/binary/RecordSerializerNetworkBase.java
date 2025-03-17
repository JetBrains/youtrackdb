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

import com.jetbrains.youtrack.db.api.record.Blob;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.internal.common.log.LogManager;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.ImmutableSchema;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Base64;
import javax.annotation.Nonnull;

public class RecordSerializerNetworkBase implements RecordSerializerNetwork {

  public static final String NAME = "onet_ser_v0";
  public static final RecordSerializerNetworkBase INSTANCE = new RecordSerializerNetworkBase();
  private static final byte CURRENT_RECORD_VERSION = 0;

  private final EntitySerializer[] serializerByVersion;

  public RecordSerializerNetworkBase() {
    serializerByVersion = new EntitySerializer[1];
    serializerByVersion[0] = new RecordSerializerNetworkV0();
  }

  @Override
  public int getCurrentVersion() {
    return CURRENT_RECORD_VERSION;
  }

  @Override
  public int getMinSupportedVersion() {
    return CURRENT_RECORD_VERSION;
  }

  @Override
  public String toString() {
    return NAME;
  }

  @Override
  public void fromStream(
      @Nonnull DatabaseSessionInternal session, final @Nonnull byte[] iSource,
      @Nonnull RecordAbstract iRecord,
      final String[] iFields) {
    if (iSource.length == 0) {
      return;
    }

    if (iRecord instanceof Blob) {
      iRecord.fromStream(iSource);
    }

    var container = new BytesContainer(iSource);
    container.skip(1);

    try {
      if (iFields != null && iFields.length > 0) {
        serializerByVersion[iSource[0]].deserializePartial(session, (EntityImpl) iRecord, container,
            iFields);
      } else {
        serializerByVersion[iSource[0]].deserialize(session, (EntityImpl) iRecord, container);
      }
    } catch (RuntimeException e) {
      LogManager.instance()
          .warn(
              this,
              "Error deserializing record with id %s send this data for debugging: %s ",
              iRecord.getIdentity().toString(),
              Base64.getEncoder().encodeToString(iSource));
      throw e;
    }
  }

  @Override
  public byte[] toStream(@Nonnull DatabaseSessionInternal session,
      @Nonnull RecordAbstract iSource) {
    if (iSource instanceof Blob) {
      return iSource.toStream();
    } else {
      final var container = new BytesContainer();

      // WRITE SERIALIZER VERSION
      var pos = container.alloc(1);
      container.bytes[pos] = CURRENT_RECORD_VERSION;
      // SERIALIZE RECORD
      serializerByVersion[CURRENT_RECORD_VERSION].serialize(session, (EntityImpl) iSource,
          container);

      return container.fitBytes();
    }
  }

  public byte[] serializeValue(DatabaseSessionInternal db, Object value, PropertyType type) {
    ImmutableSchema schema = null;
    if (db != null) {
      schema = db.getMetadata().getImmutableSchemaSnapshot();
    }
    var bytes = new BytesContainer();
    serializerByVersion[0].serializeValue(db, bytes, value, type, null, schema, null);
    return bytes.fitBytes();
  }

  public Object deserializeValue(DatabaseSessionInternal db, byte[] val, PropertyType type) {
    var bytes = new BytesContainer(val);
    return serializerByVersion[0].deserializeValue(db, bytes, type, null);
  }

  @Override
  public boolean getSupportBinaryEvaluate() {
    return false;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String[] getFieldNames(@Nonnull DatabaseSessionInternal session, EntityImpl reference,
      @Nonnull byte[] iSource) {
    if (iSource.length == 0) {
      return new String[0];
    }

    final var container = new BytesContainer(iSource).skip(1);
    try {
      return serializerByVersion[iSource[0]].getFieldNames(session, reference, container, false);
    } catch (RuntimeException e) {
      LogManager.instance()
          .warn(
              this,
              "Error deserializing record to get field-names, send this data for debugging: %s ",
              Base64.getEncoder().encodeToString(iSource));
      throw e;
    }
  }
}
