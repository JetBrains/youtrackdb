package com.jetbrains.youtrack.db.api.session;

import com.jetbrains.youtrack.db.api.record.DBRecord;

public record RecordOperation(DBRecord record, RecordOperationType type) {

}
