package com.jetbrains.youtrack.db.api.transaction;

import com.jetbrains.youtrack.db.api.record.DBRecord;

public record RecordOperation(DBRecord record, RecordOperationType type) {

}
