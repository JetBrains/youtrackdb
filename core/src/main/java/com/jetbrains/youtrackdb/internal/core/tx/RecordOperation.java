package com.jetbrains.youtrackdb.internal.core.tx;

import com.jetbrains.youtrackdb.internal.core.db.record.record.DBRecord;
import javax.annotation.Nonnull;

public record RecordOperation(@Nonnull DBRecord record, @Nonnull RecordOperationType type) {

}
