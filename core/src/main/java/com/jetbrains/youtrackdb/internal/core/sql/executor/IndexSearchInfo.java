package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchemaClass;
import javax.annotation.Nonnull;

public record IndexSearchInfo(
    String fieldName,
    boolean allowsRangeQueries,
    boolean isMap,
    boolean indexedByKey,
    boolean indexedByValue,
    @Nonnull ImmutableSchemaClass schemaClass,
    CommandContext ctx) {

}
