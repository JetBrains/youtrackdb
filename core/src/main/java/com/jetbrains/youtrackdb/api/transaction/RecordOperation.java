package com.jetbrains.youtrackdb.api.transaction;

import com.jetbrains.youtrackdb.api.record.DBRecord;
import javax.annotation.Nonnull;

public record RecordOperation(@Nonnull DBRecord record, @Nonnull RecordOperationType type) {

}
