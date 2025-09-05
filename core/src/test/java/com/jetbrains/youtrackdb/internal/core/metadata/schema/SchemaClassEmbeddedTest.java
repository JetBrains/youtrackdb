package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import org.junit.Test;

public class SchemaClassEmbeddedTest extends BaseMemoryInternalDatabase {

  @Test
  public void shouldAddSuperClass() {
    final Schema oSchema = session.getMetadata().getSchema();

    SchemaClass oClass = oSchema.createClass("Test1");
    SchemaClass newSuperClass = oSchema.createClass("Super");
    final int oldClusterId = oClass.getCollectionIds()[0];

    oClass.addSuperClass(newSuperClass);

    assertTrue(oClass.getSuperClasses().contains(newSuperClass));
  }
}