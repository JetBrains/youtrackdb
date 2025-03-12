package com.jetbrains.youtrack.db.internal.core.metadata.schema;


import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.core.id.RecordId;
import com.jetbrains.youtrack.db.internal.core.metadata.schema.clusterselection.ClusterSelectionFactory;
import java.util.Set;

public interface SchemaInternal extends Schema {

  ImmutableSchema makeSnapshot();

  Set<SchemaClass> getClassesRelyOnCluster(final String iClusterName);

  ClusterSelectionFactory getClusterSelectionFactory();

  SchemaClassInternal getClassInternal(String iClassName);

  RecordId getIdentity();
}
