package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.schema.IndexDefinition;
import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ImmutableSchema {

  /// Returns the SchemaClass instance by class name.
  ///
  /// If the class is not configured and the database has an entity manager with the requested class
  /// as registered, then creates a schema class for it at the fly.
  ///
  /// If the database nor the entity manager have not registered class with specified name, returns
  /// null.
  ///
  /// @param className Name of the class to retrieve
  /// @return class instance or null if class with given name is not configured.
  @Nullable
  ImmutableSchemaClass getClass(@Nonnull String className);

  boolean existsClass(@Nonnull String className);

  @Nonnull
  Collection<? extends ImmutableSchemaClass> getClasses();

  @Nonnull
  Collection<String> getIndexes();

  @Nonnull
  IndexDefinition getIndexDefinition(@Nonnull String indexName);

  @Nullable
  ImmutableSchemaClass getClassByCollectionId(int collectionId);

  @Nullable
  GlobalProperty getGlobalPropertyById(int id);
}
