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

import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.db.record.record.Blob;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import com.jetbrains.youtrackdb.internal.core.serialization.serializer.record.RecordSerializer;
import java.util.Base64;
import javax.annotation.Nonnull;

public class RecordSerializerBinary implements RecordSerializer {
  public static final String NAME = "RecordSerializerBinary";
  public static final RecordSerializerBinary INSTANCE = new RecordSerializerBinary();
  private static final byte CURRENT_RECORD_VERSION = 0;

  private EntitySerializer[] serializerByVersion;
  private final byte currentSerializerVersion;

  private void init() {
    serializerByVersion = new EntitySerializer[1];
    serializerByVersion[0] = new RecordSerializerBinaryV1();
  }

  public RecordSerializerBinary(byte serializerVersion) {
    currentSerializerVersion = serializerVersion;
    init();
  }

  public RecordSerializerBinary() {
    currentSerializerVersion = CURRENT_RECORD_VERSION;
    init();
  }

  public int getNumberOfSupportedVersions() {
    return serializerByVersion.length;
  }

  @Override
  public int getCurrentVersion() {
    return currentSerializerVersion;
  }

  @Override
  public int getMinSupportedVersion() {
    return currentSerializerVersion;
  }

  public EntitySerializer getSerializer(final int iVersion) {
    return serializerByVersion[iVersion];
  }

  public EntitySerializer getCurrentSerializer() {
    return serializerByVersion[currentSerializerVersion];
  }

  @Override
  public String toString() {
    return NAME;
  }

  @Override
  public void fromStream(
      @Nonnull DatabaseSessionEmbedded session, final @Nonnull byte[] iSource,
      @Nonnull RecordAbstract iRecord,
      final String[] iFields) {
    if (iSource.length == 0) {
      return;
    }

    if (iRecord instanceof Blob) {
      iRecord.fromStream(iSource);
      return;
    }

    final var container = new BytesContainer(iSource).skip(1);
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
  public byte[] toStream(@Nonnull DatabaseSessionInternal session, @Nonnull RecordAbstract record) {
    if (record instanceof Blob) {
      return record.toStream();
    } else {
      var entityToSerialize = (EntityImpl) record;

      final var container = new BytesContainer();

      // WRITE SERIALIZER VERSION
      var pos = container.alloc(1);
      container.bytes[pos] = currentSerializerVersion;
      // SERIALIZE RECORD
      serializerByVersion[currentSerializerVersion].serialize(session, entityToSerialize,
          container);

      return container.fitBytes();
    }
  }

  @Override
  public String[] getFieldNames(@Nonnull DatabaseSessionInternal session, EntityImpl reference,
      final @Nonnull byte[] iSource) {
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

  @Override
  public boolean getSupportBinaryEvaluate() {
    return true;
  }

  @Override
  public String getName() {
    return NAME;
  }
}
