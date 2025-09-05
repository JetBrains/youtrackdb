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

package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrackdb.internal.common.serialization.types.ByteSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.IntegerSerializer;
import com.jetbrains.youtrackdb.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.RecordOperationMetadata;
import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.atomicoperations.AtomicOperationMetadata;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @since 24.05.13
 */
public class AtomicUnitEndRecord extends OperationUnitBodyRecord {

  private boolean rollback;

  private Map<String, AtomicOperationMetadata<?>> atomicOperationMetadataMap =
      new LinkedHashMap<>();

  public AtomicUnitEndRecord() {
  }

  public AtomicUnitEndRecord(
      final long operationUnitId,
      final boolean rollback,
      final Map<String, AtomicOperationMetadata<?>> atomicOperationMetadataMap) {
    super(operationUnitId);

    this.rollback = rollback;

    if (atomicOperationMetadataMap != null && atomicOperationMetadataMap.size() > 0) {
      this.atomicOperationMetadataMap = atomicOperationMetadataMap;
    }
  }

  public boolean isRollback() {
    return rollback;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    buffer.put(rollback ? (byte) 1 : 0);

    if (atomicOperationMetadataMap.size() > 0) {
      for (final var entry :
          atomicOperationMetadataMap.entrySet()) {
        if (entry.getKey().equals(RecordOperationMetadata.RID_METADATA_KEY)) {
          buffer.put((byte) 1);

          final var recordOperationMetadata =
              (RecordOperationMetadata) entry.getValue();
          final var rids = recordOperationMetadata.getValue();
          buffer.putInt(rids.size());

          for (final var rid : rids) {
            buffer.putLong(rid.getCollectionPosition());
            buffer.putInt(rid.getCollectionId());
          }
        } else {
          throw new IllegalStateException(
              "Invalid metadata key " + RecordOperationMetadata.RID_METADATA_KEY);
        }
      }
    } else {
      buffer.put((byte) 0);
    }
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    rollback = buffer.get() > 0;
    atomicOperationMetadataMap = new LinkedHashMap<>();

    final int metadataId = buffer.get();

    if (metadataId == 1) {
      final var collectionsSize = buffer.getInt();

      final var recordOperationMetadata = new RecordOperationMetadata();
      for (var i = 0; i < collectionsSize; i++) {
        final var collectionPosition = buffer.getLong();
        final var collectionId = buffer.getInt();

        recordOperationMetadata.addRid(new RecordId(collectionId, collectionPosition));
      }

      atomicOperationMetadataMap.put(recordOperationMetadata.getKey(), recordOperationMetadata);
    } else if (metadataId > 0) {
      throw new IllegalStateException("Invalid metadata entry id " + metadataId);
    }
  }

  public Map<String, AtomicOperationMetadata<?>> getAtomicOperationMetadata() {
    return Collections.unmodifiableMap(atomicOperationMetadataMap);
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + ByteSerializer.BYTE_SIZE + metadataSize();
  }

  private int metadataSize() {
    var size = ByteSerializer.BYTE_SIZE;

    for (var entry :
        atomicOperationMetadataMap.entrySet()) {
      if (entry.getKey().equals(RecordOperationMetadata.RID_METADATA_KEY)) {
        final var recordOperationMetadata =
            (RecordOperationMetadata) entry.getValue();

        size += IntegerSerializer.INT_SIZE;
        final var rids = recordOperationMetadata.getValue();
        size += rids.size() * (LongSerializer.LONG_SIZE + IntegerSerializer.INT_SIZE);
      } else {
        throw new IllegalStateException(
            "Invalid metadata key " + RecordOperationMetadata.RID_METADATA_KEY);
      }
    }

    return size;
  }

  @Override
  public String toString() {
    return toString("rollback=" + rollback);
  }

  @Override
  public int getId() {
    return WALRecordTypes.ATOMIC_UNIT_END_RECORD;
  }
}
