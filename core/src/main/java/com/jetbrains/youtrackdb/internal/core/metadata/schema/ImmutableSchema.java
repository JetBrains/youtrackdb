package com.jetbrains.youtrackdb.internal.core.metadata.schema;

import com.jetbrains.youtrackdb.api.schema.IndexDefinition;
import java.util.Iterator;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public interface ImmutableSchema {

  long countClasses();

  @Nullable
  ImmutableSchemaClass getClass(Class<?> iClass);

  /**
   * Returns the SchemaClass instance by class name.
   *
   * <p>If the class is not configured and the database has an entity manager with the requested
   * class as registered, then creates a schema class for it at the fly.
   *
   * <p>If the database nor the entity manager have not registered class with specified name,
   * returns null.
   *
   * @param iClassName Name of the class to retrieve
   * @return class instance or null if class with given name is not configured.
   */
  ImmutableSchemaClass getClass(String iClassName);

  boolean existsClass(String iClassName);

  Iterator<? extends ImmutableSchemaClass> getClasses();

  Iterator<String> getIndexes();

  boolean indexExists(String indexName);

  @Nonnull
  IndexDefinition getIndexDefinition(String indexName);

  @Nullable
  ImmutableSchemaClass getClassByCollectionId(int collectionId);

  GlobalProperty getGlobalPropertyById(int id);

  Iterator<GlobalProperty> getGlobalProperties();

  SchemaSnapshot makeSnapshot();
}
