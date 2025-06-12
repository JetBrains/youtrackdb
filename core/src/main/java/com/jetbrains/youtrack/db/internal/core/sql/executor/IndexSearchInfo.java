package com.jetbrains.youtrack.db.internal.core.sql.executor;

import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.command.CommandContext;
import javax.annotation.Nonnull;

public record IndexSearchInfo(
    String fieldName,
    boolean allowsRangeQueries,
    boolean isMap,
    boolean indexedByKey,
    boolean indexedByValue,
    @Nonnull SchemaClass schemaClass,
    CommandContext ctx) {

}
