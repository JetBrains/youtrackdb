package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ReadRecordResult(@Nonnull RawBuffer buffer,
                               @Nullable RecordId previousRecordId,
                               @Nullable RecordId nextRecordId) {

}
