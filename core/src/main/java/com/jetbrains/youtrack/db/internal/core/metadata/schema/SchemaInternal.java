package com.jetbrains.youtrack.db.internal.core.metadata.schema;


import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.db.DatabaseSessionInternal;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.collectionselection.CollectionSelectionFactory;
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
