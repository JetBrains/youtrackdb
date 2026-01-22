package com.jetbrains.youtrackdb.internal.core.gremlin.gremlintest;

import com.jetbrains.youtrackdb.internal.core.db.record.record.Identifiable;
import com.jetbrains.youtrackdb.internal.core.db.record.record.RID;
import org.jspecify.annotations.NonNull;

public record MockRID(String errorText) implements RID {

    @Override
    public @NonNull RID getIdentity() {
        return this;
    }

    @Override
    public int getCollectionId() {
        throw new IllegalStateException(errorText);
    }

    @Override
    public long getCollectionPosition() {
        throw new IllegalStateException(errorText);
    }

    @Override
    public boolean isPersistent() {
        throw new IllegalStateException(errorText);
    }

    @Override
    public boolean isNew() {
        throw new IllegalStateException(errorText);
    }

    @Override
    public int compareTo(Identifiable o) {
        throw new IllegalStateException(errorText);
    }
}
