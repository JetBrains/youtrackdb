package com.jetbrains.youtrack.db.internal.core.db;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.record.RecordAbstract;
import javax.annotation.Nullable;

public record LoadRecordResult(@Nullable RecordAbstract recordAbstract,
                               @Nullable RecordId previousRecordId, @Nullable RecordId nextRecordId) {
}
