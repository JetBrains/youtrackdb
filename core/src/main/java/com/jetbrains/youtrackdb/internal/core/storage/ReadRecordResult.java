package com.jetbrains.youtrackdb.internal.core.storage;

import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record ReadRecordResult(@Nonnull RawBuffer buffer,
                               @Nullable RecordIdInternal previousRecordId,
                               @Nullable RecordIdInternal nextRecordId) {

}
