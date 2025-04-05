package com.jetbrains.youtrack.db.internal.core.metadata.schema;

import static org.junit.Assert.assertTrue;

import com.jetbrains.youtrack.db.api.schema.Schema;
import com.jetbrains.youtrack.db.api.schema.SchemaClass;
import com.jetbrains.youtrack.db.internal.BaseMemoryInternalDatabase;
import org.junit.Test;

public class SchemaClassEmbeddedTest extends BaseMemoryInternalDatabase {

  @Test
  public void shouldAddSuperClass() {
    final Schema oSchema = db.getMetadata().getSchema();

    SchemaClass oClass = oSchema.createClass("Test1");
    SchemaClass newSuperClass = oSchema.createClass("Super");
    final int oldClusterId = oClass.getClusterIds()[0];

    oClass.addSuperClass(db, newSuperClass);

    assertTrue(oClass.getSuperClasses(db).contains(newSuperClass));
  }
}