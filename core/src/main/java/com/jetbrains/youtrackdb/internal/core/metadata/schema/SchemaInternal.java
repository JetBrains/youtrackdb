package com.jetbrains.youtrackdb.internal.core.metadata.schema;


import com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded;
import com.jetbrains.youtrackdb.internal.core.id.RecordIdInternal;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.clusterselection.CollectionSelectionFactory;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.Schema;
import com.jetbrains.youtrackdb.internal.core.metadata.schema.schema.SchemaClass;
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
      DatabaseSessionEmbedded session);

  CollectionSelectionFactory getCollectionSelectionFactory();

  SchemaClassInternal getClassInternal(String iClassName);

  RecordIdInternal getIdentity();
}
