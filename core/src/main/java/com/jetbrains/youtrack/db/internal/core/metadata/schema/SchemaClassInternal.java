package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import com.jetbrains.youtrack.db.api.DatabaseSession;
import com.jetbrains.youtrack.db.api.schema.PropertyType;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.api.schema.SchemaProperty;
import com.jetbrains.youtrack.db.internal.core.index.Index;
import com.jetbrains.youtrack.db.internal.core.record.impl.EntityImpl;
import java.util.Collection;
import java.util.Set;

public interface SchemaClassInternal extends SchemaClass {

  SchemaClass truncateCluster(DatabaseSession session, String clusterName);

  ClusterSelectionStrategy getClusterSelection();

  SchemaClass setClusterSelection(DatabaseSession session,
      final ClusterSelectionStrategy clusterSelection);

  int getClusterForNewInstance(final EntityImpl entity);

  Set<Index> getInvolvedIndexesInternal(DatabaseSession session, String... fields);

  Set<Index> getInvolvedIndexesInternal(DatabaseSession session, final Collection<String> fields);

  SchemaProperty createProperty(
      DatabaseSession session, final String iPropertyName,
      final PropertyType iType,
      final PropertyType iLinkedType,
      final boolean unsafe);

  SchemaProperty createProperty(
      DatabaseSession session, final String iPropertyName,
      final PropertyType iType,
      final SchemaClass iLinkedClass,
      final boolean unsafe);

  Set<Index> getIndexesInternal(DatabaseSession session);

  void getIndexesInternal(DatabaseSession session, Collection<Index> indices);

  long count(DatabaseSession session);

  void truncate(DatabaseSession session);

  long count(DatabaseSession session, final boolean isPolymorphic);

  SchemaPropertyInternal getPropertyInternal(DatabaseSession session, String propertyName);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSession session, String... fields);

  Set<Index> getClassInvolvedIndexesInternal(DatabaseSession session,
      final Collection<String> fields);

  Set<Index> getClassIndexesInternal(DatabaseSession session);

  Index getClassIndex(DatabaseSession session, final String name);

  SchemaClass set(DatabaseSession session, final ATTRIBUTES attribute, final Object value);
}
