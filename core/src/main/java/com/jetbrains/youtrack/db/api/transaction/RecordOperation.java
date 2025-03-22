package com.jetbrains.youtrack.db.api.transaction;

import com.jetbrains.youtrack.db.api.record.DBRecord;
import javax.annotation.Nonnull;

public record RecordOperation(@Nonnull DBRecord record, @Nonnull RecordOperationType type) {

}
