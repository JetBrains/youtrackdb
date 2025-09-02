package com.jetbrains.youtrackdb.internal.core.metadata.schema;


import com.jetbrains.youtrackdb.api.schema.Schema;
import com.jetbrains.youtrackdb.api.schema.SchemaClass;
import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.CollectionSelectionFactory;
import java.util.Set;
import javax.annotation.Nonnull;

public interface SchemaInternal extends Schema {

  @Nonnull
  SchemaClass createClass(@Nonnull String className, int collections,
      @Nonnull SchemaClass... superClasses);

  SchemaClass createClass(String iClassName, SchemaClass iSuperClass, int[] iCollectionIds);

  SchemaClass createClass(String className, int[] collectionIds, SchemaClass... superClasses);

  ImmutableSchema makeSnapshot();

  Set<SchemaClass> getClassesRelyOnCollection(final String iCollectionName,
      DatabaseSessionInternal session);

  CollectionSelectionFactory getCollectionSelectionFactory();

  SchemaClassInternal getClassInternal(String iClassName);

  RecordId getIdentity();
}
