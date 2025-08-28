package com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.cas;

import com.jetbrains.youtrackdb.internal.core.storage.impl.local.paginated.wal.LogSequenceNumber;

public record WrittenUpTo(LogSequenceNumber lsn, long position) {

}
