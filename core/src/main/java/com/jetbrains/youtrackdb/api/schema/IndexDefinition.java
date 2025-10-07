package com.jetbrains.youtrackdb.api.schema;

import com.jetbrains.youtrackdb.internal.core.metadata.schema.ImmutableSchema.IndexType;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.PropertyTypeInternal;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record IndexDefinition(@Nonnull String name, @Nonnull String className,
                              @Nonnull PropertyTypeInternal[] keyTypes,
                              @Nonnull Collection<String> properties, @Nonnull IndexType type,
                              boolean nullValuesIgnored, @Nullable String collate,
                              @Nonnull Map<String, ?> metadata) {

}
