package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.common.WriteableWALRecord;
import java.util.List;

/** A recovery read stopped decoding after the records in {@link #getRecords()}. */
public final class WALRecordDecodeException extends RuntimeException {

  private final List<WriteableWALRecord> records;

  public WALRecordDecodeException(List<WriteableWALRecord> records, RuntimeException cause) {
    super("Cannot decode WAL record", cause);
    this.records = List.copyOf(records);
  }

  public List<WriteableWALRecord> getRecords() {
    return records;
  }
}
