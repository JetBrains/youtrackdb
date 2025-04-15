package com.jetbrains.youtrack.db.internal.core.storage;

import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ReadRecordResult(@Nonnull RawBuffer buffer,
                               @Nullable RecordId previousRecordId,
                               @Nullable RecordId nextRecordId) {

}
