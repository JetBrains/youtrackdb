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
import java.nio.ByteBuffer;

/**
 * @since 29.04.13
 */
public abstract class AbstractPageWALRecord extends OperationUnitBodyRecord {

  private long pageIndex;
  private long fileId;

  protected AbstractPageWALRecord() {
  }

  protected AbstractPageWALRecord(long pageIndex, long fileId, long operationUnitId) {
    super(operationUnitId);
    this.pageIndex = pageIndex;
    this.fileId = fileId;
  }

  @Override
  protected void serializeToByteBuffer(ByteBuffer buffer) {
    buffer.putLong(pageIndex);
    buffer.putLong(fileId);
  }

  @Override
  protected void deserializeFromByteBuffer(ByteBuffer buffer) {
    pageIndex = buffer.getLong();
    fileId = buffer.getLong();
  }

  @Override
  public int serializedSize() {
    return super.serializedSize() + 2 * LongSerializer.LONG_SIZE;
  }

  public long getPageIndex() {
    return pageIndex;
  }

  public long getFileId() {
    return fileId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    var that = (AbstractPageWALRecord) o;

    if (pageIndex != that.pageIndex) {
      return false;
    }
    return fileId == that.fileId;
  }

  @Override
  public int hashCode() {
    var result = super.hashCode();
    result = 31 * result + (int) (pageIndex ^ (pageIndex >>> 32));
    result = 31 * result + (int) (fileId ^ (fileId >>> 32));
    return result;
  }

  @Override
  public String toString() {
    return toString("pageIndex=" + pageIndex + ", fileId=" + fileId);
  }
}
