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
import java.nio.ByteBuffer;

/**
 * @since 24.05.13
 */
public class AtomicUnitStartRecord extends OperationUnitRecord {

  private boolean isRollbackSupported;

  public AtomicUnitStartRecord() {
  }

  public AtomicUnitStartRecord(final boolean isRollbackSupported, final long unitId) {
    super(unitId);
    this.isRollbackSupported = isRollbackSupported;
  }

  public boolean isRollbackSupported() {
    return isRollbackSupported;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    buffer.put(isRollbackSupported ? (byte) 1 : 0);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    isRollbackSupported = buffer.get() > 0;
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + ByteSerializer.BYTE_SIZE;
  }

  @Override
  public int getId() {
    return WALRecordTypes.ATOMIC_UNIT_START_RECORD;
  }

  @Override
  public String toString() {
    return toString("isRollbackSupported=" + isRollbackSupported);
  }
}
