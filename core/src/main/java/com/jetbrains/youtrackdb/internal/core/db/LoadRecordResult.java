package com.jetbrains.youtrackdb.internal.core.db;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.record.RecordAbstract;
import javax.annotation.Nullable;

public record LoadRecordResult(@Nullable RecordAbstract recordAbstract,
                               @Nullable RecordId previousRecordId, @Nullable RecordId nextRecordId) {
}
