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

package com.jetbrains.youtrack.db.internal.core.storage.impl.local.paginated.wal;

import com.jetbrains.youtrack.db.internal.common.serialization.types.LongSerializer;
import com.jetbrains.youtrack.db.internal.common.serialization.types.StringSerializer;
import java.nio.ByteBuffer;

/**
 * @since 5/21/14
 */
public class FileCreatedWALRecord extends OperationUnitBodyRecord {

  private String fileName;
  private long fileId;

  public FileCreatedWALRecord() {
  }

  public FileCreatedWALRecord(long operationUnitId, String fileName, long fileId) {
    super(operationUnitId);
    this.fileName = fileName;
    this.fileId = fileId;
  }

  public String getFileName() {
    return fileName;
  }

  public long getFileId() {
    return fileId;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    StringSerializer.staticSerializeInByteBufferObject(fileName, buffer);
    buffer.putLong(fileId);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    fileName = StringSerializer.staticDeserializeFromByteBufferObject(buffer);
    fileId = buffer.getLong();
  }

  @Override
  public int serializedSize() {
    return super.serializedSize()
        + StringSerializer.staticGetObjectSize(fileName)
        + LongSerializer.LONG_SIZE;
  }

  @Override
  public int getId() {
    return WALRecordTypes.FILE_CREATED_WAL_RECORD;
  }
}
