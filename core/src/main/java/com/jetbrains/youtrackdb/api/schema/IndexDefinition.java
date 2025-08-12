package com.jetbrains.youtrackdb.api.schema;

import com.jetbrains.youtrackdb.api.schema.SchemaClass.INDEX_TYPE;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public record IndexDefinition(@Nonnull String name, @Nonnull String className,
                              @Nonnull Collection<String> properties, @Nonnull INDEX_TYPE type,
                              boolean nullValuesIgnored, @Nullable String collate,
                              @Nonnull Map<String, ?> metadata) {

}
